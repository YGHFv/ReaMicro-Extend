// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPopupContent
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/**
 * Entry-based integration for the upstream Glass dropdown renderer.
 *
 * The string-only upstream preference cannot preserve a selected disabled unknown-value row.
 * This adapter reuses its exact popup renderer, lifecycle and motion, retaining DropdownItem roles.
 */
@Composable
fun EntryGlassDropdownPreference(
    entry: DropdownEntry,
    title: String,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    sizing: GlassPopupSizing = GlassPopupSizing(),
    renderInRootScaffold: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val mounted = remember { mutableStateOf(false) }
    val anchor = rememberGlassPopupAnchor()
    val rowAnchor = remember { GlassPreferenceAnchor() }
    DisposableEffect(rowAnchor) {
        onDispose { rowAnchor.clear() }
    }
    val actualEnabled = enabled && entry.enabled && entry.items.any { it.enabled }
    val currentExpandedChange by rememberUpdatedState(onExpandedChange)
    val setExpanded: (Boolean) -> Unit = remember {
        { next ->
            if (expanded != next) {
                if (next) rowAnchor.capture()
                expanded = next
                if (next) mounted.value = true
                currentExpandedChange?.invoke(next)
            }
        }
    }
    LaunchedEffect(actualEnabled) {
        if (!actualEnabled) setExpanded(false)
    }
    val haptic = LocalHapticFeedback.current
    val actionColor = if (actualEnabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    BasicComponent(
        title = title,
        summary = summary,
        modifier = modifier.onGloballyPositioned { rowAnchor.onPositioned(it, mounted.value) },
        endActions = {
            Row(Modifier.glassPopupAnchorValue(anchor), verticalAlignment = Alignment.CenterVertically) {
                entry.items.firstOrNull { it.selected }?.text?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(end = 8.dp).weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                        textAlign = TextAlign.End,
                    )
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
        insideMargin = insideMargin,
        onClick = {
            setExpanded(!expanded)
            if (expanded) haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        },
        role = Role.DropdownList,
        holdDownState = mounted.value,
        enabled = actualEnabled,
    )
    if (!mounted.value) return
    PopupLayout(
        visible = mounted,
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        renderInRootScaffold = renderInRootScaffold,
    ) {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
            GlassDropdownPopupContent(
                show = expanded && actualEnabled,
                onDismissRequest = { setExpanded(false) },
                anchorBounds = rowAnchor.bounds,
                backdrop = backdrop,
                anchor = anchor,
                sizing = sizing,
                coordinateOrigin = origin,
                onDismissFinished = { mounted.value = false },
            ) {
                entry.items.forEach { item ->
                    GlassPopupItem(
                        text = item.text,
                        summary = item.summary,
                        enabled = actualEnabled && item.enabled,
                        selected = item.selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            setExpanded(false)
                            item.onClick?.invoke()
                        },
                    )
                }
            }
        }
    }
}
