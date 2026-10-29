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
}
