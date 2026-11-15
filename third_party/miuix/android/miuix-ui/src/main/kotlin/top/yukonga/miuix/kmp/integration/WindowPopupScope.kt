// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.utils.LocalRootDialogStates
import top.yukonga.miuix.kmp.utils.LocalRootPopupStates

/** Start a popup-root boundary in a separate platform window, as opposed to a nested page. */
@Composable
fun WindowPopupScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRootDialogStates provides null,
        LocalRootPopupStates provides null,
        content = content,
    )
}
