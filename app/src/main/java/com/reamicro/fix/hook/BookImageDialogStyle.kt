package com.reamicro.fix.hook

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal object BookImageDialogStyle {
    private data class UnitValue(val value: Float, val em: Boolean) {
        fun local(): TextUnit = if (em) value.em else value.sp
    }
    private data class Type(val size: UnitValue?, val line: UnitValue?, val spacing: UnitValue?, val weight: Int)
    @Volatile private var types: Map<String, Type> = emptyMap()
    private val roles = listOf("TitleSmall", "BodyLarge", "BodySmall", "LabelMedium")

    fun capture(loader: ClassLoader, composer: Any?) {
        if (composer == null) return
        runCatching {
            val composerClass = loader.loadClass("androidx.compose.runtime.Composer")
            if (!composerClass.isInstance(composer)) return
            val material = loader.loadClass("androidx.compose.material3.MaterialTheme")
            val typography = material.getMethod("getTypography", composerClass, Int::class.javaPrimitiveType)
                .invoke(material.getField("INSTANCE").get(null), composer, 6)
            val unitClass = loader.loadClass("androidx.compose.ui.unit.TextUnit")
            val value = unitClass.getDeclaredMethod("getValue-impl", Long::class.javaPrimitiveType)
            val isEm = unitClass.getDeclaredMethod("isEm-impl", Long::class.javaPrimitiveType)
            val isSp = unitClass.getDeclaredMethod("isSp-impl", Long::class.javaPrimitiveType)
            fun unit(style: Any, name: String): UnitValue? {
                val packed = style.javaClass.methods.single { it.name.startsWith("$name-") && it.parameterCount == 0 }
                    .invoke(style) as Long
                if (isSp.invoke(null, packed) != true && isEm.invoke(null, packed) != true) return null
                val number = (value.invoke(null, packed) as Number).toFloat()
                return number.takeIf { it.isFinite() }?.let { UnitValue(it, isEm.invoke(null, packed) == true) }
            }
            val next = roles.associateWith { role ->
                val style = requireNotNull(typography.javaClass.getMethod("get$role").invoke(typography))
                val weight = style.javaClass.getMethod("getFontWeight").invoke(style)
                Type(unit(style, "getFontSize"), unit(style, "getLineHeight"), unit(style, "getLetterSpacing"),
                    (weight?.javaClass?.getMethod("getWeight")?.invoke(weight) as? Number)?.toInt() ?: 400)
            }
            types = next
        }
    }

    fun typography(context: Context, scale: Float): Typography {
        val family = EmbeddedHostUi.uiTypeface(context)?.let { FontFamily(it) }
            ?: StructureHome130Style.fonts(context).serif
        fun style(role: String, size: Int, bold: Boolean, spacing: Float): TextStyle {
            val native = types[role]
            return TextStyle(fontFamily = family,
                fontSize = native?.size?.local() ?: (size * scale).sp,
                lineHeight = native?.line?.local() ?: 1.5.em,
                letterSpacing = native?.spacing?.local() ?: (spacing * scale).sp,
                fontWeight = FontWeight(native?.weight ?: if (bold) 700 else 400),
                platformStyle = PlatformTextStyle(includeFontPadding = false))
        }
        return Typography(
            titleSmall = style("TitleSmall", 15, true, 1f),
            bodyLarge = style("BodyLarge", 14, false, 1f),
            bodySmall = style("BodySmall", 10, false, .5f),
            labelMedium = style("LabelMedium", 12, true, 1f),
        )
    }

    const val SHEET_CORNER = 20f
    const val CARD_CORNER = 8f
    const val BUTTON_CORNER = 4f
    const val BORDER = .8f
    const val HANDLE_WIDTH = 40f
    const val HANDLE_HEIGHT = 4f
    const val HANDLE_TOP = 14f
    const val HANDLE_BOTTOM = 16f
}
