package com.reamicro.fix.reload

import java.util.concurrent.CancellationException

/** 将一次查询的子任务和阻塞 IO 绑定到同一个取消信号。 */
internal class TaskCancellation private constructor(
    private val parent: TaskCancellation?, private val checkActive: (() -> Boolean)? = null,
) {
    constructor() : this(null)
    constructor(checkActive: () -> Boolean) : this(null, checkActive)

    private var cancelled = false
    private val actions = linkedSetOf<() -> Unit>()

    val isCancelled: Boolean
        get() = synchronized(this) { cancelled } || parent?.isCancelled == true

    fun check() {
        if (checkActive?.invoke() == false) cancel()
        if (isCancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Task cancelled")
        }
    }

    fun register(action: () -> Unit): AutoCloseable {
        val runNow = synchronized(this) {
            if (cancelled) true else {
                actions += action
                false
            }
        }
        if (runNow) runCatching(action)
        else if (parent?.isCancelled == true) cancel()
        return AutoCloseable { synchronized(this) { actions.remove(action) } }
    }

    fun cancel() {
        val pending = synchronized(this) {
            if (cancelled) return
            cancelled = true
            actions.toList().also { actions.clear() }
        }
        pending.forEach { runCatching(it) }
    }

    fun <T> run(block: () -> T): T {
        val previous = local.get()
        local.set(this)
        return try {
            check()
            block()
        } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    fun child(): TaskCancellation {
        val child = TaskCancellation(this)
        val registration = register(child::cancel)
        child.register { registration.close() }
        return child
    }

    companion object {
        private val local = ThreadLocal<TaskCancellation>()
        fun current(): TaskCancellation? = local.get()
        fun checkCurrent() {
            current()?.check()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Task interrupted")
        }

        fun rethrowIfCancelled(error: Throwable) {
            checkCurrent()
            var cause: Throwable? = error
            repeat(16) {
                when (val current = cause) {
                    null -> return
                    is CancellationException -> throw current
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        throw current
                    }
                    else -> cause = current.cause.takeUnless { it === current }
                }
            }
        }
    }
}
