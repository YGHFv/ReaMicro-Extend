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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupItem
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
) {
    val vector = moduleActionIcon(icon)
    val os4 = LocalModuleThemeConfig.current.style == ModuleUiStyle.OS4
    // Compensate each glyph's viewport padding for a visible long edge of about 20dp.
    val iconSize = when (icon) {
        ModuleActionIcon.Accounts -> if (os4) 27.dp else 24.dp
        ModuleActionIcon.Sort -> if (os4) 27.5.dp else 24.dp
        ModuleActionIcon.Close -> if (os4) 31.5.dp else 27.dp
        ModuleActionIcon.SelectAll -> if (os4) 27.5.dp else 25.dp
        ModuleActionIcon.Delete -> if (os4) 25.dp else 24.dp
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
            modifier = Modifier.size(iconSize),
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
                modifier = actionModifier,
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
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val glass = useModuleGlass() && isRuntimeShaderSupported()
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
            modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
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
        // This adapter hosts the public GlassPopup at the Scaffold origin. The sampled scene
        // contains page/chrome only, so the popup and its dim layer cannot sample themselves.
        ModuleGlassActionPopup(
            show = expanded,
            anchorBounds = anchor,
            onDismissRequest = { expanded = false },
        ) {
            GlassPopupItem(text = manual, selected = mode == ModuleTaskSort.Manual, onClick = { select(ModuleTaskSort.Manual) })
            GlassPopupItem(text = enabledFirst, selected = mode == ModuleTaskSort.EnabledFirst, onClick = { select(ModuleTaskSort.EnabledFirst) })
        }
    }
}

@Composable
private fun ModuleGlassActionPopup(
    show: Boolean,
    anchorBounds: Rect,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    top.yukonga.miuix.kmp.integration.GlassActionPopup(
        show = show,
        anchorBounds = anchorBounds,
        onDismissRequest = onDismissRequest,
        backdrop = LocalModuleSceneBackdrop.current,
        content = content,
    )
}
