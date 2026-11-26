package com.reamicro.fix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class ModuleGroupPosition(val roundsTop: Boolean, val roundsBottom: Boolean) {
    Single(true, true),
    First(true, false),
    Middle(false, false),
    Last(false, true),
}

internal fun moduleGroupPosition(index: Int, count: Int): ModuleGroupPosition {
    require(count > 0 && index in 0 until count)
    return when {
        count == 1 -> ModuleGroupPosition.Single
        index == 0 -> ModuleGroupPosition.First
        index == count - 1 -> ModuleGroupPosition.Last
        else -> ModuleGroupPosition.Middle
    }
}

/** Each row is lazy while adjacent surfaces retain the original single-card outline and spacing. */
internal fun <T> LazyListScope.modulePreferenceGroup(
    groupKey: String,
    rows: List<T>,
    rowKey: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    rows.forEachIndexed { index, row ->
        item(key = "$groupKey.${rowKey(row)}", contentType = "preference-row") {
            ModulePreferenceSurface(moduleGroupPosition(index, rows.size)) { content(row) }
        }
    }
}

@Composable
private fun ModulePreferenceSurface(
    position: ModuleGroupPosition,
    content: @Composable () -> Unit,
) {
    val color = MiuixTheme.colorScheme.surfaceContainer
    val surface = if (position == ModuleGroupPosition.Middle) {
        // A straight interior row needs neither a RuntimeShader nor an offscreen corner mask.
        Modifier.clipToBounds().background(color)
    } else {
        val top = if (position.roundsTop) CardDefaults.CornerRadius else 0.dp
        val bottom = if (position.roundsBottom) CardDefaults.CornerRadius else 0.dp
        Modifier.squircleSurface(color, top, top, bottom, bottom)
    }
    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurfaceContainer) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .then(if (position.roundsBottom) Modifier.padding(bottom = 12.dp) else Modifier)
                .semantics { isTraversalGroup = true }
                // Extend opaque straight joins outside the row's mask for half-resolution sampling.
                .then(if (!position.roundsBottom && color.alpha == 1f) Modifier.drawBehind {
                    drawRect(color, topLeft = Offset(0f, size.height), size = Size(size.width, 2f))
                } else Modifier)
                .then(surface),
            propagateMinConstraints = true,
        ) { content() }
    }
}
