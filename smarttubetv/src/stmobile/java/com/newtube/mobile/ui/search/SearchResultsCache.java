package com.newtube.mobile.ui.search;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide snapshot of the latest search results so returning to Search after the Activity
 * was recreated while a video played restores the list in place instead of reloading from the
 * network.
 */
final class SearchResultsCache {

    static final class Entry {
        final String query;
        final List<Video> videos;
        final List<VideoGroup> groups;

        Entry(String query, List<Video> videos, List<VideoGroup> groups) {
            this.query = query;
            this.videos = new ArrayList<>(videos);
            this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
        }
    }

    @Nullable
    private static Entry sEntry;

    private SearchResultsCache() {
    }

    static synchronized void put(String query, List<Video> videos, @Nullable List<VideoGroup> groups) {
        if (TextUtils.isEmpty(query) || videos == null || videos.isEmpty()) {
            return;
        }
        sEntry = new Entry(query, videos, groups);
    }

    @Nullable
    static synchronized Entry get(String query) {
        if (sEntry == null || TextUtils.isEmpty(query) || !query.equals(sEntry.query)) {
            return null;
        }
        return new Entry(sEntry.query, sEntry.videos, sEntry.groups);
    }

    static synchronized void clear() {
        sEntry = null;
    }
}
