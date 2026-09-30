package com.reamicro.fix.cloud.root

internal enum class RootModuleInstallDecision { READY, INSTALL, REBOOT }

/**
 * Magisk can copy the new module.prop into the active directory before reboot.
 * A new versionCode therefore does NOT prove that the new scripts are active.
 */
internal fun RootModuleStatus.installDecision(requiredVersion: Int): RootModuleInstallDecision = when {
    !disabled && !pendingRemoval && !stagedDisabled && !stagedRemoval && stagedReady && stagedVersion >= requiredVersion -> RootModuleInstallDecision.REBOOT
    !pendingUpdate && !stagedPresent && stagedVersion == 0 && installed && ready &&
        !disabled && !pendingRemoval && installedVersion >= requiredVersion -> RootModuleInstallDecision.READY
    else -> RootModuleInstallDecision.INSTALL
}
