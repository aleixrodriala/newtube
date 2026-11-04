package com.newtube.mobile.ui.playback;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import java.util.Map;

/** A manual queue is identified by its type, not a remote playlist ID or the contents of /next. */
final class QueueCardPolicy {
    private QueueCardPolicy() { }

    static boolean sameItems(java.util.List<Video> first, java.util.List<Video> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (first.get(i) != second.get(i)) return false;
        return true;
    }

    static Integer localGroupId(Video current, Map<Integer, VideoGroup> groups) {
        Playlist queue = Playlist.instance();
        Video playing = queue.getCurrent();
        if (current == null || current.videoId == null || playing == null
                || !current.videoId.equals(playing.videoId)
                || (!queue.hasNext() && !keepsExplicitQueue(current))) return null;
        for (Map.Entry<Integer, VideoGroup> entry : groups.entrySet()) {
            VideoGroup group = entry.getValue();
            if (group != null && group.getType() == MediaGroup.TYPE_PLAYBACK_QUEUE) return entry.getKey();
        }
        return null;
    }

    static boolean keepsExplicitQueue(Video current) {
        Playlist queue = Playlist.instance();
        Video playing = queue.getCurrent();
        return queue.hasExplicitQueue() && !queue.getAll().isEmpty() && current != null
                && current.videoId != null && playing != null
                && current.videoId.equals(playing.videoId);
    }
}
