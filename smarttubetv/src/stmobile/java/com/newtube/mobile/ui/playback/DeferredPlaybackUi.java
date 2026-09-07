package com.newtube.mobile.ui.playback;

/** Main-thread render gate; model updates happen outside it and the latest render reads that model. */
final class DeferredPlaybackUi {
    private boolean mReleased;
    private Runnable mPendingRender;

    void reset() {
        mReleased = false;
        cancelPending();
    }

    void renderWhenReady(Runnable render) {
        if (mReleased) {
            render.run();
        } else {
            mPendingRender = render;
        }
    }

    void release() {
        mReleased = true;
        Runnable render = mPendingRender;
        mPendingRender = null;
        if (render != null) {
            render.run();
        }
    }

    boolean isReleased() {
        return mReleased;
    }

    void cancelPending() {
        mPendingRender = null;
    }
}
