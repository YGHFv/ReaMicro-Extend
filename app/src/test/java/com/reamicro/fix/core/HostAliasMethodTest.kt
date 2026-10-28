package com.reamicro.fix.core

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAliasMethodTest {
    class Merged {
        fun a() = Unit
    }
    interface Callback {
        fun c(first: Any, second: Any): Any
    }
    class CombinedCallback : Callback {
        override fun c(first: Any, second: Any): Any = first
    }

    @After
    fun reset() = HostSymbols.reset()

    @Test
    fun mergedFacadePrefixResolvesAliasMembers() {
        val parsed = HostProfile.parse(
            """
            profile=fixture
            requiredClass=fixture.Marker
            class=logical.Primary=${Merged::class.java.name}
            typeAlias=logical.Secondary=${Merged::class.java.name}
            method=logical.Secondary#FamilyRow-valueClass=a
            """.trimIndent(),
        )
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, parsed)
        val method = Merged::class.java.getDeclaredMethod("a")
        assertTrue(method.hostNameStartsWith("FamilyRow"))
    }

    @Test
    fun mergedCallbackResolvesInterfaceMember() {
        val parsed = HostProfile.parse(
            """
            profile=fixture
            requiredClass=fixture.Marker
            class=logical.Callback=${Callback::class.java.name}
            method=logical.Callback#invoke=c
            """.trimIndent(),
        )
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, parsed)
        val method = CombinedCallback::class.java.getDeclaredMethod("c", Any::class.java, Any::class.java)
        assertTrue(method.hostNameMatches("invoke"))
    }
}
