package com.newtube.mobile.ui.playback;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.CommentsService;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * NEWTUBE(comments-panel): the watch page's comments, in a panel that slides up over the page
 * under the video (the video keeps playing and stays usable: a timestamp in a comment seeks it).
 * Replaced the comments bottom sheet (CommentsSheet).
 *
 * <p>Everything here belongs to one video. A new video closes the panel, drops its comments and
 * ignores late answers for the old one (a generation number on every request). Within a video the
 * two sort orders and the open thread are kept, so closing and reopening, switching Top / Newest,
 * or coming back from a replies page never reloads what is already here, and each keeps its place.</p>
 *
 * <p>Loading: the first page when the panel opens (or earlier, best effort, once the video plays -
 * {@link #prefetch()}), then page by page as the list nears its end. A failed first page offers
 * Try again; a failed next page keeps the rows and ends the list with a retry row. Back closes the
 * sort menu, then a replies page, then the panel.</p>
 *
 * <p>NEWTUBE(write-comments): signed in, the person can comment ("Add a comment…" at the top of the
 * list), reply (a comment's Reply button, or "Add a reply…" on its replies page; always to the
 * thread's top comment, "@handle " first when answering a reply) and delete their own. What they
 * post shows at once - on top of the list or the thread, and on top of any order or replies page
 * loaded later - and what they closed without posting stays as a draft until the video changes.</p>
 */
final class CommentsPanel implements CommentsAdapter.Listener, CommentsPanelLayout.Callback,
        CommentComposer.Callback {

    interface Host {
        /** A timestamp in a comment on the video that is playing. */
        void onCommentTimestamp(long positionMs);

        /** A link in a comment to another video. */
        void onCommentVideoLink(String videoId);

        /** The panel now covers (true) or uncovers the watch page. */
        void onCommentsPanelShown(boolean shown);
    }

    private static final int NO_FAILURE = -1;
    private static final int FIRST_PAGE_SKELETONS = 6;
    private static final int REPLIES_SKELETONS = 3;
    /** Rows left below the last visible one when the next page is asked for. */
    private static final int PAGE_AHEAD = 5;
    private static final long SHARED_AXIS_MS = 300;
    private static final long FADE_OUT_MS = 90;
    private static final long FADE_IN_MS = 210;
    private static final long QUICK_FADE_MS = 150;
    private static final float SORT_DIM_ALPHA = 0.4f;
    private static final long MENU_GROW_MS = 200;
    private static final long MENU_FADE_MS = 120;

    /**
     * NEWTUBE(write-comments): the comments posted from this app since it started, on any video,
     * and the account that posted each: that account's own even when its handle is unknown.
     */
    private static final Map<String, String> sPostedBy = Collections.synchronizedMap(new HashMap<>());

    /** One page sequence: the comments in one sort order, or the replies of one comment. */
    private static final class Feed {
        @Nullable
        final String firstKey;
        final boolean replies;
        final List<CommentsAdapter.Entry> entries = new ArrayList<>();
        @Nullable
        String nextKey;
        boolean loading;
        boolean loaded;
        int failure = NO_FAILURE;
        boolean moreFailed;
        @Nullable
        Parcelable scroll;
        @Nullable
        Disposable request;
        /** Continuation pages in a row that brought no comments (a runaway guard). */
        int emptyPages;

        Feed(@Nullable String firstKey, boolean replies) {
            this.firstKey = firstKey;
            this.replies = replies;
        }

        void cancel() {
            RxHelper.disposeActions(request);
            request = null;
            loading = false;
        }

        int footer() {
            if (moreFailed) {
                return CommentsAdapter.FOOTER_RETRY;
            }
            return loading && loaded ? CommentsAdapter.FOOTER_LOADING : CommentsAdapter.FOOTER_NONE;
        }
    }

    private final FragmentActivity mActivity;
    private final Host mHost;
    @Nullable
    private final CommentsService mService;
    private final CommentsPanelLayout mLayout;
    private final View mTitles;
    private final View mTitleList;
    private final View mTitleReplies;
    private final TextView mCountView;
    private final View mSort;
    private final TextView mSortLabel;
    private final ImageView mSortChevron;
    private final View mBack;
    private final View mHairline;
    private final LinearProgressIndicator mProgress;
    private final RecyclerView mList;
    private final RecyclerView mReplies;
    private final CommentsAdapter mListAdapter;
    private final CommentsAdapter mRepliesAdapter;
    private final View mLoadState;
    private final TextView mLoadStateMessage;
    private final float mDensity;
    private final OnBackPressedCallback mBackCallback;
    @Nullable
    private BackEventCompat mLastBackEvent;
    @Nullable
    private PopupWindow mSortMenu;

    // --- The video the panel is about; everything below belongs to it.
    @Nullable
    private String mVideoId;
    @Nullable
    private String mCount;
    private int mGeneration;
    @Nullable
    private Feed mTop;
    @Nullable
    private Feed mNewest;
    private boolean mNewestSelected;
    /** A switch to a sort order whose first page is still loading (the list is dimmed meanwhile). */
    private boolean mSortPending;
    /** The replies page's comment and its replies; null on the list. */
    @Nullable
    private CommentsAdapter.Entry mThreadParent;
    @Nullable
    private Feed mThread;
    @Nullable
    private String mCreatorHandle;
    private boolean mSuspended;
    @Nullable
    private Parcelable mSuspendedList;
    @Nullable
    private Parcelable mSuspendedReplies;
    private boolean mPageTransition;
    /** A new video arrived while the sheet slid away: empty it once it is gone (see onVideoChanged). */
    private boolean mViewResetPending;
    /** The list page is fading from one sort order's rows to the other's (see swapList). */
    private boolean mListSwapping;
    /** The sort menu while it fades out (kept so teardown can still remove it). */
    @Nullable
    private PopupWindow mClosingMenu;
    private boolean mReleased;
    @Nullable
    private android.animation.ValueAnimator mPageAnimator;
    private boolean mHairlineShown;

    // --- NEWTUBE(write-comments)
    @Nullable
    private CommentComposer mComposer;
    /** The thread the open composer replies to; null = a new comment on the video. */
    @Nullable
    private CommentsAdapter.Entry mComposeParent;
    @Nullable
    private String mComposePrefill;
    /** Who the open composer writes as, and whether it is the DEBUG UI check (never sent). */
    @Nullable
    private String mComposeAccountKey;
    private boolean mComposeUiTest;
    /** Texts closed without posting, one per account and target (see {@link #draftKey}). */
    private final Map<String, String> mDrafts = new HashMap<>();
    @Nullable
    private Disposable mPostRequest;
    @Nullable
    private Disposable mDeleteRequest;
    /** Top-level comments posted on this video, newest first: on top of every order's first page. */
    private final List<CommentsAdapter.Entry> mPostedTop = new ArrayList<>();
    /** Replies posted on this video by their top comment's id, shared by that comment's entries. */
    private final Map<String, List<CommentsAdapter.Entry>> mPostedReplies = new HashMap<>();
    /** Comments deleted on this video: a page that arrives later must not bring them back. */
    private final Set<String> mDeletedIds = new HashSet<>();
    @Nullable
    private androidx.appcompat.app.AlertDialog mDeleteDialog;
    /** The signed-in account's handle without the "@", lower case; null = unknown or signed out. */
    @Nullable
    private String mMyHandle;
    @Nullable
    private String mMyPhoto;
    private boolean mSignedIn;
    /** Who is signed in, to tell their posts from another account's (see {@link #sPostedBy}). */
    @Nullable
    private String mAccountKey;
    /**
     * DEBUG builds, signed out, with {@code adb shell setprop debug.arc.comments_ui_test 1}: the
     * composer opens and every comment has the ⋮, so the writing UI can be checked on an emulator
     * with no account. Nothing can reach YouTube that way: a signed-out post or delete is refused
     * before it is sent (YouTubeCommentsService.checkSignedIn).
     */
    private boolean mUiTest;
    @Nullable
    private PopupWindow mCommentMenu;

    CommentsPanel(FragmentActivity activity, CommentsPanelLayout layout, Host host) {
        mActivity = activity;
        mHost = host;
        mLayout = layout;
        mDensity = activity.getResources().getDisplayMetrics().density;
        CommentsService service = null;
        try {
            service = YouTubeServiceManager.instance().getCommentsService();
        } catch (RuntimeException e) {
            // No service, no comments: the entry stays hidden without a key anyway.
        }
        mService = service;

        mTitles = layout.findViewById(R.id.comments_titles);
        mTitleList = layout.findViewById(R.id.comments_title_list);
        mTitleReplies = layout.findViewById(R.id.comments_title_replies);
        mCountView = layout.findViewById(R.id.comments_count);
        mSort = layout.findViewById(R.id.comments_sort);
        mSortLabel = layout.findViewById(R.id.comments_sort_label);
        mSortChevron = layout.findViewById(R.id.comments_sort_chevron);
        mBack = layout.findViewById(R.id.comments_back);
        mHairline = layout.findViewById(R.id.comments_hairline);
        mProgress = layout.findViewById(R.id.comments_progress);
        mList = layout.findViewById(R.id.comments_list);
        mReplies = layout.findViewById(R.id.comments_replies);
        mLoadState = layout.findViewById(R.id.mobile_page_load_state);
        mLoadStateMessage = mLoadState.findViewById(R.id.mobile_page_load_state_message);
        mLoadState.findViewById(R.id.mobile_page_load_state_action).setOnClickListener(v -> onRetryFirstPage());
        // A comments panel is a quieter place than a whole page: a smaller picture over the message.
        View stateIcon = mLoadState.findViewById(R.id.mobile_page_load_state_icon);
        ViewGroup.LayoutParams iconParams = stateIcon.getLayoutParams();
        iconParams.width = iconParams.height = Math.round(64 * mDensity);
        stateIcon.setLayoutParams(iconParams);

        mListAdapter = new CommentsAdapter(this);
        mRepliesAdapter = new CommentsAdapter(this);
        mListAdapter.setCompose(R.string.mobile_comments_add_hint);
        mRepliesAdapter.setCompose(R.string.mobile_comments_reply_hint);
        setUpList(mList, mListAdapter);
        setUpList(mReplies, mRepliesAdapter);

        layout.setCallback(this);
        layout.findViewById(R.id.comments_close).setOnClickListener(v -> close());
        mBack.setOnClickListener(v -> leaveReplies());
        mSort.setOnClickListener(v -> showSortMenu());
        ViewCompat.setAccessibilityPaneTitle(layout.getSheet(), activity.getString(R.string.mobile_comments_title));
        updateSortLabel();

        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                mLastBackEvent = null;
                if (mThreadParent == null && !mPageTransition) {
                    mLayout.startBackProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                mLastBackEvent = backEvent;
                if (mThreadParent == null) {
                    mLayout.updateBackProgress(backEvent);
                }
            }

            @Override
            public void handleOnBackCancelled() {
                mLastBackEvent = null;
                mLayout.cancelBackProgress();
            }

            @Override
            public void handleOnBackPressed() {
                BackEventCompat last = mLastBackEvent;
                mLastBackEvent = null;
                onBack(last);
            }
        };
        // Added after the player's own handler, so it is asked first while enabled.
        activity.getOnBackPressedDispatcher().addCallback(activity, mBackCallback);
    }

    private void setUpList(RecyclerView list, CommentsAdapter adapter) {
        RowAnimator animator = new RowAnimator();
        list.setLayoutManager(new PageLayoutManager(list.getContext(), animator));
        list.setAdapter(adapter);
        list.setHasFixedSize(true);
        list.setItemAnimator(animator);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (isActivePage(recyclerView)) {
                    updateHairline(true);
                }
                if (dy > 0) {
                    maybeLoadMore(recyclerView);
                }
            }
        });
    }

    /**
     * Rows fade in where skeletons were (150ms) and move when a footer changes; a whole page being
     * replaced (a sort switch, a replies page opening) is not animated row by row - the page's
     * own fade or slide carries it, and a second, row-level fade on top read as a flicker.
     */
    private static final class RowAnimator extends DefaultItemAnimator {
        private boolean mSkipping;

        RowAnimator() {
            setSupportsChangeAnimations(false);
            setAddDuration(QUICK_FADE_MS);
            setRemoveDuration(0);
            setMoveDuration(200);
        }

        /** The next layout's row animations are skipped (cleared when that layout completes). */
        void skipNextLayout() {
            mSkipping = true;
        }

        void onLayoutCompleted() {
            mSkipping = false;
        }

        @Override
        public boolean animateAdd(RecyclerView.ViewHolder holder) {
            if (mSkipping) {
                holder.itemView.setAlpha(1f);
                dispatchAddFinished(holder);
                return false;
            }
            return super.animateAdd(holder);
        }

        @Override
        public boolean animateRemove(RecyclerView.ViewHolder holder) {
            if (mSkipping) {
                dispatchRemoveFinished(holder);
                return false;
            }
            return super.animateRemove(holder);
        }

        @Override
        public boolean animateMove(RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
            if (mSkipping) {
                dispatchMoveFinished(holder);
                return false;
            }
            return super.animateMove(holder, fromX, fromY, toX, toY);
        }

        @Override
        public boolean animateChange(RecyclerView.ViewHolder oldHolder, RecyclerView.ViewHolder newHolder,
                                     int fromX, int fromY, int toX, int toY) {
            if (mSkipping) {
                dispatchChangeFinished(oldHolder, true);
                if (newHolder != null && newHolder != oldHolder) {
                    dispatchChangeFinished(newHolder, false);
                }
                return false;
            }
            return super.animateChange(oldHolder, newHolder, fromX, fromY, toX, toY);
        }
    }

    /** Tells the {@link RowAnimator} when a layout (and so its animations) is over. */
    private static final class PageLayoutManager extends LinearLayoutManager {
        private final RowAnimator mAnimator;

        PageLayoutManager(Context context, RowAnimator animator) {
            super(context);
            mAnimator = animator;
        }

        @Override
        public void onLayoutCompleted(RecyclerView.State state) {
            super.onLayoutCompleted(state);
            mAnimator.onLayoutCompleted();
        }
    }

    /** Replace a page's rows without row-level animation (see {@link RowAnimator}). */
    private void replaceContent(CommentsAdapter adapter, @Nullable CommentsAdapter.Entry parent,
                                @Nullable CharSequence label, List<CommentsAdapter.Entry> items,
                                int skeletons, int footer) {
        RecyclerView list = adapter == mRepliesAdapter ? mReplies : mList;
        if (list.getItemAnimator() instanceof RowAnimator) {
            ((RowAnimator) list.getItemAnimator()).skipNextLayout();
        }
        adapter.setContent(parent, label, items, skeletons, footer);
    }

    // ---------------------------------------------------------------------------------
    // Video lifecycle (driven by MobilePlaybackActivity)
    // ---------------------------------------------------------------------------------

    /** A different video is now on the watch page: close, and forget the old one's comments. */
    void onVideoChanged(@Nullable String videoId) {
        if (Helpers.equals(videoId, mVideoId)) {
            return;
        }
        dismissSortMenu(false);
        dismissCommentMenu();
        cancelRequests();
        closeComposer();
        dismissDeleteDialog();
        mDrafts.clear();
        mPostedTop.clear();
        mPostedReplies.clear();
        mDeletedIds.clear();
        mGeneration++;
        mVideoId = videoId;
        mCount = null;
        mTop = null;
        mNewest = null;
        mThread = null;
        mThreadParent = null;
        mNewestSelected = false;
        mSortPending = false;
        mCreatorHandle = null;
        if (mLayout.isOpen() && !mSuspended && mLayout.getVisibility() == View.VISIBLE) {
            // The old video's comments stay in the sheet while it slides away; emptied once gone.
            mViewResetPending = true;
            mLayout.close();
        } else {
            if (mLayout.isOpen()) {
                mLayout.closeImmediately();
            }
            resetViews();
        }
    }

    /** Back to an empty list page, for the next video. */
    private void resetViews() {
        mViewResetPending = false;
        mProgress.hide();
        showListPage();
        hideState();
        replaceContent(mListAdapter, null, null, new ArrayList<>(), 0, CommentsAdapter.FOOTER_NONE);
        replaceContent(mRepliesAdapter, null, null, new ArrayList<>(), 0, CommentsAdapter.FOOTER_NONE);
        mListAdapter.setCreatorHandle(null);
        mRepliesAdapter.setCreatorHandle(null);
        updateSortLabel();
        updateCount();
        mSort.setVisibility(View.VISIBLE);
    }

    /** The video's metadata named where its comments are (null keys = comments are off). */
    void setSource(@Nullable String videoId, @Nullable String topKey, @Nullable String newestKey,
                   @Nullable String count) {
        if (!Helpers.equals(videoId, mVideoId)) {
            onVideoChanged(videoId);
        }
        if (topKey == null) {
            return;
        }
        // A later /next for the same video (a refresh) carries new tokens for the same pages:
        // what is already loaded stays.
        if (mTop == null) {
            mTop = new Feed(topKey, false);
        }
        if (mNewest == null && newestKey != null) {
            mNewest = new Feed(newestKey, false);
        }
        if (!TextUtils.isEmpty(count)) {
            mCount = count;
        }
        updateCount();
        mSort.setVisibility(mNewest != null ? View.VISIBLE : View.GONE);
    }

    boolean hasComments() {
        return mTop != null;
    }

    boolean isOpen() {
        return mLayout.isOpen();
    }

    /**
     * Best effort: fetch the first page now so the panel opens full. Only the default order's first
     * page, only when nothing is loading or loaded; the avatars wait until the rows are shown.
     */
    void prefetch() {
        if (mReleased) {
            return;
        }
        Feed feed = mTop;
        if (feed != null && !feed.loaded && !feed.loading && feed.failure == NO_FAILURE) {
            loadFirstPage(feed);
        }
    }

    // ---------------------------------------------------------------------------------
    // Open / close
    // ---------------------------------------------------------------------------------

    void open() {
        Feed feed = currentFeed();
        if (feed == null || mLayout.isOpen() || mReleased) {
            return;
        }
        if (mViewResetPending) {
            resetViews();
        }
        refreshAccount();
        if (!feed.loaded && !feed.loading) {
            // Opening is a fresh ask: a first page that failed before (the background fetch, or
            // an earlier open) is tried again rather than greeting the person with an error.
            feed.failure = NO_FAILURE;
        }
        // A sort switch still in flight owns the list page (its old rows stay, dimmed, until the
        // new order lands); otherwise show the current order as it stands.
        if (mThreadParent == null && !mSortPending && !mListSwapping) {
            showListPage();
            bindFeedToList(feed, false);
        }
        mLayout.open();
        mLayout.getSheet().post(() -> maybeLoadMore(activePage()));
    }

    @Override
    public void onPanelOpened() {
        mBackCallback.setEnabled(true);
        mHost.onCommentsPanelShown(true);
    }

    void close() {
        dismissSortMenu(false);
        mLayout.close();
    }

    /** Minimize: the panel went with the watch page; it is closed when the player comes back. */
    void closeImmediately() {
        dismissSortMenu(false);
        dismissCommentMenu();
        closeComposer();
        dismissDeleteDialog();
        mLayout.closeImmediately();
    }

    /** Fullscreen or picture-in-picture: out of the way, and back where it was on return. */
    void setSuspended(boolean suspended) {
        if (mSuspended == suspended) {
            return;
        }
        mSuspended = suspended;
        if (!mLayout.isOpen()) {
            return;
        }
        if (suspended) {
            dismissSortMenu(false);
            dismissCommentMenu();
            closeComposer();
            dismissDeleteDialog();
            // In fullscreen the pages get no height, and a list laid out in no height forgets
            // where it was: keep both places for the way back.
            mSuspendedList = saveState(mList);
            mSuspendedReplies = saveState(mReplies);
            mLayout.suspend();
            mBackCallback.setEnabled(false);
            mHost.onCommentsPanelShown(false);
        } else {
            restoreState(mList, mSuspendedList);
            restoreState(mReplies, mSuspendedReplies);
            mSuspendedList = null;
            mSuspendedReplies = null;
            mLayout.resume();
            mBackCallback.setEnabled(true);
            mHost.onCommentsPanelShown(true);
        }
    }

    @Nullable
    private static Parcelable saveState(RecyclerView list) {
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        return manager != null ? manager.onSaveInstanceState() : null;
    }

    private static void restoreState(RecyclerView list, @Nullable Parcelable state) {
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (manager != null && state != null) {
            manager.onRestoreInstanceState(state);
            list.requestLayout();
        }
    }

    /** Minimize drag: the panel fades out with the watch page (and back in if the drag is let go). */
    void setMorphAlpha(float alpha) {
        mLayout.setAlpha(alpha);
    }

    /** The activity is going: nothing here may start again (requests, fades, posted work). */
    void release() {
        mReleased = true;
        dismissSortMenu(false);
        dismissCommentMenu();
        closeComposer();
        dismissDeleteDialog();
        cancelRequests();
        mList.animate().cancel();
        showListPage();
        mLayout.setCallback(null);
        mLayout.closeImmediately();
        mBackCallback.remove();
    }

    @Override
    public void onPanelClosed() {
        if (mReleased) {
            return;
        }
        mBackCallback.setEnabled(false);
        mHost.onCommentsPanelShown(false);
        dismissSortMenu(false);
        dismissCommentMenu();
        closeComposer();
        dismissDeleteDialog();
        if (mViewResetPending) {
            resetViews();
        } else {
            saveScroll();
        }
    }

    private void onBack(@Nullable BackEventCompat lastEvent) {
        if (mSortMenu != null) {
            dismissSortMenu(true);
        } else if (mThreadParent != null) {
            leaveReplies();
        } else {
            mLayout.handleBack(lastEvent);
        }
    }

    // ---------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------

    @Nullable
    private Feed currentFeed() {
        return mNewestSelected && mNewest != null ? mNewest : mTop;
    }

    private boolean isShown(Feed feed) {
        return feed == mThread ? mThreadParent != null : feed == currentFeed() && !mSortPending;
    }

    private CommentsAdapter adapterFor(Feed feed) {
        return feed.replies ? mRepliesAdapter : mListAdapter;
    }

    private void loadFirstPage(Feed feed) {
        if (mReleased || mService == null || feed.firstKey == null || feed.loading) {
            return;
        }
        final int generation = mGeneration;
        feed.loading = true;
        feed.failure = NO_FAILURE;
        feed.request = mService.getCommentsObserve(feed.firstKey).subscribe(
                group -> onFirstPage(feed, generation, group),
                error -> onFirstPageFailed(feed, generation, error));
    }

    private void onFirstPage(Feed feed, int generation, @Nullable CommentGroup group) {
        if (generation != mGeneration) {
            return;
        }
        feed.loading = false;
        feed.request = null;
        feed.loaded = true;
        feed.entries.clear();
        feed.entries.addAll(toEntries(group, feed.replies));
        forgetDeleted(feed.entries);
        attachPostedReplies(feed.entries);
        // What the person posted here goes first (and only once, if YouTube already lists it).
        List<CommentsAdapter.Entry> posted = postedFor(feed);
        if (!posted.isEmpty()) {
            removeIds(feed.entries, posted);
            feed.entries.addAll(0, posted);
        }
        feed.nextKey = nextKey(group, feed.firstKey);
        if (!feed.replies) {
            learnCreator(feed.entries);
        }
        if (feed == pendingSortFeed()) {
            finishSortSwitch(feed);
            return;
        }
        if (!isShown(feed)) {
            return;
        }
        if (feed.entries.isEmpty() && !feed.replies) {
            showState(R.string.mobile_comments_none, false);
            replaceContent(mListAdapter, null, null, feed.entries, 0, CommentsAdapter.FOOTER_NONE);
            return;
        }
        hideState();
        adapterFor(feed).showFirstPage(feed.entries, feed.footer());
        RecyclerView list = feed.replies ? mReplies : mList;
        // A short first page (under a screenful) would never scroll to ask for more.
        list.post(() -> maybeLoadMore(list));
    }

    private void onFirstPageFailed(Feed feed, int generation, Throwable error) {
        if (generation != mGeneration) {
            return;
        }
        feed.loading = false;
        feed.request = null;
        feed.failure = LoadFailure.classify(mActivity, error);
        if (feed == pendingSortFeed()) {
            abandonSortSwitch();
            return;
        }
        if (!isShown(feed)) {
            return;
        }
        if (feed.replies) {
            mRepliesAdapter.showFirstPage(feed.entries, CommentsAdapter.FOOTER_RETRY);
        } else {
            replaceContent(mListAdapter, null, null, feed.entries, 0, CommentsAdapter.FOOTER_NONE);
            showState(feed.failure == LoadFailure.NO_CONNECTION
                    ? R.string.mobile_empty_no_connection : R.string.mobile_comments_load_error, true);
        }
    }

    private void onRetryFirstPage() {
        Feed feed = currentFeed();
        if (feed == null || feed.loading) {
            return;
        }
        feed.failure = NO_FAILURE;
        hideState();
        replaceContent(mListAdapter, null, null, feed.entries, FIRST_PAGE_SKELETONS, CommentsAdapter.FOOTER_NONE);
        loadFirstPage(feed);
    }

    private void maybeLoadMore(@Nullable RecyclerView list) {
        if (list == null || mReleased || !isActivePage(list) || !mLayout.isOpen()) {
            return;
        }
        Feed feed = list == mReplies ? mThread : currentFeed();
        if (feed == null || !feed.loaded || feed.loading || feed.moreFailed || feed.nextKey == null
                || (list == mList && (mSortPending || mListSwapping))) {
            return;
        }
        LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        CommentsAdapter adapter = adapterFor(feed);
        int last = adapter.lastCommentPosition();
        if (manager != null && last >= 0 && manager.findLastVisibleItemPosition() >= last - PAGE_AHEAD) {
            loadNextPage(feed);
        }
    }

    private void loadNextPage(Feed feed) {
        if (mReleased || mService == null || feed.nextKey == null || feed.loading) {
            return;
        }
        // The token stays on the feed until its page arrives, so a failure can ask for it again.
        final String key = feed.nextKey;
        final int generation = mGeneration;
        feed.loading = true;
        feed.moreFailed = false;
        if (isShown(feed)) {
            adapterFor(feed).setFooter(CommentsAdapter.FOOTER_LOADING);
        }
        feed.request = mService.getCommentsObserve(key).subscribe(
                group -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    feed.loading = false;
                    feed.request = null;
                    List<CommentsAdapter.Entry> more = toEntries(group, feed.replies);
                    feed.emptyPages = more.isEmpty() ? feed.emptyPages + 1 : 0;
                    removeIds(more, postedFor(feed));
                    forgetDeleted(more);
                    attachPostedReplies(more);
                    // A page of nothing may still carry a token; a few in a row means the end.
                    feed.nextKey = feed.emptyPages >= 3 ? null : nextKey(group, key);
                    feed.entries.addAll(more);
                    if (isShown(feed)) {
                        adapterFor(feed).append(more, feed.footer());
                        // Still short of the end of the screen: no scroll will come to ask for more.
                        RecyclerView list = feed.replies ? mReplies : mList;
                        list.post(() -> maybeLoadMore(list));
                    }
                },
                error -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    feed.loading = false;
                    feed.request = null;
                    feed.moreFailed = true;
                    if (isShown(feed)) {
                        adapterFor(feed).setFooter(CommentsAdapter.FOOTER_RETRY);
                    }
                });
    }

    @Override
    public void onRetry() {
        Feed feed = mThreadParent != null ? mThread : currentFeed();
        if (feed == null || feed.loading) {
            return;
        }
        if (!feed.loaded) {
            // The replies page's first page failed: its retry row asks for it again.
            adapterFor(feed).setFooter(CommentsAdapter.FOOTER_NONE);
            replaceContent(adapterFor(feed), mThreadParent, labelFor(mThreadParent), feed.entries,
                    REPLIES_SKELETONS, CommentsAdapter.FOOTER_NONE);
            loadFirstPage(feed);
            return;
        }
        feed.moreFailed = false;
        loadNextPage(feed);
    }

    private static List<CommentsAdapter.Entry> toEntries(@Nullable CommentGroup group, boolean replies) {
        List<CommentsAdapter.Entry> entries = new ArrayList<>();
        if (group == null || group.getComments() == null) {
            return entries;
        }
        for (CommentItem item : group.getComments()) {
            // A replies page starts with the comment itself, as a bare renderer the parser leaves
            // blank: skip that and any other empty placeholder. (Not CommentItem.isEmpty(), which
            // YouTube's implementation uses to mean "has no replies".)
            if (item == null || TextUtils.isEmpty(item.getMessage()) && TextUtils.isEmpty(item.getAuthorName())) {
                continue;
            }
            entries.add(new CommentsAdapter.Entry(item, replies));
        }
        return entries;
    }

    @Nullable
    private static String nextKey(@Nullable CommentGroup group, @Nullable String requested) {
        String next = group != null ? group.getNextCommentsKey() : null;
        return Helpers.equals(next, requested) ? null : next;
    }

    /** "Pinned by @handle" names the creator: their comments get the creator pill from here on. */
    private void learnCreator(List<CommentsAdapter.Entry> entries) {
        if (mCreatorHandle != null) {
            return;
        }
        for (CommentsAdapter.Entry entry : entries) {
            String pinned = entry.item.getPinnedLabel();
            if (pinned == null) {
                continue;
            }
            int at = pinned.lastIndexOf('@');
            if (at >= 0) {
                mCreatorHandle = pinned.substring(at).trim();
                mListAdapter.setCreatorHandle(mCreatorHandle);
                mRepliesAdapter.setCreatorHandle(mCreatorHandle);
            }
            return;
        }
    }

    private void cancelRequests() {
        // A post or delete already sent may still land on YouTube; its answer is simply not shown.
        RxHelper.disposeActions(mPostRequest, mDeleteRequest);
        mPostRequest = null;
        mDeleteRequest = null;
        if (mTop != null) {
            mTop.cancel();
        }
        if (mNewest != null) {
            mNewest.cancel();
        }
        if (mThread != null) {
            mThread.cancel();
        }
    }

    // ---------------------------------------------------------------------------------
    // The list page
    // ---------------------------------------------------------------------------------

    /** Show {@code feed} on the list page as it stands: rows, its failure, or skeletons + a load. */
    private void bindFeedToList(Feed feed, boolean restoreScroll) {
        if (feed.loaded) {
            if (feed.entries.isEmpty()) {
                replaceContent(mListAdapter, null, null, feed.entries, 0, CommentsAdapter.FOOTER_NONE);
                showState(R.string.mobile_comments_none, false);
            } else {
                hideState();
                replaceContent(mListAdapter, null, null, feed.entries, 0, feed.footer());
            }
        } else if (feed.failure != NO_FAILURE && !feed.loading) {
            replaceContent(mListAdapter, null, null, feed.entries, 0, CommentsAdapter.FOOTER_NONE);
            showState(feed.failure == LoadFailure.NO_CONNECTION
                    ? R.string.mobile_empty_no_connection : R.string.mobile_comments_load_error, true);
        } else {
            hideState();
            replaceContent(mListAdapter, null, null, feed.entries, FIRST_PAGE_SKELETONS, CommentsAdapter.FOOTER_NONE);
            loadFirstPage(feed);
        }
        LinearLayoutManager manager = (LinearLayoutManager) mList.getLayoutManager();
        if (manager != null) {
            if (restoreScroll && feed.scroll != null) {
                manager.onRestoreInstanceState(feed.scroll);
            } else if (!restoreScroll && feed.scroll != null && feed.loaded) {
                manager.onRestoreInstanceState(feed.scroll);
            } else {
                manager.scrollToPositionWithOffset(0, 0);
            }
        }
        updateHairline(false);
    }

    private void saveScroll() {
        Feed feed = currentFeed();
        LinearLayoutManager manager = (LinearLayoutManager) mList.getLayoutManager();
        if (feed != null && manager != null && feed.loaded && !mSortPending) {
            feed.scroll = manager.onSaveInstanceState();
        }
    }

    private void showState(int message, boolean retry) {
        mLoadStateMessage.setText(message);
        mLoadState.findViewById(R.id.mobile_page_load_state_action).setVisibility(retry ? View.VISIBLE : View.GONE);
        mLoadState.findViewById(R.id.mobile_page_load_state_icon).setVisibility(retry ? View.VISIBLE : View.GONE);
        mLoadState.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        mLoadState.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------
    // Sort
    // ---------------------------------------------------------------------------------

    private void updateSortLabel() {
        int label = mNewestSelected ? R.string.mobile_comments_sort_newest : R.string.mobile_comments_sort_top;
        mSortLabel.setText(label);
        mSort.setContentDescription(mActivity.getString(R.string.mobile_comments_sort_description,
                mActivity.getString(label)));
    }

    private void updateCount() {
        mCountView.setText(mCount);
        mCountView.setVisibility(TextUtils.isEmpty(mCount) ? View.GONE : View.VISIBLE);
    }

    private void showSortMenu() {
        if (mSortMenu != null || mNewest == null || mThreadParent != null || mPageTransition
                || mListSwapping || mReleased) {
            return;
        }
        View content = LayoutInflater.from(mActivity).inflate(R.layout.mobile_comments_sort_menu,
                (ViewGroup) mLayout, false);
        content.findViewById(R.id.comments_sort_top_check).setVisibility(mNewestSelected ? View.INVISIBLE : View.VISIBLE);
        content.findViewById(R.id.comments_sort_newest_check).setVisibility(mNewestSelected ? View.VISIBLE : View.INVISIBLE);
        content.findViewById(R.id.comments_sort_top).setOnClickListener(v -> {
            dismissSortMenu(true);
            setSort(false);
        });
        content.findViewById(R.id.comments_sort_newest).setOnClickListener(v -> {
            dismissSortMenu(true);
            setSort(true);
        });
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        PopupWindow menu = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setAnimationStyle(0);
        menu.setOutsideTouchable(true);
        menu.setElevation(8 * mDensity);
        menu.setOnDismissListener(() -> {
            if (mSortMenu == menu) {
                mSortMenu = null;
            }
            mSortChevron.animate().rotation(0f).setStartDelay(0).setDuration(MENU_GROW_MS)
                    .setInterpolator(CommentsPanelLayout.STANDARD).start();
        });
        mSortMenu = menu;

        // Grows from under the sort button, its end edge on the button's.
        int[] at = new int[2];
        mSort.getLocationInWindow(at);
        boolean rtl = ViewCompat.getLayoutDirection(mLayout) == ViewCompat.LAYOUT_DIRECTION_RTL;
        int width = content.getMeasuredWidth();
        int x = rtl ? at[0] : at[0] + mSort.getWidth() - width;
        int y = at[1] + mSort.getHeight() - Math.round(4 * mDensity);
        // LEFT, not START: x is measured from the window's left edge in both directions.
        menu.showAtLocation(mLayout, Gravity.TOP | Gravity.LEFT, Math.max(0, x), y);

        content.setPivotX(rtl ? width * 0.15f : width * 0.85f);
        content.setPivotY(0f);
        content.setScaleX(0.85f);
        content.setScaleY(0.85f);
        content.setAlpha(0f);
        content.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(MENU_GROW_MS)
                .setInterpolator(CommentsPanelLayout.EMPHASIZED_DECELERATE).start();
        content.animate().alpha(1f).setStartDelay(0).setDuration(MENU_FADE_MS).setInterpolator(null).start();
        mSortChevron.animate().rotation(180f).setStartDelay(0).setDuration(MENU_GROW_MS)
                .setInterpolator(CommentsPanelLayout.STANDARD).start();
    }

    /**
     * NEWTUBE(theme): the app changed theme under an open panel (the player re-colours its page in
     * place). The panel's own views are re-coloured with the page and its lists' rows rebuilt;
     * here the comments forget the link colour baked into their text, and the sort menu - a popup
     * with the old colours - closes.
     */
    void setSideCutoutInsets(int left, int right) {
        View sheet = mLayout.getSheet();
        if (sheet != null && (sheet.getPaddingLeft() != left || sheet.getPaddingRight() != right)) {
            sheet.setPadding(left, sheet.getPaddingTop(), right, sheet.getPaddingBottom());
        }
    }

    void onThemeChanged() {
        dismissSortMenu(false);
        dismissCommentMenu();
        closeComposer();
        dismissDeleteDialog();
        for (Feed feed : new Feed[] {mTop, mNewest, mThread}) {
            if (feed != null) {
                for (CommentsAdapter.Entry entry : feed.entries) {
                    entry.forgetStyledText();
                }
            }
        }
        if (mThreadParent != null) {
            mThreadParent.forgetStyledText();
        }
    }

    private void dismissSortMenu(boolean animate) {
        if (!animate && mClosingMenu != null) {
            // A fade already under way: teardown can't wait for it.
            PopupWindow closing = mClosingMenu;
            mClosingMenu = null;
            if (closing.getContentView() != null) {
                closing.getContentView().animate().cancel();
            }
            closing.dismiss();
        }
        PopupWindow menu = mSortMenu;
        if (menu == null) {
            return;
        }
        mSortMenu = null;
        View content = menu.getContentView();
        if (!animate || content == null || !menu.isShowing()) {
            menu.dismiss();
            return;
        }
        mClosingMenu = menu;
        content.animate().cancel();
        content.animate().alpha(0f).setStartDelay(0).setDuration(100).setInterpolator(null)
                .withEndAction(() -> {
                    if (mClosingMenu == menu) {
                        mClosingMenu = null;
                    }
                    menu.dismiss();
                }).start();
    }

    private void setSort(boolean newest) {
        if (newest == mNewestSelected || mSortPending || mListSwapping || (newest && mNewest == null)) {
            return;
        }
        Feed from = currentFeed();
        saveScroll();
        mNewestSelected = newest;
        updateSortLabel();
        Feed to = currentFeed();
        if (to == null) {
            return;
        }
        if (from == null || !from.loaded || from.entries.isEmpty()) {
            // Nothing worth keeping on screen: show the other order at once (skeletons or rows).
            bindFeedToList(to, true);
            return;
        }
        if (to.loaded || to.failure != NO_FAILURE && !to.loading) {
            // Kept from before: out in 90ms, back in 150ms, where it was left.
            swapList(to, true);
            return;
        }
        // First time for this order: the current list dims under the progress line until it lands.
        mSortPending = true;
        fadeList(SORT_DIM_ALPHA, QUICK_FADE_MS, null);
        mProgress.show();
        loadFirstPage(to);
    }

    @Nullable
    private Feed pendingSortFeed() {
        return mSortPending ? currentFeed() : null;
    }

    private void finishSortSwitch(Feed feed) {
        mProgress.hide();
        swapList(feed, false); // mSortPending holds until the new rows are in
    }

    /**
     * The list page fades out (90ms), takes {@code feed}'s rows, and fades back in (150ms). Until
     * the rows are swapped nothing may act on the list: no replies page, pagination or sort.
     */
    private void swapList(Feed feed, boolean restoreScroll) {
        mListSwapping = true;
        fadeList(0f, FADE_OUT_MS, () -> {
            mListSwapping = false;
            mSortPending = false;
            bindFeedToList(feed, restoreScroll);
            fadeList(1f, QUICK_FADE_MS, null);
            mList.post(() -> maybeLoadMore(mList));
        });
    }

    /** The other order didn't load: back on the one that was showing, and say so. */
    private void abandonSortSwitch() {
        mSortPending = false;
        mProgress.hide();
        mNewestSelected = !mNewestSelected;
        updateSortLabel();
        Feed restored = currentFeed();
        if (restored != null && mThreadParent == null) {
            // Rebound, not assumed: the panel may have been closed and reopened meanwhile.
            bindFeedToList(restored, true);
        }
        fadeList(1f, QUICK_FADE_MS, null);
        MobileSnackbar.show(mActivity, R.string.mobile_comments_load_error);
    }

    // ---------------------------------------------------------------------------------
    // The replies page (Material shared axis X)
    // ---------------------------------------------------------------------------------

    @Override
    public void onRepliesClicked(CommentsAdapter.Entry entry) {
        if (mThreadParent != null || mPageTransition || !entry.hasReplies() || mSortPending
                || mListSwapping || !mLayout.isOpen()) {
            return;
        }
        dismissSortMenu(false);
        dismissCommentMenu();
        saveScroll();
        mThreadParent = entry;
        mThread = new Feed(entry.item.getNestedCommentsKey(), true);
        replaceContent(mRepliesAdapter, entry, labelFor(entry), mThread.entries, REPLIES_SKELETONS,
                CommentsAdapter.FOOTER_NONE);
        LinearLayoutManager manager = (LinearLayoutManager) mReplies.getLayoutManager();
        if (manager != null) {
            manager.scrollToPositionWithOffset(0, 0);
        }
        loadFirstPage(mThread);
        sharedAxis(true);
    }

    private void leaveReplies() {
        if (mThreadParent == null || mPageTransition) {
            return;
        }
        if (mThread != null) {
            mThread.cancel();
        }
        mThreadParent = null;
        sharedAxis(false);
    }

    @Nullable
    private CharSequence labelFor(@Nullable CommentsAdapter.Entry entry) {
        return entry != null ? entry.replyLabel(mActivity) : null;
    }

    /**
     * Forward: the list slides 30dp toward the start and fades out in 90ms while the replies page
     * slides in from 30dp and fades in over the next 210ms (both moves 300ms, emphasized); the
     * header's title crossfades and shifts to make room for the back arrow. Backward is the mirror.
     * One animator drives it all, so it can't come apart, and with animations off it just ends.
     */
    private void sharedAxis(boolean forward) {
        if (mPageAnimator != null) {
            mPageAnimator.cancel();
        }
        mPageTransition = true;
        float dir = isRtl() ? -1f : 1f;
        float shift = 30 * mDensity * dir;
        float titleShift = 40 * mDensity * dir;
        float backShift = -10 * mDensity * dir;
        View out = forward ? mList : mReplies;
        View in = forward ? mReplies : mList;
        View titleOut = forward ? mTitleList : mTitleReplies;
        View titleIn = forward ? mTitleReplies : mTitleList;
        float outTo = forward ? -shift : shift;
        float inFrom = forward ? shift : -shift;
        float titlesFrom = mTitles.getTranslationX();
        float titlesTo = forward ? titleShift : 0f;
        float backFrom = mBack.getAlpha();
        float sortFrom = mSort.getAlpha();
        boolean sortShown = mNewest != null;

        for (View view : new View[]{out, in, titleOut, titleIn, mBack, mSort, mTitles}) {
            view.animate().cancel();
        }
        in.setVisibility(View.VISIBLE);
        titleIn.setVisibility(View.VISIBLE);
        mBack.setVisibility(View.VISIBLE);
        mBack.setEnabled(forward);
        mSort.setEnabled(!forward);
        out.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        in.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mHairline.animate().cancel();
        mHairline.animate().alpha(0f).setStartDelay(0).setDuration(QUICK_FADE_MS).start();
        mHairlineShown = false;

        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SHARED_AXIS_MS);
        animator.setInterpolator(null); // linear time: each property applies its own curve below
        animator.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            float ms = t * SHARED_AXIS_MS;
            float move = CommentsPanelLayout.EMPHASIZED.getInterpolation(t);
            out.setTranslationX(outTo * move);
            out.setAlpha(1f - clamp(ms / FADE_OUT_MS));
            in.setTranslationX(inFrom * (1f - move));
            in.setAlpha(clamp((ms - FADE_OUT_MS) / FADE_IN_MS));
            mTitles.setTranslationX(titlesFrom + (titlesTo - titlesFrom) * move);
            titleOut.setAlpha(1f - clamp(ms / QUICK_FADE_MS));
            titleIn.setAlpha(clamp((ms - FADE_OUT_MS) / QUICK_FADE_MS));
            float back = clamp(ms / 200f);
            mBack.setAlpha(forward ? backFrom + (1f - backFrom) * back : backFrom * (1f - back));
            mBack.setTranslationX(forward ? backShift * (1f - move) : backShift * move);
            if (sortShown) {
                float sort = forward ? sortFrom * (1f - clamp(ms / QUICK_FADE_MS))
                        : sortFrom + (1f - sortFrom) * clamp((ms - FADE_OUT_MS) / QUICK_FADE_MS);
                mSort.setAlpha(sort);
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mPageAnimator == animation) {
                    mPageAnimator = null;
                }
                if (mCancelled) {
                    return;
                }
                mPageTransition = false;
                out.setVisibility(View.INVISIBLE);
                out.setTranslationX(0f);
                out.setAlpha(1f);
                titleOut.setVisibility(View.INVISIBLE);
                if (!forward) {
                    mBack.setVisibility(View.INVISIBLE);
                    replaceContent(mRepliesAdapter, null, null, new ArrayList<>(), 0, CommentsAdapter.FOOTER_NONE);
                    mThread = null;
                }
                updateHairline(true);
                // TalkBack lands on the new page's way back (or on the list's sort control).
                View focus = forward ? mBack : mSort.getVisibility() == View.VISIBLE ? mSort : mList;
                focus.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
            }
        });
        mPageAnimator = animator;
        animator.start();
    }

    /** The list page as the resting state (no transition, no replies). */
    private void showListPage() {
        if (mPageAnimator != null) {
            mPageAnimator.cancel();
            mPageAnimator = null;
        }
        mPageTransition = false;
        if (mListSwapping) {
            // The swap's fade is cancelled below with the rest: whoever reset the page rebinds it.
            mListSwapping = false;
            mSortPending = false;
        }
        for (View view : new View[]{mList, mReplies, mTitles, mTitleList, mTitleReplies, mBack, mSort}) {
            view.animate().cancel();
            view.setTranslationX(0f);
        }
        mList.setVisibility(View.VISIBLE);
        mList.setAlpha(1f);
        mList.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        mReplies.setVisibility(View.INVISIBLE);
        mReplies.setAlpha(1f);
        mReplies.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        mTitleList.setVisibility(View.VISIBLE);
        mTitleList.setAlpha(1f);
        mTitleReplies.setVisibility(View.INVISIBLE);
        mTitleReplies.setAlpha(0f);
        mBack.setVisibility(View.INVISIBLE);
        mBack.setAlpha(0f);
        mSort.setAlpha(1f);
        mSort.setEnabled(true);
    }

    /** The list page's fades (sort switches). Linear, no delay - set every time, since a view's
     *  ViewPropertyAnimator keeps the last call's settings. */
    private void fadeList(float alpha, long durationMs, @Nullable Runnable end) {
        mList.animate().cancel();
        ViewPropertyAnimator animator = mList.animate().alpha(alpha).setDuration(durationMs).setStartDelay(0)
                .setInterpolator(null);
        if (end != null) {
            animator.withEndAction(end);
        }
        animator.start();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean isRtl() {
        return ViewCompat.getLayoutDirection(mLayout) == ViewCompat.LAYOUT_DIRECTION_RTL;
    }

    @Nullable
    private RecyclerView activePage() {
        return mThreadParent != null ? mReplies : mList;
    }

    private boolean isActivePage(RecyclerView list) {
        return list == activePage();
    }

    /** The hairline under the header shows once the page is scrolled away from its top. */
    private void updateHairline(boolean animate) {
        RecyclerView page = activePage();
        boolean show = page != null && page.canScrollVertically(-1) && !mPageTransition;
        if (show == mHairlineShown && animate) {
            return;
        }
        mHairlineShown = show;
        mHairline.animate().cancel();
        if (animate) {
            mHairline.animate().alpha(show ? 1f : 0f).setStartDelay(0).setDuration(QUICK_FADE_MS).start();
        } else {
            mHairline.setAlpha(show ? 1f : 0f);
        }
    }

    // ---------------------------------------------------------------------------------
    // Row actions
    // ---------------------------------------------------------------------------------

    @Override
    public void onLikeClicked(CommentsAdapter.Entry entry) {
        if (WatchActionFeedback.blockIfSignedOut(mActivity, R.string.mobile_comments_sign_in_to_like)) {
            return;
        }
        // NEWTUBE(haptics): the like takes effect - the watch page's like clicks the same way.
        com.newtube.mobile.ui.common.Haptics.click(mActivity.getWindow().getDecorView());
        boolean liked = !entry.liked;
        entry.liked = liked;
        String count = entry.likeCount;
        if (TextUtils.isEmpty(count)) {
            count = "0";
        }
        if (Helpers.isInteger(count)) {
            int value = Helpers.parseInt(count) + (liked ? 1 : -1);
            entry.likeCount = value > 0 ? String.valueOf(value) : null;
        }
        notifyEntry(entry, CommentsAdapter.PAYLOAD_LIKE);
        String key = entry.item.getNestedCommentsKey();
        if (key != null && mService != null) {
            RxHelper.execute(mService.toggleLikeObserve(key));
        }
    }

    private void notifyEntry(CommentsAdapter.Entry entry, Object payload) {
        mListAdapter.notifyEntry(entry, payload);
        mRepliesAdapter.notifyEntry(entry, payload);
    }

    @Override
    public void onLinkClicked(CommentItem.Span span) {
        if (span.videoId != null) {
            if (span.videoId.equals(mVideoId)) {
                if (span.startTimeSeconds >= 0) {
                    mHost.onCommentTimestamp(span.startTimeSeconds * 1000L);
                }
            } else {
                mHost.onCommentVideoLink(span.videoId);
            }
            return;
        }
        if (span.url != null) {
            openUrl(span.url);
        }
    }

    private void openUrl(String url) {
        Uri uri = Uri.parse(url);
        // YouTube wraps outside links in its redirect page: go straight to the address itself.
        String host = uri.getHost();
        if (host != null && host.endsWith("youtube.com") && "/redirect".equals(uri.getPath())) {
            String target = uri.getQueryParameter("q");
            if (!TextUtils.isEmpty(target)) {
                uri = Uri.parse(target);
                host = uri.getHost();
            }
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (host != null && (host.endsWith("youtube.com") || host.equals("youtu.be"))) {
            // A YouTube address is ours to open (the manifest takes these links).
            intent.setPackage(mActivity.getPackageName());
        }
        try {
            mActivity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                mActivity.startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // Nothing on the device opens it.
            }
        }
    }

    @Override
    public void onCopy(CommentsAdapter.Entry entry) {
        String text = entry.item.getMessage();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(mActivity.getString(R.string.mobile_comments_title), text));
        // Android 13+ confirms a copy itself; earlier versions get ours.
        if (Build.VERSION.SDK_INT < 33) {
            MobileSnackbar.show(mActivity, R.string.mobile_comments_copied);
        }
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(write-comments): commenting, replying, deleting
    // ---------------------------------------------------------------------------------

    /** Who is signed in: their avatar on the "Add a comment…" rows, their handle for the ⋮. */
    private void refreshAccount() {
        Account account = null;
        try {
            account = YouTubeSignInService.instance().getSelectedAccount();
        } catch (RuntimeException e) {
            // No sign-in service: treated as signed out.
        }
        boolean signedIn = account != null;
        String handle = account instanceof YouTubeAccount
                ? normalizeHandle(((YouTubeAccount) account).getChannelName()) : null;
        String photo = account != null ? account.getAvatarImageUrl() : null;
        String accountKey = accountKey(account);
        boolean uiTest = !signedIn && com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                && "1".equals(com.newtube.mobile.MobileMainApplication.getDebugSystemProperty("debug.arc.comments_ui_test"));
        boolean ownerChanged = signedIn != mSignedIn || uiTest != mUiTest || !Helpers.equals(handle, mMyHandle)
                || !Helpers.equals(accountKey, mAccountKey);
        mSignedIn = signedIn;
        mUiTest = uiTest;
        mMyHandle = handle;
        mAccountKey = accountKey;
        if (!Helpers.equals(photo, mMyPhoto)) {
            mMyPhoto = photo;
            mListAdapter.setComposePhoto(photo);
            mRepliesAdapter.setComposePhoto(photo);
        }
        if (ownerChanged) {
            // Another account (or none): the ⋮ follows whose comments these are.
            mListAdapter.notifyItemRangeChanged(0, mListAdapter.getItemCount());
            mRepliesAdapter.notifyItemRangeChanged(0, mRepliesAdapter.getItemCount());
        }
    }

    /** One channel: a Google account's brand channels share its name and address, not its page id. */
    @Nullable
    private static String accountKey(@Nullable Account account) {
        if (account == null) {
            return null;
        }
        String page = account instanceof YouTubeAccount ? ((YouTubeAccount) account).getPageIdToken() : null;
        return account.getName() + "\n" + account.getEmail() + "\n" + (page != null ? page : "");
    }

    /** "@Some_Handle" and "some_handle" are the same person. */
    @Nullable
    private static String normalizeHandle(@Nullable String handle) {
        if (handle == null) {
            return null;
        }
        String name = handle.trim();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean isOwnComment(CommentsAdapter.Entry entry) {
        if (!mSignedIn) {
            return mUiTest;
        }
        String id = entry.item.getId();
        if (id != null && mAccountKey != null && mAccountKey.equals(sPostedBy.get(id))) {
            return true;
        }
        return mMyHandle != null && mMyHandle.equals(normalizeHandle(entry.item.getAuthorName()));
    }

    @Override
    public void onComposeClicked(boolean reply) {
        if (mPageTransition) {
            return;
        }
        if (reply) {
            if (mThreadParent != null) {
                compose(mThreadParent, null);
            }
        } else {
            compose(null, null);
        }
    }

    /** Replies go to the thread's top comment; answering a reply names its author first. */
    @Override
    public void onReplyClicked(CommentsAdapter.Entry entry) {
        CommentsAdapter.Entry parent = entry.isReply ? mThreadParent : entry;
        if (parent == null || mPageTransition) {
            return;
        }
        compose(parent, mention(entry));
    }

    @Nullable
    private static String mention(CommentsAdapter.Entry entry) {
        String author = entry.item.getAuthorName();
        if (TextUtils.isEmpty(author) || TextUtils.isEmpty(author.trim())) {
            return null;
        }
        String handle = author.trim();
        return (handle.startsWith("@") ? handle : "@" + handle) + " ";
    }

    private void compose(@Nullable CommentsAdapter.Entry parent, @Nullable String prefill) {
        if (mReleased || mService == null || mVideoId == null || mComposer != null || mPostRequest != null
                || !mLayout.isOpen() || parent != null && parent.item.getId() == null) {
            return;
        }
        refreshAccount();
        if (!mUiTest && WatchActionFeedback.blockIfSignedOut(mActivity, R.string.mobile_comments_sign_in_to_comment)) {
            return;
        }
        dismissSortMenu(false);
        dismissCommentMenu();
        mComposeParent = parent;
        mComposePrefill = prefill;
        mComposeAccountKey = mAccountKey;
        mComposeUiTest = mUiTest;
        String draft = mDrafts.get(draftKey());
        mComposer = new CommentComposer(mActivity, parent == null
                ? R.string.mobile_comments_add_hint : R.string.mobile_comments_reply_hint,
                draft != null ? draft : prefill, prefill, mMyPhoto, this);
        mComposer.show();
    }

    /** The open composer's target: one account, and the video or one thread answered with one mention. */
    private String draftKey() {
        return mComposeAccountKey + "|" + (mComposeParent != null ? mComposeParent.item.getId() : "")
                + "|" + (mComposePrefill != null ? mComposePrefill : "");
    }

    private void closeComposer() {
        if (mComposer != null) {
            mComposer.dismiss();
        }
    }

    @Override
    public void onClosed(CommentComposer composer, @Nullable String draft) {
        if (composer != mComposer) {
            return;
        }
        mComposer = null;
        if (draft != null && !draft.trim().isEmpty() && !draft.trim().equals(
                mComposePrefill != null ? mComposePrefill.trim() : null)) {
            mDrafts.put(draftKey(), draft);
        } else {
            mDrafts.remove(draftKey());
        }
    }

    @Override
    public void onSend(CommentComposer composer, String text) {
        if (composer != mComposer || mPostRequest != null || mService == null || mVideoId == null || mReleased) {
            return;
        }
        CommentsAdapter.Entry parent = mComposeParent;
        String prefill = mComposePrefill;
        String key = draftKey();
        String videoId = mVideoId;
        int generation = mGeneration;
        refreshAccount();
        if (mComposeUiTest || mUiTest) {
            // The DEBUG UI check never reaches YouTube, whoever signs in meanwhile.
            composer.dismiss();
            onPostFailed(new IllegalStateException("UI check: not sent"), parent, prefill, generation);
            return;
        }
        if (mAccountKey == null || !mAccountKey.equals(mComposeAccountKey)) {
            // Written as one account, and another (or none) is signed in now: not sent as them.
            composer.dismiss();
            MobileSnackbar.show(mActivity, R.string.mobile_comments_post_failed);
            return;
        }
        String accountKey = mAccountKey;
        composer.setSending(true);
        Observable<CommentItem> post = parent == null
                ? mService.createCommentObserve(videoId, text)
                : mService.createReplyObserve(videoId, parent.item.getId(), text);
        mPostRequest = post.subscribe(
                item -> {
                    mPostRequest = null;
                    if (generation != mGeneration || mReleased) {
                        return;
                    }
                    composer.finish();
                    // Also when the composer was closed mid-post (fullscreen, a theme change).
                    mDrafts.remove(key);
                    onPosted(parent, item, accountKey);
                },
                error -> {
                    mPostRequest = null;
                    if (generation != mGeneration || mReleased) {
                        return;
                    }
                    composer.setSending(false);
                    composer.dismiss(); // the text stays as the draft (onClosed)
                    onPostFailed(error, parent, prefill, generation);
                });
    }

    private void onPosted(@Nullable CommentsAdapter.Entry parent, CommentItem item, String accountKey) {
        String id = item.getId();
        if (id == null) {
            // YouTube took it but did not describe it: it shows once the list is loaded again.
            MobileSnackbar.show(mActivity, parent == null
                    ? R.string.mobile_comments_posted : R.string.mobile_comments_reply_posted);
            return;
        }
        sPostedBy.put(id, accountKey);
        if (parent == null) {
            CommentsAdapter.Entry entry = new CommentsAdapter.Entry(item, false);
            mPostedTop.add(0, entry);
            for (Feed feed : new Feed[] {mTop, mNewest}) {
                if (feed != null && feed.loaded) {
                    feed.entries.add(0, entry);
                }
            }
            Feed shown = currentFeed();
            if (mThreadParent == null && shown != null && shown.loaded && !mSortPending && !mListSwapping) {
                hideState();
                mListAdapter.insertAtTop(entry);
                mList.scrollToPosition(0);
            }
            MobileSnackbar.show(mActivity, R.string.mobile_comments_posted);
            return;
        }
        String parentId = parent.item.getId();
        CommentsAdapter.Entry reply = new CommentsAdapter.Entry(item, true);
        List<CommentsAdapter.Entry> replies = mPostedReplies.get(parentId);
        if (replies == null) {
            replies = new ArrayList<>();
            mPostedReplies.put(parentId, replies);
        }
        replies.add(0, reply);
        // Every entry of that comment (Top's, Newest's, the replies page's) shows it has replies now.
        parent.postedReplies = replies;
        attachPostedReplies(allTopEntries());
        mListAdapter.notifyComment(parentId);
        mRepliesAdapter.notifyComment(parentId);
        if (isThreadOf(parentId)) {
            mRepliesAdapter.setParentLabel(labelFor(mThreadParent));
            if (mThread != null && mThread.loaded) {
                mThread.entries.add(0, reply);
                mRepliesAdapter.insertAtTop(reply);
                mReplies.scrollToPosition(0);
            } // still loading: the first page takes it (postedFor)
            MobileSnackbar.show(mActivity, R.string.mobile_comments_reply_posted);
        } else {
            int generation = mGeneration;
            MobileSnackbar.show(mActivity, mActivity.getString(R.string.mobile_comments_reply_posted),
                    mActivity.getString(R.string.mobile_comments_view_reply), () -> {
                        if (generation == mGeneration && mLayout.isOpen() && mThreadParent == null) {
                            onRepliesClicked(parent);
                        }
                    });
        }
    }

    private boolean isThreadOf(@Nullable String commentId) {
        return mThreadParent != null && commentId != null && commentId.equals(mThreadParent.item.getId());
    }

    /** The top-level entries this panel holds (both orders, what was posted, the open thread's). */
    private List<CommentsAdapter.Entry> allTopEntries() {
        List<CommentsAdapter.Entry> all = new ArrayList<>(mPostedTop);
        for (Feed feed : new Feed[] {mTop, mNewest}) {
            if (feed != null) {
                all.addAll(feed.entries);
            }
        }
        if (mThreadParent != null) {
            all.add(mThreadParent);
        }
        return all;
    }

    /** Entries of a comment the person replied to share its posted replies. */
    private void attachPostedReplies(List<CommentsAdapter.Entry> entries) {
        if (mPostedReplies.isEmpty()) {
            return;
        }
        for (CommentsAdapter.Entry entry : entries) {
            List<CommentsAdapter.Entry> replies = entry.isReply ? null : mPostedReplies.get(entry.item.getId());
            if (replies != null) {
                entry.postedReplies = replies;
            }
        }
    }

    private void forgetDeleted(List<CommentsAdapter.Entry> entries) {
        if (mDeletedIds.isEmpty()) {
            return;
        }
        for (Iterator<CommentsAdapter.Entry> it = entries.iterator(); it.hasNext(); ) {
            if (mDeletedIds.contains(it.next().item.getId())) {
                it.remove();
            }
        }
    }

    private void onPostFailed(Throwable error, @Nullable CommentsAdapter.Entry parent, @Nullable String prefill,
                              int generation) {
        String reason = youTubeReason(error);
        CharSequence text = LoadFailure.classify(mActivity, error) == LoadFailure.NO_CONNECTION
                ? mActivity.getString(R.string.mobile_empty_no_connection)
                : reason != null ? mActivity.getString(R.string.mobile_comments_post_failed_reason, reason)
                : mActivity.getString(R.string.mobile_comments_post_failed);
        MobileSnackbar.show(mActivity, text, mActivity.getString(R.string.mobile_comments_try_again), () -> {
            if (generation == mGeneration) {
                compose(parent, prefill); // the draft is still there
            }
        });
    }

    /** YouTube's own words for a refusal ("ErrorResponse: …"), never an exception's. */
    @Nullable
    private static String youTubeReason(Throwable error) {
        String message = error.getMessage();
        String prefix = "ErrorResponse: ";
        int at = message != null ? message.indexOf(prefix) : -1;
        if (at < 0) {
            return null;
        }
        String reason = message.substring(at + prefix.length()).trim();
        return reason.isEmpty() || reason.length() > 160 ? null : reason;
    }

    /** What the person posted that belongs on top of {@code feed}. */
    private List<CommentsAdapter.Entry> postedFor(Feed feed) {
        if (!feed.replies) {
            return mPostedTop;
        }
        List<CommentsAdapter.Entry> replies = feed == mThread && mThreadParent != null
                ? mPostedReplies.get(mThreadParent.item.getId()) : null;
        return replies != null ? replies : Collections.<CommentsAdapter.Entry>emptyList();
    }

    /** Drops from {@code entries} the comments that {@code posted} already shows. */
    private static void removeIds(List<CommentsAdapter.Entry> entries, List<CommentsAdapter.Entry> posted) {
        if (posted.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (CommentsAdapter.Entry entry : posted) {
            if (entry.item.getId() != null) {
                ids.add(entry.item.getId());
            }
        }
        for (Iterator<CommentsAdapter.Entry> it = entries.iterator(); it.hasNext(); ) {
            CommentsAdapter.Entry entry = it.next();
            if (!posted.contains(entry) && ids.contains(entry.item.getId())) {
                it.remove();
            }
        }
    }

    private static boolean removeId(List<CommentsAdapter.Entry> entries, String id) {
        boolean removed = false;
        for (Iterator<CommentsAdapter.Entry> it = entries.iterator(); it.hasNext(); ) {
            if (id.equals(it.next().item.getId())) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    /** The ⋮ of the person's own comment: Delete, growing from under the button. */
    @Override
    public void onMoreClicked(CommentsAdapter.Entry entry, View anchor) {
        if (mCommentMenu != null || mReleased || mPageTransition || !isOwnComment(entry)) {
            return;
        }
        dismissSortMenu(false);
        View content = LayoutInflater.from(mActivity).inflate(R.layout.mobile_comment_menu,
                (ViewGroup) mLayout, false);
        content.findViewById(R.id.comment_menu_delete).setOnClickListener(v -> {
            dismissCommentMenu();
            onDeleteClicked(entry);
        });
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        PopupWindow menu = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setAnimationStyle(0);
        menu.setOutsideTouchable(true);
        menu.setElevation(8 * mDensity);
        menu.setOnDismissListener(() -> {
            if (mCommentMenu == menu) {
                mCommentMenu = null;
            }
        });
        mCommentMenu = menu;

        // Its end edge on the button's; under it, or over it when the page ends first.
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int[] page = new int[2];
        mLayout.getLocationInWindow(page);
        boolean rtl = isRtl();
        int width = content.getMeasuredWidth();
        int height = content.getMeasuredHeight();
        int x = rtl ? at[0] : at[0] + anchor.getWidth() - width;
        int y = at[1] + anchor.getHeight() - Math.round(8 * mDensity);
        boolean above = y + height > page[1] + mLayout.getHeight();
        if (above) {
            y = at[1] - height + Math.round(8 * mDensity);
        }
        menu.showAtLocation(mLayout, Gravity.TOP | Gravity.LEFT, Math.max(0, x), Math.max(0, y));

        content.setPivotX(rtl ? width * 0.15f : width * 0.85f);
        content.setPivotY(above ? height : 0f);
        content.setScaleX(0.85f);
        content.setScaleY(0.85f);
        content.setAlpha(0f);
        content.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(MENU_GROW_MS)
                .setInterpolator(CommentsPanelLayout.EMPHASIZED_DECELERATE).start();
        content.animate().alpha(1f).setStartDelay(0).setDuration(MENU_FADE_MS).setInterpolator(null).start();
    }

    private void dismissCommentMenu() {
        PopupWindow menu = mCommentMenu;
        mCommentMenu = null;
        if (menu != null) {
            if (menu.getContentView() != null) {
                menu.getContentView().animate().cancel();
            }
            menu.dismiss();
        }
    }

    /**
     * Delete, after a confirmation (TalkBack's "Delete" action comes straight here). The
     * confirmation belongs to this video and account: it closes with them, and a tap on Delete
     * checks both again.
     */
    @Override
    public void onDeleteClicked(CommentsAdapter.Entry entry) {
        refreshAccount();
        String commentId = entry.item.getId();
        String videoId = mVideoId;
        if (mReleased || mDeleteRequest != null || mDeleteDialog != null || videoId == null || commentId == null
                || !isOwnComment(entry)) {
            return;
        }
        int generation = mGeneration;
        String accountKey = mAccountKey;
        boolean uiTest = mUiTest;
        // A reply belongs to the thread it was deleted from (its id is "parentId.replyId").
        String parentId = !entry.isReply ? null : mThreadParent != null ? mThreadParent.item.getId()
                : commentId.contains(".") ? commentId.substring(0, commentId.indexOf('.')) : null;
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(mActivity, R.style.MobileAlertDialog)
                .setTitle(entry.isReply ? R.string.mobile_comments_delete_reply_title
                        : R.string.mobile_comments_delete_title)
                .setMessage(R.string.mobile_comments_delete_message)
                .setNegativeButton(R.string.mobile_comments_delete_cancel, null)
                .setPositiveButton(R.string.mobile_comments_delete, (d, which) -> {
                    if (generation != mGeneration || mReleased) {
                        return;
                    }
                    refreshAccount();
                    if (uiTest || mUiTest) {
                        // The DEBUG UI check never reaches YouTube, whoever signs in meanwhile.
                        MobileSnackbar.show(mActivity, R.string.mobile_comments_delete_failed);
                        return;
                    }
                    if (accountKey == null || !accountKey.equals(mAccountKey)) {
                        MobileSnackbar.show(mActivity, R.string.mobile_comments_delete_failed);
                        return;
                    }
                    delete(entry, videoId, commentId, parentId, generation);
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (mDeleteDialog == d) {
                mDeleteDialog = null;
            }
        });
        mDeleteDialog = dialog;
        dialog.show();
    }

    private void dismissDeleteDialog() {
        androidx.appcompat.app.AlertDialog dialog = mDeleteDialog;
        mDeleteDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void delete(CommentsAdapter.Entry entry, String videoId, String commentId, @Nullable String parentId,
                        int generation) {
        if (mService == null || mDeleteRequest != null) {
            return;
        }
        mDeleteRequest = mService.deleteCommentObserve(videoId, commentId).subscribe(
                ignored -> {
                },
                error -> {
                    mDeleteRequest = null;
                    if (generation == mGeneration && !mReleased) {
                        MobileSnackbar.show(mActivity, R.string.mobile_comments_delete_failed);
                    }
                },
                () -> {
                    mDeleteRequest = null;
                    if (generation == mGeneration && !mReleased) {
                        removeComment(entry, commentId, parentId);
                        MobileSnackbar.show(mActivity, R.string.mobile_comments_deleted);
                    }
                });
    }

    /** A deleted comment leaves every list here that shows it (a reply: its thread's). */
    private void removeComment(CommentsAdapter.Entry entry, String id, @Nullable String parentId) {
        sPostedBy.remove(id);
        mDeletedIds.add(id);
        if (entry.isReply) {
            if (mThread != null) {
                removeId(mThread.entries, id);
            }
            mRepliesAdapter.removeComment(id);
            List<CommentsAdapter.Entry> replies = parentId != null ? mPostedReplies.get(parentId) : null;
            if (replies != null && removeId(replies, id)) {
                if (isThreadOf(parentId)) {
                    mRepliesAdapter.setParentLabel(labelFor(mThreadParent));
                }
                mListAdapter.notifyComment(parentId);
                mRepliesAdapter.notifyComment(parentId);
            }
            return;
        }
        if (isThreadOf(id)) {
            leaveReplies();
        }
        mPostedReplies.remove(id);
        removeId(mPostedTop, id);
        for (Feed feed : new Feed[] {mTop, mNewest}) {
            if (feed != null) {
                removeId(feed.entries, id);
            }
        }
        mListAdapter.removeComment(id);
        Feed shown = currentFeed();
        if (shown != null && shown.loaded && shown.entries.isEmpty() && !mSortPending && !mListSwapping) {
            showState(R.string.mobile_comments_none, false);
        }
    }
}
