package com.newtube.mobile.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One next-VOD sample preload, subordinate to the active player. All methods run on main.
 *
 * <p>The source is the ordinary generated DASH source: its cache, signed URLs and fail-fast
 * policy are unchanged. The manager never creates a MediaItem URL source or a download task.
 * Media3 stops at the two-second sample target (rounded up to segment boundaries); the shared
 * load control caps preload allocations, and a wall-clock deadline bounds a slow/failed load.
 * There is no speculative error recovery or repeat attempt for the same target in one open.
 *
 * <p>The manager and foreground player MUST come from the same builder. This shares their
 * playback looper, renderers, bandwidth meter and load-control allocator. A completed wrapper
 * survives the matching loadVideo/reset and is handed to the player unchanged; manager ownership
 * is removed only after foreground tracks arrive, not between setMediaSource and preparation.
 */
final class Media3NextPreloader {
    static final long TARGET_DURATION_MS = 2_000;
    static final long MIN_FOREGROUND_BUFFER_MS = 10_000;
    static final long MAX_LOAD_TIME_MS = 15_000;
    static final long MAX_STASH_AGE_MS = 90_000;
    private static final long CHECK_INTERVAL_MS = 500;

    interface Events {
        void onCompleted();
        void onError();
    }

    interface Engine {
        MediaSource add(MediaSource source, Events events);
        void remove(MediaSource source);
        void release();
    }

    interface EngineFactory {
        Engine create();
    }

    private final EngineFactory mEngineFactory;
    private final Supplier<Boolean> mCanLoad;
    private final Runnable mCopyTrackParameters;
    private final Consumer<MediaSource> mDiscardSource;
    private final Handler mHandler;
    private final LongSupplier mClock;
    private Runnable mReleaseUnbuiltSelector = () -> { };
    private final Set<String> mAttemptedVideoIds = new HashSet<>();
    private final Runnable mCheck = this::update;
    @Nullable private Engine mEngine;
    @Nullable private Slot mPending;
    @Nullable private Slot mAdopting;
    private boolean mTransitioning;
    private boolean mReleased;

    Media3NextPreloader(DefaultPreloadManager.Builder builder, DefaultTrackSelector foregroundSelector,
            DefaultTrackSelector preloadSelector, Supplier<ExoPlayer> player,
            Consumer<MediaSource> discardSource) {
        this(() -> createEngine(builder),
                () -> canLoad(player.get(), foregroundSelector),
                () -> copyTrackParameters(foregroundSelector, preloadSelector),
                discardSource, new Handler(Looper.getMainLooper()), SystemClock::elapsedRealtime);
        mReleaseUnbuiltSelector = preloadSelector::release;
    }

    /** Injects only scheduling/player observations, so lifecycle tests need no decoder or network. */
    Media3NextPreloader(EngineFactory engineFactory, Supplier<Boolean> canLoad,
            Runnable copyTrackParameters, Consumer<MediaSource> discardSource, Handler handler,
            LongSupplier clock) {
        mEngineFactory = engineFactory;
        mCanLoad = canLoad;
        mCopyTrackParameters = copyTrackParameters;
        mDiscardSource = discardSource;
        mHandler = handler;
        mClock = clock;
    }

    void offer(String videoId, MediaSource source) {
        if (mReleased || mAttemptedVideoIds.contains(videoId)
                || (mPending != null && mPending.source == source)) {
            return;
        }
        cancelPending("replaced");
        mPending = new Slot(videoId, source, mClock.getAsLong());
        update();
    }

    /** Also used by the offline instrumentation fixture before its explicit handoff. */
    boolean isReady(String videoId) {
        return mPending != null && mPending.complete && mPending.videoId.equals(videoId);
    }

