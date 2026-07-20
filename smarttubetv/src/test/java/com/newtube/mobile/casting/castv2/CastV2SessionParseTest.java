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
 * RECEIVER_STATUS parsing: the launch flow hinges on pulling the right app's
 * sessionId/transportId out of a status broadcast (which may list several apps, or none).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
// conscrypt-android on the app classpath breaks Robolectric's crypto setup; not needed here.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CastV2SessionParseTest {

    @Test
    public void extractsSessionAndTransportIdForMatchingApp() throws Exception {
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"RECEIVER_STATUS\",\"requestId\":2,"
                + "\"status\":{"
                + "  \"applications\":["
                + "    {\"appId\":\"E8C28D3C\",\"displayName\":\"Backdrop\","
                + "     \"sessionId\":\"aaa\",\"transportId\":\"backdrop-transport\"},"
                + "    {\"appId\":\"CC1AD845\",\"displayName\":\"Default Media Receiver\","
                + "     \"sessionId\":\"7E2FF513-CB85-4B87-ADCB-CF01F1F81DC5\","
                + "     \"transportId\":\"web-5\"}"
                + "  ],"
                + "  \"volume\":{\"level\":0.6,\"muted\":false}"
                + "}}");

        CastV2Session.AppSession app = CastV2Session.findApp(payload, "CC1AD845");

        assertNotNull(app);
        assertEquals("7E2FF513-CB85-4B87-ADCB-CF01F1F81DC5", app.sessionId);
        assertEquals("web-5", app.transportId);
    }

    @Test
    public void returnsNullWhenAppNotInStatus() throws Exception {
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"RECEIVER_STATUS\","
                + "\"status\":{\"applications\":["
                + "  {\"appId\":\"E8C28D3C\",\"sessionId\":\"aaa\",\"transportId\":\"t\"}"
                + "]}}");
        assertNull(CastV2Session.findApp(payload, "CC1AD845"));
    }

    @Test
    public void returnsNullWhenNoApplicationsArray() throws Exception {
        // Idle receivers broadcast statuses with only volume - no applications key at all.
        JSONObject payload = new JSONObject(
                "{\"type\":\"RECEIVER_STATUS\",\"status\":{\"volume\":{\"level\":1}}}");
        assertNull(CastV2Session.findApp(payload, "CC1AD845"));
    }

    @Test
    public void returnsNullWhenStatusMissing() throws Exception {
        assertNull(CastV2Session.findApp(new JSONObject("{\"type\":\"RECEIVER_STATUS\"}"), "CC1AD845"));
    }

    @Test
    public void returnsNullWhileAppHasNoTransportIdYet() throws Exception {
        // A still-starting app can appear without a transportId; connecting to "" would wedge.
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"RECEIVER_STATUS\","
                + "\"status\":{\"applications\":[{\"appId\":\"CC1AD845\",\"sessionId\":\"s\"}]}}");
        assertNull(CastV2Session.findApp(payload, "CC1AD845"));
    }
}
