package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import android.app.Application;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24,28,35}, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueueSnapshotTest {
    private Playlist queue;
    @Before public void before() { queue = Playlist.instance(); queue.clear(); }
    @After public void after() { queue.clear(); }
    private Video add(String id) { Video v = Video.from(id); v.fromQueue = true; queue.add(v); return v; }
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test public void neverSerializesUrlsCredentialsOrPrivateItemsAndBoundsDisplayText() throws Exception {
        Video a = add("aaaaaaaaaaa"); a.title = String.join("", Collections.nCopies(500,"x"));
        a.cardImageUrl = "https://example.invalid/private?token=DO_NOT_SAVE";
        a.playlistId = "PRIVATE_PLAYLIST_DO_NOT_SAVE";
        Video b = add("bbbbbbbbbbb"); b.isRemote = true;
        Video c = add("ccccccccccc"); c.incognito = true;
        add("invalid/id"); queue.setCurrent(b);
        byte[] data = QueueSnapshot.capture(queue, Collections.emptySet()).encode();
        String json = new String(data, StandardCharsets.UTF_8);
        assertFalse(json.contains("DO_NOT_SAVE")); assertFalse(json.contains("https://"));
        QueueSnapshot saved = QueueSnapshot.decode(data); assertNotNull(saved);
        assertEquals(1, saved.videos.size()); assertEquals(300, saved.videos.get(0).title.length());
        assertEquals(-1, saved.current); assertTrue(saved.videos.get(0).cardImageUrl.contains("aaaaaaaaaaa"));
    }
    @Test public void malformedVersionsIndicesTypesAndIdsAreRejected() throws Exception {
        Video a = add("aaaaaaaaaaa"); queue.setCurrent(a);
        JSONObject root = new JSONObject(new String(QueueSnapshot.capture(queue, Collections.emptySet()).encode(), StandardCharsets.UTF_8));
        String good = root.toString();
        root.put("version", "1"); assertNull(QueueSnapshot.decode(bytes(root.toString())));
        root = new JSONObject(good); root.put("current", 5); assertNull(QueueSnapshot.decode(bytes(root.toString())));
        root = new JSONObject(good); root.getJSONArray("items").getJSONObject(0).put("id", "../unsafe");
        assertNull(QueueSnapshot.decode(bytes(root.toString())));
        assertNull(QueueSnapshot.decode(bytes(good.substring(0,good.length()-1))));
        assertNull(QueueSnapshot.decode(bytes(good + " trailing")));
        assertNull(QueueSnapshot.decode(new byte[QueueSnapshot.MAX_BYTES + 1]));
    }
    @Test public void rejectsMoreThanTheBoundedItemCount() throws Exception {
        Video a = add("aaaaaaaaaaa");
        JSONObject root = new JSONObject(new String(QueueSnapshot.capture(queue, Collections.emptySet()).encode(), StandardCharsets.UTF_8));
        JSONObject item = root.getJSONArray("items").getJSONObject(0);
        JSONArray items = new JSONArray(); for(int i=0;i<=QueueSnapshot.MAX_ITEMS;i++) items.put(item);
        root.put("items",items); assertNull(QueueSnapshot.decode(bytes(root.toString())));
    }
    @Test public void upcomingRowsWithoutRoutingFlagsAreStillSavedAsAQueue() throws Exception {
        Video a = Video.from("aaaaaaaaaaa"), b = Video.from("bbbbbbbbbbb");
        queue.addAll(Arrays.asList(a,b)); queue.setCurrent(a);
        assertFalse(queue.hasExplicitQueue()); assertTrue(queue.hasNext());
        QueueSnapshot saved = QueueSnapshot.decode(QueueSnapshot.capture(queue, Collections.emptySet()).encode());
        assertNotNull(saved); assertEquals(2, saved.videos.size()); assertEquals(0,saved.current);
    }
}
