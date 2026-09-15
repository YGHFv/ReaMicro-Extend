package com.reamicro.fix.cloud.ksu

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class RootCommandResult(val exitCode: Int, val output: String)

internal object RootCommandRunner {
    fun run(command: String, input: String? = null, timeoutSeconds: Long = 20): RootCommandResult =
        execute(ProcessBuilder("su", "-c", command), input, timeoutSeconds)

    fun execute(builder: ProcessBuilder, input: String? = null, timeoutSeconds: Long = 20): RootCommandResult {
        val process = builder.redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val reader = thread(name = "ReaMicroRootOutput", isDaemon = true) {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val accepted = minOf(count, (MAX_OUTPUT_BYTES - output.size()).coerceAtLeast(0))
                            if (accepted > 0) output.write(buffer, 0, accepted)
                        }
                    }
                }
            }
        }
        val writer = thread(name = "ReaMicroRootInput", isDaemon = true) {
            runCatching { process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input.orEmpty()) } }
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) error("root 命令超时，执行结果未知，请同步状态后重试")
            reader.join(1000)
            writer.join(1000)
            return RootCommandResult(process.exitValue(), synchronized(output) { output.toString("UTF-8").trim() })
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
}
