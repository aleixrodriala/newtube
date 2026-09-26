package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(phone): a rating that fails late corrects - and reports on - only the live watch page
 * it was tapped on. getPlayer() keeps returning a destroyed page, whose "not saved" would land
 * over whatever screen is in front by then.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerUIControllerRatingRollbackTest {
    private final List<String> calls = new ArrayList<>();
    private PlaybackView tappedOn;
    private PlaybackView current;
    private boolean alive;
    private Video video;
    private PlayerUIController controller;

    @Before
    public void setUp() {
        tappedOn = view("tapped");
        current = tappedOn;
        alive = true;
        video = new Video();
        video.videoId = "abc";
        controller = new PlayerUIController() {
            @Override public PlaybackView getPlayer() { return current; }
            @Override public boolean isPlayerAlive() { return alive; }
            @Override public Video getVideo() { return video; }
        };
    }

    private PlaybackView view(String name) {
        return (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[] {PlaybackView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setButtonState") || method.getName().equals("onRatingNotSaved")) {
                        calls.add(name + "." + method.getName());
                        return null;
                    }
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    throw new AssertionError("Unexpected player call: " + method.getName());
                });
    }

    @Test
    public void theLivePageItCameFromIsCorrectedAndTold() {
        controller.rollBackRating(tappedOn, "abc", RatingWriter.LIKE);

        assertEquals(List.of("tapped.setButtonState", "tapped.setButtonState", "tapped.onRatingNotSaved"), calls);
    }

    @Test
    public void aDestroyedPageIsLeftAlone() {
        alive = false; // the watch page was closed before the request failed

        controller.rollBackRating(tappedOn, "abc", RatingWriter.LIKE);

        assertEquals(List.of(), calls);
    }

    @Test
    public void aNewerPageIsLeftAlone() {
        current = view("newer"); // reopened: it loads the real rating with its own metadata

        controller.rollBackRating(tappedOn, "abc", RatingWriter.LIKE);

        assertEquals(List.of(), calls);
    }

    @Test
    public void anotherVideoIsLeftAlone() {
        video.videoId = "next";

        controller.rollBackRating(tappedOn, "abc", RatingWriter.LIKE);

        assertEquals(List.of(), calls);
    }
}
