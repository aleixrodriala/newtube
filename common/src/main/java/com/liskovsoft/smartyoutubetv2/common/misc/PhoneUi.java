package com.liskovsoft.smartyoutubetv2.common.misc;

/**
 * NEWTUBE(phone-gate): "the phone app is running" for shared UI code - settings screens, card
 * menus, sharing - that the phone presents differently from the TV. Set once from
 * MobileMainApplication (same static-gate pattern as PlaybackPresenter.setPrefetchOnOpenEnabled,
 * ViewManager.setReorderToFrontEnabled, ...). The TV flavors never set it, so every branch keyed
 * on {@link #isEnabled()} leaves upstream/TV behaviour exactly as it was.
 */
public final class PhoneUi {
    private static volatile boolean sEnabled;

    private PhoneUi() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }
}
