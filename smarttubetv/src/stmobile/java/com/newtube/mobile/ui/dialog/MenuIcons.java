package com.newtube.mobile.ui.dialog;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.HashMap;
import java.util.Map;

/**
 * NEWTUBE(menu-icons): the icon drawn next to a card-menu row, like YouTube's own menu. The shared
 * menu code names each row ({@code OptionCategory.iconKey}, set in {@code VideoMenuPresenter}); this
 * maps that name to a Material Symbols drawable. A row with no key, or a key not listed here, draws
 * no icon.
 */
final class MenuIcons {
    private static final Map<String, Integer> ICONS = new HashMap<>();

    static {
        ICONS.put("return_background", R.drawable.ic_menu_return_background);
        ICONS.put("play_video", R.drawable.ic_menu_play_video);
        ICONS.put("play_incognito", R.drawable.ic_menu_play_incognito);
        ICONS.put("play_from_start", R.drawable.ic_menu_play_from_start);
        ICONS.put("remove_from_history", R.drawable.ic_menu_remove_from_history);
        ICONS.put("stream_reminder", R.drawable.ic_menu_stream_reminder);
        ICONS.put("recent_playlist", R.drawable.ic_menu_recent_playlist);
        ICONS.put("watch_later", R.drawable.ic_menu_watch_later);
        ICONS.put("add_to_playlist", R.drawable.ic_menu_add_to_playlist);
        ICONS.put("remove_from_playlist", R.drawable.ic_menu_remove_from_playlist);
        ICONS.put("download", R.drawable.ic_menu_download);
        ICONS.put("create_playlist", R.drawable.ic_menu_create_playlist);
        ICONS.put("rename_playlist", R.drawable.ic_menu_rename_playlist);
        ICONS.put("new_playlist", R.drawable.ic_menu_new_playlist);
        ICONS.put("not_interested", R.drawable.ic_menu_not_interested);
        ICONS.put("not_recommend_channel", R.drawable.ic_menu_not_recommend_channel);
        ICONS.put("remove_from_subscriptions", R.drawable.ic_menu_remove_from_subscriptions);
        ICONS.put("block_channel", R.drawable.ic_menu_block_channel);
        ICONS.put("mark_watched", R.drawable.ic_menu_mark_watched);
        ICONS.put("playlist_order", R.drawable.ic_menu_playlist_order);
        ICONS.put("add_to_queue", R.drawable.ic_menu_add_to_queue);
        ICONS.put("remove_from_queue", R.drawable.ic_menu_remove_from_queue);
        ICONS.put("play_next", R.drawable.ic_menu_play_next);
        ICONS.put("show_queue", R.drawable.ic_menu_show_queue);
        ICONS.put("open_channel", R.drawable.ic_menu_open_channel);
        ICONS.put("open_playlist", R.drawable.ic_menu_open_playlist);
        ICONS.put("subscribe", R.drawable.ic_menu_subscribe);
        ICONS.put("content_block", R.drawable.ic_menu_content_block);
        ICONS.put("pin_sidebar", R.drawable.ic_menu_pin_sidebar);
        ICONS.put("save_playlist", R.drawable.ic_menu_save_playlist);
        ICONS.put("open_description", R.drawable.ic_menu_open_description);
        ICONS.put("share", R.drawable.ic_menu_share);
        ICONS.put("share_qr", R.drawable.ic_menu_share_qr);
        ICONS.put("share_embed", R.drawable.ic_menu_share_embed);
        ICONS.put("select_account", R.drawable.ic_menu_select_account);
        ICONS.put("toggle_history", R.drawable.ic_menu_toggle_history);
        ICONS.put("clear_history", R.drawable.ic_menu_clear_history);
        ICONS.put("open_comments", R.drawable.ic_menu_open_comments);
    }

    private MenuIcons() {
    }

    /** The drawable for {@code iconKey}, or 0 for none. */
    @DrawableRes
    static int forKey(@Nullable String iconKey) {
        Integer icon = iconKey != null ? ICONS.get(iconKey) : null;
        return icon != null ? icon : 0;
    }
}
