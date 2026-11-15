package com.reamicro.fix.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled

/** Apply after the SectionCard's external margins, so only its actual silhouette casts a shadow. */
@Composable
internal fun Modifier.moduleTaskShadow(lifted: () -> Boolean): Modifier {
    val continuous = isSquircleEnabled()
    val outline = remember(continuous) {
        if (continuous) ModuleTaskCardShape else RoundedCornerShape(CardDefaults.CornerRadius)
    }
    return graphicsLayer {
        shape = outline
        shadowElevation = if (lifted()) 8.dp.toPx() else 0f
        // The Card's own squircleSurface owns content clipping. Its shadow extends outside.
        clip = false
    }
}

internal object ModuleTaskCardShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply {
            addSquircleRect(
                width = size.width,
                height = size.height,
                cornerRadius = with(density) { CardDefaults.CornerRadius.toPx() },
            )
        })
}
