package com.newtube.mobile.ui.channel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.browse.VideoCardAdapter.OnVideoClickListener;
import com.newtube.mobile.ui.browse.VideoCardAdapter.OnVideoLongClickListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Heterogeneous adapter for the LEAN touch channel page ({@link MobileChannelActivity}):
 * a single flat list interleaving full-span section headers ({@link String}) with the
 * section's videos ({@link Video}), so a {@link androidx.recyclerview.widget.GridLayoutManager}
 * renders each section as a grid under its header (no nested horizontal carousels).
 *
 * <p>Video rows reuse the Home grid's exact card layout + binding by delegating to
 * {@link VideoCardAdapter.VideoViewHolder} (same {@code item_video_card}, same
 * thumbnail/badge/progress logic), keeping the two screens visually identical.</p>
 *
 * <p>The owning Activity supplies the span-aware {@code SpanSizeLookup} using
 * {@link #isHeader(int)} (headers span the full width; videos span one cell).</p>
 */
public class ChannelSectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int TYPE_HEADER = 0;
    static final int TYPE_VIDEO = 1;

    /** Each entry is either a {@link String} (section header title) or a {@link Video}. */
    private final List<Object> mItems = new ArrayList<>();
    private final OnVideoClickListener mClickListener;
    private final OnVideoLongClickListener mLongClickListener;

    public ChannelSectionAdapter(OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
    }

    public void submit(List<Object> items) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    public boolean isHeader(int position) {
        return position >= 0 && position < mItems.size() && mItems.get(position) instanceof String;
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position) instanceof String ? TYPE_HEADER : TYPE_VIDEO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_mobile_section_header, parent, false);
            return new HeaderViewHolder(view);
        }

        View view = inflater.inflate(R.layout.item_video_card, parent, false);
        return new VideoCardAdapter.VideoViewHolder(view, mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = mItems.get(position);

        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((String) item);
        } else if (holder instanceof VideoCardAdapter.VideoViewHolder) {
            ((VideoCardAdapter.VideoViewHolder) holder).bind((Video) item, mLongClickListener);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);

        if (holder instanceof VideoCardAdapter.VideoViewHolder) {
            ((VideoCardAdapter.VideoViewHolder) holder).unbind();
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitle;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.mobile_section_header_title);
        }

        void bind(String title) {
            mTitle.setText(title);
        }
    }
}
