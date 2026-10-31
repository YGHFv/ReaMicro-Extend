package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HostRestartContractTest {
    @Test fun onlyReaderPackagesMayBeRelaunched() {
        assertTrue(HostRestartContract.isSupportedPackage("app.zhendong.reamicro"))
        assertTrue(HostRestartContract.isSupportedPackage("app.zhendong.reamicro.fix"))
        for (name in listOf(null, "", "bin.mt.plus", "com.reamicro.fix", "app.zhendong.reamicro.other")) {
            assertFalse(HostRestartContract.isSupportedPackage(name))
        }
    }

    @Test fun scanDialogHidesRowsAndUsesProcessRestartNotAccountRefresh() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val dialog = main.resolve("java/com/reamicro/fix/hook/ReaMicroSettingsHook.DexKit.kt").readText(Charsets.UTF_8)
        assertFalse(dialog.contains("report?.failures.orEmpty().take"))
        assertTrue(dialog.contains("HostRestart.request(activity)"))
        assertTrue(dialog.indexOf("notice.message") < dialog.indexOf("\"重启阅微\""))
        assertFalse(dialog.contains("restartHostAfterAccountSwitch"))
    }

    @Test fun scanSpinnerUsesHostPrimaryPalette() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val dialog = main.resolve("java/com/reamicro/fix/hook/ReaMicroSettingsHook.DexKit.kt").readText(Charsets.UTF_8)
        assertTrue(dialog.contains("header.addView(ProgressBar(activity)"))
        val spinner = dialog.substringAfter("header.addView(ProgressBar(activity)").substringBefore("val notice")
        assertTrue(spinner.contains("ModuleDialogTheme.tintProgress(this, colors.primary)"))
        val palette = main.resolve("java/com/reamicro/fix/hook/ModuleDialogTheme.kt").readText(Charsets.UTF_8)
        assertTrue(palette.contains("EmbeddedHostUi.snapshot(context)?.let { return fromHost(it) }"))
        assertTrue(palette.contains("primary = native.roles.getValue(\"Primary\")"))
    }

    @Test fun scanSpinnerSharesTitleRowWithoutTakingBodySpace() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val dialog = main.resolve("java/com/reamicro/fix/hook/ReaMicroSettingsHook.DexKit.kt").readText(Charsets.UTF_8)
        val header = dialog.substringAfter("val title = settingsDialogTitle").substringBefore("val notice")
        assertTrue(header.contains("orientation = LinearLayout.HORIZONTAL"))
        assertTrue(header.contains("header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))"))
        assertTrue(header.contains("val spinnerSize = settingsDp(activity, 24)"))
        assertTrue(header.contains("marginStart = settingsDp(activity, 12)"))
        assertTrue(header.contains("card.addView(header)"))
        assertFalse(header.contains("card.addView(ProgressBar"))
        assertTrue(header.contains("card.addView(title)"))
    }

    @Test fun restartHandoffHasNoProgressUiButRetainsFailureFeedback() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val relay = main.resolve("java/com/reamicro/fix/ui/HostRestartActivity.kt").readText(Charsets.UTF_8)
        assertFalse(relay.contains("setContentView"))
        assertFalse(relay.contains("ProgressBar"))
        assertFalse(relay.contains("正在重新打开阅微"))
        assertTrue(relay.contains("setLayout(1, 1)"))
        assertTrue(relay.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(relay.contains("linkToDeath(death, 0)"))
        assertTrue(relay.contains("receiver?.send(HostRestartContract.READY, null)"))
        assertTrue(relay.contains("Toast.makeText(this, message"))
        val manifest = main.resolve("AndroidManifest.xml").readText(Charsets.UTF_8)
        val activity = manifest.substringAfter("android:name=\".ui.HostRestartActivity\"").substringBefore("/>")
        assertTrue(activity.contains("android:theme=\"@style/NoDisplayTheme\""))
        val theme = main.resolve("res/values/styles.xml").readText(Charsets.UTF_8)
            .substringAfter("<style name=\"NoDisplayTheme\"").substringBefore("</style>")
        assertTrue(theme.contains("name=\"android:windowBackground\">@android:color/transparent"))
        assertTrue(theme.contains("name=\"android:backgroundDimEnabled\">false"))
        assertTrue(theme.contains("name=\"android:windowNoDisplay\">false"))
    }
}
