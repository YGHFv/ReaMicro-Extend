package com.reamicro.fix.core

/** 自动任务只读取已发布的安装验证状态；扫描窗口是否结束不代表当前 APK 已验证。 */
internal class HostScanReadiness {
    private data class State(
        val startupRunning: Boolean = false,
        val checking: Boolean = false,
        val scanning: Boolean = false,
        val installation: HostScanUpdateStamp? = null,
        val validated: HostScanUpdateStamp? = null,
        val failure: String? = null,
    )

    @Volatile private var state = State()

    val startupRunning: Boolean get() = state.startupRunning
    val checking: Boolean get() = state.checking

    @Synchronized fun beginStartup() {
        // 热重载的新 ClassLoader 必须验证自己的绑定，不能继承磁盘上的完成标记。
        state = State(startupRunning = true, checking = true)
    }

    @Synchronized fun beginChecking() {
        state = state.copy(checking = true)
    }

    @Synchronized fun finishChecking(installation: HostScanUpdateStamp?, failure: String? = null) {
        state = state.copy(checking = false, installation = installation, failure = failure)
    }

    @Synchronized fun observeInstallation(installation: HostScanUpdateStamp?, failure: String? = null) {
        // 起批次的后台复核不能清除另一线程刚开始的扫描或启动门禁。
        state = state.copy(installation = installation, failure = failure)
    }

    @Synchronized fun beginScan(installation: HostScanUpdateStamp) {
        state = state.copy(checking = false, scanning = true, installation = installation,
            validated = null, failure = null)
    }

    @Synchronized fun finishStartup(installation: HostScanUpdateStamp?, failure: String? = null) {
        val unchanged = installation != null && state.installation == installation
        state = state.copy(startupRunning = false, checking = false,
            validated = installation.takeIf { failure == null && unchanged },
            failure = failure ?: "启动扫描的安装状态尚未验证".takeUnless { unchanged })
    }

    @Synchronized fun finishScan(installation: HostScanUpdateStamp, successful: Boolean, failure: String? = null) {
        val unchanged = state.installation == installation
        state = state.copy(scanning = false, validated = installation.takeIf { successful && unchanged },
            failure = failure ?: "扫描期间安装状态已变化".takeUnless { unchanged })
    }

    fun automaticUpdateBlockReason(): String? {
        val current = state
        return when {
            current.startupRunning -> "启动扫描尚未完成"
            current.checking -> "正在检查阅微和模块安装状态"
            current.scanning -> "DexKit 扫描尚未完成"
            current.failure != null -> current.failure
            current.installation == null || current.installation != current.validated ->
                "当前安装尚未完成 DexKit 验证"
            else -> null
        }
    }
}
