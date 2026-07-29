package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OnlineSourceDownloadPolicyTest {
    @Test
    fun blankValuesUseSourceDefaultsAndUnlimitedDailyQuota() {
        val policy = OnlineSourceDownloadPolicyStore.parse("", "")

        assertNull(policy.requestsPerSecond)
        assertNull(policy.dailyChapterLimit)
    }

    @Test
    fun zeroAlsoClearsOptionalOverrides() {
        val policy = OnlineSourceDownloadPolicyStore.parse("0", "0")

        assertNull(policy.requestsPerSecond)
        assertNull(policy.dailyChapterLimit)
    }

    @Test
    fun configuredValuesAreParsed() {
        val policy = OnlineSourceDownloadPolicyStore.parse("8", "120")

        assertEquals(8, policy.requestsPerSecond)
        assertEquals(120, policy.dailyChapterLimit)
    }

    @Test
    fun requestRateOverridesBuiltInConcurrentRate() {
        val source = source().copy(configuredRequestsPerSecond = 6)

        assertEquals("6/1000", OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(source))
        assertEquals("10/3000", OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(source.copy(configuredRequestsPerSecond = null)))
    }

    @Test
    fun downloadModeAndParagraphCommentsDefaultToDisabled() {
        val policy = OnlineSourceDownloadPolicyStore.parse("", "")

        assertEquals(false, policy.preferOnDemandLoading)
        assertEquals(false, policy.paragraphCommentsEnabled)
    }

    @Test
    fun paragraphCommentCapabilityIsDetectedFromSourceScripts() {
        assertEquals(false, source().supportsParagraphComments)
        assertEquals(
            true,
            source().copy(loginUi = """[{"name":"段评开关","type":"button"}]""").supportsParagraphComments,
        )
        assertEquals(
            true,
            source().copy(jsLib = "function load(){ return 'idea_counts'; }").supportsParagraphComments,
        )
    }

    @Test
    fun invalidValuesAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            OnlineSourceDownloadPolicyStore.parse("abc", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineSourceDownloadPolicyStore.parse("", "-1")
        }
    }

    private fun source() = OnlineSourceEntry(
        id = "source-id",
        name = "测试源",
        fileName = "source.rmonline",
        sourceUrl = "https://example.com",
        loginUrl = "",
        loginUi = "",
        loginCheckJs = "",
        concurrentRate = "10/3000",
        header = "",
        enabledCookieJar = true,
        searchUrl = "",
        ruleSearch = "",
        ruleBookInfo = "",
        ruleToc = "",
        ruleContent = "",
        respondTime = 180_000,
        origin = "",
    )
}
