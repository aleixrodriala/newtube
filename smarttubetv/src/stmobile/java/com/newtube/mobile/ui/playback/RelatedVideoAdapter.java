package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Compact single-column "up next / related" list adapter for the watch page
 * ({@link MobilePlaybackActivity}). Mirrors {@code VideoCardAdapter}'s thumbnail/badge/progress
 * binding (Glide + {@code ClickbaitRemover} + {@code MainUIData} thumb quality) but with a
 * small horizontal row layout ({@code item_mobile_related_video}) instead of a full-width card.
 *
 * <p>Tapping a row routes the tapped {@link Video} to
 * {@code PlaybackPresenter.onSuggestionItemClicked(Video)} (via the click listener) which both
 * marks queue state and loads/plays the video in the same player.</p>
 */
public class RelatedVideoAdapter extends ListAdapter<Video, RelatedVideoAdapter.RelatedViewHolder> {

    public interface OnRelatedClickListener {
        void onRelatedClick(Video video);
    }

    private final OnRelatedClickListener mClickListener;
    /**
     * Video id to mark as "Now playing", or null for none. Only the queue list sets this - in the
     * Up-next list the playing video isn't present at all.
     */
    private String mCurrentVideoId;

    public RelatedVideoAdapter(OnRelatedClickListener clickListener) {
        super(DIFF_CALLBACK);
        mClickListener = clickListener;
    }

    /**
     * Marks one row as the playing one. Rebinds every row rather than diffing: the flag lives
     * outside the {@link Video} objects, so {@link #DIFF_CALLBACK} (identity-based by design)
     * cannot see it change, and both the OLD and the NEW current row need repainting.
     */
    public void setCurrentVideoId(String videoId) {
        if (android.text.TextUtils.equals(mCurrentVideoId, videoId)) {
            return;
        }

        mCurrentVideoId = videoId;
        notifyDataSetChanged();
    }

    private static final DiffUtil.ItemCallback<Video> DIFF_CALLBACK = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
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

    @NonNull
    @Override
    public RelatedViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_related_video, parent, false);
        return new RelatedViewHolder(view, mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RelatedViewHolder holder, int position) {
        Video video = getItem(position);
        boolean isCurrent = mCurrentVideoId != null && video != null
                && mCurrentVideoId.equals(video.videoId);
        holder.bind(video, isCurrent);
    }

    @Override
    public void onViewRecycled(@NonNull RelatedViewHolder holder) {
        super.onViewRecycled(holder);
        holder.unbind();
    }

    static class RelatedViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final TextView mBadge;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private Video mVideo;

        RelatedViewHolder(@NonNull View itemView, OnRelatedClickListener clickListener) {
            super(itemView);

            mThumbnail = itemView.findViewById(R.id.related_thumbnail);
            mBadge = itemView.findViewById(R.id.related_badge);
            mWatchProgress = itemView.findViewById(R.id.related_watch_progress);
            mTitle = itemView.findViewById(R.id.related_title);
            mSubtitle = itemView.findViewById(R.id.related_subtitle);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    clickListener.onRelatedClick(mVideo);
                }
            });
        }

        void bind(Video video, boolean isCurrent) {
            mVideo = video;
            Context context = itemView.getContext();

            mTitle.setText(video.getTitle());

            // Single subtitle line = "Channel • views • date" (Video.getSecondTitle()). Previously the
            // channel showed twice - once as its own byline (getAuthor(), which is just the channel token
            // extracted from the very same secondTitle) and again at the head of this metadata line, so
            // the two lines were identical whenever views/date were absent. Fall back to the bare channel
            // name when no secondTitle is available.
            CharSequence subtitle = video.getSecondTitle();
            if (subtitle == null || subtitle.length() == 0) {
                subtitle = video.getAuthor();
            }
            if (subtitle != null && subtitle.length() > 0) {
                mSubtitle.setText(subtitle);
                mSubtitle.setVisibility(View.VISIBLE);
            } else {
                mSubtitle.setVisibility(View.GONE);
            }

            bindBadge(video, isCurrent);
            bindProgress(video);
            bindThumbnail(context, video);
        }

        private void bindBadge(Video video, boolean isCurrent) {
            // The playing row takes over the badge slot: in a queue the duration matters far less
            // than knowing where you are, and this needs no extra view in the row layout.
            if (isCurrent) {
                mBadge.setText(R.string.mobile_watch_queue_now_playing);
                mBadge.setVisibility(View.VISIBLE);
                return;
            }

            String badgeText;
            if (video.isLive) {
                badgeText = itemView.getContext().getString(R.string.badge_live);
            } else {
                badgeText = video.badge;
            }

            if (badgeText == null || badgeText.isEmpty()) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badgeText);
                mBadge.setVisibility(View.VISIBLE);
            }
        }

        private void bindProgress(Video video) {
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

            // NEWTUBE(net): downsampling happens on the DEVICE, so a 640px sddefault still crosses the
            // wire in full before Glide throws most of it away. Ask the CDN for the row's actual size
            // instead: 12 of these bind at once and the difference is ~1.3 MB vs ~300 KB per open.
            thumbnailUrl = ClickbaitRemover.fitThumbnail(thumbnailUrl, relatedThumbWidthPx(context));

            // SCROLL-JANK FIX: opaque JPEG thumbs -> RGB_565 halves decode + GPU upload. The thumb view
            // is fixed-dp-sized, so Glide already downsamples to it (no override needed). Only build the
            // fallback request when its URL can actually differ from the primary (never at default quality).
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request = Glide.with(context)
                    .load(thumbnailUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    // Fade network loads in over the placeholder; cache hits skip the transition.
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150));

            String fallbackUrl = video.getCardImageUrl();
            if (fallbackUrl != null && !fallbackUrl.equals(thumbnailUrl)) {
                request = request.error(Glide.with(context)
                        .load(fallbackUrl)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop());
            }

            request.into(mThumbnail);
        }

        /** Row thumb width in real pixels, so the CDN rendition is chosen for THIS screen's density. */
        private int relatedThumbWidthPx(Context context) {
            return context.getResources().getDimensionPixelSize(
                    R.dimen.mobile_watch_related_thumb_width);
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }
    }
}
