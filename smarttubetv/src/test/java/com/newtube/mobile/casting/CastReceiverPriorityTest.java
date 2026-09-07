package com.newtube.mobile.casting;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import com.newtube.mobile.casting.castv2.MdxScreenIdReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real session coordinator with fake receiver transports; never opens LAN sockets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class CastReceiverPriorityTest {
    private TestManager manager;
    private final CastTarget smart = CastTarget.fromPairedScreen(new CastScreen("smart", "TV"),
            CastTarget.ReceiverApp.SMARTTUBE);
    private final CastTarget direct = CastTarget.fromCastDevice("TV", "192.0.2.1", 8009);
    private final CastTarget youtube = CastTarget.fromCastDeviceYouTubeApp("TV", "192.0.2.1", 8009);

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        manager = new TestManager(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() {
        manager.disconnect();
        RxJavaPlugins.reset();
    }

    @Test public void failuresTryBothAdFreeRoutesBeforeLaunchingYoutubeAndNeverLoop() throws Exception {
        manager.connectWithFallback(Arrays.asList(youtube, direct, smart));
        assertSame(smart, manager.getTarget());
        call("endSession", "offline");
        assertSame(direct, manager.getTarget());
        assertEquals(0, manager.youtubeLaunches);
        call("endSession", "load failed");
        assertEquals(1, manager.youtubeLaunches);
        manager.pendingMdx.onScreenId("youtube");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(CastTarget.Route.LOUNGE_MDX, manager.getTarget().getRoute());
        call("endSession", "offline too");
        assertNull(manager.getTarget());
        assertEquals(3, manager.opened.size());
        assertEquals(1, manager.youtubeLaunches);
    }

    @Test public void explicitDirectChoiceNeverLaunchesYoutube() throws Exception {
        manager.connect(direct);
        call("endSession", "load failed");
        assertEquals(0, manager.youtubeLaunches);
        assertNull(manager.getTarget());
    }

    @Test public void disconnectCancelsPendingFallbackCallback() throws Exception {
        manager.connectWithFallback(Arrays.asList(direct, youtube));
        call("endSession", "load failed");
        manager.disconnect();
        manager.pendingMdx.onScreenId("late-youtube");
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(manager.getTarget());
        assertFalse(manager.isConnecting());
        assertEquals(1, manager.opened.size());
    }

    @Test public void newUserChoiceSupersedesPendingFallbackCallback() throws Exception {
        manager.connectWithFallback(Arrays.asList(direct, youtube));
        call("endSession", "load failed");
        MdxScreenIdReader.Callback oldCallback = manager.pendingMdx;
        manager.connect(smart);
        oldCallback.onScreenId("late-youtube");
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(smart, manager.getTarget());
    }

    @Test public void smarttubeBindWithoutPlaybackTimesOutToDirectBeforeYoutube() {
        connectRealLounge();
        manager.loadVideo("video", 12000);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertSame(direct, manager.getTarget());
        assertEquals(0, manager.youtubeLaunches);
    }

    @Test public void successfulSmarttubePlaybackCancelsFallbackWatchdog() {
        connectRealLounge();
        manager.loadVideo("video", 12000);
        manager.events.onNext(CastEvent.nowPlaying("video", 12000, 100000,
                RemoteControlService.STATE_PLAYING));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertSame(smart, manager.getTarget());
        assertEquals(0, manager.youtubeLaunches);
    }

    @Test public void oldVideoPlayingDoesNotProveRequestedVideoLoaded() {
        connectRealLounge();
        manager.loadVideo("new-video", 0);
        manager.events.onNext(CastEvent.nowPlaying("old-video", 12000, 100000,
                RemoteControlService.STATE_PLAYING));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertSame(direct, manager.getTarget());
    }

    @Test public void userPauseCancelsPlaybackTimeout() {
        connectRealLounge();
        manager.loadVideo("video", 0);
        manager.pause();
        manager.events.onNext(CastEvent.nowPlaying("video", 0, 100000,
                RemoteControlService.STATE_PAUSED));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertSame(smart, manager.getTarget());
        assertEquals(0, manager.playCommands);
    }

    @Test public void loungeLoadErrorAdvancesImmediatelyToDirect() {
        connectRealLounge();
        manager.loadFails = true;
        manager.loadVideo("video", 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(direct, manager.getTarget());
        assertEquals(0, manager.youtubeLaunches);
    }

    @Test public void gracefulSessionEndNeverChangesReceiver() throws Exception {
        manager.connectWithFallback(Arrays.asList(smart, direct, youtube));
        call("endSession", null);
        assertNull(manager.getTarget());
        assertEquals(1, manager.opened.size());
    }

    @Test public void laterLoadFailureRetainsFallbackAfterSuccessfulPlayback() {
        connectRealLounge();
        manager.loadVideo("video", 0);
        manager.events.onNext(CastEvent.nowPlaying("video", 0, 100000,
                RemoteControlService.STATE_PLAYING));
        shadowOf(Looper.getMainLooper()).idle();
        manager.loadFails = true;
        manager.loadVideo("next-video", 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(direct, manager.getTarget());
    }

    @Test public void connectionThatNeverBindsAlsoFallsBack() {
        manager.realLounge = true;
        manager.connectWithFallback(Arrays.asList(smart, direct, youtube));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertSame(direct, manager.getTarget());
    }

    private void connectRealLounge() {
        manager.realLounge = true;
        manager.connectWithFallback(Arrays.asList(smart, direct, youtube));
        manager.events.onNext(CastEvent.connected());
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(manager.isConnected());
    }

    private void call(String name, String reason) throws Exception {
        Method method = CastSessionManager.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        method.invoke(manager, reason);
    }

    private static final class TestManager extends CastSessionManager {
        final List<CastTarget> opened = new ArrayList<>();
        final PublishSubject<CastEvent> events = PublishSubject.create();
        boolean realLounge;
        boolean loadFails;
        int playCommands;
        int youtubeLaunches;
        MdxScreenIdReader.Callback pendingMdx;
        final CastSenderService sender = (CastSenderService) Proxy.newProxyInstance(
                CastSenderService.class.getClassLoader(), new Class<?>[]{CastSenderService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("connectObserve")) return events;
                    if (method.getName().equals("loadVideoObserve") && loadFails)
                        return Observable.error(new IllegalStateException("receiver unavailable"));
                    if (method.getName().equals("playObserve")) return Observable.defer(() -> {
                        playCommands++;
                        return Observable.empty();
                    });
                    return Observable.empty();
                });

        TestManager(Context context) { super(context); }
        @Override public CastSenderService getSender() { return sender; }
        @Override Connection createConnection(CastTarget target) {
            opened.add(target);
            if (realLounge && target.getRoute() == CastTarget.Route.LOUNGE_MANUAL)
                return super.createConnection(target);
            return new Connection() {
                public void start() {}
                public void destroy() {}
                public void disconnect() {}
                public void loadVideo(String id, long position) {}
                public void play() {}
                public void pause() {}
                public void seekTo(long position) {}
                public void stopVideo() {}
                public void setVolume(int volume) {}
            };
        }
        @Override void readCastScreenId(CastTarget target, MdxScreenIdReader.Callback callback) {
            youtubeLaunches++;
            pendingMdx = callback;
        }
    }
}
