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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.QueuePlaybackMode;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.FilteredPageTopUp;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.ShortsFilter;
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
    /** "Couldn't load more" row after the cards when a next page failed. */
    private LoadMoreFailureAdapter mLoadMoreFooter;
    private SwipeRefreshLayout mSwipe;
    /** First page failed / came back empty (see {@link #showLoadFailure}). */
    private PageLoadState mLoadState;

    private final List<Video> mVideos = new ArrayList<>();
    private int mLastPaginationTriggerCount = -1;
    /** NEWTUBE(shorts): pages this list fetches itself when its dropped Shorts left it short. */
    private final FilteredPageTopUp mTopUp = new FilteredPageTopUp();
    /** The group the last page came in (the anchor to continue when every card was a Short). */
    private VideoGroup mLastGroup;
    /** NEWTUBE(shorts): onDestroy has begun - the presenter's teardown must not page on. */
    private boolean mTornDown;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_channel_uploads);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        setupGrid();
        setupSwipeRefresh();

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
        // No header in view - none at all, or the grid (header included) gave way to the
        // failure state: the toolbar is the only place the name shows.
        if (!mHeaderAdapter.hasHeader() || mGrid.getVisibility() != View.VISIBLE) {
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
        // NEWTUBE(mini-inset): the last row can scroll clear of the docked mini-player card.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(findViewById(R.id.mobile_mini_player), mGrid);
        mProgressBar = findViewById(R.id.mobile_channel_uploads_progress);
        mTitleView = findViewById(R.id.mobile_channel_uploads_title);
        mBackButton = findViewById(R.id.mobile_channel_uploads_back);
        mPlayAllButton = findViewById(R.id.mobile_channel_uploads_play_all);
        mSwipe = findViewById(R.id.mobile_channel_uploads_swipe);
        mLoadState = new PageLoadState(findViewById(R.id.mobile_page_load_state), this::retryFirstPage);
    }

    private void setupGrid() {
        int spanCount = computeSpanCount();
        mLayoutManager = new GridLayoutManager(this, spanCount);
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);
        mAdapter.setFeedLayout(com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.instance(this).getMobileFeedLayout());
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

        mLoadMoreFooter = new LoadMoreFailureAdapter(this::retryLoadMore);

        // The header is one full-width row in front of the cards; in landscape the grid is
        // multi-column, so it has to claim every span or it would sit in the first cell. Same for
        // the "Couldn't load more" row after them.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return isHeaderPosition(position) || isFooterPosition(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        // NOT setHasFixedSize: the header row makes the content height change with the data.
        mGrid.setAdapter(new ConcatAdapter(mHeaderAdapter, mAdapter, mLoadMoreFooter));
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        // Grid positions are shifted by the optional playlist header row.
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mGrid, position -> {
            int card = position - (mHeaderAdapter.hasHeader() ? 1 : 0);
            return card >= 0 && card < mAdapter.getItemCount() ? mAdapter.getCurrentList().get(card) : null;
        });
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy == 0: a layout pass changed the visible range, not the user.
                maybeTriggerPagination(dy != 0);
                applyToolbarTitleAlpha();
            }
        });
    }

    private boolean isHeaderPosition(int position) {
        return position == 0 && mHeaderAdapter.hasHeader();
    }

    private boolean isFooterPosition(int position) {
        return mLoadMoreFooter.isFailed()
                && position == (mHeaderAdapter.hasHeader() ? 1 : 0) + mAdapter.getItemCount();
    }

    private void setupSwipeRefresh() {
        mSwipe.setColorSchemeColors(getResources().getColor(R.color.mobile_color_on_surface));
        mSwipe.setProgressBackgroundColorSchemeColor(getResources().getColor(R.color.mobile_color_surface));
        mSwipe.setOnRefreshListener(() -> {
            if (mPresenter == null || !mPresenter.reload(!mVideos.isEmpty())) {
                mSwipe.setRefreshing(false);
            }
        });
    }

    /** Failure state's Try again: the same first load, from a blank list. */
    private void retryFirstPage() {
        if (mPresenter != null) {
            mPresenter.reload(false);
        }
    }

    /** Footer's Try again: re-ask for the page that failed (its continuation key is unchanged). */
    private void retryLoadMore() {
        mLoadMoreFooter.setFailed(false);

        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        mLastPaginationTriggerCount = mAdapter.getItemCount();
        mPresenter.onScrollEnd(mVideos.get(mVideos.size() - 1));
    }

    private void hideLoadState() {
        if (!mLoadState.isShowing()) {
            return;
        }

        mLoadState.hide();
        mGrid.setVisibility(View.VISIBLE);
        // The header row is back: re-derive the title fade once it has been laid out.
        mGrid.post(this::applyToolbarTitleAlpha);
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
     * Starts a random item and keeps the REST of this queue shuffling too - randomizing only the
     * first video would be a lie.
     *
     * <p>Scoped to THIS playlist ({@link QueuePlaybackMode}), not written to the player's stored
     * repeat mode. Writing the stored mode is what it used to do, and it made one tap here change
     * every later video's behaviour until the user hunted down the repeat picker; the toast that
     * stood here existed only to warn about that. YouTube scopes shuffle to the queue, so there is
     * nothing lasting left to warn about - and a toast would be torn away anyway, since the player
     * opens in the same breath and takes this screen with it.</p>
     */
    private void shuffle() {
        Video random = findRandomPlayableVideo();

        if (random == null) {
            return;
        }

        Video opening = withPlaylistContext(random);
        QueuePlaybackMode.shuffle(opening.getPlaylistId());

        onVideoClicked(opening);
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

    private void maybeTriggerPagination(boolean userScroll) {
        // A failed next page waits for Try again instead of re-firing on every scroll frame.
        if (mVideos.isEmpty() || mPresenter == null || mLoadMoreFooter.isFailed()) {
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
            Video last = mVideos.get(mVideos.size() - 1);
            if (userScroll) {
                mTopUp.onUserAction(); // NEWTUBE(shorts): the user asked for more
            } else if (!mTopUp.takeAutomatic(last.getGroup() != null && last.getGroup().getNextPageKey() != null)) {
                return; // a layout pass, not the user: only within the top-up budget
            }
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(last);
        }
    }

    /**
     * NEWTUBE(shorts): with its Shorts dropped the list may be too short to scroll, and then
     * nothing would ever ask for its next page: fetch it now, within {@link FilteredPageTopUp}'s
     * budget - only right after a page landed (not after a cancel or a failure). Returns whether a
     * page was asked for.
     */
    private boolean topUpIfShort() {
        VideoGroup group = mLastGroup;
        List<Video> anchor = group != null ? group.getVideos() : null;
        if (mPresenter == null || mTornDown || isFinishing() || isDestroyed() || mLoadMoreFooter.isFailed()
                || anchor == null || anchor.isEmpty()) {
            return false;
        }
        if (!mTopUp.take(mVideos.size(), group.getNextPageKey() != null)) {
            return false;
        }
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("uploads-topup page=" + mTopUp.pages()
                + " items=" + mVideos.size());
        mPresenter.onScrollEnd(anchor.get(anchor.size() - 1));
        return true;
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
        // NEWTUBE(motion): a docked card stays up frozen until the player covers it.
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostPause();
        }
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostStop();
        }
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        mTornDown = true;

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
                    com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this, newConfig));
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

            List<Video> shown = ShortsFilter.withoutShorts(group.getVideos()); // NEWTUBE(shorts)
            if (group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_SYNC) {
                mLastGroup = group;
                mTopUp.onPageLanded((group.getVideos() != null ? group.getVideos().size() : 0)
                        - (shown != null ? shown.size() : 0));
            }
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mVideos.clear();
                    mVideos.addAll(shown);
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mVideos.addAll(0, shown);
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_SYNC:
                    syncVideos(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    appendNew(shown);
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            mAdapter.submitList(new ArrayList<>(mVideos));
            updatePlayAllVisibility();

            if (!mVideos.isEmpty()) {
                hideLoadState();
            }
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
            mTopUp.clear();
            mLastGroup = null;
            mLoadMoreFooter.setFailed(false);
            mAdapter.submitList(new ArrayList<>());
            updatePlayAllVisibility();
            hideLoadState();
        });
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            if (!show && topUpIfShort()) {
                return; // NEWTUBE(shorts): another page is on its way - still loading
            }
            // Pull-to-refresh draws its own spinner; don't stack the centered one over it.
            mProgressBar.setVisibility(show && !mSwipe.isRefreshing() ? View.VISIBLE : View.GONE);
            if (!show) {
                mSwipe.setRefreshing(false);
            }
        });
    }

    /**
     * NEWTUBE(page-load-errors): the first load put nothing on screen. Items still on screen (a
     * pull-to-refresh that failed) stay - stale items beat a full-page error, as on Home - with a
     * snackbar saying the refresh failed. Otherwise the grid, playlist header included, gives way
     * to the Home-style empty state with Try again; the toolbar keeps the name.
     */
    @Override
    public void showLoadFailure(int state) {
        runOnUiThread(() -> {
            mSwipe.setRefreshing(false);
            mProgressBar.setVisibility(View.GONE);

            if (!mVideos.isEmpty()) {
                if (state != LoadFailure.EMPTY) {
                    Snackbar.make(findViewById(android.R.id.content),
                            state == LoadFailure.NO_CONNECTION
                                    ? R.string.mobile_empty_no_connection : R.string.mobile_refresh_error,
                            Snackbar.LENGTH_SHORT).show();
                }
                return;
            }

            mGrid.setVisibility(View.GONE);
            mLoadState.show(state);
            applyToolbarTitleAlpha();
        });
    }

    /** NEWTUBE(page-load-errors): a next page failed - footer row with Try again, items stay. */
    @Override
    public void showLoadMoreFailure() {
        runOnUiThread(() -> mLoadMoreFooter.setFailed(true));
    }
}
