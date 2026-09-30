package com.reamicro.fix.cloud.root

import org.junit.Assert.*
import org.junit.Test

class RootModuleLifecycleTest {
    private val ready = RootModuleStatus(rootAvailable = true, installed = true, ready = true, installedVersion = 4)
    private var status = ready
    private val commands = mutableListOf<String>()
    private var installs = 0
    private fun lifecycle(
        kind: RootModuleManager.Kind = RootModuleManager.Kind.KERNEL_SU,
        mutate: (String) -> Unit = {},
        installed: () -> Unit = {
            status = status.copy(stagedPresent = true, stagedReady = true, stagedVersion = 4, pendingUpdate = true)
        },
    ) = RootModuleLifecycle(
        inspect = { status },
        manager = { RootModuleManager(kind, "/test/manager") },
        execute = { script, _ -> commands += script; mutate(script); RootCommandResult(0, "") },
        install = { installs++; installed() },
    )
    @Test fun `ready is idempotent without manager mutations`() {
        assertEquals(RootModuleInstallDecision.READY, lifecycle().prepare(4))
        assertEquals(0, installs)
        assertTrue(commands.isEmpty())
    }
    @Test fun `upgrade stages same ID without uninstall or clearing state`() {
        status = ready.copy(installedVersion = 2)
        assertEquals(RootModuleInstallDecision.REBOOT, lifecycle().prepare(4))
        assertEquals(1, installs)
        assertTrue(commands.isEmpty())
    }
    @Test fun `complete staging does not reinstall on every tap`() {
        status = ready.copy(stagedPresent = true, stagedReady = true, stagedVersion = 4)
        assertEquals(RootModuleInstallDecision.REBOOT, lifecycle().prepare(4))
        assertEquals(0, installs)
    }
    @Test fun `corrupt staging is not mistaken for ready active copy`() {
        status = ready.copy(stagedPresent = true)
        lifecycle().prepare(4)
        assertEquals(1, installs)
    }
    @Test fun `installer exit zero with no files fails verification`() {
        status = ready.copy(installedVersion = 1)
        assertTrue(runCatching { lifecycle(installed = {}).prepare(4) }.isFailure)
        assertEquals(1, installs)
    }
    @Test fun `installer timeout is not replayed`() {
        status = ready.copy(installedVersion = 1)
        assertTrue(runCatching { lifecycle(installed = { throw RootCommandTimeout(RootCommandResult(-1, "")) }).prepare(4) }.isFailure)
        assertEquals(1, installs)
        assertTrue(commands.isEmpty())
    }
    @Test fun `failed inspection prevents any lifecycle writes`() {
        status = ready.copy(inspectionComplete = false, error = "read failure")
        assertTrue(runCatching { lifecycle().prepare(4) }.isFailure)
        assertTrue(runCatching { lifecycle().remove() }.isFailure)
        assertEquals(0, installs)
        assertTrue(commands.isEmpty())
    }
    @Test fun `unconfirmed reactivation never proceeds to installation`() {
        status = ready.copy(disabled = true)
        assertTrue(runCatching { lifecycle().prepare(4) }.isFailure)
        assertEquals(0, installs)
    }
    @Test fun `reactivation is verified then reuses current scripts`() {
        status = ready.copy(pendingRemoval = true, disabled = true)
        val life = lifecycle(mutate = { status = ready })
        assertEquals(RootModuleInstallDecision.READY, life.prepare(4))
        assertTrue(commands.single().contains("undo-uninstall"))
    }
    @Test fun `removal cancels staging before provider bookkeeping for every manager`() {
        for (kind in RootModuleManager.Kind.entries) {
            commands.clear()
            status = ready.copy(stagedPresent = true, stagedVersion = 4)
            val life = lifecycle(kind, mutate = {
                status = if (it.contains("rm -rf")) status.copy(stagedPresent = false, stagedVersion = 0)
                    else status.copy(pendingRemoval = true)
            })
            assertTrue(life.remove())
            assertTrue(commands.first().contains("/modules_update/reamicro_automation"))
            assertFalse(commands.any { it.contains("rm -rf '/data/adb/modules/reamicro_automation'") })
            assertFalse(commands.any { it.contains("--remove-modules") })
        }
    }
    @Test fun `orphan staging can be canceled without uninstalling a nonexistent active module`() {
        status = RootModuleStatus(rootAvailable = true, stagedPresent = true)
        assertFalse(lifecycle(mutate = { status = RootModuleStatus(rootAvailable = true) }).remove())
        assertEquals(1, commands.size)
    }
    @Test fun `no pending removal marker is never reported as successful uninstall`() {
        assertTrue(runCatching { lifecycle().remove() }.isFailure)
    }
    @Test fun `newer module is never silently downgraded to repair damage`() {
        status = ready.copy(installedVersion = 9, ready = false)
        assertTrue(runCatching { lifecycle().prepare(4) }.isFailure)
        assertEquals(0, installs)
    }
    @Test fun `orphan staging is not falsely reported as disabled`() {
        status = RootModuleStatus(rootAvailable = true, stagedPresent = true)
        assertTrue(runCatching { lifecycle().pause() }.isFailure)
        assertTrue(commands.isEmpty())
    }
    @Test fun `pause uses provider disable without deleting module files`() {
        lifecycle(mutate = { status = ready.copy(disabled = true) }).pause()
        assertTrue(commands.single().contains("module disable"))
        assertFalse(commands.single().contains("rm "))
    }
}