package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedHelpers

/** B4 的 TextStyle getter 已内联到 span/paragraph 字段，数值与像素转换仍用宿主实现。 */
internal class HostTypographyMetrics(
    private val loader: ClassLoader, val typography: Any, private val density: Any,
) {
    data class UnitValue(val value: Float, val em: Boolean)
    data class Style(
        val fontSize: UnitValue?, val lineHeight: UnitValue?, val letterSpacing: UnitValue?,
        val fontWeight: Int?, val fontSizePx: Float, val lineHeightPx: Float,
    )

    constructor(loader: ClassLoader, composer: Any) : this(
        loader, B2NativeUi(loader).theme(composer, "getTypography"),
        requireNotNull(XposedHelpers.callMethod(
            composer, "consume",
            loader.hostClass("androidx.compose.ui.platform.CompositionLocalsKt").hostField("LocalDensity").get(null),
        )),
    )

    private val reflect = HostReflect(loader)
    private val densityType = loader.hostClass("androidx.compose.ui.unit.Density")
    private val toPx = densityType.hostMethod("toPx--R2X_6o", Long::class.javaPrimitiveType)
    val densityKey: Pair<Float, Float> =
        (densityType.hostMethod("getDensity").invoke(density) as Number).toFloat() to
            (densityType.hostMethod("getFontScale").invoke(density) as Number).toFloat()
    private val unitValue = loader.hostStaticMethod(
        "androidx.compose.ui.unit.TextUnit", "getValue-impl", Long::class.javaPrimitiveType,
    )
    private val unitType = loader.hostStaticMethod(
        "androidx.compose.ui.unit.TextUnit", "getType-UIouoOA", Long::class.javaPrimitiveType,
    )

    init {
        check(HostSymbols.profileId == "2.3.3-b4") { "Unverified typography host" }
        check(densityType.isInstance(density))
    }

    fun style(role: String): Style {
        val style = requireNotNull(reflect.accessor(typography, "get$role"))
        val span = requireNotNull(reflect.accessor(style, "getSpanStyle"))
        val paragraph = requireNotNull(reflect.accessor(style, "getParagraphStyle"))
        val size = (reflect.accessor(span, "getFontSize") as Number).toLong()
        val line = (reflect.accessor(paragraph, "getLineHeight") as Number).toLong()
        val spacing = (reflect.accessor(span, "getLetterSpacing") as Number).toLong()
        val weight = reflect.accessor(span, "getFontWeight")
        return Style(unit(size), unit(line), unit(spacing),
            (weight?.let { reflect.accessor(it, "getWeight") } as? Number)?.toInt(),
            pixels(size), pixels(line))
    }

    private fun unit(packed: Long): UnitValue? {
        val kind = (unitType.invoke(null, packed) as Number).toLong()
        val value = (unitValue.invoke(null, packed) as Number).toFloat()
        return decode(value, kind)
    }

    private fun pixels(packed: Long): Float {
        if ((unitType.invoke(null, packed) as Number).toLong() != SP) return 0f
        // 使用 Density 的原生转换，保留 Android 非线性字体缩放，不能直接乘 fontScale。
        return (toPx.invoke(density, packed) as Number).toFloat()
            .takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    companion object {
        const val SP = 0x100000000L
        const val EM = 0x200000000L
        internal fun decode(value: Float, kind: Long): UnitValue? =
            value.takeIf { it.isFinite() && (kind == SP || kind == EM) }?.let { UnitValue(it, kind == EM) }
    }
}
