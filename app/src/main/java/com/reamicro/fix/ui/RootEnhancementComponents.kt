package com.reamicro.fix.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R
import com.reamicro.fix.cloud.root.RootModuleStatus
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

internal enum class RootEnhancementAction { ENABLE, DISABLE, UNINSTALL }

/** Root operations use the shared task-configuration dialog, not a bottom sheet. */
@Composable
internal fun RootEnhancementDialog(
    show: Boolean, title: String, onClose: () -> Unit,
    primaryLabel: String? = null, onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true, dangerous: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = AppWindowDialog(show, title, onClose, primaryLabel, onPrimary, primaryEnabled, dangerous, content)

@Composable
internal fun RootEnhancementCard(
    enabled: Boolean, busy: Boolean, status: RootModuleStatus?,
    checking: Boolean, syncing: Boolean, syncedAt: Long, syncError: String,
    onToggle: (Boolean) -> Unit, onInspect: () -> Unit,
    onManage: () -> Unit, onSync: () -> Unit,
) {
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), insideMargin = PaddingValues(0.dp)) {
        SwitchPreference(
            title = stringResource(R.string.root_enable_switch),
            summary = stringResource(if (enabled) R.string.root_enhancement_on_summary else R.string.root_enhancement_off_summary),
            checked = enabled, enabled = !busy, onCheckedChange = onToggle,
        )
        BasicComponent(
            title = stringResource(R.string.root_access_title),
            summary = if (checking) stringResource(R.string.execution_status_checking)
                else status?.let { stringResource(it.accessResource()) + checkedTime(it) }
                    ?: stringResource(R.string.root_not_checked),
            endActions = { if (checking) CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp) },
        )
        ArrowPreference(
            title = stringResource(R.string.root_inspect),
            summary = stringResource(R.string.root_inspect_hint), enabled = !busy, onClick = onInspect,
        )
        ArrowPreference(
            title = stringResource(R.string.root_module_title),
            summary = if (busy && !checking && !syncing) stringResource(R.string.root_module_busy)
                else moduleSummary(status),
            enabled = !busy, onClick = onManage,
        )
        BasicComponent(
            title = stringResource(R.string.root_daemon_title),
            summary = taskSummary(status, enabled),
        )
        ArrowPreference(
            title = stringResource(R.string.root_sync),
            summary = when {
                !enabled -> stringResource(R.string.root_sync_disabled)
                syncing -> stringResource(R.string.root_sync_running)
                syncError.isNotBlank() -> stringResource(R.string.root_sync_failed, syncError)
                syncedAt > 0L -> stringResource(R.string.root_sync_hint) + "\n" +
                    stringResource(R.string.root_synced_at, formatRootTime(syncedAt))
                else -> stringResource(R.string.root_sync_hint)
            },
            enabled = enabled && !busy, onClick = onSync,
        )
    }
}

@Composable
internal fun RootModuleDialog(
    show: Boolean, busy: Boolean, status: RootModuleStatus?, lastError: String,
    onClose: () -> Unit, onInstall: () -> Unit, onSuPath: () -> Unit, onUninstall: () -> Unit,
) {
    RootEnhancementDialog(show, stringResource(R.string.root_module_title), onClose) {
        Card(insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(
                title = stringResource(R.string.execution_install_update),
                summary = stringResource(R.string.root_install_hint), enabled = !busy, onClick = onInstall,
            )
            ArrowPreference(
                title = stringResource(R.string.execution_su_path),
                summary = status?.access?.executable?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.execution_su_path_hint),
                enabled = !busy, onClick = onSuPath,
            )
            ArrowPreference(
                title = stringResource(R.string.root_uninstall),
                titleColor = BasicComponentDefaults.titleColor(color = Color(0xFFC63838)),
                summary = stringResource(R.string.root_uninstall_hint), enabled = !busy, onClick = onUninstall,
            )
            BasicComponent(
                title = stringResource(R.string.root_versions),
                summary = if (status != null && status.rootAvailable && status.inspectionComplete)
                    stringResource(R.string.root_version_details,
                        status.installedVersion, status.stagedVersion, BuildConfig.ROOT_MODULE_VERSION_CODE)
                else stringResource(R.string.root_module_bundled_version, BuildConfig.ROOT_MODULE_VERSION_CODE),
            )
            if (status?.legacyWakeInstalled == true) BasicComponent(
                title = stringResource(R.string.root_legacy_title),
                summary = stringResource(R.string.execution_legacy_cleanup),
            )
            if (!status?.error.isNullOrBlank()) BasicComponent(
                title = stringResource(R.string.root_inspection_detail), summary = status?.error.orEmpty(),
            )
            if (lastError.isNotBlank() && lastError != status?.error) BasicComponent(
                title = stringResource(R.string.root_last_error), summary = lastError,
            )
            BasicComponent(title = stringResource(R.string.root_permission_help_title),
                summary = stringResource(R.string.root_permission_help))
            BasicComponent(title = stringResource(R.string.root_module_help_title),
                summary = stringResource(R.string.root_module_help))
            BasicComponent(title = stringResource(R.string.root_scheduler_help_title),
                summary = stringResource(R.string.root_scheduler_help))
        }
    }
}

private fun formatRootTime(time: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
        .format(java.util.Date(time))

@Composable
private fun checkedTime(status: RootModuleStatus): String =
    if (status.checkedAt > 0L) "\n" + stringResource(R.string.root_checked_at, formatRootTime(status.checkedAt)) else ""

@Composable
private fun moduleSummary(status: RootModuleStatus?): String = when {
    status == null -> stringResource(R.string.root_module_hint)
    status.rootAvailable && status.inspectionComplete && status.stagedVersion in 1 until BuildConfig.ROOT_MODULE_VERSION_CODE ->
        stringResource(R.string.root_staged_older)
    else -> stringResource(status.moduleResource())
}

@Composable
private fun taskSummary(status: RootModuleStatus?, enabled: Boolean): String = when {
    !enabled -> stringResource(R.string.root_tasks_disabled)
    status == null -> stringResource(R.string.root_task_unknown)
    status.moduleResource() != R.string.root_module_ready -> stringResource(R.string.root_task_unavailable)
    // Preserve the observation time: simply viewing a cached result does not age its activity record.
    else -> stringResource(status.stateResource(enabled, status.checkedAt))
}
