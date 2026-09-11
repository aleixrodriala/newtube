package com.newtube.mobile.downloads;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The process-wide list of downloads, persisted as one JSON file under the app's files dir.
 *
 * <p>Everything the Downloads screen and the notification show comes from here; the service
 * mutates items and calls {@link #notifyChanged}. Listeners are always called on the main
 * thread. Writes are coalesced onto a single background thread.
 *
 * <p>An item found DOWNLOADING or PROCESSING when the registry loads was interrupted by a
 * process death: it is marked failed-retryable, and its part files keep whatever was fetched
 * so a Retry resumes rather than restarts.
 */
public final class DownloadRegistry {
    private static final String TAG = DownloadRegistry.class.getSimpleName();
    private static final String FILE_NAME = "registry.json";

    public interface Listener {
        void onDownloadsChanged();
    }

    private static volatile DownloadRegistry sInstance;

    private final Context mContext;
    private final List<DownloadItem> mItems = new ArrayList<>();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "newtube-downloads-registry");
        t.setDaemon(true);
        return t;
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mNotify = this::dispatchChanged;
    private boolean mNotifyPending;

    public static DownloadRegistry instance(Context context) {
        if (sInstance == null) {
            synchronized (DownloadRegistry.class) {
                if (sInstance == null) {
                    sInstance = new DownloadRegistry(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private DownloadRegistry(Context context) {
        mContext = context;
        load();
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Temporary parts live in the cache dir: the system may reclaim them, a Retry re-fetches. */
    public static File partsDir(Context context) {
        File dir = new File(context.getCacheDir(), "downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public synchronized List<DownloadItem> items() {
        return new ArrayList<>(mItems);
    }

    /** Newest first, the order the screen shows. */
    public synchronized List<DownloadItem> itemsNewestFirst() {
        List<DownloadItem> result = new ArrayList<>(mItems);
        java.util.Collections.reverse(result);
        return result;
    }

    @Nullable
    public synchronized DownloadItem find(String id) {
        for (DownloadItem item : mItems) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** A finished download of this video, if any - so the picker can say "already downloaded". */
    @Nullable
    public synchronized DownloadItem findDone(String videoId, int kind) {
        for (DownloadItem item : mItems) {
            if (item.isDone() && item.videoId.equals(videoId) && item.kind == kind) {
                return item;
            }
        }
        return null;
    }

    /** The queued/running download of this video, if any - video file or audio track alike. */
    @Nullable
    public synchronized DownloadItem findActive(String videoId) {
        for (DownloadItem item : mItems) {
            if (item.isActive() && item.videoId.equals(videoId)) {
                return item;
            }
        }
        return null;
    }

    @Nullable
    public synchronized DownloadItem nextQueued() {
        for (DownloadItem item : mItems) {
            if (item.state == DownloadItem.STATE_QUEUED && !item.cancelRequested) {
                return item;
            }
        }
        return null;
    }

    public synchronized boolean hasActive() {
        for (DownloadItem item : mItems) {
            if (item.isActive()) {
                return true;
            }
        }
        return false;
    }

    public void add(DownloadItem item) {
        synchronized (this) {
            mItems.add(item);
        }
        notifyChanged();
    }

    public void remove(DownloadItem item) {
        synchronized (this) {
            mItems.remove(item);
        }
        notifyChanged();
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /** Persists and tells listeners; safe from any thread, coalesced per main-loop turn. */
    public void notifyChanged() {
        persist();
        synchronized (mNotify) {
            if (mNotifyPending) {
                return;
            }
            mNotifyPending = true;
        }
        mMain.post(mNotify);
    }

    private void dispatchChanged() {
        synchronized (mNotify) {
            mNotifyPending = false;
        }
        for (Listener listener : mListeners) {
            listener.onDownloadsChanged();
        }
    }

    // ---------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------

    private File file() {
        return new File(dir(mContext), FILE_NAME);
    }

    private void load() {
        File file = file();
        if (!file.exists()) {
            return;
        }

        try {
            String text = readAll(file);
            JSONArray array = new JSONObject(text).optJSONArray("items");
            if (array == null) {
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                DownloadItem item = DownloadItem.fromJson(array.getJSONObject(i));
                if (item.state == DownloadItem.STATE_DOWNLOADING || item.state == DownloadItem.STATE_PROCESSING
                        || item.state == DownloadItem.STATE_QUEUED) {
                    // Interrupted by a process death: nothing is running it any more.
                    item.state = DownloadItem.STATE_FAILED;
                    item.error = null;
                }
                mItems.add(item);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "registry unreadable, starting empty: " + e);
        }
    }

    private static String readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void persist() {
        final String snapshot;
        try {
            JSONArray array = new JSONArray();
            synchronized (this) {
                for (DownloadItem item : mItems) {
                    array.put(item.toJson());
                }
            }
            snapshot = new JSONObject().put("items", array).toString();
        } catch (JSONException e) {
            Log.e(TAG, "registry not persisted: " + e);
            return;
        }

        mWriter.execute(() -> {
            File target = file();
            File tmp = new File(target.getParentFile(), FILE_NAME + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                writer.write(snapshot);
            } catch (IOException e) {
                Log.e(TAG, "registry write failed: " + e);
                return;
            }
            if (!tmp.renameTo(target)) {
                Log.e(TAG, "registry rename failed");
            }
        });
    }
}
