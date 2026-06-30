package com.newtube.mobile.ui.playback;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.io.InputStream;
import java.util.List;

/**
 * Touch player - Wave 2 vertical slice.
 *
 * Built the same way as the {@code EmbedPlayerView} template (ARCHITECTURE.md, section 6): a
 * plain {@link PlayerView} (no Leanback) wired straight to {@link ExoPlayerController} and
 * {@link ExoPlayerInitializer}, with this Activity itself implementing {@link PlaybackView}
 * and being handed to {@link PlaybackPresenter#setView}. The 11 playback controllers owned by
 * {@code PlaybackPresenter} (VideoLoader, VideoState, Suggestions, ErrorFixer, PlayerUI, ...)
 * are reused completely unchanged; this class is only the touch View layer.
 *
 * Engine-critical methods (open calls, position, duration, play-pause, speed, formats, resize)
 * are real, delegating to {@link ExoPlayerController} exactly like {@code EmbedPlayerView} and
 * {@code PlaybackFragment} do. UI-only methods with no touch surface yet (suggestions panel,
 * per-button on/off state, debug overlay, live chat, seek-bar segments, storyboard) are stubbed
 * with sane no-op/empty defaults - see the "// TODO Wave N" comments below - mirroring exactly
 * what {@code EmbedPlayerView} already stubs for the same reasons.
 */
public class MobilePlaybackActivity extends MobileActivity implements PlaybackView {

    private PlayerView mPlayerView;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private ProgressBar mProgressBar;

