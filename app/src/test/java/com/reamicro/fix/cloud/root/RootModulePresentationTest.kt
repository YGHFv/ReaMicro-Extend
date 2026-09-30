package com.reamicro.fix.cloud.root

import com.reamicro.fix.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RootModulePresentationTest {
    private val now = 1_000_000L
    private val ready = RootModuleStatus(
        rootAvailable = true, inspectionComplete = true,
        installed = true, ready = true, installedVersion = 8,
        checkedAt = now, daemonRunning = true, heartbeatAt = now,
    )

    @Test
    fun `module readiness does not depend on task process activity`() {
        assertEquals(R.string.root_module_ready, ready.moduleResource())
        assertEquals(R.string.root_module_ready, ready.copy(daemonRunning = false, heartbeatAt = 0).moduleResource())
        assertEquals(R.string.root_module_ready, ready.copy(taskRunning = true, stopRequested = true).moduleResource())
        assertEquals(R.string.execution_daemon_stopped,
            ready.copy(daemonRunning = false).stateResource(true, now))
    }

    @Test
    fun `disabling automatic tasks does not imply that module files are missing`() {
        assertEquals(R.string.root_module_ready, ready.moduleResource())
        assertEquals(R.string.execution_module_idle, ready.stateResource(false, now))
    }

    @Test
    fun `module lifecycle markers retain their priority`() {
        assertEquals(R.string.execution_module_removing,
            ready.copy(pendingRemoval = true, pendingUpdate = true).moduleResource())
        assertEquals(R.string.execution_module_pending, ready.copy(stagedPresent = true).moduleResource())
        assertEquals(R.string.execution_module_disabled, ready.copy(disabled = true).moduleResource())
        assertEquals(R.string.execution_module_missing, ready.copy(installed = false).moduleResource())
        assertEquals(R.string.execution_module_incomplete, ready.copy(ready = false).moduleResource())
    }

    @Test
    fun `unverified and unreadable states never claim module is ready`() {
        assertEquals(R.string.execution_root_unavailable, ready.copy(rootAvailable = false).moduleResource())
        assertEquals(R.string.execution_module_probe_error, ready.copy(inspectionComplete = false).moduleResource())
    }

    @Test
    fun `task snapshot stays relative to the time it was inspected`() {
        assertEquals(R.string.execution_status_running, ready.stateResource(true, ready.checkedAt))
        assertEquals(R.string.execution_status_no_heartbeat, ready.stateResource(true, now + 120_001L))
        assertEquals(R.string.root_module_ready, ready.moduleResource())
    }
}
