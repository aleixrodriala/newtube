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

    public Media3PlayerController(Context context, PlayerEventListener eventListener) {
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new Media3SourceFactory(context);
        mEventListener = eventListener;
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
        openMediaSourceOffMain(() -> mMediaSourceFactory.fromDashFormatInfo(formatInfo),
                formatInfo.isLive() ? "dash-mpd-live" : "dash-mpd");
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
        openMediaSourceOffMain(() -> mMediaSourceFactory.fromMerged(formatInfo, hlsPlaylistUrl), "dash-mpd+hls");
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
    private void openMediaSourceOffMain(Supplier<MediaSource> mediaSourceBuilder, String netPathType) {
        final int generation = mOpenGeneration.next();

        SOURCE_BUILD_EXECUTOR.execute(mOpenGeneration.guard(generation, () -> {
            MediaSource mediaSource;
            try {
                mediaSource = mediaSourceBuilder.get();
            } catch (Throwable e) { // never kill the build thread; surface the normal error path
                Log.e(TAG, "openMediaSourceOffMain: source build failed: " + e);
                mediaSource = null;
            }

            final MediaSource result = mediaSource;
            mMainHandler.post(mOpenGeneration.guard(generation, "deliver",
                    () -> openMediaSource(result, netPathType)));
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

        if (mNextPreloader != null) {
            mNextPreloader.cancel("seek");
        }
        // A pending seek before the timeline is known is accepted; once duration is known,
        // clamp tiny overflows instead of dropping the jump (same fix as the legacy controller).
        long durationMs = getDurationMs();
        mPlayer.seekTo(durationMs >= 0 ? Math.min(positionMs, durationMs) : positionMs);
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
        player.addListener(this);
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
            mPlayer.stop();
            mPlayer.clearMediaItems();
            mPlayer.clearVideoSurface();
            mPlayer.release();
            mPlayer = null;
        }
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
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
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
