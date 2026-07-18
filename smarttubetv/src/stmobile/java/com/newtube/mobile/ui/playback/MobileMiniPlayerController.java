package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Renders an active {@link MiniPlayerBridge} session inside a screen's floating mini card.
 *
 * <p>The playback Activity still owns both ExoPlayer and its session-long SurfaceTexture. This
 * controller only adopts that texture into a temporary TextureView while its host Activity is in
 * front, then returns it before the host pauses. Keeping this small host adapter outside the
 * channel Activity makes the surface ownership rules identical to Home's proven mini-player.</p>
 */
public final class MobileMiniPlayerController {
    private static final long TICK_MS = 500;
    private static final long ENTRY_DURATION_MS = 280;

    private final Activity mActivity;
    private final View mBar;
    private final FrameLayout mFrame;
    private final ImageView mFreeze;
    private final ImageButton mPlayPause;
    private final ImageButton mClose;
    private final ProgressBar mProgress;
    private final Runnable mTick = this::onTick;

    private TextureView mTexture;
    private boolean mEntryAnimating;
    private boolean mTextureHasFrame;

    public MobileMiniPlayerController(Activity activity) {
        mActivity = activity;
        mBar = activity.findViewById(R.id.mobile_mini_player);
        mFrame = activity.findViewById(R.id.mobile_mini_player_frame);
        mFreeze = activity.findViewById(R.id.mobile_mini_freeze);
        mPlayPause = activity.findViewById(R.id.mobile_mini_play_pause);
        mClose = activity.findViewById(R.id.mobile_mini_close);
        mProgress = activity.findViewById(R.id.mobile_mini_progress);

        View.OnClickListener expand = v -> {
            publishBounds();
            MiniPlayerBridge.expand(mActivity);
        };
        mBar.setOnClickListener(expand);
        mFrame.setOnClickListener(expand);

        mPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updatePlayPause(player);
            }
        });

        mClose.setOnClickListener(v -> {
            hide();
            MiniPlayerBridge.close();
        });
    }

    /** Show the live mini session. Optionally animate it down from the watch-page video box. */
    public void sync(boolean animateFromPlayer) {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }

        mEntryAnimating = animateFromPlayer;
        mTextureHasFrame = false;

        Bitmap entryStill = MiniPlayerBridge.takeMiniEntryStill();
        if (entryStill != null) {
            mFreeze.setImageBitmap(entryStill);
            mFreeze.setVisibility(View.VISIBLE);
        }

        attachTexture();
        mBar.setVisibility(View.VISIBLE);
        updatePlayPause(player);

        Utils.removeCallbacks(mTick);
        Utils.postDelayed(mTick, TICK_MS);

        mBar.post(() -> {
            publishBounds();
            if (animateFromPlayer && mBar.getVisibility() == View.VISIBLE) {
                animateFromWatchPage();
            }
        });
    }

    /**
     * The translucent playback Activity is still on top when this runs. Make the endpoint card
     * visible now so this host can draw it underneath before Android reorders it to the front.
     * Mirrors Home's proven pre-render gate (see MobileBrowseActivity#prepareMiniPlayerForHandoff).
     */
    public boolean prepareForHandoff(Runnable onDrawn) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        sync(false);
        if (mBar.getVisibility() != View.VISIBLE) {
            return false;
        }

        // A pair of frame callbacks only proves that time passed; it does not prove this paused
        // window submitted a buffer. Gate the reorder on an actual draw containing the card, then
        // wait one compositor frame before removing the playback window above it. The fallback
        // avoids stranding the player at the endpoint if an OEM suppresses draws on paused windows.
        final ViewTreeObserver observer = mBar.getViewTreeObserver();
        class DrawGate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDelivered;

            @Override
            public void onDraw() {
                mBar.post(this);
            }

            @Override
            public void run() {
                if (mDelivered) {
                    return;
                }
                mDelivered = true;
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(this);
                }
                mBar.postOnAnimation(onDrawn);
            }
        }
        DrawGate gate = new DrawGate();
        observer.addOnDrawListener(gate);
        mBar.postDelayed(gate, 100);
        mBar.invalidate();
        return true;
    }

    /**
     * Freeze the last mini frame, release this screen's TextureView, and hide the card. The player
     * Activity can then reclaim the same session texture during Back/expand without a black beat.
     */
    public void hide() {
        Utils.removeCallbacks(mTick);
        mBar.animate().cancel();
        mEntryAnimating = false;
        mTextureHasFrame = false;
        resetTransform();
        publishBounds();

        if (mTexture != null && mTexture.getParent() != null) {
            if (mTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap still = mTexture.getBitmap();
                if (still != null) {
                    mFreeze.setImageBitmap(still);
                    mFreeze.setVisibility(View.VISIBLE);
                    MiniPlayerBridge.setHandoffStill(still);
                }
            }
            mFrame.removeView(mTexture);
            mTexture = null;
        }

        mBar.setVisibility(View.GONE);
    }

    private void attachTexture() {
        if (mTexture == null) {
            final TextureView texture = new TextureView(mActivity);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture created, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (session != null && created != session) {
                        texture.setSurfaceTexture(session);
                        created.release();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    // The playback Activity owns the session texture. Release only a temporary
                    // TextureView-created surface that was never replaced by that session.
                    return texture != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    mTextureHasFrame = true;
                    hideFreezeWhenReady();
                }
            });
            mTexture = texture;
        }

        if (mTexture.getParent() == null) {
            mFrame.addView(mTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Start as the full-width 16:9 watch video, then land on the channel's floating card. */
    private void animateFromWatchPage() {
        int width = mBar.getWidth();
        int height = mBar.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int[] cardLocation = new int[2];
        int[] contentLocation = new int[2];
        mBar.getLocationOnScreen(cardLocation);
        View content = mActivity.findViewById(android.R.id.content);
        content.getLocationOnScreen(contentLocation);

        float sourceWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        float sourceHeight = sourceWidth * 9f / 16f;

        mBar.setPivotX(0f);
        mBar.setPivotY(0f);
        mBar.setScaleX(sourceWidth / width);
        mBar.setScaleY(sourceHeight / height);
        mBar.setTranslationX(contentLocation[0] - cardLocation[0]);
        mBar.setTranslationY(contentLocation[1] - cardLocation[1]);

        setControlsAlpha(0f);
        mBar.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(ENTRY_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    resetTransform();
                    mEntryAnimating = false;
                    hideFreezeWhenReady();
                    mPlayPause.animate().alpha(1f).setDuration(90).start();
                    mClose.animate().alpha(1f).setDuration(90).start();
                    mProgress.animate().alpha(1f).setDuration(90).start();
                    publishBounds();
                })
                .start();
    }

    private void hideFreezeWhenReady() {
        if (!mEntryAnimating && mTextureHasFrame && mFreeze.getVisibility() == View.VISIBLE) {
            mFreeze.setVisibility(View.GONE);
        }
    }

    private void resetTransform() {
        mBar.setPivotX(mBar.getWidth() / 2f);
        mBar.setPivotY(mBar.getHeight() / 2f);
        mBar.setScaleX(1f);
        mBar.setScaleY(1f);
        mBar.setTranslationX(0f);
        mBar.setTranslationY(0f);
        setControlsAlpha(1f);
    }

    private void setControlsAlpha(float alpha) {
        mPlayPause.animate().cancel();
        mClose.animate().cancel();
        mProgress.animate().cancel();
        mPlayPause.setAlpha(alpha);
        mClose.setAlpha(alpha);
        mProgress.setAlpha(alpha);
    }

    private void publishBounds() {
        if (mBar.getVisibility() != View.VISIBLE || mBar.getWidth() <= 0 || mBar.getHeight() <= 0) {
            return;
        }
        int[] location = new int[2];
        mBar.getLocationOnScreen(location);
        MiniPlayerBridge.setMiniBounds(new Rect(location[0], location[1],
                location[0] + mBar.getWidth(), location[1] + mBar.getHeight()));
    }

    private void onTick() {
        if (mBar.getVisibility() != View.VISIBLE) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            mProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }
        updatePlayPause(player);
        Utils.postDelayed(mTick, TICK_MS);
    }

    private void updatePlayPause(ExoPlayer player) {
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
    }
}
