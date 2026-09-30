package com.reamicro.fix.cloud.root

import org.junit.Assert.*
import org.junit.Test

class RootModuleInstallTest {
    private val ready = RootModuleStatus(rootAvailable = true, installed = true, ready = true, installedVersion = 3)

    @Test
    fun `copied new module prop is not proof of an active update`() {
        val staged = ready.copy(stagedVersion = 3, stagedReady = true, pendingUpdate = true)
        assertEquals(RootModuleInstallDecision.REBOOT, staged.installDecision(3))
        assertEquals(RootModuleInstallDecision.READY, ready.installDecision(3))
    }

    @Test
    fun `first install staged without active scripts needs reboot`() {
        val staged = RootModuleStatus(rootAvailable = true, stagedVersion = 3, stagedReady = true)
        assertEquals(RootModuleInstallDecision.REBOOT, staged.installDecision(3))
    }

    @Test
    fun `stale or broken staging must be repaired instead of accepted`() {
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(stagedVersion = 2, stagedReady = true).installDecision(3))
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(stagedVersion = 3).installDecision(3))
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(pendingUpdate = true).installDecision(3))
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(ready = false).installDecision(3))
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(installedVersion = 2).installDecision(3))
    }

    @Test
    fun `disabled or removed module cannot be accepted as ready`() {
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(disabled = true).installDecision(3))
        assertEquals(RootModuleInstallDecision.INSTALL, ready.copy(pendingRemoval = true).installDecision(3))
    }

    @Test
    fun `each provider uses its module specific installation and removal interface`() {
        for (kind in listOf(RootModuleManager.Kind.KERNEL_SU, RootModuleManager.Kind.APATCH)) {
            val manager = RootModuleManager(kind, "/test/manager")
            assertEquals("'/test/manager' module install '/test/module.zip'", manager.install("/test/module.zip"))
            assertEquals("'/test/manager' module uninstall reamicro_automation", manager.uninstall())
            assertTrue(manager.reactivate(true).contains("module undo-uninstall reamicro_automation"))
        }
        val magisk = RootModuleManager(RootModuleManager.Kind.MAGISK, "/test/magisk")
        assertEquals("'/test/magisk' --install-module '/test/module.zip'", magisk.install("/test/module.zip"))
        assertFalse(magisk.uninstall().contains("--remove-modules"))
        assertTrue(magisk.uninstall().contains("/reamicro_automation/remove"))
    }

    @Test
    fun `manager discovery errors cannot be mistaken for an available installer`() {
        assertNull(RootModuleManager.parse(RootCommandResult(1, "manager=ksu\nbinary=/test/ksud")))
        assertNull(RootModuleManager.parse(RootCommandResult(0, "permission denied")))
        assertNull(RootModuleManager.parse(RootCommandResult(0, "manager=unknown\nbinary=/test/manager")))
        assertEquals(RootModuleManager.Kind.APATCH,
            RootModuleManager.parse(RootCommandResult(0, "manager=apatch\nbinary=/data/adb/apd"))?.kind)
    }

    @Test
    fun `shell quoting preserves paths containing spaces and quotes`() {
        val path = "/cache/it's a module.zip"
        val result = RootCommandRunner.execute(ProcessBuilder("sh", "-c",
            "printf '%s' ${RootModuleManager.quote(path)}"))
        assertEquals(0, result.exitCode)
        assertEquals(path, result.output)
    }
}
