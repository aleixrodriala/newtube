package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat-list adapter for {@link CommentsSheet}: top-level comments and their (indented) replies live
 * in one list. Replies are inserted immediately after their parent and always carry {@code isReply},
 * so they can be located/removed as the contiguous run of reply entries following a parent. The
 * hosting fragment owns all loading (first page, continuation, nested replies, like toggling); this
 * class is purely the view layer and mutates its list only through the small add/insert/remove
 * helpers below, firing the matching range notifications for smooth animations.
 */
public class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.VH> {

    public interface Listener {
        void onToggleReplies(CommentEntry entry);
        void onToggleLike(CommentEntry entry);
    }

    /** Mutable per-row state (local like state is applied optimistically before the backend confirms). */
    public static class CommentEntry {
        public final CommentItem item;
        public final boolean isReply;
        public boolean repliesExpanded;
        public boolean repliesLoading;
        public boolean liked;
        public String likeCount;
        /** Continuation key for further reply pages (null when the replies are exhausted). */
        public String repliesNextKey;

        CommentEntry(CommentItem item, boolean isReply) {
            this.item = item;
            this.isReply = isReply;
            this.liked = item.isLiked();
            this.likeCount = item.getLikeCount();
        }
    }

    private final List<CommentEntry> mItems = new ArrayList<>();
    private final Listener mListener;

    public CommentsAdapter(Listener listener) {
        mListener = listener;
    }

