package com.newtube.mobile.ui.browse;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.newtube.mobile.downloads.DownloadItem;
import com.newtube.mobile.downloads.DownloadMenu;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.downloads.DownloadsBridge;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.casting.CastPickerLauncher;
import com.newtube.mobile.casting.CastSessionManager;
import com.newtube.mobile.casting.CastTarget;
import com.newtube.mobile.casting.CastVolumeKeys;
import com.newtube.mobile.ui.common.FeedCache;
import com.newtube.mobile.ui.common.FeedSwapWarmup;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.common.SkeletonReveal;
import com.newtube.mobile.ui.update.MobileUpdateActivity;
import com.newtube.mobile.update.AppUpdates;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.PlayerGesturePrefs;
import com.newtube.mobile.ui.playback.SystemPipBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch Home shell - Wave 1 vertical slice.
 *
 * Drives the existing {@link BrowsePresenter} exactly like the TV
 * {@code BrowseFragment} does (setView/onViewInitialized + the
 * onSectionFocused/onVideoItemClicked/onScrollEnd input contract), but renders with a
 * {@link BottomNavigationView} for sections (in place of the Leanback headers column)
 * and a flat {@link RecyclerView} grid for the selected section's videos (in place of
 * nested Leanback rows/PageRow fragments - row/shorts/multi-grid layouts are a later
 * wave per ROADMAP.md Wave 2).
 *
 * {@code BottomNavigationView} hard-caps at 5 items, but {@code BrowsePresenter} can
 * deliver 10+ sections (Home, Trending, Subscriptions, History, Music, Gaming, News,
 * Playlists, Settings, ...). The bar shows only the curated
 * {@link #PREFERRED_SECTION_IDS} plus the synthetic You tab - no backfill; every other
 * delivered section (and Settings, and the account entry) is reachable through the You
 * tab's list instead (YouTube's "You" model - there is no drawer).
 *
 * Wave 2: tapping a card routes through {@link BrowsePresenter#onVideoItemClicked} into
 * the real touch player ({@code MobilePlaybackActivity}).
 */
public class MobileBrowseActivity extends MobileActivity
        implements BrowseView, MiniPlayerBridge.MiniHost {
    /** BottomNavigationView item ids must be non-zero; BrowseSection ids start at 0. */
    private static final int ITEM_ID_OFFSET = 1_000_000;
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;
    /**
     * NEWTUBE(lazy-home): Home's next section page is requested when the last visible card is this
     * close to the end (~6 portrait screens of full-width cards) - far enough ahead that a page
     * (one /browse, ~0.2-0.5 s) lands before the reader reaches the end. See HomeSectionPacer.
     */
    private static final int NEAR_END_LOOKAHEAD_ITEMS = 16;
    /** BottomNavigationView hard-caps at this many items. */
    private static final int MAX_NAV_ITEMS = 5;
    /**
     * Menu item id of the synthetic "You" tab (account + extra sections + settings). Far above
     * {@link #ITEM_ID_OFFSET} + any real section id, so it can never collide with one.
     */
    private static final int YOU_ITEM_ID = 2_000_000;
    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    /** Intent action (download notifications) that lands on the Downloads tab. */
    public static final String ACTION_OPEN_DOWNLOADS = "com.newtube.mobile.OPEN_DOWNLOADS";

    /**
     * Preferred bottom-nav sections, in priority order. Matched primarily by
     * {@link BrowseSection#getId()} (stable {@link MediaGroup} TYPE_* constants in
     * this codebase); the parallel {@link #PREFERRED_SECTION_TITLE_RES} array is a
     * title-string fallback in case a section ever arrives with a non-standard id.
     */
    private static final int[] PREFERRED_SECTION_IDS = {
            MediaGroup.TYPE_HOME,
            MediaGroup.TYPE_SUBSCRIPTIONS,
            MediaGroup.TYPE_HISTORY,
            VideoDownloads.SECTION_ID,
    };
    private static final int[] PREFERRED_SECTION_TITLE_RES = {
            R.string.header_home,
            R.string.header_subscriptions,
            R.string.header_history,
            R.string.header_downloads,
    };

    private BrowsePresenter mPresenter;

    private RecyclerView mContentGrid;
    private SwipeRefreshLayout mContentSwipe;
    private View mFeedSkeleton;
    /** NEWTUBE(motion): last session's cards for the current section, shown only if its load fails. */
    @androidx.annotation.Nullable
    private List<Video> mLastSessionFallback;
    /** NEWTUBE(motion): a reload's skeleton hid the grid (showReloadSkeleton). */
    private boolean mGridHiddenForSkeleton;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private BottomNavigationView mBottomNav;
    // "You" tab panel (account header + grouped section rows + Settings row; replaces the drawer).
    private View mYouPanel;
    private LinearLayout mYouRows;
    /** You-tab account header views (sign-in entry / current account name+email+avatar). */
    private TextView mYouAccountText;
    private TextView mYouAccountSub;
    private ImageView mYouAvatar;
    /** True while the You panel covers the content grid (the You tab is selected). */
    private boolean mYouShowing;
    /** True when the current grid section was opened from a You-panel row (back returns to You). */
    private boolean mSectionFromYou;
    private BrowseTopBar mTopBar;
    private View mErrorContainer;
    private ImageView mErrorIcon;
    private TextView mErrorMessage;
    private MaterialButton mErrorAction;
    private ImageButton mSearchButton;
    private ImageButton mCastButton;
    /** Process-wide cast session singleton; Browse only reads state + opens the picker. */
    private CastSessionManager mCastSessionManager;

    // Floating in-app mini-player (YouTube-style video-only card over the grid's bottom-right
    // corner; renders the playback activity's live player after a swipe-down minimize - see
    // MiniPlayerBridge).
    private View mMiniPlayerBar;
    private FrameLayout mMiniPlayerFrame;
    private ImageView mMiniFreeze;
    private TextureView mMiniTexture;
    private ImageButton mMiniPlayPause;
    private ProgressBar mMiniProgress;
    /** 500ms UI ticker while the bar is visible: progress line, play/pause icon, liveness check. */
    private final Runnable mMiniPlayerTick = this::onMiniPlayerTick;
    private static final long MINI_TICK_MS = 500;
    /** NEWTUBE(motion): the longest the expanding player waits for this card to fold. */
    private static final long MINI_FOLD_WAIT_MS = 50;
    /** NEWTUBE(motion): the card was pre-drawn for a minimize; its buttons fade in once it is ours. */
    private boolean mMiniButtonsFadePending;
    private final MiniPlayerBridge.CardFold mMiniCardFold = onFolded -> {
        foldMiniCard();
        if (onFolded != null) {
            FrameGate.afterNextFrame(mMiniPlayerBar.getRootView(), MINI_FOLD_WAIT_MS, onFolded);
        }
    };

    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<Video> mCurrentVideos = new ArrayList<>();
    private int mCurrentSectionId = -1;
    private boolean mProgressShowing;
    private boolean mSuppressNavCallback;
    private int mLastPaginationTriggerCount = -1;
    private int mLastNearEndTriggerCount = -1;
    /**
     * The grid is painting a stale {@link FeedCache} snapshot while the presenter refetches the
     * section. While set, the presenter's clear-before-load empty REPLACE is skipped (it would
     * blank the snapshot), and the first fresh group swaps the whole list instead of appending
     * below the stale items.
     */
    private boolean mAwaitingFreshContent;
    /**
     * NEWTUBE(feed-swap): the stale -> fresh swap in flight while the fresh first screen's pictures
     * warm up (see {@link #submitFeed}). While it is pending, grid submissions wait for it - it
     * submits the newest {@link #mCurrentVideos} when it lands.
     */
    private FeedSwapWarmup mFeedSwap;
    /** NEWTUBE(feed-swap): the grid's item animator, parked while a swap is laid out. */
    private RecyclerView.ItemAnimator mParkedItemAnimator;
    private boolean mFeedSwapPinTop;
    private final Runnable mRestoreItemAnimator = this::restoreItemAnimator;

    @Override
    protected boolean shouldInsetContentForNavigationBar() {
        // BottomNavigationView paints through the gesture area and applies that inset internally.
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Transitions: same task as the player now (singleTop + reorder, see the manifest note).
        // During interactive minimize this already-rendered Activity remains visible through the
        // translucent player. The final zero-duration reorder only hands the live texture to the
        // mini card; ordinary navigation still uses BrowseWindowAnimation's quick fade.

        setContentView(R.layout.activity_mobile_browse);

        registerBackHandler(this::handleBack);

        bindViews();
        setupContentGrid();
        setupBottomNav();
        setupYouPanel();
        setupErrorAction();
        setupSearchButton();
        setupCastButton();

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
        // The presenter just reported every section; build the bar ONCE, now, so the first
        // measure already has its tabs (see rebuildNavigation).
        flushNavRebuild();

        // NEWTUBE(downloads): keep the Downloads grid live (progress badges, finished files)
        // while it is the section on screen; see onDownloadsChanged.
        DownloadRegistry.instance(this).addListener(mDownloadsListener);
        handleOpenDownloads(getIntent());

        // NEWTUBE(update-flow): a known update puts a dot on the You tab and a row at the top of
        // its list; the first launch after an update says so (once the feed has had its moment).
        AppUpdates.instance(this).addListener(mUpdatesListener);
        refreshUpdateBadge();
        mBottomNav.postDelayed(mAnnounceUpdateInstalled, UPDATED_NOTICE_DELAY_MS);

        // Android 13+ (targetSdk 35): POST_NOTIFICATIONS is a runtime permission - without it
        // the media-playback notification never shows on a fresh install. Ask plainly on every
        // cold start until granted; the framework itself stops showing the dialog after two
        // denials, so no extra bookkeeping (and no custom rationale UI) is needed.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void bindViews() {
        mContentGrid = findViewById(R.id.mobile_content_grid);
        mContentSwipe = findViewById(R.id.mobile_content_swipe);
        mFeedSkeleton = findViewById(R.id.mobile_feed_skeleton);
        com.newtube.mobile.ui.common.MobileGrid.setupFeedSkeleton(this, (android.view.ViewGroup) mFeedSkeleton);
        setupSwipeRefresh();
        mBottomNav = findViewById(R.id.mobile_bottom_nav);
        mYouPanel = findViewById(R.id.mobile_you_panel);
        mYouRows = findViewById(R.id.mobile_you_rows);
        mYouAccountText = findViewById(R.id.mobile_you_account_text);
        mYouAccountSub = findViewById(R.id.mobile_you_account_sub);
        mYouAvatar = findViewById(R.id.mobile_you_avatar);

        mErrorContainer = findViewById(R.id.mobile_error_container);
        mErrorIcon = findViewById(R.id.mobile_error_icon);
        mErrorMessage = findViewById(R.id.mobile_error_message);
        mErrorAction = findViewById(R.id.mobile_error_action);
        mSearchButton = findViewById(R.id.mobile_search_button);
        mCastButton = findViewById(R.id.mobile_cast_button);
        mTopBar = new BrowseTopBar(this, () -> getOnBackPressedDispatcher().onBackPressed());

        mMiniPlayerBar = findViewById(R.id.mobile_mini_player);
        mMiniPlayerFrame = findViewById(R.id.mobile_mini_player_frame);
        mMiniFreeze = findViewById(R.id.mobile_mini_freeze);
        mMiniPlayPause = findViewById(R.id.mobile_mini_play_pause);
        mMiniProgress = findViewById(R.id.mobile_mini_progress);
        setupMiniPlayerBar();
        // NEWTUBE(mini-inset): the last card can scroll clear of the docked mini-player.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(mMiniPlayerBar, mContentGrid);
    }

    private void setupSwipeRefresh() {
        mContentSwipe.setColorSchemeColors(getColorInt(R.color.mobile_color_on_surface));
        mContentSwipe.setProgressBackgroundColorSchemeColor(getColorInt(R.color.mobile_color_surface));
        mContentSwipe.setOnRefreshListener(() -> {
            if (mPresenter != null) {
                mPresenter.refresh(false);
            } else {
                mContentSwipe.setRefreshing(false);
            }
        });
    }

    private int getColorInt(int colorRes) {
        return getResources().getColor(colorRes);
    }

    /**
     * Loading skeleton: card ghosts under a sweeping shimmer (ShimmerLinearLayout) instead of a
     * naked spinner. Only ever shown over an EMPTY grid - a section repainted from
     * {@link FeedCache} keeps its content visible while refreshing. NEWTUBE(motion): it leaves by
     * fading out over the cards that replace it (a cut used to swap one for the other), and at
     * once when nothing replaces it (an error, an empty section).
     */
    private void setSkeletonVisible(boolean visible) {
        if (visible) {
            mSkeletonShows++;
            mFeedSkeleton.animate().cancel();
            mFeedSkeleton.setAlpha(1f);
            mFeedSkeleton.setVisibility(View.VISIBLE);
            return;
        }
        if (mGridHiddenForSkeleton) {
            mGridHiddenForSkeleton = false;
            mContentGrid.animate().cancel();
            mContentGrid.setAlpha(1f); // under the skeleton, which fades away over it
        }
        if (mFeedSkeleton.getVisibility() != View.VISIBLE) {
            return;
        }
        mFeedSkeleton.animate().cancel();
        if (mCurrentVideos.isEmpty()) {
            mFeedSkeleton.setVisibility(View.GONE);
            mFeedSkeleton.setAlpha(1f);
            return;
        }
        int shows = mSkeletonShows;
        SkeletonReveal.fadeOverCards(mFeedSkeleton, mContentGrid, () -> shows == mSkeletonShows, () -> {
            mFeedSkeleton.setVisibility(View.GONE);
            mFeedSkeleton.setAlpha(1f);
        });
    }

    /** Counts skeleton shows, so a reveal waiting for its cards yields to a newer load. */
    private int mSkeletonShows;

    // ---------------------------------------------------------------------------------
    // In-app mini-player bar
    // ---------------------------------------------------------------------------------

    private void setupMiniPlayerBar() {
        // Tap the video (anywhere but the overlay buttons) = expand back to the watch screen.
        // The card keeps rendering until our onPause detaches it (hideMiniPlayer there) - the
        // player's onResume then re-parents the session texture back. Detaching HERE would blank
        // the card for the whole activity-switch latency.
        View.OnClickListener expand = v -> {
            if (!mMiniClosing && MiniPlayerBridge.isActive()) { // not while a closed card leaves
                MiniPlayerBridge.expand(this);
            }
        };
        mMiniPlayerBar.setOnClickListener(expand);
        mMiniPlayerFrame.setOnClickListener(expand);

        mMiniPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updateMiniPlayPauseIcon(player);
            }
        });

        findViewById(R.id.mobile_mini_close).setOnClickListener(v -> closeMiniPlayer());
        // NEWTUBE(motion): or swipe the card away sideways.
        mMiniSwipe = com.newtube.mobile.ui.playback.MiniCardSwipe.attach(mMiniPlayerBar,
                new com.newtube.mobile.ui.playback.MiniCardSwipe.Callback() {
                    @Override
                    public boolean canSwipe() {
                        return !mMiniClosing && MiniPlayerBridge.isActive();
                    }

                    @Override
                    public void onSwipedAway(float toTranslationX, long durationMs) {
                        closeMiniPlayer(toTranslationX, durationMs);
                    }
                }, mMiniPlayerFrame, mMiniPlayerBar);
    }

    /**
     * NEWTUBE(motion): X - the sound stops at once and the card, frozen on its last frame, shrinks
     * and fades away instead of vanishing in one frame. The session itself closes when the card is
     * gone: finishing the hidden player blocks this main thread for ~60 ms (Pixel 9), which ate
     * the whole animation when it ran first.
     */
    private boolean mMiniClosing;
    private float mMiniVolumeBeforeClose = 1f;
    @androidx.annotation.Nullable
    private com.newtube.mobile.ui.playback.MiniCardSwipe mMiniSwipe;

    private void closeMiniPlayer() {
        closeMiniPlayer(null, 0);
    }

    /** {@code flyToX}: swiped away - the card keeps going off that side instead of shrinking. */
    private void closeMiniPlayer(@androidx.annotation.Nullable Float flyToX, long flyMs) {
        if (mMiniClosing) {
            return;
        }
        mMiniClosing = true;
        if (MiniPlayerBridge.isActive()) {
            mMiniVolumeBeforeClose = MiniPlayerBridge.getVolume();
            MiniPlayerBridge.setVolume(0f); // released with the session right after
        }
        MiniPlayerBridge.setClosing(this::abortMiniClose);
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        detachMiniTexture();
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel();
        android.view.ViewPropertyAnimator exit = flyToX != null
                ? mMiniPlayerBar.animate().translationX(flyToX).alpha(0f).setDuration(flyMs)
                        .setInterpolator(new android.view.animation.LinearInterpolator())
                : mMiniPlayerBar.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                        .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        exit.withLayer().withEndAction(() -> {
                    if (mMiniClosing) {
                        finishMiniClose();
                    } else {
                        foldMiniCard(); // aborted: the player took the session back; only the card goes
                    }
                }).start();
    }

    /** The end of closeMiniPlayer; also run at once by anything that would re-show the card. */
    private void finishMiniClose() {
        if (!mMiniClosing) {
            return;
        }
        mMiniClosing = false;
        MiniPlayerBridge.setClosing(null);
        foldMiniCard();
        MiniPlayerBridge.close();
    }

    /** The session was handed a new video while the card left: it lives on; the card still goes. */
    private void abortMiniClose() {
        if (!mMiniClosing) {
            return;
        }
        mMiniClosing = false;
        MiniPlayerBridge.setVolume(mMiniVolumeBeforeClose);
    }

    /** Show the card and adopt the live session texture if a mini session is active. */
    private void syncMiniPlayer() {
        finishMiniClose(); // a card closing when this runs is closed, not brought back
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hideMiniPlayer();
            return;
        }

        // Keep the exact endpoint frame visible while the newly-created TextureView adopts the
        // session SurfaceTexture. Without this, releasing a fully-minimized drag briefly reveals
        // the card's black background/next decoder frame and reads as a small refresh.
        Bitmap entryStill = MiniPlayerBridge.takeMiniEntryStill();
        if (entryStill != null) {
            mMiniFreeze.setImageBitmap(entryStill);
            mMiniFreeze.setVisibility(View.VISIBLE);
        }

        MiniPlayerBridge.fitToVideo(mMiniPlayerFrame); // NEWTUBE(issue #9): letterbox, never stretch
        attachMiniTexture();
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel(); // a closing card (closeMiniPlayer) comes back whole
        mMiniPlayerBar.setAlpha(1f);
        mMiniPlayerBar.setScaleX(1f);
        mMiniPlayerBar.setScaleY(1f);
        mMiniPlayerBar.setTranslationX(0f);
        mMiniPlayerBar.setVisibility(View.VISIBLE);
        updateMiniPlayPauseIcon(player);

        Utils.removeCallbacks(mMiniPlayerTick);
        Utils.postDelayed(mMiniPlayerTick, MINI_TICK_MS);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return BrowseView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // The card floats above the 56dp Material bottom-nav row (see activity_mobile_browse.xml).
        return Math.round(56 * getResources().getDisplayMetrics().density);
    }

    /**
     * The translucent playback Activity is still on top when this runs. Make the endpoint card
     * visible now so Browse can draw it underneath before Android reorders Browse to the front.
     */
    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        if (isFinishing() || isDestroyed()) {
            return false;
        }
        syncMiniPlayer();
        if (mMiniPlayerBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        // NEWTUBE(motion): the landing video is a bare picture; the card's buttons fade in once
        // this screen is in front (onResume) rather than appearing with the card in one frame.
        setMiniButtonsAlpha(0f);
        mMiniButtonsFadePending = true;

        // A pair of frame callbacks only proves that time passed; it does not prove this paused
        // window submitted a buffer. Gate the reorder on an actual draw containing the card, then
        // wait one compositor frame before removing the playback window above it. The fallback
        // avoids stranding the player at the endpoint if an OEM suppresses draws on paused windows.
        ViewTreeObserver observer = mMiniPlayerBar.getViewTreeObserver();
        class DrawGate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDelivered;

            @Override
            public void onDraw() {
                mMiniPlayerBar.post(this);
            }

            @Override
            public void run() {
                if (mDelivered) {
                    return;
                }
                mDelivered = true;
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(this);
                }
                mMiniPlayerBar.postOnAnimation(onDrawn);
            }
        }
        DrawGate gate = new DrawGate();
        observer.addOnDrawListener(gate);
        mMiniPlayerBar.postDelayed(gate, 100);
        mMiniPlayerBar.invalidate();
        return true;
    }

    /**
     * Put a TextureView in the card and swap the player's session-long SurfaceTexture into it
     * (see MobilePlaybackActivity's persistent-surface docs). The playback activity detached its
     * own TextureView before launching us, so the texture has no other GL consumer by now. The
     * codec keeps decoding into it throughout - the card shows the LIVE stream with no surface
     * change on the player, hence no codec re-init and no playback freeze.
     */
    private void attachMiniTexture() {
        if (mMiniTexture == null) {
            // A FRESH TextureView per attach: a re-used one retains its previous SurfaceTexture
            // (the destroyed callback below returns false), which would silently be a stale,
            // already-released texture if a new playback session started since. hideMiniPlayer
            // nulls the field, so every show adopts the CURRENT session texture cleanly.
            final TextureView textureView = new TextureView(this);
            textureView.setOpaque(false); // NEWTUBE(texture-opaque): see MobilePlaybackActivity
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                "mini tex available t=" + android.os.SystemClock.uptimeMillis()
                                        + " session=" + (session != null)
                                        + " cb=" + width + "x" + height
                                        + " view=" + textureView.getWidth() + "x" + textureView.getHeight());
                    }
                    if (session != null && texture != session) {
                        textureView.setSurfaceTexture(session);
                        texture.release(); // the view-created texture is never used
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                "mini tex sizeChanged t=" + android.os.SystemClock.uptimeMillis()
                                        + " cb=" + width + "x" + height
                                        + " view=" + textureView.getWidth() + "x" + textureView.getHeight());
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    // Never release the session texture - the playback activity owns it. Only a
                    // view-created texture that was never swapped out may die here.
                    return texture != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    // First live frame in the card: lift the freeze frame.
                    if (mMiniFreeze != null && mMiniFreeze.getVisibility() == View.VISIBLE) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                    "mini tex first update t=" + android.os.SystemClock.uptimeMillis());
                        }
                        mMiniFreeze.setVisibility(View.GONE);
                        mMiniFreeze.setImageDrawable(null);
                    }
                }
            });
            mMiniTexture = textureView;
        }
        if (mMiniTexture.getParent() == null) {
            mMiniPlayerFrame.addView(mMiniTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * Freeze the current frame, detach the card's TextureView (freeing the session texture for
     * the expanding player - see the listener above) and fold the bar + ticker. Safe to call
     * repeatedly / when never shown.
     */
    private void hideMiniPlayer() {
        finishMiniClose();
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        detachMiniTexture();
        foldMiniCard();
    }

    /** Freeze the card's last frame and give the session texture back (the card stays as it is). */
    private void detachMiniTexture() {
        Utils.removeCallbacks(mMiniPlayerTick);
        if (mMiniTexture != null && mMiniTexture.getParent() != null) {
            if (mMiniTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap still = mMiniTexture.getBitmap();
                if (still != null) {
                    // Cover the card while detached AND hand the frame to the expanding player,
                    // which shows it over its own video box until the texture paints there.
                    mMiniFreeze.setImageBitmap(still);
                    mMiniFreeze.setVisibility(View.VISIBLE);
                    MiniPlayerBridge.setHandoffStill(still);
                }
            }
            // No throwaway-texture swap here: TextureView#setSurfaceTexture releases the
            // texture the view currently holds, which would destroy the session texture
            // (see MobilePlaybackActivity#detachVideoTexture).
            mMiniPlayerFrame.removeView(mMiniTexture);
            mMiniTexture = null; // next show builds a fresh view (see attachMiniTexture)
        }
    }

    /** NEWTUBE(haptics): see MiniCardSwipe#stop - before this host moves the card itself. */
    private void stopMiniSwipe() {
        if (mMiniSwipe != null) {
            mMiniSwipe.stop();
        }
    }

    private void foldMiniCard() {
        if (mMiniPlayerBar == null) {
            return;
        }
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel();
        mMiniPlayerBar.setVisibility(View.GONE);
        mMiniPlayerBar.setAlpha(1f);
        mMiniPlayerBar.setScaleX(1f);
        mMiniPlayerBar.setScaleY(1f);
        mMiniPlayerBar.setTranslationX(0f);
        mMiniButtonsFadePending = false;
        setMiniButtonsAlpha(1f);
    }

    private void setMiniButtonsAlpha(float alpha) {
        View close = findViewById(R.id.mobile_mini_close);
        for (View view : new View[] {mMiniPlayPause, close, mMiniProgress}) {
            if (view != null) {
                view.animate().cancel();
                view.setAlpha(alpha);
            }
        }
    }

    private void fadeInMiniButtons() {
        View close = findViewById(R.id.mobile_mini_close);
        for (View view : new View[] {mMiniPlayPause, close, mMiniProgress}) {
            if (view != null) {
                view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS)
                        .setInterpolator(Motion.STANDARD).start();
            }
        }
    }

    private void onMiniPlayerTick() {
        if (mMiniPlayerBar.getVisibility() != View.VISIBLE) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            // The hidden playback activity died (system kill / finished elsewhere): fold the bar.
            hideMiniPlayer();
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            mMiniProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }
        updateMiniPlayPauseIcon(player);
        MiniPlayerBridge.fitToVideo(mMiniPlayerFrame); // an autoplayed next video may have another shape
        Utils.postDelayed(mMiniPlayerTick, MINI_TICK_MS);
    }

    private void updateMiniPlayPauseIcon(ExoPlayer player) {
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mMiniPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
    }

    private void setupContentGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);
        mAdapter.setFeedLayout(com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.instance(this).getMobileFeedLayout());

        // Channel rows (rare on Home, possible in some sections) span the whole grid width
        // when landscape/tablet layouts use 2+ columns.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.isFullSpan(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mContentGrid.setLayoutManager(mLayoutManager);
        mContentGrid.setAdapter(mAdapter);
        // SCROLL-JANK FIX: the grid's own bounds never depend on item content (cards size themselves
        // to the fixed column width), so skip the full requestLayout on every adapter change; and keep
        // more offscreen holders around (default 2) so a fling-back rebinds/redecodes far fewer cards.
        mContentGrid.setHasFixedSize(true);
        mContentGrid.setItemViewCacheSize(8);
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mContentGrid, mAdapter);
        mContentGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy < 0) {
                    // NEWTUBE(shelf-tail): scrolling back up re-arms the near-end report, so a
                    // reader at a stalled end (a failed or refused page, nothing new to scroll to)
                    // gets another try by scrolling down again - one per gesture, never a loop.
                    mLastNearEndTriggerCount = -1;
                }
                maybeTriggerPagination();
            }
        });
    }

    /**
     * Search entry point (Wave 4b). Mirrors the gear button's "drive the presenter" approach
     * (the TV Home does the exact same thing - {@code BrowseFragment} wires its search affordance
     * to {@code SearchPresenter.instance(ctx).startSearch(null)}): the presenter calls
     * {@code ViewManager.startView(SearchView.class)} - now mapped to {@code MobileSearchActivity}
     * (see {@link com.newtube.mobile.MobileMainApplication}) - then drives the freshly-created
     * touch Search view. Passing {@code null} opens an empty search field (no pre-filled query).
     */
    private void setupSearchButton() {
        mSearchButton.setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
    }

    // ---------------------------------------------------------------------------------
    // Casting: top-bar entry point (same flow as the player's icon) + connected-state tint.
    //
    // Connecting from here with NO video playing is fine: the session comes up (foreground
    // service + notification), nothing plays yet, and the FIRST video opened afterwards is
    // claimed by MobilePlaybackActivity's setVideo hook (maybeRouteVideoToCast) - it loads on
    // the TV while the local player stays paused underneath.
    // ---------------------------------------------------------------------------------

    private void setupCastButton() {
        // CastPickerLauncher owns the Android 16+ ACCESS_LOCAL_NETWORK gate (shared with the
        // player); Browse has no immersive window, so the plain presenter is enough.
        mCastButton.setOnClickListener(v -> CastPickerLauncher.open(this, CastPickerLauncher::presentPlain));

        mCastSessionManager = CastSessionManager.instance(this);
        mCastSessionManager.addListener(mCastListener);
        updateCastIconTint();
    }

    /** Mirrors the player's registration pattern: listen for the activity's whole lifetime. */
    private final CastSessionManager.Listener mCastListener = new CastSessionManager.Listener() {
        @Override
        public void onCastSessionStarted(CastTarget target) {
            updateCastIconTint();
        }

        @Override
        public void onCastSessionState(String videoId, long positionMs, long durationMs, boolean playing) {
            // Browse has no transport UI; only the connected/idle tint matters here.
        }

        @Override
        public void onCastSessionEnded(String reason) {
            updateCastIconTint();
        }

        @Override
        public void onCastConnectingChanged(boolean connecting) {
            updateCastIconTint();
        }
    };

    /** Guards against restarting the connecting animation on every listener event. */
    private boolean mCastIconAnimating;

    /**
     * Official-app visual states for the cast icon: while a session is being established (picker
     * tap -> connected takes seconds; the auto-fallback's mdx resolve up to 15s) the glyph's wifi
     * arcs pulse (animation-list swaps the icon); accent tint once connected; stock white idle.
     */
    private void updateCastIconTint() {
        if (mCastButton == null) {
            return;
        }
        boolean connecting = mCastSessionManager != null && mCastSessionManager.isConnecting();
        if (connecting != mCastIconAnimating) {
            mCastIconAnimating = connecting;
            mCastButton.setImageResource(connecting
                    ? R.drawable.ic_mobile_cast_connecting : R.drawable.ic_mobile_cast);
            Drawable drawable = mCastButton.getDrawable();
            if (connecting && drawable instanceof AnimationDrawable) {
                ((AnimationDrawable) drawable).start();
            }
        }
        if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
            mCastButton.setColorFilter(getColorInt(R.color.mobile_color_cast_active));
        } else {
            mCastButton.clearColorFilter();
        }
    }

    /** Hardware volume keys drive the TV while casting; everything else falls through. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (CastVolumeKeys.onDispatchKeyEvent(this, event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Cast picker's local-network gate (the POST_NOTIFICATIONS request needs no follow-up).
        CastPickerLauncher.handlePermissionResult(this, requestCode, CastPickerLauncher::presentPlain);
    }

    /**
     * Settings entry point: the phone Settings screen (issue #2). Until 1.14 this rendered
     * AppDataSourceManager's TV categories through the AppDialog renderer; the tree now lives in
     * {@link com.newtube.mobile.ui.settings.SettingsPages}.
     */
    private void openSettings() {
        startActivity(com.newtube.mobile.ui.settings.MobileSettingsActivity.intent(this, null));
    }

    private void setupBottomNav() {
        mBottomNav.setOnItemSelectedListener(item -> {
            if (!mSuppressNavCallback) {
                onNavItemChosen(item.getItemId());
            }
            return true;
        });
        // NEWTUBE(motion): tapping the tab you are on glides a scrolled feed back to the top, like
        // YouTube; at the top it does what it always did (repaint + refresh the section).
        mBottomNav.setOnItemReselectedListener(item -> {
            if (mSuppressNavCallback) {
                return;
            }
            if (item.getItemId() != YOU_ITEM_ID && mContentGrid != null
                    && mContentGrid.getVisibility() == View.VISIBLE && mContentGrid.canScrollVertically(-1)) {
                smoothScrollGridToTop();
            } else {
                onNavItemChosen(item.getItemId());
            }
        });
    }

    private void onNavItemChosen(int itemId) {
        if (itemId == YOU_ITEM_ID) {
            showYouPanel();
        } else {
            mSectionFromYou = false;
            hideYouPanel();
            onSectionChosen(itemId - ITEM_ID_OFFSET);
        }
    }

    /** A long way down, jump most of it first: the glide covers the last few rows only. */
    private void smoothScrollGridToTop() {
        RecyclerView.LayoutManager layout = mContentGrid.getLayoutManager();
        if (layout instanceof LinearLayoutManager) {
            int first = ((LinearLayoutManager) layout).findFirstVisibleItemPosition();
            int jumpTo = 6 * Math.max(1, layout instanceof GridLayoutManager
                    ? ((GridLayoutManager) layout).getSpanCount() : 1);
            if (first > jumpTo) {
                mContentGrid.scrollToPosition(jumpTo);
            }
        }
        mContentGrid.smoothScrollToPosition(0);
    }

    // ---------------------------------------------------------------------------------
    // "You" tab: the YouTube-style personal surface that replaced the navigation drawer
    // and the top-bar settings icon. Account row on top, then a row for every enabled
    // BrowsePresenter section that didn't make the bottom nav (Playlists, Music, ...),
    // then Settings. Section rows route through the same onSectionChosen() path the nav
    // uses; back from such a section returns to the panel (mSectionFromYou).
    // ---------------------------------------------------------------------------------

    private void setupYouPanel() {
        // Account row: the one always-visible sign-in entry point (the other is the signed-out
        // Subscriptions/Playlists "Sign in" button). AccountsSheet routes: no stored accounts
        // -> device-code sign-in; otherwise the native switch/add/sign-out sheet (also when
        // browsing signed-out with stored accounts, so they stay re-selectable).
        View accountRow = findViewById(R.id.mobile_you_account_row);
        accountRow.setOnClickListener(v -> AccountsSheet.show(this, this::updateAccountRow));
    }

    /** Show the You panel over the content area (the panel is opaque; the grid stays put). */
    private void showYouPanel() {
        mYouShowing = true;
        mSectionFromYou = false;
        rebuildYouRows();
        updateAccountRow();
        mContentSwipe.setRefreshing(false);
        mYouPanel.setVisibility(View.VISIBLE);
        onYouPanelToggled();
    }

    private void hideYouPanel() {
        mYouShowing = false;
        mYouPanel.setVisibility(View.GONE);
        onYouPanelToggled();
    }

    /**
     * NEWTUBE(you-subscreen): state that follows the You panel and the section-from-You flag.
     * (1) The feed stays laid out under the opaque panel, so TalkBack kept walking its cards; hide
     * it (and its skeleton/error overlays) from accessibility while the panel covers it. (2) The
     * top bar: a section opened from a You row - one without a bottom-nav tab of its own - is a
     * You sub-screen with a back arrow and its name.
     */
    private void onYouPanelToggled() {
        int a11y = mYouShowing
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        mContentSwipe.setImportantForAccessibility(a11y);
        mFeedSkeleton.setImportantForAccessibility(a11y);
        mErrorContainer.setImportantForAccessibility(a11y);

        boolean subScreen = mSectionFromYou && !mYouShowing
                && mBottomNav.getMenu().findItem(toMenuItemId(mCurrentSectionId)) == null;
        mTopBar.show(subScreen, getCurrentSectionTitle());
    }

    /**
     * Personal-content section ids (the user's own stuff). These lead the You list, official-app
     * style; every other non-nav section is a discovery feed and goes under the "Explore" label.
     */
    private static boolean isPersonalSection(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_USER_PLAYLISTS:
            case MediaGroup.TYPE_MY_VIDEOS:
            case MediaGroup.TYPE_CHANNEL_UPLOADS:
            case MediaGroup.TYPE_PLAYBACK_QUEUE:
            case MediaGroup.TYPE_NOTIFICATIONS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Rebuild the panel's section list, grouped like the official You page: the user's own
     * content first (Playlists, My videos, Channels, ...), then the discovery feeds under an
     * "Explore" label, then Settings behind a divider. Sections shown in the bottom nav (and
     * Settings/Shorts) are excluded - Settings gets its own trailing row, and NewTube has no
     * Shorts (see ShortsFilter). Long-press on a section row opens the section-management menu
     * (the old drawer rows' "..." overflow).
     */
    private void rebuildYouRows() {
        mYouRows.removeAllViews();
        mYouUpdateRow = null;

        if (AppUpdates.instance(this).hasUpdate()) {
            addYouUpdateRow();
        }

        List<BrowseSection> navSections = selectNavSections();
        List<BrowseSection> personal = new ArrayList<>();
        List<BrowseSection> pinned = new ArrayList<>();
        List<BrowseSection> explore = new ArrayList<>();

        for (BrowseSection section : mSections) {
            if (!section.isEnabled()
                    || navSections.contains(section)
                    || section.getId() == MediaGroup.TYPE_SETTINGS
                    || section.getId() == MediaGroup.TYPE_SHORTS) {
                continue;
            }
            // NEWTUBE(menu): channels/playlists pinned from a card menu ("Pin to You") carry the
            // pinned Video as their data; they get their own group instead of mixing into Explore.
            if (section.getData() instanceof Video) {
                pinned.add(section);
                continue;
            }
            (isPersonalSection(section.getId()) ? personal : explore).add(section);
        }

        for (BrowseSection section : personal) {
            addYouSectionRow(section);
        }

        if (!pinned.isEmpty()) {
            addYouGroupLabel(getString(R.string.mobile_you_pinned));
            for (BrowseSection section : pinned) {
                addYouSectionRow(section);
            }
        }

        if (!explore.isEmpty()) {
            addYouGroupLabel(getString(R.string.mobile_you_explore));
            for (BrowseSection section : explore) {
                addYouSectionRow(section);
            }
        }

        addYouDivider();
        addYouRow(R.drawable.ic_mobile_settings, getString(R.string.header_settings),
                this::openSettings);
    }

    private void addYouSectionRow(BrowseSection section) {
        int sectionId = section.getId();
        View row = addYouRow(section.getResId(), section.getTitle(), () -> {
            mSectionFromYou = true;
            hideYouPanel();
            onSectionChosen(sectionId);
            onYouPanelToggled(); // now that the chosen section is current: its name in the top bar
        });
        row.setOnLongClickListener(v -> {
            if (mPresenter != null) {
                mPresenter.onSectionLongPressed(sectionId);
            }
            return true;
        });
    }

    /** Small secondary-color group label, official-You-page style. */
    // ---------------------------------------------------------------------------------
    // Updates (NEWTUBE update-flow)
    // ---------------------------------------------------------------------------------

    private static final long UPDATED_NOTICE_DELAY_MS = 1_000;
    private final AppUpdates.Listener mUpdatesListener = this::onUpdatesChanged;
    /** Home back in front for a few seconds: the quiet update check, if the last one is old. */
    private final Runnable mUpdateCheckIfDue = () -> AppUpdates.instance(this).checkIfDue("resume");
    /** After the first paint of a return, and not for a screen the user only passes through. */
    private static final long UPDATE_CHECK_DELAY_MS = 3_000;
    private final Runnable mAnnounceUpdateInstalled = this::announceUpdateInstalled;
    /** The You list's update row while it is built, so progress updates it in place. */
    @Nullable private View mYouUpdateRow;

    private void onUpdatesChanged() {
        refreshUpdateBadge();

        if (!mYouShowing) {
            return;
        }

        if (AppUpdates.instance(this).hasUpdate() != (mYouUpdateRow != null)) {
            rebuildYouRows();
        } else if (mYouUpdateRow != null) {
            bindYouUpdateRow(mYouUpdateRow); // a progress tick: no list rebuild
        }
    }

    /** A dot on the You tab while there is an update the user hasn't opened yet. */
    private void refreshUpdateBadge() {
        if (mBottomNav.getMenu().findItem(YOU_ITEM_ID) == null) {
            return;
        }

        if (AppUpdates.instance(this).hasUnseenUpdate()) {
            BadgeDrawable badge = mBottomNav.getOrCreateBadge(YOU_ITEM_ID);
            badge.setBackgroundColor(getColorInt(R.color.mobile_color_primary));
            badge.setContentDescriptionNumberless(getString(R.string.mobile_update_row_available));
            badge.setVisible(true);
        } else {
            mBottomNav.removeBadge(YOU_ITEM_ID);
        }
    }

    private void addYouUpdateRow() {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mobile_you_update_row, mYouRows, false);
        row.setOnClickListener(v -> MobileUpdateActivity.start(this));
        bindYouUpdateRow(row);
        mYouRows.addView(row);
        mYouUpdateRow = row;
        addYouDivider();
    }

    private void bindYouUpdateRow(View row) {
        AppUpdates updates = AppUpdates.instance(this);
        TextView title = row.findViewById(R.id.mobile_you_update_title);
        TextView subtitle = row.findViewById(R.id.mobile_you_update_subtitle);
        String version = updates.getUpdateVersionName();
        String appVersion = getString(R.string.mobile_update_app_version, version);

        switch (updates.getPhase()) {
            case DOWNLOADING:
                long total = updates.getDownloadTotal();
                title.setText(R.string.mobile_update_row_downloading);
                subtitle.setText(total > 0 ? getString(R.string.mobile_update_row_downloading_percent, version,
                        (int) Math.min(100, updates.getDownloadedBytes() * 100 / total)) : appVersion);
                break;
            case READY:
                title.setText(R.string.mobile_update_row_ready);
                subtitle.setText(appVersion);
                break;
            default:
                title.setText(R.string.mobile_update_row_available);
                subtitle.setText(appVersion);
                break;
        }
    }

    /** First launch after an update this app installed: confirm it, with the notes one tap away. */
    private void announceUpdateInstalled() {
        AppUpdates updates = AppUpdates.instance(this);

        if (isFinishing() || !updates.takeJustUpdated()) {
            return;
        }

        boolean hasNotes = !updates.getWhatsNew().isEmpty();
        MobileSnackbar.show(this, getString(R.string.mobile_update_updated, updates.getInstalledVersionName()),
                hasNotes ? getString(R.string.mobile_update_whats_new) : null,
                hasNotes ? () -> MobileUpdateActivity.startWhatsNew(this) : null, MobileSnackbar.NOTICE_DURATION_MS);
    }

    private void addYouGroupLabel(CharSequence text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColorInt(R.color.mobile_color_on_surface_secondary));
        label.setTextSize(14);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        int padV = Math.round(10 * getResources().getDisplayMetrics().density);
        label.setPadding(pad, padV, pad, padV / 2);
        mYouRows.addView(label);
    }

    private void addYouDivider() {
        View divider = new View(this);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        int margin = Math.round(20 * density);
        int marginV = Math.round(8 * density);
        lp.setMargins(margin, marginV, margin, marginV);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(getColorInt(R.color.mobile_color_divider));
        mYouRows.addView(divider);
    }

    private View addYouRow(int iconRes, CharSequence title, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mobile_you_row, mYouRows, false);

        ImageView icon = row.findViewById(R.id.mobile_you_row_icon);
        if (iconRes > 0) {
            icon.setImageResource(iconRes);
        }
        TextView label = row.findViewById(R.id.mobile_you_row_label);
        label.setText(title);

        row.setOnClickListener(v -> action.run());
        mYouRows.addView(row);
        return row;
    }

    /**
     * Single entry point for "user picked section X" - whether from the bottom nav, the
     * drawer, or (indirectly) the presenter's boot selection. Drives the standard
     * {@link BrowsePresenter#onSectionFocused} path and keeps both nav surfaces in sync.
     */
    private void onSectionChosen(int sectionId) {
        // The Settings section has no video-grid renderer on the mobile shell (its content
        // arrives as a SettingsGroup, which updateSection(SettingsGroup) intentionally
        // ignores). Route it to the working AppDialog settings screen instead of a blank grid.
        if (sectionId == MediaGroup.TYPE_SETTINGS) {
            syncNavHighlight(mCurrentSectionId); // keep highlight on the real current section
            openSettings();
            return;
        }

        boolean switched = mCurrentSectionId != sectionId;
        mCurrentSectionId = sectionId;
        paintCachedSnapshot(sectionId, switched);
        syncNavHighlight(sectionId);

        if (mPresenter != null) {
            mPresenter.onSectionFocused(sectionId);
        }
    }

    /**
     * Stale-while-revalidate: repaint the section's last-known content instantly (activity
     * recreation and section switches otherwise stare at a skeleton while the presenter
     * refetches - the single biggest "app feels slow" moment). The presenter's refetch is
     * already on its way; {@link #mAwaitingFreshContent} makes its result replace this.
     */
    private void paintCachedSnapshot(int sectionId, boolean switched) {
        // The error overlay (e.g. the previous section's sign-in gate) belongs to the section
        // that showed it - a switch repaints, so drop it here. updateSection can't be relied on
        // for this: a fresh-within-TTL section skips the refetch and never emits a group.
        hideError();
        cancelFeedSwap(); // NEWTUBE(feed-swap): the previous section's swap is moot now

        // Falls back to the persisted snapshot on the process's first paint of this section,
        // so even a cold start shows cards instead of the skeleton (display-only until the
        // refetch replaces it — see FeedCache class doc).
        List<Video> cached = sectionId == VideoDownloads.SECTION_ID ? null : FeedCache.getOrRestore(sectionId);
        // NEWTUBE(motion): last session's cards wait behind the skeleton (FeedCache
        // .isFromLastSession) - only a failed first load shows them (showError).
        mLastSessionFallback = null;
        if (cached != null && FeedCache.isFromLastSession(sectionId)) {
            mLastSessionFallback = cached;
            cached = null;
        }

        mCurrentVideos.clear();
        mAwaitingFreshContent = cached != null;
        if (cached != null) {
            mCurrentVideos.addAll(visibleFeedItems(cached));
        }

        mLastPaginationTriggerCount = -1;
        mLastNearEndTriggerCount = -1;
        mContentGrid.animate().cancel();
        mContentGrid.setAlpha(1f);
        if (switched) {
            // NEWTUBE(motion): another section is a new page, not an edit of the old one. Through
            // the item animator it played as a shuffle (Pixel 9, History -> Home: the old cards
            // faded out, the grid sat empty ~200 ms, then a card both lists shared slid up from the
            // bottom while the rest faded in - ~400 ms). The new list now replaces the old one in
            // one frame with the animator parked, pinned to the top, and fades in.
            parkItemAnimator();
            mSectionSwapPending = true;
            mAdapter.submitList(new ArrayList<>(mCurrentVideos), this::onSectionSwapCommitted);
        } else if (mSectionSwapPending) {
            // A re-paint landing before the switch's own commit supersedes it: it finishes the switch.
            mAdapter.submitList(new ArrayList<>(mCurrentVideos), this::onSectionSwapCommitted);
        } else {
            mAdapter.submitList(new ArrayList<>(mCurrentVideos));
        }
        if (!mCurrentVideos.isEmpty()) {
            setSkeletonVisible(false);
            mContentGrid.scrollToPosition(0);
            com.newtube.mobile.LaunchMilestones.onFeedSnapshotPainted(sectionId, mCurrentVideos.size());
        } else if (mLastSessionFallback != null) {
            setSkeletonVisible(true);
        }
    }

    /**
     * Highlight the given section's bottom-nav tab when it has one. A section opened from a
     * You-panel row has no tab of its own; the You tab simply stays selected (YouTube-style).
     */
    private void syncNavHighlight(int sectionId) {
        int itemId = toMenuItemId(sectionId);

        if (mBottomNav.getMenu().findItem(itemId) != null && mBottomNav.getSelectedItemId() != itemId) {
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(itemId);
            mSuppressNavCallback = false;
        }
    }

    private void setupErrorAction() {
        mErrorAction.setOnClickListener(v -> {
            // ErrorFragmentData instance is captured per-call in showError(); re-read it from the tag.
            Object data = mErrorAction.getTag();
            if (data instanceof ErrorFragmentData) {
                ((ErrorFragmentData) data).onAction();
            } else if (mPresenter != null) {
                // Offline empty state (see showError): the button is a plain retry. Pull-to-refresh
                // does the same thing, but it is invisible on a screen with no rows to pull.
                mPresenter.refresh(false);
            }
        });
    }

    private void onVideoClicked(Video video) {
        if (mPresenter == null) {
            return;
        }

        // NEWTUBE(downloads): a card that is still downloading (or failed) has no file to play
        // yet - its tap opens the download's own menu (cancel / retry / delete). A finished one
        // carries its local file and goes through the ordinary play route below.
        DownloadItem download = DownloadsBridge.instance(this).itemFor(video);
        if (download != null && !download.isDone()) {
            DownloadMenu.show(this, download);
            return;
        }

        mPresenter.onVideoItemSelected(video);

        // Cards without a videoId (playlists, mixes, channels) don't open the player - they kick
        // an async channel-rows fetch (VideoActionPresenter -> chooseChannelPresenter) that can
        // take seconds before any screen change, and this screen's showProgressBar deliberately
        // shows nothing over a non-empty grid - the tap read as dead. Use the swipe-refresh
        // spinner as tap feedback; it clears when the destination opens (onPause) or the fetch
        // ends/fails (LoadingManager -> showProgressBar(false)). Guarded on the routable shapes
        // so the "doesn't contain needed data" toast case can't leave it spinning.
        if (!video.hasVideo()
                && (video.hasChannel() || video.hasPlaylist() || video.hasNestedItems() || video.hasReloadPageKey())) {
            mContentSwipe.setRefreshing(true);
        }

        // Wave 2: real route. BrowsePresenter.onVideoItemClicked() -> VideoActionPresenter.apply()
        // -> PlaybackPresenter.openVideo() -> ViewManager.startView(PlaybackView.class) for plain
        // videos (now mapped to MobilePlaybackActivity - see MobileMainApplication); channel/
        // playlist items are routed elsewhere by the same presenter, which is fine here too.
        mPresenter.onVideoItemClicked(video);
    }

    /**
     * Wave 3: long-press = the touch equivalent of the TV D-pad OK-long-press context-menu
     * shortcut. {@code BrowsePresenter.onVideoItemLongClicked()} builds the "..." menu
     * (add to playlist / share / subscribe / etc.) via {@code VideoMenuPresenter} and shows it
     * through {@code AppDialogPresenter} - now rendered by {@code MobileAppDialogActivity}.
     */
    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }

        // NEWTUBE(downloads): the Downloads card menu is about the file (play, share, delete),
        // not about the feed (not interested, block channel...).
        DownloadItem download = DownloadsBridge.instance(this).itemFor(video);
        if (download != null) {
            DownloadMenu.show(this, download);
            return true;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    private void maybeTriggerPagination() {
        if (mCurrentVideos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (com.newtube.mobile.ui.common.FeedRunway.isShort(lastVisible, itemCount, NEAR_END_LOOKAHEAD_ITEMS)
                && itemCount != mLastNearEndTriggerCount) {
            mLastNearEndTriggerCount = itemCount;
            mPresenter.onScrollNearEnd(mCurrentVideos.size());
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(mCurrentVideos.get(mCurrentVideos.size() - 1));
        }
    }

    /**
     * NEWTUBE(lazy-home): after every grid update, ask for more when fewer than a screen plus
     * {@link #NEAR_END_LOOKAHEAD_ITEMS} cards are left below the viewport - including an EMPTY grid.
     * The scroll listener alone cannot cover this: a page whose rows were all filtered out (Shorts,
     * channel shelves, duplicates) leaves the grid unchanged, so nothing scrolls and no new size is
     * ever reported, and the walk would sit waiting for a demand while usable sections exist.
     * Home's paced walk acts on it, and once a row section's section list is done, its shelf tail
     * (the next shelf page; the grid size tells it whether the last page added any card - see
     * BrowsePresenter.onScrollNearEnd).
     */
    private void checkFeedRunway() {
        // Posted, and once per burst: a page arrives as one updateSection per shelf, all inside
        // one main-thread message, and judging the grid after its FIRST shelf would ask for a
        // page the rest of this one is about to make unnecessary.
        if (!mRunwayCheckPosted) {
            mRunwayCheckPosted = true;
            mNavHandler.post(mRunwayCheck);
        }
    }

    private boolean mRunwayCheckPosted;
    private final Runnable mRunwayCheck = () -> {
        mRunwayCheckPosted = false;
        if (isDestroyed() || mPresenter == null || mYouShowing || isDownloadsSectionShowing()) {
            return;
        }
        // Before the new list is laid out this is the old layout's last card (or NO_POSITION = -1
        // for an empty grid): a lower bound of what will be visible, which is what the check needs.
        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        if (com.newtube.mobile.ui.common.FeedRunway.isShort(lastVisible, mCurrentVideos.size(), NEAR_END_LOOKAHEAD_ITEMS)) {
            mPresenter.onScrollNearEnd(mCurrentVideos.size());
        }
    };

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    private static int toMenuItemId(int sectionId) {
        return sectionId + ITEM_ID_OFFSET;
    }

    /**
     * Rebuild both nav surfaces (bottom nav = the main tabs + You; You panel = the rest).
     *
     * <p>NEWTUBE(startup): coalesced. BrowsePresenter.refreshSections() reports the sections one
     * by one - removeAllSections, then an addSection/removeSection per pinned section (~16 on a
     * default install) - all inside this Activity's onCreate, and the bar was rebuilt for each of
     * them. Material's NavigationBarMenuView rebuilds every tab view on each menu.add() and
     * discards its item-view pool whenever the item count changes, so one clear-and-refill of k
     * tabs inflates 1+2+...+k NavigationBarItemViews: 89 per cold start (Robolectric count of the
     * default section order) against 15 for a single rebuild. Now a burst of section
     * changes posts ONE rebuild with the final section list; onCreate flushes it synchronously
     * once the presenter has reported its sections, so the first frame has its tabs.</p>
     */
    private void rebuildNavigation() {
        if (mNavRebuildPosted) {
            return;
        }
        mNavRebuildPosted = true;
        mNavHandler.post(mNavRebuild);
    }

    /** Run a pending coalesced rebuild now instead of on the next loop. */
    private void flushNavRebuild() {
        if (mNavRebuildPosted) {
            mNavHandler.removeCallbacks(mNavRebuild);
            mNavRebuild.run();
        }
    }

    private final android.os.Handler mNavHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mNavRebuildPosted;
    private final Runnable mNavRebuild = () -> {
        mNavRebuildPosted = false;
        if (isDestroyed()) {
            return;
        }
        rebuildBottomNav();
        if (mYouShowing) {
            rebuildYouRows();
        }
        openPendingDownloads();
    };

    /**
     * What the bottom nav currently shows (ids, titles, icons, in order), so an unchanged section
     * list - e.g. BrowsePresenter.updateSections() after a profile/account refresh - does not
     * tear down and re-inflate the whole bar.
     */
    private String mNavSignature;

    // ---------------------------------------------------------------------------------
    // Downloads section
    // ---------------------------------------------------------------------------------

    /** A notification tap asked for the Downloads tab before the sections had arrived. */
    private boolean mPendingDownloadsOpen;

    private final DownloadRegistry.Listener mDownloadsListener = this::onDownloadsChanged;

    private void handleOpenDownloads(Intent intent) {
        if (intent == null || !ACTION_OPEN_DOWNLOADS.equals(intent.getAction())) {
            return;
        }
        intent.setAction(null); // consume: a rotation must not re-open the tab
        mPendingDownloadsOpen = true;
        openPendingDownloads();
    }

    private void openPendingDownloads() {
        if (!mPendingDownloadsOpen) {
            return;
        }
        for (BrowseSection section : mSections) {
            if (section.getId() == VideoDownloads.SECTION_ID) {
                mPendingDownloadsOpen = false;
                hideYouPanel();
                onSectionChosen(VideoDownloads.SECTION_ID);
                return;
            }
        }
    }

    private boolean isDownloadsSectionShowing() {
        return mCurrentSectionId == VideoDownloads.SECTION_ID && !mYouShowing;
    }

    /**
     * Registry change while the Downloads grid is on screen. Progress on the cards already
     * shown is an in-place sync (payloaded partial rebind, no thumbnail reload, no blink); a
     * download appearing or leaving the list needs the presenter to rebuild the grid.
     */
    private void onDownloadsChanged() {
        if (!isDownloadsSectionShowing() || mPresenter == null) {
            return;
        }

        List<DownloadItem> items = DownloadRegistry.instance(this).itemsNewestFirst();
        boolean sameSet = items.size() == mCurrentVideos.size();
        if (sameSet) {
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).id.equals(mCurrentVideos.get(i).downloadId)) {
                    sameSet = false;
                    break;
                }
            }
        }

        if (sameSet) {
            VideoGroup sync = VideoGroup.from(DownloadsBridge.instance(this).syncCards());
            sync.setAction(VideoGroup.ACTION_SYNC);
            updateSection(sync);
        } else {
            mPresenter.refresh(false);
        }
    }

    /** The Downloads tab with nothing in it: say what the tab is for instead of a blank grid. */
    private void showEmptyAfterRemoval() {
        setSkeletonVisible(false);
        mContentSwipe.setRefreshing(false);
        mContentGrid.setVisibility(View.GONE);
        mErrorContainer.setVisibility(View.VISIBLE);
        mErrorIcon.setVisibility(View.VISIBLE);
        mErrorMessage.setText(mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS
                ? R.string.mobile_empty_subscriptions : R.string.mobile_empty_generic);
        mErrorAction.setVisibility(View.GONE);
        mErrorAction.setTag(null);
    }

    private void showDownloadsEmptyState() {
        setSkeletonVisible(false);
        mContentSwipe.setRefreshing(false);
        mContentGrid.setVisibility(View.GONE);
        mErrorContainer.setVisibility(View.VISIBLE);
        mErrorIcon.setVisibility(View.VISIBLE);
        mErrorMessage.setText(R.string.mobile_downloads_empty);
        mErrorAction.setVisibility(View.GONE);
        mErrorAction.setTag(null);
    }

    private void rebuildBottomNav() {
        Menu menu = mBottomNav.getMenu();
        List<BrowseSection> navSections = selectNavSections();

        StringBuilder signature = new StringBuilder();
        for (BrowseSection section : navSections) {
            signature.append(toMenuItemId(section.getId())).append('\u0001')
                    .append(section.getTitle()).append('\u0001')
                    .append(navIconOrResFor(section)).append('\u0002');
        }
        String newSignature = signature.toString();

        if (newSignature.equals(mNavSignature) && menu.size() == navSections.size() + 1) {
            // Same tabs as on screen: only re-assert the highlight (the section may have moved).
            mSuppressNavCallback = true;
            reassertNavHighlight(menu, false);
            mSuppressNavCallback = false;
            return;
        }
        mNavSignature = newSignature;

        mSuppressNavCallback = true;

        menu.clear();

        for (int i = 0; i < navSections.size(); i++) {
            BrowseSection section = navSections.get(i);

            android.view.MenuItem item = menu.add(Menu.NONE, toMenuItemId(section.getId()), i, section.getTitle());

            int icon = navIconOrResFor(section);
            if (icon > 0) {
                item.setIcon(icon);
            }
        }

        // The synthetic You tab always sits last (account + extra sections + settings).
        menu.add(Menu.NONE, YOU_ITEM_ID, navSections.size(), R.string.mobile_nav_you)
                .setIcon(R.drawable.ic_nav_you);

        // Re-assert the highlight after clear()/add() wiped it, so the current tab stays lit.
        reassertNavHighlight(menu, true);

        mSuppressNavCallback = false;

        refreshUpdateBadge();

        // Long-press on a section tab opens the section-management menu (Refresh / Rename /
        // Move / Mark watched / Clear history, ...) - the touch equivalent of the TV D-pad
        // section long-press, formerly the drawer rows' "..." overflow. Item views exist only
        // after the menu inflates into the bar, hence the post.
        mBottomNav.post(() -> {
            for (BrowseSection section : navSections) {
                View itemView = mBottomNav.findViewById(toMenuItemId(section.getId()));
                if (itemView != null) {
                    int sectionId = section.getId();
                    itemView.setOnLongClickListener(v -> {
                        if (mPresenter != null) {
                            mPresenter.onSectionLongPressed(sectionId);
                        }
                        return true;
                    });
                }
            }
        });
    }

    /** Callers hold {@link #mSuppressNavCallback}. {@code force}: the menu was just rebuilt. */
    private void reassertNavHighlight(Menu menu, boolean force) {
        int itemId;
        if (mYouShowing) {
            itemId = YOU_ITEM_ID;
        } else if (mCurrentSectionId >= 0 && menu.findItem(toMenuItemId(mCurrentSectionId)) != null) {
            itemId = toMenuItemId(mCurrentSectionId);
        } else {
            return;
        }
        if (force || mBottomNav.getSelectedItemId() != itemId) {
            mBottomNav.setSelectedItemId(itemId);
        }
    }

    /** The icon a nav tab shows for this section: its outline/filled pair, else the stock icon. */
    private static int navIconOrResFor(BrowseSection section) {
        int navIcon = navIconFor(section.getId());
        return navIcon != 0 ? navIcon : Math.max(section.getResId(), 0);
    }

    /**
     * State-list icons (outline when idle, filled when selected - the YouTube bar's
     * active-tab signal, since both states tint plain white) for the curated bottom-nav
     * sections. Sections without a pair (drawer-only ones) keep their stock
     * {@link BrowseSection#getResId()} icon.
     */
    private static int navIconFor(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_HOME:
                return R.drawable.ic_nav_home;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.drawable.ic_nav_subscriptions;
            case MediaGroup.TYPE_HISTORY:
                return R.drawable.ic_nav_history;
            case VideoDownloads.SECTION_ID:
                return R.drawable.ic_nav_downloads;
            default:
                return 0;
        }
    }

    /**
     * The bottom nav shows ONLY the {@link #PREFERRED_SECTION_IDS} (in priority
     * order, when present and enabled) — no backfill from the other delivered
     * sections; everything else lives in the You panel. BrowsePresenter can deliver
     * many more sections, and the {@link BottomNavigationView} itself hard-caps at
     * {@link #MAX_NAV_ITEMS}.
     */
    private List<BrowseSection> selectNavSections() {
        List<BrowseSection> enabledSections = new ArrayList<>();

        for (BrowseSection section : mSections) {
            if (section.isEnabled() && section.getId() != MediaGroup.TYPE_SETTINGS) {
                enabledSections.add(section);
            }
        }

        List<BrowseSection> chosen = new ArrayList<>();

        for (int i = 0; i < PREFERRED_SECTION_IDS.length && chosen.size() < MAX_NAV_ITEMS; i++) {
            BrowseSection match = findPreferredSection(
                    enabledSections, chosen, PREFERRED_SECTION_IDS[i], PREFERRED_SECTION_TITLE_RES[i]);

            if (match != null) {
                chosen.add(match);
            }
        }

        return chosen;
    }

    /** Matches primarily by section id; falls back to a title-string match. */
    private BrowseSection findPreferredSection(
            List<BrowseSection> candidates, List<BrowseSection> alreadyChosen, int id, int titleResId) {
        String fallbackTitle = getString(titleResId);

        for (BrowseSection section : candidates) {
            if (alreadyChosen.contains(section)) {
                continue;
            }

            if (section.getId() == id || Helpers.equals(section.getTitle(), fallbackTitle)) {
                return section;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

        updateAccountRow();
        getWindow().getDecorView().postDelayed(mUpdateCheckIfDue, UPDATE_CHECK_DELAY_MS);
        // Cheap re-sync; listener callbacks already cover changes while resumed.
        updateCastIconTint();
        // Last-resumed host wins: while this screen is (or is about to be) the one under the
        // player, minimize docks its card here.
        MiniPlayerBridge.registerMiniHost(this);
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        syncMiniPlayer();
        if (mMiniButtonsFadePending) {
            mMiniButtonsFadePending = false;
            if (mMiniPlayerBar.getVisibility() == View.VISIBLE) {
                fadeInMiniButtons();
            } else {
                setMiniButtonsAlpha(1f);
            }
        }
        // Home may have been paused while the player crossed landscape/PiP configurations. Those
        // callbacks can arrive while DisplayMetrics still describe the player window, so always
        // reconcile the retained GridLayoutManager against Home's current configuration on resume.
        updateGridSpanCount(computeSpanCount());
    }

    /**
     * Launcher tap while the player floats in a pinned PiP task: expand the exact live player
     * instead of showing Browse under a stale PiP. Android may satisfy that tap by merely bringing
     * this existing Browse task to the front (no Splash onCreate/onNewIntent callback at all), so
     * a Browse lifecycle signal is the only reliable hook. It must be focus, NOT onResume: the
     * home-gesture auto-PiP briefly resumes Browse while the player is re-parented into its pinned
     * task (Android 16+), and restoring from there re-expands the player the instant it minimizes -
     * the app looked impossible to leave. Focus never lands on Browse during that hand-off (it goes
     * to the launcher), but a real reopen always gains it.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            SystemPipBridge.restoreFromLauncher(this);
        }
    }

    /**
     * You-tab account header: name + email + circular avatar when signed in (official-app
     * style), the "Sign in" entry otherwise.
     */
    private void updateAccountRow() {
        if (mYouAccountText == null) {
            return;
        }

        Account account = MediaServiceManager.instance().getSelectedAccount();
        if (account != null) {
            String name = account.getName() != null ? account.getName() : account.getEmail();
            mYouAccountText.setText(name != null ? name : getString(R.string.settings_accounts));

            String email = account.getEmail();
            boolean showEmail = email != null && !email.equals(name);
            mYouAccountSub.setText(showEmail ? email : null);
            mYouAccountSub.setVisibility(showEmail ? View.VISIBLE : View.GONE);

            String avatarUrl = account.getAvatarImageUrl();
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(this)
                        .load(avatarUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_mobile_account)
                        .into(mYouAvatar);
            } else {
                mYouAvatar.setImageResource(R.drawable.ic_mobile_account);
            }
        } else {
            mYouAccountText.setText(R.string.dialog_add_account);
            mYouAccountSub.setVisibility(View.GONE);
            mYouAvatar.setImageResource(R.drawable.ic_mobile_account);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }

        getWindow().getDecorView().removeCallbacks(mUpdateCheckIfDue);

        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video), and a paused
        // Browse must never hold a stale TextureView on the live player. Audio is unaffected.
        // NEWTUBE(motion): a docked card stays up, frozen on that last frame, until the player
        // covers it (see MiniPlayerBridge.setPendingCardFold) - hiding it here was a blink.
        finishMiniClose();
        detachMiniTexture();
        if (mMiniPlayerBar.getVisibility() == View.VISIBLE && MiniPlayerBridge.isActive()) {
            MiniPlayerBridge.setPendingCardFold(mMiniCardFold);
        } else {
            hideMiniPlayer();
        }

        // Tap-feedback spinner (see onVideoClicked): the destination screen is opening (or the
        // user left) - never keep it spinning under the returning grid.
        mContentSwipe.setRefreshing(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Covered: a card still waiting for the player to fold it can go now.
        if (mMiniTexture == null) {
            hideMiniPlayer();
        }

        // Snapshot the feeds to disk so the NEXT cold start paints cards instantly. onStop fires
        // once per backgrounding — the last reliable moment before most process deaths.
        FeedCache.persist();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenDownloads(intent);
    }

    @Override
    protected void onDestroy() {
        mNavHandler.removeCallbacks(mNavRebuild);
        mNavHandler.removeCallbacks(mRunwayCheck);
        cancelFeedSwap(); // NEWTUBE(feed-swap)
        mContentGrid.removeCallbacks(mRestoreItemAnimator);
        MiniPlayerBridge.unregisterMiniHost(this);
        DownloadRegistry.instance(this).removeListener(mDownloadsListener);
        AppUpdates.instance(this).removeListener(mUpdatesListener);
        mBottomNav.removeCallbacks(mAnnounceUpdateInstalled);

        // Stop observing the cast session; the session itself outlives this screen by design.
        if (mCastSessionManager != null) {
            mCastSessionManager.removeListener(mCastListener);
        }

        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateGridSpanCount(com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this, newConfig));
    }

    // NEWTUBE(ui-mode): a recreated Browse (any config change the manifest doesn't absorb, or
    // process restore) got its section back from the presenter, but BottomNavigationView restored
    // its own "You" highlight over that feed and the You panel itself was lost. Carry the panel
    // state across; the presenter still owns which section is current.
    private static final String STATE_YOU_SHOWING = "newtube:you_showing";
    private static final String STATE_SECTION_FROM_YOU = "newtube:section_from_you";
    private static final String STATE_SECTION_ID = "newtube:section_id";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_YOU_SHOWING, mYouShowing);
        outState.putBoolean(STATE_SECTION_FROM_YOU, mSectionFromYou);
        outState.putInt(STATE_SECTION_ID, mCurrentSectionId);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState); // restores the nav's own highlight

        if (savedInstanceState.getBoolean(STATE_YOU_SHOWING)) {
            showYouPanel();
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(YOU_ITEM_ID);
            mSuppressNavCallback = false;
        } else {
            // "Opened from You" belongs to the section it was saved with. After a process restore
            // the presenter reselects the boot section (usually Home): with the flag carried over,
            // Back on Home would open You instead of leaving.
            mSectionFromYou = savedInstanceState.getBoolean(STATE_SECTION_FROM_YOU)
                    && savedInstanceState.getInt(STATE_SECTION_ID, -1) == mCurrentSectionId;
            // A section with its own tab re-lights that tab; one opened from a You row has none,
            // so the restored You highlight is the right one to keep.
            syncNavHighlight(mCurrentSectionId);
            if (!mSectionFromYou && mBottomNav.getMenu().findItem(toMenuItemId(mCurrentSectionId)) == null) {
                // A section without a tab that is no longer a You sub-screen: nothing may stay lit
                // on You over it; fall back to Home's tab like a fresh start.
                syncNavHighlight(MediaGroup.TYPE_HOME);
            }
            onYouPanelToggled();
        }
    }

    private void updateGridSpanCount(int spanCount) {
        if (mLayoutManager != null && mLayoutManager.getSpanCount() != spanCount) {
            mLayoutManager.setSpanCount(spanCount);
        }
    }

    private void handleBack() {
        // A grid section opened from a You-panel row: back returns to the panel (its natural
        // parent surface), like YouTube's You-tab sub-screens.
        if (mSectionFromYou && !mYouShowing) {
            showYouPanel();
            return;
        }

        // On the You tab itself, back goes Home (YouTube behavior) instead of exiting.
        // NEWTUBE(back-home): so does back on any other tab (Subscriptions, History, Downloads) -
        // it used to exit the app from there, dropping the mini-player and the Home scroll. Only
        // Home itself exits.
        if (mYouShowing || mCurrentSectionId != MediaGroup.TYPE_HOME) {
            int homeItemId = toMenuItemId(MediaGroup.TYPE_HOME);
            if (mBottomNav.getMenu().findItem(homeItemId) != null) {
                mBottomNav.setSelectedItemId(homeItemId); // listener hides the panel + loads Home
                return;
            }
        }

        finish();
    }

    // ---------------------------------------------------------------------------------
    // BrowseView
    // ---------------------------------------------------------------------------------

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        runOnUiThread(() -> {
            Helpers.removeIf(mSections, existing -> existing.getId() == section.getId());

            if (index < 0 || index > mSections.size()) {
                mSections.add(section);
            } else {
                mSections.add(index, section);
            }

            rebuildNavigation();
        });
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        runOnUiThread(() -> {
            Helpers.removeIf(mSections, existing -> existing.getId() == section.getId());
            rebuildNavigation();
        });
    }

    @Override
    public void removeAllSections() {
        runOnUiThread(() -> {
            mSections.clear();
            rebuildNavigation();
        });
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        runOnUiThread(() -> {
            if (index < 0 || index >= mSections.size()) {
                return;
            }

            BrowseSection section = mSections.get(index);

            // Same guard as onSectionChosen(): Settings has no video-grid renderer on the mobile
            // shell (its content arrives as a SettingsGroup, which updateSection(SettingsGroup)
            // ignores). If the presenter's boot/fallback selection lands on Settings, route it to
            // the working AppDialog settings screen instead of driving an empty grid.
            if (section.getId() == MediaGroup.TYPE_SETTINGS) {
                syncNavHighlight(mCurrentSectionId); // keep highlight on the real current section
                openSettings();
                return;
            }

            hideYouPanel();
            boolean switched = mCurrentSectionId != section.getId();
            mCurrentSectionId = section.getId();
            paintCachedSnapshot(section.getId(), switched);

            // The section may not be one of the sections shown in the bottom nav - e.g. the
            // sign-out boot fallback can select Music, which the preferred-tab selection
            // doesn't include (it lives in the You panel). Still drive the presenter so the
            // grid loads; syncNavHighlight no-ops when the section has no tab.
            syncNavHighlight(section.getId());

            if (mPresenter != null) {
                mPresenter.onSectionFocused(section.getId());
            }

            if (focusOnContent) {
                focusOnContent();
            }
        });
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            hideError();

            // ACTION_SYNC = an item was MUTATED in place (e.g. DeArrow / unlocalized-title
            // overrides set video.deArrowTitle / video.altCardImageUrl on the existing Video
            // instance). submitList()'s DiffUtil can't see in-place mutations (same object
            // reference on both sides), so force a targeted re-bind instead - that's how the
            // crowd-sourced titles/thumbnails actually reach the cards.
            if (group.getAction() == VideoGroup.ACTION_SYNC) {
                syncVideos(group.getVideos());
                mAdapter.refreshItems(group.getVideos());
                return;
            }

            // NEWTUBE(feed-swap): this update replaces a FeedCache snapshot with fresh content.
            boolean staleSwap = false;
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    boolean emptyReplace = group.getVideos() == null || group.getVideos().isEmpty();
                    boolean swapPending = mFeedSwap != null && mFeedSwap.isPending();
                    if (emptyReplace && swapPending) {
                        // NEWTUBE(feed-swap): a reload (pull-to-refresh...) started while the fresh
                        // page was still warming up. The grid still shows the snapshot, so this is
                        // again "a snapshot awaiting fresh content": drop the swap, keep the screen.
                        cancelFeedSwap();
                        mCurrentVideos.clear();
                        mCurrentVideos.addAll(mAdapter.getCurrentList());
                        mAwaitingFreshContent = true;
                    }
                    if (emptyReplace && FeedSwapWarmup.keepScreenOnClear(mAwaitingFreshContent, swapPending)) {
                        // The presenter's clear-before-load. The grid is painting a FeedCache
                        // snapshot - keep it on screen; the fresh result replaces it below.
                        return;
                    }
                    staleSwap = mAwaitingFreshContent;
                    mCurrentVideos.clear();
                    if (!emptyReplace) {
                        mCurrentVideos.addAll(visibleFeedItems(group.getVideos()));
                    }
                    mAwaitingFreshContent = false;
                    if (emptyReplace) {
                        showReloadSkeleton();
                    }
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mCurrentVideos.addAll(0, visibleFeedItems(group.getVideos()));
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mCurrentVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE_AUTHOR:
                    removeByAuthor(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    if (mAwaitingFreshContent) {
                        // First fresh group after a cached repaint: swap the stale snapshot
                        // out instead of appending fresh rows below it.
                        mCurrentVideos.clear();
                        mAwaitingFreshContent = false;
                        staleSwap = true;
                    }
                    appendNew(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to trigger again on the new size
            mLastNearEndTriggerCount = -1;
            // Grid growth per update (how deep a feed scrolls, and which pages added nothing).
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-grid section=" + mCurrentSectionId
                    + " action=" + group.getAction() + " in=" + (group.getVideos() != null ? group.getVideos().size() : 0)
                    + " size=" + mCurrentVideos.size());
            submitFeed(staleSwap);
            checkFeedRunway();

            if (isDownloadsSectionShowing()) {
                // Local cards carry their file and registry entry - not something a persisted
                // snapshot can restore, so the section never goes through FeedCache.
                if (mCurrentVideos.isEmpty()) {
                    showDownloadsEmptyState();
                } else {
                    setSkeletonVisible(false);
                }
                return;
            }

            if (mCurrentVideos.isEmpty() && (group.getAction() == VideoGroup.ACTION_REMOVE
                    || group.getAction() == VideoGroup.ACTION_REMOVE_AUTHOR)) {
                // NEWTUBE(feed): the last rows were removed (e.g. unsubscribing from the only
                // channel on Subscriptions) - say so instead of leaving a blank tab.
                showEmptyAfterRemoval();
                return;
            }

            if (!mCurrentVideos.isEmpty()) {
                mLastSessionFallback = null;
                setSkeletonVisible(false);
                com.newtube.mobile.LaunchMilestones.onFeedFreshBound(mCurrentSectionId, mCurrentVideos.size());
                FeedCache.put(mCurrentSectionId, mCurrentVideos);
                // First FRESH feed content is on screen -> the launch-critical /browse chain is
                // done; now the heavy one-time session warmup can run without racing it.
                SessionWarmup.start(this);
            }
        });
    }

    /**
     * NEWTUBE(feed-swap): hand {@link #mCurrentVideos} to the grid. {@code staleSwap}: this update
     * replaces a FeedCache snapshot that is ON SCREEN with the first fresh page. That swap used to go
     * through the item animator like any other change: every stale card faded out, the grid sat
     * EMPTY, then the new cards faded in - ~180 ms of blank Home on 4 of 6 Wi-Fi cold launches on the
     * Pixel, and again ~1 s after minimizing onto a freshly created Home. A stale card that survived
     * into the fresh list could also drag the viewport down to wherever it moved. Now the fresh
     * first screen's pictures warm up first (FeedSwapWarmup, capped at a few hundred ms, the
     * snapshot stays up meanwhile), then the list is swapped in ONE frame with no item animation,
     * pinned to the top. Only a grid resting at the top waits and is pinned: a scrolled or moving
     * one swaps at once (the first screen's pictures are not what it shows) and is never moved.
     * A swap that arrives before the snapshot was ever laid out (the fresh page beat the first
     * frame) needs none of this and goes straight in.
     */
    private void submitFeed(boolean staleSwap) {
        if (staleSwap && isShowingCards()) {
            beginFeedSwap();
            return;
        }
        if (mFeedSwap != null && mFeedSwap.isPending()) {
            if (!mCurrentVideos.isEmpty()) {
                return; // the rest of the page (next shelves): the pending swap submits the newest list
            }
            cancelFeedSwap(); // cleared under a pending swap: there is nothing left to swap to
        }
        submitGrid();
    }

    private boolean isShowingCards() {
        return mAdapter.getItemCount() > 0 && mContentGrid.getChildCount() > 0 && mContentGrid.isShown();
    }

    private void beginFeedSwap() {
        cancelFeedSwap();
        if (!isGridAtTopAndIdle()) {
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-swap section=" + mCurrentSectionId
                    + " warmed=0/0 waitMs=0 scrolled +" + com.newtube.mobile.LaunchMilestones.sinceProcessStartMs());
            commitFeedSwap();
            return;
        }
        int first = mLayoutManager.findFirstVisibleItemPosition();
        int last = mLayoutManager.findLastVisibleItemPosition();
        int visible = first >= 0 && last >= first ? last - first + 1 : 1;
        long startMs = android.os.SystemClock.uptimeMillis();
        int sectionId = mCurrentSectionId;
        FeedSwapWarmup swap = FeedSwapWarmup.create(this);
        mFeedSwap = swap; // before begin(): it may finish synchronously (memory-cache hits)
        swap.begin(FeedSwapWarmup.firstScreen(mCurrentVideos, visible), (warmed, total, timedOut) -> {
            if (mFeedSwap == swap) {
                mFeedSwap = null;
            }
            if (isDestroyed()) {
                return;
            }
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-swap section=" + sectionId
                    + " warmed=" + warmed + "/" + total
                    + " waitMs=" + (android.os.SystemClock.uptimeMillis() - startMs)
                    + (timedOut ? " timeout" : "")
                    + " +" + com.newtube.mobile.LaunchMilestones.sinceProcessStartMs());
            commitFeedSwap();
        });
    }

    private void cancelFeedSwap() {
        if (mFeedSwap != null) {
            mFeedSwap.cancel();
            mFeedSwap = null;
        }
    }

    /** At the very top and not being dragged or flung: the only state a swap may pin. */
    private boolean isGridAtTopAndIdle() {
        return FeedSwapWarmup.warmAndPin(!mContentGrid.canScrollVertically(-1),
                mContentGrid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
    }

    /** Swap the whole list in one layout: animator parked, top pinned if the grid rests at the top. */
    private void commitFeedSwap() {
        if (!mCurrentVideos.isEmpty()) {
            mFeedSwapPinTop = isGridAtTopAndIdle();
            parkItemAnimator();
        }
        submitGrid();
    }

    /**
     * NEWTUBE(motion): a section switch waits for its first commit (onSectionSwapCommitted). Kept as
     * a flag, not only as the snapshot's commit callback: the section's fresh page often lands right
     * behind the snapshot, and AsyncListDiffer drops a superseded submission's callback (Pixel 9:
     * History's switch then skipped the fade and the pin).
     */
    private boolean mSectionSwapPending;

    private void parkItemAnimator() {
        if (mParkedItemAnimator == null && mContentGrid.getItemAnimator() != null) {
            mParkedItemAnimator = mContentGrid.getItemAnimator();
            mContentGrid.setItemAnimator(null);
        }
        if (mParkedItemAnimator != null) {
            // Safety net for a commit that never lands (superseded by a section switch), counted
            // from the LATEST park: an earlier swap's timer must not bring the animator back in the
            // middle of a newer one.
            mContentGrid.removeCallbacks(mRestoreItemAnimator);
            mContentGrid.postDelayed(mRestoreItemAnimator, 1_000);
        }
    }

    /** NEWTUBE(motion): a section switch landed (see paintCachedSnapshot) - top of the list, fade in. */
    private void onSectionSwapCommitted() {
        if (isDestroyed() || !mSectionSwapPending) {
            return;
        }
        mSectionSwapPending = false;
        // DiffUtil keeps the viewport anchored on a card both sections share; a new section starts
        // at its top.
        mLayoutManager.scrollToPositionWithOffset(0, 0);
        if (mAdapter.getItemCount() > 0) {
            mContentGrid.setAlpha(0f);
            mContentGrid.animate().alpha(1f).setDuration(Motion.FADE_IN_MS)
                    .setInterpolator(Motion.STANDARD).withLayer().start();
        }
        restoreItemAnimatorAfterLayout();
    }

    private void submitGrid() {
        List<Video> list = new ArrayList<>(mCurrentVideos);
        if (mParkedItemAnimator == null && !mSectionSwapPending) {
            mAdapter.submitList(list);
        } else {
            // Every submission while parked carries the callback: a later one supersedes the
            // earlier diff (and drops its callback), and the last one must still restore.
            mAdapter.submitList(list, this::onFeedSwapCommitted);
        }
    }

    private void onFeedSwapCommitted() {
        if (isDestroyed()) {
            return;
        }
        if (mSectionSwapPending) {
            mFeedSwapPinTop = false;
            onSectionSwapCommitted(); // this commit superseded the switch's own (see the flag)
            return;
        }
        boolean pin = mFeedSwapPinTop && isGridAtTopAndIdle(); // a finger may have landed since
        mFeedSwapPinTop = false;
        if (pin) {
            // DiffUtil keeps the viewport anchored on a surviving card; pin the fresh top instead.
            mLayoutManager.scrollToPositionWithOffset(0, 0);
        }
        restoreItemAnimatorAfterLayout();
    }

    private void restoreItemAnimatorAfterLayout() {
        // Item animations are decided when the update is LAID OUT (the next frame), so the animator
        // may only come back once RecyclerView has consumed the swap.
        mContentGrid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (mContentGrid.hasPendingAdapterUpdates()) {
                    return true;
                }
                ViewTreeObserver observer = mContentGrid.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                restoreItemAnimator();
                return true;
            }
        });
    }

    private void restoreItemAnimator() {
        mContentGrid.removeCallbacks(mRestoreItemAnimator);
        if (mParkedItemAnimator != null) {
            mContentGrid.setItemAnimator(mParkedItemAnimator);
            mParkedItemAnimator = null;
        }
    }

    private void appendNew(List<Video> videos) {
        if (videos == null) {
            return;
        }
        // NEWTUBE(perf): membership through a hash set instead of List.contains - every page of a
        // long feed used to scan the whole grid per new item on the main thread (Video.equals
        // recomputes both hashCodes per comparison). Same answer: Video.equals is "same hashCode
        // and same isMix()", so equal videos always share a bucket.
        java.util.Set<Video> present = new java.util.HashSet<>(mCurrentVideos);
        for (Video video : videos) {
            if (isVisibleFeedItem(video) && present.add(video)) {
                mCurrentVideos.add(video);
            }
        }
    }

    /**
     * Browse sections are video feeds, not discovery results. YouTube occasionally injects a
     * channel-shaped recommendation into Home/Trending; rendering that with the shared search
     * adapter creates an avatar-only row in the middle of otherwise full-thumbnail cards. NewTube
     * has no Shorts, so none are shown in any section ({@link ShortsFilter}). Keep true playlists
     * (which use a similar service shape), and leave channel discovery to Search.
     */
    private static List<Video> visibleFeedItems(List<Video> videos) {
        List<Video> visible = new ArrayList<>();
        if (videos == null) {
            return visible;
        }
        for (Video video : videos) {
            if (isVisibleFeedItem(video)) {
                visible.add(video);
            }
        }
        return visible;
    }

    private static boolean isVisibleFeedItem(Video video) {
        return video != null
                && !ShortsFilter.isShort(video)
                && (!video.isChannel() || video.isPlaylistAsChannel())
                && !isSearchQueryTile(video);
    }

    /**
     * NEWTUBE(feed): the TV Home feed mixes in search-suggestion tiles - a thumbnail plus a query
     * ("Rivian owner experience"), no video, playlist or channel behind it. On the phone they
     * rendered as video cards whose meta line repeated the title, and tapping one opened Search
     * instead of playing: a card that lies about what it is. Dropped from the grids.
     */
    private static boolean isSearchQueryTile(Video video) {
        return video.searchQuery != null
                && video.videoId == null && video.playlistId == null && video.channelId == null;
    }

    private void syncVideos(List<Video> videos) {
        for (Video video : videos) {
            int idx = mCurrentVideos.indexOf(video);
            if (idx >= 0) {
                mCurrentVideos.set(idx, video);
            }
        }
    }

    private void removeByAuthor(List<Video> videos) {
        for (Video video : videos) {
            String author = video.getAuthor();
            for (int i = mCurrentVideos.size() - 1; i >= 0; i--) {
                if (Helpers.equals(mCurrentVideos.get(i).getAuthor(), author)) {
                    mCurrentVideos.remove(i);
                }
            }
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        // Settings rendering goes through the AppDialog renderer (ROADMAP Wave 1 -
        // universal OptionCategory/OptionItem sheet), not the video grid. Out of scope
        // for this slice; presenter already calls showProgressBar(false) right after.
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (section == null || section.getId() != mCurrentSectionId) {
            return;
        }

        runOnUiThread(() -> {
            cancelFeedSwap(); // NEWTUBE(feed-swap)
            mCurrentVideos.clear();
            mAdapter.submitList(new ArrayList<>());
            showReloadSkeleton();
        });
    }

    /**
     * NEWTUBE(motion): the presenter's clear-before-load (pull-to-refresh, a reload) emptied the
     * grid. showProgressBar(true) usually came first, when the grid still had cards, so it put up
     * no skeleton and a refresh sat on a blank page with only the pull indicator. The skeleton now
     * takes the pull indicator's place until the fresh cards arrive.
     */
    private void showReloadSkeleton() {
        if (!mProgressShowing || !mCurrentVideos.isEmpty() || isDownloadsSectionShowing()
                || mErrorContainer.getVisibility() == View.VISIBLE) {
            return;
        }
        setSkeletonVisible(true);
        mContentSwipe.setRefreshing(false);
        // The old cards leave with the item animator's fade, which showed through the skeleton's
        // gaps: they go under it - once it is on screen (hidden in the same frame, the grid left a
        // blank frame while the skeleton drew its first). The grid is back when the skeleton leaves.
        mGridHiddenForSkeleton = true;
        FrameGate.afterNextFrame(mFeedSkeleton, 50, () -> {
            if (mGridHiddenForSkeleton && mFeedSkeleton.getVisibility() == View.VISIBLE) {
                mContentGrid.animate().cancel();
                mContentGrid.setAlpha(0f);
            }
        });
    }

    @Override
    public void selectSectionItem(int index) {
        runOnUiThread(() -> {
            if (index >= 0 && index < mAdapter.getItemCount()) {
                mContentGrid.scrollToPosition(index);
            }
        });
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item == null) {
            return;
        }

        runOnUiThread(() -> {
            int index = mCurrentVideos.indexOf(item);
            if (index >= 0) {
                mContentGrid.scrollToPosition(index);
            }
        });
    }

    /**
     * Graceful empty state for an empty / sign-in-gated / errored section (ROADMAP polish).
     * {@code BrowsePresenter} routes ALL of these here: signed-out auth-only sections (Subscriptions,
     * Playlists) via {@code authCheck()} -> {@link com.liskovsoft.smartyoutubetv2.common.app.models.errors.SignInError},
     * and genuinely-empty / failed loads via {@code handleLoadError()} ->
     * {@link com.liskovsoft.smartyoutubetv2.common.app.models.errors.CategoryEmptyError}. We render a
     * centered icon + message (and, when the error carries a sign-in action, a "Sign in" button that
     * runs {@code ErrorFragmentData.onAction()} -> {@code YTSignInPresenter.start()} -> the mobile
     * SignIn screen). The message is section-aware for the sign-in case ("Sign in to see your
     * Subscriptions") and a clean generic line otherwise - we deliberately do NOT surface
     * {@code CategoryEmptyError.getMessage()} verbatim because for real errors it returns a raw stack
     * trace, which is not something to show a phone user.
     */
    @Override
    public void showError(ErrorFragmentData data) {
        runOnUiThread(() -> {
            String actionText = data != null ? data.getActionText() : null;
            boolean hasSignInAction = actionText != null && !actionText.isEmpty();
            // What the action button will carry. Cleared by the offline branch below, where the
            // button means "retry" rather than "run this error's own action".
            ErrorFragmentData actionData = data;

            // NEWTUBE(motion): the first load after a cold start failed - last session's cards
            // beat an error page (the pre-skeleton behaviour); pull-to-refresh is the retry.
            if (!hasSignInAction && mCurrentVideos.isEmpty() && mLastSessionFallback != null) {
                mCurrentVideos.addAll(visibleFeedItems(mLastSessionFallback));
                mLastSessionFallback = null;
                mAwaitingFreshContent = true;
                mAdapter.submitList(new ArrayList<>(mCurrentVideos));
            }

            setSkeletonVisible(false);
            mContentSwipe.setRefreshing(false);

            // A failed refresh over a FeedCache repaint: stale content beats a full-screen
            // error. Keep the grid; pull-to-refresh is the retry. Sign-in gating still takes
            // the full-screen path - stale rows from another signed-in state would mislead.
            if (!hasSignInAction && !mCurrentVideos.isEmpty()) {
                return;
            }

            mContentGrid.setVisibility(View.GONE);
            mErrorContainer.setVisibility(View.VISIBLE);
            mErrorIcon.setVisibility(View.VISIBLE);

            if (hasSignInAction) {
                mErrorMessage.setText(SignInCopy.forSection(this, mCurrentSectionId, getCurrentSectionTitle()));
                mErrorAction.setText(actionText);
                mErrorAction.setVisibility(View.VISIBLE);
            } else if (!hasValidatedNetwork()) {
                // NEWTUBE(offline-feed): a load that failed because the phone has no working
                // network used to render as "Nothing to show here yet" with no affordance - the
                // app blaming YouTube for the user's tunnel, on the screen where "nothing loads"
                // is the whole complaint. The section really being empty and the request never
                // leaving the device are indistinguishable at this seam, so ask the device
                // instead of guessing from the error.
                mErrorMessage.setText(R.string.mobile_empty_no_connection);
                mErrorAction.setText(R.string.mobile_signin_retry_button);
                mErrorAction.setVisibility(View.VISIBLE);
                // Null tag = "plain retry" for the click listener; this state has no sign-in action
                // to carry, and running the section's own onAction here would be the wrong thing.
                actionData = null;
            } else {
                mErrorMessage.setText(R.string.mobile_empty_generic);
                mErrorAction.setVisibility(View.GONE);
            }

            mErrorAction.setTag(actionData);
        });
    }

    /** Title of the section the user is currently viewing, for a section-aware empty message. */
    /**
     * Does the device currently have a working default network? Used only to word the empty state:
     * the feed's failure paths all collapse to "no rows", so the error object itself cannot tell an
     * offline failure from a genuinely empty section.
     */
    private boolean hasValidatedNetwork() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // can't tell - keep the neutral wording
        }

        android.net.Network active = cm.getActiveNetwork();
        android.net.NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String getCurrentSectionTitle() {
        for (BrowseSection section : mSections) {
            if (section.getId() == mCurrentSectionId) {
                return section.getTitle();
            }
        }
        return null;
    }

    private void hideError() {
        if (mErrorContainer.getVisibility() != View.VISIBLE) {
            return;
        }

        mErrorContainer.setVisibility(View.GONE);
        mContentGrid.setVisibility(View.VISIBLE);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgressShowing = show;
        runOnUiThread(() -> {
            // Empty grid = first load: card-skeleton ghosts (the old centered spinner over a
            // black void read as "the app hangs"). Grid with content (FeedCache repaint or
            // pagination): no overlay at all - the pull-to-refresh indicator covers the
            // user-initiated case, and background refreshes just swap content in when ready.
            if (show || mFeedSkeleton.getVisibility() != View.VISIBLE) {
                setSkeletonVisible(show && mCurrentVideos.isEmpty());
            } else {
                // NEWTUBE(motion): the presenter says "done" just BEFORE it hands over the rows (in
                // the same callback): decide once they are in, so the skeleton fades over them
                // instead of vanishing onto an empty grid.
                mFeedSkeleton.post(() -> {
                    if (!mProgressShowing) {
                        setSkeletonVisible(false);
                    }
                });
            }
            if (show && mCurrentVideos.isEmpty()) {
                mContentSwipe.setRefreshing(false); // the skeleton says it
            }
            if (!show) {
                mContentSwipe.setRefreshing(false);
            }
        });
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressShowing;
    }

    @Override
    public void focusOnContent() {
        runOnUiThread(() -> mContentGrid.requestFocus());
    }

    @Override
    public boolean isEmpty() {
        return mCurrentVideos.isEmpty();
    }

    @Override
    public void updateBadge() {
        // Account/bridge badge icon (top corner on TV). No equivalent surface yet on
        // the mobile shell; revisit alongside the account/sign-in screens (Wave 5).
    }

    @Override
    public void onSectionContentCurrent(int sectionId) {
        runOnUiThread(() -> {
            if (sectionId != mCurrentSectionId) {
                return;
            }

            // The presenter skipped the refetch (section fresh within TTL): the snapshot painted
            // by paintCachedSnapshot IS the current content. Clear the awaiting flag so a later
            // scroll-end APPEND extends the grid instead of swap-replacing it through the
            // stale-snapshot path, and make sure no loading affordance lingers.
            mAwaitingFreshContent = false;
            if (mCurrentVideos.isEmpty() && mLastSessionFallback != null) {
                // No fetch is coming for a section held back behind the skeleton: its cards it is.
                mCurrentVideos.addAll(visibleFeedItems(mLastSessionFallback));
                mAdapter.submitList(new ArrayList<>(mCurrentVideos));
            }
            mLastSessionFallback = null;
            setSkeletonVisible(false);
            mContentSwipe.setRefreshing(false);
        });
    }
}
