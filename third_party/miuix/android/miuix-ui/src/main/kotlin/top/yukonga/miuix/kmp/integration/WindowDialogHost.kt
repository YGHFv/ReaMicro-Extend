// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.layout.DialogSurfaceScope
import top.yukonga.miuix.kmp.window.WindowComponentHost

/** Supply the focused platform window while upstream components own its contents and motion. */
@Composable
fun WindowDialogHost(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    WindowComponentHost(onDismissRequest, content)
}

/** Resolve the upstream surface for the application's optional AOSP navigation transition. */
@Composable
fun dialogSurfaceModifier(
    cornerRadius: Dp,
    scrimColor: State<Color>,
    scale: State<Float>,
    surface: @Composable DialogSurfaceScope.() -> Modifier,
): Modifier = surface(DialogSurfaceScope(cornerRadius, scrimColor, scale))
