package com.reamicro.fix.reload

import java.util.concurrent.ConcurrentLinkedQueue

/** 框架重载线程持有模块锁；主线程只排队卸载，返回后由持锁线程执行。 */
internal class ReloadHookRelease {
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    fun release(defer: Boolean, action: () -> Unit) {
        if (defer) pending.add(action) else action()
    }

    fun drain() {
        var failure: Throwable? = null
        while (true) {
            val action = pending.poll() ?: break
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
