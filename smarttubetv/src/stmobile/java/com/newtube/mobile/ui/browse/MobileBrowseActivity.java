package com.newtube.mobile.ui.browse;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

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
 * deliver 10+ sections (Home, Shorts, Trending, Subscriptions, History, Music,
 * Gaming, News, Playlists, Settings, ...). Rather than rebuild the section selector
 * (a scrollable tab strip is a later wave), this slice shows only the 5 main
 * sections - {@link #PREFERRED_SECTION_IDS} - in delivery order, falling back to
 * the next available non-Settings sections if any preferred one is missing. The
 * remaining sections are simply not shown in this slice (a "more"/drawer entry for
 * them is future work).
 *
 * Wave 2: tapping a card routes through {@link BrowsePresenter#onVideoItemClicked} into
 * the real touch player ({@code MobilePlaybackActivity}).
 */
public class MobileBrowseActivity extends MobileActivity implements BrowseView {
    /** BottomNavigationView item ids must be non-zero; BrowseSection ids start at 0. */
    private static final int ITEM_ID_OFFSET = 1_000_000;
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;
    /** BottomNavigationView hard-caps at this many items. */
    private static final int MAX_NAV_ITEMS = 5;

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
            MediaGroup.TYPE_USER_PLAYLISTS,
            MediaGroup.TYPE_TRENDING,
    };
    private static final int[] PREFERRED_SECTION_TITLE_RES = {
            R.string.header_home,
            R.string.header_subscriptions,
            R.string.header_history,
            R.string.header_playlists,
            R.string.header_trending,
    };

    private BrowsePresenter mPresenter;

    private RecyclerView mContentGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private BottomNavigationView mBottomNav;
    private ProgressBar mProgressBar;
    private View mErrorContainer;
    private TextView mErrorMessage;
    private MaterialButton mErrorAction;
    private ImageButton mSettingsButton;

    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<Video> mCurrentVideos = new ArrayList<>();
    private int mCurrentSectionId = -1;
    private boolean mProgressShowing;
    private boolean mSuppressNavCallback;
    private int mLastPaginationTriggerCount = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_browse);

        bindViews();
        setupContentGrid();
        setupBottomNav();
        setupErrorAction();
        setupSettingsButton();

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mContentGrid = findViewById(R.id.mobile_content_grid);
        mBottomNav = findViewById(R.id.mobile_bottom_nav);
        mProgressBar = findViewById(R.id.mobile_progress_bar);
        mErrorContainer = findViewById(R.id.mobile_error_container);
        mErrorMessage = findViewById(R.id.mobile_error_message);
        mErrorAction = findViewById(R.id.mobile_error_action);
        mSettingsButton = findViewById(R.id.mobile_settings_button);
    }

    private void setupContentGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

        mContentGrid.setLayoutManager(mLayoutManager);
        mContentGrid.setAdapter(mAdapter);
        mContentGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeTriggerPagination();
            }
        });
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
    private void setupSettingsButton() {
        mSettingsButton.setOnClickListener(v -> openSettings());
    }

    private void openSettings() {
        AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(this);
        dialogPresenter.clearBackstack();

        for (SettingsItem item : AppDataSourceManager.instance().getSettingItems(this)) {
            dialogPresenter.appendSingleButton(UiOptionItem.from(item.title, optionItem -> item.onClick.run()));
        }

        dialogPresenter.showDialog(getString(R.string.header_settings));
    }

    private void setupBottomNav() {
        mBottomNav.setOnItemSelectedListener(item -> {
            if (!mSuppressNavCallback && mPresenter != null) {
                mCurrentSectionId = item.getItemId() - ITEM_ID_OFFSET;
                mPresenter.onSectionFocused(mCurrentSectionId);
            }
            return true;
        });
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
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float cardWidthPx = getResources().getDimension(R.dimen.mobile_card_target_width);
        float spacingPx = getResources().getDimension(R.dimen.mobile_card_spacing);

        int span = (int) (metrics.widthPixels / (cardWidthPx + spacingPx));

        return Math.max(2, span);
    }

    private static int toMenuItemId(int sectionId) {
        return sectionId + ITEM_ID_OFFSET;
    }

    private void rebuildBottomNav() {
        mSuppressNavCallback = true;

        Menu menu = mBottomNav.getMenu();
        menu.clear();

        List<BrowseSection> navSections = selectNavSections();

        for (int i = 0; i < navSections.size(); i++) {
            BrowseSection section = navSections.get(i);

            android.view.MenuItem item = menu.add(Menu.NONE, toMenuItemId(section.getId()), i, section.getTitle());

            if (section.getResId() > 0) {
                item.setIcon(section.getResId());
            }
        }

        mSuppressNavCallback = false;
    }

    /**
     * BottomNavigationView hard-caps at {@link #MAX_NAV_ITEMS} items, but
     * BrowsePresenter can deliver many more sections. Pick the up-to-5 main sections
     * to show: the {@link #PREFERRED_SECTION_IDS}, in priority order, if present and
     * enabled; then fill any remaining slots with the next available enabled,
     * non-Settings sections in delivery order.
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

        for (BrowseSection section : enabledSections) {
            if (chosen.size() == MAX_NAV_ITEMS) {
                break;
            }

            if (!chosen.contains(section)) {
                chosen.add(section);
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

        // Wave-1 wart fix (ARCHITECTURE.md / ROADMAP Wave 2): MotherActivity goes
        // immersive-sticky by default - see MobileActivity.showSystemBars() for why.
        showSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(computeSpanCount());
        }
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

            rebuildBottomNav();
        });
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        runOnUiThread(() -> {
            Helpers.removeIf(mSections, existing -> existing.getId() == section.getId());
            rebuildBottomNav();
        });
    }

    @Override
    public void removeAllSections() {
        runOnUiThread(() -> {
            mSections.clear();
            rebuildBottomNav();
        });
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        runOnUiThread(() -> {
            if (index < 0 || index >= mSections.size()) {
                return;
            }

            BrowseSection section = mSections.get(index);
            mCurrentSectionId = section.getId();

            // The section may not be one of the (up to 5) sections shown in the bottom
            // nav - e.g. the sign-out boot fallback can select Music, which can be
            // bumped out by the preferred-5 selection. Still drive the presenter so the
            // grid loads; just skip the visual nav selection if there's no matching item.
            if (mBottomNav.getMenu().findItem(toMenuItemId(section.getId())) != null) {
                mSuppressNavCallback = true;
                mBottomNav.setSelectedItemId(toMenuItemId(section.getId()));
                mSuppressNavCallback = false;
            }

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

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mCurrentVideos.clear();
                    mCurrentVideos.addAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mCurrentVideos.addAll(0, group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mCurrentVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE_AUTHOR:
                    removeByAuthor(group.getVideos());
                    break;
                case VideoGroup.ACTION_SYNC:
                    syncVideos(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    appendNew(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to trigger again on the new size
            mAdapter.submitList(new ArrayList<>(mCurrentVideos));
        });
    }

    private void appendNew(List<Video> videos) {
        for (Video video : videos) {
            if (!mCurrentVideos.contains(video)) {
                mCurrentVideos.add(video);
            }
        }
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

    @Override
    public void showError(ErrorFragmentData data) {
        runOnUiThread(() -> {
            mContentGrid.setVisibility(View.GONE);
            mErrorContainer.setVisibility(View.VISIBLE);

            mErrorMessage.setText(data != null ? data.getMessage() : null);

            String actionText = data != null ? data.getActionText() : null;
            mErrorAction.setTag(data);

            if (data != null && actionText != null && !actionText.isEmpty()) {
                mErrorAction.setText(actionText);
                mErrorAction.setVisibility(View.VISIBLE);
            } else {
                mErrorAction.setVisibility(View.GONE);
            }
        });
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
        runOnUiThread(() -> mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE));
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
}
