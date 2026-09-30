package com.reamicro.fix.cloud.root

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RootAccessProbeTest {
    @Test fun `virtual su is executed without checking file existence`() {
        val paths = mutableListOf<String>()
        val probe = RootAccessProbe { builder, _ ->
            paths += builder.command().first()
            RootCommandResult(0, "manager banner\nREAMICRO_UID=0")
        }
        assertTrue(probe.inspect(listOf("/virtual/su"), 60).granted)
        assertEquals(listOf("/virtual/su"), paths)
    }
    @Test fun `missing PATH binary falls back to absolute path`() {
        var calls = 0
        val probe = RootAccessProbe { _, _ ->
            if (calls++ == 0) throw IOException("error=2, No such file")
            RootCommandResult(0, "REAMICRO_UID=0")
        }
        assertEquals("/system/bin/su", probe.inspect(listOf("su", "/system/bin/su"), 60).executable)
        assertEquals(2, calls)
    }
    @Test fun `denial does not spam every alternative path`() {
        var calls = 0
        val probe = RootAccessProbe { _, _ -> calls++; RootCommandResult(1, "", "Permission denied") }
        assertEquals(RootAccessState.DENIED, probe.inspect(listOf("su", "/system/bin/su"), 60).state)
        assertEquals(1, calls)
    }
    @Test fun `timeout is unknown not denial and is not retried`() {
        var calls = 0
        val probe = RootAccessProbe { _, _ -> calls++; throw RootCommandTimeout(RootCommandResult(-1, "")) }
        assertEquals(RootAccessState.TIMEOUT, probe.inspect(listOf("su", "/system/bin/su"), 60).state)
        assertEquals(1, calls)
    }
    @Test fun `exit zero or stderr uid alone is not proof of root`() {
        assertFalse(RootAccessProbe { _, _ -> RootCommandResult(0, "", "REAMICRO_UID=0") }
            .inspect(listOf("su"), 60).granted)
        assertEquals(RootAccessState.NOT_ROOT,
            RootAccessProbe { _, _ -> RootCommandResult(0, "REAMICRO_UID=2000") }
                .inspect(listOf("su"), 60).state)
    }
    @Test fun `all unavailable paths report launch errors`() {
        val report = RootAccessProbe { _, _ -> throw IOException("not found") }.inspect(listOf("su"), 60)
        assertEquals(RootAccessState.UNAVAILABLE, report.state)
        assertTrue(report.detail.contains("not found"))
    }
}
