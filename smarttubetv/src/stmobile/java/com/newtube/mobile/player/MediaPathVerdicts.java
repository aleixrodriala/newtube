package com.newtube.mobile.player;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NEWTUBE(media-path): what this device has proven about the media path to googlevideo on a network,
 * kept across process restarts.
 *
 * <p>Measured on Movistar LTE (Pixel 9, 2026-09-25): Cronet stalls in TLS to some googlevideo
 * edges, and OkHttp's IPv6 route to them stalls too, while IPv4 answers in ~150 ms. One open
 * already learnt both facts (StartupFailoverDataSource, MediaAddressPreference), but only in memory
 * and only for 120 s (Cronet) / 20-60 min (IPv6): every cold share link paid ~8.8 s (6/6: 3.5 s of
 * Cronet budget, then ~4.3 s of OkHttp IPv6 handshake), and every in-app open right after the
 * 120 s Cronet bypass lapsed paid the 3.5 s again (4 of 16 related hops).</p>
 *
 * <pre>
 *   verdict        set when (start and end on the same network)          steers
 *   cronet-stall   Cronet stuck in connect/TLS past the startup budget   media starts on OkHttp;
 *                  and OkHttp got an HTTP answer for the same request    the Cronet warm is skipped
 *   v6-stall       an IPv6 connect/TLS attempt timed out, then an IPv4   OkHttp resolves googlevideo
 *                  route to the same host connected                      IPv4-only
 * </pre>
 *
 * <p>Scope - what a verdict belongs to ({@link Env#scope}):</p>
 * <ul>
 *   <li><b>carrier</b> (cellular, not roaming): subscription id + serving PLMN + SIM PLMN, e.g.
 *       {@code carrier:1:21407:21407}. Measured: the Pixel's cellular network id changed on every
 *       Wi-Fi/LTE switch ({@code cell:112} then {@code cell:114}), so an attachment-scoped verdict
 *       made the first LTE open after every Wi-Fi period - the owner's daily "leaving home" -
 *       relearn the ~8 s stall. The stall is the carrier's path, not the session's. A different
 *       SIM, a different serving network, and roaming (never carrier-scoped) do not inherit.</li>
 *   <li><b>attachment</b> (Wi-Fi, Ethernet, and cellular without a readable carrier identity or
 *       while roaming): NetPath's network id (one per NetworkAgent, not reused within a boot) plus
 *       the interface name, e.g. {@code wifi:112/wlan0}. No SSID or BSSID is read.</li>
 * </ul>
 * <p>No verdict is learnt or applied on a VPN (its network id survives underlying cell/Wi-Fi
 * changes) or while media goes through a proxy (the stall would be the proxy's). A network where
 * Cronet and IPv6 work never gets a verdict. The asymmetry that makes the wider carrier scope
 * right: a wrong verdict costs little (OkHttp over IPv4 served every edge in 93-199 ms), a missing
 * one costs 4-8 s - and the re-probes below still clear a verdict that stopped being true.</p>
 *
 * <p>Across a reboot: attachment verdicts are dropped (network ids restart); carrier verdicts are
 * kept within their lifetime, their times re-based on the two boots' wall-clock origins (a clock
 * jump can only make them look expired or corrupt, i.e. dropped).</p>
 *
 * <p>Lifetime and recovery: a verdict lives {@link #TTL_MS} from its last renewal once at least two
 * different edges have shown the stall, {@link #SINGLE_EDGE_TTL_MS} while only one has (a single
 * broken edge must not keep a whole network off Cronet/IPv6). Using a verdict claims a background
 * re-probe when one is due ({@link #FIRST_REPROBE_MS} while single-edge, then {@link #REPROBE_MS}),
 * alternating two roles: RECHECK the stalled edges in turn (an answer means the path is fixed: a
 * single-edge verdict goes at once, a multi-edge one after a second answer with no stall in
 * between) and EXPLORE a host this network's media used that is not known to stall (it stalls too:
 * a second edge, the verdict is renewed; it answers: nothing). Re-probing the same broken edge never
 * renews anything, so only fresh evidence keeps a verdict alive. Every removal is logged and leaves
 * a persisted breadcrumb that the next process's {@code media-path restore} line reports.</p>
 *
 * <p>Pure logic: clock, network identity, scope, proxy state, boot identity, persistence and
 * logging come from {@link Env}. Thread-safe; the persisted snapshot is read only by {@link #load()}
 * (a background thread) - until then only this process's observations count. The use listener
 * (the prober) is called without the lock held.</p>
 */
final class MediaPathVerdicts {

    enum Kind {
        CRONET_STALL("cronet-stall"),
        V6_STALL("v6-stall");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        @Nullable
        static Kind fromLabel(String label) {
            for (Kind kind : values()) {
                if (kind.label.equals(label)) {
                    return kind;
                }
            }
            return null;
        }
    }

    enum ProbeOutcome { RECOVERED, STALLED, INCONCLUSIVE }

    /** Lifetime since the last renewal once two or more different edges have stalled. */
    static final long TTL_MS = 24 * 60 * 60_000L;
    /** Lifetime while a single edge is the only evidence. */
    static final long SINGLE_EDGE_TTL_MS = 2 * 60 * 60_000L;
    /** Re-probe cadence while single-edge: a hiccup is undone, a wider stall confirmed, fast. */
    static final long FIRST_REPROBE_MS = 60_000L;
    /** Re-probe cadence once two or more edges have stalled. */
    static final long REPROBE_MS = 15 * 60_000L;
    /** Scopes kept (the oldest-renewed one is dropped first). */
    static final int MAX_SCOPES = 4;
    /** Stalled edges remembered per verdict (oldest dropped: it may count as new again later). */
    static final int MAX_STALLED_HOSTS = 4;
    /** The boot wall time moves with clock corrections; beyond this it is another boot. */
    static final long BOOT_WALL_TOLERANCE_MS = 10 * 60_000L;
    static final String CARRIER_PREFIX = "carrier:";
    private static final int MAX_COUNT = 99;
    private static final String FORMAT = "v4";
    /** Still read: v3 records simply carry no answered edges. */
    private static final String FORMAT_V3 = "v3";
    private static final AtomicLong IDS = new AtomicLong();

    interface Env {
        /** {@code SystemClock.elapsedRealtime()}: comparable across process restarts of one boot. */
        long nowMs();

        /** Credential-free identity of the default network (NetPath), or null when unknown. */
        @Nullable
        String networkKey();

        /**
         * The scope a verdict about {@code network} belongs to when it is the current default
         * network: {@code carrier:<subId>:<servingPlmn>:<simPlmn>} or {@code <network>/<iface>}
         * (see the class doc); null when it is not current, or cannot be identified.
         */
        @Nullable
        String scope(String network);

        /** False while media requests would go through a proxy. */
        boolean direct();

        /** The open this evidence belongs to (NetPath episode), or null outside one. */
        @Nullable
        String episode();

        /** Settings.Global.BOOT_COUNT, or -1 when unknown. */
        long bootCount();

        /** Wall-clock time of this boot ({@code currentTimeMillis - elapsedRealtime}). */
        long bootWallMs();

        @Nullable
        String load();

        /**
         * Saves the snapshot (null = none left) and, when {@code lastOff} is not null, the
         * breadcrumb of the last removal ({@link #lastOffNote}) - in ONE preferences transaction,
         * so a process death can never leave an "off" note beside the old verdict.
         */
        void save(@Nullable String snapshot, @Nullable String lastOff);

        /** The persisted breadcrumb of the last verdict removal. */
        @Nullable
        String loadLastOff();

        void log(String line);
    }

    /** Told whenever a verdict steered a request, so a due re-probe can be claimed. */
    interface UseListener {
        void onVerdictUsed(Kind kind, String network);
    }

    /** One claimed re-probe: the host to test, the verdict it answers for, and why. */
    static final class ProbeTicket {
        final Kind kind;
        /** The network attachment the probe must start and end on. */
        final String network;
        final String scope;
        final String host;
        final long claimedAtMs;
        final long verdictId;
        /** EXPLORE: a used host not known to stall; RECHECK: a host that stalled. */
        final boolean explore;

        ProbeTicket(Kind kind, String network, String scope, String host, long claimedAtMs,
                long verdictId, boolean explore) {
            this.kind = kind;
            this.network = network;
            this.scope = scope;
            this.host = host;
            this.claimedAtMs = claimedAtMs;
            this.verdictId = verdictId;
            this.explore = explore;
        }
    }

    private static final class Verdict {
        final long id = IDS.incrementAndGet();
        final long sinceMs;
        final boolean restored;
        /** Last renewal: a real media stall, or an EXPLORE probe that found another stalled edge. */
        long seenMs;
        long probeMs;
        /** Distinct pieces of evidence (episodes, probes), for the log. */
        int count;
        @Nullable String lastEpisode;
        /** Edges that showed the stall, oldest first. */
        final LinkedHashSet<String> stalledHosts = new LinkedHashSet<>();
        boolean exploreNext;
        /** Rotates RECHECK over the candidate edges (one claim each, in turn). */
        int recheckCursor;
        /**
         * Stalled edges that ANSWERED a RECHECK since the last stall anywhere on this verdict
         * (persisted, so short sessions add up). The verdict clears once {@link #answersNeeded}
         * DIFFERENT edges answered; any stall empties it. An edge that can no longer be probed
         * (inconclusive) keeps the clear pending; no aging rule is needed for it: without a new
         * stall nothing renews the verdict, so it lapses at its lifetime anyway - and until then
         * it only keeps media on OkHttp/IPv4, which answered every edge in 93-199 ms.
         */
        final LinkedHashSet<String> answeredHosts = new LinkedHashSet<>();

        Verdict(long sinceMs, long seenMs, int count, boolean restored) {
            this.sinceMs = sinceMs;
            this.seenMs = seenMs;
            this.count = count;
            this.restored = restored;
        }

        void addStalledHost(String host) {
            stalledHosts.remove(host);
            stalledHosts.add(host);
            while (stalledHosts.size() > MAX_STALLED_HOSTS) {
                Iterator<String> oldest = stalledHosts.iterator();
                oldest.next();
                oldest.remove();
            }
        }

        long ttlMs() {
            return stalledHosts.size() >= 2 ? TTL_MS : SINGLE_EDGE_TTL_MS;
        }

        long reprobeMs() {
            // A clear waiting for its confirming answer is re-checked at the quick cadence.
            return stalledHosts.size() >= 2 && answeredHosts.isEmpty() ? REPROBE_MS : FIRST_REPROBE_MS;
        }

        /** One answer clears a single-edge verdict; a multi-edge one needs two different edges. */
        int answersNeeded() {
            return Math.min(2, Math.max(1, stalledHosts.size()));
        }

        void stalledAgain() {
            answeredHosts.clear();
        }
    }

    private final Env mEnv;
    private final boolean mPersistent;
    /** scope -> its verdicts. */
    private final Map<String, Map<Kind, Verdict>> mScopes = new LinkedHashMap<>();
    /** scope -> the googlevideo host its OkHttp media used last (EXPLORE candidates). */
    private final Map<String, String> mUsedHosts = new LinkedHashMap<>();
    private boolean mLoaded;
    /** Breadcrumb of the latest removal, written with the next snapshot (same transaction). */
    @Nullable private String mPendingLastOff;
    @Nullable private volatile UseListener mUseListener;

    MediaPathVerdicts(Env env, boolean persistent) {
        mEnv = env;
        mPersistent = persistent;
        mLoaded = !persistent;
    }

    void setUseListener(@Nullable UseListener listener) {
        mUseListener = listener;
    }

    /** True when no scope holds a verdict: lets callers skip the network lookup entirely. */
    synchronized boolean isEmpty() {
        return mScopes.isEmpty();
    }

    /** Whether {@code kind} holds on {@code network}; retires a lapsed verdict. */
    synchronized boolean isActive(Kind kind, @Nullable String network) {
        String scope = scopeOf(network);
        return scope != null && activeVerdict(kind, scope) != null;
    }

    /**
     * {@code kind} was observed on {@code network} by a real media request (the network the
     * evidence STARTED on - the caller has checked it did not change). Returns true when this
     * created the verdict.
     */
    synchronized boolean observe(Kind kind, @Nullable String network, @Nullable String host,
            String evidence) {
        String scope = isVerdictNetwork(network) && mEnv.direct() ? mEnv.scope(network) : null;
        if (scope == null || !isScopeKey(scope)) {
            String refusal = network == null || !StartupDeadlinePolicy.isKnownNetwork(network)
                    ? null : network.startsWith("vpn:") ? "vpn"
                    : !isVerdictNetwork(network) ? "transport"
                    : !mEnv.direct() ? "proxy" : "no-scope";
            if (refusal != null) {
                mEnv.log("media-path verdict skipped kind=" + kind.label + " network=" + network
                        + " reason=" + refusal + " evidence=" + evidence);
            }
            return false;
        }
        long now = mEnv.nowMs();
        String episode = mEnv.episode();
        Verdict verdict = activeVerdict(kind, scope);
        if (verdict != null) {
            verdict.seenMs = now;
            verdict.stalledAgain();
            if (episode == null || !episode.equals(verdict.lastEpisode)) {
                // Audio and video of one open stall together: one piece of evidence, not two.
                verdict.count = Math.min(MAX_COUNT, verdict.count + 1);
            }
            verdict.lastEpisode = episode;
            if (isGoogleVideoHost(host)) {
                verdict.addStalledHost(host);
            }
            persist();
            return false;
        }
        Map<Kind, Verdict> verdicts = mScopes.get(scope);
        if (verdicts == null) {
            if (!makeRoom(scope.startsWith(CARRIER_PREFIX), now)) {
                mEnv.log("media-path verdict skipped kind=" + kind.label + " network=" + network
                        + " reason=full-of-carriers evidence=" + evidence);
                persist(); // what makeRoom pruned
                return false;
            }
            verdicts = new EnumMap<>(Kind.class);
            mScopes.put(scope, verdicts);
        }
        verdict = new Verdict(now, now, 1, false);
        verdict.lastEpisode = episode;
        if (isGoogleVideoHost(host)) {
            verdict.addStalledHost(host);
        }
        verdicts.put(kind, verdict);
        mEnv.log("media-path verdict on kind=" + kind.label + " network=" + network
                + " scope=" + scopeKind(scope) + " key=" + scope + " host=" + host
                + " evidence=" + evidence
                + " ttlMs=" + SINGLE_EDGE_TTL_MS + "/" + TTL_MS
                + " reprobeMs=" + FIRST_REPROBE_MS + "/" + REPROBE_MS
                + " persisted=" + (mPersistent ? "y" : "n"));
        persist();
        return true;
    }

    /** Drops {@code kind} for {@code network}'s scope (evidence against it); no-op when absent. */
    synchronized void clear(Kind kind, @Nullable String network, String reason) {
        String scope = scopeOf(network);
        Map<Kind, Verdict> verdicts = scope != null ? mScopes.get(scope) : null;
        Verdict verdict = verdicts != null ? verdicts.get(kind) : null;
        if (verdict != null) {
            drop(scope, kind, verdict, reason);
        }
    }

    /** OkHttp media reached {@code host} on {@code network}: a candidate for an EXPLORE probe. */
    synchronized void noteHost(@Nullable String network, @Nullable String host) {
        if (!isGoogleVideoHost(host) || mScopes.isEmpty()) {
            return;
        }
        String scope = scopeOf(network);
        if (scope == null || !mScopes.containsKey(scope)) {
            return;
        }
        mUsedHosts.remove(scope);
        mUsedHosts.put(scope, host);
        while (mUsedHosts.size() > MAX_SCOPES) {
            mUsedHosts.remove(mUsedHosts.keySet().iterator().next());
        }
    }

    /** A verdict steered a request: lets the listener claim a due re-probe (outside the lock). */
    void noteUse(Kind kind, @Nullable String network) {
        UseListener listener = mUseListener;
        if (listener != null && network != null && isActive(kind, network)) {
            listener.onVerdictUsed(kind, network);
        }
    }

    /** Claims the re-probe of {@code kind} on {@code network} when one is due; null otherwise. */
    @Nullable
    synchronized ProbeTicket claimProbe(Kind kind, @Nullable String network) {
        String scope = scopeOf(network);
        Verdict verdict = scope != null ? activeVerdict(kind, scope) : null;
        if (verdict == null || verdict.stalledHosts.isEmpty()) {
            return null;
        }
        long now = mEnv.nowMs();
        if (now - Math.max(verdict.seenMs, verdict.probeMs) < verdict.reprobeMs()) {
            return null;
        }
        String used = mUsedHosts.get(scope);
        boolean explore = verdict.answeredHosts.isEmpty() && verdict.exploreNext && used != null
                && !verdict.stalledHosts.contains(used);
        verdict.exploreNext = !verdict.exploreNext;
        verdict.probeMs = now;
        String host = explore ? used : nextRecheckHost(verdict);
        return new ProbeTicket(kind, network, scope, host, now, verdict.id, explore);
    }

    /**
     * The stalled edges in turn - every one gets RECHECKed, not just the oldest - skipping the
     * ones that already answered while a clear is pending (the confirmation must come from a
     * DIFFERENT edge).
     */
    private static String nextRecheckHost(Verdict verdict) {
        List<String> hosts = new ArrayList<>(verdict.stalledHosts);
        hosts.removeAll(verdict.answeredHosts);
        if (hosts.isEmpty()) {
            hosts.addAll(verdict.stalledHosts);
        }
        String host = hosts.get(Math.floorMod(verdict.recheckCursor, hosts.size()));
        verdict.recheckCursor++;
        return host;
    }

    /**
     * A re-probe finished. {@code sameNetwork}: the probe started and ended on the ticket's
     * network (a probe across a handover proves nothing about either network).
     */
    synchronized boolean onProbeResult(ProbeTicket ticket, ProbeOutcome outcome, long elapsedMs,
            String detail, boolean sameNetwork) {
        Verdict verdict = mEnv.direct() ? activeVerdict(ticket.kind, ticket.scope) : null;
        String applied;
        String scopeNow = sameNetwork && isVerdictNetwork(ticket.network)
                ? mEnv.scope(ticket.network) : null;
        if (!sameNetwork) {
            applied = "n reason=network-changed";
        } else if (!ticket.scope.equals(scopeNow)) {
            // Same attachment, another scope now (it started roaming while the probe ran): the
            // answer describes the roaming path, not the home carrier's.
            applied = "n reason=scope-changed";
        } else if (verdict == null || verdict.id != ticket.verdictId) {
            applied = "n reason=stale-verdict"; // cleared or re-learnt since the claim
        } else if (outcome == ProbeOutcome.RECOVERED && ticket.explore) {
            applied = "n reason=healthy-edge"; // one working edge says nothing about the others
        } else if (outcome == ProbeOutcome.RECOVERED && verdict.seenMs > ticket.claimedAtMs) {
            applied = "n reason=newer-evidence"; // a media stall arrived while this ran
        } else if (outcome == ProbeOutcome.RECOVERED
                && answeredWith(verdict, ticket.host) < verdict.answersNeeded()) {
            // NEWTUBE(media-path): Movistar edges flip between stalling and answering (rr6-cjol
            // answered 4 min after its stall on 09-25; rr4-cjoe answered at 20:03, rr1-cjoe
            // stalled at 09:33): a verdict two edges confirmed needs two DIFFERENT edges to answer
            // - a missing verdict costs 4-8 s, a stale one next to nothing.
            verdict.answeredHosts.add(ticket.host);
            persist();
            applied = "n reason=confirm-pending answered=" + verdict.answeredHosts.size() + "/"
                    + verdict.answersNeeded();
        } else if (outcome == ProbeOutcome.RECOVERED) {
            applied = "y";
        } else if (outcome == ProbeOutcome.STALLED && ticket.explore
                && !verdict.stalledHosts.contains(ticket.host)) {
            applied = "y";
        } else if (outcome == ProbeOutcome.STALLED) {
            applied = "n reason=known-edge"; // the same broken edge never renews on its own
        } else {
            applied = "n";
        }
        mEnv.log("media-path probe kind=" + ticket.kind.label + " network=" + ticket.network
                + " scope=" + scopeKind(ticket.scope) + " host=" + ticket.host
                + " role=" + (ticket.explore ? "explore" : "recheck")
                + " outcome=" + outcome.name().toLowerCase(Locale.ROOT)
                + " elapsedMs=" + elapsedMs + " detail=" + detail + " applied=" + applied);
        if (outcome == ProbeOutcome.STALLED && sameNetwork && ticket.scope.equals(scopeNow)
                && verdict != null && verdict.id == ticket.verdictId
                && !verdict.answeredHosts.isEmpty()) {
            verdict.stalledAgain(); // still stalling somewhere: a pending clear starts over
            persist();
        }
        if (!"y".equals(applied)) {
            // A pending clear gets its confirming RECHECK scheduled (not just made eligible).
            return applied.startsWith("n reason=confirm-pending");
        }
        if (outcome == ProbeOutcome.RECOVERED) {
            drop(ticket.scope, ticket.kind, verdict, "probe-answered host=" + ticket.host
                    + (verdict.answeredHosts.isEmpty() ? "" : " after=" + verdict.answeredHosts));
        } else {
            verdict.seenMs = mEnv.nowMs();
            verdict.count = Math.min(MAX_COUNT, verdict.count + 1);
            verdict.lastEpisode = null;
            verdict.addStalledHost(ticket.host);
            verdict.stalledAgain();
            persist();
        }
        return false;
    }

    /** How many different edges will have answered, counting {@code host}. */
    private static int answeredWith(Verdict verdict, String host) {
        return verdict.answeredHosts.size() + (verdict.answeredHosts.contains(host) ? 0 : 1);
    }

    /**
     * "scope=carrier kind:ageMs=..:edges=..:restored=y,..." for {@code network}, or "" when no
     * verdict holds there.
     */
    synchronized String describe(@Nullable String network) {
        String scope = scopeOf(network);
        if (scope == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Kind kind : Kind.values()) {
            Verdict verdict = activeVerdict(kind, scope);
            if (verdict == null) {
                continue;
            }
            result.append(result.length() == 0 ? "scope=" + scopeKind(scope) + " verdicts=" : ",");
            describe(result, kind, verdict);
            result.append(":restored=").append(verdict.restored ? 'y' : 'n');
        }
        return result.toString();
    }

    private void describe(StringBuilder out, Kind kind, Verdict verdict) {
        long now = mEnv.nowMs();
        out.append(kind.label).append(":ageMs=").append(now - verdict.sinceMs)
                .append(":seenAgoMs=").append(now - verdict.seenMs)
                .append(":count=").append(verdict.count)
                .append(":edges=").append(verdict.stalledHosts.size());
    }

    static String scopeKind(String scope) {
        return scope.startsWith(CARRIER_PREFIX) ? "carrier" : "attachment";
    }

    // ------------------------------------------------------------------------------------------

    /** The scope of {@code network} when a verdict could apply there right now; null otherwise. */
    @Nullable
    private String scopeOf(@Nullable String network) {
        if (mScopes.isEmpty() || !isVerdictNetwork(network) || !mEnv.direct()) {
            return null; // nothing held (no lookups at all), not a verdict network, or proxied
        }
        String scope = mEnv.scope(network);
        return scope != null && isScopeKey(scope) ? scope : null;
    }

    @Nullable
    private Verdict activeVerdict(Kind kind, String scope) {
        Map<Kind, Verdict> verdicts = mScopes.get(scope);
        Verdict verdict = verdicts != null ? verdicts.get(kind) : null;
        if (verdict == null) {
            return null;
        }
        long now = mEnv.nowMs();
        if (now - verdict.seenMs >= verdict.ttlMs() || now < verdict.seenMs) {
            drop(scope, kind, verdict, "expired");
            return null;
        }
        return verdict;
    }

    private void drop(String scope, Kind kind, Verdict verdict, String reason) {
        Map<Kind, Verdict> verdicts = mScopes.get(scope);
        if (verdicts == null || verdicts.get(kind) != verdict) {
            return;
        }
        verdicts.remove(kind);
        if (verdicts.isEmpty()) {
            mScopes.remove(scope);
            mUsedHosts.remove(scope);
        }
        logOff(scope, kind, verdict, reason);
        persist();
    }

    /**
     * One "verdict off" line, and the persisted breadcrumb the next process's restore line shows:
     * a verdict that vanished between two captured sessions (Pixel, 2026-09-25 20:49 -> 09:10,
     * nothing logged in between) can then be traced to its cause.
     */
    private void logOff(String scope, Kind kind, Verdict verdict, String reason) {
        mEnv.log("media-path verdict off kind=" + kind.label + " scope=" + scopeKind(scope)
                + " key=" + scope + " reason=" + reason + " ageMs=" + (mEnv.nowMs() - verdict.sinceMs)
                + " count=" + verdict.count + " edges=" + verdict.stalledHosts.size());
        mPendingLastOff = lastOffNote(mEnv.bootWallMs() + mEnv.nowMs(), kind, scopeKind(scope),
                reason, verdict.stalledHosts.size());
    }

    /** {@code <wallMs>,<kind>,<scope kind>,<edges>,<reason>} with the reason made comma-free. */
    static String lastOffNote(long wallMs, Kind kind, String scopeKind, String reason, int edges) {
        return wallMs + "," + kind.label + "," + scopeKind + "," + edges + ","
                + reason.replace(',', ';').replace(' ', '_').replace('\n', '_');
    }

    /** "v6-stall@carrier:edges=2:probe-answered_host=..:agoMs=..", or "none". */
    static String describeLastOff(@Nullable String note, long wallNowMs) {
        String[] fields = note != null ? note.split(",", 5) : new String[0];
        long wall = fields.length == 5 ? parseLong(fields[0], -1) : -1;
        if (wall < 0) {
            return "none";
        }
        return fields[1] + "@" + fields[2] + ":edges=" + fields[3] + ":" + fields[4]
                + ":agoMs=" + (wallNowMs - wall);
    }

    /**
     * Room for one more scope. Lapsed verdicts go first (an expired record must not hold a slot
     * while a live one is pushed out); then the least recently renewed ATTACHMENT scope (a Wi-Fi
     * session's verdict must not push out the carrier's, which every later cellular session
     * inherits); a carrier only to make room for another carrier. The newcomer is declined (false)
     * when it ranks below the would-be victim - an attachment against live carriers, or an OLDER
     * record of the same kind (a stale stored verdict must not push out a newer one learnt before
     * the restore finished). Every removal is logged.
     */
    private boolean makeRoom(boolean incomingCarrier, long incomingSeenMs) {
        long now = mEnv.nowMs();
        for (String scope : new ArrayList<>(mScopes.keySet())) {
            for (Map.Entry<Kind, Verdict> entry : new ArrayList<>(mScopes.get(scope).entrySet())) {
                Verdict verdict = entry.getValue();
                if (now - verdict.seenMs >= verdict.ttlMs() || now < verdict.seenMs) {
                    drop(scope, entry.getKey(), verdict, "expired");
                }
            }
        }
        while (mScopes.size() >= MAX_SCOPES) {
            String victim = null;
            long victimSeen = Long.MAX_VALUE;
            boolean victimCarrier = true;
            for (Map.Entry<String, Map<Kind, Verdict>> entry : mScopes.entrySet()) {
                boolean carrier = entry.getKey().startsWith(CARRIER_PREFIX);
                long seen = Long.MIN_VALUE;
                for (Verdict verdict : entry.getValue().values()) {
                    seen = Math.max(seen, verdict.seenMs);
                }
                if (victim == null || (victimCarrier && !carrier)
                        || (victimCarrier == carrier && seen < victimSeen)) {
                    victim = entry.getKey();
                    victimSeen = seen;
                    victimCarrier = carrier;
                }
            }
            if ((victimCarrier && !incomingCarrier)
                    || (victimCarrier == incomingCarrier && victimSeen > incomingSeenMs)) {
                return false;
            }
            Map<Kind, Verdict> evicted = mScopes.remove(victim);
            mUsedHosts.remove(victim);
            for (Map.Entry<Kind, Verdict> entry : evicted.entrySet()) {
                logOff(victim, entry.getKey(), entry.getValue(), "evicted");
            }
        }
        return true;
    }

    /**
     * Reads the persisted snapshot once (call it on a background thread: preferences, the boot
     * count, and the current network's scope - binder calls - are all read here, not by a request).
     * Observations this process made before it completes are kept; a restored record never
     * overrides them.
     */
    void load() {
        if (!mPersistent) {
            return;
        }
        String snapshot;
        long bootCount;
        String lastOff;
        try {
            snapshot = mEnv.load();
            bootCount = mEnv.bootCount();
            lastOff = mEnv.loadLastOff();
        } catch (RuntimeException e) {
            snapshot = null;
            bootCount = -1;
            lastOff = null;
        }
        String current = mEnv.networkKey();
        String currentScope = isVerdictNetwork(current) ? mEnv.scope(current) : null; // warms it
        synchronized (this) {
            if (mLoaded) {
                return;
            }
            List<String> dropped = new ArrayList<>();
            int restored = snapshot == null || snapshot.isEmpty() ? 0
                    : decode(snapshot, bootCount, dropped);
            mLoaded = true; // from here on writes carry the merged book (nothing partial before)
            StringBuilder active = new StringBuilder();
            Map<Kind, Verdict> verdicts = currentScope != null ? mScopes.get(currentScope) : null;
            if (verdicts != null) {
                for (Map.Entry<Kind, Verdict> entry : verdicts.entrySet()) {
                    if (active.length() > 0) {
                        active.append(',');
                    }
                    describe(active, entry.getKey(), entry.getValue());
                }
            }
            // Always one line per process - "stored=none" is as telling as a restore - listing
            // EVERY record (not only the current network's) with the time it has left.
            StringBuilder all = new StringBuilder();
            long now = mEnv.nowMs();
            for (Map.Entry<String, Map<Kind, Verdict>> scope : mScopes.entrySet()) {
                for (Map.Entry<Kind, Verdict> entry : scope.getValue().entrySet()) {
                    Verdict verdict = entry.getValue();
                    if (all.length() > 0) {
                        all.append(',');
                    }
                    all.append(scopeKind(scope.getKey())).append('/').append(entry.getKey().label)
                            .append(":edges=").append(verdict.stalledHosts.size())
                            .append(":seenAgoMs=").append(now - verdict.seenMs)
                            .append(":leftMs=").append(verdict.ttlMs() - (now - verdict.seenMs));
                }
            }
            mEnv.log("media-path restore records=" + restored
                    + " stored=" + (snapshot == null || snapshot.isEmpty() ? "none" : "present")
                    + (dropped.isEmpty() ? "" : " dropped=" + dropped)
                    + " current=" + current
                    + (currentScope != null ? " scope=" + scopeKind(currentScope) : "")
                    + " active=[" + active + "] all=[" + all + "]"
                    + " lastOff=" + describeLastOff(lastOff, mEnv.bootWallMs() + now));
            if (!dropped.isEmpty() || !mScopes.isEmpty() || mPendingLastOff != null) {
                persist(); // drop what is stale; save what this process learnt meanwhile
            }
        }
    }

    private void persist() {
        if (!mPersistent || !mLoaded) {
            return;
        }
        try {
            mEnv.save(mScopes.isEmpty() ? null : encode(), mPendingLastOff);
            mPendingLastOff = null;
        } catch (RuntimeException ignored) {
            // Best effort: the in-memory verdict still steers this process.
        }
    }

    /**
     * {@code v3,<bootCount>,<bootWallMs>} then one line per verdict:
     * {@code <scope>,<kind>,<sinceMs>,<seenMs>,<count>,<host>;<host>...}. Times are
     * elapsedRealtime of the writing boot.
     */
    String encode() {
        StringBuilder out = new StringBuilder(FORMAT).append(',').append(mEnv.bootCount())
                .append(',').append(mEnv.bootWallMs());
        for (Map.Entry<String, Map<Kind, Verdict>> entry : mScopes.entrySet()) {
            for (Map.Entry<Kind, Verdict> verdict : entry.getValue().entrySet()) {
                Verdict value = verdict.getValue();
                out.append('\n').append(entry.getKey()).append(',').append(verdict.getKey().label)
                        .append(',').append(value.sinceMs).append(',').append(value.seenMs)
                        .append(',').append(value.count).append(',')
                        .append(String.join(";", value.stalledHosts)).append(',')
                        .append(String.join(";", value.answeredHosts));
            }
        }
        return out.toString();
    }

    /**
     * Restores what is still valid. Attachment scopes only from this very boot; carrier scopes
     * also from an earlier one, times re-based on the boots' wall-clock origins. Anything else is
     * dropped (and named in {@code dropped}); a damaged value can only ever restore less.
     */
    private int decode(String snapshot, long bootCount, List<String> dropped) {
        String[] lines = snapshot.split("\n");
        String[] header = lines[0].split(",", -1);
        boolean v3 = header.length == 3 && FORMAT_V3.equals(header[0]);
        if (header.length != 3 || !(v3 || FORMAT.equals(header[0]))) {
            dropped.add("format");
            return 0;
        }
        long storedBootWall = parseLong(header[2], Long.MIN_VALUE);
        long currentBootWall = mEnv.bootWallMs();
        boolean sameBoot = sameBoot(parseLong(header[1], Long.MIN_VALUE), storedBootWall,
                bootCount, currentBootWall);
        boolean rebasable = storedBootWall > 0 && currentBootWall > 0;
        long now = mEnv.nowMs();
        int restored = 0;
        // Carrier records first: when slots run short, they are the ones every later cellular
        // session inherits (makeRoom applies the same preference against this process's own).
        List<String> records = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith(CARRIER_PREFIX)) {
                records.add(lines[i]);
            }
        }
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].startsWith(CARRIER_PREFIX)) {
                records.add(lines[i]);
            }
        }
        for (String line : records) {
            String[] fields = line.split(",", -1);
            boolean shaped = fields.length == (v3 ? 6 : 7);
            String scope = shaped ? fields[0] : null;
            Kind kind = shaped ? Kind.fromLabel(fields[1]) : null;
            long since = shaped ? parseLong(fields[2], Long.MIN_VALUE) : Long.MIN_VALUE;
            long seen = shaped ? parseLong(fields[3], Long.MIN_VALUE) : Long.MIN_VALUE;
            long count = shaped ? parseLong(fields[4], -1) : -1;
            if (kind == null || !isScopeKey(scope) || since == Long.MIN_VALUE
                    || seen == Long.MIN_VALUE || count < 1 || count > MAX_COUNT) {
                dropped.add("corrupt");
                continue;
            }
            boolean carrier = scope.startsWith(CARRIER_PREFIX);
            if (!sameBoot) {
                if (!carrier || !rebasable) {
                    dropRestored(dropped, "other-boot", kind, scope, fields[5]);
                    continue;
                }
                // Same instant on the wall clock, expressed in this boot's elapsedRealtime.
                long shift = storedBootWall - currentBootWall;
                since += shift;
                seen += shift;
            }
            if (seen < since || seen > now) {
                dropped.add("corrupt");
                continue;
            }
            Verdict verdict = new Verdict(since, seen, (int) count, true);
            for (String host : fields[5].split(";")) {
                if (isGoogleVideoHost(host)) {
                    verdict.addStalledHost(host);
                }
            }
            if (!v3) {
                for (String host : fields[6].split(";")) {
                    if (verdict.stalledHosts.contains(host)) {
                        verdict.answeredHosts.add(host);
                    }
                }
            }
            if (now - seen >= verdict.ttlMs()) {
                dropRestored(dropped, "expired", kind, scope, fields[5]);
                continue;
            }
            Map<Kind, Verdict> verdicts = mScopes.get(scope);
            if (verdicts == null) {
                if (!makeRoom(carrier, verdict.seenMs)) {
                    dropRestored(dropped, "overflow", kind, scope, fields[5]);
                    continue;
                }
                verdicts = new EnumMap<>(Kind.class);
                mScopes.put(scope, verdicts);
            }
            if (verdicts.containsKey(kind)) {
                continue; // learnt by this process before the load finished: newer
            }
            verdicts.put(kind, verdict);
            restored++;
        }
        return restored;
    }

    /** A valid stored verdict not restored: named in the restore line AND left as breadcrumb. */
    private void dropRestored(List<String> dropped, String reason, Kind kind, String scope,
            String hosts) {
        dropped.add(reason + ":" + kind.label + "@" + scopeKind(scope));
        int edges = 0;
        for (String host : hosts.split(";")) {
            edges += isGoogleVideoHost(host) ? 1 : 0;
        }
        mPendingLastOff = lastOffNote(mEnv.bootWallMs() + mEnv.nowMs(), kind, scopeKind(scope),
                reason + "-at-restore", edges);
    }

    /**
     * Same boot only when BOOT_COUNT is known on both sides and equal, and the boot wall times
     * agree: network ids restart after a reboot, so "cell:108" may then be any network.
     */
    static boolean sameBoot(long storedCount, long storedWall, long currentCount, long currentWall) {
        if (storedCount < 0 || currentCount < 0 || storedCount != currentCount) {
            return false;
        }
        return storedWall > 0 && currentWall > 0
                && Math.abs(storedWall - currentWall) <= BOOT_WALL_TOLERANCE_MS;
    }

    /** Verdicts live only on physical networks: "cell:108", "wifi:-42", "ethernet:7". */
    static boolean isVerdictNetwork(@Nullable String network) {
        if (!StartupDeadlinePolicy.isKnownNetwork(network)) {
            return false;
        }
        int colon = network.indexOf(':');
        if (colon <= 0 || colon == network.length() - 1) {
            return false;
        }
        String transport = network.substring(0, colon);
        if (!transport.equals("cell") && !transport.equals("wifi")
                && !transport.equals("ethernet")) {
            return false; // vpn (follows the underlying network around), bluetooth, other
        }
        return isInteger(network, colon + 1, network.length());
    }

    /**
     * {@code carrier:<subId>:<servingPlmn>:<simPlmn>} (PLMNs are MCC+MNC, 5-6 digits) or
     * {@code <verdict network>/<interface>}.
     */
    static boolean isScopeKey(@Nullable String scope) {
        if (scope == null || scope.length() > 96) {
            return false;
        }
        if (scope.startsWith(CARRIER_PREFIX)) {
            String[] parts = scope.substring(CARRIER_PREFIX.length()).split(":", -1);
            return parts.length == 3 && parts[0].length() <= 9 && isDigits(parts[0])
                    && isPlmn(parts[1]) && isPlmn(parts[2]);
        }
        int slash = scope.indexOf('/');
        if (slash <= 0 || !isVerdictNetwork(scope.substring(0, slash))) {
            return false;
        }
        String iface = scope.substring(slash + 1);
        if (iface.isEmpty() || iface.length() > 32) {
            return false;
        }
        for (int i = 0; i < iface.length(); i++) {
            char c = iface.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    static boolean isPlmn(@Nullable String plmn) {
        return plmn != null && (plmn.length() == 5 || plmn.length() == 6) && isDigits(plmn);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isInteger(String value, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c == '-' && i == from)) {
                return false;
            }
        }
        return to > from && !(to - from == 1 && value.charAt(from) == '-');
    }

    static boolean isGoogleVideoHost(@Nullable String host) {
        if (host == null || !host.endsWith(".googlevideo.com") || host.length() > 253) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Test/diagnostic view: the scopes that currently hold a verdict. */
    synchronized List<String> scopes() {
        return new ArrayList<>(mScopes.keySet());
    }
}
