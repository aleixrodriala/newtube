package com.newtube.mobile.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

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

    private final Context mContext;
    private final Media3SourceFactory mMediaSourceFactory;
    private final PlayerEventListener mEventListener;
    private Media3TrackAdapter mTrackAdapter;
    private ExoPlayer mPlayer;
    private WeakReference<Video> mVideo;
    private boolean mOnSourceChanged;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    // NEWTUBE(live): last resort for a pathological live stream - rate-limits BLW recoveries.
    private long mLastLiveEdgeRecoveryMs;

    public Media3PlayerController(Context context, PlayerEventListener eventListener) {
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new Media3SourceFactory(context);
        mEventListener = eventListener;
    }

    // ---------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------

    public void openSabr(MediaItemFormatInfo formatInfo) {
        Log.e(TAG, "openSabr: SABR-only response on the media3 engine; trying the LQ url list");

        if (formatInfo.containsUrlFormats()) {
            openUrlList(formatInfo.createUrlList());
        } else {
            // Feed the regular error path (ErrorFixer -> reload) instead of hanging silently.
            mEventListener.onEngineError(
                    ExoPlaybackException.TYPE_SOURCE, -1,
                    new IllegalStateException("SABR-only stream isn't supported by the media3 engine yet"));
        }
    }

    public void openDash(MediaItemFormatInfo formatInfo) {
        openMediaSource(mMediaSourceFactory.fromDashFormatInfo(formatInfo));
    }

    public void openDash(InputStream dashManifest) {
        openMediaSource(mMediaSourceFactory.fromDashManifest(dashManifest));
    }

    public void openDashUrl(String dashManifestUrl) {
        openMediaSource(mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl));
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        openMediaSource(mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl));
    }

    public void openUrlList(List<String> urlList) {
        openMediaSource(mMediaSourceFactory.fromUrlList(urlList));
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        openMediaSource(mMediaSourceFactory.fromMerged(formatInfo, hlsPlaylistUrl));
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        openMediaSource(mMediaSourceFactory.fromMerged(dashManifest, hlsPlaylistUrl));
    }

    private void openMediaSource(@Nullable MediaSource mediaSource) {
        if (mPlayer == null) {
            return;
        }

        if (mediaSource == null) {
            mEventListener.onEngineError(
                    ExoPlaybackException.TYPE_SOURCE, -1,
                    new IllegalStateException("Can't build a media source for this video"));
            return;
        }

        resetPlayerState(); // same video-artifact fix as the legacy controller

        if (mTrackAdapter != null) {
            mTrackAdapter.onSourceChanged();
        }
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());

        mPlayer.setMediaSource(mediaSource);
        mPlayer.prepare();
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
        return mPlayer != null && mPlayer.getPlaybackState() != Player.STATE_IDLE;
    }

    public void resetPlayerState() {
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
        mTrackAdapter = new Media3TrackAdapter(trackSelector);
        mTrackAdapter.setPreferOriginalAudio(true); // NEWTUBE(mobile): match the legacy default
        applyPersistedFormats();
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
            mTrackAdapter.selectFormat(formatItem);
            mEventListener.onTrackSelected(formatItem);
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
        dispatchStateChange(getPlayWhenReady(), playbackState);
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
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
            mEventListener.onSeekEnd();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "onPlayerError: " + error);

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

        // The legacy TYPE_* int values match media3's, so the shared error-fixer logic holds.
        mEventListener.onEngineError(type, rendererIndex, nested);
    }
}
