package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests {@link MobilePlaybackActivity#canOpenChaptersOnTap} so hidden, fading, or scrub-hidden
 * overlay states cannot open the chapters sheet on an accidental screen touch.
 */
public class ChapterTapGuardTest {

    @Test
    public void opensOnlyWhenControlsAreVisibleSettledAndNotScrubbing() {
        assertTrue(MobilePlaybackActivity.canOpenChaptersOnTap(true, false, false, 1.0f));
        assertTrue(MobilePlaybackActivity.canOpenChaptersOnTap(true, false, false, 0.9f));
    }

    @Test
    public void blocksWhenControlsAreHiddenOrFading() {
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(false, false, false, 1.0f));
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(false, false, false, 0.5f));
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(true, false, false, 0.0f));
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(true, false, false, 0.89f));
    }

    @Test
    public void blocksDuringScrubbingOrHiddenScrubChrome() {
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(true, true, false, 1.0f));
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(true, false, true, 1.0f));
        assertFalse(MobilePlaybackActivity.canOpenChaptersOnTap(true, true, true, 1.0f));
    }
}
