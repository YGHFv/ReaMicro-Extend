package com.reamicro.fix.cloud.root

import androidx.annotation.StringRes
import com.reamicro.fix.R

internal data class RootModuleStatus(
    val rootAvailable: Boolean = false,
    val checkedAt: Long = 0L,
    val access: RootAccessReport = RootAccessReport(if (rootAvailable) RootAccessState.GRANTED else RootAccessState.ERROR),
    val inspectionComplete: Boolean = true,
    val stopRequested: Boolean = false,
    val taskRunning: Boolean = false,
    val installed: Boolean = false,
    val statePresent: Boolean = false,
    val ready: Boolean = false,
    val installedVersion: Int = 0,
    val stagedVersion: Int = 0,
    val stagedPresent: Boolean = false,
    val stagedDisabled: Boolean = false,
    val stagedRemoval: Boolean = false,
    val stagedReady: Boolean = false,
    val pendingUpdate: Boolean = false,
    val disabled: Boolean = false,
    val pendingRemoval: Boolean = false,
    val daemonRunning: Boolean = false,
    val heartbeatAt: Long = 0L,
    val legacyWakeInstalled: Boolean = false,
    val error: String = "",
) {
    @StringRes
    fun accessResource(): Int = when (access.state) {
        RootAccessState.GRANTED -> R.string.execution_root_granted
        RootAccessState.UNAVAILABLE -> R.string.execution_su_missing
        RootAccessState.DENIED -> R.string.execution_root_denied
        RootAccessState.NOT_ROOT -> R.string.execution_root_not_uid_zero
        RootAccessState.TIMEOUT -> R.string.execution_root_timeout
        RootAccessState.ERROR -> R.string.execution_root_probe_error
    }

    @StringRes
    fun moduleResource(): Int = when {
        !rootAvailable -> R.string.execution_root_unavailable
        !inspectionComplete -> R.string.execution_module_probe_error
        pendingRemoval || stagedRemoval -> R.string.execution_module_removing
        pendingUpdate || stagedPresent || stagedVersion > 0 -> R.string.execution_module_pending
        !installed -> R.string.execution_module_missing
        disabled -> R.string.execution_module_disabled
        !ready -> R.string.execution_module_incomplete
        else -> R.string.root_module_ready
    }
    @StringRes
    fun stateResource(rootSelected: Boolean, now: Long): Int = when {
        !rootAvailable -> R.string.execution_root_unavailable
        !inspectionComplete -> R.string.execution_module_probe_error
        pendingRemoval || stagedRemoval -> R.string.execution_module_removing
        pendingUpdate || stagedPresent || stagedVersion > 0 -> R.string.execution_module_pending
        !installed -> R.string.execution_module_missing
        disabled -> R.string.execution_module_disabled
        !ready -> R.string.execution_module_incomplete
        !rootSelected -> R.string.execution_module_idle
        stopRequested -> R.string.execution_daemon_stopped
        !daemonRunning -> R.string.execution_daemon_stopped
        taskRunning && heartbeatAt > 0L && now - heartbeatAt in 0L..120_000L -> R.string.execution_task_running
        heartbeatAt > 0L && now - heartbeatAt in 0L..120_000L -> R.string.execution_status_running
        else -> R.string.execution_status_no_heartbeat
    }
}
