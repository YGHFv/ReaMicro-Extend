package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNativeAbiTest {
    private fun assets(): File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).first { it.isDirectory }

    @Test
    fun `native contracts do not leak to unverified profiles`() {
        for (id in listOf("identity", "2.3.3-b3", "2.3.3-b5", "2.3.3-b4-debug")) {
            assertNull(HostNativeAbi.navigation(id))
            assertNull(HostNativeAbi.http(id))
            assertFalse(HostNativeAbi.hasNativePaging(id))
            assertNull(HostNativeAbi.fileWrapperField(id))
        }
    }

    @Test
    fun `b2 native contracts retain their existing member names`() {
        assertEquals(HostNativeAbi.Navigation("a", "b", "a", "a"), HostNativeAbi.navigation("2.3.3-b2"))
        assertEquals(HostNativeAbi.Http(12, "z", "s", "w"), HostNativeAbi.http("2.3.3-b2"))
    }

    @Test
    fun `b4 navigation distinguishes scope from underlying controller`() {
        val parsed = HostProfile.parse(File(assets(), "host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8))
        assertEquals(HostNativeAbi.Navigation("b", "a", "a", "c"), HostNativeAbi.navigation("2.3.3-b4"))
        assertEquals("nw6", parsed.classOf("app.zhendong.reamicro.NavGraphScope"))
        assertEquals("ow6", parsed.classOf("ReaMicroHost.NavController"))
        assertEquals("b", parsed.methodOf("app.zhendong.reamicro.NavGraphScope", "navigate"))
        assertEquals("a", parsed.methodOf("app.zhendong.reamicro.NavGraphScope", "popBackStack"))
        assertEquals("a", parsed.fieldOf("app.zhendong.reamicro.NavGraphScope", "navController"))
    }

    @Test
    fun `b4 HTTP streaming asset binds only its own host types`() {
        assertEquals(HostNativeAbi.Http(13, "A", "t", "x"), HostNativeAbi.http("2.3.3-b4"))
        val dex = File(assets(), "host-bridges/http-2.3.3-b4.dex").readBytes()
        assertTrue(dex.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
        val strings = dex.toString(Charsets.ISO_8859_1)
        assertTrue(strings.contains("Lzw8;"))
        assertTrue(strings.contains("Leb6;"))
        assertTrue(strings.contains("Ltt0;"))
        assertFalse(strings.contains("Lvv8;"))
    }

    @Test
    fun `b4 paging event uses host function0 and native flow`() {
        val parsed = HostProfile.parse(File(assets(), "host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8))
        assertTrue(HostNativeAbi.hasNativePaging("2.3.3-b4"))
        assertEquals("yo3", parsed.classOf("kotlin.jvm.functions.Function0"))
        assertEquals("d", parsed.methodOf("kotlin.jvm.functions.Function0", "invoke"))
        assertEquals("sr1", parsed.classOf("ReaMicroHost.SingleFlow"))
        assertEquals("fz5", parsed.classOf("ReaMicroHost.LoadType"))
        assertNotNull(parsed.classOf("ReaMicroHost.PagingData"))
    }

    @Test fun `file wrappers keep their verified per-host field`() {
        val parsed = HostProfile.parse(File(assets(), "host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8))
        assertEquals("b", HostNativeAbi.fileWrapperField("2.3.3-b2"))
        assertEquals("d", HostNativeAbi.fileWrapperField("2.3.3-b4"))
        assertEquals("xh", parsed.classOf("ReaMicroHost.FileWrapper"))
        assertEquals("sp7", parsed.classOf("ReaMicroHost.PlatformFile"))
    }
}
