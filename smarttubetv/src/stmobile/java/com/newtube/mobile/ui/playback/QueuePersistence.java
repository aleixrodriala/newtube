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
import java.util.function.Supplier;
import java.util.HashSet;
import java.util.Set;

/** Process-owned listener: coalesce main-thread edits, write immutable snapshots in FIFO order. */
public final class QueuePersistence {
    private static QueuePersistence instance;
    private final Playlist queue;
    private final QueueStorage store;
    private String scope;
    private boolean switching;
    private final Handler main;
    private final Executor writer;
    private final BooleanSupplier privateMode;
    private final Supplier<String> privateVideoId;
    private final Runnable changed = this::onChanged;
    private final Runnable save = this::saveNow;
    private boolean pending;
    private boolean started;
    private final Set<String> privateIds = new HashSet<>();
    private final Set<String> knownIds = new HashSet<>();
    private final java.util.LinkedHashMap<String, byte[]> latest = new java.util.LinkedHashMap<>();
    private boolean writing;
    private final java.util.Map<String, byte[]> failed = new java.util.HashMap<>();

    public static void install(Context context) {
        if (instance != null) return;
        File directory = context.getNoBackupFilesDir();
        try {
            QueueAccountIdentity identity = new QueueAccountIdentity(directory);
            String initialScope = identity.initialScope(context);
            QueueStorage storage;
            try {
                if (directory == null) throw new java.io.IOException("Private storage unavailable");
                storage = new QueueAccountStorage(directory, initialScope);
            } catch (java.io.IOException | RuntimeException unavailable) {
                Log.w("QueuePersistence", "Queue storage unavailable; using session isolation");
                storage = new QueueStorage() {
                    private final java.util.Map<String, byte[]> memory = new java.util.HashMap<>();
                    public synchronized QueueSnapshot read(String scope) {
                        byte[] bytes = memory.get(scope);
                        return bytes != null ? QueueSnapshot.decode(bytes) : null;
                    }
                    public synchronized void write(String scope, byte[] bytes) { memory.put(scope, bytes); }
                };
            }
            instance = new QueuePersistence(Playlist.instance(), storage, initialScope,
                    new Handler(Looper.getMainLooper()), Executors.newSingleThreadExecutor(task -> {
                        Thread thread = new Thread(task, "queue-storage"); thread.setDaemon(true); return thread;
                    }), PlaybackPresenter::isIncognitoPlayback, PlaybackPresenter::getIncognitoVideoId);
            instance.start();
            com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance()
                    .addAccountListener(account -> {
                        // Service notifications are posted through an IO scheduler and may arrive late.
                        // Resolve the live selection on main, never a captured outgoing notification.
                        Runnable switchAccount = () -> instance.switchScope(identity.scope(
                                com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount()),
                                QueuePersistence::closePlayback);
                        if (Looper.myLooper() == Looper.getMainLooper()) switchAccount.run();
                        else instance.main.post(switchAccount);
                    });
        } catch (RuntimeException error) {
            Log.w("QueuePersistence", "Account queue storage unavailable");
        }
    }

    private static void closePlayback() {
        PlaybackPresenter.prepareQueueAccountChange();
        com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView view = PlaybackPresenter.getExistingPlaybackView();
        if (view instanceof MobilePlaybackActivity) ((MobilePlaybackActivity) view).closeForAccountChange();
        PlaybackPresenter.clearQueueSession();
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.QueuePlaybackMode.clear();
    }

    QueuePersistence(Playlist queue, QueueSnapshotStore store, Handler main, Executor writer,
            BooleanSupplier privateMode, Supplier<String> privateVideoId) {
        this(queue, new QueueStorage() {
            public QueueSnapshot read(String scope) { return store.read(); }
            public void write(String scope, byte[] bytes) throws java.io.IOException { store.write(bytes); }
        }, "test", main, writer, privateMode, privateVideoId);
    }

    QueuePersistence(Playlist queue, QueueStorage store, String scope, Handler main, Executor writer,
            BooleanSupplier privateMode, Supplier<String> privateVideoId) {
        this.queue = queue; this.store = store; this.scope = scope; this.main = main;
        this.writer = writer; this.privateMode = privateMode; this.privateVideoId = privateVideoId;
    }

    /** Main-thread boundary: persist outgoing state before retiring playback and replacing the list. */
    void switchScope(String next, Runnable closePlayback) {
        if (!started || scope.equals(next)) return;
        main.removeCallbacks(save); pending = false;
        saveNow(); // private sessions leave their last normal snapshot intact
        switching = true;
        try {
            closePlayback.run(); // cancel old engine/network callbacks before exposing the next account
            scope = next;
            privateIds.clear(); knownIds.clear();
            queue.clear();
            QueueSnapshot snapshot = readScope();
            if (snapshot != null && !snapshot.videos.isEmpty()) queue.restoreQueue(snapshot.videos, snapshot.current);
            rememberIds();
        } finally { switching = false; }
    }

    private QueueSnapshot readScope() {
        byte[] bytes;
        synchronized (this) { bytes = latest.containsKey(scope) ? latest.get(scope) : failed.get(scope); }
        // Includes an in-flight write. A -> B -> A cannot reload stale disk contents.
        return bytes != null ? QueueSnapshot.decode(bytes) : store.read(scope);
    }

    private void rememberIds() {
        knownIds.clear();
        for (Video video : queue.getAll()) if (video != null) knownIds.add(video.videoId);
    }

    void start() {
        if (started) return;
        boolean alreadySelected = !queue.getAll().isEmpty();
        // Load once before the first Activity. Never replace a selection already made in memory.
        if (queue.getAll().isEmpty()) {
            QueueSnapshot snapshot = readScope();
            if (snapshot != null && !snapshot.videos.isEmpty()) queue.restoreQueue(snapshot.videos, snapshot.current);
        }
        rememberIds();
        started = true; queue.addListener(changed);
        if (alreadySelected) onChanged();
    }

    private void onChanged() {
        if (switching) return;
        boolean isPrivate = privateMode.getAsBoolean();
        String currentPrivateId = isPrivate ? privateVideoId.get() : null;
        Set<String> liveIds = new HashSet<>();
        for (Video video : queue.getAll()) {
            if (video == null) continue;
            liveIds.add(video.videoId);
            // Untouched normal rows are not private merely because they remain in the list.
            // Exclude the private open itself and entries newly queued during that session.
            if (video.incognito || (isPrivate && (!knownIds.contains(video.videoId)
                    || (video.videoId != null && video.videoId.equals(currentPrivateId))))) {
                privateIds.add(video.videoId);
            }
        }
        knownIds.clear();
        knownIds.addAll(liveIds);
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
                latest.put(scope, bytes); // one pending snapshot per account, one worker overall
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
            String target;
            synchronized (this) {
                if (latest.isEmpty()) { writing = false; return; }
                java.util.Map.Entry<String, byte[]> entry = latest.entrySet().iterator().next();
                target = entry.getKey(); bytes = entry.getValue();
            }
            boolean saved = false;
            try { store.write(target, bytes); saved = true; }
            catch (java.io.IOException | RuntimeException error) {
                Log.w("QueuePersistence", "Could not save queue; previous complete snapshot retained");
            }
            synchronized (this) {
                if (saved) failed.remove(target);
                else failed.put(target, bytes); // preserve session state even when storage is full
                if (latest.get(target) == bytes) latest.remove(target);
            }
        }
    }

    /** Tests/host teardown; submitted immutable writes may finish without retaining any Views. */
    void stop() {
        started = false; queue.removeListener(changed); main.removeCallbacks(save); pending = false;
    }
}
