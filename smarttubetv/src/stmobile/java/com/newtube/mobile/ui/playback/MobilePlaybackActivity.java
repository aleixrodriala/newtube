package com.newtube.mobile.ui.playback;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.vkay94.dtpv.DoubleTapPlayerView;
import com.github.vkay94.dtpv.DoubleTapPlayerViewImpl;
import com.github.vkay94.dtpv.youtube.YouTubeOverlay;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.DefaultTimeBar;
import com.google.android.exoplayer2.ui.TimeBar;
import com.google.android.exoplayer2.util.Util;
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
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.io.InputStream;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

/**
 * Touch player - PLAYER POLISH wave.
 *
 * <p>Built the same way as the {@code EmbedPlayerView} template (ARCHITECTURE.md, section 6): a
 * plain ExoPlayer {@code PlayerView} (here the {@link DoubleTapPlayerViewImpl} subclass, still no
 * Leanback) wired straight to {@link ExoPlayerController} and {@link ExoPlayerInitializer}, with
 * this Activity itself implementing {@link PlaybackView} and being handed to
 * {@link PlaybackPresenter#setView}. The 11 playback controllers owned by {@code PlaybackPresenter}
 * (VideoLoader, VideoState, Suggestions, ErrorFixer, PlayerUI, ...) are reused completely unchanged;
 * this class is only the touch View layer. The engine (open calls, position, duration, play-pause,
 * speed, formats, resize) is untouched - this wave adds a polished custom control surface, open/close
 * transitions, swipe-down-to-dismiss and buffer tuning on top.</p>
 *
 * <h3>Controls</h3>
 * The stock {@code PlaybackControlView} is disabled ({@code use_controller="false"}); instead a
 * custom overlay (top back + title, large center play/pause/replay, bottom {@link DefaultTimeBar}
 * with current/total time + fullscreen toggle) is shown/hidden on a single tap and auto-hidden after
 * {@link #AUTO_HIDE_MS}. Double-tap left/right seeks +/-10s via the {@code doubletapplayerview}
 * module's {@link YouTubeOverlay}, wired to the live player. Position/buffer are polled and seeking
 * is wired straight to {@link ExoPlayerController}.
 */
