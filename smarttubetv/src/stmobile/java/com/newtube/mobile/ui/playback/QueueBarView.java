package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** A collapsed queue entry point. No polling, progress ticks, images or background work. */
public final class QueueBarView extends LinearLayout implements DefaultLifecycleObserver {
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mQueueChanged = this::scheduleRefresh;
    private final Runnable mRefresh = this::refresh;
    private final TextView mLabel;
    private final int mTouchSlop;
    private volatile boolean mStarted;
    private boolean mAvailable = true;
    private boolean mRefreshPosted;
    private float mDownX;
    private float mDownY;
    private Runnable mOpen;
    private LifecycleOwner mOwner;

    public QueueBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_mobile_queue_bar, this);
        mLabel = findViewById(R.id.queue_bar_label);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setMinimumHeight(Math.round(56 * getResources().getDisplayMetrics().density));
        setClickable(true);
        setFocusable(true);
        setVisibility(GONE);
        setOnClickListener(v -> {
            if (mStarted && mOpen != null) mOpen.run();
        });
    }

    /** Shared browsing entry point; playback keeps its own fullscreen-aware sheet host. */
    public static void bindBrowsing(androidx.fragment.app.FragmentActivity activity) {
        QueueBarView bar = activity.findViewById(R.id.mobile_queue_bar);
        bar.bind(activity, () -> {
            // Dismiss the search keyboard before opening the queue, without clearing the query.
            android.view.View focus = activity.getCurrentFocus();
            if (focus != null) {
                androidx.core.view.WindowInsetsControllerCompat controller =
                        androidx.core.view.ViewCompat.getWindowInsetsController(focus);
                if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime());
            }
            QueueSheet.show(activity, new QueueSheet.Host() {
                @Override public void playFromQueue(Video video) {
                    video.fromQueue = true;
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
                            .instance(activity).openVideo(video);
                }
                @Override public void onQueueChanged() { /* Playlist notifies visible bars. */ }
                @Override public void showQueueSheet(
                        com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
                    dialog.show();
                }
            });
        });
    }

    public void bind(LifecycleOwner owner, Runnable open) {
        if (mOwner != null) {
            onStop(mOwner);
            mOwner.getLifecycle().removeObserver(this);
        }
        mOwner = owner;
        mOpen = open;
        if (owner.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.DESTROYED) {
            mOpen = null;
            mOwner = null;
            return;
        }
        owner.getLifecycle().addObserver(this);
    }

    /** Fullscreen and PiP explicitly suspend the bar, independently of window orientation. */
    public void setAvailable(boolean available) {
        mAvailable = available;
        if (mStarted) refresh();
        else if (!available) setVisibility(GONE);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        if (owner != mOwner) return;
        mStarted = true;
        Playlist.instance().addListener(mQueueChanged);
        refresh();
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        if (owner != mOwner) return;
        mStarted = false;
        Playlist.instance().removeListener(mQueueChanged);
        synchronized (this) {
            mMain.removeCallbacks(mRefresh);
            mRefreshPosted = false;
        }
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        if (owner != mOwner) return;
        onStop(owner);
        mOpen = null;
        mOwner = null;
        owner.getLifecycle().removeObserver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mOwner != null) {
            onStop(mOwner);
            mOwner.getLifecycle().removeObserver(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mOwner != null) {
            if (mOwner.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.DESTROYED) {
                onDestroy(mOwner);
            } else {
                mOwner.getLifecycle().addObserver(this);
            }
        }
    }

    // One main-thread update for a burst (e.g. remove + insert). No delayed or repeating task.
    private synchronized void scheduleRefresh() {
        if (mStarted && !mRefreshPosted) {
            mRefreshPosted = true;
            mMain.post(mRefresh);
        }
    }

    private void refresh() {
        synchronized (this) { mRefreshPosted = false; }
        if (!mStarted) return;
        Playlist queue = Playlist.instance();
        int current = queue.getCurrentIndex();
        int size = queue.getAll().size();
        boolean restored = queue.isRestoredQueue();
        int remaining = current >= 0 ? size - current - (restored ? 0 : 1) : size;
        Video playing = queue.getCurrent();
        // Playback consumes Video.fromQueue on every open. The model remembers an explicit
        // queue independently, including after the last video advances while this view is stopped.
        boolean manuallyQueued = queue.hasExplicitQueue();
        boolean visible = mAvailable && size > 0
                && (playing == null || remaining > 0 || manuallyQueued);
        if (visible) {
            Video next = restored && playing != null ? playing
                    : playing == null ? queue.getAll().get(0) : queue.getNext();
            String count = getResources().getQuantityString(
                    R.plurals.mobile_queue_bar_remaining, remaining, remaining);
            String nextTitle = next != null ? next.getTitle() : null;
            if (android.text.TextUtils.isEmpty(nextTitle)) {
                nextTitle = getContext().getString(R.string.mobile_queue_untitled_video);
            }
            String label = remaining > 0
                    ? getContext().getString(R.string.mobile_queue_bar_next, count, nextTitle)
                    : getContext().getString(R.string.mobile_queue_bar_end);
            if (!label.contentEquals(mLabel.getText())) {
                mLabel.setText(label);
                setContentDescription(getContext().getString(R.string.mobile_queue_bar_open, label));
            }
        }
        int visibility = visible ? VISIBLE : GONE;
        if (getVisibility() != visibility) setVisibility(visibility);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDownX = event.getX();
            mDownY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                && mDownY - event.getY() > mTouchSlop
                && mDownY - event.getY() > Math.abs(event.getX() - mDownX)) {
            setPressed(false);
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
