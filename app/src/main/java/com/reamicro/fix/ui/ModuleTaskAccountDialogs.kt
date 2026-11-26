package com.reamicro.fix.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AccountDialog { Closed, Records, Tasks, Manage, Import, Remark, Delete }

internal class ModuleAccountsUi {
    var dialog by mutableStateOf(AccountDialog.Closed)
    var editing by mutableStateOf<LocalTaskAccount?>(null)
    val remark = TextFieldState()
    // Credentials deliberately stay in memory and are never put in saved instance state.
    var credential by mutableStateOf(TextFieldState())
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    val open: Boolean get() = dialog != AccountDialog.Closed

    fun show(value: AccountDialog) { error = ""; dialog = value }
    fun close() {
        if (busy) return
        credential = TextFieldState(); error = ""; editing = null; dialog = AccountDialog.Closed
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
    val value = if (ui.open) AccountWindowSession(ui.dialog, ui.editing, ui.credential, ui.error) else null
    RetainedWindowContent(value) { session, show, onDismissFinished ->
        val filter = session.kind == AccountDialog.Records || session.kind == AccountDialog.Tasks
        AppWindowDialog(
            show = show,
            onDismissFinished = onDismissFinished,
            title = stringResource(when (session.kind) {
                AccountDialog.Import -> R.string.accounts_import
                AccountDialog.Remark -> R.string.accounts_remark
                AccountDialog.Delete -> R.string.accounts_delete
                AccountDialog.Records, AccountDialog.Tasks -> R.string.accounts_filter
                else -> R.string.accounts_manage
            }),
            onClose = ui::close,
            dismissEnabled = !ui.busy,
            primaryLabel = when (session.kind) {
                AccountDialog.Import -> stringResource(R.string.accounts_import)
                AccountDialog.Remark -> stringResource(R.string.action_save)
                AccountDialog.Delete -> stringResource(R.string.accounts_delete_action)
                else -> null
            },
            onPrimary = when (session.kind) {
                AccountDialog.Import -> ({ onImport(session.credential.text.toString()) })
                AccountDialog.Remark -> ({ session.account?.let { onSaveRemark(it, ui.remark.text.toString()) } })
                AccountDialog.Delete -> ({ session.account?.let(onDelete) })
                else -> null
            },
            primaryEnabled = !ui.busy && (session.kind != AccountDialog.Import || session.credential.text.isNotBlank()),
            dangerous = session.kind == AccountDialog.Delete,
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
                session.kind == AccountDialog.Manage -> {
                    Text(stringResource(R.string.accounts_description), style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(12.dp))
                    CapsuleTextButton(label = stringResource(R.string.accounts_import)) { ui.show(AccountDialog.Import) }
                    accounts.forEach { account ->
                        val deleteDescription = stringResource(R.string.accounts_delete_named, account.label)
                        BasicComponent(
                            title = account.name,
                            summary = account.id,
                            onClick = { ui.editing = account; ui.remark.setTextAndPlaceCursorAtEnd(account.remark); ui.show(AccountDialog.Remark) },
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
                session.kind == AccountDialog.Import -> {
                    Text(stringResource(R.string.accounts_import_description), style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(12.dp))
                    ModuleDialogTextField(
                        state = session.credential,
                        label = stringResource(R.string.accounts_credential_hint),
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        enabled = !ui.busy,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(stringResource(R.string.accounts_choose_file), onClick = onChooseFile, enabled = !ui.busy)
                }
                session.kind == AccountDialog.Remark -> {
                    Text(session.account?.label.orEmpty(), style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(12.dp))
                    ModuleDialogTextField(state = ui.remark,
                        inputTransformation = { if (length > 80) replace(80, length, "") },
                        label = stringResource(R.string.accounts_remark_hint), enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth(), lineLimits = TextFieldLineLimits.SingleLine)
                }
                session.kind == AccountDialog.Delete -> {
                    Text(
                        stringResource(R.string.accounts_delete_confirm, session.account?.label.orEmpty()),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            if (ui.busy) Text(stringResource(
                if (session.kind == AccountDialog.Delete) R.string.accounts_deleting else R.string.accounts_saving,
            ), style = MiuixTheme.textStyles.body2)
            if (session.error.isNotBlank()) Text(session.error, color = MiuixTheme.colorScheme.error)
        }
    }
}

private data class AccountWindowSession(
    val kind: AccountDialog,
    val account: LocalTaskAccount?,
    val credential: TextFieldState,
    val error: String,
)
