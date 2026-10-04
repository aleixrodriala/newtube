package com.newtube.mobile.ui.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.ContextMenuManager;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.ContextMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.BackupSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.LanguageSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.BackupAndRestoreManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SearchData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.newtube.mobile.CardMenuMigration;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.ThemeMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The app-side pages of the phone Settings: General, Feeds (tabs, hidden videos, the video menu),
 * History and privacy, Backup. Every row here was checked against what the phone actually reads
 * (the 2026-10 audit); rows that only ever did something on a TV are not offered (their stored
 * values are left alone).
 */
final class AppPages {
    private AppPages() {
    }

    // ---------------------------------------------------------------------------------------------
    // General
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page general(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        GeneralData generalData = GeneralData.instance(context);
        MainUIData mainUIData = MainUIData.instance(context);
        SearchData searchData = SearchData.instance(context);

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_theme))
                .option(context.getString(R.string.mobile_theme_system), ThemeMode.SYSTEM)
                .option(context.getString(R.string.mobile_theme_light), ThemeMode.LIGHT)
                .option(context.getString(R.string.mobile_theme_dark), ThemeMode.DARK)
                .bind(ThemeMode::get, mode -> ThemeMode.set(context, mode)));

        LanguageSettingsPresenter language = LanguageSettingsPresenter.instance(context);
        rows.add(SettingsRow.fromRadio(language.createLanguageCategory(),
                context.getString(R.string.mobile_settings_language)).needsRestart());
        rows.add(SettingsRow.fromRadio(language.createCountryCategory(),
                context.getString(R.string.mobile_settings_country)).needsRestart());

        rows.add(startScreen(context));

        rows.add(interfaceSize(context, mainUIData));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_voice_search),
                context.getString(R.string.mobile_settings_voice_search_summary),
                searchData::isInstantVoiceSearchEnabled, searchData::setInstantVoiceSearchEnabled));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_24_hour),
                context.getString(R.string.mobile_settings_24_hour_summary),
                generalData::is24HourLocaleEnabled, generalData::set24HourLocaleEnabled).needsRestart());

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_general), rows);
    }

    /** The tab NewTube opens on: the enabled sections, then pinned channels and playlists. */
    private static SettingsRow startScreen(Context context) {
        SidebarService sidebar = SidebarService.instance(context);
        SettingsRow.Choice<Integer> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_start_screen));
        int boot = sidebar.getBootSectionId();
        for (Map.Entry<Integer, Integer> section : sidebar.getDefaultSections().entrySet()) {
            int sectionId = section.getValue();
            boolean current = sectionId == boot;
            if (!current && (sectionId == MediaGroup.TYPE_SHORTS || sectionId == MediaGroup.TYPE_SETTINGS
                    || !sidebar.isSectionPinned(sectionId))) {
                continue;
            }
            choice.option(context.getString(sectionTitle(section.getKey())), sectionId);
        }
        for (Video item : sidebar.getPinnedItems()) {
            if (item != null && item.getTitle() != null) {
                choice.option(item.getTitle(), item.getId());
            }
        }
        return choice.bind(sidebar::getBootSectionId, sidebar::setBootSectionId);
    }

    /**
     * UI scale. The TV list ran from 0.4x; on a phone the useful range is a step or two either way
     * of 1x (a value stored from the old list stays listed so the row still shows it).
     */
    private static SettingsRow interfaceSize(Context context, MainUIData mainUIData) {
        List<Float> scales = new ArrayList<>();
        for (float scale : new float[] {0.8f, 0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f}) {
            scales.add(scale);
        }
        float current = mainUIData.getUIScale();
        boolean listed = false;
        for (float scale : scales) {
            listed |= Math.abs(scale - current) < 0.001f;
        }
        if (!listed) {
            scales.add(current);
            Collections.sort(scales);
        }
        SettingsRow.Choice<Float> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_interface_size));
        for (float scale : scales) {
            String label = String.format(Locale.US, "%d%%", Math.round(scale * 100));
            choice.option(Math.abs(scale - 1.0f) < 0.001f
                    ? context.getString(R.string.mobile_settings_default_value, label) : label, scale);
        }
        // Equality on the rounded percentage: the stored float may not be bit-identical.
        return choice.bind(() -> nearest(scales, mainUIData.getUIScale()), mainUIData::setUIScale).needsRestart();
    }

    private static Float nearest(List<Float> values, float value) {
        for (Float candidate : values) {
            if (Math.abs(candidate - value) < 0.001f) {
                return candidate;
            }
        }
        return value;
    }

    /** The section list keys Live by the upper-case badge string; the phone says "Live". */
    static int sectionTitle(int resId) {
        return resId == R.string.badge_live ? R.string.header_live : resId;
    }

    // ---------------------------------------------------------------------------------------------
    // Feeds
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page feeds(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        MainUIData mainUIData = MainUIData.instance(context);

        rows.add(SettingsRow.page(context.getString(R.string.mobile_settings_tabs),
                () -> context.getString(R.string.mobile_settings_tabs_summary), SettingsPages.FEEDS_TABS));
        rows.add(SettingsRow.page(context.getString(R.string.mobile_settings_hidden),
                () -> hiddenSummary(context), SettingsPages.FEEDS_HIDDEN));
        rows.add(SettingsRow.page(context.getString(R.string.mobile_settings_video_menu),
                () -> context.getString(R.string.mobile_settings_video_menu_summary), SettingsPages.FEEDS_MENU));

        rows.add(SettingsRow.divider());

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_thumbnails))
                .option(context.getString(R.string.mobile_settings_thumbnails_default), ClickbaitRemover.THUMB_QUALITY_DEFAULT)
                .option(context.getString(R.string.mobile_settings_thumbnails_start), ClickbaitRemover.THUMB_QUALITY_START)
                .option(context.getString(R.string.mobile_settings_thumbnails_middle), ClickbaitRemover.THUMB_QUALITY_MIDDLE)
                .option(context.getString(R.string.mobile_settings_thumbnails_end), ClickbaitRemover.THUMB_QUALITY_END)
                .bind(mainUIData::getThumbQuality, mainUIData::setThumbQuality));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_original_titles),
                context.getString(R.string.mobile_settings_original_titles_summary),
                mainUIData::isUnlocalizedTitlesEnabled, on -> {
                    mainUIData.setUnlocalizedTitlesEnabled(on);
                    // The two replace the same title: original wins over DeArrow's.
                    com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData.instance(context).setReplaceTitlesEnabled(false);
                }));

        // On the phone these change what the feed holds, not its layout (both work): kept as choices.
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_feed_layout))
                .option(context.getString(R.string.mobile_settings_feed_layout_list), MainUIData.MOBILE_FEED_LAYOUT_LIST)
                .option(context.getString(R.string.mobile_settings_feed_layout_grid), MainUIData.MOBILE_FEED_LAYOUT_GRID)
                .option(context.getString(R.string.mobile_settings_feed_layout_compact), MainUIData.MOBILE_FEED_LAYOUT_COMPACT)
                .bind(mainUIData::getMobileFeedLayout, layout -> {
                    mainUIData.setMobileFeedLayout(layout);
                    BrowsePresenter.instance(context).updatePlaylistsStyle();
                }).needsRestart());
        rows.add(SettingsRow.<Boolean>choice(context.getString(R.string.mobile_settings_pinned_channels))
                .option(context.getString(R.string.mobile_settings_pinned_channels_all), true)
                .option(context.getString(R.string.mobile_settings_pinned_channels_uploads), false)
                .bind(mainUIData::isPinnedChannelRowsEnabled, mainUIData::setPinnedChannelRowsEnabled)
                .needsRestart());
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_playlists_style))
                .option(context.getString(R.string.mobile_settings_playlists_style_grid), MainUIData.PLAYLISTS_STYLE_GRID)
                .option(context.getString(R.string.mobile_settings_playlists_style_rows), MainUIData.PLAYLISTS_STYLE_ROWS)
                .bind(mainUIData::getPlaylistsStyle, style -> {
                    mainUIData.setPlaylistsStyle(style);
                    BrowsePresenter.instance(context).updatePlaylistsStyle();
                }));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_channels_order))
                .option(context.getString(R.string.mobile_settings_channels_order_recent), MainUIData.CHANNEL_SORTING_LAST_VIEWED)
                .option(context.getString(R.string.mobile_settings_channels_order_name), MainUIData.CHANNEL_SORTING_NAME)
                .option(context.getString(R.string.mobile_settings_channels_order_new), MainUIData.CHANNEL_SORTING_NEW_CONTENT)
                .bind(mainUIData::getChannelCategorySorting, sorting -> {
                    mainUIData.setChannelCategorySorting(sorting);
                    BrowsePresenter.instance(context).updateChannelSorting();
                }));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_feeds), rows);
    }

    /** Which feeds exist, grouped the way the app shows them: bottom bar, You, Explore. */
    static SettingsPages.Page tabs(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        SidebarService sidebar = SidebarService.instance(context);
        Map<Integer, Integer> sections = sidebar.getDefaultSections();

        int[] bottomBar = {MediaGroup.TYPE_HOME, MediaGroup.TYPE_SUBSCRIPTIONS, MediaGroup.TYPE_HISTORY, VideoDownloads.SECTION_ID};
        int[] you = {MediaGroup.TYPE_USER_PLAYLISTS, MediaGroup.TYPE_MY_VIDEOS, MediaGroup.TYPE_CHANNEL_UPLOADS,
                MediaGroup.TYPE_PLAYBACK_QUEUE, MediaGroup.TYPE_BLOCKED_CHANNELS};

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_tabs_bottom_bar)));
        addSectionToggles(context, rows, sections, bottomBar, true);
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_tabs_you)));
        addSectionToggles(context, rows, sections, you, true);
        rows.add(SettingsRow.header(context.getString(R.string.mobile_you_explore)));
        // Everything else the section list offers (Trending, Music, Live, ...).
        List<Integer> listed = new ArrayList<>();
        for (int id : bottomBar) {
            listed.add(id);
        }
        for (int id : you) {
            listed.add(id);
        }
        int[] rest = new int[sections.size()];
        int count = 0;
        for (int sectionId : sections.values()) {
            if (!listed.contains(sectionId)) {
                rest[count++] = sectionId;
            }
        }
        addSectionToggles(context, rows, sections, Arrays.copyOf(rest, count), false);
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_tabs_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_tabs), rows);
    }

    private static void addSectionToggles(Context context, List<SettingsRow> rows, Map<Integer, Integer> sections,
                                          int[] order, boolean keepOrder) {
        SidebarService sidebar = SidebarService.instance(context);
        for (int sectionId : order) {
            if (sectionId == MediaGroup.TYPE_SETTINGS || sectionId == MediaGroup.TYPE_SHORTS) {
                continue;
            }
            Integer titleRes = null;
            for (Map.Entry<Integer, Integer> entry : sections.entrySet()) {
                if (entry.getValue() == sectionId) {
                    titleRes = entry.getKey();
                    break;
                }
            }
            if (titleRes == null) {
                continue;
            }
            rows.add(SettingsRow.toggle(context.getString(sectionTitle(titleRes)), null,
                    () -> sidebar.isSectionPinned(sectionId),
                    on -> BrowsePresenter.instance(context).enableSection(sectionId, on)));
        }
    }

    /** "Upcoming on Home" / "3 kinds of videos" / "Nothing hidden". */
    private static CharSequence hiddenSummary(Context context) {
        MediaServiceData data = MediaServiceData.instance();
        int count = 0;
        for (int flag : HIDDEN_FLAGS) {
            if (data.isContentHidden(flag)) {
                count++;
            }
        }
        return count == 0 ? context.getString(R.string.mobile_settings_hidden_none)
                : context.getResources().getQuantityString(R.plurals.mobile_settings_hidden_count, count, count);
    }

    private static final int[] HIDDEN_FLAGS = {
            MediaServiceData.CONTENT_WATCHED_HOME,
            MediaServiceData.CONTENT_WATCHED_SUBSCRIPTIONS,
            MediaServiceData.CONTENT_UPCOMING_HOME,
            MediaServiceData.CONTENT_UPCOMING_SUBSCRIPTIONS,
            MediaServiceData.CONTENT_UPCOMING_CHANNEL,
            MediaServiceData.CONTENT_STREAMS_SUBSCRIPTIONS,
            MediaServiceData.CONTENT_WATCHED_WATCH_LATER,
    };

    static SettingsPages.Page hidden(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        MediaServiceData data = MediaServiceData.instance();

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_hidden_watched)));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_watched_home, 0, MediaServiceData.CONTENT_WATCHED_HOME));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_watched_subscriptions, 0, MediaServiceData.CONTENT_WATCHED_SUBSCRIPTIONS));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_watched_later,
                R.string.mobile_settings_hidden_watched_later_summary, MediaServiceData.CONTENT_WATCHED_WATCH_LATER));
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_hidden_watched_note)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_hidden_upcoming)));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_upcoming_home, 0, MediaServiceData.CONTENT_UPCOMING_HOME));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_upcoming_subscriptions, 0, MediaServiceData.CONTENT_UPCOMING_SUBSCRIPTIONS));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_upcoming_channels, 0, MediaServiceData.CONTENT_UPCOMING_CHANNEL));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_hidden_live)));
        rows.add(hiddenToggle(context, data, R.string.mobile_settings_hidden_live_subscriptions, 0, MediaServiceData.CONTENT_STREAMS_SUBSCRIPTIONS));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_hidden), rows);
    }

    private static SettingsRow hiddenToggle(Context context, MediaServiceData data, int titleRes, int summaryRes, int flag) {
        return SettingsRow.toggle(context.getString(titleRes), summaryRes != 0 ? context.getString(summaryRes) : null,
                () -> data.isContentHidden(flag), on -> data.setContentHidden(flag, on));
    }

    /**
     * The "⋮" menu on videos and the long-press menu on tabs: one switch per item, grouped by what
     * it does instead of the menu's order. Left out: items whose switch the phone ignores (Return to
     * background video and Open playlist always show when they apply; Move section up follows Move
     * section down), and the ones PhoneOnlyPrefs keeps off: two broken there (Open comments, Pause
     * history) and three from the TV (QR code, the TV's account picker, Check for updates, in About).
     * No per-item position picker: "Usual order" puts back the phone's order.
     */
    static SettingsPages.Page videoMenu(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        MainUIData data = MainUIData.instance(context);

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_video_menu_note)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_play)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PLAY_NEXT, R.string.play_next);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_ADD_TO_QUEUE, R.string.mobile_settings_menu_queue_add);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_SHOW_QUEUE, R.string.action_playback_queue);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PLAY_VIDEO_INCOGNITO, R.string.play_video_incognito);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PLAY_FROM_START, R.string.play_from_start);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PLAY_VIDEO, R.string.play_video);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_STREAM_REMINDER, R.string.set_stream_reminder);

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_save)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER, R.string.add_video_to_watch_later);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_ADD_TO_PLAYLIST, R.string.dialog_add_to_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_RECENT_PLAYLIST, R.string.mobile_settings_menu_recent_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_ADD_TO_NEW_PLAYLIST, R.string.add_video_to_new_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_DOWNLOAD, R.string.dialog_download);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_MARK_AS_WATCHED, R.string.mark_as_watched);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_REMOVE_FROM_HISTORY, R.string.remove_from_history);

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_channel)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_OPEN_CHANNEL, R.string.open_channel);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_SUBSCRIBE, R.string.subscribe_to_channel);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PIN_TO_SIDEBAR, R.string.pin_unpin_from_sidebar);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_NOT_INTERESTED, R.string.not_interested);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_NOT_RECOMMEND_CHANNEL, R.string.not_recommend_channel);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_REMOVE_FROM_SUBSCRIPTIONS, R.string.mobile_settings_menu_hide_subscriptions);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_BLOCK_CHANNEL, R.string.dialog_block_channel);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_EXCLUDE_FROM_CONTENT_BLOCK, R.string.content_block_exclude_channel);

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_share)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_SHARE_LINK, R.string.share_link);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_SHARE_EMBED_LINK, R.string.share_embed_link);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_OPEN_DESCRIPTION, R.string.action_video_info);

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_playlists)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_SAVE_REMOVE_PLAYLIST, R.string.mobile_settings_menu_save_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_CREATE_PLAYLIST, R.string.create_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_RENAME_PLAYLIST, R.string.rename_playlist);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_PLAYLIST_ORDER, R.string.playlist_order);

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_menu_tabs)));
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_MOVE_SECTION_DOWN, R.string.mobile_settings_menu_move_section);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_RENAME_SECTION, R.string.rename_section);
        menuToggle(context, rows, data, MainUIData.MENU_ITEM_CLEAR_HISTORY, R.string.clear_history);
        for (ContextMenuProvider provider : new ContextMenuManager(context).getProviders()) {
            menuToggle(context, rows, data, provider.getId(), provider.getTitleResId());
        }

        // Shown only to people whose menu was reordered (by the old per-item picker).
        List<Long> stock = data.getDefaultMenuItemsOrder();
        List<Long> usual = CardMenuMigration.phoneOrder(stock);
        List<Long> current = data.getMenuItemsOrdered();
        if (current.size() < usual.size() || !current.subList(0, usual.size()).equals(usual)) {
            rows.add(SettingsRow.divider());
            rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_menu_usual_order),
                    context.getString(R.string.mobile_settings_menu_usual_order_summary),
                    page -> {
                        data.setMenuItemsOrder(usual);
                        page.rebuild();
                        MobileSnackbar.show(context, R.string.mobile_settings_menu_usual_order_done);
                    }));
        }

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_video_menu), rows);
    }

    private static void menuToggle(Context context, List<SettingsRow> rows, MainUIData data, long item, int titleRes) {
        rows.add(SettingsRow.toggle(context.getString(titleRes), null,
                () -> data.isMenuItemEnabled(item),
                on -> {
                    if (on) {
                        data.setMenuItemEnabled(item);
                    } else {
                        data.setMenuItemDisabled(item);
                    }
                }));
    }

    // ---------------------------------------------------------------------------------------------
    // History and privacy
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page privacy(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        GeneralData generalData = GeneralData.instance(context);
        SearchData searchData = SearchData.instance(context);
        boolean signedIn = MediaServiceManager.instance().getSelectedAccount() != null;

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_watch_history))
                .option(context.getString(R.string.mobile_settings_watch_history_auto),
                        context.getString(R.string.mobile_settings_watch_history_auto_desc), GeneralData.HISTORY_AUTO)
                .option(context.getString(R.string.mobile_settings_watch_history_on),
                        context.getString(R.string.mobile_settings_watch_history_on_desc), GeneralData.HISTORY_ENABLED)
                .option(context.getString(R.string.mobile_settings_watch_history_off),
                        context.getString(R.string.mobile_settings_watch_history_off_desc), GeneralData.HISTORY_DISABLED)
                .bind(generalData::getHistoryState, state -> {
                    generalData.setHistoryState(state);
                    MediaServiceManager.instance().enableHistory(state == GeneralData.HISTORY_AUTO || state == GeneralData.HISTORY_ENABLED);
                }));

        if (signedIn) {
            rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_clear_watch_history),
                    context.getString(R.string.mobile_settings_clear_watch_history_summary),
                    page -> confirm(page, R.string.mobile_settings_clear_watch_history_confirm,
                            R.string.mobile_settings_clear_watch_history_body, R.string.mobile_settings_clear,
                            () -> MediaServiceManager.instance().clearHistory(context, () ->
                                    MobileSnackbar.show(context, R.string.mobile_settings_cleared)))));
        }

        rows.add(SettingsRow.divider());

        // The searches NewTube keeps on the phone (signed in or not); Search also clears them
        // whenever it closes while this is on, as the old dialog did when it closed.
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_search_history_off),
                context.getString(R.string.mobile_settings_search_history_off_summary),
                searchData::isSearchHistoryDisabled, off -> {
                    searchData.setSearchHistoryDisabled(off);
                    if (off) {
                        MediaServiceManager.instance().clearSearchHistory();
                    }
                }));

        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_clear_search_history),
                context.getString(R.string.mobile_settings_clear_search_history_summary),
                page -> confirm(page, R.string.mobile_settings_clear_search_history_confirm,
                        R.string.mobile_settings_clear_search_history_body, R.string.mobile_settings_clear,
                        () -> {
                            MediaServiceManager.instance().clearSearchHistory();
                            MobileSnackbar.show(context, R.string.mobile_settings_cleared);
                        })));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_privacy), rows);
    }

    /** A confirmation whose button repeats the verb ("Clear"), red because it deletes something. */
    static void confirm(@NonNull SettingsPageFragment page, int titleRes, int bodyRes, int buttonRes, Runnable onConfirm) {
        page.showDialog(new MaterialAlertDialogBuilder(page.requireContext(), R.style.MobileAlertDialog)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(buttonRes, (dialog, which) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    // ---------------------------------------------------------------------------------------------
    // Backup
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page backup(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        GeneralData generalData = GeneralData.instance(context);
        // Read without BackupSettingsPresenter: while that exists the auto-backup worker only
        // retries (the TV dialog released it on close), so it is created for an action and let go.
        BackupAndRestoreManager paths = new BackupAndRestoreManager(context);
        String backupPath = paths.getBackupRootPath();
        String restorePath = paths.getRestoreRootPath();

        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_backup_now),
                backupPath != null ? context.getString(R.string.mobile_settings_backup_now_summary, shortPath(backupPath)) : null,
                page -> {
                    BackupSettingsPresenter.instance(page.requireContext()).backupLocal();
                    BackupSettingsPresenter.unhold();
                    MobileSnackbar.show(context, R.string.mobile_settings_backup_done);
                }));

        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_restore),
                restorePath != null ? context.getString(R.string.mobile_settings_restore_summary, shortPath(restorePath)) : null,
                page -> {
                    BackupSettingsPresenter.instance(page.requireContext()).restoreLocal();
                    BackupSettingsPresenter.unhold();
                }));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_auto_backup))
                .option(context.getString(R.string.mobile_settings_auto_backup_off), -1)
                .option(context.getString(R.string.once_a_day), 1)
                .option(context.getString(R.string.once_a_week), 7)
                .option(context.getString(R.string.once_a_month), 30)
                .bind(generalData::getLocalDriveBackupFreqDays,
                        days -> {
                            BackupSettingsPresenter.instance(context).setAutoBackupDays(days);
                            BackupSettingsPresenter.unhold();
                        }));

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_backup_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_backup), rows);
    }

    /**
     * "/storage/emulated/0/Documents/SmartTubeBackup/<pkg>_<date>.zip" -> "Documents/SmartTubeBackup":
     * the folder to look in, not the file name of the next backup.
     */
    private static String shortPath(String path) {
        if (path.endsWith(".zip") && path.lastIndexOf('/') > 0) {
            path = path.substring(0, path.lastIndexOf('/'));
        }
        String prefix = "/storage/emulated/0/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }
}
