package com.reamicro.fix.cloud.root

internal enum class RootModuleInstallDecision { READY, INSTALL, REBOOT }

internal fun RootModuleStatus.installDecision(requiredVersion: Int): RootModuleInstallDecision = when {
    !safeMode && !disabled && !pendingRemoval && !stagedDisabled && !stagedRemoval && stagedReady && stagedVersion >= requiredVersion -> RootModuleInstallDecision.REBOOT
    !pendingUpdate && !stagedPresent && stagedVersion == 0 && installed && ready &&
        !safeMode && !disabled && !pendingRemoval && installedVersion >= requiredVersion -> RootModuleInstallDecision.READY
    else -> RootModuleInstallDecision.INSTALL
}
