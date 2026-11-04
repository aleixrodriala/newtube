package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import android.app.Application;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueueCardPolicyTest {
    private Video a, b;
    private Map<Integer, VideoGroup> groups;
    private static Video video(String id) { Video v = new Video(); v.videoId = id; return v; }
    @Before public void before() {
        a = video("aaaaaaaaaaa"); b = video("bbbbbbbbbbb");
        Playlist.instance().clear(); Playlist.instance().addAll(Arrays.asList(a, b)); Playlist.instance().setCurrent(a);
        VideoGroup group = VideoGroup.from(Arrays.asList(b)); group.setType(MediaGroup.TYPE_PLAYBACK_QUEUE);
        groups = new HashMap<>(); groups.put(123, group);
    }
    @After public void after() { Playlist.instance().clear(); }
    @Test public void manualQueueWorksWithoutPlaylistIdOrCurrentVideoInItsGroup() {
        assertNull(a.getPlaylistId());
        assertEquals(Integer.valueOf(123), QueueCardPolicy.localGroupId(a, groups));
    }
    @Test public void exhaustedQueueDoesNotReuseAnObsoleteUpcomingGroup() {
        Playlist.instance().setCurrent(b);
        assertNull(QueueCardPolicy.localGroupId(b, groups));
    }
    @Test public void explicitQueueKeepsItsGroupAtTheLastVideoAfterLaunchFlagIsConsumed() {
        b.fromQueue = true; Playlist.instance().add(b);
        b.fromQueue = false; Playlist.instance().setCurrent(b);
        assertEquals(Integer.valueOf(123), QueueCardPolicy.localGroupId(b, groups));
        Playlist.instance().clearExceptCurrent();
        assertNull(QueueCardPolicy.localGroupId(b, groups));
    }
    @Test public void anotherVideoAndEmptyQueueCannotClaimTheQueueCard() {
        assertNull(QueueCardPolicy.localGroupId(video("ccccccccccc"), groups));
        Playlist.instance().clear(); assertNull(QueueCardPolicy.localGroupId(a, groups));
    }
    @Test public void changedInstancesAreNotMistakenForFreshCachedRows() {
        assertTrue(QueueCardPolicy.sameItems(Arrays.asList(a, b), Arrays.asList(a, b)));
        assertFalse(QueueCardPolicy.sameItems(Arrays.asList(a, b), Arrays.asList(a, video(b.videoId))));
        assertFalse(QueueCardPolicy.sameItems(null, Arrays.asList(b)));
    }
}
