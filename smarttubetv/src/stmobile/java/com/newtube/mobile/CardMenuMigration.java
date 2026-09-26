package com.newtube.mobile;

import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(menu): one-shot reshaping of the card menu for the phone - Share on, right after
 * Download (YouTube's place for it), and "Block the channel" below "Play next" so the everyday
 * actions lead. Applied only to a menu that is still exactly as shipped
 * ({@link MainUIData#isMenuConfigDefault()}): a user who enabled, disabled or moved anything keeps
 * their menu untouched.
 */
final class CardMenuMigration {
    private CardMenuMigration() {
    }

    static void applyIfDefault(MainUIData ui) {
        if (!ui.isMenuConfigDefault()) {
            return;
        }
        ui.setMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_LINK);

        // MainUIData.setMenuItemIndex removes the item and re-inserts it at the index, so each step
        // passes the item's index in the list AFTER that step.
        List<Long> step1 = shareAfterDownload(ui.getMenuItemsOrdered());
        ui.setMenuItemIndex(step1.indexOf(MainUIData.MENU_ITEM_SHARE_LINK), MainUIData.MENU_ITEM_SHARE_LINK);
        List<Long> step2 = blockAfterPlayNext(step1);
        ui.setMenuItemIndex(step2.indexOf(MainUIData.MENU_ITEM_BLOCK_CHANNEL), MainUIData.MENU_ITEM_BLOCK_CHANNEL);
    }

    /** {@code order} with Share moved to right after Download (unchanged if Download is absent). */
    static List<Long> shareAfterDownload(List<Long> order) {
        return moveAfter(order, MainUIData.MENU_ITEM_SHARE_LINK, MainUIData.MENU_ITEM_DOWNLOAD, false);
    }

    /** {@code order} with Block moved to right after Play next - only ever DOWN the menu. */
    static List<Long> blockAfterPlayNext(List<Long> order) {
        return moveAfter(order, MainUIData.MENU_ITEM_BLOCK_CHANNEL, MainUIData.MENU_ITEM_PLAY_NEXT, true);
    }

    private static List<Long> moveAfter(List<Long> order, long item, long anchor, boolean onlyDown) {
        List<Long> result = new ArrayList<>(order);
        int itemIdx = result.indexOf(item);
        int anchorIdx = result.indexOf(anchor);
        if (anchorIdx < 0 || (onlyDown && (itemIdx < 0 || itemIdx > anchorIdx))) {
            return result;
        }
        result.remove(item);
        result.add(result.indexOf(anchor) + 1, item);
        return result;
    }
}
