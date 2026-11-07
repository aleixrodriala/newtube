package com.newtube.mobile.ui.dialog;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** Presentation only: keep the presenter's eligible actions and callbacks intact. */
final class VideoMenuLayout {
    private static final int[] ORDER = {R.string.play_next, R.string.add_video_to_watch_later,
            R.string.dialog_add_to_playlist, R.string.dialog_download, R.string.share_link, R.string.not_interested};

    static List<OptionCategory> primary(List<OptionCategory> categories, CharSequence moreTitle,
                                        Consumer<List<OptionCategory>> showMore) {
        List<OptionCategory> primary = new ArrayList<>();
        List<OptionCategory> extras = new ArrayList<>(categories);
        for (int id : ORDER) {
            Iterator<OptionCategory> iterator = extras.iterator();
            while (iterator.hasNext()) {
                OptionCategory category = iterator.next();
                if (category.id == id) {
                    primary.add(category);
                    iterator.remove();
                }
            }
        }
        if (!extras.isEmpty()) {
            primary.add(OptionCategory.from(R.string.mobile_menu_more, OptionCategory.TYPE_SINGLE_BUTTON, null,
                    UiOptionItem.from(moreTitle, item -> showMore.accept(extras))));
        }
        return primary;
    }

    static int labelFor(int id) {
        if (id == R.string.play_next) return R.string.mobile_menu_play_next;
        if (id == R.string.dialog_add_to_playlist) return R.string.mobile_menu_save_playlist;
        if (id == R.string.dialog_download) return R.string.mobile_menu_download_video;
        if (id == R.string.share_link) return R.string.mobile_watch_share;
        return 0;
    }

    static int iconFor(int id) {
        if (id == R.string.play_next) return R.drawable.ic_menu_play_next;
        if (id == R.string.add_video_to_watch_later) return R.drawable.ic_nav_history_outline;
        if (id == R.string.dialog_add_to_playlist) return R.drawable.ic_menu_bookmark;
        if (id == R.string.dialog_download) return R.drawable.ic_nav_downloads_outline;
        if (id == R.string.share_link) return R.drawable.ic_watch_share;
        if (id == R.string.not_interested) return R.drawable.ic_menu_not_interested;
        if (id == R.string.mobile_menu_more) return R.drawable.ic_mobile_more_vert;
        return 0;
    }
}
