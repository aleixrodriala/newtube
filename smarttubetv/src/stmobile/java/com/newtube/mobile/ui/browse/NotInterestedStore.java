package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Persists video IDs dismissed via "Not interested" on Home and Shorts feeds so subsequent
 * scroll continuations, refreshes, and cached feed restores do not bring them back.
 */
final class NotInterestedStore {

    private static final String PREFS_NAME = "newtube_not_interested";
    private static final String KEY_VIDEO_IDS = "video_ids";
    static final int MAX_ENTRIES = 500;

    private static LinkedHashSet<String> sCachedIds;

    private NotInterestedStore() {
    }

    static boolean isRecommendationSection(int sectionId) {
        return sectionId == MediaGroup.TYPE_HOME || sectionId == MediaGroup.TYPE_SHORTS;
    }

    static synchronized void add(Context context, String videoId) {
        if (context == null || TextUtils.isEmpty(videoId)) {
            return;
        }
        LinkedHashSet<String> ids = ensureLoaded(context);
        ids.remove(videoId);
        ids.add(videoId);
        while (ids.size() > MAX_ENTRIES) {
            Iterator<String> it = ids.iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        persist(context, ids);
    }

    static synchronized boolean contains(Context context, String videoId) {
        if (context == null || TextUtils.isEmpty(videoId)) {
            return false;
        }
        return ensureLoaded(context).contains(videoId);
    }

    static synchronized void clear(Context context) {
        sCachedIds = new LinkedHashSet<>();
        if (context != null) {
            prefs(context).edit().remove(KEY_VIDEO_IDS).apply();
        }
    }

    static synchronized void resetInMemoryCacheForTest() {
        sCachedIds = null;
    }

    private static LinkedHashSet<String> ensureLoaded(Context context) {
        if (sCachedIds == null) {
            LinkedHashSet<String> loaded = new LinkedHashSet<>();
            String raw = prefs(context).getString(KEY_VIDEO_IDS, "");
            if (!TextUtils.isEmpty(raw)) {
                for (String token : raw.split("\n")) {
                    if (!TextUtils.isEmpty(token)) {
                        loaded.add(token);
                    }
                }
            }
            sCachedIds = loaded;
        }
        return sCachedIds;
    }

    private static void persist(Context context, LinkedHashSet<String> ids) {
        prefs(context).edit().putString(KEY_VIDEO_IDS, TextUtils.join("\n", ids)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
