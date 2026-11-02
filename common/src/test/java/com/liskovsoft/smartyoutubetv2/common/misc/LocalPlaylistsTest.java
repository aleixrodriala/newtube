package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.core.Observable;

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
    public void eachAccountIsOfferedWhatItHasntGot() {
        assertNull(LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT)); // nothing saved yet
        LocalPlaylists.instance(mContext).create("Mix", video("aaaaaaaaaaa"));
        assertArrayEquals(new int[] {1, 0}, LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));

        copy(new FakeYouTube()); // all of it, to ACCOUNT

        assertNull(LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));
        assertArrayEquals(new int[] {1, 0}, LocalPlaylistsCopy.whatToOffer(mContext, "other@example.com"));
    }

    @Test
    public void keepOnPhoneHoldsUntilSomethingMoreIsSaved() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", null);
        LocalPlaylistsCopy.keepOnPhone(mContext, ACCOUNT);
        assertNull(LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));

        playlists.rename(id, "Mix 2"); // nothing new saved
        assertNull(LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));

        LocalWatchLater.instance(mContext).add(video("aaaaaaaaaaa"));
        assertArrayEquals(new int[] {1, 1}, LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));
    }

    @Test
    public void aCopyThatDidntGetEverythingIsOfferedAgain() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.refusedVideos.add("bbbbbbbbbbb");

        copy(youTube);

        assertArrayEquals(new int[] {0, 1}, LocalPlaylistsCopy.whatToOffer(mContext, ACCOUNT));
    }

    @Test
    public void aDamagedFileIsPutAsideNotWrittenOver() {
        SharedPreferences prefs = mContext.getSharedPreferences("newtube_playlists", Context.MODE_PRIVATE);
        prefs.edit().putString("playlists", "[{\"id\":\"NTLOCAL:a\",\"tit").commit();

        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        assertEquals(0, playlists.playlists().size());
        playlists.create("New", null); // the next save writes the list over...

        assertEquals("[{\"id\":\"NTLOCAL:a\",\"tit", prefs.getString("playlists_damaged", null)); // ...not over what it was
    }

    @Test
    public void oneBadEntryCostsOnlyItself() throws Exception {
        JSONArray videos = new JSONArray();
        videos.put(video("aaaaaaaaaaa").toString());
        videos.put("not a video");
        videos.put(video("bbbbbbbbbbb").toString());
        JSONObject good = new JSONObject();
        good.put("id", LocalPlaylists.ID_PREFIX + "good");
        good.put("title", "Good");
        good.put("videos", videos);
        JSONArray array = new JSONArray();
        array.put("not a playlist");
        array.put(good);
        SharedPreferences prefs = mContext.getSharedPreferences("newtube_playlists", Context.MODE_PRIVATE);
        prefs.edit().putString("playlists", array.toString()).commit();

        LocalPlaylists playlists = LocalPlaylists.instance(mContext);

        assertEquals(1, playlists.playlists().size());
        assertEquals(2, playlists.videos(LocalPlaylists.ID_PREFIX + "good").size());
        assertEquals(array.toString(), prefs.getString("playlists_damaged", null));
    }

    @Test
    public void aFullPlaylistSaysSo() throws Exception {
        JSONArray videos = new JSONArray();
        for (int i = 0; i < LocalPlaylists.MAX_VIDEOS; i++) {
            videos.put(video(String.format(Locale.US, "v%010d", i)).toString());
        }
        JSONObject full = new JSONObject();
        full.put("id", LocalPlaylists.ID_PREFIX + "full");
        full.put("title", "Full");
        full.put("videos", videos);
        mContext.getSharedPreferences("newtube_playlists", Context.MODE_PRIVATE).edit()
                .putString("playlists", new JSONArray().put(full).toString()).commit();
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String other = playlists.create("Other", null);

        assertTrue(playlists.isFull(LocalPlaylists.ID_PREFIX + "full"));
        assertFalse(playlists.isFull(other));
        playlists.add(LocalPlaylists.ID_PREFIX + "full", video("aaaaaaaaaaa"));
        assertFalse(playlists.contains(LocalPlaylists.ID_PREFIX + "full", "aaaaaaaaaaa"));
    }

    @Test
    public void everyChangeMovesTheChangeCountAndReadingDoesNot() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        int start = playlists.changes();
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        playlists.remove(id, "aaaaaaaaaaa");
        playlists.rename(id, "Mix 2");
        int changed = playlists.changes();

        playlists.videos(id);
        playlists.cards(mContext);
        playlists.add(id, video("bbbbbbbbbbb")); // already in it: no change

        assertEquals(start + 4, changed);
        assertEquals(changed, playlists.changes());
    }

    @Test
    public void aYouTubeIdIsNeverLocal() {
        assertFalse(LocalPlaylists.isLocalId("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"));
        assertFalse(LocalPlaylists.isLocalId("WL"));
        assertFalse(LocalPlaylists.isLocalId(null));
    }

    @Test
    public void theCopyFindsThePlaylistItJustMade() {
        List<PlaylistInfo> before = Arrays.asList(
                info("Watch later", "WL", false),
                info("Road trip", PL_OLD, true)); // same name, and already holds the video
        List<PlaylistInfo> after = Arrays.asList(
                info("Watch later", "WL", false),
                info("Road trip", PL_OLD, true),
                info("Road trip", PL_NEW, true),
                info("Other", PL_OTHER, true));

        assertEquals(PL_NEW, LocalPlaylistsCopy.findCreated(after, "Road trip", before));
        assertNull(LocalPlaylistsCopy.findCreated(after, "Missing", before));
        assertNull(LocalPlaylistsCopy.findCreated(Arrays.asList(info("Watch later", "WL", true)), "Watch later", null));
        assertNull(LocalPlaylistsCopy.findCreated(null, "Road trip", before));
        // The wrapper's stand-in for a playlist YouTube refused to make (a made-up id) is never it.
        assertNull(LocalPlaylistsCopy.findCreated(Arrays.asList(
                info("Watch later", "WL", false), info("Road trip", "Ab3dE6gH9jK1", true)), "Road trip", before));
    }

    @Test
    public void aPlaylistCopiedEmptyIsFoundByNameLater() {
        List<PlaylistInfo> infos = Arrays.asList(
                info("Watch later", "WL", false),
                info("Road trip", PL_OLD, false)); // holds none of the new videos yet

        assertEquals(PL_OLD, LocalPlaylistsCopy.findByTitle(infos, "Road trip"));
        assertNull(LocalPlaylistsCopy.findCreated(infos, "Road trip", null));
    }

    @Test
    public void onlyYouTubesOwnAnswerConfirms() {
        List<PlaylistInfo> youTube = Arrays.asList(info("Watch later", "WL", false), info("Mix", PL_NEW, true));
        // When YouTube's answer fails, MediaServiceCore answers from its own copies, the playlist
        // touched last first, "holding" what it was last told to add.
        List<PlaylistInfo> standIn = Arrays.asList(info("Mix", PL_NEW, true), info("Watch later", "WL", false));

        assertTrue(LocalPlaylistsCopy.holds(youTube, PL_NEW));
        assertFalse(LocalPlaylistsCopy.holds(youTube, "WL"));
        assertFalse(LocalPlaylistsCopy.holds(standIn, PL_NEW));
        assertFalse(LocalPlaylistsCopy.holds(null, PL_NEW));
    }

    @Test
    public void aVideoYouTubeRefusesIsNotMarkedCopied() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        playlists.add(id, video("ccccccccccc"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.refusedVideos.add("bbbbbbbbbbb"); // a private video, say: the add "succeeds" all the same

        LocalPlaylistsCopy.Outcome outcome = copy(youTube);

        assertEquals(1, outcome.playlists());
        assertEquals(2, outcome.videos());
        assertEquals(1, outcome.failedVideos());
        assertEquals(0, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[0]);
        assertEquals(1, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]); // still to copy

        youTube.refusedVideos.clear();
        LocalPlaylistsCopy.Outcome again = copy(youTube);

        assertEquals(0, again.playlists()); // the playlist itself is not made twice
        assertEquals(1, again.videos());
        assertEquals(1, youTube.made);
        assertEquals(0, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]);
    }

    @Test
    public void theNewPlaylistIsTheOneMadeNotAnOlderNamesake() {
        FakeYouTube youTube = new FakeYouTube();
        String older = youTube.playlist("Road trip", "aaaaaaaaaaa");
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Road trip", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));

        copy(youTube);

        assertEquals(2, youTube.order.size() - 1);
        String made = youTube.order.get(1); // YouTube lists the newest first
        assertTrue(youTube.contents.get(made).contains("bbbbbbbbbbb"));
        assertFalse(youTube.contents.get(older).contains("bbbbbbbbbbb"));
    }

    @Test
    public void aMakeYouTubeRefusesIsNotTakenForTheNewPlaylist() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.refuseMakes = true;

        LocalPlaylistsCopy.Outcome outcome = copy(youTube);

        assertEquals(0, outcome.playlists());
        assertEquals(1, outcome.failedPlaylists());
        assertEquals(0, youTube.adds); // nothing went into the wrapper's stand-in
        assertEquals(1, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[0]); // still to copy
    }

    @Test
    public void aCopyCutShortAfterTheCreateNeverMakesItTwice() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.failLookupAfterMake = true; // the create went through, the lookup after it didn't

        assertEquals(1, copy(youTube).failedPlaylists());
        LocalPlaylistsCopy.Outcome again = copy(youTube);

        assertEquals(1, youTube.made);
        assertEquals(2, again.videos()); // the first one found in it, the second one added
        assertTrue(youTube.contents.get(youTube.order.get(1)).containsAll(Arrays.asList("aaaaaaaaaaa", "bbbbbbbbbbb")));
        assertEquals(0, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]);
    }

    @Test
    public void aCreateThatNeverHappenedIsMadeAgain() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        playlists.add(id, video("bbbbbbbbbbb"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.refuseMakes = true;
        youTube.failLookupAfterMake = true; // so this run can't tell it was refused

        copy(youTube);
        youTube.refuseMakes = false;
        LocalPlaylistsCopy.Outcome again = copy(youTube);

        assertEquals(1, again.playlists());
        assertEquals(2, again.videos());
        assertEquals(2, youTube.order.size()); // Watch later and the one Mix
        assertEquals(0, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[0]);
        assertEquals(0, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]);
    }

    @Test
    public void watchLaterIsCopiedVideoByVideo() {
        LocalWatchLater watchLater = LocalWatchLater.instance(mContext);
        watchLater.add(video("aaaaaaaaaaa"));
        watchLater.add(video("bbbbbbbbbbb"));
        FakeYouTube youTube = new FakeYouTube();
        youTube.refusedVideos.add("bbbbbbbbbbb");

        LocalPlaylistsCopy.Outcome outcome = copy(youTube);

        assertEquals(1, outcome.videos());
        assertEquals(1, outcome.failedVideos());
        assertTrue(youTube.contents.get("WL").contains("aaaaaaaaaaa"));
        assertEquals(1, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]);
    }

    @Test
    public void anAnswerThatIsntYouTubesConfirmsNothing() {
        LocalPlaylists playlists = LocalPlaylists.instance(mContext);
        String id = playlists.create("Mix", video("aaaaaaaaaaa"));
        FakeYouTube youTube = new FakeYouTube();
        copy(youTube);
        playlists.add(id, video("bbbbbbbbbbb"));
        youTube.standInAnswers = true; // too many requests, say

        LocalPlaylistsCopy.Outcome outcome = copy(youTube);

        assertEquals(0, outcome.videos());
        assertEquals(1, outcome.failedVideos());
        assertEquals(1, LocalPlaylistsCopy.countNew(mContext, ACCOUNT)[1]);
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

    private static final String ACCOUNT = "someone@example.com";
    private static final String PL_OLD = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";
    private static final String PL_NEW = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG";
    private static final String PL_OTHER = "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i";

    private LocalPlaylistsCopy.Outcome copy(FakeYouTube youTube) {
        LocalPlaylistsCopy.Outcome outcome = new LocalPlaylistsCopy.Outcome();
        LocalPlaylistsCopy.copy(mContext, youTube.service(), ACCOUNT, outcome).blockingAwait();
        return outcome;
    }

    /**
     * YouTube as the copy sees it through MediaServiceCore: an add or a make YouTube refuses still
     * completes (the answer is dropped), and a refused make leaves the wrapper's stand-in, with a
     * made-up 12-character id, in every later list.
     */
    private static final class FakeYouTube implements InvocationHandler {
        /** Playlist ids as YouTube lists them: Watch later, then the newest first. */
        final List<String> order = new ArrayList<>(Collections.singletonList("WL"));
        final Map<String, String> titles = new HashMap<>();
        final Map<String, Set<String>> contents = new HashMap<>();
        final Map<String, String> standIns = new LinkedHashMap<>();
        final Set<String> refusedVideos = new HashSet<>();
        boolean refuseMakes;
        /** The lookup after the next create fails (too many requests, say). */
        boolean failLookupAfterMake;
        private boolean mFailNextLookup;
        /** YouTube's answer fails, so the wrapper answers from its stand-ins alone. */
        boolean standInAnswers;
        int made;
        int adds;
        private String mTouched = "WL";

        FakeYouTube() {
            titles.put("WL", "Watch later");
            contents.put("WL", new HashSet<>());
        }

        String playlist(String title, String... videoIds) {
            String id = String.format(Locale.US, "PL%032d", titles.size());
            order.add(1, id);
            titles.put(id, title);
            contents.put(id, new HashSet<>(Arrays.asList(videoIds)));
            return id;
        }

        MediaItemService service() {
            return (MediaItemService) Proxy.newProxyInstance(MediaItemService.class.getClassLoader(),
                    new Class<?>[] {MediaItemService.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getPlaylistsInfoObserve":
                    if (mFailNextLookup) {
                        mFailNextLookup = false;
                        return Observable.error(new IllegalStateException("429"));
                    }
                    return Observable.just(list((String) args[0]));
                case "addToPlaylistObserve":
                    adds++;
                    mTouched = (String) args[0];
                    if (contents.containsKey(mTouched) && !refusedVideos.contains((String) args[1])) {
                        contents.get(mTouched).add((String) args[1]);
                    }
                    return Observable.empty();
                case "createPlaylistObserve":
                    made++;
                    mFailNextLookup = failLookupAfterMake;
                    failLookupAfterMake = false;
                    String first = args[1] instanceof String ? (String) args[1] : null;
                    if (refuseMakes) {
                        standIns.put(String.format(Locale.US, "Ab3dE6gH9j%02d", made), (String) args[0]);
                        contents.put(String.format(Locale.US, "Ab3dE6gH9j%02d", made),
                                first != null ? new HashSet<>(Collections.singleton(first)) : new HashSet<>());
                    } else {
                        mTouched = first != null ? playlist((String) args[0], first) : playlist((String) args[0]);
                    }
                    return Observable.empty();
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }

        private List<PlaylistInfo> list(String videoId) {
            List<PlaylistInfo> result = new ArrayList<>();
            for (String id : order) {
                result.add(info(titles.get(id), id, standInAnswers || contents.get(id).contains(videoId)));
            }
            for (Map.Entry<String, String> standIn : standIns.entrySet()) { // after Watch later, as the wrapper puts them
                result.add(1, info(standIn.getValue(), standIn.getKey(), contents.get(standIn.getKey()).contains(videoId)));
            }
            if (standInAnswers) { // the playlist touched last comes first
                PlaylistInfo touched = null;
                for (PlaylistInfo info : result) {
                    if (info.getPlaylistId().equals(mTouched)) {
                        touched = info;
                    }
                }
                result.remove(touched);
                result.add(0, touched);
            }
            return result;
        }
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
