package com.newtube.mobile.ui.common;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide stale-while-revalidate snapshots of the feed grids, keyed by section id.
 * The shared presenters (BrowsePresenter etc.) keep only Observable factories, so every
 * activity recreation refetches from the network into an EMPTY grid behind a spinner.
 * This cache lets a grid repaint its previous snapshot instantly while that refetch runs;
 * the fresh result then replaces it through the normal update path. Since the browse TTL,
 * a repainted snapshot can be the CURRENT content (no refetch follows), so the in-memory
 * snapshot must stay paginatable — see the group retention below.
 *
 * DISK LAYER (round 2): the sections are additionally persisted on Browse onStop and
 * restored on the first in-memory miss of the process, so even a COLD start paints cards
 * immediately. A disk-restored {@link Video} has no live {@link VideoGroup} behind it
 * (Video.group is a WeakReference that serialization can't capture), so a restored
 * snapshot is DISPLAY-ONLY until the cold-start refetch replaces it — which always
 * happens, because the presenter's browse TTL is in-memory and empty on a fresh process.
 * The snapshots implicitly belong to the signed-in account: {@link #clear()} (wired to
 * the account-change listener) wipes memory AND disk, so whatever is on disk was written
 * by the currently selected account.
 */
public final class FeedCache {
    private static final String TAG = FeedCache.class.getSimpleName();
    private static final int MAX_ITEMS_PER_SECTION = 120;
    /** Disk cap: a couple of screenfuls — enough to kill the skeleton, cheap to parse. */
    private static final int MAX_PERSISTED_PER_SECTION = 40;
    private static final String SNAPSHOT_DIR = "feed_snapshots";
    private static final Map<Integer, List<Video>> sSnapshots = new HashMap<>();
    /**
     * Video.group is a WeakReference (upstream memory-leak fix) and the adapter holds only
     * Videos, so once a load's rx chain ends nothing keeps the VideoGroups alive. After any
     * GC a repainted snapshot would answer getGroup()==null and scroll-end pagination dies
     * (continueGroup needs the group's nextPageKey). Pin the snapshot's distinct groups for
     * exactly as long as the snapshot itself lives: replaced on put(), dropped on clear().
     */
    private static final Map<Integer, List<VideoGroup>> sSnapshotGroups = new HashMap<>();
    /** One disk attempt per section per process — a missing/corrupt file shouldn't be re-read. */
    private static final Set<Integer> sDiskRestoreTried = new HashSet<>();
    private static volatile Context sAppContext;

    private FeedCache() {
    }

    /** Call once from Application.onCreate — the disk layer needs a context for filesDir. */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
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

    /**
     * Like {@link #get} but falls through to the persisted snapshot on the first in-memory
     * miss (the cold-start path). The restored list is cached in memory so section switches
     * before the refetch lands don't re-read disk, and it is display-only (see class doc).
     */
    public static synchronized List<Video> getOrRestore(int sectionId) {
        List<Video> cached = sSnapshots.get(sectionId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        if (sAppContext == null || !sDiskRestoreTried.add(sectionId)) {
            return null;
        }
        List<Video> restored = readSnapshotFile(sectionId);
        if (restored == null || restored.isEmpty()) {
            return null;
        }
        sSnapshots.put(sectionId, restored);
        Log.d(TAG, "Restored %s videos from disk for section %s", restored.size(), sectionId);
        return new ArrayList<>(restored);
    }

    /**
     * Persist every in-memory section to disk (called from Browse onStop — once per
     * backgrounding, which is also the last moment before most process deaths).
     * Serialization runs on a background thread; Video field reads are racy in theory but
     * benign (plain strings/ints, and the videos are no longer being mutated by a load).
     */
    public static void persist() {
        if (sAppContext == null) {
            return;
        }
        final Map<Integer, List<Video>> work = new HashMap<>();
        synchronized (FeedCache.class) {
            for (Map.Entry<Integer, List<Video>> entry : sSnapshots.entrySet()) {
                List<Video> videos = entry.getValue();
                int count = Math.min(videos.size(), MAX_PERSISTED_PER_SECTION);
                work.put(entry.getKey(), new ArrayList<>(videos.subList(0, count)));
            }
        }
        if (work.isEmpty()) {
            return;
        }
        Thread writer = new Thread(() -> {
            for (Map.Entry<Integer, List<Video>> entry : work.entrySet()) {
                writeSnapshotFile(entry.getKey(), entry.getValue());
            }
        }, "FeedSnapshotWriter");
        writer.setDaemon(true);
        writer.start();
    }

    /** Drops everything - call when the signed-in account changes (feeds are per-account). */
    public static synchronized void clear() {
        sSnapshots.clear();
        sSnapshotGroups.clear();
        sDiskRestoreTried.clear();
        final Context context = sAppContext;
        if (context == null) {
            return;
        }
        Thread cleaner = new Thread(() -> {
            File[] files = snapshotDir(context).listFiles();
            if (files != null) {
                for (File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }, "FeedSnapshotCleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private static void writeSnapshotFile(int sectionId, List<Video> videos) {
        Context context = sAppContext;
        if (context == null || videos.isEmpty()) {
            return;
        }
        File dir = snapshotDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(dir, sectionId + ".tmp");
        File target = new File(dir, sectionId + ".snap");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            out.write(Helpers.mergeList(videos)); // one Video.toString per item, %AR%-joined
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Snapshot write failed for section %s: %s", sectionId, e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static List<Video> readSnapshotFile(int sectionId) {
        Context context = sAppContext;
        if (context == null) {
            return null;
        }
        File file = new File(snapshotDir(context), sectionId + ".snap");
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] raw = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < raw.length) {
                    int read = in.read(raw, off, raw.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
            }
            String content = new String(raw, StandardCharsets.UTF_8);
            List<Video> result = new ArrayList<>();
            for (String spec : Helpers.splitArray(content)) {
                Video video = Video.fromString(spec);
                // Feed cards are videos/playlists/channels; an all-null husk means corruption.
                if (video != null && (video.videoId != null || video.playlistId != null || video.channelId != null)) {
                    result.add(video);
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Snapshot read failed for section %s: %s", sectionId, e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private static File snapshotDir(Context context) {
        return new File(context.getFilesDir(), SNAPSHOT_DIR);
    }
}
