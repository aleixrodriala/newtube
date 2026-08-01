package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

public interface PlaybackView extends PlayerManager {
    void showProgressBar(boolean show);

    /**
     * NEWTUBE(mobile-ttff): deliver the single {@link MediaItemMetadata} that
     * {@code SuggestionsController} already loads to the View, so the touch watch-header can bind from
     * it instead of firing a duplicate {@code getMetadataObserve}. No-op default -> TV
     * (PlaybackFragment / PlaybackActivity / EmbedPlayerView) behavior is unchanged; only
     * {@code MobilePlaybackActivity} overrides it.
     */
    default void onWatchMetadata(MediaItemMetadata metadata) {}

    /**
     * NEWTUBE(offline-ux): a PERSISTENT status line over the video box, or {@code null} to clear it.
     * The dead state after a lost connection has no other honest surface: {@code setTitle} is
     * overwritten by the metadata bind of every recovery reload, and a toast is gone in a few
     * seconds while the outage lasts minutes. Set when playback stops for good, cleared only when
     * it genuinely resumes or the user moves on - it deliberately stays up across retries.
     */
    default void showPlaybackNotice(String message) {}
}
