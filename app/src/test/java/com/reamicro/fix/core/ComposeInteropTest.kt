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

    @Test
    fun `带 inline class mangling 后缀的 composable 能被定位`() {
        // 2.3.1 的宿主 AppTopBar 就是这个形状：新增 Color 参数使 JVM 方法名多了 -cd68TDI 后缀。
        val found = interop.findComposableMethod(
            candidates = Composables::class.java.declaredMethods,
            baseName = "AppTopBar",
            composerClassName = FakeComposer::class.java.name,
            firstParameterType = String::class.java,
        )
        assertEquals("AppTopBar-cd68TDI", found?.name)
    }

    @Test
    fun `没有 mangling 后缀的裸名 composable 同样能被定位`() {
        val found = interop.findComposableMethod(
            candidates = Composables::class.java.declaredMethods,
            baseName = "PlainBar",
            composerClassName = FakeComposer::class.java.name,
            firstParameterType = String::class.java,
        )
        assertEquals("PlainBar", found?.name)
    }

    @Test
    fun `新旧重载共存时取参数最多者`() {
        // 宿主同时留有两个版本的重载时，参数多的那个才是当前版本的完整签名。
        val found = interop.findComposableMethod(
            candidates = Overloaded::class.java.declaredMethods,
            baseName = "AppTopBar",
            composerClassName = FakeComposer::class.java.name,
            firstParameterType = String::class.java,
        )
        assertEquals("AppTopBar-cd68TDI", found?.name)
        assertEquals(5, found?.parameterTypes?.size)
    }

    @Test
    fun `同前缀但不同名的函数不会被误命中`() {
        // baseName 后必须紧跟连字符，否则 AppTopBarPreview 这类会被当成目标。
        val found = interop.findComposableMethod(
            candidates = Decoys::class.java.declaredMethods,
            baseName = "AppTopBar",
            composerClassName = FakeComposer::class.java.name,
            firstParameterType = String::class.java,
        )
        assertEquals(null, found)
    }

    @Test
    fun `末三参不是 Composer int int 时挑不出方法`() {
        // 这一条同时排除了 $default 桥接方法：它的末三参是三个 int。
        val found = interop.findComposableMethod(
            candidates = NotComposable::class.java.declaredMethods,
            baseName = "AppTopBar",
            composerClassName = FakeComposer::class.java.name,
        )
        assertEquals(null, found)
    }

    @Test
    fun `首参类型不符时挑不出方法`() {
        val found = interop.findComposableMethod(
            candidates = Composables::class.java.declaredMethods,
            baseName = "AppTopBar",
            composerClassName = FakeComposer::class.java.name,
            firstParameterType = Int::class.java,
        )
        assertEquals(null, found)
    }

    @Suppress("unused", "FunctionName")
    private class Sample {
        fun `toArgb-8_81llA`(color: Long): Int = color.toInt()
        fun somethingElse(): Int = 0
    }

    private class FakeComposer

    @Suppress("unused", "FunctionName", "LongParameterList")
    private class Composables {
        fun `AppTopBar-cd68TDI`(
            title: String,
            contentColor: Long,
            composer: FakeComposer,
            changed: Int,
            default: Int,
        ) = Unit

        fun PlainBar(title: String, composer: FakeComposer, changed: Int, default: Int) = Unit
    }

    @Suppress("unused", "FunctionName", "LongParameterList")
    private class Overloaded {
        fun AppTopBar(title: String, composer: FakeComposer, changed: Int, default: Int) = Unit

        fun `AppTopBar-cd68TDI`(
            title: String,
            contentColor: Long,
            composer: FakeComposer,
            changed: Int,
            default: Int,
        ) = Unit
    }

    @Suppress("unused", "FunctionName")
    private class Decoys {
        fun AppTopBarPreview(title: String, composer: FakeComposer, changed: Int, default: Int) = Unit
        fun AppTopBarDefaults(title: String, composer: FakeComposer, changed: Int, default: Int) = Unit
    }

    @Suppress("unused", "FunctionName")
    private class NotComposable {
        /** $default 桥接方法的形状：末三参全是 int，没有 Composer。 */
        fun AppTopBar(title: String, changed: Int, default: Int, mask: Int) = Unit
    }
}
