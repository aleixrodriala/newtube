package com.newtube.mobile.ui.playback;

import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.app.PendingIntent;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Rational;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.LiveChatService;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

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
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.DebugInfoManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
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
        implements PlaybackView, PlayerContainerLayout.DragListener, LiveChatSheet.Host {

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
    private ImageButton mPipButton;
    private ImageButton mMoreButton;
    private ImageButton mPrevButton;
    private ImageButton mNextButton;
    private ViewGroup mDebugViewGroup;

    // Subtitle styling + debug overlay. Both mirror the TV PlaybackFragment wiring: SubtitleManager
    // applies the user's stored SubtitleStyle to the PlayerView's built-in SubtitleView; the
    // DebugInfoManager drives the "stats for nerds" overlay. Created lazily once the player exists.
    private SubtitleManager mSubtitleManager;
    private DebugInfoManager mDebugInfoManager;

    // Screen-orientation lock toggled from the overflow menu ("Rotate lock").
    private boolean mOrientationLocked;

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

    // Comments + live chat entries (open bottom sheets). Keys come from the loaded metadata.
    private View mWatchCommentsEntry;
    private View mWatchChatEntry;
    private String mCommentsKey;
    private String mLiveChatKey;

    // Live chat stream: the reused ChatController pushes a ChatReceiver here; incoming messages
    // accumulate into a bounded buffer that the LiveChatSheet seeds from and observes live.
    private static final int MAX_CHAT_ITEMS = 250;
    private final List<ChatItem> mChatItems = new ArrayList<>();
    private ChatReceiver mChatReceiver;
    private LiveChatSheet.Observer mChatObserver;
    private Disposable mLiveChatAction;
    // Dedicated metadata load for the watch header + comments/chat keys. Kept independent of the
    // shared MediaServiceManager singleton (whose single Disposable the player's own loads clobber).
    private Disposable mMetadataAction;

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
    private boolean mIsInPip;

    // Background-playback foreground service (reuses THIS Activity's player; see MobilePlaybackService).
    private MobilePlaybackService mPlaybackService;
    private boolean mServiceBound;

    // Picture-in-Picture play/pause RemoteAction wiring.
    private static final String ACTION_PIP_TOGGLE = "com.newtube.mobile.action.PIP_TOGGLE";
    private static final int PIP_REQUEST_TOGGLE = 700;
    private BroadcastReceiver mPipReceiver;

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

        // NOTE: buffer tuning is applied locally around createPlayer() (see createPlayerObjects),
        // NOT here - forcing PlayerData.setVideoBufferType() on every onCreate permanently
        // overwrote the user's persisted global buffer preference. See createPlayerObjects().

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

        registerPipReceiver();
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
        mPipButton = findViewById(R.id.mobile_player_pip);
        mMoreButton = findViewById(R.id.mobile_player_more);
        mPrevButton = findViewById(R.id.mobile_player_previous);
        mNextButton = findViewById(R.id.mobile_player_next);
        mDebugViewGroup = findViewById(R.id.mobile_player_debug);

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
        mWatchCommentsEntry = findViewById(R.id.mobile_watch_comments_entry);
        mWatchChatEntry = findViewById(R.id.mobile_watch_chat_entry);
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

        // Manual previous/next skip (auto-advance already handled by the controllers).
        if (mPrevButton != null) {
            mPrevButton.setOnClickListener(v -> {
                if (mPresenter != null) {
                    mPresenter.onPreviousClicked();
                }
                armAutoHide();
            });
        }
        if (mNextButton != null) {
            mNextButton.setOnClickListener(v -> {
                if (mPresenter != null) {
                    mPresenter.onNextClicked();
                }
                armAutoHide();
            });
        }

        // Overflow "⋮" menu: the long tail of SmartTube player actions.
        if (mMoreButton != null) {
            mMoreButton.setOnClickListener(v -> openPlayerMenu());
        }

        // Player options row (Quality / Subtitles / Speed). Each dispatches an R.id.action_* through
        // the presenter so the existing controllers open their AppDialog option lists (rendered by
        // MobileAppDialogActivity). See openPlayerOption() for why some use the long-click path.
        mQualityButton.setOnClickListener(v -> openPlayerOption(R.id.lb_control_high_quality, false));
        mSubtitlesButton.setOnClickListener(v -> openPlayerOption(R.id.lb_control_closed_captioning, true));
        mSpeedButton.setOnClickListener(v -> openPlayerOption(R.id.action_video_speed, true));

        // Picture-in-Picture control button. Only offered when the device supports PiP.
        if (mPipButton != null) {
            if (Helpers.isPictureInPictureSupported(this)) {
                mPipButton.setOnClickListener(v -> enterPipMode());
            } else {
                mPipButton.setVisibility(View.GONE);
            }
        }

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

        // Comments / live-chat entries open their respective bottom sheets.
        if (mWatchCommentsEntry != null) {
            mWatchCommentsEntry.setOnClickListener(v -> onCommentsEntryClicked());
        }
        if (mWatchChatEntry != null) {
            mWatchChatEntry.setOnClickListener(v -> onChatEntryClicked());
        }

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

        // Buffer tuning applied LOCALLY (see class doc). ExoPlayerInitializer.createLoadControl()
        // reads PlayerData.getVideoBufferType() during createPlayer(), so temporarily force
        // BUFFER_HIGH (50s min/max + back-buffer, generous cushion against mobile-data stutter),
        // then restore the user's persisted global immediately. The already-built LoadControl keeps
        // the high value; the saved preference is left exactly as the user chose it. (Previously
        // this was set in onCreate and never restored, permanently clobbering the global pref.)
        PlayerData playerData = PlayerData.instance(this);
        int priorBufferType = playerData.getVideoBufferType();
        playerData.setVideoBufferType(PlayerData.BUFFER_HIGH);
        try {
            mPlayer = mPlayerInitializer.createPlayer(this, renderersFactory, trackSelector);
        } finally {
            playerData.setVideoBufferType(priorBufferType);
        }
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

        // Apply the user's subtitle style to the PlayerView's built-in SubtitleView (see gap #2).
        // Registered AFTER setPlayer() so our (styled) SubtitleManager is the last TextOutput and
        // wins over PlayerView's default component that would otherwise render with embedded styles.
        createSubtitleManager();

        mPresenter.onEngineInitialized(); // VideoLoaderController picks up the pending video here

        // Attach the reused player to the background-playback service (media session + notification).
        // If already bound (e.g. after restartEngine) re-attach directly; otherwise start+bind now
        // while this Activity is in the foreground so startForeground is reached from the foreground.
        if (mServiceBound && mPlaybackService != null) {
            mPlaybackService.attachPlayer(mPlayer, mPresenter, buildContentIntent());
        } else {
            bindPlaybackService();
        }

        startProgressUpdates();
        updatePlayPauseIcon();
    }

    private void destroyPlayerObjects() {
        if (mPlayer == null) {
            return;
        }

        stopProgressUpdates();
        mPlayer.removeListener(mUiPlayerListener);

        // Tear down the debug overlay (removes its Player listener) and drop the subtitle manager
        // before the player is released. SubtitleManager registers on PlayerData via a WeakHashSet,
        // so nulling the reference is enough to let it be collected.
        if (mDebugInfoManager != null) {
            mDebugInfoManager.show(false);
            mDebugInfoManager = null;
        }
        mSubtitleManager = null;

        // Detach the player from the media session/notification BEFORE releasing it, so the service
        // never references a released player. The service (and any audio) stops here on real finish.
        if (mPlaybackService != null) {
            mPlaybackService.detachPlayer();
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
        cancelAutoHide();

        RxHelper.disposeActions(mMetadataAction, mLiveChatAction);

        // Fix situations when the engine wasn't properly destroyed (mirrors PlaybackFragment).
        destroyPlayerObjects();

        // Real finish: tear down the background-playback service and the PiP receiver.
        unbindPlaybackService();
        unregisterPipReceiver();

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    /**
     * HOME pressed (or the app is otherwise being sent to the background) while a video is playing:
     * slip into Picture-in-Picture so the video keeps playing in a floating window. If PiP isn't
     * available the background-playback service keeps the audio going instead (see MobilePlaybackService).
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mIsInPip || isFinishing()) {
            return;
        }
        if (!Helpers.isPictureInPictureSupported(this)) {
            return;
        }
        // Only auto-enter PiP while actually playing (matches YouTube; avoids PiP on a paused pre-roll).
        if (mPlayer == null || !isPlaying()) {
            return;
        }
        // Don't hijack navigation to one of our own screens (e.g. opening a dialog / channel).
        if (getViewManager() != null && getViewManager().isNewViewPending()) {
            return;
        }

        enterPipMode();
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
    // Picture-in-Picture
    // ---------------------------------------------------------------------------------

    /** Enter PiP: shrink the video into a floating window that keeps playing, with a play/pause action. */
    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !Helpers.isPictureInPictureSupported(this)
                || mIsInPip) {
            return;
        }

        try {
            enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            // Device reported PiP support but refused (e.g. OEM restriction) - ignore, stay full-screen.
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();

        builder.setAspectRatio(getVideoAspectRatio());

        // Smooth expand/collapse animation anchored on the current video box.
        if (mVideoArea != null) {
            Rect sourceRect = new Rect();
            mVideoArea.getGlobalVisibleRect(sourceRect);
            if (!sourceRect.isEmpty()) {
                builder.setSourceRectHint(sourceRect);
            }
        }

        builder.setActions(java.util.Collections.singletonList(buildPlayPauseAction()));

        return builder.build();
    }

    /** Video aspect ratio for the PiP window, clamped to the range Android accepts (~0.42..2.39). */
    private Rational getVideoAspectRatio() {
        int width = 16;
        int height = 9;

        if (mPlayer != null && mPlayer.getVideoFormat() != null && mPlayer.getVideoFormat().height > 0) {
            width = mPlayer.getVideoFormat().width;
            height = mPlayer.getVideoFormat().height;
        }

        float ratio = (float) width / height;
        if (ratio < 0.5f) {
            return new Rational(1, 2);
        }
        if (ratio > 2.3f) {
            return new Rational(23, 10);
        }
        return new Rational(width, height);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction buildPlayPauseAction() {
        boolean playing = mExoPlayerController != null && mExoPlayerController.getPlayWhenReady() && !mIsEnded;

        int iconRes = playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play;
        int labelRes = playing ? R.string.mobile_player_pause : R.string.mobile_player_play;

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent intent = PendingIntent.getBroadcast(
                this,
                PIP_REQUEST_TOGGLE,
                new Intent(ACTION_PIP_TOGGLE).setPackage(getPackageName()),
                piFlags);

        Icon icon = Icon.createWithResource(this, iconRes);
        return new RemoteAction(icon, getString(labelRes), getString(labelRes), intent);
    }

    /** Refresh the PiP window's play/pause action to reflect the current state (icon swap). */
    private void updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !mIsInPip) {
            return;
        }
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (Exception e) {
            // ignore
        }
    }

    private void registerPipReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mPipReceiver != null) {
            return;
        }

        mPipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_PIP_TOGGLE.equals(intent.getAction())) {
                    togglePlayPause();
                    updatePipActions();
                }
            }
        };

        // Internal-only broadcast; must be flagged not-exported on API 34+.
        ContextCompat.registerReceiver(this, mPipReceiver,
                new IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterPipReceiver() {
        if (mPipReceiver != null) {
            try {
                unregisterReceiver(mPipReceiver);
            } catch (Exception e) {
                // not registered
            }
            mPipReceiver = null;
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        mIsInPip = isInPictureInPictureMode;

        if (isInPictureInPictureMode) {
            // Video only: hide the controls overlay and the watch-page content, fill with the video.
            cancelAutoHide();
            hideControls();
            if (mControlsRoot != null) {
                mControlsRoot.setVisibility(View.GONE);
            }
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.GONE);
            }
            if (mVideoArea != null) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.weight = 0;
                mVideoArea.setLayoutParams(lp);
            }
            updatePipActions();
        } else {
            // Restore the normal layout for the current orientation; controls come back on tap.
            int orientation = newConfig != null ? newConfig.orientation
                    : getResources().getConfiguration().orientation;
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            updatePlayPauseIcon();
        }
    }

    // ---------------------------------------------------------------------------------
    // Background-playback service (media session + notification; reuses THIS player)
    // ---------------------------------------------------------------------------------

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mPlaybackService = ((MobilePlaybackService.LocalBinder) binder).getService();
            mServiceBound = true;
            if (mPlayer != null) {
                mPlaybackService.attachPlayer(mPlayer, mPresenter, buildContentIntent());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mPlaybackService = null;
            mServiceBound = false;
        }
    };

    private void bindPlaybackService() {
        Intent intent = new Intent(this, MobilePlaybackService.class);
        // startService keeps it alive independently of the binding so audio survives backgrounding;
        // safe to call here because the player is created while this Activity is in the foreground.
        try {
            startService(intent);
        } catch (Exception e) {
            // Background start restrictions - fall back to bind-only (audio still survives while bound).
        }
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindPlaybackService() {
        if (mServiceBound) {
            try {
                unbindService(mServiceConnection);
            } catch (Exception e) {
                // not bound
            }
            mServiceBound = false;
        }
        try {
            stopService(new Intent(this, MobilePlaybackService.class));
        } catch (Exception e) {
            // ignore
        }
        mPlaybackService = null;
    }

    private PendingIntent buildContentIntent() {
        Intent intent = new Intent(this, MobilePlaybackActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, intent, piFlags);
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

    // ---------------------------------------------------------------------------------
    // Overflow ("⋮") menu: the long tail of SmartTube player actions.
    //
    // The top controls only expose Quality/Subtitles/Speed/PiP + prev/next + play-pause. Everything
    // else SmartTube offers on the player is reached from here. Each row dispatches an R.id.action_*
    // through PlaybackPresenter (same vocabulary the TV VideoPlayerGlue used) so the reused
    // PlayerUIController does the real work: dialog-opening actions (repeat/zoom/playlist/queue) show
    // their AppDialog via the touch MobileAppDialogActivity; simple toggles (stats/screen-off) flip
    // and are reflected here. Actions with no mobile meaning (AFR) are omitted; "Rotate lock" is a
    // native screen-orientation lock rather than the TV video-frame rotate.
    // ---------------------------------------------------------------------------------

    private void openPlayerMenu() {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        BottomSheetDialog sheet = new BottomSheetDialog(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_mobile_sheet);
        content.setPadding(0, dp(8), 0, dp(16));

        View handle = new View(this);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(8);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_mobile_sheet_handle);
        content.addView(handle);

        TextView title = new TextView(this);
        title.setText(R.string.mobile_menu_title);
        title.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(4), dp(20), dp(12));
        content.addView(title);

        boolean shuffleOn = PlayerData.instance(this).getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        boolean statsOn = getButtonState(R.id.action_video_stats) == BUTTON_ON;
        boolean screenOffOn = getButtonState(R.id.action_screen_dimming) == BUTTON_ON;

        // Repeat mode -> playback-mode dialog (long-click path always opens the picker; the plain
        // click just cycles). The dialog includes Shuffle among its radio options too.
        addMenuRow(content, sheet, R.string.mobile_menu_repeat, null, () -> openPlayerOption(R.id.action_repeat, true));
        // Dedicated Shuffle toggle (SHUFFLE <-> ALL) for quick access.
        addMenuRow(content, sheet, R.string.mobile_menu_shuffle, stateLabel(shuffleOn), this::toggleShuffleMode);
        // Video zoom / aspect ratio / rotate dialog.
        addMenuRow(content, sheet, R.string.mobile_menu_zoom, null, () -> openPlayerOption(R.id.action_video_zoom, false));
        // Play as audio / background mode (PiP-on-home etc.).
        addMenuRow(content, sheet, R.string.mobile_menu_background, null, this::openBackgroundModeDialog);
        // Screen off / dimming toggle.
        addMenuRow(content, sheet, R.string.mobile_menu_screen_off, stateLabel(screenOffOn), () -> openPlayerOption(R.id.action_screen_dimming, false));
        // Stats for nerds (debug overlay) toggle.
        addMenuRow(content, sheet, R.string.mobile_menu_stats, stateLabel(statsOn), () -> openPlayerOption(R.id.action_video_stats, false));
        // Rotate lock (native screen-orientation lock).
        addMenuRow(content, sheet, R.string.mobile_menu_rotate_lock, stateLabel(mOrientationLocked), this::toggleRotateLock);
        // Add to playlist.
        addMenuRow(content, sheet, R.string.mobile_menu_playlist_add, null, () -> openPlayerOption(R.id.action_playlist_add, false));
        // Playback queue.
        addMenuRow(content, sheet, R.string.mobile_menu_queue, null, () -> openPlayerOption(R.id.action_playback_queue, false));

        sheet.setContentView(content);
        sheet.setOnDismissListener(d -> armAutoHide());
        sheet.show();
    }

    private void addMenuRow(LinearLayout container, BottomSheetDialog sheet, int labelRes,
                            String trailing, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(resolveSelectableItemBackground());
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        if (trailing != null) {
            TextView state = new TextView(this);
            state.setText(trailing);
            state.setTextColor(getColorInt(R.color.mobile_color_on_surface_secondary));
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.addView(state);
        }

        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });

        container.addView(row);
    }

    private String stateLabel(boolean on) {
        return getString(on ? R.string.mobile_menu_on : R.string.mobile_menu_off);
    }

    private int resolveSelectableItemBackground() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Toggle the SmartTube playback mode between Shuffle and All (default). Persisted in PlayerData. */
    private void toggleShuffleMode() {
        PlayerData pd = PlayerData.instance(this);
        boolean wasShuffle = pd.getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        int mode = wasShuffle ? PlayerConstants.PLAYBACK_MODE_ALL : PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        pd.setPlaybackMode(mode);
        // Reflect on the (hidden) repeat button state so the menu shows the right On/Off next time.
        setButtonState(R.id.action_repeat, mode);
    }

    /** Open the Play-in-background / audio-mode option dialog via the reused AppDialog path. */
    private void openBackgroundModeDialog() {
        cancelAutoHide();
        AppDialogPresenter dialog = AppDialogPresenter.instance(this);
        OptionCategory category = AppDialogUtil.createBackgroundPlaybackCategory(
                this, PlayerData.instance(this), GeneralData.instance(this));
        dialog.appendRadioCategory(category.title, category.options);
        dialog.showDialog(getString(R.string.mobile_menu_background));
    }

    /** Native screen-orientation lock (mobile equivalent of "rotate lock"). */
    private void toggleRotateLock() {
        mOrientationLocked = !mOrientationLocked;
        setRequestedOrientation(mOrientationLocked
                ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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
                    // Surface the replay affordance - but NOT while in PiP: full-size controls would
                    // appear inside the tiny PiP window and onAutoHideTick would keep re-arming them
                    // (mIsEnded stays true), so they'd never hide. In PiP the RemoteAction handles it.
                    if (!mIsInPip) {
                        showControlsInternal(true);
                    }
                    break;
                default:
                    break;
            }
            updatePlayPauseIcon();
            // Keep the PiP play/pause action icon in sync with the real playback state.
            updatePipActions();
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
                || buttonId == R.id.action_subscribe
                || buttonId == R.id.action_chat
                // Overflow-menu toggles: track state so the reused controllers can flip them and
                // the menu can reflect On/Off. setButtonState() already stores every id it receives.
                || buttonId == R.id.action_repeat
                || buttonId == R.id.action_video_stats
                || buttonId == R.id.action_screen_dimming
                || buttonId == R.id.action_playlist_add
                || buttonId == R.id.action_rotate
                || buttonId == R.id.action_sound_off) {
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
        // "Stats for nerds" overlay. Mirrors PlaybackFragment.showDebugInfo(): lazily build the
        // DebugInfoManager over the debug view group and toggle it. Driven by the reused
        // PlayerUIController (action_video_stats -> onDebugInfoClicked -> showDebugInfo()).
        createDebugManager();
        if (mDebugInfoManager != null) {
            mDebugInfoManager.show(show);
        }
    }

    @Override
    public void showSubtitles(boolean show) {
        // The user's subtitle STYLE (size/color/background/position) is applied by SubtitleManager
        // over the PlayerView's built-in SubtitleView. This toggles that view's visibility; actual
        // track selection is done by ExoPlayerController/TrackSelectorManager.
        createSubtitleManager();
        if (mSubtitleManager != null) {
            mSubtitleManager.show(show);
        }
    }

    /**
     * Build the {@link SubtitleManager} over the PlayerView's built-in {@link
     * com.google.android.exoplayer2.ui.SubtitleView} and register it as a text output, so the user's
     * stored {@code SubtitleStyle} (from SubtitleSettingsPresenter) actually takes effect. Mirrors
     * PlaybackFragment.createSubtitleManager(). Idempotent.
     */
    private void createSubtitleManager() {
        if (mSubtitleManager != null || mPlayer == null || mPlayerView == null) {
            return;
        }

        com.google.android.exoplayer2.ui.SubtitleView subtitleView = mPlayerView.getSubtitleView();
        if (subtitleView == null) {
            return;
        }

        mSubtitleManager = new SubtitleManager(subtitleView);

        if (mPlayer.getTextComponent() != null) {
            mPlayer.getTextComponent().addTextOutput(mSubtitleManager);
        }
    }

    /** Build the {@link DebugInfoManager} over the debug overlay group. Mirrors the TV fragment. */
    private void createDebugManager() {
        if (mDebugInfoManager != null || mDebugViewGroup == null || mPlayer == null) {
            return;
        }
        mDebugInfoManager = new DebugInfoManager(mDebugViewGroup, mPlayer, mPlayerInitializer);
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
        // The reused ChatController pushes a receiver when live chat is enabled for a live stream
        // (and null when it is torn down). Subscribe to it: each incoming ChatItem is buffered and
        // forwarded to an open LiveChatSheet. Best-effort - if the video isn't live this is never
        // called and the chat panel stays hidden.
        runOnUiThread(() -> {
            mChatReceiver = chatReceiver;

            if (chatReceiver == null) {
                return;
            }

            chatReceiver.setCallback(this::onChatItemReceived);

            // Chat is now streaming; make sure the entry is reachable even if metadata was slow.
            if (mWatchChatEntry != null) {
                mWatchChatEntry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void onChatItemReceived(ChatItem item) {
        if (item == null) {
            return;
        }
        runOnUiThread(() -> {
            mChatItems.add(item);
            while (mChatItems.size() > MAX_CHAT_ITEMS) {
                mChatItems.remove(0);
            }
            if (mChatObserver != null) {
                mChatObserver.onChatItem(item);
            }
        });
    }

    private void onCommentsEntryClicked() {
        if (mCommentsKey == null) {
            return;
        }
        Video video = getVideo();
        CharSequence title = video != null ? video.getTitleFull() : getString(R.string.mobile_comments_title);
        CommentsSheet.show(getSupportFragmentManager(), mCommentsKey, title);
    }

    private void onChatEntryClicked() {
        // If the reused ChatController already pushed a receiver (live chat auto-enabled in settings),
        // messages already flow through setChatReceiver(); just open the panel. Otherwise open our own
        // subscription to the same LiveChatService the controller uses, keyed by the live-chat key
        // from the metadata (live chat defaults to off, so the controller won't have started it).
        if (mChatReceiver == null && mLiveChatAction == null) {
            startLiveChatStream();
        }
        LiveChatSheet.show(getSupportFragmentManager());
    }

    private void startLiveChatStream() {
        if (mLiveChatKey == null) {
            return;
        }
        RxHelper.disposeActions(mLiveChatAction);
        LiveChatService chatService = YouTubeServiceManager.instance().getLiveChatService();
        mLiveChatAction = chatService.openLiveChatObserve(mLiveChatKey)
                .subscribe(
                        this::onChatItemReceived,
                        error -> { /* stream error - panel keeps last messages */ },
                        () -> { /* live chat session closed */ });
    }

    // ---------------------------------------------------------------------------------
    // LiveChatSheet.Host - expose the buffered chat stream to the open sheet.
    // ---------------------------------------------------------------------------------

    @Override
    public List<ChatItem> getChatSnapshot() {
        return new ArrayList<>(mChatItems);
    }

    @Override
    public void registerChatObserver(LiveChatSheet.Observer observer) {
        mChatObserver = observer;
    }

    @Override
    public void unregisterChatObserver(LiveChatSheet.Observer observer) {
        if (mChatObserver == observer) {
            mChatObserver = null;
        }
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
            loadWatchMetadata(item);
        }
    }

    /**
     * Load the current video's metadata for the watch header and the comments/live-chat keys. Uses a
     * private Disposable + the {@link MediaItemService} directly rather than the shared
     * {@link MediaServiceManager} singleton, whose single metadata Disposable is disposed by the
     * player's own concurrent loads (which was silently cancelling this callback).
     */
    private void loadWatchMetadata(Video item) {
        RxHelper.disposeActions(mMetadataAction);

        MediaItemService itemService = YouTubeServiceManager.instance().getMediaItemService();
        Observable<MediaItemMetadata> observable = item.mediaItem != null
                ? itemService.getMetadataObserve(item.mediaItem)
                : itemService.getMetadataObserve(item.videoId, item.getPlaylistId(), item.playlistIndex, item.playlistParams);

        mMetadataAction = observable.subscribe(this::onWatchMetadata, error -> { /* header stays on fallback */ });
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

        // New video: clear comments/chat availability and any buffered chat until metadata returns.
        mCommentsKey = null;
        mLiveChatKey = null;
        if (mWatchCommentsEntry != null) {
            mWatchCommentsEntry.setVisibility(View.GONE);
        }
        if (mWatchChatEntry != null) {
            mWatchChatEntry.setVisibility(View.GONE);
        }
        mChatItems.clear();
        RxHelper.disposeActions(mLiveChatAction);
        mLiveChatAction = null;
        mChatReceiver = null;
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

            // Comments / live-chat availability. A non-null comments key = comments enabled; a
            // non-null live-chat key = live stream. Reuse the same keys the TV controllers use.
            mCommentsKey = metadata.getCommentsKey();
            mLiveChatKey = metadata.getLiveChatKey();
            if (mWatchCommentsEntry != null) {
                mWatchCommentsEntry.setVisibility(mCommentsKey != null ? View.VISIBLE : View.GONE);
            }
            if (mWatchChatEntry != null && mLiveChatKey != null) {
                mWatchChatEntry.setVisibility(View.VISIBLE);
            }
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
        return mIsInPip;
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
