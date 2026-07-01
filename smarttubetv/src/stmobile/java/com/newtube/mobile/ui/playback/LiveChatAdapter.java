package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple append-only adapter for {@link LiveChatSheet}: an avatar plus a single line of
 * "<b>author</b> message". New messages are added at the end via {@link #addItem(ChatItem)} so the
 * sheet can auto-scroll to the newest.
 */
public class LiveChatAdapter extends RecyclerView.Adapter<LiveChatAdapter.VH> {

    private final List<ChatItem> mItems = new ArrayList<>();

    public void setItems(List<ChatItem> items) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    /** Append a message; returns the new item count (so the caller can scroll to the last row). */
    public int addItem(ChatItem item) {
        if (item == null) {
            return mItems.size();
        }
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
        return mItems.size();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mobile_chat, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(mItems.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.recycle();
    }

    static class VH extends RecyclerView.ViewHolder {
        private final ImageView mAvatar;
        private final TextView mMessage;

        VH(@NonNull View itemView) {
            super(itemView);
            mAvatar = itemView.findViewById(R.id.chat_avatar);
            mMessage = itemView.findViewById(R.id.chat_message);
        }

        void bind(ChatItem item) {
            Context context = itemView.getContext();

            String author = item.getAuthorName() != null ? item.getAuthorName() : "";
            String message = item.getMessage() != null ? item.getMessage() : "";

            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (!author.isEmpty()) {
                sb.append(author);
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, author.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ");
            }
            sb.append(message);
            mMessage.setText(sb);

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

        void recycle() {
            Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
        }
    }
}
