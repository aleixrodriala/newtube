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

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch Channel-Uploads list (Wave 4a).
 *
 * <p>Renders a MIX / PLAYLIST / CHART / channel-uploads destination as a single Material
 * RecyclerView GRID, mirroring {@code MobileBrowseActivity}'s grid setup (same
 * {@link VideoCardAdapter}, same runtime span-count math, same {@code onScrollEnd}
 * pagination), but with a Toolbar (title + back) instead of a bottom-nav shell. It is the
 * touch replacement for the Leanback {@code ChannelUploadsFragment}/{@code
 * ChannelUploadsActivity}.</p>
 *
 * <p>Drives the unchanged {@link ChannelUploadsPresenter} via the standard MVP seam
 * (setView/onViewInitialized + the {@code VideoGroupPresenter} input contract). The
 * presenter already has its target ({@code mChannel}/{@code mPendingGroup}) set by whoever
 * opened this view ({@code ChannelUploadsPresenter.openChannel()} /
 * {@code VideoActionPresenter.apply()}); {@code onViewInitialized()} -> {@code refresh()}
 * kicks off the actual content load against this freshly-created view, exactly like the TV
 * fragment.</p>
 *
 * <ul>
 *   <li>Tap a card -> {@link ChannelUploadsPresenter#onVideoItemClicked} ->
 *       {@code VideoActionPresenter.apply()} -> normal routing (plays a video via
 *       {@code MobilePlaybackActivity}; opens a nested playlist/sub-channel via the natural
 *       Channel(Uploads) routing).</li>
 *   <li>Long-press a card -> {@link ChannelUploadsPresenter#onVideoItemLongClicked} ->
 *       {@code VideoMenuPresenter}/{@code AppDialogPresenter}, rendered by the Wave-3
 *       {@code MobileAppDialogActivity}.</li>
 *   <li>Scroll near the end -> {@link ChannelUploadsPresenter#onScrollEnd} paginates.</li>
 * </ul>
 */
public class MobileChannelUploadsActivity extends MobileActivity
        implements ChannelUploadsView, MiniPlayerBridge.MiniHost {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;

    private ChannelUploadsPresenter mPresenter;

    /** Docks the live player card when a video opened from this list is minimized. */
    private MobileMiniPlayerController mMiniPlayer;

    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private ImageButton mBackButton;
    private MaterialButton mPlayAllButton;

    private final List<Video> mVideos = new ArrayList<>();
    private int mLastPaginationTriggerCount = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel_uploads);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        setupGrid();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mPlayAllButton.setOnClickListener(v -> playAll());

        mPresenter = ChannelUploadsPresenter.instance(this);

        // Prefer the channel/playlist title the opener already knows (set before this view
        // existed); the first delivered VideoGroup also carries a title and updates it.
        if (mPresenter.getChannel() != null) {
            mTitleView.setText(mPresenter.getChannel().getTitle());
        }

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_uploads_grid);
        mProgressBar = findViewById(R.id.mobile_channel_uploads_progress);
        mTitleView = findViewById(R.id.mobile_channel_uploads_title);
        mBackButton = findViewById(R.id.mobile_channel_uploads_back);
        mPlayAllButton = findViewById(R.id.mobile_channel_uploads_play_all);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

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

    /** Start from the first playable item that carries the playlist context. */
    private void playAll() {
        Video first = findFirstPlaylistVideo();
        if (first != null) {
            onVideoClicked(first);
        }
    }

    private Video findFirstPlaylistVideo() {
        for (Video video : mVideos) {
            if (video != null && video.hasVideo() && video.getPlaylistId() != null) {
                return video;
            }
        }

        return null;
    }

    private void updatePlayAllVisibility() {
        mPlayAllButton.setVisibility(findFirstPlaylistVideo() != null ? View.VISIBLE : View.GONE);
    }

    private void maybeTriggerPagination() {
        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(mVideos.get(mVideos.size() - 1));
        }
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
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
        // Last-resumed host wins: a video opened from this list minimizes back onto it.
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(false);
        }
    }

    @Override
    protected void onPause() {
        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video).
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
        MiniPlayerBridge.unregisterMiniHost(this);

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return ChannelUploadsView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // Overlay card (mobile_mini_player_overlay.xml) sits flush at the content bottom.
        return 0;
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
            mLayoutManager.setSpanCount(
                    com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig));
        }
    }

    // ---------------------------------------------------------------------------------
    // ChannelUploadsView
    // ---------------------------------------------------------------------------------

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            if (group.getTitle() != null && !group.getTitle().isEmpty()) {
                mTitleView.setText(group.getTitle());
            }

            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mVideos.clear();
                    mVideos.addAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mVideos.addAll(0, group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_SYNC:
                    syncVideos(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    appendNew(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            mAdapter.submitList(new ArrayList<>(mVideos));
            updatePlayAllVisibility();
        });
    }

    private void appendNew(List<Video> videos) {
        for (Video video : videos) {
            if (!mVideos.contains(video)) {
                mVideos.add(video);
            }
        }
    }

    private void syncVideos(List<Video> videos) {
        for (Video video : videos) {
            int idx = mVideos.indexOf(video);
            if (idx >= 0) {
                mVideos.set(idx, video);
            }
        }
    }

    @Override
    public void clear() {
        runOnUiThread(() -> {
            mVideos.clear();
            mLastPaginationTriggerCount = -1;
            mAdapter.submitList(new ArrayList<>());
            updatePlayAllVisibility();
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }
}
