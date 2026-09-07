package com.newtube.mobile.player;

/** One unconsumed source; publication must keep an existing same-video owner's exact instance. */
final class SourceStash<T> {
    private String mVideoId;
    private T mSource;

    synchronized boolean hasSource() {
        return mSource != null;
    }

    synchronized boolean contains(String videoId) {
        return mSource != null && mVideoId.equals(videoId);
    }

    synchronized boolean containsSource(T source) {
        return mSource != null && mSource == source;
    }

    /** A queued duplicate must not replace a source already owned by the sample preloader. */
    synchronized boolean offerIfAbsent(String videoId, T source) {
        java.util.Objects.requireNonNull(videoId);
        java.util.Objects.requireNonNull(source);
        if (contains(videoId)) {
            return false;
        }
        mVideoId = videoId;
        mSource = source;
        return true;
    }

    synchronized T take(String videoId) {
        if (!contains(videoId)) {
            return null;
        }
        T source = mSource;
        clear();
        return source;
    }

    synchronized void discard(T source) {
        if (containsSource(source)) {
            clear();
        }
    }

    synchronized void dropExcept(String videoId) {
        if (!contains(videoId)) {
            clear();
        }
    }

    synchronized void clear() {
        mVideoId = null;
        mSource = null;
    }
}
