package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.util.LinkifyCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MetaSeparator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A selectable description in the player's existing sheet lifecycle; never owns playback. */
final class DescriptionSheet extends BottomSheetDialog {
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<![\\w:])(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})(?![\\w:])");
    private final TextView mTitle;
    private final TextView mInfo;
    private final TextView mChannel;
    private final TextView mBody;
    private final TextView mChapters;
    private final View mChaptersHeading;
    private final LongConsumer mSeek;

    DescriptionSheet(Activity activity, int height, LongConsumer seek) {
        super(activity, R.style.ThemeOverlay_NewTube_BottomSheetDialog_Description);
        setContentView(R.layout.sheet_mobile_description);
        mSeek = seek;
        mTitle = findViewById(R.id.description_title);
        mInfo = findViewById(R.id.description_info);
        mChannel = findViewById(R.id.description_channel);
        mBody = findViewById(R.id.description_text);
        mChapters = findViewById(R.id.description_chapters);
        mChaptersHeading = findViewById(R.id.description_chapters_heading);
        findViewById(R.id.description_close).setOnClickListener(v -> dismiss());
        getBehavior().setMaxHeight(height);
        // Scroll/selection gestures stay in the text; the header still drags the sheet down.
        getBehavior().setDraggableOnNestedScroll(false);
        View frame = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        android.view.ViewGroup.LayoutParams params = frame.getLayoutParams();
        params.height = height;
        frame.setLayoutParams(params);
    }

    void bind(Video video, @Nullable MediaItemMetadata metadata, CharSequence fallbackInfo,
              List<Video> chapters, long durationMs) {
        setText(mTitle, first(metadata != null ? metadata.getTitle() : null, video.getTitleFull()));
        setText(mChannel, first(metadata != null ? metadata.getAuthor() : null, video.getAuthor()));
        List<CharSequence> info = new ArrayList<>();
        if (metadata != null) {
            if (!TextUtils.isEmpty(metadata.getLikeCount())) {
                info.add(getContext().getString(R.string.mobile_comment_likes, metadata.getLikeCount()));
            }
            if (!TextUtils.isEmpty(metadata.getViewCount())) {
                info.add(metadata.getViewCount());
            }
            if (!TextUtils.isEmpty(metadata.getPublishedDate())) {
                info.add(metadata.getPublishedDate());
            }
        }
        setText(mInfo, info.isEmpty() ? fallbackInfo : TextUtils.join(MetaSeparator.DOT, info));
        String description = first(metadata != null ? metadata.getDescription() : null, video.description);
        setLinkedText(mBody, TextUtils.isEmpty(description)
                ? getContext().getString(R.string.description_not_found) : description, durationMs);
        boolean showChapters = !chapters.isEmpty() && DescriptionChapters.parse(description, durationMs).isEmpty();
        mChaptersHeading.setVisibility(showChapters ? View.VISIBLE : View.GONE);
        mChapters.setVisibility(showChapters ? View.VISIBLE : View.GONE);
        if (showChapters) {
            StringBuilder lines = new StringBuilder();
            for (Video chapter : chapters) {
                if (lines.length() > 0) lines.append('\n');
                lines.append(DateUtils.formatElapsedTime(chapter.startTimeMs / 1000)).append("  ").append(chapter.title);
            }
            setLinkedText(mChapters, lines.toString(), durationMs);
        }
    }

    private static String first(String preferred, String fallback) {
        return !TextUtils.isEmpty(preferred) ? preferred : fallback != null ? fallback : "";
    }

    private static void setText(TextView view, CharSequence text) {
        if (!view.hasSelection() && !TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private void setLinkedText(TextView view, String text, long durationMs) {
        if (!view.hasSelection() && !TextUtils.equals(view.getText(), text)) {
            view.setText(linkedText(text, durationMs, mSeek));
        }
    }

    static Spanned linkedText(String message, long durationMs, LongConsumer seek) {
        SpannableString text = new SpannableString(message);
        LinkifyCompat.addLinks(text, Linkify.WEB_URLS);
        Matcher matcher = TIMESTAMP.matcher(message);
        while (matcher.find()) {
            int minutes = Integer.parseInt(matcher.group(2));
            int seconds = Integer.parseInt(matcher.group(3));
            if (seconds >= 60 || (matcher.group(1) != null && minutes >= 60)
                    || text.getSpans(matcher.start(), matcher.end(), ClickableSpan.class).length > 0) {
                continue;
            }
            long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long positionMs = ((hours * 60 + minutes) * 60 + seconds) * 1000;
            if (durationMs > 0 && positionMs >= durationMs) {
                continue;
            }
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    seek.accept(positionMs);
                }
            }, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
