package com.newtube.mobile.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NEWTUBE(wall-swap): the one-minute wall without the stop and the rewind (issue #84).
 *
 * <p>googlevideo refuses a walled (visitor, client) pair's media past 60.0 s of stream (HANDOFF
 * §34). The loader asks for that segment long before the playhead gets there - 40 s ahead on the
 * emulator - but media3 took the 403 as fatal: the playback stopped with that buffer unplayed, and
 * the recovery reload resumed on the keyframe before the position (a 2-6 s rewind).
 *
 * <p>Here the refused request waits instead of failing, while the buffer keeps playing: the app
 * re-resolves the video through the recovery walk (ErrorFixerController, the same blame, wall
 * memory and walk as the reload) and hands the answer to {@link Session#land}. The media files are
 * the same on every client (same itag, lmt and length) - so the refused request, and every later one
 * of the open, goes to the new answer's URL for the same file at the same byte range; the playback
 * never notices. The new answer's pre-roll wait,
 * if it announced one, is honoured through its own ReadinessGate.
 *
 * <p>Only the generated-DASH VOD source of the watched video is armed (live, HLS, progressive,
 * SABR and the next-video preload pass through), and only a 403 after this open has been served
 * waits: a first request refused before anything played is the ordinary "this client's links are
 * refused" case the readiness gate and the reload already handle. No answer within
 * {@link #SWAP_WAIT_MS}, an answer without the refused file, or no swap left: the 403 surfaces as
 * before and ErrorFixerController's reload takes over.
 *
 * <p>The layer sits below the media cache and above the debug shaper (so the synthetic wall,
 * {@code debug.arc.poison_wall_s}, is seen and the swapped URL is judged by its own answer).
 * Rollback: {@code debug.arc.wall_swap=0}.
 */
@UnstableApi
public final class MediaRepoint {
    /** How long a refused request waits for the new answer before it fails as before. */
    static final long SWAP_WAIT_MS = 20_000;
    /** {@link #SWAP_WAIT_MS}; tests shorten it. */
    static volatile long sSwapWaitMs = SWAP_WAIT_MS;
    /** Swaps per open: the first wall, and one more if the new answer walls too. */
    static final int MAX_SWAPS = 2;

    /** Called on a loader thread when an armed open needs a new answer. */
    interface Requester {
        void onSwapNeeded(Session session, Uri refused);
    }

    private static volatile boolean sEnabled = true;
    @Nullable
    private static volatile Session sArmed;

    private MediaRepoint() {
    }

    /** Monotonic ms (the waits must also run under Robolectric, whose SystemClock stands still). */
    static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Rollback switch (MobileMainApplication, debug.arc.wall_swap=0). */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    static boolean isEnabled() {
        return sEnabled;
    }

    /**
     * Arms {@code formatInfo}'s generated-DASH source as the watched open; null when nothing of it
     * can be swapped (disabled, live, no keyed range-addressed formats).
     */
    @Nullable
    static Session arm(@Nullable String videoId, @Nullable MediaItemFormatInfo formatInfo,
            Requester requester) {
        Session previous = sArmed;
        if (previous != null) {
            disarm(previous);
        }
        if (!sEnabled || videoId == null || formatInfo == null || formatInfo.isLive()
                || !videoId.equals(formatInfo.getVideoId())) {
            return null;
        }
        Map<String, String> keyed = keyedUrls(formatInfo);
        if (keyed.isEmpty()) {
            return null;
        }
        Session session = new Session(videoId, Collections.unmodifiableSet(new HashSet<>(keyed.keySet())),
                requester);
        sArmed = session;
        return session;
    }

    static void disarm(@Nullable Session session) {
        if (session == null) {
            return;
        }
        if (sArmed == session) {
            sArmed = null;
        }
        session.close();
    }

    /** Stable key -> URL of the answer's range-addressed adaptive formats (not OTF). */
    static Map<String, String> keyedUrls(MediaItemFormatInfo formatInfo) {
        Map<String, String> keyed = new HashMap<>();
        List<MediaFormat> formats = formatInfo.getAdaptiveFormats();
        if (formats == null) {
            return keyed;
        }
        for (MediaFormat format : formats) {
            if (format == null || format.isOtf() || format.getUrl() == null) {
                continue;
            }
            String key = keyOf(Uri.parse(format.getUrl()));
            if (key != null) {
                keyed.put(key, format.getUrl());
            }
        }
        return keyed;
    }

    /**
     * The file a googlevideo media URL of the open's video names: itag, lmt (the encode's
     * revision), xtags (the audio variant) and clen (its length), or null without itag and lmt.
     * Not the URL's {@code id}: that one is per answer (VISIONOS and WEB_EMBED answers for the same
     * video carry different ids for the same itag and lmt, emulator 2026-10-19).
     */
    @Nullable
    static String keyOf(@Nullable Uri uri) {
        if (uri == null || !ReadinessGate.isMedia(uri) || !uri.isHierarchical()) {
            return null;
        }
        String itag = uri.getQueryParameter("itag");
        String lmt = uri.getQueryParameter("lmt");
        if (itag == null || lmt == null || uri.getQueryParameter("sq") != null
                || uri.getPath().contains("/sq/")) {
            return null;
        }
        String xtags = uri.getQueryParameter("xtags");
        String clen = uri.getQueryParameter("clen");
        return itag + "." + lmt + "." + (xtags != null ? xtags : "") + "." + (clen != null ? clen : "");
    }

    /** The itag in a key or URL, for log lines (never the signed URL). */
    static String itagOf(@Nullable Uri uri) {
        String itag = uri != null && uri.isHierarchical() ? uri.getQueryParameter("itag") : null;
        return itag != null ? itag : "?";
    }

    /** Wraps the HTTP side of every media source; inert unless an open is armed. */
    static DataSource.Factory wrap(DataSource.Factory upstream) {
        return () -> new RepointDataSource(upstream.createDataSource());
    }

    /** One armed open: its files, the answer it was swapped to, and the wait for it. */
    static final class Session {
        final String videoId;
        final Set<String> keys;
        private final Requester mRequester;
        /** nowMs() of the open's first served request; -1 until then. */
        private volatile long mFirstServedAtMs = -1;
        // Guarded by this.
        private final Map<String, String> mTargets = new HashMap<>();
        private final Set<String> mRefusedKeys = new HashSet<>();
        @Nullable
        private ReadinessGate mGate;
        private boolean mPending;
        private boolean mFailed;
        private boolean mClosed;
        private int mSwaps;
        /** The swap in flight (or last landed): a waiter only ever waits for its own one. */
        private int mGeneration;
        private boolean mLanded;
        private long mPendingSinceMs;

        Session(String videoId, Set<String> keys, Requester requester) {
            this.videoId = videoId;
            this.keys = keys;
            mRequester = requester;
        }

        synchronized boolean isPending() {
            return mPending && !mClosed;
        }

        /** How much longer a waiting request may wait, ms; 0 when nothing waits. */
        synchronized long waitLeftMs() {
            return mPending && !mClosed
                    ? Math.max(0, mPendingSinceMs + sSwapWaitMs - nowMs()) : 0;
        }

        synchronized int swaps() {
            return mSwaps;
        }

        synchronized Set<String> refusedKeys() {
            return new HashSet<>(mRefusedKeys);
        }

        @Nullable
        synchronized String target(String key) {
            return mTargets.get(key);
        }

        @Nullable
        synchronized ReadinessGate gate() {
            return mGate;
        }

        /**
         * The new answer, on the main thread: {@code targets} (key -> its URL for the same file).
         * Every refused file must be in it, or nothing changes and the caller gives up.
         */
        synchronized boolean land(Map<String, String> targets, @Nullable ReadinessGate gate) {
            if (!mPending || mClosed || !targets.keySet().containsAll(mRefusedKeys)) {
                return false;
            }
            mTargets.putAll(targets);
            mGate = gate;
            mPending = false;
            // Answered: a later swap only needs the files refused after this one.
            mRefusedKeys.clear();
            mLanded = true;
            notifyAll();
            return true;
        }

        /** No usable answer: the waiting requests fail as before, and no later 403 waits. */
        synchronized void fail() {
            mFailed = true;
            mPending = false;
            notifyAll();
        }

        synchronized void close() {
            mClosed = true;
            mPending = false;
            notifyAll();
        }

        void onServed() {
            if (mFirstServedAtMs < 0) {
                synchronized (this) {
                    if (mFirstServedAtMs < 0) {
                        mFirstServedAtMs = nowMs();
                    }
                }
            }
        }

        /**
         * Whether a refusal of a request sent at {@code requestStartMs} may wait for a swap: only
         * once the open was served before that request went out. Requests sent together at the
         * start (audio and video) are the original answer's own readiness business (ReadinessGate,
         * above this layer, retries them), not a wall.
         */
        boolean servedBefore(long requestStartMs) {
            long servedAt = mFirstServedAtMs;
            return servedAt >= 0 && servedAt < requestStartMs;
        }

        /**
         * A loader thread: {@code key} was refused at {@code refusedTarget} (null = the open's own
         * URL). Asks for a new answer unless one is already on its way, and waits for it.
         *
         * @return the URL to retry the request at, or null to fail it as before
         */
        @Nullable
        String awaitSwap(String key, @Nullable String refusedTarget, Uri refusedUri)
                throws InterruptedIOException {
            boolean request = false;
            long deadline;
            int generation;
            synchronized (this) {
                if (mClosed || mFailed) {
                    return null;
                }
                mRefusedKeys.add(key);
                if (!mPending) {
                    String current = mTargets.get(key);
                    if (current != null && !current.equals(refusedTarget)) {
                        return current; // swapped while this request was on its way
                    }
                    if (current == null && mLanded) {
                        // A file the new answer does not have, still on the old answer's URL: the
                        // old answer refused it, and asking again would blame the new one. Fails
                        // this request alone; media3 drops that format and plays the others.
                        mRefusedKeys.remove(key);
                        return null;
                    }
                    if (mSwaps >= MAX_SWAPS) {
                        return null;
                    }
                    mSwaps++;
                    mGeneration++;
                    mPending = true;
                    mPendingSinceMs = nowMs();
                    request = true;
                }
                generation = mGeneration;
                deadline = mPendingSinceMs + sSwapWaitMs;
            }
            if (request) {
                mRequester.onSwapNeeded(this, refusedUri);
            }
            synchronized (this) {
                // A later swap starting before this waiter wakes is not this waiter's to time out.
                while (mPending && !mClosed && !mFailed && mGeneration == generation) {
                    long left = deadline - nowMs();
                    if (left <= 0) {
                        mPending = false;
                        mFailed = true;
                        notifyAll();
                        NetPath.log(NetPath.context() + " wall-swap timeout video=" + videoId
                                + " waitedMs=" + sSwapWaitMs);
                        return null;
                    }
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("wall swap wait cancelled");
                    }
                }
                if (mClosed || mFailed) {
                    return null;
                }
                String target = mTargets.get(key);
                return target != null && !target.equals(refusedTarget) ? target : null;
            }
        }
    }

    private static final class RepointDataSource implements DataSource {
        private final DataSource mUpstream;

        RepointDataSource(DataSource upstream) {
            mUpstream = upstream;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            mUpstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            Session session = sArmed;
            if (session == null) {
                return mUpstream.open(dataSpec);
            }
            String key = keyOf(dataSpec.uri);
            if (key == null || !session.keys.contains(key)) {
                return mUpstream.open(dataSpec);
            }
            String target = session.target(key);
            long waitStartMs = -1;
            while (true) {
                long requestStartMs = nowMs();
                try {
                    long length = openAt(dataSpec, target, target != null ? session.gate() : null);
                    session.onServed();
                    if (waitStartMs >= 0) {
                        NetPath.log(NetPath.context() + " wall-swap served video=" + session.videoId
                                + " itag=" + itagOf(dataSpec.uri) + " byte=" + dataSpec.position
                                + " waitedMs=" + (nowMs() - waitStartMs));
                    }
                    return length;
                } catch (IOException refused) {
                    if (ReadinessGate.responseCode(refused) != 403
                            || !session.servedBefore(requestStartMs)) {
                        throw refused;
                    }
                    closeQuietly();
                    if (waitStartMs < 0) {
                        waitStartMs = nowMs();
                    }
                    NetPath.log(NetPath.context() + " wall-swap wait video=" + session.videoId
                            + " itag=" + itagOf(dataSpec.uri) + " byte=" + dataSpec.position
                            + " swapped=" + (target != null ? "y" : "n"));
                    // A replacement refused too asks once more (MAX_SWAPS bounds the loop).
                    String next = session.awaitSwap(key, target, dataSpec.uri);
                    if (next == null) {
                        NetPath.log(NetPath.context() + " wall-swap give-up video=" + session.videoId
                                + " itag=" + itagOf(dataSpec.uri) + " waitedMs="
                                + (nowMs() - waitStartMs));
                        throw refused;
                    }
                    target = next;
                }
            }
        }

        private long openAt(DataSpec dataSpec, @Nullable String target,
                @Nullable ReadinessGate gate) throws IOException {
            if (target == null) {
                return mUpstream.open(dataSpec);
            }
            DataSpec swapped = dataSpec.buildUpon().setUri(Uri.parse(target)).build();
            if (gate == null) {
                return mUpstream.open(swapped);
            }
            // The new answer's announced pre-roll wait, on this same upstream instance.
            return gate.wrap(() -> mUpstream).createDataSource().open(swapped);
        }

        private void closeQuietly() {
            try {
                mUpstream.close();
            } catch (IOException ignored) {
                // The refused open's own resources; nothing to report.
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return mUpstream.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return mUpstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return mUpstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            mUpstream.close();
        }
    }
}
