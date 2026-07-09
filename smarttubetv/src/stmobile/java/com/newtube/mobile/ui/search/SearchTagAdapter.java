package com.newtube.mobile.ui.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical list of search suggestion/history rows (replaces the old horizontal chip strip).
 *
 * <p>Fed live as the user types by {@code MobileSearchActivity}'s text watcher, which asks
 * the {@code SearchPresenter}-supplied {@code MediaServiceSearchTagProvider} for tags. The
 * suggest endpoint returns the user's search history for an EMPTY query and live suggestions
 * for a typed one — {@link #setHistoryMode} switches the row icon (clock vs magnifier)
 * accordingly, since the {@link Tag} model itself carries no origin flag.</p>
 *
 * <p>Row interactions: tap = run that search; trailing NW arrow = put the text into the
 * field without submitting (query refinement); long-press = {@code
 * SearchPresenter.onTagLongClicked} (remove-entry / clear-history dialog).</p>
 */
public class SearchTagAdapter extends RecyclerView.Adapter<SearchTagAdapter.TagViewHolder> {
    public interface OnTagClickListener {
        void onTagClick(Tag tag);
    }

    public interface OnTagLongClickListener {
        boolean onTagLongClick(Tag tag);
    }

    /** The NW insert arrow: drop the text into the search field, don't submit. */
    public interface OnTagInsertListener {
        void onTagInsert(Tag tag);
    }

    private final List<Tag> mTags = new ArrayList<>();
    private final OnTagClickListener mClickListener;
    private final OnTagLongClickListener mLongClickListener;
    private final OnTagInsertListener mInsertListener;
    private boolean mHistoryMode;

    public SearchTagAdapter(OnTagClickListener clickListener, OnTagLongClickListener longClickListener,
            OnTagInsertListener insertListener) {
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
        mInsertListener = insertListener;
    }

    /** History mode = rows came from an empty-query lookup (the user's past searches). */
    public void setHistoryMode(boolean historyMode) {
        if (mHistoryMode != historyMode) {
            mHistoryMode = historyMode;
            notifyDataSetChanged();
        }
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
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_search_suggestion, parent, false);
        return new TagViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
        holder.bind(mTags.get(position), mHistoryMode, mClickListener, mLongClickListener, mInsertListener);
    }

    @Override
    public int getItemCount() {
        return mTags.size();
    }

    static class TagViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mIcon;
        private final TextView mText;
        private final View mInsert;

        TagViewHolder(@NonNull View itemView) {
            super(itemView);
            mIcon = itemView.findViewById(R.id.suggestion_icon);
            mText = itemView.findViewById(R.id.suggestion_text);
            mInsert = itemView.findViewById(R.id.suggestion_insert);
        }

        void bind(Tag tag, boolean historyMode, OnTagClickListener clickListener,
                OnTagLongClickListener longClickListener, OnTagInsertListener insertListener) {
            mIcon.setImageResource(historyMode ? R.drawable.ic_mobile_history : R.drawable.ic_mobile_search);
            mText.setText(tag.tag);

            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onTagClick(tag);
                }
            });
            itemView.setOnLongClickListener(v ->
                    longClickListener != null && longClickListener.onTagLongClick(tag));
            mInsert.setOnClickListener(v -> {
                if (insertListener != null) {
                    insertListener.onTagInsert(tag);
                }
            });
        }
    }
}
