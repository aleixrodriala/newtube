package com.newtube.mobile.ui.playback;

import static com.newtube.mobile.ui.playback.RatingUndo.DISLIKE;
import static com.newtube.mobile.ui.playback.RatingUndo.LIKE;
import static com.newtube.mobile.ui.playback.RatingUndo.NONE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** NEWTUBE(snackbar): Undo puts back the rating from before the tap, not just the tapped thumb. */
public class RatingUndoTest {
    private static final int UP = 11;
    private static final int DOWN = 22;

    @Test
    public void likedThenDislikedUndoesBackToLiked() {
        // before LIKE, the tap made it DISLIKE: Undo taps Like (which also clears the dislike)
        assertEquals(UP, RatingUndo.tapFor(DISLIKE, LIKE, UP, DOWN));
    }

    @Test
    public void dislikedThenLikedUndoesBackToDisliked() {
        assertEquals(DOWN, RatingUndo.tapFor(LIKE, DISLIKE, UP, DOWN));
    }

    @Test
    public void aNewRatingUndoesBackToUnrated() {
        assertEquals(UP, RatingUndo.tapFor(LIKE, NONE, UP, DOWN));
        assertEquals(DOWN, RatingUndo.tapFor(DISLIKE, NONE, UP, DOWN));
    }

    @Test
    public void aRemovedRatingComesBack() {
        assertEquals(UP, RatingUndo.tapFor(NONE, LIKE, UP, DOWN));
        assertEquals(DOWN, RatingUndo.tapFor(NONE, DISLIKE, UP, DOWN));
    }

    @Test
    public void nothingToDoWhenAlreadyThere() {
        assertEquals(0, RatingUndo.tapFor(LIKE, LIKE, UP, DOWN));
    }

    @Test
    public void ratingReadsTheThumbs() {
        assertEquals(LIKE, RatingUndo.rating(true, false));
        assertEquals(DISLIKE, RatingUndo.rating(false, true));
        assertEquals(NONE, RatingUndo.rating(false, false));
    }
}
