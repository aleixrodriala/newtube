package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        assertTrue(mQueue.move(mD, 2)); // D before C

        assertEquals(Arrays.asList(mA, mB, mD, mC), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertSame(mD, mQueue.getNext());
    }

    @Test
    public void movingPastThePlayingOneFollowsIt() {
        assertTrue(mQueue.move(mA, 3)); // A to the end: B is now first

        assertEquals(Arrays.asList(mB, mC, mD, mA), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertEquals(0, mQueue.getCurrentIndex());

        assertTrue(mQueue.move(mA, 0)); // and back in front of it
        assertSame(mB, mQueue.getCurrent());
        assertEquals(1, mQueue.getCurrentIndex());
    }

    @Test
    public void stillMovesThatVideoAfterTheListShifted() {
        mQueue.remove(mA); // as trimPlaylist drops the oldest: every index moves down one

        assertTrue(mQueue.move(mD, 1));

        assertEquals(Arrays.asList(mB, mD, mC), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
    }

    @Test
    public void anEqualCopyMovesNothing() {
        Video copyOfC = video("ccccccccccc");
        assertEquals(mC, copyOfC);

        assertFalse(mQueue.move(copyOfC, 3));

        assertEquals(Arrays.asList(mA, mB, mC, mD), mQueue.getAll());
    }

    @Test
    public void outOfRangeDoesNothing() {
        assertFalse(mQueue.move(mC, -1));
        assertFalse(mQueue.move(mC, 9));

        assertEquals(Arrays.asList(mA, mB, mC, mD), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
    }

    @Test
    public void removesOnlyWhatComesNext() {
        assertFalse(mQueue.removeAfterCurrent(mA)); // already played: Previous needs it
        assertFalse(mQueue.removeAfterCurrent(mB)); // playing
        assertFalse(mQueue.removeAfterCurrent(video("ccccccccccc"))); // an equal copy

        assertTrue(mQueue.removeAfterCurrent(mC));

        assertEquals(Arrays.asList(mA, mB, mD), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertSame(mA, mQueue.getPrevious());
    }

    @Test
    public void clearingTheQueueKeepsHistoryAndUndoPutsItBack() {
        List<Video> cleared = new ArrayList<>(mQueue.getAllAfterCurrent());
        mQueue.removeAllAfterCurrent();

        assertEquals(Arrays.asList(mA, mB), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
        assertSame(mA, mQueue.getPrevious());
        assertFalse(mQueue.hasNext());

        mQueue.addAllAfterCurrent(cleared);

        assertEquals(Arrays.asList(mA, mB, mC, mD), mQueue.getAll());
        assertSame(mB, mQueue.getCurrent());
    }

    @Test
    public void undoLeavesAVideoQueuedAgainWhereItIs() {
        List<Video> cleared = new ArrayList<>(mQueue.getAllAfterCurrent());
        mQueue.removeAllAfterCurrent();
        Video queuedAgain = video("ddddddddddd"); // D added from another card before Undo
        mQueue.add(queuedAgain);

        mQueue.addAllAfterCurrent(cleared);

        assertEquals(4, mQueue.getAll().size());
        assertSame(mC, mQueue.getAll().get(2));
        assertSame(queuedAgain, mQueue.getAll().get(3));
        assertSame(mB, mQueue.getCurrent());
    }
}
