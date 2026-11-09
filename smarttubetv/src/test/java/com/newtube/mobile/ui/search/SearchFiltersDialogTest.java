package com.newtube.mobile.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SearchFiltersDialogTest {
    @Test
    public void filterDraftsApplySupportedOptionsAndCancelWithoutChangingTheSearch() {
        int initial = SearchOptions.TYPE_VIDEO | SearchOptions.DURATION_OVER_20
                | SearchOptions.UPLOAD_DATE_THIS_WEEK | SearchOptions.SORT_BY_VIEW_COUNT | SearchOptions.FEATURE_LIVE;
        int[] applied = {-1};
        AlertDialog dialog = show(initial, applied);
        List<Spinner> choices = descendants(dialog.getWindow().getDecorView(), Spinner.class);
        assertEquals(1, choices.get(0).getSelectedItemPosition());
        assertEquals(3, choices.get(1).getSelectedItemPosition());
        choices.get(0).setSelection(4); // Movie replaces Video without discarding video filters.
        List<Chip> chips = descendants(dialog.getWindow().getDecorView(), Chip.class);
        chips.get(0).setChecked(false);
        chips.get(1).setChecked(true);
        chips.get(2).setChecked(true);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(-1, applied[0]); // Draft edits do not issue a search.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals((initial & ~SearchOptions.TYPE_VIDEO & ~SearchOptions.FEATURE_LIVE)
                | SearchOptions.TYPE_MOVIE | SearchOptions.FEATURE_4K | SearchOptions.FEATURE_HDR, applied[0]);

        applied[0] = -1;
        dialog = show(initial, applied);
        descendants(dialog.getWindow().getDecorView(), Spinner.class).get(0).setSelection(3);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(-1, applied[0]);
        dialog = show(initial, applied);
        apply(dialog);
        assertEquals(initial, applied[0]);

        int[][] values = {{0, SearchOptions.TYPE_VIDEO, SearchOptions.TYPE_CHANNEL,
                SearchOptions.TYPE_PLAYLIST, SearchOptions.TYPE_MOVIE},
                {0, SearchOptions.DURATION_UNDER_4, SearchOptions.DURATION_BETWEEN_4_20, SearchOptions.DURATION_OVER_20},
                {0, SearchOptions.UPLOAD_DATE_TODAY, SearchOptions.UPLOAD_DATE_THIS_WEEK,
                        SearchOptions.UPLOAD_DATE_THIS_MONTH, SearchOptions.UPLOAD_DATE_THIS_YEAR},
                {0, SearchOptions.SORT_BY_VIEW_COUNT}};
        for (int group = 0; group < values.length; group++) {
            for (int choice = 0; choice < values[group].length; choice++) {
                int[] result = {-1};
                AlertDialog optionsDialog = show(0, result);
                List<Spinner> spinners = descendants(optionsDialog.getWindow().getDecorView(), Spinner.class);
                assertEquals(values[group].length, spinners.get(group).getCount());
                spinners.get(group).setSelection(choice);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                apply(optionsDialog);
                assertEquals(values[group][choice], result[0]);
            }
        }
        int[] features = {SearchOptions.FEATURE_LIVE, SearchOptions.FEATURE_4K, SearchOptions.FEATURE_HDR};
        for (int mask = 0; mask < 8; mask++) {
            int[] result = {-1};
            AlertDialog optionsDialog = show(0, result);
            List<Chip> featureChips = descendants(optionsDialog.getWindow().getDecorView(), Chip.class);
            int expected = 0;
            for (int i = 0; i < features.length; i++) {
                boolean checked = (mask & (1 << i)) != 0;
                featureChips.get(i).setChecked(checked);
                if (checked) expected |= features[i];
            }
            apply(optionsDialog);
            assertEquals(expected, result[0]);
        }

        for (int type : new int[] {2, 3}) {
            int[] result = {-1};
            AlertDialog typeDialog = show(SearchOptions.TYPE_VIDEO | SearchOptions.DURATION_UNDER_4
                    | SearchOptions.UPLOAD_DATE_TODAY | SearchOptions.FEATURE_4K | SearchOptions.SORT_BY_VIEW_COUNT, result);
            List<Spinner> typeChoices = descendants(typeDialog.getWindow().getDecorView(), Spinner.class);
            typeChoices.get(0).setSelection(type);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(typeChoices.get(1).isEnabled());
            assertFalse(typeChoices.get(2).isEnabled());
            for (Chip chip : descendants(typeDialog.getWindow().getDecorView(), Chip.class)) {
                assertFalse(chip.isEnabled());
                assertFalse(chip.isChecked());
            }
            apply(typeDialog);
            assertEquals((type == 2 ? SearchOptions.TYPE_CHANNEL : SearchOptions.TYPE_PLAYLIST)
                    | SearchOptions.SORT_BY_VIEW_COUNT, result[0]);
        }
        applied[0] = -1;
        dialog = show(SearchOptions.TYPE_CHANNEL | SearchOptions.UPLOAD_DATE_LAST_HOUR
                | SearchOptions.SORT_BY_UPLOAD_DATE | SearchOptions.SORT_BY_RATING, applied);
        choices = descendants(dialog.getWindow().getDecorView(), Spinner.class);
        choices.get(0).setSelection(1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(choices.get(1).isEnabled());
        assertTrue(choices.get(2).isEnabled());
        assertEquals(2, choices.get(3).getCount());
        apply(dialog);
        assertEquals(SearchOptions.TYPE_VIDEO, applied[0]);
    }

    private static AlertDialog show(int options, int[] applied) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_NewTube);
        AlertDialog dialog = SearchFiltersDialog.show(activity, options, value -> applied[0] = value);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return dialog;
    }

    private static void apply(AlertDialog dialog) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static <T extends View> List<T> descendants(View view, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(view)) result.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(descendants(group.getChildAt(i), type));
        }
        return result;
    }
}
