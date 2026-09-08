package com.newtube.sabr;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.protobuf.ByteString;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.newtube.mobile.SessionWarmup;
import com.newtube.sabr.proto.videostreaming.MediaHeader;
import com.newtube.sabr.proto.videostreaming.ReloadPlayerResponse;
import com.newtube.sabr.ump.UMPDecoder;
import com.newtube.sabr.ump.UMPPart;
import com.newtube.sabr.ump.UMPPartId;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Diagnostic only - asserts nothing about delivery. Runs the app's OWN request builder and media
 * transport against the endpoint the ordinary metadata pipeline issued, then reports the reply's
 * UMP part census. Exists to find why an on-device SABR POST answers RELOAD_PLAYER_RESPONSE while
 * the identical client, config and body succeed off-device (HANDOFF section 28).
 *
 * <p>Reports structure and sizes only: no signed URL, no configuration bytes, no reload token
 * value ever reaches the log.</p>
 */
@RunWith(AndroidJUnit4.class)
public class SabrReloadDiagnosticTest {
    private static final String TAG = "SabrDiag";

    @Test
    public void reportFirstSabrExchange() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Live diagnostic requires explicit opt-in",
                "true".equals(args.getString("allow_network_proof")));
        String videoId = args.getString("video_id");
        String client = args.getString("client");
        long positionMs = parse(args.getString("position_ms"));
        Assume.assumeTrue("invalid video id", videoId != null && videoId.matches("[A-Za-z0-9_-]{11}"));
        Assume.assumeTrue("invalid client", client != null && client.matches("[A-Z_]{2,20}"));

        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().getTargetContext());
        SessionWarmup.onPlaybackRequested();
        boolean previous = SabrVodCapability.isEnabled();
        SabrVodCapability.setEnabled(true);
        try {
            VideoInfo info;
            VideoInfoService.setDebugForcedClient(client);
            try {
                info = VideoInfoService.instance().getVideoInfo(videoId, null, null);
            } finally {
                VideoInfoService.setDebugForcedClient(null);
            }
            if (info == null) {
                Log.i(TAG, "metadata=null client=" + client);
                return;
            }
            MediaItemFormatInfo formatInfo = YouTubeMediaItemFormatInfo.from(info);
            Log.i(TAG, "metadata client=" + info.getClient()
                    + " status=" + info.getRawPlayabilityStatus()
                    + " auth=" + info.isAuth()
                    + " serverLoggedIn=" + info.isServerLoggedIn()
                    + " botCheck=" + info.isBotCheckRequired()
                    + " formats=" + (info.getAdaptiveFormats() == null ? -1 : info.getAdaptiveFormats().size())
                    + " sabrEligible=" + formatInfo.isSabrVodEligible()
                    + " sabrFormats=" + formatInfo.containsSabrFormats()
                    + " dashFormats=" + formatInfo.containsDashFormats()
                    + " configChars=" + length(info.getVideoPlaybackUstreamerConfig()));

            SabrStreamInfo stream = adapt(formatInfo);
            SabrStreamInfo.Track video = null;
            SabrStreamInfo.Track audio = null;
            for (SabrStreamInfo.Track track : stream.tracks) {
                if (video == null && track.type == C.TRACK_TYPE_VIDEO) video = track;
                if (audio == null && track.type == C.TRACK_TYPE_AUDIO) audio = track;
            }
            Log.i(TAG, "adapted tracks=" + stream.tracks.size()
                    + " video=" + (video == null ? "none" : video.format.id + "@" + video.format.height)
                    + " audio=" + (audio == null ? "none" : audio.format.id)
                    + " durationUs=" + stream.durationUs
                    + " endpointHost=" + stream.endpoint.getHost()
                    + " endpointPath=" + stream.endpoint.getPath()
                    + " queryParams=" + countParams(stream.endpoint));
            if (video == null || audio == null) return;

            OkHttpClient transport = mediaTransport();
            String userAgent = mediaUserAgent();

            Log.i(TAG, "startPositionMs=" + positionMs);
            exchange(transport, userAgent, stream, audio, null, 1, positionMs, "audio-first");
            exchange(transport, userAgent, stream, video, audio, 2, positionMs, "video-with-companion");
        } finally {
            SabrVodCapability.setEnabled(previous);
        }
    }

    /** One POST built by the app's own {@link SabrProtocol}, reported as a part census. */
    private static void exchange(OkHttpClient transport, String userAgent, SabrStreamInfo stream,
            SabrStreamInfo.Track track, SabrStreamInfo.Track companion, int requestNumber,
            long positionMs, String label) throws Exception {
        long positionUs = positionMs * 1000;
        SabrProtocol.State state = new SabrProtocol.State(positionUs, stream.durationUs);
        byte[] body = SabrProtocol.request(stream, track, companion, state, positionUs, 1_000_000, 1f);
        Log.i(TAG, label + " request bytes=" + body.length + " fields=" + fieldMap(body));

        Uri uri = SabrMediaSource.numberedUri(stream.endpoint, requestNumber);
        Request request = new Request.Builder().url(uri.toString())
                .header("User-Agent", userAgent)
                .header("Accept", "application/vnd.yt-ump")
                .post(RequestBody.create(MediaType.parse("application/x-protobuf"), body))
                .build();
        try (Response response = transport.newCall(request).execute()) {
            byte[] payload = response.body() == null ? new byte[0] : response.body().bytes();
            Log.i(TAG, label + " http=" + response.code() + " bytes=" + payload.length
                    + " contentType=" + response.header("Content-Type"));
            census(label, payload);
        }
    }

    /**
     * Part id census, plus MEDIA bytes attributed to the itag that asked for them and, for a
     * reload, the shape of the token the server wants echoed back.
     *
     * <p>The per-itag split exists because a fixed-window byte comparison said SABR moved 17.5%
     * fewer bytes than DASH while buffering only 32 s against DASH's 59 s - i.e. ~1.5x more bytes
     * per second of media. This says whether the extra bytes are a track the request did not
     * ask for.</p>
     */
    private static void census(String label, byte[] payload) {
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        Map<Integer, long[]> perItag = new LinkedHashMap<>();
        Map<Long, Integer> headerItag = new java.util.HashMap<>();
        UMPDecoder decoder = new UMPDecoder(32 * 1024 * 1024);
        InputStream input = new java.io.ByteArrayInputStream(payload);
        StringBuilder order = new StringBuilder();
        try {
            UMPPart part;
            while ((part = decoder.decode(input)) != null) {
                byte[] bytes = drain(part.toStream());
                int[] entry = counts.computeIfAbsent(part.partId, key -> new int[2]);
                entry[0]++;
                entry[1] += bytes.length;
                if (order.length() < 240) order.append(part.partId).append(':').append(bytes.length).append(' ');
                if (part.partId == UMPPartId.MEDIA_HEADER) {
                    MediaHeader header = MediaHeader.parseFrom(bytes);
                    int itag = header.getItag() != 0 ? header.getItag() : header.getFormatId().getItag();
                    headerItag.put((long) header.getHeaderId(), itag);
                    long[] slot = perItag.computeIfAbsent(itag, key -> new long[2]);
                    slot[1]++;
                } else if (part.partId == UMPPartId.MEDIA && bytes.length > 0) {
                    // A MEDIA part is prefixed with the header id its bytes belong to.
                    Integer itag = headerItag.get((long) (bytes[0] & 0xFF));
                    perItag.computeIfAbsent(itag == null ? -1 : itag, key -> new long[2])[0]
                            += bytes.length - 1;
                } else if (part.partId == UMPPartId.RELOAD_PLAYER_RESPONSE) {
                    reload(label, bytes);
                }
            }
        } catch (Exception failure) {
            Log.i(TAG, label + " census stopped: " + failure.getClass().getSimpleName());
        }
        StringBuilder split = new StringBuilder();
        for (Map.Entry<Integer, long[]> entry : perItag.entrySet()) {
            split.append("itag").append(entry.getKey()).append('=').append(entry.getValue()[0])
                    .append("B/").append(entry.getValue()[1]).append("hdr ");
        }
        Log.i(TAG, label + " media by itag: " + split);
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            summary.append(entry.getKey()).append("x").append(entry.getValue()[0])
                    .append("(").append(entry.getValue()[1]).append("B) ");
        }
        Log.i(TAG, label + " parts: " + summary);
        Log.i(TAG, label + " order: " + order);
    }

    private static void reload(String label, byte[] bytes) {
        try {
            ReloadPlayerResponse parsed = ReloadPlayerResponse.parseFrom(bytes);
            String token = parsed.hasReloadPlaybackParams()
                    ? parsed.getReloadPlaybackParams().getToken() : null;
            Log.i(TAG, label + " RELOAD payload=" + bytes.length
                    + " hasParams=" + parsed.hasReloadPlaybackParams()
                    + " tokenChars=" + length(token)
                    + " wireFields=" + fieldMap(bytes));
        } catch (Exception failure) {
            Log.i(TAG, label + " RELOAD unparsed=" + bytes.length + " " + failure.getClass().getSimpleName());
        }
    }

    /** Field number -> wire type/length, so a request can be diffed without exposing its bytes. */
    private static String fieldMap(byte[] body) {
        StringBuilder out = new StringBuilder();
        try {
            com.google.protobuf.CodedInputStream in = com.google.protobuf.CodedInputStream.newInstance(body);
            while (true) {
                int tag = in.readTag();
                if (tag == 0) break;
                int number = tag >>> 3;
                int wire = tag & 7;
                out.append(number).append(':');
                if (wire == 2) {
                    ByteString value = in.readBytes();
                    out.append("len").append(value.size());
                } else if (wire == 0) {
                    out.append("v").append(in.readInt64());
                } else if (wire == 5) {
                    out.append("f").append(Float.intBitsToFloat(in.readFixed32()));
                } else {
                    in.skipField(tag);
                    out.append("w").append(wire);
                }
                out.append(' ');
            }
        } catch (Exception failure) {
            out.append("<").append(failure.getClass().getSimpleName()).append(">");
        }
        return out.toString();
    }

    private static SabrStreamInfo adapt(MediaItemFormatInfo info) throws Exception {
        Class<?> adapter = Class.forName("com.newtube.mobile.player.SabrFormatAdapter");
        java.lang.reflect.Method method = adapter.getDeclaredMethod("adapt", MediaItemFormatInfo.class);
        method.setAccessible(true);
        return (SabrStreamInfo) method.invoke(null, info);
    }

    /** Exactly the transport the SABR source uses: shared pool/proxy, no account headers. */
    private static OkHttpClient mediaTransport() throws Exception {
        Class<?> client = Class.forName("com.newtube.mobile.player.MediaHttpClient");
        java.lang.reflect.Method create = client.getDeclaredMethod("create", OkHttpClient.class);
        create.setAccessible(true);
        return ((OkHttpClient) create.invoke(null, OkHttpManager.instance().getClient()))
                .newBuilder().retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS)
                .eventListener(EventListener.NONE).build();
    }

    private static String mediaUserAgent() throws Exception {
        Class<?> factory = Class.forName("com.newtube.mobile.player.Media3SourceFactory");
        Field field = factory.getDeclaredField("USER_AGENT");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static byte[] drain(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static long parse(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static int length(String value) { return value == null ? -1 : value.length(); }

    private static int countParams(Uri uri) {
        String query = uri.getEncodedQuery();
        return query == null || query.isEmpty() ? 0 : query.split("&", -1).length;
    }
}
