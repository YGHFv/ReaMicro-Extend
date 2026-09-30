package com.reamicro.fix.diagnostics

import android.content.Context
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.root.RootCommandRunner
import com.reamicro.fix.cloud.root.RootModuleBundle
import com.reamicro.fix.cloud.root.RootModuleManager
import com.reamicro.fix.cloud.root.RootTaskBridge
import com.reamicro.fix.cloud.root.RootTaskRepository
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Opt-in device diagnostics, packaged in the separate test APK only.
 * Never enables/disables task ownership, installs a manager module, reboots, or reads account data.
 */
class RootDeviceInstrumentation : Instrumentation() {
    private var action = "inspect"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        action = arguments?.getString("action") ?: "inspect"
        start()
    }

    override fun onStart() {
        val context = targetContext.applicationContext
        val report = JSONObject()
            .put("action", action).put("appUid", Process.myUid()).put("appPid", Process.myPid())
            .put("package", context.packageName)
            .put("selinux", runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n') }.getOrDefault("unreadable"))
            .put("enhancementBefore", RootTaskBridge.isEnabled(context))
        try {
            check(Process.myUid() >= 10_000) { "Must run as the application, not shell/root" }
            check(context.packageName == "com.reamicro.fix")
            when (action) {
                "ui" -> probeRootUi(report)
                "inspect", "root-sandbox" -> {
                    val started = SystemClock.elapsedRealtime()
                    val status = RootTaskBridge.inspect(context, authorizationTimeoutSeconds = 30)
                    report.put("inspectionMs", SystemClock.elapsedRealtime() - started)
                        .put("rootAvailable", status.rootAvailable).put("accessState", status.access.state.name)
                        .put("su", status.access.executable).put("error", status.error)
                        .put("inspectionComplete", status.inspectionComplete)
                        .put("moduleInstalled", status.installed).put("moduleReady", status.ready)
                        .put("activeReportedVersion", status.installedVersion)
                        .put("stagedVersion", status.stagedVersion).put("stagedReady", status.stagedReady)
                        .put("pendingUpdate", status.pendingUpdate).put("daemonRunning", status.daemonRunning)
                    if (status.rootAvailable) {
                        val native = RootCommandRunner.run("/system/bin/id; /system/bin/id -Z; test -r /data/adb && echo adb_readable=1", timeoutSeconds = 10)
                        report.put("rootChild", native.diagnosticOutput).put("rootChildExit", native.exitCode)
                        val manager = RootModuleManager.parse(RootCommandRunner.run(
                            RootModuleManager.detectionScript(RootCommandRunner.selectedExecutable()), timeoutSeconds = 10))
                        report.put("provider", manager?.kind?.name).put("providerBinary", manager?.executable)
                        if (action == "root-sandbox") runRootSandbox(report)
                    }
                }
                "migration" -> {
                    report.put("cleanupCompleted", com.reamicro.fix.migration.LegacyAndroidWakeCleanup.completed(context))
                    report.put("legacyPreferencesAbsent",
                        !File(context.applicationInfo.dataDir, "shared_prefs/reamicro-task-wake.xml").exists())
                    report.put("legacyHintAbsent", !File(context.filesDir, "next-wake-at").exists())
                }
                else -> error("Unknown diagnostic action")
            }
            report.put("completed", true)
        } catch (error: Throwable) {
            report.put("completed", false).put("failure", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            report.put("enhancementAfter", RootTaskBridge.isEnabled(context))
            report.put("enhancementUnchanged", report.getBoolean("enhancementBefore") == RootTaskBridge.isEnabled(context))
            val directory = File(context.filesDir, "device-diagnostics").apply { mkdirs() }
            File(directory, "$action.json").writeText(report.toString(2))
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n" + report.toString(2) + "\n") })
        }
    }

    private fun runRootSandbox(report: JSONObject) {
        val context = targetContext.applicationContext
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val base = "/data/local/tmp/reamicro-wake-diag-$nonce"
        val module = "$base/module"
        val state = "$base/state"
        val cache = File(context.cacheDir, "wake-diag-$nonce").apply { mkdirs() }
        report.put("sandboxDirectory", base)
        fun quote(text: String) = RootModuleManager.quote(text)
        fun command(text: String, timeout: Long = 20): String {
            val result = RootCommandRunner.run(text, timeoutSeconds = timeout)
            check(result.exitCode == 0) { "Sandbox exit=${result.exitCode}: ${result.diagnosticOutput.takeLast(500)}" }
            return result.output
        }
        var prepared = false
        try {
            val names = context.assets.list("module").orEmpty().filter { it.endsWith(".zip") }
            check(names.size == 1)
            val bundle = File(cache, "module.zip")
            context.assets.open("module/${names.single()}").use { input -> bundle.outputStream().use { input.copyTo(it) } }
            RootModuleBundle.verify(bundle, BuildConfig.ROOT_MODULE_VERSION_CODE)
            ZipFile(bundle).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".sh") && '/' !in it.name }.forEach { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        .replace(RootTaskRepository.STATE_DIRECTORY, state)
                        .replace(RootTaskRepository.MODULE_DIRECTORY, module)
                        .replace("/data/adb/service.d/reamicro-watchdog.sh", "$base/legacy.sh")
                        .replace("com.reamicro.fix.cloud.root.RootTaskMain", "diagnostic.$nonce.FakeMain")
                        .replace("/sys/power/", "$base/unused-sys/")
                    check(!text.contains(RootTaskRepository.STATE_DIRECTORY))
                    File(cache, entry.name).writeText(text)
                }
            }
            // No real engine or account token. The task proves daemon dispatch and heartbeat only.
            File(cache, "runner.sh").writeText("#!/system/bin/sh\nprintf 'run\\n' >> '$state/runs'\nsleep 8\n")
            command("umask 077; mkdir -p '$module' '$state' && cp ${quote(cache.absolutePath)}/*.sh '$module/' && chmod 700 '$module/'*.sh && printf '{\"enabled\":true}' >'$state/state.json' && echo 0 >'$state/next-run-at'")
            prepared = true
            command("sh '$module/service.sh' start </dev/null")
            val first = command("cat '$state/daemon.pid'")
            command("sh '$module/service.sh' start </dev/null")
            val second = command("cat '$state/daemon.pid'")
            report.put("singleInstance", first == second).put("sandboxPid", first)
            val heartbeatBefore = command("cat '$state/heartbeat'")
            SystemClock.sleep(3_000)
            val heartbeatAfter = command("cat '$state/heartbeat'")
            report.put("heartbeatAdvanced", heartbeatAfter.toLong() > heartbeatBefore.toLong())
            SystemClock.sleep(7_000)
            report.put("sandboxDispatchCount", command("wc -l <'$state/runs'").trim().toInt())
            command("sh '$module/stop.sh'")
            report.put("stopConfirmed", command("test -f '$state/stop' && test ! -f '$state/daemon.pid' && echo stopped") == "stopped")
            command("sh '$module/service.sh'")
            report.put("stopPersists", command("test ! -f '$state/daemon.pid' && echo stopped") == "stopped")
            check(first == second)
        } finally {
            val cleanup = runCatching {
                if (prepared) command("sh '$module/uninstall.sh'", 30)
                command("rm -rf '$base'")
            }
            report.put("sandboxCleaned", cleanup.isSuccess)
            cleanup.exceptionOrNull()?.let { report.put("cleanupError", it.message) }
            cache.deleteRecursively()
        }
    }
}
