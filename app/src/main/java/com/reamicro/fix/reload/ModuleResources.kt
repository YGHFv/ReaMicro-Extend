package com.reamicro.fix.reload

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory
import java.util.concurrent.RejectedExecutionException

/** 每个模块 ClassLoader 各持有一份；模块独立进程不启用退场门禁。 */
object ModuleResources {
    internal val gate = ReloadGate()
    @Volatile private var injected = false
    private val handlers = WeakResourceRegistry<Handler>()
    private val executors = ArrayList<ExecutorService>()
    private val cleanups = ArrayList<() -> Unit>()
    private val blockers = ArrayList<() -> String?>()
    val accepting: Boolean get() = !injected || gate.state == ReloadGate.State.ACTIVE

    internal fun enterHook(): Boolean = !injected || gate.enter()
    internal fun leaveHook() { if (injected) gate.leave() }

    fun enable() { injected = true }

    private class GenerationHandler(looper: Looper) : Handler(looper) {
        override fun dispatchMessage(message: android.os.Message) {
            if (accepting) super.dispatchMessage(message)
        }
    }

    fun handler(looper: Looper): Handler = GenerationHandler(looper).also {
        handlers.add(it)
    }

    fun <T : ExecutorService> executor(value: T): T = value.also {
        synchronized(executors) { executors += it }
    }

    fun pool(size: Int, factory: ThreadFactory): ThreadPoolExecutor =
        pool(size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)

    fun pool(core: Int, max: Int, keepAlive: Long, unit: TimeUnit,
             queue: java.util.concurrent.BlockingQueue<Runnable>, factory: ThreadFactory): ThreadPoolExecutor =
        executor(GenerationPool(core, max, keepAlive, unit, queue, factory))

    private class GenerationPool(core: Int, max: Int, keepAlive: Long, unit: TimeUnit,
        queue: java.util.concurrent.BlockingQueue<Runnable>, factory: ThreadFactory) :
        ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory) {
        override fun execute(command: Runnable) {
            synchronized(gate) {
                if (!accepting) throw RejectedExecutionException("Module generation is retiring")
                super.execute(command)
            }
        }
    }

    fun cleanup(action: () -> Unit) { synchronized(cleanups) { cleanups += action } }
    fun blockWhile(reason: () -> String?) { synchronized(blockers) { blockers += reason } }

    fun lease(): AutoCloseable? {
        if (!injected) return AutoCloseable { }
        if (!gate.enter()) return null
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) gate.leave() }
    }

    fun thread(action: Runnable): Thread = thread(action, "ReaMicroTask")

    fun thread(action: Runnable, name: String): Thread = GenerationThread(action, name)

    fun backgroundThread(name: String, isDaemon: Boolean, block: () -> Unit): Thread =
        thread(Runnable(block), name).apply { this.isDaemon = isDaemon; start() }

    private class GenerationThread(private val action: Runnable, name: String) : Thread(name) {
        @Synchronized override fun start() {
            if (injected && !gate.enter()) throw RejectedExecutionException("Module generation is retiring")
            try { super.start() } catch (error: Throwable) {
                if (injected) gate.leave()
                throw error
            }
        }
        override fun run() {
            try { action.run() } finally { if (injected) gate.leave() }
        }
    }

    internal fun begin(): String? {
        if (!gate.begin()) return "模块任务仍在运行"
        val reason = runCatching {
            synchronized(blockers) { blockers.firstNotNullOfOrNull { it() } }
                ?: synchronized(executors) {
                    executors.firstOrNull {
                        when (it) {
                            is ScheduledThreadPoolExecutor -> it.activeCount != 0 || it.queue.isNotEmpty()
                            is ThreadPoolExecutor -> it.activeCount != 0 || it.queue.isNotEmpty()
                            else -> true
                        }
                    }?.let { "后台队列尚未清空" }
                }
        }.getOrElse { "无法确认模块空闲：${it.javaClass.simpleName}" }
        if (reason != null) gate.reject()
        return reason
    }

    internal fun reject() = gate.reject()

    internal fun retire() {
        check(gate.state == ReloadGate.State.CHECKING)
        synchronized(cleanups) { cleanups.asReversed().forEach { it() }; cleanups.clear() }
        handlers.drain().forEach { it.removeCallbacksAndMessages(null) }
        synchronized(executors) {
            executors.forEach { it.shutdown() }
            executors.forEach { check(it.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) }
            executors.clear()
        }
        clearMainCallbacks()
        gate.retire()
    }

    internal fun verifyMainQueueAccess() {
        val queue = Looper.getMainLooper().queue
        queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }.get(queue)
        android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
    }

    private fun clearMainCallbacks() {
        // runOnUiThread / View.post 使用宿主 Handler，不能只清理模块创建的 Handler。
        val queue = Looper.getMainLooper().queue
        val first = queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        val callbacks = ArrayList<Pair<Handler, Runnable>>()
        synchronized(queue) {
            var message = first.get(queue) as? android.os.Message
            while (message != null) {
                val callback = message.callback
                if (callback != null && owns(callback.javaClass.classLoader)) {
                    message.target?.let { callbacks += it to callback }
                }
                message = next.get(message) as? android.os.Message
            }
            callbacks.forEach { (handler, runnable) -> handler.removeCallbacks(runnable) }
        }
    }

    private fun owns(loader: ClassLoader?): Boolean {
        var current = loader
        while (current != null) {
            if (current === ModuleResources::class.java.classLoader) return true
            current = current.parent
        }
        return false
    }
}
