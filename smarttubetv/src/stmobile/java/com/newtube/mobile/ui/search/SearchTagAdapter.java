package com.newtube.mobile.ui.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal strip of search-suggestion chips (tags).
 *
 * <p>Fed live as the user types by {@code MobileSearchActivity}'s text watcher, which asks
 * the {@code SearchPresenter}-supplied {@code MediaServiceSearchTagProvider} for tags. Tap a
 * chip -> run that search; long-press a chip -> {@code SearchPresenter.onTagLongClicked}
 * (remove-from / clear search history dialog). This is the touch analogue of the Leanback
 * search "tags" row.</p>
 */
public class SearchTagAdapter extends RecyclerView.Adapter<SearchTagAdapter.TagViewHolder> {
    public interface OnTagClickListener {
        void onTagClick(Tag tag);
    }

    public interface OnTagLongClickListener {
        boolean onTagLongClick(Tag tag);
    }

    private final List<Tag> mTags = new ArrayList<>();
    private final OnTagClickListener mClickListener;
    private final OnTagLongClickListener mLongClickListener;

    public SearchTagAdapter(OnTagClickListener clickListener, OnTagLongClickListener longClickListener) {
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
    }

    public void setTags(List<Tag> tags) {
        mTags.clear();
        if (tags != null) {
            mTags.addAll(tags);
        }
        notifyDataSetChanged();
    }

    public void clearTags() {
        mTags.clear();
        notifyDataSetChanged();
    }

    public void removeTag(Tag tag) {
        int index = mTags.indexOf(tag);
        if (index >= 0) {
            mTags.remove(index);
            notifyItemRemoved(index);
        }
    }

    public boolean isEmpty() {
        return mTags.isEmpty();
    }

    @NonNull
    @Override
    public TagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_search_tag, parent, false);
        return new TagViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
        holder.bind(mTags.get(position), mClickListener, mLongClickListener);
    }

    @Override
    public int getItemCount() {
        return mTags.size();
    }

    static class TagViewHolder extends RecyclerView.ViewHolder {
        private final TextView mText;

        TagViewHolder(@NonNull View itemView) {
            super(itemView);
            mText = (TextView) itemView;
        }

        void bind(Tag tag, OnTagClickListener clickListener, OnTagLongClickListener longClickListener) {
            mText.setText(tag.tag);
            mText.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onTagClick(tag);
                }
            });
            mText.setOnLongClickListener(v ->
                    longClickListener != null && longClickListener.onTagLongClick(tag));
        }
    }
}
