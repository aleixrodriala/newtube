package com.newtube.mobile.casting;

import android.app.Activity;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Locale;

/**
 * The TV-volume indicator: a floating top-center pill with a draggable slider + percent readout
 * (replaced the v1 toast). One instance lazily attaches to an activity's content view the first
 * time a volume key fires there ({@link CastVolumeKeys}) and is reused via the root view's tag;
 * it auto-hides {@value #AUTO_HIDE_MS}ms after the last key press or drag.
 *
 * <p>Dragging sets absolute TV volume through {@link CastSessionManager#setVolumePercent}, but
 * THROTTLED ({@value #SEND_THROTTLE_MS}ms leading+trailing): onProgressChanged fires per integer
 * step, and on the Lounge route every set is an HTTP POST - an unthrottled drag would flood the
 * sender. The final value always lands (trailing send + a forced send on finger-up). Programmatic
 * {@code setProgress} echoes into the listener with {@code fromUser=false} and is ignored - only
 * real drags talk to the TV.</p>
 *
 * <p>Main-thread confined (key dispatch + touch), like the session manager it drives.</p>
 */
public final class CastVolumeOverlay {

    private static final long AUTO_HIDE_MS = 2_000;
    private static final long FADE_MS = 150;
    private static final long SEND_THROTTLE_MS = 200;

    private final View mRoot;
    private final SeekBar mSlider;
    private final TextView mValue;
    private final Runnable mHide = this::hideNow;
    private final Runnable mSendPending = () -> sendPending(true);
    /** Finger currently on the slider: suppress auto-hide until it lifts. */
    private boolean mTracking;
    private long mLastSendUptime;
    /** Latest dragged value not yet sent to the TV; -1 when nothing is pending. */
    private int mPendingPercent = -1;

    /** Show (or refresh) the overlay in this activity at the given volume. */
    public static void show(Activity activity, int volumePercent) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View existing = content.findViewById(R.id.mobile_cast_volume_overlay);
        CastVolumeOverlay overlay = existing != null
                ? (CastVolumeOverlay) existing.getTag()
                : new CastVolumeOverlay(activity, content);
        overlay.showVolume(volumePercent);
    }

    private CastVolumeOverlay(Activity activity, ViewGroup content) {
        mRoot = LayoutInflater.from(activity)
                .inflate(R.layout.overlay_mobile_cast_volume, content, false);
        mRoot.setTag(this);
        content.addView(mRoot);
        mSlider = mRoot.findViewById(R.id.cast_volume_slider);
        mValue = mRoot.findViewById(R.id.cast_volume_value);

        mSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mValue.setText(formatPercent(progress));
                if (fromUser) {
                    mPendingPercent = progress;
                    sendPending(false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mTracking = true;
                mRoot.removeCallbacks(mHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mTracking = false;
                sendPending(true); // the value the finger settled on always lands
                scheduleHide();
            }
        });
    }

    private void showVolume(int volumePercent) {
        int clamped = Math.max(0, Math.min(100, volumePercent));
        mSlider.setProgress(clamped);
        mValue.setText(formatPercent(clamped)); // setProgress skips its listener when unchanged
        mRoot.animate().cancel();
        mRoot.setAlpha(1f);
        mRoot.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    /** Leading+trailing throttle around {@link CastSessionManager#setVolumePercent}. */
    private void sendPending(boolean force) {
        if (mPendingPercent < 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long sinceLast = now - mLastSendUptime;
        if (!force && sinceLast < SEND_THROTTLE_MS) {
            mRoot.removeCallbacks(mSendPending);
            mRoot.postDelayed(mSendPending, SEND_THROTTLE_MS - sinceLast);
            return;
        }
        mLastSendUptime = now;
        int percent = mPendingPercent;
        mPendingPercent = -1;
        mRoot.removeCallbacks(mSendPending);
        CastSessionManager.instance(mRoot.getContext()).setVolumePercent(percent);
    }

    private void scheduleHide() {
        mRoot.removeCallbacks(mHide);
        mRoot.postDelayed(mHide, AUTO_HIDE_MS);
    }

    private void hideNow() {
        if (mTracking) {
            return; // finger still down; onStopTrackingTouch reschedules
        }
        mRoot.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> mRoot.setVisibility(View.GONE)).start();
    }

    private static String formatPercent(int percent) {
        return String.format(Locale.US, "%d%%", percent);
    }
}
