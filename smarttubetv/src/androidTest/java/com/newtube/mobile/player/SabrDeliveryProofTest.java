package com.newtube.mobile.player;

import android.os.Bundle;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.sabrproof.SabrProofGate;
import com.newtube.mobile.sabrproof.SabrProofProtocol;
import com.newtube.mobile.sabrproof.SabrProofProtocol.Inspection;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Explicitly opted-in, single-session delivery gate. NOT a playback/TTFF benchmark. */
@RunWith(AndroidJUnit4.class)
public class SabrDeliveryProofTest {
    private static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

    @Test
    public void existingSessionDeliversSabrMedia() {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Live proof requires explicit opt-in",
                "true".equals(args.getString("allow_network_proof")));
        String videoId = args.getString("video_id");
        require(videoId != null && videoId.matches("[A-Za-z0-9_-]{11}"), "invalid-video-id");
        boolean previousCapability = SabrVodCapability.isEnabled();
        SabrVodCapability.setEnabled(true);
        try {
            runProof(videoId);
        } catch (Exception failure) {
            // Exception messages/causes may contain signed URLs or response bodies.
            throw new AssertionError("SABR proof stopped: " + failure.getClass().getSimpleName());
        } finally {
            SabrVodCapability.setEnabled(previousCapability);
        }
    }

    private static void runProof(String videoId) throws Exception {
        ConnectivityManager connectivity = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSystemService(ConnectivityManager.class);
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        require(network != null && capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN), "unvalidated-or-vpn-network");
        String actualTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "other";
        String expectedTransport = InstrumentationRegistry.getArguments().getString("expected_transport");
        require(expectedTransport == null || expectedTransport.equals(actualTransport), "wrong-network");
        report("network", "transport=" + actualTransport + " validated=true vpn=false");
        SessionWarmup.onPlaybackRequested();
        require("io.github.aleixrodriala.arc".equals(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName()), "wrong-target-package");
        // Normal app startup does this in SplashPresenter, which headless instrumentation skips.
        // Initialize only this app's preferences; do not copy or expose persisted account data.
        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().getTargetContext());
        YouTubeSignInService signIn = YouTubeSignInService.instance();
        // GlobalPreferences.setOnInit restores accounts on a separate thread, even if the
        // preferences are already initialized. Do not mistake that startup race for sign-out.
        long accountDeadline = SystemClock.elapsedRealtime() + 5_000;
        while (signIn.getSelectedAccount() == null && SystemClock.elapsedRealtime() < accountDeadline) {
            SystemClock.sleep(25);
        }
        require(signIn.getSelectedAccount() != null, "no-existing-account");
        signIn.checkAuth(); // Restore/refresh this app's selected account normally; no new identity.
        long metadataStart = SystemClock.elapsedRealtime();
        // The raw authenticated metadata entry point omits normal playback URL preparation.
        // Use the same complete metadata pipeline as playback, with one declared TV client.
        VideoInfo info = SabrNetworkComparisonTest.preparedTvMetadata(videoId);
        require(info != null, "no-metadata");
        boolean rawOk = "OK".equals(info.getRawPlayabilityStatus());
        boolean serverAuth = Boolean.TRUE.equals(info.isServerLoggedIn());
        report("metadata", "rawOk=" + rawOk + " serverAuth=" + serverAuth
                + " botCheck=" + info.isBotCheckRequired()
                + " normalPlaybackMetadataPipeline=true elapsedMs=" + (SystemClock.elapsedRealtime() - metadataStart));
        require(rawOk && !info.isBotCheckRequired(), "metadata-denied");
        require(serverAuth && info.getClient() == AppClient.TV, "session-not-confirmed");
        require(info.getVideoDetails() != null
                && videoId.equals(info.getVideoDetails().getVideoId()), "video-identity-mismatch");
        require(!info.isLive() && !info.getVideoDetails().isLiveContent(), "vod-only-proof");
        require(info.getVideoPlaybackUstreamerConfig() != null
                && !info.getVideoPlaybackUstreamerConfig().isEmpty(), "no-sabr-config");
        require(SabrProofGate.accepts(info.getRawPlayabilityStatus(), info.isBotCheckRequired(),
                info.isServerLoggedIn(), videoId, info.getVideoDetails().getVideoId(),
                info.isLive(), info.getVideoDetails().isLiveContent(),
                info.getServerAbrStreamingUrl() != null && !info.getServerAbrStreamingUrl().isEmpty(),
                true, info.getClient() == AppClient.TV), "delivery-gate-rejected");

        // Use the ordinary playback pipeline's URL as-is, apart from request numbering.
        // No diagnostic URL rewrite, alternate client or player/decoder is involved in the POST.
        String issuedUrl = info.getServerAbrStreamingUrl();
        HttpUrl endpoint = issuedUrl == null ? null : HttpUrl.parse(issuedUrl);
        require(endpoint != null && endpoint.isHttps()
                && endpoint.host().endsWith(".googlevideo.com")
                && endpoint.username().isEmpty() && endpoint.password().isEmpty(), "invalid-media-endpoint");
        VideoFormat audio = selectUnique(info.getAdaptiveFormats(), new int[]{140}, "audio/mp4");
        VideoFormat video = selectUnique(info.getAdaptiveFormats(), new int[]{136, 135, 134}, "video/mp4");
        require(audio != null && video != null, "no-unambiguous-aac-avc-pair");
        require(video.getMimeType().contains("avc1"), "avc-required");
        FormatId audioId = identity(audio);
        FormatId videoFormatId = identity(video);
        StreamerContext.ClientInfo clientInfo = StreamerContext.ClientInfo.newBuilder()
                .setClientName(StreamerContext.ClientName.valueOf(info.getClient().getClientName()))
                .setClientVersion(info.getClient().getClientVersion()).build();
        Field userAgent = Media3SourceFactory.class.getDeclaredField("USER_AGENT");
        userAgent.setAccessible(true); // Non-secret constant: use precisely the app's normal media UA.
        OkHttpClient transport = MediaHttpClient.create(OkHttpManager.instance().getClient()).newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS).eventListener(EventListener.NONE).build();
        report("selection", "audioItag=" + audio.getITag() + " videoItag=" + video.getITag()
                + " height=" + video.getHeight() + " preparedPlaybackUrl=true");
        // Observe freshness only; never edit expiry or other authorization parameters.
        String expiry = endpoint.queryParameter("expire");
        long remainingSeconds = expiry != null && expiry.matches("[0-9]{1,12}")
                ? Long.parseLong(expiry) - System.currentTimeMillis() / 1000 : Long.MIN_VALUE;
        report("endpoint", "expiryKnown=" + (remainingSeconds != Long.MIN_VALUE)
                + " unexpired=" + (remainingSeconds > 0)
                + " networkUnchanged=" + network.equals(connectivity.getActiveNetwork()));
        require(remainingSeconds == Long.MIN_VALUE || remainingSeconds > 0, "expired-media-url");
        require(network.equals(connectivity.getActiveNetwork()), "network-changed-after-metadata");

        Inspection audioResult = request(transport, endpoint, (String) userAgent.get(null), videoId,
                info.getVideoPlaybackUstreamerConfig(), clientInfo, audioId, videoFormatId,
                false, -1, audio.getBitrate(), 1);
        // No second request after any protocol/HTTP denial, redirect, reload or other stop.
        require(audioResult.hasCompleteMedia() && audioResult.completedAudioSegments > 0
                        && audioResult.audioInitBytes > 0 && audioResult.audioMediaBytes > 0,
                "audio-init-or-media-not-delivered");
        Inspection videoResult = request(transport, endpoint, (String) userAgent.get(null), videoId,
                info.getVideoPlaybackUstreamerConfig(), clientInfo, audioId, videoFormatId,
                true, video.getHeight(), video.getBitrate(), 2);
        require(videoResult.hasCompleteMedia() && videoResult.completedVideoSegments > 0
                        && videoResult.videoInitBytes > 0 && videoResult.videoMediaBytes > 0,
                "video-init-or-media-not-delivered");
        SabrProofDecoder.Result decodedAudio = SabrProofDecoder.decode(audioResult.getAudioBytes(), false);
        SabrProofDecoder.Result decodedVideo = SabrProofDecoder.decode(videoResult.getVideoBytes(), true);
        report("decoded", "audioOutputs=" + decodedAudio.outputBufferCount + " videoOutputs="
                + decodedVideo.outputBufferCount + " localVideoFirstOutputMs=" + decodedVideo.firstOutputMs);
        require(decodedAudio.sampleCount > 0 && decodedVideo.sampleCount > 0
                && decodedAudio.outputBufferCount > 0 && decodedVideo.outputBufferCount > 0, "no-decoded-output");
        report("delivery-proof-passed", "rendered=false sustained=false ttffMeasured=false");
    }

    private static Inspection request(OkHttpClient transport, HttpUrl endpoint, String userAgent,
            String videoId, String config, StreamerContext.ClientInfo clientInfo,
            FormatId audio, FormatId video, boolean videoRequest, int height, long bitrate,
            int requestNumber) throws Exception {
        byte[] payload = SabrProofProtocol.buildInitialRequest(config, clientInfo, audio, video,
                videoRequest, height, bitrate);
        Request request = new Request.Builder()
                .url(endpoint.newBuilder().setQueryParameter("rn", Integer.toString(requestNumber)).build())
                .header("User-Agent", userAgent).header("Accept", "application/vnd.yt-ump")
                .post(RequestBody.create(MediaType.parse("application/x-protobuf"), payload)).build();
        String phase = videoRequest ? "video" : "audio";
        long start = SystemClock.elapsedRealtime();
        try (Response response = transport.newCall(request).execute()) {
            String type = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            // Fixed categories/counts only: signed URLs, headers and response bodies stay private.
            String mime = type.startsWith("application/vnd.yt-ump") ? "ump"
                    : type.startsWith("text/") ? "text" : type.isEmpty() ? "missing" : "other";
            report(phase + "-http", "status=" + response.code()
                    + " headersMs=" + (SystemClock.elapsedRealtime() - start)
                    + " protocol=" + response.protocol() + " tls=" + (response.handshake() != null)
                    + " mime=" + mime + " declaredBodyBytes="
                    + (response.body() == null ? -1 : response.body().contentLength())
                    + " requestBytes=" + payload.length);
            require(response.code() == 200, phase + "-http-stop-" + response.code());
            require(response.body() != null, "missing-response-body");
            require(type.toLowerCase(java.util.Locale.ROOT).startsWith("application/vnd.yt-ump"),
                    "unexpected-content-type");
            Inspection result = SabrProofProtocol.inspect(response.body().byteStream(), videoId,
                    audio, video, MAX_RESPONSE_BYTES);
            report(phase + "-ump", "bytes=" + result.responseBytes + " parts=" + result.parts
                    + " initializations=" + result.formatInitializations + " headers=" + result.mediaHeaders
                    + " audioInit=" + result.audioInitBytes + " audioMedia=" + result.audioMediaBytes
                    + " videoInit=" + result.videoInitBytes + " videoMedia=" + result.videoMediaBytes
                    + " protection=" + result.protectionStatus + " errorCode=" + result.protocolErrorCode
                    + " stop=" + result.stopReason + " totalMs=" + (SystemClock.elapsedRealtime() - start));
            require("eof".equals(result.stopReason), phase + "-protocol-stop");
            return result;
        }
    }

    private static VideoFormat selectUnique(List<? extends VideoFormat> formats, int[] preference, String mime) {
        if (formats == null) return null;
        for (int itag : preference) {
            VideoFormat selected = null;
            int matches = 0;
            for (VideoFormat format : formats) {
                if (format.getITag() == itag && !format.isDrc()
                        && format.getMimeType() != null && format.getMimeType().startsWith(mime)) {
                    selected = format;
                    matches++;
                }
            }
            if (matches == 1) return selected;
        }
        return null;
    }

    private static FormatId identity(VideoFormat format) {
        require(format.getLastModified() != null, "missing-format-identity");
        FormatId.Builder id = FormatId.newBuilder().setItag(format.getITag())
                .setLastModified(Long.parseLong(format.getLastModified()));
        if (format.getXtags() != null) id.setXtags(format.getXtags());
        return id.build();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError("SABR proof stopped: " + reason);
    }

    private static void report(String phase, String safeMetrics) {
        Bundle metrics = new Bundle();
        metrics.putString("sabrPhase", phase);
        metrics.putString("sabrMetrics", safeMetrics);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }
}
