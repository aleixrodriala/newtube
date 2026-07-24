package com.newtube.mobile.ui.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

public class MobileSearchSubmitTest {
    @Test
    public void acceptsSoftKeyboardSearchAction() {
        assertTrue(MobileSearchActivity.isSearchSubmission(
                EditorInfo.IME_ACTION_SEARCH, KeyEvent.KEYCODE_UNKNOWN, -1));
    }

    @Test
    public void acceptsHardwareEnterOnKeyUp() {
        assertTrue(MobileSearchActivity.isSearchSubmission(
                EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP));
    }

    @Test
    public void ignoresHardwareEnterKeyDownToAvoidDoubleSubmit() {
        assertFalse(MobileSearchActivity.isSearchSubmission(
                EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN));
    }

    @Test
    public void ignoresUnrelatedKey() {
        assertFalse(MobileSearchActivity.isSearchSubmission(
                EditorInfo.IME_NULL, KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP));
    }
}
