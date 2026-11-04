package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages a playlist of videos.
 */
public class Playlist {
    private static final int LOW_RAM_PLAYLIST_MAX_SIZE = 50;
    private static final int HIGH_RAM_PLAYLIST_MAX_SIZE = 300;
    private final int mPlaylistMaxSize;
    private final List<Video> mPlaylist;
    private final List<Video> mSyncedItems;
    private int mCurrentIndex;
    // fromQueue is a one-open routing flag consumed by VideoLoaderController. It cannot tell
    // UI whether this session still contains a queue the user explicitly built.
    private boolean mHasExplicitQueue;
    private boolean mRestoredQueue;
    private static Playlist sInstance;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> mListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Structural/current-item changes only; never playback-position ticks. */
    public void addListener(Runnable listener) {
        mListeners.addIfAbsent(listener);
    }

    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : mListeners) {
            listener.run();
        }
    }

    private Playlist() {
        mPlaylist = new ArrayList<>();
        mSyncedItems = new ArrayList<>();
        mCurrentIndex = -1;
        mPlaylistMaxSize = Utils.isEnoughRam() ? HIGH_RAM_PLAYLIST_MAX_SIZE : LOW_RAM_PLAYLIST_MAX_SIZE;
    }

    public static Playlist instance() {
        if (sInstance == null) {
            sInstance = new Playlist();
        }

        return sInstance;
    }

    /**
     * Clears the videos from the playlist.
     */
    public void clear() {
        mPlaylist.clear();
        mCurrentIndex = -1;
        mHasExplicitQueue = false;
        mRestoredQueue = false;
        notifyChanged();
    }

    /** Clears the queue in one pass and emits one complete state, preserving the playing object. */
    public void clearExceptCurrent() {
        if (mRestoredQueue) {
            clear(); // there is a saved cursor, but no playing item to preserve
            return;
        }
        Video current = getCurrent();
        boolean hadExplicitQueue = mHasExplicitQueue;
        mHasExplicitQueue = false;
        if (mPlaylist.isEmpty() || (mPlaylist.size() == 1 && current != null)) {
            if (hadExplicitQueue) notifyChanged();
            return;
        }
        for (Video video : mPlaylist) {
            if (video != null && video != current) video.fromQueue = false;
        }
        mPlaylist.clear();
        if (current != null) mPlaylist.add(current);
        mCurrentIndex = current != null ? 0 : -1;
        notifyChanged();
    }

    public void clearPosition() {
        mCurrentIndex = -1;
        notifyChanged();
    }

    /**
     * Used to sync list with remotely added items
     */
    public void addAll(List<Video> videos) {
        for (Video video : videos) {
            if (video != null && video.fromQueue) mHasExplicitQueue = true;
        }
        mPlaylist.removeAll(videos);
        mPlaylist.addAll(videos);
        notifyChanged();
    }

    /**
     * Adds a video to the end of the playlist.
     *
     * @param video to be added to the playlist.
     */
    public void add(Video video) {
        if (Video.isEmpty(video)) {
            return;
        }

        Video current = getCurrent();

        // Skip add currently playing item
        // And replace to correct position sync in fragments
        if (video.equals(current)) {
            if (video.fromQueue) mHasExplicitQueue = true;
            replace(current, video);
            return;
        }

        boolean isLastElement = !mPlaylist.isEmpty() && video.equals(mPlaylist.get(mPlaylist.size() - 1));

        removeInternal(video);
        if (video.fromQueue) mHasExplicitQueue = true;

        mPlaylist.add(video);

        // Replacing last element? Increase index then.
        if (isLastElement) {
            mCurrentIndex++;
        }

        // Video opened from the browser or suggestions.
        // In this case remove all next items.
        trimPlaylist();
        stripPrevItem();
        notifyChanged();
    }

    public void next(Video video) {
        if (Video.isEmpty(video)) {
            return;
        }

        removeInternal(video);
        if (video.fromQueue) mHasExplicitQueue = true;

        int nextIdx = mPlaylist.size() > mCurrentIndex ? mCurrentIndex + 1 : mPlaylist.size() - 1;

        // IndexOutOfBoundsException fix
        if (nextIdx < 0) {
            return;
        }

        mPlaylist.add(nextIdx, video);

        // Video opened from the browser or suggestions.
        // In this case remove all next items.
        trimPlaylist();
        stripPrevItem();
        notifyChanged();
    }

    public void remove(Video video) {
        if (removeInternal(video)) notifyChanged();
    }

    /** User edits preserve the playing occurrence; a restored cursor is not active playback. */
    public boolean canRemoveQueuedItem(Video video) {
        int index = indexOfVideo(video);
        return index >= 0 && (mRestoredQueue || index != mCurrentIndex);
    }

    /** Recheck at execution time: playback may advance while a menu is open. */
    public boolean removeQueuedItem(Video video) {
        if (!canRemoveQueuedItem(video)) return false;
        remove(video);
        return true;
    }

    private int indexOfVideo(Video video) {
        if (Video.isEmpty(video)) return -1;
        int index = indexOfInstance(video);
        return index >= 0 ? index : mPlaylist.indexOf(video);
    }

    // Compound add/next operations publish only their completed state. In particular, a
    // temporary removal must not make persistence forget a requeued item's private status.
    private boolean removeInternal(Video video) {
        if (Video.isEmpty(video)) {
            return false;
        }

        int index = indexOfVideo(video);

        // If contains
        if (index >= 0) {
            mPlaylist.remove(index);
            if (mPlaylist.isEmpty()) mHasExplicitQueue = false;

            // Shift video stack index if needed
            // Don't remove current index. Except this is the last element.
            // Give a chance to replace current element.
            if (index < mCurrentIndex) {
                mCurrentIndex--;
            }

            // Index out of bounds as the result of previous operation.
            // Select last element in this case.
            if (mCurrentIndex >= mPlaylist.size()) {
                mCurrentIndex = mPlaylist.size() - 1;
            }
            return true;
        }
        return false;
    }

    /**
     * NEWTUBE(queue): moves the video at {@code from} to {@code to} (both indexes into
     * {@link #getAll()}), keeping the playing one current wherever it ends up.
     */
    public void move(int from, int to) {
        int size = mPlaylist.size();
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) {
            return;
        }

        Video current = getCurrent();
        Video moved = mPlaylist.remove(from);
        mPlaylist.add(to, moved);

        if (current != null) {
            mCurrentIndex = indexOfInstance(current);
        }
        notifyChanged();
    }

    /** By identity: {@code Video.equals} is a composite that two rows of one video can share. */
    private int indexOfInstance(Video video) {
        for (int i = 0; i < mPlaylist.size(); i++) {
            if (mPlaylist.get(i) == video) {
                return i;
            }
        }
        return -1;
    }

    /** NEWTUBE(queue): the index of the playing video in {@link #getAll()}, or -1. */
    public int getCurrentIndex() {
        return mCurrentIndex < mPlaylist.size() ? mCurrentIndex : -1;
    }

    public boolean contains(Video video) {
        if (Video.isEmpty(video)) {
            return false;
        }

        return mPlaylist.contains(video);
    }

    public boolean containsAfterCurrent(Video video) {
        if (Video.isEmpty(video)) {
            return false;
        }

        List<Video> afterCurrent = getAllAfterCurrent();

        return afterCurrent != null && afterCurrent.contains(video);
    }

    ///**
    // * Trim playlist if one exceeds needed size or current element not last in the list
    // */
    //private void trimPlaylist() {
    //    int fromIndex = 0;
    //    int toIndex = mCurrentIndex + 1;
    //
    //    boolean isLastElement = mCurrentIndex == (mPlaylist.size() - 1);
    //    boolean playlistTooBig = mPlaylist.size() > PLAYLIST_MAX_SIZE;
    //
    //    if (playlistTooBig) {
    //        fromIndex = mPlaylist.size() - PLAYLIST_MAX_SIZE;
    //    }
    //
    //    if (!isLastElement || playlistTooBig) {
    //        mPlaylist = mPlaylist.subList(fromIndex, toIndex);
    //        mCurrentIndex = mPlaylist.size() - 1;
    //    }
    //}

    /**
     * Moves to the next video in the playlist. If already at the end of the playlist, null will
     * be returned and the position will not change.
     *
     * @return The next video in the playlist.
     */
    public Video getNext() {
        if (mCurrentIndex >= 0 && (mCurrentIndex + 1) < mPlaylist.size()) {
            return mPlaylist.get(mCurrentIndex + 1);
        }

        return null;
    }

    /**
     * Moves to the previous video in the playlist. If the playlist is already at the beginning,
     * null will be returned and the position will not change.
     *
     * @return The previous video in the playlist.
     */
    public Video getPrevious() {
        if ((mCurrentIndex - 1) >= 0) {
            return mPlaylist.get(mCurrentIndex - 1);
        }

        return null;
    }

    public void setCurrent(Video video) {
        if (Video.isEmpty(video)) {
            return;
        }
        mRestoredQueue = false;

        int currentPosition = indexOfInstance(video);
        if (currentPosition < 0) currentPosition = mPlaylist.indexOf(video);

        if (currentPosition >= 0) {
            mCurrentIndex = currentPosition;
        } else {
            add(video);
            mCurrentIndex = mPlaylist.size() - 1;
        }
        notifyChanged();
    }

    public Video getCurrent() {
        if (mCurrentIndex < mPlaylist.size() && mCurrentIndex >= 0) {
            return mPlaylist.get(mCurrentIndex);
        }

        return null;
    }

    public List<Video> getAll() {
        return Collections.unmodifiableList(mPlaylist);
    }

    public List<Video> getAllReversed() {
        List<Video> reversed = new ArrayList<>(mPlaylist.size());
        for (int i = mPlaylist.size() - 1; i >= 0; i--) {
            reversed.add(mPlaylist.get(i));
        }

        return reversed;
    }

    public List<Video> getChangedItems() {
        return Collections.unmodifiableList(mSyncedItems);
    }

    public boolean hasNext() {
        return getNext() != null;
    }

    /** Retain access to a user-built queue after its final item starts, until it is cleared. */
    public boolean hasExplicitQueue() {
        return mHasExplicitQueue;
    }

    /** A saved cursor is selectable; restoring it never starts a player. */
    public boolean isRestoredQueue() {
        return mRestoredQueue;
    }

    /** Restore one complete bounded snapshot, without add/next routing side effects. */
    public void restoreQueue(List<Video> videos, int currentIndex) {
        if (videos == null || videos.isEmpty() || videos.size() > mPlaylistMaxSize
                || currentIndex < -1 || currentIndex >= videos.size()) return;
        for (Video video : videos) if (Video.isEmpty(video)) return;
        mPlaylist.clear();
        mPlaylist.addAll(videos);
        mCurrentIndex = currentIndex;
        mHasExplicitQueue = true;
        mRestoredQueue = true;
        notifyChanged();
    }

    public List<Video> getAllAfterCurrent() {
        if (mCurrentIndex == -1) {
            return mPlaylist;
        }

        int fromIndex = mCurrentIndex + 1;
        if (fromIndex > 0 && fromIndex < mPlaylist.size()) {
            return mPlaylist.subList(fromIndex, mPlaylist.size());
        }

        return null;
    }

    public void removeAllAfterCurrent() {
        if (mCurrentIndex == -1) {
            return;
        }

        int fromIndex = mCurrentIndex + 1;
        int size = mPlaylist.size();
        if (fromIndex > 0 && fromIndex < size) {
            //mPlaylist = mPlaylist.subList(0, fromIndex);
            mPlaylist.subList(fromIndex, size).clear();
            notifyChanged();
        }
    }

    /**
     * Trim playlist if one exceeds needed size or current element not last in the list
     */
    private void trimPlaylist() {
        int size = mPlaylist.size();
        boolean playlistTooBig = size > mPlaylistMaxSize;

        if (playlistTooBig) {
            int toIndex = size - mPlaylistMaxSize;
            mPlaylist.subList(0, toIndex).clear();
            mCurrentIndex -= toIndex;
        }
    }

    /**
     * Do some cleanup to prevent possible OOM exception
     */
    private void stripPrevItem() {
        if (mCurrentIndex == -1) {
            return;
        }

        int prevPosition = mCurrentIndex - 1;

        if (prevPosition < mPlaylist.size() && prevPosition >= 0) {
            Video prevItem = mPlaylist.get(prevPosition);
            if (prevItem != null) {
                //prevItem.mediaItem = null; // Don't remove: stores feedback tokens
                prevItem.nextMediaItem = null;
                prevItem.shuffleMediaItem = null;
            }
        }
    }

    private void replace(Video origin, Video newItem) {
        int index = mPlaylist.indexOf(origin);

        if (index != -1) {
            mPlaylist.set(index, newItem);
            notifyChanged();
        }
    }

    public void onNewSession() {
        mSyncedItems.clear();
    }

    /**
     * Since all items are clones (saves memory) we need to sync sometimes.
     */
    public void sync(Video origin) {
        if (origin == null) {
            return;
        }

        // Sync to maintain order. Item may be cloned.
        for (Video video : mPlaylist) {
            if (video.equals(origin)) {
                video.sync(origin);
                break;
            }
        }

        // Replace if exists. Item may be cloned.
        mSyncedItems.remove(origin);
        mSyncedItems.add(origin);
    }
}
