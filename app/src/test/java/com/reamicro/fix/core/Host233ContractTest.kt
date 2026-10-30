package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Host233ContractTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val profile = HostProfile.parse(main.resolve("assets/host-profiles/2.3.3.profile").readText(Charsets.UTF_8))

    @Test
    fun releaseHasIndependentSymbolsAndAssets() {
        assertEquals("2.3.3", profile.fingerprint.id)
        assertEquals("yrb", profile.classOf("app.zhendong.reamicro.ui.reader.UiStatus"))
        assertEquals("vrb", profile.classOf("app.zhendong.reamicro.ui.reader.UiStatus\$Reader"))
        assertEquals("my1", profile.classOf("kotlin.coroutines.Continuation"))
        assertEquals("usa", profile.classOf("kotlin.coroutines.jvm.internal.SuspendLambda"))
        assertEquals("r90", profile.methodOwners["androidx.compose.foundation.text.BasicTextKt#BasicText-4YKlhWE"])
        assertEquals("o8e", profile.methodOwners["app.zhendong.reamicro.ui.reader.components.ScrollPagerKt#ScrollPager"])
        assertEquals("cld", profile.methodOwners["app.zhendong.reamicro.ui.reader.components.EpubContainerKt#EpubContainer"])
        assertEquals("grd", profile.methodOwners["org.epub.utils.NodeExtKt#getAllSteps"])
        assertEquals("ivd", profile.methodOwners["androidx.compose.foundation.layout.FlowLayoutKt#FlowRow"])
        assertEquals("kp2", profile.methodOwners["androidx.compose.ui.node.DrawModifierNodeKt#invalidateDraw"])
        assertEquals("kj0", profile.typeAliases["ReaMicroHost.BookCoverSheetContent"])
        assertEquals("lj0", profile.typeAliases["ReaMicroHost.BookBannerSheetContent"])
        assertEquals("b", profile.fields["ReaMicroHost.BookCoverSheetContent#pick"])
        assertEquals("e", profile.fields["ReaMicroHost.BookBannerSheetContent#pick"])
        assertTrue(main.resolve("assets/host-profiles/2.3.3.shapes").isFile)
        assertTrue(main.resolve("assets/host-profiles/2.3.3.abi.json").isFile)
        assertTrue(main.resolve("assets/host-bridges/2.3.3.dex").isFile)
        assertTrue(main.resolve("assets/host-bridges/http-2.3.3.dex").isFile)
    }

    @Test
    fun sourceIndexUsesRelocatedStaticOwners() {
        val source = main.resolve("java/com/reamicro/fix/hook/HostSearchIndexBuilder.kt").readText(Charsets.UTF_8)
        assertTrue(source.contains("loader.hostStaticMethod(\"org.epub.utils.NodeExtKt\", \"getAllSteps\""))
        assertTrue(source.contains("loader.hostStaticMethod(\"org.epub.utils.NodeExtKt\", \"getSteps\""))
        assertFalse(source.contains("ext.hostMethod(\"getAllSteps\""))
    }

    @Test
    fun releaseProtocolsDoNotGrantUnknownHostsAnAbi() {
        assertEquals(HostNativeAbi.Navigation("b", "a", "a", "c"), HostNativeAbi.navigation("2.3.3"))
        assertEquals(HostNativeAbi.Http(12, "x", "n", "t"), HostNativeAbi.http("2.3.3"))
        assertEquals("b", HostNativeAbi.fileWrapperField("2.3.3"))
        assertNull(HostNativeAbi.navigation("2.3.4"))
        assertNull(HostNativeAbi.http("automatic"))
        assertFalse(HostNativeUiAbi.supports("automatic"))
    }
}
