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
}
