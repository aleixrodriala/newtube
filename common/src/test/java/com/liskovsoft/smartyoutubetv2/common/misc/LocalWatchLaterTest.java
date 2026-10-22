package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class LocalWatchLaterTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE).edit().clear().commit();
        LocalWatchLater.resetForTests();
    }

    private static Video video(String id, String title) {
        Video video = new Video();
        video.videoId = id;
        video.title = title;
        video.channelId = "UCchannel";
        video.secondTitle = "Channel • 1K views";
        video.badge = "12:34";
        video.cardImageUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        video.playlistId = "PLsomewhere"; // where it was found: not kept
        return video;
    }

    @Test
    public void newestFirstAndOnce() {
        LocalWatchLater watchLater = LocalWatchLater.instance(mContext);
        watchLater.add(video("aaaaaaaaaaa", "A"));
        watchLater.add(video("bbbbbbbbbbb", "B"));
        watchLater.add(video("aaaaaaaaaaa", "A again")); // moves to the top, not a second copy

        List<Video> list = watchLater.list();
        assertEquals(2, list.size());
        assertEquals("aaaaaaaaaaa", list.get(0).videoId);
        assertEquals("bbbbbbbbbbb", list.get(1).videoId);
        assertEquals(LocalWatchLater.SECTION_ID, list.get(0).sectionId);
    }

    @Test
    public void survivesARestartWithTheCardButNotTheContext() {
        LocalWatchLater.instance(mContext).add(video("aaaaaaaaaaa", "Title"));

        LocalWatchLater.resetForTests();
        List<Video> list = LocalWatchLater.instance(mContext).list();

        assertEquals(1, list.size());
        Video card = list.get(0);
        assertEquals("Title", card.title);
        assertEquals("UCchannel", card.channelId);
        assertEquals("12:34", card.badge);
        assertEquals("Channel • 1K views", String.valueOf(card.getSecondTitle()));
        assertNull(card.playlistId);
    }

    @Test
    public void removeAndContains() {
        LocalWatchLater watchLater = LocalWatchLater.instance(mContext);
        watchLater.add(video("aaaaaaaaaaa", "A"));
        assertTrue(watchLater.contains("aaaaaaaaaaa"));

        watchLater.remove("aaaaaaaaaaa");

        assertFalse(watchLater.contains("aaaaaaaaaaa"));
        assertEquals(0, watchLater.size());
        LocalWatchLater.resetForTests();
        assertEquals(0, LocalWatchLater.instance(mContext).size());
    }

    @Test
    public void onlyOrdinaryVideosCanBeSaved() {
        assertFalse(LocalWatchLater.canSave(null));
        Video channel = new Video();
        channel.channelId = "UCchannel";
        assertFalse(LocalWatchLater.canSave(channel));
        assertTrue(LocalWatchLater.canSave(video("aaaaaaaaaaa", "A")));
    }

    @Test
    public void aDamagedFileStartsOver() {
        mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE).edit()
                .putString("videos", "{not json").commit();
        assertEquals(0, LocalWatchLater.instance(mContext).size());
    }
}
