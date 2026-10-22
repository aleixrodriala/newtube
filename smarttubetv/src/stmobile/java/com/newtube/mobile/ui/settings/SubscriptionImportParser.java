package com.newtube.mobile.ui.settings;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NEWTUBE(import): the pure part of {@link SubscriptionImport}: reading a NewPipe or PipePipe
 * subscriptions export, and writing the YouTube channels in it back out in the one shape the
 * engine's importer reads ({@code NewPipeService} in MediaServiceCore).
 *
 * <p>Both apps write the same JSON ({@code {"subscriptions": [{"service_id", "url", "name"}]}}) and
 * keep the same {@code subscriptions} table in the {@code newpipe.db} of their backup zip. Their
 * other services (SoundCloud, PeerTube, PipePipe's BiliBili and NicoNico...) are left out: the
 * engine can only follow YouTube channels, and a non-YouTube URL would come back as an empty row.</p>
 */
final class SubscriptionImportParser {

    /** One subscription, as NewPipe/PipePipe store it. */
    static final class Channel {
        final String url;
        final String name;

        Channel(@NonNull String url, @Nullable String name) {
            this.url = url;
            this.name = name;
        }
    }

    private SubscriptionImportParser() {
    }

    /** True for a zip file (NewPipe's and PipePipe's "Export database"): it starts with "PK\3\4". */
    static boolean isZip(@NonNull byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /**
     * The YouTube channels of a NewPipe/PipePipe subscriptions JSON, or null when {@code json} is not
     * one (GrayJay and PocketTube files: the engine reads those as they are).
     */
    @Nullable
    static List<Channel> parseJson(@NonNull String json) {
        JSONArray subscriptions;
        try {
            Object root = new JSONTokener(json).nextValue();
            if (!(root instanceof JSONObject)) {
                return null;
            }
            subscriptions = ((JSONObject) root).optJSONArray("subscriptions");
        } catch (JSONException | RuntimeException e) {
            return null;
        }
        if (subscriptions == null) {
            return null;
        }

        List<Channel> result = new ArrayList<>();
        for (int i = 0; i < subscriptions.length(); i++) {
            JSONObject object = subscriptions.optJSONObject(i);
            if (object == null) {
                continue;
            }
            String url = string(object, "url");
            if (url != null) {
                result.add(new Channel(url, string(object, "name")));
            }
        }
        return youTubeOnly(result);
    }

    /**
     * The YouTube channels of {@code channels}, each once, in their order. Judged by the URL rather
     * than {@code service_id}: YouTube is 0 in both apps today, but the URL says it for certain.
     */
    @NonNull
    static List<Channel> youTubeOnly(@NonNull List<Channel> channels) {
        Map<String, Channel> unique = new LinkedHashMap<>();
        for (Channel channel : channels) {
            String id = channelId(channel.url);
            if (id != null && !unique.containsKey(id)) {
                unique.put(id, new Channel("https://www.youtube.com/channel/" + id, channel.name));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * The {@code UC...} id of a YouTube channel URL, or null. NewPipe and PipePipe always store the
     * {@code /channel/<id>} form; a handle or {@code /user/} URL can't be followed without a lookup.
     */
    @Nullable
    static String channelId(@Nullable String url) {
        if (url == null) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(url.trim());
        } catch (RuntimeException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
            return null;
        }
        List<String> path = uri.getPathSegments();
        if (path.size() >= 2 && "channel".equals(path.get(0))) {
            String id = path.get(1);
            if (id.startsWith("UC") && id.length() >= 20) {
                return id;
            }
        }
        return null;
    }

    /** {@code channels} as a NewPipe subscriptions file, which the engine's NewPipe importer reads. */
    @NonNull
    static String toNewPipeJson(@NonNull List<Channel> channels) {
        try {
            JSONArray array = new JSONArray();
            for (Channel channel : channels) {
                JSONObject item = new JSONObject();
                item.put("service_id", 0);
                item.put("url", channel.url);
                item.put("name", channel.name != null ? channel.name : "");
                array.put(item);
            }
            JSONObject root = new JSONObject();
            root.put("app_version", "NewTube import");
            root.put("app_version_int", 0);
            root.put("subscriptions", array);
            return root.toString();
        } catch (JSONException e) { // only for NaN numbers, which none of these are
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private static String string(@NonNull JSONObject object, @NonNull String key) {
        Object value = object.opt(key);
        return value instanceof String || value instanceof Number ? String.valueOf(value) : null;
    }
}
