package com.newtube.mobile.ui.playback;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Description fallback only; chapters supplied by YouTube always take precedence. */
final class DescriptionChapters {
    private static final Pattern LINE = Pattern.compile(
            "(?m)^\\h*(\\d{1,3}:\\d{2}(?::\\d{2})?)\\h+(?:[-–—]\\h*)?(.+?)\\h*$");

    private DescriptionChapters() {
    }

    static List<Video> parse(String description, long durationMs) {
        if (description == null) {
            return Collections.emptyList();
        }
        List<Video> chapters = new ArrayList<>();
        Matcher matcher = LINE.matcher(description);
        long previous = -1;
        while (matcher.find()) {
            String[] parts = matcher.group(1).split(":");
            long seconds = 0;
            for (int i = 0; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (i > 0 && part >= 60) {
                    return Collections.emptyList();
                }
                seconds = seconds * 60 + part;
            }
            long start = seconds * 1000;
            if ((chapters.isEmpty() && start != 0) || start <= previous
                    || (durationMs > 0 && start >= durationMs)) {
                return Collections.emptyList();
            }
            Video chapter = new Video();
            chapter.isChapter = true;
            chapter.startTimeMs = start;
            chapter.title = matcher.group(2).trim();
            if (chapter.title.isEmpty()) {
                return Collections.emptyList();
            }
            chapter.badge = matcher.group(1);
            chapters.add(chapter);
            previous = start;
        }
        return chapters.size() >= 2 ? chapters : Collections.emptyList();
    }
}
