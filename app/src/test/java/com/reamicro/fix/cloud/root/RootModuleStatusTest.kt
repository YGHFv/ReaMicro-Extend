package com.reamicro.fix.cloud.root

import com.reamicro.fix.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RootModuleStatusTest {
    private val now = 1_000_000L
    private val active = RootModuleStatus(
        rootAvailable = true, installed = true, ready = true,
        installedVersion = 1, daemonRunning = true, heartbeatAt = now,
    )

    @Test
    fun `authorization failure never reports a running scheduler`() {
        assertEquals(R.string.execution_root_unavailable, active.copy(rootAvailable = false).stateResource(true, now))
    }

    @Test
    fun `module lifecycle markers take priority over stale process data`() {
        assertEquals(R.string.execution_module_removing, active.copy(pendingRemoval = true).stateResource(true, now))
        assertEquals(R.string.execution_module_pending, active.copy(stagedVersion = 2).stateResource(true, now))
        assertEquals(R.string.execution_module_pending, RootModuleStatus(rootAvailable = true, stagedVersion = 2).stateResource(false, now))
        assertEquals(R.string.execution_module_disabled, active.copy(disabled = true).stateResource(true, now))
        assertEquals(R.string.execution_module_incomplete, active.copy(ready = false).stateResource(true, now))
    }

    @Test
    fun `fresh heartbeat alone does not prove that the process is alive`() {
        assertEquals(R.string.execution_daemon_stopped, active.copy(daemonRunning = false).stateResource(true, now))
        assertEquals(R.string.execution_status_running, active.stateResource(true, now))
    }

    @Test
    fun `stale and future heartbeats are not reported as healthy`() {
        assertEquals(R.string.execution_status_no_heartbeat, active.copy(heartbeatAt = now - 120_001L).stateResource(true, now))
        assertEquals(R.string.execution_status_no_heartbeat, active.copy(heartbeatAt = now + 1L).stateResource(true, now))
        assertEquals(R.string.execution_status_no_heartbeat, active.copy(heartbeatAt = 0L).stateResource(true, now))
    }

    @Test
    fun `installed module is not confused with selected Root execution`() {
        assertEquals(R.string.execution_module_idle, active.stateResource(false, now))
        assertEquals(R.string.execution_module_missing, RootModuleStatus(rootAvailable = true).stateResource(false, now))
    }
}
