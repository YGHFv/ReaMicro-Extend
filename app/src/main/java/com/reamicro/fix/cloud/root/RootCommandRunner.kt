package com.reamicro.fix.cloud.root

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal data class RootCommandResult(
    val exitCode: Int,
    val output: String,
    val stderr: String = "",
    val truncated: Boolean = false,
) {
    val diagnosticOutput: String get() = listOf(output, stderr).filter(String::isNotBlank).joinToString("\n")
}
internal class RootCommandTimeout(val partial: RootCommandResult) :
    IllegalStateException("Root 命令超时，执行结果未知；不自动重试，请检测与同步后再操作")

internal object RootCommandRunner {
    @Volatile private var selectedSu: String? = null
    @Volatile private var customSu: String? = null

    @Synchronized
    fun setCustomSuPath(value: String) {
        val path = value.trim().takeIf(String::isNotEmpty)
        require(path == null || (path.startsWith("/") && path.none { it == '\n' || it == '\r' || it == '\u0000' })) {
            "SU 路径必须是绝对路径，不能包含换行或 NUL；留空使用默认 SU"
        }
        if (customSu != path) {
            customSu = path
            selectedSu = null
        }
    }
    fun selectedExecutable(): String = selectedSu ?: "su"

    @Synchronized
    fun inspectAccess(timeoutSeconds: Long = 60): RootAccessReport {
        val candidates = customSu?.let(::listOf) ?: (listOfNotNull(selectedSu) + RootAccessProbe.DEFAULT_PATHS)
        val report = RootAccessProbe { builder, timeout -> execute(builder, timeoutSeconds = timeout) }
            .inspect(candidates, timeoutSeconds)
        selectedSu = report.executable.takeIf { report.granted }
        return report
    }

    fun run(command: String, input: String? = null, timeoutSeconds: Long = 20): RootCommandResult {
        val executable = selectedSu ?: inspectAccess().let {
            if (!it.granted) throw RootAccessException(it)
            it.executable
        }
        return runAuthorized(executable, command, input, timeoutSeconds)
    }

    fun runAuthorized(executable: String, command: String, input: String? = null, timeoutSeconds: Long = 20): RootCommandResult {
        val guarded = """[ "${'$'}(/system/bin/id -u 2>/dev/null || id -u)" = 0 ] || exit 77
printf '$RECEIPT\n'
$command"""
        val result = try {
            execute(ProcessBuilder(executable, "-c", guarded), input, timeoutSeconds)
        } catch (error: Exception) {
            selectedSu = null
            throw error
        }
        val lines = result.output.lines()
        val receipt = lines.indexOf(RECEIPT)
        if (receipt < 0) {
            selectedSu = null
            val denied = result.exitCode == 77 ||
                result.diagnosticOutput.contains("denied", ignoreCase = true)
            throw RootAccessException(RootAccessReport(
                if (denied) RootAccessState.DENIED else RootAccessState.ERROR, executable,
                "SU 未返回 UID 0 确认；exit=${result.exitCode}"))
        }
        return result.copy(output = lines.drop(receipt + 1).joinToString("\n").trim())
    }

    fun execute(builder: ProcessBuilder, input: String? = null, timeoutSeconds: Long = 20): RootCommandResult {
        require(timeoutSeconds > 0)
        val process = builder.redirectErrorStream(false).start()
        val stdout = Capture(MAX_OUTPUT_BYTES)
        val stderr = Capture(256 * 1024)
        val writerError = AtomicReference<Throwable?>()
        val reader = stdout.drain(process.inputStream, "ReaMicroRootStdout")
        val errorReader = stderr.drain(process.errorStream, "ReaMicroRootStderr")
        val writer = thread(name = "ReaMicroRootStdin", isDaemon = true) {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input.orEmpty()) }
            } catch (error: Exception) { writerError.set(error) }
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw RootCommandTimeout(RootCommandResult(-1, stdout.text(), stderr.text(), stdout.truncated || stderr.truncated))
            }
            reader.join(1000)
            errorReader.join(1000)
            writer.join(1000)
            check(!reader.isAlive && !errorReader.isAlive) { "Root 输出管道未关闭，执行结果未知；不会重试" }
            if (!input.isNullOrEmpty() && process.exitValue() == 0) {
                check(!writer.isAlive && writerError.get() == null) { "Root 配置输入未完整写入，执行结果未知" }
            }
            check(stdout.error == null && stderr.error == null) { "Root 输出读取失败，执行结果未知" }
            return RootCommandResult(process.exitValue(), stdout.text(), stderr.text(), stdout.truncated || stderr.truncated)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private class Capture(private val limit: Int) {
        private val buffer = ByteArrayOutputStream()
        @Volatile var truncated = false
        @Volatile var error: Exception? = null
        fun text(): String = synchronized(buffer) { buffer.toString("UTF-8").trim() }
        fun drain(stream: InputStream, name: String): Thread = thread(name = name, isDaemon = true) {
            try {
                stream.use {
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = it.read(bytes)
                        if (count < 0) break
                        synchronized(buffer) {
                            val accepted = minOf(count, (limit - buffer.size()).coerceAtLeast(0))
                            buffer.write(bytes, 0, accepted)
                            if (accepted < count) truncated = true
                        }
                    }
                }
            } catch (failure: Exception) { error = failure }
        }
    }
    private const val RECEIPT = "REAMICRO_ROOT_UID_0"
    private const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
}
