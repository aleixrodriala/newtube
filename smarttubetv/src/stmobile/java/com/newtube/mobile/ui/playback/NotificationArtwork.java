package com.newtube.mobile.ui.playback;

import java.util.Objects;
import java.util.function.Consumer;

/** Main-thread artwork state: notification rebuilds share one request and use its latest callback. */
final class NotificationArtwork<T> {
    private String mUrl;
    private T mResource;
    private Consumer<T> mCallback;
    private long mGeneration;
    private boolean mLoading;

    T get(String url) {
        return Objects.equals(url, mUrl) ? mResource : null;
    }

    /** Returns a new request token, or zero when the existing download can serve this callback. */
    long request(String url, Consumer<T> callback) {
        mCallback = callback;
        if (mLoading && Objects.equals(url, mUrl)) {
            return 0;
        }
        mUrl = url;
        mResource = null;
        mLoading = true;
        return ++mGeneration;
    }

    boolean complete(long generation, String selectedUrl, T resource) {
        if (!mLoading || generation != mGeneration) {
            return false;
        }
        // The presenter can switch videos before Media3 asks for the next notification image.
        // Its current URL, not just the latest image-request token, decides whether this can show.
        if (!Objects.equals(mUrl, selectedUrl)) {
            clear();
            return false;
        }
        mLoading = false;
        mResource = resource;
        Consumer<T> callback = mCallback;
        mCallback = null;
        if (callback != null) {
            callback.accept(resource);
        }
        return true;
    }

    void fail(long generation) {
        if (generation == mGeneration) {
            mLoading = false;
            mCallback = null;
        }
    }

    void clear() {
        ++mGeneration;
        mUrl = null;
        mResource = null;
        mCallback = null;
        mLoading = false;
    }
}
