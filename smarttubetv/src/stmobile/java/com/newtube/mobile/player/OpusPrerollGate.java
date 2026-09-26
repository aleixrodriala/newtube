package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.OpusUtil;

/**
 * NEWTUBE(opus-preroll): which Opus input packets the audio renderer may drop before the decoder
 * after a position reset (seek, resume, snap). Pure bookkeeping, driven by
 * {@link OpusPrerollAudioRenderer}.
 *
 * <p>The problem it solves (Pixel r3e, 10 resumes): after a seek into the middle of a 10 s WebM/Opus
 * audio segment, media3 feeds EVERY packet from the segment start to the seek position through
 * MediaCodec as decode-only. {@code SampleQueue} only drops pre-start samples itself for formats in
 * {@code MimeTypes.allSamplesAreSyncSamples}, and Opus is deliberately not one (it needs a seek
 * pre-roll). The decode-only round trips cost ~100 ms per second of audio skipped: the audio
 * renderer became ready 0.5-1.2 s after the video one, and READY (hence the picture) waited on it.
 * AAC resumes were fast because the queue drops their pre-start samples.</p>
 *
 * <p>Opus only needs {@code 80 ms} of pre-roll to reconverge after a decoder reset (RFC 7845 §4.6,
 * {@link OpusUtil#needToDecodeOpusFrame}, which media3 itself applies on its bypass path): packets
 * older than that are dropped before the codec, the pre-roll still goes through it and is discarded
 * on output as before.</p>
 *
 * <p>Only while ARMED: a reset whose sample stream was reset to a key frame (the renderer flushed
 * its decoder), until the first packet actually reaches the decoder. A stream change (a new period
 * whose timestamps belong to another stream) disarms it, and so does a reset that did not flush the
 * decoder - dropping packets into a running, un-flushed decoder would corrupt its state.</p>
 */
final class OpusPrerollGate {
    private boolean mArmed;
    private int mSkipped;
    private long mFirstSkippedUs = C.TIME_UNSET;
    private long mLastSkippedUs = C.TIME_UNSET;
    private long mResetUs = C.TIME_UNSET;

    /**
     * @return a report of the previous reset's skipping if it had not been reported yet (a reset
     *     that arrives before any packet reached the decoder, e.g. the resume snap's second seek)
     */
    @Nullable
    String onPositionReset(long positionUs, boolean decoderFlushed) {
        String report = closeRun(/* interrupted= */ true);
        mArmed = decoderFlushed;
        mResetUs = positionUs;
        return report;
    }

    void onStreamChanged() {
        mArmed = false;
    }

    /** @return true to drop this packet before the decoder */
    boolean shouldSkip(@Nullable String codecMimeType, long resetPositionUs, long bufferTimeUs,
            boolean endOfStream) {
        if (!mArmed || endOfStream || !canSkip(codecMimeType, resetPositionUs, bufferTimeUs)) {
            return false;
        }
        if (mSkipped == 0) {
            mFirstSkippedUs = bufferTimeUs;
        }
        mSkipped++;
        mLastSkippedUs = bufferTimeUs;
        return true;
    }

    /**
     * A packet went to the decoder: skipping for this reset is over (timestamps only grow from here;
     * disarming guards against any later discontinuity being taken for pre-roll).
     *
     * @return the report line payload, or null when nothing was skipped
     */
    @Nullable
    String onQueued() {
        mArmed = false;
        return closeRun(/* interrupted= */ false);
    }

    static boolean canSkip(@Nullable String codecMimeType, long resetPositionUs, long bufferTimeUs) {
        return MimeTypes.AUDIO_OPUS.equals(codecMimeType)
                && resetPositionUs != C.TIME_UNSET
                && bufferTimeUs != C.TIME_UNSET
                && bufferTimeUs < resetPositionUs
                && !OpusUtil.needToDecodeOpusFrame(/* startTimeUs= */ resetPositionUs,
                        /* frameTimeUs= */ bufferTimeUs);
    }

    @Nullable
    private String closeRun(boolean interrupted) {
        if (mSkipped == 0) {
            return null;
        }
        String report = "skipped=" + mSkipped
                + " fromMs=" + mFirstSkippedUs / 1000
                + " toMs=" + mLastSkippedUs / 1000
                + " resetMs=" + (mResetUs == C.TIME_UNSET ? -1 : mResetUs / 1000)
                + (interrupted ? " interrupted=y" : "");
        mSkipped = 0;
        mFirstSkippedUs = C.TIME_UNSET;
        mLastSkippedUs = C.TIME_UNSET;
        return report;
    }
}
