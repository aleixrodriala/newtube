package com.newtube.mobile.ui.browse;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;
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
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
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
    /** Single checkable group id for the navigation-drawer section list. */
    private static final int DRAWER_GROUP_ID = 1;

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
    private DrawerLayout mDrawerLayout;
    private NavigationView mNavView;
    private ProgressBar mProgressBar;
    private View mErrorContainer;
    private ImageView mErrorIcon;
    private TextView mErrorMessage;
    private MaterialButton mErrorAction;
    private ImageButton mSearchButton;
    private ImageButton mSettingsButton;
    private ImageButton mMenuButton;

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
        setupDrawer();
        setupErrorAction();
        setupSearchButton();
        setupSettingsButton();

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mContentGrid = findViewById(R.id.mobile_content_grid);
        mBottomNav = findViewById(R.id.mobile_bottom_nav);
        mDrawerLayout = findViewById(R.id.mobile_drawer_layout);
        mNavView = findViewById(R.id.mobile_nav_view);
        mProgressBar = findViewById(R.id.mobile_progress_bar);

        // Paint the status-bar strip (DrawerLayout draws it while fitsSystemWindows insets the toolbar
        // below the bar - see the layout comment) with the toolbar surface color so the top reads as
        // one solid bar rather than the theme's default colorPrimaryDark.
        mDrawerLayout.setStatusBarBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.mobile_color_surface));
        mErrorContainer = findViewById(R.id.mobile_error_container);
        mErrorIcon = findViewById(R.id.mobile_error_icon);
        mErrorMessage = findViewById(R.id.mobile_error_message);
        mErrorAction = findViewById(R.id.mobile_error_action);
        mSearchButton = findViewById(R.id.mobile_search_button);
        mSettingsButton = findViewById(R.id.mobile_settings_button);
        mMenuButton = findViewById(R.id.mobile_menu_button);
    }

    private void setupContentGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

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

    private void setupSettingsButton() {
        mSettingsButton.setOnClickListener(v -> openSettings());
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
                onSectionChosen(item.getItemId() - ITEM_ID_OFFSET);
            }
            return true;
        });
    }

    /**
     * All-sections access (Wave 7a). The bottom nav hard-caps at {@link #MAX_NAV_ITEMS},
     * but BrowsePresenter can deliver many more sections (Music, Gaming, News, ...) via
     * {@link #addSection}. The drawer lists EVERY delivered section (icon + title) and
     * routes a tap through the exact same {@link #onSectionChosen} path the bottom nav
     * uses, so a drawer-only section loads its content into the same grid.
     */
    private void setupDrawer() {
        mMenuButton.setOnClickListener(v -> mDrawerLayout.openDrawer(GravityCompat.START));

        mNavView.setNavigationItemSelectedListener(item -> {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            int sectionId = item.getItemId() - ITEM_ID_OFFSET;
            onSectionChosen(sectionId);
            // Return true to keep a browsable section visually selected in the drawer; return
            // false for Settings so it isn't left highlighted (it opens a dialog, not a grid).
            return sectionId != MediaGroup.TYPE_SETTINGS;
        });
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
        syncNavHighlight(sectionId);

        if (mPresenter != null) {
            mPresenter.onSectionFocused(sectionId);
        }
    }

    /** Highlight the given section in whichever nav surface(s) contain it (bottom nav + drawer). */
    private void syncNavHighlight(int sectionId) {
        int itemId = toMenuItemId(sectionId);

        if (mBottomNav.getMenu().findItem(itemId) != null && mBottomNav.getSelectedItemId() != itemId) {
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(itemId);
            mSuppressNavCallback = false;
        }

        if (mNavView.getMenu().findItem(itemId) != null) {
            mNavView.setCheckedItem(itemId);
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

    /** Rebuild both nav surfaces (bottom nav = quick access to the main 5; drawer = all). */
    private void rebuildNavigation() {
        rebuildBottomNav();
        rebuildDrawer();
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

        // Re-assert the highlight after clear()/add() wiped it, so the current section stays lit.
        if (mCurrentSectionId >= 0 && menu.findItem(toMenuItemId(mCurrentSectionId)) != null) {
            mBottomNav.setSelectedItemId(toMenuItemId(mCurrentSectionId));
        }

        mSuppressNavCallback = false;
    }

    /**
     * Rebuild the navigation drawer to list EVERY enabled section BrowsePresenter delivered
     * (icon via {@link BrowseSection#getResId()} + {@link BrowseSection#getTitle()}) - not just
     * the up-to-5 shown in the bottom nav. Settings is included so the drawer is a complete
     * index; {@link #onSectionChosen} routes it to the AppDialog settings screen.
     */
    private void rebuildDrawer() {
        Menu menu = mNavView.getMenu();
        menu.clear();

        int order = 0;
        for (BrowseSection section : mSections) {
            if (!section.isEnabled()) {
                continue;
            }

            android.view.MenuItem item =
                    menu.add(DRAWER_GROUP_ID, toMenuItemId(section.getId()), order++, section.getTitle());
            item.setCheckable(true);

            if (section.getResId() > 0) {
                item.setIcon(section.getResId());
            }

            // Per-row "..." overflow = the touch equivalent of the TV D-pad section long-press.
            // NavigationView menu rows don't expose a long-press, so hang the section-management menu
            // (Refresh / Rename / Move / Unpin / Mark watched / Create playlist / Toggle-Clear history)
            // off an actionView button that drives BrowsePresenter.onSectionLongPressed(sectionId) ->
            // SectionMenuPresenter -> AppDialogPresenter (rendered by MobileAppDialogActivity).
            addSectionOverflow(item, section.getId());
        }

        // Single-choice highlight: only one section can be the current one.
        menu.setGroupCheckable(DRAWER_GROUP_ID, true, true);

        if (mCurrentSectionId >= 0 && menu.findItem(toMenuItemId(mCurrentSectionId)) != null) {
            mNavView.setCheckedItem(toMenuItemId(mCurrentSectionId));
        }
    }

    /**
     * Attach a trailing "..." overflow button to a drawer row that opens the section-management
     * menu - the touch replacement for SmartTube's TV D-pad section long-press. Tapping it closes
     * the drawer and calls {@link BrowsePresenter#onSectionLongPressed(int)}, which builds the menu
     * via {@code SectionMenuPresenter} and shows it through {@code AppDialogPresenter} (rendered by
     * {@code MobileAppDialogActivity}). If the resulting menu is empty for a given section,
     * {@code SectionMenuPresenter} simply shows nothing (it guards on {@code !isEmpty()}).
     */
    private void addSectionOverflow(android.view.MenuItem item, int sectionId) {
        ImageButton overflow = (ImageButton) android.view.LayoutInflater.from(this)
                .inflate(R.layout.item_mobile_section_overflow, mNavView, false);
        overflow.setOnClickListener(v -> {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            if (mPresenter != null) {
                mPresenter.onSectionLongPressed(sectionId);
            }
        });
        item.setActionView(overflow);
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

    @Override
    public void onBackPressed() {
        // Back closes an open drawer first (standard Material drawer behavior); only then
        // does it fall through to MobileActivity's finish()/app-exit handling.
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return;
        }

        super.onBackPressed();
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

            mCurrentSectionId = section.getId();

            // The section may not be one of the (up to 5) sections shown in the bottom
            // nav - e.g. the sign-out boot fallback can select Music, which can be bumped
            // out by the preferred-5 selection. It IS always in the drawer, though. Still
            // drive the presenter so the grid loads; syncNavHighlight lights up whichever
            // surface(s) contain it and no-ops on the ones that don't.
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
            mContentGrid.setVisibility(View.GONE);
            mErrorContainer.setVisibility(View.VISIBLE);
            mErrorIcon.setVisibility(View.VISIBLE);

            String actionText = data != null ? data.getActionText() : null;
            boolean hasSignInAction = actionText != null && !actionText.isEmpty();

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
