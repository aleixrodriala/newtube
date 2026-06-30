package com.newtube.mobile.ui.dialog;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private static final class DialogLevel {
        final List<OptionCategory> categories;
        final CharSequence title;

        DialogLevel(List<OptionCategory> categories, CharSequence title) {
            this.categories = categories;
            this.title = title;
        }
    }

    private AppDialogPresenter mPresenter;
    private RecyclerView mRecyclerView;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private DialogRowAdapter mAdapter;

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
        mRecyclerView = findViewById(R.id.mobile_dialog_list);
        mTitleView = findViewById(R.id.mobile_dialog_title);
        mBackButton = findViewById(R.id.mobile_dialog_back);
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
        mAdapter.submit(level.categories, mRadioOverrides);
        mRecyclerView.scrollToPosition(0);
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

        // Full-screen Toolbar + list per this wave's scope (see class javadoc) - keep normal
        // system bars like every other mobile screen except the player.
        showSystemBars();
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
