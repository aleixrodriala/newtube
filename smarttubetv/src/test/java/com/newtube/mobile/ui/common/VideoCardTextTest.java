package com.newtube.mobile.ui.common;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class VideoCardTextTest {
    @Test
    public void gridCardsWrapTitleAndDetailsAndRestoreCompactLimits() {
        verifyLineLimits(R.layout.item_video_card, R.id.video_title, R.id.video_meta);
    }

    @Test
    public void relatedRowsWrapTitleAndDetailsAndRestoreCompactLimits() {
        verifyLineLimits(R.layout.item_mobile_related_video, R.id.related_title, R.id.related_subtitle);
    }

    private void verifyLineLimits(int layout, int titleId, int detailsId) {
        Application context = RuntimeEnvironment.getApplication();
        AppPrefs prefs = AppPrefs.instance(context);
        View card = LayoutInflater.from(new ContextThemeWrapper(context, R.style.Theme_NewTube))
                .inflate(layout, null);
        TextView title = card.findViewById(titleId);
        TextView details = card.findViewById(detailsId);
        title.setText("A particularly long video title that needs several lines to fit on a narrow card");
        details.setText("A particularly long channel name • 125K views • 3 weeks ago");
        try {
            VideoCardText.applyLineLimits(title, details);
            layoutCard(card);
            int compactHeight = card.getHeight();
            assertTruncated(title, 2);
            assertTruncated(details, 1);

            prefs.setFullCardTextEnabled(true);
            VideoCardText.applyLineLimits(title, details);
            layoutCard(card);
            assertExpanded(title, 2);
            assertExpanded(details, 1);
            assertTrue(card.getHeight() > compactHeight);

            prefs.setFullCardTextEnabled(false);
            VideoCardText.applyLineLimits(title, details);
            layoutCard(card);
            assertEquals(compactHeight, card.getHeight());
            assertTruncated(title, 2);
            assertTruncated(details, 1);
        } finally {
            prefs.setFullCardTextEnabled(false);
        }
    }

    private static void assertTruncated(TextView view, int lines) {
        assertEquals(lines, view.getLineCount());
        assertTrue(view.getLayout().getEllipsisCount(lines - 1) > 0);
    }

    private static void assertExpanded(TextView view, int compactLines) {
        int lines = view.getLineCount();
        assertTrue(lines > compactLines);
        assertEquals(view.length(), view.getLayout().getLineEnd(lines - 1));
        for (int line = 0; line < lines; line++) {
            assertEquals(0, view.getLayout().getEllipsisCount(line));
        }
    }

    private static void layoutCard(View card) {
        int width = Math.round(260 * card.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, width, card.getMeasuredHeight());
    }
}
