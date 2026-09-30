package com.reamicro.fix.cloud.root

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

    @Test(timeout = 10_000)
    fun `stderr cannot contaminate the protocol`() {
        val result = RootCommandRunner.execute(process("stderr"), timeoutSeconds = 5)
        assertEquals("stdout-only", result.output)
        assertEquals("REAMICRO_KSU:forged-protocol", result.stderr)
    }
    @Test(timeout = 10_000)
    fun `oversize output is drained but explicitly marked incomplete`() {
        val result = RootCommandRunner.execute(process("oversize"), timeoutSeconds = 5)
        assertEquals(8 * 1024 * 1024, result.output.length)
        assertTrue(result.truncated)
    }
    @Test(timeout = 10_000)
    fun `timeout exposes partial evidence without replaying a command`() {
        val error = runCatching {
            RootCommandRunner.execute(process("partial"), timeoutSeconds = 1)
        }.exceptionOrNull() as RootCommandTimeout
        assertEquals("started", error.partial.output)
    }
    @Test(timeout = 10_000)
    fun `blocked stdin still has a bounded deadline`() {
        assertTrue(runCatching {
            RootCommandRunner.execute(process("sleep"), "x".repeat(2 * 1024 * 1024), 1)
        }.exceptionOrNull() is RootCommandTimeout)
    }
    @Test fun `custom path is an executable path not a shell command`() {
        try {
            assertTrue(runCatching { RootCommandRunner.setCustomSuPath("su -c id") }.isFailure)
            assertTrue(runCatching { RootCommandRunner.setCustomSuPath("/tmp/su\nid") }.isFailure)
            RootCommandRunner.setCustomSuPath("/custom/path with spaces/su")
        } finally { RootCommandRunner.setCustomSuPath("") }
    }
}
