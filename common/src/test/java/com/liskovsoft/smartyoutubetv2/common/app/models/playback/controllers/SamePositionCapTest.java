package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.reflect.Proxy;

/**
 * NEWTUBE(same-position cap): errors recurring at the same media position must reach the cap even
 * though every reload "plays" from the disk cache in between (onPlay). Codex C1, 2026-09-25: onPlay
 * nulls mAutoFixVideoId, and the window used to reset with it, so samePos never passed 1.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SamePositionCapTest {
    private long position = 41_000;
    private final Video video = new Video();
    private final ErrorFixerController controller = new ErrorFixerController() {
        @Override public Context getContext() { return RuntimeEnvironment.getApplication(); }
        @Override public PlaybackView getPlayer() { return player(); }
        @Override public Video getVideo() { return video; }
    };

    @Test
    public void theSameChunkFailingAfterEveryCachedReplayReachesTheCap() {
        video.videoId = "same-chunk";

        for (int cycle = 1; cycle <= 3; cycle++) {
            assertFalse("cycle " + cycle + " is still inside the cap", register());
            assertEquals(cycle, samePos());
            controller.onPlay(); // the reload replayed from cache and reached READY: false-healthy
        }

        assertTrue("the 4th identical failure stops the loop", register());
        assertEquals(4, samePos());
    }

    @Test
    public void aGenuinelyDifferentPositionOrVideoStillStartsAFreshWindow() {
        video.videoId = "moving";
        register();
        controller.onPlay();
        position += 60_000; // playback got past it and died somewhere else
        register();
        assertEquals(1, samePos());

        controller.onPlay();
        video.videoId = "another-video";
        register();
        assertEquals(1, samePos());
    }

    private boolean register() {
        return ReflectionHelpers.callInstanceMethod(controller, "registerAutoFixAndCheckCap",
                ClassParameter.from(Throwable.class,
                        new java.io.IOException("Response code: 403")));
    }

    private int samePos() {
        return ReflectionHelpers.getField(controller, "mSamePositionErrorCount");
    }

    private PlaybackView player() {
        return (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[] {PlaybackView.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPositionMs": return position;
                        case "getDurationMs": return 600_000L;
                        case "isPlaying": return true;
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                    }
                });
    }
}
