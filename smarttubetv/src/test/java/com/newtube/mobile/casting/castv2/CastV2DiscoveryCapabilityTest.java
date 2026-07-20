package com.newtube.mobile.casting.castv2;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The {@code ca} TXT capabilities bitmask filter (bit 0 = VIDEO_OUT): audio-only Cast devices
 * (soundbars, Chromecast Audio) are hidden from the picker, and anything unparseable fails OPEN -
 * hiding a real TV is worse than listing a soundbar.
 */
public class CastV2DiscoveryCapabilityTest {

    private static byte[] ca(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void videoChromecastHasVideoOut() {
        // Real-world value from a video Chromecast: 4101 = 0x1005, bit 0 set.
        assertTrue(CastV2Discovery.hasVideoOut(ca("4101")));
    }

    @Test
    public void audioOnlyDeviceLacksVideoOut() {
        // Real-world audio-group/Chromecast-Audio style value: even, bit 0 clear.
        assertFalse(CastV2Discovery.hasVideoOut(ca("2052")));
        assertFalse(CastV2Discovery.hasVideoOut(ca("0")));
    }

    @Test
    public void bareVideoOutBitCounts() {
        assertTrue(CastV2Discovery.hasVideoOut(ca("1")));
    }

    @Test
    public void missingCaFailsOpen() {
        assertTrue(CastV2Discovery.hasVideoOut(null));
        assertTrue(CastV2Discovery.hasVideoOut(ca("")));
    }

    @Test
    public void unparseableCaFailsOpen() {
        assertTrue(CastV2Discovery.hasVideoOut(ca("garbage")));
        assertTrue(CastV2Discovery.hasVideoOut(ca("0x1005"))); // hex is not the wire format
    }

    @Test
    public void whitespaceIsTolerated() {
        // TXT values are raw bytes; be lenient about stray whitespace rather than failing open.
        assertTrue(CastV2Discovery.hasVideoOut(ca(" 4101 ")));
        assertFalse(CastV2Discovery.hasVideoOut(ca(" 2052 ")));
    }
}
