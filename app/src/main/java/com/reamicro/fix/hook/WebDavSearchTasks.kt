package com.reamicro.fix.hook

import android.graphics.Bitmap
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 搜索和详情使用不同的有界队列，等待详情不会占满搜索所需的执行器。 */
internal class WebDavSearchTasks : AutoCloseable {
    private val lock = Any()
    private val jobs = linkedSetOf<SearchTask>()
    private var epoch = 0L
    private var paused = false
    private var closed = false
    private val sourcePool = pool("Source", 3, 10)
    private val metadataPool = pool("Metadata", 4, 32)
    private val coverPool = pool("Cover", 2, 24)
    private val covers = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var coverBytes = 0L

    init {
        ModuleResources.cleanup(::close)
    }

    val generation: Long get() = synchronized(lock) { epoch }

    fun isCurrent(generation: Long): Boolean =
        synchronized(lock) { !closed && !paused && epoch == generation } && ModuleResources.accepting

    fun source(parent: TaskCancellation?, generation: Long, finished: () -> Unit, action: () -> Unit): Boolean =
        submit(sourcePool, parent, generation, finished, action)

    fun metadata(parent: TaskCancellation?, generation: Long, finished: () -> Unit, action: () -> Unit): Boolean =
        submit(metadataPool, parent, generation, finished, action)

    fun cover(generation: Long, action: () -> Unit): Boolean = submit(coverPool, null, generation, {}, action)

    fun cachedCover(key: String): Bitmap? = synchronized(lock) { covers[key] }

    fun cacheCover(key: String, bitmap: Bitmap, generation: Long) {
        synchronized(lock) {
            if (closed || paused || generation != epoch) return
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes > MAX_COVER_BYTES) return
            covers.remove(key)?.let { coverBytes -= it.allocationByteCount }
            covers[key] = bitmap
            coverBytes += bytes
            while (covers.size > MAX_COVER_COUNT || coverBytes > MAX_COVER_BYTES) {
                val entry = covers.entries.iterator()
                coverBytes -= entry.next().value.allocationByteCount
                entry.remove()
            }
        }
    }

    fun cancelAll() = cancel(pausing = false, closing = false)

    fun pause() = cancel(pausing = true, closing = false)

    fun resume() {
        synchronized(lock) { if (!closed) paused = false }
    }

    override fun close() = cancel(pausing = true, closing = true)

    private fun cancel(pausing: Boolean, closing: Boolean) {
        val pending = synchronized(lock) {
            epoch++
            if (pausing) paused = true
            if (closing) closed = true
            if (pausing || closing) {
                covers.clear()
                coverBytes = 0
            }
            jobs.toList()
        }
        // disconnect() 和完成回调可能重入，不能在任务集合的锁内执行。
        pending.forEach { it.cancel(true) }
        listOf(sourcePool, metadataPool, coverPool).forEach { pool ->
            pool.purge()
            if (closing) pool.shutdownNow().forEach { (it as? FutureTask<*>)?.cancel(true) }
        }
    }

    private fun submit(
        pool: ThreadPoolExecutor,
        parent: TaskCancellation?,
        generation: Long,
        finished: () -> Unit,
        action: () -> Unit,
    ): Boolean {
        val signal = parent?.child() ?: TaskCancellation()
        val task = SearchTask(pool, signal, finished) {
            parent?.check()
            signal.check()
            if (!isCurrent(generation)) throw java.util.concurrent.CancellationException("Search generation changed")
            action()
        }
        val accepted = synchronized(lock) {
            if (closed || paused || epoch != generation || signal.isCancelled || !ModuleResources.accepting) {
                false
            } else {
                jobs += task
                try {
                    pool.execute(task)
                    // 取消可能发生在 execute 入队前，补做一次移除。
                    if (task.isCancelled) pool.remove(task)
                    true
                } catch (_: RejectedExecutionException) {
                    jobs.remove(task)
                    false
                }
            }
        }
        if (!accepted) task.cancel(false)
        return accepted
    }

    private inner class SearchTask(
        private val pool: ThreadPoolExecutor,
        private val signal: TaskCancellation,
        private val finished: () -> Unit,
        action: () -> Unit,
    ) : FutureTask<Unit>(Callable { signal.run(action) }) {
        init {
            signal.register {
                cancel(true)
                pool.remove(this)
            }
        }

        override fun done() {
            synchronized(lock) { jobs.remove(this) }
            signal.cancel()
            finished()
        }
    }

    private fun pool(label: String, size: Int, capacity: Int): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        return ModuleResources.pool(
            size, size, 20, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
            ThreadFactory { task -> Thread(task, "ReaMicroOnline$label-${sequence.incrementAndGet()}") },
        )
    }

    private companion object {
        const val MAX_COVER_COUNT = 32
        const val MAX_COVER_BYTES = 4L * 1024 * 1024
    }
}

/** 关闭与发布持有同一把锁，返回后的任务不能再改动结果或发出旧快照。 */
internal class WebDavSearchResults<T>(
    initial: List<T>,
    private val isCurrent: () -> Boolean,
    private val onUpdate: ((List<T>) -> Unit)? = null,
) {
    private val lock = Any()
    private val values = initial.toMutableList()
    private var closed = false
    private var finalSnapshot: List<T>? = null

    fun publish(index: Int, value: T) {
        synchronized(lock) {
            if (closed || !isCurrent()) return
            values[index] = value
            onUpdate?.invoke(values.toList())
        }
    }

    fun finish(transform: (T) -> T = { it }, publish: Boolean = false): List<T> =
        synchronized(lock) {
            finalSnapshot?.let { return@synchronized it }
            closed = true
            values.map(transform).also {
                finalSnapshot = it
                if (publish && isCurrent()) onUpdate?.invoke(it)
            }
        }
}
