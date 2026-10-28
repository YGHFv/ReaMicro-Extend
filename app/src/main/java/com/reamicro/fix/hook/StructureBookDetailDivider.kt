package com.reamicro.fix.hook

import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostStaticMethod

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
        if (HostSymbols.profileId == "2.3.3-b4") {
            // getUdp(1f) 复用宿主懒初始化的真实缩放因子，不读取未初始化字段或重复计算。
            return@runCatching normalizeScale((loader.hostStaticMethod(
                "app.zhendong.reamicro.arch.extensions.UnitExtKt", "getUdp", Float::class.javaPrimitiveType,
            ).invoke(null, 1f) as Number).toFloat())
        }
        val reader = synchronized(readers) {
            if (readers.containsKey(loader)) readers[loader] else {
                val found = runCatching {
                    val type = loader.loadClass("app.zhendong.reamicro.arch.AppScaleManager")
                    Reader(type.hostField("INSTANCE").get(null), type.getMethod("getScaleFactor"))
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
