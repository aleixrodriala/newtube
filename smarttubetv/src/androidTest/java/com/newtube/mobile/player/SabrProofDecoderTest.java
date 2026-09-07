package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Decoder-helper validation only; the APK asset is not SABR or network delivery evidence. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrProofDecoderTest {
    private static final int MAX_FIXTURE_BYTES = 16 * 1024 * 1024;

    @Test
    public void localFixtureDecodesVideoAndAudioToBuffers() throws IOException {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.aleixrodriala.arc", target.getPackageName());
        byte[] data = readFixture(target);

        verifyAndReport(SabrProofDecoder.decode(data, true), true);
        verifyAndReport(SabrProofDecoder.decode(data, false), false);
    }

    @Test
    public void emptyInputIsRejectedBeforeDecoderSetup() {
        assertThrows(IOException.class, () -> SabrProofDecoder.decode(new byte[0], true));
        assertThrows(IOException.class, () -> SabrProofDecoder.decode(null, false));
    }

    private static byte[] readFixture(Context target) throws IOException {
        try (InputStream input = target.getAssets().open("ttff-fixture.mp4");
                ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buffer = new byte[16 * 1024];
            while (output.size() < MAX_FIXTURE_BYTES) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, MAX_FIXTURE_BYTES - output.size()));
                if (count == -1) {
                    assertTrue("fixture must contain media bytes", output.size() > 0);
                    return output.toByteArray();
                }
                if (count == 0) {
                    throw new IOException("fixture_zero_progress");
                }
                output.write(buffer, 0, count);
            }
            // Do not consume even one byte beyond the cap to distinguish a larger asset.
            throw new IOException("fixture_size_limit");
        }
    }

    private static void verifyAndReport(SabrProofDecoder.Result result, boolean video) {
        assertTrue("extractor must supply samples", result.sampleCount > 0);
        assertTrue("codec must produce nonempty decoded buffers", result.outputBufferCount > 0);
        assertTrue("bounded output count", result.outputBufferCount <= 30);
        assertTrue("first output must occur within the decode deadline",
                result.firstOutputMs >= 0 && result.firstOutputMs < 8_000);

        Bundle metrics = new Bundle();
        metrics.putInt("sabrDecoderFixtureVideo", video ? 1 : 0);
        metrics.putInt("sabrDecoderFixtureSamples", result.sampleCount);
        metrics.putInt("sabrDecoderFixtureOutputs", result.outputBufferCount);
        metrics.putLong("sabrDecoderFixtureFirstOutputMs", result.firstOutputMs);
        metrics.putInt("sabrDecoderFixtureOutputEos", result.outputEos ? 1 : 0);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }
}
