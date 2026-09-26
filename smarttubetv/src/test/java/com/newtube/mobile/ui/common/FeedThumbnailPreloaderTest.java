package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FeedThumbnailPreloaderTest {
    @Test
    public void windowIsTheNextCardsInTheScrollDirection() {
        assertEquals(Arrays.asList(3, 4, 5, 6), FeedThumbnailPreloader.window(0, 2, 40, false, 4));
        assertEquals(Arrays.asList(9, 8, 7, 6), FeedThumbnailPreloader.window(10, 12, 40, true, 4));
    }

    @Test
    public void windowStopsAtTheListEdges() {
        assertEquals(Arrays.asList(38, 39), FeedThumbnailPreloader.window(35, 37, 40, false, 4));
        assertEquals(Arrays.asList(1, 0), FeedThumbnailPreloader.window(2, 4, 40, true, 4));
        assertEquals(Collections.emptyList(), FeedThumbnailPreloader.window(-1, -1, 40, false, 4));
        assertEquals(Collections.emptyList(), FeedThumbnailPreloader.window(0, 2, 0, false, 4));
    }

    @Test
    public void smallScrollsDoNotRequestTheSameCardTwice() {
        Recorder sink = new Recorder();
        FeedThumbnailPreloader preloader = preloader(feed("a", 40), sink);

        preloader.onViewport(0, 2, 40, false);
        preloader.onViewport(0, 2, 40, false);
        preloader.onViewport(1, 3, 40, false);

        assertEquals(Arrays.asList("a3", "a4", "a5", "a6", "a7"), sink.preloaded);
    }

    @Test
    public void swappedListIsPreloadedAgainAtTheSamePositions() {
        Recorder sink = new Recorder();
        List<Video> cards = new ArrayList<>(feed("home", 40));
        FeedThumbnailPreloader preloader = preloader(cards, sink);
        preloader.onViewport(0, 2, 40, false);

        cards.clear();
        cards.addAll(feed("subs", 40)); // tab switch: same viewport, different cards
        preloader.onViewport(0, 2, 40, false);

        assertEquals(Arrays.asList("home3", "home4", "home5", "home6", "subs3", "subs4", "subs5", "subs6"),
                sink.preloaded);
    }

    @Test
    public void aLongFlingCancelsPreloadsForCardsAlreadyPassed() {
        Recorder sink = new Recorder();
        FeedThumbnailPreloader preloader = preloader(feed("a", 200), sink);

        for (int first = 0; first < 100; first += 3) {
            preloader.onViewport(first, first + 2, 200, false);
        }

        int outstanding = sink.preloaded.size() - sink.cancelled.size();
        assertTrue("outstanding=" + outstanding, outstanding <= FeedThumbnailPreloader.AHEAD * 2);
        assertEquals("a3", sink.cancelled.get(0)); // oldest first
    }

    @Test
    public void channelRowsAndMissingUrlsAreSkipped() {
        Recorder sink = new Recorder();
        List<Video> cards = feed("a", 10);
        Video channel = new Video();
        channel.channelId = "UCchannel";
        cards.set(3, channel);
        cards.get(4).cardImageUrl = null;
        FeedThumbnailPreloader preloader = preloader(cards, sink);

        preloader.onViewport(0, 2, 10, false);

        assertEquals(Arrays.asList("a5", "a6"), sink.preloaded);
    }

    private static FeedThumbnailPreloader preloader(List<Video> cards, Recorder sink) {
        return new FeedThumbnailPreloader(position -> position < cards.size() ? cards.get(position) : null,
                video -> video.cardImageUrl, sink);
    }

    private static List<Video> feed(String prefix, int count) {
        List<Video> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Video video = new Video();
            video.videoId = prefix + i;
            video.cardImageUrl = prefix + i;
            cards.add(video);
        }
        return cards;
    }

    private static final class Recorder implements FeedThumbnailPreloader.Sink {
        final List<String> preloaded = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();

        @Override
        public Object preload(Video video) {
            preloaded.add(video.cardImageUrl);
            return video.cardImageUrl;
        }

        @Override
        public void cancel(Object handle) {
            cancelled.add((String) handle);
        }
    }
}
