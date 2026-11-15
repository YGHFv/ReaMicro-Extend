// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.layout.DialogContentLayout
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.layout.DialogSurfaceScope
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.WindowNavigationEventScope
import top.yukonga.miuix.kmp.utils.platformDialogProperties

/**
 * Android integration bridge for upstream's shared dialog layout.
 *
 * WindowDialog does not yet expose its surface slot. This adapter lives in the UI module so the
 * app can use the public Glass surface without copying internal layout, animation or IME code.
 * Each window supplies its own popup host; callers still own business state and dismissal.
 */
@Composable
fun WindowSurfaceDialog(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    containerColor: Color = DialogDefaults.backgroundColor(),
    dimColor: Color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.windowDimming,
    surface: (@Composable DialogSurfaceScope.() -> Modifier)? = null,
    onDismissFinished: (() -> Unit)? = null,
    windowHost: @Composable (@Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val safeTop = maxOf(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        WindowInsets.captionBar.asPaddingValues().calculateTopPadding(),
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
    )
    DialogContentLayout(
        show = show,
        title = title,
        titleColor = DialogDefaults.titleColor(),
        summaryColor = DialogDefaults.summaryColor(),
        backgroundColor = containerColor,
        outsideMargin = DialogDefaults.outsideMargin,
        insideMargin = insideMargin,
        onDismissRequest = { currentDismiss() },
        onDismissFinished = onDismissFinished,
        topInset = safeTop,
        dimColor = dimColor,
        surface = surface,
        modifier = modifier,
        popupHost = { visible, hostContent ->
            if (visible) {
                Dialog(
                    onDismissRequest = { currentDismiss() },
                    properties = platformDialogProperties(),
                ) {
                    RemovePlatformDialogDefaultEffects()
                    WindowNavigationEventScope {
                        windowHost(hostContent)
                    }
                }
            }
        },
        content = content,
    )
}

/** Resolve the same surface for an application-owned navigation window panel. */
@Composable
fun dialogSurfaceModifier(
    cornerRadius: Dp,
    scrimColor: State<Color>,
    scale: State<Float>,
    surface: @Composable DialogSurfaceScope.() -> Modifier,
): Modifier = surface(DialogSurfaceScope(cornerRadius, scrimColor, scale))
