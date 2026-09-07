package com.newtube.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App-specific local-decoder startup profile; it does not cover service extraction or the watch UI. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaybackBaselineProfileGenerator {
    @get:Rule val profiles = BaselineProfileRule()
    private var targetValidated = false

    @Before fun validateTarget() {
        FixtureWorkload.checkTarget()
        targetValidated = true
    }
    @After fun stopFixture() {
        if (targetValidated) FixtureWorkload.stop()
    }

    @Test fun offlinePlaybackProfile() {
        profiles.collect(
            packageName = FixtureWorkload.packageName,
            maxIterations = 5,
            stableIterations = 2,
            strictStability = true,
            outputFilePrefix = "newtube-offline-playback",
            // A fixture's route is not the production launcher/deep-link journey.
            includeInStartupProfile = false,
            filterPredicate = FixtureWorkload::appRule,
        ) {
            pressHome()
            FixtureWorkload.start(this)
        }
    }
}
