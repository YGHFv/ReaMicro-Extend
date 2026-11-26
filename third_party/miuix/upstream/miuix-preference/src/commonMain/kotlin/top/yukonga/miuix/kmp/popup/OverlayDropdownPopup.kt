// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

/**
 * Overlay dropdown popup for a single [DropdownEntry].
 *
 * Item clicks call [top.yukonga.miuix.kmp.basic.DropdownItem.onClick], and [collapseOnSelection]
 * controls whether the popup is dismissed after a click.
 */
@Composable
fun OverlayDropdownPopup(
    entry: DropdownEntry,
    show: Boolean,
    onDismiss: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
    collapseOnSelection: Boolean = true,
    onDismissFinished: (() -> Unit)? = null,
) {
    val entries = remember(entry) { listOf(entry) }
    OverlayDropdownPopup(
        entries = entries,
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        dropdownColors = dropdownColors,
        renderInRootScaffold = renderInRootScaffold,
        collapseOnSelection = collapseOnSelection,
    )
}

/**
 * Overlay dropdown popup for one or more [DropdownEntry] groups.
 *
 * Groups are separated by dividers. Entries without selection state can be used as action menus.
 */
@Composable
fun OverlayDropdownPopup(
    entries: List<DropdownEntry>,
    show: Boolean,
    onDismiss: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
    collapseOnSelection: Boolean = entries.size <= 1,
    onDismissFinished: (() -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentCollapseOnSelection by rememberUpdatedState(collapseOnSelection)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val onItemClicked: (Int, Int) -> Unit = remember {
        { entryIdx, itemIdx ->
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            currentEntries.getOrNull(entryIdx)?.let { entry ->
                entry.items.getOrNull(itemIdx)?.onClick?.invoke()
            }
            if (currentCollapseOnSelection) {
                currentOnDismiss()
            }
        }
    }
    OverlayListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.End,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        renderInRootScaffold = renderInRootScaffold,
    ) {
        ListPopupColumn {
            DropdownEntriesPopupContent(
                entries = entries,
                dropdownColors = dropdownColors,
                onItemClick = onItemClicked,
            )
        }
    }
}
