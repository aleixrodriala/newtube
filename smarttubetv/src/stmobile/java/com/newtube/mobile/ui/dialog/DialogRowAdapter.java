package com.newtube.mobile.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flattens a single dialog "level" ({@code List<OptionCategory>}, as handed to
 * {@code AppDialogView.show()}) into a flat list of rows and renders them as a Material
 * list - the touch equivalent of {@code AppPreferenceManager.createPreference()} (which
 * turns the same data model into Leanback {@code Preference} objects).
 *
 * Supported {@link OptionCategory#type} kinds (see {@code AppDialogFragment.onPreferenceDisplayDialog()}
 * for the authoritative TV enumeration this mirrors):
 * <ul>
 *     <li>{@code TYPE_SINGLE_BUTTON} -> one clickable row (plain action item).</li>
 *     <li>{@code TYPE_SINGLE_SWITCH} -> one row with a trailing {@link SwitchMaterial} (boolean setting).</li>
 *     <li>{@code TYPE_RADIO_LIST} -> an optional header row + one radio row per option
 *     (single-select; unlike the TV {@code ListPreference} popup, selections render inline -
 *     no extra navigation level).</li>
 *     <li>{@code TYPE_CHECKBOX_LIST}/{@code TYPE_STRING_LIST} -> an optional header row + one
 *     checkbox row per option (multi-select; functionally identical on this data model -
 *     {@code AppPreferenceManager} backs both with the same {@code MultiSelectListPreference}).</li>
 *     <li>{@code TYPE_LONG_TEXT}/{@code TYPE_CHAT}/{@code TYPE_COMMENTS} -> read-only text
 *     fallback row (chat/comments live-stream panels are deferred to a later wave per
 *     ROADMAP.md; this only guarantees we don't crash on them).</li>
 * </ul>
 */
class DialogRowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_BUTTON = 1;
    private static final int TYPE_RADIO = 2;
    private static final int TYPE_CHECKBOX = 3;
    private static final int TYPE_SWITCH = 4;
    private static final int TYPE_TEXT = 5;

    interface Listener {
        void onButtonClicked(OptionItem item);
        void onRadioClicked(OptionCategory category, OptionItem item);
        void onCheckboxClicked(OptionCategory category, OptionItem item);
        void onSwitchToggled(OptionItem item, boolean checked);
    }

    private static final class Row {
        final int viewType;
        final OptionCategory category;
        final OptionItem item;
        final CharSequence title;
        final CharSequence subtitle;
        final boolean checked;

        private Row(int viewType, OptionCategory category, OptionItem item, CharSequence title, CharSequence subtitle, boolean checked) {
            this.viewType = viewType;
            this.category = category;
            this.item = item;
            this.title = title;
            this.subtitle = subtitle;
            this.checked = checked;
        }

        static Row header(CharSequence title) {
            return new Row(TYPE_HEADER, null, null, title, null, false);
        }

        static Row button(OptionItem item) {
            return new Row(TYPE_BUTTON, null, item, item.getTitle(), item.getDescription(), false);
        }

        static Row radio(OptionCategory category, OptionItem item, boolean checked) {
            return new Row(TYPE_RADIO, category, item, item.getTitle(), item.getDescription(), checked);
        }

        static Row checkbox(OptionCategory category, OptionItem item, boolean checked) {
            return new Row(TYPE_CHECKBOX, category, item, item.getTitle(), item.getDescription(), checked);
        }

        static Row switchRow(OptionItem item) {
            return new Row(TYPE_SWITCH, null, item, item.getTitle(), item.getDescription(), item.isSelected());
        }

        static Row text(CharSequence text) {
            return new Row(TYPE_TEXT, null, null, text, null, false);
        }
    }

    private List<Row> mRows = new ArrayList<>();
    private final Context mContext;
    private final Listener mListener;

    DialogRowAdapter(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
    }

    /**
     * @param radioOverrides client-side "currently picked" override per radio category - see
     *                       {@link MobileAppDialogActivity}. TV's {@code ListPreference} only ever
     *                       calls {@code onSelect(true)} on the newly-picked item (never
     *                       {@code onSelect(false)} on the others - see
     *                       {@code AppPreferenceManager.initSingleSelectListPreference}), so the
     *                       sibling items' own {@code isSelected()} never flips. We need this map
     *                       purely to know which row to draw as checked after a tap.
     */
    void submit(List<OptionCategory> categories, Map<OptionCategory, OptionItem> radioOverrides) {
        mRows = build(categories, radioOverrides);
        notifyDataSetChanged();
    }

    private List<Row> build(List<OptionCategory> categories, Map<OptionCategory, OptionItem> radioOverrides) {
        List<Row> rows = new ArrayList<>();

        if (categories == null) {
            return rows;
        }

        for (OptionCategory category : categories) {
            if (category == null || category.options == null || category.options.isEmpty()) {
                continue;
            }

            switch (category.type) {
                case OptionCategory.TYPE_SINGLE_BUTTON:
                    rows.add(Row.button(category.options.get(0)));
                    break;
                case OptionCategory.TYPE_SINGLE_SWITCH:
                    rows.add(Row.switchRow(category.options.get(0)));
                    break;
                case OptionCategory.TYPE_RADIO_LIST: {
                    if (category.title != null) {
                        rows.add(Row.header(category.title));
                    }
                    OptionItem override = radioOverrides != null ? radioOverrides.get(category) : null;
                    for (OptionItem item : category.options) {
                        boolean checked = override != null ? item == override : item.isSelected();
                        rows.add(Row.radio(category, item, checked));
                    }
                    break;
                }
                case OptionCategory.TYPE_CHECKBOX_LIST:
                case OptionCategory.TYPE_STRING_LIST: {
                    if (category.title != null) {
                        rows.add(Row.header(category.title));
                    }
                    for (OptionItem item : category.options) {
                        rows.add(Row.checkbox(category, item, item.isSelected()));
                    }
                    break;
                }
                case OptionCategory.TYPE_LONG_TEXT: {
                    if (category.title != null) {
                        rows.add(Row.header(category.title));
                    }
                    rows.add(Row.text(category.options.get(0).getTitle()));
                    break;
                }
                case OptionCategory.TYPE_CHAT:
                case OptionCategory.TYPE_COMMENTS: {
                    // TODO Wave N: live chat / comments panel. Read-only stub for now - must not crash.
                    if (category.title != null) {
                        rows.add(Row.header(category.title));
                    }
                    rows.add(Row.text(mContext.getString(R.string.mobile_dialog_unavailable)));
                    break;
                }
            }
        }

        return rows;
    }

    @Override
    public int getItemViewType(int position) {
        return mRows.get(position).viewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_mobile_dialog_header, parent, false));
        }

        return new RowViewHolder(inflater.inflate(R.layout.item_mobile_dialog_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = mRows.get(position);

        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(row);
        } else if (holder instanceof RowViewHolder) {
            ((RowViewHolder) holder).bind(row, mListener);
        }
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    private static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.dialog_header_title);
        }

        void bind(Row row) {
            title.setText(row.title);
        }
    }

    /**
     * Account rows (AccountSelectionPresenter/AccountSettingsPresenter) embed the avatar as an
     * inline {@code ImageSpan} whose drawable keeps its intrinsic bitmap size - full-resolution
     * on a phone, which blew the row up to the avatar's pixel height. Clamp any inline icon to
     * ~1.4x the row's text size (roughly the line box, like an emoji). TV renders these rows with
     * Leanback (which sizes icons itself), so this stays a mobile-renderer concern.
     */
    private static CharSequence sizeInlineIcons(CharSequence text, TextView target) {
        if (!(text instanceof android.text.Spanned)) {
            return text;
        }

        android.text.Spanned spanned = (android.text.Spanned) text;
        android.text.style.ImageSpan[] spans =
                spanned.getSpans(0, spanned.length(), android.text.style.ImageSpan.class);
        if (spans.length == 0) {
            return text;
        }

        int size = Math.round(target.getTextSize() * 1.4f);
        for (android.text.style.ImageSpan span : spans) {
            android.graphics.drawable.Drawable drawable = span.getDrawable();
            if (drawable != null) {
                drawable.setBounds(0, 0, size, size);
            }
        }

        return text;
    }

    private static class RowViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView subtitle;
        private final RadioButton radio;
        private final CheckBox checkbox;
        private final SwitchMaterial switchControl;

        RowViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.dialog_row_title);
            subtitle = itemView.findViewById(R.id.dialog_row_subtitle);
            radio = itemView.findViewById(R.id.dialog_row_radio);
            checkbox = itemView.findViewById(R.id.dialog_row_checkbox);
            switchControl = itemView.findViewById(R.id.dialog_row_switch);
        }

        void bind(Row row, Listener listener) {
            title.setText(sizeInlineIcons(row.title, title));

            if (row.subtitle != null && row.subtitle.length() > 0) {
                subtitle.setText(row.subtitle);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }

            radio.setVisibility(View.GONE);
            checkbox.setVisibility(View.GONE);
            switchControl.setVisibility(View.GONE);
            switchControl.setOnCheckedChangeListener(null);
            itemView.setOnClickListener(null);

            boolean clickable = row.viewType != TYPE_TEXT;
            itemView.setClickable(clickable);
            itemView.setFocusable(clickable);
            title.setAlpha(row.viewType == TYPE_TEXT ? 0.85f : 1f);

            switch (row.viewType) {
                case TYPE_BUTTON:
                    itemView.setOnClickListener(v -> listener.onButtonClicked(row.item));
                    break;
                case TYPE_RADIO:
                    radio.setVisibility(View.VISIBLE);
                    radio.setChecked(row.checked);
                    itemView.setOnClickListener(v -> listener.onRadioClicked(row.category, row.item));
                    break;
                case TYPE_CHECKBOX:
                    checkbox.setVisibility(View.VISIBLE);
                    checkbox.setChecked(row.checked);
                    itemView.setOnClickListener(v -> listener.onCheckboxClicked(row.category, row.item));
                    break;
                case TYPE_SWITCH:
                    switchControl.setVisibility(View.VISIBLE);
                    switchControl.setChecked(row.checked);
                    switchControl.setOnCheckedChangeListener(
                            (buttonView, isChecked) -> listener.onSwitchToggled(row.item, isChecked));
                    itemView.setOnClickListener(v -> switchControl.setChecked(!switchControl.isChecked()));
                    break;
                case TYPE_TEXT:
                default:
                    // Read-only - no click handling.
                    break;
            }
        }
    }
}
