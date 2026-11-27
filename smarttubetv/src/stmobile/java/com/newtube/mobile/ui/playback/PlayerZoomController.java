package com.newtube.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.github.vkay94.dtpv3.DoubleTapPlayerViewImpl;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

/**
 * Manages continuous pinch-to-zoom scaling, snap animations, Fill/Fit toggling, and zoom hint
 * feedback for {@link MobilePlaybackActivity}.
 */
final class PlayerZoomController {

    interface Host {
        int getZoomResizeMode();

        void applyZoomResizeMode(int mode);

        float getZoomVideoAspect();

        boolean isZoomSuspended();

        void onZoomInsetsChanged();
    }

    private final Context mContext;
    private final Host mHost;
    @Nullable private final DoubleTapPlayerViewImpl mPlayerView;
    @Nullable private final View mVideoArea;
    @Nullable private final TextView mZoomHintView;
    private float mCustomZoomScale = 1f;
    private boolean mZoomBoundaryHit;
    @Nullable private ValueAnimator mZoomScaleAnimator;
    private final Runnable mHideZoomHint;

    PlayerZoomController(Context context, Host host, @Nullable DoubleTapPlayerViewImpl playerView,
            @Nullable View videoArea, @Nullable TextView zoomHintView) {
        mContext = context;
        mHost = host;
        mPlayerView = playerView;
        mVideoArea = videoArea;
        mZoomHintView = zoomHintView;
        mHideZoomHint = () -> {
            if (mZoomHintView != null) {
                mZoomHintView.animate().alpha(0f).setDuration(250)
                        .withEndAction(() -> mZoomHintView.setVisibility(View.GONE)).start();
            }
        };
    }

    static float fillScaleFor(int boxWidth, int boxHeight, float videoAspect) {
        if (boxWidth <= 0 || boxHeight <= 0 || !(videoAspect > 0f)) {
            return 1f;
        }
        float boxAspect = (float) boxWidth / boxHeight;
        float ratio = boxAspect > videoAspect ? boxAspect / videoAspect : videoAspect / boxAspect;
        return Math.max(1f, ratio);
    }

    static float clampZoomScale(float scale, float maxScale) {
        float ceiling = Math.max(1f, maxScale);
        if (Float.isNaN(scale) || scale <= 1f) {
            return 1f;
        }
        return Math.min(ceiling, scale);
    }

    float getCustomZoomScale() {
        return mCustomZoomScale;
    }

    boolean isFilled() {
        return mHost.getZoomResizeMode() == PlayerConstants.RESIZE_MODE_FIT_BOTH
                || mCustomZoomScale > 1.01f;
    }

    void setCustomScaleFromPercents(int percents) {
        mCustomZoomScale = percents > 100 ? percents / 100f : 1f;
        syncScale();
    }

    void resetScaleToDefault() {
        mCustomZoomScale = 1f;
        applyContentFrameScale(1f);
    }

    float currentFillScale() {
        if (mPlayerView == null) {
            return 1f;
        }
        int width = mPlayerView.getWidth() > 0 ? mPlayerView.getWidth()
                : (mVideoArea != null ? mVideoArea.getWidth() : 0);
        int height = mPlayerView.getHeight() > 0 ? mPlayerView.getHeight()
                : (mVideoArea != null ? mVideoArea.getHeight() : 0);
        return fillScaleFor(width, height, mHost.getZoomVideoAspect());
    }

    void syncScale() {
        cancelAnimation();
        if (mHost.isZoomSuspended() || mHost.getZoomResizeMode() != PlayerConstants.RESIZE_MODE_DEFAULT) {
            applyContentFrameScale(1f);
            return;
        }
        applyContentFrameScale(clampZoomScale(mCustomZoomScale, currentFillScale()));
    }

    void cancelAnimation() {
        if (mZoomScaleAnimator != null) {
            mZoomScaleAnimator.cancel();
            mZoomScaleAnimator = null;
        }
    }

    void applyContentFrameScale(float scale) {
        ViewGroup contentFrame = mPlayerView != null ? mPlayerView.getContentFrame() : null;
        if (contentFrame != null) {
            contentFrame.animate().cancel();
            contentFrame.setScaleX(scale);
            contentFrame.setScaleY(scale);
        }
    }

    void onPinchBegin() {
        cancelAnimation();
        mZoomBoundaryHit = false;
        float maxScale = currentFillScale();
        int mode = mHost.getZoomResizeMode();
        if (mode == PlayerConstants.RESIZE_MODE_FIT_BOTH) {
            mCustomZoomScale = maxScale;
            if (mPlayerView != null) {
                mPlayerView.setResizeMode(PlayerConstants.RESIZE_MODE_DEFAULT);
            }
            applyContentFrameScale(maxScale);
        } else if (mode != PlayerConstants.RESIZE_MODE_DEFAULT) {
            mCustomZoomScale = 1f;
            if (mPlayerView != null) {
                mPlayerView.setResizeMode(PlayerConstants.RESIZE_MODE_DEFAULT);
            }
            applyContentFrameScale(1f);
        } else {
            mCustomZoomScale = clampZoomScale(mCustomZoomScale, maxScale);
        }
    }

