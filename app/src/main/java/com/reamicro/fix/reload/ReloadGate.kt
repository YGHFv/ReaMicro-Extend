package com.reamicro.fix.reload

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 先封住新任务入口，再判断是否空闲；拒绝时必须恢复旧代际。 */
internal class ReloadGate {
    enum class State { ACTIVE, CHECKING, RETIRED }
    @Volatile var state = State.ACTIVE
        private set
    private var tasks = 0
    private var idle = CountDownLatch(0)
    private var resolution = CountDownLatch(0)
    val activeTasks: Int
        @Synchronized get() = tasks

    @Synchronized fun enter(): Boolean {
        if (state != State.ACTIVE) return false
        if (tasks == 0) idle = CountDownLatch(1)
        tasks++
        return true
    }

    @Synchronized fun leave() {
        check(tasks > 0)
        tasks--
        if (tasks == 0) idle.countDown()
    }

    @Synchronized fun begin(): Boolean {
        if (state != State.ACTIVE || tasks != 0) return false
        resolution = CountDownLatch(1)
        state = State.CHECKING
        return true
    }

    /** Wait outside the main thread; an idle moment must still be sealed by begin(). */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val pending = synchronized(this) {
                if (state != State.ACTIVE) return false
                if (tasks == 0) return true
                idle
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L || !pending.await(remaining, TimeUnit.NANOSECONDS)) return false
        }
    }

    /** 已入队的线程池任务等待检查结果；拒绝后继续，退场后不再进入旧代码。 */
    fun awaitTask(): Boolean {
        while (true) {
            val pending = synchronized(this) {
                when (state) {
                    State.ACTIVE -> return enter()
                    State.RETIRED -> return false
                    State.CHECKING -> resolution
                }
            }
            pending.await()
        }
    }

    @Synchronized fun reject() {
        check(state == State.CHECKING)
        state = State.ACTIVE
        resolution.countDown()
    }

    @Synchronized fun retire() {
        check(state == State.CHECKING && tasks == 0)
        state = State.RETIRED
        resolution.countDown()
    }
}
