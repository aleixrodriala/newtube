package com.newtube.mobile.ui.playback;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;

import java.util.List;
import java.util.Objects;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/** Per-video Watch Later state, using the same playlist service as the existing Save picker. */
final class WatchLaterAction {
    private final MediaItemService mService;
    private final Runnable mChanged;
    private final Runnable mFailed;
    private Disposable mRequest;
    private String mVideoId;
    private int mGeneration;
    private boolean mKnown;
    private boolean mBusy;
    private boolean mSaved;
    private boolean mAnyPlaylistSaved;

    WatchLaterAction(MediaItemService service, Runnable changed, Runnable failed) {
        mService = service;
        mChanged = changed;
        mFailed = failed;
    }

    boolean isBusy() { return mBusy; }
    boolean isKnown() { return mKnown; }
    boolean isSaved() { return mSaved; }
    boolean isAnyPlaylistSaved() { return mAnyPlaylistSaved; }

    void reset() {
        mGeneration++;
        if (mRequest != null) {
            mRequest.dispose();
        }
        mVideoId = null;
        mKnown = mBusy = mSaved = mAnyPlaylistSaved = false;
    }

    void load(String videoId) { request(videoId, false); }
    void toggle(String videoId) { request(videoId, true); }

    private void request(String videoId, boolean toggle) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        if (!Objects.equals(mVideoId, videoId)) {
            reset();
            mVideoId = videoId;
        }
        if (mBusy || (!toggle && mKnown)) {
            return;
        }
        mBusy = true;
        mChanged.run();
        int generation = mGeneration;
        // Re-read membership on a tap: another Save picker or device may have changed it.
        mRequest = mService.getPlaylistsInfoObserve(videoId).take(1)
                .switchIfEmpty(Observable.error(new IllegalStateException("No playlist information")))
                .flatMap(infos -> selection(infos, videoId, toggle))
                .subscribe(state -> {
                    if (generation != mGeneration) {
                        return;
                    }
                    mSaved = state.saved;
                    mAnyPlaylistSaved = state.anyPlaylistSaved;
                    mKnown = true;
                    mBusy = false;
                    mChanged.run();
                }, error -> {
                    if (generation == mGeneration) {
                        mBusy = false;
                        mChanged.run();
                        if (toggle) {
                            mFailed.run();
                        }
                    }
                });
    }

    private Observable<Selection> selection(List<PlaylistInfo> infos, String videoId, boolean toggle) {
        PlaylistInfo watchLater = null;
        boolean otherSelected = false;
        for (PlaylistInfo info : infos) {
            if ("WL".equals(info.getPlaylistId())) {
                watchLater = info;
            } else {
                otherSelected |= info.isSelected();
            }
        }
        if (watchLater == null) {
            return Observable.error(new IllegalStateException("Watch Later is unavailable"));
        }
        boolean selected = watchLater.isSelected();
        boolean saved = toggle ? !selected : selected;
        Selection result = new Selection(saved, saved || otherSelected);
        if (!toggle) {
            return Observable.just(result);
        }
        Observable<Void> edit = selected
                ? mService.removeFromPlaylistObserve(watchLater.getPlaylistId(), videoId)
                : mService.addToPlaylistObserve(watchLater.getPlaylistId(), videoId);
        // Void services complete without emitting: only mark saved after the edit succeeds.
        return edit.ignoreElements().andThen(Observable.just(result));
    }

    private static final class Selection {
        final boolean saved;
        final boolean anyPlaylistSaved;

        Selection(boolean saved, boolean anyPlaylistSaved) {
            this.saved = saved;
            this.anyPlaylistSaved = anyPlaylistSaved;
        }
    }
}
