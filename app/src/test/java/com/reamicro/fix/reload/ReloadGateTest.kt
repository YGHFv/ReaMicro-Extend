package com.reamicro.fix.reload

import org.junit.Assert.*
import org.junit.Test

class ReloadGateTest {
    @Test fun runningTaskRejectsWithoutChangingGeneration() {
        val gate = ReloadGate()
        assertTrue(gate.enter())
        assertFalse(gate.begin())
        assertEquals(ReloadGate.State.ACTIVE, gate.state)
        gate.leave()
        assertTrue(gate.begin())
    }

    @Test fun preparationBlocksNewTasksAndRejectionRestoresThem() {
        val gate = ReloadGate()
        assertTrue(gate.begin())
        assertFalse(gate.enter())
        assertFalse(gate.begin())
        gate.reject()
        assertTrue(gate.enter())
        gate.leave()
    }

    @Test fun retiredGenerationCannotRestartOrReloadTwice() {
        val gate = ReloadGate()
        assertTrue(gate.begin())
        gate.retire()
        assertFalse(gate.enter())
        assertFalse(gate.begin())
    }

    @Test fun callerCannotSelectAnotherPackage() {
        assertTrue(HotReloadContract.authorized("app.zhendong.reamicro", listOf("app.zhendong.reamicro")))
        assertFalse(HotReloadContract.authorized("app.zhendong.reamicro", listOf("bin.mt.plus")))
        assertFalse(HotReloadContract.authorized("com.reamicro.fix", listOf("com.reamicro.fix")))
        assertFalse(HotReloadContract.authorized(null, emptyList()))
    }

    @Test fun racingWorkerEitherBlocksReloadOrIsRejected() {
        repeat(100) {
            val gate = ReloadGate()
            val start = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.atomic.AtomicBoolean()
            val worker = Thread { start.await(); entered.set(gate.enter()) }
            worker.start()
            start.countDown()
            val reloading = gate.begin()
            worker.join()
            assertFalse(reloading && entered.get())
            assertTrue(reloading || entered.get())
            if (entered.get()) gate.leave() else gate.reject()
        }
    }
}
