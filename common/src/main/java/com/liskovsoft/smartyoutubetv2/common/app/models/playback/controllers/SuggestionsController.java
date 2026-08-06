package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.text.TextUtils;
import android.util.Pair;

import androidx.core.content.ContextCompat;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.ChapterItem;
import com.liskovsoft.mediaserviceinterfaces.data.DislikeData;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.ArrayList;
import java.util.List;

public class SuggestionsController extends BasePlayerController {
    private static final String TAG = SuggestionsController.class.getSimpleName();
    private final List<Disposable> mActions = new ArrayList<>();
    private MediaItemService mMediaItemService;
    private ContentService mContentService;
    private BrowseProcessorManager mBrowseProcessor;
    private Video mNextSectionVideo;
    private int mFocusCount;
    private int mNextRetryCount;
    private List<ChapterItem> mChapters;
    private final Runnable mChapterHandler = this::startChapterNotificationServiceIfNeededInt;
    private static final int MAX_PLAYLIST_CONTINUATIONS = 20;
    private static final int CHAPTER_NOTIFICATION_Id = 565;

    // NEWTUBE(mobile): the touch watch page flattens every suggestion row into one scrolling list
    // that pages itself on scroll (onScrollEnd), so the TV-style "top up small rows right away"
    // continuations are pure waste there - observed as ~9 parallel continueGroup calls per video
    // that all fail with "fromNullable result is null" and compete with the stream fetch for
    // bandwidth right at open. Set from MobileMainApplication only; TV keeps the default (false)
    // and is byte-for-byte unchanged.
    private static volatile boolean sRowContinuationsDisabled;
    // NEWTUBE(mobile): start the metadata/suggestions fetch at onNewVideo (parallel with the video
    // format fetch + engine load) instead of after onVideoLoaded. The watch page (title, counts,
    // related list) fills ~1 video-load earlier. The other controllers still get onMetadata at the
    // usual time (see mPendingListenerMetadata) because some of them touch the engine (e.g.
    // VideoStateController.onMetadata restores position/speed) and must not run before the new
    // stream is actually loaded. Set from MobileMainApplication only; TV default false.
    private static volatile boolean sEagerSuggestionsEnabled;
    // NEWTUBE(mobile): a COLD open (deep link, notification, a launch that starts on the player)
    // runs onNewVideo BEFORE the playback Activity exists, so the eager fetch above used to be
    // skipped there - exactly the open that needs it most, because nothing else is on the wire.
    // Firing it that early means the metadata can land while getPlayer() is still null, and every
    // delivery point below early-returns on a null player, so the watch page would silently stay
    // empty. Park the document instead and replay it from onInit(), which the Activity calls once
    // its watch UI is inflated (setContentView + setupWatchContent both run BEFORE
    // setView/onViewInitialized - see MobilePlaybackActivity.onCreate). False restores the old
    // wait-for-onVideoLoaded behaviour so both arms of a paired A/B run from one apk.
    private static volatile boolean sEagerColdOpenEnabled = true;
    private String mEagerVideoId;
    private boolean mEagerDelivered;
    private MediaItemMetadata mPendingListenerMetadata;
    private String mLoadedVideoId;
    private MediaItemMetadata mPendingViewMetadata;
    private Video mPendingViewVideo;
    /**
     * Park and replay both run on the main thread as things stand: the metadata callback is
     * delivered through {@code RxHelper.create}, which ends in
     * {@code observeOn(AndroidSchedulers.mainThread())}, and onInit() is called from the
     * Activity's onCreate. That ordering is exactly what makes the replay safe, so the pair is
     * fenced here instead of resting on an assumption a future scheduler change could quietly
     * invalidate - whichever side runs second must see the other's write, and both test
     * isPlayerAlive() INSIDE the lock so the answer cannot go stale between test and act.
     */
    private final Object mPendingViewLock = new Object();

    public static void setRowContinuationsDisabled(boolean disabled) {
        sRowContinuationsDisabled = disabled;
    }

    public static void setEagerSuggestionsEnabled(boolean enabled) {
        sEagerSuggestionsEnabled = enabled;
    }

    public static void setEagerColdOpenEnabled(boolean enabled) {
        sEagerColdOpenEnabled = enabled;
    }

    private interface OnVideoGroup {
        void onVideoGroup(VideoGroup group);
    }

    private interface OnMetadata {
        void onMetadata(MediaItemMetadata metadata);
    }

