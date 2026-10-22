package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class LocalPlaylistsTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mContext.getSharedPreferences("newtube_playlists", Context.MODE_PRIVATE).edit().clear().commit();
        mContext.getSharedPreferences("newtube_playlist_copies", Context.MODE_PRIVATE).edit().clear().commit();
        mContext.getSharedPreferences("newtube_watch_later", Context.MODE_PRIVATE).edit().clear().commit();
        LocalWatchLater.resetForTests();
        LocalPlaylists.resetForTests();
    }

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        video.title = "Title " + id;
        video.channelId = "UCchannel";
        video.badge = "1:00";
        video.cardImageUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        return video;
    }

    @Test
    public void createAddAndKeepOrder() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("  Road trip ", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        playlists.add(id, video("aaaaaaaaaaa")); // already in it: once only

        assertTrue(LocalPlaylists.isLocalId(id));
        assertEquals("Road trip", playlists.get(id).title);
        List<Video> videos = playlists.videos(id);
        assertEquals(2, videos.size());
        assertEquals("aaaaaaaaaaa", videos.get(0).videoId); // the order they were added
        assertEquals(id, videos.get(0).localPlaylistId);
        assertTrue(playlists.containsAnywhere("bbbbbbbbbbb"));
    }

    @Test
    public void newestPlaylistFirstAndSurvivesARestart() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String older = playlists.create("Older", null);
        String newer = playlists.create("Newer", video("aaaaaaaaaaa"));

        LocalPlaylists.resetForTests();
        LocalPlaylists again = LocalPlaylists.instance(mContext);

        assertEquals(2, again.playlists().size());
        assertEquals(newer, again.playlists().get(0).id);
        assertEquals(older, again.playlists().get(1).id);
        assertEquals("1:00", again.videos(newer).get(0).badge);
    }

    @Test
    public void cardsOpenThePhonePlaylist() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));

        Video card = playlists.cards(mContext).get(0);

        assertTrue(LocalPlaylists.isLocalPlaylistCard(card));
        assertEquals(id, card.playlistId);
        assertNull(card.videoId);
        assertEquals("Mix", card.title);
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", card.cardImageUrl);
    }

    @Test
    public void renameRemoveDelete() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("A", video("aaaaaaaaaaa"));

        playlists.rename(id, "B");
        playlists.rename(id, "   "); // a blank name is ignored
        playlists.remove(id, "aaaaaaaaaaa");

        assertEquals("B", playlists.get(id).title);
        assertEquals(0, playlists.get(id).size());

        playlists.delete(id);
        assertNull(playlists.get(id));
    }

    @Test
    public void savingMarksACopyOffer() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        assertFalse(playlists.isCopyOfferPending());

        String id = playlists.create("A", null);
        assertTrue(playlists.isCopyOfferPending());

        playlists.setCopyOfferPending(false);
        playlists.rename(id, "B"); // a rename saves nothing new
        assertFalse(playlists.isCopyOfferPending());

        playlists.add(id, video("aaaaaaaaaaa"));
        assertTrue(playlists.isCopyOfferPending());
    }

    @Test
    public void aYouTubeIdIsNeverLocal() {
        assertFalse(LocalPlaylists.isLocalId("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"));
        assertFalse(LocalPlaylists.isLocalId("WL"));
        assertFalse(LocalPlaylists.isLocalId(null));
    }

    @Test
    public void theCopyFindsThePlaylistItJustMade() {
        List<PlaylistInfo> infos = Arrays.asList(
                info("Watch later", "WL", true),
                info("Road trip", "PLold", false),  // same name, but doesn't hold the video
                info("Road trip", "PLnew", true),
                info("Other", "PLother", true));

        assertEquals("PLnew", LocalPlaylistsCopy.findCreated(infos, "Road trip"));
        assertNull(LocalPlaylistsCopy.findCreated(infos, "Missing"));
        assertNull(LocalPlaylistsCopy.findCreated(Arrays.asList(info("Watch later", "WL", true)), "Watch later"));
        assertNull(LocalPlaylistsCopy.findCreated(null, "Road trip"));
    }

    @Test
    public void aPlaylistCopiedEmptyIsFoundByNameLater() {
        List<PlaylistInfo> infos = Arrays.asList(
                info("Watch later", "WL", false),
                info("Road trip", "PLempty", false)); // holds none of the new videos yet

        assertEquals("PLempty", LocalPlaylistsCopy.findByTitle(infos, "Road trip"));
        assertNull(LocalPlaylistsCopy.findCreated(infos, "Road trip"));
    }

    @Test
    public void onlyWhatTheAccountHasntGotCountsAsNew() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));

        int[] before = LocalPlaylistsCopy.countNew(mContext, "none");
        assertEquals(1, before[0]); // one playlist the account doesn't have
        assertEquals(0, before[1]); // its videos go with it, not counted again

        LocalPlaylistsCopy.Copied copied = LocalPlaylistsCopy.Copied.load(mContext, "none");
        copied.putPlaylist(id, "PLmix");
        copied.putVideo(id, "aaaaaaaaaaa");
        copied.save();

        int[] after = LocalPlaylistsCopy.countNew(mContext, "none");
        assertEquals(0, after[0]);
        assertEquals(1, after[1]); // only bbbbbbbbbbb is new to the account
    }

    private static PlaylistInfo info(String title, String id, boolean selected) {
        return new PlaylistInfo() {
            @Override public String getTitle() { return title; }
            @Override public String getPlaylistId() { return id; }
            @Override public boolean isSelected() { return selected; }
            @Override public int getSize() { return 0; }
            @Override public int getCurrentIndex() { return 0; }
        };
    }
}
