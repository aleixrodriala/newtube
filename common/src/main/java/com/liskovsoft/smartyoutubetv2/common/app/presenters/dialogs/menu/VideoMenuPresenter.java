package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu;

import android.content.Context;
import android.os.SystemClock;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackReasons.FeedbackItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.CommentsController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.ContextMenuManager;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.ContextMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneUi;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.common.misc.StreamReminderService;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoMenuPresenter extends BaseMenuPresenter {
    private static final String TAG = VideoMenuPresenter.class.getSimpleName();
    private final MediaItemService mMediaItemService;
    private final AppDialogPresenter mDialogPresenter;
    private final MediaServiceManager mServiceManager;
    private Disposable mAddToPlaylistAction;
    private Disposable mNotInterestedAction;
    private Disposable mSubscribeAction;
    private Disposable mPlaylistsInfoAction;
    private Video mVideo;
    public static WeakReference<Video> sVideoHolder = new WeakReference<>(null);
    private boolean mIsNotInterestedButtonEnabled;
    private boolean mIsNotRecommendChannelEnabled;
    private boolean mIsBlockChannelEnabled;
    private boolean mIsRemoveFromHistoryButtonEnabled;
    private boolean mIsRemoveFromSubscriptionsButtonEnabled;
    private boolean mIsOpenChannelButtonEnabled;
    private boolean mIsOpenChannelUploadsButtonEnabled;
    private boolean mIsSubscribeButtonEnabled;
    private boolean mIsShareLinkButtonEnabled;
    private boolean mIsDownloadButtonEnabled;
    private boolean mIsShareQRLinkButtonEnabled;
    private boolean mIsShareEmbedLinkButtonEnabled;
    private boolean mIsAddToWatchLaterButtonEnabled;
    private boolean mIsAddToPlaylistButtonEnabled;
    private boolean mIsAddToRecentPlaylistButtonEnabled;
    private boolean mIsReturnToBackgroundVideoEnabled;
    private boolean mIsOpenPlaylistButtonEnabled;
    private boolean mIsAddToPlaybackQueueButtonEnabled;
    private boolean mIsPlayNextButtonEnabled;
    private boolean mIsShowPlaybackQueueButtonEnabled;
    private boolean mIsOpenDescriptionButtonEnabled;
    private boolean mIsOpenCommentsButtonEnabled;
    private boolean mIsPlayVideoButtonEnabled;
    private boolean mIsPlayVideoIncognitoButtonEnabled;
    private boolean mIsPlayFromStartButtonEnabled;
    private boolean mIsPlaylistOrderButtonEnabled;
    private boolean mIsStreamReminderButtonEnabled;
    private boolean mIsMarkAsWatchedButtonEnabled;
    private VideoMenuCallback mCallback;
    private List<PlaylistInfo> mPlaylistInfos;
    private final Map<Long, MenuAction> mMenuMapping = new HashMap<>();

    public interface VideoMenuCallback {
        int ACTION_UNDEFINED = 0;
        int ACTION_UNSUBSCRIBE = 1;
        int ACTION_REMOVE = 2;
        int ACTION_REMOVE_FROM_PLAYLIST = 3;
        int ACTION_REMOVE_FROM_QUEUE = 4;
        int ACTION_ADD_TO_QUEUE = 5;
        int ACTION_PLAY_NEXT = 6;
        int ACTION_REMOVE_AUTHOR = 7;
        void onItemAction(Video videoItem, int action);
    }

    public static class MenuAction {
        private final Runnable mAction;
        private final boolean mIsAuth;

        public MenuAction(Runnable action, boolean isAuth) {
            this.mAction = action;
            this.mIsAuth = isAuth;
        }

        public void run() {
            mAction.run();
        }

        public boolean isAuth() {
            return mIsAuth;
        }
    }

    private VideoMenuPresenter(Context context) {
        super(context);
        ServiceManager service = YouTubeServiceManager.instance();
        mMediaItemService = service.getMediaItemService();
        mServiceManager = MediaServiceManager.instance();
        mDialogPresenter = AppDialogPresenter.instance(context);

        initMenuMapping();
    }

    public static VideoMenuPresenter instance(Context context) {
        return new VideoMenuPresenter(context);
    }

    @Override
    protected Video getVideo() {
        return mVideo;
    }

    @Override
    protected AppDialogPresenter getDialogPresenter() {
        return mDialogPresenter;
    }

    @Override
    protected VideoMenuCallback getCallback() {
        return mCallback;
    }

    public void showMenu(Video video, VideoMenuCallback callback) {
        mCallback = callback;
        showMenu(video);
    }

    public void showMenu(Video video) {
        if (video == null) {
            return;
        }

        mVideo = video;
        sVideoHolder = new WeakReference<>(video);

        MediaServiceManager.instance().authCheck(this::bootstrapPrepareAndShowDialogSigned, this::prepareAndShowDialogUnsigned);
    }

    private void bootstrapPrepareAndShowDialogSigned() {
        mPlaylistInfos = null;
        RxHelper.disposeActions(mPlaylistsInfoAction);
        if (isAddToRecentPlaylistButtonEnabled() || isOwnPlaylistCandidate()) {
            mPlaylistsInfoAction = mMediaItemService.getPlaylistsInfoObserve(mVideo.videoId)
                    .subscribe(
                            videoPlaylistInfos -> {
                                mPlaylistInfos = videoPlaylistInfos;
                                prepareAndShowDialogSigned();
                            },
                            error -> {
                                Log.e(TAG, "Add to recent playlist error: %s", error.getMessage());
                                // NEWTUBE(#36): the playlist lookup only adds rows - a failed one
                                // must not swallow the whole menu (it used to show nothing).
                                if (PhoneUi.isEnabled()) {
                                    prepareAndShowDialogSigned();
                                }
                            }
                    );
        } else {
            prepareAndShowDialogSigned();
        }
    }

    private void prepareAndShowDialogSigned() {
        if (getContext() == null) {
            return;
        }

        runWithIcon("return_background", this::appendReturnToBackgroundVideoButton);

        for (Long menuItem : MainUIData.instance(getContext()).getMenuItemsOrdered()) {
            MenuAction menuAction = mMenuMapping.get(menuItem);
            if (menuAction != null) {
                runWithIcon(MENU_ICONS.get(menuItem), menuAction::run);
            }
        }

        if (!mDialogPresenter.isEmpty()) {
            String title = mVideo != null ? mVideo.getTitle() : null;
            // No need to add author because: 1) This could be a channel card. 2) This info isn't so important.
            mDialogPresenter.showDialog(title);
        }
    }

    private void prepareAndShowDialogUnsigned() {
        if (getContext() == null) {
            return;
        }

        runWithIcon("return_background", this::appendReturnToBackgroundVideoButton);

        for (Long menuItem : MainUIData.instance(getContext()).getMenuItemsOrdered()) {
            MenuAction menuAction = mMenuMapping.get(menuItem);
            if (menuAction != null && !menuAction.isAuth()) {
                runWithIcon(MENU_ICONS.get(menuItem), menuAction::run);
            }
        }

        if (!mDialogPresenter.isEmpty()) {
            String title = mVideo != null ? mVideo.getTitle() : null;
            mDialogPresenter.showDialog(title);
        }
    }

    /**
     * NEWTUBE(menu-icons): the kind of row each menu item appends ({@code OptionCategory.iconKey});
     * the phone draws an icon for each. Kept apart from {@code mMenuMapping} so neither has to know
     * about the other.
     */
    private static final java.util.Map<Long, String> MENU_ICONS = new java.util.HashMap<>();

    static {
        MENU_ICONS.put(MainUIData.MENU_ITEM_PLAY_VIDEO, "play_video");
        MENU_ICONS.put(MainUIData.MENU_ITEM_PLAY_VIDEO_INCOGNITO, "play_incognito");
        MENU_ICONS.put(MainUIData.MENU_ITEM_PLAY_FROM_START, "play_from_start");
        MENU_ICONS.put(MainUIData.MENU_ITEM_REMOVE_FROM_HISTORY, "remove_from_history");
        MENU_ICONS.put(MainUIData.MENU_ITEM_STREAM_REMINDER, "stream_reminder");
        MENU_ICONS.put(MainUIData.MENU_ITEM_RECENT_PLAYLIST, "recent_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER, "watch_later");
        MENU_ICONS.put(MainUIData.MENU_ITEM_ADD_TO_PLAYLIST, "add_to_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_DOWNLOAD, "download");
        MENU_ICONS.put(MainUIData.MENU_ITEM_CREATE_PLAYLIST, "create_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_RENAME_PLAYLIST, "rename_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_ADD_TO_NEW_PLAYLIST, "new_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_NOT_INTERESTED, "not_interested");
        MENU_ICONS.put(MainUIData.MENU_ITEM_NOT_RECOMMEND_CHANNEL, "not_recommend_channel");
        MENU_ICONS.put(MainUIData.MENU_ITEM_REMOVE_FROM_SUBSCRIPTIONS, "remove_from_subscriptions");
        MENU_ICONS.put(MainUIData.MENU_ITEM_BLOCK_CHANNEL, "block_channel");
        MENU_ICONS.put(MainUIData.MENU_ITEM_MARK_AS_WATCHED, "mark_watched");
        MENU_ICONS.put(MainUIData.MENU_ITEM_PLAYLIST_ORDER, "playlist_order");
        MENU_ICONS.put(MainUIData.MENU_ITEM_ADD_TO_QUEUE, "add_to_queue");
        MENU_ICONS.put(MainUIData.MENU_ITEM_PLAY_NEXT, "play_next");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SHOW_QUEUE, "show_queue");
        MENU_ICONS.put(MainUIData.MENU_ITEM_OPEN_CHANNEL, "open_channel");
        MENU_ICONS.put(MainUIData.MENU_ITEM_OPEN_PLAYLIST, "open_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SUBSCRIBE, "subscribe");
        MENU_ICONS.put(MainUIData.MENU_ITEM_EXCLUDE_FROM_CONTENT_BLOCK, "content_block");
        MENU_ICONS.put(MainUIData.MENU_ITEM_PIN_TO_SIDEBAR, "pin_sidebar");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SAVE_REMOVE_PLAYLIST, "save_playlist");
        MENU_ICONS.put(MainUIData.MENU_ITEM_OPEN_DESCRIPTION, "open_description");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SHARE_LINK, "share");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SHARE_QR_LINK, "share_qr");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SHARE_EMBED_LINK, "share_embed");
        MENU_ICONS.put(MainUIData.MENU_ITEM_SELECT_ACCOUNT, "select_account");
        MENU_ICONS.put(MainUIData.MENU_ITEM_TOGGLE_HISTORY, "toggle_history");
        MENU_ICONS.put(MainUIData.MENU_ITEM_CLEAR_HISTORY, "clear_history");
        MENU_ICONS.put(MainUIData.MENU_ITEM_OPEN_COMMENTS, "open_comments");
    }

    /**
     * NEWTUBE(menu-icons): the buttons {@code append} adds are {@code iconKey} rows (the phone draws
     * an icon for each). Nests: an action that appends two kinds of row wraps the second.
     */
    private void runWithIcon(String iconKey, Runnable append) {
        String previous = mDialogPresenter.setIconKey(iconKey);
        try {
            append.run();
        } finally {
            mDialogPresenter.setIconKey(previous);
        }
    }

    private void appendAddToWatchLaterButton() {
        if (!mIsAddToWatchLaterButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo() || mVideo.isPlaylistAsChannel()) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.add_video_to_watch_later),
                        optionItem -> {
                            MediaServiceManager.instance().addToWatchLaterPlaylist(mVideo);
                            BrowsePresenter.instance(getContext()).onPlaylistEdited(); // #36: pinned Watch later refetches
                            mDialogPresenter.closeDialog();
                        }
                ));
    }

    /**
     * NEWTUBE(#36): the phone had no way to take a video out of a playlist - upstream's
     * "Remove from" lives in the add-to-playlist dialog and the recent-playlist row, and its
     * card-menu version was commented out long ago. A card listed inside one of the signed-in
     * user's own playlists (Watch later included) now gets "Remove from &lt;playlist&gt;". It is
     * shown only when the account's playlist list (fetched signed in only) has that playlist and
     * says the video is in it, so mixes, other people's playlists and signed-out use never see it.
     */
    private boolean isOwnPlaylistCandidate() {
        return PhoneUi.isEnabled() && mVideo != null && mVideo.hasVideo() && !mVideo.isPlaylistCard()
                && mVideo.getPlaylistId() != null;
    }

    private PlaylistInfo findOwnPlaylist() {
        return isOwnPlaylistCandidate() ? OwnPlaylistMatch.find(mVideo.getPlaylistId(), mPlaylistInfos) : null;
    }

    private void appendRemoveFromOwnPlaylistButton() {
        PlaylistInfo own = findOwnPlaylist();
        if (own == null) {
            return;
        }

        String playlistId = own.getPlaylistId();
        String playlistTitle = own.getTitle();

        runWithIcon("remove_from_playlist", () -> mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.dialog_remove_from, playlistTitle),
                        optionItem -> removeFromOwnPlaylist(playlistId, playlistTitle))));
    }

    private void removeFromOwnPlaylist(String playlistId, String playlistTitle) {
        Video video = mVideo; // closeDialog() below may reset the presenter's state
        VideoMenuCallback callback = mCallback;
        Context context = getContext();
        closeDialog();

        RxHelper.disposeActions(mAddToPlaylistAction);
        mAddToPlaylistAction = RxHelper.execute(mMediaItemService.removeFromPlaylistObserve(playlistId, video.videoId),
                error -> confirm(context.getString(R.string.feedback_failed)),
                () -> {
                    // The card leaves the list it is in; other screens showing the playlist
                    // (the You tab's pinned Watch later) refetch when next shown.
                    if (callback != null) {
                        callback.onItemAction(video, VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST);
                    }
                    BrowsePresenter.instance(context).onPlaylistEdited();
                    confirm(context.getString(R.string.removed_from, playlistTitle),
                            context.getString(R.string.action_undo),
                            () -> undoRemoveFromOwnPlaylist(context, video, playlistId, playlistTitle));
                });
    }

    /** Undo puts the video back in the playlist; the list shows it again on its next load. */
    private void undoRemoveFromOwnPlaylist(Context context, Video video, String playlistId, String playlistTitle) {
        Observable<Void> addObserve = video.mediaItem != null ?
                mMediaItemService.addToPlaylistObserve(playlistId, video.mediaItem) : mMediaItemService.addToPlaylistObserve(playlistId, video.videoId);
        RxHelper.disposeActions(mAddToPlaylistAction);
        mAddToPlaylistAction = RxHelper.execute(addObserve,
                error -> confirm(context.getString(R.string.feedback_failed)),
                () -> {
                    BrowsePresenter.instance(context).onPlaylistEdited();
                    confirm(context.getString(R.string.added_to, playlistTitle));
                });
    }

    private void appendAddToPlaylistButton() {
        if (!mIsAddToPlaylistButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo() || mVideo.isPlaylistAsChannel()) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.dialog_add_to_playlist),
                        optionItem -> AppDialogUtil.showAddToPlaylistDialog(getContext(), mVideo, mCallback)
                ));
    }

    /**
     * NEWTUBE(downloads): "Download" sits next to the save actions, as on YouTube's card menu.
     * The item exists only while the phone has installed a handler (see {@link VideoDownloads}),
     * and only for a finished, ordinary video - live and upcoming streams have nothing to fetch.
     */
    private void appendDownloadButton() {
        if (!mIsDownloadButtonEnabled) {
            return;
        }

        if (!VideoDownloads.canDownload(mVideo) || mVideo.isPlaylistAsChannel()) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.dialog_download),
                        optionItem -> VideoDownloads.request(getContext(), mVideo)
                ));
    }

    private void appendAddToRecentPlaylistButton() {
        if (!isAddToRecentPlaylistButtonEnabled()) {
            return;
        }

        String playlistId = GeneralData.instance(getContext()).getLastPlaylistId();
        String playlistTitle = GeneralData.instance(getContext()).getLastPlaylistTitle();

        if (playlistId == null || playlistTitle == null) {
            return;
        }

        // NEWTUBE(#36): inside that very playlist the "Remove from" row already says it.
        PlaylistInfo own = findOwnPlaylist();
        if (own != null && playlistId.equals(own.getPlaylistId())) {
            return;
        }

        appendSimpleAddToRecentPlaylistButton(playlistId, playlistTitle);
    }

    private boolean isAddToRecentPlaylistButtonEnabled() {
        return mIsAddToPlaylistButtonEnabled && mIsAddToRecentPlaylistButtonEnabled && mVideo != null && mVideo.hasVideo();
    }

    private void appendSimpleAddToRecentPlaylistButton(String playlistId, String playlistTitle) {
        if (mPlaylistInfos == null) {
            return;
        }

        boolean isSelected = false;
        for (PlaylistInfo playlistInfo : mPlaylistInfos) {
            if (playlistInfo.getPlaylistId().equals(playlistId)) {
                isSelected = playlistInfo.isSelected();
                break;
            }
        }
        boolean add = !isSelected;
        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(
                        add ? R.string.dialog_add_to : R.string.dialog_remove_from, playlistTitle),
                        optionItem -> addRemoveFromPlaylist(playlistId, playlistTitle, add)
                )
        );
    }

    private void appendOpenChannelButton() {
        if (!mIsOpenChannelButtonEnabled) {
            return;
        }

        if (!ChannelPresenter.canOpenChannel(mVideo)) {
            return;
        }

        // Prepare to special type of channels that work as playlist
        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(
                        mVideo.isPlaylistAsChannel() ? R.string.open_playlist : R.string.open_channel), optionItem -> {
                    MediaServiceManager.chooseChannelPresenter(getContext(), mVideo);
                    mDialogPresenter.closeDialog();
                }));
    }

    private void appendOpenPlaylistButton() {
        if (!mIsOpenPlaylistButtonEnabled) {
            return;
        }

        // Check view to allow open playlist in grid
        if (mVideo == null || !mVideo.hasPlaylist() || (getViewManager().getTopView() == ChannelUploadsView.class && mVideo.belongsToSamePlaylistGroup())) {
            return;
        }

        // Prepare to special type of channels that work as playlist
        if (mVideo.isPlaylistAsChannel() && ChannelPresenter.canOpenChannel(mVideo)) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.open_playlist), optionItem -> ChannelUploadsPresenter.instance(getContext()).openChannel(mVideo)));
    }

    private void appendOpenChannelUploadsButton() {
        if (!mIsOpenChannelUploadsButtonEnabled || mVideo == null) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.open_channel_uploads), optionItem -> ChannelUploadsPresenter.instance(getContext()).openChannel(mVideo)));
    }

    private void appendNotInterestedButton() {
        logFeedbackMenuData();

        if (mVideo == null || mVideo.mediaItem == null || (mVideo.mediaItem.getFeedbackToken() == null && mVideo.mediaItem.getFeedbackEndpoint() == null)) {
            return;
        }

        if ((!mVideo.belongsToHome() && !mVideo.belongsToShorts()) || !mIsNotInterestedButtonEnabled) {
            return;
        }

        RxHelper.disposeActions(mNotInterestedAction);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.not_interested), optionItem -> {
                    if (PhoneUi.isEnabled()) {
                        sendFeedbackPhone(false);
                        return;
                    }

                    if (mVideo.mediaItem.getFeedbackEndpoint() == null) {
                        markAsNotInterested(mVideo.mediaItem.getFeedbackToken());
                    } else {
                        mNotInterestedAction = mMediaItemService.getFeedbackTokensObserve(mVideo.mediaItem.getFeedbackEndpoint())
                                .subscribe(
                                        tokens -> {
                                            if (tokens.size() == 2) {
                                                markAsNotInterested(tokens.get(0));
                                            }
                                        },
                                        error -> Log.e(TAG, "Mark as 'not interested' error: %s", error.getMessage())
                                );
                    }
                }));
    }

    private void markAsNotInterested(String feedbackToken) {
        mNotInterestedAction = mMediaItemService.getFeedbackReasonsObserve(feedbackToken)
                .subscribe(
                        reasons -> {
                            if (reasons.getItems() != null) {
                                for (FeedbackItem item : reasons.getItems()) {
                                    mDialogPresenter.appendSingleButton(
                                            UiOptionItem.from(item.getTitle(),
                                                    option -> {
                                                        RxHelper.execute(mMediaItemService.markAsNotInterestedObserve(item.getToken()));
                                                        removeSuggestedItemAndClose();
                                                    }
                                            )
                                    );
                                }
                                mDialogPresenter.showDialog(reasons.getTitle());
                            } else {
                                removeSuggestedItemAndClose();
                            }
                        },
                        error -> Log.e(TAG, "Mark as 'not interested' error: %s", error.getMessage())
                );
    }

    private void removeSuggestedItemAndClose() {
        if (mCallback != null) {
            mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
        } else {
            MessageHelpers.showMessage(getContext(), R.string.you_wont_see_this_video);
        }
        mDialogPresenter.closeDialog();
    }

    private void appendNotRecommendChannelButton() {
        if (mVideo == null || mVideo.mediaItem == null || (mVideo.mediaItem.getFeedbackToken2() == null && mVideo.mediaItem.getFeedbackEndpoint() == null)) {
            return;
        }

        if ((!mVideo.belongsToHome() && !mVideo.belongsToShorts()) || !mIsNotRecommendChannelEnabled) {
            return;
        }

        RxHelper.disposeActions(mNotInterestedAction);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.not_recommend_channel), optionItem -> {
                    if (PhoneUi.isEnabled()) {
                        sendFeedbackPhone(true);
                        return;
                    }

                    if (mVideo.mediaItem.getFeedbackEndpoint() == null) {
                        markAsNotRecommendChannel(mVideo.mediaItem.getFeedbackToken2());
                    } else {
                        mNotInterestedAction = mMediaItemService.getFeedbackTokensObserve(mVideo.mediaItem.getFeedbackEndpoint())
                                .subscribe(
                                        tokens -> {
                                            if (tokens.size() == 2) {
                                                markAsNotRecommendChannel(tokens.get(1));
                                            }
                                        },
                                        error -> Log.e(TAG, "Mark as 'not interested' error: %s", error.getMessage())
                                );
                    }
                }));
    }

    private void markAsNotRecommendChannel(String feedbackToken2) {
        mNotInterestedAction = mMediaItemService.markAsNotInterestedObserve(feedbackToken2)
                .subscribe(
                        var -> {},
                        error -> Log.e(TAG, "Mark as 'not interested' error: %s", error.getMessage()),
                        () -> {
                            if (mCallback != null) {
                                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
                            } else {
                                MessageHelpers.showMessage(getContext(), R.string.you_wont_see_this_video);
                            }
                        }
                );
        mDialogPresenter.closeDialog();
    }

    /**
     * NEWTUBE(not-interested): the phone's "Not interested" and "Don't recommend channel". The menu
     * closes on the tap, like every other phone menu action. The card leaves the feed once YouTube
     * has taken the feedback, with a Snackbar saying so; when it has not, the card stays and the
     * Snackbar says "Couldn't ...". There is no "Tell us why" step (the TV's reasons list): the
     * first feedback call already records "Not interested".
     *
     * <p>Home cards from today's TV answers carry no feedback tokens, only a long-press panel
     * endpoint; its two tokens ("Not interested" first, then the confirmation of "Don't recommend
     * channel") are fetched on the tap. The NetPath line carries the answer's status, never a token.
     */
    private void sendFeedbackPhone(boolean channel) {
        Video video = mVideo; // closeDialog() below may reset the presenter's state
        VideoMenuCallback callback = mCallback;
        closeDialog();

        MediaItem item = video.mediaItem;
        FeedbackEndpoint endpoint = item.getFeedbackEndpoint();
        String line = "feedback " + (channel ? "dont-recommend" : "not-interested") + " video=" + video.videoId
                + " source=" + (endpoint != null ? "panel" : "card");
        long startMs = SystemClock.elapsedRealtime();

        Observable<String> token = endpoint == null
                ? Observable.just(channel ? item.getFeedbackToken2() : item.getFeedbackToken())
                : mMediaItemService.getFeedbackTokensObserve(endpoint).map(tokens -> pickPanelToken(tokens, channel));

        RxHelper.disposeActions(mNotInterestedAction);
        mNotInterestedAction = token
                .flatMap(mMediaItemService::sendFeedbackObserve)
                .subscribe(
                        result -> {
                            boolean ok = result.getCode() >= 200 && result.getCode() < 300;
                            NetPath.log(line + " code=" + result.getCode() + " ok=" + ok
                                    + " processed=" + result.isProcessed() + " ms=" + (SystemClock.elapsedRealtime() - startMs));
                            if (!ok) {
                                confirm(getContext().getString(R.string.feedback_failed));
                                return;
                            }
                            if (callback != null) {
                                callback.onItemAction(video, VideoMenuCallback.ACTION_REMOVE);
                            }
                            confirm(getContext().getString(channel ? R.string.you_wont_see_this_channel : R.string.you_wont_see_this_video));
                        },
                        error -> {
                            NetPath.log(line + " code=-1 ok=false ms=" + (SystemClock.elapsedRealtime() - startMs)
                                    + " error=" + error.getClass().getSimpleName() + ":" + NetPath.trunc(error.getMessage(), 80));
                            confirm(getContext().getString(R.string.feedback_failed));
                        }
                );
    }

    /** Upstream's order: the panel lists "Not interested" first, "Don't recommend channel" second. */
    private static String pickPanelToken(List<String> tokens, boolean channel) {
        if (tokens == null || tokens.size() != 2) {
            throw new IllegalStateException("panel tokens=" + (tokens == null ? "none" : tokens.size()));
        }

        return tokens.get(channel ? 1 : 0);
    }

    /**
     * NEWTUBE(not-interested): one NetPath line per Home card menu (built signed in only), saying
     * which feedback data the card carries - the old tokens or the long-press panel endpoint.
     */
    private void logFeedbackMenuData() {
        if (mVideo == null || !mVideo.belongsToHome()) {
            return;
        }

        MediaItem item = mVideo.mediaItem;
        NetPath.log("feedback menu video=" + mVideo.videoId
                + " token=" + (item != null && item.getFeedbackToken() != null ? "y" : "n")
                + " token2=" + (item != null && item.getFeedbackToken2() != null ? "y" : "n")
                + " endpoint=" + (item != null && item.getFeedbackEndpoint() != null ? "y" : "n"));
    }

    private void appendBlockChannelButton() {
        // NEWTUBE(#56): not on a playlist card - it would block the playlist owner's channel.
        if (mVideo == null || mVideo.isChapter || mVideo.isPlaylistCard() || !mIsBlockChannelEnabled) {
            return;
        }

        String channelId = mVideo.channelId;
        String channelName = mVideo.getAuthor();

        if (channelName == null) {
            return;
        }

        BlockedChannelData blockedChannelData = BlockedChannelData.instance(getContext());
        boolean isBlocked = blockedChannelData.containsChannel(channelId, channelName);

        String buttonText = isBlocked
                ? getContext().getString(R.string.dialog_unblock_channel)
                : getContext().getString(R.string.dialog_block_channel);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(buttonText, optionItem -> {
                    if (isBlocked) {
                        blockedChannelData.removeChannel(channelId, channelName);
                        MessageHelpers.showMessage(getContext(), R.string.channel_unblocked);
                    } else {
                        blockedChannelData.addChannel(channelId, channelName);
                        MessageHelpers.showMessage(getContext(), R.string.channel_blocked);
                    }

                    if (mCallback != null) {
                        mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
                    }
                    mDialogPresenter.closeDialog();
                    showHideBlockedChannelsSection(blockedChannelData);
                }));
    }

    private void showHideBlockedChannelsSection(BlockedChannelData blockedChannelData) {
        if (blockedChannelData.getChannelCount() == 1) { // show on add first channel
            BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_BLOCKED_CHANNELS, true);
        } else if (blockedChannelData.isEmpty()) { // hide on remove all
            BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_BLOCKED_CHANNELS, false);
        }
    }

    private void appendRemoveFromHistoryButton() {
        if (mVideo == null || !mVideo.belongsToHistory() || !mIsRemoveFromHistoryButtonEnabled) {
            return;
        }

        RxHelper.disposeActions(mNotInterestedAction);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.remove_from_history), optionItem -> {
                    if (mVideo.mediaItem == null || mVideo.mediaItem.getFeedbackToken() == null) {
                        onRemoveFromHistoryDone();
                    } else {
                        mNotInterestedAction = mMediaItemService.markAsNotInterestedObserve(mVideo.mediaItem.getFeedbackToken())
                                .subscribe(
                                        var -> {},
                                        error -> Log.e(TAG, "Remove from history error: %s", error.getMessage()),
                                        this::onRemoveFromHistoryDone
                                );
                    }
                    mDialogPresenter.closeDialog();
                }));
    }

    private void onRemoveFromHistoryDone() {
        if (mCallback != null) {
            mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
        } else {
            MessageHelpers.showMessage(getContext(), R.string.removed_from_history);
        }
        VideoStateService stateService = VideoStateService.instance(getContext());
        stateService.removeByVideoId(mVideo.videoId);
    }

    private void appendRemoveFromSubscriptionsButton() {
        if (mVideo == null || mVideo.mediaItem == null || mVideo.mediaItem.getFeedbackToken() == null) {
            return;
        }

        if (!mVideo.belongsToSubscriptions() || !mIsRemoveFromSubscriptionsButtonEnabled) {
            return;
        }

        RxHelper.disposeActions(mNotInterestedAction);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.remove_from_subscriptions), optionItem -> {
                    mNotInterestedAction = mMediaItemService.markAsNotInterestedObserve(mVideo.mediaItem.getFeedbackToken())
                            .subscribe(
                                    var -> {},
                                    error -> Log.e(TAG, "Remove from subscriptions error: %s", error.getMessage()),
                                    () -> {
                                        if (mCallback != null) {
                                            mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
                                        }
                                    }
                            );
                    mDialogPresenter.closeDialog();
                }));
    }

    private void appendRemoveFromNotificationsButton() {
        if (mVideo == null || mVideo.mediaItem == null) {
            return;
        }

        if (!mVideo.belongsToNotifications() || !mIsRemoveFromSubscriptionsButtonEnabled) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.remove_from_subscriptions), optionItem -> {
                    MediaServiceManager.instance().hideNotification(mVideo);
                    if (mCallback != null) {
                        mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE);
                    }
                    mDialogPresenter.closeDialog();
                }));
    }

    private void appendMarkAsWatchedButton() {
        if (mVideo == null || !mVideo.hasVideo() || !mIsMarkAsWatchedButtonEnabled) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.mark_as_watched), optionItem -> {
                    MediaServiceManager.instance().updateHistory(mVideo, mVideo.getDurationMs());
                    mVideo.markFullyViewed();
                    VideoStateService.instance(getContext()).save(new State(mVideo, mVideo.getDurationMs()));
                    Playlist.instance().sync(mVideo);
                    mDialogPresenter.closeDialog();
                }));
    }

    private void appendShareLinkButton() {
        if (!mIsShareLinkButtonEnabled) {
            return;
        }

        // NEWTUBE(share): on the phone a card's Share links the video, not the resume point in it.
        AppDialogUtil.appendShareLinkDialogItem(getContext(), mDialogPresenter, mVideo, PhoneUi.isEnabled() ? 0 : -1);
    }

    private void appendShareQRLinkButton() {
        if (!mIsShareQRLinkButtonEnabled) {
            return;
        }

        AppDialogUtil.appendShareQRLinkDialogItem(getContext(), mDialogPresenter, mVideo);
    }

    private void appendShareEmbedLinkButton() {
        if (!mIsShareEmbedLinkButtonEnabled) {
            return;
        }

        AppDialogUtil.appendShareEmbedLinkDialogItem(getContext(), mDialogPresenter, mVideo);
    }

    private void appendOpenDescriptionButton() {
        if (!mIsOpenDescriptionButtonEnabled || mVideo == null) {
            return;
        }

        if (mVideo.videoId == null) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.action_video_info),
                        optionItem -> {
                            MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
                            mServiceManager.loadMetadata(mVideo, metadata -> {
                                String description = metadata.getDescription();
                                if (description != null) {
                                    showLongTextDialog(description);
                                } else {
                                    mServiceManager.loadFormatInfo(mVideo, formatInfo -> {
                                        String newDescription = formatInfo.getDescription();
                                        if (newDescription != null) {
                                            showLongTextDialog(newDescription);
                                        } else {
                                            MessageHelpers.showMessage(getContext(), R.string.description_not_found);
                                        }
                                    });
                                }
                            });
                        }
                ));
    }

    private void appendOpenCommentsButton() {
        if (!mIsOpenCommentsButtonEnabled || mVideo == null) {
            return;
        }

        if (mVideo.videoId == null || mVideo.isLive || mVideo.isUpcoming) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.open_comments),
                        optionItem -> {
                            MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
                            mServiceManager.loadMetadata(mVideo, metadata -> {
                                CommentsController controller = new CommentsController(getContext(), metadata);
                                controller.onButtonClicked(R.id.action_chat, PlayerUI.BUTTON_ON);
                            });
                        }
                ));
    }

    private void appendPlayVideoButton() {
        if (!mIsPlayVideoButtonEnabled || mVideo == null || mVideo.videoId == null) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.play_video),
                        optionItem -> {
                            PlaybackPresenter.instance(getContext()).openVideo(mVideo);
                            mDialogPresenter.closeDialog();
                        }
                ));
    }

    private void appendPlayVideoIncognitoButton() {
        if (!mIsPlayVideoIncognitoButtonEnabled || mVideo == null || mVideo.videoId == null) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.play_video_incognito),
                        optionItem -> {
                            mVideo.incognito = true;
                            PlaybackPresenter.instance(getContext()).openVideo(mVideo);
                            mDialogPresenter.closeDialog();
                        }
                ));
    }

    private void appendPlayFromStartButton() {
        if (!mIsPlayFromStartButtonEnabled || mVideo == null || mVideo.videoId == null) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.play_from_start),
                        optionItem -> {
                            VideoStateService stateService = VideoStateService.instance(getContext());
                            stateService.save(new State(mVideo, 0));
                            PlaybackPresenter.instance(getContext()).openVideo(mVideo);
                            mDialogPresenter.closeDialog();
                        }
                ));
    }

    private void showLongTextDialog(String description) {
        mDialogPresenter.appendLongTextCategory(mVideo.getTitle(), UiOptionItem.from(description));
        mDialogPresenter.showDialog(mVideo.getTitle());
    }

    private void appendSubscribeButton() {
        if (!mIsSubscribeButtonEnabled) {
            return;
        }

        if (mVideo == null || mVideo.isPlaylistAsChannel() || (!mVideo.isChannel() && !mVideo.hasVideo())) {
            return;
        }

        mVideo.isSubscribed = mVideo.isSubscribed || mVideo.belongsToSubscriptions() || mVideo.belongsToChannelUploads();

        // NEWTUBE(menu-icons): an Unsubscribe row shows the remove-person icon, not the add one.
        if (mVideo.isSubscribed) {
            mDialogPresenter.setIconKey("remove_from_subscriptions");
        }
        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(
                        mVideo.isSynced || mVideo.isSubscribed || (!getSignInService().isSigned() && mVideo.channelId != null) ? mVideo.isSubscribed ?
                                R.string.unsubscribe_from_channel : R.string.subscribe_to_channel : R.string.subscribe_unsubscribe_from_channel),
                        optionItem -> toggleSubscribe()));
    }

    private void appendReturnToBackgroundVideoButton() {
        if (!mIsReturnToBackgroundVideoEnabled || !PlaybackPresenter.instance(getContext()).isRunningInBackground()) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.return_to_background_video),
                        // Assume that the Playback view already blocked and remembered.
                        optionItem -> getViewManager().startView(PlaybackView.class)
                )
        );
    }

    private void appendAddToPlaybackQueueButton() {
        if (!mIsAddToPlaybackQueueButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo()) {
            return;
        }

        Playlist playlist = Playlist.instance();

        // Remain ability to reorder already added video (e.g. move to the top)
        List<Video> all = playlist.getAll();
        if (!all.isEmpty() && mVideo.equals(all.get(all.size() - 1))) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.add_to_playback_queue),
                        optionItem -> {
                            mVideo.fromQueue = true;
                            playlist.add(mVideo);
                            if (mCallback != null) {
                                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_ADD_TO_QUEUE);
                            }

                            closeDialog();
                            MessageHelpers.showMessage(getContext(), String.format("%s: %s",
                                    mVideo.getAuthor(),
                                    getContext().getString(R.string.added_to_playback_queue))
                            );
                        }));
    }

    private void appendRemoveFromPlaybackQueueButton() {
        if (!mIsAddToPlaybackQueueButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo()) {
            return;
        }

        Playlist playlist = Playlist.instance();

        if (!playlist.contains(mVideo)) {
            return;
        }

        // NEWTUBE(menu-icons): shares the Add-to-queue action, so it names its own row.
        mDialogPresenter.setIconKey("remove_from_queue");
        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.remove_from_playback_queue),
                        optionItem -> {
                            mVideo.fromQueue = false;
                            playlist.remove(mVideo);
                            if (mCallback != null) {
                                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE_FROM_QUEUE);
                            }

                            closeDialog();
                            MessageHelpers.showMessage(getContext(), String.format("%s: %s",
                                    mVideo.getAuthor(),
                                    getContext().getString(R.string.removed_from_playback_queue))
                            );
                        }));
    }

    private void appendPlayNextButton() {
        if (!mIsPlayNextButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo()) {
            return;
        }

        Playlist playlist = Playlist.instance();

        if (Helpers.equals(playlist.getNext(), mVideo)) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.play_next),
                        optionItem -> {
                            mVideo.fromQueue = true;
                            playlist.next(mVideo);
                            if (mCallback != null) {
                                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_PLAY_NEXT);
                            }

                            closeDialog();
                            MessageHelpers.showMessage(getContext(), String.format("%s: %s",
                                    mVideo.getAuthor(),
                                    getContext().getString(R.string.play_next))
                            );
                        }));
    }

    private void appendShowPlaybackQueueButton() {
        if (!mIsShowPlaybackQueueButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.hasVideo()) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(
                        getContext().getString(R.string.action_playback_queue),
                        optionItem -> AppDialogUtil.showPlaybackQueueDialog(getContext(), video -> PlaybackPresenter.instance(getContext()).openVideo(video))
                )
        );
    }

    private void appendPlaylistOrderButton() {
        if (!mIsPlaylistOrderButtonEnabled) {
            return;
        }

        BrowsePresenter presenter = BrowsePresenter.instance(getContext());

        if (mVideo == null || !(presenter.isPlaylistsSection() && presenter.inForeground())) {
            return;
        }

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(
                        R.string.playlist_order),
                        optionItem -> AppDialogUtil.showPlaylistOrderDialog(getContext(), mVideo, mDialogPresenter::closeDialog)
                ));
    }

    private void appendStreamReminderButton() {
        if (!mIsStreamReminderButtonEnabled) {
            return;
        }

        if (mVideo == null || !mVideo.isUpcoming) {
            return;
        }

        StreamReminderService reminderService = StreamReminderService.instance(getContext());
        boolean reminderSet = reminderService.isReminderSet(mVideo);

        mDialogPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(reminderSet ? R.string.unset_stream_reminder : R.string.set_stream_reminder),
                        optionItem -> {
                            reminderService.toggleReminder(mVideo);
                            closeDialog();
                            MessageHelpers.showMessage(getContext(), reminderSet ? R.string.msg_done : R.string.playback_starts_shortly);
                        }
                ));
    }

    private void addRemoveFromPlaylist(String playlistId, String playlistTitle, boolean add) {
        RxHelper.disposeActions(mAddToPlaylistAction);
        if (add) {
            Observable<Void> editObserve = mVideo.mediaItem != null ?
                    mMediaItemService.addToPlaylistObserve(playlistId, mVideo.mediaItem) : mMediaItemService.addToPlaylistObserve(playlistId, mVideo.videoId);
            // Handle error: Maximum playlist size exceeded (> 5000 items)
            mAddToPlaylistAction = RxHelper.execute(editObserve, error -> MessageHelpers.showLongMessage(getContext(), error.getMessage()));
            mDialogPresenter.closeDialog();
            MessageHelpers.showMessage(getContext(),
                    getContext().getString(R.string.added_to, playlistTitle));
        } else {
            // Check that the current video belongs to the right section
            if (mCallback != null && Helpers.equals(mVideo.playlistId, playlistId)) {
                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST);
            }
            Observable<Void> editObserve = mMediaItemService.removeFromPlaylistObserve(playlistId, mVideo.videoId);
            mAddToPlaylistAction = RxHelper.execute(editObserve);
            mDialogPresenter.closeDialog();
            MessageHelpers.showMessage(getContext(),
                    getContext().getString(R.string.removed_from, playlistTitle));
        }
    }

    //private void addRemoveFromPlaylist(String playlistId, String playlistTitle, boolean add) {
    //    if (add) {
    //        Observable<Void> editObserve = mItemManager.addToPlaylistObserve(playlistId, mVideo.videoId);
    //        mAddToPlaylistAction = RxUtils.execute(editObserve);
    //        mDialogPresenter.closeDialog();
    //        MessageHelpers.showMessage(getContext(),
    //                getContext().getString(R.string.added_to, playlistTitle));
    //    } else {
    //        AppDialogUtil.showConfirmationDialog(getContext(), () -> {
    //            // Check that the current video belongs to the right section
    //            if (mCallback != null && Helpers.equals(mVideo.playlistId, playlistId)) {
    //                mCallback.onItemAction(mVideo, VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST);
    //            }
    //            Observable<Void> editObserve = mItemManager.removeFromPlaylistObserve(playlistId, mVideo.videoId);
    //            mAddToPlaylistAction = RxUtils.execute(editObserve);
    //            mDialogPresenter.closeDialog();
    //            MessageHelpers.showMessage(getContext(),
    //                    getContext().getString(R.string.removed_from, playlistTitle));
    //        }, getContext().getString(R.string.dialog_remove_from, playlistTitle));
    //    }
    //}

    private void toggleSubscribe() {
        if (mVideo == null) {
            return;
        }

        //mVideo.isSynced = true; // default to subscribe

        // Until synced we won't really know weather we subscribed to a channel.
        // Exclusion: channel item (can't be synced)
        // Note, regular items (from subscribed section etc) aren't contain channel id
        if (PhoneUi.isEnabled()) {
            toggleSubscribePhone();
            return;
        }

        if (mVideo.isSynced || mVideo.isSubscribed || mVideo.isChannel() || (!getSignInService().isSigned() && mVideo.channelId != null)) {
            toggleSubscribe(mVideo);
        } else {
            MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);

            mServiceManager.loadMetadata(mVideo, metadata -> {
                mVideo.sync(metadata);
                toggleSubscribe(mVideo);
            });
        }
    }

    /**
     * NEWTUBE(menu): the phone's Subscribe row. The menu closes on the tap, like every other menu
     * action (the TV leaves it open); an unknown channel is looked up quietly (no "Please wait"
     * Toast); and the Snackbar reports what actually happened once the request finished - the
     * channel's name and Undo on success, "Couldn't ..." when there was no channel to act on or
     * the request failed - instead of announcing "Subscribed" the moment the row is tapped.
     */
    private void toggleSubscribePhone() {
        Video video = mVideo; // closeDialog() below may reset the presenter's state
        VideoMenuCallback callback = mCallback;
        closeDialog();

        if (video.isSynced || video.isSubscribed || video.isChannel() || (!getSignInService().isSigned() && video.channelId != null)) {
            toggleSubscribePhone(video, callback, null);
        } else {
            mServiceManager.loadMetadata(video, metadata -> {
                video.sync(metadata);
                toggleSubscribePhone(video, callback, null);
            });
        }
    }

    private void toggleSubscribePhone(Video video, VideoMenuCallback callback, Runnable onDone) {
        boolean subscribe = !video.isSubscribed;
        String channel = video.getAuthor();

        if (video.channelId == null) {
            confirm(getContext().getString(subscribe ? R.string.subscribe_failed : R.string.unsubscribe_failed));
            return;
        }

        RxHelper.disposeActions(mSubscribeAction);

        Observable<Void> observable = subscribe ?
                mMediaItemService.subscribeObserve(video.channelId) : mMediaItemService.unsubscribeObserve(video.channelId);

        mSubscribeAction = RxHelper.execute(observable,
                error -> confirm(getContext().getString(subscribe ? R.string.subscribe_failed : R.string.unsubscribe_failed)),
                () -> {
                    video.isSubscribed = subscribe;
                    if (!subscribe && callback != null) {
                        callback.onItemAction(video, VideoMenuCallback.ACTION_UNSUBSCRIBE);
                    }
                    if (onDone != null) {
                        onDone.run();
                        return;
                    }
                    String message = channel == null
                            ? getContext().getString(subscribe ? R.string.subscribed_to_channel : R.string.unsubscribed_from_channel)
                            : getContext().getString(subscribe ? R.string.subscribed_to_channel_name : R.string.unsubscribed_from_channel_name, channel);
                    // Undo re-toggles. Undoing an unsubscribe made from the Subscriptions feed also
                    // reloads it, since the feed already dropped that channel's videos.
                    boolean reloadFeed = !subscribe && video.belongsToSubscriptions();
                    confirm(message, getContext().getString(R.string.action_undo),
                            () -> toggleSubscribePhone(video, null, reloadFeed
                                    ? () -> BrowsePresenter.instance(getContext()).refresh(false) : () -> { }));
                });
    }

    private void toggleSubscribe(Video video) {
        if (video == null) {
            return;
        }

        RxHelper.disposeActions(mSubscribeAction);

        Observable<Void> observable = video.isSubscribed ?
                mMediaItemService.unsubscribeObserve(video.channelId) : mMediaItemService.subscribeObserve(video.channelId);

        mSubscribeAction = RxHelper.execute(observable);
        // NEWTUBE(reopen-related): kept watch-page documents carry the old subscribe state.
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.onUserStateChanged();

        video.isSubscribed = !video.isSubscribed;

        if (!video.isSubscribed && mCallback != null) {
            mCallback.onItemAction(video, VideoMenuCallback.ACTION_UNSUBSCRIBE);
        }

        MessageHelpers.showMessage(getContext(), getContext().getString(!video.isSubscribed ? R.string.unsubscribed_from_channel : R.string.subscribed_to_channel));
    }

    @Override
    protected void updateEnabledMenuItems() {
        super.updateEnabledMenuItems();

        MainUIData mainUIData = MainUIData.instance(getContext());

        mIsOpenChannelUploadsButtonEnabled = true;
        mIsOpenPlaylistButtonEnabled = true;
        mIsReturnToBackgroundVideoEnabled = true;
        mIsOpenChannelButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_OPEN_CHANNEL);
        mIsAddToRecentPlaylistButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_RECENT_PLAYLIST);
        mIsAddToPlaybackQueueButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE);
        mIsPlayNextButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_PLAY_NEXT);
        mIsAddToWatchLaterButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER);
        mIsAddToPlaylistButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_PLAYLIST);
        mIsShareLinkButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_LINK);
        mIsDownloadButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_DOWNLOAD);
        mIsShareQRLinkButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_QR_LINK);
        mIsShareEmbedLinkButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_EMBED_LINK);
        mIsNotInterestedButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_NOT_INTERESTED);
        mIsNotRecommendChannelEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_NOT_RECOMMEND_CHANNEL);
        mIsBlockChannelEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_BLOCK_CHANNEL);
        mIsRemoveFromHistoryButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_REMOVE_FROM_HISTORY);
        mIsRemoveFromSubscriptionsButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_REMOVE_FROM_SUBSCRIPTIONS);
        mIsOpenDescriptionButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_OPEN_DESCRIPTION);
        mIsPlayVideoButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_PLAY_VIDEO);
        mIsPlayVideoIncognitoButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_PLAY_VIDEO_INCOGNITO);
        mIsPlayFromStartButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_PLAY_FROM_START);
        mIsSubscribeButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_SUBSCRIBE);
        mIsStreamReminderButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_STREAM_REMINDER);
        mIsShowPlaybackQueueButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_SHOW_QUEUE);
        mIsPlaylistOrderButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_PLAYLIST_ORDER);
        mIsMarkAsWatchedButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_MARK_AS_WATCHED);
        mIsOpenCommentsButtonEnabled = mainUIData.isMenuItemEnabled(MainUIData.MENU_ITEM_OPEN_COMMENTS);
    }

    private void initMenuMapping() {
        mMenuMapping.clear();

        mMenuMapping.put(MainUIData.MENU_ITEM_PLAY_VIDEO, new MenuAction(this::appendPlayVideoButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_PLAY_VIDEO_INCOGNITO, new MenuAction(this::appendPlayVideoIncognitoButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_PLAY_FROM_START, new MenuAction(this::appendPlayFromStartButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_REMOVE_FROM_HISTORY, new MenuAction(this::appendRemoveFromHistoryButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_STREAM_REMINDER, new MenuAction(this::appendStreamReminderButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_RECENT_PLAYLIST, new MenuAction(this::appendAddToRecentPlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER, new MenuAction(this::appendAddToWatchLaterButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_ADD_TO_PLAYLIST, new MenuAction(() -> { appendRemoveFromOwnPlaylistButton(); appendAddToPlaylistButton(); }, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_DOWNLOAD, new MenuAction(this::appendDownloadButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_CREATE_PLAYLIST, new MenuAction(this::appendCreatePlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_RENAME_PLAYLIST, new MenuAction(this::appendRenamePlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_ADD_TO_NEW_PLAYLIST, new MenuAction(this::appendAddToNewPlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_NOT_INTERESTED, new MenuAction(this::appendNotInterestedButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_NOT_RECOMMEND_CHANNEL, new MenuAction(this::appendNotRecommendChannelButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_REMOVE_FROM_SUBSCRIPTIONS, new MenuAction(() -> { appendRemoveFromSubscriptionsButton(); appendRemoveFromNotificationsButton(); }, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_BLOCK_CHANNEL, new MenuAction(this::appendBlockChannelButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_MARK_AS_WATCHED, new MenuAction(this::appendMarkAsWatchedButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_PLAYLIST_ORDER, new MenuAction(this::appendPlaylistOrderButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_ADD_TO_QUEUE, new MenuAction(() -> { appendAddToPlaybackQueueButton(); appendRemoveFromPlaybackQueueButton(); }, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_PLAY_NEXT, new MenuAction(this::appendPlayNextButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SHOW_QUEUE, new MenuAction(this::appendShowPlaybackQueueButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_OPEN_CHANNEL, new MenuAction(this::appendOpenChannelButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_OPEN_PLAYLIST, new MenuAction(this::appendOpenPlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SUBSCRIBE, new MenuAction(this::appendSubscribeButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_EXCLUDE_FROM_CONTENT_BLOCK, new MenuAction(this::appendToggleExcludeFromContentBlockButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_PIN_TO_SIDEBAR, new MenuAction(this::appendTogglePinVideoToSidebarButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SAVE_REMOVE_PLAYLIST, new MenuAction(this::appendSaveRemovePlaylistButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_OPEN_DESCRIPTION, new MenuAction(this::appendOpenDescriptionButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SHARE_LINK, new MenuAction(this::appendShareLinkButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SHARE_QR_LINK, new MenuAction(this::appendShareQRLinkButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SHARE_EMBED_LINK, new MenuAction(this::appendShareEmbedLinkButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_SELECT_ACCOUNT, new MenuAction(this::appendAccountSelectionButton, false));
        mMenuMapping.put(MainUIData.MENU_ITEM_TOGGLE_HISTORY, new MenuAction(this::appendToggleHistoryButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_CLEAR_HISTORY, new MenuAction(this::appendClearHistoryButton, true));
        mMenuMapping.put(MainUIData.MENU_ITEM_OPEN_COMMENTS, new MenuAction(this::appendOpenCommentsButton, false));

        for (ContextMenuProvider provider : new ContextMenuManager(getContext()).getProviders()) {
            if (provider.getMenuType() != ContextMenuProvider.MENU_TYPE_VIDEO) {
                continue;
            }
            mMenuMapping.put(provider.getId(), new MenuAction(() -> appendContextMenuItem(provider), false));
        }
    }

    private void appendContextMenuItem(ContextMenuProvider provider) {
        MainUIData mainUIData = MainUIData.instance(getContext());
        if (mainUIData.isMenuItemEnabled(provider.getId()) && provider.isEnabled(getVideo())) {
            mDialogPresenter.appendSingleButton(
                    UiOptionItem.from(getContext().getString(provider.getTitleResId()), optionItem -> provider.onClicked(getVideo(), getCallback()))
            );
        }
    }
}
