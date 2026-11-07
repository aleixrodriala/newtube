package com.newtube.mobile.ui.common;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * NEWTUBE(feed-avatars): the channel avatar of a Home or Subscriptions card. Those feeds come from
 * YouTube's TV client, whose tiles carry no avatar, and a signed-in Home tile not even its channel
 * (only its long-press menu names it). So a card's avatar is worked out in up to two steps, each
 * answered by the cheapest source that has it:
 * <ol>
 * <li>video -> channel: the tile itself (Subscriptions, topic feeds), else this cache, else the
 * tile's context menu ({@link ContentService#getChannelIdFromMenu}, ~1 KB). A video's channel never
 * changes, so this is kept for good (LRU-capped).</li>
 * <li>channel -> avatar: the Subscriptions response, which lists every subscribed channel with its
 * avatar ({@link ContentService#getSubscribedChannelAvatars}; no request), else this cache, else
 * the channel's YouTube Music header ({@link ContentService#getChannelAvatarUrl}, a few KB). An
 * avatar older than {@link #AVATAR_TTL_MS} is looked up again (and shown meanwhile); every
 * Subscriptions response renews its channels' for free.</li>
 * </ol>
 * Both caches live on disk, so a channel or a video is looked up once, not on every launch.
 * Lookups run only for cards being shown, {@link #LOOKUP_THREADS} at a time and newest first, so
 * the cards on screen resolve before the ones flown past; they go out without the account's token.
 * A channel without an avatar is remembered for {@link #MISSING_TTL_MS}; a failed or refused
 * lookup is not remembered, only retried after {@link #RETRY_AFTER_MS}. Nothing here is per
 * account, so an account change keeps it all.
 */
public final class ChannelAvatars {
    private static final String TAG = ChannelAvatars.class.getSimpleName();

    /**
     * Told on the main thread whose avatar changed: channel ids, and video ids whose channel was
     * just found. The set holds {@link #ALL} when every card should look again (the disk copy has
     * just been read). Held weakly: a screen that goes away needs no unregistering.
     */
    public interface Listener {
        void onAvatarsChanged(Set<String> ids);
    }

    public static final String ALL = "*";

    private static final String AVATARS_FILE = "channel_avatars.tsv";
    private static final String VIDEOS_FILE = "video_channels.tsv";
    /** Channels kept: a long subscription list plus the channels Home has shown. */
    private static final int MAX_AVATARS = 3000;
    /** Videos kept: several Home pages' worth. */
    private static final int MAX_VIDEOS = 1500;
    private static final long AVATAR_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_TTL_MS = 3L * 24 * 60 * 60 * 1000;
    private static final long RETRY_AFTER_MS = 60_000;
    /** Consecutive failed lookups that pause that kind of lookup for {@link #PAUSE_MS} (offline, throttled). */
    private static final int FAILURES_BEFORE_PAUSE = 3;
    private static final long PAUSE_MS = 30_000;
    private static final int LOOKUP_THREADS = 2;
    /** Pending lookups kept; older ones (cards long scrolled past) are dropped. */
    private static final int MAX_PENDING = 40;
    private static final long WRITE_DELAY_MS = 3_000;
    private static final long NOTIFY_DELAY_MS = 50;
    /** A pending lookup of a video's channel; any other pending key is a channel id. */
    private static final String VIDEO_KEY = "v:";
    /** A video whose menu names no channel. */
    private static final String NO_CHANNEL = "";

    private static final class Avatar {
        final String url; // null = the channel has no avatar
        final long savedAtMs; // wall clock: it outlives the process

        Avatar(String url, long savedAtMs) {
            this.url = url;
            this.savedAtMs = savedAtMs;
        }
    }

    private static final LinkedHashMap<String, Avatar> sAvatars = lru(MAX_AVATARS);
    /** videoId -> channelId (or {@link #NO_CHANNEL}), for cards that do not carry their channel. */
    private static final LinkedHashMap<String, String> sVideoChannels = lru(MAX_VIDEOS);
    /** The menus of the videos waiting in {@link #sPending}. */
    private static final Map<String, FeedbackEndpoint> sVideoMenus = new HashMap<>();
    private static final Set<String> sInFlight = new HashSet<>();
    private static final Map<String, Long> sFailedAtMs = new HashMap<>();
    private static final LinkedBlockingDeque<String> sPending = new LinkedBlockingDeque<>();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** One writer at a time, so two snapshots never share the temp file or land out of order. */
    private static final ExecutorService sWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ChannelAvatarsWrite");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> sChanged = new HashSet<>();
    private static Context sAppContext;
    private static boolean sLoaded;
    private static boolean sWriteScheduled;
    private static boolean sWorkersStarted;
    /** Per stage (the menus of {@link #STAGE_MENU}, the avatars of {@link #STAGE_AVATAR}): two endpoints. */
    private static final int STAGE_MENU = 0;
    private static final int STAGE_AVATAR = 1;
    private static final int[] sFailureStreak = new int[2];
    private static final long[] sPausedUntilMs = new long[2];
    /** The subscription map last merged in (the service hands back the same map until a new response). */
    private static Map<String, String> sMergedSubscriptions;

    private ChannelAvatars() {
    }

    private static <V> LinkedHashMap<String, V> lru(int max) {
        return new LinkedHashMap<String, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    /** Call once from Application.onCreate: reads the disk copy off the main thread. */
    public static void init(Context context) {
        synchronized (ChannelAvatars.class) {
            sAppContext = context.getApplicationContext();
        }
        Thread reader = new Thread(ChannelAvatars::readFiles, "ChannelAvatarsRead");
        reader.setDaemon(true);
        reader.start();
    }

    public static void addListener(Listener listener) {
        for (WeakReference<Listener> ref : sListeners) {
            Listener registered = ref.get();
            if (registered == null) {
                sListeners.remove(ref);
            } else if (registered == listener) {
                return;
            }
        }
        sListeners.add(new WeakReference<>(listener));
    }

    public static void removeListener(Listener listener) {
        for (WeakReference<Listener> ref : sListeners) {
            Listener registered = ref.get();
            if (registered == null || registered == listener) {
                sListeners.remove(ref);
            }
        }
    }

    /** The card's channel: its own, or the one its menu named. Null while unknown. */
    public static synchronized String channelIdOf(Video video) {
        if (video.channelId != null) {
            return video.channelId;
        }
        String channelId = video.videoId != null ? sVideoChannels.get(video.videoId) : null;
        return NO_CHANNEL.equals(channelId) ? null : channelId;
    }

    /**
     * The card's avatar URL, or null when none is known yet (then {@link #request} it). An
     * expired one is still returned: it shows while {@link #request} refreshes it.
     */
    public static synchronized String get(Video video) {
        String channelId = channelIdOf(video);
        if (channelId == null) {
            return null;
        }
        mergeSubscriptions();
        Avatar avatar = sAvatars.get(channelId);
        return avatar != null ? avatar.url : null;
    }

    /**
     * Works out the card's avatar unless it is known and fresh, already under way, or recently
     * failed. Listeners hear when it arrives.
     */
    public static synchronized void request(Video video) {
        if (!sLoaded) {
            return; // before the disk copy is read, a miss is not a miss: readFiles() notifies ALL
        }
        String channelId = channelIdOf(video);
        if (channelId != null) {
            requestChannel(channelId);
            return;
        }
        FeedbackEndpoint menu = video.videoId != null && video.mediaItem != null ? video.mediaItem.getFeedbackEndpoint() : null;
        if (menu == null || sVideoChannels.containsKey(video.videoId)) {
            return; // nothing to find the channel with, or its menu named none
        }
        String key = VIDEO_KEY + video.videoId;
        if (enqueue(key)) {
            sVideoMenus.put(key, menu);
        }
    }

    private static void requestChannel(String channelId) {
        mergeSubscriptions();
        if (!isFresh(sAvatars.get(channelId))) {
            enqueue(channelId);
        }
    }

    /** Puts {@code key} at the head of the queue; false when it is under way or recently failed. */
    private static boolean enqueue(String key) {
        if (sInFlight.contains(key) || isCoolingDown(key)) {
            return false;
        }
        sPending.remove(key);
        sPending.addFirst(key); // newest first: the card just bound is on screen
        trimPending();
        startWorkers();
        return true;
    }

    private static void trimPending() {
        while (sPending.size() > MAX_PENDING) {
            String dropped = sPending.pollLast();
            if (dropped != null && !sPending.contains(dropped)) {
                sVideoMenus.remove(dropped);
            }
        }
    }

    private static boolean isCoolingDown(String key) {
        Long failedAt = sFailedAtMs.get(key);
        return failedAt != null && SystemClock.uptimeMillis() - failedAt < RETRY_AFTER_MS;
    }

    /**
     * The avatar image at {@code px} square, cropped: YouTube's avatar URLs take their size and crop
     * after the last '=' (an artist photo is a wide banner, so the crop matters). Other URLs are
     * returned as they are.
     */
    public static String sized(String url, int px) {
        if (url == null || !(url.contains("ggpht.com/") || url.contains("googleusercontent.com/"))) {
            return url;
        }
        int options = url.lastIndexOf('=');
        String base = options > url.lastIndexOf('/') ? url.substring(0, options) : url;
        return base + "=s" + px + "-c-k-c0x00ffffff-no-rj";
    }

    private static boolean isFresh(Avatar avatar) {
        return avatar != null
                && System.currentTimeMillis() - avatar.savedAtMs < (avatar.url != null ? AVATAR_TTL_MS : MISSING_TTL_MS);
    }

    /** Folds the last Subscriptions response's channel list in, once per response. */
    private static void mergeSubscriptions() {
        Map<String, String> subscriptions;
        try {
            subscriptions = contentService().getSubscribedChannelAvatars();
        } catch (RuntimeException e) {
            return;
        }
        if (subscriptions == null || subscriptions == sMergedSubscriptions) {
            return;
        }
        sMergedSubscriptions = subscriptions;
        long now = System.currentTimeMillis();
        int changed = 0;
        for (Map.Entry<String, String> channel : subscriptions.entrySet()) {
            Avatar old = sAvatars.get(channel.getKey());
            sAvatars.put(channel.getKey(), new Avatar(channel.getValue(), now)); // renewed either way
            if (old == null || !channel.getValue().equals(old.url)) {
                markChanged(channel.getKey());
                changed++;
            }
        }
        NetPath.log("channel-avatar subscriptions channels=" + subscriptions.size() + " changed=" + changed);
        scheduleWrite();
    }

    private static void startWorkers() {
        if (sWorkersStarted) {
            return;
        }
        sWorkersStarted = true;
        for (int i = 0; i < LOOKUP_THREADS; i++) {
            Thread worker = new Thread(ChannelAvatars::lookupLoop, "ChannelAvatarLookup" + i);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void lookupLoop() {
        while (true) {
            String key;
            try {
                key = sPending.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            FeedbackEndpoint menu = null;
            long pauseMs;
            synchronized (ChannelAvatars.class) {
                boolean video = key.startsWith(VIDEO_KEY);
                int stage = video ? STAGE_MENU : STAGE_AVATAR;
                // The other worker may have taken (or failed) the same key since it was queued, or a
                // Subscriptions response answered it.
                boolean answered = video
                        ? sVideoChannels.containsKey(key.substring(VIDEO_KEY.length()))
                        : isFresh(sAvatars.get(key));
                if (answered || sInFlight.contains(key) || isCoolingDown(key)) {
                    if (!sPending.contains(key)) {
                        sVideoMenus.remove(key);
                    }
                    continue;
                }
                pauseMs = sPausedUntilMs[stage] - SystemClock.uptimeMillis();
                if (pauseMs > 0) {
                    // Kept for after the pause (its menu stays in sVideoMenus), unless a newer bind
                    // has queued it again meanwhile.
                    if (!sPending.contains(key)) {
                        sPending.offerLast(key);
                        trimPending();
                    }
                } else {
                    menu = video ? sVideoMenus.remove(key) : null;
                    if (video && menu == null) {
                        continue;
                    }
                    sInFlight.add(key);
                }
            }
            if (pauseMs > 0) {
                SystemClock.sleep(pauseMs);
                continue;
            }
            if (menu != null) {
                lookupVideo(key, menu);
            } else {
                lookupChannel(key);
            }
        }
    }

    private static void lookupVideo(String key, FeedbackEndpoint menu) {
        String videoId = key.substring(VIDEO_KEY.length());
        long startMs = SystemClock.uptimeMillis();
        String channelId = null;
        boolean failed = false;
        try {
            channelId = contentService().getChannelIdFromMenu(menu);
        } catch (RuntimeException e) {
            failed = true;
            Log.d(TAG, "Channel lookup failed for %s: %s", videoId, e.getMessage());
        }
        long tookMs = SystemClock.uptimeMillis() - startMs;
        synchronized (ChannelAvatars.class) {
            sInFlight.remove(key);
            if (failed) {
                onLookupFailed(key, STAGE_MENU);
            } else {
                sFailureStreak[STAGE_MENU] = 0;
                sFailedAtMs.remove(key);
                sVideoChannels.put(videoId, channelId != null ? channelId : NO_CHANNEL);
                scheduleWrite();
                if (channelId != null) {
                    markChanged(videoId); // the card can open its channel directly now
                    requestChannel(channelId);
                }
            }
        }
        NetPath.log("channel-avatar menu video=" + videoId + " ms=" + tookMs
                + (failed ? " failed" : " channel=" + channelId));
    }

    private static void lookupChannel(String channelId) {
        long startMs = SystemClock.uptimeMillis();
        long startedAtMs = System.currentTimeMillis();
        String url = null;
        boolean failed = false;
        try {
            url = contentService().getChannelAvatarUrl(channelId);
        } catch (RuntimeException e) {
            failed = true;
            Log.d(TAG, "Avatar lookup failed for %s: %s", channelId, e.getMessage());
        }
        long tookMs = SystemClock.uptimeMillis() - startMs;
        synchronized (ChannelAvatars.class) {
            sInFlight.remove(channelId);
            if (failed) {
                onLookupFailed(channelId, STAGE_AVATAR);
            } else {
                sFailureStreak[STAGE_AVATAR] = 0;
                sFailedAtMs.remove(channelId);
                Avatar old = sAvatars.get(channelId);
                // A Subscriptions response that landed while this was in flight is newer: it stays.
                if (old == null || old.savedAtMs < startedAtMs) {
                    sAvatars.put(channelId, new Avatar(url, System.currentTimeMillis()));
                    if (old == null ? url != null : url == null ? old.url != null : !url.equals(old.url)) {
                        markChanged(channelId);
                    }
                    scheduleWrite();
                }
            }
        }
        NetPath.log("channel-avatar lookup channel=" + channelId + " ms=" + tookMs
                + (failed ? " failed" : url != null ? " found" : " none"));
    }

    private static void onLookupFailed(String key, int stage) {
        sFailedAtMs.put(key, SystemClock.uptimeMillis());
        if (++sFailureStreak[stage] >= FAILURES_BEFORE_PAUSE) {
            sFailureStreak[stage] = 0;
            sPausedUntilMs[stage] = SystemClock.uptimeMillis() + PAUSE_MS;
        }
    }

    private static void markChanged(String id) {
        boolean first = sChanged.isEmpty();
        sChanged.add(id);
        if (first) {
            sMain.postDelayed(ChannelAvatars::notifyChanged, NOTIFY_DELAY_MS); // one post per batch
        }
    }

    private static void notifyChanged() {
        Set<String> changed;
        synchronized (ChannelAvatars.class) {
            if (sChanged.isEmpty()) {
                return;
            }
            changed = new HashSet<>(sChanged);
            sChanged.clear();
        }
        for (WeakReference<Listener> ref : sListeners) {
            Listener listener = ref.get();
            if (listener == null) {
                sListeners.remove(ref);
            } else {
                listener.onAvatarsChanged(changed);
            }
        }
    }

    private static ContentService contentService() {
        return YouTubeServiceManager.instance().getContentService();
    }

    // ------------------------------------------------------------------------------------------
    // Disk, oldest first. channel_avatars.tsv: "channelId<TAB>url<TAB>savedAtMs" (url empty =
    // none). video_channels.tsv: "videoId<TAB>channelId" (channelId empty = the menu named none).
    // ------------------------------------------------------------------------------------------

    private static void readFiles() {
        Context context;
        synchronized (ChannelAvatars.class) {
            context = sAppContext;
        }
        long startMs = SystemClock.uptimeMillis();
        List<String[]> avatars = readRows(new File(context.getFilesDir(), AVATARS_FILE), 3);
        List<String[]> videos = readRows(new File(context.getFilesDir(), VIDEOS_FILE), 2);
        synchronized (ChannelAvatars.class) {
            for (String[] fields : avatars) {
                if (sAvatars.containsKey(fields[0])) {
                    continue; // something newer arrived while reading
                }
                try {
                    sAvatars.put(fields[0], new Avatar(fields[1].isEmpty() ? null : fields[1], Long.parseLong(fields[2])));
                } catch (NumberFormatException e) {
                    // a torn line: skip it
                }
            }
            for (String[] fields : videos) {
                if (!sVideoChannels.containsKey(fields[0])) {
                    sVideoChannels.put(fields[0], fields[1]);
                }
            }
            sLoaded = true;
            // Cards bound before the read finished showed no avatar and asked for nothing.
            markChanged(ALL);
        }
        NetPath.log("channel-avatar cache-read avatars=" + avatars.size() + " videos=" + videos.size()
                + " ms=" + (SystemClock.uptimeMillis() - startMs));
    }

    private static List<String[]> readRows(File file, int fieldCount) {
        List<String[]> rows = new ArrayList<>();
        if (!file.isFile()) {
            return rows;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length == fieldCount && !fields[0].isEmpty()) {
                    rows.add(fields);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Read failed for %s: %s", file.getName(), e.getMessage());
            rows.clear();
        }
        return rows;
    }

    private static void scheduleWrite() {
        if (sWriteScheduled || sAppContext == null) {
            return;
        }
        sWriteScheduled = true;
        sMain.postDelayed(() -> sWriter.execute(ChannelAvatars::writeFiles), WRITE_DELAY_MS);
    }

    private static void writeFiles() {
        Context context;
        StringBuilder avatars = new StringBuilder();
        StringBuilder videos = new StringBuilder();
        synchronized (ChannelAvatars.class) {
            sWriteScheduled = false;
            context = sAppContext;
            for (Map.Entry<String, Avatar> channel : sAvatars.entrySet()) {
                Avatar avatar = channel.getValue();
                avatars.append(channel.getKey()).append('\t')
                        .append(avatar.url != null ? avatar.url : "").append('\t')
                        .append(avatar.savedAtMs).append('\n');
            }
            for (Map.Entry<String, String> video : sVideoChannels.entrySet()) {
                videos.append(video.getKey()).append('\t').append(video.getValue()).append('\n');
            }
        }
        writeFile(new File(context.getFilesDir(), AVATARS_FILE), avatars);
        writeFile(new File(context.getFilesDir(), VIDEOS_FILE), videos);
    }

    private static void writeFile(File target, CharSequence content) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(content.toString());
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Write failed for %s: %s", target.getName(), e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
