package com.newtube.mobile.ui.common;

/** NEWTUBE(lazy-home): "does this grid need more cards?" - shared by scrolling and post-update checks. */
public final class FeedRunway {
    private FeedRunway() {
    }

    /**
     * True when fewer than {@code lookahead} cards are left below the last visible one, i.e. the
     * grid holds less than a screen plus the lookahead. {@code lastVisible} is a layout position
     * or RecyclerView.NO_POSITION (-1: nothing laid out yet), so an empty grid is always short.
     */
    public static boolean isShort(int lastVisible, int itemCount, int lookahead) {
        return Math.max(lastVisible, -1) >= itemCount - lookahead;
    }
}
