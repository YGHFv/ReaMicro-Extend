package com.reamicro.fix.hook


import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal object StructureBookDetailDividerStyle {
    data class Metrics(val horizontalInset: Float, val thickness: Float)
    fun metrics(scale: Float) = normalizeScale(scale).let { Metrics(16f * it, .8f * it) }
    internal fun normalizeScale(scale: Float) = scale.takeIf { it.isFinite() && it > 0f } ?: 1f

    fun scale(loader: ClassLoader): Float = runCatching {
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        require(ui.loader === loader)
        normalizeScale((ui.call("dpInt", 1) as Number).toFloat())
    }.getOrDefault(1f)
}

@Composable
internal fun StructureBookDetailDivider(loader: ClassLoader, palette: HostEmbeddedStyle.Palette) {

    val metrics = StructureBookDetailDividerStyle.metrics(StructureBookDetailDividerStyle.scale(loader))
    HorizontalDivider(Modifier.padding(horizontal = metrics.horizontalInset.dp),
        thickness = metrics.thickness.dp, color = palette.borderVariant)
}
