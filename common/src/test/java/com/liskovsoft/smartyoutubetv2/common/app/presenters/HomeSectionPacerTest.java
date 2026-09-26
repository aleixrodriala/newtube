package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Home's section list: page 1 + one ahead up front, later pages only when the grid asks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class HomeSectionPacerTest {
    private static final long NO_POLL = 60_000;
    private final ExecutorService mWalker = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        mWalker.shutdownNow();
    }

    @Test
    public void otherSectionListsAreNeverPaced() {
        HomeSectionPacer pacer = pacer(NO_POLL);
        AtomicBoolean disposed = new AtomicBoolean();
        pacer.setViewResumed(false);

        assertTrue(pacer.awaitNextPage(MediaGroup.TYPE_MUSIC, 7, disposed::get));
        assertTrue(pacer.awaitNextPage(MediaGroup.TYPE_CHANNEL, 3, disposed::get));
    }

    @Test
    public void firstContinuationIsFetchedRightAway() {
        HomeSectionPacer pacer = pacer(NO_POLL);

        assertTrue(pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false));
    }

    @Test
    public void laterPagesWaitForTheGridAndEachDemandReleasesOnePage() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);

        Future<Boolean> page3 = walk(pacer, 3, new AtomicBoolean());
        assertStillWaiting(page3);
        pacer.demand();
        assertTrue(page3.get(2, TimeUnit.SECONDS));

        Future<Boolean> page4 = walk(pacer, 4, new AtomicBoolean());
        assertStillWaiting(page4);
        pacer.demand();
        assertTrue(page4.get(2, TimeUnit.SECONDS));

        Future<Boolean> page5 = walk(pacer, 5, new AtomicBoolean());
        assertStillWaiting(page5);
        page5.cancel(true);
    }

    @Test
    public void repeatedDemandsForTheSameGridReleaseOnlyOnePage() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);

        pacer.demand();
        pacer.demand(); // several shelves of one page each reporting a short grid
        pacer.demand();

        assertTrue(walk(pacer, 3, new AtomicBoolean()).get(2, TimeUnit.SECONDS));
        assertStillWaiting(walk(pacer, 4, new AtomicBoolean()));
    }

    @Test
    public void aDemandWhileAPageIsStillInFlightQueuesTheNextOne() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);

        pacer.demand(); // page 2 has not landed yet; the walker is not waiting for page 3 yet

        assertTrue(walk(pacer, 3, new AtomicBoolean()).get(2, TimeUnit.SECONDS));
        assertStillWaiting(walk(pacer, 4, new AtomicBoolean()));
    }

    @Test
    public void aGridThatStaysShortKeepsPullingPagesUntilTheListEnds() throws Exception {
        // Pages whose rows are all filtered out do not grow the grid; the view's post-update check
        // (or the presenter, for a page with no rows) demands again after each of them.
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);

        for (int page = 3; page <= 8; page++) {
            pacer.demand(); // the page before this one added nothing visible
            assertTrue("page " + page, walk(pacer, page, new AtomicBoolean()).get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void nothingIsFetchedWhileTheViewIsPaused() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);
        pacer.setViewResumed(false);

        Future<Boolean> page3 = walk(pacer, 3, new AtomicBoolean());
        pacer.demand(); // asked for just before the player opened
        assertStillWaiting(page3);

        pacer.setViewResumed(true);
        assertTrue(page3.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void pageTwoAlsoWaitsWhileTheViewIsPaused() throws Exception {
        // A cached card tapped before page 1 arrived: page 1 lands behind the player.
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.setViewResumed(false);

        Future<Boolean> page2 = walk(pacer, 2, new AtomicBoolean());
        assertStillWaiting(page2);

        pacer.setViewResumed(true);
        assertTrue(page2.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void disposalWithWakeEndsTheWaitPromptly() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);
        AtomicBoolean disposed = new AtomicBoolean();

        Future<Boolean> page3 = walk(pacer, 3, disposed);
        assertStillWaiting(page3);
        disposed.set(true);
        pacer.wake(); // what BrowsePresenter.disposeActions does

        assertFalse(page3.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void disposalWithoutWakeStillEndsWithinAPoll() throws Exception {
        HomeSectionPacer pacer = pacer(200);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);
        AtomicBoolean disposed = new AtomicBoolean();

        Future<Boolean> page3 = walk(pacer, 3, disposed);
        assertStillWaiting(page3);
        disposed.set(true);

        assertFalse(page3.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void aWalkParkedForALongTimeStillResumesOnDemand() throws Exception {
        // Home left open: scrolling much later must load the remaining sections, not end the list.
        HomeSectionPacer pacer = pacer(20);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false);

        Future<Boolean> page3 = walk(pacer, 3, new AtomicBoolean());
        Thread.sleep(600); // ~30 polls with nobody asking
        assertStillWaiting(page3);

        pacer.demand();
        assertTrue(page3.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void aDisposedWalkDoesNotReleasePagesOfItsReplacement() throws Exception {
        HomeSectionPacer pacer = pacer(NO_POLL);
        pacer.awaitNextPage(MediaGroup.TYPE_HOME, 2, () -> false); // new walk after a refresh

        // The old walk fetched deep pages before it was disposed and now reports in.
        assertFalse(pacer.awaitNextPage(MediaGroup.TYPE_HOME, 6, () -> true));

        Future<Boolean> page3 = walk(pacer, 3, new AtomicBoolean());
        assertStillWaiting(page3);
        page3.cancel(true);
    }

    private static HomeSectionPacer pacer(long pollMs) {
        return new HomeSectionPacer(System::currentTimeMillis, pollMs);
    }

    private Future<Boolean> walk(HomeSectionPacer pacer, int page, AtomicBoolean disposed) {
        return mWalker.submit(() -> pacer.awaitNextPage(MediaGroup.TYPE_HOME, page, disposed::get));
    }

    private static void assertStillWaiting(Future<Boolean> page) throws Exception {
        try {
            page.get(150, TimeUnit.MILLISECONDS);
            throw new AssertionError("page was released without a demand");
        } catch (TimeoutException expected) {
            // still waiting
        }
    }
}