    @Override
    public void onInit() {
        mBrowseProcessor = new BrowseProcessorManager(getContext(), PlaybackPresenter.instance(getContext())::syncItem);
        mMediaItemService = YouTubeServiceManager.instance().getMediaItemService();
        mContentService = YouTubeServiceManager.instance().getContentService();

        // NEWTUBE(mobile): the player view exists now (the Activity sets it right before calling
        // this), so hand over anything the cold-open fetch had to park. See mPendingViewMetadata.
        deliverPendingViewMetadata();
    }

    /**
     * NEWTUBE(mobile): the services are cached in {@link #onInit}, but the eager fetch can run
     * before that on a cold open. Resolving them on demand costs nothing - the service manager
     * hands back process-wide singletons - and keeps every call site null-safe.
     */
    private MediaItemService mediaItemService() {
        if (mMediaItemService == null) {
            mMediaItemService = getMediaItemService();
        }

        return mMediaItemService;
    }

    private ContentService contentService() {
        if (mContentService == null) {
            mContentService = getContentService();
        }

        return mContentService;
    }

    private BrowseProcessorManager browseProcessor() {
        if (mBrowseProcessor == null) {
            mBrowseProcessor = new BrowseProcessorManager(getContext(), PlaybackPresenter.instance(getContext())::syncItem);
        }

        return mBrowseProcessor;
    }

    @Override
    public void onNewVideo(Video video) {
        // NEWTUBE(mobile): an error-recovery reload re-enters here with the SAME video (see
        // VideoLoaderController.mReloadVideo: 403 url-regen, engine restarts...). Its metadata is
        // already delivered and rendered - a re-fetch would visibly wipe and repopulate the related
        // list in the middle of the recovery, for identical data. Keep the suggestions; the engine
        // reload path doesn't need them repeated (position restore runs off the state service).
        // If the metadata never arrived (mEagerDelivered false) this doesn't trigger and the
        // classic dispose+reload below runs as always. TV (flag off) is untouched.
        if (sEagerSuggestionsEnabled && video != null && mEagerDelivered
                && Helpers.equals(video.videoId, mEagerVideoId)) {
            return;
        }

        // Remote control fix. Slow network fix. Suggestions may still be loading.
        // This could lead to changing current video info (title, id etc) to wrong one.
        disposeActions();
        //mCurrentGroup = video.getGroup(); // disable garbage collected
        //appendNextSectionVideoIfNeeded(video); // ConcurrentModificationException error

        // NEWTUBE(mobile): kick the metadata fetch NOW, in parallel with the format fetch and
        // engine load, instead of waiting for onVideoLoaded. See sEagerSuggestionsEnabled docs.
        // mMediaItemService null = onInit hasn't run yet, i.e. this is a COLD open (the playback
        // view is started right after this callback). That used to be skipped; it is now covered
        // by the park/replay mechanism - see sEagerColdOpenEnabled.
        mPendingListenerMetadata = null;
        mLoadedVideoId = null;
        mEagerDelivered = false;
        boolean canFetch = mMediaItemService != null || sEagerColdOpenEnabled;
        if (sEagerSuggestionsEnabled && video != null && video.hasVideo() && canFetch) {
            mEagerVideoId = video.videoId;
            loadSuggestions(video);
        } else {
            mEagerVideoId = null;
        }
    }

    /**
     * Improve video load time by running a fetch after load event
     */
    @Override
    public void onVideoLoaded(Video item) {
        // NEWTUBE(mobile): normally onInit already replayed this (it runs a whole video-load
        // earlier); this covers the case where the view arrived without an onInit of its own.
        deliverPendingViewMetadata();

        // NEWTUBE(mobile): the eager fetch from onNewVideo is either still in flight or already
        // delivered for this exact video - don't fetch the same document twice. If it FAILED
        // (not delivered, nothing running) fall through and reload the classic way.
        if (sEagerSuggestionsEnabled && item != null && Helpers.equals(item.videoId, mEagerVideoId)) {
            mLoadedVideoId = item.videoId;
            if (mEagerDelivered || RxHelper.isAnyActionRunning(mActions)) {
                // Engine is loaded now: release the held-back controller callback (see
                // updateSuggestions). If metadata is still in flight it's delivered on arrival.
                MediaItemMetadata pending = mPendingListenerMetadata;
                mPendingListenerMetadata = null;
                if (pending != null) {
                    callListener(pending);
                }
                return;
            }
        }

        mLoadedVideoId = item != null ? item.videoId : null;
        loadSuggestions(item);
    }

