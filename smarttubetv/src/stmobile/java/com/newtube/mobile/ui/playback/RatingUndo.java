package com.newtube.mobile.ui.playback;

/**
 * NEWTUBE(snackbar): what a rating's Undo taps to put back the rating the video had before the tap.
 * Re-tapping the same thumb is not enough: liked, then Dislike, then Undo would clear the dislike
 * and leave the video unrated instead of liked. Thumbs are toggles, and liking clears a dislike
 * (and the reverse), so one tap always gets there.
 */
final class RatingUndo {
    static final int NONE = 0;
    static final int LIKE = 1;
    static final int DISLIKE = 2;

    private RatingUndo() {
    }

    static int rating(boolean likeOn, boolean dislikeOn) {
        return likeOn ? LIKE : dislikeOn ? DISLIKE : NONE;
    }

    /**
     * The thumb to tap so that {@code now} becomes {@code target}: {@code likeId}, {@code dislikeId},
     * or 0 when they already match.
     */
    static int tapFor(int now, int target, int likeId, int dislikeId) {
        if (now == target) {
            return 0;
        }
        if (target == LIKE) {
            return likeId;
        }
        if (target == DISLIKE) {
            return dislikeId;
        }
        return now == LIKE ? likeId : dislikeId; // back to unrated: switch off the one that is on
    }
}
