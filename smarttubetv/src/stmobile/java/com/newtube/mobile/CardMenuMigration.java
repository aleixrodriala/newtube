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
public final class CardMenuMigration {
    private CardMenuMigration() {
    }

    /** The phone's menu order: the stock order with the two moves below (the Settings' "Usual order"). */
    public static List<Long> phoneOrder(List<Long> stockOrder) {
        return appendAfterPlayNext(blockAfterPlayNext(shareAfterDownload(stockOrder)));
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

    /** Enable the newly exposed append action once per profile; preserve later user choices. */
    static void enableQueueActionsOnce(MainUIData ui,
            com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs prefs) {
        if ("1".equals(prefs.getProfileData("queue_append_menu_v2"))) return;
        applyIfDefault(ui); // preserve the existing phone's fresh-install menu migration
        ui.setMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE);
        ui.setMenuItemsOrder(appendAfterPlayNext(ui.getMenuItemsOrdered()));
        prefs.setProfileData("queue_append_menu_v2", "1");
    }

    static List<Long> appendAfterPlayNext(List<Long> order) {
        return moveAfter(order, MainUIData.MENU_ITEM_ADD_TO_QUEUE, MainUIData.MENU_ITEM_PLAY_NEXT, false);
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
