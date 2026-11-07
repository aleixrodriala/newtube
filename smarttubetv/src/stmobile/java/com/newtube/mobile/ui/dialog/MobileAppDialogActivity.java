package com.newtube.mobile.ui.dialog;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSheets;
import com.newtube.mobile.ui.common.Motion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch renderer for {@link AppDialogView} - Wave 3 (ARCHITECTURE.md section 4, the
 * highest-leverage seam: one screen here lights up every "..." context menu and player picker,
 * since they all funnel through {@link AppDialogPresenter}). The phone Settings drew their TV
 * categories here too until 1.15; they have their own screen now ({@code ui.settings}).
 *
 * <h3>Backstack model</h3>
 * {@code AppDialogPresenter} drives multi-level navigation (e.g. tapping a Settings category
 * opens a nested options screen) by calling {@link #show} again on the same live view
 * instance - see {@code AppDialogPresenter.showDialog()}: it calls {@code onViewInitialized()}
 * (which re-invokes {@code show()}) directly whenever the presenter already has a view, rather
 * than going through {@code ViewManager} (which no-ops because this Activity is already on
 * top). The TV renderer ({@code AppDialogFragment}) tracks this with a child
 * {@code FragmentManager} backstack - one entry per {@code show()} call beyond the first,
 * replaced (not added) when the stack is empty. We mirror that with a plain {@link #mLevels}
 * list: every {@code show()} call appends a level; {@link #goBack()} pops one; Android back
 * goes through the AndroidX back dispatcher, which pops via {@code goBack()} while
 * {@link #canGoBack()}, and only finishes the Activity at the root level.
 *
 * <p>{@link #finish()} (the {@link AppDialogView} contract method, called by
 * {@code AppDialogPresenter.closeDialog()} - used pervasively by context-menu items like
 * "Add to watch later" to dismiss the whole menu after one tap) is intentionally NOT the same
 * as "pop one level": it always tears down the entire dialog regardless of depth, matching
 * {@code AppDialogFragment.finish()} -> {@code Activity.finish()} on TV (which only special-cases
 * a level pop when the close was triggered by a physical/back-press, tracked there via
 * {@code mIsBackPressed}). Here that split is expressed by keeping level-popping entirely in
 * the dialog can go back, separate from {@link #finish()}.
 *
 * <h3>Item kinds rendered</h3>
 * See {@link DialogRowAdapter} for the per-{@code OptionCategory.type} rendering (single-select
 * radio, multi-select checkbox, switch/toggle, plain button, and a read-only fallback for
 * long-text/chat/comments - the last two stubbed per ARCHITECTURE.md/this wave's scope).
 */
public class MobileAppDialogActivity extends MobileActivity implements AppDialogView {

    /**
     * NEWTUBE(ui-mode): the level stack handed from an instance being recreated for a configuration
     * change to its replacement (same process; the OptionCategory callbacks can't be parcelled).
     * Without it a recreated Settings screen kept only the level on top, and Back closed Settings
     * instead of returning to the parent category.
     */
    private static RecreationState sRecreation;

    private static final class RecreationState {
        final List<DialogLevel> levels;
        final Map<OptionCategory, OptionItem> radioOverrides;
        final boolean transparent;

        RecreationState(List<DialogLevel> levels, Map<OptionCategory, OptionItem> radioOverrides, boolean transparent) {
            this.levels = levels;
            this.radioOverrides = radioOverrides;
            this.transparent = transparent;
        }
    }

    /** Bottom sheet is capped at this fraction of the screen height, then the list scrolls. */
    private static final float SHEET_MAX_HEIGHT_FRACTION = 0.72f;

    private static final class DialogLevel {
        final List<OptionCategory> categories;
        final CharSequence title;
        /** NEWTUBE(settings-scroll): where this level's list was when a level was opened over it. */
        Parcelable listState;

        DialogLevel(List<OptionCategory> categories, CharSequence title) {
            this.categories = categories;
            this.title = title;
        }
    }

    private AppDialogPresenter mPresenter;
    private FrameLayout mRoot;
    private View mScrim;
    private LinearLayout mContent;
    private View mHandle;
    private MaxHeightRecyclerView mRecyclerView;
    private TextView mTitleView;
    private View mToolbar;
    /** NEWTUBE(sheet-landscape): opened over the player, whose bars it follows (onConfigurationChanged). */
    private boolean mOverPlayer;
    private ImageButton mBackButton;
    private DialogRowAdapter mAdapter;

    /**
     * The sheet is set up on the first show() call. (Until the phone Settings got their own screen
     * in 1.15, the Settings tree was drawn here too, full screen; every caller now gets a sheet.)
     */
    private boolean mModeConfigured;
    /**
     * NEWTUBE(motion): the sheet is sliding away and {@code super.finish()} lands when it is gone.
     * A {@link #show} arriving meanwhile (the presenter re-uses a live view for a follow-up dialog)
     * brings the sheet back instead.
     */
    private boolean mExiting;

    private final List<DialogLevel> mLevels = new ArrayList<>();
    /** See {@link DialogRowAdapter#submit}. Keyed by category identity; stale entries from a
     *  since-discarded level are harmless (they simply never match a future category instance). */
    private final Map<OptionCategory, OptionItem> mRadioOverrides = new HashMap<>();

    private boolean mIsTransparent;
    private boolean mIsOverlay;
    private boolean mIsPaused = true;
    private int mId;

    /** Last system-bar + cutout insets delivered to {@link #mRoot}. See {@link #applyDialogInsets}. */
    private Insets mSystemInsets = Insets.NONE;

    private final DialogRowAdapter.Listener mRowListener = new DialogRowAdapter.Listener() {
        @Override
        public void onButtonClicked(OptionItem item) {
            // Mirrors AppPreferenceManager.createButtonPreference(): imitate a click on the item.
            item.onSelect(true);
        }

        @Override
        public void onRadioClicked(OptionCategory category, OptionItem item) {
            mRadioOverrides.put(category, item);
            // Mirrors AppPreferenceManager.initSingleSelectListPreference(): only the newly picked
            // item gets onSelect(true); siblings are intentionally left untouched.
            item.onSelect(true);
            renderTopLevel(SCROLL_KEEP);
        }

        @Override
        public void onCheckboxClicked(OptionCategory category, OptionItem item) {
            boolean newSelected = !item.isSelected();

            // Mirrors AppPreferenceManager.initMultiSelectListPreference()'s required/radio handling.
            if (newSelected) {
                OptionItem[] required = item.getRequired();
                if (required != null) {
                    for (OptionItem requiredItem : required) {
                        if (requiredItem != null && !requiredItem.isSelected()) {
                            MessageHelpers.showMessage(MobileAppDialogActivity.this,
                                    getString(R.string.require_checked, requiredItem.getTitle()));
                        }
                    }
                }

                OptionItem[] radio = item.getRadio();
                if (radio != null) {
                    for (OptionItem radioItem : radio) {
                        if (radioItem != null) {
                            radioItem.onSelect(false);
                        }
                    }
                }
            }

            item.onSelect(newSelected);
            renderTopLevel(SCROLL_KEEP);
        }

        @Override
        public void onSwitchToggled(OptionItem item, boolean checked) {
            item.onSelect(checked);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_app_dialog);
        mOverPlayer = MobileSheets.isPlayerShown();
        if (MobileSheets.isFullscreenPlayerShown()) {
            // NEWTUBE(sheet-landscape): a picker over the fullscreen video (gear -> More -> Repeat,
            // Zoom, Save to playlist...) - this window takes focus, so it must ask for the
            // player's hidden bars itself, or they slide in over the video while it is open.
            MobileSheets.keepSystemBarsHidden(getWindow());
        }

        registerBackHandler(this::handleBack);

        bindViews();
        setupRecyclerView();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // NEWTUBE(ui-mode): a recreated sheet keeps the levels below the top one (see sRecreation);
        // the presenter only re-shows its last level.
        RecreationState recreation = sRecreation;
        sRecreation = null;
        if (savedInstanceState != null && recreation != null) {
            mLevels.addAll(recreation.levels);
            mRadioOverrides.putAll(recreation.radioOverrides);
            mIsTransparent = recreation.transparent;
        }
        if (savedInstanceState != null && !mLevels.isEmpty()) {
            mModeConfigured = true;
            configureSheet();
        }

        mPresenter = AppDialogPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mRoot = findViewById(R.id.mobile_dialog_root);
        mScrim = findViewById(R.id.mobile_dialog_scrim);
        mContent = findViewById(R.id.mobile_dialog_content);
        mHandle = findViewById(R.id.mobile_dialog_handle);
        mRecyclerView = findViewById(R.id.mobile_dialog_list);
        mTitleView = findViewById(R.id.mobile_dialog_title);
        mToolbar = findViewById(R.id.mobile_dialog_toolbar);
        mBackButton = findViewById(R.id.mobile_dialog_back);

        // Sheet mode: tapping the dim scrim dismisses the whole dialog (like a Material sheet).
        mScrim.setOnClickListener(v -> dismissSheet());

        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (view, windowInsets) -> {
            mSystemInsets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            applyDialogInsets();
            return windowInsets;
        });
    }

    /**
     * This overlay is full-bleed, so it inherits none of {@link MobileActivity}'s blanket content
     * padding and places the insets itself.
     *
     * <p>Padding the whole activity content is right for an ordinary opaque screen and wrong for a
     * scrim: on an edge-to-edge device it left the dim stopping at the status bar and the sheet
     * floating a gesture-bar's height above the display edge (measured on a Pixel 9 / API 37: the
     * dim began at y=173, exactly the status-bar height, and the sheet surface ended 63px short of
     * the bottom). A Material sheet dims the whole display and runs its own background under the
     * gesture bar, so the scrim stays edge to edge and only the sheet's CONTENT is inset.
     */
    @Override
    protected boolean shouldInsetContentForSystemBars() {
        return false;
    }

    /**
     * Pad the sides and the bottom, never the top - the rounded background then reaches the
     * display edge while the last row still clears the gesture bar.
     */
    private void applyDialogInsets() {
        if (mContent == null) {
            return;
        }

        Rect window = liveWindowBounds();
        // NEWTUBE(sheet-landscape): a Material sheet stops at 640dp and centres on a wide
        // window; full-width rows 914dp long read as a page, not a menu.
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.mobile_sheet_max_width);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
        int width = window.width() > maxWidth ? maxWidth : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != width) {
            lp.width = width;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mContent.setLayoutParams(lp);
        }
        // A capped sheet no longer reaches the side bars; only a full-width one pads for them.
        boolean capped = width != ViewGroup.LayoutParams.MATCH_PARENT;
        mContent.setPadding(capped ? 0 : mSystemInsets.left, 0,
                capped ? 0 : mSystemInsets.right, mSystemInsets.bottom);
        mRecyclerView.setMaxHeight(sheetMaxHeight(window.height()));
    }

    /**
     * The cap is a fraction of the space the sheet can actually occupy, not of the raw display:
     * under edge-to-edge the display height includes the bars, so measuring against it would let a
     * long sheet grow into the status bar.
     *
     * <p>NEWTUBE(sheet-landscape): measured against the LIVE window, not
     * {@code getResources().getDisplayMetrics()} - before 1.10.4 that was MotherActivity's
     * process-wide copy frozen at the first activity's orientation, so a landscape sheet was capped at
     * 72% of the PORTRAIT height (1650px in a 1080px window) and its last rows were unreachable.
     */
    private int sheetMaxHeight(int windowHeight) {
        int usable = windowHeight - mSystemInsets.top - mSystemInsets.bottom;
        return Math.round(usable * SHEET_MAX_HEIGHT_FRACTION);
    }

    private Rect liveWindowBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            return getWindowManager().getCurrentWindowMetrics().getBounds();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mOverPlayer) {
            // Turned with the picker open: the player goes fullscreen with the phone sideways and
            // back to its watch page, which has its bars, when it is upright again.
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                MobileSheets.keepSystemBarsHidden(getWindow());
            } else {
                MobileSheets.showSystemBars(getWindow());
            }
        }
        // Rotation with the sheet open: re-cap width/height for the new window (the insets
        // callback also re-runs this, but not when the insets happen to be unchanged).
        applyDialogInsets();
    }

    private void setupRecyclerView() {
        mAdapter = new DialogRowAdapter(this, mRowListener);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);
    }

    /**
     * NEWTUBE(settings-scroll): every render used to jump the list to the top, so ticking a box or
     * picking an option halfway down a long settings page threw the person back to its first row
     * (issue #2). Only a newly opened level starts at the top; a re-render keeps its place, and
     * going back returns to where that level was left.
     */
    private static final int SCROLL_TOP = 0;
    private static final int SCROLL_KEEP = 1;
    private static final int SCROLL_RESTORE = 2;

    private void renderTopLevel(int scroll) {
        if (mLevels.isEmpty()) {
            return;
        }

        DialogLevel level = mLevels.get(mLevels.size() - 1);

        mTitleView.setText(level.title);
        // Show the back arrow when it can pop a level. A root-level sheet has no back arrow - the
        // scrim/back dismiss it.
        boolean showBack = canGoBack();
        mBackButton.setVisibility(showBack ? View.VISIBLE : View.GONE);
        // NEWTUBE(sheet-title): a root sheet without a title (Repeat mode, Zoom: their category
        // header names them) left the bar as 56dp of blank between the handle and the first row -
        // a fifth of the height over the landscape player.
        mToolbar.setVisibility(showBack || !TextUtils.isEmpty(level.title) ? View.VISIBLE : View.GONE);
        // NEWTUBE(sheet-title): without the arrow the title sat 4dp from the edge while every row
        // starts at 16dp; line it up with the rows. Beside the arrow, 4dp keeps the usual gap.
        mTitleView.setPaddingRelative(
                getResources().getDimensionPixelSize(showBack
                        ? R.dimen.mobile_dialog_title_inset_with_back : R.dimen.mobile_dialog_title_inset),
                mTitleView.getPaddingTop(), mTitleView.getPaddingEnd(), mTitleView.getPaddingBottom());
        mAdapter.submit(level.categories, mRadioOverrides, level.title);
        RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
        if (scroll == SCROLL_RESTORE && level.listState != null && layoutManager != null) {
            layoutManager.onRestoreInstanceState(level.listState);
        } else if (scroll != SCROLL_KEEP) {
            mRecyclerView.scrollToPosition(0);
        }
    }

    /** Set the sheet up once, on the first {@link #show}. */
    private void configurePresentation() {
        if (mModeConfigured) {
            return;
        }
        mModeConfigured = true;
        configureSheet();
    }

    private void configureSheet() {
        mScrim.setVisibility(View.VISIBLE);
        mHandle.setVisibility(View.VISIBLE);
        mContent.setBackgroundResource(R.drawable.bg_mobile_sheet);
        // NEWTUBE(theme): in the light theme the sheet window draws edge to edge, so the scrim also
        // dims the status-bar band (a fitted window left it as an undimmed white strip over the
        // dimmed page, or over the player's black video band) and its icons are light over it; the
        // sheet's surface runs under the navigation bar, whose icons keep the theme's. Only the
        // appearance changes: the bars' visibility stays the caller's. The dark theme keeps its
        // fitted #0F0F0F band, as it always had.
        if (com.newtube.mobile.ui.common.ThemeMode.isLight(this)) {
            android.view.Window window = getWindow();
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            // Below Android 8.1 the navigation buttons can't turn dark: keep the black bar there.
            window.setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? android.graphics.Color.TRANSPARENT
                    : com.newtube.mobile.ui.common.ThemeMode.color(this, R.attr.newtubeColorNavigationBar));
            androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                    .setAppearanceLightStatusBars(false);
        }

        // Anchor the content to the bottom and size it to its content.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM;
        mContent.setLayoutParams(lp);

        // Let the list wrap its content but cap it so a long sheet scrolls instead of overrunning.
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) mRecyclerView.getLayoutParams();
        rlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        rlp.weight = 0;
        mRecyclerView.setLayoutParams(rlp);

        applyDialogInsets();

        // NEWTUBE(motion): the scrim fades in and the sheet slides up, decelerating. The sheet is
        // parked below the window BEFORE its first frame: starting the slide from a post() let the
        // first frame draw it fully open, then jump down and slide up (seen frame by frame on the
        // Pixel 9).
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.STANDARD).start();
        mContent.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        mContent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver observer = mContent.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                if (!mExiting) {
                    mContent.setTranslationY(mContent.getHeight());
                    mContent.animate().translationY(0f).setDuration(Motion.ENTER_MS)
                            .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
                }
                // NEWTUBE(sheet-landscape): the player sheet this was opened from leaves now.
                MobileSheets.notifyCoveringSheetShown();
                return true;
            }
        });
    }

    /**
     * NEWTUBE(motion): slide the sheet away (and fade the scrim), then finish. It used to vanish in
     * one frame. False = nothing to animate, finish at once. The window keeps its touches while it
     * leaves (a tap there is a no-op): passed through, a tap that opened a screen below would be
     * buried by this finish's parent routing a moment later, as in {@link #dismissSheet}.
     */
    private boolean animateSheetExit() {
        if (mExiting) {
            return true;
        }
        if (!mModeConfigured || mContent == null || !mContent.isLaidOut()
                || isFinishing() || isDestroyed()) {
            return false;
        }
        mExiting = true;
        mScrim.animate().cancel();
        mScrim.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.STANDARD).start();
        mContent.animate().cancel();
        mContent.animate().translationY(mContent.getHeight()).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(this::finishAfterExit).start();
        return true;
    }

    private void finishAfterExit() {
        if (mExiting) {
            mExiting = false;
            super.finish();
        }
    }

    /**
     * A follow-up {@link #show} while the sheet was leaving: keep this window for it. The dismissed
     * flow's levels are gone, so the new one is a root flow of its own - its sheet is set up again,
     * with its own entrance (Codex review of this change).
     */
    private void cancelSheetExit() {
        mExiting = false;
        mScrim.animate().cancel();
        mScrim.setAlpha(1f);
        mContent.animate().cancel();
        mContent.setTranslationY(0f);
        mModeConfigured = false;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        mIsPaused = false;
        MobileSheets.setPickerResumed(true);

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

    }

    /**
     * Bottom-sheet overlays must leave the caller's system-bar state untouched (a sheet opened
     * over the immersive landscape player must not pop the status bar in over the video), so the
     * standard mobile chrome is never applied here.
     */
    @Override
    protected void applyFullscreenModeIfNeeded() {
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Covered by the screen an item opened: nobody sees the rest of the slide.
        finishAfterExit();
    }

    @Override
    protected void onPause() {
        super.onPause();

        mIsPaused = true;
        MobileSheets.setPickerResumed(false);

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        // Not while recreating: onViewDestroyed() clears the presenter's copy of the dialog, which
        // the replacement instance re-shows from its onCreate.
        if (mPresenter != null && mPresenter.getView() == this && !isChangingConfigurations()) {
            mPresenter.onViewDestroyed();
        }
        if (isChangingConfigurations() && !mLevels.isEmpty()) {
            sRecreation = new RecreationState(new ArrayList<>(mLevels), new HashMap<>(mRadioOverrides), mIsTransparent);
        }

        super.onDestroy();
    }

    private void handleBack() {
        if (canGoBack()) {
            goBack();
        } else {
            dismissSheet();
        }
    }

    /**
     * NEWTUBE(motion): the person dismissed the sheet (scrim tap, Back): slide it away, then finish.
     * Only here, not in {@link #finish()}: that is the presenter's close, which an item's action
     * follows with the next screen - delayed, the finish's parent routing (startParentView with a
     * docked mini player) landed AFTER that launch and buried it (Share's chooser ended up under
     * Home on the emulator).
     */
    private void dismissSheet() {
        if (mExiting) {
            return;
        }
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }
        mLevels.clear();
        if (!animateSheetExit()) {
            super.finish();
        }
    }

    // ---------------------------------------------------------------------------------
    // AppDialogView
    // ---------------------------------------------------------------------------------

    @Override
    public void show(List<OptionCategory> categories, CharSequence title, boolean isExpandable, boolean isTransparent, boolean isOverlay, int id) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (mExiting) {
                cancelSheetExit();
            }

            // Only the root level can make the whole dialog transparent (mirrors AppDialogFragment.show()).
            boolean stackWasEmpty = mLevels.isEmpty();
            mIsTransparent = stackWasEmpty ? isTransparent : mIsTransparent;
            mIsOverlay = isOverlay;
            mId = id;

            // The sheet is set up on the root level (nested levels reuse it).
            if (stackWasEmpty) {
                configurePresentation();
            }

            // A recreated instance already holds its stack (onCreate); the presenter's re-show of
            // the top level after recreation is that same level, not a new one.
            if (!stackWasEmpty && mLevels.get(mLevels.size() - 1).categories == categories) {
                renderTopLevel(SCROLL_KEEP);
                return;
            }

            if (!stackWasEmpty && mRecyclerView.getLayoutManager() != null) {
                mLevels.get(mLevels.size() - 1).listState = mRecyclerView.getLayoutManager().onSaveInstanceState();
            }
            mLevels.add(new DialogLevel(categories, title));
            renderTopLevel(SCROLL_TOP);
        });
    }

    @Override
    public void finish() {
        // AppDialogView contract: always end the whole dialog flow (used by
        // AppDialogPresenter.closeDialog()), regardless of how many levels are pushed - see class
        // javadoc for why this is deliberately NOT the same as popping one level.
        // NEWTUBE(motion): at once, even over a sheet already sliding away (see dismissSheet).
        if (!mExiting && mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }
        mExiting = false;

        mLevels.clear();

        super.finish();
    }

    @Override
    public void goBack() {
        runOnUiThread(() -> {
            if (canGoBack()) {
                mLevels.remove(mLevels.size() - 1);
                renderTopLevel(SCROLL_RESTORE);
            } else {
                finish();
            }
        });
    }

    @Override
    public void clearBackstack() {
        mLevels.clear();
    }

    @Override
    public boolean canGoBack() {
        return mLevels.size() > 1;
    }

    @Override
    public boolean isShown() {
        return !mIsPaused && !isFinishing();
    }

    @Override
    public boolean isTransparent() {
        return mIsTransparent;
    }

    @Override
    public boolean isOverlay() {
        return mIsOverlay;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public int getViewId() {
        return mId;
    }
}
