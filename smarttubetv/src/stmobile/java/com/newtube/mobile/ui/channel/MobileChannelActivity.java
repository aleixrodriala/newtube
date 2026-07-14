package com.newtube.mobile.ui.channel;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch Channel page (Wave 4a; tabbed 2026-07-09).
 *
 * <p>The touch replacement for the Leanback {@code ChannelFragment}/{@code ChannelActivity}.
 * {@link ChannelView} delivers MULTIPLE {@link VideoGroup}s (one per channel section:
 * Videos, Live, Playlists, Shorts, ...). YouTube-style but pure Material: each section is a
 * {@link TabLayout} tab, and the selected section renders below as the standard full-width
 * card feed (the same {@link VideoCardAdapter} as Home/Search) — no nested carousels, no
 * interleaved mega-list.</p>
 *
 * <p>Drives the unchanged {@link ChannelPresenter} via the standard MVP seam. Routing is
 * fully natural: tapping a plain video plays it ({@code MobilePlaybackActivity}); tapping a
 * playlist/sub-channel item opens {@code MobileChannelUploadsActivity}/this screen again
 * (via {@code VideoActionPresenter.apply()}); long-press/⋮ shows the context menu through
 * the Wave-3 {@code MobileAppDialogActivity}.</p>
 */
public class MobileChannelActivity extends MobileActivity implements ChannelView {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    /** A channel section, keyed by its {@link VideoGroup#getId()} so continuations/replacements
     *  for the same row merge into it instead of creating a duplicate tab. */
    private static final class Section {
        final int id;
        String title;
        final List<Video> videos = new ArrayList<>();

        Section(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private ChannelPresenter mPresenter;

    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private TabLayout mTabs;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private MobileMiniPlayerController mMiniPlayer;
    private boolean mAnimateMiniFromPlayer;

    /** Sections in delivery order; iteration order == tab order. */
    private final Map<Integer, Section> mSections = new LinkedHashMap<>();
    /** Group id of the section shown in the grid (its tab is selected). */
    private int mActiveSectionId = -1;
    private int mLastPaginationTriggerCount = -1;
    /** Guards against the tab-selected listener reacting to programmatic tab sync. */
    private boolean mSuppressTabCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        mAnimateMiniFromPlayer = MiniPlayerBridge.completePendingNavigation();
        if (mAnimateMiniFromPlayer) {
            // The live card supplies the spatial transition from the watch page; suppress the
            // channel window's generic alpha so the two motions do not stack.
            overridePendingTransition(0, 0);
        }
        setupGrid();
        setupTabs();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        mPresenter = ChannelPresenter.instance(this);

        Video channel = mPresenter.getChannel();
        if (channel != null) {
            // Prefer the channel/author name (e.g. "Ibai") over the source item's own title
            // (which, when a channel is opened from a video, is that video's title). Mirrors the
            // TV ChannelFragment header (Helpers.firstNonNull(author, title)).
            mTitleView.setText(Helpers.firstNonNull(channel.getAuthor(), channel.getTitle()));
        }

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_grid);
        mTabs = findViewById(R.id.mobile_channel_tabs);
        mProgressBar = findViewById(R.id.mobile_channel_progress);
        mTitleView = findViewById(R.id.mobile_channel_title);
        mBackButton = findViewById(R.id.mobile_channel_back);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

