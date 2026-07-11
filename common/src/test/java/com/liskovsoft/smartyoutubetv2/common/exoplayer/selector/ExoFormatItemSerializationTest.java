package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.TrackFormat;

import org.junit.Test;

/**
 * Locks the ExoFormatItem persistence format (PlayerData VIDEO_PLAYER_DATA fields 9/10/11/47) to
 * the exact byte behavior of the legacy exoplayer2-Format-backed implementation it replaced in the
 * phone-only port. Every expected string below was derived from the legacy code path
 * (Format.createXxxSampleFormat semantics incl. Util.normalizeLanguageCode): if one of these
 * assertions ever fails, persisted user prefs (saved video/audio/subtitle picks) break.
 */
public class ExoFormatItemSerializationTest {

    // ------------------------------------------------------------------
    // from(String) <-> toString() round-trips
    // ------------------------------------------------------------------

    @Test
    public void videoSpecRoundTripsWithLegacyQuirks() {
        // Legacy quirk: the video factory (Format.createVideoSampleFormat) received neither the
        // parsed language nor the parsed bitrate, so both collapse to their defaults on restore.
        String in = "0,0,137,avc1.640028,1920,1080,30.0,en,false,-1,false";
        assertEquals("0,0,137,avc1.640028,1920,1080,30.0,null,false,-1,false",
                ExoFormatItem.from(in).toString());
    }

    @Test
    public void persistedVideoSpecIsIdentity() {
        // What toString() actually persisted for video always had language=null: identity.
        String persisted = "0,0,137,avc1.640028,1920,1080,30.0,null,false,-1,false";
        assertEquals(persisted, ExoFormatItem.from(persisted).toString());
    }

    @Test
    public void persistedAudioSpecIsIdentity() {
        String persisted = "1,1,251,opus,-1,-1,-1.0,english original,false,145000,false";
        assertEquals(persisted, ExoFormatItem.from(persisted).toString());
    }

    @Test
    public void persistedDrcAudioSpecIsIdentity() {
        String persisted = "1,1,140-drc,mp4a.40.2,-1,-1,-1.0,english original,false,129000,true";
        assertEquals(persisted, ExoFormatItem.from(persisted).toString());
    }

    @Test
    public void persistedSubtitleSpecIsIdentity() {
        String persisted = "2,2,ru,null,-1,-1,-1.0,ru,false,-1,false";
        assertEquals(persisted, ExoFormatItem.from(persisted).toString());
    }

    @Test
    public void audioLanguageIsNormalizedLikeLegacyExoFormat() {
        // The legacy exo2 Format constructor normalized the language (BCP-47 lowercase);
        // a pref written by a pre-normalization version restores normalized.
        String in = "1,1,251,opus,-1,-1,-1.0,en-US,false,145000,false";
        assertEquals("1,1,251,opus,-1,-1,-1.0,en-us,false,145000,false",
                ExoFormatItem.from(in).toString());
    }

    @Test
    public void disabledTrackSentinelRoundTrips() {
        // Empty id+codecs = the audio/video "disabled" sentinel (format stays null).
        String persisted = "1,1,,,-1,-1,-1.0,,false,-1,false";
        assertEquals(persisted, ExoFormatItem.from(persisted).toString());
    }

    // ------------------------------------------------------------------
    // Legacy short-spec upgrade path
    // ------------------------------------------------------------------

    @Test
    public void tenFieldLegacySpecGainsIsDrcFalse() {
        String in = "0,0,137,avc1.640028,1920,1080,30.0,null,false,-1";
        assertEquals("0,0,137,avc1.640028,1920,1080,30.0,null,false,-1,false",
                ExoFormatItem.from(in).toString());
    }

    @Test
    public void nineFieldLegacySpecReturnsNullAsInLegacyCode() {
        // Legacy quirk (else-if chain): a 9-field spec is padded to 10 fields and then REJECTED
        // by the length==11 gate. PlayerData falls back to the default format - keep that intact.
        assertNull(ExoFormatItem.from("0,0,137,avc1.640028,1920,1080,30.0,null,false"));
    }

    @Test
    public void malformedSpecReturnsNull() {
        assertNull(ExoFormatItem.from("0,0,137"));
        assertNull(ExoFormatItem.from((String) null));
    }

