package com.reamicro.fix.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun ModuleDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    summary: String? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    if (useModuleGlass() && isRuntimeShaderSupported()) {
        GlassDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = modifier,
            enabled = enabled,
            summary = summary,
            backdrop = LocalModuleSceneBackdrop.current,
            renderInRootScaffold = !LocalModuleWindowScaffold.current,
            insideMargin = insideMargin,
            sizing = if (maxHeight == null) GlassPopupSizing() else GlassPopupSizing(maxHeight = maxHeight),
            onExpandedChange = onExpandedChange,
        )
    } else {
        OverlayDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = modifier,
            enabled = enabled,
            summary = summary,
            renderInRootScaffold = !LocalModuleWindowScaffold.current,
            insideMargin = insideMargin,
            maxHeight = maxHeight,
            onExpandedChange = onExpandedChange,
        )
    }
}
