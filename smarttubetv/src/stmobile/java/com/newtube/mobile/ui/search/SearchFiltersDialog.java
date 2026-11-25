package com.newtube.mobile.ui.search;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.function.IntConsumer;

/** Edits a local draft: dismissing the dialog never changes the active search. */
final class SearchFiltersDialog {
    static AlertDialog show(Context context, int options, IntConsumer apply) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog);
        Context themed = builder.getContext();
        // Retired criteria can be ignored or return results outside their advertised range.
        int[] draft = {options & ~(SearchOptions.UPLOAD_DATE_LAST_HOUR | SearchOptions.SORT_BY_RELEVANCE
                | SearchOptions.SORT_BY_UPLOAD_DATE | SearchOptions.SORT_BY_RATING)};
        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * themed.getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, padding / 2);
        Spinner type = addChoice(content, draft, R.string.content_type, new int[][] {
                {R.string.content_type_any, 0}, {R.string.content_type_video, SearchOptions.TYPE_VIDEO},
                {R.string.content_type_channel, SearchOptions.TYPE_CHANNEL},
                {R.string.content_type_playlist, SearchOptions.TYPE_PLAYLIST},
                {R.string.content_type_movie, SearchOptions.TYPE_MOVIE}});
        Spinner duration = addChoice(content, draft, R.string.video_duration, new int[][] {
                {R.string.video_duration_any, 0}, {R.string.video_duration_under_4, SearchOptions.DURATION_UNDER_4},
                {R.string.video_duration_between_4_20, SearchOptions.DURATION_BETWEEN_4_20},
                {R.string.video_duration_over_20, SearchOptions.DURATION_OVER_20}});
        Spinner date = addChoice(content, draft, R.string.upload_date, new int[][] {
                {R.string.upload_date_any, 0},
                {R.string.upload_date_today, SearchOptions.UPLOAD_DATE_TODAY},
                {R.string.upload_date_this_week, SearchOptions.UPLOAD_DATE_THIS_WEEK},
                {R.string.upload_date_this_month, SearchOptions.UPLOAD_DATE_THIS_MONTH},
                {R.string.upload_date_this_year, SearchOptions.UPLOAD_DATE_THIS_YEAR}});
        addChoice(content, draft, R.string.mobile_search_prioritize, new int[][] {
                {R.string.sort_by_relevance, 0},
                {R.string.mobile_search_popularity, SearchOptions.SORT_BY_VIEW_COUNT}});

        TextView heading = new TextView(themed);
        heading.setText(R.string.video_features);
        heading.setPadding(0, padding / 2, 0, padding / 4);
        content.addView(heading);
        ChipGroup features = new ChipGroup(themed);
        for (int[] pair : new int[][] {{R.string.video_feature_live, SearchOptions.FEATURE_LIVE},
                {R.string.video_feature_4k, SearchOptions.FEATURE_4K},
                {R.string.video_feature_hdr, SearchOptions.FEATURE_HDR}}) {
            Chip chip = new Chip(themed);
            chip.setText(pair[0]);
            chip.setCheckable(true);
            chip.setChecked((options & pair[1]) != 0);
            chip.setOnCheckedChangeListener((button, checked) ->
                    draft[0] = checked ? draft[0] | pair[1] : draft[0] & ~pair[1]);
            features.addView(chip);
        }
        content.addView(features);
        Runnable updateAvailability = () -> {
            boolean videoFilters = (draft[0] & (SearchOptions.TYPE_CHANNEL | SearchOptions.TYPE_PLAYLIST)) == 0;
            duration.setEnabled(videoFilters);
            date.setEnabled(videoFilters);
            if (!videoFilters) {
                duration.setSelection(0);
                date.setSelection(0);
                draft[0] &= ~(SearchOptions.DURATION_UNDER_4 | SearchOptions.DURATION_BETWEEN_4_20
                        | SearchOptions.DURATION_OVER_20 | SearchOptions.UPLOAD_DATE_LAST_HOUR
                        | SearchOptions.UPLOAD_DATE_TODAY | SearchOptions.UPLOAD_DATE_THIS_WEEK
                        | SearchOptions.UPLOAD_DATE_THIS_MONTH | SearchOptions.UPLOAD_DATE_THIS_YEAR);
            }
            for (int i = 0; i < features.getChildCount(); i++) {
                Chip chip = (Chip) features.getChildAt(i);
                if (!videoFilters) chip.setChecked(false);
                chip.setEnabled(videoFilters);
            }
        };
        AdapterView.OnItemSelectedListener selectType = type.getOnItemSelectedListener();
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectType.onItemSelected(parent, view, position, id);
                updateAvailability.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        updateAvailability.run();
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(content);
        return builder.setTitle(R.string.mobile_search_filters).setView(scroll)
                .setNegativeButton(R.string.cancel_dialog, null)
                .setPositiveButton(R.string.mobile_search_apply, (dialog, which) -> apply.accept(draft[0]))
                .show();
    }

    private static Spinner addChoice(LinearLayout content, int[] draft, int title, int[][] choices) {
        Context context = content.getContext();
        int mask = 0;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            mask |= choices[i][1];
            labels[i] = context.getString(choices[i][0]);
        }
        int groupMask = mask;
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * context.getResources().getDisplayMetrics().density));
        TextView label = new TextView(context);
        label.setText(title);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Spinner spinner = new AppCompatSpinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < choices.length; i++) {
            if ((draft[0] & mask) == choices[i][1]) spinner.setSelection(i);
        }
        spinner.setContentDescription(context.getString(title));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                draft[0] = (draft[0] & ~groupMask) | choices[position][1];
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(0, -2, 1.5f));
        content.addView(row);
        return spinner;
    }
}
