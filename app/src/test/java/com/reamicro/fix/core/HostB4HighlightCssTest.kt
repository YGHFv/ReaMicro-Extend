package com.reamicro.fix.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字号在保存后必须进入 SpanStyle，工厂迁移不能把 em/sp 丢成 unspecified。 */
class HostB4HighlightCssTest {
    class TextFactory {
        companion object {
            @JvmStatic fun k(amount: Float, kind: Long): Long =
                kind or (amount.toRawBits().toLong() and 0xffffffffL)
        }
    }

    @After fun reset() = HostSymbols.reset()

    @Test fun `relocated factory packs both em and sp without requiring the deleted facade`() {
        val profile = HostProfile.parse(
            "profile=fixture\nrequiredClass=fixture.Marker\n" +
                "methodOwner=androidx.compose.ui.unit.TextUnitKt#TextUnit-anM5pPY=${TextFactory::class.java.name}\n" +
                "method=androidx.compose.ui.unit.TextUnitKt#TextUnit-anM5pPY=k\n",
        )
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, profile)
        val method = javaClass.classLoader.hostStaticMethod(
            "androidx.compose.ui.unit.TextUnitKt", "TextUnit-anM5pPY",
            Float::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        for ((value, kind) in listOf(.3f to 0x200000000L, 2f to 0x200000000L, 24f to 0x100000000L)) {
            val packed = method.invoke(null, value, kind) as Long
            assertEquals(kind, packed and 0xff00000000L)
            assertEquals(value, Float.fromBits(packed.toInt()), 0f)
        }
    }

    @Test fun `b4 font size follows exact native factory and both span constructors`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val profile = HostProfile.parse(main.resolve("assets/host-profiles/2.3.3-b4.profile")
            .readText(Charsets.UTF_8))
        assertEquals("jtd", profile.methodOwners["androidx.compose.ui.unit.TextUnitKt#TextUnit-anM5pPY"])
        assertEquals("k", profile.methodOf("androidx.compose.ui.unit.TextUnitKt", "TextUnit-anM5pPY"))
        assertEquals("hea", profile.classOf("androidx.compose.ui.graphics.SolidColor"))
        val source = main.resolve("java/com/reamicro/fix/hook/ReaderDialogueHighlightHook.kt")
            .readText(Charsets.UTF_8)
        val pack = source.substringAfter("private fun packTextUnit(").substringBefore("private fun composeColor")
        assertTrue(pack.contains("classLoader.hostStaticMethod("))
        assertTrue(pack.contains("TEXT_UNIT_KT_CLASS, \"TextUnit-anM5pPY\""))
        assertTrue(pack.contains("0x200000000L else 0x100000000L"))
        val constructor = source.substringAfter("private val spanStyleConstructor by lazy")
            .substringBefore("private val reedenBoxStyleCache")
        assertTrue(constructor.contains("constructors.firstOrNull"))
        val brush = source.substringAfter("val created = if (constructor.parameterTypes.size == 17)")
            .substringBefore("} else if (")
        assertTrue(brush.contains("androidx.compose.ui.graphics.SolidColor"))
        assertTrue(brush.contains("fontSize ?: 0L"))
    }
}
