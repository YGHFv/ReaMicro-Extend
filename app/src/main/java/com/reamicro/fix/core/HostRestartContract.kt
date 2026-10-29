package com.reamicro.fix.core

internal object HostRestartContract {
    const val ACTIVITY = "com.reamicro.fix.ui.HostRestartActivity"
    const val EXTRA_PACKAGE = "reamicro.restart.package"
    const val EXTRA_PROCESS = "reamicro.restart.process"
    const val EXTRA_READY = "reamicro.restart.ready"
    const val READY = 1
    const val FAILED = 2

    fun isSupportedPackage(packageName: String?): Boolean =
        packageName in setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix")
}
