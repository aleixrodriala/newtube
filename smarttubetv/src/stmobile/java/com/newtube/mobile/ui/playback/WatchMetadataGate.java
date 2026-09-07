package com.newtube.mobile.ui.playback;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;

import java.util.Objects;

/** Keeps watch-page binding off each video's first-frame path and drops obsolete metadata. */
final class WatchMetadataGate {
    private String mVideoId;
    private MediaItemMetadata mPending;
    private boolean mReleased;

    void open(String videoId) {
        mVideoId = videoId;
        mPending = null;
        mReleased = false;
    }

    MediaItemMetadata offer(MediaItemMetadata metadata) {
        if (metadata == null || (metadata.getVideoId() != null
                && !Objects.equals(metadata.getVideoId(), mVideoId))) {
            return null;
        }
        if (mReleased) {
            return metadata;
        }
        mPending = metadata;
        return null;
    }

    /** The pending document is consumed once so large descriptions/suggestion trees are not pinned. */
    MediaItemMetadata release() {
        mReleased = true;
        MediaItemMetadata pending = mPending;
        mPending = null;
        return pending;
    }
}
