package com.reamicro.fix.hook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy

@Composable
internal fun StructureScriptaBody(
    searchVisible: Boolean,
    modifier: Modifier = Modifier,
    editor: @Composable () -> Unit,
    search: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    // IME 动画只更新测量约束，不按每帧高度重新组合查找控件。
    Layout(modifier = modifier, content = {
        Box(propagateMinConstraints = true) { editor() }
        if (searchVisible) Box(propagateMinConstraints = true) { search() }
        Box(propagateMinConstraints = true) { footer() }
    }, measurePolicy = remember(searchVisible) {
        MeasurePolicy { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0)
            val footerPlaceable = measurables.last().measure(loose)
            val available = (constraints.maxHeight - footerPlaceable.height).coerceAtLeast(0)
            val searchPlaceable = if (searchVisible) {
                val heights = StructureScriptaLayout.searchHeights(
                    constraints.maxHeight, footerPlaceable.height, density, fontScale,
                )
                measurables[1].measure(loose.copy(minHeight = heights.min, maxHeight = heights.max))
            } else null
            val editorHeight = (available - (searchPlaceable?.height ?: 0)).coerceAtLeast(0)
            val editorPlaceable = measurables[0].measure(loose.copy(minHeight = editorHeight, maxHeight = editorHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                editorPlaceable.placeRelative(0, 0)
                searchPlaceable?.placeRelative(0, editorHeight)
                footerPlaceable.placeRelative(0, constraints.maxHeight - footerPlaceable.height)
            }
        }
    })
}

@Composable
internal fun StructureScriptaSourceBody(
    searchVisible: Boolean,
    header: @Composable () -> Unit,
    editor: @Composable () -> Unit,
) {
    // 紧凑模式仅停止测量和放置标题，代码编辑器及其缓存保留在同一组合位置。
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Column { header() }
        Box(propagateMinConstraints = true) { editor() }
    }, measurePolicy = remember(searchVisible) {
        MeasurePolicy { measurables, constraints ->
            val showHeader = StructureScriptaLayout.showSourceHeader(searchVisible, constraints.maxHeight, density)
            val headerPlaceable = if (showHeader) measurables[0].measure(constraints.copy(minHeight = 0)) else null
            val headerHeight = headerPlaceable?.height ?: 0
            val editorHeight = (constraints.maxHeight - headerHeight).coerceAtLeast(0)
            val editorPlaceable = measurables[1].measure(constraints.copy(minHeight = editorHeight, maxHeight = editorHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                headerPlaceable?.placeRelative(0, 0)
                editorPlaceable.placeRelative(0, headerHeight)
            }
        }
    })
}
