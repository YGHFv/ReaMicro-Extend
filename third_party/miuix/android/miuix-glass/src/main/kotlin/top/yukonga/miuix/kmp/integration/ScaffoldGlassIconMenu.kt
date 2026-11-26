// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/** Lift the upstream menu out of the toolbar and retain its host until the anchor is restored. */
@Composable
fun ScaffoldGlassIconMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchor: GlassPopupAnchor,
    anchorContent: @Composable () -> Unit,
    entries: List<DropdownEntry>,
    backdrop: Backdrop?,
) {
    val mounted = remember { mutableStateOf(false) }
    LaunchedEffect(show, anchor.contentHidden) {
        if (show) mounted.value = true
        else if (!anchor.contentHidden) mounted.value = false
    }
    PopupLayout(
        visible = mounted,
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        renderInRootScaffold = true,
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(show) {
            if (show) withFrameNanos { }
            visible = show
        }
        Box(Modifier.fillMaxSize()) {
            GlassIconDropdownMenu(
                show = visible,
                onDismissRequest = onDismissRequest,
                anchor = anchor,
                anchorContent = anchorContent,
                entries = entries,
                backdrop = backdrop,
            )
        }
    }
}
