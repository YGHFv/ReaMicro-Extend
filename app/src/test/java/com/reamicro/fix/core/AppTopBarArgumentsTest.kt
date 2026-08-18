package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 锁住 `AppTopBar` 反射实参的落位与 `$default` 掩码。
 *
 * 这处映射在 2.2.0→2.3.0→2.3.1 三次宿主升级里连续断了三次，每次的表现都是「顶栏静默消失」：
 * 异常被 Compose 函数代理的 runCatching 吞掉，编译器不报错、真机也不崩，只有 LSPosed 日志
 * 留一行。所以三代签名都在这里钉住，而不是等下次装机时靠肉眼发现。
 */
class AppTopBarArgumentsTest {

    private val composer = Any()
    private val onBack = Any()
    private val navIcon = Any()
    private val insets = Any()

    /** 2.3.1 beta：10 参，`onNavigationBack` 之后多了 inline class `contentColor: Color`（JVM 上是 long）。 */
    private val signature231 = arrayOf<Class<*>>(
        String::class.java,
        Function2::class.java,
        FakeWindowInsets::class.java,
        FakeImageVector::class.java,
        Function0::class.java,
        java.lang.Long.TYPE,
        Function3::class.java,
        FakeComposer::class.java,
        Integer.TYPE,
        Integer.TYPE,
    )

    /** 2.3.0 beta：9 参，参数重排 + 新增尾随内容槽 Function3，还没有 contentColor。 */
    private val signature230 = arrayOf<Class<*>>(
        String::class.java,
        Function2::class.java,
        FakeWindowInsets::class.java,
        FakeImageVector::class.java,
        Function0::class.java,
        Function3::class.java,
        FakeComposer::class.java,
        Integer.TYPE,
        Integer.TYPE,
    )

    /** 2.2.0：8 参，insets/navIcon 在前、Function0 在第 4 位、尾随槽是 Function2。 */
    private val signature220 = arrayOf<Class<*>>(
        String::class.java,
        FakeWindowInsets::class.java,
        FakeImageVector::class.java,
        Function0::class.java,
        Function2::class.java,
        FakeComposer::class.java,
        Integer.TYPE,
        Integer.TYPE,
    )

    private fun plan(
        parameterTypes: Array<Class<*>>,
        onBack: Any? = null,
        navIcon: Any? = null,
        windowInsets: Any? = null,
    ) = AppTopBarArguments.plan(
        parameterTypes = parameterTypes,
        composer = composer,
        title = TITLE,
        onBack = onBack,
        navIcon = navIcon,
        windowInsets = windowInsets,
        function0ClassName = Function0::class.java.name,
        imageVectorClassName = FakeImageVector::class.java.name,
        windowInsetsClassName = FakeWindowInsets::class.java.name,
    )

    @Test
    fun `2_3_1 十参签名下 title 与 onBack 各自落位`() {
        val args = plan(signature231, onBack = onBack)
        assertEquals(10, args.size)
        assertEquals(TITLE, args[0])
        assertSame(onBack, args[4])
        assertSame(composer, args[7])
        assertEquals(0, args[8])
    }

    @Test
    fun `2_3_1 的 contentColor 是基元 long 必须填零值而非 null`() {
        // 这一条是 2.3.1 回归的第二个致命点：置了 $default 位宿主也会忽略实参，
        // 但 Method.invoke 仍要过形参类型校验，给基元 long 传 null 直接抛 IllegalArgumentException。
        val args = plan(signature231, onBack = onBack)
        assertEquals(0L, args[5])
    }

    @Test
    fun `2_3_1 设置页只覆盖 title 与 onBack 其余走宿主默认值`() {
        val args = plan(signature231, onBack = onBack)
        // titleContent(2) | insets(4) | navIcon(8) | contentColor(32) | actions(64)
        assertEquals(2 or 4 or 8 or 32 or 64, args[9])
        assertNull(args[1])
        assertNull(args[2])
        assertNull(args[3])
        assertNull(args[6])
    }

    @Test
    fun `2_3_1 阅读页 sheet 额外覆盖 navIcon 与 insets 后不再置对应默认位`() {
        val args = plan(signature231, onBack = onBack, navIcon = navIcon, windowInsets = insets)
        assertSame(insets, args[2])
        assertSame(navIcon, args[3])
        assertSame(onBack, args[4])
        // titleContent(2) | contentColor(32) | actions(64)
        assertEquals(2 or 32 or 64, args[9])
    }

