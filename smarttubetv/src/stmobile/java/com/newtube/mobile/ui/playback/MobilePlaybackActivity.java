package com.newtube.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Rational;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.mediaserviceinterfaces.LiveChatService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.rxjava3.disposables.Disposable;

import com.github.vkay94.dtpv3.DoubleTapPlayerView;
import com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl;
import com.github.vkay94.dtpv3.youtube.YouTubeOverlay;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.SubtitleSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.SubtitleTrack;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.newtube.mobile.casting.CastPickerLauncher;
import com.newtube.mobile.casting.CastSessionManager;
import com.newtube.mobile.casting.CastTarget;
import com.newtube.mobile.casting.CastVolumeKeys;
import com.newtube.mobile.player.Media3DebugInfoManager;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.Media3PlayerInitializer;
import com.newtube.mobile.player.Media3SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.SessionWarmup;
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
 * plain media3 {@code PlayerView} (here the {@link DoubleTapPlayerViewImpl} subclass, still no
 * Leanback) wired straight to {@link Media3PlayerController} and {@link Media3PlayerInitializer}, with
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
 * is wired straight to {@link Media3PlayerController}.
 */
public class MobilePlaybackActivity extends MobileActivity
        implements PlaybackView, PlayerContainerLayout.DragListener, LiveChatSheet.Host {

    private static final long AUTO_HIDE_MS = 3_500;
    private static final long PROGRESS_UPDATE_MS = 500;
    /** Live-edge jump target: mirrors the shared VideoStateController's ~15s park behind the edge. */
    private static final long LIVE_EDGE_OFFSET_MS = 15_000;
    /** Within this of the edge counts as "watching live" (the park offset plus segment slack). */
    private static final long LIVE_EDGE_THRESHOLD_MS = 20_000;
    /** Trigger related-list paging when the content is scrolled within this many px of the bottom. */
    private static final int SUGGESTIONS_PAGE_THRESHOLD_PX = 800;

    private PlayerContainerLayout mContainer;
    private PinchZoomLayout mVideoArea;
    private TextView mZoomHintView;
    private DoubleTapPlayerViewImpl mPlayerView;
    private YouTubeOverlay mYouTubeOverlay;
    private View mControlsRoot;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private ImageButton mPlayPauseButton;
    private ImageButton mFullscreenButton;
    private TextView mPositionView;
    private TextView mDurationView;
    private TextView mLiveChip;
    private DefaultTimeBar mTimeBar;
    private SeekBarSegmentsView mSegmentsView;
    private ProgressBar mProgressBar;
    /** First-run only: "one-time setup" line under the spinner while the session is cold. */
    private TextView mSetupHint;
    private ImageButton mSubtitlesButton;
    private ImageButton mMoreButton;
    private ImageButton mPrevButton;
    private ImageButton mNextButton;
    private ViewGroup mDebugViewGroup;

    // Casting (Route B scaffolding): cast button + the "Playing on TV" remote panel. The panel is
    // self-contained (own seek bar driven by CastEvents) so the local transport stays untouched.
    private ImageButton mCastButton;
    private View mCastOverlay;
    private TextView mCastOverlayTitle;
    private ImageButton mCastPlayPause;
    private TextView mCastLiveChip;
    private View mCastTimeline;
    private TextView mCastPosition;
    private TextView mCastDuration;
    private SeekBar mCastSeekBar;
    private CastSessionManager mCastSessionManager;
    private boolean mCastScrubbing;
    /** Optimistic receiver-caption selection; Lounge doesn't echo full track metadata. */
    @Nullable
    private String mCastSubtitleVssId;
    @Nullable
    private String mCastSubtitleLabel;

    // Subtitle styling + debug overlay. Both mirror the TV PlaybackFragment wiring: the subtitle
    // manager applies the user's stored SubtitleStyle to the PlayerView's built-in SubtitleView;
    // the debug manager drives the "stats for nerds" overlay. Created lazily once the player exists.
    private Media3SubtitleManager mSubtitleManager;
    private Media3DebugInfoManager mDebugInfoManager;

    // Screen-orientation lock toggled from the overflow menu ("Rotate lock").
    private boolean mOrientationLocked;

    // Watch page (portrait content column under the video).
    private View mWatchRoot;
    private NestedScrollView mWatchScroll;
    private View mWatchContent;
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
    private View mRelatedSkeleton;
    private android.animation.ValueAnimator mSkeletonPulse;
    private RecyclerView mWatchRelated;
    private RelatedVideoAdapter mRelatedAdapter;

    // Comments + live chat entries (open bottom sheets). Keys come from the loaded metadata.
    private View mWatchCommentsEntry;
    /** Chapters of the current video (Video.isChapter items from the suggestions pipeline). */
    private final List<Video> mChapterVideos = new ArrayList<>();
    /** Shown above the seek bar ONLY while scrubbing: the chapter under the scrub position. */
    private TextView mScrubChapterView;
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
    // NEWTUBE(mobile-ttff): the watch header binds from the SINGLE metadata document that
    // SuggestionsController already loads (delivered via PlaybackView.onWatchMetadata), instead of a
    // 2nd getMetadataObserve. Header binding is deferred until the first frame has rendered so it never
    // competes with first-frame render: metadata arriving early is stashed here and applied on first
    // STATE_READY. Accessed on the UI thread only.
    private MediaItemMetadata mPendingMetadata;
    private boolean mFirstFrameReady;

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

    /**
     * Keep the portrait video box tied to the width the watch page actually receives. Display
     * metrics can still describe the old landscape window inside onConfigurationChanged (observed
     * on Pixel), which used to leave a 2251 * 9 / 16 tall black box after rotating back.
     */
    private final View.OnLayoutChangeListener mWatchRootLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                if (width > 0
                        && getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT) {
                    applyPortraitVideoHeight(width);
                }
            };

    private PlaybackPresenter mPresenter;
    private Media3PlayerInitializer mPlayerInitializer;
    private Media3PlayerController mExoPlayerController;
    private ExoPlayer mPlayer;
    private boolean mIsEngineBlocked;

    private boolean mControlsVisible;
    private boolean mScrubbing;
    private boolean mIsEnded;
    private boolean mIsInPip;
    /** True between onStop and onStart; distinguishes PiP-dismiss orderings (see onPictureInPictureModeChanged). */
    private boolean mIsStopped;
    /** True between onResume and onPause. Gates auto-enter PiP (see {@link #shouldAutoEnterPip()}). */
    private boolean mIsResumed;
    /**
     * Set when PiP mode ends, cleared when the fullscreen UI actually resumes. If onStop arrives
     * with it still set, the PiP window was DISMISSED (the X / swipe-away), not expanded: Android 16's
     * pip2 shell doesn't finish the activity on dismiss (it delivers modeChanged(false) then onStop
     * and parks the task at the bottom), so without this the "closed" video kept playing audio
     * forever and the stopped player lingered as a zombie task that hijacked the next video open.
     */
    private boolean mPipDismissPending;
    /**
     * Requested orientation captured when a PiP stint began, restored when it ends;
     * {@link #ORIENTATION_NONE} when there was nothing to restore.
     *
     * <p>A landscape lock must never ride into the pinned task. {@link #toggleFullscreen()} sets
     * SCREEN_ORIENTATION_SENSOR_LANDSCAPE and nothing used to clear it, so going fullscreen and then
     * leaving the app produced a pinned task whose activity still demanded landscape. The expand
     * transition then could not complete at all: the window stayed {@code mode=pinned} with
     * {@code requestedOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE} forever, ignoring both taps
     * and an explicit relaunch, while rendering sideways against a portrait display. Reproduced on a
     * Pixel 9 (Android 16) as: fullscreen -&gt; home -&gt; the PiP window can no longer be opened.</p>
     */
    private int mPrePipOrientation = ORIENTATION_NONE;
    /** True while in true background audio-only playback (video renderer dropped); see setBackgroundAudioMode. */
    private boolean mBackgroundAudioMode;

    // Background-playback foreground service (reuses THIS Activity's player; see MobilePlaybackService).
    private MobilePlaybackService mPlaybackService;
    private boolean mServiceBound;

    // Picture-in-Picture play/pause RemoteAction wiring.
    private static final String ACTION_PIP_TOGGLE = "com.newtube.mobile.action.PIP_TOGGLE";
    private static final int PIP_REQUEST_TOGGLE = 700;
    /** Sentinel for {@link #mPrePipOrientation}: no orientation was captured. */
    private static final int ORIENTATION_NONE = Integer.MIN_VALUE;
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

        SystemPipBridge.attach(this);

        // A tapped card supplies its own geometry-driven open below. Suppress Android's whole-
        // window animation so it cannot fade/scale the custom thumbnail morph a second time.
        if (PlayerTransitionBridge.hasPending()) {
            overridePendingTransition(0, 0);
        }

        // NOTE: buffer tuning is applied locally around createPlayer() (see createPlayerObjects),
        // NOT here - forcing PlayerData.setVideoBufferType() on every onCreate permanently
        // overwrote the user's persisted global buffer preference. See createPlayerObjects().

        setContentView(R.layout.activity_mobile_playback);

        registerBackHandler(this::handleBack);

        bindViews();
        setupVideoSurface();
        setupControls();
        setupWatchContent();

        int orientation = getResources().getConfiguration().orientation;
        applyWatchLayoutForOrientation(orientation);
        applySystemBarsForOrientation(orientation);
        updateFullscreenIcon(orientation);

        // NOTE: position matters! Mirrors EmbedPlayerView.initPlayer()/PlaybackFragment.onCreate():
        // create the controller objects and hand the presenter our view BEFORE building the actual
        // player, then call onViewInitialized() to (re-)init all 11 playback controllers.
        // NEWTUBE(media3): the engine behind this activity is androidx.media3; the controller
        // mirrors ExoPlayerController's surface, so everything below it is unchanged.
        mPresenter = PlaybackPresenter.instance(this);
        mPlayerInitializer = new Media3PlayerInitializer(this);
        mExoPlayerController = new Media3PlayerController(this, mPresenter);

        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        createPlayerObjects();

        registerPipReceiver();

        // Casting: observe the (process-wide) session so the "Playing on TV" panel follows
        // whatever session connects/updates/ends while this player is open. A session that
        // already exists (activity recreated mid-cast) restores the panel immediately.
        mCastSessionManager = CastSessionManager.instance(this);
        mCastSessionManager.addListener(mCastListener);
        if (mCastSessionManager.isConnected()) {
            showCastOverlay();
        }
        updateCastIconTint();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // REORDER_TO_FRONT reuses this instance after mini mode. The pending card snapshot means
        // this is a new feed selection, not a plain mini-card expansion.
        if (PlayerTransitionBridge.hasPending()) {
            overridePendingTransition(0, 0);
        }
    }

    private void bindViews() {
        mContainer = findViewById(R.id.mobile_player_container);
        mVideoArea = findViewById(R.id.mobile_video_area);
        mZoomHintView = findViewById(R.id.mobile_player_zoom_hint);
        mPlayerView = findViewById(R.id.mobile_player_view);
        mYouTubeOverlay = findViewById(R.id.mobile_player_yt_overlay);
        mControlsRoot = findViewById(R.id.mobile_controls_root);
        mTitleView = findViewById(R.id.mobile_player_title);
        mBackButton = findViewById(R.id.mobile_player_back);
        mPlayPauseButton = findViewById(R.id.mobile_player_play_pause);
        mFullscreenButton = findViewById(R.id.mobile_player_fullscreen);
        mPositionView = findViewById(R.id.mobile_player_position);
        mDurationView = findViewById(R.id.mobile_player_duration);
        mLiveChip = findViewById(R.id.mobile_player_live);
        mLiveChip.setOnClickListener(v -> jumpToLiveEdge());
        mTimeBar = findViewById(R.id.mobile_player_time_bar);
        mSegmentsView = findViewById(R.id.mobile_player_segments);
        mProgressBar = findViewById(R.id.mobile_player_progress);
        mSetupHint = findViewById(R.id.mobile_player_setup_hint);
        mCastButton = findViewById(R.id.mobile_player_cast);
        mCastOverlay = findViewById(R.id.mobile_cast_overlay);
        mCastOverlayTitle = findViewById(R.id.mobile_cast_overlay_title);
        mCastPlayPause = findViewById(R.id.mobile_cast_play_pause);
        mCastLiveChip = findViewById(R.id.mobile_cast_live);
        mCastTimeline = findViewById(R.id.mobile_cast_timeline);
        mCastPosition = findViewById(R.id.mobile_cast_position);
        mCastDuration = findViewById(R.id.mobile_cast_duration);
        mCastSeekBar = findViewById(R.id.mobile_cast_seekbar);
        mSubtitlesButton = findViewById(R.id.mobile_player_subtitles);
        mMoreButton = findViewById(R.id.mobile_player_more);
        mPrevButton = findViewById(R.id.mobile_player_previous);
        mNextButton = findViewById(R.id.mobile_player_next);
        mDebugViewGroup = findViewById(R.id.mobile_player_debug);

        // Watch page content column.
        mWatchRoot = findViewById(R.id.mobile_watch_root);
        mWatchRoot.addOnLayoutChangeListener(mWatchRootLayoutListener);
        mWatchScroll = findViewById(R.id.mobile_watch_scroll);
        mWatchContent = findViewById(R.id.mobile_watch_content);
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
        mRelatedSkeleton = findViewById(R.id.mobile_watch_related_skeleton);
        mWatchRelated = findViewById(R.id.mobile_watch_related);
        mWatchCommentsEntry = findViewById(R.id.mobile_watch_comments_entry);
        mWatchChatEntry = findViewById(R.id.mobile_watch_chat_entry);
        mScrubChapterView = findViewById(R.id.mobile_player_scrub_chapter);
    }

    private void setupControls() {
        mContainer.setDragListener(this);
        // Only let a swipe-to-dismiss begin over the video box, so the watch content scrolls freely.
        mContainer.setDragStartBoundView(mVideoArea);

        // Pinch on the video = YouTube's zoom-to-fill toggle. Enabled in landscape/fullscreen only
        // (applyWatchLayoutForOrientation), like the official app.
        mVideoArea.setPinchListener(this::onPinchZoom);

        // PLAYER LAYOUT POLISH. The controls overlay fills the video box.
        //  * LANDSCAPE/fullscreen: the video is full-bleed to the screen edges, so inset the whole
        //    overlay by the system bars (notch/status/nav) so the back/title and the seek row never
        //    hide under, or get their taps eaten by, the status/navigation bars.
        //  * PORTRAIT: the decor fits the system windows (see applyMobileSystemBars) - the video box
        //    starts below a solid status bar, YouTube-style, so the window insets are already
        //    consumed and the controls just anchor FLUSH to the video box (no extra padding).
        ViewCompat.setOnApplyWindowInsetsListener(mControlsRoot, (v, insets) -> {
            applyControlsInsets();
            return insets;
        });
        // Re-run on every size change too: the insets pass alone proved unreliable across the
        // fullscreen rotation (it can fire before the window has its landscape size, leaving the
        // controls pinned to the far screen corners).
        mControlsRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                applyControlsInsets();
            }
        });

        // No extra system-bar padding on the watch page itself: in portrait the Activity content
        // container already applies the top/bottom safe insets (MobileActivity.installContentInsets;
        // under enforced edge-to-edge the insets arrive UNconsumed, so padding here again doubled
        // the bottom margin), and in landscape the content column is GONE and the video is
        // deliberately full-bleed.

        // Single tap on the video surface toggles the overlay. DoubleTapPlayerViewImpl routes a
        // single (non-double) tap to performClick() on the view captured at construction (itself,
        // since it isn't attached yet), so an OnClickListener here is exactly that single tap.
        mPlayerView.setOnClickListener(v -> toggleControls());

        // Tap on empty overlay space hides the controls (buttons/seek bar consume their own taps).
        mControlsRoot.setOnClickListener(v -> hideControls());

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
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

        // Gear menu: quality/speed/PiP plus the long tail of SmartTube player actions
        // (see openPlayerMenu). The top-right row stays a YouTube-style trio: cast, CC, gear.
        if (mMoreButton != null) {
            mMoreButton.setOnClickListener(v -> openPlayerMenu());
        }

        // Cast picker (Route B). The button stays visible even while the sender implementation is
        // pending in the submodule - the picker degrades to browse-only with a toast on connect.
        if (mCastButton != null) {
            mCastButton.setOnClickListener(v -> openCastPicker());
        }
        setupCastOverlay();
        // CC tap toggles captions like the official app (last-used track <-> off; first use falls
        // through to the picker). The full track picker is the native captions sheet, reachable via
        // long-press here and the gear menu's Subtitles row - see showCaptionsSheet().
        mSubtitlesButton.setOnClickListener(v -> toggleCaptions());
        mSubtitlesButton.setOnLongClickListener(v -> {
            showCaptionsSheet();
            return true;
        });

        mTimeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                mScrubbing = true;
                // Grabbing the bar definitively ends any double-tap seek burst: make sure the
                // release seek below resolves with the bounded default, not a leaked directional
                // NEXT/PREVIOUS_SYNC (see the seek-burst watchdog doc).
                endUserSeekBurst();
                cancelAutoHide();
                mPositionView.setText(formatTime(position));
                updateScrubChapterLabel(position);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                mPositionView.setText(formatTime(position));
                updateScrubChapterLabel(position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                mScrubbing = false;
                if (mScrubChapterView != null) {
                    mScrubChapterView.setVisibility(View.GONE);
                }
                if (!canceled && mExoPlayerController != null) {
                    // Plain seek -> the player's mobile default, the bounded 5s/1s tolerance
                    // set at player creation (see that comment for the EXACT-vs-PREVIOUS_SYNC
                    // measurements this replaces).
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

        // Channel row (avatar + name/subs) opens the channel page; the Subscribe button inside
        // the row keeps its own click. ChannelPresenter resolves the channelId from metadata
        // when the Video doesn't carry one yet, so this works right after a cold open too.
        View channelRow = findViewById(R.id.mobile_watch_channel_row);
        if (channelRow != null) {
            channelRow.setOnClickListener(v -> openCurrentChannel());
        }

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

        // Pinch-zoom is a fullscreen gesture (the portrait 16:9 box keeps its two-finger touches
        // for nothing - matching YouTube, which only zooms in fullscreen).
        mVideoArea.setPinchEnabled(orientation == Configuration.ORIENTATION_LANDSCAPE);

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.GONE);
            }
            // The watch-page title is hidden with the content in fullscreen, so retain the compact
            // title in the player chrome there.
            if (mTitleView != null) {
                mTitleView.setVisibility(View.VISIBLE);
            }
        } else {
            // Configuration width is already updated when display metrics/window views can still
            // have the previous landscape bounds. The layout listener above then makes this exact
            // for split-screen, inset and resize changes once the new window has been laid out.
            Configuration config = getResources().getConfiguration();
            int width = Math.round(config.screenWidthDp
                    * getResources().getDisplayMetrics().density);
            if (width > 0) {
                lp.height = Math.round(width * 9f / 16f);
                lp.weight = 0;
                mVideoArea.setLayoutParams(lp);
            }
            if (mWatchScroll != null) {
                mWatchScroll.setVisibility(View.VISIBLE);
            }
            // Portrait already presents the complete title immediately below the video. Repeating
            // it in the overlay squeezes five useful controls into half the top bar and makes the
            // player look crowded, especially for two-line titles.
            if (mTitleView != null) {
                mTitleView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * A pinch crossed the trigger ratio: snap between "fill the screen" (crop) and "original"
     * (fit). Writes the same PlayerData pair as the overflow "Zoom / aspect ratio" dialog
     * (AppDialogUtil.createVideoZoomCategory), so the gesture, the dialog selection and the
     * PlayerUIController restore-on-init all stay one setting.
     */
    private void onPinchZoom(boolean zoomIn) {
        int mode = zoomIn ? RESIZE_MODE_FIT_BOTH : RESIZE_MODE_DEFAULT;
        PlayerData playerData = PlayerData.instance(this);
        playerData.setResizeMode(mode);
        playerData.setZoomPercents(-1);
        animateResizeMode(mode);
        showZoomHint(zoomIn ? R.string.mobile_player_zoom_fill : R.string.mobile_player_zoom_original);
    }

    /**
     * Apply a resize mode with YouTube's smooth grow/shrink instead of a one-frame snap: capture
     * the content frame's current VISUAL size, switch the mode, then on the first pre-draw of the
     * new layout start scaled to the old size and animate to 1. Aspect is preserved in both fit
     * and zoom modes, so a single uniform factor is exact.
     */
    private void animateResizeMode(int mode) {
        ViewGroup contentFrame = mPlayerView != null ? mPlayerView.getContentFrame() : null;
        if (contentFrame == null || contentFrame.getWidth() == 0 || getResizeMode() == mode) {
            setResizeMode(mode);
            return;
        }
        final float visualWidth = contentFrame.getWidth() * contentFrame.getScaleX();
        contentFrame.animate().cancel();
        setResizeMode(mode);
        OneShotPreDrawListener.add(contentFrame, () -> {
            if (contentFrame.getWidth() == 0) {
                return;
            }
            float startScale = visualWidth / contentFrame.getWidth();
            contentFrame.setScaleX(startScale);
            contentFrame.setScaleY(startScale);
            contentFrame.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private final Runnable mHideZoomHint = new Runnable() {
        @Override
        public void run() {
            mZoomHintView.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> mZoomHintView.setVisibility(View.GONE)).start();
        }
    };

    /** Show the YouTube-style zoom chip, re-arming the fade-out if a pinch fires again mid-show. */
    private void showZoomHint(int textRes) {
        if (mZoomHintView == null) {
            return;
        }
        mZoomHintView.removeCallbacks(mHideZoomHint);
        mZoomHintView.animate().cancel();
        mZoomHintView.setText(textRes);
        if (mZoomHintView.getVisibility() != View.VISIBLE) {
            mZoomHintView.setAlpha(0f);
            mZoomHintView.setVisibility(View.VISIBLE);
        }
        mZoomHintView.animate().alpha(1f).setDuration(120).start();
        mZoomHintView.postDelayed(mHideZoomHint, 900);
    }

    private void createPlayerObjects() {
        // NEWTUBE(media3): the initializer owns the mobile tuning that used to be scattered here
        // (ABR 5s up-switch, 1080p Auto ceiling, 50/75s buffer + TTFF start gate + 120s back-buffer);
        // the buffer numbers are baked in, so the old PlayerData.setVideoBufferType() juggling is gone.
        // Native track selection replaces RestoreTrackSelector + the custom renderers factory: media3
        // ABR under app-level constraints, decoder fallback instead of the codec blacklist.
        DefaultTrackSelector trackSelector = mPlayerInitializer.createTrackSelector();

        mExoPlayerController.setTrackSelector(trackSelector);

        // An engine restart (error fix, network flap) while backgrounded builds a FRESH selector
        // with video re-enabled; re-apply the audio-only drop so the restart doesn't resume video.
        if (mBackgroundAudioMode) {
            mExoPlayerController.setVideoTrackDisabled(true);
        }

        mPlayer = mPlayerInitializer.createPlayer(
                trackSelector, mExoPlayerController.getMediaSourceFactory().getBandwidthMeter());
        // NEWTUBE(diagnostics): media3's stock per-event logcat tap (tag EventLogger) - track/ABR
        // switches, renderer state, loadError. Debug builds only, kept permanently.
        // NOTE: stock EventLogger does NOT emit loadStarted/loadCompleted lines, so per-chunk
        // network timing is invisible with it alone - NetPathLoadListener (tag NetPath) fills
        // exactly that gap with one dense line per load event.
        if (BuildConfig.DEBUG) {
            mPlayer.addAnalyticsListener(new androidx.media3.exoplayer.util.EventLogger());
            mPlayer.addAnalyticsListener(new NetPathLoadListener(this));
        }
        mPlayer.setPlayWhenReady(true);

        // Bounded-tolerance seeking as the player-wide default (scrub release, position restore -
        // every plain seekTo); see MOBILE_SEEK_PARAMETERS for the measurements behind it.
        // Double-tap bursts override per-direction (setUserSeekDirection) and restore to this.
        mPlayer.setSeekParameters(MOBILE_SEEK_PARAMETERS);

        mExoPlayerController.setPlayer(mPlayer);
        mPlayerView.setPlayer(mPlayer);

        // Persistent surface: PlayerView owns no surface (surface_type="none"); hand the
        // session-long Surface to each new player instance. On the very first open the texture
        // may not exist yet - the SurfaceTextureListener attaches it on availability.
        if (mSessionSurface != null) {
            mPlayer.setVideoSurface(mSessionSurface);
        }
        // The stock black shutter only lifts on a "rendered first frame" event tied to
        // PlayerView-owned surfaces; with the external surface it would sit over the loading
        // still forever. The still + black video area do its job now.
        mPlayerView.setShutterBackgroundColor(Color.TRANSPARENT);

        // Wire the YouTube-style double-tap seek overlay to the live player.
        // NOTE: PerformListener.shouldForward() compiles to an abstract method (the Kotlin default
        // body lives in DefaultImpls, invisible to Java), so it must be implemented here. We use the
        // ExoPlayer-correct version: left third rewinds, right third forwards, middle is ignored.
        mYouTubeOverlay
                .performListener(new YouTubeOverlay.PerformListener() {
                    @Override
                    public void onAnimationStart() {
                        // FAST-SEEK: the overlay seeks the raw player during the animation; the
                        // directional keyframe-snap is installed per tap in shouldForward.
                        mYouTubeOverlay.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationEnd() {
                        endUserSeekBurst();
                        mYouTubeOverlay.setVisibility(View.GONE);
                    }

                    @Override
                    public Boolean shouldForward(Player player, DoubleTapPlayerView playerView, float posX) {
                        int state = player.getPlaybackState();
                        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                            return null;
                        }
                        if (player.getCurrentPosition() > 500 && posX < playerView.getPlayerWidth() * 0.35f) {
                            setUserSeekDirection(false);
                            return false;
                        }
                        if (posX > playerView.getPlayerWidth() * 0.65f) {
                            setUserSeekDirection(true);
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

        // NEWTUBE(perf): the 500ms progress loop only feeds the overlay time bar / labels, so it runs
        // ONLY while the controls are visible (started in showControlsInternal, stopped in
        // hideControls). Kick it here only if the controls are still up from open; otherwise it stays
        // idle until the user shows the controls. The buffering spinner is driven separately by
        // mUiPlayerListener, so it keeps working while the loop is idle.
        if (mControlsVisible) {
            startProgressUpdates();
        }
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
        mExoPlayerController.release();
        mPlayer = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsStopped = false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        PlayerTransitionBridge.LaunchSnapshot launch = PlayerTransitionBridge.take();

        mIsResumed = true;
        // In the foreground again: auto-PiP behaves normally from here on.
        mSuppressAutoPip = false;
        // The PiP exit ended in the fullscreen UI, so it was an expand, not a dismiss.
        mPipDismissPending = false;
        // Re-enable the video track BEFORE any texture reattach below, so the first frame comes
        // back promptly (true background audio-only mode dropped the whole video renderer).
        setBackgroundAudioMode(false);
        updatePipActions(); // re-arm the Android 12+ auto-enter flag cleared by the minimize hand-off

        // Back from the mini-player (expand tap, new video, notification, recents): the Browse
        // card displayed the session texture until Browse's onPause detached it (guaranteed to
        // run before this). Re-parent it into our content frame - the codec kept decoding into
        // it the whole time, so no surface change, no codec re-init, no frozen frames. The card
        // captured its last frame for us; it covers the 1-2 frames until the texture paints.
        boolean fromMini = MiniPlayerBridge.isActive();
        Rect miniBounds = fromMini ? MiniPlayerBridge.takeMiniBounds() : null;
        if (fromMini) {
            Bitmap handoff = MiniPlayerBridge.takeHandoffStill();
            if (handoff != null) {
                showHandoffStill(handoff);
            } else {
                mStillAwaitFrame = true; // minimize-time capture is showing; lift on first frame
            }
            reattachVideoTexture();
        }
        MiniPlayerBridge.deactivate();

        if (launch != null) {
            // A normal feed/search/channel tap: cover the video with the exact tapped thumbnail,
            // place it over the source rect, then grow it into the watch page while Browse remains
            // visible through the rest of our window.
            showHandoffStill(launch.frame);
            mStillAwaitFrame = false; // do not lift the source image before the morph completes
            if (fromMini) {
                // The old stream is still producing frames after its texture was re-parented.
                // Keep those frames behind the selected video's thumbnail until the new load wins.
                mStillAwaitReady = true;
            }
            startOpenMorph(launch.sourceBounds, 300);
        } else if (fromMini && mContainer != null) {
            // Plain mini-card expansion: exact reverse of minimize, from the card rectangle.
            overridePendingTransition(0, 0);
            mContainer.setVisibility(View.INVISIBLE);
            mContainer.post(() -> {
                if (miniBounds != null) {
                    computeMorphTarget(miniBounds);
                } else {
                    computeMorphTarget();
                }
                applyMorph(1f);
                mContainer.setVisibility(View.VISIBLE);
                mContainer.postOnAnimation(() -> animateMorph(0f, 240, this::resetMorph));
            });
        }

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

        // Foreground recovery: if a background startForeground was rejected (API 31+, see
        // MobilePlaybackService.onNotificationPosted), re-run the promotion now that we're visible.
        if (mPlaybackService != null) {
            mPlaybackService.ensureForeground();
        }

        applySystemBarsForOrientation(getResources().getConfiguration().orientation);
    }

    @Override
    protected void onPause() {
        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }

        mIsResumed = false;

        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        mIsStopped = true;

        // Going to the background right after leaving PiP mode, without the fullscreen UI ever
        // resuming: the user dismissed the PiP window. Close the video for real (see
        // mPipDismissPending) instead of falling through into background-audio mode.
        if (mPipDismissPending) {
            mPipDismissPending = false;
            finishFromPipDismiss();
            return;
        }

        // True background audio-only playback: the window is no longer visible AND we're neither in
        // system PiP nor the in-app Browse mini-player (both of which show live video), so the
        // service is keeping only the audio going. Drop the video renderer so VIDEO stops streaming
        // and decoding - onStop is not delivered while a PiP window stays visible, so reaching here
        // uninhibited means a real background (home without PiP, screen off, another screen on top).
        // Re-enabled at the top of onResume, before the texture reattach.
        if (mPlayer != null && !mIsInPip && !mSuppressAutoPip && !isFinishing()) {
            setBackgroundAudioMode(true);
        }

        // The minimize drag left the video morphed onto the mini-card rect. Reset it once
        // this window is no longer visible (here, not in minimizeByDrag - resetting while our
        // window still shows behind the task switch would flash the fullscreen player back).
        // The expand path immediately re-applies the morph in onResume, so this never fights it.
        if (mContainer != null && mMorphFraction != 0f) {
            resetMorph();
        }
    }

    /**
     * Enter/exit true background audio-only mode: enabled = drop the whole video renderer so VIDEO
     * neither downloads nor decodes while the service keeps only audio going; disabled = restore it.
     * PiP and the Browse mini-player both render live video, so they never enter this mode. The flag
     * is the single source of truth (createPlayerObjects re-applies it after an engine restart).
     */
    private void setBackgroundAudioMode(boolean enabled) {
        mBackgroundAudioMode = enabled;
        if (mExoPlayerController != null) {
            mExoPlayerController.setVideoTrackDisabled(enabled);
        }

        // The Activity-owned live-chat poll keeps hitting the network (~700 req/hr) even with the
        // video renderer dropped. Stop it going audio-only in the background; if the chat sheet
        // survived the stint (mChatObserver != null - the sheet fragment outlives onStop/onResume)
        // revive the stream on return so the user doesn't come back to a frozen panel. The
        // ChatController receiver path (mChatReceiver) owns its own stream, so never touch it here.
        if (enabled) {
            if (mLiveChatAction != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll stop (background audio)");
                }
                stopLiveChatStream();
            }
        } else if (mChatObserver != null && mChatReceiver == null
                && mLiveChatAction == null && mLiveChatKey != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                        "live-chat poll resume (foreground)");
            }
            startLiveChatStream();
        }
    }

    @Override
    protected void onDestroy() {
        cancelAutoHide();
        hideRelatedSkeleton(); // cancels the pulse animator + pending timeout

        // Casting: stop observing the session. The session itself (manager + foreground service)
        // deliberately outlives this activity - the phone is just a remote.
        if (mCastSessionManager != null) {
            mCastSessionManager.removeListener(mCastListener);
        }
        Utils.removeCallbacks(mCastProgressRunnable);

        if (mWatchRoot != null) {
            mWatchRoot.removeOnLayoutChangeListener(mWatchRootLayoutListener);
        }
        SystemPipBridge.detach(this);

        RxHelper.disposeActions(mLiveChatAction);

        // The only playback activity (singleInstance) is going away: no mini session can outlive it.
        MiniPlayerBridge.deactivate();

        // Fix situations when the engine wasn't properly destroyed (mirrors PlaybackFragment).
        destroyPlayerObjects();

        // The codec is gone (player released above): the session-long surface can die now.
        releaseSessionTexture();

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
    /** Set while minimizing into the in-app mini-player, so auto-PiP keeps its hands off. */
    private boolean mSuppressAutoPip;

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (mSuppressAutoPip) {
            // Backgrounding into the Browse mini-player, not leaving the app: no system PiP.
            logPip("leave-skip reason=mini-handoff");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mIsInPip || isFinishing()) {
            logPip("leave-skip reason=state");
            return;
        }
        if (!Helpers.isPictureInPictureSupported(this)) {
            logPip("leave-skip reason=unsupported");
            return;
        }
        // Only auto-enter PiP while actually playing (matches YouTube; avoids PiP on a paused pre-roll).
        if (mPlayer == null || !isPlaying()) {
            logPip("leave-skip reason=not-playing");
            return;
        }
        // Don't hijack navigation to one of our own screens (e.g. opening a dialog / channel).
        if (getViewManager() != null && getViewManager().isNewViewPending()) {
            logPip("leave-skip reason=internal-navigation");
            return;
        }

        logPip("leave-enter");
        enterPipMode();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // PiP entry delivers a config change right AFTER onPictureInPictureModeChanged(true) hid
        // the watch UI; reapplying the portrait layout here un-hid it again, squeezing the whole
        // watch page into the tiny PiP window. PiP owns its video-only layout; the exit branch of
        // onPictureInPictureModeChanged restores everything below.
        if (mIsInPip) {
            return;
        }

        refreshContentInsets();
        applyWatchLayoutForOrientation(newConfig.orientation);
        applySystemBarsForOrientation(newConfig.orientation);
        updateFullscreenIcon(newConfig.orientation);
        // The standing auto-enter params carry a sourceRectHint captured from the video box; after
        // a rotation that rect is stale (portrait box vs fullscreen), which degrades the
        // home-gesture shrink animation. Re-push with the post-rotation geometry.
        if (mVideoArea != null) {
            mVideoArea.post(this::updatePipActions);
        }
    }

    private void handleBack() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }

        finish();
    }

    /**
     * The user dismissed the system PiP window (X / swipe-away): close the video like YouTube does.
     * The player sits alone in its own (formerly pinned) task at this point, so
     * {@code finishAndRemoveTask()} kills exactly that task - the shared Browse task is untouched.
     * Bypasses the {@code MobileActivity.finish()} routing on purpose: its root-screen branches
     * (parent relaunch / task-to-back) are for screens the user is looking at, not for an invisible
     * dismissed player.
     */
    private void finishFromPipDismiss() {
        if (isFinishing() || isDestroyed()) {
            return; // the system already finished us (older PiP shell) - nothing to do
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "pip dismissed -> closing playback");
        }
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
        getViewManager().removeTop(this);
        finishAndRemoveTask();
    }

    /**
     * The player is the one mobile screen where landscape means TRUE immersive fullscreen, so the
     * {@code MobileActivity} override (always-standard bars) is re-specialized by orientation.
     */
    @Override
    protected void applyFullscreenModeIfNeeded() {
        applySystemBarsForOrientation(getResources().getConfiguration().orientation);
    }

    @Override
    protected boolean shouldInsetContentForSystemBars() {
        return getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Landscape = edge-to-edge immersive fullscreen; portrait = normal with the status bar back.
     * Actual rotation is handled by the system (manifest {@code configChanges} keeps the live
     * ExoPlayer instance across rotation); this only follows it.
     */
    /**
     * PLAYER LAYOUT POLISH + REACH FIX. Portrait: the decor fits the system windows, controls
     * anchor flush to the video box (no padding). Landscape/fullscreen: inset by the system bars
     * AND by the pillarbox strip of a 16:9 video, so the whole overlay - especially the
     * fullscreen-exit button in the bottom-right - aligns with the video content edges ("where
     * the black strips start", like YouTube) instead of the far screen corners.
     */
    private void applyControlsInsets() {
        if (mControlsRoot == null) {
            return;
        }
        int left = 0, top = 0, right = 0, bottom = 0;
        if (isLandscape()) {
            WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(mControlsRoot);
            Insets bars = rootInsets != null
                    ? rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()) : Insets.NONE;
            int width = mControlsRoot.getWidth() > 0
                    ? mControlsRoot.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int height = mControlsRoot.getHeight() > 0
                    ? mControlsRoot.getHeight() : getResources().getDisplayMetrics().heightPixels;
            int strip = Math.max(0, Math.round((width - height * 16f / 9f) / 2f));
            left = Math.max(bars.left, strip);
            right = Math.max(bars.right, strip);
            top = bars.top;
            bottom = bars.bottom;
        }
        if (mControlsRoot.getPaddingLeft() != left || mControlsRoot.getPaddingTop() != top
                || mControlsRoot.getPaddingRight() != right || mControlsRoot.getPaddingBottom() != bottom) {
            mControlsRoot.setPadding(left, top, right, bottom);
        }
    }

    private void applySystemBarsForOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            applyDisplayCutoutMode(true);
            // Immersive full-bleed video (PLAYER POLISH behaviour).
            Helpers.makeActivityFullscreen2(this);
        } else {
            applyDisplayCutoutMode(false);
            // Watch page: standard phone chrome (solid status bar, video box below it -
            // YouTube-style). Replaces the old transparent-status-bar-over-the-video design,
            // whose edge-to-edge flags survived the fullscreen round-trip and left the status
            // bar overlapping the video (and leaked into the other screens).
            applyMobileSystemBars();
            // Keep the safe status-bar inset, but paint it the same black as the video surface.
            // Otherwise the app's #0F0F0F window background reads as a stray top margin above
            // the #000000 player on cutout devices with a tall safe inset (notably Pixel). Modern
            // Android makes the status bar transparent for edge-to-edge apps, so its effective
            // color comes from the decor background rather than setStatusBarColor alone. The
            // minimize morph animates this drawable's alpha so it does not become an opaque black
            // wall between the shrinking player and the translucent window's live backdrop.
            Window window = getWindow();
            window.getDecorView().setBackgroundColor(Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setStatusBarContrastEnforced(false);
            }
            window.setStatusBarColor(Color.BLACK);
        }

        if (mControlsRoot != null) {
            ViewCompat.requestApplyInsets(mControlsRoot);
        }
        if (mWatchRoot != null) {
            ViewCompat.requestApplyInsets(mWatchRoot);
        }
    }

    /**
     * Hiding the bars does not by itself let a window use the camera-cutout strip. Without this,
     * Android inset the whole landscape decor by the Pixel's 173px cutout and the 16:9 frame was
     * visibly shifted right by 86.5px. ALWAYS is the modern full-bleed mode; Android 9/10 use the
     * closest available SHORT_EDGES behavior. Portrait returns to the platform default.
     */
    private void applyDisplayCutoutMode(boolean fullscreen) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        int desired;
        if (!fullscreen) {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            desired = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        if (attributes.layoutInDisplayCutoutMode != desired) {
            attributes.layoutInDisplayCutoutMode = desired;
            getWindow().setAttributes(attributes);
        }
    }

    private void applyPortraitVideoHeight(int width) {
        if (mVideoArea == null || width <= 0) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
        int height = Math.round(width * 9f / 16f);
        if (lp.height != height || lp.weight != 0) {
            lp.height = height;
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ---------------------------------------------------------------------------------
    // Picture-in-Picture
    // ---------------------------------------------------------------------------------

    /** Enter PiP: shrink the video into a floating window that keeps playing, with a play/pause action. */
    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !Helpers.isPictureInPictureSupported(this)
                || mIsInPip) {
            logPip("enter-skip");
            return;
        }

        logPip("enter-request");
        // Strip the window down to video BEFORE handing it to the system: the shrink animation
        // captures the live window content, and waiting for onPictureInPictureModeChanged(true)
        // to hide things meant the whole watch page + controls stayed visible squeezed inside the
        // shrinking PiP window for its first ~300 ms (the "enter-animation flash").
        applyPipVideoOnlyLayout();

        boolean entered = false;
        try {
            entered = enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            // Device reported PiP support but refused (e.g. OEM restriction) - stay full-screen.
            logPip("enter-exception error=" + e.getClass().getSimpleName()
                    + ':' + com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(
                            e.getMessage(), 100));
        }
        if (!entered) {
            // Refused: undo the pre-stripped layout so the watch page comes back.
            int orientation = getResources().getConfiguration().orientation;
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            showControlsInternal(false);
            logPip("enter-refused-restored");
        } else {
            logPip("enter-accepted");
        }
    }

    /** Video-only window: what PiP shows. Idempotent; onPictureInPictureModeChanged exit undoes it. */
    private void applyPipVideoOnlyLayout() {
        cancelAutoHide();
        hideControls();
        // Defensive surface repair: a task/mini-player hand-off can leave the Activity-owned
        // TextureView detached for a frame. PiP must never snapshot the watch UI with no video
        // consumer. Do not steal a texture that is legitimately owned by an active mini card.
        if (!MiniPlayerBridge.isActive()) {
            reattachVideoTexture();
        }
        if (mVideoTexture != null) {
            mVideoTexture.setVisibility(View.VISIBLE);
        }
        if (mControlsRoot != null) {
            mControlsRoot.setVisibility(View.GONE);
        }
        if (mWatchScroll != null) {
            mWatchScroll.setVisibility(View.GONE);
        }
        if (mVideoArea != null) {
            mVideoArea.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mVideoArea.getLayoutParams();
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            lp.weight = 0;
            mVideoArea.setLayoutParams(lp);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();

        builder.setAspectRatio(getVideoAspectRatio());

        // Smooth expand/collapse animation anchored on the current video box. Skip while already
        // pinned: the video area's global rect is then in PiP-window coordinates (observed pushing
        // an off-screen hint mid-PiP), and the entry hint the system captured stays valid anyway.
        if (mVideoArea != null && !mIsInPip) {
            Rect sourceRect = new Rect();
            mVideoArea.getGlobalVisibleRect(sourceRect);
            if (!sourceRect.isEmpty()) {
                builder.setSourceRectHint(sourceRect);
            }
        }

        builder.setActions(java.util.Collections.singletonList(buildPlayPauseAction()));

        // Android 12+ gesture navigation does NOT deliver onUserLeaveHint in time for the home
        // gesture, so the manual enterPipMode() path never fires there (observed on the emulator:
        // KEYCODE_HOME entered PiP, the swipe-home gesture didn't). The modern mechanism - and the
        // one the official YouTube app uses for its seamless shrink-into-PiP - is auto-enter: the
        // params carry a standing "PiP me when the user leaves" flag, kept in sync with the play
        // state by updatePipActions() so a paused/ended video doesn't PiP (matches YouTube).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldAutoEnterPip());
        }

        return builder.build();
    }

    /**
     * Auto-enter PiP is armed only while something is actually playing (or about to resume after a
     * rebuffer: playWhenReady covers both, so a home-press during buffering still PiPs, like
     * YouTube) and we're not mid-hand-off to the in-app mini-player.
     *
     * <p>{@link #mIsResumed} is what keeps auto-enter tied to a real user departure. The standing
     * flag is refreshed whenever the play state changes, so without it a video that reaches
     * playWhenReady while this Activity sits in the background arms auto-enter from the background
     * - and the system, seeing an already-departed activity, drops it straight into PiP. Observed on
     * a Pixel 9 as: open a video by intent while a PiP session is up, and ~7s later (the moment the
     * new video became ready) the freshly expanded player bounced back into a PiP window on its own,
     * which reads as "the video opened in a corner of the screen".</p>
     */
    private boolean shouldAutoEnterPip() {
        return !mSuppressAutoPip
                && mIsResumed
                && !isFinishing()
                && !mIsEnded
                && mExoPlayerController != null
                && mExoPlayerController.getPlayWhenReady();
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

    /**
     * Push fresh PiP params to the system. In PiP this updates the play/pause action icon; OUTSIDE
     * PiP it keeps the standing auto-enter flag + aspect ratio + source rect current, which is what
     * makes the Android 12+ home-gesture auto-PiP fire (the system reads these params at leave time
     * - they must already be set, there is no callback to set them in).
     */
    private void updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Helpers.isPictureInPictureSupported(this)) {
            return;
        }
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (Exception e) {
            logPip("params-error error=" + e.getClass().getSimpleName());
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
        logPip("mode-changed inPip=" + (isInPictureInPictureMode ? "y" : "n")
                + " stopped=" + (mIsStopped ? "y" : "n"));

        if (isInPictureInPictureMode) {
            mPipDismissPending = false;
            // A forced orientation must not survive into the pinned task - it wedges the window
            // there permanently (see mPrePipOrientation). Done here rather than in enterPipMode()
            // because the Android 12+ home-gesture auto-enter never goes through that method.
            releaseOrientationLockForPip();
            // Video only: hide the controls overlay and the watch-page content, fill with the
            // video. Usually already done (enterPipMode pre-applies it; the auto-enter home
            // gesture is the path that arrives here without it).
            applyPipVideoOnlyLayout();
            // The PiP window renders only video: an open chat sheet is invisible, so its poll is
            // pure waste (same rule as background audio in setBackgroundAudioMode).
            if (mLiveChatAction != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll stop (pip)");
                }
                stopLiveChatStream();
            }
            updatePipActions();
        } else {
            // Dismiss vs expand: an expand always ends with onResume (which clears the flag); a
            // dismiss ends with onStop. On the older PiP shell the dismissal onStop ran BEFORE this
            // callback, so if we're already stopped this exit can only be a dismissal - finish now.
            if (mIsStopped) {
                mPrePipOrientation = ORIENTATION_NONE; // dismissed; nothing left to restore onto
                finishFromPipDismiss();
                return;
            }
            mPipDismissPending = true;
            restoreOrientationLockAfterPip();

            // Mirror of the setBackgroundAudioMode(false) revive: the sheet fragment survives the
            // PiP stint, so bring its stream back when the full watch UI returns.
            if (mChatObserver != null && mChatReceiver == null
                    && mLiveChatAction == null && mLiveChatKey != null) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            "live-chat poll resume (pip exit)");
                }
                startLiveChatStream();
            }
            // Restore the normal layout and controls. Explicitly showing them also resets the
            // GONE/alpha state left by PiP; on some task-reparent exits the first PlayerView tap
            // is swallowed by the system transition, otherwise leaving the restored player with
            // no apparent controls.
            int orientation = newConfig != null ? newConfig.orientation
                    : getResources().getConfiguration().orientation;
            logPip("exit-layout newConfig=" + (newConfig != null ? newConfig.orientation : -1)
                    + " resources=" + getResources().getConfiguration().orientation
                    + " chosen=" + orientation);
            applyWatchLayoutForOrientation(orientation);
            applySystemBarsForOrientation(orientation);
            updatePlayPauseIcon();
            if (mControlsRoot != null) {
                showControlsInternal(false);
            }
        }
    }

    /**
     * Drop any forced orientation for the duration of a PiP stint. A PiP window is sized by its
     * aspect ratio, never by the activity's orientation request, so nothing is lost while pinned -
     * but leaving the request in place wedges the task in {@code mode=pinned} (see
     * {@link #mPrePipOrientation}).
     */
    private void releaseOrientationLockForPip() {
        int requested = getRequestedOrientation();
        if (requested == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            mPrePipOrientation = ORIENTATION_NONE;
            return;
        }

        mPrePipOrientation = requested;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        logPip("orientation-released requested=" + requested);
    }

    /**
     * Put the pre-PiP orientation back once the watch UI returns, so expanding a video that was
     * left in fullscreen lands back in fullscreen. Deliberately posted: re-asserting the lock while
     * the PiP-to-fullscreen transition is still running makes the window rotate mid-animation.
     */
    private void restoreOrientationLockAfterPip() {
        if (mPrePipOrientation == ORIENTATION_NONE) {
            return;
        }

        final int restore = mPrePipOrientation;
        mPrePipOrientation = ORIENTATION_NONE;

        if (mVideoArea == null) {
            setRequestedOrientation(restore);
            return;
        }
        mVideoArea.post(() -> {
            // A second PiP entry (or a finish) may have overtaken this post.
            if (mIsInPip || isFinishing()) {
                return;
            }
            setRequestedOrientation(restore);
            logPip("orientation-restored requested=" + restore);
        });
    }

    /** Sparse, credential-free PiP/surface snapshot for OEM/system transition bug reports. */
    private void logPip(String event) {
        Video video = getVideo();
        boolean textureAttached = mVideoTexture != null && mVideoTexture.getParent() != null;
        boolean textureAvailable = mVideoTexture != null && mVideoTexture.isAvailable();
        boolean surfaceValid = mSessionSurface != null && mSessionSurface.isValid();
        int playbackState = mPlayer != null ? mPlayer.getPlaybackState() : -1;
        boolean playWhenReady = mExoPlayerController != null
                && mExoPlayerController.getPlayWhenReady();
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                        + " pip " + event
                        + " video=" + (video != null ? video.videoId : "?")
                        + " state=" + playbackState
                        + " pwr=" + (playWhenReady ? "y" : "n")
                        + " ended=" + (mIsEnded ? "y" : "n")
                        + " texture=" + (textureAttached ? "attached" : "detached")
                        + '/' + (textureAvailable ? "available" : "unavailable")
                        + " surface=" + (surfaceValid ? "valid" : "invalid")
                        + " mini=" + (MiniPlayerBridge.isActive() ? "y" : "n"));
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
        // REORDER_TO_FRONT: the player may sit BELOW Browse in the shared task (mini-player);
        // a notification tap must surface the existing instance, not stack a duplicate.
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, intent, piFlags);
    }

    // ---------------------------------------------------------------------------------
    // Casting (Route B scaffolding, CASTING.md): picker entry point + "Playing on TV" panel.
    //
    // The integration is deliberately tiny: the CastSessionManager singleton owns the session
    // (it outlives this activity - the phone is a remote); this activity only (1) opens the
    // picker, (2) pauses local playback while a session is active (never tears it down),
    // (3) mirrors CastEvents into the self-contained overlay panel, and (4) routes newly
    // selected videos to the TV (see the hooks in setVideo/handleUiStateChange).
    // ---------------------------------------------------------------------------------

    private void openCastPicker() {
        cancelAutoHide();
        // Permission gate + picker open live in CastPickerLauncher (shared with Browse);
        // presentation stays ours - showPlayerSheet is the immersive-safe presenter.
        CastPickerLauncher.open(this, this::showPlayerSheet);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        CastPickerLauncher.handlePermissionResult(this, requestCode, this::showPlayerSheet);
    }

    /**
     * Hardware volume keys drive the TV while casting (official-app behavior); everything else -
     * including volume keys with no session - falls through to normal dispatch.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (CastVolumeKeys.onDispatchKeyEvent(this, event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupCastOverlay() {
        if (mCastOverlay == null) {
            return;
        }

        if (mCastPlayPause != null) {
            mCastPlayPause.setOnClickListener(v -> {
                if (mCastSessionManager != null) {
                    if (mCastSessionManager.isPlayingOnTv()) {
                        mCastSessionManager.pause();
                    } else {
                        mCastSessionManager.play();
                    }
                    updateCastOverlay();
                }
            });
        }

        View disconnect = findViewById(R.id.mobile_cast_disconnect);
        if (disconnect != null) {
            disconnect.setOnClickListener(v -> {
                if (mCastSessionManager != null) {
                    mCastSessionManager.disconnect(); // onCastSessionEnded resumes local playback
                }
            });
        }

        View options = findViewById(R.id.mobile_cast_options);
        if (options != null) {
            // These are playback capabilities, not generic device settings: Direct gets a real
            // quality cap; Lounge gets receiver subtitles and an honest TV-remote quality row.
            options.setOnClickListener(v -> showCastPlaybackOptions());
        }

        if (mCastSeekBar != null) {
            // Seconds-granularity bar (int progress can't overflow on any real duration).
            mCastSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mCastPosition != null) {
                        mCastPosition.setText(formatTime(progress * 1_000L));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mCastScrubbing = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mCastScrubbing = false;
                    if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
                        mCastSessionManager.seekTo(seekBar.getProgress() * 1_000L);
                    }
                }
            });
        }
    }

    private final CastSessionManager.Listener mCastListener = new CastSessionManager.Listener() {
        @Override
        public void onCastSessionStarted(CastTarget target) {
            // Hand playback to the TV: pause the LOCAL player (keep it loaded so disconnect can
            // resume seamlessly), send the current video at the current position, show the panel.
            long resumeMs = Math.max(getPositionMs(), 0);
            if (mPlayer != null) {
                mPlayer.setPlayWhenReady(false);
            }
            Video video = getVideo();
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            if (video != null && video.videoId != null) {
                mCastSessionManager.loadVideo(video.videoId, resumeMs);
            }
            showCastOverlay();
            updateCastIconTint();
        }

        @Override
        public void onCastSessionState(String videoId, long positionMs, long durationMs, boolean playing) {
            updateCastOverlay();
        }

        @Override
        public void onCastSessionEnded(String reason) {
            // Resume locally where the TV left off. Listeners fire before the manager resets its
            // state, so the last cast position is still readable here.
            long castPositionMs = mCastSessionManager != null ? mCastSessionManager.getPositionMs() : -1;
            hideCastOverlay();
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            updateCastIconTint();
            if (castPositionMs > 0) {
                setPositionMs(castPositionMs);
            }
            setPlayWhenReady(true);
        }
    };

    /**
     * Connected-state affordance on the top-bar cast icon: theme accent while a session is live,
     * stock white otherwise (matches the official app's colored connected icon). Same accent as
     * the theme's {@code colorAccent}/dialog headers - the app's "active accent", distinct from
     * the playback-red used by like/progress states.
     */
    private void updateCastIconTint() {
        if (mCastButton == null) {
            return;
        }
        if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
            mCastButton.setColorFilter(getColorInt(R.color.mobile_color_accent));
        } else {
            mCastButton.clearColorFilter();
        }
    }

    private void showCastOverlay() {
        if (mCastOverlay == null) {
            return;
        }
        CastTarget target = mCastSessionManager != null ? mCastSessionManager.getTarget() : null;
        if (mCastOverlayTitle != null) {
            mCastOverlayTitle.setText(getString(R.string.mobile_cast_playing_on,
                    target != null ? target.getName() : ""));
        }
        mCastOverlay.setVisibility(View.VISIBLE);
        hideControls();
        updateCastOverlay();
        // 1s remote ticker: CastEvents only arrive on changes; the position interpolates between
        // them (CastSessionManager.getPositionMs) so the bar moves like a normal player's.
        Utils.removeCallbacks(mCastProgressRunnable);
        Utils.postDelayed(mCastProgressRunnable, 1_000);
    }

    private void hideCastOverlay() {
        Utils.removeCallbacks(mCastProgressRunnable);
        if (mCastOverlay != null) {
            mCastOverlay.setVisibility(View.GONE);
        }
    }

    private void updateCastOverlay() {
        if (mCastOverlay == null || mCastOverlay.getVisibility() != View.VISIBLE
                || mCastSessionManager == null) {
            return;
        }

        long positionMs = Math.max(mCastSessionManager.getPositionMs(), 0);
        long durationMs = Math.max(mCastSessionManager.getDurationMs(), 0);
        Video currentVideo = getVideo();
        boolean isLive = currentVideo != null && currentVideo.isLive
                && Helpers.equals(currentVideo.videoId, mCastSessionManager.getVideoId());

        if (mCastPlayPause != null) {
            boolean playing = mCastSessionManager.isPlayingOnTv();
            mCastPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
            mCastPlayPause.setContentDescription(
                    getString(playing ? R.string.mobile_player_pause : R.string.mobile_player_play));
        }
        // Lounge reports long-running livestream position/duration as timestamps from the stream's
        // original start (for example 1121:36:52), not a useful DVR window. Present the semantic
        // state instead and disable seeking; VOD keeps the normal elapsed/total timeline.
        if (mCastLiveChip != null) {
            mCastLiveChip.setVisibility(isLive ? View.VISIBLE : View.GONE);
        }
        if (mCastTimeline != null) {
            mCastTimeline.setVisibility(isLive ? View.GONE : View.VISIBLE);
        }
        if (isLive) {
            mCastScrubbing = false;
            return;
        }
        if (mCastDuration != null) {
            mCastDuration.setText(formatTime(durationMs));
        }
        if (mCastSeekBar != null) {
            mCastSeekBar.setMax((int) (durationMs / 1_000));
            if (!mCastScrubbing) {
                mCastSeekBar.setProgress((int) (positionMs / 1_000));
            }
        }
        if (mCastPosition != null && !mCastScrubbing) {
            mCastPosition.setText(formatTime(positionMs));
        }
    }

    private final Runnable mCastProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCastOverlay != null && mCastOverlay.getVisibility() == View.VISIBLE) {
                updateCastOverlay();
                Utils.postDelayed(this, 1_000);
            }
        }
    };

    /**
     * Casting has two intentionally different capability sets. Put the real controls in one
     * obvious sheet instead of sending users back through the device picker:
     * Direct = phone-side adaptive quality cap; Lounge = receiver-side subtitles, with quality
     * honestly delegated to the TV player UI.
     */
    private void showCastPlaybackOptions() {
        if (mCastSessionManager == null || !mCastSessionManager.isConnected()) {
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = createSheetContent();
        addCastSheetHeader(content, R.string.mobile_cast_controls_title,
                mCastSessionManager.isDirectRoute()
                        ? R.string.mobile_cast_direct_summary : R.string.mobile_cast_app_summary);

        if (mCastSessionManager.isDirectRoute()) {
            addMenuRow(content, sheet, R.drawable.ic_player_quality,
                    R.string.mobile_player_quality, currentDirectCastQualityLabel(), true,
                    this::showDirectCastQualitySheet);
            addMenuRow(content, sheet, R.drawable.ic_player_cc,
                    R.string.mobile_player_subtitles,
                    getString(R.string.mobile_cast_subtitles_need_tv_app), true,
                    this::confirmSwitchDirectCastForSubtitles);
        } else {
            addMenuRow(content, sheet, R.drawable.ic_player_quality,
                    R.string.mobile_player_quality,
                    getString(R.string.mobile_cast_quality_tv_remote), false,
                    () -> showCastSnackbar(R.string.mobile_cast_quality_receiver_help));
            addMenuRow(content, sheet, R.drawable.ic_player_cc,
                    R.string.mobile_player_subtitles, currentReceiverCaptionsLabel(), true,
                    this::showReceiverCaptionsSheet);
        }

        sheet.setContentView(content);
        showPlayerSheet(sheet);
    }

    private void addCastSheetHeader(LinearLayout content, int titleRes, int summaryRes) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(4), dp(20), dp(4));
        content.addView(title);

        TextView summary = new TextView(this);
        summary.setText(summaryRes);
        summary.setTextColor(getColorInt(R.color.mobile_color_on_surface_secondary));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summary.setPadding(dp(20), 0, dp(20), dp(10));
        content.addView(summary);
    }

    private String currentDirectCastQualityLabel() {
        int height = mCastSessionManager != null
                ? mCastSessionManager.getDirectQualityHeight() : 0;
        return height > 0
                ? getString(R.string.mobile_cast_quality_cap, height)
                : getString(R.string.mobile_cast_quality_auto_1080);
    }

    /** Direct Cast keeps every compatible rung up to this ceiling, preserving adaptive fallback. */
    private void showDirectCastQualitySheet() {
        if (mCastSessionManager == null || !mCastSessionManager.isDirectRoute()) {
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_quality, null);
        dialog.setContentView(content);
        LinearLayout qualityList = content.findViewById(R.id.quality_sheet_quality_list);
        content.findViewById(R.id.quality_sheet_divider).setVisibility(View.GONE);
        content.findViewById(R.id.quality_sheet_audio_title).setVisibility(View.GONE);
        content.findViewById(R.id.quality_sheet_audio_list).setVisibility(View.GONE);

        int selectedHeight = mCastSessionManager.getDirectQualityHeight();
        addQualityRow(qualityList, getString(R.string.mobile_cast_quality_auto_1080),
                selectedHeight == 0, () -> {
                    if (mCastSessionManager != null) {
                        mCastSessionManager.setDirectQualityHeight(0);
                    }
                    dialog.dismiss();
                });

        java.util.TreeSet<Integer> heights = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        List<FormatItem> formats = getVideoFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (item != null && item.getHeight() > 0
                        && item.getHeight() <= com.newtube.mobile.casting.proxy.MpdRewriter.MAX_VIDEO_HEIGHT) {
                    heights.add(item.getHeight());
                }
            }
        }
        for (int height : heights) {
            addQualityRow(qualityList,
                    getString(R.string.mobile_cast_quality_cap, height),
                    selectedHeight == height, () -> {
                        if (mCastSessionManager != null) {
                            mCastSessionManager.setDirectQualityHeight(height);
                        }
                        dialog.dismiss();
                    });
        }
        showPlayerSheet(dialog);
    }

    private String currentReceiverCaptionsLabel() {
        return mCastSubtitleLabel != null
                ? mCastSubtitleLabel : getString(R.string.mobile_menu_off);
    }

    /** Actual Lounge setSubtitlesTrack commands, backed by the current video's loaded track list. */
    private void showReceiverCaptionsSheet() {
        if (mCastSessionManager == null || mCastSessionManager.isDirectRoute()) {
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_captions, null);
        dialog.setContentView(content);
        content.findViewById(R.id.captions_sheet_style_divider).setVisibility(View.GONE);
        content.findViewById(R.id.captions_sheet_style).setVisibility(View.GONE);
        LinearLayout trackList = content.findViewById(R.id.captions_sheet_track_list);

        addQualityRow(trackList, getString(R.string.mobile_captions_off),
                mCastSubtitleVssId == null, () -> {
                    applyReceiverCaption(null);
                    dialog.dismiss();
                });

        List<FormatItem> tracks = new ArrayList<>();
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (isCaptionTrack(item)) {
                    tracks.add(item);
                }
            }
        }
        moveLastUsedCaptionsFirst(tracks);
        for (FormatItem item : tracks) {
            String vssId = item.getFormatId();
            addQualityRow(trackList, captionLabel(item),
                    Helpers.equals(vssId, mCastSubtitleVssId), () -> {
                        applyReceiverCaption(item);
                        dialog.dismiss();
                    });
        }
        if (tracks.isEmpty()) {
            content.findViewById(R.id.captions_sheet_empty).setVisibility(View.VISIBLE);
        }
        showPlayerSheet(dialog);
    }

    private void applyReceiverCaption(@Nullable FormatItem item) {
        String vssId = item != null ? item.getFormatId() : null;
        String languageCode = item != null
                ? castCaptionLanguageCode(item.getFormatId(), item.getLanguage()) : null;
        if (mCastSessionManager == null
                || !mCastSessionManager.setReceiverSubtitle(vssId, languageCode)) {
            showCastSnackbar(R.string.mobile_cast_subtitles_failed);
            return;
        }
        mCastSubtitleVssId = vssId;
        mCastSubtitleLabel = item != null ? captionLabel(item) : null;
        showCastSnackbar(item != null
                ? R.string.mobile_cast_subtitles_sent : R.string.mobile_captions_off_toast);
    }

    /** Extract BCP-47 from YouTube vss ids (.en / a.en); keep a code-shaped fallback only. */
    @Nullable
    static String castCaptionLanguageCode(@Nullable String vssId, @Nullable String fallback) {
        if (vssId != null && !vssId.isEmpty()) {
            int dot = vssId.lastIndexOf('.');
            String candidate = dot >= 0 && dot + 1 < vssId.length()
                    ? vssId.substring(dot + 1) : vssId;
            if (candidate.matches("(?i)[a-z]{2,3}([_-][a-z0-9]{2,8})*")) {
                return candidate.replace('_', '-');
            }
        }
        if (fallback != null && !fallback.isEmpty()
                && fallback.matches("(?i)[a-z]{2,3}([_-][a-z0-9]{2,8})*")) {
            return fallback.replace('_', '-');
        }
        return null;
    }

    private void confirmSwitchDirectCastForSubtitles() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_cast_switch_subtitles_title)
                .setMessage(R.string.mobile_cast_switch_subtitles_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_cast_switch_subtitles_positive,
                        (dialog, which) -> switchDirectCastToTvApp())
                .show();
    }

    private void switchDirectCastToTvApp() {
        if (mCastSessionManager == null || !mCastSessionManager.switchDirectSessionToTvApp()) {
            showCastSnackbar(R.string.mobile_cast_launch_failed);
        }
    }

    private void showCastSnackbar(int messageRes) {
        com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content), messageRes,
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
    }

    /**
     * setVideo hook: while a session is active a newly selected video (related tap, queue,
     * auto-advance) routes to the TV instead of playing locally. The local engine still loads it
     * paused underneath, so state/controllers stay consistent and disconnect resumes instantly.
     */
    private void maybeRouteVideoToCast(String videoId) {
        if (mCastSessionManager == null || !mCastSessionManager.isConnected() || videoId == null) {
            return;
        }
        if (!Helpers.equals(videoId, mCastSessionManager.getVideoId())) {
            mCastSubtitleVssId = null;
            mCastSubtitleLabel = null;
            mCastSessionManager.loadVideo(videoId, 0);
        }
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
        showCastOverlay(); // refresh the "Playing on <TV>" panel over the new video
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

        // NEWTUBE(perf): drive the 500ms progress loop only while controls are visible. startProgress
        // ticks once immediately (refresh on show) then reschedules every 500ms.
        startProgressUpdates();

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
        // NEWTUBE(perf): controls hidden -> stop the 500ms progress loop (nothing visible to update).
        stopProgressUpdates();
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

    /**
     * The mobile default seek resolution: snap to a known sync point when it is within 5s before /
     * 1s after the target, otherwise land at the window edge. Measured on the Norway repro video
     * (bnQI3v_MpOs, 1080p60 VP9):
     *  - EXACT everywhere froze the player in "buffering" ~5-7s per seek (decode from the previous
     *    keyframe up to the requested frame; sw-decode emulator numbers - shorter on hw decode but
     *    still a visible freeze), 13.5s after a rapid tap-around burst.
     *  - PREVIOUS_SYNC everywhere resumed in &lt;1s but could land up to 52s BEFORE the finger:
     *    ExoPlayer 2.10's seek adjustment sometimes consults a much coarser index than the ~5s
     *    container keyframes its own loader uses ("jumped back" feel).
     * With the bounded window, seeks resolved in ~0.9s and landed at most ~5s early - scrubbing
     * feels like YouTube's keyframe-aligned bar. 5s is ~2px on the portrait seekbar of a 30-min
     * video, well under scrub aim precision.
     */
    private static final SeekParameters MOBILE_SEEK_PARAMETERS =
            new SeekParameters(/* toleranceBeforeUs= */ 5_000_000, /* toleranceAfterUs= */ 1_000_000);

    /**
     * FAST-SEEK, scoped to the double-tap gesture only. Each +10s/-10s tap seeks with a
     * DIRECTIONAL keyframe snap - NEXT_SYNC going forward, PREVIOUS_SYNC going back - so a tap
     * always makes progress in the tapped direction at keyframe speed. The earlier CLOSEST_SYNC
     * could snap BACKWARD past a forward target: on sparse-keyframe streams repeated forward taps
     * kept landing on the same keyframe (video "stuck"/jumping - user-reported on device).
     *
     * <p>Restoring the default must NOT rely on the overlay's onAnimationEnd alone: shouldForward
     * installs the directional parameters on every double-tap detection, and a skipped animation
     * cycle used to leak PREVIOUS_SYNC as the permanent default (observed: later scrubs landing
     * ~20s early). Three layers now restore MOBILE_SEEK_PARAMETERS: onAnimationEnd (normal path),
     * a watchdog re-armed on every directional tap (covers missed/skipped animation ends), and
     * onScrubStart (a scrub definitively ends any double-tap burst).
     * setSeekParameters/seekTo post FIFO to the player's internal handler, so per-tap set -> seek
     * -> restore brackets exactly the burst's own seeks.</p>
     */
    private static final long SEEK_BURST_WATCHDOG_MS = 1_500;

    private final Runnable mSeekBurstWatchdog = this::endUserSeekBurst;

    /** Per-tap: snap to the next keyframe in the tapped direction. */
    private void setUserSeekDirection(boolean forward) {
        if (mPlayer != null) {
            mPlayer.setSeekParameters(forward ? SeekParameters.NEXT_SYNC : SeekParameters.PREVIOUS_SYNC);
            Utils.removeCallbacks(mSeekBurstWatchdog);
            Utils.postDelayed(mSeekBurstWatchdog, SEEK_BURST_WATCHDOG_MS);
        }
    }

    private void endUserSeekBurst() {
        Utils.removeCallbacks(mSeekBurstWatchdog);
        if (mPlayer != null) {
            mPlayer.setSeekParameters(MOBILE_SEEK_PARAMETERS);
        }
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
    // Gear menu: every player option beyond the overlay's YouTube-style trio (cast/CC/gear)
    // + transport + fullscreen.
    //
    // The everyday actions (Quality / Speed / PiP) head the sheet; the long tail follows. Each
    // row dispatches an R.id.action_* through PlaybackPresenter (same vocabulary the TV
    // VideoPlayerGlue used) so the reused PlayerUIController does the real work: dialog-opening
    // actions (repeat/zoom/playlist/queue) show their AppDialog via the touch
    // MobileAppDialogActivity; simple toggles (stats/screen-off) flip and are reflected here.
    // Actions with no mobile meaning (AFR) are omitted; "Rotate lock" is a native
    // screen-orientation lock rather than the TV video-frame rotate.
    // ---------------------------------------------------------------------------------

    private void openPlayerMenu() {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = createSheetContent();

        // Mirrors the official app's gear sheet: no title, a handful of everyday rows, icon +
        // current value on every everyday action; the long tail nests behind "More". Quality opens
        // the simple YouTube-style picker (Auto + resolutions, plus audio language when dubbed) -
        // the exhaustive TV HQ dialog stays reachable for power users deeper in that sheet.
        addMenuRow(content, sheet, R.drawable.ic_player_quality, R.string.mobile_player_quality,
                currentQualityLabel(), true, this::showQualitySheet);
        // Captions: the native captions sheet (same target as long-pressing the overlay CC button).
        addMenuRow(content, sheet, R.drawable.ic_player_cc, R.string.mobile_player_subtitles,
                currentCaptionsLabel(), true, this::showCaptionsSheet);
        // Speed: the native preset sheet; the exhaustive TV dialog nests behind its "More speeds".
        addMenuRow(content, sheet, R.drawable.ic_player_speed, R.string.mobile_player_speed,
                currentSpeedLabel(), true, this::showSpeedSheet);
        if (Helpers.isPictureInPictureSupported(this)) {
            addMenuRow(content, sheet, R.drawable.ic_player_pip, R.string.mobile_player_pip,
                    null, false, this::enterPipMode);
        }
        // Rotate lock (native screen-orientation lock) - the phone-holdable equivalent of the
        // official sheet's "Lock screen" slot.
        addMenuRow(content, sheet, R.drawable.ic_player_lock, R.string.mobile_menu_rotate_lock,
                stateLabel(mOrientationLocked), false, this::toggleRotateLock);
        addMenuRow(content, sheet, R.drawable.ic_mobile_settings, R.string.mobile_menu_more,
                null, true, this::openPlayerMoreMenu);

        sheet.setContentView(content);
        sheet.setOnDismissListener(d -> armAutoHide());
        showPlayerSheet(sheet);
    }

    /** The gear sheet's "More" level: the long tail of SmartTube player actions. */
    private void openPlayerMoreMenu() {
        if (mPresenter == null) {
            return;
        }

        cancelAutoHide();

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = createSheetContent();

        boolean shuffleOn = PlayerData.instance(this).getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE;
        boolean statsOn = getButtonState(R.id.action_video_stats) == BUTTON_ON;

        // Repeat mode -> playback-mode dialog (long-click path always opens the picker; the plain
        // click just cycles). The dialog includes Shuffle among its radio options too.
        addMenuRow(content, sheet, R.drawable.ic_player_repeat, R.string.mobile_menu_repeat,
                null, true, () -> openPlayerOption(R.id.action_repeat, true));
        // Dedicated Shuffle toggle (SHUFFLE <-> ALL) for quick access.
        addMenuRow(content, sheet, R.drawable.ic_player_shuffle, R.string.mobile_menu_shuffle,
                stateLabel(shuffleOn), false, this::toggleShuffleMode);
        // Video zoom / aspect ratio / rotate dialog.
        addMenuRow(content, sheet, R.drawable.ic_player_zoom, R.string.mobile_menu_zoom,
                null, true, () -> openPlayerOption(R.id.action_video_zoom, false));
        // Play as audio / background mode (PiP-on-home etc.).
        addMenuRow(content, sheet, R.drawable.ic_player_background, R.string.mobile_menu_background,
                null, true, this::openBackgroundModeDialog);
        // (No screen-off/dimming row: the TV screensaver doesn't exist on mobile - power button +
        // background playback cover that use case.)
        // Add to playlist.
        addMenuRow(content, sheet, R.drawable.ic_player_playlist_add, R.string.mobile_menu_playlist_add,
                null, true, () -> openPlayerOption(R.id.action_playlist_add, false));
        // Playback queue.
        addMenuRow(content, sheet, R.drawable.ic_player_queue, R.string.mobile_menu_queue,
                null, true, () -> openPlayerOption(R.id.action_playback_queue, false));
        // Stats for nerds (debug overlay) toggle.
        addMenuRow(content, sheet, R.drawable.ic_player_stats, R.string.mobile_menu_stats,
                stateLabel(statsOn), false, () -> openPlayerOption(R.id.action_video_stats, false));

        sheet.setContentView(content);
        sheet.setOnDismissListener(d -> armAutoHide());
        showPlayerSheet(sheet);
    }

    /** Shared scaffold for the gear sheets: rounded background + drag handle, no title. */
    private LinearLayout createSheetContent() {
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

        return content;
    }

    /**
     * Trailing value for the Quality row, official-app style: "Auto (720p)" while adaptive
     * (the rung actually playing right now), the pinned rung's label otherwise.
     */
    private String currentQualityLabel() {
        PlayerData playerData = PlayerData.instance(this);
        FormatItem tempOverride = playerData.getTempVideoFormat();
        FormatItem chosen = tempOverride != null ? tempOverride : playerData.getFormat(FormatItem.TYPE_VIDEO);

        if (!isAutoFormat(chosen) && chosen.getHeight() > 0) {
            return qualityLabel(chosen);
        }

        FormatItem playing = mExoPlayerController != null ? mExoPlayerController.getVideoFormat() : null;
        return playing != null && playing.getHeight() > 0
                ? getString(R.string.mobile_quality_auto_current, qualityLabel(playing))
                : getString(R.string.mobile_quality_auto_short);
    }

    /** Trailing value for the Speed row: "Normal", "1.5x", ... */
    private String currentSpeedLabel() {
        float speed = mExoPlayerController != null ? mExoPlayerController.getSpeed() : -1;
        return speedLabel(speed <= 0 ? 1f : speed);
    }

    /**
     * A bare BottomSheetDialog over the player misbehaves two ways: it opens half-collapsed at the
     * default auto peek height (tall content ends up cut off below the screen edge), and in the
     * immersive landscape player its focusable window re-summons the system bars, shifting the
     * sheet's layout so it lands partly off-screen. Every in-player sheet must open through here:
     * expanded and never collapsible, shown focus-less first with the player's system-UI state
     * mirrored onto its window (focus is restored right after, per the standard immersive-dialog
     * recipe) so the bars stay hidden.
     */
    private void showPlayerSheet(BottomSheetDialog dialog) {
        Window window = dialog.getWindow();
        boolean immersive = isLandscape();
        if (immersive && window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility());
        }

        dialog.setOnShowListener(d -> {
            View sheetView = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheetView != null) {
                // The frame's own white background would poke out around bg_mobile_sheet's corners.
                sheetView.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheetView);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (immersive && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        });

        dialog.show();
    }

    /**
     * One gear-sheet row, official-app anatomy: leading icon, label, then (optionally) the
     * current value in secondary color and a chevron when the row opens a sub-picker.
     */
    private void addMenuRow(LinearLayout container, BottomSheetDialog sheet, int iconRes,
                            int labelRes, String trailing, boolean chevron, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(resolveSelectableItemBackground());
        row.setPadding(dp(20), dp(14), dp(16), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColorInt(R.color.mobile_color_on_surface));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.setMarginEnd(dp(20));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

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
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.addView(state);
        }

        if (chevron) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_chevron_right);
            arrow.setColorFilter(getColorInt(R.color.mobile_color_on_surface_secondary));
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            arrowLp.setMarginStart(dp(4));
            arrow.setLayoutParams(arrowLp);
            row.addView(arrow);
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
            updateLiveChip(position, duration);
        }

        updatePlayPauseIcon();

        Utils.postDelayed(mProgressUpdateRunnable, PROGRESS_UPDATE_MS);
    }

    /**
     * Live streams: a red LIVE chip when watching at the edge, dimmed while rewound into the DVR
     * window (the seekbar stays scrubbable); tapping it jumps back to the edge. Non-live keeps
     * the plain position/duration pair.
     */
    private void updateLiveChip(long positionMs, long durationMs) {
        if (mLiveChip == null) {
            return;
        }

        boolean isLive = getVideo() != null && getVideo().isLive;
        mLiveChip.setVisibility(isLive ? View.VISIBLE : View.GONE);

        if (isLive) {
            boolean atEdge = durationMs - positionMs <= LIVE_EDGE_THRESHOLD_MS;
            mLiveChip.setAlpha(atEdge ? 1f : 0.55f);
        }
    }

    private void jumpToLiveEdge() {
        long durationMs = getDurationMs();

        if (mExoPlayerController == null || durationMs <= 0) {
            return;
        }

        mExoPlayerController.setPositionMs(Math.max(0, durationMs - LIVE_EDGE_OFFSET_MS));

        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    private String formatTime(long timeMs) {
        if (timeMs < 0) {
            timeMs = 0;
        }
        return Util.getStringForTime(mFormatBuilder, mFormatter, timeMs);
    }

    private final Player.Listener mUiPlayerListener = new Player.Listener() {
        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            // The old 2-arg onPlayerStateChanged callback split in two in media3; both re-enter
            // the same state handler so the icon/PiP/screen-on logic sees every combination.
            if (mPlayer != null) {
                handleUiStateChange(playWhenReady, mPlayer.getPlaybackState());
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            handleUiStateChange(mPlayer != null && mPlayer.getPlayWhenReady(), playbackState);
        }

        private void handleUiStateChange(boolean playWhenReady, int playbackState) {
            switch (playbackState) {
                case Player.STATE_BUFFERING:
                    showProgressBar(true);
                    break;
                case Player.STATE_READY:
                    showProgressBar(false);
                    mIsEnded = false;
                    // CASTING: the TV owns playback while a session is active - the local engine
                    // may load/buffer (so disconnect can resume instantly) but must never audibly
                    // play. Re-pause the moment any (re)load reaches READY.
                    if (mCastSessionManager != null && mCastSessionManager.isConnected()
                            && mPlayer != null && mPlayer.getPlayWhenReady()) {
                        mPlayer.setPlayWhenReady(false);
                    }
                    // A stream reached READY = the one-time session setup is complete (whether the
                    // background warmup or this very load did the work). Persists; kills the
                    // first-run hint for good.
                    SessionWarmup.markWarm(MobilePlaybackActivity.this);
                    // LOADING STILL: the NEW stream is ready - the very next rendered frame is the
                    // new video, so let onSurfaceTextureUpdated lift the thumbnail then.
                    if (mStillAwaitReady) {
                        mStillAwaitReady = false;
                        mStillAwaitFrame = true;
                    }
                    // NEWTUBE(mobile-ttff): first frame has rendered. Now (and only now) apply any
                    // watch-header metadata that arrived early, so the bind stays off the first-frame
                    // path. Runs on the UI thread (ExoPlayer callbacks post here).
                    if (!mFirstFrameReady) {
                        mFirstFrameReady = true;
                        if (mPendingMetadata != null) {
                            bindWatchMetadata(mPendingMetadata);
                        }
                    }
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

            // SCREEN-ON: hold the screen awake while actively playing/buffering (like the YouTube
            // app); paused or ended releases it so the system's own display timeout applies. This
            // replaces the TV ScreensaverManager dim, which is disabled on mobile.
            if (mPlayerView != null) {
                mPlayerView.setKeepScreenOn(playWhenReady
                        && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING));
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            // Never leave the loading still covering an error state.
            mStillAwaitReady = false;
            mStillAwaitFrame = false;
            hideVideoStill();
        }
    };

    // ---------------------------------------------------------------------------------
    // Persistent video surface + loading still
    //
    // The video decodes into ONE SurfaceTexture for the whole life of this activity. PlayerView
    // no longer owns a surface (surface_type="none"): a code-managed TextureView lives inside its
    // content frame (so aspect-ratio/zoom still apply) and hands its very first SurfaceTexture to
    // the player as a Surface the player never lets go of. Every hand-off - minimize to the
    // Browse mini card, expand back, background/return - only RE-PARENTS that texture between
    // TextureViews. The codec's output surface never changes, so the decoder is never released
    // and re-initialized: no more "audio keeps playing while the frames are stuck" (a codec
    // re-init must decode from the previous keyframe back to the position, which takes seconds
    // on some devices). This is the same technique the YouTube app uses.
    //
    // The "loading still" ImageView covers the texture in the two moments a stale frame would
    // show: a NEW video opening on this reused activity (thumbnail until the new stream's first
    // frame - see maybeShowLoadingStill) and the mini-player hand-offs (a captured frame bridges
    // the couple of frames until the re-parented texture paints).
    // ---------------------------------------------------------------------------------

    private TextureView mVideoTexture;
    private SurfaceTexture mSessionTexture;
    private Surface mSessionSurface;
    private ImageView mVideoStill;
    /** Loading-still state: waiting for the NEW stream's STATE_READY... */
    private boolean mStillAwaitReady;
    /** ...then for the next actually-rendered frame; only then the still lifts. */
    private boolean mStillAwaitFrame;
    private String mStillVideoId;

    /** Build the code-managed video texture + still inside the PlayerView's content frame. */
    private void setupVideoSurface() {
        ViewGroup contentFrame = mPlayerView.getContentFrame();

        mVideoTexture = new TextureView(this);
        mVideoTexture.setSurfaceTextureListener(mVideoTextureListener);
        contentFrame.addView(mVideoTexture, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mVideoStill = new ImageView(this);
        mVideoStill.setScaleType(ImageView.ScaleType.FIT_XY);
        mVideoStill.setBackgroundColor(Color.BLACK);
        mVideoStill.setVisibility(View.GONE);
        contentFrame.addView(mVideoStill, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final TextureView.SurfaceTextureListener mVideoTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            if (mSessionTexture == null) {
                // Very first availability: adopt this texture for the whole session.
                mSessionTexture = texture;
                mSessionSurface = new Surface(texture);
                if (mPlayer != null) {
                    mPlayer.setVideoSurface(mSessionSurface);
                }
            } else if (texture != mSessionTexture) {
                // The view re-created its texture (re-attach after mini / return from background):
                // swap the session texture back in. The codec kept decoding into it all along, so
                // the live stream shows within a frame or two. The fresh texture is discarded.
                mVideoTexture.setSurfaceTexture(mSessionTexture);
                texture.release();
            }
            logPip("surface-available size=" + width + 'x' + height
                    + " adopted=" + (texture == mSessionTexture ? "y" : "n"));
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            // NEVER let a view release the session texture - that would detach the codec's
            // surface and force the re-init this whole design exists to avoid. Disposable
            // (never-adopted) textures may be released normally.
            boolean release = texture != mSessionTexture;
            logPip("surface-destroyed release=" + (release ? "y" : "n"));
            return release;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            // A real frame just rendered behind the still: lift it.
            if (mStillAwaitFrame && !mStillAwaitReady) {
                mStillAwaitFrame = false;
                hideVideoStill();
            }
        }
    };

    /** Session-long video texture, handed to the Browse mini card (see MiniPlayerBridge). */
    SurfaceTexture getSessionTexture() {
        return mSessionTexture;
    }

    /** Detach the TextureView so the session texture is free for another view's GL consumer. */
    private void detachVideoTexture() {
        if (mVideoTexture != null && mVideoTexture.getParent() instanceof ViewGroup) {
            // NOTE: do NOT "swap a throwaway texture in" here to force an eager GL release.
            // TextureView#setSurfaceTexture releases the texture it currently holds, so that
            // swap destroys the session texture and the adopting mini card crashes with
            // "Cannot setSurfaceTexture to a released SurfaceTexture". The known cosmetic
            // cost of plain removeView is that the outgoing HWUI layer can keep the texture
            // GL-bound for a beat (observed 1.5-3s on Pixel), during which the adopting card
            // renders mis-transformed and then snaps - an open issue needing a different fix.
            ((ViewGroup) mVideoTexture.getParent()).removeView(mVideoTexture);
        }
    }

    /** Re-parent the (still decoding) session texture back into this player's content frame. */
    private void reattachVideoTexture() {
        if (mVideoTexture != null && mVideoTexture.getParent() == null) {
            ViewGroup contentFrame = mPlayerView.getContentFrame();
            contentFrame.addView(mVideoTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** New video on this reused view: thumbnail over the stale frame until the new first frame. */
    private void maybeShowLoadingStill(Video item) {
        if (item == null || item.videoId == null || mVideoStill == null
                || Helpers.equals(item.videoId, mStillVideoId)) {
            return;
        }
        mStillVideoId = item.videoId;
        mStillAwaitReady = true; // the OLD stream is still READY; wait for the new one
        mStillAwaitFrame = false;
        mVideoStill.animate().cancel();
        mVideoStill.setImageDrawable(null); // solid black until the thumbnail lands
        mVideoStill.setAlpha(1f);
        mVideoStill.setVisibility(View.VISIBLE);
        String thumb = item.getBackgroundUrl();
        if (thumb != null && !isFinishing() && !isDestroyed()) {
            Glide.with(this).load(thumb).into(mVideoStill);
        }
    }

    /** Mini hand-off: show a captured frame while the re-parented texture paints (1-2 frames). */
    private void showHandoffStill(Bitmap frame) {
        if (mVideoStill == null || frame == null) {
            return;
        }
        mStillAwaitReady = false;
        mStillAwaitFrame = true;
        mVideoStill.animate().cancel();
        mVideoStill.setImageBitmap(frame);
        mVideoStill.setAlpha(1f);
        mVideoStill.setVisibility(View.VISIBLE);
    }

    private void hideVideoStill() {
        if (mVideoStill == null || mVideoStill.getVisibility() != View.VISIBLE) {
            return;
        }
        mVideoStill.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            mVideoStill.setVisibility(View.GONE);
            mVideoStill.setAlpha(1f);
            mVideoStill.setImageDrawable(null);
        }).start();
    }

    private void releaseSessionTexture() {
        if (mSessionSurface != null) {
            mSessionSurface.release();
            mSessionSurface = null;
        }
        if (mSessionTexture != null) {
            // If the texture is still displayed by the Browse card (we died while minimized) the
            // card's own detach releases it again - a double release is tolerated natively.
            try {
                mSessionTexture.release();
            } catch (RuntimeException ignored) {
            }
            mSessionTexture = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Swipe-down-to-dismiss (PlayerContainerLayout.DragListener)
    // ---------------------------------------------------------------------------------

    /**
     * Swipe-down morph, YouTube-style: the video itself shrinks toward the exact spot where
     * Browse's floating mini-player card sits while the watch content fades away. The player
     * window is translucent, so every pixel uncovered by that movement reveals the already-live
     * screen underneath throughout the drag; there is no intermediate black window or route fade.
     * Geometry note: both activities fit system windows, so their coordinates line up and a 16:9
     * video scaled to the card's width lands exactly on the 16:9 card.
     */
    private float mMorphScaleX = 1f;
    private float mMorphScaleY = 1f;
    private float mMorphTx;
    private float mMorphTy;
    private float mMorphFraction;
    private ValueAnimator mMorphAnimator;
    private static final int MINI_CARD_WIDTH_DP = 180;
    private static final int MINI_CARD_HEIGHT_DP = 102;

    /** Compute the video transform (pivot 0,0) that maps the video area onto the mini card. */
    private void computeMorphTarget() {
        float density = getResources().getDisplayMetrics().density;
        float cardW = MINI_CARD_WIDTH_DP * density;
        float margin = 12 * density;
        // Card bottom offset comes from the DESTINATION host: Browse's card floats above its
        // 56dp bottom-nav row, Search/Channel overlay cards sit flush at the content bottom.
        // Both containers already exclude the gesture inset in portrait, so no inset term here.
        MiniPlayerBridge.MiniHost host = MiniPlayerBridge.getMiniHost();
        float bottomNav = host != null ? host.getMiniCardBottomOffsetPx() : 56 * density;

        Rect video = new Rect();
        video.set(0, 0, mVideoArea.getWidth(), mVideoArea.getHeight());
        mContainer.offsetDescendantRectToMyCoords(mVideoArea, video);

        float scale = video.width() > 0 ? cardW / video.width() : 0.44f;
        float cardH = video.height() * scale; // 16:9 video -> ~the card's 102dp
        float targetX = mContainer.getWidth() - margin - cardW;
        float targetY = mContainer.getHeight() - bottomNav - margin - cardH;

        mMorphScaleX = scale;
        mMorphScaleY = scale;
        // mVideoArea's translation is expressed in its parent's (unscaled) coordinates. With a
        // top-left pivot its rendered origin is layoutOrigin + translation, independent of scale.
        mMorphTx = targetX - video.left;
        mMorphTy = targetY - video.top;
    }

    /** Map the watch-page video box onto an arbitrary tapped thumbnail in screen coordinates. */
    private void computeMorphTarget(Rect sourceBounds) {
        Rect video = new Rect(0, 0, mVideoArea.getWidth(), mVideoArea.getHeight());
        mContainer.offsetDescendantRectToMyCoords(mVideoArea, video);

        int[] containerLocation = new int[2];
        mContainer.getLocationOnScreen(containerLocation);
        float sourceLeft = sourceBounds.left - containerLocation[0];
        float sourceTop = sourceBounds.top - containerLocation[1];

        mMorphScaleX = video.width() > 0
                ? (float) sourceBounds.width() / video.width() : 1f;
        mMorphScaleY = video.height() > 0
                ? (float) sourceBounds.height() / video.height() : mMorphScaleX;
        mMorphTx = sourceLeft - video.left;
        mMorphTy = sourceTop - video.top;
    }

    /** Start with the player hidden, reveal it exactly over the tapped card, then expand. */
    private void startOpenMorph(Rect sourceBounds, long durationMs) {
        if (mContainer == null || mVideoArea == null) {
            return;
        }
        overridePendingTransition(0, 0);
        mContainer.setVisibility(View.INVISIBLE);
        mContainer.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            computeMorphTarget(sourceBounds);
            applyMorph(1f);
            mContainer.setVisibility(View.VISIBLE);
            mContainer.postOnAnimation(() -> animateMorph(0f, durationMs, () -> {
                resetMorph();
                // The launch thumbnail may now yield to the next actual frame. If a new stream is
                // still loading, mStillAwaitReady keeps it up until STATE_READY first.
                if (mVideoStill != null && mVideoStill.getVisibility() == View.VISIBLE) {
                    mStillAwaitFrame = true;
                }
            }));
        });
    }

    /** Apply the morph at fraction f (0 = fullscreen player, 1 = sitting on the mini card). */
    private void applyMorph(float f) {
        mMorphFraction = f;
        mVideoArea.setPivotX(0f);
        mVideoArea.setPivotY(0f);
        float sx = 1f + (mMorphScaleX - 1f) * f;
        float sy = 1f + (mMorphScaleY - 1f) * f;
        mVideoArea.setScaleX(sx);
        mVideoArea.setScaleY(sy);
        mVideoArea.setTranslationX(mMorphTx * f);
        mVideoArea.setTranslationY(mMorphTy * f);
        // The content column is the next LinearLayout child and would otherwise be drawn over the
        // moving video. Any positive Z keeps the live TextureView visually on top during the morph.
        float density = getResources().getDisplayMetrics().density;
        mVideoArea.setTranslationZ(f > 0f ? 12f * density : 0f);

        // Remove labels/cards early so they do not ghost over Browse, then fade the solid watch
        // background more slowly. This reads as a black sheet becoming transparent while the live
        // video remains fully opaque above it.
        float contentAlpha = Math.max(0f, 1f - f * 5f);
        if (mWatchContent != null) {
            mWatchContent.setAlpha(contentAlpha);
        }
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            int backdropAlpha = Math.round(255f * (1f - f));
            mWatchScroll.getBackground().mutate().setAlpha(backdropAlpha);
        }
        setWindowBackdropAlpha(1f - f);
        if (mControlsRoot != null && mControlsRoot.getVisibility() == View.VISIBLE) {
            mControlsRoot.setAlpha(contentAlpha);
        }
    }

    /**
     * Fade the edge-to-edge window backdrop with the view morph. On current Android versions the
     * decor drawable also paints the manually inset status/navigation-bar bands, so leaving it
     * opaque would hide the Activity below even after all watch-page children became transparent.
     */
    private void setWindowBackdropAlpha(float alpha) {
        Drawable backdrop = getWindow().getDecorView().getBackground();
        if (backdrop != null) {
            int drawableAlpha = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
            backdrop.mutate().setAlpha(drawableAlpha);
        }
    }

    private void resetMorph() {
        if (mMorphAnimator != null) {
            mMorphAnimator.cancel();
            mMorphAnimator = null;
        }
        mMorphFraction = 0f;
        mVideoArea.setScaleX(1f);
        mVideoArea.setScaleY(1f);
        mVideoArea.setTranslationX(0f);
        mVideoArea.setTranslationY(0f);
        mVideoArea.setTranslationZ(0f);
        if (mWatchContent != null) {
            mWatchContent.setAlpha(1f);
        }
        if (mWatchScroll != null && mWatchScroll.getBackground() != null) {
            mWatchScroll.getBackground().mutate().setAlpha(255);
        }
        setWindowBackdropAlpha(1f);
        if (mControlsRoot != null) {
            mControlsRoot.setAlpha(mControlsVisible ? 1f : 0f);
        }
    }

    private void animateMorph(float to, long durationMs, @Nullable Runnable endAction) {
        if (mMorphAnimator != null) {
            mMorphAnimator.cancel();
        }
        mMorphAnimator = ValueAnimator.ofFloat(mMorphFraction, to);
        mMorphAnimator.setDuration(durationMs);
        mMorphAnimator.setInterpolator(new DecelerateInterpolator());
        mMorphAnimator.addUpdateListener(a -> applyMorph((float) a.getAnimatedValue()));
        mMorphAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mMorphAnimator = null;
                if (endAction != null) {
                    endAction.run();
                }
            }
        });
        mMorphAnimator.start();
    }

    @Override
    public boolean canStartDismissDrag() {
        return !mScrubbing && mPlayer != null && mMorphAnimator == null;
    }

    @Override
    public void onDismissDrag(float dy) {
        if (mMorphFraction == 0f && dy > 0f) {
            computeMorphTarget(); // anchor the corner path once per drag
        }
        int height = Math.max(1, mContainer.getHeight());
        applyMorph(Math.min(1f, dy / (height * 0.6f)));
    }

    @Override
    public void onDismissDragReleased(float dy, float yVelocity) {
        boolean dismiss = mMorphFraction > 0.3f || (yVelocity > 2200f && mMorphFraction > 0.08f);

        if (!dismiss) {
            animateMorph(0f, 180, this::resetMorph);
            return;
        }

        if (mPlayer == null) {
            // Error screen (nothing to dock): old close-by-drag behavior.
            animateMorph(1f, 150, () -> {
                if (mPresenter != null) {
                    mPresenter.onFinish();
                }
                finish();
                overridePendingTransition(0, 0);
            });
            return;
        }

        // The destination is already visible through our translucent window, so finish the live
        // video motion in this Activity first. Only then reorder Browse and hand it the texture;
        // its mini card occupies the same rectangle, making the Activity switch a visual no-op.
        // If the finger already dragged to the endpoint, do not run a 150ms no-op animator after
        // release: that pause made the eventual surface handoff look like a refresh/stutter.
        float remaining = Math.max(0f, 1f - mMorphFraction);
        if (remaining < 0.001f) {
            applyMorph(1f);
            minimizeByDrag();
        } else {
            long settleDurationMs = Math.max(50L, Math.round(150f * remaining));
            animateMorph(1f, settleDurationMs, this::minimizeByDrag);
        }
    }

    /**
     * Swipe-down now MINIMIZES like the YouTube app (playback continues in the Browse mini
     * card) instead of closing. This activity stays alive behind Browse - it still owns the
     * player. Called once the release animation has landed on the mini-card rectangle.
     */
    private void minimizeByDrag() {
        if (!prepareMiniPlayerHandoff(false)) {
            return;
        }

        // Return to the screen the video was opened from (Search, Channel, Home...) - the
        // last-resumed mini host is exactly the Activity visible through this translucent
        // window during the drag. Falling back to Home only when no host exists (deep link).
        MiniPlayerBridge.MiniHost host = MiniPlayerBridge.getMiniHost();
        final Class<?> hostView = host != null ? host.getMiniHostViewClass() : BrowseView.class;

        Runnable showHost = () -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            getViewManager().startView(hostView);
            overridePendingTransition(0, 0);
            // Only AFTER the reorder launch: a docked player leaves the logical back stack (see
            // prepareMiniPlayerHandoff). Removing it first would make the host the logical top
            // and startView's "already top" guard would skip the reorder entirely, stranding the
            // transparent player window above the host (observed: taps fell through to nothing).
            getViewManager().removeTop(this);
        };

        boolean prepared = MiniPlayerBridge.prepareMiniHostForHandoff(showHost);
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "mini minimize host=" + (host != null ? host.getClass().getSimpleName() : "none")
                            + " prepared=" + prepared);
        }
        if (!prepared) {
            // Cold/deep-link path: no retained host instance exists to pre-render.
            showHost.run();
        }
    }

    /** Channel navigation completed: background this player as a live in-app mini session. */
    boolean minimizeForNavigation() {
        if (!prepareMiniPlayerHandoff(true)) {
            return false;
        }
        // The channel is already launched and on top here, so the docked player can leave the
        // logical back stack immediately (the drag path defers this until after its reorder).
        getViewManager().removeTop(this);
        return true;
    }

    /** Capture/freeze the current frame and make the session texture available to a mini host. */
    private boolean prepareMiniPlayerHandoff(boolean captureFullSizeStill) {
        if (mPlayer == null) {
            return false;
        }

        // Freeze the current frame over the morphing video box, then detach the TextureView so
        // the session texture is free for the Browse card the moment it resumes (a SurfaceTexture
        // can feed only one GL consumer at a time). The codec keeps decoding into the briefly
        // consumer-less texture - audio and playback never hiccup - and the card picks the live
        // stream up without any surface change on the player.
        if (mVideoTexture != null && mVideoTexture.isAvailable()) {
            // A completed dismiss only needs a mini-card-sized bridge frame. Reading the entire
            // video TextureView back to a Bitmap on ACTION_UP forces a much larger GPU->CPU copy
            // on the UI thread and was the remaining hitch at the end of the gesture. Channel
            // navigation still starts its destination animation at full width, so retain the
            // full-size capture for that path.
            Bitmap frame;
            if (captureFullSizeStill) {
                frame = mVideoTexture.getBitmap();
            } else {
                float density = getResources().getDisplayMetrics().density;
                int width = Math.max(1, Math.round(MINI_CARD_WIDTH_DP * density));
                int height = Math.max(1, Math.round(MINI_CARD_HEIGHT_DP * density));
                frame = mVideoTexture.getBitmap(width, height);
            }
            if (frame != null) {
                showHandoffStill(frame);
                mStillAwaitFrame = false; // keep it until the expand path re-arms the lift
                // The destination paints this same frame until its TextureView receives the first
                // live update, avoiding a black/new-frame discontinuity at the Activity switch.
                MiniPlayerBridge.setMiniEntryStill(frame);
            }
        }
        detachVideoTexture();
        if (BuildConfig.DEBUG) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    "mini handoff detach t=" + android.os.SystemClock.uptimeMillis());
        }
        MiniPlayerBridge.activate(this);
        // A deep-linked open arms ViewManager's player-only mode ("watch, then back to the
        // launcher"). Minimizing into an in-app host means the user is now USING the app, so
        // drop the flag - a stale one makes startParentView "exit to Home" on the next back
        // press (observed: back from a channel with a docked card sent the app to the launcher).
        getViewManager().enablePlayerOnlyMode(false);
        // NOTE: the docked player also leaves ViewManager's logical stack (so a host's
        // back-press resolves its parent to the screen BELOW the player instead of expanding
        // the video), but each caller removes it at its own safe point - see minimizeByDrag
        // (after the reorder launch) and minimizeForNavigation. The next onResume's addTop()
        // re-inserts it when the card expands back to full screen.
        // Launching Browse over ourselves delivers onUserLeaveHint to this activity, and the
        // isNewViewPending() guard there is NOT reliable for this hand-off (observed: minimize
        // put the player into a system PiP window floating over the mini card). Suppress
        // explicitly; cleared on the next onResume. The param push disarms the Android 12+
        // auto-enter flag too - same failure mode, system-initiated instead of leave-hint.
        mSuppressAutoPip = true;
        updatePipActions();
        return true;
    }

    /** X tapped on the Browse mini bar: stop playback and quietly retire this hidden activity. */
    void closeFromMiniPlayer() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
        // finishReally() (not finish()): Browse is already in the foreground; finish()'s root-screen
        // branch would send the whole app to the background when the player was deep-linked (no
        // parent view), yanking Browse away mid-scroll.
        finishReally();
    }

    /** Live player accessor for the mini bar (package-private, see MiniPlayerBridge). */
    ExoPlayer getSharedPlayer() {
        return mPlayer;
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
        // Fresh installs: while the one-time session setup is still running, tell the user why
        // this first load is longer than usual. Never shows again once any fetch succeeded.
        if (mSetupHint != null) {
            mSetupHint.setVisibility(show && !SessionWarmup.isWarm() ? View.VISIBLE : View.GONE);
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

        // Chapter rows aren't related videos: route them to the YouTube-style "Chapters" entry
        // (titled list + tap-to-seek) instead of the Up-next list.
        if (group.isChapters()) {
            runOnUiThread(() -> setChapters(group.getVideos()));
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
            setChapters(null);
            if (mRelatedAdapter != null) {
                mRelatedAdapter.submitList(new ArrayList<>());
            }
            if (mWatchRelatedLabel != null) {
                mWatchRelatedLabel.setVisibility(View.GONE);
            }
            // A new video is loading: the video itself starts first (by design), so show the
            // pulsing "up next" skeleton until the related feed lands.
            showRelatedSkeleton();
        });
    }

    /**
     * LOADING SKELETON: pulsing placeholder rows under "Up next" while the related feed loads.
     * The video deliberately starts before the page content (all fetches are parallelized), so the
     * skeleton communicates "this part is on its way" instead of leaving dead space. Hidden the
     * moment real rows land ({@link #rebuildRelatedList}) or after a safety timeout (no related).
     */
    private static final long SKELETON_TIMEOUT_MS = 10_000;
    private final Runnable mHideSkeletonTimeout = this::hideRelatedSkeleton;

    private void showRelatedSkeleton() {
        if (mRelatedSkeleton == null) {
            return;
        }
        mRelatedSkeleton.setVisibility(View.VISIBLE);
        if (mSkeletonPulse == null) {
            mSkeletonPulse = android.animation.ValueAnimator.ofFloat(1f, 0.45f);
            mSkeletonPulse.setDuration(700);
            mSkeletonPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            mSkeletonPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            mSkeletonPulse.addUpdateListener(a -> {
                if (mRelatedSkeleton != null) {
                    mRelatedSkeleton.setAlpha((float) a.getAnimatedValue());
                }
            });
        }
        if (!mSkeletonPulse.isStarted()) {
            mSkeletonPulse.start();
        }
        Utils.removeCallbacks(mHideSkeletonTimeout);
        Utils.postDelayed(mHideSkeletonTimeout, SKELETON_TIMEOUT_MS);
    }

    private void hideRelatedSkeleton() {
        Utils.removeCallbacks(mHideSkeletonTimeout);
        if (mSkeletonPulse != null && mSkeletonPulse.isStarted()) {
            mSkeletonPulse.cancel();
        }
        if (mRelatedSkeleton != null) {
            mRelatedSkeleton.setVisibility(View.GONE);
            mRelatedSkeleton.setAlpha(1f);
        }
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
                || buttonId == R.id.action_playlist_add
                || buttonId == R.id.action_rotate
                || buttonId == R.id.action_sound_off
                // CC toggle state: kept in sync by the controller (onMetadata ->
                // setSubtitleButtonState) and by applyCaptionFormat(); rendered as the overlay
                // CC button's tint.
                || buttonId == R.id.lb_control_closed_captioning) {
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
     * Build the {@link Media3SubtitleManager} over the PlayerView's built-in {@link
     * androidx.media3.ui.SubtitleView} and register it as a player listener (media3's cue path),
     * so the user's stored {@code SubtitleStyle} (from SubtitleSettingsPresenter) actually takes
     * effect. Mirrors PlaybackFragment.createSubtitleManager(). Idempotent.
     */
    private void createSubtitleManager() {
        if (mSubtitleManager != null || mPlayer == null || mPlayerView == null) {
            return;
        }

        androidx.media3.ui.SubtitleView subtitleView = mPlayerView.getSubtitleView();
        if (subtitleView == null) {
            return;
        }

        mSubtitleManager = new Media3SubtitleManager(subtitleView);
        mPlayer.addListener(mSubtitleManager);
    }

    /** Build the media3 stats-for-nerds over the debug overlay group. Mirrors the TV fragment. */
    private void createDebugManager() {
        if (mDebugInfoManager != null || mDebugViewGroup == null || mPlayer == null) {
            return;
        }
        mDebugInfoManager = new Media3DebugInfoManager(mDebugViewGroup, mPlayer,
                mExoPlayerController.getMediaSourceFactory().getBandwidthMeter());
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

    // ---------------------------------------------------------------------------------
    // Chapters (data = the isChapters() suggestions group; UI = the scrub-time label above the
    // seek bar - YouTube's subtle "which chapter is this" hover text - plus the shared seek-bar
    // tick marks that already flow through setSeekBarSegments)
    // ---------------------------------------------------------------------------------

    /** Store the current video's chapters (null/empty clears them). */
    private void setChapters(List<Video> chapters) {
        mChapterVideos.clear();

        if (chapters != null && !chapters.isEmpty()) {
            mChapterVideos.addAll(chapters);
        }

        if (mScrubChapterView != null && mChapterVideos.isEmpty()) {
            mScrubChapterView.setVisibility(View.GONE);
        }
    }

    /** While scrubbing: show the title of the chapter under the scrub position (hidden if none). */
    private void updateScrubChapterLabel(long positionMs) {
        if (mScrubChapterView == null) {
            return;
        }

        if (mChapterVideos.isEmpty()) {
            mScrubChapterView.setVisibility(View.GONE);
            return;
        }

        CharSequence title = null;
        for (int i = 0; i < mChapterVideos.size(); i++) {
            if (mChapterVideos.get(i).startTimeMs <= positionMs) {
                title = mChapterVideos.get(i).title;
            } else {
                break;
            }
        }

        if (TextUtils.isEmpty(title)) {
            mScrubChapterView.setVisibility(View.GONE);
        } else {
            if (!TextUtils.equals(mScrubChapterView.getText(), title)) {
                mScrubChapterView.setText(title);
            }
            mScrubChapterView.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------------------------
    // Simple quality / audio-language sheet (the quality button's everyday picker)
    // ---------------------------------------------------------------------------------

    /** Distinct resolution rung of the current video: "1080p" / "1080p60" style. */
    private static String qualityLabel(FormatItem item) {
        int height = item.getHeight();
        boolean highFps = item.getFrameRate() > 40;
        return height + "p" + (highFps ? "60" : "");
    }

    /**
     * "Auto" = a ceiling preset, not a concrete stream format. The DEFAULT format constants ship
     * with the isPreset flag unset but a null format id - the selector's own "preset by id
     * presence" rule (VideoTrack.inBounds) - so both must count, or a fresh install never shows
     * Auto as active and explicit rungs persist instead of being per-session.
     */
    private static boolean isAutoFormat(FormatItem item) {
        if (item == null || item.isPreset()) {
            return true;
        }

        com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack track = item.getTrack();
        return track == null || track.format == null || track.format.id == null;
    }

    private void showQualitySheet() {
        List<FormatItem> videoFormats = getVideoFormats();
        if (videoFormats == null) {
            videoFormats = new ArrayList<>();
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_quality, null);
        dialog.setContentView(content);

        LinearLayout qualityList = content.findViewById(R.id.quality_sheet_quality_list);
        LinearLayout audioList = content.findViewById(R.id.quality_sheet_audio_list);

        // ---- Quality: Auto + one row per distinct resolution rung (best track of each rung). ----
        // The ACTIVE choice is the per-session override when one is set (explicit rung picked
        // while the persisted default stays Auto), else the persisted format - otherwise the
        // sheet would keep check-marking Auto right after the user picked a rung.
        PlayerData playerData = PlayerData.instance(this);
        FormatItem tempOverride = playerData.getTempVideoFormat();
        FormatItem persisted = tempOverride != null ? tempOverride : playerData.getFormat(FormatItem.TYPE_VIDEO);
        boolean autoActive = isAutoFormat(persisted);

        // Rung -> representative track. Formats arrive quality-descending; the first of each rung
        // is its best variant. An explicitly selected non-preset track marks its rung instead.
        java.util.LinkedHashMap<String, FormatItem> rungs = new java.util.LinkedHashMap<>();
        String selectedRung = null;
        for (FormatItem item : videoFormats) {
            if (item.getHeight() <= 0) {
                continue;
            }
            String label = qualityLabel(item);
            if (!rungs.containsKey(label)) {
                rungs.put(label, item);
            }
            if (!autoActive && item.isSelected()) {
                selectedRung = label;
            }
        }

        addQualityRow(qualityList, getString(R.string.mobile_quality_auto), autoActive, () -> {
            // Back to the smart default: ABR under the mobile 1080p ceiling. The session override
            // must also go - VideoStateController restores tempVideoFormat FIRST on every new
            // video, so a stale explicit rung would silently out-vote Auto from the next video on.
            playerData.setTempVideoFormat(null);
            FormatItem auto = playerData.getDefaultVideoFormat();
            setFormat(auto);
            playerData.setFormat(auto);
            dialog.dismiss();
        });
        for (java.util.Map.Entry<String, FormatItem> rung : rungs.entrySet()) {
            FormatItem item = rung.getValue();
            addQualityRow(qualityList, rung.getKey(), rung.getKey().equals(selectedRung), () -> {
                // Mirrors HQDialogController.selectFormatOption: while the preset (Auto) is the
                // persisted default, an explicit rung is a per-session override, like YouTube.
                setFormat(item);
                if (isAutoFormat(playerData.getFormat(FormatItem.TYPE_VIDEO))) {
                    playerData.setTempVideoFormat(item);
                } else {
                    playerData.setFormat(item);
                }
                dialog.dismiss();
            });
        }

        // ---- Audio: only when the video actually ships multiple languages (dubs). ----
        List<FormatItem> audioFormats = getAudioFormats();
        java.util.LinkedHashMap<String, FormatItem> languages = new java.util.LinkedHashMap<>();
        String selectedLanguage = null;
        if (audioFormats != null) {
            for (FormatItem item : audioFormats) {
                String language = item.getLanguage();
                String label = TextUtils.isEmpty(language)
                        ? getString(R.string.mobile_audio_default) : capitalize(language);
                if (!languages.containsKey(label)) {
                    languages.put(label, item);
                }
                if (item.isSelected()) {
                    selectedLanguage = label;
                }
            }
        }

        if (languages.size() > 1) {
            for (java.util.Map.Entry<String, FormatItem> language : languages.entrySet()) {
                FormatItem item = language.getValue();
                addQualityRow(audioList, language.getKey(), language.getKey().equals(selectedLanguage), () -> {
                    setFormat(item);
                    playerData.setFormat(item);
                    dialog.dismiss();
                });
            }
        } else {
            // Single-language video: hide the whole audio section.
            content.findViewById(R.id.quality_sheet_divider).setVisibility(View.GONE);
            content.findViewById(R.id.quality_sheet_audio_title).setVisibility(View.GONE);
            audioList.setVisibility(View.GONE);
        }

        showPlayerSheet(dialog);
    }

    private void addQualityRow(LinearLayout parent, CharSequence label, boolean selected, Runnable onClick) {
        View row = getLayoutInflater().inflate(R.layout.item_mobile_quality_row, parent, false);
        TextView labelView = row.findViewById(R.id.quality_row_label);
        labelView.setText(label);
        if (selected) {
            // YouTube-style: the leading check alone marks the active choice (no bold).
            row.findViewById(R.id.quality_row_check).setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    private static String capitalize(String text) {
        return TextUtils.isEmpty(text) ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---------------------------------------------------------------------------------
    // Captions, native YouTube-style UX. The overlay CC button toggles (last track <-> off);
    // the captions sheet below (long-press CC, or gear -> Subtitles) is the track picker,
    // replacing the TV AppDialog radio list.
    // ---------------------------------------------------------------------------------

    /** A real caption track, as opposed to the fake default/"disabled" entry. */
    private static boolean isCaptionTrack(FormatItem item) {
        return item != null && !item.isDefault() && item.getLanguage() != null;
    }

    /**
     * Row label for a caption track. Subtitle FormatItems carry the MPD's human-readable name in
     * the language slot ("English", "English (auto-generated)*"); autogenerated/auto-translated
     * variants end with the TRANSLATE_MARKER, which the picker shouldn't show.
     */
    private static String captionLabel(FormatItem item) {
        String label = item.getLanguage() != null ? item.getLanguage()
                : item.getTitle() != null ? item.getTitle().toString() : "";
        if (SubtitleTrack.isAuto(label)) {
            label = label.substring(0, label.length() - 1);
        }
        // The MPD's localized track names arrive lowercase in some languages ("inglés").
        return capitalize(label);
    }

    /** Mirrors PlayerUIController.isSubtitleSelected: a real track is actually selected. */
    private boolean areCaptionsOn() {
        List<FormatItem> formats = getSubtitleFormats();
        if (formats == null) {
            return false;
        }
        for (FormatItem item : formats) {
            if (item.isSelected() && isCaptionTrack(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CC button tap: toggle like the official app - captions off, or the last-used track back on.
     * Falls through to the picker when there's no usable history yet (first use, or the remembered
     * languages don't exist on this video) and when the video has no tracks at all (the sheet
     * shows its empty state).
     */
    private void toggleCaptions() {
        cancelAutoHide();

        if (areCaptionsOn()) {
            applyCaptionFormat(FormatItem.SUBTITLE_NONE);
            armAutoHide();
            return;
        }

        FormatItem match = null;
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem last : PlayerData.instance(this).getLastSubtitleFormats()) {
                int index = formats.indexOf(last);
                if (index != -1) {
                    // Apply THIS video's own track instance, not the persisted twin: the stored
                    // item's format id can be stale across videos/sessions, in which case the
                    // selector override finds no track and the selection silently stays put.
                    match = formats.get(index);
                    break;
                }
            }
        }

        if (match != null) {
            applyCaptionFormat(match);
            armAutoHide();
        } else {
            showCaptionsSheet();
        }
    }

    /**
     * Select a caption track (or {@link FormatItem#SUBTITLE_NONE}): the same persistence steps as
     * the TV picker's callback (PlayerUIController.onSubtitleLongClicked) - engine, PlayerData
     * (which also feeds the last-used toggle list), per-channel memory - plus the CC button state
     * and the official app's confirmation snackbar ("Subtitles on (English)" / "Subtitles off").
     */
    private void applyCaptionFormat(FormatItem format) {
        boolean on = isCaptionTrack(format);

        setFormat(format);
        PlayerData playerData = PlayerData.instance(this);
        playerData.setFormat(format);

        if (playerData.isSubtitlesPerChannelEnabled()) {
            Video video = getVideo();
            String channelId = video != null ? video.channelId : null;
            if (on) {
                playerData.enableSubtitlesPerChannel(channelId);
            } else {
                playerData.disableSubtitlesPerChannel(channelId);
            }
        }

        setButtonState(R.id.lb_control_closed_captioning, on ? BUTTON_ON : BUTTON_OFF);

        com.google.android.material.snackbar.Snackbar.make(
                        findViewById(android.R.id.content),
                        on ? getString(R.string.mobile_captions_on_toast, captionLabel(format))
                                : getString(R.string.mobile_captions_off_toast),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show();
    }

    /** Last-used tracks bubble to the top, like the TV picker (PlayerUIController.reorderSubtitles). */
    private void moveLastUsedCaptionsFirst(List<FormatItem> tracks) {
        List<FormatItem> top = new ArrayList<>();
        for (FormatItem last : PlayerData.instance(this).getLastSubtitleFormats()) {
            if (last == null || last.getLanguage() == null) {
                continue;
            }
            int index = tracks.indexOf(last);
            if (index != -1) {
                top.add(tracks.remove(index));
            }
        }
        tracks.addAll(0, top);
    }

    private void showCaptionsSheet() {
        cancelAutoHide();

        List<FormatItem> tracks = new ArrayList<>();
        List<FormatItem> autoTracks = new ArrayList<>();
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (!isCaptionTrack(item)) {
                    continue;
                }
                if (SubtitleTrack.isAuto(item.getLanguage())) {
                    autoTracks.add(item);
                } else {
                    tracks.add(item);
                }
            }
        }
        moveLastUsedCaptionsFirst(tracks);
        moveLastUsedCaptionsFirst(autoTracks);

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_captions, null);
        dialog.setContentView(content);

        // One flat list, official-app style: Off, then the video's tracks, then the
        // autogenerated/auto-translated variants (their labels already carry the
        // "(auto-generated)" wording, so no section header is needed).
        LinearLayout trackList = content.findViewById(R.id.captions_sheet_track_list);
        addQualityRow(trackList, getString(R.string.mobile_captions_off), !areCaptionsOn(), () -> {
            applyCaptionFormat(FormatItem.SUBTITLE_NONE);
            dialog.dismiss();
        });
        tracks.addAll(autoTracks);
        for (FormatItem item : tracks) {
            addQualityRow(trackList, captionLabel(item), item.isSelected(), () -> {
                applyCaptionFormat(item);
                dialog.dismiss();
            });
        }

        if (tracks.isEmpty()) {
            content.findViewById(R.id.captions_sheet_empty).setVisibility(View.VISIBLE);
        }

        // Caption appearance (style/size/position + the per-channel memory switch): the existing
        // settings dialog, rendered by MobileAppDialogActivity like the rest of settings.
        content.findViewById(R.id.captions_sheet_style).setOnClickListener(v -> {
            dialog.dismiss();
            SubtitleSettingsPresenter.instance(this).show();
        });

        dialog.setOnDismissListener(d -> armAutoHide());
        showPlayerSheet(dialog);
    }

    /** Trailing value for the gear menu's Subtitles row: the active track, or "Off". */
    private String currentCaptionsLabel() {
        List<FormatItem> formats = getSubtitleFormats();
        if (formats != null) {
            for (FormatItem item : formats) {
                if (item.isSelected() && isCaptionTrack(item)) {
                    return captionLabel(item);
                }
            }
        }
        return getString(R.string.mobile_menu_off);
    }

    // ---------------------------------------------------------------------------------
    // Playback speed: native preset sheet, classic official-app anatomy (0.25x..2x flat
    // list, "Normal" for 1x, leading check). The exhaustive TV dialog (0.25-4x list +
    // remember-speed options) stays reachable behind "More speeds".
    // ---------------------------------------------------------------------------------

    private static final float[] SPEED_PRESETS = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    /** "Normal" for 1x (official-app wording), "0.5x"/"1.5x"/"2x" otherwise. */
    private String speedLabel(float speed) {
        if (Helpers.floatEquals(speed, 1.0f)) {
            return getString(R.string.mobile_speed_normal);
        }
        String number = speed == Math.floor(speed)
                ? String.valueOf((int) speed) : String.valueOf(speed);
        return number + "x";
    }

    private void showSpeedSheet() {
        cancelAutoHide();

        float current = mExoPlayerController != null ? mExoPlayerController.getSpeed() : -1;
        if (current <= 0) {
            current = 1f;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_mobile_speed, null);
        dialog.setContentView(content);

        LinearLayout list = content.findViewById(R.id.speed_sheet_list);
        for (float speed : SPEED_PRESETS) {
            addQualityRow(list, speedLabel(speed), Helpers.floatEquals(speed, current), () -> {
                applySpeed(speed);
                dialog.dismiss();
            });
        }

        // Full TV speed dialog: extended 0.25-4x list (long-click path always opens the list).
        content.findViewById(R.id.speed_sheet_more).setOnClickListener(v -> {
            dialog.dismiss();
            openPlayerOption(R.id.action_video_speed, true);
        });

        dialog.setOnDismissListener(d -> armAutoHide());
        showPlayerSheet(dialog);
    }

    /**
     * Apply a speed pick. The engine change fires onSpeedChanged, which VideoStateController
     * already persists (global/per-channel memory); only the per-video State save - the TV
     * dialog's close hook - needs mirroring here. Confirms via snackbar, same as captions.
     */
    private void applySpeed(float speed) {
        setSpeed(speed);

        Video video = getVideo();
        if (video != null && PlayerData.instance(this).isSpeedPerVideoEnabled()) {
            VideoStateService stateService = VideoStateService.instance(this);
            State state = stateService.getByVideoId(video.videoId);
            if (state != null) {
                stateService.save(new State(state.video, state.positionMs, state.durationMs, speed));
            }
        }

        com.google.android.material.snackbar.Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.mobile_speed_toast, speedLabel(speed)),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show();
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

    /**
     * Stop the Activity-owned live-chat poll (openLiveChatObserve loops forever) and clear it so a
     * later onChatEntryClicked re-seeds a fresh stream - its gate requires mLiveChatAction == null.
     * The ChatController receiver path (mChatReceiver) owns its own stream and is left untouched.
     */
    private void stopLiveChatStream() {
        RxHelper.disposeActions(mLiveChatAction);
        mLiveChatAction = null;
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

    @Override
    public void onChatSheetDismissed() {
        // Panel closed by the user: stop the invisible forever-poll opened in onChatEntryClicked.
        // A later re-open re-seeds a fresh stream via the mLiveChatAction == null gate.
        stopLiveChatStream();
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

        // Never blank a previously shown title on a SAME-video rebind: error-reloads re-enter with
        // a bare Video (title lost) and the fetch that would repopulate it may die on a bad
        // network - keep the last-known-good text. A genuinely new video may reset to empty.
        if (isNewVideo || !TextUtils.isEmpty(item.getTitleFull())) {
            mWatchTitle.setText(item.getTitleFull());
        }
        // Channel name has the same bare-reload-Video blanking problem as the title above.
        if (isNewVideo || !TextUtils.isEmpty(item.getAuthor())) {
            mWatchChannelName.setText(item.getAuthor());
        }

        // Fallback meta line until the metadata load returns a clean "views • date". The
        // second-title leads with the channel name, which the channel row right below repeats -
        // strip it so the line reads "1.4M views • 10 months ago" like YouTube's.
        CharSequence second = item.getSecondTitleFull();
        if (mWatchMeta.length() == 0 && !TextUtils.isEmpty(second)) {
            String line = second.toString();
            String author = item.getAuthor();
            if (!TextUtils.isEmpty(author) && line.startsWith(author)) {
                String stripped = line.substring(author.length()).replaceFirst("^\\s*[•·]\\s*", "");
                if (!stripped.isEmpty()) {
                    line = stripped;
                }
            }
            mWatchMeta.setText(line);
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
            // NEWTUBE(mobile-ttff): a new video resets the header to its fallback and clears any stale
            // stashed metadata. The real header data now arrives via onWatchMetadata (the single
            // metadata document SuggestionsController loads), not a 2nd getMetadataObserve here.
            mPendingMetadata = null;
        }
    }

    private void resetWatchHeader() {
        mDescriptionExpanded = false;
        mWatchDescription.setVisibility(View.GONE);
        mWatchDescription.setText(null);
        mWatchMeta.setText(null);
        mWatchMeta.setMaxLines(1);
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

    @Override
    public void onWatchMetadata(MediaItemMetadata metadata) {
        if (metadata == null) {
            return;
        }

        // NEWTUBE(mobile-ttff): delivered on the metadata load thread by SuggestionsController. Marshal
        // to the UI thread, then either bind now (first frame already rendered) or stash and bind on
        // the first STATE_READY, so the header bind never competes with first-frame render.
        runOnUiThread(() -> {
            mPendingMetadata = metadata;
            if (mFirstFrameReady) {
                bindWatchMetadata(metadata);
            }
        });
    }

    private void bindWatchMetadata(MediaItemMetadata metadata) {
        if (metadata == null) {
            return;
        }

        {
            // Clean "views • date" line. YouTube's raw date string arrives as "Published on
            // Jan 14, 2024" / "Premiered ..." - drop the wordy prefix, keep just the date.
            // The title otherwise binds only from setVideo (Video.getTitleFull) - which is empty on
            // bare error-reload Videos. Metadata carries the real title: use it to (re)populate,
            // and fill the controls title too if nothing is showing there.
            if (!TextUtils.isEmpty(metadata.getTitle())) {
                mWatchTitle.setText(metadata.getTitle());
                if (mTitleView != null && TextUtils.isEmpty(mTitleView.getText())) {
                    mTitleView.setText(metadata.getTitle());
                }
            }

            String views = metadata.getViewCount();
            String date = metadata.getPublishedDate();
            if (date != null) {
                date = date.replaceFirst("(?i)^(published|premiered|streamed live) on ", "");
                // Non-English locales label the date "Data de publicació: 29 de des. 2019" /
                // "Fecha de publicación: ..." - drop the leading "Label:" too. Publish dates
                // never contain a colon themselves, so this can't clip the date.
                String unlabeled = date.replaceFirst("^[^:]{1,40}:\\s*", "");
                if (!unlabeled.isEmpty()) {
                    date = unlabeled;
                }
            }
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
        }
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
        // Smooth expand/collapse: animate the content column's layout change and spin the
        // chevron, instead of the block just popping in.
        ViewGroup content = (ViewGroup) mWatchDescription.getParent();
        if (content != null) {
            androidx.transition.TransitionManager.beginDelayedTransition(content,
                    new androidx.transition.AutoTransition().setDuration(180));
        }
        mWatchDescription.setVisibility(mDescriptionExpanded ? View.VISIBLE : View.GONE);
        // Expanded: let the views/date line wrap so long localized dates (e.g. "29 de des. 2019"
        // behind a wordy label) are fully readable instead of ellipsized.
        mWatchMeta.setMaxLines(mDescriptionExpanded ? Integer.MAX_VALUE : 1);
        mWatchExpand.animate().rotation(mDescriptionExpanded ? 180f : 0f).setDuration(180).start();
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
        } else if (buttonId == R.id.lb_control_closed_captioning && mSubtitlesButton != null) {
            // YouTube-style: filled CC glyph while captions are on, outlined while off.
            mSubtitlesButton.setImageResource(on ? R.drawable.ic_player_cc : R.drawable.ic_player_cc_off);
        } else if (buttonId == R.id.action_subscribe && mWatchSubscribe != null) {
            mWatchSubscribe.setText(on ? R.string.mobile_watch_subscribed : R.string.mobile_watch_subscribe);
            mWatchSubscribe.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColorInt(on ? R.color.mobile_color_subscribed_button : android.R.color.white)));
            mWatchSubscribe.setTextColor(getColorInt(on
                    ? R.color.mobile_color_on_surface : android.R.color.black));
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

    /** Watch-page channel row tap → the mobile channel screen. Same routing as the card menu's
     *  "Open channel" entry; playback stays alive behind the channel screen (the pending-view
     *  flag in onUserLeaveHint keeps auto-PiP from hijacking the in-app navigation). */
    private void openCurrentChannel() {
        Video video = getVideo();
        if (video == null || !ChannelPresenter.canOpenChannel(video)) {
            return;
        }
        // Channel-id lookup may be asynchronous. Mark the route now, but detach/activate the live
        // mini session only when MobileChannelActivity is actually created; a failed lookup leaves
        // the watch page untouched.
        MiniPlayerBridge.prepareNavigation(this);
        MediaServiceManager.chooseChannelPresenter(this, video);
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
        // When playing from a playlist, the section-playlist row (SuggestionsController.
        // appendSectionPlaylistIfNeeded) contains the WHOLE playlist including the video that's
        // already playing - YouTube's queue hides it, and as the first tappable "Up next" row it
        // reads as broken (tapping it restarts the current video). Hide it from the visible list
        // only, by videoId (Video.equals is unreliable across instances): the controller's
        // next/prev logic walks the group objects, which stay untouched.
        Video current = getVideo();
        String currentId = current != null ? current.videoId : null;
        for (List<Video> vids : mSuggestionVideos.values()) {
            for (Video v : vids) {
                if (currentId == null || v == null || !currentId.equals(v.videoId)) {
                    mRelatedVideos.add(v);
                }
            }
        }

        if (mRelatedAdapter != null) {
            mRelatedAdapter.submitList(new ArrayList<>(mRelatedVideos));
        }
        if (mWatchRelatedLabel != null) {
            mWatchRelatedLabel.setVisibility(mRelatedVideos.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (!mRelatedVideos.isEmpty()) {
            hideRelatedSkeleton(); // real rows are in; stop pulsing
        }
    }

    // ---------------------------------------------------------------------------------
    // PlayerEngine - real, delegates to ExoPlayerController (playback-critical).
    // ---------------------------------------------------------------------------------

    @Override
    public void prebuildNextSource(MediaItemFormatInfo formatInfo) {
        // NEWTUBE(prepare-stash): pre-build + stash the likely next video's MediaSource so the
        // auto-advance open skips the MPD gen+parse (TV keeps the no-op PlayerEngine default).
        mExoPlayerController.prebuildNextSource(formatInfo);
    }

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

        // Belt-and-braces for live: besides the legacy too-big clamp, catch a broken engine
        // duration (media3 computed a negative live window before the LiveDashManifestParser fix;
        // <=0 keeps the timebar dead and live-edge math garbage) and fall back to wall-clock
        // "now - stream start". liveDurationMs != 0 only for live videos (Video.getLiveDurationMs
        // returns 0 when startTimeMs == 0), so VOD's transient pre-prepare durationMs <= 0 is
        // never touched.
        if ((durationMs <= 0 || durationMs > Video.MAX_LIVE_DURATION_MS) && liveDurationMs != 0) {
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
        // Pinch-to-zoom itself is handled by PinchZoomLayout -> onPinchZoom (snap fill/fit via
        // resize mode). TODO Wave N: the dialog's percent-based zoom values (50%-300%).
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

        // Same keep-last-known-good rule as bindWatchVideo: a same-video rebind with an empty
        // title (bare error-reload Video) must not blank the controls title.
        boolean sameVideo = item != null && Helpers.equals(item.videoId, mWatchVideoId);
        if (!sameVideo || (item != null && !TextUtils.isEmpty(item.getTitleFull()))) {
            setTitle(item != null ? item.getTitleFull() : null);
        }
        bindWatchVideo(item);

        // LOADING STILL: a DIFFERENT video was just set on this (reused) view. The texture still
        // shows the previous video's last frame and the new audio starts as soon as it buffers,
        // so cover the stale frame with the new video's thumbnail until ITS first frame renders
        // (YouTube does exactly this). Also gives the very first open a thumbnail instead of black.
        runOnUiThread(() -> maybeShowLoadingStill(item));

        // LOADING SKELETON: a new video is being set on the view and its related feed hasn't landed
        // yet. Covers the FIRST open too (clearSuggestions only fires on subsequent loads).
        if (item != null && mRelatedVideos.isEmpty()) {
            runOnUiThread(this::showRelatedSkeleton);
        }

        // CASTING: an active session claims every newly selected video (see maybeRouteVideoToCast).
        if (item != null && item.videoId != null) {
            final String videoId = item.videoId;
            runOnUiThread(() -> maybeRouteVideoToCast(videoId));
        }
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

    /**
     * NEWTUBE(diagnostics): debug-only per-chunk load logging under tag {@code NetPath}. media3's
     * stock {@code EventLogger} prints loadError only - loadStarted/loadCompleted (bytes, duration,
     * media position per chunk) never reach logcat with it. One dense line per event:
     * {@code load[S|C|X|E] <dataType>/<trackType> <uri-tail> bytes=<n> ms=<n> pos=<n>}
     * (S=started, C=completed, X=canceled, E=error; E appends the exception class+message).
     * Registered next to EventLogger in {@link #createPlayerObjects()} behind the same
     * {@code BuildConfig.DEBUG} gate. Uses android.util.Log directly so lines always reach logcat.
     */
    private static final class NetPathLoadListener implements androidx.media3.exoplayer.analytics.AnalyticsListener {
        private final Context mContext;

        NetPathLoadListener(Context context) {
            mContext = context.getApplicationContext();
        }

        @Override
        public void onLoadStarted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData, int retryCount) {
            log("load[S]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadCompleted(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            log("load[C]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadCanceled(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            log("load[X]", loadEventInfo, mediaLoadData, null);
        }

        @Override
        public void onLoadError(EventTime eventTime, androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData, java.io.IOException error,
                boolean wasCanceled) {
            log("load[E]", loadEventInfo, mediaLoadData, error);
        }

        @Override
        public void onPositionDiscontinuity(EventTime eventTime,
                androidx.media3.common.Player.PositionInfo oldPosition,
                androidx.media3.common.Player.PositionInfo newPosition, int reason) {
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                            + " position-discontinuity reason=" + discontinuityReason(reason)
                            + " from=" + oldPosition.positionMs + " to=" + newPosition.positionMs
                            + " delta=" + (newPosition.positionMs - oldPosition.positionMs));
        }

        @Override
        public void onDownstreamFormatChanged(EventTime eventTime,
                androidx.media3.exoplayer.source.MediaLoadData mediaLoadData) {
            androidx.media3.common.Format format = mediaLoadData.trackFormat;
            if (format == null) {
                return;
            }
            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                            + " track-selected type=" + mediaLoadData.trackType
                            + " id=" + com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(format.id, 32)
                            + " mime=" + format.sampleMimeType
                            + " bitrate=" + format.bitrate
                            + " size=" + format.width + 'x' + format.height
                            + " fps=" + format.frameRate);
        }

        private void log(String event,
                androidx.media3.exoplayer.source.LoadEventInfo info,
                androidx.media3.exoplayer.source.MediaLoadData data,
                @Nullable Exception error) {
            StringBuilder line = new StringBuilder(
                    com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context())
                    .append(' ').append(event)
                    .append(" lid=").append(info.loadTaskId)
                    .append(' ').append(data.dataType).append('/').append(data.trackType)
                    .append(" host=").append(info.uri.getHost())
                    .append(' ').append(uriTail(info.uri))
                    .append(" req=").append(info.dataSpec.position).append('+').append(info.dataSpec.length)
                    .append(" bytes=").append(info.bytesLoaded)
                    .append(" ms=").append(info.loadDurationMs)
                    .append(" pos=").append(data.mediaStartTimeMs)
                    .append(" net=").append(activeNetwork());
            appendResponseSummary(line, info.responseHeaders);
            if (error != null) {
                // Errors get the request's byte range plus the URL's declared length/version
                // params: a req beyond clen, or an lmt that differs between /player mints, each
                // pin a distinct failure mode of a deterministic per-range 403 (seen on-device).
                if (info.uri.isHierarchical()) {
                    appendQueryParam(line, info.uri, "clen");
                    appendQueryParam(line, info.uri, "lmt");
                }
                line.append(' ').append(error.getClass().getSimpleName()).append(": ")
                        .append(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(error.getMessage(), 120));
                android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, line.toString());
                // On HTTP errors log the rejection body plus a replay-useful but credential-safe
                // URL fingerprint. Complete googlevideo URLs contain signed credentials and must
                // never enter logcat.
                androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException http =
                        findInvalidResponseCode(error);
                if (http != null) {
                    StringBuilder detail = new StringBuilder(
                            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context())
                            .append(" load[E-http] code=").append(http.responseCode);
                    byte[] body = http.responseBody;
                    if (body != null && body.length > 0) {
                        detail.append(" body[").append(body.length).append("] hash=")
                                .append(fingerprint(body)).append(" text=").append(printable(body, 240));
                    }
                    android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, detail.toString());
                    android.util.Log.w(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.context()
                                    + " load[E-request] " + safeRequestFingerprint(info));
                }
            } else {
                android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG, line.toString());
            }
        }

        private static void appendResponseSummary(StringBuilder line,
                java.util.Map<String, java.util.List<String>> headers) {
            if (headers == null || headers.isEmpty()) {
                line.append(" responseHeaders=none");
                return;
            }
            line.append(" responseHeaders=y");
            appendHeader(line, headers, "content-length", "respLen");
            appendHeader(line, headers, "content-range", "contentRange");
            appendHeader(line, headers, "accept-ranges", "acceptRanges");
            appendHeader(line, headers, "content-encoding", "encoding");
            appendHeader(line, headers, "server", "server");
        }

        private static void appendHeader(StringBuilder line,
                java.util.Map<String, java.util.List<String>> headers,
                String wantedName, String logName) {
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && wantedName.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    line.append(' ').append(logName).append('=')
                            .append(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.trunc(
                                    entry.getValue().get(0), 80));
                    return;
                }
            }
        }

        private static String discontinuityReason(int reason) {
            switch (reason) {
                case androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION:
                    return "auto";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK:
                    return "seek";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
                    return "seek-adjust";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP:
                    return "skip";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE:
                    return "remove";
                case androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL:
                    return "internal";
                default:
                    return Integer.toString(reason);
            }
        }

        private String activeNetwork() {
            return com.liskovsoft.smartyoutubetv2.common.misc.NetPath.networkId(mContext);
        }

        /** Last path segment plus the identifying query params (itag/range/sq/rn), max ~80 chars. */
        private static String uriTail(android.net.Uri uri) {
            StringBuilder tail = new StringBuilder();
            String segment = uri.getLastPathSegment();
            tail.append(segment != null ? segment : uri);
            if (uri.isHierarchical()) {
                appendQueryParam(tail, uri, "itag");
                appendQueryParam(tail, uri, "range");
                appendQueryParam(tail, uri, "sq");
                appendQueryParam(tail, uri, "rn");
            }
            return tail.length() <= 80 ? tail.toString() : tail.substring(0, 80);
        }

        private static void appendQueryParam(StringBuilder tail, android.net.Uri uri, String name) {
            String value = uri.getQueryParameter(name);
            if (value != null) {
                tail.append(' ').append(name).append('=').append(value);
            }
        }

        private static String safeRequestFingerprint(
                androidx.media3.exoplayer.source.LoadEventInfo info) {
            android.net.Uri uri = info.uri;
            StringBuilder result = new StringBuilder("host=").append(uri.getHost())
                    .append(" req=").append(info.dataSpec.position).append('+').append(info.dataSpec.length);
            appendQueryParam(result, uri, "itag");
            appendQueryParam(result, uri, "c");
            appendQueryParam(result, uri, "cver");
            appendQueryParam(result, uri, "range");
            String expire = uri.getQueryParameter("expire");
            if (expire != null) {
                try {
                    long remaining = Long.parseLong(expire) - System.currentTimeMillis() / 1_000L;
                    result.append(" expireInSec=").append(remaining);
                } catch (NumberFormatException ignored) {
                    result.append(" expire=invalid");
                }
            }
            result.append(" ipBound=").append(uri.getQueryParameter("ip") != null ? 'y' : 'n')
                    .append(" pot=").append(uri.getQueryParameter("pot") != null ? 'y' : 'n');
            return result.toString();
        }

        @Nullable
        private static androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                findInvalidResponseCode(Throwable error) {
            for (Throwable e = error; e != null; e = e.getCause()) {
                if (e instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    return (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) e;
                }
            }
            return null;
        }

        /** Body bytes as one logcat-safe line: printable ASCII kept, everything else becomes '.'. */
        private static String printable(byte[] body, int max) {
            int n = Math.min(body.length, max);
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                char c = (char) (body[i] & 0xFF);
                sb.append(c >= 0x20 && c < 0x7F ? c : '.');
            }
            if (body.length > max) {
                sb.append("...");
            }
            return sb.toString();
        }

        private static String fingerprint(byte[] value) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value);
                StringBuilder result = new StringBuilder(10);
                for (int i = 0; i < 5; i++) {
                    result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
                }
                return result.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                return Integer.toHexString(java.util.Arrays.hashCode(value));
            }
        }
    }
}
