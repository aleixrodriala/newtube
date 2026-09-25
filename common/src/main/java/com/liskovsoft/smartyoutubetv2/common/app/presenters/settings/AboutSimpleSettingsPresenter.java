package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
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

    /**
     * NEWTUBE(settings): the phone's About. Was titled "NewTube MOD 1.9.0" (the "MOD" hard-coded for
     * upstream's unofficial builds) and offered "Enable global search (firmware support needed)" -
     * an installer for the Android TV / Fire TV voice-search bridge apps. Now: the version on the
     * update row, the update switch, and where the source and licence live.
     */
    public void show() {
        String mainTitle = getContext().getString(R.string.about_app, getContext().getString(R.string.app_name));

        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendUpdateCheckButton(settingsPresenter);

        appendAutoUpdateSwitch(settingsPresenter);

        appendLinks(settingsPresenter);

        settingsPresenter.showDialog(mainTitle);
    }

    private void appendLinks(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.about_source_code),
                getContext().getString(R.string.about_source_code_url).replace("https://", ""),
                option -> Utils.openLinkExt(getContext(), getContext().getString(R.string.about_source_code_url))));
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.about_license),
                getContext().getString(R.string.about_license_name),
                option -> Utils.openLinkExt(getContext(), getContext().getString(R.string.about_license_url))));
    }

    private void appendAutoUpdateSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.check_updates_auto), optionItem -> {
            mUpdateChecker.setUpdateCheckEnabled(optionItem.isSelected());
        }, mUpdateChecker.isUpdateCheckEnabled()));
    }

    private void appendUpdateCheckButton(AppDialogPresenter settingsPresenter) {
        OptionItem updateCheckOption = UiOptionItem.from(
                getContext().getString(R.string.check_for_updates),
                String.format("%s %s", getContext().getString(R.string.app_name),
                        AppInfoHelpers.getAppVersionName(getContext())),
                option -> AppUpdatePresenter.instance(getContext()).start(true));

        settingsPresenter.appendSingleButton(updateCheckOption);
    }
}
