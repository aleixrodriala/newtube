package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnComplete;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnError;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnMediaGroup;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.List;

public class ChannelUploadsPresenter extends BasePresenter<ChannelUploadsView> implements VideoGroupPresenter {
    private static final String TAG = ChannelUploadsPresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static ChannelUploadsPresenter sInstance;
    private final BrowseProcessorManager mBrowseProcessor;
    private Disposable mUpdateAction;
    private Disposable mScrollAction;
    private Video mChannel;
    private MediaGroup mPendingGroup;
    private VideoGroup mBaseGroup;
    /** The running first-page load has delivered its group (empty or not). */
    private boolean mLoadDelivered;
    /** Pull-to-refresh over items: the old items stay until the fresh group replaces them. */
    private boolean mReplaceOnFirstGroup;

    public ChannelUploadsPresenter(Context context) {
        super(context);
        mBrowseProcessor = new BrowseProcessorManager(getContext(), this::syncItem);
    }

    public static ChannelUploadsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new ChannelUploadsPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();

        refresh();
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
        disposeActions();
        mChannel = null;
        mPendingGroup = null;
        mBaseGroup = null;
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
        VideoMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
            if (action == VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST) {
                removeItem(videoItem);
            } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE) {
                MessageHelpers.showMessage(getContext(), R.string.unsubscribed_from_channel);
            }
        });
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        if (group == null) {
            Log.e(TAG, "Can't scroll. VideoGroup is null.");
            return;
        }

        Log.d(TAG, "onScrollEnd: Group title: " + group.getTitle());

        boolean scrollInProgress = mScrollAction != null && !mScrollAction.isDisposed();

        if (!scrollInProgress) {
            continueGroup(group);
        }
    }

    @Override
    public boolean hasPendingActions() {
        return RxHelper.isAnyActionRunning(mScrollAction, mUpdateAction);
    }

    public void openChannel(Video item) {
        // Working with uploads or playlists
        if (item == null || (!item.hasNestedItems() && !item.hasPlaylist())) {
            return;
        }

        clear();

        mChannel = item;

        getViewManager().startView(ChannelUploadsView.class);

        if (getView() != null) {
            update(item);
        }
    }

    public void obtainGroup(Video item, OnMediaGroup callback) {
        obtainGroup(item, callback, null, null);
    }

    public void obtainGroup(Video item, OnMediaGroup callback, OnError onError, OnComplete onComplete) {
        if (item != null && item.mediaItem != null) {
            obtainGroup(item.mediaItem, callback, onError, onComplete);
        }
    }

    public Observable<MediaGroup> obtainUploadsObservable(Video item) {
        if (item == null) {
            return null;
        }

        if (item.mediaItem == null) {
            item.mediaItem = SimpleMediaItem.from(item);
        }

        disposeActions();

        if (item.hasNestedItems() || item.isChannel() || (item.hasPlaylist() && !item.hasVideo())) {
            return getContentService().getGroupObserve(item.mediaItem);
        }

        if (item.hasReloadPageKey()) {
            return getContentService().getGroupObserve(item.getReloadPageKey());
        }

        // NEWTUBE(page-load-errors): no playlist row is "nothing here", not a crash -
        // Observable.just(null) threw an NPE into onError.
        return getMediaItemService().getMetadataObserve(item.videoId, item.playlistId, 0, item.playlistParams)
                .flatMap(mediaItemMetadata -> {
                    MediaGroup playlistRow = findPlaylistRow(mediaItemMetadata);
                    return playlistRow != null ? Observable.just(playlistRow) : Observable.empty();
                });
    }

    public Video getChannel() {
        return mChannel;
    }

    public void setChannel(Video channel) {
        mChannel = channel;
    }

    private void disposeActions() {
        RxHelper.disposeActions(mUpdateAction, mScrollAction);
        MediaServiceManager.instance().disposeActions();
        mBrowseProcessor.dispose();
    }

    private void continueGroup(VideoGroup group) {
        disposeActions();

        if (getView() == null) {
            Log.e(TAG, "Can't continue group. The view is null.");
            return;
        }

        if (group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        MediaGroup mediaGroup = group.getMediaGroup();
        if (mediaGroup == null || mediaGroup.getNextPageKey() == null) {
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        getView().showProgressBar(true);

        Observable<MediaGroup> continuation;

        continuation = getContentService().continueGroupObserve(mediaGroup);

        // A failed page leaves the group's MediaGroup (and so its next-page key) untouched, so
        // retrying is just another onScrollEnd.
        mScrollAction = continuation
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

    private void update(Video item) {
        load(item, false);
    }

    private void load(Video item, boolean keepContentUntilLoaded) {
        // Liked music fix - not all videos displayed. The behavior with other playlists is buggy.
        if (Helpers.equals(item.playlistId, Video.PLAYLIST_LIKED_MUSIC)) {
            if (keepContentUntilLoaded && getView() != null) {
                getView().clear(); // a local group: it can't fail, so there is nothing to keep
            }
            update(item.getGroup());
        } else {
            load(obtainUploadsObservable(item), keepContentUntilLoaded);
        }
    }

    /**
     * NEWTUBE(page-load-errors): the first-page load. A failure used to be a log line and a hidden
     * spinner over a blank grid; it now ends in {@link ChannelUploadsView#showLoadFailure}. Note
     * that delivering the group ends this subscription early ({@link #update(VideoGroup)} disposes
     * it), so onComplete only runs for a load that delivered nothing.
     */
    private void load(Observable<MediaGroup> group, boolean keepContentUntilLoaded) {
        Log.d(TAG, "update: Start loading a group...");

        disposeActions();

        if (getView() == null) {
            return;
        }

        mLoadDelivered = false;
        mReplaceOnFirstGroup = keepContentUntilLoaded;

        if (group == null) {
            finishLoad(null);
            return;
        }

        getView().showProgressBar(true);

        mUpdateAction = group
                .subscribe(
                        this::onGroupLoaded,
                        error -> {
                            Log.e(TAG, "update error: %s", error.getMessage());
                            finishLoad(error);
                        },
                        () -> {
                            if (!mLoadDelivered) {
                                finishLoad(null);
                            } else if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        }
                );
    }

    private void onGroupLoaded(MediaGroup mediaGroup) {
        mLoadDelivered = true;

        if (mReplaceOnFirstGroup && getView() != null) {
            // The refresh has an answer: swap the old items out in the same frame the new ones land.
            mReplaceOnFirstGroup = false;
            getView().clear();
            mBaseGroup = null;
        }

        update(mediaGroup);
    }

    private void finishLoad(Throwable error) {
        mReplaceOnFirstGroup = false;

        ChannelUploadsView view = getView();

        if (view == null) {
            return;
        }

        view.showProgressBar(false);
        view.showLoadFailure(LoadFailure.classify(getContext(), error));
    }

    public void update(MediaGroup mediaGroup) {
        // The view could be running in the background
        getViewManager().startView(ChannelUploadsView.class);

        if (getView() == null) { // starting from outside (e.g. MediaServiceManager)
            mPendingGroup = mediaGroup; // start loading from this group
            return;
        }

        mBaseGroup = mBaseGroup != null ? VideoGroup.from(mBaseGroup, mediaGroup) : VideoGroup.from(mediaGroup);
        if (mChannel != null && TextUtils.isEmpty(mBaseGroup.getTitle())) {
            mBaseGroup.setTitle(mChannel.getTitle());
        }
        update(mBaseGroup);
    }

    private void update(VideoGroup group) {
        disposeActions();

        if (getView() == null || group == null) {
            return;
        }

        getView().update(group);
        mBrowseProcessor.process(group);

        // Hide loading as long as first group received
        getView().showProgressBar(false);

        // NEWTUBE(page-load-errors): an answer with no items. The spinner used to stay up over a
        // blank grid forever here (disposeActions() above cancels the load before its onComplete),
        // so say "nothing here" instead. YouTube answered, so this is never an error.
        if (group.isEmpty()) {
            getView().showLoadFailure(LoadFailure.EMPTY);
        }
    }

    private void obtainGroup(MediaItem mediaItem, OnMediaGroup callback, OnError onError, OnComplete onComplete) {
        Log.d(TAG, "obtainGroup: Start loading group...");

        disposeActions();

        mUpdateAction = obtainUploadsObservable(Video.from(mediaItem))
                .subscribe(
                        callback::onMediaGroup,
                        error -> {
                            Log.e(TAG, "obtainGroup error: %s", error.getMessage());
                            if (onError != null) {
                                onError.onError(error);
                            }
                        },
                        () -> {
                            if (onComplete != null) {
                                onComplete.onComplete();
                            }
                        }
                );
    }

    /**
     * Playlist usually is the first row with media items.<br/>
     * NOTE: before playlist may be the video description row
     */
    private MediaGroup findPlaylistRow(MediaItemMetadata mediaItemMetadata) {
        if (mediaItemMetadata == null || mediaItemMetadata.getSuggestions() == null) {
            return null;
        }

        for (MediaGroup group : mediaItemMetadata.getSuggestions()) {
            List<MediaItem> mediaItems = group.getMediaItems();
            if (mediaItems != null && !mediaItems.isEmpty()) {
                return group;
            }
        }

        return null;
    }

    public void clear() {
        disposeActions();
        if (getView() != null) {
            getView().clear();
        }
        mChannel = null;
        mPendingGroup = null;
        mBaseGroup = null;
    }

    /**
     * NEWTUBE(page-load-errors): runs this list's first load again for the same destination - the
     * failure state's Try again and pull-to-refresh. The previous load and any in-flight next page
     * are dropped first, and the base group restarts from the fresh first page (with its own
     * continuation key), so nothing is appended twice.
     *
     * @param keepContentUntilLoaded pull-to-refresh over items: they stay until the fresh group
     *                               replaces them, and stay for good if the reload fails
     * @return {@code false} when there is nothing to reload (no view, or no destination)
     */
    public boolean reload(boolean keepContentUntilLoaded) {
        if (getView() == null || (mChannel == null && mPendingGroup == null)) {
            return false;
        }

        if (mChannel == null) {
            // Nothing to ask the network for: re-show the group that was handed in (local data,
            // it can't fail - so there is nothing to keep the old items for).
            getView().clear();
            mBaseGroup = null;
            update(mPendingGroup);
            return true;
        }

        if (!keepContentUntilLoaded) {
            getView().clear();
            mBaseGroup = null;
        }

        // A network refetch of the destination. A group handed in from outside (if any) is now
        // older than what this asks for, so a later refresh() must not re-show it.
        mPendingGroup = null;
        load(mChannel, keepContentUntilLoaded);

        return true;
    }

    public void refresh() {
        if (getView() == null) {
            return;
        }

        if (mPendingGroup != null) {
            getView().clear();
            update(mPendingGroup);
        } else if (mChannel != null) {
            getView().clear();
            update(mChannel);
        }
    }
}