    void onPinchScale(float scaleFactor) {
        float maxScale = currentFillScale();
        if (maxScale <= 1.01f) {
            showHint(R.string.mobile_player_zoom_original);
            return;
        }
        float next = clampZoomScale(mCustomZoomScale * scaleFactor, maxScale);
        boolean atBoundary = next <= 1f || next >= maxScale;
        if (atBoundary && !mZoomBoundaryHit && Math.abs(next - mCustomZoomScale) > 1e-4f) {
            if (mVideoArea != null) {
                Haptics.tick(mVideoArea);
            }
            mZoomBoundaryHit = true;
        } else if (!atBoundary) {
            mZoomBoundaryHit = false;
        }
        mCustomZoomScale = next;
        applyContentFrameScale(next);
        showHintText(formatZoomLabel(next, maxScale));
    }

    void onPinchEnd() {
        float maxScale = currentFillScale();
        PlayerData playerData = PlayerData.instance(mContext);
        if (mCustomZoomScale <= 1.03f || maxScale <= 1.01f) {
            mCustomZoomScale = 1f;
            playerData.setResizeMode(PlayerConstants.RESIZE_MODE_DEFAULT);
            playerData.setZoomPercents(-1);
            animateZoomTo(1f, PlayerConstants.RESIZE_MODE_DEFAULT, R.string.mobile_player_zoom_original);
            return;
        }
        if (maxScale > 1.05f && mCustomZoomScale >= maxScale - 0.03f) {
            mCustomZoomScale = 1f;
            playerData.setResizeMode(PlayerConstants.RESIZE_MODE_FIT_BOTH);
            playerData.setZoomPercents(-1);
            animateZoomTo(maxScale, PlayerConstants.RESIZE_MODE_FIT_BOTH, R.string.mobile_player_zoom_fill);
            return;
        }
        playerData.setResizeMode(PlayerConstants.RESIZE_MODE_DEFAULT);
        playerData.setZoomPercents(Math.round(mCustomZoomScale * 100f));
        mHost.onZoomInsetsChanged();
        showHintText(formatZoomLabel(mCustomZoomScale, maxScale));
    }

    void toggleFillZoom() {
        float maxScale = currentFillScale();
        if (maxScale <= 1.02f) {
            return;
        }
        boolean filled = isFilled();
        mCustomZoomScale = filled ? 1f : maxScale;
        int nextMode = filled ? PlayerConstants.RESIZE_MODE_DEFAULT : PlayerConstants.RESIZE_MODE_FIT_BOTH;
        PlayerData playerData = PlayerData.instance(mContext);
        playerData.setResizeMode(nextMode);
        playerData.setZoomPercents(-1);
        if (mPlayerView != null) {
            mPlayerView.setResizeMode(nextMode);
        }
        applyContentFrameScale(1f);
        showHint(filled ? R.string.mobile_player_zoom_original : R.string.mobile_player_zoom_fill);
    }

    private CharSequence formatZoomLabel(float scale, float maxScale) {
        if (scale <= 1.01f) {
            return mContext.getString(R.string.mobile_player_zoom_original);
        }
        if (maxScale > 1.01f && scale >= maxScale - 0.01f) {
            return mContext.getString(R.string.mobile_player_zoom_fill);
        }
        return mContext.getString(R.string.mobile_player_zoom_percent, Math.round(scale * 100f));
    }

    private void animateZoomTo(float targetScale, int finalResizeMode, @StringRes int hintRes) {
        ViewGroup contentFrame = mPlayerView != null ? mPlayerView.getContentFrame() : null;
        if (contentFrame == null || contentFrame.getWidth() == 0) {
            mHost.applyZoomResizeMode(finalResizeMode);
            showHint(hintRes);
            return;
        }
        cancelAnimation();
        float startScale = contentFrame.getScaleX();
        if (Math.abs(startScale - targetScale) < 0.005f) {
            if (finalResizeMode == PlayerConstants.RESIZE_MODE_FIT_BOTH) {
                mHost.applyZoomResizeMode(PlayerConstants.RESIZE_MODE_FIT_BOTH);
            } else {
                applyContentFrameScale(1f);
                mHost.onZoomInsetsChanged();
            }
            showHint(hintRes);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(startScale, targetScale);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> applyContentFrameScale((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mZoomScaleAnimator == animation) {
                    mZoomScaleAnimator = null;
                }
                if (mCancelled) {
                    return;
                }
                applyContentFrameScale(1f);
                mHost.applyZoomResizeMode(finalResizeMode);
            }
        });
        mZoomScaleAnimator = anim;
        anim.start();
        showHint(hintRes);
    }

    private void showHint(@StringRes int textRes) {
        showHintText(mContext.getString(textRes));
    }

    private void showHintText(CharSequence text) {
        if (mZoomHintView == null) {
            return;
        }
        mZoomHintView.removeCallbacks(mHideZoomHint);
        mZoomHintView.animate().cancel();
        mZoomHintView.setText(text);
        if (mZoomHintView.getVisibility() != View.VISIBLE) {
            mZoomHintView.setAlpha(0f);
            mZoomHintView.setVisibility(View.VISIBLE);
        }
        mZoomHintView.animate().alpha(1f).setDuration(120).start();
        mZoomHintView.postDelayed(mHideZoomHint, 900);
    }
}
