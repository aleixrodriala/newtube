package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.ATVBridgePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AmazonBridgePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneUi;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class AboutSimpleSettingsPresenter extends BasePresenter<Void> {
    private final AppUpdateChecker mUpdateChecker;

    public AboutSimpleSettingsPresenter(Context context) {
        super(context);

        mUpdateChecker = new AppUpdateChecker(getContext(), null);
    }

    public static AboutSimpleSettingsPresenter instance(Context context) {
        return new AboutSimpleSettingsPresenter(context);
    }

    public void show() {
        if (PhoneUi.isEnabled()) {
            showPhone();
            return;
        }

        String mainTitle = String.format("%s %s",
                getContext().getString(R.string.app_name) + " MOD",
                AppInfoHelpers.getAppVersionName(getContext()));

        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendAutoUpdateSwitch(settingsPresenter);

        appendUpdateCheckButton(settingsPresenter);

        appendInstallBridge(settingsPresenter);

        settingsPresenter.showDialog(mainTitle);
    }

    /**
     * NEWTUBE(settings): the phone's About. The TV one is titled "NewTube MOD 1.9.0" (the "MOD"
     * hard-coded for upstream's unofficial builds) and offers "Enable global search (firmware
     * support needed)" - an installer for the Android TV / Fire TV voice-search bridge apps. Here:
     * the version on the update row, the update switch, and where the source and licence live.
     */
    private void showPhone() {
        String mainTitle = getContext().getString(R.string.about_app, getContext().getString(R.string.app_name));

        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.check_for_updates),
                String.format("%s %s", getContext().getString(R.string.app_name),
                        AppInfoHelpers.getAppVersionName(getContext())),
                option -> AppUpdatePresenter.instance(getContext()).start(true)));

        appendAutoUpdateSwitch(settingsPresenter);

        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.about_source_code),
                getContext().getString(R.string.about_source_code_url).replace("https://", ""),
                option -> Utils.openLinkExt(getContext(), getContext().getString(R.string.about_source_code_url))));
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.about_license),
                getContext().getString(R.string.about_license_name),
                option -> Utils.openLinkExt(getContext(), getContext().getString(R.string.about_license_url))));

        settingsPresenter.showDialog(mainTitle);
    }

    private void appendAutoUpdateSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.check_updates_auto), optionItem -> {
            mUpdateChecker.setUpdateCheckEnabled(optionItem.isSelected());
        }, mUpdateChecker.isUpdateCheckEnabled()));
    }

    private void appendUpdateCheckButton(AppDialogPresenter settingsPresenter) {
        OptionItem updateCheckOption = UiOptionItem.from(
                getContext().getString(R.string.check_for_updates),
                option -> AppUpdatePresenter.instance(getContext()).start(true));

        settingsPresenter.appendSingleButton(updateCheckOption);
    }

    private void appendInstallBridge(AppDialogPresenter settingsPresenter) {
        OptionItem installBridgeOption = UiOptionItem.from(
                getContext().getString(R.string.enable_voice_search),
                option -> startBridgePresenter());

        settingsPresenter.appendSingleButton(installBridgeOption);
    }

    private void startBridgePresenter() {
        MessageHelpers.showLongMessage(getContext(), R.string.enable_voice_search_desc);

        ATVBridgePresenter atvPresenter = ATVBridgePresenter.instance(getContext());
        atvPresenter.runBridgeInstaller(true);
        atvPresenter.unhold();

        AmazonBridgePresenter amazonPresenter = AmazonBridgePresenter.instance(getContext());
        amazonPresenter.runBridgeInstaller(true);
        amazonPresenter.unhold();
    }
}
