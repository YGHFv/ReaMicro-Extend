package com.reamicro.fix.online.download

/** One overall percentage for the host row and notification, with a single terminal event. */
internal class OnlineProgressState(private val minimumIntervalMs: Long = 1_000L) {
    data class Update(val progress: Int, val message: String, val finished: Boolean, val success: Boolean) {
        val status: String get() = when {
            !finished -> "Running"
            success -> "Success"
            message.contains("取消") || message.contains("暂停") -> "Cancelled"
            else -> "Error"
        }
    }

    private var finished = false
    private var maximumProgress = 0
    private var lastSentAt: Long? = null

    @Synchronized
    fun running(progress: Int, message: String, now: Long, force: Boolean = false): Update? {
        if (finished) return null
        // Chapter counts describe a phase. 100 belongs to the completed import/update.
        maximumProgress = maxOf(maximumProgress, progress.coerceIn(0, 99))
        val last = lastSentAt
        if (!force && last != null && now >= last && now - last < minimumIntervalMs) return null
        lastSentAt = now
        return Update(maximumProgress, compact(message), false, false)
    }

    @Synchronized
    fun finish(message: String, success: Boolean): Update? {
        if (finished) return null
        finished = true
        return Update(if (success) 100 else maximumProgress, compact(message), true, success)
    }

    private fun compact(message: String) = message.replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
