package com.reamicro.fix.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 一条模块日志。 */
data class ModuleLogEntry(
    val at: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * 模块进程的诊断日志缓冲。
 *
 * 为什么需要它：模块进程的 INFO 日志受「简洁日志」开关（[ModuleLogState.conciseLogEnabled]，
 * 默认开）抑制，`logcat` 里压根看不到；而这个进程常年没有界面，出问题时无从查起。这里把每条
 * 日志先收进内存环形缓冲，模块主界面可以直接翻看，需要时还能落到文件里带走。
 *
 * 记录发生在**过滤之前**——被 logcat 吞掉的那些恰恰是排查时最想看的。
 * 落盘走单线程后台队列，不阻塞调用方（接收器、闹钟唤醒路径都不该因为写日志而变慢）。
 */
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

    /**
     * 绑定落盘位置。由模块进程里有 Context 的组件（接收器、主界面）调用即可，
     * 没绑定之前日志只留在内存里——不影响查看，只是重启后会丢。
     */
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

    /** 内存里的日志，最新在前。 */
    fun snapshot(): List<ModuleLogEntry> = synchronized(lock) { entries.toList() }.asReversed()

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
        val target = logFile ?: return
        runCatching { io.execute { runCatching { target.delete() } } }
    }

    /** 日志文件路径（未 attach 或不可写时为 null），供界面显示/分享。 */
    fun filePath(): String? = logFile?.takeIf { it.exists() }?.absolutePath

    private fun appendLine(target: File, line: String) {
        runCatching {
            // 先裁剪再追加：日志文件长期只增不减会把用户存储吃满。
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
