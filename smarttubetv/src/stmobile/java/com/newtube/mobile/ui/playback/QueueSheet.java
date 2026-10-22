package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(queue): the playback queue as a sheet, in place of the TV's text list: thumbnails, a drag
 * handle to reorder, a remove button, Clear, and a tap to play. TalkBack gets Move up / Move down /
 * Remove as actions on each row, since dragging needs a pointer.
 *
 * <p>Edits go straight to {@link Playlist} (the queue's one store); {@link Host#onQueueChanged} lets
 * the watch page bring its own "Playback queue" list in line.</p>
 */
final class QueueSheet {
    interface Host {
        void playFromQueue(Video video);

        void onQueueChanged();

        void showQueueSheet(BottomSheetDialog dialog);
    }

    private QueueSheet() {
    }

    static void show(@NonNull Context context, @NonNull Host host) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.sheet_mobile_queue, null);
        dialog.setContentView(content);

        TextView count = content.findViewById(R.id.queue_sheet_count);
        RecyclerView list = content.findViewById(R.id.queue_sheet_list);
        View clear = content.findViewById(R.id.queue_sheet_clear);

        Adapter adapter = new Adapter(context, host, dialog);
        adapter.onCountChanged = () -> {
            int total = adapter.mVideos.size();
            int current = adapter.currentIndex();
            count.setText(current >= 0
                    ? context.getString(R.string.mobile_queue_count_position, current + 1, total)
                    : context.getResources().getQuantityString(R.plurals.mobile_queue_count, total, total));
            clear.setVisibility(total > (current >= 0 ? 1 : 0) ? View.VISIBLE : View.GONE);
            if (total == 0) {
                dialog.dismiss();
            }
        };
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        ItemTouchHelper dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getDragDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                return adapter.canMove(holder.getBindingAdapterPosition()) ? super.getDragDirs(recyclerView, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return adapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                // No swipe: removing is the row's own button, so a sideways scroll can't lose a video.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // the handle starts a drag; a long press stays free for TalkBack
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                super.onSelectedChanged(holder, actionState);
                // The sheet itself drags down to close: hold it still while a row is being dragged.
                dialog.getBehavior().setDraggable(actionState != ItemTouchHelper.ACTION_STATE_DRAG);
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    host.onQueueChanged();
                }
            }
        });
        dragger.attachToRecyclerView(list);
        adapter.dragger = dragger;

        clear.setOnClickListener(v -> {
            adapter.clearAllButCurrent();
            host.onQueueChanged();
        });

        adapter.reload();
        int current = adapter.currentIndex();
        if (current > 0) {
            list.scrollToPosition(current);
        }
        host.showQueueSheet(dialog);
    }

    private static final class Adapter extends RecyclerView.Adapter<RowHolder> {
        private final Context mContext;
        private final Host mHost;
        private final BottomSheetDialog mDialog;
        private final List<Video> mVideos = new ArrayList<>();
        ItemTouchHelper dragger;
        Runnable onCountChanged;

        Adapter(Context context, Host host, BottomSheetDialog dialog) {
            mContext = context;
            mHost = host;
            mDialog = dialog;
        }

        void reload() {
            mVideos.clear();
            mVideos.addAll(Playlist.instance().getAll());
            notifyDataSetChanged();
            onCountChanged.run();
        }

        int currentIndex() {
            Video current = Playlist.instance().getCurrent();
            for (int i = 0; i < mVideos.size(); i++) {
                if (mVideos.get(i) == current) {
                    return i;
                }
            }
            return -1;
        }

        boolean canMove(int position) {
            return position >= 0 && position < mVideos.size() && position != currentIndex();
        }

        boolean move(int from, int to) {
            if (from < 0 || to < 0 || from >= mVideos.size() || to >= mVideos.size()) {
                return false;
            }
            Playlist.instance().move(from, to);
            mVideos.add(to, mVideos.remove(from));
            notifyItemMoved(from, to);
            onCountChanged.run(); // the playing one's number may have changed
            return true;
        }

        void remove(int position) {
            if (!canMove(position)) {
                return;
            }
            Video video = mVideos.remove(position);
            video.fromQueue = false;
            Playlist.instance().remove(video);
            notifyItemRemoved(position);
            onCountChanged.run();
        }

        void clearAllButCurrent() {
            Video current = Playlist.instance().getCurrent();
            for (Video video : new ArrayList<>(mVideos)) {
                if (video != current) {
                    video.fromQueue = false;
                    Playlist.instance().remove(video);
                }
            }
            reload();
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_mobile_queue_row, parent, false));
        }

        @SuppressLint("ClickableViewAccessibility") // the handle only starts the drag; the row's click stays
        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Video video = mVideos.get(position);
            boolean playing = position == currentIndex();

            holder.title.setText(video.getTitle());
            holder.channel.setText(video.getAuthor());
            holder.channel.setVisibility(video.getAuthor() != null ? View.VISIBLE : View.GONE);

            String badge = playing ? mContext.getString(R.string.mobile_watch_queue_now_playing) : video.badge;
            holder.badge.setText(badge);
            holder.badge.setVisibility(badge != null && !badge.isEmpty() ? View.VISIBLE : View.GONE);

            Glide.with(holder.thumbnail)
                    .load(video.getCardImageUrl())
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .into(holder.thumbnail);

            holder.handle.setVisibility(playing ? View.INVISIBLE : View.VISIBLE);
            holder.remove.setVisibility(playing ? View.INVISIBLE : View.VISIBLE);
            holder.handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && dragger != null) {
                    dragger.startDrag(holder);
                }
                return false;
            });
            holder.remove.setOnClickListener(v -> {
                remove(holder.getBindingAdapterPosition());
                mHost.onQueueChanged();
            });
            holder.itemView.setOnClickListener(v -> {
                int at = holder.getBindingAdapterPosition();
                if (at >= 0 && at < mVideos.size() && at != currentIndex()) {
                    Video picked = mVideos.get(at);
                    picked.fromQueue = true;
                    mDialog.dismiss();
                    mHost.playFromQueue(picked);
                }
            });

            bindAccessibility(holder, playing);
        }

        /** Dragging needs a pointer: TalkBack and switch users get Move up / Move down / Remove. */
        private void bindAccessibility(@NonNull RowHolder holder, boolean playing) {
            View row = holder.itemView;
            for (int id : holder.actionIds) {
                ViewCompat.removeAccessibilityAction(row, id);
            }
            holder.actionIds.clear();
            ViewCompat.setStateDescription(row, playing ? mContext.getString(R.string.mobile_watch_queue_now_playing) : null);
            if (playing) {
                return;
            }
            holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.mobile_queue_move_up), (view, args) -> {
                int at = holder.getBindingAdapterPosition();
                boolean moved = at > 0 && move(at, at - 1);
                if (moved) {
                    mHost.onQueueChanged();
                    notifyItemChanged(at - 1);
                }
                return moved;
            }));
            holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.mobile_queue_move_down), (view, args) -> {
                int at = holder.getBindingAdapterPosition();
                boolean moved = at >= 0 && at < mVideos.size() - 1 && move(at, at + 1);
                if (moved) {
                    mHost.onQueueChanged();
                    notifyItemChanged(at + 1);
                }
                return moved;
            }));
            holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.remove_from_playback_queue), (view, args) -> {
                remove(holder.getBindingAdapterPosition());
                mHost.onQueueChanged();
                return true;
            }));
        }

        @Override
        public int getItemCount() {
            return mVideos.size();
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ImageView handle;
        final ImageView thumbnail;
        final TextView badge;
        final TextView title;
        final TextView channel;
        final ImageButton remove;
        final List<Integer> actionIds = new ArrayList<>();

        RowHolder(@NonNull View itemView) {
            super(itemView);
            handle = itemView.findViewById(R.id.queue_row_handle);
            thumbnail = itemView.findViewById(R.id.queue_row_thumbnail);
            badge = itemView.findViewById(R.id.queue_row_badge);
            title = itemView.findViewById(R.id.queue_row_title);
            channel = itemView.findViewById(R.id.queue_row_channel);
            remove = itemView.findViewById(R.id.queue_row_remove);
        }
    }
}
