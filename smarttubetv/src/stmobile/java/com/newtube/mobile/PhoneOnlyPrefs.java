package com.newtube.mobile;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.prefs.AccountsData;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SearchData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * NEWTUBE(settings): values the phone pins, because the phone Settings (issue #2) have no row for
 * them and the phone reads them only to do harm or to do something other than what the TV row
 * said: the Oculus fix (landscape-locks every screen), "Ambilight"/TextureView (stops SponsorBlock
 * skipping near a segment's end), the auto-hide timeout (could only hide the controls sooner), the
 * likes counter (it only gated the dislike fetch), Channels' old look and its auto-load (they change
 * what a tap on a channel does), "Fullscreen mode" (unticked it adds a TV inset theme), and two card
 * menu items that are broken on the phone (Open comments shows a stub; Pause history never pauses).
 *
 * <p>And the TV's ones the phone Settings dropped (the owner's call, 2026-10-02: "if there's a few
 * settings that don't make sense because they were from the tv app ... we just remove it"), off:
 * child mode and the start-up, Settings and account passwords (anyone could clear or get past them
 * on the phone, and child mode never locked what it said), the account picker on start (the TV's),
 * and three card menu items (the QR code to scan with a phone, the TV's account picker, Check for
 * updates, which is in About).</p>
 *
 * <p>Applied at every start and whenever the settings profile changes (with "separate settings
 * per account" each account has its own copy), writing only what differs. A one-shot migration
 * didn't hold: the prefs classes save 10 s after a change, so its marker could outlive values that
 * were never saved, and it covered only the profile that was active.</p>
 */
final class PhoneOnlyPrefs implements AppPrefs.ProfileChangeListener {
    private static final int UI_HIDE_TIMEOUT_SEC = 3;
    private static final long[] OFF_MENU_ITEMS = {MainUIData.MENU_ITEM_OPEN_COMMENTS, MainUIData.MENU_ITEM_TOGGLE_HISTORY,
            MainUIData.MENU_ITEM_SHARE_QR_LINK, MainUIData.MENU_ITEM_SELECT_ACCOUNT, MainUIData.MENU_ITEM_UPDATE_CHECK};

    private final Context mContext;

    PhoneOnlyPrefs(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Applies the values now and again on every profile change (keep a reference: listeners are weak). */
    void install() {
        apply();
        AppPrefs.instance(mContext).addListener(this);
    }

    @Override
    public void onProfileChanged() {
        apply();
    }

    private void apply() {
        PlayerTweaksData tweaks = PlayerTweaksData.instance(mContext);
        if (tweaks.isOculusQuestFixEnabled() && !Utils.isOculusQuest()) {
            tweaks.setOculusQuestFixEnabled(false);
        }
        if (tweaks.isTextureViewEnabled()) {
            tweaks.setTextureViewEnabled(false);
        }
        if (!tweaks.isLikesCounterEnabled()) {
            tweaks.setLikesCounterEnabled(true);
        }

        PlayerData player = PlayerData.instance(mContext);
        if (player.getUiHideTimeoutSec() != UI_HIDE_TIMEOUT_SEC) {
            player.setUiHideTimeoutSec(UI_HIDE_TIMEOUT_SEC);
        }

        GeneralData general = GeneralData.instance(mContext);
        if (general.isChildModeEnabled()) {
            turnOffChildMode(); // before the menu below: it puts the card menu back
        }

        MainUIData ui = MainUIData.instance(mContext);
        CardMenuMigration.enableQueueActionsOnce(ui, AppPrefs.instance(mContext));
        if (ui.isUploadsOldLookEnabled()) {
            ui.setUploadsOldLookEnabled(false);
        }
        if (!ui.isUploadsAutoLoadEnabled()) {
            ui.setUploadsAutoLoadEnabled(true);
        }
        for (long item : OFF_MENU_ITEMS) {
            if (ui.isMenuItemEnabled(item)) {
                ui.setMenuItemDisabled(item);
            }
        }

        if (!general.isFullscreenModeEnabled()) {
            general.setFullscreenModeEnabled(true);
        }
        if (general.getMasterPassword() != null) {
            general.setMasterPassword(null);
        }
        if (general.getSettingsPassword() != null) {
            general.setSettingsPassword(null);
        }

        AccountsData accounts = AccountsData.instance(mContext);
        if (accounts.hasAccountPasswords()) {
            accounts.clearAccountPasswords();
        }
        if (accounts.isSelectAccountOnBootEnabled()) {
            accounts.selectAccountOnBoot(false);
        }
    }

    /**
     * Undoes what turning child mode on did (GeneralSettingsPresenter.enableChildMode), the phone's
     * way: the card menu back to the phone's default, Home back on (the Explore tabs it switched off
     * stay a choice on the Tabs page; Shorts don't exist on the phone), Up next and autoplay back,
     * popular searches back.
     */
    private void turnOffChildMode() {
        GeneralData general = GeneralData.instance(mContext);
        MainUIData ui = MainUIData.instance(mContext);
        general.setChildModeEnabled(false);
        // The same bits turning it on cleared (context-menu providers sit above them), then the
        // phone's menu: the stock items plus Share, in the phone's order.
        ui.setMenuItemDisabled(Integer.MAX_VALUE);
        ui.setMenuItemEnabled(MainUIData.MENU_ITEM_DEFAULT | MainUIData.MENU_ITEM_SHARE_LINK);
        ui.setMenuItemsOrder(CardMenuMigration.phoneOrder(ui.getDefaultMenuItemsOrder()));
        ui.setTopButtonEnabled(MainUIData.TOP_BUTTON_DEFAULT);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(mContext);
        tweaks.setPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_DEFAULT);
        tweaks.setSuggestionsDisabled(false);
        PlayerData.instance(mContext).setPlaybackMode(PlayerConstants.PLAYBACK_MODE_ALL);
        SearchData.instance(mContext).setPopularSearchesDisabled(false);
        // Not BrowsePresenter's: this runs before any screen exists, which then reads the sections.
        SidebarService.instance(mContext).enableSection(MediaGroup.TYPE_HOME, true);
    }
}
