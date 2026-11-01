package com.newtube.mobile;

import static org.junit.Assert.*;
import android.app.Application;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueueMenuMigrationTest {
    private MainUIData ui;
    private AppPrefs prefs;
    @Before public void before() {
        prefs = AppPrefs.instance(RuntimeEnvironment.getApplication());
        prefs.setProfileData("queue_append_menu_v2", "0");
        assertEquals("0", prefs.getProfileData("queue_append_menu_v2"));
        ui = MainUIData.instance(RuntimeEnvironment.getApplication());
    }
    @Test public void appendActionIsEnabledNextToPlayNextWithoutResettingOtherChoices() {
        ui.setMenuItemEnabled(MainUIData.MENU_ITEM_SHOW_QUEUE);
        ui.setMenuItemDisabled(MainUIData.MENU_ITEM_DOWNLOAD | MainUIData.MENU_ITEM_ADD_TO_QUEUE);
        CardMenuMigration.enableQueueActionsOnce(ui, prefs);
        assertTrue(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE));
        assertTrue(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_SHOW_QUEUE));
        assertFalse(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_DOWNLOAD));
        java.util.List<Long> order = ui.getMenuItemsOrdered();
        assertEquals(order.indexOf(MainUIData.MENU_ITEM_PLAY_NEXT) + 1,
                order.indexOf(MainUIData.MENU_ITEM_ADD_TO_QUEUE));
        ui.setMenuItemDisabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE);
        CardMenuMigration.enableQueueActionsOnce(ui, prefs);
        assertFalse(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE));
    }
    @Test public void freshMenuStillGetsTheExistingShareMigration() {
        ui.setMenuItemDisabled(-1L); ui.setMenuItemEnabled(MainUIData.MENU_ITEM_DEFAULT);
        ui.setMenuItemsOrder(ui.getDefaultMenuItemsOrder());
        CardMenuMigration.enableQueueActionsOnce(ui, prefs);
        assertTrue(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_LINK));
        assertTrue(ui.isMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_QUEUE));
    }
    @Test public void menuOrderingDoesNotMutateItsInput() {
        java.util.List<Long> original = Arrays.asList(MainUIData.MENU_ITEM_ADD_TO_QUEUE,
                MainUIData.MENU_ITEM_PLAY_NEXT, MainUIData.MENU_ITEM_DOWNLOAD);
        java.util.List<Long> result = CardMenuMigration.appendAfterPlayNext(original);
        assertEquals(Long.valueOf(MainUIData.MENU_ITEM_ADD_TO_QUEUE), original.get(0));
        assertEquals(Arrays.asList(MainUIData.MENU_ITEM_PLAY_NEXT, MainUIData.MENU_ITEM_ADD_TO_QUEUE,
                MainUIData.MENU_ITEM_DOWNLOAD), result);
    }
}
