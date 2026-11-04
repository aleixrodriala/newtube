package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.WindowManager;
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
import com.bumptech.glide.RequestManager;
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
public final class QueueSheet {
    public interface Host {
        void playFromQueue(Video video);

        void onQueueChanged();

        void showQueueSheet(BottomSheetDialog dialog);
    }

    private static final java.util.WeakHashMap<Context, java.lang.ref.WeakReference<BottomSheetDialog>>
            ACTIVE = new java.util.WeakHashMap<>();

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    private static boolean canShow(Context context) {
        Activity activity = activity(context);
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private QueueSheet() {
    }

    @androidx.annotation.MainThread
    public static void show(@NonNull Context context, @NonNull Host host) {
        // Main-thread UI entry point. Weak keys AND values cannot retain a destroyed host.
        if (!canShow(context) || Playlist.instance().getAll().isEmpty()) return;
        java.lang.ref.WeakReference<BottomSheetDialog> existing = ACTIVE.get(context);
        BottomSheetDialog shown = existing != null ? existing.get() : null;
        if (shown != null && shown.isShowing()) return;
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
            private boolean mMoved;
            @Override
            public int getDragDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                return adapter.canMove(holder.getBindingAdapterPosition()) ? super.getDragDirs(recyclerView, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                boolean moved = adapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
                mMoved |= moved;
                return moved;
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
                if (!content.isAttachedToWindow() || adapter.mDisposed) return;
                // The sheet itself drags down to close: hold it still while a row is being dragged.
                dialog.getBehavior().setDraggable(actionState != ItemTouchHelper.ACTION_STATE_DRAG);
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE && mMoved) {
                    mMoved = false;
                    host.onQueueChanged();
                }
            }
        });
        dragger.attachToRecyclerView(list);
        adapter.dragger = dragger;

        clear.setOnClickListener(v -> {
            if (!adapter.hostUsable()) return;
            adapter.clearAllButCurrent();
            host.onQueueChanged();
        });

        // Structural notifications only while the sheet is attached. No playback timer or polling.
        androidx.lifecycle.LifecycleOwner owner = activity(context) instanceof androidx.lifecycle.LifecycleOwner
                ? (androidx.lifecycle.LifecycleOwner) activity(context) : null;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable afterLayout = adapter::requestRefresh;
        Runnable refresh = () -> {
            if (adapter.mDisposed || !content.isAttachedToWindow() || !dialog.isShowing()
                    || !canShow(context) || (owner != null && !owner.getLifecycle().getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))) return;
            if (list.isComputingLayout()) {
                // A one-shot retry after this layout, not a repeating timer.
                list.removeCallbacks(afterLayout);
                list.post(afterLayout);
                return;
            }
            if (!adapter.matchesLiveQueue()) {
                // An external edit during a drag must not apply stale adapter indexes.
                dragger.attachToRecyclerView(null);
                adapter.reload();
                dragger.attachToRecyclerView(list);
            }
        };
        Runnable changed = () -> {
            main.removeCallbacks(refresh);
            main.post(refresh);
        };
        adapter.onStaleSnapshot = changed;
        androidx.lifecycle.DefaultLifecycleObserver observer = new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull androidx.lifecycle.LifecycleOwner hostOwner) {
                if (content.isAttachedToWindow()) {
                    Playlist.instance().addListener(changed);
                    changed.run();
                }
            }
            @Override public void onStop(@NonNull androidx.lifecycle.LifecycleOwner hostOwner) {
                Playlist.instance().removeListener(changed);
                main.removeCallbacks(refresh);
                list.removeCallbacks(afterLayout);
            }
            @Override public void onDestroy(@NonNull androidx.lifecycle.LifecycleOwner hostOwner) {
                dialog.dismiss();
            }
        };
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                if (owner != null) owner.getLifecycle().addObserver(observer);
                else {
                    Playlist.instance().addListener(changed);
                    changed.run();
                }
            }
            @Override public void onViewDetachedFromWindow(View v) {
                if (owner != null) owner.getLifecycle().removeObserver(observer);
                Playlist.instance().removeListener(changed);
                main.removeCallbacks(refresh);
                list.removeCallbacks(afterLayout);
                adapter.dispose();
                dragger.attachToRecyclerView(null);
                list.setAdapter(null);
            }
        });
        adapter.reload();
        int current = adapter.currentIndex();
        if (current > 0) {
            list.scrollToPosition(current);
        }
        ACTIVE.put(context, new java.lang.ref.WeakReference<>(dialog));
        dialog.setOnDismissListener(d -> {
            java.lang.ref.WeakReference<BottomSheetDialog> active = ACTIVE.get(context);
            if (active != null && active.get() == dialog) ACTIVE.remove(context);
        });
        if (!canShow(context)) {
            ACTIVE.remove(context);
            adapter.dispose();
            return;
        }
        try {
            host.showQueueSheet(dialog);
        } catch (WindowManager.BadTokenException e) {
            // The activity can lose its window token between the check and show().
            ACTIVE.remove(context);
            adapter.dispose();
            dialog.dismiss();
            android.util.Log.w("QueueSheet", "Queue host window is no longer available");
        }
    }

    static final class Adapter extends RecyclerView.Adapter<RowHolder> {
        private final Context mContext;
        private final Host mHost;
        private final BottomSheetDialog mDialog;
        private final List<Video> mVideos = new ArrayList<>();
        private final RequestManager mImages;
        private boolean mDisposed;
        private final int mThumbnailWidth;
        private final int mThumbnailHeight;
        private Video mPlaying;
        ItemTouchHelper dragger;
        Runnable onCountChanged;
        Runnable onStaleSnapshot;

        Adapter(Context context, Host host, BottomSheetDialog dialog) {
            mContext = context;
            mHost = host;
            mDialog = dialog;
            mImages = Glide.with(context);
            float density = context.getResources().getDisplayMetrics().density;
            mThumbnailWidth = Math.round(112 * density);
            mThumbnailHeight = Math.round(63 * density);
        }

        void requestRefresh() {
            if (!mDisposed && onStaleSnapshot != null) onStaleSnapshot.run();
        }

        private boolean hostUsable() {
            Activity host = activity(mContext);
            return !mDisposed && (host == null || (!host.isFinishing() && !host.isDestroyed()));
        }

        private boolean readyForEdit() {
            if (!hostUsable()) return false;
            if (!matchesLiveQueue()) {
                requestRefresh();
                return false;
            }
            return true;
        }

        void dispose() {
            mDisposed = true;
            mVideos.clear();
            mPlaying = null;
            onCountChanged = null;
            onStaleSnapshot = null;
            dragger = null;
        }

        void reload() {
            if (mDisposed) return;
            mVideos.clear();
            mVideos.addAll(Playlist.instance().getAll());
            mPlaying = playingItem();
            notifyDataSetChanged();
            onCountChanged.run();
        }

        boolean matchesLiveQueue() {
            List<Video> live = Playlist.instance().getAll();
            if (mPlaying != playingItem() || live.size() != mVideos.size()) return false;
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i) != mVideos.get(i)) return false;
            }
            return true;
        }

        int currentIndex() {
            Video current = playingItem();
            for (int i = 0; i < mVideos.size(); i++) {
                if (mVideos.get(i) == current) {
                    return i;
                }
            }
            return -1;
        }

        private Video playingItem() {
            Playlist queue = Playlist.instance();
            return queue.isRestoredQueue() ? null : queue.getCurrent();
        }

        boolean canMove(int position) {
            return !mDisposed && position >= 0 && position < mVideos.size()
                    && mVideos.get(position) != null;
        }

        boolean canRemove(int position) {
            return canMove(position) && position != currentIndex();
        }

        boolean move(int from, int to) {
            if (!readyForEdit()) return false;
            if (!canMove(from)) return false;
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
            if (!readyForEdit()) return;
            if (!canRemove(position)) {
                return;
            }
            Video video = mVideos.get(position);
            if (!Playlist.instance().removeQueuedItem(video)) {
                requestRefresh();
                return;
            }
            mVideos.remove(position);
            video.fromQueue = false;
            notifyItemRemoved(position);
            onCountChanged.run();
        }

        void clearAllButCurrent() {
            if (!hostUsable()) return;
            Playlist.instance().clearExceptCurrent();
            reload();
        }

        boolean play(int position) {
            if (!readyForEdit() || !canRemove(position)) return false;
            Video picked = mVideos.get(position);
            picked.fromQueue = true;
            // Capture before dismiss: detach disposes the adapter's snapshot.
            mDialog.dismiss();
            mHost.playFromQueue(picked);
            return true;
        }

        @Override
        public void onViewRecycled(@NonNull RowHolder holder) {
            clearRow(holder);
            super.onViewRecycled(holder);
        }

        @Override
        public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
            // Dismissing a dialog need not recycle its attached holders.
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
                if (holder instanceof RowHolder) clearRow((RowHolder) holder);
            }
            super.onDetachedFromRecyclerView(recyclerView);
        }

        private void clearRow(RowHolder holder) {
            mImages.clear(holder.thumbnail);
            holder.thumbnail.setImageDrawable(null);
            holder.handle.setOnTouchListener(null);
            holder.remove.setOnClickListener(null);
            holder.itemView.setOnClickListener(null);
            for (int id : holder.actionIds) ViewCompat.removeAccessibilityAction(holder.itemView, id);
            holder.actionIds.clear();
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
            if (video == null) {
                // Keep index alignment with the live store even if a partial response left a hole.
                clearRow(holder);
                holder.title.setText(R.string.mobile_queue_untitled_video);
                holder.channel.setVisibility(View.GONE);
                holder.badge.setVisibility(View.GONE);
                holder.handle.setVisibility(View.INVISIBLE);
                holder.remove.setVisibility(View.INVISIBLE);
                ViewCompat.setStateDescription(holder.itemView, null);
                return;
            }
            boolean playing = position == currentIndex();

            String title = video.getTitle();
            holder.title.setText(android.text.TextUtils.isEmpty(title)
                    ? mContext.getString(R.string.mobile_queue_untitled_video) : title);
            holder.channel.setText(video.getAuthor());
            holder.channel.setVisibility(video.getAuthor() != null ? View.VISIBLE : View.GONE);

            String badge = playing ? mContext.getString(R.string.mobile_watch_queue_now_playing) : video.badge;
            holder.badge.setText(badge);
            holder.badge.setVisibility(badge != null && !badge.isEmpty() ? View.VISIBLE : View.GONE);

            mImages.load(video.getCardImageUrl())
                    .placeholder(R.color.mobile_color_placeholder)
                    .error(R.color.mobile_color_placeholder)
                    .fallback(R.color.mobile_color_placeholder)
                    .override(mThumbnailWidth, mThumbnailHeight)
                    .dontAnimate()
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .into(holder.thumbnail);

            holder.handle.setVisibility(View.VISIBLE);
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
            holder.itemView.setOnClickListener(v -> play(holder.getBindingAdapterPosition()));

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
            if (playing) return; // current video can move, but must not be removed or restarted
            holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.remove_from_playback_queue), (view, args) -> {
                if (!readyForEdit()) return false;
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

    static final class RowHolder extends RecyclerView.ViewHolder {
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
