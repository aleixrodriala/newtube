package com.newtube.mobile.ui.playback;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.liskovsoft.mediaserviceinterfaces.CommentsService;
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;

/**
 * Comments panel for the touch watch page. Reuses SmartTube's backend/data flow (the same
 * {@link CommentsService#getCommentsObserve(String)} +
 * {@link CommentsService#toggleLikeObserve(String)} that the TV {@code CommentsController} drives)
 * but renders into a Material {@link BottomSheetDialogFragment} + {@link CommentsAdapter} instead of
 * the Leanback comments dialog.
 *
 * <p>Flow: the caller passes the video's {@code commentsKey} (from {@code MediaItemMetadata}); the
 * first page loads on open, further pages load as the list nears its end (continuation via
 * {@link CommentGroup#getNextCommentsKey()}), replies load on demand via each comment's
 * {@code nestedCommentsKey} and embed inline, and a like tap toggles optimistically then calls the
 * backend.</p>
 */
public class CommentsSheet extends BottomSheetDialogFragment implements CommentsAdapter.Listener {

    private static final String ARG_COMMENTS_KEY = "comments_key";
    private static final String ARG_TITLE = "title";
    private static final String TAG_SHEET = "mobile_comments_sheet";
    private static final int PAGE_PREFETCH_DISTANCE = 4;

    private String mCommentsKey;
    private CharSequence mTitle;

    private CommentsService mCommentsService;
    private CommentsAdapter mAdapter;
    private RecyclerView mList;
    private ProgressBar mProgress;
    private TextView mEmpty;

    private Disposable mCommentsAction;
    private Disposable mRepliesAction;
    private String mNextKey;
    private boolean mLoading;
    private boolean mFirstPageLoaded;

    public static void show(@NonNull androidx.fragment.app.FragmentManager fm, String commentsKey, CharSequence title) {
        if (commentsKey == null) {
            return;
        }
        CommentsSheet sheet = new CommentsSheet();
        Bundle args = new Bundle();
        args.putString(ARG_COMMENTS_KEY, commentsKey);
        args.putCharSequence(ARG_TITLE, title);
        sheet.setArguments(args);
        sheet.show(fm, TAG_SHEET);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mCommentsKey = getArguments().getString(ARG_COMMENTS_KEY);
            mTitle = getArguments().getCharSequence(ARG_TITLE);
        }
        mCommentsService = YouTubeServiceManager.instance().getCommentsService();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_mobile_comments, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView title = view.findViewById(R.id.comments_sheet_title);
        if (mTitle != null && mTitle.length() > 0) {
            title.setText(mTitle);
        }
        view.findViewById(R.id.comments_sheet_close).setOnClickListener(v -> dismissAllowingStateLoss());

        mProgress = view.findViewById(R.id.comments_sheet_progress);
        mEmpty = view.findViewById(R.id.comments_sheet_empty);
        mList = view.findViewById(R.id.comments_sheet_list);