    public int indexOf(CommentEntry entry) {
        return mItems.indexOf(entry);
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    /** Append a fresh page of top-level comments (skips backend placeholder/empty items). */
    public void addComments(List<CommentItem> comments) {
        if (comments == null) {
            return;
        }
        int start = mItems.size();
        for (CommentItem c : comments) {
            if (isBlank(c)) {
                continue;
            }
            mItems.add(new CommentEntry(c, false));
        }
        int added = mItems.size() - start;
        if (added > 0) {
            notifyItemRangeInserted(start, added);
        }
    }

    /** Insert replies for a parent right after its existing (if any) reply run. */
    public void insertReplies(CommentEntry parent, List<CommentItem> replies) {
        if (replies == null) {
            return;
        }
        int parentIndex = mItems.indexOf(parent);
        if (parentIndex < 0) {
            return;
        }
        int insertAt = parentIndex + 1;
        while (insertAt < mItems.size() && mItems.get(insertAt).isReply) {
            insertAt++;
        }
        List<CommentEntry> toAdd = new ArrayList<>();
        for (CommentItem c : replies) {
            if (isBlank(c)) {
                continue;
            }
            toAdd.add(new CommentEntry(c, true));
        }
        if (!toAdd.isEmpty()) {
            mItems.addAll(insertAt, toAdd);
            notifyItemRangeInserted(insertAt, toAdd.size());
        }
    }

    /** Remove the contiguous run of reply entries that follows a parent. */
    public void removeReplies(CommentEntry parent) {
        int parentIndex = mItems.indexOf(parent);
        if (parentIndex < 0) {
            return;
        }
        int removeFrom = parentIndex + 1;
        int count = 0;
        while (removeFrom + count < mItems.size() && mItems.get(removeFrom + count).isReply) {
            count++;
        }
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                mItems.remove(removeFrom);
            }
            notifyItemRangeRemoved(removeFrom, count);
        }
    }

    /**
     * A truly blank item (no author and no text). NOTE: do NOT use {@code CommentItem.isEmpty()} here
     * - for YouTube that returns true whenever a comment has no nested replies (its "has replies"
     * marker), which would wrongly discard every leaf reply and every reply-less top-level comment.
     */
    private static boolean isBlank(CommentItem c) {
        return c == null || (TextUtils.isEmpty(c.getMessage()) && TextUtils.isEmpty(c.getAuthorName()));
    }

    public void notifyEntryChanged(CommentEntry entry) {
        int idx = mItems.indexOf(entry);
        if (idx >= 0) {
            notifyItemChanged(idx);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_comment, parent, false);
        return new VH(view, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        private final View mRoot;
        private final ImageView mAvatar;
        private final TextView mAuthor;
        private final TextView mMessage;
        private final LinearLayout mLikeGroup;
        private final ImageView mLikeIcon;
        private final TextView mLikeCount;
        private final TextView mRepliesToggle;
        private final int mBasePaddingPx;
        private final int mReplyPaddingPx;
        private CommentEntry mEntry;

        VH(@NonNull View itemView, Listener listener) {
            super(itemView);
            mRoot = itemView.findViewById(R.id.comment_root);
            mAvatar = itemView.findViewById(R.id.comment_avatar);
            mAuthor = itemView.findViewById(R.id.comment_author);
            mMessage = itemView.findViewById(R.id.comment_message);
            mLikeGroup = itemView.findViewById(R.id.comment_like_group);
            mLikeIcon = itemView.findViewById(R.id.comment_like_icon);
            mLikeCount = itemView.findViewById(R.id.comment_like_count);
            mRepliesToggle = itemView.findViewById(R.id.comment_replies_toggle);

            mBasePaddingPx = dp(itemView.getContext(), 12);
            mReplyPaddingPx = dp(itemView.getContext(), 44);

            mLikeGroup.setOnClickListener(v -> {
                if (mEntry != null && listener != null) {
                    listener.onToggleLike(mEntry);
                }
            });
            mRepliesToggle.setOnClickListener(v -> {
                if (mEntry != null && listener != null) {
                    listener.onToggleReplies(mEntry);
                }
            });
        }

        void bind(CommentEntry entry) {
            mEntry = entry;
            Context context = itemView.getContext();
            CommentItem item = entry.item;

            mRoot.setPadding(entry.isReply ? mReplyPaddingPx : mBasePaddingPx,
                    mRoot.getPaddingTop(),
                    mBasePaddingPx,
                    mRoot.getPaddingBottom());

            // Author + relative time on one secondary line.
            String author = item.getAuthorName() != null ? item.getAuthorName() : "";
            String date = item.getPublishedDate();
            mAuthor.setText(TextUtils.isEmpty(date) ? author : author + "  •  " + date);

            mMessage.setText(item.getMessage());

            // Like affordance: icon always visible, count only when the backend supplied one.
            if (TextUtils.isEmpty(entry.likeCount)) {
                mLikeCount.setVisibility(View.GONE);
            } else {
                mLikeCount.setVisibility(View.VISIBLE);
                mLikeCount.setText(entry.likeCount);
            }
            mLikeIcon.setColorFilter(ContextCompat.getColor(context,
                    entry.liked ? R.color.mobile_color_primary : R.color.mobile_color_on_surface_secondary));

            bindRepliesToggle(context, entry);

            String photo = item.getAuthorPhoto();
            if (TextUtils.isEmpty(photo)) {
                mAvatar.setImageResource(R.drawable.ic_watch_channel_placeholder);
            } else {
                Glide.with(context)
                        .load(photo)
                        .circleCrop()
                        .placeholder(R.drawable.ic_watch_channel_placeholder)
                        .error(R.drawable.ic_watch_channel_placeholder)
                        .into(mAvatar);
            }
        }

        private void bindRepliesToggle(Context context, CommentEntry entry) {
            boolean hasReplies = !entry.isReply && hasReplies(entry.item);
            if (!hasReplies) {
                mRepliesToggle.setVisibility(View.GONE);
                return;
            }

            mRepliesToggle.setVisibility(View.VISIBLE);
            if (entry.repliesLoading) {
                mRepliesToggle.setText(R.string.mobile_comments_loading_replies);
            } else if (entry.repliesExpanded) {
                mRepliesToggle.setText(R.string.mobile_comments_hide_replies);
            } else {
                String count = entry.item.getReplyCount();
                if (TextUtils.isEmpty(count)) {
                    mRepliesToggle.setText(R.string.mobile_comments_view_replies);
                } else {
                    mRepliesToggle.setText(context.getString(R.string.mobile_comments_view_replies_count, count));
                }
            }
        }

        void recycle() {
            Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
        }

        private static boolean hasReplies(CommentItem item) {
            String count = item.getReplyCount();
            return count != null && !count.isEmpty() && !"0".equals(count.trim());
        }

        private static int dp(Context context, int dp) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                    context.getResources().getDisplayMetrics()));
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.recycle();
    }
}
