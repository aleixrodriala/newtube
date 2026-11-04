package com.newtube.mobile.ui.playback;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned, bounded public-video metadata only; never serialize Video's tokens or URLs. */
final class QueueSnapshot {
    static final int MAX_ITEMS = 300;
    static final int MAX_BYTES = 512 * 1024;
    final List<Video> videos;
    final int current;

    QueueSnapshot(List<Video> videos, int current) { this.videos = videos; this.current = current; }

    static QueueSnapshot capture(Playlist queue, Set<String> privateIds) {
        List<Video> copy = new ArrayList<>();
        int current = -1;
        if (queue.hasExplicitQueue() || queue.hasNext()) {
            int index = 0;
            for (Video video : queue.getAll()) {
                if (copy.size() >= MAX_ITEMS) break;
                if (video != null && validId(video.videoId) && !video.isLocal() && !video.isRemote
                        && !video.incognito && !privateIds.contains(video.videoId)) {
                    if (index == queue.getCurrentIndex()) current = copy.size();
                    Video item = Video.from(video.videoId);
                    item.title = bounded(video.getTitle(), 300);
                    item.author = bounded(video.getAuthor(), 128);
                    item.badge = bounded(video.badge, 32);
                    item.isLive = video.isLive;
                    item.isUpcoming = video.isUpcoming;
                    copy.add(item);
                }
                index++;
            }
        }
        return new QueueSnapshot(copy, current);
    }

    byte[] encode() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1); root.put("current", current);
        JSONArray items = new JSONArray();
        for (Video v : videos) {
            JSONObject item = new JSONObject();
            item.put("id", v.videoId); item.put("title", bounded(v.title, 300));
            item.put("author", bounded(v.author, 128)); item.put("badge", bounded(v.badge, 32));
            item.put("live", v.isLive); item.put("upcoming", v.isUpcoming);
            items.put(item);
        }
        root.put("items", items);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new JSONException("Queue too large");
        return bytes;
    }

    static QueueSnapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        try {
            // The parser must consume the complete file, including trailing bytes.
            org.json.JSONTokener tokener = new org.json.JSONTokener(new String(bytes, StandardCharsets.UTF_8));
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) return null;
            JSONObject root = (JSONObject) parsed;
            if (!(root.get("version") instanceof Integer) || root.getInt("version") != 1
                    || !(root.get("current") instanceof Integer)) return null;
            Object raw = root.get("items");
            if (!(raw instanceof JSONArray)) return null;
            JSONArray items = (JSONArray) raw;
            int cursor = root.getInt("current");
            if (items.length() > MAX_ITEMS || cursor < -1 || cursor >= items.length()) return null;
            List<Video> videos = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                Object id = item.get("id");
                if (!(id instanceof String) || !validId((String) id)) return null;
                Video v = Video.from((String) id);
                v.title = text(item, "title", 300); v.author = text(item, "author", 128);
                v.badge = text(item, "badge", 32);
                if (!(item.get("live") instanceof Boolean) || !(item.get("upcoming") instanceof Boolean)) return null;
                v.isLive = item.getBoolean("live"); v.isUpcoming = item.getBoolean("upcoming");
                v.cardImageUrl = "https://i.ytimg.com/vi/" + v.videoId + "/hqdefault.jpg";
                videos.add(v);
            }
            return new QueueSnapshot(videos, cursor);
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(JSONObject item, String key, int max) throws JSONException {
        Object value = item.get(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw new JSONException("Invalid queue text");
        return (String) value;
    }
    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }
    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }
}
