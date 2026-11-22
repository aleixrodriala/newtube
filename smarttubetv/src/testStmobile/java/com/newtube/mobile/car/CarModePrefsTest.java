package com.newtube.mobile.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CarModePrefsTest {
    @Test
    public void passengerModeIsOnByDefault() {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(CarModePrefs.isPassengerModeEnabled(context));
    }

    @Test
    public void passengerModeCanBeTurnedOffAndOn() {
        Context context = RuntimeEnvironment.getApplication();
        CarModePrefs.setPassengerModeEnabled(context, false);
        assertFalse(CarModePrefs.isPassengerModeEnabled(context));
        CarModePrefs.setPassengerModeEnabled(context, true);
        assertTrue(CarModePrefs.isPassengerModeEnabled(context));
    }
}
