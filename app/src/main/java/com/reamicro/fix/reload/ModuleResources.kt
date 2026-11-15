package com.reamicro.fix.reload

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch

/** 每个模块 ClassLoader 各持有一份；模块独立进程不启用退场门禁。 */
object ModuleResources {
    private const val CHECK_RETRY_MILLIS = 16L
    internal val gate = ReloadGate()
    @Volatile private var injected = false
    private val handlers = WeakResourceRegistry<Handler>()
    private val threads = WeakResourceRegistry<Thread>()
    private val executors = ArrayList<ExecutorService>()
    private val cleanups = ArrayList<() -> Unit>()
    private val blockers = ArrayList<() -> String?>()
    val accepting: Boolean get() = !injected || gate.state == ReloadGate.State.ACTIVE

    internal fun enterHook(): Boolean = !injected || gate.enter()
    internal fun leaveHook() { if (injected) gate.leave() }

    fun enable() { synchronized(gate) { injected = true } }

    /** Installation must finish before retirement can seal the old hook inventory. */
    internal fun <T> withRegistration(action: () -> T): T {
        val lease = lease() ?: throw RejectedExecutionException("Module generation is retiring")
        return lease.use { action() }
    }

    private class GenerationHandler(looper: Looper) : Handler(looper) {
        override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean = synchronized(gate) {
            if (injected && gate.state == ReloadGate.State.RETIRED) {
                message.recycle()
                false
            } else {
                super.sendMessageAtTime(message, uptimeMillis)
            }
        }

        override fun dispatchMessage(message: Message) {
            if (!injected) {
                super.dispatchMessage(message)
                return
            }
            val entered = synchronized(gate) {
                when (gate.state) {
                    ReloadGate.State.ACTIVE -> gate.enter()
                    ReloadGate.State.CHECKING -> {
                        // 留在标准队列里，removeCallbacks/removeMessages 仍能取消任务。
                        sendMessageAtTime(Message.obtain(message), SystemClock.uptimeMillis() + CHECK_RETRY_MILLIS)
                        false
                    }
                    ReloadGate.State.RETIRED -> false
                }
            }
            if (!entered) return
            try {
                super.dispatchMessage(message)
            } finally {
                gate.leave()
            }
        }
    }

    fun handler(looper: Looper): Handler = synchronized(gate) {
        if (injected && gate.state == ReloadGate.State.RETIRED) {
            throw RejectedExecutionException("Module generation is retired")
        }
        GenerationHandler(looper).also(handlers::add)
    }

    fun <T : ExecutorService> executor(value: T): T {
        val registered = synchronized(gate) {
            if (!accepting) false else {
                if (value is ThreadPoolExecutor) {
                    val factory = value.threadFactory
                    value.threadFactory = ThreadFactory { task ->
                        factory.newThread(task)?.also(threads::add)
                    }
                }
                synchronized(executors) { executors += value }
                true
            }
        }
        if (!registered) {
            val rejection = RejectedExecutionException("Module generation is retiring")
            try { value.shutdownNow() } catch (error: Throwable) { rejection.addSuppressed(error) }
            throw rejection
        }
        return value
    }

    fun pool(size: Int, factory: ThreadFactory): ThreadPoolExecutor =
        pool(size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)

    fun pool(core: Int, max: Int, keepAlive: Long, unit: TimeUnit,
             queue: java.util.concurrent.BlockingQueue<Runnable>, factory: ThreadFactory): ThreadPoolExecutor =
        executor(GenerationPool(core, max, keepAlive, unit, queue, factory))

