package com.newtube.mobile.ui.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** YouTube vss-id to Lounge setSubtitlesTrack language conversion. */
public class CastCaptionLanguageTest {
    @Test
    public void extractsManualAndAutomaticCaptionLanguages() {
        assertEquals("en", MobilePlaybackActivity.castCaptionLanguageCode(".en", "English"));
        assertEquals("es-419", MobilePlaybackActivity.castCaptionLanguageCode("a.es-419", "Spanish"));
    }

    @Test
    public void usesOnlyCodeShapedFallbacks() {
        assertEquals("ca", MobilePlaybackActivity.castCaptionLanguageCode(null, "ca"));
        assertNull(MobilePlaybackActivity.castCaptionLanguageCode(null, "Català"));
        assertNull(MobilePlaybackActivity.castCaptionLanguageCode("track-1", "English"));
    }
}
