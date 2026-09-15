package com.reamicro.fix.cloud.ksu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootCommandRunnerTest {
    private fun process(mode: String): ProcessBuilder {
        val executable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = File(requireNotNull(RootCommandProbe::class.java.protectionDomain).codeSource.location.toURI()).absolutePath
        return ProcessBuilder(executable, "-cp", classpath, RootCommandProbe::class.java.name, mode)
    }

    @Test(timeout = 10_000)
    fun `UTF-8 configuration goes through stdin rather than shell arguments`() {
        val payload = "任务执行 · KSU"
        val result = RootCommandRunner.execute(process("echo"), payload, timeoutSeconds = 5)
        assertEquals(0, result.exitCode)
        assertEquals(payload, result.output)
    }

    @Test(timeout = 10_000)
    fun `full pipes do not deadlock input and output`() {
        val payload = "y".repeat(1024 * 1024)
        val result = RootCommandRunner.execute(process("duplex"), payload, timeoutSeconds = 5)
        assertEquals(0, result.exitCode)
        assertEquals("x".repeat(1024 * 1024) + payload, result.output)
    }

    @Test(timeout = 10_000)
    fun `timeout is bounded even when child produces no output`() {
        val started = System.nanoTime()
        val result = runCatching { RootCommandRunner.execute(process("sleep"), timeoutSeconds = 1) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("结果未知"))
        assertTrue((System.nanoTime() - started) / 1_000_000L < 5000L)
    }
}