    private class GenerationPool(core: Int, max: Int, keepAlive: Long, unit: TimeUnit,
        queue: java.util.concurrent.BlockingQueue<Runnable>, factory: ThreadFactory) :
        ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory) {
        init {
            if (keepAlive > 0) allowCoreThreadTimeOut(true)
        }

        override fun execute(command: Runnable) {
            synchronized(gate) {
                if (!accepting) throw RejectedExecutionException("Module generation is retiring")
                super.execute(GenerationTask(command))
            }
        }

        private inner class GenerationTask(val command: Runnable) : Runnable {
            override fun run() = runTask(command)
        }

        override fun remove(task: Runnable): Boolean {
            if (super.remove(task)) return true
            val queued = queue.firstOrNull { it is GenerationTask && it.command === task } ?: return false
            return super.remove(queued)
        }

        override fun purge() {
            queue.toTypedArray().forEach { queued ->
                val command = (queued as? GenerationTask)?.command ?: queued
                if ((command as? java.util.concurrent.Future<*>)?.isCancelled == true) super.remove(queued)
            }
            super.purge()
        }

        override fun shutdownNow(): MutableList<Runnable> =
            super.shutdownNow().mapTo(ArrayList()) { (it as? GenerationTask)?.command ?: it }

        private fun runTask(command: Runnable) {
            val tracked = injected
            if (tracked) {
                val entered = try {
                    gate.awaitTask()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!entered) {
                    (command as? java.util.concurrent.Future<*>)?.cancel(false)
                    return
                }
            }
            try {
                command.run()
            } finally {
                if (tracked) gate.leave()
            }
        }
    }

    fun cleanup(action: () -> Unit) {
        val retired = synchronized(cleanups) {
            if (injected && gate.state == ReloadGate.State.RETIRED) true else {
                cleanups += action
                false
            }
        }
        if (retired) action()
    }
    fun blockWhile(reason: () -> String?) = synchronized(gate) {
        if (!accepting) throw RejectedExecutionException("Module generation is retiring")
        synchronized(blockers) { blockers += reason }
    }

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
        private var admission: AutoCloseable? = null

        @Synchronized override fun start() {
            synchronized(gate) {
                if (state != State.NEW) throw IllegalThreadStateException()
                admission = lease() ?: throw RejectedExecutionException("Module generation is retiring")
                threads.add(this)
                try { super.start() } catch (error: Throwable) {
                    admission?.close()
                    admission = null
                    throw error
                }
            }
        }
        override fun run() {
            try { action.run() } finally { admission?.close() }
        }
    }

    internal fun begin(): String? {
        if (!gate.begin()) return "模块任务仍在运行"
        val reason = runCatching {
            synchronized(blockers) { blockers.toList() }.firstNotNullOfOrNull { it() }
                ?: synchronized(executors) { executors.toList() }.let { registered ->
                    registered.firstOrNull {
                        when (it) {
                            is ScheduledThreadPoolExecutor -> it.activeCount != 0 || it.queue.isNotEmpty()
                            is ThreadPoolExecutor -> it.activeCount != 0 || it.queue.isNotEmpty()
                            else -> true
                        }
                    }?.let { "后台队列尚未清空" }
                }
                ?: threads.snapshot().firstOrNull { it is GenerationThread && it.isAlive }
                    ?.let { "后台线程尚未退出" }
        }.getOrElse { "无法确认模块空闲：${it.javaClass.simpleName}" }
        if (reason != null) reject()
        return reason
    }

    internal fun reject() = gate.reject()

    internal fun awaitIdle(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "Cannot wait for module work on the main thread" }
        if (!gate.awaitIdle(timeoutMillis)) return false
        // A pool worker leaves the gate just before ThreadPoolExecutor updates activeCount.
        // Give that bookkeeping and queued main completions a chance to finish, then recheck.
        CountDownLatch(1).await(minOf(timeoutMillis, CHECK_RETRY_MILLIS), TimeUnit.MILLISECONDS)
        return true
    }

    internal fun busySummary(): String {
        val pools = synchronized(executors) { executors.filterIsInstance<ThreadPoolExecutor>() }
        return "tasks=${gate.activeTasks} activeWorkers=${pools.sumOf { it.activeCount }} queued=${pools.sumOf { it.queue.size }}"
    }

    internal fun retireOnMain(): ResourceRetirement {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Module UI retirement requires the main thread" }
        // 先停止正在等待检查结果的任务及 Handler 重排，再清理所有回调。
        gate.retire()
        val pools = synchronized(executors) { executors.toList().also { executors.clear() } }
        val retirement = ResourceRetirement(pools, threads::drain, Looper.getMainLooper().thread)
        val callbacks = synchronized(cleanups) { cleanups.asReversed().toList().also { cleanups.clear() } }
        callbacks.forEach(retirement::attempt)
        handlers.drain().forEach { handler -> retirement.attempt { handler.removeCallbacksAndMessages(null) } }
        retirement.attempt { clearMainCallbacks() }
        retirement.shutdown()
        synchronized(blockers) { blockers.clear() }
        return retirement
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
