package com.reamicro.fix.online.search

import java.util.concurrent.atomic.AtomicLong

/** 每个源从实际开始执行时计时；排队和请求分别有截止时间，诊断不包含关键词或请求参数。 */
internal class OnlineSearchTrace(
    val id: Long,
    private val requestBudgetMs: Long,
    private val queueBudgetMs: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val submittedAt = clock()
    private var startedAt: Long? = null
    private var worker: Thread? = null
    private var stageAt = submittedAt
    private var stage = "queued"
    private val timings = linkedMapOf<String, Long>()
    private var terminal = false
    private var parsed = false
    private var slowLogged = false

    @Synchronized fun start(): Boolean {
        if (terminal) return false
        startedAt = clock()
        worker = Thread.currentThread()
        stage("source-start")
        return true
    }

    @Synchronized fun stage(value: String) {
        if (terminal) return
        val now = clock()
        timings[stage] = (timings[stage] ?: 0L) + now - stageAt
        stage = value
        stageAt = now
    }

    @Synchronized fun parsed(action: () -> Unit = {}) {
        if (terminal) return
        parsed = true
        action()
    }
    @Synchronized fun hasParsed(): Boolean = parsed
    @Synchronized fun hasStarted(): Boolean = startedAt != null

    @Synchronized fun takeSlowSnapshot(): Boolean {
        val start = startedAt ?: return false
        if (terminal || slowLogged || clock() - start < 8_000_000_000L) return false
        slowLogged = true
        return true
    }

    @Synchronized fun remainingMs(): Long {
        if (terminal) return Long.MAX_VALUE
        val start = startedAt ?: submittedAt
        val budget = if (startedAt == null) queueBudgetMs else requestBudgetMs
        return budget - (clock() - start) / 1_000_000
    }

    @Synchronized fun finish(): Boolean {
        if (terminal) return false
        stage(stage)
        terminal = true
        return true
    }

    @Synchronized fun diagnostic(includeStack: Boolean = false): String {
        val now = clock()
        val values = timings.toMutableMap()
        if (!terminal) values[stage] = (values[stage] ?: 0L) + now - stageAt
        val stack = if (includeStack) worker?.stackTrace?.take(12)?.joinToString(
            prefix = " stack=", separator = ">",
        ) { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty() else ""
        return "id=$id elapsedMs=${(now - submittedAt) / 1_000_000} " +
            "budgetMs=$requestBudgetMs phase=$stage parsed=$parsed " +
            values.entries.joinToString(prefix = "timings=", separator = ",") { "${it.key}:${it.value / 1_000_000}" } + stack
    }

    fun <T> run(action: () -> T): T {
        val previous = local.get()
        local.set(this)
        return try { action() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    companion object {
        private val sequence = AtomicLong()
        internal val local = ThreadLocal<OnlineSearchTrace?>()
        fun nextId(): Long = sequence.incrementAndGet()
        fun current(): OnlineSearchTrace? = local.get()
    }
}

internal fun onlineSearchRequestBudgetMs(enhanced: Boolean, respondTime: Int, concurrentRate: String): Long {
    val interval = concurrentRate.substringAfter('/', concurrentRate).toLongOrNull()
        ?.coerceIn(0L, 60_000L) ?: 0L
    // 增强 HTTP 的单次调用允许 60 秒；旧解析器另有连接时间，解析与限速也要留在外层预算中。
    val network = if (enhanced) 60_000L else {
        val read = respondTime.toLong().coerceIn(15_000L, 300_000L)
        read + read.coerceAtMost(30_000L)
    }
    return network + interval + 15_000L
}
