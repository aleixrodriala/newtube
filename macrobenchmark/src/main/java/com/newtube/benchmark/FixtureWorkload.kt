package com.newtube.benchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal object FixtureWorkload {
    private const val ACTIVITY = "com.newtube.mobile.player.BenchmarkPlaybackActivity"
    val arguments get() = InstrumentationRegistry.getArguments()
    val packageName: String
        get() = arguments.getString("targetPackage") ?: "io.github.aleixrodriala.arc"
    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun checkTarget() {
        check(Build.VERSION.SDK_INT >= 34) {
            "API 34+ is required so compilation resets cannot reinstall the user's app"
        }
        check(packageName == "io.github.aleixrodriala.arc"
            || packageName == "io.github.aleixrodriala.arc.debug")
        val info = InstrumentationRegistry.getInstrumentation().context.packageManager
            .getApplicationInfo(packageName, 0)
        check(info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Install the non-debuggable benchmark variant first"
        }
        check(info.isProfileableByShell) { "The target must be the profileable benchmark variant" }
        check(device.isScreenOn) { "Unlock and wake the test phone before benchmarking" }
        check(!device.executeShellCommand("dumpsys window").contains("mDreamingLockscreen=true")) {
            "The test phone is locked"
        }
        check(device.executeShellCommand("getprop debug.arc.benchmark_fixture").trim() == "1") {
            "Use tools/benchmark-pixel.py to set and restore offline fixture mode"
        }
    }

    fun start(scope: MacrobenchmarkScope) {
        scope.startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(packageName, ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        check(device.wait(Until.hasObject(By.desc("fixture:progress")), 20_000)) {
            "Fixture did not reach READY and continued decoded progress; inspect BenchmarkFixture logs"
        }
        check(!device.hasObject(By.desc("fixture:error"))) { "Local fixture playback failed" }
    }

    fun stop() {
        device.executeShellCommand("am force-stop $packageName")
    }

    fun appRule(rule: String): Boolean =
        (rule.contains("Lcom/newtube/mobile/")
            || rule.contains("Lcom/liskovsoft/smartyoutubetv2/common/")
            || rule.contains("Lcom/liskovsoft/googlecommon/")
            || rule.contains("Lcom/liskovsoft/youtubeapi/"))
            && !rule.contains("Lcom/newtube/mobile/player/BenchmarkPlaybackActivity")
}
