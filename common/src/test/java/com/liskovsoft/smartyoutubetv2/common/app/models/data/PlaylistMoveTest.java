package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaylistMoveTest {
    private Playlist mQueue;
    private Video mA;
    private Video mB;
    private Video mC;
    private Video mD;

    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        video.title = id;
        return video;
    }

    @Before
    public void setUp() {
        mQueue = Playlist.instance();
        mQueue.clear();
        mA = video("aaaaaaaaaaa");
        mB = video("bbbbbbbbbbb");
        mC = video("ccccccccccc");
        mD = video("ddddddddddd");
        mQueue.addAll(Arrays.asList(mA, mB, mC, mD));
        mQueue.setCurrent(mB);
    }

    @Test
    public void movingLaterVideosKeepsThePlayingOne() {
        mQueue.move(3, 2); // D before C

        assertEquals(Arrays.asList(mA, mB, mD, mC), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertSame(mD, mQueue.getNext());
    }

    @Test
    public void movingPastThePlayingOneFollowsIt() {
        mQueue.move(0, 3); // A to the end: B is now first

        assertEquals(Arrays.asList(mB, mC, mD, mA), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertEquals(0, mQueue.getCurrentIndex());

        mQueue.move(3, 0); // and back in front of it
        assertSame(mB, mQueue.getCurrent());
        assertEquals(1, mQueue.getCurrentIndex());
    }

    @Test
    public void outOfRangeDoesNothing() {
        mQueue.move(-1, 2);
        mQueue.move(1, 9);
        mQueue.move(2, 2);

        assertEquals(Arrays.asList(mA, mB, mC, mD), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
    }
}
