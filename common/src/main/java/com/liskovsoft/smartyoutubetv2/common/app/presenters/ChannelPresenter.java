package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.ArrayList;
import java.util.List;

public class ChannelPresenter extends BasePresenter<ChannelView> implements VideoGroupPresenter {
    private static final String TAG = ChannelPresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static ChannelPresenter sInstance;
    private final BrowseProcessorManager mBrowseProcessor;
    private String mChannelId;
    private final List<List<MediaGroup>> mPendingGroups = new ArrayList<>();
    private Disposable mUpdateAction;
    private Disposable mScrollAction;
    private int mSortIdx;
    private Video mChannel;
    /** The running first-page load has put at least one item on screen. */
    private boolean mLoadDelivered;
    /** Pull-to-refresh over rows: the old rows stay until the first fresh batch replaces them. */
    private boolean mReplaceOnFirstRows;

    private interface OnChannelId {
        void onChannelId(String channelId);
    }

    public interface OnUploadsRow {
        void onUploadsRow(Observable<MediaGroup> row);
    }

    public ChannelPresenter(Context context) {
        super(context);
        mBrowseProcessor = new BrowseProcessorManager(getContext(), this::syncItem);
    }

    public static ChannelPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new ChannelPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();

        if (mChannelId != null) {
            getView().clear();
            updateRows(obtainChannelObservable(mChannelId));
        } else if (!mPendingGroups.isEmpty()) {
            getView().clear();
            for (List<MediaGroup> group : mPendingGroups) {
                updateRows(group);
            }
            mPendingGroups.clear();
        }
    }

    @Override
    public void onViewDestroyed() {
        super.onViewDestroyed();
        disposeActions();
    }

    @Override
    public void onFinish() {
        super.onFinish();

        // Destroy the cache only (!) when user pressed back (e.g. wants to explicitly kill the activity)
        // Otherwise keep the cache to easily restore in case activity is killed by the system.
        mChannelId = null;
        mPendingGroups.clear();
        disposeActions();
    }

    @Override
    public void onVideoItemSelected(Video item) {
        // NOP
    }

    @Override
    public void onVideoItemClicked(Video item) {
        VideoActionPresenter.instance(getContext()).apply(item);
    }

    @Override
    public void onVideoItemLongClicked(Video item) {
        VideoMenuPresenter.instance(getContext()).showMenu(item);
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        if (item.getGroup() == null) {
            Log.e(TAG, "Can't scroll. Video group is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        Log.d(TAG, "onScrollEnd: Group title: " + group.getTitle());

        continueGroup(group);
    }

    @Override
    public boolean hasPendingActions() {
        return RxHelper.isAnyActionRunning(mScrollAction, mUpdateAction);
    }

    public static boolean canOpenChannel(Video item) {
        if (item == null) {
            return false;
        }

        return item.videoId != null || item.channelId != null || item.belongsToChannelUploads();
    }

    public void openChannel(Video item) {
        mChannel = item;
        extractChannelId(item, this::openChannel);
    }

    public void openChannel(String channelId) {
        if (channelId == null) {
            return;
        }

        disposeActions();

        mChannelId = channelId;

        if (getView() != null) {
            getView().clear();
            updateRows(obtainChannelObservable(channelId));
            // Fix double results. Prevent from doing the same in onViewInitialized()
            //mChannelId = null;
        }

        getViewManager().startView(ChannelView.class);
    }

    public String getChannelId() {
        return mChannel != null && mChannel.channelId != null ? mChannel.channelId : mChannelId;
    }

    public void setChannelId(String channelId) {
        mChannelId = channelId;
    }

    public Video getChannel() {
        return mChannel;
    }

    public void setChannel(Video channel) {
        mChannel = channel;
    }

    private void disposeActions() {
        RxHelper.disposeActions(mUpdateAction, mScrollAction);
        getServiceManager().disposeActions();
        mSortIdx = 0;
        mBrowseProcessor.dispose();
    }

    private void updateRows(Observable<List<MediaGroup>> group) {
        loadRows(group, false);
    }

    /**
     * NEWTUBE(page-load-errors): the first-page load. It used to handle only onError - and only
     * with a log line - while the channel observable reports most failures as a plain onComplete
     * with nothing emitted (a {@code null} answer, see {@link LoadFailure}), which left the
     * spinner up over a blank page forever. Both ends now reach {@link #finishLoad}.
     */
    private void loadRows(Observable<List<MediaGroup>> groups, boolean keepContentUntilLoaded) {
        Log.d(TAG, "updateRows: Start loading...");

        disposeActions();

        if (getView() == null) {
            return;
        }

        mLoadDelivered = false;
        mReplaceOnFirstRows = keepContentUntilLoaded;

        getView().showProgressBar(true);

        mUpdateAction = groups
                .subscribe(
                        this::onRowsLoaded,
                        error -> {
                            Log.e(TAG, "updateRows error: %s", error.getMessage());
                            finishLoad(error);
                        },
                        () -> finishLoad(null)
                 );
    }

    private void onRowsLoaded(List<MediaGroup> mediaGroups) {
        if (mReplaceOnFirstRows && getView() != null) {
            // The refresh has an answer: swap the old rows out in the same frame the new ones land.
            mReplaceOnFirstRows = false;
            getView().clear();
        }

        if (containsItems(mediaGroups)) {
            mLoadDelivered = true;
        }

        updateRows(mediaGroups);
    }

    private void finishLoad(Throwable error) {
        mReplaceOnFirstRows = false;

        ChannelView view = getView();

        if (view == null) {
            return;
        }

        view.showProgressBar(false);

        if (!mLoadDelivered) {
            view.showLoadFailure(LoadFailure.classify(getContext(), error));
        }
    }

    private static boolean containsItems(List<MediaGroup> mediaGroups) {
        if (mediaGroups == null) {
            return false;
        }

        for (MediaGroup mediaGroup : mediaGroups) {
            if (mediaGroup != null && mediaGroup.getMediaItems() != null && !mediaGroup.getMediaItems().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * NEWTUBE(page-load-errors): runs this page's first load again for the same channel - the
     * failure state's Try again and pull-to-refresh. The previous load and any in-flight next page
     * are dropped first, and the fresh first page brings its own continuation keys, so nothing is
     * appended twice.
     *
     * @param keepContentUntilLoaded pull-to-refresh over rows: they stay until fresh rows replace
     *                               them, and stay for good if the reload fails
     * @return {@code false} when there is nothing to reload (no view, or no channel to ask for)
     */
    public boolean reload(boolean keepContentUntilLoaded) {
        if (getView() == null) {
            return false;
        }

        Observable<List<MediaGroup>> source = obtainReloadObservable();

        if (source == null) {
            return false;
        }

        if (!keepContentUntilLoaded) {
            getView().clear();
        }

        loadRows(source, keepContentUntilLoaded);

        return true;
    }

    private Observable<List<MediaGroup>> obtainReloadObservable() {
        if (mChannelId != null) {
            return obtainChannelObservable(mChannelId);
        }

        // Rows handed in from outside (MediaServiceManager.chooseChannelPresenter, mChannelId null):
        // ask the way it did - loadChannelRows() prefers the card's MediaItem (title + params).
        if (mChannel != null && mChannel.channelId != null) {
            return mChannel.mediaItem != null
                    ? getContentService().getChannelObserve(mChannel.mediaItem)
                    : obtainChannelObservable(mChannel.channelId);
        }

        return null;
    }

    public Observable<List<MediaGroup>> obtainChannelObservable(String channelId) {
        return getContentService().getChannelObserve(channelId);
    }

    public void updateRows(List<MediaGroup> mediaGroups) {
        if (getView() == null) { // starting from outside (e.g. MediaServiceManager)
            mChannelId = null;
            mPendingGroups.add(mediaGroups);
            getViewManager().startView(ChannelView.class);
            return;
        }

        // The view could be running in the background
        getViewManager().startView(ChannelView.class);

        for (MediaGroup mediaGroup : mediaGroups) {
            if (mediaGroup.getMediaItems() == null) {
                Log.e(TAG, "updateRowsHeader: MediaGroup is empty. Group Name: " + mediaGroup.getTitle());
                continue;
            }

            VideoGroup group = VideoGroup.from(mediaGroup);
            getView().update(group);
            mBrowseProcessor.process(group);
        }

        getView().showProgressBar(false);
    }

    private void continueGroup(VideoGroup group) {
        boolean scrollInProgress = mScrollAction != null && !mScrollAction.isDisposed();

        if (scrollInProgress) {
            return;
        }

        if (getView() == null) {
            Log.e(TAG, "Can't continue group. The view is null.");
            return;
        }

        if (group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        MediaGroup mediaGroup = group.getMediaGroup();

        // Last page: there is nothing to ask for. The service would answer null, which now reads
        // as a failed page (showLoadMoreFailure) - same guard as ChannelUploadsPresenter.
        if (mediaGroup == null || mediaGroup.getNextPageKey() == null) {
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        getView().showProgressBar(true);

        // A failed page leaves this group's MediaGroup (and so its next-page key) untouched, so
        // retrying is just another onScrollEnd for the same section.
        mScrollAction = getContentService().continueGroupObserve(mediaGroup)
                .subscribe(
                        continueMediaGroup -> {
                            if (getView() == null) {
                                return;
                            }
                            VideoGroup newGroup = VideoGroup.from(group, continueMediaGroup);
                            getView().update(newGroup);
                            mBrowseProcessor.process(newGroup);
                        },
                        error -> {
                            Log.e(TAG, "continueGroup error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                                getView().showLoadMoreFailure();
                            }
                        },
                        () -> {
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        }
                );
    }

    /**
     * Sort channel content: move Uploads on top.
     */
    private void moveToTopIfNeeded(List<MediaGroup> mediaGroups) {
        moveToTop(mediaGroups, R.string.playlists_row_name);
        moveToTop(mediaGroups, R.string.popular_uploads_row_name);
        moveToTop(mediaGroups, R.string.uploads_row_name);
        moveToTop(mediaGroups, R.string.live_now_row_name);
    }

    private void moveToTop(List<MediaGroup> mediaGroups, int rowNameResId) {
        if (rowNameResId <= 0) {
            return;
        }

        String rowName = getContext().getString(rowNameResId);

        List<MediaGroup> group = Helpers.removeIf(mediaGroups, value -> rowName.equals(value.getTitle()));

        if (group != null) {
            mediaGroups.addAll(0, group);
        }
    }

    public void clear() {
        if (getView() != null) {
            getView().clear();
        }
        mChannel = null;
        mChannelId = null;
    }

    private void extractChannelId(Video item, OnChannelId callback) {
        if (item != null) {
            if (item.channelId != null) {
                callback.onChannelId(item.channelId);
            } else if (item.videoId != null) {
                LoadingManager.showLoading(getContext(), true);
                getServiceManager().loadMetadata(item, metadata -> {
                    LoadingManager.showLoading(getContext(), false);
                    callback.onChannelId(metadata.getChannelId());
                    item.channelId = metadata.getChannelId();
                },
                e -> LoadingManager.showLoading(getContext(), false),
                () -> LoadingManager.showLoading(getContext(), false));
            } else if (item.belongsToChannelUploads()) {
                LoadingManager.showLoading(getContext(), true);
                // Maybe this is subscribed items view
                ChannelUploadsPresenter.instance(getContext())
                        .obtainGroup(item, group -> {
                            LoadingManager.showLoading(getContext(), false);
                            // Some uploads groups doesn't contain channel button.
                            // Use data from first item instead.
                            if (group.getChannelId() == null) {
                                List<MediaItem> mediaItems = group.getMediaItems();

                                // Filter collaborative items
                                MediaItem first = Helpers.findFirst(mediaItems, mediaItem -> Helpers.startsWith(mediaItem.getAuthor(), item.getAuthor()));

                                if (first == null && mediaItems != null && !mediaItems.isEmpty()) {
                                    first = mediaItems.get(0);
                                }

                                if (first != null) {
                                    extractChannelId(Video.from(first), callback);
                                }

                                return;
                            }

                            callback.onChannelId(group.getChannelId());
                            item.channelId = group.getChannelId();
                        },
                        e -> LoadingManager.showLoading(getContext(), false),
                        () -> LoadingManager.showLoading(getContext(), false));
            }
        }
    }

    public void onSearchSettingsClicked() {
        Observable<List<MediaGroup>> sorting = getContentService().getChannelSortingOptionsObserve(getChannelId());
        Disposable result = sorting.subscribe(
                items -> {
                    AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(getContext());
                    List<OptionItem> options = new ArrayList<>();
                    int idx = 0;
                    for (MediaGroup group : items) {
                        final int tempIdx = idx;
                        options.add(UiOptionItem.from(group.getTitle(), item -> {
                            //dialogPresenter.closeDialog();
                            Observable<MediaGroup> continuation = getContentService().continueGroupObserve(group);
                            Disposable result2 = continuation.subscribe(mediaGroup -> {
                                if (getView() == null) {
                                    return;
                                }

                                VideoGroup replace = VideoGroup.from(mediaGroup);
                                replace.setId(144);
                                replace.setPosition(0);
                                replace.setAction(VideoGroup.ACTION_REPLACE);
                                getView().update(replace);
                                //getView().setPosition(1);
                                mSortIdx = tempIdx;
                            });
                        }, mSortIdx == idx));
                        idx++;
                    }
                    dialogPresenter.appendRadioCategory(getContext().getString(R.string.search_sorting), options);
                    dialogPresenter.showDialog();
                },
                error -> Log.e(TAG, "onSearchSettingsClicked error: %s", error.getMessage())
        );
    }

    public boolean onSearchSubmit(String query) {
        Observable<MediaGroup> search = getContentService().getChannelSearchObserve(getChannelId(), query);
        Disposable result = search.subscribe(
                items -> {
                    if (getView() == null) {
                        return;
                    }

                    VideoGroup update = VideoGroup.from(items);

                    if (update.isEmpty()) {
                        MessageHelpers.showMessage(getContext(), R.string.nothing_found);
                        return;
                    }

                    update.setId(112);
                    update.setPosition(0);
                    update.setAction(VideoGroup.ACTION_REPLACE);
                    getView().update(update);
                    getView().setPosition(1);
                },
                error -> Log.e(TAG, "onSearchSubmit error: %s", error.getMessage())
        );

        return true;
    }
}
