package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One entry of the Downloads list, persisted by {@link DownloadRegistry}.
 *
 * <p>The stream URLs are deliberately NOT persisted: googlevideo links expire after a few hours
 * and are bound to the network they were minted on. A job that outlives the process (or a Retry)
 * resolves the itags again from a fresh format info.
 */
public final class DownloadItem {
    public static final int STATE_QUEUED = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_PROCESSING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_FAILED = 4;

    public final String id;
    public final String videoId;
    public final String title;
    @Nullable public final String author;
    @Nullable public final String thumbUrl;
    /** {@link DownloadOption#KIND_VIDEO} or {@link DownloadOption#KIND_AUDIO}. */
    public final int kind;
    @Nullable public final String qualityLabel;
    @Nullable public final String videoItag;
    @Nullable public final String audioItag;
    public final long createdAt;

    public volatile int state = STATE_QUEUED;
    public volatile long bytesDone;
    public volatile long bytesTotal = -1;
    @Nullable public volatile String outputUri;
    @Nullable public volatile String fileName;
    @Nullable public volatile String thumbPath;
    public volatile long durationMs;
    /** Short, user-facing failure reason (already localized) or null. */
    @Nullable public volatile String error;

    // Transient: the URLs of the enqueue moment, reused while they are fresh.
    @Nullable public transient volatile String videoUrl;
    @Nullable public transient volatile String audioUrl;
    public transient volatile long videoLength = -1;
    public transient volatile long audioLength = -1;
    public transient volatile boolean cancelRequested;

    public DownloadItem(String id, String videoId, String title, @Nullable String author,
                        @Nullable String thumbUrl, int kind, @Nullable String qualityLabel,
                        @Nullable String videoItag, @Nullable String audioItag, long createdAt) {
        this.id = id;
        this.videoId = videoId;
        this.title = title;
        this.author = author;
        this.thumbUrl = thumbUrl;
        this.kind = kind;
        this.qualityLabel = qualityLabel;
        this.videoItag = videoItag;
        this.audioItag = audioItag;
        this.createdAt = createdAt;
    }

    public static DownloadItem create(String videoId, String title, @Nullable String author,
                                      @Nullable String thumbUrl, DownloadOption option) {
        DownloadItem item = new DownloadItem(UUID.randomUUID().toString(), videoId, title, author, thumbUrl,
                option.kind, option.qualityLabel,
                option.video != null ? option.video.getITag() : null,
                option.audio != null ? option.audio.getITag() : null,
                System.currentTimeMillis());
        item.videoUrl = option.video != null ? option.video.getUrl() : null;
        item.audioUrl = option.audio != null ? option.audio.getUrl() : null;
        item.videoLength = DownloadOptions.lengthOf(option.video);
        item.audioLength = DownloadOptions.lengthOf(option.audio);
        item.bytesTotal = option.totalBytes;
        return item;
    }

    public boolean isAudioOnly() {
        return kind == DownloadOption.KIND_AUDIO;
    }

    public boolean isActive() {
        return state == STATE_QUEUED || state == STATE_DOWNLOADING || state == STATE_PROCESSING;
    }

    public boolean isDone() {
        return state == STATE_DONE;
    }

    public boolean isFailed() {
        return state == STATE_FAILED;
    }

    /** 0..100, or -1 while the total is unknown. */
    public int progressPercent() {
        long total = bytesTotal;
        if (total <= 0) {
            return -1;
        }
        return (int) Math.min(100, bytesDone * 100 / total);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("videoId", videoId);
        json.put("title", title);
        json.putOpt("author", author);
        json.putOpt("thumbUrl", thumbUrl);
        json.put("kind", kind);
        json.putOpt("qualityLabel", qualityLabel);
        json.putOpt("videoItag", videoItag);
        json.putOpt("audioItag", audioItag);
        json.put("createdAt", createdAt);
        json.put("state", state);
        json.put("bytesDone", bytesDone);
        json.put("bytesTotal", bytesTotal);
        json.putOpt("outputUri", outputUri);
        json.putOpt("fileName", fileName);
        json.putOpt("thumbPath", thumbPath);
        json.put("durationMs", durationMs);
        json.putOpt("error", error);
        return json;
    }

    public static DownloadItem fromJson(JSONObject json) throws JSONException {
        DownloadItem item = new DownloadItem(
                json.getString("id"), json.getString("videoId"), json.optString("title", ""),
                optString(json, "author"), optString(json, "thumbUrl"), json.optInt("kind", DownloadOption.KIND_VIDEO),
                optString(json, "qualityLabel"), optString(json, "videoItag"), optString(json, "audioItag"),
                json.optLong("createdAt", 0));
        item.state = json.optInt("state", STATE_FAILED);
        item.bytesDone = json.optLong("bytesDone", 0);
        item.bytesTotal = json.optLong("bytesTotal", -1);
        item.outputUri = optString(json, "outputUri");
        item.fileName = optString(json, "fileName");
        item.thumbPath = optString(json, "thumbPath");
        item.durationMs = json.optLong("durationMs", 0);
        item.error = optString(json, "error");
        return item;
    }

    @Nullable
    private static String optString(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }
}
