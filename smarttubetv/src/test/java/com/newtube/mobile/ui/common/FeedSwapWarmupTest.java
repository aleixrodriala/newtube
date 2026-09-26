package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(feed-swap): the stale -> fresh Home swap waits for the fresh first screen's pictures, but
 * never longer than the cap, never twice, and never after it was abandoned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FeedSwapWarmupTest {
    /** Loads that end only when the test says so (or at once, for memory-cache hits). */
    private static final class Loads implements FeedSwapWarmup.Loader {
        final List<String> started = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();
        final List<Object> cancelled = new ArrayList<>();
        boolean instant;

        @Override
        public Object load(Video video, Runnable onDone) {
            started.add(video.videoId);
            if (instant) {
                onDone.run();
                onDone.run(); // a misbehaving double report must not count twice
            } else {
                pending.add(onDone);
            }
            return video.videoId;
        }

        @Override
        public void cancel(Object handle) {
            cancelled.add(handle);
        }
    }

    private static final class Clock implements FeedSwapWarmup.Timer {
        Runnable task;
        long delayMs;
        boolean cancelled;

        @Override
        public Object schedule(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
            return task;
        }

        @Override
        public void cancel(Object token) {
            cancelled = true;
            task = null;
        }
    }

    private static final class Result implements FeedSwapWarmup.OnReady {
        int calls;
        int warmed = -1;
        int total = -1;
        boolean timedOut;

        @Override
        public void onReady(int warmed, int total, boolean timedOut) {
            calls++;
            this.warmed = warmed;
            this.total = total;
            this.timedOut = timedOut;
        }
    }

    private static List<Video> cards(String... ids) {
        List<Video> result = new ArrayList<>();
        for (String id : ids) {
            Video video = new Video();
            video.videoId = id;
            result.add(video);
        }
        return result;
    }

    @Test
    public void swapsOnceEveryPictureIsReady() {
        Loads loads = new Loads();
        Clock clock = new Clock();
        Result result = new Result();
        FeedSwapWarmup warmup = new FeedSwapWarmup(loads, clock);

        warmup.begin(cards("a", "b", "c"), result);
        assertEquals(Arrays.asList("a", "b", "c"), loads.started);
        assertEquals(FeedSwapWarmup.MAX_WAIT_MS, clock.delayMs);
        assertTrue(warmup.isPending());

        loads.pending.get(0).run();
        loads.pending.get(2).run();
        assertEquals("not before the last picture", 0, result.calls);

        loads.pending.get(1).run();
        assertEquals(1, result.calls);
        assertEquals(3, result.warmed);
        assertEquals(3, result.total);
        assertFalse(result.timedOut);
        assertTrue("the cap timer is dropped", clock.cancelled);
        assertFalse(warmup.isPending());
    }

    @Test
    public void theCapSwapsWithWhateverIsReady() {
        Loads loads = new Loads();
        Clock clock = new Clock();
        Result result = new Result();
        FeedSwapWarmup warmup = new FeedSwapWarmup(loads, clock);

        warmup.begin(cards("a", "b"), result);
        loads.pending.get(0).run();
        clock.task.run();

        assertEquals(1, result.calls);
        assertEquals(1, result.warmed);
        assertTrue(result.timedOut);
        assertTrue("slow loads keep going into the cache for the cards", loads.cancelled.isEmpty());

        loads.pending.get(1).run();
        assertEquals("a late picture does not swap again", 1, result.calls);
    }

    @Test
    public void memoryCacheHitsSwapBeforeBeginReturns() {
        Loads loads = new Loads();
        loads.instant = true;
        Clock clock = new Clock();
        Result result = new Result();
        FeedSwapWarmup warmup = new FeedSwapWarmup(loads, clock);

        warmup.begin(cards("a", "b"), result);

        assertEquals(1, result.calls);
        assertEquals(2, result.warmed);
        assertFalse(result.timedOut);
        assertNull("no timer left behind", clock.task);
        assertFalse(warmup.isPending());
    }

    @Test
    public void nothingToWarmSwapsAtOnceWithoutATimer() {
        Loads loads = new Loads();
        Clock clock = new Clock();
        Result result = new Result();

        new FeedSwapWarmup(loads, clock).begin(Collections.emptyList(), result);

        assertEquals(1, result.calls);
        assertEquals(0, result.total);
        assertNull(clock.task);
    }

    @Test
    public void anAbandonedSwapNeverRuns() {
        Loads loads = new Loads();
        Clock clock = new Clock();
        Result result = new Result();
        FeedSwapWarmup warmup = new FeedSwapWarmup(loads, clock);

        warmup.begin(cards("a", "b"), result);
        Runnable cap = clock.task;
        assertNotNull(cap);
        warmup.cancel();

        assertTrue(clock.cancelled);
        assertEquals(Arrays.asList("a", "b"), loads.cancelled);
        loads.pending.get(0).run();
        loads.pending.get(1).run();
        cap.run();
        assertEquals(0, result.calls);
        assertFalse(warmup.isPending());
    }

    @Test
    public void firstScreenIsTheVisibleCountClampedAndSkipsChannelRows() {
        List<Video> fresh = cards("a", "b", "c", "d", "e", "f", "g", "h");
        Video channel = new Video();
        channel.channelId = "UCxyz"; // a channel-shaped row: round avatar, no thumbnail
        fresh.add(1, channel);

        assertEquals(Arrays.asList("a", "b"), ids(FeedSwapWarmup.firstScreen(fresh, 3)));
        assertEquals("at least one card", Collections.singletonList("a"), ids(FeedSwapWarmup.firstScreen(fresh, 0)));
        assertEquals(FeedSwapWarmup.MAX_CARDS - 1, FeedSwapWarmup.firstScreen(fresh, 50).size());
        assertTrue(FeedSwapWarmup.firstScreen(null, 3).isEmpty());
    }

    @Test
    public void onlyAGridRestingAtTheTopWaitsAndIsPinned() {
        assertTrue(FeedSwapWarmup.warmAndPin(/* atTop= */ true, /* scrollIdle= */ true));
        assertFalse("scrolled down: the warmed cards are not the ones on screen",
                FeedSwapWarmup.warmAndPin(false, true));
        assertFalse("a finger on the grid (or a fling) is never snapped to the top",
                FeedSwapWarmup.warmAndPin(true, false));
        assertFalse(FeedSwapWarmup.warmAndPin(false, false));
    }

    @Test
    public void aClearBeforeLoadKeepsTheSnapshotWhileItIsStillWhatIsOnScreen() {
        assertTrue(FeedSwapWarmup.keepScreenOnClear(/* awaitingFresh= */ true, /* swapPending= */ false));
        assertTrue("pull-to-refresh during the warm-up must not blank the feed",
                FeedSwapWarmup.keepScreenOnClear(false, true));
        assertFalse("fresh content on screen: a refresh clears as before",
                FeedSwapWarmup.keepScreenOnClear(false, false));
    }

    private static List<String> ids(List<Video> videos) {
        List<String> ids = new ArrayList<>();
        for (Video video : videos) {
            ids.add(video.videoId);
        }
        return ids;
    }
}
