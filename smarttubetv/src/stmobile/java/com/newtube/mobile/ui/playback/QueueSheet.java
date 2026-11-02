package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
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
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(queue): the playback queue as a sheet, in place of the TV's text list: the playing video,
 * then what comes after it, with thumbnails, a drag handle to reorder, a remove button, Clear (with
 * Undo), and a tap to play. TalkBack gets Move up / Move down / Remove as actions on each row, since
 * dragging needs a pointer.
 *
 * <p>Edits go straight to {@link Playlist} (the queue's one store); {@link Host#onQueueChanged} lets
 * the watch page bring its own "Playback queue" list in line. Playlist is the session's history too
 * (every open lands in it, and Previous walks it), so only what comes after the playing video is the
 * queue; the sheet leaves the rest alone.</p>
 *
 * <p>Playlist also changes while the sheet is open (autoplay, the notification's Next, a new open
 * trimming its front), so every edit first checks that the rows still are Playlist's own, by
 * identity, and reloads instead of acting on a stale row; and the watch page calls
 * {@link #onPlaylistChanged} when its video changes.</p>
 */
final class QueueSheet {
    interface Host {
        void playFromQueue(Video video);

        void onQueueChanged();

        void showQueueSheet(BottomSheetDialog dialog);
    }

    /** Rebinds only a row's TalkBack actions: same ViewHolder, so TalkBack keeps its place. */
    private static final Object PAYLOAD_ACTIONS = new Object();
    /** A video with no title at all: what the TV's queue list showed. */
    private static final String NO_TITLE = "...";

    private final Context mContext;
    private final Host mHost;
    private final BottomSheetDialog mDialog;
    private final RecyclerView mList;
    private final TextView mCount;
    private final View mClear;
    private final Adapter mAdapter = new Adapter();

    private QueueSheet(@NonNull Context context, @NonNull Host host) {
        mContext = context;
        mHost = host;
        mDialog = new BottomSheetDialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.sheet_mobile_queue, null);
        mDialog.setContentView(content);

        mCount = content.findViewById(R.id.queue_sheet_count);
        mList = content.findViewById(R.id.queue_sheet_list);
        mClear = content.findViewById(R.id.queue_sheet_clear);

        mList.setLayoutManager(new LinearLayoutManager(context));
        mList.setAdapter(mAdapter);

        ItemTouchHelper dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getDragDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                return mAdapter.canMove(holder.getBindingAdapterPosition()) ? super.getDragDirs(recyclerView, holder) : 0;
            }

            @Override
            public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current,
                                       @NonNull RecyclerView.ViewHolder target) {
                return mAdapter.canMove(target.getBindingAdapterPosition()); // never above Now playing
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return mAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
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
                mDialog.getBehavior().setDraggable(actionState != ItemTouchHelper.ACTION_STATE_DRAG);
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    mHost.onQueueChanged();
                    // A drop can make a row first or last. Posted: a drag also ends when its row is
                    // detached, mid-layout, where the list can't take a change.
                    mList.post(mAdapter::refreshActions);
                }
            }
        });
        dragger.attachToRecyclerView(mList);
        mAdapter.dragger = dragger;

        mClear.setOnClickListener(v -> clear());

        mAdapter.reload();
    }

    static QueueSheet show(@NonNull Context context, @NonNull Host host) {
        QueueSheet sheet = new QueueSheet(context, host);
        host.showQueueSheet(sheet.mDialog);
        return sheet;
    }

    /** The watch page's video changed (autoplay, Next, a new open): list Playlist as it is now. */
    void onPlaylistChanged() {
        if (mDialog.isShowing() && !mAdapter.isInSync()) {
            mAdapter.reload();
        }
    }

    /** "3 videos" (what comes after the playing one), and closes once nothing does. */
    private void updateHeader() {
        int queued = mAdapter.queueSize();
        mCount.setText(mContext.getResources().getQuantityString(R.plurals.mobile_queue_count, queued, queued));
        mClear.setVisibility(queued > 0 ? View.VISIBLE : View.GONE);
        if (queued == 0) {
            mDialog.dismiss();
        }
    }

    /**
     * Empties the queue: what comes after the playing video, never the playing one or the ones
     * before it (Previous walks those). Undo puts it back.
     */
    private void clear() {
        Playlist playlist = Playlist.instance();
        List<Video> cleared = playlist.hasNext() ? new ArrayList<>(playlist.getAllAfterCurrent()) : null;
        if (cleared == null) {
            mAdapter.reload();
            return;
        }

        playlist.removeAllAfterCurrent();
        for (Video video : cleared) {
            video.fromQueue = false;
        }
        mDialog.dismiss();
        mHost.onQueueChanged();

        MobileSnackbar.show(mContext, mContext.getString(R.string.mobile_queue_cleared),
                mContext.getString(R.string.mobile_undo), () -> {
                    for (Video video : cleared) {
                        video.fromQueue = true; // back in the queue, as Add to queue leaves it
                    }
                    Playlist.instance().addAllAfterCurrent(cleared);
                    mHost.onQueueChanged();
                });
    }

    /** Row 0 is the playing video, rows 1.. the queue after it, in Playlist's order. */
    private final class Adapter extends RecyclerView.Adapter<RowHolder> {
        private final List<Video> mVideos = new ArrayList<>();
        ItemTouchHelper dragger;

        /** What the sheet lists: Playlist's playing video, then the ones after it. */
        private List<Video> fromPlaylist() {
            Playlist playlist = Playlist.instance();
            List<Video> rows = new ArrayList<>();
            Video current = playlist.getCurrent();
            if (current != null) {
                rows.add(current);
                // null, not empty, when the playing video is the last one
                List<Video> next = playlist.getAllAfterCurrent();
                if (next != null) {
                    rows.addAll(next);
                }
            }
            return rows;
        }

        void reload() {
            mVideos.clear();
            mVideos.addAll(fromPlaylist());
            notifyDataSetChanged();
            updateHeader();
        }

        /** The rows still are Playlist's: the same videos (instances) in the same order. */
        boolean isInSync() {
            List<Video> rows = fromPlaylist();
            if (rows.size() != mVideos.size()) {
                return false;
            }
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) != mVideos.get(i)) {
                    return false;
                }
            }
            return true;
        }

        int queueSize() {
            return Math.max(mVideos.size() - 1, 0);
        }

        boolean canMove(int position) {
            return position >= 1 && position < mVideos.size();
        }

        boolean move(int from, int to) {
            if (from == to || !canMove(from) || !canMove(to)) {
                return false;
            }
            // In sync, row N is Playlist's current index + N.
            if (!isInSync() || !Playlist.instance().move(mVideos.get(from), Playlist.instance().getCurrentIndex() + to)) {
                reload();
                return false;
            }
            mVideos.add(to, mVideos.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        boolean remove(int position) {
            if (!canMove(position)) {
                return false;
            }
            Video video = mVideos.get(position);
            if (!isInSync() || !Playlist.instance().removeAfterCurrent(video)) {
                reload();
                return false;
            }
            video.fromQueue = false;
            mVideos.remove(position);
            notifyItemRemoved(position);
            refreshActions();
            mHost.onQueueChanged();
            updateHeader();
            return true;
        }

        void refreshActions() {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_ACTIONS);
        }

        /** TalkBack's Move up / Move down: one row, then say where the video is now. */
        private boolean moveBy(@NonNull RowHolder holder, int step) {
            int at = holder.getBindingAdapterPosition();
            int to = at + step;
            if (!move(at, to)) {
                return false;
            }
            mHost.onQueueChanged();
            notifyItemChanged(at, PAYLOAD_ACTIONS);
            notifyItemChanged(to, PAYLOAD_ACTIONS);
            holder.itemView.announceForAccessibility(mContext.getString(R.string.mobile_queue_moved, to, queueSize()));
            return true;
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_mobile_queue_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && allActions(payloads)) {
                bindAccessibility(holder, position);
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        private boolean allActions(List<Object> payloads) {
            for (Object payload : payloads) {
                if (payload != PAYLOAD_ACTIONS) {
                    return false;
                }
            }
            return true;
        }

        @SuppressLint("ClickableViewAccessibility") // the handle only starts the drag; the row's click stays
        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Video video = mVideos.get(position);
            boolean playing = position == 0;

            String title = video.getTitleFull();
            holder.title.setText(TextUtils.isEmpty(title) ? NO_TITLE : title);
            String author = video.getAuthor();
            holder.channel.setText(author);
            holder.channel.setVisibility(TextUtils.isEmpty(author) ? View.GONE : View.VISIBLE);

            String badge = playing ? mContext.getString(R.string.mobile_watch_queue_now_playing) : video.badge;
            holder.badge.setText(badge);
            holder.badge.setVisibility(badge != null && !badge.isEmpty() ? View.VISIBLE : View.GONE);

            // A video opened from a link has no image of its own (the Now playing row was grey).
            com.newtube.mobile.ui.common.LocalThumbnails.fill(video);
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
            holder.remove.setOnClickListener(v -> remove(holder.getBindingAdapterPosition()));
            // The playing row is not a button: tapping it would only restart what is playing.
            holder.itemView.setOnClickListener(playing ? null : v -> {
                int at = holder.getBindingAdapterPosition();
                if (!canMove(at) || !isInSync()) {
                    reload();
                    return;
                }
                Video picked = mVideos.get(at);
                picked.fromQueue = true;
                mDialog.dismiss();
                mHost.playFromQueue(picked);
            });
            holder.itemView.setClickable(!playing);
            holder.itemView.setFocusable(!playing);

            bindAccessibility(holder, position);
        }

        /**
         * Dragging needs a pointer: TalkBack and switch users get Move up / Move down / Remove, each
         * only where it can work (no Move up on the first row of the queue, no Move down on the last).
         */
        private void bindAccessibility(@NonNull RowHolder holder, int position) {
            View row = holder.itemView;
            boolean playing = position == 0;
            for (int id : holder.actionIds) {
                ViewCompat.removeAccessibilityAction(row, id);
            }
            holder.actionIds.clear();
            ViewCompat.setScreenReaderFocusable(row, true); // one stop per row, button or not
            ViewCompat.setStateDescription(row, playing ? mContext.getString(R.string.mobile_watch_queue_now_playing) : null);
            if (playing) {
                return;
            }
            if (canMove(position - 1)) {
                holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.mobile_queue_move_up),
                        (view, args) -> moveBy(holder, -1)));
            }
            if (canMove(position + 1)) {
                holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.mobile_queue_move_down),
                        (view, args) -> moveBy(holder, 1)));
            }
            holder.actionIds.add(ViewCompat.addAccessibilityAction(row, mContext.getString(R.string.remove_from_playback_queue),
                    (view, args) -> remove(holder.getBindingAdapterPosition())));
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
