package com.liskovsoft.smartyoutubetv2.common.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Locks {@link ClickbaitRemover#fitThumbnail} to the two rules that keep it safe.
 *
 * <p>It exists because InnerTube hands back sddefault (640x480, ~114 KB) for every card, and a
 * watch page binds a dozen of those at once - ~1.3 MB of thumbnails contending with the first
 * media chunks on a phone link. Downsizing is worth real seconds, but only while these hold:</p>
 *
 * <ul>
 *   <li>never widen - hq720/maxresdefault are not generated for every video, so "upgrading" 404s;</li>
 *   <li>never touch hq1/hq2/hq3 - those are the clickbait remover's start/middle/end FRAME
 *       selectors, not sizes, and rewriting one silently undoes that feature.</li>
 * </ul>
 */
public class ClickbaitRemoverFitTest {
    private static final String BASE = "https://i.ytimg.com/vi/aqz-KE-bpKQ/";

    @Test
    public void narrowsToTheSmallestRenditionThatStillCoversTheView() {
        // 160dp row at 3x = 480px -> hqdefault (480 wide) is an exact fit, not sddefault.
        assertEquals(BASE + "hqdefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "sddefault.jpg", 480));
        // A narrower target may drop another step.
        assertEquals(BASE + "mqdefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "sddefault.jpg", 320));
        // Exactly on a boundary picks that rendition, not the one above it.
        assertEquals(BASE + "hqdefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "hq720.jpg", 480));
    }

    @Test
    public void neverWidens() {
        // Already narrower than the target: leave it rather than "upgrade" into a 404-prone size.
        assertEquals(BASE + "mqdefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "mqdefault.jpg", 640));
        assertEquals(BASE + "hqdefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "hqdefault.jpg", 480));
        // Wider than every safe rendition (a full-width feed card on a tall phone): keep the original.
        assertEquals(BASE + "sddefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "sddefault.jpg", 1080));
        assertEquals(BASE + "maxresdefault.jpg",
                ClickbaitRemover.fitThumbnail(BASE + "maxresdefault.jpg", 1080));
    }

    @Test
    public void leavesClickbaitFrameSelectorsAlone() {
        // hq1/hq2/hq3 are frames, not sizes - and are 480x360 already, i.e. the right size anyway.
        for (String frame : new String[] {"hq1", "hq2", "hq3"}) {
            assertEquals(BASE + frame + ".jpg", ClickbaitRemover.fitThumbnail(BASE + frame + ".jpg", 320));
        }
    }

    @Test
    public void passesThroughUrlsItDoesNotRecognise() {
        // DeArrow covers and other hosts carry no rendition token.
        String dearrow = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=aqz-KE-bpKQ";
        assertEquals(dearrow, ClickbaitRemover.fitThumbnail(dearrow, 480));
        assertNull(ClickbaitRemover.fitThumbnail(null, 480));
        // A non-positive target means "no idea how big the view is" - do nothing.
        assertEquals(BASE + "sddefault.jpg", ClickbaitRemover.fitThumbnail(BASE + "sddefault.jpg", 0));
    }

    @Test
    public void rewritesOnlyTheRenditionSegment() {
        // The video id itself can contain a token-like substring; only the /<size>. segment moves.
        String tricky = "https://i.ytimg.com/vi/hq720-abc/sddefault.jpg";
        assertEquals("https://i.ytimg.com/vi/hq720-abc/hqdefault.jpg",
                ClickbaitRemover.fitThumbnail(tricky, 480));
    }
}
