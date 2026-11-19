package com.reamicro.fix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/** Blend the two visible page recordings; never include an offscreen page in sampled material. */
@Stable
private class ModulePageBackdrops(
    private val pages: List<Backdrop>,
    private val visiblePages: () -> List<Int>,
) : Backdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        visiblePages().forEach { index ->
            pages.getOrNull(index)?.let {
                with(it) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
        }
    }
}

@Composable
internal fun rememberModulePageBackdrops(
    pages: List<Backdrop>,
    visiblePages: () -> List<Int>,
): Backdrop = remember(pages, visiblePages) { ModulePageBackdrops(pages, visiblePages) }
