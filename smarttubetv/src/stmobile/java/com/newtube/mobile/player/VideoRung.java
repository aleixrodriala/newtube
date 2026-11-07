package com.newtube.mobile.player;

/**
 * NEWTUBE(quality-start): a video stream's quality class ("1080p") the way YouTube labels it, in
 * both orientations. A vertical video's 1080p stream is 1080x1920 and the one YouTube calls 480p
 * is 608x1080, so neither the height nor the width alone names the class: the class is the
 * larger of the short side and the long side's 16:9 equivalent (1920x800 is 1080p, 1440x1080 is
 * 1080p, 608x1080 is 480p), rounded down to the nearest standard class with 10% slack for crops
 * (1916x1036 is still 1080p).
 */
public final class VideoRung {
    private static final int[] CLASSES = {144, 240, 360, 480, 720, 1080, 1440, 2160, 4320};
    private static final float SLACK = 0.9f;

    private VideoRung() {
    }

    /** The class height ("1080" for 1080p); the plain height when a dimension is unknown. */
    public static int classHeight(int width, int height) {
        if (width <= 0 || height <= 0) {
            return Math.max(height, 0);
        }
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        float size = Math.max(shortSide, longSide * 9f / 16f);
        int result = CLASSES[0];
        for (int c : CLASSES) {
            if (size >= c * SLACK) {
                result = c;
            }
        }
        return size < CLASSES[0] * SLACK ? Math.round(size) : result;
    }

    /**
     * The longest side a stream of class {@code classHeight} may have, for a square
     * {@code setMaxVideoSize(side, side)} ceiling that treats both orientations alike: 1080 ->
     * 1920 keeps 1920x1080 and 1080x1920 and drops 2560x1440 and 1440x2560 (480 -> 854, not 853).
     * A box is not the class rule, so odd shapes land a little off in either direction: under a
     * 720p ceiling a square 1080x1080 stream fits and a 4:3 1440x1080 does not; under 480p the
     * 608x1080 variant does not fit (480x854 does). All rare; the 16:9 rungs of both
     * orientations, which is nearly every video, are exact.
     */
    public static int ceilingSide(int classHeight) {
        return classHeight <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(classHeight * 16d / 9d);
    }
}
