package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class MobilePlaybackStillTest {
    @Test
    public void readyStreamRemovesItsStillWithoutAdvancingTimeOrRunningAnAnimation() {
        ImageView still = new ImageView(RuntimeEnvironment.getApplication());
        still.setImageDrawable(new ColorDrawable(Color.BLACK));
        still.setVisibility(View.VISIBLE);
        still.setAlpha(0.8f);

        assertTrue(MobilePlaybackActivity.hideLoadingStillImmediately(still));

        // No looper/clock advance: a scheduled fade would leave this VISIBLE until its end action.
        assertEquals(View.GONE, still.getVisibility());
        assertEquals(1f, still.getAlpha(), 0f);
        assertNull(still.getDrawable());
    }

    @Test
    public void repeatedTextureFramesDoNotReportAnotherReveal() {
        ImageView still = new ImageView(RuntimeEnvironment.getApplication());
        assertTrue(MobilePlaybackActivity.hideLoadingStillImmediately(still));
        assertFalse(MobilePlaybackActivity.hideLoadingStillImmediately(still));
    }

    @Test
    public void absentOrAlreadyHiddenStillHasNoVisibilityMilestone() {
        assertFalse(MobilePlaybackActivity.hideLoadingStillImmediately(null));
        ImageView still = new ImageView(RuntimeEnvironment.getApplication());
        still.setVisibility(View.INVISIBLE);
        assertFalse(MobilePlaybackActivity.hideLoadingStillImmediately(still));
        assertEquals(View.INVISIBLE, still.getVisibility());
    }
}