        // Channel rows (rare here) span the whole grid width in multi-column layouts.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.isFullSpan(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setHasFixedSize(true);
        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        mGrid.setAdapter(mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeTriggerPagination();
            }
        });
    }

    private void setupTabs() {
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (mSuppressTabCallback || !(tab.getTag() instanceof Integer)) {
                    return;
                }
                mActiveSectionId = (Integer) tab.getTag();
                showActiveSection(true);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                mGrid.scrollToPosition(0);
            }
        });
    }

    private void onVideoClicked(Video video) {
        if (mPresenter != null) {
            mPresenter.onVideoItemClicked(video);
        }
    }

    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    private void maybeTriggerPagination() {
        Section active = mSections.get(mActiveSectionId);
        if (active == null || active.videos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            // Continues the ACTIVE section; the continuation arrives as an ACTION_APPEND
            // VideoGroup with the same id and merges back into it.
            mPresenter.onScrollEnd(active.videos.get(active.videos.size() - 1));
        }
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    /** Sync the TabLayout with the current sections (delivery order), keeping the selection. */
    private void rebuildTabs() {
        mSuppressTabCallback = true;

        // Add missing tabs / fix titles in place; section order only ever grows by append.
        int index = 0;
        for (Section section : mSections.values()) {
            String title = section.title != null && !section.title.isEmpty()
                    ? section.title : getString(R.string.mobile_channel_tab_videos);

            TabLayout.Tab tab = index < mTabs.getTabCount() ? mTabs.getTabAt(index) : null;
            if (tab == null) {
                tab = mTabs.newTab();
                mTabs.addTab(tab, false);
            }
            if (!(tab.getTag() instanceof Integer) || (Integer) tab.getTag() != section.id) {
                tab.setTag(section.id);
            }
            if (!title.contentEquals(tab.getText() != null ? tab.getText() : "")) {
                tab.setText(title);
            }

            if (section.id == mActiveSectionId && !tab.isSelected()) {
                tab.select();
            }
            index++;
        }
        while (mTabs.getTabCount() > mSections.size()) {
            mTabs.removeTabAt(mTabs.getTabCount() - 1);
        }

        // Tab bar only earns its space with 2+ sections; a single section shows as a plain feed.
        mTabs.setVisibility(mSections.size() > 1 ? View.VISIBLE : View.GONE);

        mSuppressTabCallback = false;
    }

    /** Push the active section's videos into the grid. */
    private void showActiveSection(boolean scrollToTop) {
        Section active = mSections.get(mActiveSectionId);
        mLastPaginationTriggerCount = -1;
        mAdapter.submitList(active != null ? new ArrayList<>(active.videos) : new ArrayList<>());
        if (scrollToTop) {
            mGrid.scrollToPosition(0);
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        // REORDER_TO_FRONT may reuse an existing channel Activity, bypassing onCreate entirely.
        // Complete a watch-page channel handoff here as well so that path still becomes mini mode.
        if (MiniPlayerBridge.completePendingNavigation()) {
            mAnimateMiniFromPlayer = true;
            overridePendingTransition(0, 0);
        }

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(mAnimateMiniFromPlayer);
            mAnimateMiniFromPlayer = false;
        }
    }

    @Override
    protected void onPause() {
        if (mMiniPlayer != null) {
            mMiniPlayer.hide();
        }
        super.onPause();

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

    private void handleBack() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(computeSpanCount());
        }
    }

    // ---------------------------------------------------------------------------------
    // ChannelView
    // ---------------------------------------------------------------------------------

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            int id = group.getId();
            Section section = mSections.get(id);

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    section = new Section(id, group.getTitle());
                    section.videos.addAll(group.getVideos());
                    mSections.put(id, section);
                    break;
                case VideoGroup.ACTION_REMOVE:
                    if (section != null) {
                        section.videos.removeAll(group.getVideos());
                    }
                    break;
                case VideoGroup.ACTION_SYNC:
                    if (section != null) {
                        syncVideos(section, group.getVideos());
                    }
                    break;
                case VideoGroup.ACTION_PREPEND:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    }
                    section.videos.addAll(0, group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    if (section == null) {
                        section = new Section(id, group.getTitle());
                        mSections.put(id, section);
                    } else if ((section.title == null || section.title.isEmpty()) && group.getTitle() != null) {
                        section.title = group.getTitle();
                    }
                    appendNew(section, group.getVideos());
                    break;
            }

            // A SYNC/REMOVE for an unknown section (e.g. the just-watched video's position
            // sync arriving on resume before any section loaded) must not touch the map —
            // guard the empty case or iterator().next() throws.
            if (mSections.isEmpty()) {
                return;
            }

            // First section to arrive becomes the visible one.
            if (mActiveSectionId == -1 || !mSections.containsKey(mActiveSectionId)) {
                mActiveSectionId = mSections.keySet().iterator().next();
            }

            rebuildTabs();

            if (id == mActiveSectionId) {
                showActiveSection(false);
            }
        });
    }

    private void appendNew(Section section, List<Video> videos) {
        for (Video video : videos) {
            if (!section.videos.contains(video)) {
                section.videos.add(video);
            }
        }
    }

    private void syncVideos(Section section, List<Video> videos) {
        for (Video video : videos) {
            int idx = section.videos.indexOf(video);
            if (idx >= 0) {
                section.videos.set(idx, video);
            }
        }
    }

    @Override
    public void setPosition(int index) {
        runOnUiThread(() -> {
            if (index >= 0 && index < mTabs.getTabCount()) {
                TabLayout.Tab tab = mTabs.getTabAt(index);
                if (tab != null) {
                    tab.select(); // routes through the listener -> shows that section
                }
            }
        });
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            mSections.clear();
            mActiveSectionId = -1;
            mLastPaginationTriggerCount = -1;
            mSuppressTabCallback = true;
            mTabs.removeAllTabs();
            mTabs.setVisibility(View.GONE);
            mSuppressTabCallback = false;
            mAdapter.submitList(new ArrayList<>());
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }
}
