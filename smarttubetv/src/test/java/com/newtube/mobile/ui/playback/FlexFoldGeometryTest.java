package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Unit tests for {@link FlexFoldGeometry#computeSplit}. */
public class FlexFoldGeometryTest {

    @Test
    public void horizontalMiddleCreaseComputesBothHalves() {
        FlexFoldGeometry.FlexSplit split = FlexFoldGeometry.computeSplit(0, 2172, 1086, 1086, 400);
        assertNotNull(split);
        assertEquals(1086, split.topPanelHeightPx);
        assertEquals(0, split.hingeSpacerHeightPx);
    }

    @Test
    public void physicalHingeGapAccountsForRootTopOffset() {
        FlexFoldGeometry.FlexSplit split = FlexFoldGeometry.computeSplit(100, 2000, 1050, 1090, 400);
        assertNotNull(split);
        assertEquals(950, split.topPanelHeightPx);
        assertEquals(40, split.hingeSpacerHeightPx);
    }

    @Test
    public void foldTooCloseToEdgeReturnsNull() {
        assertNull(FlexFoldGeometry.computeSplit(0, 1000, 150, 150, 300));
        assertNull(FlexFoldGeometry.computeSplit(0, 1000, 850, 860, 300));
        assertNull(FlexFoldGeometry.computeSplit(0, 0, 500, 500, 300));
    }
}