    /** Called for player loading/state changes, and while a pending slot exists. */
    void update() {
        mHandler.removeCallbacks(mCheck);
        Slot slot = mPending;
        if (mReleased || slot == null) {
            return;
        }
        long now = mClock.getAsLong();
        if (now - slot.offeredAtMs >= MAX_STASH_AGE_MS) {
            cancelPending("expired");
            return;
        }
        if (!slot.complete && slot.wrapped != null) {
            if (mTransitioning || !mCanLoad.get()) {
                cancelPending("foreground");
                return;
            }
            if (now - slot.startedAtMs >= MAX_LOAD_TIME_MS) {
                cancelPending("deadline");
                return;
            }
        } else if (slot.wrapped == null && !mTransitioning && mAdopting == null && mCanLoad.get()) {
            // Bound even pathological suggestion churn; reset clears the attempt set.
            if (mAttemptedVideoIds.size() >= 16) {
                cancelPending("attempt-budget");
                return;
            }
            mAttemptedVideoIds.add(slot.videoId);
            slot.attempted = true;
            slot.startedAtMs = now;
            try {
                mCopyTrackParameters.run();
                if (mEngine == null) {
                    mEngine = mEngineFactory.create();
                }
                slot.wrapped = mEngine.add(slot.source, new Events() {
                    @Override public void onCompleted() { complete(slot); }
                    @Override public void onError() {
                        if (mPending == slot) {
                            cancelPending("error");
                        }
                    }
                });
                NetPath.log("next-preload start video=" + slot.videoId
                        + " target-ms=" + TARGET_DURATION_MS);
            } catch (RuntimeException error) {
                // Never route a speculative failure into the active video's error/reload path.
                cancelPending("setup-error");
                return;
            }
        }
        mHandler.postDelayed(mCheck, CHECK_INTERVAL_MS);
    }

    /**
     * Never return a half-loaded or failed source to the foreground. The original unprepared
     * stash remains useful when no network attempt started; otherwise a miss builds normally.
     */
    @Nullable
    MediaSource take(String videoId, MediaSource source) {
        Slot slot = mPending;
        if (slot == null || slot.source != source || !slot.videoId.equals(videoId)) {
            return source;
        }
        if (mClock.getAsLong() - slot.offeredAtMs >= MAX_STASH_AGE_MS) {
            boolean wasPrepared = slot.attempted;
            cancelPending("expired-at-open");
            return wasPrepared ? null : source;
        }
        if (slot.wrapped == null) {
            mPending = null;
            mHandler.removeCallbacks(mCheck);
            return source;
        }
        if (!slot.complete) {
            cancelPending("opened-before-ready");
            return null;
        }
        releaseAdopting();
        mPending = null;
        mAdopting = slot;
        mHandler.removeCallbacks(mCheck);
        NetPath.log("next-preload hit video=" + slot.videoId);
        return slot.wrapped;
    }

    /** loadVideo resets before openDash can consume its matching stash. */
    void onReset(@Nullable String targetVideoId) {
        mTransitioning = true;
        mAttemptedVideoIds.clear();
        if (mAdopting != null && !mAdopting.videoId.equals(targetVideoId)) {
            releaseAdopting();
        }
        if (mPending != null && (!mPending.videoId.equals(targetVideoId)
                || (mPending.wrapped != null && !mPending.complete))) {
            cancelPending("new-open");
        }
        update();
    }

    void onSourceOpened(MediaSource source) {
        mTransitioning = false;
        // A matching video routed through HLS/merged/etc. must not leave a prepared DASH behind.
        cancelPending("other-source");
        if (mAdopting != null && mAdopting.wrapped != source) {
            releaseAdopting();
        }
    }

    void onForegroundTracksChanged() {
        // The player now owns the period. Removing preload ownership no longer clears its samples.
        releaseAdopting();
        update();
    }

    void cancel(String reason) {
        cancelPending(reason);
        // An adopted source remains the foreground player's responsibility until its tracks arrive
        // or the next reset/release. Removing it here during an early loading event loses handoff.
    }

    void onForegroundError() {
        cancelPending("foreground-error");
        releaseAdopting();
    }

