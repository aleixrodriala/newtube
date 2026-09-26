package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

import org.junit.Before;
import org.junit.Test;

/** Only the automatic history resume snaps, only onto the requested segment's own start. */
public class ResumeSeekSnapTest {
    private final ResumeSeekSnap snap = new ResumeSeekSnap();

    @Before
    public void setUp() {
        snap.onPrepare();
    }

    @Test
    public void eligibility() {
        assertTrue(ResumeSeekSnap.isEligible(260_000, 600_000, false));
        assertTrue(ResumeSeekSnap.isEligible(260_000, -1, false)); // duration not known yet
        assertFalse(ResumeSeekSnap.isEligible(260_000, 600_000, true)); // live: exact
        assertFalse(ResumeSeekSnap.isEligible(0, 600_000, false));
        assertFalse(ResumeSeekSnap.isEligible(599_500, 600_000, false)); // "ended" resume
    }

    @Test
    public void snapsOntoTheStartOfTheSegmentThatContainsTheTarget() {
        assertTrue(snap.onResumeRequest(260_000, 600_000, false));
        assertEquals(ResumeSeekSnap.SNAP, snap.onVideoChunkStart(256_780, 262_100, 260_000));
        assertFalse(snap.isArmed());
        assertEquals(260_000, snap.awaitingTargetMs());
        snap.onReported();
        assertEquals(C.TIME_UNSET, snap.awaitingTargetMs());
    }

    @Test
    public void aSegmentThatDoesNotContainTheTargetKeepsItArmed() {
        snap.onResumeRequest(260_000, 600_000, false);
        // a stale/other request (e.g. the pre-seek position 0) proves nothing about our segment
        assertEquals(ResumeSeekSnap.NONE, snap.onVideoChunkStart(0, 5_340, 260_000));
        assertEquals(ResumeSeekSnap.NONE, snap.onVideoChunkStart(262_100, 267_400, 260_000));
        assertEquals(ResumeSeekSnap.NONE, snap.onVideoChunkStart(C.TIME_UNSET, C.TIME_UNSET, 260_000));
        assertTrue(snap.isArmed());
        assertEquals(ResumeSeekSnap.SNAP, snap.onVideoChunkStart(256_780, 262_100, 260_000));
    }

    @Test
    public void nearAndFarSegmentStartsKeepTheExactPosition() {
        snap.onResumeRequest(260_000, 600_000, false);
        assertEquals(ResumeSeekSnap.SKIP_NEAR, snap.onVideoChunkStart(259_700, 265_000, 260_000));
        snap.onResumeRequest(260_000, 600_000, false);
        // an unusually long segment: rewinding 12 s is worse than decoding
        assertEquals(ResumeSeekSnap.SKIP_FAR, snap.onVideoChunkStart(248_000, 270_000, 260_000));
        assertEquals(260_000, snap.historyPositionMs(260_000));
        assertEquals(C.TIME_UNSET, snap.awaitingTargetMs());
    }

    @Test
    public void audioRequestAlreadyWellUnderWayKeepsTheExactPosition() {
        snap.onResumeRequest(260_000, 600_000, false);
        assertEquals(ResumeSeekSnap.SKIP_AUDIO, snap.onVideoChunkStart(256_780, 262_100, 260_000, 400));
        snap.onResumeRequest(260_000, 600_000, false);
        assertEquals(ResumeSeekSnap.SNAP, snap.onVideoChunkStart(256_780, 262_100, 260_000, 12));
        snap.onResumeRequest(260_000, 600_000, false);
        assertEquals(ResumeSeekSnap.SNAP, snap.onVideoChunkStart(256_780, 262_100, 260_000, -1));
    }

    @Test
    public void ownSeeksDoNotCancelButAnyOtherSeekDoes() {
        snap.onResumeRequest(260_000, 600_000, false);
        snap.noteOwnSeek(260_000);
        assertFalse(snap.onSeekDiscontinuity(260_000)); // our exact resume seek
        assertTrue(snap.isArmed());
        assertTrue(snap.onSeekDiscontinuity(260_500)); // media session / double-tap / scrub
        assertFalse(snap.isArmed());
        assertEquals(ResumeSeekSnap.NONE, snap.onVideoChunkStart(256_780, 262_100, 260_500));
    }

    @Test
    public void externalSeekToTheSamePositionAfterOursIsStillExternal() {
        snap.onResumeRequest(260_000, 600_000, false);
        snap.noteOwnSeek(260_000);
        snap.onSeekDiscontinuity(260_000);
        assertTrue(snap.onSeekDiscontinuity(260_000)); // no own seek pending any more
        assertFalse(snap.isArmed());
    }

    @Test
    public void readyExpiresTheArm() {
        snap.onResumeRequest(260_000, 600_000, false);
        assertTrue(snap.onReady());
        assertFalse(snap.onReady());
        assertEquals(ResumeSeekSnap.NONE, snap.onVideoChunkStart(256_780, 262_100, 260_000));
    }

    @Test
    public void playheadMovedBehindOurBackDropsTheSnap() {
        snap.onResumeRequest(260_000, 600_000, false);
        assertEquals(ResumeSeekSnap.DROPPED_MOVED, snap.onVideoChunkStart(256_780, 262_100, 270_000));
        assertFalse(snap.isArmed());
    }

    @Test
    public void historyNeverDropsBelowTheUnwatchedTarget() {
        snap.onResumeRequest(260_000, 600_000, false);
        snap.onVideoChunkStart(256_780, 262_100, 260_000);
        assertEquals(260_000, snap.historyPositionMs(256_780)); // opened and left at once
        assertEquals(260_000, snap.historyPositionMs(259_000));
        assertEquals(261_000, snap.historyPositionMs(261_000)); // watched past it
        assertEquals(257_000, snap.historyPositionMs(257_000)); // floor released for good
    }

    @Test
    public void otherSeekReleasesTheHistoryFloor() {
        snap.onResumeRequest(260_000, 600_000, false);
        snap.onVideoChunkStart(256_780, 262_100, 260_000);
        snap.onSeekDiscontinuity(10_000); // the user went back to the start
        assertEquals(10_000, snap.historyPositionMs(10_000));
    }

    @Test
    public void newSourceForgetsEverything() {
        snap.onResumeRequest(260_000, 600_000, false);
        snap.onVideoChunkStart(256_780, 262_100, 260_000);
        snap.noteOwnSeek(260_000);
        snap.onPrepare();
        assertEquals(C.TIME_UNSET, snap.awaitingTargetMs());
        assertEquals(1_000, snap.historyPositionMs(1_000));
        assertTrue(snap.onSeekDiscontinuity(260_000) == false); // nothing armed: no state to cancel
    }

    @Test
    public void ineligibleRequestClearsAnEarlierArm() {
        snap.onResumeRequest(260_000, 600_000, false);
        assertFalse(snap.onResumeRequest(0, 600_000, false));
        assertFalse(snap.isArmed());
    }
}
