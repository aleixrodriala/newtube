package com.newtube.mobile.ui.channel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(page-load-errors): the grid's last row when a NEXT page failed - "Couldn't load more"
 * and Try again, concatenated after the cards. It holds ZERO items otherwise, so a healthy grid
 * never sees it.
 *
 * <p>An explicit button rather than retrying on the next scroll: a failed page answers fast when
 * the phone is offline, and a retry wired to scroll events would fire once per frame of a fling.
 * The pages suspend their scroll-end pagination while this row is showing.</p>
 */
final class LoadMoreFailureAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final Runnable mOnRetry;
    private boolean mFailed;

    LoadMoreFailureAdapter(Runnable onRetry) {
        mOnRetry = onRetry;
    }

    void setFailed(boolean failed) {
        if (mFailed == failed) {
            return;
        }

        mFailed = failed;

        if (failed) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    boolean isFailed() {
        return mFailed;
    }

    @Override
    public int getItemCount() {
        return mFailed ? 1 : 0;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_load_more_failure, parent, false);
        view.findViewById(R.id.mobile_load_more_failure_action).setOnClickListener(v -> mOnRetry.run());

        return new RecyclerView.ViewHolder(view) { };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        // Static content.
    }
}
