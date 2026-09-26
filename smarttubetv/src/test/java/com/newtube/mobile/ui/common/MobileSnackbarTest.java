package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.dialog.MobileAppDialogActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;

/**
 * NEWTUBE(snackbar): a message sent while a menu sheet is in front ("Download started" from the
 * download picker) must reach the screen under the sheet whether or not that screen was paused
 * behind the translucent sheet. The Pixel (Android 17) showed neither a Snackbar nor its View
 * action for the watch page's download, while the API-36 emulator, which pauses the screen and
 * resumes it, did. The lifecycle is driven by hand so both orders can be replayed exactly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileSnackbarTest {
    private static final String TEXT = "Download started • 44.7 MB";

    /** MobileSnackbar installs once per process; keep the callbacks it registered. */
    private static Application.ActivityLifecycleCallbacks sCallbacks;

    private Activity host;
    private MobileAppDialogActivity sheet;

    @Before
    public void setUp() {
        if (sCallbacks == null) {
            MobileSnackbar.install(new Application() {
                @Override
                public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
                    sCallbacks = callback;
                }
            });
        }
        host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(R.style.Theme_NewTube);
        sheet = Robolectric.buildActivity(MobileAppDialogActivity.class).get();
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        sCallbacks.onActivityPaused(host);
        sCallbacks.onActivityStopped(host);
        idle(5_000); // lets any message still waiting end, so the next test starts clean
        host.finish();
    }

    @Test
    public void screenLeftResumedBehindTheSheetStillGetsTheSnackbar() {
        sCallbacks.onActivityResumed(host);
        sCallbacks.onActivityResumed(sheet); // Android 17: the host is NOT paused

        MobileSnackbar.show(host, TEXT, "View", () -> { });
        sCallbacks.onActivityPaused(sheet); // the sheet closes; the host gets no resume
        idle(400);

        assertEquals(1, countShown(TEXT));
        idle(2_000);
        assertNull("no fallback Toast on top of the Snackbar", ShadowToast.getLatestToast());
    }

    @Test
    public void screenPausedBehindTheSheetGetsItOnResumeOnlyOnce() {
        sCallbacks.onActivityResumed(host);
        sCallbacks.onActivityPaused(host); // API 36: paused under the translucent sheet
        sCallbacks.onActivityResumed(sheet);

        MobileSnackbar.show(host, TEXT, "View", () -> { });
        sCallbacks.onActivityPaused(sheet);
        idle(400);
        assertEquals("waits for the host to resume", 0, countShown(TEXT));

        sCallbacks.onActivityResumed(host);
        idle(400);
        assertEquals(1, countShown(TEXT));
        idle(2_000);
        assertEquals(1, countShown(TEXT));
        assertNull(ShadowToast.getLatestToast());
    }

    @Test
    public void sheetStillOpenKeepsTheMessageForLater() {
        sCallbacks.onActivityResumed(host);
        sCallbacks.onActivityResumed(sheet);

        MobileSnackbar.show(host, TEXT, "View", () -> { });
        idle(400);
        assertEquals("not under an open sheet", 0, countShown(TEXT));
        sCallbacks.onActivityPaused(sheet);
        idle(400);
        assertEquals(1, countShown(TEXT));
    }

    @Test
    public void nothingOnScreenFallsBackToTheToast() {
        sCallbacks.onActivityResumed(host);
        sCallbacks.onActivityResumed(sheet);
        MobileSnackbar.show(host, TEXT, "View", () -> { });
        sCallbacks.onActivityPaused(sheet);
        sCallbacks.onActivityPaused(host);
        sCallbacks.onActivityStopped(host); // the app went to the background
        idle(2_000);

        assertEquals(0, countShown(TEXT));
        assertEquals(TEXT, ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void resumeRunningLateBehindABusyMainThreadStillEndsInTheToast() {
        sCallbacks.onActivityResumed(host);
        sCallbacks.onActivityPaused(host);
        sCallbacks.onActivityResumed(sheet);
        MobileSnackbar.show(host, TEXT, "View", () -> { });
        sCallbacks.onActivityPaused(sheet);

        // The main thread is blocked past the message's age; the host's resume, queued before the
        // fallback Toast's time, runs first when it frees up.
        ShadowSystemClock.advanceBy(Duration.ofMillis(3_500));
        sCallbacks.onActivityResumed(host);
        idle(0);

        assertEquals(TEXT, ShadowToast.getTextOfLatestToast());
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private int countShown(String text) {
        return count(host.findViewById(android.R.id.content), text);
    }

    private static int count(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())
                && view.isShown()) {
            return 1;
        }
        int n = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                n += count(group.getChildAt(i), text);
            }
        }
        return n;
    }
}