    @Test
    fun `2_3_0 九参签名仍然落位正确`() {
        val args = plan(signature230, onBack = onBack)
        assertEquals(9, args.size)
        assertEquals(TITLE, args[0])
        assertSame(onBack, args[4])
        assertSame(composer, args[6])
        assertEquals(0, args[7])
        // titleContent(2) | insets(4) | navIcon(8) | actions(32)
        assertEquals(2 or 4 or 8 or 32, args[8])
    }

    @Test
    fun `2_2_0 八参旧序下 onBack 按类型落到第 4 位`() {
        val args = plan(signature220, onBack = onBack)
        assertEquals(8, args.size)
        assertEquals(TITLE, args[0])
        assertSame(onBack, args[3])
        assertSame(composer, args[5])
        assertEquals(0, args[6])
        // insets(2) | navIcon(4) | titleContent(16)
        assertEquals(2 or 4 or 16, args[7])
    }

    @Test
    fun `未提供 onBack 时 Function0 位也走宿主默认值`() {
        val args = plan(signature231)
        assertNull(args[4])
        // 连 onNavigationBack(16) 一起置位
        assertEquals(2 or 4 or 8 or 16 or 32 or 64, args[9])
    }

    @Test
    fun `所有基元形参都填对应零值而不是 null`() {
        // 宿主下次再加 Dp / TextUnit 这类 inline class 参数时不该再断一次。
        val signature = arrayOf<Class<*>>(
            String::class.java,
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            java.lang.Float.TYPE,
            java.lang.Double.TYPE,
            FakeComposer::class.java,
            Integer.TYPE,
            Integer.TYPE,
        )
        val args = plan(signature)
        assertEquals(false, args[1])
        assertEquals(0, args[2])
        assertEquals(0f, args[3])
        assertEquals(0.0, args[4])
        assertEquals(2 or 4 or 8 or 16, args[7])
    }

    @Test
    fun `铺出的实参能真的通过 Method_invoke 的形参校验`() {
        // 前面几条只断言数组内容；这一条真的把参数交给反射调用一次。
        // 2.3.1 的 contentColor 是基元 long，实参给 null 会在这里抛 IllegalArgumentException——
        // 而线上这个异常被 Compose 函数代理的 runCatching 吞掉，只表现为顶栏消失，所以必须在这层拦住。
        val method = HostSignature::class.java.declaredMethods
            .single { it.name == "AppTopBar-cd68TDI" }
            .apply { isAccessible = true }
        val args = AppTopBarArguments.plan(
            parameterTypes = method.parameterTypes,
            composer = FakeComposer(),
            title = TITLE,
            onBack = { Unit } as Function0<Unit>,
            function0ClassName = Function0::class.java.name,
            imageVectorClassName = FakeImageVector::class.java.name,
            windowInsetsClassName = FakeWindowInsets::class.java.name,
        )
        val received = method.invoke(null, *args) as String
        assertEquals("$TITLE|onBack=yes|contentColor=0|default=110", received)
    }

    private class FakeComposer
    private class FakeImageVector
    private class FakeWindowInsets

    /**
     * 阅微 2.3.1 beta 里 `AppTopBar-cd68TDI` 的真实 JVM 形参序列（由 MT MCP 反编译核对）：
     * `(String, Function2, WindowInsets, ImageVector, Function0, long, Function3, Composer, int, int)`。
     * 这里用等价形状的静态方法复现，只为让反射调用真的发生一次。
     */
    @Suppress("unused", "FunctionName", "LongParameterList")
    private object HostSignature {
        @JvmStatic
        fun `AppTopBar-cd68TDI`(
            title: String,
            titleContent: Function2<*, *, *>?,
            windowInsets: FakeWindowInsets?,
            navigationIcon: FakeImageVector?,
            onNavigationBack: Function0<*>?,
            contentColor: Long,
            actions: Function3<*, *, *, *>?,
            composer: FakeComposer,
            changed: Int,
            default: Int,
        ): String {
            require(titleContent == null && windowInsets == null && navigationIcon == null && actions == null)
            require(composer is FakeComposer && changed == 0)
            val back = if (onNavigationBack != null) "yes" else "no"
            return "$title|onBack=$back|contentColor=$contentColor|default=$default"
        }
    }

    private companion object {
        const val TITLE = "阅读补全"
    }
}
