package com.newtube.mobile.ui.browse;

import android.Manifest;
import android.animation.ValueAnimator;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.casting.CastPickerLauncher;
import com.newtube.mobile.casting.CastSessionManager;
import com.newtube.mobile.casting.CastTarget;
import com.newtube.mobile.casting.CastVolumeKeys;
import com.newtube.mobile.ui.common.FeedCache;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
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
    /** BottomNavigationView hard-caps at this many items. */
    private static final int MAX_NAV_ITEMS = 5;
    /**
     * Menu item id of the synthetic "You" tab (account + extra sections + settings). Far above
     * {@link #ITEM_ID_OFFSET} + any real section id, so it can never collide with one.
     */
    private static final int YOU_ITEM_ID = 2_000_000;
    private static final int REQUEST_POST_NOTIFICATIONS = 1;

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
    };
    private static final int[] PREFERRED_SECTION_TITLE_RES = {
            R.string.header_home,
            R.string.header_subscriptions,
            R.string.header_history,
    };

    private BrowsePresenter mPresenter;

    private RecyclerView mContentGrid;
    private SwipeRefreshLayout mContentSwipe;
    private View mFeedSkeleton;
    private ValueAnimator mSkeletonPulse;
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

    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<Video> mCurrentVideos = new ArrayList<>();
    private int mCurrentSectionId = -1;
    private boolean mProgressShowing;
    private boolean mSuppressNavCallback;
    private int mLastPaginationTriggerCount = -1;
    /**
     * The grid is painting a stale {@link FeedCache} snapshot while the presenter refetches the
     * section. While set, the presenter's clear-before-load empty REPLACE is skipped (it would
     * blank the snapshot), and the first fresh group swaps the whole list instead of appending
     * below the stale items.
     */
    private boolean mAwaitingFreshContent;

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

        mMiniPlayerBar = findViewById(R.id.mobile_mini_player);
        mMiniPlayerFrame = findViewById(R.id.mobile_mini_player_frame);
        mMiniFreeze = findViewById(R.id.mobile_mini_freeze);
        mMiniPlayPause = findViewById(R.id.mobile_mini_play_pause);
        mMiniProgress = findViewById(R.id.mobile_mini_progress);
        setupMiniPlayerBar();
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
     * First-load skeleton: card ghosts instead of a naked spinner, alpha-pulsed like the
     * watch page's related-list skeleton. Only ever shown over an EMPTY grid - a section
     * repainted from {@link FeedCache} keeps its content visible while refreshing.
     */
    private void setSkeletonVisible(boolean visible) {
        if (visible == (mFeedSkeleton.getVisibility() == View.VISIBLE)) {
            return;
        }

        if (visible) {
            mFeedSkeleton.setVisibility(View.VISIBLE);
            if (mSkeletonPulse == null) {
                mSkeletonPulse = ValueAnimator.ofFloat(1f, 0.45f);
                mSkeletonPulse.setDuration(700);
                mSkeletonPulse.setRepeatMode(ValueAnimator.REVERSE);
                mSkeletonPulse.setRepeatCount(ValueAnimator.INFINITE);
                mSkeletonPulse.addUpdateListener(a -> mFeedSkeleton.setAlpha((float) a.getAnimatedValue()));
            }
            if (!mSkeletonPulse.isStarted()) {
                mSkeletonPulse.start();
            }
        } else {
            if (mSkeletonPulse != null) {
                mSkeletonPulse.cancel();
            }
            mFeedSkeleton.setAlpha(1f);
            mFeedSkeleton.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------------------------
    // In-app mini-player bar
    // ---------------------------------------------------------------------------------

    private void setupMiniPlayerBar() {
        // Tap the video (anywhere but the overlay buttons) = expand back to the watch screen.
        // The card keeps rendering until our onPause detaches it (hideMiniPlayer there) - the
        // player's onResume then re-parents the session texture back. Detaching HERE would blank
        // the card for the whole activity-switch latency.
        View.OnClickListener expand = v -> MiniPlayerBridge.expand(this);
        mMiniPlayerBar.setOnClickListener(expand);
        mMiniPlayerFrame.setOnClickListener(expand);

        mMiniPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updateMiniPlayPauseIcon(player);
            }
        });

        findViewById(R.id.mobile_mini_close).setOnClickListener(v -> {
            hideMiniPlayer();
            MiniPlayerBridge.close();
        });
    }

    /** Show the card and adopt the live session texture if a mini session is active. */
    private void syncMiniPlayer() {
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

        attachMiniTexture();
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
        if (mMiniPlayerBar != null) {
            mMiniPlayerBar.setVisibility(View.GONE);
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
        mContentGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
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
            mCastButton.setColorFilter(getColorInt(R.color.mobile_color_accent));
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
     * Settings entry point (Wave 3). Reuses the Wave 3 AppDialog renderer directly instead of
     * a separate settings screen: {@link AppDataSourceManager#getSettingItems} returns the same
     * {@code List<SettingsItem>} (title + onClick + icon) that the TV {@code SettingsGridFragment}
     * renders as a grid - each item's {@code onClick} already calls e.g.
     * {@code GeneralSettingsPresenter.instance(context).show()}, which itself just builds
     * {@code OptionCategory} lists and calls {@code AppDialogPresenter.showDialog()}. So the
     * cleanest reuse is to render the top-level Settings list as one more
     * {@code AppDialogPresenter} button category screen: tapping a row runs the real
     * {@code SettingsItem.onClick}, which opens its own nested AppDialog screen on top (the
     * same "push a new level" mechanism described in {@code MobileAppDialogActivity}).
     */
    private void openSettings() {
        AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(this);
        dialogPresenter.clearBackstack();

        for (SettingsItem item : AppDataSourceManager.instance().getSettingItems(this)) {
            dialogPresenter.appendSingleButton(UiOptionItem.from(item.title, optionItem -> item.onClick.run()));
        }

        // Tag this as the full-screen Settings tree so MobileAppDialogActivity renders it full-screen
        // (nested category screens push onto the same activity and inherit that). Context menus and the
        // player option pickers leave the id unset and get the default bottom-sheet presentation.
        dialogPresenter.setId(com.newtube.mobile.ui.dialog.MobileAppDialogActivity.ID_FULLSCREEN_SETTINGS);
        dialogPresenter.showDialog(getString(R.string.header_settings));
    }

    private void setupBottomNav() {
        mBottomNav.setOnItemSelectedListener(item -> {
            if (!mSuppressNavCallback) {
                if (item.getItemId() == YOU_ITEM_ID) {
                    showYouPanel();
                } else {
                    mSectionFromYou = false;
                    hideYouPanel();
                    onSectionChosen(item.getItemId() - ITEM_ID_OFFSET);
                }
            }
            return true;
        });
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
    }

    private void hideYouPanel() {
        mYouShowing = false;
        mYouPanel.setVisibility(View.GONE);
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
     * Settings/Shorts) are excluded - Settings gets its own trailing row, Shorts is retired on
     * the phone shell. Long-press on a section row opens the section-management menu (the old
     * drawer rows' "..." overflow).
     */
    private void rebuildYouRows() {
        mYouRows.removeAllViews();

        List<BrowseSection> navSections = selectNavSections();
        List<BrowseSection> personal = new ArrayList<>();
        List<BrowseSection> explore = new ArrayList<>();

        for (BrowseSection section : mSections) {
            if (!section.isEnabled()
                    || navSections.contains(section)
                    || section.getId() == MediaGroup.TYPE_SETTINGS
                    || section.getId() == MediaGroup.TYPE_SHORTS) {
                continue;
            }
            (isPersonalSection(section.getId()) ? personal : explore).add(section);
        }

        for (BrowseSection section : personal) {
            addYouSectionRow(section);
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
        });
        row.setOnLongClickListener(v -> {
            if (mPresenter != null) {
                mPresenter.onSectionLongPressed(sectionId);
            }
            return true;
        });
    }

    /** Small secondary-color group label, official-You-page style. */
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

        mCurrentSectionId = sectionId;
        paintCachedSnapshot(sectionId);
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
    private void paintCachedSnapshot(int sectionId) {
        // Falls back to the persisted snapshot on the process's first paint of this section,
        // so even a cold start shows cards instead of the skeleton (display-only until the
        // refetch replaces it — see FeedCache class doc).
        List<Video> cached = FeedCache.getOrRestore(sectionId);

        mCurrentVideos.clear();
        mAwaitingFreshContent = cached != null;
        if (cached != null) {
            mCurrentVideos.addAll(visibleFeedItems(cached, sectionId));
        }

        mLastPaginationTriggerCount = -1;
        mAdapter.submitList(new ArrayList<>(mCurrentVideos));
        if (!mCurrentVideos.isEmpty()) {
            setSkeletonVisible(false);
            mContentGrid.scrollToPosition(0);
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
            }
        });
    }

    private void onVideoClicked(Video video) {
        if (mPresenter == null) {
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

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(mCurrentVideos.get(mCurrentVideos.size() - 1));
        }
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    private static int toMenuItemId(int sectionId) {
        return sectionId + ITEM_ID_OFFSET;
    }

    /** Rebuild both nav surfaces (bottom nav = the main tabs + You; You panel = the rest). */
    private void rebuildNavigation() {
        rebuildBottomNav();
        if (mYouShowing) {
            rebuildYouRows();
        }
    }

    private void rebuildBottomNav() {
        mSuppressNavCallback = true;

        Menu menu = mBottomNav.getMenu();
        menu.clear();

        List<BrowseSection> navSections = selectNavSections();

        for (int i = 0; i < navSections.size(); i++) {
            BrowseSection section = navSections.get(i);

            android.view.MenuItem item = menu.add(Menu.NONE, toMenuItemId(section.getId()), i, section.getTitle());

            int navIcon = navIconFor(section.getId());
            if (navIcon != 0) {
                item.setIcon(navIcon);
            } else if (section.getResId() > 0) {
                item.setIcon(section.getResId());
            }
        }

        // The synthetic You tab always sits last (account + extra sections + settings).
        menu.add(Menu.NONE, YOU_ITEM_ID, navSections.size(), R.string.mobile_nav_you)
                .setIcon(R.drawable.ic_nav_you);

        // Re-assert the highlight after clear()/add() wiped it, so the current tab stays lit.
        if (mYouShowing) {
            mBottomNav.setSelectedItemId(YOU_ITEM_ID);
        } else if (mCurrentSectionId >= 0 && menu.findItem(toMenuItemId(mCurrentSectionId)) != null) {
            mBottomNav.setSelectedItemId(toMenuItemId(mCurrentSectionId));
        }

        mSuppressNavCallback = false;

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
        // Cheap re-sync; listener callbacks already cover changes while resumed.
        updateCastIconTint();
        // Last-resumed host wins: while this screen is (or is about to be) the one under the
        // player, minimize docks its card here.
        MiniPlayerBridge.registerMiniHost(this);
        syncMiniPlayer();
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

        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video), and a paused
        // Browse must never hold a stale TextureView on the live player. Audio is unaffected.
        hideMiniPlayer();

        // Tap-feedback spinner (see onVideoClicked): the destination screen is opening (or the
        // user left) - never keep it spinning under the returning grid.
        mContentSwipe.setRefreshing(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Snapshot the feeds to disk so the NEXT cold start paints cards instantly. onStop fires
        // once per backgrounding — the last reliable moment before most process deaths.
        FeedCache.persist();
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);

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

        updateGridSpanCount(com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig));
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
        if (mYouShowing) {
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
            mCurrentSectionId = section.getId();
            paintCachedSnapshot(section.getId());

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

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    boolean emptyReplace = group.getVideos() == null || group.getVideos().isEmpty();
                    if (emptyReplace && mAwaitingFreshContent) {
                        // The presenter's clear-before-load. The grid is painting a FeedCache
                        // snapshot - keep it on screen; the fresh result replaces it below.
                        return;
                    }
                    mCurrentVideos.clear();
                    if (!emptyReplace) {
                        mCurrentVideos.addAll(visibleFeedItems(group.getVideos(), mCurrentSectionId));
                    }
                    mAwaitingFreshContent = false;
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mCurrentVideos.addAll(0, visibleFeedItems(group.getVideos(), mCurrentSectionId));
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
                    }
                    appendNew(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to trigger again on the new size
            mAdapter.submitList(new ArrayList<>(mCurrentVideos));

            if (!mCurrentVideos.isEmpty()) {
                setSkeletonVisible(false);
                FeedCache.put(mCurrentSectionId, mCurrentVideos);
                // First FRESH feed content is on screen -> the launch-critical /browse chain is
                // done; now the heavy one-time session warmup can run without racing it.
                SessionWarmup.start(this);
            }
        });
    }

    private void appendNew(List<Video> videos) {
        if (videos == null) {
            return;
        }
        for (Video video : videos) {
            if (isVisibleFeedItem(video, mCurrentSectionId) && !mCurrentVideos.contains(video)) {
                mCurrentVideos.add(video);
            }
        }
    }

    /**
     * Browse sections are video feeds, not discovery results. YouTube occasionally injects a
     * channel-shaped recommendation into Home/Trending; rendering that with the shared search
     * adapter creates an avatar-only row in the middle of otherwise full-thumbnail cards. The
     * phone shell has no Shorts destination at all (retired with the You-tab redesign), so the
     * Shorts YouTube mixes into the Home and Subscriptions shelves are filtered out too; History
     * keeps them - it is a record of what was actually watched. Keep true playlists (which use a
     * similar service shape), and leave channel discovery to Search.
     */
    private static List<Video> visibleFeedItems(List<Video> videos, int sectionId) {
        List<Video> visible = new ArrayList<>();
        if (videos == null) {
            return visible;
        }
        for (Video video : videos) {
            if (isVisibleFeedItem(video, sectionId)) {
                visible.add(video);
            }
        }
        return visible;
    }

    private static boolean isVisibleFeedItem(Video video, int sectionId) {
        boolean shortsFiltered = sectionId == MediaGroup.TYPE_HOME
                || sectionId == MediaGroup.TYPE_SUBSCRIPTIONS;
        return video != null
                && (!shortsFiltered || !video.isShorts)
                && (!video.isChannel() || video.isPlaylistAsChannel());
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
            mCurrentVideos.clear();
            mAdapter.submitList(new ArrayList<>());
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
                String sectionTitle = getCurrentSectionTitle();
                mErrorMessage.setText(sectionTitle != null
                        ? getString(R.string.mobile_empty_signin_section, sectionTitle)
                        : getString(R.string.mobile_empty_signin_generic));
                mErrorAction.setText(actionText);
                mErrorAction.setVisibility(View.VISIBLE);
            } else {
                mErrorMessage.setText(R.string.mobile_empty_generic);
                mErrorAction.setVisibility(View.GONE);
            }

            mErrorAction.setTag(data);
        });
    }

    /** Title of the section the user is currently viewing, for a section-aware empty message. */
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
            setSkeletonVisible(show && mCurrentVideos.isEmpty());
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
            setSkeletonVisible(false);
            mContentSwipe.setRefreshing(false);
        });
    }
}
