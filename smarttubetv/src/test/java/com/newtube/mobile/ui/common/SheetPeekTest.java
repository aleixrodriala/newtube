package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.widget.NestedScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(sheet-landscape): a capped player sheet whose list does not fit ends with its last row
 * cut about in half, never on a row boundary (MobileSheets.capHeight).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SheetPeekTest {
    private static final int ROW = 100;

    private View mList;

    @Test
    public void aListThatFitsKeepsTheCap() {
        FrameLayout frame = sheet(3, 0, 500);
        assertEquals(500, MobileSheets.peekingCap(frame, mList, 500));
    }

    @Test
    public void anEdgeBetweenTwoRowsMovesUpToHalfARow() {
        // Rows end exactly at the cap: the edge would read as the end of the list.
        FrameLayout frame = sheet(8, 0, 500);
        assertEquals(450, MobileSheets.peekingCap(frame, mList, 500));
    }

    @Test
    public void anEdgeThatAlreadyCutsARowInHalfStays() {
        FrameLayout frame = sheet(8, 0, 550);
        assertEquals(550, MobileSheets.peekingCap(frame, mList, 550));
    }

    @Test
    public void aRowAlmostWholeIsCutBackToHalf() {
        // 90% of the row shows - its label reads whole.
        FrameLayout frame = sheet(8, 0, 590);
        assertEquals(550, MobileSheets.peekingCap(frame, mList, 590));
    }

    @Test
    public void fittingAgainAtTheFittedHeightGivesTheSameAnswer() {
        // The sheet already at 450 of its 500 cap: a later layout must not walk it up a row.
        FrameLayout frame = sheet(8, 0, 450);
        assertEquals(450, MobileSheets.peekingCap(frame, mList, 500));
    }

    @Test
    public void aPinnedFooterIsLeftBelowTheEdge() {
        // A 100px footer under the list (Subtitles' style row): the list ends at 400, between rows.
        FrameLayout frame = sheet(8, ROW, 500);
        assertEquals(450, MobileSheets.peekingCap(frame, mList, 500));
    }

    @Test
    public void neverGivesUpHalfTheListForIt() {
        // One row and a sliver show: lifting to the middle of the first row would hide most of it.
        FrameLayout frame = sheet(8, 0, 110);
        assertEquals(110, MobileSheets.peekingCap(frame, mList, 110));
    }

    @Test
    public void aHiddenRowIsNotTheOneCut() {
        // Hidden after its layout (Cast's device list behind the TV-code chooser), the row the edge
        // cut in half still has its old bounds; the edge between the rows left must move.
        FrameLayout frame = sheet(8, 0, 550);
        ((ViewGroup) ((ViewGroup) mList).getChildAt(0)).getChildAt(5).setVisibility(View.GONE);
        assertEquals(450, MobileSheets.peekingCap(frame, mList, 550));
    }

    /** A sheet frame {@code height} tall: a list of {@code rows} rows, then a footer. */
    private FrameLayout sheet(int rows, int footer, int height) {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout frame = new FrameLayout(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        NestedScrollView list = new NestedScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < rows; i++) {
            View row = new View(context);
            row.setClickable(true);
            content.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ROW));
        }
        list.addView(content);
        column.addView(list, footer > 0
                ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        if (footer > 0) {
            View pinned = new View(context);
            pinned.setClickable(true);
            column.addView(pinned, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, footer));
        }

        frame.measure(MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        frame.layout(0, 0, frame.getMeasuredWidth(), frame.getMeasuredHeight());
        mList = list;
        return frame;
    }
}
