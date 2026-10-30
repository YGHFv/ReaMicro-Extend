package com.reamicro.fix.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostReflectionTest {
    interface Callback
    class NarrowedCallback : Callback
    open class Parent {
        @JvmField var a = true
        @JvmField var b = 0
        fun r(value: Int) = value
        fun s(callback: NarrowedCallback) = callback
    }
    class Child : Parent()
    class Relocated {
        companion object {
            @JvmStatic fun z(value: Int) = value * 2
        }
    }

    @Before
    fun installFixture() {
        HostSymbols.reset()
        val parsed = HostProfile.parse(
            """
            profile=fixture
            requiredClass=fixture.Marker
            class=logical.Parent=${Parent::class.java.name}
            class=logical.Child=${Child::class.java.name}
            class=logical.Callback=${Callback::class.java.name}
            method=logical.Parent#updateUiState=r
            method=logical.Parent#staticValue=z
            methodOwner=logical.Parent#staticValue=${Relocated::class.java.name}
            field=logical.Parent#isReady=a
            field=logical.Parent#tag=a
            field=logical.Parent#FLIP_STYLE=b
            """.trimIndent(),
        )
        // 纯 JVM 测试只注入解析结果，不触发 Android 初始化和框架日志。
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, parsed)
    }

    @After
    fun clearFixture() = HostSymbols.reset()

    @Test
    fun `inherited members resolve on declaring owner`() {
        val method = Child::class.java.hostMethod("updateUiState", Int::class.javaPrimitiveType)
        assertEquals("r", method.name)
        assertEquals("a", Child::class.java.hostField("isReady").name)
        assertEquals("r", HostSymbols.resolveMethodName(Child::class.java, "updateUiState"))
    }

    @Test
    fun `narrowed interface parameters remain recognizable`() {
        assertTrue(typeMatches(NarrowedCallback::class.java, "logical.Callback"))
        assertFalse(typeMatches(Child::class.java, "logical.Callback"))
        assertFalse(typeMatches(Child::class.java, "java.lang.Object"))
        assertTrue(typeMatches(java.util.ArrayList::class.java, "java.util.List"))
        assertTrue(typeMatches(java.io.StringReader::class.java, "java.io.Reader"))
        assertFalse(typeMatches(String::class.java, "java.io.Reader"))
        val method = Parent::class.java.getDeclaredMethod("s", NarrowedCallback::class.java)
        assertTrue(method.isSuspendingContinuationParameter(0, "logical.Callback"))
        assertFalse(method.isSuspendingContinuationParameter(1, "logical.Callback"))
    }

    @Test
    fun `deleted accessors retain boolean and uppercase property spelling`() {
        assertEquals("a", HostSymbols.resolveAccessorFieldName("logical.Parent", "isReady"))
        assertEquals("a", HostSymbols.resolveAccessorFieldName("logical.Parent", "getIsReady-valueClass"))
        assertEquals("b", HostSymbols.resolveAccessorFieldName("logical.Parent", "getFLIP_STYLE"))
        assertEquals("a", HostSymbols.resolveAccessorFieldName(Child::class.java, "getTag"))
    }

    @Test
    fun `relocated facade methods resolve without loading the old facade`() {
        val method = Parent::class.java.hostMethod("staticValue", Int::class.javaPrimitiveType)
        assertEquals(Relocated::class.java, method.declaringClass)
        assertEquals(6, method.invoke(null, 3))
        val direct = Parent::class.java.classLoader.hostStaticMethod(
            "logical.Parent", "staticValue", Int::class.javaPrimitiveType,
        )
        assertEquals(8, direct.invoke(null, 4))
        val reflected = HostReflect(Parent::class.java.classLoader)
            .method("logical.Parent", "staticValue", 1)
        assertEquals(Relocated::class.java, reflected.declaringClass)
        assertEquals(10, reflected.invoke(null, 5))
    }
}
