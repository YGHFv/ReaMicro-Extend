package com.reamicro.fix.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模拟内联字段和非线性 Density，确保迁移没有退化为直接乘 fontScale。 */
class HostTypographyMetricsTest {
    class Weight(@JvmField val a: Int)
    class Span(@JvmField val b: Long, @JvmField val h: Long, @JvmField val c: Weight)
    class Paragraph(@JvmField val c: Long)
    class Style(@JvmField val a: Span, @JvmField val b: Paragraph)
    class Typography(@JvmField val j: Style)
    class Values(@JvmField val b: Typography)
    class Locals {
        companion object { @JvmField val h = Any() }
    }
    class Theme {
        companion object { @JvmField val a = Any() }
    }
    class Composer(val values: Values, val density: Density) {
        fun j(local: Any): Any = if (local === Theme.a) values else {
            check(local === Locals.h)
            density
        }
    }
    class Density {
        fun getDensity() = 2f
        fun a0() = 1.5f
        fun D0(packed: Long): Float = UnitBits.c(packed).let { it * it * getDensity() }
    }
    class UnitBits {
        companion object {
            @JvmStatic fun b(packed: Long) = packed and 0xff00000000L
            @JvmStatic fun c(packed: Long) = Float.fromBits(packed.toInt())
        }
    }

    private fun pack(value: Float, kind: Long) = kind or (value.toRawBits().toLong() and 0xffffffffL)
    @After fun reset() = HostSymbols.reset()

    @Test fun `native fields preserve sp em weight and density conversion`() {
        val names = mapOf(
            "androidx.compose.runtime.Composer" to Composer::class.java.name,
            "androidx.compose.material3.MaterialThemeKt" to Theme::class.java.name,
            "androidx.compose.material3.MaterialTheme\$Values" to Values::class.java.name,
            "androidx.compose.material3.Typography" to Typography::class.java.name,
            "androidx.compose.ui.platform.CompositionLocalsKt" to Locals::class.java.name,
            "androidx.compose.ui.unit.Density" to Density::class.java.name,
            "androidx.compose.ui.unit.TextUnit" to UnitBits::class.java.name,
            "androidx.compose.ui.text.TextStyle" to Style::class.java.name,
            "androidx.compose.ui.text.SpanStyle" to Span::class.java.name,
            "androidx.compose.ui.text.ParagraphStyle" to Paragraph::class.java.name,
            "androidx.compose.ui.text.font.FontWeight" to Weight::class.java.name,
        )
        val text = "profile=2.3.3-b4\nrequiredClass=fixture.Marker\n" +
            names.entries.joinToString("\n") { "class=${it.key}=${it.value}" } + "\n" +
            """
            field=androidx.compose.material3.MaterialThemeKt#_localMaterialTheme=a
            field=androidx.compose.material3.MaterialTheme${'$'}Values#typography=b
            field=androidx.compose.material3.Typography#bodyLarge=j
            field=androidx.compose.ui.platform.CompositionLocalsKt#LocalDensity=h
            field=androidx.compose.ui.text.TextStyle#spanStyle=a
            field=androidx.compose.ui.text.TextStyle#paragraphStyle=b
            field=androidx.compose.ui.text.SpanStyle#fontSize=b
            field=androidx.compose.ui.text.SpanStyle#letterSpacing=h
            field=androidx.compose.ui.text.SpanStyle#fontWeight=c
            field=androidx.compose.ui.text.ParagraphStyle#lineHeight=c
            field=androidx.compose.ui.text.font.FontWeight#weight=a
            method=androidx.compose.runtime.Composer#consume=j
            method=androidx.compose.ui.unit.Density#getFontScale=a0
            method=androidx.compose.ui.unit.Density#toPx--R2X_6o=D0
            method=androidx.compose.ui.unit.TextUnit#getValue-impl=c
            method=androidx.compose.ui.unit.TextUnit#getType-UIouoOA=b
            """.trimIndent()
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }
            .set(null, HostProfile.parse(text))
        val style = Style(Span(pack(12f, HostTypographyMetrics.SP),
            pack(.1f, HostTypographyMetrics.EM), Weight(650)),
            Paragraph(pack(1.5f, HostTypographyMetrics.EM)))
        val native = HostTypographyMetrics(javaClass.classLoader,
            Composer(Values(Typography(style)), Density()))
        val read = native.style("BodyLarge")
        assertEquals(HostTypographyMetrics.UnitValue(12f, false), read.fontSize)
        assertEquals(HostTypographyMetrics.UnitValue(1.5f, true), read.lineHeight)
        assertEquals(HostTypographyMetrics.UnitValue(.1f, true), read.letterSpacing)
        assertEquals(650, read.fontWeight)
        assertEquals(288f, read.fontSizePx, 0f)
        assertEquals(0f, read.lineHeightPx, 0f)
        assertEquals(2f to 1.5f, native.densityKey)
    }

    @Test fun `unspecified and invalid units never become visible numeric defaults`() {
        assertNull(HostTypographyMetrics.decode(Float.NaN, HostTypographyMetrics.SP))
        assertNull(HostTypographyMetrics.decode(12f, 0L))
        assertNull(HostTypographyMetrics.decode(12f, 0x300000000L))
        assertNull(HostTypographyMetrics.decode(Float.POSITIVE_INFINITY, HostTypographyMetrics.EM))
    }

    @Test fun `b4 metric capture and app scale use the verified native entry points`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val parsed = HostProfile.parse(main.resolve("assets/host-profiles/2.3.3-b4.profile")
            .readText(Charsets.UTF_8))
        assertEquals("pc2", parsed.classOf("androidx.compose.ui.unit.Density"))
        assertEquals("cr1", parsed.classOf("androidx.compose.ui.platform.CompositionLocalsKt"))
        assertEquals("h", parsed.fieldOf("androidx.compose.ui.platform.CompositionLocalsKt", "LocalDensity"))
        assertEquals("D0", parsed.methodOf("androidx.compose.ui.unit.Density", "toPx--R2X_6o"))
        assertEquals("y5b", parsed.classOf("androidx.compose.ui.unit.TextUnit"))
        assertEquals("c", parsed.methodOf("androidx.compose.ui.unit.TextUnit", "getValue-impl"))
        assertEquals("yyd", parsed.methodOwners["app.zhendong.reamicro.arch.extensions.UnitExtKt#getUdp"])
        assertEquals("f", parsed.methodOf("app.zhendong.reamicro.arch.extensions.UnitExtKt", "getUdp"))
        val sources = main.resolve("java/com/reamicro/fix/hook")
        for (file in listOf("BookImageDialogStyle.kt", "EmbeddedHostUi.kt")) {
            assertTrue(sources.resolve(file).readText(Charsets.UTF_8).contains("HostTypographyMetrics(loader, composer)"))
        }
        val scale = sources.resolve("StructureBookDetailDivider.kt").readText(Charsets.UTF_8)
            .substringAfter("if (HostSymbols.profileId == \"2.3.3-b4\")")
            .substringBefore("val reader =")
        assertTrue(scale.contains("\"getUdp\", Float::class.javaPrimitiveType"))
        assertTrue(scale.contains(".invoke(null, 1f)"))
        assertFalse(scale.contains("scaleFactor"))
    }
}
