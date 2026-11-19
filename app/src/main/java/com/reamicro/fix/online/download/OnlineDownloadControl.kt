package com.reamicro.fix.online.download

import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.CancellationException

/**
 * Network ownership ends on pause. Publication retains a separate lifetime so completed chapters
 * can still be committed. The last cancellable point is beginPublishing(), before host mutation.
 */
internal class OnlineDownloadControl(val cancellation: TaskCancellation = TaskCancellation()) {
    enum class RequestResult { ACCEPTED, ALREADY_REQUESTED, TOO_LATE, FINISHED }
    private enum class Stage { RUNNING, PAUSED, PREPARING, CANCELLED, PUBLISHING, COMMITTED, FINISHED }

    val networkCancellation = cancellation.child()
    private var stage = Stage.RUNNING
    private var paused = false
    private val pauseNetworkStopped = java.util.concurrent.CountDownLatch(1)

    val shouldPause: Boolean get() = synchronized(this) { paused && stage != Stage.CANCELLED }
    val isCancelled: Boolean get() = synchronized(this) { stage == Stage.CANCELLED } || cancellation.isCancelled
    val isPublishing: Boolean get() = synchronized(this) { stage == Stage.PUBLISHING || stage == Stage.COMMITTED }
    val isFinished: Boolean get() = synchronized(this) { stage == Stage.FINISHED }
    val canStop: Boolean get() = synchronized(this) {
        stage == Stage.RUNNING || stage == Stage.PAUSED || stage == Stage.PREPARING
    }

    fun attachParent(parent: OnlineDownloadControl): AutoCloseable {
        val network = parent.networkCancellation.register {
            if (parent.shouldPause && !parent.isCancelled) requestPause() else requestCancel()
        }
        // Network cancellation fires only once; a later cancel must still override a prior pause.
        val lifetime = parent.cancellation.register { requestCancel() }
        return AutoCloseable { network.close(); lifetime.close() }
    }

    fun requestPause(): RequestResult {
        val result = synchronized(this) {
            when (stage) {
                Stage.RUNNING -> {
                    paused = true
                    stage = Stage.PAUSED
                    RequestResult.ACCEPTED
                }
                Stage.PAUSED, Stage.PREPARING -> RequestResult.ALREADY_REQUESTED
                Stage.CANCELLED, Stage.FINISHED -> RequestResult.FINISHED
                Stage.PUBLISHING, Stage.COMMITTED -> RequestResult.TOO_LATE
            }
        }
        if (result == RequestResult.ACCEPTED) {
            try { networkCancellation.cancel() } finally { pauseNetworkStopped.countDown() }
        }
        return result
    }

    fun requestCancel(): RequestResult {
        val result = synchronized(this) {
            when (stage) {
                Stage.RUNNING, Stage.PAUSED, Stage.PREPARING -> {
                    stage = Stage.CANCELLED
                    RequestResult.ACCEPTED
                }
                Stage.CANCELLED -> RequestResult.ALREADY_REQUESTED
                Stage.FINISHED -> RequestResult.FINISHED
                Stage.PUBLISHING, Stage.COMMITTED -> RequestResult.TOO_LATE
            }
        }
        if (result == RequestResult.ACCEPTED) cancellation.cancel()
        return result
    }

    /** Call only after the downloader has exited its cancelled network scope. */
    fun preparePausedPublication(): Boolean {
        if (!synchronized(this) { stage == Stage.PAUSED } || cancellation.isCancelled) return false
        // Wait for the pause request's IO-closing callbacks before clearing its interrupt;
        // otherwise a late callback could interrupt the new publication scope.
        Thread.interrupted()
        while (true) {
            try {
                pauseNetworkStopped.await()
                break
            } catch (_: InterruptedException) {
                cancellation.check()
            }
        }
        val accepted = synchronized(this) {
            if (stage != Stage.PAUSED || cancellation.isCancelled) false else {
                stage = Stage.PREPARING
                true
            }
        }
        if (accepted) {
            Thread.interrupted()
            cancellation.check()
        }
        return accepted
    }

    fun check() {
        cancellation.check()
        synchronized(this) {
            when (stage) {
                Stage.PAUSED -> throw CancellationException("下载已暂停")
                Stage.CANCELLED -> throw CancellationException("下载已取消")
                Stage.FINISHED -> throw CancellationException("下载任务已结束")
                else -> Unit
            }
        }
    }

    /** Atomic user-action gate. No user cancellation is accepted after this returns true. */
    @Synchronized
    fun beginPublishing(): Boolean {
        if (cancellation.isCancelled || Thread.currentThread().isInterrupted) return false
        if (stage != Stage.RUNNING && stage != Stage.PREPARING) return false
        stage = Stage.PUBLISHING
        return true
    }

    @Synchronized
    fun markCommitted() {
        check(stage == Stage.PUBLISHING || stage == Stage.COMMITTED)
        stage = Stage.COMMITTED
    }

    /** A committed first batch stays in the library while the remaining chapters can still stop. */
    @Synchronized
    fun resumeAfterCheckpoint() {
        check(stage == Stage.COMMITTED && !paused) { "首批导入尚未完成" }
        cancellation.check()
        stage = Stage.RUNNING
    }

    @Synchronized
    fun finish() { stage = Stage.FINISHED }
}
