package com.newtube.mobile.ui.common;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide stale-while-revalidate snapshots of the feed grids, keyed by section id.
 * The shared presenters (BrowsePresenter etc.) keep only Observable factories, so every
 * activity recreation refetches from the network into an EMPTY grid behind a spinner.
 * This cache lets a grid repaint its previous snapshot instantly while that refetch runs;
 * the fresh result then replaces it through the normal update path. In-memory only -
 * survives activity recreation, not process death. It is a visual stopgap, never a data
 * source: nothing reads it to decide what exists, only what to PAINT first.
 */
public final class FeedCache {
    private static final int MAX_ITEMS_PER_SECTION = 120;
    private static final Map<Integer, List<Video>> sSnapshots = new HashMap<>();

    private FeedCache() {
    }

    public static synchronized void put(int sectionId, List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        int count = Math.min(videos.size(), MAX_ITEMS_PER_SECTION);
        sSnapshots.put(sectionId, new ArrayList<>(videos.subList(0, count)));
    }

    /** Snapshot copy, or null when the section was never loaded this process. */
    public static synchronized List<Video> get(int sectionId) {
        List<Video> cached = sSnapshots.get(sectionId);
        return cached == null ? null : new ArrayList<>(cached);
    }

    /** Drops everything - call when the signed-in account changes (feeds are per-account). */
    public static synchronized void clear() {
        sSnapshots.clear();
    }
}
