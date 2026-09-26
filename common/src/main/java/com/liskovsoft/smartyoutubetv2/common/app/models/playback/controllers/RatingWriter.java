package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.rx.RxHelper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.core.Observable;

/**
 * NEWTUBE(phone): the phone's rating writes (like, dislike, remove), sent one at a time in tap
 * order. RxHelper runs every call on its own pooled thread, so like, then dislike, then Undo could
 * reach YouTube in any order and leave a rating the screen does not show. Every write asks for an
 * absolute rating, so sending them in order leaves the last tap's.
 *
 * <p>A write that fails rolls the screen back to the last rating YouTube did take for that video,
 * unless a later tap for the same video is already queued - that one decides instead. Callbacks
 * arrive on the thread the calls deliver on (RxHelper: the main thread), where writes come from.</p>
 */
final class RatingWriter {
    static final int NONE = 0;
    static final int LIKE = 1;
    static final int DISLIKE = 2;

    interface Rollback {
        /** Put {@code videoId}'s rating on screen back to {@code rating}; the write failed. */
        void to(String videoId, int rating);
    }

    private static final class Write {
        final String videoId;
        final int rating;
        final Observable<Void> call;
        final Rollback rollback;

        Write(String videoId, int rating, Observable<Void> call, Rollback rollback) {
            this.videoId = videoId;
            this.rating = rating;
            this.call = call;
            this.rollback = rollback;
        }
    }

    private final ArrayDeque<Write> mQueue = new ArrayDeque<>();
    /** Per video with writes in flight: the rating YouTube last took (or had before the first). */
    private final Map<String, Integer> mConfirmed = new HashMap<>();
    private boolean mBusy;

    /**
     * @param before the rating on screen before this tap
     * @param after  the rating this write asks YouTube for
     * @param call   the not-yet-subscribed service call
     */
    void write(String videoId, int before, int after, Observable<Void> call, Rollback rollback) {
        if (!mConfirmed.containsKey(videoId)) {
            mConfirmed.put(videoId, before);
        }
        mQueue.add(new Write(videoId, after, call, rollback));
        if (!mBusy) {
            next();
        }
    }

    private void next() {
        Write write = mQueue.poll();
        if (write == null) {
            mBusy = false;
            mConfirmed.clear(); // the next tap reads its "before" from the screen again
            return;
        }
        mBusy = true;
        RxHelper.execute(write.call, null,
                error -> {
                    if (!isQueued(write.videoId)) {
                        Integer confirmed = mConfirmed.get(write.videoId);
                        write.rollback.to(write.videoId, confirmed != null ? confirmed : NONE);
                    }
                    next();
                },
                () -> {
                    mConfirmed.put(write.videoId, write.rating);
                    next();
                });
    }

    private boolean isQueued(String videoId) {
        for (Write queued : mQueue) {
            if (queued.videoId.equals(videoId)) {
                return true;
            }
        }
        return false;
    }
}