    // ------------------------------------------------------------------
    // Static FormatItem constants (persisted verbatim when they are the active pick)
    // ------------------------------------------------------------------

    @Test
    public void staticFormatConstantsKeepLegacyStrings() {
        assertEquals("0,0,null,avc,1920,1080,30.0,null,false,-1,false", FormatItem.VIDEO_FHD_AVC_30.toString());
        assertEquals("0,0,null,avc,1920,1080,60.0,null,false,-1,false", FormatItem.VIDEO_FHD_AVC_60.toString());
        assertEquals("0,0,null,vp9,1920,1080,60.0,null,false,-1,false", FormatItem.VIDEO_FHD_VP9_60.toString());
        assertEquals("0,0,null,vp9,3840,2160,60.0,null,false,-1,false", FormatItem.VIDEO_4K_VP9_60.toString());
        assertEquals("0,0,null,avc,1280,720,30.0,null,false,-1,false", FormatItem.VIDEO_HD_AVC_30.toString());
        assertEquals("0,0,null,avc,640,360,30.0,null,false,-1,false", FormatItem.VIDEO_SD_AVC_30.toString());
        assertEquals("2,2,null,null,-1,-1,-1.0,null,false,-1,false", FormatItem.SUBTITLE_NONE.toString());
        assertEquals("1,1,null,mp4a,-1,-1,-1.0,null,false,-1,false", FormatItem.AUDIO_HQ_MP4A.toString());
        assertEquals("1,1,null,ac-3,-1,-1,-1.0,null,false,-1,false", FormatItem.AUDIO_51_AC3.toString());
        assertEquals("0,0,null,null,2147483647,2147483647,30.0,null,false,-1,false", FormatItem.VIDEO_AUTO.toString());
        assertEquals("0,0,,,-1,-1,-1.0,,false,-1,false", FormatItem.NO_VIDEO.toString());
    }

    @Test
    public void staticConstantsRoundTripThroughPersistence() {
        for (FormatItem item : new FormatItem[]{
                FormatItem.VIDEO_FHD_AVC_30, FormatItem.VIDEO_FHD_VP9_60, FormatItem.VIDEO_4K_VP9_60,
                FormatItem.AUDIO_HQ_MP4A, FormatItem.AUDIO_51_AC3}) {
            String persisted = item.toString();
            assertEquals(persisted, ExoFormatItem.from(persisted).toString());
        }
    }

    @Test
    public void subtitleNoneConvergesToSentinelFormLikeLegacyCode() {
        // Legacy quirk: SUBTITLE_NONE serializes id/codecs as "null,null"; on restore both parse
        // to null -> the empty-id+codecs "disabled" sentinel -> re-serializes as ",," (empty).
        // That sentinel form is then a fixed point. Same two-step convergence as the legacy code.
        String first = ExoFormatItem.from(FormatItem.SUBTITLE_NONE.toString()).toString();
        assertEquals("2,2,,,-1,-1,-1.0,,false,-1,false", first);
        assertEquals(first, ExoFormatItem.from(first).toString());
    }

    // ------------------------------------------------------------------
    // Language normalization replica (exo2 Util.normalizeLanguageCode)
    // ------------------------------------------------------------------

    @Test
    public void languageNormalizationMatchesLegacyExoBehavior() {
        assertNull(TrackFormat.normalizeLanguage(null));
        assertEquals("en", TrackFormat.normalizeLanguage("en"));
        assertEquals("en-us", TrackFormat.normalizeLanguage("en-US"));
        assertEquals("en-us", TrackFormat.normalizeLanguage("en_US"));
        assertEquals("en", TrackFormat.normalizeLanguage("eng")); // ISO 639-2/T -> ISO 639-1
        assertEquals("de", TrackFormat.normalizeLanguage("ger")); // ISO 639-2/B -> ISO 639-1
        // YouTube's human-readable dub labels aren't valid tags: kept, lowercased.
        assertEquals("english original", TrackFormat.normalizeLanguage("English original"));
        assertEquals("fil", TrackFormat.normalizeLanguage("fil")); // no 2-letter code: unchanged
    }
}
