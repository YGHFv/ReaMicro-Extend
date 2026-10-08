package com.reamicro.fix.cloud.root

internal class RootModuleLifecycle(
    private val inspect: () -> RootModuleStatus,
    private val manager: () -> RootModuleManager,
    private val execute: (String, Long) -> RootCommandResult,
    private val install: (RootModuleManager) -> Unit,
) {
    fun prepare(requiredVersion: Int): RootModuleInstallDecision {
        var state = checked()
        var provider: RootModuleManager? = null
        fun provider(): RootModuleManager = provider ?: manager().also { provider = it }
        if (state.disabled || state.pendingRemoval) {
            command(provider().reactivate(state.pendingRemoval))
            state = checked()
            check(!state.disabled && !state.pendingRemoval) { "管理器未确认恢复模块，请重新检测" }
        }
        when (state.installDecision(requiredVersion)) {
            RootModuleInstallDecision.READY -> return RootModuleInstallDecision.READY
            RootModuleInstallDecision.REBOOT -> return RootModuleInstallDecision.REBOOT
            RootModuleInstallDecision.INSTALL -> Unit
        }
        check(maxOf(state.installedVersion, state.stagedVersion) <= requiredVersion) {
            "检测到比 APK 内置版本更新的模块，拒绝自动降级；请使用匹配的 APK 或管理器修复"
        }
        install(provider())
        state = checked()

        val decision = state.installDecision(requiredVersion)
        check(decision != RootModuleInstallDecision.INSTALL) {
            "安装器已返回，但未发现完整的目标模块；请查看管理器安装日志并重新检测"
        }
        return decision
    }

    fun remove(): Boolean {
        val before = checked()
        if (!before.installed && !before.stagedPresent && before.stagedVersion == 0) return false
        val provider = manager()

        command(provider.cancelStaging())
        if (before.installed) command(provider.uninstall(), 30)
        val after = checked()
        check(!after.stagedPresent && after.stagedVersion == 0 &&
            (!after.installed || after.pendingRemoval)) { "卸载未确认：仍有暂存模块或缺少待移除标记" }
        return after.installed
    }

    fun pause() {
        val before = checked()
        if (!before.installed) {
            check(!before.stagedPresent && before.stagedVersion == 0) {
                "模块只有暂存副本：请选择取消安装，或重启生效后再停用；未伪造管理器停用成功"
            }
            return
        }
        command(manager().disable())
        val after = checked()
        check(after.disabled || after.pendingRemoval || !after.installed) { "管理器未确认停用模块" }
    }

    private fun checked(): RootModuleStatus = inspect().also {
        check(it.rootAvailable) { it.error.ifBlank { "未验证本应用 Root 授权" } }
        check(it.inspectionComplete) { it.error.ifBlank { "模块检测未完成，不执行安装或移除" } }
    }
    private fun command(script: String, timeout: Long = 20) {
        val result = execute(script, timeout)
        check(result.exitCode == 0) { "模块管理失败 (exit=${result.exitCode}): ${result.diagnosticOutput.takeLast(500)}" }
    }
}
