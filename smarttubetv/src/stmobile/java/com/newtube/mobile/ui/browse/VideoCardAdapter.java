package com.newtube.mobile.ui.browse;

import android.annotation.SuppressLint;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.newtube.mobile.ui.playback.PlayerTransitionBridge;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Touch video card adapter (port of the gist of
 * {@code com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter}, minus
 * focus-scale/d-pad concerns; ripple/elevation instead via the card's own foreground
 * and {@code MaterialCardView} elevation).
 */
public class VideoCardAdapter extends ListAdapter<Video, RecyclerView.ViewHolder> {
    /** Regular video card (full-width thumbnail + title/meta). */
    private static final int VIEW_TYPE_VIDEO = 0;
    /** Channel result row (round avatar + name + subs) — e.g. channels in search results. */
    private static final int VIEW_TYPE_CHANNEL = 1;

    public interface OnVideoClickListener {
        void onVideoClick(Video video);
    }

    /** Long-press = the touch equivalent of the TV D-pad OK-long-press context-menu shortcut
     *  (ARCHITECTURE.md section 3's input contract: {@code onVideoItemLongClicked}). Routes to
     *  {@code BrowsePresenter.onVideoItemLongClicked()}, which builds an OptionCategory menu via
     *  {@code VideoMenuPresenter}/{@code AppDialogPresenter} and shows it through the Wave 3
     *  {@code MobileAppDialogActivity} renderer. */
    public interface OnVideoLongClickListener {
        boolean onVideoLongClick(Video video);
    }

    private final OnVideoClickListener mClickListener;
    private final OnVideoLongClickListener mLongClickListener;

    public VideoCardAdapter(OnVideoClickListener clickListener) {
        this(clickListener, null);
    }

    public VideoCardAdapter(OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
    }

