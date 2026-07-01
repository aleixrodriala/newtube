package com.newtube.mobile.ui.playback;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;

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
import java.util.ArrayList;
import java.util.Formatter;
import java.util.LinkedHashMap;
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
    /** Trigger related-list paging when the content is scrolled within this many px of the bottom. */
    private static final int SUGGESTIONS_PAGE_THRESHOLD_PX = 800;

    private PlayerContainerLayout mContainer;
    private View mVideoArea;
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
    private SeekBarSegmentsView mSegmentsView;
    private ProgressBar mProgressBar;
    private ImageButton mQualityButton;
    private ImageButton mSubtitlesButton;
    private ImageButton mSpeedButton;

    // Watch page (portrait content column under the video).
    private NestedScrollView mWatchScroll;
    private TextView mWatchTitle;
    private TextView mWatchMeta;
    private View mWatchMetaRow;
    private ImageView mWatchExpand;
    private TextView mWatchDescription;
    private View mWatchLike;
    private ImageView mWatchLikeIcon;
    private TextView mWatchLikeCount;
    private View mWatchDislike;
    private ImageView mWatchDislikeIcon;
    private TextView mWatchDislikeCount;
    private View mWatchShare;
    private ImageView mWatchAvatar;
    private TextView mWatchChannelName;
    private TextView mWatchSubs;
    private MaterialButton mWatchSubscribe;
    private TextView mWatchRelatedLabel;
    private RecyclerView mWatchRelated;
    private RelatedVideoAdapter mRelatedAdapter;

    // Suggestions store: id -> accumulated videos (LinkedHashMap keeps delivery/row order).
    private final LinkedHashMap<Integer, List<Video>> mSuggestionVideos = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, VideoGroup> mSuggestionGroups = new LinkedHashMap<>();
    private final List<Video> mRelatedVideos = new ArrayList<>();
    private Video mLastPagedVideo;

    // Like/Dislike/Subscribe visual state, keyed by R.id.action_*.
    private final SparseIntArray mButtonStates = new SparseIntArray();

    private Video mWatchVideo;
    private String mWatchVideoId;
    private boolean mDescriptionExpanded;

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
        setupWatchContent();

        int orientation = getResources().getConfiguration().orientation;
        applyWatchLayoutForOrientation(orientation);
        applySystemBarsForOrientation(orientation);
        updateFullscreenIcon(orientation);

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
        mVideoArea = findViewById(R.id.mobile_video_area);
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
        mSegmentsView = findViewById(R.id.mobile_player_segments);
        mProgressBar = findViewById(R.id.mobile_player_progress);
        mQualityButton = findViewById(R.id.mobile_player_quality);
        mSubtitlesButton = findViewById(R.id.mobile_player_subtitles);
        mSpeedButton = findViewById(R.id.mobile_player_speed);

        // Watch page content column.
        mWatchScroll = findViewById(R.id.mobile_watch_scroll);
        mWatchTitle = findViewById(R.id.mobile_watch_title);
        mWatchMeta = findViewById(R.id.mobile_watch_meta);
        mWatchMetaRow = findViewById(R.id.mobile_watch_meta_row);
        mWatchExpand = findViewById(R.id.mobile_watch_expand);
        mWatchDescription = findViewById(R.id.mobile_watch_description);
        mWatchLike = findViewById(R.id.mobile_watch_like);
        mWatchLikeIcon = findViewById(R.id.mobile_watch_like_icon);
        mWatchLikeCount = findViewById(R.id.mobile_watch_like_count);
        mWatchDislike = findViewById(R.id.mobile_watch_dislike);
        mWatchDislikeIcon = findViewById(R.id.mobile_watch_dislike_icon);
        mWatchDislikeCount = findViewById(R.id.mobile_watch_dislike_count);
        mWatchShare = findViewById(R.id.mobile_watch_share);
        mWatchAvatar = findViewById(R.id.mobile_watch_avatar);
        mWatchChannelName = findViewById(R.id.mobile_watch_channel_name);
        mWatchSubs = findViewById(R.id.mobile_watch_subs);
        mWatchSubscribe = findViewById(R.id.mobile_watch_subscribe);
        mWatchRelatedLabel = findViewById(R.id.mobile_watch_related_label);
        mWatchRelated = findViewById(R.id.mobile_watch_related);
    }

    private void setupControls() {
        mContainer.setDragListener(this);
        // Only let a swipe-to-dismiss begin over the video box, so the watch content scrolls freely.
        mContainer.setDragStartBoundView(mVideoArea);

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

        // Player options row (Quality / Subtitles / Speed). Each dispatches an R.id.action_* through
        // the presenter so the existing controllers open their AppDialog option lists (rendered by
        // MobileAppDialogActivity). See openPlayerOption() for why some use the long-click path.
        mQualityButton.setOnClickListener(v -> openPlayerOption(R.id.lb_control_high_quality, false));
        mSubtitlesButton.setOnClickListener(v -> openPlayerOption(R.id.lb_control_closed_captioning, true));
        mSpeedButton.setOnClickListener(v -> openPlayerOption(R.id.action_video_speed, true));

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

    private void setupWatchContent() {
        mRelatedAdapter = new RelatedVideoAdapter(this::onRelatedClicked);
        mWatchRelated.setLayoutManager(new LinearLayoutManager(this));
        mWatchRelated.setNestedScrollingEnabled(false);
        mWatchRelated.setHasFixedSize(false);
        mWatchRelated.setAdapter(mRelatedAdapter);

        // Expandable description (tap the views/date row or chevron).
        mWatchMetaRow.setOnClickListener(v -> toggleDescription());

        // Actions row. Like/Dislike/Subscribe go through the presenter's onButtonClicked vocabulary
        // (R.id.action_*); the controller flips the visual state back via setButtonState. Share fires
        // a plain ACTION_SEND of the video url (per brief), independent of the presenter.
        mWatchLike.setOnClickListener(v -> onActionButtonClicked(R.id.action_thumbs_up));
        mWatchDislike.setOnClickListener(v -> onActionButtonClicked(R.id.action_thumbs_down));
        mWatchSubscribe.setOnClickListener(v -> onActionButtonClicked(R.id.action_subscribe));
        mWatchShare.setOnClickListener(v -> shareCurrentVideo());

        // Related-list paging: when the content is scrolled near the bottom, page the last row.
        mWatchScroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldX, oldY) -> {
                    View child = v.getChildAt(0);
                    if (child == null) {
                        return;
                    }
                    int distanceToBottom = child.getBottom() - (v.getHeight() + scrollY);
                    if (distanceToBottom <= SUGGESTIONS_PAGE_THRESHOLD_PX) {
                        maybePageSuggestions();
                    }
                });

        // Reflect the initial (empty) button states.
        updateButtonVisual(R.id.action_thumbs_up, BUTTON_OFF);
        updateButtonVisual(R.id.action_thumbs_down, BUTTON_OFF);
        updateButtonVisual(R.id.action_subscribe, BUTTON_OFF);
    }

    /**
     * Portrait: 16:9 video box pinned at the top, scrollable watch content filling the rest.
     * Landscape: hide the content and let the video fill the whole screen (PLAYER POLISH immersive).
     */
    private void applyWatchLayoutForOrientation(int orientation) {
        if (mVideoArea == null) {
            return;
        }

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.GONE);
            }
        } else {
            int width = getResources().getDisplayMetrics().widthPixels;
            lp.height = Math.round(width * 9f / 16f);
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.VISIBLE);
            }
        }
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

        applyWatchLayoutForOrientation(newConfig.orientation);
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
            // Immersive full-bleed video (PLAYER POLISH behaviour).
            Helpers.makeActivityFullscreen2(this);
        } else {
            // Watch page: keep the status/navigation bars; the video sits below the status bar and
            // the content column lays out inside the safe area (decor fits system windows).
            showSystemBars();
        }

        if (mControlsRoot != null) {
            ViewCompat.requestApplyInsets(mControlsRoot);
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

    /**
     * Open one of the player option sheets by dispatching its {@code R.id.action_*} through the
     * presenter, which fans it out to the reused SmartTube controllers; the matching controller
     * builds an {@code AppDialog} option list and shows it via {@link MobileAppDialogActivity}.
     *
     * <ul>
     *   <li>Quality → {@code R.id.lb_control_high_quality} (onButtonClicked): HQDialogController's
     *       playback-settings sheet (video formats/resolutions, audio formats/language, presets, ...).</li>
     *   <li>Subtitles/CC → {@code R.id.lb_control_closed_captioning} (onButtonLongClicked): the full
     *       subtitle-track picker. The plain click path only toggles the last track, so we use the
     *       long-click path to always open the list.</li>
     *   <li>Speed → {@code R.id.action_video_speed} (onButtonLongClicked): the speed list
     *       (0.25x..2x+). Same reasoning - the plain click can just toggle the remembered speed.</li>
     * </ul>
     */
    private void openPlayerOption(int actionId, boolean asLongClick) {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        int state = getButtonState(actionId);
        if (state == BUTTON_DISABLED) {
            state = BUTTON_OFF;
        }

        if (asLongClick) {
            mPresenter.onButtonLongClicked(actionId, state);
        } else {
            mPresenter.onButtonClicked(actionId, state);
        }
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
    // PlayerUI - Suggestions (related / up-next list in the portrait watch page).
    //
    // The SuggestionsController feeds this. On each new video it calls clearSuggestions() then
    // updateSuggestions(group) once per row (chapters/queue/related). We flatten all non-chapter
    // rows into a single related list keyed by group id (LinkedHashMap preserves delivery order),
    // so continuations (ACTION_APPEND, same id) append to that row. isSuggestionsShown() returns
    // false so the controller always (re)populates suggestions for the current video (the mobile
    // watch page rebuilds them per video rather than preserving a TV-style focused row).
    // ---------------------------------------------------------------------------------

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return;
        }

        // Skip chapter rows - they aren't related videos and would play as odd seek points.
        if (group.isChapters()) {
            return;
        }

        runOnUiThread(() -> {
            int id = group.getId();
            List<Video> incoming = group.getVideos();

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mSuggestionVideos.put(id, new ArrayList<>(incoming));
                    mSuggestionGroups.put(id, group);
                    break;
                case VideoGroup.ACTION_REMOVE:
                case VideoGroup.ACTION_REMOVE_AUTHOR: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing != null) {
                        existing.removeAll(incoming);
                    }
                    break;
                }
                case VideoGroup.ACTION_SYNC: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing == null) {
                        mSuggestionVideos.put(id, new ArrayList<>(incoming));
                        mSuggestionGroups.put(id, group);
                    } else {
                        for (Video v : incoming) {
                            int idx = existing.indexOf(v);
                            if (idx >= 0) {
                                existing.set(idx, v);
                            }
                        }
                    }
                    break;
                }
                case VideoGroup.ACTION_APPEND:
                default: {
                    List<Video> existing = mSuggestionVideos.get(id);
                    if (existing == null) {
                        mSuggestionVideos.put(id, new ArrayList<>(incoming));
                    } else {
                        for (Video v : incoming) {
                            if (!existing.contains(v)) {
                                existing.add(v);
                            }
                        }
                    }
                    mSuggestionGroups.put(id, group);
                    break;
                }
            }

            rebuildRelatedList();
        });
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            mSuggestionVideos.remove(group.getId());
            mSuggestionGroups.remove(group.getId());
            rebuildRelatedList();
        });
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        if (group == null) {
            return -1;
        }

        int id = group.getId();
        int i = 0;
        for (Integer key : mSuggestionVideos.keySet()) {
            if (key != null && key == id) {
                return i;
            }
            i++;
        }
        return -1;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        // Callers null-check this (see SuggestionsController.focusCurrentChapter).
        if (mRelatedVideos.isEmpty() || index < 0) {
            return null;
        }

        int i = 0;
        for (Integer key : mSuggestionVideos.keySet()) {
            if (i == index) {
                List<Video> vids = mSuggestionVideos.get(key);
                return (vids == null || vids.isEmpty()) ? null : VideoGroup.from(vids);
            }
            i++;
        }
        return null;
    }

    @Override
    public void focusSuggestedItem(int index) {
        // No TV-style row focus on touch; the related list is a plain scroll list.
    }

    @Override
    public void focusSuggestedItem(Video video) {
        // No TV-style row focus on touch.
    }

    @Override
    public void resetSuggestedPosition() {
        // No TV-style row focus on touch.
    }

    @Override
    public boolean isSuggestionsEmpty() {
        return mRelatedVideos.isEmpty();
    }

    @Override
    public void clearSuggestions() {
        runOnUiThread(() -> {
            mSuggestionVideos.clear();
            mSuggestionGroups.clear();
            mRelatedVideos.clear();
            mLastPagedVideo = null;
            if (mRelatedAdapter != null) {
                mRelatedAdapter.submitList(new ArrayList<>());
            }
            if (mWatchRelatedLabel != null) {
                mWatchRelatedLabel.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void showSuggestions(boolean show) {
        // The related list is always visible as part of the scrollable portrait content.
    }

    @Override
    public boolean isSuggestionsShown() {
        // Report "not shown" so the controller always (re)loads suggestions for the current video.
        return false;
    }

    // ---------------------------------------------------------------------------------
    // PlayerUI - action buttons (Like / Dislike / Subscribe visual state).
    // ---------------------------------------------------------------------------------

    @Override
    public int getButtonState(int buttonId) {
        if (buttonId == R.id.action_thumbs_up
                || buttonId == R.id.action_thumbs_down
                || buttonId == R.id.action_subscribe) {
            return mButtonStates.get(buttonId, BUTTON_OFF);
        }
        return BUTTON_DISABLED;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        mButtonStates.put(buttonId, buttonState);
        runOnUiThread(() -> updateButtonVisual(buttonId, buttonState));
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        runOnUiThread(() -> {
            if (mWatchAvatar == null) {
                return;
            }
            if (TextUtils.isEmpty(iconUrl)) {
                mWatchAvatar.setImageResource(R.drawable.ic_watch_channel_placeholder);
            } else {
                Glide.with(this)
                        .load(iconUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_watch_channel_placeholder)
                        .error(R.drawable.ic_watch_channel_placeholder)
                        .into(mWatchAvatar);
            }
        });
    }

    @Override
    public void setSeekPreviewTitle(String title) {
        // TODO Wave N: chapter/seek-preview UI.
    }

    @Override
    public void setNextTitle(Video nextVideo) {
        // The related/up-next list already surfaces what plays next; no separate label needed.
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
        // SponsorBlock colored ranges on the seek bar. SponsorBlockController resolves each range to
        // start/end progress fractions + an ARGB color and pushes them here (null to reset); the
        // overlay draws them on the scrubber track. Skipping itself is done by the controller.
        if (mSegmentsView == null) {
            return;
        }
        runOnUiThread(() -> mSegmentsView.setSegments(segments));
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
    // Watch page - header binding, actions and related list.
    // ---------------------------------------------------------------------------------

    /**
     * Bind the watch-page header from the current {@link Video}. Called from {@link #setVideo} on
     * every update: the presenter/SuggestionsController calls setVideo() again after folding the
     * loaded metadata (and again after the real Return-YouTube-Dislike counts) into the Video, so
     * the like/dislike/subscriber counts and description fill in as they arrive. A fresh video id
     * additionally resets the header, scrolls back to the top and kicks off a metadata load for the
     * bits not stored on the Video (channel avatar, clean view-count / date line).
     */
    private void bindWatchVideo(Video item) {
        if (item == null || mWatchTitle == null) {
            return;
        }

        mWatchVideo = item;
        boolean isNewVideo = !Helpers.equals(item.videoId, mWatchVideoId);

        if (isNewVideo) {
            mWatchVideoId = item.videoId;
            resetWatchHeader();
            if (mWatchScroll != null) {
                mWatchScroll.scrollTo(0, 0);
            }
        }

        mWatchTitle.setText(item.getTitleFull());
        mWatchChannelName.setText(item.getAuthor());

        // Fallback meta line until the metadata load returns a clean "views • date".
        CharSequence second = item.getSecondTitleFull();
        if (mWatchMeta.length() == 0 && !TextUtils.isEmpty(second)) {
            mWatchMeta.setText(second);
        }

        if (!TextUtils.isEmpty(item.likeCount)) {
            mWatchLikeCount.setText(item.likeCount);
        }
        if (!TextUtils.isEmpty(item.dislikeCount)) {
            mWatchDislikeCount.setText(item.dislikeCount);
        }
        if (!TextUtils.isEmpty(item.subscriberCount)) {
            mWatchSubs.setText(item.subscriberCount);
            mWatchSubs.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.isEmpty(item.description)) {
            mWatchDescription.setText(item.description);
        }

        if (isNewVideo) {
            MediaServiceManager.instance().loadMetadata(item, this::onWatchMetadata);
        }
    }

    private void resetWatchHeader() {
        mDescriptionExpanded = false;
        mWatchDescription.setVisibility(View.GONE);
        mWatchDescription.setText(null);
        mWatchMeta.setText(null);
        mWatchLikeCount.setText(R.string.mobile_watch_count_placeholder);
        mWatchDislikeCount.setText(R.string.mobile_watch_count_placeholder);
        mWatchSubs.setText(null);
        mWatchSubs.setVisibility(View.GONE);
        mWatchAvatar.setImageResource(R.drawable.ic_watch_channel_placeholder);
    }

    private void onWatchMetadata(MediaItemMetadata metadata) {
        if (metadata == null) {
            return;
        }

        runOnUiThread(() -> {
            // Clean "views • date" line.
            String views = metadata.getViewCount();
            String date = metadata.getPublishedDate();
            String meta;
            if (!TextUtils.isEmpty(views) && !TextUtils.isEmpty(date)) {
                meta = views + "  •  " + date;
            } else if (!TextUtils.isEmpty(views)) {
                meta = views;
            } else {
                meta = date;
            }
            if (!TextUtils.isEmpty(meta)) {
                mWatchMeta.setText(meta);
            }

            String description = metadata.getDescription();
            if (!TextUtils.isEmpty(description)) {
                mWatchDescription.setText(description);
            }

            if (!TextUtils.isEmpty(metadata.getAuthor())) {
                mWatchChannelName.setText(metadata.getAuthor());
            }

            if (!TextUtils.isEmpty(metadata.getSubscriberCount())) {
                mWatchSubs.setText(metadata.getSubscriberCount());
                mWatchSubs.setVisibility(View.VISIBLE);
            }

            setChannelIcon(metadata.getAuthorImageUrl());

            // Counts: prefer the real values already synced onto the Video; fall back to metadata.
            if (isCountUnset(mWatchLikeCount) && !TextUtils.isEmpty(metadata.getLikeCount())) {
                mWatchLikeCount.setText(metadata.getLikeCount());
            }
            if (isCountUnset(mWatchDislikeCount) && !TextUtils.isEmpty(metadata.getDislikeCount())) {
                mWatchDislikeCount.setText(metadata.getDislikeCount());
            }

            // Initial like/dislike/subscribe button states (also pushed by PlayerUIController.onMetadata).
            setButtonState(R.id.action_thumbs_up,
                    metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE ? BUTTON_ON : BUTTON_OFF);
            setButtonState(R.id.action_thumbs_down,
                    metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_DISLIKE ? BUTTON_ON : BUTTON_OFF);
            setButtonState(R.id.action_subscribe, metadata.isSubscribed() ? BUTTON_ON : BUTTON_OFF);
        });
    }

    private boolean isCountUnset(TextView view) {
        CharSequence text = view.getText();
        return TextUtils.isEmpty(text) || getString(R.string.mobile_watch_count_placeholder).contentEquals(text);
    }

    private void toggleDescription() {
        if (TextUtils.isEmpty(mWatchDescription.getText())) {
            return;
        }

        mDescriptionExpanded = !mDescriptionExpanded;
        mWatchDescription.setVisibility(mDescriptionExpanded ? View.VISIBLE : View.GONE);
        mWatchExpand.setRotation(mDescriptionExpanded ? 180f : 0f);
    }

    /** Route Like / Dislike / Subscribe through the presenter's onButtonClicked vocabulary. */
    private void onActionButtonClicked(int actionId) {
        if (mPresenter == null) {
            return;
        }

        int currentState = getButtonState(actionId);
        if (currentState == BUTTON_DISABLED) {
            currentState = BUTTON_OFF;
        }

        // The controller performs the toggle and calls setButtonState() back with the new state.
        mPresenter.onButtonClicked(actionId, currentState);
    }

    private void updateButtonVisual(int buttonId, int buttonState) {
        boolean on = buttonState == BUTTON_ON;

        if (buttonId == R.id.action_thumbs_up && mWatchLikeIcon != null) {
            mWatchLikeIcon.setColorFilter(getColorInt(on
                    ? R.color.mobile_color_primary : R.color.mobile_color_on_surface));
        } else if (buttonId == R.id.action_thumbs_down && mWatchDislikeIcon != null) {
            mWatchDislikeIcon.setColorFilter(getColorInt(on
                    ? R.color.mobile_color_primary : R.color.mobile_color_on_surface));
        } else if (buttonId == R.id.action_subscribe && mWatchSubscribe != null) {
            mWatchSubscribe.setText(on ? R.string.mobile_watch_subscribed : R.string.mobile_watch_subscribe);
            mWatchSubscribe.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColorInt(on ? R.color.mobile_color_pill : R.color.mobile_color_primary)));
            mWatchSubscribe.setTextColor(getColorInt(on
                    ? R.color.mobile_color_on_surface : android.R.color.white));
        }
    }

    private int getColorInt(int colorRes) {
        return androidx.core.content.ContextCompat.getColor(this, colorRes);
    }

    private void shareCurrentVideo() {
        Video video = getVideo();
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            return;
        }

        String url = "https://youtu.be/" + video.videoId;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.mobile_watch_share_subject));
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, getString(R.string.mobile_watch_share)));
    }

    private void onRelatedClicked(Video video) {
        if (mPresenter != null && video != null) {
            // Loads + plays the tapped video in this same player (VideoLoaderController.openVideoInt).
            mPresenter.onSuggestionItemClicked(video);
        }
    }

    private void maybePageSuggestions() {
        if (mPresenter == null || mRelatedVideos.isEmpty()) {
            return;
        }

        Video last = mRelatedVideos.get(mRelatedVideos.size() - 1);
        if (last == mLastPagedVideo) {
            return;
        }

        mLastPagedVideo = last;
        // The controller derives the row to continue from last.getGroup().
        mPresenter.onScrollEnd(last);
    }

    private void rebuildRelatedList() {
        mRelatedVideos.clear();
        for (List<Video> vids : mSuggestionVideos.values()) {
            mRelatedVideos.addAll(vids);
        }

        if (mRelatedAdapter != null) {
            mRelatedAdapter.submitList(new ArrayList<>(mRelatedVideos));
        }
        if (mWatchRelatedLabel != null) {
            mWatchRelatedLabel.setVisibility(mRelatedVideos.isEmpty() ? View.GONE : View.VISIBLE);
        }
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
        bindWatchVideo(item);
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
