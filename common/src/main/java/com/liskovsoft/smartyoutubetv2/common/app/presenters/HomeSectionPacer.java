package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.os.SystemClock;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates;

/**
 * NEWTUBE(lazy-home): Home's section list, one page ahead of the reader instead of all at once.
 *
 * <p>Signed-in Home arrives as a first page plus a chain of section-list continuations, and
 * YouTubeContentService used to walk the whole chain back to back: a cold start fired the first
 * /browse and then 6 more, serially, in the ~1.5 s after the first page painted (Pixel 9 release
 * logs, Wi-Fi and LTE) - each one a 20-40 KB brotli body whose JSON parse ran 50-500 ms on a
 * worker, each page a burst of grid updates on the main thread, all of it landing exactly while
 * the first thumbnails load and the user makes the first tap.</p>
 *
 * <p>Now page 2 still follows page 1 without being asked (one page of runway), and every later
 * page waits until the grid asks for it ({@link #demand()}): the view asks whenever it has less
 * than a screen plus a lookahead of cards left - while scrolling, and after every update, so a
 * page whose rows were all filtered out (Shorts, channels, duplicates) or empty pulls the next
 * one straight away. Nothing, page 2 included, is fetched while the Browse view is paused (a
 * video or another screen on top). A walk waits as long as Home stays loaded: a user who comes
 * back to the grid an hour later still gets the remaining sections. It ends only when the list
 * runs out or the load is disposed (section change, refresh, account change) - the presenter
 * calls {@link #wake()} then, and a slow poll bounds any missed wake-up.</p>
 */
final class HomeSectionPacer implements BrowseServiceGates.SectionListPacer {
    /** Pages fetched without being asked: the first page and one page ahead of it. */
    static final int INITIAL_PAGES = 2;
    /** Safety net for a disposal without {@link #wake()}; a parked walk costs one wake-up per poll. */
    static final long POLL_MS = 5_000;
    private static final String LOG = "home-walk";

    interface Clock {
        long nowMs();
    }

    private final Clock mClock;
    private final long mPollMs;
    /** Highest page the walk has been let through to fetch (page 1 is the walk's own request). */
    private int mReleasedPages = 1;
    private int mAllowedPages = INITIAL_PAGES;
    private boolean mViewResumed = true;

    HomeSectionPacer() {
        this(SystemClock::elapsedRealtime, POLL_MS);
    }

    HomeSectionPacer(Clock clock, long pollMs) {
        mClock = clock;
        mPollMs = pollMs;
    }

    /**
     * The grid is short of cards: let one more page through than has been let through so far.
     * Counting from released (not emitted) pages means a demand that arrives while a page is in
     * flight queues the next one instead of being lost, and repeated demands for the same grid
     * state release nothing more.
     */
    synchronized void demand() {
        int allowed = Math.max(mAllowedPages, mReleasedPages + 1);
        if (allowed != mAllowedPages) {
            mAllowedPages = allowed;
            notifyAll();
        }
    }

    synchronized void setViewResumed(boolean resumed) {
        if (mViewResumed != resumed) {
            mViewResumed = resumed;
            notifyAll();
        }
    }

    /** A walk may have been disposed: let a waiting one notice now instead of at its next poll. */
    synchronized void wake() {
        notifyAll();
    }

    @Override
    public boolean awaitNextPage(int groupType, int nextPage, BrowseServiceGates.Disposal disposal) {
        if (groupType != MediaGroup.TYPE_HOME) {
            return true;
        }

        synchronized (this) {
            // A walk that is already gone must not touch the page bookkeeping of the one that
            // replaced it (refresh, account change).
            if (disposal.isDisposed()) {
                return false;
            }

            if (nextPage <= INITIAL_PAGES) {
                // A new walk (the first continuation of a fresh Home load) starts from scratch.
                mReleasedPages = nextPage - 1;
                mAllowedPages = INITIAL_PAGES;
            }

            long startMs = mClock.nowMs();
            boolean logged = false;

            while (true) {
                if (disposal.isDisposed()) {
                    return false;
                }
                if (mViewResumed && nextPage <= mAllowedPages) {
                    mReleasedPages = Math.max(mReleasedPages, nextPage);
                    if (logged) {
                        NetPath.log(LOG + " go page=" + nextPage + " waitedMs=" + (mClock.nowMs() - startMs));
                    }
                    return true;
                }
                if (!logged) {
                    logged = true;
                    NetPath.log(LOG + " wait page=" + nextPage + " reason=" + (mViewResumed ? "no-demand" : "view-paused"));
                }
                try {
                    wait(mPollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
