package com.reamicro.fix.hook

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap

internal object StructureBookDetailDividerStyle {
    data class Metrics(val horizontalInset: Float, val thickness: Float)
    fun metrics(scale: Float) = normalizeScale(scale).let { Metrics(16f * it, .8f * it) }
    internal fun normalizeScale(scale: Float) = scale.takeIf { it.isFinite() && it > 0f } ?: 1f

    private data class Reader(val instance: Any, val getter: java.lang.reflect.Method)
    private val readers = WeakHashMap<ClassLoader, Reader?>()
    fun scale(loader: ClassLoader): Float = runCatching {
        val reader = synchronized(readers) {
            if (readers.containsKey(loader)) readers[loader] else {
                val found = runCatching {
                    val type = loader.loadClass("app.zhendong.reamicro.arch.AppScaleManager")
                    Reader(type.getField("INSTANCE").get(null), type.getMethod("getScaleFactor"))
                }.getOrNull()
                readers[loader] = found
                found
            }
        } ?: return@runCatching 1f
        normalizeScale((reader.getter.invoke(reader.instance) as Number).toFloat())
    }.getOrDefault(1f)
}

@Composable
internal fun StructureBookDetailDivider(loader: ClassLoader, palette: StructureHome130Style.Palette) {

    val metrics = StructureBookDetailDividerStyle.metrics(StructureBookDetailDividerStyle.scale(loader))
    HorizontalDivider(Modifier.padding(horizontal = metrics.horizontalInset.dp),
        thickness = metrics.thickness.dp, color = palette.borderVariant)
}
