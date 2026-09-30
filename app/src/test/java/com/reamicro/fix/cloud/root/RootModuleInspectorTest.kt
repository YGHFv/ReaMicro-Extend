package com.reamicro.fix.cloud.root

import com.reamicro.fix.R
import org.junit.Assert.*
import org.junit.Test

class RootModuleInspectorTest {
    private val granted = RootAccessReport(RootAccessState.GRANTED, "su")
    @Test fun `slow module inspection never turns granted root into no root`() {
        val state = RootModuleInspector.inspect(granted) { throw RootCommandTimeout(RootCommandResult(-1, "")) }
        assertTrue(state.rootAvailable)
        assertFalse(state.inspectionComplete)
        assertEquals(R.string.execution_module_probe_error, state.stateResource(false, 0))
    }
    @Test fun `nonzero file probe retains the independent grant`() {
        assertTrue(RootModuleInspector.inspect(granted) { RootCommandResult(72, "", "denied /data/adb") }.rootAvailable)
    }
    @Test fun `denied permission never starts module probe`() {
        val status = RootModuleInspector.inspect(RootAccessReport(RootAccessState.DENIED)) { error("must not run") }
        assertFalse(status.rootAvailable)
    }
    @Test fun `revocation between stages replaces earlier grant`() {
        val status = RootModuleInspector.inspect(granted) {
            throw RootAccessException(RootAccessReport(RootAccessState.DENIED))
        }
        assertFalse(status.rootAvailable)
    }
    @Test fun `partial output is not a module missing verdict`() {
        val status = RootModuleInspector.parse("installed=1", granted)
        assertFalse(status.inspectionComplete)
        assertTrue(status.rootAvailable)
    }
    @Test fun `staging and current scripts are separate versions`() {
        val status = RootModuleInspector.parse(
            "installed=1\nready=1\nversion=3\nstaged=4\nstagedReady=1\nstagedPresent=1\nupdate=1\nprobe=complete", granted)
        assertEquals(3, status.installedVersion)
        assertEquals(4, status.stagedVersion)
        assertEquals(RootModuleInstallDecision.REBOOT, status.installDecision(4))
    }
    @Test fun `read only probe never enumerates every process or installs anything`() {
        val script = RootModuleInspector.script
        assertFalse(script.contains("/proc/[0-9]*"))
        assertFalse(script.contains("module install"))
        assertFalse(script.contains("rm -"))
        assertTrue(script.contains("daemon.pid"))
    }
}
