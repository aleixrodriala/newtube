package com.liskovsoft.smartyoutubetv2.common.app.models.playback.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Application;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The launch-time history restore collapses the persisted states in one pass instead of add()-ing
 * them one by one through the LRU list. The result must be exactly what the LRU list produced.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoStateRestoreTest {
    @Test
    public void repeatedVideoKeepsOnlyItsLatestStateAtTheEnd() {
        State a1 = state("a", 1);
        State b = state("b", 2);
        State a2 = state("a", 3);

        List<State> collapsed = VideoStateService.collapseLru(Arrays.asList(a1, b, a2), 300);

        assertEquals(2, collapsed.size());
        assertSame(b, collapsed.get(0));
        assertSame(a2, collapsed.get(1));
    }

    @Test
    public void mixAndPlainVideoWithTheSameIdsStayDistinct() {
        State plain = state("x", 1);
        Video mix = video("x");
        mix.playlistId = "RDx";
        mix.badge = "Mix";
        State mixState = new State(mix, 2, 100);

        assertSameStates(sequential(Arrays.asList(plain, mixState), 300),
                VideoStateService.collapseLru(Arrays.asList(plain, mixState), 300));
        assertEquals(2, VideoStateService.collapseLru(Arrays.asList(plain, mixState), 300).size());
    }

    @Test
    public void matchesSequentialLruAddsOnRandomHistories() {
        Random random = new Random(20260925L);
        for (int round = 0; round < 300; round++) {
            int maxEntries = 1 + random.nextInt(8);
            int count = random.nextInt(40);
            List<State> states = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                states.add(state("v" + random.nextInt(12), i));
            }

            assertSameStates(sequential(states, maxEntries), VideoStateService.collapseLru(states, maxEntries));
        }
    }

    @Test
    public void persistedRecordsRoundTripLikeTheOldRestore() {
        List<State> states = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            State original = state("id" + (i % 17), i * 1000L);
            states.add(State.from(original.toString()));
        }

        assertSameStates(sequential(states, 20), VideoStateService.collapseLru(states, 20));
    }

    /** What the pre-change restore did: add() one by one into the LRU list. */
    private static List<State> sequential(List<State> states, int maxEntries) {
        List<State> lru = Helpers.createSafeLRUList(maxEntries);
        for (State state : states) {
            lru.add(state);
        }
        return new ArrayList<>(lru);
    }

    /** Element identity, not State.equals (which only compares the videos). */
    private static void assertSameStates(List<State> expected, List<State> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame("index " + i, expected.get(i), actual.get(i));
        }
    }

    private static State state(String videoId, long positionMs) {
        return new State(video(videoId), positionMs, 100_000);
    }

    private static Video video(String videoId) {
        Video video = new Video();
        video.videoId = videoId;
        return video;
    }
}