    // Could make negative impact on the video load time.
    //@Override
    //public void onSourceChanged(Video item) {
    //    loadSuggestions(item);
    //}

    @Override
    public void onEngineReleased() {
        disposeActions();
    }

    @Override
    public void onFinish() {
        disposeActions();
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        continueGroup(group);
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        markAsQueueIfNeeded(item);
    }

    @Override
    public void onControlsShown(boolean shown) {
        if (shown) {
            focusCurrentChapter();
        } else {
            startChapterNotificationServiceIfNeeded();
        }
    }

    @Override
    public void onSeekEnd() {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isControlsShown()) {
            focusCurrentChapter();
        } else {
            startChapterNotificationServiceIfNeeded();
        }
    }

    @Override
    public void onSeekPositionChanged(long positionMs) {
        if (getPlayer() != null && getPlayer().isControlsShown()) {
            updateSeekPreviewTitle(positionMs);
        }
    }

    @Override
    public void onTickle() {
        updateLiveDescription();
    }

    private void updateLiveDescription() {
        if (getPlayer() == null) {
            return;
        }

        Video video = getVideo();

        if (video == null || !video.isLive || RxHelper.isAnyActionRunning(mActions)) {
            return;
        }

        loadMetadata(video, metadata -> syncCurrentVideo(metadata, video));
    }

    private void continueGroup(VideoGroup group) {
        continueGroup(group, null, true);
    }

    private void continueGroup(VideoGroup group, boolean showLoading) {
        continueGroup(group, null, showLoading);
    }

    private void continueGroup(VideoGroup group, OnVideoGroup callback, boolean showLoading) {
        if (getPlayer() == null || group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        if (showLoading) {
            getPlayer().showProgressBar(true);
        }

        MediaGroup mediaGroup = group.getMediaGroup();

        Disposable continueAction = contentService().continueGroupObserve(mediaGroup)
                .subscribe(
                        continueMediaGroup -> {
                            getPlayer().showProgressBar(false);

                            VideoGroup videoGroup = VideoGroup.from(group, continueMediaGroup);
                            getPlayer().updateSuggestions(videoGroup);
                            browseProcessor().process(videoGroup);

                            mergeUserAndRemoteQueue(videoGroup);

                            if (callback != null) {
                                callback.onVideoGroup(videoGroup);
                            } else {
                                continueGroupIfNeeded(videoGroup);
                            }
                        },
                        error -> {
                            Log.e(TAG, "continueGroup error: %s", error.getMessage());
                            if (getPlayer() != null) {
                                getPlayer().showProgressBar(false);
                            }
                        },
                        () -> {
                            if (getPlayer() != null) {
                                getPlayer().showProgressBar(false);
                            }
                        }
                );

        mActions.add(continueAction);
    }

    private void syncCurrentVideo(MediaItemMetadata mediaItemMetadata, Video video) {
        if (getPlayer() == null) {
            return;
        }

        video.sync(mediaItemMetadata);
        getPlayer().setVideo(video);

        getPlayer().setNextTitle(getNext());

        appendDislikes(video);
    }

    public void loadSuggestions(Video video) {
        if (isEmbedPlayer()) {
            return;
        }

        if (sEagerSuggestionsEnabled) {
            NetPath.log(NetPath.context() + " suggest fetch +" + NetPath.elapsedMs()
                    + " view=" + (isPlayerAlive() ? "y" : "n"));
        }

        clearSuggestionsIfNeeded(video);
        loadMetadata(video, metadata -> updateSuggestions(metadata, video));
    }

    private void loadMetadata(Video video, OnMetadata callback) {
        disposeActions();

        if (video == null) {
            Log.e(TAG, "loadSuggestions: video is null");
            return;
        }

        Observable<MediaItemMetadata> observable;

        // NOTE: Load suggestions from mediaItem isn't robust. Because playlistId may be initialized from RemoteControlManager.
        // Video might be loaded from Channels section (has playlistParams)
        observable = mediaItemService().getMetadataObserve(video.videoId, video.getPlaylistId(), video.playlistIndex, video.playlistParams);

        Disposable metadataAction = observable
                .subscribe(
                        callback::onMetadata,
                        error -> {
                            // NEWTUBE(no-raw-toasts): this used to throw the raw exception text
                            // over the video ("loadSuggestions error: java.lang.IllegalStateException:
                            // java.net.UnknownHostException: ..."). It is the call site the
                            // remove-the-raw-toasts round missed, and it is the worst of them: it
                            // fires on every /next failure, and onNewVideo re-runs for every
                            // ErrorFixer recovery reload - so an outage printed a fresh stack over
                            // the player once per retry cycle, while the recovery stack was quietly
                            // doing the right thing underneath. The error surface is the player
                            // (title + notice); the diagnosis lives in the log.
                            Log.e(TAG, "loadSuggestions error: %s", error.getMessage());
                            error.printStackTrace();
                        }
                );

        mActions.add(metadataAction);
    }

    public Video getNext() {
        if (getPlayer() == null || getVideo() == null) {
            return null;
        }

        Video result = null;
        Video next = Playlist.instance().getNext();

        if (next != null) {
            next.fromQueue = true;
            result = next;
        } else if (mNextSectionVideo != null && !getVideo().isShuffled) {
            result = mNextSectionVideo;
        } else if (getVideo().nextMediaItem != null) {
            result = Video.from(getVideo().nextMediaItem);
        }

        return result;
    }

    public Video getPrevious() {
        if (getPlayer() == null || getVideo() == null) {
            return null;
        }

        Video result = getPreviousFromGroup(getVideo());

        if (result == null) {
            Video previous = Playlist.instance().getPrevious();

            if (previous != null) {
                previous.fromQueue = true;
                result = previous;
            }
        }

        return result;
    }

    private Video getPreviousFromGroup(Video current) {
        Video result = null;

        if (current != null) {
            VideoGroup group = current.getGroup();

            if (group != null && !group.isEmpty()) {
                Video previous = null;

                for (Video item : group.getVideos()) {
                    if (item.equals(current)) {
                        result = previous;
                        break;
                    }

                    if (item.hasVideo() && !item.isUpcoming) {
                        previous = item;
                    }
                }
            }
        }

        return result;
    }

    private void clearSuggestionsIfNeeded(Video video) {
        if (video == null || getPlayer() == null) {
            return;
        }

        // Frees a lot of memory
        if (video.isRemote || !getPlayer().isSuggestionsShown()) {
            getPlayer().clearSuggestions();
        }
    }

    private void updateSuggestions(MediaItemMetadata mediaItemMetadata, Video video) {
        // NEWTUBE(mobile): cold open - the playback Activity isn't up yet, so syncCurrentVideo,
        // appendSuggestions and onWatchMetadata below would all no-op against a null player and
        // the document would be lost. Park it; onInit replays this exact call. See the field docs.
        // isPlayerAlive() (not getPlayer() != null) because a view whose Activity is already
        // destroyed still answers every one of those calls - it just paints into nothing.
        if (sEagerSuggestionsEnabled && mediaItemMetadata != null && video != null) {
            synchronized (mPendingViewLock) {
                if (!isPlayerAlive()) {
                    mPendingViewMetadata = mediaItemMetadata;
                    mPendingViewVideo = video;
                    if (Helpers.equals(video.videoId, mEagerVideoId)) {
                        mEagerDelivered = true; // the document is in hand: don't refetch it
                    }
                    NetPath.log(NetPath.context() + " suggest parked +" + NetPath.elapsedMs());
                    return;
                }
            }
        }

        syncCurrentVideo(mediaItemMetadata, video);

        appendSuggestions(video, mediaItemMetadata);

        // After video suggestions.
        // NEWTUBE(mobile): on the eager path the metadata can arrive BEFORE the engine has loaded
        // the new stream. The UI work above is safe early, but the controller chain is not (e.g.
        // VideoStateController.onMetadata seeks/sets speed on the engine), so hold the callback
        // until onVideoLoaded releases it. Classic path (TV / eager off) is unchanged.
        if (sEagerSuggestionsEnabled && video != null && Helpers.equals(video.videoId, mEagerVideoId)) {
            mEagerDelivered = true;
            if (!Helpers.equals(video.videoId, mLoadedVideoId)) {
                mPendingListenerMetadata = mediaItemMetadata;
            } else {
                callListener(mediaItemMetadata);
            }
        } else {
            callListener(mediaItemMetadata);
        }

        // NEWTUBE(mobile-ttff): hand the SAME metadata document to the View so the touch watch-header
        // can bind from it instead of issuing a duplicate getMetadataObserve. No-op on TV (default
        // View method). Delivered via this callback (not MediaServiceManager's shared Disposable), so
        // the earlier watch-header race fix is preserved.
        if (getPlayer() != null) {
            getPlayer().onWatchMetadata(mediaItemMetadata);
        }

        if (sEagerSuggestionsEnabled) {
            NetPath.log(NetPath.context() + " suggest ready +" + NetPath.elapsedMs());
        }
    }

    /**
     * NEWTUBE(mobile): replay a metadata document that landed before the playback view existed.
     * Called from {@link #onInit} (the normal case, one whole video-load before the classic path
     * would even start fetching) and defensively from {@link #onVideoLoaded}. Nothing is cleared
     * unless it can actually be delivered, so a still-viewless call is a no-op rather than a drop.
     */
    private void deliverPendingViewMetadata() {
        MediaItemMetadata metadata;
        Video video;

        synchronized (mPendingViewLock) {
            if (mPendingViewMetadata == null || !isPlayerAlive()) {
                return;
            }

            metadata = mPendingViewMetadata;
            video = mPendingViewVideo;
            mPendingViewMetadata = null;
            mPendingViewVideo = null;
        }

        // Only for the open this document belongs to: a parked document from a previous open
        // would repaint the watch page with the wrong video.
        if (video == null || !Helpers.equals(video.videoId, mEagerVideoId)) {
            return;
        }

        NetPath.log(NetPath.context() + " suggest replay +" + NetPath.elapsedMs());

        updateSuggestions(metadata, video);
    }

    private void appendSuggestions(Video video, MediaItemMetadata mediaItemMetadata) {
        if (video == null || getPlayer() == null) {
            return;
        }

        if (!video.isRemote && getPlayer().isSuggestionsShown()) {
            Log.d(TAG, "Suggestions is opened. Seems that user want to stay here.");
            return;
        }

        getPlayer().clearSuggestions(); // clear previous videos

        appendChaptersIfNeeded(mediaItemMetadata);

        mergePlaybackAndRemoteQueueIfNeeded(video, mediaItemMetadata);

        appendSectionPlaylistIfNeeded(video);

        List<MediaGroup> suggestions = mediaItemMetadata.getSuggestions();

        if (suggestions == null) {
            String msg = "loadSuggestions: Can't obtain suggestions for video: " + video.getTitle();
            Log.e(TAG, msg);
            return;
        }

        int groupIndex = -1;
        int suggestRows = -1;

        if (GeneralData.instance(getContext()).isChildModeEnabled() || getPlayerTweaksData().isSuggestionsDisabled()) {
            suggestRows = video.hasPlaylist() ? 1 : 0;
        }

        for (MediaGroup group : suggestions) {
            groupIndex++;

            if (groupIndex == suggestRows) {
                break;
            }

            // Remove duplicated playlist
            if (groupIndex == 0 && video.isSectionPlaylistEnabled(getContext()) && video.belongsToSamePlaylistGroup()) {
                continue;
            }

            if (group != null && !group.isEmpty()) {
                VideoGroup videoGroup = VideoGroup.from(group);

                if (TextUtils.isEmpty(videoGroup.getTitle())) {
                    videoGroup.setTitle(getContext().getString(R.string.suggestions));
                    if (getPlayerTweaksData().isSuggestionsHorizontallyScrolled()) {
                        videoGroup.setId(videoGroup.getTitle().hashCode()); // merge by the id
                    }
                }

                getPlayer().updateSuggestions(videoGroup);
                browseProcessor().process(videoGroup);

                if (groupIndex == 0) {
                    focusAndContinueIfNeeded(videoGroup);
                } else {
                    continueGroupIfNeeded(videoGroup);
                }
            }
        }
    }

    /**
     * Merge remote queue with player's queue (when phone cast just started or user clicked on playlist item)
     */
    private void mergePlaybackAndRemoteQueueIfNeeded(Video video, MediaItemMetadata metadata) {
        // Ensure that the user pressed video thumb on the phone
        if (video.isRemote && video.remotePlaylistId != null) {
            // Create user queue from remote queue

            List<MediaGroup> suggestions = metadata.getSuggestions();

            if (suggestions != null && !suggestions.isEmpty()) {
                MediaGroup remoteRow = suggestions.get(0);

                VideoGroup remoteGroup = VideoGroup.from(remoteRow);

                suggestions.remove(remoteRow);

                appendRemoteQueueIfNeeded(video, remoteGroup);
            }
        } else {
            appendPlaybackQueueIfNeeded();
        }
    }

    private void mergeUserAndRemoteQueue(VideoGroup videoGroup) {
        if (getPlayer() == null || getVideo() == null)
            return;

        Video video = getVideo();
        if (videoGroup.isQueue) {
            Playlist.instance().addAll(videoGroup.getVideos());
            Playlist.instance().setCurrent(video);
        }
    }

    private void appendPlaybackQueueIfNeeded() {
        if (getPlayer() == null)
            return;

        Playlist playlist = Playlist.instance();

        if (playlist.hasNext()) {
            List<Video> queue = playlist.getAllAfterCurrent();

            VideoGroup videoGroup = VideoGroup.from(queue);
            videoGroup.setTitle(getContext().getString(R.string.action_playback_queue));
            videoGroup.setId(videoGroup.getTitle().hashCode());
            videoGroup.setType(MediaGroup.TYPE_PLAYBACK_QUEUE);

            getPlayer().updateSuggestions(videoGroup);
        }
    }

    private void appendRemoteQueueIfNeeded(Video video, VideoGroup remoteGroup) {
        if (getPlayer() == null)
            return;

        remoteGroup.removeAllBefore(video);
        remoteGroup.stripPlaylistInfo(); // prefer user queue even when a phone disconnected

        if (remoteGroup.contains(video)) {
            Playlist playlist = Playlist.instance();
            playlist.removeAllAfterCurrent();
            playlist.addAll(remoteGroup.getVideos());
            playlist.setCurrent(video);
        }

        remoteGroup.setTitle(getContext().getString(R.string.action_playback_queue));
        remoteGroup.setId(remoteGroup.getTitle().hashCode());
        remoteGroup.setType(MediaGroup.TYPE_PLAYBACK_QUEUE);
        remoteGroup.isQueue = true;

        remoteGroup.setAction(VideoGroup.ACTION_REPLACE);
        getPlayer().updateSuggestions(remoteGroup);

        if (!remoteGroup.contains(video) && remoteGroup.getSize() < 100) {
            continueGroup(remoteGroup, group -> appendRemoteQueueIfNeeded(video, group), false);
        }
    }

    private void addChapterMarkersIfNeeded() {
        if (getPlayer() == null || mChapters == null) {
            return;
        }

        getPlayer().setSeekBarSegments(toSeekBarSegments(mChapters));
    }

    private void appendChapterSuggestionsIfNeeded() {
        if (getPlayer() == null || mChapters == null) {
            return;
        }

        VideoGroup videoGroup = VideoGroup.fromChapters(mChapters, getContext().getString(R.string.chapters));

        getPlayer().updateSuggestions(videoGroup);
    }

    private void startChapterNotificationServiceIfNeeded() {
        if (getPlayerTweaksData().isChapterNotificationEnabled()) {
            Utils.postDelayed(mChapterHandler, 1_000); // small delay to give a chance to complete dialog transitions
        }
    }

    private void startChapterNotificationServiceIfNeededInt() {
        Utils.removeCallbacks(mChapterHandler);

        Pair<ChapterItem, Integer> currentChapter = getCurrentChapter();
        showChapterDialog(currentChapter != null ? currentChapter.first : null);

        if (mChapters == null) {
            return;
        }

        long positionMs = getPlayer().getPositionMs();

        ChapterItem chapter = getNextChapter();

        if (chapter != null) {
            Utils.postDelayed(mChapterHandler, (long) ((chapter.getStartTimeMs() - positionMs) * getPlayer().getSpeed()));
        }
    }

    private void appendChaptersIfNeeded(MediaItemMetadata mediaItemMetadata) {
        mChapters = mediaItemMetadata.getChapters();
        
        addChapterMarkersIfNeeded();
        appendChapterSuggestionsIfNeeded();
        startChapterNotificationServiceIfNeeded();
        focusCurrentChapter();
    }

    private void appendSectionPlaylistIfNeeded(Video video) {
        if (getPlayer() == null) {
            return;
        }

        if (!video.isSectionPlaylistEnabled(getContext())) {
            // Important fix. Gives priority to playlist or suggestion.
            mNextSectionVideo = null;
            return;
        }

        getPlayer().updateSuggestions(video.getGroup());
        focusAndContinueIfNeeded(video.getGroup(), () -> findNextSectionVideoIfNeeded(video));
    }

    private void markAsQueueIfNeeded(Video item) {
        List<Video> afterCurrent = Playlist.instance().getAllAfterCurrent();

        if (afterCurrent != null && afterCurrent.contains(item)) {
            item.fromQueue = true;
        }
    }

    private void focusCurrentChapter() {
        if (getPlayer() == null || !getPlayer().isControlsShown()) {
            return;
        }

        VideoGroup group = getPlayer().getSuggestionsByIndex(0);

        if (group == null || group.isEmpty() || !group.getVideos().get(0).isChapter) {
            return;
        }

        Pair<ChapterItem, Integer> currentChapter = getCurrentChapter();

        if (currentChapter != null) {
            getPlayer().focusSuggestedItem(currentChapter.second);
            getPlayer().setSeekPreviewTitle(currentChapter.first.getTitle());
        }
    }

    private void updateSeekPreviewTitle(long positionMs) {
        if (getPlayer() == null || !getPlayer().isControlsShown()) {
            return;
        }

        Pair<ChapterItem, Integer> currentChapter = getCurrentChapter(positionMs);

        if (currentChapter != null) {
            getPlayer().setSeekPreviewTitle(currentChapter.first.getTitle());
        }
    }

    private List<SeekBarSegment> toSeekBarSegments(List<ChapterItem> chapters) {
        if (chapters == null) {
            return null;
        }

        List<SeekBarSegment> result = new ArrayList<>();
        long markLengthMs = getPlayer().getDurationMs() / 10000;

        for (ChapterItem chapter : chapters) {
            if (chapter.getStartTimeMs() == 0) {
                continue;
            }

            SeekBarSegment seekBarSegment = new SeekBarSegment();
            float startRatio = (float) chapter.getStartTimeMs() / getPlayer().getDurationMs(); // Range: [0, 1]
            float endRatio = (float) (chapter.getStartTimeMs() + markLengthMs) / getPlayer().getDurationMs(); // Range: [0, 1]
            seekBarSegment.startProgress = startRatio;
            seekBarSegment.endProgress = endRatio;
            seekBarSegment.color = ContextCompat.getColor(getContext(), R.color.black);
            result.add(seekBarSegment);
        }

        return result;
    }

    /**
     * Most tiny ui has 8 cards in a row or 24 in grid.
     */
    private void continueGroupIfNeeded(VideoGroup group) {
        if (getPlayer() == null) {
            return;
        }

        // NEWTUBE(mobile): the touch related list pages itself on scroll - see field docs.
        if (sRowContinuationsDisabled) {
            return;
        }

        if (MediaServiceManager.instance().shouldContinueRowGroup(getContext(), group)) {
            continueGroup(group, getPlayer().isSuggestionsShown());
        }
    }

    private void focusAndContinueIfNeeded(VideoGroup group) {
       focusAndContinueIfNeeded(group, () -> {});
    }

    private void focusAndContinueIfNeeded(VideoGroup group, Runnable onDone) {
        if (getPlayer() == null) {
            return;
        }

        Video video = getVideo();

        if (group == null || group.isEmpty() || video == null || !video.hasVideo()) {
            return;
        }

        int index = group.getVideos().indexOf(video);

        if (index >= 0) { // continuation group starts with zero index
            Log.d(TAG, "Found current video index: %s", index);
            Video found = group.getVideos().get(index);
            if (!found.isMix() || video.isSectionPlaylistEnabled(getContext())) {
                getPlayer().focusSuggestedItem(found);
            }
            mFocusCount = 0; // Stop the continuation loop
            onDone.run();
        } else if (mFocusCount > MAX_PLAYLIST_CONTINUATIONS || !video.hasPlaylist()) {
            // Stop the continuation loop. Maybe the video isn't there.
            mFocusCount = 0;
            onDone.run();
        } else {
            // load more and repeat
            continueGroup(group, newGroup -> focusAndContinueIfNeeded(newGroup, onDone), getPlayer().isSuggestionsShown());
            mFocusCount++;
        }
    }

    private void findNextSectionVideoIfNeeded(Video video) {
        //if (getPlayerData().getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
        //    findRandomSectionVideo(video);
        //} else {
        //    findNextSectionVideo(video);
        //}

        findNextSectionVideo(video);
    }

    private void findRandomSectionVideo(Video video) {
        if (getPlayer() == null) {
            return;
        }

        mNextSectionVideo = null;

        VideoGroup group = video.getGroup();

        if (group == null || group.isEmpty()) {
            return;
        }

        int currentIdx = group.indexOf(video);

        int nextIdx = Utils.getRandomIndex(currentIdx, group.getSize());

        mNextSectionVideo = group.get(nextIdx);
        getPlayer().setNextTitle(mNextSectionVideo);
    }

    private void findNextSectionVideo(Video video) {
        if (getPlayer() == null) {
            return;
        }

        mNextSectionVideo = null;

        VideoGroup group = video.getGroup();

        if (group == null || group.isEmpty()) {
            return;
        }

        List<Video> videos = group.getVideos();
        boolean found = false;

        for (Video current : videos) {
            if (found && current.hasVideo() && !current.isUpcoming) {
                mNextRetryCount = 0;
                mNextSectionVideo = current;
                getPlayer().setNextTitle(mNextSectionVideo);
                return;
            }

            if (current.equals(video)) {
                found = true;
            }
        }

        if (mNextRetryCount > 0) {
            mNextRetryCount = 0;
        } else {
            continueGroup(group, continuation -> findNextSectionVideoIfNeeded(video), getPlayer().isSuggestionsShown());
            mNextRetryCount++;
        }
    }

    private void showChapterDialog(ChapterItem chapter) {
        AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(getContext());

        if (dialogPresenter.isDialogShown() && dialogPresenter.getId() != CHAPTER_NOTIFICATION_Id) {
            // Another dialog is opened. Don't distract a user.
            return;
        }

        if (dialogPresenter.isDialogShown() && getPlayer() != null && !getPlayer().isPlaying()) {
            return;
        }

        dialogPresenter.closeDialog(); // remove previous dialog

        if (chapter == null || getPlayer() == null || getPlayer().isOverlayShown() || getPlayer().isInPIPMode() ||
                Utils.isScreenOff(getContext())) {
            return;
        }

        OptionItem acceptOption = UiOptionItem.from(
                chapter.getTitle(),
                option -> {
                    // return to previous dialog or close if no other dialogs in stack
                    dialogPresenter.closeDialog();
                    ChapterItem nextChapter = getNextChapter();
                    getPlayer().setPositionMs(nextChapter != null ? nextChapter.getStartTimeMs() : getPlayer().getDurationMs());
                }
        );

        dialogPresenter.appendSingleButton(acceptOption);

        dialogPresenter.enableTransparent(true);
        dialogPresenter.enableOverlay(true);
        dialogPresenter.enableExpandable(false);
        dialogPresenter.setId(CHAPTER_NOTIFICATION_Id);
        dialogPresenter.showDialog();
    }

    private ChapterItem getNextChapter() {
        if (getPlayer() == null || mChapters == null) {
            return null;
        }

        long positionMs = getPlayer().getPositionMs();
        for (ChapterItem chapter : mChapters) {
            if (chapter.getStartTimeMs() > (positionMs + 3_000)) {
                return chapter;
            }
        }

        return null;
    }

    private Pair<ChapterItem, Integer> getCurrentChapter() {
        if (getPlayer() == null || mChapters == null) {
            return null;
        }

        return getCurrentChapter(getPlayer().getPositionMs());
    }

    private Pair<ChapterItem, Integer> getCurrentChapter(long positionMs) {
        if (mChapters == null) {
            return null;
        }

        ChapterItem currentChapter = null;
        int idx = -1;

        for (ChapterItem chapter : mChapters) {
            if (chapter.getStartTimeMs() > (positionMs + 3_000)) {
                break;
            }
            currentChapter = chapter;
            idx++;
        }

        return currentChapter != null ? new Pair<>(currentChapter, idx) : null;
    }

    private void callListener(MediaItemMetadata mediaItemMetadata) {
        if (mediaItemMetadata != null) {
            getMainController().onMetadata(mediaItemMetadata);
        }
    }

    private void disposeActions() {
        RxHelper.disposeActions(mActions);
        mChapters = null;
        mNextSectionVideo = null;
        mPendingListenerMetadata = null; // NEWTUBE(mobile): never deliver a stale held-back callback
        synchronized (mPendingViewLock) { // ...nor a parked document from an abandoned open
            mPendingViewMetadata = null;
            mPendingViewVideo = null;
        }
        if (mBrowseProcessor != null) {
            mBrowseProcessor.dispose();
        }
    }

    private void appendDislikes(Video video) {
        if (video == null) {
            return;
        }

        if (!getPlayerTweaksData().isLikesCounterEnabled()) {
            video.likeCount = null;
            video.dislikeCount = null;
            getPlayer().setVideo(video);
            return;
        }

        Observable<DislikeData> dislikeDataObserve = mediaItemService().getDislikeDataObserve(video.videoId);

        Disposable dislikeAction = dislikeDataObserve.subscribe(
                dislikeData -> {
                    video.sync(dislikeData);
                    getPlayer().setVideo(video);
                },
                error -> Log.e(TAG, "Dislike not working...")
        );

        mActions.add(dislikeAction);
    }
}