    private static final DiffUtil.ItemCallback<Video> DIFF_CALLBACK = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video#equals/hashCode are content-based (videoId/playlistId/channelId/...).
            return oldItem.equals(newItem);
        }

        @Override
        @SuppressLint("DiffUtilEquals")
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video.equals() is identity-like (IDs only). A new instance with the same ID can
            // carry refreshed metadata and must be rebound; the same instance needs no rebind.
            return oldItem == newItem;
        }
    };

    @Override
    public int getItemViewType(int position) {
        // isChannel() alone also matches playlists delivered in the playlist-as-channel shape
        // (videoId/playlistId null + a VL... channelId — the standard shape of the user
        // Playlists section): those must stay thumbnail cards, not round-avatar channel rows.
        Video item = getItem(position);
        return item.isChannel() && !item.isPlaylistAsChannel() ? VIEW_TYPE_CHANNEL : VIEW_TYPE_VIDEO;
    }

    /** Full-span rows (channel results) for the grid's SpanSizeLookup in multi-column layouts. */
    public boolean isFullSpan(int position) {
        return position >= 0 && position < getItemCount() && getItemViewType(position) == VIEW_TYPE_CHANNEL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_CHANNEL) {
            return new ChannelViewHolder(inflater.inflate(R.layout.item_mobile_channel_card, parent, false), mClickListener);
        }
        return new VideoViewHolder(inflater.inflate(R.layout.item_video_card, parent, false), mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).bind(getItem(position), mLongClickListener);
        } else {
            ((VideoViewHolder) holder).bind(getItem(position), mLongClickListener);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).unbind();
        } else if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).unbind();
        }
    }

    /**
     * Force a re-bind of the given items in place.
     *
     * Needed for {@code VideoGroup.ACTION_SYNC} (e.g. DeArrow / unlocalized-title overrides):
     * those processors MUTATE the existing {@link Video} instances in place
     * ({@code video.deArrowTitle} / {@code video.altCardImageUrl}) rather than emitting new
     * ones, so {@code submitList()} can't detect the change - {@link #DIFF_CALLBACK}'s
     * {@code areContentsTheSame} sees the same object reference on both sides and skips the
     * bind. We locate each item by content identity ({@link Video#equals}) in the currently
     * displayed list and rebind that row so the crowd-sourced title/thumbnail actually shows.
     */
    public void refreshItems(java.util.List<Video> items) {
        java.util.List<Video> current = getCurrentList();
        for (Video item : items) {
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).equals(item)) {
                    notifyItemChanged(i);
                }
            }
        }
    }

    /**
     * Public for reuse by other screens that render the standard video card (kept public
     * after the channel page's old heterogeneous adapter was replaced by tabs, 2026-07-09).
     */
    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final View mThumbnailFrame;
        private final TextView mBadge;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mMeta;
        private final View mOverflow;
        private Video mVideo;

        public VideoViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mThumbnail = itemView.findViewById(R.id.video_thumbnail);
            mThumbnailFrame = itemView.findViewById(R.id.video_thumbnail_frame);
            mBadge = itemView.findViewById(R.id.video_badge);
            mWatchProgress = itemView.findViewById(R.id.video_watch_progress);
            mTitle = itemView.findViewById(R.id.video_title);
            mMeta = itemView.findViewById(R.id.video_meta);
            mOverflow = itemView.findViewById(R.id.video_overflow);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    if (mVideo.videoId != null) {
                        PlayerTransitionBridge.prepare(mThumbnailFrame);
                    } else {
                        PlayerTransitionBridge.clear();
                    }
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        public void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;
            Context context = itemView.getContext();

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            // ⋮ = the exact long-press context menu, just discoverable (Not interested, Open
            // channel, playlists...). Hidden when the host screen has no menu wired.
            if (mOverflow != null) {
                mOverflow.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);
                mOverflow.setOnClickListener(v -> {
                    if (mVideo != null && longClickListener != null) {
                        longClickListener.onVideoLongClick(mVideo);
                    }
                });
            }

            mTitle.setText(video.getTitle());

            // "Channel · 1.2M views · 3 weeks ago" like YouTube; author-only when the
            // service didn't build the info line (e.g. some playlist rows).
            CharSequence meta = video.getSecondTitle();
            mMeta.setText(meta == null || meta.length() == 0 ? video.getAuthor() : meta);

            bindBadge(context, video);
            bindProgress(video);
            bindThumbnail(context, video);
        }

        private void bindBadge(Context context, Video video) {
            String badgeText;
            if (video.hasNewContent) {
                badgeText = context.getString(R.string.badge_new_content);
            } else if (video.isLive) {
                badgeText = context.getString(R.string.badge_live);
            } else if (video.isShorts) {
                badgeText = context.getString(R.string.header_shorts).toUpperCase();
            } else {
                badgeText = video.badge;
            }

            if (badgeText == null || badgeText.isEmpty()) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badgeText);
                mBadge.setBackgroundColor(ContextCompat.getColor(context,
                        video.isLive || video.isUpcoming ? R.color.mobile_color_badge_live_bg : R.color.mobile_color_badge_bg));
                mBadge.setVisibility(View.VISIBLE);
            }
        }

        private void bindProgress(Video video) {
            // Count progress that's very close to zero (e.g. user closed the video immediately).
            int progress = video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched);

            if (progress > 0 && progress <= 100) {
                mWatchProgress.setProgress(progress);
                mWatchProgress.setVisibility(View.VISIBLE);
            } else {
                mWatchProgress.setVisibility(View.GONE);
            }
        }

        /**
         * SCROLL-JANK FIX: decode thumbnails downsampled to the card's real display size (not the
         * layout-determined size Glide would otherwise wait a measure pass for) and in RGB_565.
         * Thumbnails are opaque JPEGs, so 565 halves both decode work and the GPU texture upload
         * per card - the biggest per-frame cost while fling-scrolling the grid. Sized for the
         * portrait single-column feed (the largest card = full screen width); landscape splits
         * the wider dimension across 2+ columns, so this stays a correct downsample bound there.
         */
        private static int sThumbW;
        private static int sThumbH;

        private static int thumbWidth(Context context) {
            if (sThumbW == 0) {
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                sThumbW = Math.min(dm.widthPixels, dm.heightPixels);
                sThumbH = sThumbW * 9 / 16;
            }
            return sThumbW;
        }

        private void bindThumbnail(Context context, Video video) {
            int thumbQuality = MainUIData.instance(context).getThumbQuality();
            String thumbnailUrl = ClickbaitRemover.updateThumbnail(video, thumbQuality);

            int w = thumbWidth(context);
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request = Glide.with(context)
                    .load(thumbnailUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(w, sThumbH)
                    .centerCrop()
                    // Network loads fade in over the placeholder-colored card instead of popping;
                    // memory-cache hits skip the transition entirely, so scrolling stays crisp.
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150));

            // At the default thumb quality the primary URL IS the card URL, so a fallback request
            // would be identical - only pay the second RequestBuilder when it can actually differ.
            String fallbackUrl = video.getCardImageUrl();
            if (fallbackUrl != null && !fallbackUrl.equals(thumbnailUrl)) {
                request = request.error(Glide.with(context)
                        .load(fallbackUrl)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(w, sThumbH)
                        .centerCrop());
            }

            request.into(mThumbnail);
        }

        public void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }
    }

    /**
     * Channel result row: round avatar (the channel item's cardImageUrl IS the avatar) +
     * name + "subscribers · N videos". Tap routes through the same click listener — the
     * presenters' onVideoItemClicked already opens the mobile channel screen for
     * {@code Video.isChannel()} items; long-press/⋮-less row keeps the context menu too.
     */
    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mAvatar;
        private final TextView mName;
        private final TextView mMeta;
        private Video mVideo;

        ChannelViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mAvatar = itemView.findViewById(R.id.channel_avatar);
            mName = itemView.findViewById(R.id.channel_name);
            mMeta = itemView.findViewById(R.id.channel_meta);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    PlayerTransitionBridge.clear();
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            mName.setText(video.getTitle());

            // secondTitle = subscriber count, badge = "N videos" (see MediaItemImpl mapping).
            CharSequence subs = video.getSecondTitle();
            String videosCount = video.badge;
            StringBuilder meta = new StringBuilder();
            if (subs != null && subs.length() > 0) {
                meta.append(subs);
            }
            if (videosCount != null && !videosCount.isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append(videosCount);
            }
            mMeta.setText(meta);
            mMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

            Glide.with(itemView.getContext())
                    .load(video.getCardImageUrl())
                    .circleCrop()
                    .placeholder(R.drawable.ic_watch_channel_placeholder)
                    .error(R.drawable.ic_watch_channel_placeholder)
                    .into(mAvatar);
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
        }
    }
}
