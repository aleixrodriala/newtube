package com.newtube.mobile.ui.dialog;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch renderer for {@link AppDialogView} - Wave 3 (ARCHITECTURE.md section 4, the
 * highest-leverage seam: one screen here lights up every settings category AND every
 * "..." context menu, since they all funnel through {@link AppDialogPresenter}).
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
 * goes through {@link #onBackPressed()} which pops via {@code goBack()} while
 * {@link #canGoBack()}, and only finishes the Activity at the root level.
 *
 * <p>{@link #finish()} (the {@link AppDialogView} contract method, called by
 * {@code AppDialogPresenter.closeDialog()} - used pervasively by context-menu items like
 * "Add to watch later" to dismiss the whole menu after one tap) is intentionally NOT the same
 * as "pop one level": it always tears down the entire dialog regardless of depth, matching
 * {@code AppDialogFragment.finish()} -> {@code Activity.finish()} on TV (which only special-cases
 * a level pop when the close was triggered by a physical/back-press, tracked there via
 * {@code mIsBackPressed}). Here that split is expressed by keeping level-popping entirely in
 * {@link #onBackPressed()}/{@link #goBack()}, separate from {@link #finish()}.
 *
 * <h3>Item kinds rendered</h3>
 * See {@link DialogRowAdapter} for the per-{@code OptionCategory.type} rendering (single-select
 * radio, multi-select checkbox, switch/toggle, plain button, and a read-only fallback for
 * long-text/chat/comments - the last two stubbed per ARCHITECTURE.md/this wave's scope).
 */
public class MobileAppDialogActivity extends MobileActivity implements AppDialogView {

    /**
     * Marker dialog id set by the mobile Settings entry point ({@code MobileBrowseActivity.openSettings()})
     * so this renderer knows to present the (large, multi-level) Settings tree FULL-SCREEN. Every other
     * caller - long-press context menus, the player Quality/Speed/Subtitles pickers, overlay dialogs -
     * uses the default BOTTOM-SHEET presentation. Nested Settings screens push new levels onto this same
     * activity instance, so the full-screen mode chosen for the root level naturally carries through them.
     * The value is deliberately far outside the small integer ids the common dialogs use (144, 565, ...).
     */
    public static final int ID_FULLSCREEN_SETTINGS = 0x4E540001;

    /** Bottom sheet is capped at this fraction of the screen height, then the list scrolls. */
    private static final float SHEET_MAX_HEIGHT_FRACTION = 0.72f;

    private static final class DialogLevel {
        final List<OptionCategory> categories;
        final CharSequence title;

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
    private ImageButton mBackButton;
    private DialogRowAdapter mAdapter;

    /** true = full-screen settings surface; false (default) = bottom sheet overlay. */
    private boolean mFullScreen;
    /** Presentation (sheet vs full-screen) is locked in on the first show() call. */
    private boolean mModeConfigured;

    private final List<DialogLevel> mLevels = new ArrayList<>();
    /** See {@link DialogRowAdapter#submit}. Keyed by category identity; stale entries from a
     *  since-discarded level are harmless (they simply never match a future category instance). */
    private final Map<OptionCategory, OptionItem> mRadioOverrides = new HashMap<>();

    private boolean mIsTransparent;
    private boolean mIsOverlay;
    private boolean mIsPaused = true;
    private int mId;

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
            renderTopLevel();
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
            renderTopLevel();
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

        bindViews();
        setupRecyclerView();

        mBackButton.setOnClickListener(v -> onBackPressed());

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
        mBackButton = findViewById(R.id.mobile_dialog_back);

        // Sheet mode: tapping the dim scrim dismisses the whole dialog (like a Material sheet).
        mScrim.setOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        mAdapter = new DialogRowAdapter(mRowListener);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);
    }

    private void renderTopLevel() {
        if (mLevels.isEmpty()) {
            return;
        }

        DialogLevel level = mLevels.get(mLevels.size() - 1);

        mTitleView.setText(level.title);
        // Show the back arrow when it can pop a level; in full-screen mode also at the root (it
        // closes Settings). A root-level bottom sheet has no back arrow - the scrim/back dismiss it.
        mBackButton.setVisibility(canGoBack() || mFullScreen ? View.VISIBLE : View.GONE);
        mAdapter.submit(level.categories, mRadioOverrides);
        mRecyclerView.scrollToPosition(0);
    }

    /**
     * Choose the presentation once, on the first {@link #show}. FULL-SCREEN only for the Settings
     * tree (tagged with {@link #ID_FULLSCREEN_SETTINGS}); everything else - context menus, the player
     * Quality/Speed/Subtitles pickers, transparent/overlay dialogs - is a bottom sheet.
     */
    private void configurePresentation(int id) {
        if (mModeConfigured) {
            return;
        }
        mModeConfigured = true;
        mFullScreen = (id == ID_FULLSCREEN_SETTINGS);

        if (mFullScreen) {
            configureFullScreen();
        } else {
            configureSheet();
        }
    }

    private void configureFullScreen() {
        mScrim.setVisibility(View.GONE);
        mHandle.setVisibility(View.GONE);
        mContent.setBackgroundColor(ContextCompat.getColor(this, R.color.mobile_color_background));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.NO_GRAVITY;
        mContent.setLayoutParams(lp);

        // List fills the window (no cap).
        mRecyclerView.setMaxHeight(0);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) mRecyclerView.getLayoutParams();
        rlp.height = 0;
        rlp.weight = 1;
        mRecyclerView.setLayoutParams(rlp);
    }

    private void configureSheet() {
        mScrim.setVisibility(View.VISIBLE);
        mHandle.setVisibility(View.VISIBLE);
        mContent.setBackgroundResource(R.drawable.bg_mobile_sheet);

        // Anchor the content to the bottom and size it to its content.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM;
        mContent.setLayoutParams(lp);

        // Let the list wrap its content but cap it so a long sheet scrolls instead of overrunning.
        int maxSheet = Math.round(getResources().getDisplayMetrics().heightPixels * SHEET_MAX_HEIGHT_FRACTION);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) mRecyclerView.getLayoutParams();
        rlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        rlp.weight = 0;
        mRecyclerView.setLayoutParams(rlp);
        mRecyclerView.setMaxHeight(maxSheet);

        // Enter animation: scrim fades in, sheet slides up.
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(180).start();
        mContent.post(() -> {
            mContent.setTranslationY(mContent.getHeight());
            mContent.animate().translationY(0f).setDuration(220).start();
        });
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        mIsPaused = false;

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }

        // Full-screen settings: restore the normal system bars like every other mobile screen.
        // Bottom-sheet overlays: leave the caller's system-bar state untouched (so a sheet opened
        // over the immersive landscape player doesn't pop the status bar in over the video).
        if (mFullScreen) {
            showSystemBars();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        mIsPaused = true;

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack()) {
            goBack();
        } else {
            finish();
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

            // Only the root level can make the whole dialog transparent (mirrors AppDialogFragment.show()).
            boolean stackWasEmpty = mLevels.isEmpty();
            mIsTransparent = stackWasEmpty ? isTransparent : mIsTransparent;
            mIsOverlay = isOverlay;
            mId = id;

            // Lock in sheet-vs-full-screen on the root level (nested levels inherit it).
            if (stackWasEmpty) {
                configurePresentation(id);
            }

            mLevels.add(new DialogLevel(categories, title));
            renderTopLevel();
        });
    }

    @Override
    public void finish() {
        // AppDialogView contract: always end the whole dialog flow (used by
        // AppDialogPresenter.closeDialog()), regardless of how many levels are pushed - see class
        // javadoc for why this is deliberately NOT the same as popping one level.
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        mLevels.clear();

        super.finish();
    }

    @Override
    public void goBack() {
        runOnUiThread(() -> {
            if (canGoBack()) {
                mLevels.remove(mLevels.size() - 1);
                renderTopLevel();
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
