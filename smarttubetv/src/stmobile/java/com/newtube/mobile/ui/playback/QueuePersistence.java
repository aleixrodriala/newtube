package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.HashSet;
import java.util.Set;

/** Process-owned listener: coalesce main-thread edits, write immutable snapshots in FIFO order. */
public final class QueuePersistence {
    private static QueuePersistence instance;
    private final Playlist queue;
    private final QueueSnapshotStore store;
    private final Handler main;
    private final Executor writer;
    private final BooleanSupplier privateMode;
    private final Runnable changed = this::onChanged;
    private final Runnable save = this::saveNow;
    private boolean pending;
    private boolean started;
    private final Set<String> privateIds = new HashSet<>();
    private byte[] latest;
    private boolean writing;

    public static void install(Context context) {
        if (instance != null) return;
        File directory = context.getNoBackupFilesDir();
        if (directory == null) { Log.w("QueuePersistence", "Private queue storage unavailable"); return; }
        instance = new QueuePersistence(Playlist.instance(),
                new QueueSnapshotStore(new File(directory, "mobile-queue-v1.json")),
                new Handler(Looper.getMainLooper()), Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "queue-storage"); thread.setDaemon(true); return thread;
                }), PlaybackPresenter::isIncognitoPlayback);
        instance.start();
    }

    QueuePersistence(Playlist queue, QueueSnapshotStore store, Handler main, Executor writer,
            BooleanSupplier privateMode) {
        this.queue = queue; this.store = store; this.main = main;
        this.writer = writer; this.privateMode = privateMode;
    }

    void start() {
        if (started) return;
        boolean alreadySelected = !queue.getAll().isEmpty();
        // Load once before the first Activity. Never replace a selection already made in memory.
        if (queue.getAll().isEmpty()) {
            QueueSnapshot snapshot = store.read();
            if (snapshot != null && !snapshot.videos.isEmpty()) queue.restoreQueue(snapshot.videos, snapshot.current);
        }
        started = true; queue.addListener(changed);
        if (alreadySelected) onChanged();
    }

    private void onChanged() {
        boolean isPrivate = privateMode.getAsBoolean();
        Set<String> liveIds = new HashSet<>();
        for (Video video : queue.getAll()) {
            if (video == null) continue;
            liveIds.add(video.videoId);
            if (isPrivate || video.incognito) privateIds.add(video.videoId);
        }
        // Bound remembered private IDs to the current in-memory list. Incognito consumes its
        // routing flag, so retain these exclusions after the private player has finished too.
        privateIds.retainAll(liveIds);
        if (isPrivate) { main.removeCallbacks(save); pending = false; return; }
        if (!pending) { pending = true; main.post(save); }
    }

    private void saveNow() {
        pending = false;
        if (!started || privateMode.getAsBoolean()) return;
        try {
            byte[] bytes = QueueSnapshot.capture(queue, privateIds).encode();
            synchronized (this) {
                latest = bytes; // one pending snapshot, even if storage is temporarily slow
                if (writing) return;
                writing = true;
            }
            try { writer.execute(this::drain); }
            catch (RuntimeException error) {
                synchronized (this) { writing = false; }
                throw error;
            }
        } catch (org.json.JSONException | RuntimeException error) {
            Log.w("QueuePersistence", "Could not capture queue");
        }
    }

    private void drain() {
        while (true) {
            byte[] bytes;
            synchronized (this) {
                bytes = latest; latest = null;
                if (bytes == null) { writing = false; return; }
            }
            try { store.write(bytes); }
            catch (java.io.IOException | RuntimeException error) {
                Log.w("QueuePersistence", "Could not save queue; previous complete snapshot retained");
            }
        }
    }

    /** Tests/host teardown; submitted immutable writes may finish without retaining any Views. */
    void stop() {
        started = false; queue.removeListener(changed); main.removeCallbacks(save); pending = false;
    }
}
