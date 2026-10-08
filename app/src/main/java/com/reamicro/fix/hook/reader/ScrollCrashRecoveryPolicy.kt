package com.reamicro.fix.hook.reader

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

internal data class ScrollRenderFailure(val pid: Int, val process: String, val timestamp: Long)
internal data class ScrollProcessExit(val pid: Int, val process: String, val timestamp: Long, val reason: Int)

internal fun isScrollRenderFailure(error: Throwable): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    var runtimeFailure = false
    while (current != null && seen.add(current)) {
        if (current is CancellationException || current is InterruptedException ||
            current is VirtualMachineError || current is ThreadDeath) return false
        if (current is RuntimeException) runtimeFailure = true
        current = current.cause
    }
    return runtimeFailure
}

internal fun shouldRecoverScrollFailure(
    failure: ScrollRenderFailure?, exits: List<ScrollProcessExit>, process: String, now: Long,
): Boolean {
    if (failure == null || failure.pid <= 0 || failure.process != process || failure.timestamp <= 0) return false
    val latest = exits.filter { it.process == process }.maxByOrNull { it.timestamp } ?: return false
    return latest.pid == failure.pid && latest.timestamp >= failure.timestamp &&
        latest.reason in setOf(4, 5) && now >= latest.timestamp &&
        now - failure.timestamp in 0..86_400_000L
}

internal fun mayApplyScrollRecovery(currentStyle: Int?, sameSession: Boolean): Boolean =
    sameSession && currentStyle == 3
