package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.media3.exoplayer.dash.manifest.DashManifest;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(open-cpu): the recorded-events manifest (DirectMpd) must be the manifest the printed
 * and parsed XML gives: same pull events as KXmlParser reports for the text, and the same parsed
 * DashManifest, on real /player responses and on variants that reach the other branches
 * (past-live SegmentTemplate, dubbed/DRC audio sets, escaped caption names), while anything the
 * text round trip could alter is declined.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DirectMpdEquivalenceTest {
    private static final Uri BASE = Uri.parse("https://youtube.com/generated.mpd");
    private static final File FIXTURES =
            new File("../MediaServiceCore/youtubeapi/src/test/resources/video_info/player_2026_09");

    @Test
    public void realResponsesGiveTheSameEventsAndManifest() throws Exception {
        File[] files = FIXTURES.listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull("fixtures at " + FIXTURES.getAbsolutePath(), files);
        assertTrue(files.length >= 4);
        for (File file : files) {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertEquivalent(file.getName(), info(file.getName(), json));
        }
    }

    @Test
    public void pastLiveDubbedAndEscapedVariantsGiveTheSameManifest() throws Exception {
        String json = new String(Files.readAllBytes(new File(FIXTURES, "visionos_dQw4w9WgXcQ.json")
                .toPath()), StandardCharsets.UTF_8);

        // Past live stream: live=1 media urls make the builder write a dynamic MPD with
        // SegmentTemplate/SegmentTimeline, which the static parser then forces static.
        assertEquivalent("past-live", info("past-live",
                json.replace("videoplayback?expire", "videoplayback?live=1&expire")));

        // Dubbed and original audio tracks: label, lang and Role per language set.
        JSONObject root = new JSONObject(json);
        JSONArray formats = root.getJSONObject("streamingData").getJSONArray("adaptiveFormats");
        String[] tags = {"acont%3Doriginal%3Alang%3Den", "acont%3Ddubbed-auto%3Alang%3Des-419",
                "acont%3Ddescriptive%3Alang%3Den", "acont%3Dsecondary%3Alang%3Dfr"};
        int audio = 0;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.getJSONObject(i);
            if (format.getString("mimeType").startsWith("audio/")) {
                format.put("url", format.getString("url") + "&xtags=" + tags[audio++ % tags.length]);
            }
        }
        // Caption names with every character XML has to escape, and non-ASCII.
        JSONArray tracks = root.getJSONObject("captions").getJSONObject("playerCaptionsTracklistRenderer")
                .getJSONArray("captionTracks");
        tracks.getJSONObject(0).put("name", new JSONObject().put("simpleText", "A & B <c> \"d\" 'e' ñ ç ü"));
        assertEquivalent("dubbed+escaped", info("dubbed", root.toString()));
    }

    @Test
    public void textTheXmlRoundTripCouldAlterIsDeclined() throws Exception {
        String json = new String(Files.readAllBytes(new File(FIXTURES, "visionos_gFM-BL_0YvI.json")
                .toPath()), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
        root.getJSONObject("captions").getJSONObject("playerCaptionsTracklistRenderer")
                .getJSONArray("captionTracks").getJSONObject(0)
                .put("name", new JSONObject().put("simpleText", "tab\there"));
        assertNull(DirectMpd.record(info("tab", root.toString())));

        JSONObject emoji = new JSONObject(json);
        emoji.getJSONObject("captions").getJSONObject("playerCaptionsTracklistRenderer")
                .getJSONArray("captionTracks").getJSONObject(0)
                .put("name", new JSONObject().put("simpleText", "flag 🇪🇸"));
        assertNull(DirectMpd.record(info("emoji", emoji.toString())));
    }

    @Test
    public void manifestsTheTextRouteRejectsFailTheDirectRouteToo() throws Exception {
        // bitrate 0 is printed as bandwidth="" -> Integer.parseInt("") in media3, on both routes.
        String json = new String(Files.readAllBytes(new File(FIXTURES, "visionos_aqz-KE-bpKQ.json")
                .toPath()), StandardCharsets.UTF_8).replace("\"bitrate\":", "\"bitrateGone\":");
        MediaItemFormatInfo info = info("no-bitrate", json);
        String xml = describeOrError(() -> parseXml(info));
        String direct = describeOrError(() -> parseDirect(info));
        assertTrue(xml, xml.startsWith("error "));
        assertEquals(xml, direct);
    }

    private static void assertEquivalent(String label, MediaItemFormatInfo info) throws Exception {
        assertEquals(label + " events", xmlEvents(info), directEvents(info));
        String xml = describeOrError(() -> parseXml(info));
        String direct = describeOrError(() -> parseDirect(info));
        assertTrue(label + ": " + xml, xml.startsWith("mpd "));
        assertEquals(label + " manifest", xml, direct);
    }

    private interface ManifestSource {
        Object get() throws Exception;
    }

    private static String describeOrError(ManifestSource source) {
        try {
            Object result = source.get();
            if (!(result instanceof Object[])) {
                return "null";
            }
            Object[] parsed = (Object[]) result;
            return SourceBuildCpuBenchTest.describe((DashManifest) parsed[0]) + " wasDynamic=" + parsed[1];
        } catch (Exception e) {
            return "error " + e.getClass().getName();
        }
    }

    private static Object[] parseXml(MediaItemFormatInfo info) throws Exception {
        InputStream mpd = info.createMpdStream();
        Media3SourceFactory.StaticDashManifestParser parser = new Media3SourceFactory.StaticDashManifestParser();
        DashManifest manifest = parser.parse(BASE, mpd);
        return new Object[]{manifest, parser.wasDynamic()};
    }

    private static Object[] parseDirect(MediaItemFormatInfo info) throws Exception {
        DirectMpd.Recorder recorder = DirectMpd.record(info);
        assertNotNull("declined", recorder);
        Media3SourceFactory.StaticDashManifestParser parser = new Media3SourceFactory.StaticDashManifestParser();
        DashManifest manifest = parser.parse(recorder.replay(), BASE);
        return new Object[]{manifest, parser.wasDynamic()};
    }

    /** What KXmlParser reports for the printed document, indentation whitespace left out. */
    private static List<String> xmlEvents(MediaItemFormatInfo info) throws Exception {
        XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
        xpp.setInput(info.createMpdStream(), null);
        return events(xpp, true);
    }

    private static List<String> directEvents(MediaItemFormatInfo info) throws Exception {
        DirectMpd.Recorder recorder = DirectMpd.record(info);
        assertNotNull("declined", recorder);
        return events(recorder.replay(), false);
    }

    private static List<String> events(XmlPullParser xpp, boolean skipIndentation) throws Exception {
        List<String> events = new ArrayList<>();
        int type;
        while ((type = xpp.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.TEXT) {
                if (skipIndentation && xpp.isWhitespace()) {
                    continue;
                }
                events.add("text " + xpp.getText() + " name=" + xpp.getName() + " depth=" + xpp.getDepth());
            } else if (type == XmlPullParser.START_TAG) {
                StringBuilder event = new StringBuilder("start " + xpp.getName() + " ns=" + xpp.getNamespace()
                        + " depth=" + xpp.getDepth() + " text=" + xpp.getText());
                for (int i = 0; i < xpp.getAttributeCount(); i++) {
                    event.append(" [").append(xpp.getAttributeNamespace(i)).append('|')
                            .append(xpp.getAttributeName(i)).append('=').append(xpp.getAttributeValue(i))
                            .append(" lookup=").append(xpp.getAttributeValue(null, xpp.getAttributeName(i)))
                            .append(']');
                }
                events.add(event.toString());
            } else if (type == XmlPullParser.END_TAG) {
                events.add("end " + xpp.getName() + " depth=" + xpp.getDepth() + " text=" + xpp.getText());
            } else {
                events.add("other " + type);
            }
        }
        return events;
    }

    private static MediaItemFormatInfo info(String name, String json) throws Exception {
        // Retrofit/JsonPath are not on this module's compile classpath: the real converter,
        // reflectively (JsonPathConverterFactory -> JsonPathResponseBodyConverter.convert).
        Class<?> factoryType = Class.forName(
                "com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory");
        Object factory = factoryType.getMethod("create").invoke(null);
        Method bodyConverter = null;
        for (Method m : factoryType.getMethods()) {
            if (m.getName().equals("responseBodyConverter")) bodyConverter = m;
        }
        Object converter = bodyConverter.invoke(factory, VideoInfo.class, null, null);
        VideoInfo info = (VideoInfo) converter.getClass().getMethod("convert", InputStream.class)
                .invoke(converter, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        info.setClient(name.startsWith("tvhtml5") ? AppClient.TV_TIZEN : AppClient.VISIONOS);
        return YouTubeMediaItemFormatInfo.from(info);
    }
}
