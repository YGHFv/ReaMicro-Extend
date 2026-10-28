package com.reamicro.fix.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HostAccessorTest {
    open class Parent {
        @JvmField var a = 42L
        @JvmField var b = true
        fun c() = "getter"
    }
    class Child : Parent()
    interface Scope { fun d(): Any }
    class DrawScope : Scope { override fun d(): Any = "canvas" }

    @Before fun install() {
        val profile = HostProfile.parse(
            """
            profile=fixture
            requiredClass=fixture.Marker
            class=logical.Parent=${Parent::class.java.name}
            class=logical.Scope=${Scope::class.java.name}
            field=logical.Parent#primary=a
            field=logical.Parent#isAttached=b
            method=logical.Parent#getTitle=c
            method=logical.Scope#getDrawContext=d
            """.trimIndent(),
        )
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, profile)
    }

    @After fun reset() = HostSymbols.reset()

    @Test fun inlinedAndInheritedGettersBindToFields() {
        val target = Child()
        val accessor = requireNotNull(HostAccessor.bind(target.javaClass, "getPrimary-0d7_KjU"))
        assertEquals(42L, accessor.get(target))
        target.a = 7L
        assertEquals(7L, accessor.get(target))
        assertEquals(true, HostAccessor.bind(target.javaClass, "isAttached")?.get(target))
    }

    @Test fun existingGetterAndInterfaceMappingTakePrecedence() {
        assertEquals("getter", HostAccessor.bind(Child::class.java, "getTitle")?.get(Child()))
        val scope = DrawScope()
        assertEquals("canvas", HostAccessor.bind(scope.javaClass, "getDrawContext")?.get(scope))
    }

    @Test fun absentAccessorDoesNotInventAField() {
        assertNull(HostAccessor.bind(Child::class.java, "getMissing"))
    }
}
