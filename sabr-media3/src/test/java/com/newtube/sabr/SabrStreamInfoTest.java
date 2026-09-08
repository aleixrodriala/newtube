package com.newtube.sabr;

import static org.junit.Assert.*;
import android.app.Application;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.util.List;

/** Exercise metadata construction on the app's minimum Android API, not just the test phone. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 24, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SabrStreamInfoTest {
    private final SabrStreamInfo.Track video = SabrTestData.video();

    @Test public void urlSafeConfigurationWorksOnApi24() {
        assertEquals(1, info("https://fixture.googlevideo.com/videoplayback", "AA", List.of(video)).config.size());
        assertEquals(1, info("https://fixture.googlevideo.com/videoplayback", "AA==", List.of(video)).config.size());
    }
    @Test public void invalidConfigurationsAreRejectedWithoutEchoingThem() {
        for (String config : new String[]{"", "private!fixture", "A", "AA====", "A A", "AA\n"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> info("https://fixture.googlevideo.com/videoplayback", config, List.of(video)));
            assertFalse(error.getMessage().contains("private!fixture"));
            assertNull(error.getCause());
        }
    }
    @Test public void endpointMustBeTheIssuedHttpsMediaServiceNotAnArbitraryUrl() {
        for (String endpoint : new String[]{"http://fixture.googlevideo.com/videoplayback",
                "https://example.invalid/videoplayback", "https://x.googlevideo.com.example.invalid/videoplayback",
                "https://name:secret@fixture.googlevideo.com/videoplayback",
                "https://fixture.googlevideo.com:8443/videoplayback", "https://fixture.googlevideo.com/other",
                "https://fixture.googlevideo.com/videoplayback#fragment"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> info(endpoint, "AA", List.of(video)));
            assertFalse(error.getMessage().contains(endpoint));
        }
    }
    @Test public void duplicateFullIdentityCannotBeAmbiguous() {
        SabrStreamInfo.Track duplicate = new SabrStreamInfo.Track(video.format, video.identity.getLastModified(),
                video.identity.getXtags(), null, false);
        assertThrows(IllegalArgumentException.class, () -> info(SabrFixtures.ENDPOINT, "AA", List.of(video, duplicate)));
    }
    @Test public void differentFullIdentitiesRemainSeparateEvenAtTheSamePublicItag() {
        SabrStreamInfo.Track other = new SabrStreamInfo.Track(video.format, video.identity.getLastModified() + 1,
                "variant=2", null, false);
        assertEquals(2, info(SabrFixtures.ENDPOINT, "AA", List.of(video, other)).tracks.size());
    }
    @Test public void ordinaryVodIdentityAndDurationAreRequired() {
        for (long duration : new long[]{0, -1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new SabrStreamInfo("sabrfixture", duration,
                    SabrFixtures.ENDPOINT, "AA", "TVHTML5", "fixture", null, null, List.of(video)));
        }
        assertThrows(IllegalArgumentException.class, () -> new SabrStreamInfo("bad", 1000,
                SabrFixtures.ENDPOINT, "AA", "TVHTML5", "fixture", null, null, List.of(video)));
    }
    @Test public void textCannotEnterAContainerExtractorAsAVideoTrack() {
        Format text = new Format.Builder().setId("1").setContainerMimeType(MimeTypes.VIDEO_MP4)
                .setSampleMimeType(MimeTypes.TEXT_VTT).build();
        assertThrows(IllegalArgumentException.class, () -> new SabrStreamInfo.Track(text, 1, null, null, false));
    }
    private SabrStreamInfo info(String endpoint, String config, List<SabrStreamInfo.Track> tracks) {
        return new SabrStreamInfo("sabrfixture", 12_050_000, endpoint, config, "TVHTML5", "fixture", null, null, tracks);
    }
}
