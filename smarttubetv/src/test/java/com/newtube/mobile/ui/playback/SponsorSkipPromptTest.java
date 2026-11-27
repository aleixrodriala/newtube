package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SponsorBlockController;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tests SponsorBlock manual skip timeout calculation and floating overlay skip pill views.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SponsorSkipPromptTest {

    @Test
    public void computesTimeoutAdjustedByPlaybackSpeedAndClampedAtOneSecond() {
        assertEquals(30_000L, SponsorBlockController.computeSkipPromptTimeoutMs(40_000L, 10_000L, 1.0f));
        assertEquals(15_000L, SponsorBlockController.computeSkipPromptTimeoutMs(40_000L, 10_000L, 2.0f));
        assertEquals(60_000L, SponsorBlockController.computeSkipPromptTimeoutMs(40_000L, 10_000L, 0.5f));
        assertEquals(1_000L, SponsorBlockController.computeSkipPromptTimeoutMs(10_000L, 10_500L, 1.0f));
        assertEquals(40_000L, SponsorBlockController.computeSkipPromptTimeoutMs(20_000L, 10_000L, 0.0f));
    }

    @Test
    public void playbackLayoutIncludesSponsorSkipPillAndStringFormat() {
        Application context = RuntimeEnvironment.getApplication();
        ContextThemeWrapper themed = new ContextThemeWrapper(context, R.style.Theme_NewTube_Player);
        View root = LayoutInflater.from(themed).inflate(R.layout.activity_mobile_playback, new FrameLayout(themed), false);

        View skipButton = root.findViewById(R.id.mobile_player_sponsor_skip);
        View accentDot = root.findViewById(R.id.mobile_player_sponsor_skip_accent);
        TextView skipLabel = root.findViewById(R.id.mobile_player_sponsor_skip_label);

        assertNotNull(skipButton);
        assertNotNull(accentDot);
        assertNotNull(skipLabel);
        assertEquals(View.GONE, skipButton.getVisibility());
        assertEquals("Skip Sponsor", context.getString(R.string.mobile_player_sponsor_skip, "Sponsor"));
    }
}
