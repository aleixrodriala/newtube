package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.sharedutils.rx.RxHelper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.observables.ConnectableObservable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The boot prefetch (BrowsePresenter.prefetchBootSection) relies on these properties of a
 * replayed, connected observable; pin them so an Rx upgrade cannot silently break Home's launch.
 */
public class BootPrefetchReplayTest {
    /** Same kind of scheduler as RxHelper's: a cached pool that is NOT interrupted on dispose. */
    private static final io.reactivex.rxjava3.core.Scheduler WALKER =
            Schedulers.from(java.util.concurrent.Executors.newCachedThreadPool());

    @Test
    public void pagesThatLandedBeforeBrowseSubscribedAreHandedOverInOrder() throws Exception {
        CountDownLatch walkDone = new CountDownLatch(1);
        ConnectableObservable<String> prefetch = Observable.<String>create(emitter -> {
            emitter.onNext("page1");
            emitter.onNext("page2");
            emitter.onComplete();
            walkDone.countDown();
        }).subscribeOn(WALKER).replay();
        Disposable connection = prefetch.connect();
        assertTrue(walkDone.await(2, TimeUnit.SECONDS));

        List<String> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        Disposable subscriber = prefetch.subscribe(received::add, e -> { }, () -> completed.set(true));

        assertEquals(Arrays.asList("page1", "page2"), received);
        assertTrue(completed.get());
        // A finished walk must not look "in flight" to the presenter's refocus guard.
        assertFalse(RxHelper.isAnyActionRunning(connection, subscriber));
    }

    @Test
    public void disposingTheConnectionStopsTheWalk() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean sawDisposed = new AtomicBoolean();
        CountDownLatch walkEnded = new CountDownLatch(1);
        ConnectableObservable<String> prefetch = Observable.<String>create(emitter -> {
            emitter.onNext("page1");
            waiting.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!emitter.isDisposed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10); // a paced walk parked before its next page
            }
            sawDisposed.set(emitter.isDisposed());
            walkEnded.countDown();
        }).subscribeOn(WALKER).replay();
        Disposable connection = prefetch.connect();
        Disposable subscriber = prefetch.subscribe(page -> { });
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        assertTrue(RxHelper.isAnyActionRunning(connection, subscriber));

        // BrowsePresenter.disposeActions disposes both (the connection is added to mActions).
        RxHelper.disposeActions(subscriber, connection);

        assertTrue(walkEnded.await(2, TimeUnit.SECONDS));
        assertTrue(sawDisposed.get());
    }
}
