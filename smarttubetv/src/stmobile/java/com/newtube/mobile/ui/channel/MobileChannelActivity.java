package com.newtube.mobile.ui.channel;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch Channel page (Wave 4a).
 *
 * <p>The touch replacement for the Leanback {@code ChannelFragment}/{@code ChannelActivity}.
 * {@link ChannelView} delivers MULTIPLE {@link VideoGroup}s (one per channel section:
 * Uploads, Playlists, Live, ...). Per the port's LEAN preference this renders them as ONE
 * vertically-scrolling {@link RecyclerView} (a {@link GridLayoutManager}) interleaving
 * full-span section headers with each section's videos laid out as a grid - NOT the TV's
 * nested horizontal-scroll carousels. See {@link ChannelSectionAdapter}.</p>
 *
 * <p>Drives the unchanged {@link ChannelPresenter} via the standard MVP seam. Routing is
 * fully natural: tapping a plain video plays it ({@code MobilePlaybackActivity}); tapping a
 * playlist/sub-channel item opens {@code MobileChannelUploadsActivity}/this screen again
 * (via {@code VideoActionPresenter.apply()}); long-press shows the context menu through the
 * Wave-3 {@code MobileAppDialogActivity}.</p>
 */
public class MobileChannelActivity extends MobileActivity implements ChannelView {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    /** A channel section, keyed by its {@link VideoGroup#getId()} so continuations/replacements
     *  for the same row merge into it instead of creating a duplicate header. */
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
    private ChannelSectionAdapter mAdapter;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private ImageButton mBackButton;

    /** Sections in delivery order. */
    private final Map<Integer, Section> mSections = new LinkedHashMap<>();
    /** Flat positions of each section header in the current display list, for {@link #setPosition}. */
    private final List<Integer> mHeaderPositions = new ArrayList<>();
    private Video mLastVideo;
    private int mLastPaginationTriggerCount = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel);

        bindViews();
        setupGrid();

        mBackButton.setOnClickListener(v -> onBackPressed());

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
        mProgressBar = findViewById(R.id.mobile_channel_progress);
        mTitleView = findViewById(R.id.mobile_channel_title);
        mBackButton = findViewById(R.id.mobile_channel_back);
    }

    private void setupGrid() {
        int spanCount = computeSpanCount();
        mLayoutManager = new GridLayoutManager(this, spanCount);
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // Headers occupy a whole row; video cards take a single cell.
                return mAdapter != null && mAdapter.isHeader(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mAdapter = new ChannelSectionAdapter(this::onVideoClicked, this::onVideoLongClicked);

        mGrid.setLayoutManager(mLayoutManager);
        mGrid.setAdapter(mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeTriggerPagination();
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
        if (mLastVideo == null || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            // Continues the last (bottom-most) section; the continuation arrives as an
            // ACTION_APPEND VideoGroup with the same id and merges back into it.
            mPresenter.onScrollEnd(mLastVideo);
        }
    }

    private int computeSpanCount() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float cardWidthPx = getResources().getDimension(R.dimen.mobile_card_target_width);
        float spacingPx = getResources().getDimension(R.dimen.mobile_card_spacing);

        int span = (int) (metrics.widthPixels / (cardWidthPx + spacingPx));

        return Math.max(2, span);
    }

    /** Rebuilds the flat header+video display list from the current sections. */
    private void rebuildList() {
        List<Object> flat = new ArrayList<>();
        mHeaderPositions.clear();
        mLastVideo = null;

        for (Section section : mSections.values()) {
            if (section.videos.isEmpty()) {
                continue;
            }

            mHeaderPositions.add(flat.size());
            flat.add(section.title != null ? section.title : "");
            flat.addAll(section.videos);
            mLastVideo = section.videos.get(section.videos.size() - 1);
        }

        mLastPaginationTriggerCount = -1;
        mAdapter.submit(flat);
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
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        super.onBackPressed();
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

            rebuildList();
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
            if (index >= 0 && index < mHeaderPositions.size()) {
                mGrid.scrollToPosition(mHeaderPositions.get(index));
            }
        });
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            mSections.clear();
            mHeaderPositions.clear();
            mLastVideo = null;
            mLastPaginationTriggerCount = -1;
            mAdapter.submit(new ArrayList<>());
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }
}
