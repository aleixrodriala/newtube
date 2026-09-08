package com.newtube.mobile.player;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.player.TtffFixtureActivity.Snapshot;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Explicit, bounded network cell. Uses the existing account and either the ordinary format service
 * or the predeclared authenticated TV SABR entry point. Never switches routes after a media failure.
 * TTFF excludes metadata/UI and is source-open-to-render on the same real Media3 engine.
 */
@RunWith(AndroidJUnit4.class)
public class SabrNetworkComparisonTest {
    @Test public void selectedSourceRendersAndSeeksOnTheDeclaredNetwork() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Network comparison requires opt-in", "true".equals(args.getString("allow_network_comparison")));
        String sourceKind = args.getString("source");
        String expectedNetwork = args.getString("expected_network");
        String metadataRoute = args.getString("metadata_route", "default");
        String video = args.getString("video_id");
        assertTrue("source must be sabr or dash", "sabr".equals(sourceKind) || "dash".equals(sourceKind));
        assertTrue("Use a declared metadata route", "default".equals(metadataRoute)
                || ("sabr-tv".equals(metadataRoute) && "sabr".equals(sourceKind)));
        assertTrue("network must be wifi or cellular", "wifi".equals(expectedNetwork) || "cellular".equals(expectedNetwork));
        assertTrue("Invalid video", video != null && video.matches("[A-Za-z0-9_-]{11}"));
        assertEquals("Pin the same transport for comparison", "okhttp", DebugMediaShaper.prop("debug.arc.media_transport"));
        assertEquals("Disable the byte cache, without clearing it", "off", DebugMediaShaper.prop("debug.arc.media_cache"));
        assertEquals("Disable speculative background work", "1", DebugMediaShaper.prop("debug.arc.benchmark_fixture"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean previousCapability = SabrVodCapability.isEnabled();
        boolean sabr = "sabr".equals(sourceKind);
        SabrVodCapability.setEnabled(sabr);
        try (ActivityScenario<TtffFixtureActivity> scenario = ActivityScenario.launch(TtffFixtureActivity.class)) {
            await(scenario, s -> s.initialized, 10000);
            Network network = requireNetwork(context, expectedNetwork, null);
            SessionWarmup.onPlaybackRequested();
            GlobalPreferences.instance(context);
            YouTubeSignInService signIn = YouTubeSignInService.instance();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (signIn.getSelectedAccount() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            assertNotNull("No existing selected account", signIn.getSelectedAccount());
            signIn.checkAuth();
            long metadataStart = SystemClock.elapsedRealtime();
            Bundle metadata = new Bundle();
            MediaItemFormatInfo info;
            if ("sabr-tv".equals(metadataRoute)) {
                // getAuthVideoInfo is a RAW metadata API: it skips the normal playback URL
                // preparation. Use the complete playback pipeline with one declared TV client.
                // The selector cannot walk to another client and is restored before media opens.
                VideoInfo prepared = preparedTvMetadata(video);
                metadata.putBoolean("rawPlayabilityOk", prepared != null && "OK".equals(prepared.getRawPlayabilityStatus()));
                metadata.putBoolean("serverAuthenticated", prepared != null && Boolean.TRUE.equals(prepared.isServerLoggedIn()));
                metadata.putBoolean("normalPlaybackMetadataPipeline", true);
                info = YouTubeMediaItemFormatInfo.from(prepared);
            } else {
                info = YouTubeMediaItemService.instance().getFormatInfo(video);
            }
            metadata.putString("metadataRoute", metadataRoute);
            metadata.putString("source", sourceKind);
            metadata.putString("network", expectedNetwork);
            metadata.putLong("metadataMs", SystemClock.elapsedRealtime() - metadataStart);
            metadata.putBoolean("metadataPresent", info != null);
            metadata.putBoolean("unplayable", info != null && info.isUnplayable());
            metadata.putBoolean("botCheck", info != null && info.isBotCheckRequired());
            metadata.putBoolean("sabrEligible", info != null && SabrFormatAdapter.eligible(info));
            metadata.putBoolean("dashAvailable", info != null && info.containsDashFormats());
            if (info != null && info.getClientInfo() != null) {
                metadata.putString("client", info.getClientInfo().getClientName());
                metadata.putString("clientVersion", info.getClientInfo().getClientVersion());
            }
            report("metadata", metadata);
            assertNotNull("No metadata response from the declared route", info);
            assertEquals("Unexpected video metadata", video, info.getVideoId());
            assertFalse("Metadata denied: no media request attempted", info.isUnplayable() || info.isBotCheckRequired());
            assertFalse("VOD only", info.isLive() || info.isLiveContent());
            assertTrue("Selected source unavailable in declared metadata: no alternate client attempted",
                    sabr ? SabrFormatAdapter.eligible(info) : info.containsDashFormats());
            requireNetwork(context, expectedNetwork, network);
            Media3SourceFactory factory = new Media3SourceFactory(context);
            scenario.onActivity(a -> a.fixturePlayer().setTrackSelectionParameters(a.fixturePlayer()
                    .getTrackSelectionParameters().buildUpon().setMaxVideoSize(1280, 720)
                    .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264).setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC)
                    .setForceHighestSupportedBitrate(true).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()));
            int iterations = Integer.parseInt(args.getString("iterations", "1"));
            assertTrue("Bounded cell: 1 to 3 opens", iterations >= 1 && iterations <= 3);
            for (int iteration = 0; iteration < iterations; iteration++) {
                requireNetwork(context, expectedNetwork, network);
                MediaSource media = sabr ? factory.fromSabrFormatInfo(info) : factory.fromDashFormatInfo(info);
                assertNotNull("Source construction failed", media);
                long rx = TrafficStats.getUidRxBytes(Process.myUid());
                long cpu = Process.getElapsedCpuTime();
                long started = SystemClock.elapsedRealtime();
                String phase = sourceKind + "-" + expectedNetwork + "-" + iteration;
                Bundle opened = new Bundle();
                opened.putString("source", sourceKind);
                opened.putString("network", expectedNetwork);
                opened.putString("metadataRoute", metadataRoute);
                opened.putBoolean("realNetworkSourceConstructed", true);
                report(phase + "-source-open", opened);
                scenario.onActivity(a -> a.openFixtureSource(media, phase));
                try {
                    await(scenario, s -> s.firstFrameMs >= 0 && s.playing && s.frames >= 3, 25000);
                    scenario.onActivity(TtffFixtureActivity::finishExpectedTransition);
                    await(scenario, s -> s.positionMs >= 8000 && s.frames >= 60, 20000);
                    Snapshot state = snapshot(scenario);
                    Bundle metrics = metrics(state, sourceKind, expectedNetwork);
                    metrics.putLong("elapsedMs", SystemClock.elapsedRealtime() - started);
                    metrics.putLong("processCpuMs", Process.getElapsedCpuTime() - cpu);
                    long endRx = TrafficStats.getUidRxBytes(Process.myUid());
                    metrics.putLong("appUidRxBytes", rx >= 0 && endRx >= rx ? endRx - rx : -1);
                    report(phase, metrics);
                    scenario.onActivity(TtffFixtureActivity::pauseFixture);
                    scenario.onActivity(a -> a.seekFixture(30000));
                    long seekStart = SystemClock.elapsedRealtime();
                    await(scenario, s -> s.state == Player.STATE_READY && s.positionMs >= 30000, 20000);
                    assertFalse("Paused seek must not resume", snapshot(scenario).playing);
                    scenario.onActivity(TtffFixtureActivity::resumeFixture);
                    await(scenario, s -> s.playing && s.positionMs >= 31500, 10000);
                    scenario.onActivity(a -> a.seekFixture(2000));
                    await(scenario, s -> s.playing && s.positionMs >= 3000 && s.positionMs < 10000, 20000);
                    Bundle seek = metrics(snapshot(scenario), sourceKind, expectedNetwork);
                    seek.putLong("seekSequenceMs", SystemClock.elapsedRealtime() - seekStart);
                    report(phase + "-seeks", seek);
                    requireNetwork(context, expectedNetwork, network);
                } catch (AssertionError failure) {
                    report(phase + "-unavailable", metrics(snapshot(scenario), sourceKind, expectedNetwork));
                    throw failure; // Never continue the cell after a denial or failed delivery.
                }
            }
        } finally { SabrVodCapability.setEnabled(previousCapability); }
    }

