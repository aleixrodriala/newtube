package com.newtube.mobile.ui.browse;

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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
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
public class VideoCardAdapter extends ListAdapter<Video, VideoCardAdapter.VideoViewHolder> {
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
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            return oldItem == newItem;
        }
    };

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_card, parent, false);
        return new VideoViewHolder(view, mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        holder.bind(getItem(position), mLongClickListener);
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        holder.unbind();
    }

    /**
     * Public so the channel page's heterogeneous adapter
     * ({@code com.newtube.mobile.ui.channel.ChannelSectionAdapter}) can reuse the exact same
     * card binding for its video rows (headers are a separate view type there), instead of
     * duplicating the thumbnail/badge/progress binding logic. Inflate {@link R#layout} {@code
     * item_video_card} and construct one of these for each video row.
     */
    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final TextView mBadge;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mChannel;
        private Video mVideo;

        public VideoViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mThumbnail = itemView.findViewById(R.id.video_thumbnail);
            mBadge = itemView.findViewById(R.id.video_badge);
            mWatchProgress = itemView.findViewById(R.id.video_watch_progress);
            mTitle = itemView.findViewById(R.id.video_title);
            mChannel = itemView.findViewById(R.id.video_channel);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        public void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;
            Context context = itemView.getContext();

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            mTitle.setText(video.getTitle());
            mChannel.setText(video.getAuthor());

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

        private void bindThumbnail(Context context, Video video) {
            int thumbQuality = MainUIData.instance(context).getThumbQuality();
            String thumbnailUrl = ClickbaitRemover.updateThumbnail(video, thumbQuality);

            Glide.with(context)
                    .load(thumbnailUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .error(Glide.with(context).load(video.getCardImageUrl()).centerCrop())
                    .into(mThumbnail);
        }

        public void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }
    }
}
