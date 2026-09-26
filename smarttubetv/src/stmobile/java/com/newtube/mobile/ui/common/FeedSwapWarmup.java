package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.newtube.mobile.ui.browse.VideoCardAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(feed-swap): holds a stale-snapshot -> fresh-feed swap until the thumbnails of the fresh
 * first screen are decoded (or {@link #MAX_WAIT_MS} passes), so the swap lands as ONE frame of
 * complete cards.
 *
 * <p>A cold launch paints the {@link FeedCache} snapshot at ~0.4-0.6 s and the fresh first page
 * replaces it ~1 s later. Swapped the moment the page arrived, the new cards came up as grey boxes
 * whose pictures faded in over the next few hundred ms - the snapshot was already complete, so
 * that read as the feed blanking out. Keeping the (perfectly usable) snapshot on screen a little
 * longer and swapping to pictures that are already in Glide's memory cache does not. The wait is
 * capped: a slow image host costs at most {@link #MAX_WAIT_MS} of staleness, never a stuck feed.</p>
 *
 * <p>The preloads use the card's exact request ({@link VideoCardAdapter.VideoViewHolder#thumbnailRequest}),
 * so the bind right after the swap is a synchronous memory-cache hit with no cross-fade. Main
 * thread only; one instance per swap.</p>
 */
public final class FeedSwapWarmup {
    /** Longest the stale snapshot is kept on screen after the fresh page is in hand. */
    public static final long MAX_WAIT_MS = 350;
    /** A portrait phone shows ~2.5 full-width cards; a landscape/tablet grid a few more. */
    static final int MAX_CARDS = 6;

    /** Starts one thumbnail load and reports its end (success or failure) exactly once. */
    interface Loader {
        @Nullable
        Object load(Video video, Runnable onDone);

        void cancel(Object handle);
    }

    interface Timer {
        Object schedule(Runnable task, long delayMs);

        void cancel(Object token);
    }

    /** Called once when the swap may proceed. {@code warmed} of {@code total} pictures are ready. */
    public interface OnReady {
        void onReady(int warmed, int total, boolean timedOut);
    }

    private final Loader mLoader;
    private final Timer mTimer;
    private final List<Object> mHandles = new ArrayList<>();
    @Nullable
    private OnReady mOnReady;
    private Object mTimeout;
    private int mTotal;
    private int mDone;

    FeedSwapWarmup(Loader loader, Timer timer) {
        mLoader = loader;
        mTimer = timer;
    }

    /** Glide + main-thread timer wiring. */
    public static FeedSwapWarmup create(Context context) {
        RequestManager glide = Glide.with(context);
        Handler handler = new Handler(Looper.getMainLooper());
        return new FeedSwapWarmup(new Loader() {
            @Override
            public Object load(Video video, Runnable onDone) {
                return VideoCardAdapter.VideoViewHolder.thumbnailRequest(glide, context, video)
                        .priority(Priority.IMMEDIATE)
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                    Target<Drawable> target, boolean isFirstResource) {
                                onDone.run();
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                    DataSource dataSource, boolean isFirstResource) {
                                onDone.run();
                                return false;
                            }
                        })
                        .preload();
            }

            @Override
            @SuppressWarnings("unchecked")
            public void cancel(Object handle) {
                glide.clear((Target<Drawable>) handle);
            }
        }, new Timer() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object token) {
                handler.removeCallbacks((Runnable) token);
            }
        });
    }

    /**
     * Only a grid RESTING at the very top waits for the first screen's pictures and is pinned to the
     * fresh top. A scrolled grid shows other cards than the ones that would be warmed, and a grid
     * under a finger (or mid-fling) must never be moved; both swap at once, unpinned. Asked again
     * when the swap is applied - a finger can land during the wait.
     */
    public static boolean warmAndPin(boolean atTop, boolean scrollIdle) {
        return atTop && scrollIdle;
    }

    /**
     * Whether the presenter's clear-before-load (an empty REPLACE) must leave the grid as it is: it
     * shows a snapshot awaiting fresh content, or its fresh page is still warming up (the snapshot is
     * still what is on screen - a pull-to-refresh in that window used to blank the feed).
     */
    public static boolean keepScreenOnClear(boolean awaitingFreshContent, boolean swapPending) {
        return awaitingFreshContent || swapPending;
    }

    /**
     * The cards whose pictures the swap waits for: the first {@code visibleCount} (clamped to
     * 1..{@link #MAX_CARDS}) of the fresh list, skipping channel rows (round avatars, not thumbnails).
     */
    public static List<Video> firstScreen(List<Video> fresh, int visibleCount) {
        int limit = Math.max(1, Math.min(MAX_CARDS, visibleCount));
        List<Video> result = new ArrayList<>();
        if (fresh == null) {
            return result;
        }
        for (int i = 0; i < fresh.size() && i < limit; i++) {
            Video video = fresh.get(i);
            if (video != null && (!video.isChannel() || video.isPlaylistAsChannel())) {
                result.add(video);
            }
        }
        return result;
    }

    /**
     * Warms {@code cards} and calls {@code onReady} once: when every load has ended, or when the
     * cap passes, whichever comes first. With nothing to warm it is called before this returns
     * (so assign the instance BEFORE calling this). Memory-cache hits can also end synchronously.
     */
    public void begin(List<Video> cards, OnReady onReady) {
        mOnReady = onReady;
        mTotal = cards != null ? cards.size() : 0;
        mDone = 0;
        if (mTotal == 0) {
            finish(false);
            return;
        }

        mTimeout = mTimer.schedule(() -> {
            mTimeout = null;
            finish(true);
        }, MAX_WAIT_MS);

        for (Video video : new ArrayList<>(cards)) {
            final boolean[] reported = {false};
            Object handle = mLoader.load(video, () -> {
                if (reported[0]) {
                    return;
                }
                reported[0] = true;
                mDone++;
                if (mDone >= mTotal) {
                    finish(false);
                }
            });
            if (handle != null) {
                mHandles.add(handle);
            }
        }
    }

    /** True until onReady has run or the warm-up was cancelled. */
    public boolean isPending() {
        return mOnReady != null;
    }

    /**
     * The swap is abandoned (section switch, screen destroyed): onReady never runs, pending loads
     * and the timer are dropped.
     */
    public void cancel() {
        mOnReady = null;
        cancelTimeout();
        for (Object handle : mHandles) {
            mLoader.cancel(handle);
        }
        mHandles.clear();
    }

    private void finish(boolean timedOut) {
        OnReady onReady = mOnReady;
        if (onReady == null) {
            return;
        }
        mOnReady = null;
        cancelTimeout();
        // Loads still running on timeout are left alone: they land in the memory cache and the
        // cards bound by the swap pick them up (a normal Glide request joins the in-flight load).
        mHandles.clear();
        onReady.onReady(Math.min(mDone, mTotal), mTotal, timedOut);
    }

    private void cancelTimeout() {
        if (mTimeout != null) {
            mTimer.cancel(mTimeout);
            mTimeout = null;
        }
    }
}
