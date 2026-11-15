package com.reamicro.fix.core

/** Reads the host's actual text style fields and preserves its native font scaling. */
internal class HostTypographyMetrics(
    private val loader: ClassLoader, val typography: Any, private val density: Any,
) {
    data class UnitValue(val value: Float, val em: Boolean)
    data class Style(
        val fontSize: UnitValue?, val lineHeight: UnitValue?, val letterSpacing: UnitValue?,
        val fontWeight: Int?, val fontSizePx: Float, val lineHeightPx: Float,
    )

    constructor(loader: ClassLoader, composer: Any) : this(
        loader, requireNotNull(HostTypographyBindings.current).typography(composer),
        requireNotNull(HostTypographyBindings.current).density(composer),
    )

    private val symbols = requireNotNull(HostTypographyBindings.current) { "Typography contract unavailable" }
    private val densityType = symbols.type("density")
    private val toPx = symbols.method("toPx")
    val densityKey: Pair<Float, Float> =
        (symbols.method("density").invoke(density) as Number).toFloat() to
            (symbols.method("fontScale").invoke(density) as Number).toFloat()
    private val unitValue = symbols.method("unitValue")
    private val unitType = symbols.method("unitType")

    init {
        check(densityType.isInstance(density))
        check(symbols.type("typography").isInstance(typography))
    }

    fun style(role: String): Style {
        val style = requireNotNull(symbols.field("typography.${role.replaceFirstChar(Char::lowercaseChar)}").get(typography))
        val span = requireNotNull(symbols.field("span").get(style))
        val paragraph = requireNotNull(symbols.field("paragraph").get(style))
        val size = symbols.field("fontSize").getLong(span)
        val line = symbols.field("lineHeight").getLong(paragraph)
        val spacing = symbols.field("letterSpacing").getLong(span)
        val weight = symbols.field("fontWeight").get(span)
        return Style(unit(size), unit(line), unit(spacing),
            weight?.let { symbols.field("weight").getInt(it) },
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