    static VideoInfo preparedTvMetadata(String video) {
        assertTrue("Test-only client selection", com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG);
        assertTrue("Declare SABR capability before preparing SABR-only metadata", SabrVodCapability.isEnabled());
        String override = DebugMediaShaper.prop("debug.arc.player_client");
        assertTrue("An existing client override would confound this cell", override.isEmpty()
                || "none".equalsIgnoreCase(override));
        MediaServiceData data = MediaServiceData.instance();
        int previousHint = data.getVideoInfoType();
        try {
            assertTrue(VideoInfoService.setDebugForcedClient("TV"));
            VideoInfo info = VideoInfoService.instance().getVideoInfo(video, null, null);
            if (info != null) assertEquals("Declared client only",
                    com.liskovsoft.youtubeapi.common.helpers.AppClient.TV, info.getClient());
            return info;
        } finally {
            VideoInfoService.setDebugForcedClient(null);
            data.setVideoInfoType(previousHint);
        }
    }

    private static Network requireNetwork(Context context, String expected, Network original) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        assertNotNull("No default network", caps);
        assertTrue("Network not validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        assertFalse("VPN would confound the comparison", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        assertTrue("Wrong default transport", caps.hasTransport("wifi".equals(expected)
                ? NetworkCapabilities.TRANSPORT_WIFI : NetworkCapabilities.TRANSPORT_CELLULAR));
        if (original != null) assertEquals("Default network changed during comparison", original, network);
        return network;
    }
    private static void await(ActivityScenario<TtffFixtureActivity> scenario, Predicate<Snapshot> condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        Snapshot state;
        do {
            state = snapshot(scenario);
            assertEquals("Delivery stopped: " + state.error, 0, state.errors);
            if (condition.test(state)) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("No playback milestone; state=" + state.state + " frames=" + state.frames);
    }
    private static Snapshot snapshot(ActivityScenario<TtffFixtureActivity> scenario) {
        AtomicReference<Snapshot> result = new AtomicReference<>();
        scenario.onActivity(a -> result.set(a.snapshot()));
        return result.get();
    }
    private static Bundle metrics(Snapshot state, String source, String network) {
        Bundle metrics = new Bundle();
        metrics.putString("source", source);
        metrics.putString("network", network);
        metrics.putBoolean("localFixtureOnly", false);
        metrics.putLong("firstFrameMs", state.firstFrameMs);
        metrics.putLong("readyMs", state.readyMs);
        metrics.putLong("positionMs", state.positionMs);
        metrics.putInt("frames", state.frames);
        metrics.putInt("dropped", state.dropped);
        metrics.putInt("rebuffers", state.unexpectedBuffering);
        metrics.putInt("width", state.width);
        metrics.putInt("height", state.height);
        metrics.putString("videoItag", state.videoItag);
        metrics.putString("audioItag", state.audioItag);
        metrics.putString("error", state.error);
        return metrics;
    }
    private static void report(String phase, Bundle result) {
        result.putString("sabrPhase", phase);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
}