    private PlaybackPresenter mPresenter;
    private ExoPlayerInitializer mPlayerInitializer;
    private ExoPlayerController mExoPlayerController;
    private SimpleExoPlayer mPlayer;
    private boolean mIsEngineBlocked;

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_playback);

        bindViews();
        applySystemBarsForOrientation(getResources().getConfiguration().orientation);

        // NOTE: position matters! Mirrors EmbedPlayerView.initPlayer()/PlaybackFragment.onCreate():
        // create the controller objects and hand the presenter our view BEFORE building the actual
        // SimpleExoPlayer, then call onViewInitialized() to (re-)init all 11 playback controllers.
        mPresenter = PlaybackPresenter.instance(this);
        mPlayerInitializer = new ExoPlayerInitializer(this);
        mExoPlayerController = new ExoPlayerController(this, mPresenter);

        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        createPlayerObjects();
    }

    private void bindViews() {
        mPlayerView = findViewById(R.id.mobile_player_view);
        mTitleView = findViewById(R.id.mobile_player_title);
        mBackButton = findViewById(R.id.mobile_player_back);
        mProgressBar = findViewById(R.id.mobile_player_progress);

        mBackButton.setOnClickListener(v -> onBackPressed());
    }

    private void createPlayerObjects() {
        DefaultTrackSelector trackSelector = new RestoreTrackSelector(new AdaptiveTrackSelection.Factory());
        mExoPlayerController.setTrackSelector(trackSelector);

        DefaultRenderersFactory renderersFactory = new CustomOverridesRenderersFactory(this);
        mPlayer = mPlayerInitializer.createPlayer(this, renderersFactory, trackSelector);
        mPlayer.setPlayWhenReady(true);

        mExoPlayerController.setPlayer(mPlayer);
        mPlayerView.setPlayer(mPlayer);

        mPresenter.onEngineInitialized(); // VideoLoaderController picks up the pending video here
    }

    private void destroyPlayerObjects() {
        if (mPlayer == null) {
            return;
        }

        // Don't release a different (e.g. embed) player's engine state.
        if (mPresenter.getView() == null || mPresenter.getView() == this) {
            mPresenter.onEngineReleased();
        }

        mPlayerView.setPlayer(null);
        mPlayerInitializer.release();
        mExoPlayerController.release();
        mPlayer = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

        applySystemBarsForOrientation(getResources().getConfiguration().orientation);
    }

    @Override
    protected void onPause() {
        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Fix situations when the engine wasn't properly destroyed (mirrors PlaybackFragment).
        destroyPlayerObjects();

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        applySystemBarsForOrientation(newConfig.orientation);
    }

    @Override
    public void onBackPressed() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }

        super.onBackPressed();
    }

    /**
     * Touch "rotation/fullscreen" control (v1). Actual rotation is handled by the system
     * (manifest {@code android:screenOrientation="unspecified"} + {@code configChanges} so the
     * Activity isn't recreated, hence playback isn't interrupted) - this only follows it: go
     * edge-to-edge immersive in landscape, restore the normal status/nav bars in portrait.
     */
    private void applySystemBarsForOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Helpers.makeActivityFullscreen2(this);
        } else {
            showSystemBars();
        }
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - touch surface implemented (overlay show/hide drives PlayerView's own
    // built-in controller, which already renders play/pause + seek bar + position/duration).
    // ---------------------------------------------------------------------------------

    @Override
    public void showOverlay(boolean show) {
        if (mPlayerView == null) {
            return;
        }

        if (show) {
            mPlayerView.showController();
        } else {
            mPlayerView.hideController();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mPlayerView != null && mPlayerView.isControllerVisible();
    }

    @Override
    public void showControls(boolean show) {
        showOverlay(show);
    }

    @Override
    public boolean isControlsShown() {
        return isOverlayShown();
    }

    @Override
    public void setTitle(String title) {
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        if (mProgressBar != null) {
            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - stubs (no touch surface yet). Safe no-ops/empty defaults; don't crash.
    // ---------------------------------------------------------------------------------

    @Override
    public void updateSuggestions(VideoGroup group) {
        // TODO Wave 3: related-videos panel (swipe-up sheet under the player).
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        // TODO Wave 3: related-videos panel.
        return 0;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        // TODO Wave 3: related-videos panel. Callers null-check this (see SuggestionsController).
        return null;
    }

    @Override
    public void focusSuggestedItem(int index) {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public void focusSuggestedItem(Video video) {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public void resetSuggestedPosition() {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public boolean isSuggestionsEmpty() {
        // TODO Wave 3: related-videos panel.
        return true;
    }

    @Override
    public void clearSuggestions() {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public void showSuggestions(boolean show) {
        // TODO Wave 3: related-videos panel.
    }

    @Override
    public boolean isSuggestionsShown() {
        // TODO Wave 3: related-videos panel.
        return false;
    }

    @Override
    public int getButtonState(int buttonId) {
        // TODO Wave 3: custom touch transport row (like/dislike/quality/speed buttons).
        return BUTTON_DISABLED;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        // TODO Wave 3: custom touch transport row.
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        // TODO Wave 3: custom touch transport row.
    }

    @Override
    public void setSeekPreviewTitle(String title) {
        // TODO Wave 3: chapter/seek-preview UI.
    }

    @Override
    public void setNextTitle(Video nextVideo) {
        // TODO Wave 3: custom touch transport row ("up next" label).
    }

    @Override
    public void showDebugInfo(boolean show) {
        // TODO Wave N: debug overlay (low priority on touch).
    }

    @Override
    public void showSubtitles(boolean show) {
        // TODO Wave N: subtitle on/off toggle UI. NOTE: PlayerView already renders whatever
        // subtitle track ExoPlayerController/TrackSelectorManager has selected via its own
        // built-in SubtitleView - this only stubs the user-facing toggle, not rendering.
    }

    @Override
    public void loadStoryboard() {
        // TODO Wave N: storyboard thumbnail preview on the seek bar.
    }

    @Override
    public void setSeekBarSegments(List<SeekBarSegment> segments) {
        // TODO Wave N: SponsorBlock colored ranges on the seek bar.
    }

    @Override
    public void updateEndingTime() {
        // TODO Wave N: "ends at HH:mm" label (no surface for it yet).
    }

    @Override
    public void setChatReceiver(ChatReceiver chatReceiver) {
        // TODO Wave N: live chat panel.
    }

    // ---------------------------------------------------------------------------------
    // PlayerEngine - real, delegates to ExoPlayerController (playback-critical).
    // ---------------------------------------------------------------------------------

    @Override
    public void openSabr(MediaItemFormatInfo formatInfo) {
        mExoPlayerController.openSabr(formatInfo);
    }

    @Override
    public void openDash(MediaItemFormatInfo formatInfo) {
        mExoPlayerController.openDash(formatInfo);
    }

    @Override
    public void openDash(InputStream dashManifest) {
        mExoPlayerController.openDash(dashManifest);
    }

    @Override
    public void openDashUrl(String dashManifestUrl) {
        mExoPlayerController.openDashUrl(dashManifestUrl);
    }

    @Override
    public void openHlsUrl(String hlsPlaylistUrl) {
        mExoPlayerController.openHlsUrl(hlsPlaylistUrl);
    }

    @Override
    public void openUrlList(List<String> urlList) {
        mExoPlayerController.openUrlList(urlList);
    }

    @Override
    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        mExoPlayerController.openMerged(formatInfo, hlsPlaylistUrl);
    }

    @Override
    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        mExoPlayerController.openMerged(dashManifest, hlsPlaylistUrl);
    }

    @Override
    public long getPositionMs() {
        return mExoPlayerController.getPositionMs();
    }

    @Override
    public void setPositionMs(long positionMs) {
        mExoPlayerController.setPositionMs(positionMs);
    }

    @Override
    public long getDurationMs() {
        long durationMs = mExoPlayerController.getDurationMs();

        long liveDurationMs = getVideo() != null ? getVideo().getLiveDurationMs() : 0;

        if (durationMs > Video.MAX_LIVE_DURATION_MS && liveDurationMs != 0) {
            durationMs = liveDurationMs;
        }

        return durationMs;
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        mExoPlayerController.setPlayWhenReady(play);
    }

    @Override
    public boolean getPlayWhenReady() {
        return mExoPlayerController.getPlayWhenReady();
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayerController.isPlaying();
    }

    @Override
    public boolean isLoading() {
        return mExoPlayerController.isLoading();
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mExoPlayerController.getVideoFormats();
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mExoPlayerController.getAudioFormats();
    }

    @Override
    public List<FormatItem> getSubtitleFormats() {
        return mExoPlayerController.getSubtitleFormats();
    }

    @Override
    public void setFormat(FormatItem option) {
        mExoPlayerController.selectFormat(option);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mExoPlayerController.getVideoFormat();
    }

    @Override
    public FormatItem getAudioFormat() {
        return mExoPlayerController.getAudioFormat();
    }

    @Override
    public FormatItem getSubtitleFormat() {
        return mExoPlayerController.getSubtitleFormat();
    }

    @Override
    public boolean isEngineInitialized() {
        return mPlayer != null;
    }

    @Override
    public void restartEngine() {
        destroyPlayerObjects();
        createPlayerObjects();
    }

    @Override
    public void reloadPlayback() {
        if (mPlayer != null) {
            mPresenter.onEngineReleased();
            mPresenter.onEngineInitialized();
        }
    }

    @Override
    public void blockEngine(boolean block) {
        mIsEngineBlocked = block;
    }

    @Override
    public boolean isEngineBlocked() {
        return mIsEngineBlocked;
    }

    @Override
    public boolean isInPIPMode() {
        // TODO Wave N: Picture-in-Picture (dropped for v1 per ARCHITECTURE.md ?6).
        return false;
    }

    @Override
    public boolean containsMedia() {
        return mExoPlayerController != null && mExoPlayerController.containsMedia();
    }

    @Override
    public void setSpeed(float speed) {
        mExoPlayerController.setSpeed(speed);
    }

    @Override
    public float getSpeed() {
        return mExoPlayerController.getSpeed();
    }

    @Override
    public void setPitch(float pitch) {
        mExoPlayerController.setPitch(pitch);
    }

    @Override
    public float getPitch() {
        return mExoPlayerController.getPitch();
    }

    @Override
    public void setVolume(float volume) {
        mExoPlayerController.setVolume(volume);
    }

    @Override
    public float getVolume() {
        return mExoPlayerController.getVolume();
    }

    @Override
    public void setResizeMode(int mode) {
        if (mPlayerView != null) {
            mPlayerView.setResizeMode(mode);
        }
    }

    @Override
    public int getResizeMode() {
        return mPlayerView != null ? mPlayerView.getResizeMode() : RESIZE_MODE_DEFAULT;
    }

    @Override
    public void setZoomPercents(int percents) {
        // TODO Wave N: pinch-to-zoom gesture (no touch surface yet).
    }

    @Override
    public void setAspectRatio(float ratio) {
        // TODO Wave N: forced aspect-ratio setting (HQDialog quality sheet, Wave 4).
    }

    @Override
    public void setRotationAngle(int angle) {
        // TODO Wave N: forced video-frame rotation (rare edge case, HQDialog sheet).
    }

    @Override
    public void setVideoFlipEnabled(boolean enabled) {
        // TODO Wave N: forced video-frame flip (rare edge case, HQDialog sheet).
    }

    @Override
    public void setVideoGravity(int gravity) {
        // TODO Wave N: forced video-frame gravity (rare edge case, HQDialog sheet).
    }

    // ---------------------------------------------------------------------------------
    // PlayerManager
    // ---------------------------------------------------------------------------------

    @Override
    public void setVideo(Video item) {
        if (mExoPlayerController != null) {
            mExoPlayerController.setVideo(item);
        }

        setTitle(item != null ? item.getTitleFull() : null);
    }

    @Override
    public Video getVideo() {
        return mExoPlayerController != null ? mExoPlayerController.getVideo() : null;
    }

    // finish()/finishReally() are inherited from MobileActivity/MotherActivity, which already
    // implement the PlayerManager contract (parent-aware back navigation via ViewManager). No
    // override needed here; player resource cleanup happens in onDestroy() above.

    @Override
    public void showBackground(String url) {
        // TODO Wave N: idle/loading background art (no UriBackgroundManager equivalent yet).
    }

    @Override
    public void showBackgroundColor(int colorResId) {
        // TODO Wave N: idle/loading background art.
    }

    @Override
    public void resetPlayerState() {
        if (mExoPlayerController != null) {
            mExoPlayerController.resetPlayerState();
        }
    }

    @Override
    public boolean isEmbed() {
        return false;
    }
}
