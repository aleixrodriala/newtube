package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** YouTube's quality classes in both orientations, and the square ceiling built from them. */
public class VideoRungTest {
    @Test
    public void landscapeRungsAreTheirHeight() {
        assertEquals(2160, VideoRung.classHeight(3840, 2160));
        assertEquals(1440, VideoRung.classHeight(2560, 1440));
        assertEquals(1080, VideoRung.classHeight(1920, 1080));
        assertEquals(720, VideoRung.classHeight(1280, 720));
        assertEquals(480, VideoRung.classHeight(854, 480));
        assertEquals(360, VideoRung.classHeight(640, 360));
        assertEquals(240, VideoRung.classHeight(426, 240));
        assertEquals(144, VideoRung.classHeight(256, 144));
    }

    @Test
    public void verticalRungsAreNamedLikeYouTubeDoes() {
        assertEquals(1080, VideoRung.classHeight(1080, 1920));
        assertEquals(720, VideoRung.classHeight(720, 1280));
        // The stream YouTube lists as "480p" (itags 779/780): as tall as a 1080p one.
        assertEquals(480, VideoRung.classHeight(608, 1080));
        assertEquals(480, VideoRung.classHeight(480, 854));
    }

    @Test
    public void otherShapes() {
        assertEquals(1080, VideoRung.classHeight(1920, 800));   // 2.40:1
        assertEquals(720, VideoRung.classHeight(1280, 536));
        assertEquals(1080, VideoRung.classHeight(1440, 1080));  // 4:3
        assertEquals(1080, VideoRung.classHeight(1080, 1080));  // square
        assertEquals(1080, VideoRung.classHeight(1916, 1036));  // a crop keeps its class
    }

    @Test
    public void unknownSizesFallBackToTheHeight() {
        assertEquals(1080, VideoRung.classHeight(-1, 1080));
        assertEquals(0, VideoRung.classHeight(-1, -1));
    }

    @Test
    public void theCeilingIsTheClassLongSide() {
        assertEquals(1920, VideoRung.ceilingSide(1080));
        assertEquals(1280, VideoRung.ceilingSide(720));
        assertEquals(854, VideoRung.ceilingSide(480));
        assertEquals(640, VideoRung.ceilingSide(360));
        assertEquals(2560, VideoRung.ceilingSide(1440));
        assertEquals(3840, VideoRung.ceilingSide(2160));
        assertEquals(Integer.MAX_VALUE, VideoRung.ceilingSide(0));
    }

    @Test
    public void theSquareCeilingKeepsBothOrientationsOfItsClassOnly() {
        int side = VideoRung.ceilingSide(1080);
        assertEquals(true, fits(1920, 1080, side));
        assertEquals(true, fits(1080, 1920, side));
        assertEquals(true, fits(720, 1280, side));
        assertEquals(false, fits(2560, 1440, side));
        assertEquals(false, fits(1440, 2560, side));
    }

    private static boolean fits(int width, int height, int side) {
        return width <= side && height <= side; // media3's maxVideoWidth/maxVideoHeight check
    }
}
