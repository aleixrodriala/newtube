package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.os.SystemClock;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DescriptionSheetTest {
    private int clicks;

    @Test
    public void linkTapsStayDistinctFromLongPressSelectionAndScrolling() {
        SelectableLinkTextView view = linkView();
        touch(view, MotionEvent.ACTION_DOWN, 1, 0);
        touch(view, MotionEvent.ACTION_UP, 1, 80);
        assertEquals(1, clicks);
        float outside = view.getLayout().getLineRight(0) + 30;
        touch(view, MotionEvent.ACTION_DOWN, outside, 0);
        touch(view, MotionEvent.ACTION_UP, outside, 80);
        assertEquals(1, clicks);
        touch(view, MotionEvent.ACTION_DOWN, 1, 0);
        touch(view, MotionEvent.ACTION_UP, 1, ViewConfiguration.getLongPressTimeout() + 100);
        Selection.setSelection((Spannable) view.getText(), 0, 4);
        touch(view, MotionEvent.ACTION_DOWN, 1, 0);
        touch(view, MotionEvent.ACTION_UP, 1, 80);
        Selection.removeSelection((Spannable) view.getText());
        touch(view, MotionEvent.ACTION_DOWN, 1, 0);
        touch(view, MotionEvent.ACTION_MOVE, 100, 50);
        touch(view, MotionEvent.ACTION_UP, 1, 100);
        assertEquals(1, clicks);
        assertTrue(view.isTextSelectable());
        assertEquals(0, view.getAutoLinkMask());
    }

    @Test
    public void timestampsSeekWithoutChangingUrlsOrLinkingInvalidTimes() {
        String message = "0:00 Intro\n1:02 Basics\n1:01:03 Advanced\nhttps://example.com/4:20\n"
                + "1:70 invalid\n99:59:59 outside the video";
        List<Long> seeks = new ArrayList<>();
        Spanned text = DescriptionSheet.linkedText(message, 4_000_000, seeks::add);
        ClickableSpan[] spans = text.getSpans(0, text.length(), ClickableSpan.class);
        assertEquals(message, text.toString());
        assertEquals(4, spans.length);
        for (ClickableSpan span : spans) {
            if (span instanceof URLSpan) {
                assertEquals("https://example.com/4:20", ((URLSpan) span).getURL());
            } else {
                span.onClick(null);
            }
        }
        assertEquals(Arrays.asList(0L, 62_000L, 3_663_000L), seeks);
    }

    private SelectableLinkTextView linkView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_NewTube);
        SelectableLinkTextView view = new SelectableLinkTextView(activity, null);
        SpannableString text = new SpannableString("link text");
        text.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) { clicks++; }
        }, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        activity.setContentView(view);
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 160);
        return view;
    }

    private void touch(SelectableLinkTextView view, int action, float x, long elapsed) {
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down + elapsed, action, x,
                view.getTotalPaddingTop() + view.getLayout().getLineBottom(0) / 2f, 0);
        view.onTouchEvent(event);
        event.recycle();
    }
}
