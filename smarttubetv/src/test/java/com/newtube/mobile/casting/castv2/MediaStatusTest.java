package com.newtube.mobile.casting.castv2;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * MEDIA_STATUS parsing: seconds-to-ms happens here and nowhere else, empty status arrays are a
 * legitimate receiver answer, and missing fields degrade to -1/null instead of throwing.
 * Robolectric supplies the real org.json (the unit-test android.jar stubs it).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
// The app depends on conscrypt-android (Cronet path), whose JNI-only classes shadow Robolectric's
// JVM conscrypt and crash environment setup with UnsatisfiedLinkError - these tests do no crypto.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaStatusTest {

    @Test
    public void parsesPlayingStatusAndConvertsSecondsToMs() throws Exception {
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"MEDIA_STATUS\",\"requestId\":3,"
                + "\"status\":[{"
                + "  \"mediaSessionId\":7,"
                + "  \"playerState\":\"PLAYING\","
                + "  \"currentTime\":12.5,"
                + "  \"media\":{\"contentId\":\"http://x/y.mpd\",\"duration\":733.4}"
                + "}]}");

        MediaStatus status = MediaStatus.parseFirst(payload);

        assertNotNull(status);
        assertEquals(7, status.mediaSessionId);
        assertEquals("PLAYING", status.playerState);
        assertEquals(12_500, status.positionMs);
        assertEquals(733_400, status.durationMs);
        assertNull(status.idleReason);
    }

    @Test
    public void emptyStatusArrayReturnsNull() throws Exception {
        JSONObject payload = new JSONObject("{\"type\":\"MEDIA_STATUS\",\"status\":[]}");
        assertNull(MediaStatus.parseFirst(payload));
    }

    @Test
    public void missingStatusArrayReturnsNull() throws Exception {
        JSONObject payload = new JSONObject("{\"type\":\"MEDIA_STATUS\"}");
        assertNull(MediaStatus.parseFirst(payload));
    }

    @Test
    public void missingDurationAndPositionDegradeToMinusOne() throws Exception {
        // Pre-buffer statuses often carry no currentTime and a media block without duration.
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"MEDIA_STATUS\","
                + "\"status\":[{\"mediaSessionId\":1,\"playerState\":\"BUFFERING\","
                + "\"media\":{\"contentId\":\"http://x\"}}]}");

        MediaStatus status = MediaStatus.parseFirst(payload);

        assertNotNull(status);
        assertEquals("BUFFERING", status.playerState);
        assertEquals(-1, status.positionMs);
        assertEquals(-1, status.durationMs);
    }

    @Test
    public void missingMediaSessionIdIsMinusOne() throws Exception {
        JSONObject payload = new JSONObject(
                "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"playerState\":\"IDLE\"}]}");
        MediaStatus status = MediaStatus.parseFirst(payload);
        assertNotNull(status);
        assertEquals(-1, status.mediaSessionId);
    }

    @Test
    public void idleReasonIsParsed() throws Exception {
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"MEDIA_STATUS\","
                + "\"status\":[{\"mediaSessionId\":2,\"playerState\":\"IDLE\","
                + "\"currentTime\":0,\"idleReason\":\"FINISHED\"}]}");

        MediaStatus status = MediaStatus.parseFirst(payload);

        assertNotNull(status);
        assertEquals("IDLE", status.playerState);
        assertEquals("FINISHED", status.idleReason);
        assertEquals(0, status.positionMs);
    }

    @Test
    public void fractionalSecondsRoundToNearestMs() {
        assertEquals(1, MediaStatus.secondsToMs(0.0006));
        assertEquals(0, MediaStatus.secondsToMs(0.0004));
        assertEquals(90_061_500, MediaStatus.secondsToMs(90_061.5));
    }
}
