package com.reamicro.fix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.integration.ScaffoldGlassIconMenu
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleToolbarAction(
    icon: ModuleActionIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    canActivate: Boolean = enabled,
    tint: Color = MiuixTheme.colorScheme.onSurface,
    popupAnchor: GlassPopupAnchor? = null,
) {
    val vector = moduleActionIcon(icon)
    val os4 = LocalModuleThemeConfig.current.style == ModuleUiStyle.OS4
    val iconSize = if (os4) 28.dp else when (icon) {
        ModuleActionIcon.Accounts, ModuleActionIcon.Sort, ModuleActionIcon.Delete -> 24.dp
        ModuleActionIcon.Close -> 27.dp
        ModuleActionIcon.SelectAll -> 25.dp
        else -> vector.defaultWidth
    }
    val buttonSize = if (os4) {
        GlassTopAppBarDefaults.ButtonSize
    } else IconButtonDefaults.MinWidth
    val actionModifier = modifier.semantics {
        role = Role.Button
        if (!enabled || !canActivate) disabled()
    }
    val guardedClick = { if (enabled && canActivate) onClick() }
    val content = @Composable {
        Icon(
            imageVector = vector,
            contentDescription = description,
            modifier = Modifier.size(iconSize).then(
                popupAnchor?.let { Modifier.glassPopupAnchorContent(it) } ?: Modifier,
            ),
            tint = if (enabled && canActivate) tint else MiuixTheme.colorScheme.disabledOnSurface,
        )
    }
    // Reserve touch space outside the visual surface. Each native button remains the only
    // click owner, and its actual bounds still anchor the sorting popup.
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (useModuleGlass()) {
            GlassIconButton(
                onClick = guardedClick,
                modifier = actionModifier.then(
                    popupAnchor?.let { Modifier.glassPopupAnchor(it, cornerRadius = buttonSize / 2) } ?: Modifier,
                ),
                size = buttonSize,
                content = content,
            )
        } else {
            IconButton(
                onClick = guardedClick,
                modifier = actionModifier,
                enabled = enabled && canActivate,
                minWidth = buttonSize,
                minHeight = buttonSize,
                content = content,
            )
        }
    }
}

@Composable
internal fun ModuleTaskSortAction(
    mode: ModuleTaskSort,
    onSelect: (ModuleTaskSort) -> Unit,
    enabled: Boolean,
    canActivate: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlassPopupAnchor()
    val glass = useModuleGlass()
    LaunchedEffect(enabled, canActivate) { if (!enabled || !canActivate) expanded = false }
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val select: (ModuleTaskSort) -> Unit = {
        expanded = false
        onSelect(it)
    }
    val manual = stringResource(R.string.task_sort_manual)
    val enabledFirst = stringResource(R.string.task_sort_enabled_first)
    Box {
        ModuleToolbarAction(
            icon = ModuleActionIcon.Sort,
            description = stringResource(R.string.task_sort_title),
            onClick = { expanded = !expanded },
            popupAnchor = if (glass) anchor else null,
            enabled = enabled,
            canActivate = canActivate,
        )
        if (!glass) {
            OverlayListPopup(
                show = expanded,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { expanded = false },
            ) {
                ListPopupColumn {
                    DropdownImpl(text = manual, optionSize = 2, index = 0, isSelected = mode == ModuleTaskSort.Manual,
                        onSelectedIndexChange = { select(ModuleTaskSort.Manual) })
                    DropdownImpl(text = enabledFirst, optionSize = 2, index = 1, isSelected = mode == ModuleTaskSort.EnabledFirst,
                        onSelectedIndexChange = { select(ModuleTaskSort.EnabledFirst) })
                }
            }
        }
    }
    if (glass) {
        val entries = listOf(DropdownEntry(items = listOf(
            DropdownItem(text = manual, selected = mode == ModuleTaskSort.Manual,
                onClick = { select(ModuleTaskSort.Manual) }),
            DropdownItem(text = enabledFirst, selected = mode == ModuleTaskSort.EnabledFirst,
                onClick = { select(ModuleTaskSort.EnabledFirst) }),
        )))
        ScaffoldGlassIconMenu(
            show = expanded,
            anchor = anchor,
            onDismissRequest = { expanded = false },
            backdrop = LocalModuleSceneBackdrop.current,
            entries = entries,
            anchorContent = {
                Icon(
                    imageVector = moduleActionIcon(ModuleActionIcon.Sort),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
        )
    }
}
