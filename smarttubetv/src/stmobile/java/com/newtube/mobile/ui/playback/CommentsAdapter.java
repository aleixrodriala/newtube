package com.newtube.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MetaSeparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(comments-panel): the rows of one page of {@link CommentsPanel} - the comments list, or a
 * replies page. In order: the comment a replies page is about and its "N replies" line (replies
 * page only), loading skeletons while the first page is on its way, the comments, and a footer
 * (two skeletons while the next page loads, or a retry row when it failed).
 *
 * <p>Rows have stable ids, and a like updates only its own row's like views (a payload), so no
 * avatar reloads and nothing else moves. "Read more" is the text view's own business
 * ({@link CommentTextView}); the entry just remembers it.</p>
 *
 * <p>NEWTUBE(write-comments): an "Add a comment…" / "Add a reply…" row heads each page (after the
 * replies page's comment and label), every comment but the replies page's own has a Reply
 * button, and the person's own comments a ⋮ with Delete.</p>
 */
final class CommentsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onLikeClicked(Entry entry);
        void onRepliesClicked(Entry entry);
        void onLinkClicked(CommentItem.Span span);
        void onCopy(Entry entry);
        void onRetry();

        // NEWTUBE(write-comments)
        /** The "Add a comment…" row ({@code reply} = the replies page's "Add a reply…"). */
        void onComposeClicked(boolean reply);
        void onReplyClicked(Entry entry);
        void onMoreClicked(Entry entry, View anchor);
        void onDeleteClicked(Entry entry);
        /** The signed-in person wrote it: it gets the ⋮ with Delete. */
        boolean isOwnComment(Entry entry);
    }

    /** One comment and what the person did to it here (their like, whether it is unfolded). */
    static final class Entry {
        private static long sNextId = 1;

        final long id = sNextId++;
        final CommentItem item;
        final boolean isReply;
        boolean liked;
        @Nullable
        String likeCount;
        boolean expanded;
        /**
         * NEWTUBE(write-comments): replies the person posted to it here, newest first. The panel
         * shares one list among every entry of the same comment (Top and Newest each have theirs).
         */
        List<Entry> postedReplies = new ArrayList<>();
        @Nullable
        private CharSequence mText;

        Entry(CommentItem item, boolean isReply) {
            this.item = item;
            this.isReply = isReply;
            this.liked = item.isLiked();
            this.likeCount = item.getLikeCount();
        }

        boolean hasReplies() {
            return !isReply && item.getNestedCommentsKey() != null
                    && (hasCountedReplies() || !postedReplies.isEmpty());
        }

        /** "12 replies" as YouTube counts them, or the ones posted here on a comment that had none. */
        @Nullable
        CharSequence replyLabel(Context context) {
            if (hasCountedReplies()) {
                return item.getReplyCount();
            }
            int posted = postedReplies.size();
            return posted == 0 ? null
                    : context.getResources().getQuantityString(R.plurals.mobile_comments_replies, posted, posted);
        }

        private boolean hasCountedReplies() {
            String count = item.getReplyCount();
            return !TextUtils.isEmpty(count) && !"0".equals(count.trim());
        }

        /** NEWTUBE(theme): the styled text carries the link colour; build it again on next bind. */
        void forgetStyledText() {
            mText = null;
        }
    }

    static final int FOOTER_NONE = 0;
    static final int FOOTER_LOADING = 1;
    static final int FOOTER_RETRY = 2;

    private static final int TYPE_COMMENT = 0;
    private static final int TYPE_PARENT = 1;
    private static final int TYPE_LABEL = 2;
    private static final int TYPE_SKELETON = 3;
    private static final int TYPE_RETRY = 4;
    private static final int TYPE_COMPOSE = 5;

    static final Object PAYLOAD_LIKE = new Object();
    private static final int FOOTER_SKELETONS = 2;
    private static final long ID_LABEL = -2;
    private static final long ID_RETRY = -3;
    private static final long ID_COMPOSE = -4;
    private static final long ID_SKELETON_BASE = -100;

    private final Listener mListener;
    @Nullable
    private Entry mParent;
    @Nullable
    private CharSequence mParentLabel;
    private int mSkeletons;
    private final List<Entry> mItems = new ArrayList<>();
    private int mFooter = FOOTER_NONE;
    @Nullable
    private String mCreatorHandle;
    /** The "Add a comment…" row's text; 0 = no such row. */
    private int mComposeHint;
    @Nullable
    private String mComposePhoto;

    CommentsAdapter(Listener listener) {
        mListener = listener;
        setHasStableIds(true);
    }

    // ---------------------------------------------------------------------------------
    // Content
    // ---------------------------------------------------------------------------------

    /** Replace everything: a sort switch, a replies page opening, a reset. */
    void setContent(@Nullable Entry parent, @Nullable CharSequence parentLabel, List<Entry> items,
                    int skeletons, int footer) {
        mParent = parent;
        mParentLabel = parentLabel;
        mItems.clear();
        mItems.addAll(items);
        mSkeletons = items.isEmpty() ? skeletons : 0;
        mFooter = footer;
        notifyDataSetChanged();
    }

    /** The first page landed: the skeletons leave and the comments fade in where they were. */
    void showFirstPage(List<Entry> items, int footer) {
        int start = headerCount();
        if (mSkeletons > 0) {
            int skeletons = mSkeletons;
            mSkeletons = 0;
            notifyItemRangeRemoved(start, skeletons);
        }
        int oldItems = mItems.size();
        if (oldItems > 0) {
            mItems.clear();
            notifyItemRangeRemoved(start, oldItems);
        }
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
        setFooter(footer);
    }

    /** A further page: rows go on after the last one. */
    void append(List<Entry> more, int footer) {
        int start = headerCount() + mSkeletons + mItems.size();
        mItems.addAll(more);
        // The footer is replaced first, so the new rows arrive where the loading rows were.
        int oldFooter = footerCount();
        mFooter = FOOTER_NONE;
        if (oldFooter > 0) {
            notifyItemRangeRemoved(start, oldFooter);
        }
        notifyItemRangeInserted(start, more.size());
        setFooter(footer);
    }

    void setFooter(int footer) {
        if (mFooter == footer) {
            return;
        }
        int start = headerCount() + mSkeletons + mItems.size();
        int old = footerCount();
        mFooter = footer;
        int now = footerCount();
        if (old > 0) {
            notifyItemRangeRemoved(start, old);
        }
        if (now > 0) {
            notifyItemRangeInserted(start, now);
        }
    }

    int getFooter() {
        return mFooter;
    }

    /** NEWTUBE(write-comments): the page starts with an "Add a comment…" row reading {@code hint}. */
    void setCompose(int hint) {
        mComposeHint = hint;
        notifyDataSetChanged();
    }

    /** The signed-in person's avatar on the "Add a comment…" row (null = the placeholder). */
    void setComposePhoto(@Nullable String photo) {
        if (!Objects.equals(mComposePhoto, photo)) {
            mComposePhoto = photo;
            if (mComposeHint != 0) {
                notifyItemChanged(headerCount() - 1);
            }
        }
    }

    /** The replies page's "N replies" line (a reply posted to a comment that had none). */
    void setParentLabel(@Nullable CharSequence label) {
        mParentLabel = label;
        if (mParent != null) {
            notifyItemChanged(1);
        }
    }

    /** Every row showing this comment (the replies page's own included) binds again. */
    void notifyComment(@Nullable String commentId) {
        if (commentId == null) {
            return;
        }
        if (mParent != null && commentId.equals(mParent.item.getId())) {
            notifyItemChanged(0);
        }
        for (int i = 0; i < mItems.size(); i++) {
            if (commentId.equals(mItems.get(i).item.getId())) {
                notifyItemChanged(headerCount() + mSkeletons + i);
            }
        }
    }

    /** A comment the person just posted: first, under the header rows. */
    void insertAtTop(Entry entry) {
        mItems.add(0, entry);
        notifyItemInserted(headerCount() + mSkeletons);
    }

    /** A deleted comment leaves (every row showing it: the same comment may be two entries). */
    void removeComment(@Nullable String commentId) {
        if (commentId == null) {
            return;
        }
        for (int i = mItems.size() - 1; i >= 0; i--) {
            if (commentId.equals(mItems.get(i).item.getId())) {
                mItems.remove(i);
                notifyItemRemoved(headerCount() + mSkeletons + i);
            }
        }
    }

    void setCreatorHandle(@Nullable String handle) {
        if (!Objects.equals(mCreatorHandle, handle)) {
            mCreatorHandle = handle;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    void notifyEntry(Entry entry, @Nullable Object payload) {
        if (entry == mParent) {
            notifyItemChanged(0, payload);
            return;
        }
        int index = mItems.indexOf(entry);
        if (index >= 0) {
            notifyItemChanged(headerCount() + mSkeletons + index, payload);
        }
    }

    /** Adapter position of the last comment (not a skeleton or footer), or -1. */
    int lastCommentPosition() {
        return mItems.isEmpty() ? -1 : headerCount() + mSkeletons + mItems.size() - 1;
    }

    private int headerCount() {
        return (mParent != null ? 2 : 0) + (mComposeHint != 0 ? 1 : 0);
    }

    private int footerCount() {
        switch (mFooter) {
            case FOOTER_LOADING:
                return FOOTER_SKELETONS;
            case FOOTER_RETRY:
                return 1;
            default:
                return 0;
        }
    }

    // ---------------------------------------------------------------------------------
    // Adapter
    // ---------------------------------------------------------------------------------

    @Override
    public int getItemCount() {
        return headerCount() + mSkeletons + mItems.size() + footerCount();
    }

    @Override
    public int getItemViewType(int position) {
        int header = headerCount();
        if (position < header) {
            if (mParent != null && position < 2) {
                return position == 0 ? TYPE_PARENT : TYPE_LABEL;
            }
            return TYPE_COMPOSE;
        }
        position -= header;
        if (position < mSkeletons) {
            return TYPE_SKELETON;
        }
        position -= mSkeletons;
        if (position < mItems.size()) {
            return TYPE_COMMENT;
        }
        return mFooter == FOOTER_RETRY ? TYPE_RETRY : TYPE_SKELETON;
    }

    @Override
    public long getItemId(int position) {
        int header = headerCount();
        if (position < header) {
            if (mParent != null && position < 2) {
                return position == 0 ? mParent.id : ID_LABEL;
            }
            return ID_COMPOSE;
        }
        position -= header;
        if (position < mSkeletons) {
            return ID_SKELETON_BASE - position;
        }
        position -= mSkeletons;
        if (position < mItems.size()) {
            return mItems.get(position).id;
        }
        position -= mItems.size();
        return mFooter == FOOTER_RETRY ? ID_RETRY : ID_SKELETON_BASE - 50 - position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_LABEL:
                return new SimpleVH(inflater.inflate(R.layout.item_mobile_comment_label, parent, false));
            case TYPE_SKELETON:
                return new SkeletonVH(inflater.inflate(R.layout.item_mobile_comment_skeleton, parent, false));
            case TYPE_RETRY: {
                SimpleVH holder = new SimpleVH(inflater.inflate(R.layout.item_mobile_comment_retry, parent, false));
                holder.itemView.findViewById(R.id.comment_retry).setOnClickListener(v -> mListener.onRetry());
                return holder;
            }
            case TYPE_COMPOSE: {
                ComposeVH holder = new ComposeVH(inflater.inflate(R.layout.item_mobile_comment_compose, parent, false));
                holder.itemView.setOnClickListener(v -> mListener.onComposeClicked(mParent != null));
                return holder;
            }
            default:
                return new CommentVH(inflater.inflate(R.layout.item_mobile_comment, parent, false),
                        viewType == TYPE_PARENT, mListener);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        onBindViewHolder(holder, position, java.util.Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (holder instanceof CommentVH) {
            Entry entry = position == 0 && mParent != null ? mParent : mItems.get(position - headerCount() - mSkeletons);
            CommentVH comment = (CommentVH) holder;
            if (payloads.contains(PAYLOAD_LIKE)) {
                comment.bindLike(true);
            } else {
                comment.bind(entry, mCreatorHandle);
            }
        } else if (holder instanceof ComposeVH) {
            ((ComposeVH) holder).bind(mComposeHint, mComposePhoto);
        } else if (getItemViewType(position) == TYPE_LABEL) {
            ((TextView) holder.itemView).setText(mParentLabel);
        } else if (getItemViewType(position) == TYPE_RETRY) {
            // A replies page whose first page failed has nothing to be "more" than.
            ((TextView) holder.itemView.findViewById(R.id.comment_retry_message)).setText(
                    mParent != null && mItems.isEmpty()
                            ? R.string.mobile_comments_replies_load_error : R.string.mobile_load_more_error);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof CommentVH) {
            ((CommentVH) holder).recycle();
        } else if (holder instanceof ComposeVH) {
            Glide.with(holder.itemView.getContext()).clear(((ComposeVH) holder).mAvatar);
        }
    }

    // ---------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------

    private static final class SimpleVH extends RecyclerView.ViewHolder {
        SimpleVH(@NonNull View itemView) {
            super(itemView);
        }
    }

    /** NEWTUBE(write-comments): "Add a comment…" with the signed-in person's avatar. */
    private static final class ComposeVH extends RecyclerView.ViewHolder {
        final ImageView mAvatar;
        private final TextView mField;
        private final int mAvatarPx;

        ComposeVH(@NonNull View itemView) {
            super(itemView);
            mAvatar = itemView.findViewById(R.id.comment_compose_avatar);
            mField = itemView.findViewById(R.id.comment_compose_field);
            mAvatarPx = itemView.getResources().getDimensionPixelSize(R.dimen.mobile_comment_avatar);
        }

        void bind(int hint, @Nullable String photo) {
            if (hint != 0) {
                mField.setText(hint);
            }
            loadAvatar(mAvatar, photo, mAvatarPx);
        }
    }

    /** A round avatar near its drawn size, or the placeholder. */
    static void loadAvatar(ImageView view, @Nullable String url, int sizePx) {
        Context context = view.getContext();
        if (TextUtils.isEmpty(url)) {
            Glide.with(context).clear(view);
            view.setImageResource(R.drawable.ic_watch_channel_placeholder);
            return;
        }
        Glide.with(context)
                .load(url)
                .override(sizePx)
                .circleCrop()
                .placeholder(R.drawable.ic_watch_channel_placeholder)
                .error(R.drawable.ic_watch_channel_placeholder)
                .into(view);
    }

    /** A comment-shaped loading row; its root shimmers itself (ShimmerLinearLayout). */
    private static final class SkeletonVH extends RecyclerView.ViewHolder {
        SkeletonVH(@NonNull View itemView) {
            super(itemView);
        }
    }

    static final class CommentVH extends RecyclerView.ViewHolder implements CommentTextView.Listener {
        private static final Pattern AVATAR_SIZE = Pattern.compile("=s\\d+-");
        private static final long LIKE_POP_MS = 260;
        private static final long COUNT_ROLL_MS = 200;

        private final boolean mIsParent;
        private final Listener mListener;
        private final ImageView mAvatar;
        private final View mPinned;
        private final TextView mPinnedLabel;
        private final TextView mMeta;
        private final CommentTextView mMessage;
        private final View mLike;
        private final ImageView mLikeIcon;
        private final View mLikeCountBox;
        private final TextView mLikeCount;
        private final TextView mLikeCountNext;
        private final View mReplies;
        private final TextView mRepliesLabel;
        private final View mReply;
        private final View mMore;
        private final int mAvatarPx;
        private final int mHandleColor;
        private final int mCreatorPill;
        private final int mLinkColor;
        @Nullable
        private Entry mEntry;
        @Nullable
        private Animator mLikeAnimator;

        CommentVH(@NonNull View itemView, boolean isParent, Listener listener) {
            super(itemView);
            mIsParent = isParent;
            mListener = listener;
            Context context = itemView.getContext();
            mAvatar = itemView.findViewById(R.id.comment_avatar);
            mPinned = itemView.findViewById(R.id.comment_pinned);
            mPinnedLabel = itemView.findViewById(R.id.comment_pinned_label);
            mMeta = itemView.findViewById(R.id.comment_meta);
            mMessage = itemView.findViewById(R.id.comment_message);
            mLike = itemView.findViewById(R.id.comment_like);
            mLikeIcon = itemView.findViewById(R.id.comment_like_icon);
            mLikeCountBox = itemView.findViewById(R.id.comment_like_count_box);
            mLikeCount = itemView.findViewById(R.id.comment_like_count);
            mLikeCountNext = itemView.findViewById(R.id.comment_like_count_next);
            mReplies = itemView.findViewById(R.id.comment_replies);
            mRepliesLabel = itemView.findViewById(R.id.comment_replies_label);
            mReply = itemView.findViewById(R.id.comment_reply);
            mMore = itemView.findViewById(R.id.comment_more);
            mAvatarPx = context.getResources().getDimensionPixelSize(R.dimen.mobile_comment_avatar);
            mHandleColor = ContextCompat.getColor(context, R.color.mobile_color_comment_handle);
            mCreatorPill = ContextCompat.getColor(context, R.color.mobile_color_comment_creator_pill);
            mLinkColor = ContextCompat.getColor(context, R.color.mobile_color_link);

            if (isParent) {
                int ground = com.newtube.mobile.ui.common.ThemeMode.color(context, R.attr.newtubeColorCommentParent);
                itemView.setBackgroundColor(ground);
                itemView.setPadding(itemView.getPaddingLeft(), itemView.getPaddingTop(),
                        itemView.getPaddingRight(), itemView.getPaddingBottom() + dp(context, 6));
                mMessage.setGroundColor(ground);
                mMessage.setFoldable(false);
            }
            mMessage.setListener(this);
            mLike.setOnClickListener(v -> {
                if (mEntry != null) {
                    mListener.onLikeClicked(mEntry);
                }
            });
            mReplies.setOnClickListener(v -> {
                if (mEntry != null) {
                    mListener.onRepliesClicked(mEntry);
                }
            });
            // The replies page's own comment is answered from its "Add a reply…" row.
            mReply.setVisibility(isParent ? View.GONE : View.VISIBLE);
            mReply.setOnClickListener(v -> {
                if (mEntry != null) {
                    mListener.onReplyClicked(mEntry);
                }
            });
            mMore.setOnClickListener(v -> {
                if (mEntry != null) {
                    mListener.onMoreClicked(mEntry, v);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (mEntry != null) {
                    mListener.onCopy(mEntry);
                    return true;
                }
                return false;
            });
            // TalkBack reads a comment as one item and offers its actions there; the like and
            // replies buttons stay reachable on their own too.
            ViewCompat.setScreenReaderFocusable(itemView, true);
            ViewCompat.setAccessibilityDelegate(itemView, new RowAccessibility());
        }

        void bind(Entry entry, @Nullable String creatorHandle) {
            if (mEntry != entry) {
                cancelLikeAnimation();
            }
            mEntry = entry;
            Context context = itemView.getContext();
            CommentItem item = entry.item;

            String pinned = item.getPinnedLabel();
            mPinned.setVisibility(TextUtils.isEmpty(pinned) ? View.GONE : View.VISIBLE);
            mPinnedLabel.setText(pinned);

            mMeta.setText(buildMeta(item, creatorHandle));

            if (entry.mText == null) {
                entry.mText = buildText(item, mListener, mLinkColor);
            }
            mMessage.setText(entry.mText);
            mMessage.setVisibility(TextUtils.isEmpty(entry.mText) ? View.GONE : View.VISIBLE);
            mMessage.setExpanded(mIsParent || entry.expanded, false);

            bindLike(false);

            boolean replies = !mIsParent && entry.hasReplies();
            mReplies.setVisibility(replies ? View.VISIBLE : View.GONE);
            if (replies) {
                mRepliesLabel.setText(entry.replyLabel(context));
            }
            mMore.setVisibility(mListener.isOwnComment(entry) ? View.VISIBLE : View.GONE);

            loadAvatar(mAvatar, avatarUrl(item.getAuthorPhoto()), mAvatarPx);
        }

        /** Like state; {@code animate} = the person just tapped it (pop the thumb, roll the count). */
        void bindLike(boolean animate) {
            Entry entry = mEntry;
            if (entry == null) {
                return;
            }
            Context context = itemView.getContext();
            mLikeIcon.setImageResource(entry.liked ? R.drawable.ic_watch_thumb_up : R.drawable.ic_watch_thumb_up_outline);
            mLikeIcon.setColorFilter(ContextCompat.getColor(context,
                    entry.liked ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
            int countColor = ContextCompat.getColor(context,
                    entry.liked ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary);
            mLikeCount.setTextColor(countColor);
            mLikeCountNext.setTextColor(countColor);

            String count = entry.likeCount;
            mLike.setSelected(entry.liked);
            mLike.setContentDescription(TextUtils.isEmpty(count)
                    ? context.getString(R.string.mobile_comments_like)
                    : context.getString(R.string.mobile_comment_likes, count));
            ViewCompat.setStateDescription(mLike, entry.liked ? context.getString(R.string.mobile_comments_liked) : null);

            CharSequence shown = mLikeCount.getText();
            if (!animate || TextUtils.equals(shown, count == null ? "" : count)) {
                cancelLikeAnimation();
                mLikeCount.setText(count);
                mLikeCountBox.setVisibility(TextUtils.isEmpty(count) ? View.GONE : View.VISIBLE);
                if (animate) {
                    popThumb();
                }
                return;
            }
            popThumb();
            rollCount(count == null ? "" : count, entry.liked);
        }

        private void popThumb() {
            Keyframe k0 = Keyframe.ofFloat(0f, 1f);
            Keyframe k1 = Keyframe.ofFloat(0.35f, 0.78f);
            Keyframe k2 = Keyframe.ofFloat(0.75f, 1.12f);
            Keyframe k3 = Keyframe.ofFloat(1f, 1f);
            ObjectAnimator pop = ObjectAnimator.ofPropertyValuesHolder(mLikeIcon,
                    PropertyValuesHolder.ofKeyframe(View.SCALE_X, k0, k1, k2, k3),
                    PropertyValuesHolder.ofKeyframe(View.SCALE_Y, k0, k1, k2, k3));
            pop.setDuration(LIKE_POP_MS);
            pop.setInterpolator(CommentsPanelLayout.STANDARD);
            pop.start();
        }

        /** The count rolls: up to a higher number, down to a lower one. */
        private void rollCount(String next, boolean up) {
            cancelLikeAnimation();
            float distance = mLikeCountBox.getHeight() > 0 ? mLikeCountBox.getHeight() : dp(itemView.getContext(), 16);
            mLikeCountBox.setVisibility(View.VISIBLE);
            mLikeCountNext.setText(next);
            mLikeCountNext.setTranslationY(up ? distance : -distance);
            mLikeCountNext.setAlpha(0f);
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(
                    ObjectAnimator.ofFloat(mLikeCount, View.TRANSLATION_Y, 0f, up ? -distance : distance),
                    ObjectAnimator.ofFloat(mLikeCount, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(mLikeCountNext, View.TRANSLATION_Y, mLikeCountNext.getTranslationY(), 0f),
                    ObjectAnimator.ofFloat(mLikeCountNext, View.ALPHA, 0f, 1f));
            set.setDuration(COUNT_ROLL_MS);
            set.setInterpolator(CommentsPanelLayout.STANDARD);
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    settleCount(next);
                }
            });
            mLikeAnimator = set;
            set.start();
        }

        private void settleCount(String count) {
            mLikeAnimator = null;
            mLikeCount.setText(count);
            mLikeCount.setTranslationY(0f);
            mLikeCount.setAlpha(1f);
            mLikeCountNext.setText(null);
            mLikeCountNext.setTranslationY(0f);
            mLikeCountNext.setAlpha(0f);
            mLikeCountBox.setVisibility(TextUtils.isEmpty(count) ? View.GONE : View.VISIBLE);
        }

        private void cancelLikeAnimation() {
            if (mLikeAnimator != null) {
                Animator animator = mLikeAnimator;
                mLikeAnimator = null;
                animator.removeAllListeners();
                animator.cancel();
                mLikeCount.setTranslationY(0f);
                mLikeCount.setAlpha(1f);
                mLikeCountNext.setAlpha(0f);
                mLikeCountNext.setTranslationY(0f);
            }
        }

        void recycle() {
            cancelLikeAnimation();
            mMessage.finishFold();
            mLikeIcon.setScaleX(1f);
            mLikeIcon.setScaleY(1f);
            Glide.with(itemView.getContext()).clear(mAvatar);
            mEntry = null;
        }

        // CommentTextView.Listener

        @Override
        public void onFoldToggled(CommentTextView view, boolean expanded) {
            if (mEntry != null) {
                mEntry.expanded = expanded;
            }
        }

        @Override
        public void onTextLongPressed(CommentTextView view) {
            if (mEntry != null) {
                mListener.onCopy(mEntry);
            }
        }

        /** "@handle • 2 days ago"; the video's creator gets their handle on a quiet pill. */
        private CharSequence buildMeta(CommentItem item, @Nullable String creatorHandle) {
            String handle = item.getAuthorName() != null ? item.getAuthorName() : "";
            String date = item.getPublishedDate();
            SpannableStringBuilder meta = new SpannableStringBuilder(handle);
            if (!handle.isEmpty()) {
                if (handle.equals(creatorHandle)) {
                    meta.setSpan(new CreatorPillSpan(itemView.getContext(), mCreatorPill), 0, handle.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    meta.setSpan(new ForegroundColorSpan(mHandleColor), 0, handle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    meta.setSpan(new TypefaceSpan("sans-serif-medium"), 0, handle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (!TextUtils.isEmpty(date)) {
                if (meta.length() > 0) {
                    meta.append(MetaSeparator.DOT);
                }
                meta.append(date);
            }
            return meta;
        }

        /** Asks for the avatar near its drawn size (YouTube serves 48/88/176px of the same image). */
        @Nullable
        private String avatarUrl(@Nullable String url) {
            if (TextUtils.isEmpty(url)) {
                return null;
            }
            int size = mAvatarPx <= 48 ? 48 : mAvatarPx <= 88 ? 88 : 176;
            Matcher matcher = AVATAR_SIZE.matcher(url);
            return matcher.find() ? matcher.replaceFirst("=s" + size + "-") : url;
        }

        /** TalkBack: one comment, read whole, with its like / replies / fold / copy as actions. */
        private final class RowAccessibility extends AccessibilityDelegateCompat {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                Entry entry = mEntry;
                if (entry == null) {
                    return;
                }
                Context context = host.getContext();
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_like,
                        context.getString(entry.liked ? R.string.mobile_comments_unlike : R.string.mobile_comments_like)));
                if (!mIsParent) {
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_reply,
                            context.getString(R.string.mobile_comments_reply)));
                }
                if (!mIsParent && entry.hasReplies()) {
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_replies,
                            context.getString(R.string.mobile_comments_open_replies)));
                }
                if (mListener.isOwnComment(entry)) {
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_more,
                            context.getString(R.string.mobile_comments_delete)));
                }
                if (mMessage.canFold()) {
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_message,
                            context.getString(mMessage.isExpanded()
                                    ? R.string.mobile_comments_show_less : R.string.mobile_comments_read_more)));
                }
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.comment_root,
                        context.getString(R.string.mobile_comments_copy)));
            }

            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action, @Nullable Bundle args) {
                Entry entry = mEntry;
                if (entry != null) {
                    if (action == R.id.comment_like) {
                        mListener.onLikeClicked(entry);
                        return true;
                    } else if (action == R.id.comment_replies) {
                        mListener.onRepliesClicked(entry);
                        return true;
                    } else if (action == R.id.comment_reply) {
                        mListener.onReplyClicked(entry);
                        return true;
                    } else if (action == R.id.comment_more) {
                        mListener.onDeleteClicked(entry);
                        return true;
                    } else if (action == R.id.comment_message) {
                        boolean expanded = !mMessage.isExpanded();
                        mMessage.setExpanded(expanded, true);
                        entry.expanded = expanded;
                        return true;
                    } else if (action == R.id.comment_root) {
                        mListener.onCopy(entry);
                        return true;
                    }
                }
                return super.performAccessibilityAction(host, action, args);
            }
        }
    }

    /** The comment text with YouTube's bold stretches and links (timestamps included) in place. */
    static CharSequence buildText(CommentItem item, Listener listener, int linkColor) {
        String message = item.getMessage();
        if (TextUtils.isEmpty(message)) {
            return "";
        }
        // Trailing blank lines would only fold into a "Read more" that reveals nothing.
        int length = message.length();
        while (length > 0 && Character.isWhitespace(message.charAt(length - 1))) {
            length--;
        }
        message = message.substring(0, length);
        List<CommentItem.Span> spans = item.getMessageSpans();
        if (spans == null || spans.isEmpty()) {
            return message;
        }
        SpannableString text = new SpannableString(message);
        for (CommentItem.Span span : spans) {
            if (span.start < 0 || span.start >= Math.min(span.end, length)) {
                continue;
            }
            int end = Math.min(span.end, length);
            if (span.bold) {
                text.setSpan(new StyleSpan(Typeface.BOLD), span.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (span.isLink()) {
                text.setSpan(new LinkSpan(span, listener, linkColor), span.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text;
    }

    /** A link or timestamp in a comment: the app's link blue, no underline, handled by the panel. */
    private static final class LinkSpan extends ClickableSpan {
        private final CommentItem.Span mSpan;
        private final Listener mListener;
        private final int mColor;

        LinkSpan(CommentItem.Span span, Listener listener, int color) {
            mSpan = span;
            mListener = listener;
            mColor = color;
        }

        @Override
        public void onClick(@NonNull View widget) {
            mListener.onLinkClicked(mSpan);
        }

        @Override
        public void updateDrawState(@NonNull TextPaint paint) {
            paint.setColor(mColor);
            paint.setUnderlineText(false);
        }
    }

    /**
     * The video creator's handle on their own comment: the handle in 11sp medium on a quiet pill,
     * vertically centred on the meta line (the creator is known from "Pinned by @handle").
     */
    private static final class CreatorPillSpan extends ReplacementSpan {
        private final float mTextSize;
        private final float mPadding;
        private final float mHeight;
        private final int mFill;
        private final int mTextColor;
        private final Typeface mTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final RectF mRect = new RectF();

        CreatorPillSpan(Context context, int fill) {
            float density = context.getResources().getDisplayMetrics().density;
            mTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11,
                    context.getResources().getDisplayMetrics());
            mPadding = 6 * density;
            mHeight = 14 * density;
            mFill = fill;
            mTextColor = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        }

        private TextPaint pillPaint(Paint base) {
            TextPaint paint = new TextPaint(base);
            paint.setTextSize(mTextSize);
            paint.setTypeface(mTypeface);
            return paint;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return Math.round(pillPaint(paint).measureText(text, start, end) + 2 * mPadding);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, @NonNull Paint paint) {
            TextPaint pill = pillPaint(paint);
            float width = pill.measureText(text, start, end) + 2 * mPadding;
            Paint.FontMetrics lineMetrics = paint.getFontMetrics();
            float centre = y + (lineMetrics.ascent + lineMetrics.descent) / 2f;
            mRect.set(x, centre - mHeight / 2f, x + width, centre + mHeight / 2f);
            int color = pill.getColor();
            pill.setColor(mFill);
            canvas.drawRoundRect(mRect, mHeight / 2f, mHeight / 2f, pill);
            Paint.FontMetrics metrics = pill.getFontMetrics();
            pill.setColor(mTextColor);
            canvas.drawText(text, start, end, x + mPadding, centre - (metrics.ascent + metrics.descent) / 2f, pill);
            pill.setColor(color);
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
