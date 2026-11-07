package com.newtube.mobile.ui.dialog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** NEWTUBE(sheet-title): a section header that only repeats the sheet's title is left out. */
public class DialogRowAdapterTitleTest {

    @Test
    public void theSameWordsAreARepeat() {
        assertTrue(DialogRowAdapter.repeatsTitle("Playback queue", "Playback queue"));
        assertTrue(DialogRowAdapter.repeatsTitle(" video speed ", "Video speed"));
    }

    @Test
    public void aDifferentHeaderStays() {
        assertFalse(DialogRowAdapter.repeatsTitle("Quality", "Video speed"));
        assertFalse(DialogRowAdapter.repeatsTitle("Playback queue", "Playback"));
    }

    @Test
    public void anUntitledSheetKeepsItsHeaders() {
        assertFalse(DialogRowAdapter.repeatsTitle("Repeat mode", null));
    }
}
