package com.reamicro.fix.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import com.reamicro.fix.R
import com.reamicro.fix.cloud.local.LocalTaskAccount
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AccountDialog { Closed, Records, Tasks, Manage, Import, Remark, Delete }

internal class ModuleAccountsUi {
    var dialog by mutableStateOf(AccountDialog.Closed)
    var editing by mutableStateOf<LocalTaskAccount?>(null)
    var remark by mutableStateOf("")
    // Credentials deliberately stay in memory and are never put in saved instance state.
    var credential by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    val open: Boolean get() = dialog != AccountDialog.Closed

    fun show(value: AccountDialog) { error = ""; dialog = value }
    fun close() {
        if (busy) return
        credential = ""; error = ""; editing = null; dialog = AccountDialog.Closed
    }
}

@Composable
internal fun ModuleTaskAccountDialogs(
    ui: ModuleAccountsUi,
    accounts: List<LocalTaskAccount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onImport: (String) -> Unit,
    onChooseFile: () -> Unit,
    onSaveRemark: (LocalTaskAccount, String) -> Unit,
    onDelete: (LocalTaskAccount) -> Unit,
) {
    if (!ui.open) return
    val filter = ui.dialog == AccountDialog.Records || ui.dialog == AccountDialog.Tasks
    AppWindowDialog(
        show = true,
        title = stringResource(when (ui.dialog) {
            AccountDialog.Import -> R.string.accounts_import
            AccountDialog.Remark -> R.string.accounts_remark
            AccountDialog.Delete -> R.string.accounts_delete
            AccountDialog.Records, AccountDialog.Tasks -> R.string.accounts_filter
            else -> R.string.accounts_manage
        }),
        onClose = ui::close,
        dismissEnabled = !ui.busy,
        primaryLabel = when (ui.dialog) {
            AccountDialog.Import -> stringResource(R.string.accounts_import)
            AccountDialog.Remark -> stringResource(R.string.action_save)
            AccountDialog.Delete -> stringResource(R.string.accounts_delete_action)
            else -> null
        },
        onPrimary = when (ui.dialog) {
            AccountDialog.Import -> ({ onImport(ui.credential) })
            AccountDialog.Remark -> ({ ui.editing?.let { onSaveRemark(it, ui.remark) } })
            AccountDialog.Delete -> ({ ui.editing?.let(onDelete) })
            else -> null
        },
        primaryEnabled = !ui.busy && (ui.dialog != AccountDialog.Import || ui.credential.isNotBlank()),
        dangerous = ui.dialog == AccountDialog.Delete,
    ) {
        when {
            filter -> {
                BasicComponent(
                    modifier = Modifier.semantics { this.selected = selected == null },
                    title = stringResource(R.string.accounts_all),
                    endActions = { if (selected == null) Icon(MiuixIcons.Basic.Check, null, tint = MiuixTheme.colorScheme.primary) },
                    onClick = { onSelect(null); ui.close() },
                )
                accounts.forEach { account ->
                    BasicComponent(
                        modifier = Modifier.semantics { this.selected = selected == account.id },
                        title = account.name,
                        summary = account.id,
                        endActions = { if (selected == account.id) Icon(MiuixIcons.Basic.Check, null, tint = MiuixTheme.colorScheme.primary) },
                        onClick = { onSelect(account.id); ui.close() },
                    )
                }
            }
            ui.dialog == AccountDialog.Manage -> {
                Text(stringResource(R.string.accounts_description), style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(12.dp))
                CapsuleTextButton(label = stringResource(R.string.accounts_import)) { ui.show(AccountDialog.Import) }
                accounts.forEach { account ->
                    val deleteDescription = stringResource(R.string.accounts_delete_named, account.label)
                    BasicComponent(
                        title = account.name,
                        summary = account.id,
                        onClick = { ui.editing = account; ui.remark = account.remark; ui.show(AccountDialog.Remark) },
                        endActions = {
                            TextButton(
                                text = stringResource(R.string.accounts_delete_action),
                                modifier = Modifier.semantics { contentDescription = deleteDescription },
                                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                                onClick = { ui.editing = account; ui.show(AccountDialog.Delete) },
                            )
                        },
                    )
                }
            }
            ui.dialog == AccountDialog.Import -> {
                Text(stringResource(R.string.accounts_import_description), style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = ui.credential,
                    onValueChange = { ui.credential = it },
                    label = stringResource(R.string.accounts_credential_hint),
                    modifier = Modifier.fillMaxWidth(), maxLines = 5, singleLine = false,
                    enabled = !ui.busy,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(stringResource(R.string.accounts_choose_file), onClick = onChooseFile, enabled = !ui.busy)
            }
            ui.dialog == AccountDialog.Remark -> {
                Text(ui.editing?.label.orEmpty(), style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(12.dp))
                TextField(value = ui.remark, onValueChange = { ui.remark = it.take(80) },
                    label = stringResource(R.string.accounts_remark_hint), enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            ui.dialog == AccountDialog.Delete -> {
                Text(
                    stringResource(R.string.accounts_delete_confirm, ui.editing?.label.orEmpty()),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
        if (ui.busy) Text(stringResource(
            if (ui.dialog == AccountDialog.Delete) R.string.accounts_deleting else R.string.accounts_saving,
        ), style = MiuixTheme.textStyles.body2)
        if (ui.error.isNotBlank()) Text(ui.error, color = MiuixTheme.colorScheme.error)
    }
}
