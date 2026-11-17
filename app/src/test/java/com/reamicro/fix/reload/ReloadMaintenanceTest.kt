package com.reamicro.fix.reload

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReloadMaintenanceTest {
    @Test fun pauseCancelsNetworkAndWaitsForTheWorkerToLeaveItsGate() {
        val maintenance = ReloadMaintenance()
        val gate = ReloadGate()
        val started = CountDownLatch(1)
        val disconnected = AtomicBoolean()
        val completed = AtomicBoolean()
        val cancelled = AtomicBoolean()
        lateinit var ticket: ReloadMaintenance.Ticket
        val worker = Thread {
            try {
                ticket.run(Runnable {
                    TaskCancellation.current()!!.register { disconnected.set(true) }.use {
                        started.countDown()
                        CountDownLatch(1).await(3, TimeUnit.SECONDS)
                        TaskCancellation.checkCurrent()
                        completed.set(true)
                    }
                })
            } catch (_: InterruptedException) {
                cancelled.set(true)
            } catch (_: CancellationException) {
                cancelled.set(true)
            } finally {
                ticket.close()
                gate.leave()
            }
        }
        ticket = maintenance.register(worker)
        assertTrue(gate.enter())
        worker.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertFalse(gate.begin())
            maintenance.pause()
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(disconnected.get())
            assertTrue(cancelled.get())
            assertFalse(completed.get())
            assertTrue(gate.begin())
        } finally {
            worker.interrupt()
            worker.join(3_000)
        }
    }

    @Test fun aTaskRegisteredBeforePauseCannotBeginItsActionAfterPause() {
        val maintenance = ReloadMaintenance()
        val ran = AtomicBoolean()
        maintenance.register(Thread()).use { ticket ->
            maintenance.pause()
            assertThrows(CancellationException::class.java) { ticket.run(Runnable { ran.set(true) }) }
            assertFalse(ran.get())
        }
    }

    @Test fun pauseRejectsNewMaintenanceAndResumeAllowsRetryAfterRefusal() {
        val maintenance = ReloadMaintenance()
        maintenance.pause()
        assertThrows(RejectedExecutionException::class.java) { maintenance.register(Thread()) }
        maintenance.resume()
        val ran = AtomicBoolean()
        maintenance.register(Thread()).use { it.run(Runnable { ran.set(true) }) }
        assertTrue(ran.get())
    }

    @Test fun completedMaintenanceIsNotInterruptedByALaterReload() {
        val maintenance = ReloadMaintenance()
        val thread = Thread()
        maintenance.register(thread).close()
        maintenance.pause()
        assertFalse(thread.isInterrupted)
    }

    @Test fun pauseDoesNotReleaseTheAdmissionOfAnOrdinaryImportOrDownload() {
        val gate = ReloadGate()
        val maintenance = ReloadMaintenance()
        assertTrue(gate.enter())
        maintenance.pause()
        assertFalse(gate.begin())
        assertEquals(1, gate.activeTasks)
        gate.leave()
        assertTrue(gate.begin())
        gate.reject()
        maintenance.resume()
        assertTrue(gate.enter())
        gate.leave()
    }
}
