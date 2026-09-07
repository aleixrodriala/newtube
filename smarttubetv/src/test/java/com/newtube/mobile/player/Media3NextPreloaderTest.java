package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Real coordinator with deterministic player observations; no decoder, account or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class Media3NextPreloaderTest {
    private final MediaSource source = source("next");
    private final FakeEngine engine = new FakeEngine();
    private final List<MediaSource> discarded = new ArrayList<>();
    private Media3NextPreloader preloader;
    private Media3NextPreloader.Events events;
    private boolean healthy;
    private long now;
    private int builds;
    private int parameterCopies;

    @Before
    public void setUp() {
        healthy = true;
        preloader = new Media3NextPreloader(() -> {
            builds++;
            return engine;
        }, () -> healthy, () -> parameterCopies++, discarded::add,
                new Handler(Looper.getMainLooper()), () -> now);
    }

    @After
    public void tearDown() {
        preloader.release();
    }

    @Test
    public void waitsWithoutBuildingManagerUntilForegroundHasSpareBandwidth() {
        healthy = false;
        preloader.offer("next", source);
        preloader.update();
        assertEquals(0, builds);
        assertEquals(0, parameterCopies);
        healthy = true;
        preloader.update();
        assertEquals(1, builds);
        assertEquals(1, engine.added.size());
        assertEquals(1, parameterCopies);
    }

    @Test
    public void repeatedOffersAndStateEventsStartOnlyOneAttempt() {
        preloader.offer("next", source);
        preloader.offer("next", source);
        preloader.offer("next", source("replacement-for-same-video"));
        preloader.update();
        assertEquals(1, builds);
        assertEquals(1, engine.added.size());
        assertSame(source, engine.added.get(0));
    }

    @Test
    public void foregroundLoadingCancelsAndRemovesPreparedRawStash() {
        preloader.offer("next", source);
        healthy = false;
        preloader.update();
        assertEquals(1, engine.removed.size());
        assertEquals(1, discarded.size());
        assertSame(source, discarded.get(0));
        healthy = true;
        preloader.offer("next", source("fresh-raw"));
        assertEquals(1, engine.added.size());
    }

    @Test
    public void speculativeErrorHasNoRetryAndLateCompletionCannotPublish() {
        preloader.offer("next", source);
        events.onError();
        events.onCompleted();
        preloader.offer("next", source("fresh-raw"));
        preloader.update();
        assertEquals(1, engine.added.size());
        assertEquals(1, engine.removed.size());
        assertEquals(1, discarded.size());
    }

    @Test
    public void cancelledAttemptsLateCompletionCannotCompleteItsReplacement() {
        preloader.offer("next", source);
        Media3NextPreloader.Events oldEvents = events;
        MediaSource replacement = source("different");
        preloader.offer("different", replacement);
        oldEvents.onCompleted();
        assertNull(preloader.take("different", replacement));
        assertEquals(2, engine.removed.size());
    }

    @Test
    public void cancelledAttemptsLateErrorCannotCancelItsReplacement() {
        preloader.offer("next", source);
        Media3NextPreloader.Events oldEvents = events;
        MediaSource replacement = source("different");
        preloader.offer("different", replacement);
        oldEvents.onError();
        assertEquals(1, engine.removed.size());
        events.onCompleted();
        assertSame(engine.wrapped, preloader.take("different", replacement));
    }

    @Test
    public void oldSameVideoCallbacksCannotAffectANewOpenGeneration() {
        preloader.offer("next", source);
        Media3NextPreloader.Events oldEvents = events;
        preloader.onReset("current");
        preloader.onSourceOpened(source("current"));
        MediaSource replacement = source("next-again");
        preloader.offer("next", replacement);
        oldEvents.onError();
        oldEvents.onCompleted();
        assertEquals(1, engine.removed.size());
        assertNull(preloader.take("next", replacement));
    }

    @Test
    public void completedSourceSurvivesBothMatchingResetsAndIsConsumedOnce() {
        preloader.offer("next", source);
        events.onCompleted();
        preloader.onReset("next"); // loadVideo, before format info arrives
        MediaSource wrapped = preloader.take("next", source);
        assertSame(engine.wrapped, wrapped);
        preloader.onReset("next"); // openMediaSource's own reset
        preloader.onSourceOpened(wrapped);
        assertTrue(engine.removed.isEmpty());
        preloader.onForegroundTracksChanged();
        assertEquals(1, engine.removed.size());
        assertSame(wrapped, engine.removed.get(0));
        assertTrue(discarded.isEmpty());
    }

    @Test
    public void incompleteSourceIsNeverHandedToForeground() {
        preloader.offer("next", source);
        assertNull(preloader.take("next", source));
        assertEquals(1, engine.removed.size());
        assertSame(source, discarded.get(0));
    }

    @Test
    public void unpreparedStashStillSkipsXmlBuildWhenBandwidthNeverBecameAvailable() {
        healthy = false;
        preloader.offer("next", source);
        preloader.onReset("next");
        assertSame(source, preloader.take("next", source));
        assertEquals(0, builds);
        assertTrue(discarded.isEmpty());
    }

    @Test
    public void differentTargetResetReleasesCompletedResources() {
        preloader.offer("next", source);
        events.onCompleted();
        preloader.onReset("different");
        assertEquals(1, engine.removed.size());
        assertSame(source, discarded.get(0));
    }

    @Test
    public void matchingResetCancelsAnIncompleteLoadBeforeNewPlaybackStarts() {
        preloader.offer("next", source);
        preloader.onReset("next");
        assertEquals(1, engine.removed.size());
        assertSame(source, discarded.get(0));
    }

    @Test
    public void completedBufferDoesNotCompeteWhenForegroundNeedsMoreData() {
        preloader.offer("next", source);
        events.onCompleted();
        healthy = false;
        preloader.update();
        assertTrue(engine.removed.isEmpty());
        assertSame(engine.wrapped, preloader.take("next", source));
    }

    @Test
    public void foregroundErrorReleasesManagerOwnershipEvenBeforeTracksArrive() {
        preloader.offer("next", source);
        events.onCompleted();
        MediaSource wrapped = preloader.take("next", source);
        preloader.onSourceOpened(wrapped);
        preloader.onForegroundError();
        assertEquals(1, engine.removed.size());
        assertSame(wrapped, engine.removed.get(0));
    }

    @Test
    public void seekOrFormatChangeCancelsEvenACompletedUnusedPreload() {
        preloader.offer("next", source);
        events.onCompleted();
        preloader.cancel("seek");
        assertEquals(1, engine.removed.size());
        assertEquals(1, discarded.size());
    }

    @Test
    public void timeBudgetStopsSlowLoadAtFifteenSeconds() {
        preloader.offer("next", source);
        now = Media3NextPreloader.MAX_LOAD_TIME_MS - 1;
        preloader.update();
        assertTrue(engine.removed.isEmpty());
        now++;
        preloader.update();
        assertEquals(1, engine.removed.size());
        preloader.update();
        assertEquals(1, engine.removed.size());
    }

    @Test
    public void completedStashExpiresAndCannotReturnAPreparedRawSourceAtOpen() {
        preloader.offer("next", source);
        events.onCompleted();
        now = Media3NextPreloader.MAX_STASH_AGE_MS;
        assertNull(preloader.take("next", source));
        assertEquals(1, engine.removed.size());
        assertEquals(1, discarded.size());
    }

    @Test
    public void sourceRouteChangeDropsCompletedDashInsteadOfLeavingItAlive() {
        preloader.offer("next", source);
        events.onCompleted();
        preloader.onReset("next");
        preloader.onSourceOpened(source("hls-selected"));
        assertEquals(1, engine.removed.size());
        assertEquals(1, discarded.size());
    }

    @Test
    public void engineIsReleasedOnlyOnceAndDoesNotAcceptNewWorkAfterRelease() {
        preloader.offer("next", source);
        preloader.release();
        preloader.release();
        preloader.offer("other", source("other"));
        preloader.update();
        assertEquals(1, engine.releases);
        assertEquals(1, engine.added.size());
        assertEquals(1, engine.removed.size());
    }

    @Test
    public void releasingBeforeFirstLoadDoesNotCreateAnEngine() {
        healthy = false;
        preloader.offer("next", source);
        preloader.release();
        assertEquals(0, builds);
        assertTrue(discarded.isEmpty());
    }

    @Test
    public void newForegroundGenerationCanAttemptTheNextTargetAgain() {
        preloader.offer("next", source);
        events.onError();
        preloader.onReset("current");
        preloader.onSourceOpened(source("current"));
        preloader.offer("next", source("refreshed"));
        assertEquals(2, engine.added.size());
        assertEquals(1, builds);
    }

    @Test
    public void replacementReleasesOldSourceBeforeAddingNewOne() {
        preloader.offer("next", source);
        MediaSource replacement = source("different");
        preloader.offer("different", replacement);
        assertEquals(2, engine.added.size());
        assertEquals(1, engine.removed.size());
        assertSame(source, discarded.get(0));
        assertSame(replacement, engine.added.get(1));
    }

    @Test
    public void bufferThresholdUsesPlayoutTimeAndAllowsOnlyFullyLoadedShortTail() {
        assertFalse(Media3NextPreloader.hasHealthyBuffer(9_999, 60_000, 0, 1f));
        assertTrue(Media3NextPreloader.hasHealthyBuffer(10_000, 60_000, 0, 1f));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(19_999, 60_000, 0, 2f));
        assertTrue(Media3NextPreloader.hasHealthyBuffer(20_000, 60_000, 0, 2f));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(4_999, 60_000, 55_000, 1f));
        assertTrue(Media3NextPreloader.hasHealthyBuffer(5_000, 60_000, 55_000, 1f));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(0, 60_000, 60_000, 1f));
    }

    @Test
    public void unknownOrInvalidBufferObservationsDoNotStartSpeculation() {
        assertFalse(Media3NextPreloader.hasHealthyBuffer(5_000, C.TIME_UNSET, 0, 1f));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(-1, 60_000, 0, 1f));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(20_000, 60_000, 0, Float.NaN));
        assertFalse(Media3NextPreloader.hasHealthyBuffer(20_000, 60_000, 0, 0f));
    }

    @Test
    public void playerReadinessAndForegroundLoadingGateRealEligibility() {
        DefaultTrackSelector selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        try {
            assertTrue(Media3NextPreloader.canLoad(player(Player.STATE_READY, false, true), selector));
            assertFalse(Media3NextPreloader.canLoad(player(Player.STATE_READY, true, true), selector));
            assertFalse(Media3NextPreloader.canLoad(player(Player.STATE_BUFFERING, false, true), selector));
            assertFalse(Media3NextPreloader.canLoad(player(Player.STATE_READY, false, false), selector));
            assertFalse(Media3NextPreloader.canLoad(null, selector));
        } finally {
            selector.release();
        }
    }

    @Test
    public void manualVideoPinAndBackgroundAudioSkipMediaPreload() {
        DefaultTrackSelector selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        TrackGroup video = new TrackGroup(new Format.Builder().setId("manual")
                .setSampleMimeType(MimeTypes.VIDEO_H264).build());
        try {
            selector.setParameters(selector.buildUponParameters()
                    .setOverrideForType(new TrackSelectionOverride(video, 0)));
            assertFalse(Media3NextPreloader.canLoad(player(Player.STATE_READY, false, true), selector));
            selector.setParameters(selector.buildUponParameters().clearOverrides()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true));
            assertFalse(Media3NextPreloader.canLoad(player(Player.STATE_READY, false, true), selector));
            selector.setParameters(selector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).setMaxVideoSize(1280, 720));
            assertTrue(Media3NextPreloader.canLoad(player(Player.STATE_READY, false, true), selector));
        } finally {
            selector.release();
        }
    }

    @Test
    public void preloadParametersDropOldGroupOverridesWithoutChangingForegroundPicks() {
        DefaultTrackSelector foreground = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        DefaultTrackSelector next = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        TrackGroup audio = new TrackGroup(new Format.Builder().setId("current-original")
                .setSampleMimeType(MimeTypes.AUDIO_AAC).build());
        try {
            foreground.setParameters(foreground.buildUponParameters().setMaxVideoSize(1280, 720)
                    .setPreferredAudioLanguage("en").setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(audio, 0)));
            DefaultTrackSelector.Parameters before = foreground.getParameters();
            Media3NextPreloader.copyTrackParameters(foreground, next);
            assertSame(before, foreground.getParameters());
            assertEquals(1, foreground.getParameters().overrides.size());
            assertFalse(foreground.getParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertTrue(next.getParameters().overrides.isEmpty());
            assertTrue(next.getParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertEquals(720, next.getParameters().maxVideoHeight);
            assertEquals(before.preferredAudioLanguages, next.getParameters().preferredAudioLanguages);
        } finally {
            foreground.release();
            next.release();
        }
    }

    private static ExoPlayer player(int state, boolean loading, boolean playWhenReady) {
        return (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[] {ExoPlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPlayerError": return null;
                        case "getPlayWhenReady": return playWhenReady;
                        case "getPlaybackState": return state;
                        case "isLoading": return loading;
                        case "isCurrentMediaItemLive": return false;
                        case "getTotalBufferedDuration": return 10_000L;
                        case "getDuration": return 60_000L;
                        case "getCurrentPosition": return 0L;
                        case "getPlaybackParameters": return PlaybackParameters.DEFAULT;
                        default: throw new AssertionError("Unexpected player observation: " + method.getName());
                    }
                });
    }

    private static MediaSource source(String name) {
        MediaItem item = MediaItem.fromUri("https://media.invalid/" + name);
        return (MediaSource) Proxy.newProxyInstance(MediaSource.class.getClassLoader(),
                new Class<?>[] {MediaSource.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMediaItem": return item;
                        case "toString": return name;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: throw new AssertionError("Unexpected source operation: " + method.getName());
                    }
                });
    }

    private final class FakeEngine implements Media3NextPreloader.Engine {
        final List<MediaSource> added = new ArrayList<>();
        final List<MediaSource> removed = new ArrayList<>();
        MediaSource wrapped;
        int releases;

        @Override public MediaSource add(MediaSource source, Media3NextPreloader.Events listener) {
            events = listener;
            added.add(source);
            wrapped = source("wrapped-" + added.size());
            return wrapped;
        }
        @Override public void remove(MediaSource source) { removed.add(source); }
        @Override public void release() { releases++; }
    }
}
