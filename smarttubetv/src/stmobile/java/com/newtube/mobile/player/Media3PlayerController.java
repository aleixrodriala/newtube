package com.newtube.mobile.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.datasource.HttpDataSource;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Media3 twin of {@code ExoPlayerController}: same public surface (so the
 * {@code MobilePlaybackActivity} delegation block is unchanged), same
 * {@link PlayerEventListener} semantics towards the shared presenter/controllers - but the engine
 * behind it is androidx.media3. Track selection goes through {@link Media3TrackAdapter} instead
 * of the legacy {@code TrackSelectorManager}.
 *
 * <p>SABR is not implemented on this engine (the vendored SABR MediaSource is exoplayer2-bound).
 * The shared {@code VideoLoaderController} prefers DASH whenever DASH formats exist, so
 * {@link #openSabr} only fires for SABR-only responses - it falls back to the LQ URL list when
 * present, else surfaces a source error into the normal reload path.</p>
 */
public class Media3PlayerController implements Player.Listener {
    private static final String TAG = Media3PlayerController.class.getSimpleName();

    /**
     * NEWTUBE(open-latency): the generated-MPD build (XML generation + XML re-parse, the measured
     * 50-160ms info->prepare gap) runs here instead of the main thread. Process-wide single thread:
     * builds are strictly ordered, and a per-instance executor would leak (nothing shuts it down -
     * same rule as the factory's CRONET_EXECUTOR).
     */
    private static final ExecutorService SOURCE_BUILD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Media3SourceBuild");
        thread.setDaemon(true);
        return thread;
    });

    private final Context mContext;
    private final Media3SourceFactory mMediaSourceFactory;
    private final PlayerEventListener mEventListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Open generation: bumped on every open/reset/release so a stale off-main build never prepares. */
    private final SourceBuildGeneration mOpenGeneration = new SourceBuildGeneration();
    private Media3TrackAdapter mTrackAdapter;
    private DefaultTrackSelector mTrackSelector;
    private Media3NextPreloader mNextPreloader;
    private ExoPlayer mPlayer;
    private WeakReference<Video> mVideo;
    private boolean mOnSourceChanged;
    private boolean mIsEnded;
    /**
     * NetPath milestone 4 gate: media3 re-renders a "first" frame on every surface/stream refresh
     * (a live window slide every ~2s made this line fire 400+ times per session) - log only the
     * first one per open. Reset in {@link #resetPlayerState()}, which every open* path runs, so a
     * real reload logs again.
     */
    private boolean mFirstFrameLogged;
    /**
     * NEWTUBE(focus-grace): an error-reload's own play() was observed being killed by an external
     * AUDIO_FOCUS_LOSS landing ~200ms after re-prepare (Pixel 9, 2026-07-13) - recovery succeeded
     * but playback sat paused until the user noticed. A focus loss that lands within this window
     * of a prepare() is startup interference, not the user leaving for another media app: retry
     * play() ONCE. If the other holder still has focus, media3's own focus request suppresses the
     * retry and playback stays paused - no fight loop.
     */
    private static final long FOCUS_GRACE_MS = 5_000;
    private long mLastPrepareMs;
    private boolean mFocusGraceUsed;
    private boolean mSabrSourceActive;
    /** Whether the active SABR source is the automatic fallback rather than the opt-in preference. */
    private boolean mSabrWasFallback;
    private Runnable mOnVideoLoaded;
    // NEWTUBE(live): last resort for a pathological live stream - rate-limits BLW recoveries.
    private long mLastLiveEdgeRecoveryMs;

    /**
     * NEWTUBE(prepare-stash): one-slot (videoId, MediaSource) pre-built for the likely NEXT video
     * (autoplay prefetch, see {@code VideoLoaderController.preloadNextVideoIfNeeded}) so the
     * auto-advance {@link #openDash(MediaItemFormatInfo)} skips the MPD XML generation+parse.
     * Written on {@link #SOURCE_BUILD_EXECUTOR}, consumed at most once on main (media3 sources
     * are consumed once by this controller) - both under the stash monitor. Invalidated in
     * {@link #resetPlayerState()} (every open runs it, so a mismatching entry never outlives the
     * open that skipped it) and {@link #release()}; overwritten by newer pre-builds. A never-used
     * entry holds no player resources until {@link Media3NextPreloader} adopts it for bounded
     * sample loading. The preloader explicitly releases its loaders/periods on cancellation and
     * removes that prepared raw source from this stash; an untouched entry is a plain GC.
     */
    private final SourceStash<MediaSource> mSourceStash = new SourceStash<>();

    /** NEWTUBE(open-phases): release-build milestones between prepare and first frame. */
    private final OpenPhaseLog mOpenPhaseLog = new OpenPhaseLog();
    /** NEWTUBE(keep-codec): foreground mode was dropped by {@link #onBackgroundAudio} and is owed back. */
    private boolean mKeepCodecSuspended;
    /**
     * NEWTUBE(resume-seek): the snap seek. PREVIOUS_SYNC can only resolve to a real sync point (a
     * DASH segment start from the loaded index) at or before the target - never to an arbitrary
     * "target - tolerance" edge the way a bounded tolerance falls back - and it is only issued
     * after the loading segment's start was checked to lie 0.5-8 s before the target (see
     * {@link ResumeSeekSnap}). The resume seek itself is EXACT so that the snap decision, not the
     * player-wide scrub tolerance (which may land up to 1 s AFTER), decides where a resume lands.
     */
    static final androidx.media3.exoplayer.SeekParameters RESUME_SNAP_PARAMETERS =
            androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC;
    private final ResumeSeekSnap mResumeSnap = new ResumeSeekSnap();
    /** elapsedRealtime of this open's first audio media request, to quantify a snap's audio cost. */
    private long mAudioChunkStartedAtMs;
    /** Media start time of that request (its audio segment), to see if a snap crosses into the previous one. */
    private long mAudioChunkStartMs = C.TIME_UNSET;
    /** The first video media request of an open carries the index-derived segment start. */
    private final androidx.media3.exoplayer.analytics.AnalyticsListener mResumeListener =
            new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override
                public void onLoadStarted(EventTime eventTime,
                        androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                        androidx.media3.exoplayer.source.MediaLoadData mediaLoadData, int retryCount) {
                    if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA || !isCurrentPeriod(eventTime)) {
                        return;
                    }
                    if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO && mAudioChunkStartedAtMs == 0) {
                        mAudioChunkStartedAtMs = android.os.SystemClock.elapsedRealtime();
                        mAudioChunkStartMs = mediaLoadData.mediaStartTimeMs;
                    } else if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
                        onVideoChunkStart(mediaLoadData.mediaStartTimeMs, mediaLoadData.mediaEndTimeMs);
                    }
                }
            };

    public Media3PlayerController(Context context, PlayerEventListener eventListener) {
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new Media3SourceFactory(context);
        mEventListener = eventListener;
        // NEWTUBE(viewport): the small-window cap is process-wide so it survives an engine restart
        // (release + fresh selector) while pinned. A NEW playback screen starts full size: a cap
        // left by a previous instance (PiP dismissed, mini card closed) must not carry over. The
        // inline box is re-reported by the new screen's first portrait layout.
        clearInlineViewport("new-session");
        clearSmallWindowViewport("new-session");
        // A dropped build is a legitimate outcome, but a SILENT one is indistinguishable from a
        // player that was never asked to open anything - both leave a spinner at 00:00 and no log.
        // One line per drop is what makes that difference readable in a NetPath trace.
        mOpenGeneration.setDropListener((stage, generation, currentGeneration) ->
                NetPath.log("source-build dropped stage=" + stage + " gen=" + generation
                        + " current=" + currentGeneration + " video=" + getVideoId()));
    }

    // ---------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------

    public void openSabr(MediaItemFormatInfo formatInfo) {
        if (SabrSourcePreference.isEnabled(mContext) && SabrFormatAdapter.eligible(formatInfo)) {
            // A preferred SABR source displaced a working DASH route, so its failure is the
            // user's experiment failing and stays terminal. A fallback SABR source is the only
            // thing standing between a link-less response and "unplayable": if it fails, the
            // normal client/reload recovery must still get its turn.
            mSabrWasFallback = !SabrSourcePreference.isPreferred(mContext);
            mOpenGeneration.next(); // Also invalidate old queued source builds when this build fails.
            try {
                if (!java.util.Objects.equals(getVideoId(), formatInfo.getVideoId())) {
                    throw new IllegalArgumentException("Mismatched SABR video");
                }
                openMediaSource(mMediaSourceFactory.fromSabrFormatInfo(formatInfo), "sabr-vod");
            } catch (RuntimeException failure) {
                NetPath.log("sabr source-build stopped reason=" + failure.getClass().getSimpleName());
                if (mPlayer != null) mPlayer.stop();
                mEventListener.onEngineError(ExoPlaybackException.TYPE_SOURCE, -1, sabrTerminalError());
            }
            return;
        }
        Log.e(TAG, "openSabr: SABR-only response on the media3 engine; trying the LQ url list");

        if (formatInfo.containsUrlFormats()) {
            openMediaSource(mMediaSourceFactory.fromUrlList(formatInfo.createUrlList()), "sabr-fallback");
        } else {
            // Feed the regular error path (ErrorFixer -> reload) instead of hanging silently.
            mEventListener.onEngineError(
                    ExoPlaybackException.TYPE_SOURCE, -1,
                    new IllegalStateException("SABR-only stream isn't supported by the media3 engine yet"));
        }
    }

    public boolean allowsAutomaticSourceRecovery() { return !mSabrSourceActive || mSabrWasFallback; }

    public void openDash(MediaItemFormatInfo formatInfo) {
        // Only the opt-in experiment displaces working DASH links. The default-on fallback never
        // reaches this route: it exists for responses that have no links to displace.
        if (SabrSourcePreference.isPreferred(mContext) && SabrFormatAdapter.eligible(formatInfo)) {
            openSabr(formatInfo);
            return;
        }
        // NEWTUBE(prepare-stash): a pre-built source for this exact video skips the XML gen+parse
        // AND the executor round-trip - prepare fires synchronously, within ~1ms of this call.
        if (!formatInfo.isLive()) {
            MediaSource stashed = takeStashedSource(formatInfo.getVideoId());
            if (stashed != null) {
                // Adopt exactly like a fresh build: bump the open generation first (any in-flight
                // off-main build is stale now), then the same openMediaSource path (whose
                // resetPlayerState bumps again - the off-main route also bumps twice per open).
                mOpenGeneration.next();
                openMediaSource(stashed, "dash-mpd-stash");
                return;
            }
        }

        // "dash-mpd-live" only fires on the last-resort live route (no dash/hls manifest url).
        SourceBuildTiming timing = new SourceBuildTiming();
        openMediaSourceOffMain(() -> mMediaSourceFactory.fromDashFormatInfo(formatInfo, timing),
                formatInfo.isLive() ? "dash-mpd-live" : "dash-mpd", timing);
    }

    /**
     * NEWTUBE(prepare-stash): pre-build the DASH MediaSource for the likely next video and stash
     * it (see the stash field doc). Same factory entry point as {@link #openDash} on the same
     * executor, so cache routing / ABR wiring are identical to a normal build. Live videos are
     * skipped (their manifest must stay URL-loaded so it can refresh); an id-less info can't be
     * matched at open time. Failures leave no entry - the real open just builds normally.
     */
    public void prebuildNextSource(MediaItemFormatInfo formatInfo) {
        // Prebuilding a DASH source is wasted only when SABR is going to displace it anyway.
        if (SabrSourcePreference.isPreferred(mContext)) return;
        if (formatInfo == null || formatInfo.getVideoId() == null || formatInfo.isLive()) {
            return;
        }

        final String videoId = formatInfo.getVideoId();
        final int generation = mOpenGeneration.current();

        if (mSourceStash.contains(videoId)) {
            return; // already stashed (the minute tick fires again inside the 80s window)
        }

        SOURCE_BUILD_EXECUTOR.execute(mOpenGeneration.guard(generation, "prebuild", () -> {
            if (mSourceStash.contains(videoId)) {
                return; // another queued prebuild already published this exact next video
            }
            MediaSource mediaSource;
            try {
                mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
            } catch (Throwable e) { // never kill the build thread
                Log.e(TAG, "prebuildNextSource: build failed: " + e);
                mediaSource = null;
            }

            if (mediaSource != null) {
                final MediaSource result = mediaSource;
                mOpenGeneration.publishIfCurrent(generation, "prebuild-publish",
                        () -> mSourceStash.offerIfAbsent(videoId, result));
                // Manager/player interactions belong to main. Recorded live/OTF keeps only the
                // existing XML prebuild; its normalized manifest must not start speculative loads.
                if (!formatInfo.isLiveContent() && !formatInfo.isUnplayable()) {
                    mMainHandler.post(mOpenGeneration.guard(generation, "preload-deliver", () -> {
                        if (!mSourceStash.containsSource(result)) {
                            return;
                        }
                        if (mNextPreloader != null) {
                            mNextPreloader.offer(videoId, result);
                        }
                    }));
                }
            }
        }));
    }

    /**
     * Consume-at-most-once stash read (id match required). Logs the NetPath consult line only
     * when an entry exists - at most one {@code prepare-stash hit|miss} line per open.
     */
    @Nullable
    private MediaSource takeStashedSource(@Nullable String videoId) {
        MediaSource stashed = null;

        synchronized (mSourceStash) {
            if (!mSourceStash.hasSource()) {
                return null; // nothing stashed -> no consult line
            }
            stashed = mSourceStash.take(videoId);
            // Mismatch: leave the entry; resetPlayerState (this very open runs it) clears it.
        }

        if (stashed != null && mNextPreloader != null) {
            stashed = mNextPreloader.take(videoId, stashed);
        }
        NetPath.log("prepare-stash " + (stashed != null ? "hit " : "miss ") + videoId);
        return stashed;
    }

    private void discardPreparedStash(MediaSource source) {
        mSourceStash.discard(source);
    }

    private void clearStashedSource() {
        mSourceStash.clear();
    }

    /**
     * Stash invalidation for {@link #resetPlayerState()}. Every open runs reset - and the
     * loading pipeline ({@code VideoLoaderController.loadVideo}) runs it BEFORE the format info
     * even arrives, i.e. before {@link #openDash} could consume the entry. An unconditional clear
     * there would wipe the pre-built next-source at the very start of the auto-advance it was
     * built for (the same self-eviction that made the first negative-cache cut inert). So: since
     * {@code loadVideo} calls {@code setVideo(item)} right before reset, the target of the
     * current open is known - keep the entry ONLY if it matches, drop anything else (manual tap
     * on a different video, engine restarts, ...). The matching entry that then goes unused
     * (e.g. the open dispatch picks another route) is consumed-or-dropped by the openMediaSource
     * reset of that same open or the next one's mismatch drop.
     */
    private void dropMismatchedStash() {
        mSourceStash.dropExcept(getVideoId());
    }

    public void openDash(InputStream dashManifest) {
        openMediaSource(mMediaSourceFactory.fromDashManifest(dashManifest), "dash-mpd");
    }

    public void openDashUrl(String dashManifestUrl) {
        openMediaSource(mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl), "dash-url");
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        openMediaSource(mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl), "hls");
    }

    public void openUrlList(List<String> urlList) {
        openMediaSource(mMediaSourceFactory.fromUrlList(urlList), "progressive");
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        openMediaSourceOffMain(() -> mMediaSourceFactory.fromMerged(formatInfo, hlsPlaylistUrl), "dash-mpd+hls",
                new SourceBuildTiming());
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        openMediaSource(mMediaSourceFactory.fromMerged(dashManifest, hlsPlaylistUrl), "dash-mpd+hls");
    }

    /**
     * NEWTUBE(open-latency): build the MediaSource (MPD XML generation + parse, 50-160ms) on the
     * background executor, then hand it to {@link #openMediaSource} back on main. The open
     * generation skips obsolete queued work before XML generation, and checks again on main so
     * an open/reset/release during the build cannot prepare over the newer video. URL-only paths
     * stay synchronous - they are already lazy (no XML work at open time).
     */
    private void openMediaSourceOffMain(Supplier<MediaSource> mediaSourceBuilder, String netPathType,
            SourceBuildTiming timing) {
        final int generation = mOpenGeneration.next();
        timing.queuedAtMs = android.os.SystemClock.elapsedRealtime();
        timing.queuedAtOpenMs = NetPath.elapsedMs();

        SOURCE_BUILD_EXECUTOR.execute(mOpenGeneration.guard(generation, () -> {
            timing.startedAtMs = android.os.SystemClock.elapsedRealtime();
            MediaSource mediaSource;
            try {
                mediaSource = mediaSourceBuilder.get();
            } catch (Throwable e) { // never kill the build thread; surface the normal error path
                Log.e(TAG, "openMediaSourceOffMain: source build failed: " + e);
                mediaSource = null;
            }
            timing.builtAtMs = android.os.SystemClock.elapsedRealtime();

            final MediaSource result = mediaSource;
            mMainHandler.post(mOpenGeneration.guard(generation, "deliver", () -> {
                long deliveredAtMs = android.os.SystemClock.elapsedRealtime();
                long deliveredAtOpenMs = NetPath.elapsedMs();
                openMediaSource(result, netPathType);
                // NEWTUBE(open-phases): info -> prepare was a flat 40-50 ms median on the Pixel
                // (release, compiled) for every open; this splits it into executor queueing, XML
                // generation, XML parse + source creation, and the main-thread hop back. Written
                // AFTER prepare() so the line itself is never on the open's critical path; its +X
                // is the delivery time, i.e. the moment prepare started.
                NetPath.log(NetPath.context() + " source-build +" + deliveredAtOpenMs
                        + " type=" + netPathType + ' ' + timing.describe(deliveredAtMs));
            }));
        }));
    }

    private void openMediaSource(@Nullable MediaSource mediaSource, String netPathType) {
        if (mPlayer == null) {
            // Nothing downstream reports this, so without a line here the open simply evaporates:
            // no prepare, no error, no NetPath milestone - just a player stuck at 00:00.
            NetPath.log("source-open skipped reason=no-engine type=" + netPathType
                    + " video=" + getVideoId());
            return;
        }

        if (mediaSource == null) {
            mEventListener.onEngineError(
                    ExoPlaybackException.TYPE_SOURCE, -1,
                    new IllegalStateException("Can't build a media source for this video"));
            return;
        }

        resetPlayerState(); // same video-artifact fix as the legacy controller
        mSabrSourceActive = "sabr-vod".equals(netPathType);

        if (mTrackAdapter != null) {
            mTrackAdapter.onSourceChanged();
        }
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());

        mOpenPhaseLog.onPrepare();
        mResumeSnap.onPrepare();
        mAudioChunkStartedAtMs = 0;
        mAudioChunkStartMs = C.TIME_UNSET;
        PlayerInfrastructureWarmup.onPlaybackPreparing();
        mPlayer.setMediaSource(mediaSource);
        mPlayer.prepare();
        if (mNextPreloader != null) {
            mNextPreloader.onSourceOpened(mediaSource);
        }
        mLastPrepareMs = System.currentTimeMillis();
        mFocusGraceUsed = false;

        NetPath.logPrepare(getVideoId(), netPathType); // NetPath milestone 3: source prepared
    }

    // ---------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------

    public long getPositionMs() {
        if (mPlayer == null) {
            return -1;
        }

        return mPlayer.getCurrentPosition();
    }

    public void setPositionMs(long positionMs) {
        if (mPlayer == null || positionMs < 0) {
            return;
        }
        // Scrubs, SponsorBlock/chapter skips, share-link timestamps, live-edge jumps: exact (within
        // the player-wide tolerance) and never replaced by a pending resume snap.
        mResumeSnap.onOtherSeek();
        seekTo(positionMs);
    }

    private void seekTo(long positionMs) {
        if (mNextPreloader != null) {
            mNextPreloader.cancel("seek");
        }
        // A pending seek before the timeline is known is accepted; once duration is known,
        // clamp tiny overflows instead of dropping the jump (same fix as the legacy controller).
        long durationMs = getDurationMs();
        mPlayer.seekTo(durationMs >= 0 ? Math.min(positionMs, durationMs) : positionMs);
    }

    /**
     * NEWTUBE(resume-seek): the automatic history ("continue watching") position of an open - see
     * {@link ResumeSeekSnap}. Lands on the keyframe at or before {@code positionMs} (at most 10 s
     * earlier, in practice the start of its ~5 s segment), like YouTube's own resume, instead of
     * decoding every frame from that keyframe up to the exact millisecond before the first one
     * shows. Live streams and positions at the very end stay exact.
     */
    public void seekToResumePosition(long positionMs) {
        if (mPlayer == null || positionMs < 0) {
            return;
        }
        boolean live = mPlayer.isCurrentMediaItemLive()
                || (getVideo() != null && getVideo().isLive);
        if (!resumeSnapEnabled() || !mResumeSnap.onResumeRequest(positionMs, getDurationMs(), live)) {
            seekTo(positionMs); // the pre-snap behavior: player-wide seek parameters
            return;
        }
        // cacheMB: how full the 512 MB LRU media cache is when a resume starts - the resume's
        // READY wait is only explained by a cache miss if its range was evicted or never stored.
        androidx.media3.datasource.cache.Cache cache = Media3PlayerCache.get(mContext);
        NetPath.log(NetPath.context() + " resume-seek target=" + positionMs + " armed cacheMB="
                + (cache != null ? cache.getCacheSpace() / (1024 * 1024) : -1));
        // Exact: makes media3 request the segment that contains the target; the snap decision
        // follows when that request starts (onVideoChunkStart).
        ownSeek(positionMs, androidx.media3.exoplayer.SeekParameters.EXACT);
    }

    private void onVideoChunkStart(long chunkStartMs, long chunkEndMs) {
        if (mPlayer == null || !mResumeSnap.isArmed()) {
            return;
        }
        long target = mResumeSnap.armedTargetMs();
        long audioInFlightMs = mAudioChunkStartedAtMs == 0 ? -1
                : android.os.SystemClock.elapsedRealtime() - mAudioChunkStartedAtMs;
        int decision = mResumeSnap.onVideoChunkStart(chunkStartMs, chunkEndMs,
                mPlayer.getCurrentPosition(), audioInFlightMs);
        switch (decision) {
            case ResumeSeekSnap.SNAP:
                // audioInFlightMs quantifies the one cost of the snap: a started audio request is
                // restarted at the snapped position (-1 = not started yet: nothing to restart).
                NetPath.log(NetPath.context() + " resume-seek snap target=" + target
                        + " segmentStart=" + chunkStartMs + " audioInFlightMs=" + audioInFlightMs
                        + audioChunkTag(chunkStartMs));
                ownSeek(target, RESUME_SNAP_PARAMETERS);
                break;
            case ResumeSeekSnap.SKIP_NEAR:
            case ResumeSeekSnap.SKIP_FAR:
            case ResumeSeekSnap.SKIP_AUDIO:
                NetPath.log(NetPath.context() + " resume-seek skipped target=" + target
                        + " segmentStart=" + chunkStartMs + " audioInFlightMs=" + audioInFlightMs
                        + audioChunkTag(chunkStartMs)
                        + " reason=" + (decision == ResumeSeekSnap.SKIP_NEAR ? "near"
                                : decision == ResumeSeekSnap.SKIP_FAR ? "far" : "audio-busy"));
                break;
            case ResumeSeekSnap.DROPPED_MOVED:
                NetPath.log(NetPath.context() + " resume-seek dropped reason=moved pos="
                        + mPlayer.getCurrentPosition());
                break;
            default:
                break;
        }
    }

    /**
     * Whether a snap to {@code snappedMs} leaves the audio segment the first audio request asked
     * for ({@code crossesAudioChunk=y}: that request is cancelled and the previous 10 s segment is
     * loaded instead). Known only once the audio request has started.
     */
    private String audioChunkTag(long snappedMs) {
        if (mAudioChunkStartMs == C.TIME_UNSET) {
            return "";
        }
        return " audioChunkStart=" + mAudioChunkStartMs
                + " crossesAudioChunk=" + (snappedMs < mAudioChunkStartMs ? "y" : "n");
    }

    /**
     * A seek of the resume machinery: recorded as ours (its SEEK discontinuity must not cancel the
     * snap), issued with {@code parameters} for this one seek; the previous (player-wide)
     * parameters are restored right after. setSeekParameters and seekTo reach the playback thread
     * in order, so only this seek resolves with {@code parameters}.
     */
    private void ownSeek(long positionMs, androidx.media3.exoplayer.SeekParameters parameters) {
        androidx.media3.exoplayer.SeekParameters previous = mPlayer.getSeekParameters();
        mPlayer.setSeekParameters(parameters);
        long durationMs = getDurationMs();
        mResumeSnap.noteOwnSeek(durationMs >= 0 ? Math.min(positionMs, durationMs) : positionMs);
        seekTo(positionMs);
        mPlayer.setSeekParameters(previous);
    }

    /** Always on in release; debug/benchmark builds can A/B it with {@code debug.arc.resume_snap=0}. */
    static boolean resumeSnapEnabled() {
        return !((com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                || com.liskovsoft.smartyoutubetv2.tv.BuildConfig.BENCHMARK)
                && "0".equals(DebugMediaShaper.prop("debug.arc.resume_snap")));
    }

    /** Events of an earlier source/period (queued across a new open) must not drive this open's snap. */
    private boolean isCurrentPeriod(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime eventTime) {
        if (mPlayer == null || eventTime.mediaPeriodId == null) {
            return false;
        }
        androidx.media3.common.Timeline timeline = mPlayer.getCurrentTimeline();
        int periodIndex = mPlayer.getCurrentPeriodIndex();
        if (timeline.isEmpty() || periodIndex < 0 || periodIndex >= timeline.getPeriodCount()) {
            return false;
        }
        return eventTime.mediaPeriodId.periodUid.equals(timeline.getUidOfPeriod(periodIndex));
    }

    /**
     * NEWTUBE(resume-seek): the position history and resume should store - the real playback
     * position, except that it never reports earlier than a snapped-over resume target the user
     * has not watched back to yet (open and leave at once must not lose that segment of progress).
     */
    public long getHistoryPositionMs() {
        return mResumeSnap.historyPositionMs(getPositionMs());
    }

    private void reportResumeSnap(long snappedMs, boolean adjusted) {
        long target = mResumeSnap.awaitingTargetMs();
        if (target == C.TIME_UNSET) {
            return;
        }
        mResumeSnap.onReported();
        NetPath.log(NetPath.context() + " resume-seek target=" + target + " snapped=" + snappedMs
                + " earlyMs=" + (target - snappedMs) + " adjusted=" + (adjusted ? "y" : "n")
                + " mode=previous-sync");
    }

    public long getDurationMs() {
        if (mPlayer == null) {
            return -1;
        }

        long duration = mPlayer.getDuration();
        return duration != C.TIME_UNSET ? duration : -1;
    }

    public void setPlayWhenReady(boolean play) {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(play);
        }
    }

    public boolean getPlayWhenReady() {
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }

    public boolean isPlaying() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlaybackState() == Player.STATE_READY && mPlayer.getPlayWhenReady();
    }

    public boolean isLoading() {
        return mPlayer != null && mPlayer.isLoading();
    }

    public boolean containsMedia() {
        // Must be media-item based, NOT playback-state based: a fatal player error puts ExoPlayer
        // in STATE_IDLE BEFORE onPlayerError is dispatched, and VideoStateController's error-path
        // position save is guarded by containsMedia(). With the state-based check every
        // error-reload resumed from a stale persisted position (observed on-device: a 403 loop
        // replaying the same 41s forever because the death position was never saved).
        return mPlayer != null && mPlayer.getMediaItemCount() > 0;
    }

    public void resetPlayerState() {
        // Any in-flight off-main source build is now stale (a new open resets first, and
        // openMediaSource itself resets) - drop it instead of letting it prepare later.
        mOpenGeneration.invalidate(this::dropMismatchedStash);
        if (mNextPreloader != null) {
            mNextPreloader.onReset(getVideoId());
        }
        mFirstFrameLogged = false; // new open = a fresh NetPath first-frame milestone
        mResumeSnap.onPrepare();

        if (containsMedia()) {
            mPlayer.stop();
            mPlayer.clearMediaItems();
        }
    }

    // ---------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------

    public void setPlayer(ExoPlayer player) {
        mPlayer = player;
        mKeepCodecSuspended = false; // a new player starts with its builder's foreground mode
        player.addListener(this);
        player.addAnalyticsListener(mOpenPhaseLog);
        player.addAnalyticsListener(mResumeListener);
    }

    public void setTrackSelector(DefaultTrackSelector trackSelector) {
        mTrackSelector = trackSelector;
        mTrackAdapter = new Media3TrackAdapter(trackSelector);
        mTrackAdapter.setPreferOriginalAudio(true); // NEWTUBE(mobile): match the legacy default
        applyPersistedFormats();
    }

    /** The initializer's shared builder guarantees preload/foreground looper and allocator parity. */
    public void attachPreloader(@Nullable DefaultPreloadManager.Builder builder,
            @Nullable DefaultTrackSelector preloadTrackSelector) {
        if (mNextPreloader != null) {
            mNextPreloader.release();
        }
        boolean enabled = builder != null && preloadTrackSelector != null;
        NetPath.log("next-preload enabled=" + (enabled ? "y" : "n"));
        if (!enabled) {
            mNextPreloader = null;
            return;
        }
        mNextPreloader = new Media3NextPreloader(builder, mTrackSelector, preloadTrackSelector,
                () -> mPlayer, this::discardPreparedStash);
    }

    /** Seed the adapter with the persisted picks (legacy applyShield720pFix analog). */
    private void applyPersistedFormats() {
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData playerData =
                com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(mContext);
        selectFormat(playerData.getFormat(FormatItem.TYPE_VIDEO));
        selectFormat(playerData.getFormat(FormatItem.TYPE_AUDIO));
        selectFormat(playerData.getFormat(FormatItem.TYPE_SUBTITLE));
    }

    public Media3SourceFactory getMediaSourceFactory() {
        return mMediaSourceFactory;
    }

    public void release() {
        // Also prevents a running prebuild from repopulating the stash after this cleanup.
        mOpenGeneration.invalidate(this::clearStashedSource);
        if (mNextPreloader != null) {
            mNextPreloader.release();
            mNextPreloader = null;
        }

        if (mPlayer != null) {
            mPlayer.removeListener(this);
            mPlayer.removeAnalyticsListener(mOpenPhaseLog);
            mPlayer.removeAnalyticsListener(mResumeListener);
            mPlayer.stop();
            mPlayer.clearMediaItems();
            mPlayer.clearVideoSurface();
            mPlayer.release();
            mPlayer = null;
        }
    }

    /**
     * NEWTUBE(keep-codec): the watch page entered ({@code true}) or left true background audio.
     * Media3 wants foreground mode off while the app is not in the foreground; what that mode can
     * retain here is narrow, and turning it off is not free, so only the case that matters is
     * handled:
     * <ul>
     *   <li>A player with media (READY/BUFFERING/ENDED): the background-audio track disable makes
     *       media3 reselect tracks, and {@code ExoPlayerImplInternal.enableRenderers} resets every
     *       renderer the new selection leaves disabled - the video decoder is released there,
     *       foreground mode or not. Nothing to do; above all, no blocking call next to live
     *       audio.</li>
     *   <li>An IDLE player (between an open's stop() and its prepare, or parked after an error):
     *       no reselection runs, so a decoder kept by foreground mode would stay allocated while
     *       another app may want it. Foreground mode is dropped, which resets the disabled
     *       renderers on the playback thread. {@code setForegroundMode(false)} blocks this thread
     *       for at most the player's release timeout (500 ms) and turns a timeout into a player
     *       error - harmless here because nothing is playing, and that one error is filtered in
     *       {@link #onPlayerError}.</li>
     * </ul>
     * Leaving background audio restores the mode ({@code setForegroundMode(true)} never blocks).
     * PiP and the Browse mini player show live video and never come here.
     */
    public void onBackgroundAudio(boolean background) {
        if (mPlayer == null) {
            return;
        }
        if (!background) {
            if (mKeepCodecSuspended) {
                mKeepCodecSuspended = false;
                mPlayer.setForegroundMode(true);
                NetPath.log(NetPath.context() + " codec-keep resumed");
            }
            return;
        }
        if (mKeepCodecSuspended || !Media3PlayerInitializer.keepCodecsEnabled()) {
            return;
        }
        if (mPlayer.getPlaybackState() != Player.STATE_IDLE) {
            NetPath.log(NetPath.context() + " codec-keep background state=" + mPlayer.getPlaybackState()
                    + " action=none reason=track-disable-resets-video");
            return;
        }
        mKeepCodecSuspended = true;
        long startMs = android.os.SystemClock.elapsedRealtime();
        mPlayer.setForegroundMode(false);
        NetPath.log(NetPath.context() + " codec-keep suspended state=idle blockedMs="
                + (android.os.SystemClock.elapsedRealtime() - startMs));
    }

    static boolean isKeepCodecReleaseTimeout(@Nullable PlaybackException error) {
        if (error == null || error.errorCode != PlaybackException.ERROR_CODE_TIMEOUT) {
            return false;
        }
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof androidx.media3.exoplayer.ExoTimeoutException) {
                return ((androidx.media3.exoplayer.ExoTimeoutException) cause).timeoutOperation
                        == androidx.media3.exoplayer.ExoTimeoutException.TIMEOUT_OPERATION_SET_FOREGROUND_MODE;
            }
        }
        return false;
    }

    public void setVideo(Video video) {
        mVideo = new WeakReference<>(video);
    }

    public Video getVideo() {
        return mVideo != null ? mVideo.get() : null;
    }

    private String getVideoId() {
        Video video = getVideo();
        return video != null ? video.videoId : null;
    }

    public void setOnVideoLoaded(Runnable onVideoLoaded) {
        mOnVideoLoaded = onVideoLoaded;
    }

    // ---------------------------------------------------------------------------------
    // Formats
    // ---------------------------------------------------------------------------------

    public List<FormatItem> getVideoFormats() {
        return mTrackAdapter != null ? mTrackAdapter.getFormats(TrackSelectorManager.RENDERER_INDEX_VIDEO) : null;
    }

    public List<FormatItem> getAudioFormats() {
        return mTrackAdapter != null ? mTrackAdapter.getFormats(TrackSelectorManager.RENDERER_INDEX_AUDIO) : null;
    }

    public List<FormatItem> getSubtitleFormats() {
        return mTrackAdapter != null ? mTrackAdapter.getFormats(TrackSelectorManager.RENDERER_INDEX_SUBTITLE) : null;
    }

    public void selectFormat(FormatItem formatItem) {
        if (formatItem != null && mTrackAdapter != null) {
            if (mNextPreloader != null) {
                mNextPreloader.cancel("format-change");
            }
            mTrackAdapter.selectFormat(formatItem);
            mEventListener.onTrackSelected(formatItem);
        }
    }

    /**
     * NEWTUBE(bg-audio): disable/enable the VIDEO track type for true background audio-only
     * playback (see {@link Media3TrackAdapter#setVideoTrackDisabled}). Routed through the adapter
     * so the track selector has a single owner for its parameters.
     */
    public void setVideoTrackDisabled(boolean disabled) {
        if (disabled && mNextPreloader != null) {
            mNextPreloader.cancel("background-audio");
        }
        if (mTrackAdapter != null) {
            mTrackAdapter.setVideoTrackDisabled(disabled);
        }
    }

    /**
     * NEWTUBE(viewport): the video is now drawn into a small window (system PiP, the Browse mini
     * card) of {@code widthPx x heightPx} real pixels - fetch NEW chunks at the rung that window can
     * show. Not a selector-parameter change (see {@link VideoViewportCap} for why that would drop
     * the buffer and rebuffer): the track set, the stream and every buffered chunk stay, explicit
     * quality picks and the background-audio video disable are untouched. Repeated calls with a new
     * size (PiP resize) just move the cap.
     */
    public void setSmallWindowViewport(String mode, int widthPx, int heightPx) {
        VideoViewportCap.shared().set(mode, widthPx, heightPx);
    }

    /**
     * Same, sized from the window's configuration (PiP entry/resize): real pixels from the system's
     * {@code screen*Dp} and {@code densityDpi}, never the app's swapped DisplayMetrics.
     */
    public void setSmallWindowViewport(String mode, @Nullable android.content.res.Configuration windowConfig) {
        int[] pixels = VideoViewportCap.windowPixels(windowConfig);
        if (pixels == null) {
            NetPath.log("viewport " + mode + " ignored reason=no-window-size");
            return;
        }
        setSmallWindowViewport(mode, pixels[0], pixels[1]);
    }

    /** Full-size player again: lift the cap; ABR up-switches and refetches beyond 25 s natively. */
    public void clearSmallWindowViewport(String reason) {
        VideoViewportCap.shared().clear(reason);
    }

    /**
     * NEWTUBE(viewport): the portrait watch-page video box is {@code widthPx x heightPx} real
     * pixels ({@code fill}: a zoom/fill resize mode crops the video to it). While saving data - a
     * metered network AND Android Data Saver on for this app ({@link MeteredNetworkMonitor}) -
     * NEW chunks are capped to the rung that box can show: the same in-ABR cap as PiP (buffer
     * kept, explicit quality picks untouched), re-checked on every selection so Wi-Fi or Data
     * Saver off lifts it for the next chunk. A PiP/mini window wins while set. Cheap to repeat:
     * an unchanged box is a no-op.
     */
    public void setInlineViewport(int widthPx, int heightPx, boolean fill) {
        VideoViewportCap.shared().setInline(widthPx, heightPx, fill);
    }

    /** Fullscreen (landscape): no inline box, so no data-saving inline cap for NEW chunks. */
    public void clearInlineViewport(String reason) {
        VideoViewportCap.shared().clearInline(reason);
    }

    public FormatItem getVideoFormat() {
        return mTrackAdapter != null ? mTrackAdapter.getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_VIDEO) : null;
    }

    public FormatItem getAudioFormat() {
        return mTrackAdapter != null ? mTrackAdapter.getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_AUDIO) : null;
    }

    public FormatItem getSubtitleFormat() {
        return mTrackAdapter != null ? mTrackAdapter.getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_SUBTITLE) : null;
    }

    // ---------------------------------------------------------------------------------
    // Speed / pitch / volume
    // ---------------------------------------------------------------------------------

    public float getSpeed() {
        return mPlayer != null ? mPlayer.getPlaybackParameters().speed : -1;
    }

    public void setSpeed(float speed) {
        if (mPlayer != null && speed > 0) {
            if (PlayerTweaksData.instance(mContext).isAudioTimeStretchingEnabled()) {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, mPlayer.getPlaybackParameters().pitch));
            } else {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }

            mEventListener.onSpeedChanged(speed);
        }
    }

    public float getPitch() {
        return mPlayer != null ? mPlayer.getPlaybackParameters().pitch : -1;
    }

    public void setPitch(float pitch) {
        if (mPlayer != null && pitch > 0) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(mPlayer.getPlaybackParameters().speed, pitch));
        }
    }

    public void setVolume(float volume) {
        if (mPlayer != null && volume >= 0) {
            mPlayer.setVolume(Math.min(volume, 1f));
        }
    }

    public float getVolume() {
        return mPlayer != null ? mPlayer.getVolume() : 1;
    }

    // ---------------------------------------------------------------------------------
    // Player.Listener -> PlayerEventListener translation
    // ---------------------------------------------------------------------------------

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (tracks.getGroups().isEmpty()) {
            return;
        }

        if (mNextPreloader != null) {
            mNextPreloader.onForegroundTracksChanged();
        }

        if (mTrackAdapter != null) {
            mTrackAdapter.onTracksChanged(tracks);
        }

        if (mOnSourceChanged) {
            mOnSourceChanged = false;

            mEventListener.onVideoLoaded(getVideo());

            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }
        }

        FormatItem videoFormat = getVideoFormat();
        if (videoFormat != null) {
            mEventListener.onTrackChanged(videoFormat);
        }
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mNextPreloader != null) {
            mNextPreloader.update();
        }
        if (playbackState == Player.STATE_READY && mResumeSnap.onReady()) {
            // Never snap back once playable (e.g. audio-only playback whose video came back later).
            NetPath.log(NetPath.context() + " resume-seek expired reason=ready");
        }
        dispatchStateChange(getPlayWhenReady(), playbackState);
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        if (mNextPreloader != null) {
            mNextPreloader.update();
        }
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (mNextPreloader != null) {
            mNextPreloader.update();
        }
        // NEWTUBE(focus-grace): see FOCUS_GRACE_MS. One retry per prepare.
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                && !mFocusGraceUsed
                && System.currentTimeMillis() - mLastPrepareMs < FOCUS_GRACE_MS) {
            mFocusGraceUsed = true;
            android.util.Log.d("NetPath", "focus-grace: AUDIO_FOCUS_LOSS "
                    + (System.currentTimeMillis() - mLastPrepareMs) + "ms after prepare - retrying play once");
            mMainHandler.postDelayed(() -> {
                if (mPlayer != null && !mPlayer.getPlayWhenReady()) {
                    mPlayer.setPlayWhenReady(true);
                }
            }, 750);
        }

        if (mPlayer != null) {
            dispatchStateChange(playWhenReady, mPlayer.getPlaybackState());
        }
    }

    /** Reconstructs the legacy (playWhenReady, state) callback semantics the presenter expects. */
    private void dispatchStateChange(boolean playWhenReady, int playbackState) {
        boolean isPlayPressed = playbackState == Player.STATE_READY && playWhenReady;
        boolean isPausePressed = playbackState == Player.STATE_READY && !playWhenReady;
        boolean isPlaybackEnded = playbackState == Player.STATE_ENDED && playWhenReady;
        boolean isBuffering = playbackState == Player.STATE_BUFFERING && playWhenReady;

        // Fix chapters (seek and play) after playback ends
        if (isPlaybackEnded && mIsEnded) {
            return;
        }

        if (isPlayPressed) {
            mEventListener.onPlay();
        } else if (isPausePressed) {
            mEventListener.onPause();
        } else if (isPlaybackEnded) {
            mEventListener.onPlayEnd();
            mIsEnded = true;
        } else if (isBuffering) {
            mEventListener.onBuffering();
        }

        if (getPositionMs() < getDurationMs()) {
            mIsEnded = false;
        }
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT && newPosition != null) {
            reportResumeSnap(newPosition.positionMs, /* adjusted= */ true);
        }
        // Any seek that is not ours - also the raw ones that bypass setPositionMs (media session /
        // lock screen, the double-tap overlay) - cancels a pending snap and the history floor.
        if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition != null
                && mResumeSnap.onSeekDiscontinuity(newPosition.positionMs)) {
            NetPath.log(NetPath.context() + " resume-seek cancelled reason=other-seek to="
                    + newPosition.positionMs);
        }
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            if (mNextPreloader != null) {
                mNextPreloader.cancel("seek");
            }
            mEventListener.onSeekEnd();
            // SmartTube 6331ce5: seek completion resets the buffering watchdog. Media3 may
            // already have emitted BUFFERING, with no further state transition until data arrives.
            // Re-arm only an actively playing seek; paused or already-ready seeks must stay idle.
            if (mPlayer != null && mPlayer.getPlayWhenReady()
                    && mPlayer.getPlaybackState() == Player.STATE_BUFFERING) {
                mEventListener.onBuffering();
            }
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        if (!mFirstFrameLogged) {
            mFirstFrameLogged = true;
            NetPath.logFirstFrame(getVideoId()); // NetPath milestone 4: first frame rendered
            // A snap that needed no adjustment (the saved position already sat on a keyframe).
            if (mPlayer != null) {
                reportResumeSnap(mPlayer.getCurrentPosition(), /* adjusted= */ false);
            }
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (isKeepCodecReleaseTimeout(error)) {
            // Our own onBackgroundAudio(true) on an IDLE player: media3 turned the late codec
            // release into this error. Nothing was playing and nothing failed to load, so it
            // must not reach the reload/quarantine machinery (see onBackgroundAudio).
            NetPath.log(NetPath.context() + " codec-keep release-timeout ignored");
            return;
        }
        if (mNextPreloader != null) {
            mNextPreloader.onForegroundError();
        }
        Log.e(TAG, "onPlayerError: " + error);
        NetPath.logError(getVideoId(), error); // NetPath milestone 5: player error
        // Debug playground only: keep a synthetic one-shot media fault active until Media3 really
        // gives up, then make the app-level client/transport reload clean. No property means a
        // no-op, and release builds never construct the shaper in the first place.
        if (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG) {
            DebugMediaShaper.disarmOneShotPoisonForRecovery();
        }
        if (mSabrSourceActive) {
            int type = error instanceof ExoPlaybackException
                    ? ((ExoPlaybackException) error).type : ExoPlaybackException.TYPE_UNEXPECTED;
            if (mSabrWasFallback) {
                // The response carried no URL formats, so there is nothing to retry here. Hand the
                // real cause to the shared fixer and let it remint/reload onto another client -
                // the same recovery a plain source error gets. Its own attempt caps bound the loop.
                NetPath.log(NetPath.context() + " sabr-fallback failed; deferring to client recovery");
                mEventListener.onEngineError(type, -1,
                        error.getCause() != null ? error.getCause() : error);
            } else {
                mEventListener.onEngineError(type, -1, sabrTerminalError());
            }
            return;
        }

        // NEWTUBE(live): playhead fell out of the live DVR window (device slept, long pause).
        // media3's canonical recovery: jump to the default (live-edge) position and re-prepare the
        // same source - near-instant vs the generic full video reload below. Rate-limited so a
        // pathological stream still falls through to the full reload.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && mPlayer != null
                && System.currentTimeMillis() - mLastLiveEdgeRecoveryMs > 15_000) {
            Log.e(TAG, "onPlayerError: behind live window, re-preparing at the live edge");
            mLastLiveEdgeRecoveryMs = System.currentTimeMillis();
            mPlayer.seekToDefaultPosition();
            mPlayer.prepare();
            mPlayer.setPlayWhenReady(true);
            return;
        }

        int type = ExoPlaybackException.TYPE_UNEXPECTED;
        int rendererIndex = -1;
        if (error instanceof ExoPlaybackException) {
            type = ((ExoPlaybackException) error).type;
            if (type == ExoPlaybackException.TYPE_RENDERER) {
                rendererIndex = ((ExoPlaybackException) error).rendererIndex;
            }
        }

        Throwable nested = error.getCause() != null ? error.getCause() : error;
        if (type == ExoPlaybackException.TYPE_SOURCE) {
            rendererIndex = inferSourceRendererIndex(nested);
        }

        // The legacy TYPE_* int values match media3's, so the shared error-fixer logic holds.
        mEventListener.onEngineError(type, rendererIndex, nested);
    }

    private com.liskovsoft.smartyoutubetv2.common.app.models.playback.TerminalSourceException sabrTerminalError() {
        return new com.liskovsoft.smartyoutubetv2.common.app.models.playback.TerminalSourceException(
                mContext.getString(com.liskovsoft.smartyoutubetv2.tv.R.string.sabr_vod_stopped));
    }

    /**
     * Media3 reports HTTP failures as TYPE_SOURCE with renderer=-1 even though the failing DataSpec
     * still names its YouTube itag. Match that short id against the active explicit targets so the
     * shared recovery code never blames an audio preference for a video failure (or vice versa).
     * Auto/preset selections intentionally remain unknown: a client-wide URL remint is the right
     * first response and there is no concrete user pin to relax.
     */
    private int inferSourceRendererIndex(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof HttpDataSource.HttpDataSourceException)) {
                continue;
            }

            Uri uri = ((HttpDataSource.HttpDataSourceException) cause).dataSpec.uri;
            String itag = uri != null && uri.isHierarchical() ? uri.getQueryParameter("itag") : null;
            int rendererIndex = rendererIndexForTargetId(itag);
            if (rendererIndex != TrackSelectorManager.RENDERER_INDEX_UNKNOWN) {
                android.util.Log.d("NetPath", "source-error inferred-renderer=" + rendererIndex
                        + " itag=" + itag);
            }
            return rendererIndex;
        }

        return TrackSelectorManager.RENDERER_INDEX_UNKNOWN;
    }

    private int rendererIndexForTargetId(@Nullable String formatId) {
        if (formatId == null || mTrackAdapter == null) {
            return TrackSelectorManager.RENDERER_INDEX_UNKNOWN;
        }
        if (targetIdEquals(TrackSelectorManager.RENDERER_INDEX_VIDEO, formatId)) {
            return TrackSelectorManager.RENDERER_INDEX_VIDEO;
        }
        if (targetIdEquals(TrackSelectorManager.RENDERER_INDEX_AUDIO, formatId)) {
            return TrackSelectorManager.RENDERER_INDEX_AUDIO;
        }
        if (targetIdEquals(TrackSelectorManager.RENDERER_INDEX_SUBTITLE, formatId)) {
            return TrackSelectorManager.RENDERER_INDEX_SUBTITLE;
        }
        return TrackSelectorManager.RENDERER_INDEX_UNKNOWN;
    }

    private boolean targetIdEquals(int rendererIndex, String formatId) {
        FormatItem target = mTrackAdapter.getSelectedFormat(rendererIndex);
        return target != null && formatId.equals(target.getFormatId());
    }
}
