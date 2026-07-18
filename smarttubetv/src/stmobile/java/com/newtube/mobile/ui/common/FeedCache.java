package com.newtube.mobile.ui.common;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

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
 * survives activity recreation, not process death. Since the browse TTL, a repainted
 * snapshot can be the CURRENT content (no refetch follows), so the snapshot must stay
 * paginatable — see the group retention below.
 */
public final class FeedCache {
    private static final int MAX_ITEMS_PER_SECTION = 120;
    private static final Map<Integer, List<Video>> sSnapshots = new HashMap<>();
    /**
     * Video.group is a WeakReference (upstream memory-leak fix) and the adapter holds only
     * Videos, so once a load's rx chain ends nothing keeps the VideoGroups alive. After any
     * GC a repainted snapshot would answer getGroup()==null and scroll-end pagination dies
     * (continueGroup needs the group's nextPageKey). Pin the snapshot's distinct groups for
     * exactly as long as the snapshot itself lives: replaced on put(), dropped on clear().
     */
    private static final Map<Integer, List<VideoGroup>> sSnapshotGroups = new HashMap<>();

    private FeedCache() {
    }

    public static synchronized void put(int sectionId, List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        int count = Math.min(videos.size(), MAX_ITEMS_PER_SECTION);
        List<Video> snapshot = new ArrayList<>(videos.subList(0, count));
        sSnapshots.put(sectionId, snapshot);

        List<VideoGroup> groups = new ArrayList<>();
        for (Video video : snapshot) {
            VideoGroup group = video.getGroup();
            if (group != null && !containsIdentity(groups, group)) {
                groups.add(group);
            }
        }
        sSnapshotGroups.put(sectionId, groups);
    }

    private static boolean containsIdentity(List<VideoGroup> groups, VideoGroup group) {
        for (VideoGroup existing : groups) {
            if (existing == group) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot copy, or null when the section was never loaded this process. */
    public static synchronized List<Video> get(int sectionId) {
        List<Video> cached = sSnapshots.get(sectionId);
        return cached == null ? null : new ArrayList<>(cached);
    }

    /** Drops everything - call when the signed-in account changes (feeds are per-account). */
    public static synchronized void clear() {
        sSnapshots.clear();
        sSnapshotGroups.clear();
    }
}
