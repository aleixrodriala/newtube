package com.newtube.mobile.ui.dialog;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.view.View.MeasureSpec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** NEWTUBE(sheet-landscape): the sheet list cap never exceeds the parent's own limit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MaxHeightRecyclerViewTest {

    @Test
    public void noCapLeavesTheSpecAlone() {
        int spec = MeasureSpec.makeMeasureSpec(900, MeasureSpec.EXACTLY);
        assertEquals(spec, MaxHeightRecyclerView.capHeightSpec(spec, 0));
    }

    @Test
    public void capBelowTheParentLimitWins() {
        int capped = MaxHeightRecyclerView.capHeightSpec(
                MeasureSpec.makeMeasureSpec(1000, MeasureSpec.AT_MOST), 700);
        assertEquals(MeasureSpec.AT_MOST, MeasureSpec.getMode(capped));
        assertEquals(700, MeasureSpec.getSize(capped));
    }

    @Test
    public void staleCapTallerThanTheWindowIsClampedToIt() {
        // The landscape bug: a cap from frozen portrait metrics (1650px) in a ~900px parent.
        int capped = MaxHeightRecyclerView.capHeightSpec(
                MeasureSpec.makeMeasureSpec(900, MeasureSpec.AT_MOST), 1650);
        assertEquals(900, MeasureSpec.getSize(capped));
    }

    @Test
    public void unspecifiedParentTakesTheCap() {
        int capped = MaxHeightRecyclerView.capHeightSpec(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 700);
        assertEquals(MeasureSpec.AT_MOST, MeasureSpec.getMode(capped));
        assertEquals(700, MeasureSpec.getSize(capped));
    }
}
