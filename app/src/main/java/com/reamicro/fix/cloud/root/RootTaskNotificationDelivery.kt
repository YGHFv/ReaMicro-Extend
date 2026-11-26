package com.reamicro.fix.cloud.root

import android.os.SystemClock

/** 一批投递共享等待预算；只有接收器确认才允许清除待发记录。 */
internal class RootTaskNotificationDelivery(
    private var remainingMs: Long,
    private val commandSeconds: Long,
    private val cancelled: () -> Boolean = { false },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val execute: (List<String>, Long) -> RootCommandResult = { args, timeout ->
        RootCommandRunner.executeTransient(ProcessBuilder(args), timeoutSeconds = timeout)
    },
) {
    val hasBudget: Boolean get() = remainingMs >= 1_000L

    init { require(remainingMs > 0L && commandSeconds > 0L) }

    fun deliver(message: RootTaskNotificationBroadcast): Boolean {
        checkCancelled()
        if (!hasBudget) return false
        val broadcast = try {
            command(rootTaskNotificationBroadcastArgs(message))
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            null
        }
        checkCancelled()
        if (broadcast != null && broadcast.exitCode == 0 && !broadcast.truncated &&
            rootTaskBroadcastConfirmed(broadcast.output)) return true
        if (!hasBudget) return false
        val activity = command(rootTaskNotificationActivityArgs(message))
        check(activity.exitCode == 0) { "Module notification wake failed: exit=${activity.exitCode}" }
        // Activity 启动回执不能证明已获得通知权限；保留队列，稍后凭接收器或同步回执确认。
        return false
    }

    private fun command(args: List<String>): RootCommandResult {
        checkCancelled()
        val started = clock()
        return try {
            execute(args, minOf(commandSeconds, remainingMs / 1_000L)).also { checkCancelled() }
        } finally {
            remainingMs = (remainingMs - (clock() - started).coerceAtLeast(0L)).coerceAtLeast(0L)
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedException("Root 通知投递已取消")
    }
}