    void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        cancelPending("release");
        releaseAdopting();
        if (mEngine != null) {
            mEngine.release();
            mEngine = null;
        } else {
            mReleaseUnbuiltSelector.run();
        }
        mAttemptedVideoIds.clear();
    }

    private void complete(Slot slot) {
        if (mPending != slot || slot.wrapped == null || mReleased) {
            return;
        }
        slot.complete = true;
        NetPath.log("next-preload ready video=" + slot.videoId
                + " elapsed-ms=" + (mClock.getAsLong() - slot.startedAtMs));
        update();
    }

    private void cancelPending(String reason) {
        mHandler.removeCallbacks(mCheck);
        Slot slot = mPending;
        mPending = null;
        if (slot == null) {
            return;
        }
        if (slot.attempted && mEngine != null) {
            mEngine.remove(slot.wrapped != null ? slot.wrapped : slot.source);
            // This raw source was prepared inside a wrapper; never use it as an unprepared stash.
            mDiscardSource.accept(slot.source);
            NetPath.log("next-preload stop video=" + slot.videoId + " reason=" + reason);
        }
    }

    private void releaseAdopting() {
        if (mAdopting != null && mEngine != null) {
            mEngine.remove(mAdopting.wrapped);
        }
        mAdopting = null;
    }

    static boolean canLoad(@Nullable ExoPlayer player, DefaultTrackSelector selector) {
        if (player == null || player.getPlayerError() != null || !player.getPlayWhenReady()
                || player.getPlaybackState() != Player.STATE_READY || player.isLoading()
                || player.isCurrentMediaItemLive()) {
            return false;
        }
        DefaultTrackSelector.Parameters parameters = selector.getParameters();
        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
            return false; // background audio-only mode
        }
        for (TrackSelectionOverride override : parameters.overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_VIDEO) {
                return false; // manual quality stays entirely under foreground ownership
            }
        }
        return hasHealthyBuffer(player.getTotalBufferedDuration(), player.getDuration(),
                player.getCurrentPosition(), player.getPlaybackParameters().speed);
    }

    static void copyTrackParameters(DefaultTrackSelector foregroundSelector,
            DefaultTrackSelector preloadSelector) {
        preloadSelector.setParameters(foregroundSelector.buildUponParameters()
                // Overrides name groups in the CURRENT video, never the next video's groups.
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
    }

    static boolean hasHealthyBuffer(long bufferedMs, long durationMs, long positionMs, float speed) {
        if (bufferedMs < 0 || Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0) {
            return false;
        }
        long remainingMs = durationMs != C.TIME_UNSET && durationMs > 0 && positionMs >= 0
                ? Math.max(0, durationMs - positionMs) : Long.MAX_VALUE;
        // A fully buffered short tail needs no foreground network, even when <10 seconds remain.
        return bufferedMs / speed >= MIN_FOREGROUND_BUFFER_MS
                || (remainingMs > 0 && bufferedMs >= remainingMs);
    }

    private static Engine createEngine(DefaultPreloadManager.Builder builder) {
        DefaultPreloadManager manager = builder.build();
        return new Engine() {
            @Nullable private PreloadManagerListener listener;
            @Nullable private MediaItem currentItem;

            @Override public MediaSource add(MediaSource source, Events events) {
                if (listener != null) {
                    manager.removeListener(listener);
                }
                MediaItem expectedItem = source.getMediaItem();
                currentItem = expectedItem;
                listener = new PreloadManagerListener() {
                    @Override public void onCompleted(MediaItem item) {
                        if (expectedItem.equals(item)) events.onCompleted();
                    }
                    @Override public void onError(PreloadException error) {
                        if (expectedItem.equals(error.mediaItem)) events.onError();
                    }
                };
                manager.addListener(listener);
                manager.add(source, 0);
                MediaSource wrapped = manager.getMediaSource(source.getMediaItem());
                if (wrapped == null) {
                    throw new IllegalStateException("Missing preload source");
                }
                manager.invalidate();
                return wrapped;
            }
            @Override public void remove(MediaSource source) {
                if (source.getMediaItem().equals(currentItem) && listener != null) {
                    manager.removeListener(listener);
                    listener = null;
                    currentItem = null;
                }
                manager.remove(source.getMediaItem());
            }
            @Override public void release() { manager.release(); }
        };
    }

    private static final class Slot {
        final String videoId;
        final MediaSource source;
        final long offeredAtMs;
        @Nullable MediaSource wrapped;
        long startedAtMs;
        boolean attempted;
        boolean complete;

        Slot(String videoId, MediaSource source, long offeredAtMs) {
            this.videoId = videoId;
            this.source = source;
            this.offeredAtMs = offeredAtMs;
        }
    }
}
