package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

/** Which Opus packets may bypass the decoder after a reset: everything older than the 80 ms pre-roll. */
public class OpusPrerollGateTest {
    private static final String OPUS = MimeTypes.AUDIO_OPUS;
    // zrOOjjgIVW4 on the Pixel: snapped to 39.760 s inside the audio segment that starts at 30.001 s.
    private static final long RESET_US = 39_760_000;

    @Test
    public void onlyPacketsOlderThanThePrerollAreSkippable() {
        assertTrue(OpusPrerollGate.canSkip(OPUS, RESET_US, 30_001_000)); // the segment start
        assertTrue(OpusPrerollGate.canSkip(OPUS, RESET_US, RESET_US - 80_001));
        assertFalse(OpusPrerollGate.canSkip(OPUS, RESET_US, RESET_US - 80_000)); // pre-roll: decode
        assertFalse(OpusPrerollGate.canSkip(OPUS, RESET_US, RESET_US - 20_000));
        assertFalse(OpusPrerollGate.canSkip(OPUS, RESET_US, RESET_US)); // the target itself
        assertFalse(OpusPrerollGate.canSkip(OPUS, RESET_US, RESET_US + 20_000));
    }

    @Test
    public void otherCodecsAndUnknownTimesAreNeverSkipped() {
        assertFalse(OpusPrerollGate.canSkip(MimeTypes.AUDIO_AAC, RESET_US, 30_001_000));
        assertFalse(OpusPrerollGate.canSkip(MimeTypes.AUDIO_VORBIS, RESET_US, 30_001_000));
        assertFalse(OpusPrerollGate.canSkip(null, RESET_US, 30_001_000));
        assertFalse(OpusPrerollGate.canSkip(OPUS, C.TIME_UNSET, 30_001_000));
        assertFalse(OpusPrerollGate.canSkip(OPUS, RESET_US, C.TIME_UNSET));
    }

    @Test
    public void flushedResetSkipsUntilTheFirstPacketReachesTheDecoder() {
        OpusPrerollGate gate = new OpusPrerollGate();
        assertNull(gate.onPositionReset(RESET_US, /* decoderFlushed= */ true));
        int skipped = 0;
        long timeUs = 30_001_000;
        for (; OpusPrerollGate.canSkip(OPUS, RESET_US, timeUs); timeUs += 20_000) {
            assertTrue(gate.shouldSkip(OPUS, RESET_US, timeUs, false));
            skipped++;
        }
        assertFalse(gate.shouldSkip(OPUS, RESET_US, timeUs, false)); // first pre-roll packet
        assertEquals("skipped=" + skipped + " fromMs=30001 toMs=39661 resetMs=39760", gate.onQueued());
        // Disarmed: a later packet with an odd timestamp is never taken for pre-roll.
        assertFalse(gate.shouldSkip(OPUS, RESET_US, 31_000_000, false));
        assertNull(gate.onQueued());
    }

    @Test
    public void unflushedResetNeverSkips() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onPositionReset(RESET_US, /* decoderFlushed= */ false);
        assertFalse(gate.shouldSkip(OPUS, RESET_US, 30_001_000, false));
    }

    @Test
    public void streamChangeAfterTheResetDisarms() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onPositionReset(RESET_US, true);
        gate.onStreamChanged(); // a new period's samples: the old reset does not describe them
        assertFalse(gate.shouldSkip(OPUS, RESET_US, 30_001_000, false));
    }

    @Test
    public void enableOrderIsStreamChangeThenResetSoItArms() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onStreamChanged(); // BaseRenderer.enable -> replaceStream
        gate.onPositionReset(RESET_US, true); // ... -> resetPosition
        assertTrue(gate.shouldSkip(OPUS, RESET_US, 30_001_000, false));
    }

    @Test
    public void endOfStreamIsNeverSkipped() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onPositionReset(RESET_US, true);
        assertFalse(gate.shouldSkip(OPUS, RESET_US, 30_001_000, /* endOfStream= */ true));
    }

    @Test
    public void aResetBeforeAnyPacketQueuedReportsTheInterruptedRun() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onPositionReset(42_000_000, true); // the exact resume seek
        assertTrue(gate.shouldSkip(OPUS, 42_000_000, 40_001_000, false));
        assertEquals("skipped=1 fromMs=40001 toMs=40001 resetMs=42000 interrupted=y",
                gate.onPositionReset(RESET_US, true)); // the snap
        assertTrue(gate.shouldSkip(OPUS, RESET_US, 30_001_000, false)); // re-armed
    }

    @Test
    public void seekToTheSegmentStartSkipsNothing() {
        OpusPrerollGate gate = new OpusPrerollGate();
        gate.onPositionReset(30_001_000, true);
        assertFalse(gate.shouldSkip(OPUS, 30_001_000, 30_001_000, false));
        assertNull(gate.onQueued());
    }
}
