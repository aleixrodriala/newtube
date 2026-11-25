package com.newtube.mobile.ui.common;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.core.text.BidiFormatter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

public final class VideoCardMetadata {
    private static final String SEPARATOR = " " + Video.TERTIARY_TEXT_DELIM + " ";

    private VideoCardMetadata() {
    }

    /** Bind the independent date and return the text for the original metadata line. */
    public static CharSequence bind(Video video, TextView dateView) {
        CharSequence metadata = video.getSecondTitle();
        if (TextUtils.isEmpty(metadata)) {
            metadata = video.getAuthor();
        }

        if (dateView == null) {
            return metadata;
        }

        String date = AppPrefs.instance(dateView.getContext()).isAlwaysShowUploadDateEnabled()
                ? video.getUploadDate() : null;
        dateView.setText(date);
        dateView.setVisibility(date == null ? View.GONE : View.VISIBLE);
        return date == null ? metadata : withoutDate(metadata, date);
    }

    // Identify a date by its structured value. The service wraps each field for bidi and
    // joins the fields with bullets. Match a whole field to preserve unrelated metadata.
    static CharSequence withoutDate(CharSequence metadata, String date) {
        if (TextUtils.isEmpty(metadata)) {
            return metadata;
        }
        String field = BidiFormatter.getInstance().unicodeWrap(date);
        if (TextUtils.equals(metadata, field)) {
            return null;
        }

        String text = metadata.toString();
        String trailingSeparator = field + SEPARATOR;
        if (text.startsWith(trailingSeparator)) {
            return metadata.subSequence(trailingSeparator.length(), metadata.length());
        }
        String leadingSeparator = SEPARATOR + field;
        if (text.endsWith(leadingSeparator)) {
            return metadata.subSequence(0, metadata.length() - leadingSeparator.length());
        }
        int index = text.indexOf(leadingSeparator + SEPARATOR);
        return index < 0 ? metadata : TextUtils.concat(metadata.subSequence(0, index),
                metadata.subSequence(index + leadingSeparator.length(), metadata.length()));
    }
}
