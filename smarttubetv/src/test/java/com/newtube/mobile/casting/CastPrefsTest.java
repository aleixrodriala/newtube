package com.newtube.mobile.casting;

import android.app.Application;
import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CastPrefsTest {
    private Context context;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("newtube_cast", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void legacyDataSurvivesWithoutAnInventedAdFreeClaim() {
        context.getSharedPreferences("newtube_cast", Context.MODE_PRIVATE).edit()
                .putString("paired_screens", "legacy\u0001SmartTube").commit();
        CastTarget target = CastPrefs.getPairedTargets(context).get(0);
        assertEquals("legacy", target.getScreen().getScreenId());
        assertEquals(CastTarget.ReceiverApp.UNKNOWN, target.getReceiverApp());
        assertFalse(target.isAdFree());
    }

    @Test public void youtubePairingAndNameRefreshDoNotOverwriteSmarttubeIdentity() {
        CastPrefs.addPairedScreen(context, new CastScreen("smart", "TV"), CastTarget.ReceiverApp.SMARTTUBE);
        CastPrefs.addPairedScreen(context, new CastScreen("yt", "TV"), CastTarget.ReceiverApp.YOUTUBE);
        CastPrefs.addPairedScreen(context, new CastScreen("smart", "Renamed"));
        assertEquals(2, CastPrefs.getPairedTargets(context).size());
        CastTarget smart = CastPrefs.getPairedTargets(context).get(1);
        assertTrue(smart.isAdFree());
        assertEquals("Renamed", smart.getName());
    }

    @Test public void removingAPairingRemovesItsAppIdentityToo() {
        CastPrefs.addPairedScreen(context, new CastScreen("id", "TV"), CastTarget.ReceiverApp.SMARTTUBE);
        CastPrefs.removePairedScreen(context, "id");
        CastPrefs.addPairedScreen(context, new CastScreen("id", "TV"));
        assertEquals(CastTarget.ReceiverApp.UNKNOWN, CastPrefs.getPairedTargets(context).get(0).getReceiverApp());
    }
}
