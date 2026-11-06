package com.newtube.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33}, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LauncherIdentityTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final PackageManager pm = context.getPackageManager();

    @Test
    public void freshInstallHasOnlyTheOriginalLauncher() {
        assertFalse(LauncherIdentity.isYouTube(context));
        assertLauncher("NewTube");
    }

    @Test
    public void switchesNameAndIconTogetherInBothDirections() throws Exception {
        int originalIcon = launcher().getIconResource();
        assertTrue(LauncherIdentity.setYouTube(context, true));
        assertTrue(LauncherIdentity.isYouTube(context));
        assertLauncher("YouTube");
        assertTrue(originalIcon != launcher().getIconResource());

        // The target remains enabled for explicit launches and shares.
        ActivityInfo target = pm.getActivityInfo(new ComponentName(context,
                "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"), 0);
        assertTrue(target.enabled);
        assertFalse(LauncherIdentity.restore(context));
        assertLauncher("YouTube");

        assertTrue(LauncherIdentity.setYouTube(context, false));
        assertFalse(LauncherIdentity.isYouTube(context));
        assertLauncher("NewTube");
        assertEquals(originalIcon, launcher().getIconResource());
        assertFalse(LauncherIdentity.restore(context));
        assertLauncher("NewTube");
    }

    @Test
    public void repairsAnInterruptedOlderSwitchInEitherDirection() {
        for (boolean youtube : new boolean[] {true, false}) {
            // Process died after saving intent and enabling the destination, before disabling
            // the previous alias. Either icon can start the app and finish the switch.
            save(youtube);
            pm.setComponentEnabledSetting(alias("NewTube"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(alias("YouTube"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            assertTrue(LauncherIdentity.restore(context));
            assertLauncher(youtube ? "YouTube" : "NewTube");
        }
    }

    @Test
    public void restoresTheSavedChoiceWhenPackageOverridesWereNotBackedUp() {
        save(true);
        assertTrue(LauncherIdentity.restore(context));
        assertLauncher("YouTube");
    }

    @Test
    @Config(shadows = RejectDisablePackageManager.class)
    public void failedSwitchRestoresThePreviousChoiceAndLauncher() {
        RejectDisablePackageManager.rejectNextDisable = true;
        assertFalse(LauncherIdentity.setYouTube(context, true));
        assertFalse(LauncherIdentity.isYouTube(context));
        assertLauncher("NewTube");
        LauncherIdentity.restore(context);
        assertLauncher("NewTube");
    }

    private void save(boolean youtube) {
        assertTrue(context.getSharedPreferences("newtube_launcher", Context.MODE_PRIVATE)
                .edit().putBoolean("youtube", youtube).commit());
    }

    private ComponentName alias(String name) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".launcher." + name);
    }

    private ResolveInfo launcher() {
        List<ResolveInfo> launchers = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.getPackageName()), 0);
        assertEquals("Exactly one launcher entry", 1, launchers.size());
        return launchers.get(0);
    }

    private void assertLauncher(String name) {
        ResolveInfo launcher = launcher();
        assertEquals(alias(name).getClassName(), launcher.activityInfo.name);
        assertEquals(name, launcher.loadLabel(pm).toString());
        assertEquals("com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity",
                launcher.activityInfo.targetActivity);
        assertTrue(launcher.activityInfo.exported);
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    public static class RejectDisablePackageManager extends ShadowApplicationPackageManager {
        static boolean rejectNextDisable;

        @Implementation
        protected void setComponentEnabledSetting(ComponentName name, int state, int flags) {
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                rejectOnce();
            }
            super.setComponentEnabledSetting(name, state, flags);
        }

        @Implementation(minSdk = 33)
        protected void setComponentEnabledSettings(List<PackageManager.ComponentEnabledSetting> settings) {
            rejectOnce();
            super.setComponentEnabledSettings(settings);
        }

        private void rejectOnce() {
            if (rejectNextDisable) {
                rejectNextDisable = false;
                throw new SecurityException("Simulated PackageManager failure");
            }
        }
    }
}
