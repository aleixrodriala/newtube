package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
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
    public void aFullListSaysTheOldestWent() {
        JSONArray specs = new JSONArray();
        for (int i = 0; i < LocalWatchLater.MAX_VIDEOS; i++) {
            specs.put(video(String.format("v%010d", i), "V" + i).toString()); // newest first
        }
        mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE).edit()
                .putString("videos", specs.toString()).commit();
        LocalWatchLater watchLater = LocalWatchLater.instance(mContext);

        assertFalse(watchLater.add(video("v0000000000", "V0"))); // already in it: nothing goes
        assertTrue(watchLater.add(video("aaaaaaaaaaa", "A")));

        assertEquals(LocalWatchLater.MAX_VIDEOS, watchLater.size());
        assertFalse(watchLater.contains(String.format("v%010d", LocalWatchLater.MAX_VIDEOS - 1)));
        assertTrue(LocalWatchLater.savedMessage(mContext, true).contains(String.valueOf(LocalWatchLater.MAX_VIDEOS)));
    }

    @Test
    public void aDamagedFileIsPutAsideNotWrittenOver() {
        SharedPreferences prefs = mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE);
        prefs.edit().putString("videos", "[\"truncat").commit();

        LocalWatchLater watchLater = LocalWatchLater.instance(mContext);
        assertEquals(0, watchLater.size());
        watchLater.add(video("aaaaaaaaaaa", "A")); // the next save writes the list over...

        assertEquals("[\"truncat", prefs.getString("videos_damaged", null)); // ...not over what it was
        assertEquals(1, watchLater.size());
    }

    @Test
    public void oneBadEntryCostsOnlyItself() {
        SharedPreferences prefs = mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE);
        JSONArray specs = new JSONArray();
        specs.put(video("aaaaaaaaaaa", "A").toString());
        specs.put("not a video");
        specs.put(video("bbbbbbbbbbb", "B").toString());
        prefs.edit().putString("videos", specs.toString()).commit();

        List<Video> list = LocalWatchLater.instance(mContext).list();

        assertEquals(2, list.size());
        assertEquals("bbbbbbbbbbb", list.get(1).videoId);
        assertEquals(specs.toString(), prefs.getString("videos_damaged", null));
    }

    @Test
    public void anIntactFileIsNotPutAside() {
        LocalWatchLater.instance(mContext).add(video("aaaaaaaaaaa", "A"));
        LocalWatchLater.resetForTests();

        assertEquals(1, LocalWatchLater.instance(mContext).size());
        assertNull(mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE)
                .getString("videos_damaged", null));
    }
}
