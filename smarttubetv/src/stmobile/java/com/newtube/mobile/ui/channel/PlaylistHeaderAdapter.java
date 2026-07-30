package com.newtube.mobile.ui.channel;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The playlist page header, as a one-item adapter concatenated in front of the video grid.
 *
 * <p>Everything it shows comes from the card that opened the screen - cover, name, owner,
 * "N videos" - so it needs no extra request and is complete before the first item lands. It
 * holds ZERO items until {@link #setPlaylist} is given a playlist, which is how channel
 * uploads (no playlist behind them) end up with no header at all.</p>
 */
class PlaylistHeaderAdapter extends RecyclerView.Adapter<PlaylistHeaderAdapter.HeaderHolder> {
    interface Callbacks {
        void onPlayAll();
        void onShuffle();
    }

    private final Callbacks mCallbacks;
    private Video mPlaylist;
    private boolean mActionsEnabled;

    PlaylistHeaderAdapter(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    /** @param playlist the opener card, or {@code null} for "this destination has no header". */
    void setPlaylist(Video playlist) {
        boolean had = mPlaylist != null;
        mPlaylist = playlist;

        if (had && playlist == null) {
            notifyItemRemoved(0);
        } else if (!had && playlist != null) {
            notifyItemInserted(0);
        } else if (playlist != null) {
            notifyItemChanged(0);
        }
    }

    /** Play all / Shuffle stay disabled until the grid actually holds something playable. */
    void setActionsEnabled(boolean enabled) {
        if (mActionsEnabled == enabled) {
            return;
        }

        mActionsEnabled = enabled;

        if (mPlaylist != null) {
            notifyItemChanged(0);
        }
    }

    boolean hasHeader() {
        return mPlaylist != null;
    }

    @Override
    public int getItemCount() {
        return mPlaylist != null ? 1 : 0;
    }

    @NonNull
    @Override
    public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_playlist_header, parent, false);

        return new HeaderHolder(view, mCallbacks);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
        holder.bind(mPlaylist, mActionsEnabled);
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        private final ImageView mCover;
        private final ImageView mBackdrop;
        private final TextView mTitle;
        private final TextView mOwner;
        private final TextView mMeta;
        private final MaterialButton mPlayAll;
        private final MaterialButton mShuffle;

        HeaderHolder(@NonNull View itemView, Callbacks callbacks) {
            super(itemView);

            mCover = itemView.findViewById(R.id.mobile_channel_uploads_cover);
            mBackdrop = itemView.findViewById(R.id.mobile_channel_uploads_backdrop);
            mTitle = itemView.findViewById(R.id.mobile_channel_uploads_header_title);
            mOwner = itemView.findViewById(R.id.mobile_channel_uploads_header_owner);
            mMeta = itemView.findViewById(R.id.mobile_channel_uploads_header_meta);
            mPlayAll = itemView.findViewById(R.id.mobile_channel_uploads_header_play_all);
            mShuffle = itemView.findViewById(R.id.mobile_channel_uploads_shuffle);

            mPlayAll.setOnClickListener(v -> callbacks.onPlayAll());
            mShuffle.setOnClickListener(v -> callbacks.onShuffle());
        }

        void bind(Video playlist, boolean actionsEnabled) {
            if (playlist == null) {
                return;
            }

            mTitle.setText(playlist.getTitle());

            CharSequence owner = playlist.getAuthor();
            mOwner.setText(owner);
            mOwner.setVisibility(TextUtils.isEmpty(owner) ? View.GONE : View.VISIBLE);

            // "30 videos" (badge) + "Private - Updated today" (second title): the same two facts
            // YouTube stacks under a playlist name, in the same order. The second title is only
            // added when it isn't already standing in as the owner line.
            CharSequence meta = joinMeta(playlist.badge, owner == null ? playlist.getSecondTitle() : null);
            mMeta.setText(meta);
            mMeta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);

            mPlayAll.setEnabled(actionsEnabled);
            mShuffle.setEnabled(actionsEnabled);

            String coverUrl = playlist.getCardImageUrl();
            Glide.with(itemView.getContext().getApplicationContext()).load(coverUrl).centerCrop().into(mCover);
            Glide.with(itemView.getContext().getApplicationContext()).load(coverUrl).centerCrop().into(mBackdrop);
        }

        private CharSequence joinMeta(CharSequence first, CharSequence second) {
            if (TextUtils.isEmpty(first)) {
                return second;
            }
            if (TextUtils.isEmpty(second)) {
                return first;
            }

            return TextUtils.concat(first, " • ", second);
        }
    }
}
