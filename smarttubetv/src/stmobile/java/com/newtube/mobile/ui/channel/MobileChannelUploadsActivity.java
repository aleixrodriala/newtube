package com.newtube.mobile.ui.channel;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    /** Playlist header row (absent for plain channel uploads) - see {@link PlaylistHeaderAdapter}. */
    private PlaylistHeaderAdapter mHeaderAdapter;

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

        applyOpenerTitle();

        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    /**
     * This activity is {@code singleTop} and every {@code startView()} adds
     * {@code FLAG_ACTIVITY_REORDER_TO_FRONT}, so opening a second playlist/channel REUSES this
     * instance: {@link #onCreate} never runs again and the toolbar would keep showing the
     * previous destination's name until a titled {@code VideoGroup} happens to arrive (the first
     * delivered group often has none, so the wrong title survived the whole content load -
     * "Watch later" rendered as "Recommended" on the emulator).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        applyOpenerTitle();
    }

    /**
     * Prefer the channel/playlist title the opener already knows ({@code mChannel} is set before
     * this view is started). The delivered {@link VideoGroup}s also carry a title and refine it.
     */
    private void applyOpenerTitle() {
        Video channel = mPresenter != null ? mPresenter.getChannel() : null;

        if (channel != null && channel.getTitle() != null) {
            mTitleView.setText(channel.getTitle());
        }

        applyHeader(channel);
    }

    /**
     * Hands the opener card to the header row - it already carries everything the header shows,
     * so no extra request is needed and the header is complete before the first item lands.
     *
     * <p>Only playlists get one. Channel uploads have no playlist behind them and would render
     * a header that just repeats the toolbar, so they keep the compact toolbar button.</p>
     */
    private void applyHeader(Video opener) {
        boolean isPlaylist = opener != null && opener.getPlaylistId() != null;

        mHeaderAdapter.setPlaylist(isPlaylist ? opener : null);
        applyToolbarTitleAlpha();
    }

    /**
     * The playlist name is already the biggest thing on screen while the header is in view, so
     * the toolbar copy of it only fades in as the header scrolls away - the YouTube playlist page
     * shows a bare back arrow over the cover for the same reason. Screens without a header
     * (channel uploads) keep the title visible at all times.
     */
    private void applyToolbarTitleAlpha() {
        if (!mHeaderAdapter.hasHeader()) {
            mTitleView.setAlpha(1f);
            return;
        }

        View header = mLayoutManager.findViewByPosition(0);
        float collapsed;

        if (header == null) {
            collapsed = 1f; // scrolled past it entirely
        } else if (header.getHeight() <= 0) {
            collapsed = 0f;
        } else {
            collapsed = Math.min(1f, Math.max(0f, -header.getTop() / (float) header.getHeight()));
        }

        // Hold at 0 until the header is mostly gone, then fade in over the last quarter.
        mTitleView.setAlpha(Math.max(0f, (collapsed - 0.75f) * 4f));
    }

    private void bindViews() {
        mGrid = findViewById(R.id.mobile_channel_uploads_grid);
        mProgressBar = findViewById(R.id.mobile_channel_uploads_progress);
        mTitleView = findViewById(R.id.mobile_channel_uploads_title);
        mBackButton = findViewById(R.id.mobile_channel_uploads_back);
        mPlayAllButton = findViewById(R.id.mobile_channel_uploads_play_all);
    }

    private void setupGrid() {
        int spanCount = computeSpanCount();
        mLayoutManager = new GridLayoutManager(this, spanCount);
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);
        mHeaderAdapter = new PlaylistHeaderAdapter(new PlaylistHeaderAdapter.Callbacks() {
            @Override
            public void onPlayAll() {
                playAll();
            }

            @Override
            public void onShuffle() {
                shuffle();
            }
        });

        // The header is one full-width row in front of the cards; in landscape the grid is
        // multi-column, so it has to claim every span or it would sit in the first cell.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return isHeaderPosition(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        // NOT setHasFixedSize: the header row makes the content height change with the data.
        mGrid.setAdapter(new ConcatAdapter(mHeaderAdapter, mAdapter));
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeTriggerPagination();
                applyToolbarTitleAlpha();
            }
        });
    }

    private boolean isHeaderPosition(int position) {
        return position == 0 && mHeaderAdapter.hasHeader();
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

    /** Start from the first playable item, carrying this destination's playlist context. */
    private void playAll() {
        Video first = findFirstPlayableVideo();

        if (first != null) {
            onVideoClicked(withPlaylistContext(first));
        }
    }

    private Video findFirstPlayableVideo() {
        for (Video video : mVideos) {
            if (video != null && video.hasVideo()) {
                return video;
            }
        }

        return null;
    }

    /**
     * Not every path fills the playlist id INTO the listed items: a playlist reached through a
     * card that also carries a video resolves via {@code getMetadataObserve() ->
     * findPlaylistRow()}, whose rows are plain suggestion items. Those items still belong to this
     * playlist - the opener knows its id - so borrow it, or "Play all" would either hide itself
     * or start a single video with no queue behind it.
     *
     * <p>Returns a COPY when it has to inject the id: {@code Video}'s identity is a composite
     * hash that includes the playlist, and the grid's list/diffing holds the original.</p>
     */
    private Video withPlaylistContext(Video video) {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        String playlistId = opener != null ? opener.getPlaylistId() : null;

        if (video.getPlaylistId() != null || playlistId == null) {
            return video;
        }

        Video copy = Video.from(video);
        copy.playlistId = playlistId;
        copy.playlistParams = opener.playlistParams;

        return copy;
    }

    /**
     * Starts a random item and turns the player's repeat mode to shuffle, so the REST of the
     * queue keeps shuffling too - randomizing only the first video would be a lie. That mode is
     * a persisted player setting (and stays on until changed), hence the confirmation.
     */
    private void shuffle() {
        Video random = findRandomPlayableVideo();

        if (random == null) {
            return;
        }

        PlayerData.instance(this).setPlaybackMode(PlayerConstants.PLAYBACK_MODE_SHUFFLE);
        // A toast, not a snackbar: the player opens in the same breath and takes this screen -
        // and any snackbar anchored to it - away before it could be read.
        MessageHelpers.showMessage(this, R.string.mobile_playlist_shuffle_on);

        onVideoClicked(withPlaylistContext(random));
    }

    /** Random pick among the rows in hand (the loaded page), never the "no items" case. */
    private Video findRandomPlayableVideo() {
        List<Video> playable = new ArrayList<>();

        for (Video video : mVideos) {
            if (video != null && video.hasVideo()) {
                playable.add(video);
            }
        }

        return playable.isEmpty() ? null : playable.get(new Random().nextInt(playable.size()));
    }

    /**
     * Visible whenever this destination can start a queue: either the items already carry the
     * playlist context, or the opener does (see {@link #withPlaylistContext}).
     *
     * <p>Play all lives in TWO places and only one shows at a time: the playlist header has its
     * own pair of buttons, so the compact toolbar button is for the header-less case (channel
     * uploads) - otherwise the same action would sit on screen twice.</p>
     */
    private void updatePlayAllVisibility() {
        Video opener = mPresenter != null ? mPresenter.getChannel() : null;
        boolean hasContext = findFirstPlayableVideo() != null
                && (hasPlaylistItem() || (opener != null && opener.getPlaylistId() != null));
        boolean hasHeader = mHeaderAdapter.hasHeader();

        mPlayAllButton.setVisibility(hasContext && !hasHeader ? View.VISIBLE : View.GONE);
        mHeaderAdapter.setActionsEnabled(hasContext);
    }

    private boolean hasPlaylistItem() {
        for (Video video : mVideos) {
            if (video != null && video.hasVideo() && video.getPlaylistId() != null) {
                return true;
            }
        }

        return false;
    }

    private void maybeTriggerPagination() {
        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        // Grid positions include the header row, so shift back into card space before comparing.
        int headerOffset = mHeaderAdapter.hasHeader() ? 1 : 0;
        int lastVisible = mLayoutManager.findLastVisibleItemPosition() - headerOffset;
        int itemCount = mAdapter.getItemCount();

        if (lastVisible < 0 || itemCount == 0) {
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
