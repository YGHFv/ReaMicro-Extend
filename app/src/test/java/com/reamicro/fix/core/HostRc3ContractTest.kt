package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 固定本轮真实参数变化，不将成功计数当作未知版本的 ABI 保证。 */
class HostRc3ContractTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val profile = HostProfile.parse(main.resolve("assets/host-profiles/2.3.3-rc3.profile").readText())

    @Test fun relocatedOwnersAreNotForcedBackToTheirOldFacade() {
        assertEquals("r90", profile.methodOwners["androidx.compose.foundation.text.BasicTextKt#BasicText-4YKlhWE"])
        assertEquals("bld", profile.methodOwners["app.zhendong.reamicro.ui.reader.components.SwipePagerKt#SwipePagerEffects"])
        assertEquals("am6", profile.classOf("androidx.compose.ui.text.MultiParagraphIntrinsics"))
        assertEquals("kh1", profile.classOf("ReaMicroHost.HttpRequestBuilder"))
    }

    @Test fun httpBranchAndMethodsAreRc3Specific() {
        val http = requireNotNull(HostNativeAbi.http("2.3.3-rc3"))
        assertEquals(12.toByte(), http.builderBranch)
        assertEquals("x", http.url)
        assertEquals("n", http.header)
        assertEquals("t", http.method)
        assertNull(HostNativeAbi.http("2.3.4"))
    }

    @Test fun callbacksRemainLogicalAndScanRulesAreBundled() {
        assertEquals("nb", profile.classOf("ReaMicroHost.CommunityRowClick"))
        assertEquals("mh8", profile.classOf("ReaMicroHost.ReaderMoreSettingsContent"))
        assertTrue(main.resolve("assets/host-profiles/automatic.rules.json").length() > 1000)
        assertTrue(main.resolve("assets/host-bridges/http-2.3.3-rc3.dex").isFile)
        assertTrue(main.resolve("assets/host-bridges/2.3.3-rc3.dex").isFile)
        val paging = main.resolve("java/com/reamicro/fix/core/B2PagingData.kt").readText()
        assertTrue(paging.contains("HostSymbols.profileId == \"2.3.3-rc3\") 6 else 5"))
    }
}