public class MobilePlaybackActivity extends MobileActivity
        implements PlaybackView, PlayerContainerLayout.DragListener {

    private static final long AUTO_HIDE_MS = 3_500;
    private static final long PROGRESS_UPDATE_MS = 500;

    private PlayerContainerLayout mContainer;
    private DoubleTapPlayerViewImpl mPlayerView;
    private YouTubeOverlay mYouTubeOverlay;
    private View mControlsRoot;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private ImageButton mPlayPauseButton;
    private ImageButton mFullscreenButton;
    private TextView mPositionView;
    private TextView mDurationView;
    private DefaultTimeBar mTimeBar;
    private ProgressBar mProgressBar;

    private PlaybackPresenter mPresenter;
    private ExoPlayerInitializer mPlayerInitializer;
    private ExoPlayerController mExoPlayerController;
    private SimpleExoPlayer mPlayer;
    private boolean mIsEngineBlocked;

    private boolean mControlsVisible;
    private boolean mScrubbing;
    private boolean mIsEnded;

    private final StringBuilder mFormatBuilder = new StringBuilder();
    private final Formatter mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());

    private final Runnable mHideControlsRunnable = this::onAutoHideTick;
    private final Runnable mProgressUpdateRunnable = this::onProgressTick;

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Buffer/cache tuning (see class doc / report). Must be set BEFORE the player is created:
        // ExoPlayerInitializer.createLoadControl() reads PlayerData.getVideoBufferType() when
        // createPlayer() runs. BUFFER_HIGH = 50s min/max + 50s back-buffer, while ExoPlayer's
        // bufferForPlaybackMs stays at the engine default (2.5s) so the first frame still starts
        // fast - a quick start with a generous cushion against mid-stream stutter on mobile data.
        // (No on-disk SimpleCache: wiring CacheDataSource would require editing common/'s
        // ExoMediaSourceFactory, which this wave must not touch, so we tune buffers only.)
        PlayerData.instance(this).setVideoBufferType(PlayerData.BUFFER_HIGH);

        setContentView(R.layout.activity_mobile_playback);

        bindViews();
        setupControls();
        applySystemBarsForOrientation(getResources().getConfiguration().orientation);
        updateFullscreenIcon(getResources().getConfiguration().orientation);

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
        mContainer = findViewById(R.id.mobile_player_container);
        mPlayerView = findViewById(R.id.mobile_player_view);
        mYouTubeOverlay = findViewById(R.id.mobile_player_yt_overlay);
        mControlsRoot = findViewById(R.id.mobile_controls_root);
        mTitleView = findViewById(R.id.mobile_player_title);
        mBackButton = findViewById(R.id.mobile_player_back);
        mPlayPauseButton = findViewById(R.id.mobile_player_play_pause);
        mFullscreenButton = findViewById(R.id.mobile_player_fullscreen);
        mPositionView = findViewById(R.id.mobile_player_position);
        mDurationView = findViewById(R.id.mobile_player_duration);
        mTimeBar = findViewById(R.id.mobile_player_time_bar);
        mProgressBar = findViewById(R.id.mobile_player_progress);
    }

    private void setupControls() {
        mContainer.setDragListener(this);

        // The window is edge-to-edge (MotherActivity.makeActivityFullscreen2 sets translucent
        // status/nav flags, so the video fills behind the bars). Pad ONLY the controls overlay by
        // the system-bar insets so the back/title and the seek row never hide under, or get their
        // taps eaten by, the status/navigation bars. In landscape immersive the bars are hidden so
        // the insets are 0 and the controls go full-bleed.
        ViewCompat.setOnApplyWindowInsetsListener(mControlsRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // Single tap on the video surface toggles the overlay. DoubleTapPlayerViewImpl routes a
        // single (non-double) tap to performClick() on the view captured at construction (itself,
        // since it isn't attached yet), so an OnClickListener here is exactly that single tap.
        mPlayerView.setOnClickListener(v -> toggleControls());

        // Tap on empty overlay space hides the controls (buttons/seek bar consume their own taps).
        mControlsRoot.setOnClickListener(v -> hideControls());

        mBackButton.setOnClickListener(v -> onBackPressed());
        mPlayPauseButton.setOnClickListener(v -> togglePlayPause());
        mFullscreenButton.setOnClickListener(v -> toggleFullscreen());

        mTimeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                mScrubbing = true;
                cancelAutoHide();
                mPositionView.setText(formatTime(position));
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                mPositionView.setText(formatTime(position));
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                mScrubbing = false;
                if (!canceled && mExoPlayerController != null) {
                    mExoPlayerController.setPositionMs(position);
                }
                armAutoHide();
            }
        });

        // Start with the controls visible so the back button / title are immediately reachable on
        // open; the auto-hide timer takes them away once playback is actually running.
        mControlsVisible = true;
        mControlsRoot.setVisibility(View.VISIBLE);
        mControlsRoot.setAlpha(1f);
        armAutoHide();
    }

    private void createPlayerObjects() {
        DefaultTrackSelector trackSelector = new RestoreTrackSelector(new AdaptiveTrackSelection.Factory());
        mExoPlayerController.setTrackSelector(trackSelector);

        DefaultRenderersFactory renderersFactory = new CustomOverridesRenderersFactory(this);
        mPlayer = mPlayerInitializer.createPlayer(this, renderersFactory, trackSelector);
        mPlayer.setPlayWhenReady(true);

        mExoPlayerController.setPlayer(mPlayer);
        mPlayerView.setPlayer(mPlayer);

        // Wire the YouTube-style double-tap seek overlay to the live player.
        // NOTE: PerformListener.shouldForward() compiles to an abstract method (the Kotlin default
        // body lives in DefaultImpls, invisible to Java), so it must be implemented here. We use the
        // ExoPlayer-correct version: left third rewinds, right third forwards, middle is ignored.
        mYouTubeOverlay
                .performListener(new YouTubeOverlay.PerformListener() {
                    @Override
                    public void onAnimationStart() {
                        mYouTubeOverlay.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationEnd() {
                        mYouTubeOverlay.setVisibility(View.GONE);
                    }

                    @Override
                    public Boolean shouldForward(Player player, DoubleTapPlayerView playerView, float posX) {
                        int state = player.getPlaybackState();
                        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                            return null;
                        }
                        if (player.getCurrentPosition() > 500 && posX < playerView.getPlayerWidth() * 0.35f) {
                            return false;
                        }
                        if (posX > playerView.getPlayerWidth() * 0.65f) {
                            return true;
                        }
                        return null;
                    }
                })
                .player(mPlayer)
                .playerView(mPlayerView);
        mPlayerView.controller(mYouTubeOverlay);

        // Our own lightweight UI listener (separate from ExoPlayerController's): drives the
        // buffering spinner, the play/pause/replay icon and the end-of-video state.
        mPlayer.addListener(mUiPlayerListener);

        mPresenter.onEngineInitialized(); // VideoLoaderController picks up the pending video here

        startProgressUpdates();
        updatePlayPauseIcon();
    }

    private void destroyPlayerObjects() {
        if (mPlayer == null) {
            return;
        }

        stopProgressUpdates();
        mPlayer.removeListener(mUiPlayerListener);

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
        cancelAutoHide();

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
        updateFullscreenIcon(newConfig.orientation);
    }

    @Override
    public void onBackPressed() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }

        super.onBackPressed();
    }

    /**
     * Landscape = edge-to-edge immersive fullscreen; portrait = normal with the status bar back.
     * Actual rotation is handled by the system (manifest {@code configChanges} keeps the live
     * ExoPlayer instance across rotation); this only follows it.
     */
    private void applySystemBarsForOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Helpers.makeActivityFullscreen2(this);
        } else {
            showSystemBarsEdgeToEdge();
        }

        if (mControlsRoot != null) {
            ViewCompat.requestApplyInsets(mControlsRoot);
        }
    }

    /**
     * Portrait: show the status + navigation bars but keep the window edge-to-edge (decor does NOT
     * fit system windows) so the video stays full-bleed behind them AND window insets are still
     * dispatched to the controls overlay's listener (which pads itself out of the bars). This is
     * deliberately different from {@code MobileActivity.showSystemBars()}, whose
     * {@code setDecorFitsSystemWindows(true)} would consume the insets before the controls see them.
     */
    private void showSystemBarsEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        } else {
            // Layout flags only (no FULLSCREEN/HIDE_NAVIGATION) -> edge-to-edge with bars visible.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---------------------------------------------------------------------------------
    // Custom touch controls
    // ---------------------------------------------------------------------------------

    private void toggleControls() {
        if (mControlsVisible) {
            hideControls();
        } else {
            showControlsInternal(true);
        }
    }

    private void showControlsInternal(boolean animate) {
        mControlsVisible = true;
        mControlsRoot.setVisibility(View.VISIBLE);
        mControlsRoot.animate().cancel();
        if (animate) {
            mControlsRoot.setAlpha(0f);
            mControlsRoot.animate().alpha(1f).setDuration(150).start();
        } else {
            mControlsRoot.setAlpha(1f);
        }
        updatePlayPauseIcon();

        if (mPresenter != null) {
            mPresenter.onControlsShown(true);
        }

        armAutoHide();
    }

    private void hideControls() {
        if (!mControlsVisible) {
            return;
        }

        mControlsVisible = false;
        cancelAutoHide();
        mControlsRoot.animate().cancel();
        mControlsRoot.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    if (!mControlsVisible) {
                        mControlsRoot.setVisibility(View.GONE);
                    }
                }).start();

        if (mPresenter != null) {
            mPresenter.onControlsShown(false);
        }
    }

    private void armAutoHide() {
        cancelAutoHide();
        Utils.postDelayed(mHideControlsRunnable, AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        Utils.removeCallbacks(mHideControlsRunnable);
    }

    private void onAutoHideTick() {
        if (!mControlsVisible) {
            return;
        }

        // Keep the controls up while the user is scrubbing or while paused/buffering/ended;
        // re-check shortly. Only auto-hide during steady playback (matches YouTube/PlayerUIController).
        if (mScrubbing || mIsEnded || mPlayer == null || !isPlaying()) {
            armAutoHide();
            return;
        }

        hideControls();
    }

    private void togglePlayPause() {
        if (mExoPlayerController == null) {
            return;
        }

        if (mIsEnded) {
            // Replay from the start.
            mIsEnded = false;
            mExoPlayerController.setPositionMs(0);
            mExoPlayerController.setPlayWhenReady(true);
            if (mPresenter != null) {
                mPresenter.onPlayClicked();
            }
        } else {
            boolean play = !mExoPlayerController.getPlayWhenReady();
            mExoPlayerController.setPlayWhenReady(play);
            if (mPresenter != null) {
                if (play) {
                    mPresenter.onPlayClicked();
                } else {
                    mPresenter.onPauseClicked();
                }
            }
        }

        updatePlayPauseIcon();
        armAutoHide();
    }

    private void toggleFullscreen() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        armAutoHide();
    }

    private void updateFullscreenIcon(int orientation) {
        if (mFullscreenButton == null) {
            return;
        }

        mFullscreenButton.setImageResource(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? R.drawable.ic_player_fullscreen_exit
                : R.drawable.ic_player_fullscreen);
    }

    private void updatePlayPauseIcon() {
        if (mPlayPauseButton == null) {
            return;
        }

        if (mIsEnded) {
            mPlayPauseButton.setImageResource(R.drawable.ic_player_replay);
            mPlayPauseButton.setContentDescription(getString(R.string.mobile_player_replay));
        } else if (mExoPlayerController != null && mExoPlayerController.getPlayWhenReady()) {
            mPlayPauseButton.setImageResource(R.drawable.ic_player_pause);
            mPlayPauseButton.setContentDescription(getString(R.string.mobile_player_pause));
        } else {
            mPlayPauseButton.setImageResource(R.drawable.ic_player_play);
            mPlayPauseButton.setContentDescription(getString(R.string.mobile_player_play));
        }
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        Utils.postDelayed(mProgressUpdateRunnable, 0);
    }

    private void stopProgressUpdates() {
        Utils.removeCallbacks(mProgressUpdateRunnable);
    }

    private void onProgressTick() {
        if (mPlayer == null || mExoPlayerController == null) {
            return;
        }

        if (!mScrubbing) {
            long position = mExoPlayerController.getPositionMs();
            long duration = getDurationMs();
            long buffered = mPlayer.getBufferedPosition();

            if (duration < 0) {
                duration = 0;
            }
            if (position < 0) {
                position = 0;
            }

            mTimeBar.setDuration(duration);
            mTimeBar.setPosition(position);
            mTimeBar.setBufferedPosition(buffered);
            mPositionView.setText(formatTime(position));
            mDurationView.setText(formatTime(duration));
        }

        updatePlayPauseIcon();

        Utils.postDelayed(mProgressUpdateRunnable, PROGRESS_UPDATE_MS);
    }

    private String formatTime(long timeMs) {
        if (timeMs < 0) {
            timeMs = 0;
        }
        return Util.getStringForTime(mFormatBuilder, mFormatter, timeMs);
    }

    private final Player.EventListener mUiPlayerListener = new Player.EventListener() {
        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            switch (playbackState) {
                case Player.STATE_BUFFERING:
                    showProgressBar(true);
                    break;
                case Player.STATE_READY:
                    showProgressBar(false);
                    mIsEnded = false;
                    break;
                case Player.STATE_ENDED:
                    showProgressBar(false);
                    mIsEnded = true;
                    // Surface the replay affordance.
                    showControlsInternal(true);
                    break;
                default:
                    break;
            }
            updatePlayPauseIcon();
        }
    };

    // ---------------------------------------------------------------------------------
    // Swipe-down-to-dismiss (PlayerContainerLayout.DragListener)
    // ---------------------------------------------------------------------------------

    @Override
    public boolean canStartDismissDrag() {
        return !mScrubbing && mPlayer != null;
    }

    @Override
    public void onDismissDrag(float dy) {
        mContainer.setTranslationY(dy);
        int height = Math.max(1, mContainer.getHeight());
        float fraction = Math.min(1f, dy / (height * 0.5f));
        mContainer.setAlpha(1f - 0.5f * fraction);
    }

    @Override
    public void onDismissDragReleased(float dy, float yVelocity) {
        int height = Math.max(1, mContainer.getHeight());
        boolean dismiss = dy > height * 0.22f || (yVelocity > 2200f && dy > height * 0.08f);

        if (dismiss) {
            mContainer.animate()
                    .translationY(height)
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(this::closeByDrag)
                    .start();
        } else {
            mContainer.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        }
    }

    private void closeByDrag() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
        finish();
        // We already animated the slide-out; skip the window close animation to avoid double motion.
        overridePendingTransition(0, 0);
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - touch surface implemented (drives the custom overlay above).
    // ---------------------------------------------------------------------------------

    @Override
    public void showOverlay(boolean show) {
        if (show) {
            showControlsInternal(true);
        } else {
            hideControls();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mControlsVisible;
    }

    @Override
    public void showControls(boolean show) {
        showOverlay(show);
    }

    @Override
    public boolean isControlsShown() {
        return mControlsVisible;
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
