package com.newtube.mobile.player;

import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * NEWTUBE(media-path): the cheap background re-probe behind {@link MediaPathVerdicts}. When a
 * verdict steers a request and its re-probe is due, the path it avoids is tested once, off the
 * open's critical path, against the host that stalled: a tiny {@code generate_204} over Cronet
 * (cronet-stall) or over an IPv6-only OkHttp route (v6-stall). An answer within
 * {@link #RECOVERED_WITHIN_MS} clears the verdict, a timeout re-observes it, anything else (DNS
 * without IPv6, a refused connection, a slow answer) changes nothing.
 *
 * <p>Pure scheduling: the probes themselves are injected (Android ones live in
 * {@link MediaPathRouting}); every run is one request, at most one per verdict per claim.</p>
 */
final class MediaPathProber implements MediaPathVerdicts.UseListener {

    /** After the open that used the verdict: its media requests go first. */
    static final long PROBE_DELAY_MS = 5_000;
    /** A probe with no answer by then is the stall again (the stall itself lasts 3.5 s+). */
    static final long PROBE_TIMEOUT_MS = 6_000;
    /** An answer this fast would also have met the startup budget: the path works again. */
    static final long RECOVERED_WITHIN_MS = 2_500;

    /** One blocking request; {@link Result#elapsedMs} measured by the probe. */
    interface Probe {
        Result run(String host);
    }

    static final class Result {
        final MediaPathVerdicts.ProbeOutcome outcome;
        final long elapsedMs;
        final String detail;

        Result(MediaPathVerdicts.ProbeOutcome outcome, long elapsedMs, String detail) {
            this.outcome = outcome;
            this.elapsedMs = elapsedMs;
            this.detail = detail;
        }

        /** Classifies an HTTP answer (any status: the path reached the server) by its speed. */
        static Result answered(long elapsedMs, String detail) {
            return new Result(elapsedMs <= RECOVERED_WITHIN_MS
                    ? MediaPathVerdicts.ProbeOutcome.RECOVERED
                    : MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE,
                    elapsedMs, elapsedMs <= RECOVERED_WITHIN_MS ? detail : "slow-" + detail);
        }

        static Result stalled(long elapsedMs, String detail) {
            return new Result(MediaPathVerdicts.ProbeOutcome.STALLED, elapsedMs, detail);
        }

        static Result inconclusive(long elapsedMs, String detail) {
            return new Result(MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE, elapsedMs, detail);
        }
    }

    private final MediaPathVerdicts mVerdicts;
    private final Supplier<String> mNetwork;
    private final ScheduledExecutorService mExecutor;
    private final Map<MediaPathVerdicts.Kind, Probe> mProbes;
    private final long mDelayMs;

    MediaPathProber(MediaPathVerdicts verdicts, Supplier<String> network,
            ScheduledExecutorService executor, Map<MediaPathVerdicts.Kind, Probe> probes,
            long delayMs) {
        mVerdicts = verdicts;
        mNetwork = network;
        mExecutor = executor;
        mProbes = new EnumMap<>(probes);
        mDelayMs = delayMs;
    }

    @Override
    public void onVerdictUsed(MediaPathVerdicts.Kind kind, String network) {
        Probe probe = mProbes.get(kind);
        if (probe == null) {
            return;
        }
        MediaPathVerdicts.ProbeTicket ticket = mVerdicts.claimProbe(kind, network);
        if (ticket == null) {
            return;
        }
        try {
            mExecutor.schedule(() -> run(ticket, probe), mDelayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shut down: the claim lapses and the next due use claims again.
        }
    }

    void run(MediaPathVerdicts.ProbeTicket ticket, Probe probe) {
        if (!ticket.network.equals(mNetwork.get())) {
            mVerdicts.onProbeResult(ticket, MediaPathVerdicts.ProbeOutcome.INCONCLUSIVE, 0,
                    "not-started", false);
            return;
        }
        Result result;
        try {
            result = probe.run(ticket.host);
        } catch (RuntimeException e) {
            result = Result.inconclusive(-1, "error-" + e.getClass().getSimpleName());
        }
        @Nullable String after = mNetwork.get();
        boolean confirm = mVerdicts.onProbeResult(ticket, result.outcome, result.elapsedMs,
                result.detail, ticket.network.equals(after));
        if (confirm) {
            // A clear waits for a second, different edge to answer: schedule that RECHECK now
            // rather than hoping for another verdict use (a single long video may not make one).
            try {
                mExecutor.schedule(() -> onVerdictUsed(ticket.kind, ticket.network),
                        MediaPathVerdicts.FIRST_REPROBE_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Shut down: the next due use claims it instead.
            }
        }
    }
}
