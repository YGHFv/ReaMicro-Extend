package com.reamicro.fix.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.integration.WindowDialogHost
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ModuleWindowDialog(
    show: Boolean,
    title: String?,
    onClose: () -> Unit,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    dismissEnabled: Boolean = true,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val mode = LocalModuleWindowGestures.current.predictiveMode
    if (mode == ModulePredictiveBackMode.Aosp &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ModuleAospWindowDialog(show, title, onClose, dismissEnabled, insideMargin, onDismissFinished, content)
    } else {
        ModuleMiuixWindowDialog(show, title, onClose, dismissEnabled, insideMargin, onDismissFinished, content)
    }
}

@Composable
private fun ModuleMiuixWindowDialog(
    show: Boolean,
    title: String?,
    onClose: () -> Unit,
    dismissEnabled: Boolean,
    insideMargin: DpSize,
    onDismissFinished: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(show) }
    LaunchedEffect(show) { if (show) mounted = true }
    if (!mounted) return

    val glass = useModuleGlass()
    val backdrop = LocalModuleSceneBackdrop.current
    val currentClose by rememberUpdatedState(onClose)
    val currentEnabled by rememberUpdatedState(dismissEnabled)
    val closing = !show
    val requestClose = { if (currentEnabled && !closing) currentClose() }
    val finishDismiss: () -> Unit = {
        mounted = false
        onDismissFinished?.invoke()
    }
    val dialogContent: @Composable () -> Unit = {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        LaunchedEffect(closing) {
            if (closing) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        // Child menus register later and receive Back before the dialog or keyboard guard.
        ModuleWindowBackGuard(dismissEnabled = dismissEnabled && !closing)
        CompositionLocalProvider(
            LocalModuleWindowCloseRequest provides requestClose,
            LocalModuleWindowClosing provides closing,
        ) { content() }
    }
    WindowDialogHost(onDismissRequest = requestClose) {
        ModulePredictiveBackScope(animated = LocalModuleWindowGestures.current.predictiveMode.enabled) {
            ModuleWindowPopupHost {
                // Record the inner dialog + scrim; menus use the outer window Scaffold and
                // stay outside this recording. Every surface samples only the layer below it.
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    if (glass) {
                        GlassDialog(
                            show = show,
                            title = title,
                            onDismissRequest = requestClose,
                            onDismissFinished = finishDismiss,
                            modifier = Modifier.moduleClosingInput(closing),
                            backdrop = backdrop,
                            insideMargin = insideMargin,
                            renderInRootScaffold = false,
                            content = dialogContent,
                        )
                    } else {
                        OverlayDialog(
                            show = show,
                            title = title,
                            onDismissRequest = requestClose,
                            onDismissFinished = finishDismiss,
                            modifier = Modifier.moduleClosingInput(closing),
                            insideMargin = insideMargin,
                            renderInRootScaffold = false,
                            content = dialogContent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleWindowBackGuard(dismissEnabled: Boolean) {
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = !dismissEnabled || imeInsets.getBottom(density) > 0,
        onBackCompleted = { if (imeInsets.getBottom(density) > 0) keyboard?.hide() },
    )
}

private fun Modifier.moduleClosingInput(closing: Boolean): Modifier = if (!closing) this else {
    this
        .clearAndSetSemantics { }
        .onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                }
            }
        }
}

/** Keep a dismissed editor's draft in composition until its actual window exit completes. */
@Composable
internal fun <T : Any> RetainedWindowContent(
    value: T?,
    content: @Composable (value: T, show: Boolean, onDismissFinished: () -> Unit) -> Unit,
) {
    var retained by remember { mutableStateOf<T?>(null) }
    val currentValue by rememberUpdatedState(value)
    if (value != null) SideEffect { retained = value }
    val displayed = value ?: retained ?: return
    content(displayed, value != null) {
        // A cancelled older exit must not discard a newly opened editor.
        if (currentValue == null) retained = null
    }
}

@Composable
internal fun AppWindowDialog(
    show: Boolean, title: String, onClose: () -> Unit,
    primaryLabel: String? = null, onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true, dangerous: Boolean = false,
    dismissEnabled: Boolean = true,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModuleWindowDialog(
        show = show, title = title, onClose = onClose,
        dismissEnabled = dismissEnabled,
        onDismissFinished = onDismissFinished,
        insideMargin = DpSize(DIALOG_INSIDE_DP.dp, DIALOG_INSIDE_DP.dp),
    ) {
        AppDialogBody(onClose, primaryLabel, onPrimary, primaryEnabled, dangerous, content)
    }
}

@Composable
internal fun ModuleDialogCard(content: @Composable ColumnScope.() -> Unit) {
    if (useModuleGlass()) {
        GlassCard(insideMargin = PaddingValues(0.dp), content = content)
    } else {
        Card(insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
private fun AppDialogBody(
    onClose: () -> Unit, primaryLabel: String?, onPrimary: (() -> Unit)?,
    primaryEnabled: Boolean, dangerous: Boolean, content: @Composable ColumnScope.() -> Unit,
) {
    val requestClose = LocalModuleWindowCloseRequest.current ?: onClose
    // Keep short forms at their natural height; reserve space for title/footer while
    // allowing long forms to use the available portrait or landscape viewport.
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * .78f - 128f)
        .coerceAtLeast(80f).dp
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = contentMaxHeight)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
        AppDialogFooter(requestClose, primaryLabel, onPrimary, primaryEnabled, dangerous)
    }
}

@Composable
private fun AppDialogFooter(
    onClose: () -> Unit, primaryLabel: String?, onPrimary: (() -> Unit)?,
    primaryEnabled: Boolean, dangerous: Boolean,
) {
    val closing = LocalModuleWindowClosing.current
    val density = LocalDensity.current
    val view = LocalView.current
    val cornerPx = if (Build.VERSION.SDK_INT >= 31) maxOf(
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
    ) else 0
    val safe = dialogFooterPadding(cornerPx / density.density)
    Row(
        Modifier.fillMaxWidth().padding(start = safe.horizontalExtra.dp, end = safe.horizontalExtra.dp,
            top = 12.dp, bottom = safe.bottomExtra.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModuleDialogActionButton(
            text = stringResource(if (onPrimary == null) R.string.action_close else R.string.action_cancel),
            onClick = onClose,
            modifier = Modifier.weight(1f),
            enabled = !closing,
        )
        if (onPrimary != null && primaryLabel != null) {
            ModuleDialogActionButton(
                text = primaryLabel,
                onClick = { if (!closing) onPrimary() },
                modifier = Modifier.weight(1f),
                enabled = primaryEnabled && !closing,
                primary = true,
                dangerous = dangerous,
            )
        }
    }
}

@Composable
internal fun ModuleDialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    dangerous: Boolean = false,
) {
    if (useModuleGlass()) {
        GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = when {
                dangerous -> GlassDialogButtonRole.Destructive
                primary -> GlassDialogButtonRole.Primary
                else -> GlassDialogButtonRole.Normal
            },
        ) {
            Text(text, style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button, textAlign = TextAlign.Center)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            minHeight = 48.dp,
            enabled = enabled,
            colors = when {
                dangerous -> ButtonDefaults.buttonColorsPrimary(color = Color(0xFFC63838), contentColor = Color.White)
                primary -> ButtonDefaults.buttonColorsPrimary()
                else -> ButtonDefaults.buttonColors()
            },
        ) {
            Text(text, textAlign = TextAlign.Center)
        }
    }
}
