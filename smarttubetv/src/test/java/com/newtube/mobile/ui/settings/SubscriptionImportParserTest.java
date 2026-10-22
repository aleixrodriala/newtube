package com.newtube.mobile.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SubscriptionImportParserTest {

    private static final String A = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "UCbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void newPipeJsonKeepsYouTubeChannelsOnce() {
        String json = "{\"app_version\":\"0.27.6\",\"app_version_int\":1001,\"subscriptions\":["
                + "{\"service_id\":0,\"url\":\"https://www.youtube.com/channel/" + A + "\",\"name\":\"Alpha\"},"
                + "{\"service_id\":1,\"url\":\"https://soundcloud.com/someone\",\"name\":\"SoundCloud\"},"
                + "{\"service_id\":5,\"url\":\"https://space.bilibili.com/123\",\"name\":\"BiliBili\"},"
                + "{\"service_id\":0,\"url\":\"https://youtube.com/channel/" + B + "\",\"name\":\"Beta\"},"
                + "{\"service_id\":0,\"url\":\"https://m.youtube.com/channel/" + A + "\",\"name\":\"Alpha again\"}"
                + "]}";

        List<SubscriptionImportParser.Channel> channels = SubscriptionImportParser.parseJson(json);

        assertEquals(2, channels.size());
        assertEquals("https://www.youtube.com/channel/" + A, channels.get(0).url);
        assertEquals("Alpha", channels.get(0).name);
        assertEquals("https://www.youtube.com/channel/" + B, channels.get(1).url);
    }

    @Test
    public void otherJsonIsNotNewPipe() {
        // GrayJay / PocketTube files go to the engine as they are.
        assertNull(SubscriptionImportParser.parseJson("{\"groups\":[]}"));
        assertNull(SubscriptionImportParser.parseJson("[1,2,3]"));
        assertNull(SubscriptionImportParser.parseJson("not json at all {"));
    }

    @Test
    public void channelIdNeedsAYouTubeChannelUrl() {
        assertEquals(A, SubscriptionImportParser.channelId("https://www.youtube.com/channel/" + A));
        assertEquals(A, SubscriptionImportParser.channelId(" https://www.youtube.com/channel/" + A + "/videos "));
        assertNull(SubscriptionImportParser.channelId("https://www.youtube.com/@someone"));
        assertNull(SubscriptionImportParser.channelId("https://www.youtube.com/user/someone"));
        assertNull(SubscriptionImportParser.channelId("https://notyoutube.com/channel/" + A));
        assertNull(SubscriptionImportParser.channelId(null));
    }

    @Test
    public void writesWhatTheEngineReadsBack() {
        List<SubscriptionImportParser.Channel> channels = Arrays.asList(
                new SubscriptionImportParser.Channel("https://www.youtube.com/channel/" + A, "Alpha"),
                new SubscriptionImportParser.Channel("https://www.youtube.com/channel/" + B, null));

        List<SubscriptionImportParser.Channel> back =
                SubscriptionImportParser.parseJson(SubscriptionImportParser.toNewPipeJson(channels));

        assertEquals(2, back.size());
        assertEquals("Alpha", back.get(0).name);
        assertEquals("", back.get(1).name);
    }

    @Test
    public void zipIsToldByItsMagic() {
        assertTrue(SubscriptionImportParser.isZip(new byte[] {'P', 'K', 3, 4}));
        assertFalse(SubscriptionImportParser.isZip("{\"su".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SubscriptionImportParser.isZip(new byte[] {'P', 'K'}));
    }
}
