package com.reamicro.fix.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HookInstallReportTest {

    @Before
    fun setUp() {
        HookInstallReport.reset()
    }

    @After
    fun tearDown() {
        HookInstallReport.reset()
    }

    @Test
    fun `记录成功的安装动作`() {
        var ran = false
        val ok = HookInstallReport.install("Demo", "alpha") { ran = true }

        assertTrue(ran)
        assertTrue(ok)
        assertEquals("hook installed 1/1", HookInstallReport.summaryLine())
        assertTrue(HookInstallReport.failures().isEmpty())
    }

    @Test
    fun `失败的安装动作不外抛异常并被记入汇总`() {
        val ok = HookInstallReport.install("Demo", "beta") { error("boom") }

        assertFalse(ok)
        assertEquals(listOf("Demo.beta"), HookInstallReport.failures())
        assertEquals("hook installed 0/1, failed: Demo.beta", HookInstallReport.summaryLine())
        assertEquals(listOf("Demo.beta <- IllegalStateException: boom"), HookInstallReport.failureDetails())
    }

    @Test
    fun `单个动作失败不会中断后续动作`() {
        val executed = mutableListOf<String>()
        HookInstallReport.installAll(
            "Demo",
            listOf(
                "one" to { executed += "one" },
                "two" to { executed += "two"; error("boom") },
                "three" to { executed += "three" },
            ),
        )

        assertEquals(listOf("one", "two", "three"), executed)
        assertEquals("hook installed 2/3, failed: Demo.two", HookInstallReport.summaryLine())
    }

    @Test
    fun `同名动作重复登记时以最后一次为准`() {
        HookInstallReport.record("Demo", "alpha", ok = false, error = IllegalStateException("first"))
        HookInstallReport.record("Demo", "alpha", ok = true)

        assertEquals(1, HookInstallReport.snapshot().size)
        assertEquals("hook installed 1/1", HookInstallReport.summaryLine())
    }

    @Test
    fun `按 feature 汇总`() {
        HookInstallReport.install("A", "one") {}
        HookInstallReport.install("A", "two") { error("boom") }
        HookInstallReport.install("B", "one") {}

        assertEquals(listOf("A 1/2", "B 1/1"), HookInstallReport.featureSummaries())
    }
}
