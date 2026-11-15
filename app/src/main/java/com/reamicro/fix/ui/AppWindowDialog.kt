package com.reamicro.fix.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.glass.glassDialogSurface
import top.yukonga.miuix.kmp.integration.WindowSurfaceDialog

@Composable
internal fun ModuleWindowDialog(
    show: Boolean,
    title: String?,
    onClose: () -> Unit,
    insideMargin: DpSize = top.yukonga.miuix.kmp.layout.DialogDefaults.insideMargin,
    dismissEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val mode = LocalModuleWindowGestures.current.predictiveMode
    if (mode == ModulePredictiveBackMode.Aosp &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ModuleAospWindowDialog(show, title, onClose, dismissEnabled, insideMargin, content)
    } else if (useModuleGlass()) {
        ModuleGlassWindowDialog(show, title, onClose, dismissEnabled, insideMargin, content)
    } else {
        WindowSurfaceDialog(
            show = show,
            title = title,
            onDismissRequest = { if (dismissEnabled) onClose() },
            insideMargin = insideMargin,
            windowHost = {
                ModulePredictiveBackScope(animated = mode.enabled) {
                    ModuleWindowPopupHost(it)
                }
            },
        ) {
            ModuleWindowBackGuard(dismissEnabled = dismissEnabled)
            content()
        }
    }
}

@Composable
private fun ModuleGlassWindowDialog(
    show: Boolean,
    title: String?,
    onClose: () -> Unit,
    dismissEnabled: Boolean,
    insideMargin: DpSize,
    content: @Composable () -> Unit,
) {
    var closing by remember { mutableStateOf(false) }
    val currentClose by rememberUpdatedState(onClose)
    val currentEnabled by rememberUpdatedState(dismissEnabled)
    LaunchedEffect(show) { if (show) closing = false }
    val requestClose = { if (currentEnabled) closing = true }
    WindowSurfaceDialog(
        show = show && !closing,
        title = title,
        onDismissRequest = requestClose,
        modifier = Modifier.moduleClosingInput(closing),
        onDismissFinished = {
            if (closing) {
                if (currentEnabled) currentClose() else closing = false
            }
        },
        insideMargin = insideMargin,
        containerColor = GlassDialogDefaults.containerColor(),
        dimColor = GlassDialogDefaults.ScrimColor,
        surface = glassDialogSurface(LocalModuleSceneBackdrop.current),
        windowHost = {
            ModulePredictiveBackScope(animated = LocalModuleWindowGestures.current.predictiveMode.enabled) {
                ModuleWindowPopupHost(it)
            }
        },
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        LaunchedEffect(closing) {
            if (closing) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        // Register before child menu content: a menu composed later owns the first back event.
        ModuleWindowBackGuard(dismissEnabled = dismissEnabled && !closing)
        CompositionLocalProvider(
            LocalModuleWindowCloseRequest provides requestClose,
            LocalModuleWindowClosing provides closing,
        ) {
            content()
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

@Composable
internal fun AppWindowDialog(
    show: Boolean, title: String, onClose: () -> Unit,
    primaryLabel: String? = null, onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true, dangerous: Boolean = false,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModuleWindowDialog(
        show = show, title = title, onClose = onClose,
        dismissEnabled = dismissEnabled,
        insideMargin = DpSize(DIALOG_INSIDE_DP.dp, DIALOG_INSIDE_DP.dp),
    ) {
        AppDialogBody(onClose, primaryLabel, onPrimary, primaryEnabled, dangerous, content)
    }
}

@Composable
private fun AppDialogBody(
    onClose: () -> Unit, primaryLabel: String?, onPrimary: (() -> Unit)?,
    primaryEnabled: Boolean, dangerous: Boolean, content: @Composable ColumnScope.() -> Unit,
) {
    val requestClose = LocalModuleWindowCloseRequest.current ?: onClose
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = 420.dp)
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
    val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = colors.primary, contentColor = colors.onPrimary,
            disabledContainerColor = colors.surfaceContainerHighest,
            disabledContentColor = colors.onBackground.copy(alpha = .38f)),
    ) {
        Text(text, color = if (enabled) colors.onPrimary else colors.onBackground.copy(alpha = .38f),
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button, textAlign = TextAlign.Center)
    }
}
