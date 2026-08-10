package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeInteropTest {

    private val interop = ComposeInterop(
        classLoader = javaClass.classLoader!!,
        resolveClass = { error("测试不解析宿主类") },
        unitInstance = { null },
        logPrefix = "test",
    )

    @Test
    fun `sRGB 颜色兜底取高 32 位为 ARGB`() {
        // Compose Color 的位布局：高 32 位是 ARGB，低 6 位是色彩空间编号，0 = sRGB。
        val opaqueRed = 0xFFFF0000L shl 32
        assertEquals(0xFFFF0000.toInt(), interop.colorFallbackToArgb(opaqueRed))
    }

    @Test
    fun `半透明 sRGB 颜色兜底保留 alpha`() {
        val halfTransparentBlue = 0x800000FFL shl 32
        assertEquals(0x800000FF.toInt(), interop.colorFallbackToArgb(halfTransparentBlue))
    }

    @Test
    fun `非 sRGB 色彩空间无法位运算还原时返回兜底色`() {
        // 低 6 位非 0 表示不是 sRGB，此时高 32 位不是 ARGB，只能退回默认色。
        val displayP3 = (0xFFFF0000L shl 32) or 0x09L
        assertEquals(0, interop.colorFallbackToArgb(displayP3))
        assertEquals(0x123456, interop.colorFallbackToArgb(displayP3, fallback = 0x123456))
    }

    @Test
    fun `全透明黑在 sRGB 下兜底为 0`() {
        assertEquals(0, interop.colorFallbackToArgb(0L))
    }

    @Test
    fun `按名字片段与参数个数挑出混淆方法`() {
        val found = interop.findMangledMethod(Sample::class.java.declaredMethods, "toArgb", 1)
        assertEquals("toArgb-8_81llA", found?.name)
    }

    @Test
    fun `参数个数不匹配时挑不出方法`() {
        assertEquals(null, interop.findMangledMethod(Sample::class.java.declaredMethods, "toArgb", 3))
    }

    @Suppress("unused", "FunctionName")
    private class Sample {
        fun `toArgb-8_81llA`(color: Long): Int = color.toInt()
        fun somethingElse(): Int = 0
    }
}
