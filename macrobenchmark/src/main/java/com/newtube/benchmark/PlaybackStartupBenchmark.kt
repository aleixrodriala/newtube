package com.newtube.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
@OptIn(ExperimentalMetricApi::class)
class PlaybackStartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private var targetValidated = false

    @Before fun validateTarget() {
        FixtureWorkload.checkTarget()
        targetValidated = true
    }
    @After fun stopFixture() {
        if (targetValidated) FixtureWorkload.stop()
    }

    @OptIn(ExperimentalMacrobenchmarkApi::class) // CompilationMode.Ignore preserves existing ART state.
    @Test fun coldOfflinePlayback() {
        val iterations = (FixtureWorkload.arguments.getString("iterations") ?: "5").toInt()
        require(iterations in 1..30)
        val mode = when (FixtureWorkload.arguments.getString("compilation", "keep")) {
            "keep" -> CompilationMode.Ignore()
            "none" -> CompilationMode.None()
            "baseline" -> CompilationMode.Partial(BaselineProfileMode.Require)
            else -> error("Unknown compilation mode")
        }
        benchmark.measureRepeated(
            packageName = FixtureWorkload.packageName,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric("NewTubeFixture.firstFrame", TraceSectionMetric.Mode.First),
                TraceSectionMetric("NewTubeFixture.ready", TraceSectionMetric.Mode.First),
            ),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = iterations,
            setupBlock = { pressHome() },
            measureBlock = { FixtureWorkload.start(this) },
        )
    }
}
