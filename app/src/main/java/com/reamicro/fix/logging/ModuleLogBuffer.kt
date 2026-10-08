package com.reamicro.fix.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ModuleLogEntry(
    val at: Long,
    val level: String,
    val tag: String,
    val message: String,
)

object ModuleLogBuffer {
    private const val MAX_MEMORY_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val FILE_NAME = "module-log.txt"

    private val lock = Any()
    private val entries = ArrayDeque<ModuleLogEntry>()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReaMicroLogWriter").apply { isDaemon = true }
    }

    @Volatile private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun attach(context: Context) {
        if (logFile != null) return
        synchronized(lock) {
            if (logFile == null) {
                logFile = runCatching { File(context.applicationContext.filesDir, FILE_NAME) }.getOrNull()
            }
        }
    }

    fun record(level: String, tag: String, message: String) {
        val entry = ModuleLogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_MEMORY_ENTRIES) entries.removeFirst()
        }
        val target = logFile ?: return
        val line = "${timeFormat.format(Date(entry.at))} $level/$tag: $message"
        runCatching { io.execute { appendLine(target, line) } }
    }

    fun snapshot(): List<ModuleLogEntry> = synchronized(lock) { entries.toList() }.asReversed()

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
        val target = logFile ?: return
        runCatching { io.execute { runCatching { target.delete() } } }
    }

    fun filePath(): String? = logFile?.takeIf { it.exists() }?.absolutePath

    private fun appendLine(target: File, line: String) {
        runCatching {

            if (target.isFile && target.length() > MAX_FILE_BYTES) {
                val kept = target.readLines().takeLast(MAX_MEMORY_ENTRIES)
                target.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            target.appendText(line + "\n")
        }.onFailure {
            Log.w(LOG_TAG, "module log write failed: ${it.message}")
        }
    }

    private const val LOG_TAG = "ReaMicroLog"
}