        mAdapter = new CommentsAdapter(this);
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }
                maybeLoadMore();
            }
        });

        loadFirstPage();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Make the sheet tall (most of the screen) and let its own rounded background show through.
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.85f);
                sheet.getLayoutParams().height = height;
                sheet.requestLayout();
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(height);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onDestroyView() {
        RxHelper.disposeActions(mCommentsAction, mRepliesAction);
        mCommentsAction = null;
        mRepliesAction = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------------------------
    // Loading (mirrors CommentsController.loadComments, rendered into the sheet)
    // ---------------------------------------------------------------------------------

    private void loadFirstPage() {
        if (mCommentsService == null || mCommentsKey == null) {
            showEmpty();
            return;
        }
        mLoading = true;
        setProgress(true);
        mCommentsAction = mCommentsService.getCommentsObserve(mCommentsKey)
                .subscribe(this::onFirstPage, this::onLoadError);
    }

    private void onFirstPage(CommentGroup group) {
        mLoading = false;
        mFirstPageLoaded = true;
        setProgress(false);

        if (group == null || group.getComments() == null || group.getComments().isEmpty()) {
            showEmpty();
            return;
        }

        mEmpty.setVisibility(View.GONE);
        mNextKey = group.getNextCommentsKey();
        mAdapter.addComments(group.getComments());
    }

    private void maybeLoadMore() {
        if (mLoading || mNextKey == null || !mFirstPageLoaded) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) mList.getLayoutManager();
        if (lm == null) {
            return;
        }
        int lastVisible = lm.findLastVisibleItemPosition();
        if (lastVisible >= mAdapter.getItemCount() - PAGE_PREFETCH_DISTANCE) {
            loadNextPage();
        }
    }

    private void loadNextPage() {
        final String key = mNextKey;
        mLoading = true;
        mNextKey = null; // guard against re-entrancy until this page resolves
        mCommentsAction = mCommentsService.getCommentsObserve(key)
                .subscribe(
                        group -> {
                            mLoading = false;
                            if (group != null && group.getComments() != null) {
                                mNextKey = group.getNextCommentsKey();
                                mAdapter.addComments(group.getComments());
                            }
                        },
                        error -> mLoading = false); // keep pagination error non-fatal
    }

    private void onLoadError(Throwable error) {
        mLoading = false;
        setProgress(false);
        showEmpty();
    }

    // ---------------------------------------------------------------------------------
    // CommentsAdapter.Listener
    // ---------------------------------------------------------------------------------

    @Override
    public void onToggleReplies(CommentsAdapter.CommentEntry entry) {
        if (entry.repliesExpanded) {
            mAdapter.removeReplies(entry);
            entry.repliesExpanded = false;
            mAdapter.notifyEntryChanged(entry);
            return;
        }

        if (entry.repliesLoading) {
            return;
        }

        String repliesKey = entry.item.getNestedCommentsKey();
        if (repliesKey == null || mCommentsService == null) {
            return;
        }

        entry.repliesLoading = true;
        mAdapter.notifyEntryChanged(entry);

        RxHelper.disposeActions(mRepliesAction);
        mRepliesAction = mCommentsService.getCommentsObserve(repliesKey)
                .subscribe(
                        group -> {
                            entry.repliesLoading = false;
                            entry.repliesExpanded = true;
                            if (group != null) {
                                entry.repliesNextKey = group.getNextCommentsKey();
                                mAdapter.insertReplies(entry, group.getComments());
                            }
                            mAdapter.notifyEntryChanged(entry);
                        },
                        error -> {
                            entry.repliesLoading = false;
                            mAdapter.notifyEntryChanged(entry);
                        });
    }

    @Override
    public void onToggleLike(CommentsAdapter.CommentEntry entry) {
        // Optimistic local toggle (mirrors CommentsController.MyCommentItem.setLiked), then backend.
        boolean nowLiked = !entry.liked;
        entry.liked = nowLiked;

        if (entry.likeCount == null) {
            entry.likeCount = String.valueOf(0);
        }
        if (Helpers.isInteger(entry.likeCount)) {
            int count = Helpers.parseInt(entry.likeCount);
            count = nowLiked ? count + 1 : count - 1;
            entry.likeCount = count > 0 ? String.valueOf(count) : null;
        }
        mAdapter.notifyEntryChanged(entry);

        String likeKey = entry.item.getNestedCommentsKey();
        if (likeKey != null && mCommentsService != null) {
            RxHelper.execute(mCommentsService.toggleLikeObserve(likeKey));
        }
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private void setProgress(boolean show) {
        if (mProgress != null) {
            mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showEmpty() {
        setProgress(false);
        if (mEmpty != null && mAdapter != null && mAdapter.getItemCount() == 0) {
            mEmpty.setVisibility(View.VISIBLE);
        }
    }
}
