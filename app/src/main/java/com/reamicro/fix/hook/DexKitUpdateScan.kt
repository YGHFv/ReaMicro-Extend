package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostScanReadiness
import com.reamicro.fix.core.HostScanUpdateStamp
import com.reamicro.fix.core.HostScanAnnouncement
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** 扫描独立于宿主路由与弹窗；在当前前台 Activity 展示，页面重建时接回同一次任务。 */
internal object DexKitUpdateScan {
    private const val PREFS = "reamicro_fix_dexkit_updates"
    private const val COMPLETED = "completed_update_v1"
    private const val PENDING = "pending_result_update_v1"
    private const val PENDING_REASON = "pending_result_reason_v1"
    private val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private val worker = com.reamicro.fix.reload.ModuleResources.pool(1) { task ->
        Thread(task, "ReaMicro-DexKit-Update").apply { isDaemon = true }
    }
    private var resumed = WeakReference<Activity>(null)
    private var dialog: Dialog? = null
    private var displayedSession: Session? = null
    private var displayedFinished = false
    private var displayedNative = false
    private var displayedHook: ReaMicroSettingsHook? = null
    private var uiReadyChecked = false
    @Volatile private var session: Session? = null
    private var attempted: HostScanUpdateStamp? = null
    private val readiness = HostScanReadiness()
    private val checking: Boolean get() = readiness.checking
    private val startupRunning: Boolean get() = readiness.startupRunning
    @Volatile private var startupInstallation: Installation? = null
    private var automaticBlocked = true
    private val stateListeners = LinkedHashSet<() -> Unit>()

    init {
        com.reamicro.fix.reload.ModuleResources.blockWhile {
            if (startupRunning || checking || isBusy()) "DexKit 扫描尚未结束" else null
        }
        com.reamicro.fix.reload.ModuleResources.cleanup {
            dismissDialog()
            resumed.clear()
            session = null
            startupInstallation = null
            readiness.observeInstallation(null, "模块运行时已退场")
            synchronized(stateListeners) { stateListeners.clear() }
        }
    }

    private data class Installation(
        val stamp: HostScanUpdateStamp,
        val hostPath: String,
        val dataDir: String,
    )

    private class Session(
        val installation: Installation,
        val reason: String,
        val manual: Boolean = false,
        @Volatile var finished: Boolean = false,
        var report: HostAutoScanner.Report? = null,
        var error: String? = null,
    )

    /** 在启动查询开始时检查更新，首个页面恢复前台即可提示，不再重复强制扫描。 */
    fun beginStartup(context: Context) {
        readiness.beginStartup()
        startupInstallation = null
        main.post { notifyBusyChanged(isBusy()) }
        worker.execute {
            val result = runCatching {
                val installation = readInstallation(context)
                startupInstallation = installation
                val previous = HostScanUpdateStamp.decode(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COMPLETED, null))
                installation to previous
            }
            main.post {
                val wasBusy = isBusy()
                result.onSuccess { (installation, previous) ->
                    readiness.observeInstallation(installation.stamp)
                    if (installation.stamp.needsScan(previous, attempted)) {
                        session = Session(installation, installation.stamp.changeReason(previous))
                        attempted = installation.stamp
                        showSession()
                    }
                }.onFailure {
                    readiness.observeInstallation(null, it.message ?: "启动安装状态检查失败")
                    HookRuntime.logError("ReaMicro startup update check failed", it)
                }
                notifyBusyChanged(wasBusy)
            }
        }
    }

    fun onStartupResume(activity: Activity) {
        resumed = WeakReference(activity)
        if (startupRunning || session != null) showSession()
    }

    fun onUiReady() {
        main.post {
            uiReadyChecked = true
            showSession()
        }
    }

    fun finishStartup(context: Context, report: HostAutoScanner.Report?, failure: Throwable? = null) {
        worker.execute {
            val result = runCatching {
                if (failure != null) throw failure
                check(report?.functionalityComplete == true) {
                    report?.runtimeFailures?.firstOrNull() ?: "启动扫描未完成"
                }
                recordStartupValidation(context)
            }
            main.post {
                val wasBusy = isBusy()
                readiness.finishStartup(result.getOrNull()?.stamp, result.exceptionOrNull()?.message)
                session?.takeUnless { it.manual }?.let {
                    it.report = report
                    it.error = result.exceptionOrNull()?.message
                    it.finished = true
                }
                notifyBusyChanged(wasBusy)
                showSession()
            }
        }
    }

    fun onResume(activity: Activity) {
        val wasBusy = isBusy()
        resumed = WeakReference(activity)
        if (startupRunning || checking || isBusy()) {
            showSession()
            return
        }
        // 已完成的结果仍需复核安装状态，不能让旧弹窗绕过覆盖安装后的自动任务门禁。
        showSession()
        readiness.beginChecking()
        notifyBusyChanged(wasBusy)
        val context = activity.applicationContext
        worker.execute {
            val result = runCatching {
                val installation = readInstallation(context)
                val previous = HostScanUpdateStamp.decode(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COMPLETED, null),
                )
                val pending = HostScanUpdateStamp.decode(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, null),
                )
                if (!installation.stamp.needsScan(previous, attempted)) {
                    HostAutoScanner.restoreCachedReport(installation.hostPath, installation.dataDir)
                }
                Triple(installation, previous, pending)
            }
            main.post {
                result.onSuccess { (installation, previous, pending) ->
                    readiness.finishChecking(installation.stamp)
                    if (session?.installation?.let { it != installation } == true) {
                        session = null
                        dismissDialog()
                    }
                    if (session != null) {
                        showSession()
                        return@onSuccess
                    }
                    if (pending == installation.stamp && session == null && !isManualDexKitScanning() &&
                        HostScanUpdateStamp.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(PENDING, null)) == pending) {
                        val reason = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(PENDING_REASON, null) ?: "后台扫描已完成"
                        session = Session(installation, reason, finished = true, report = HostAutoScanner.snapshot())
                        showSession()
                        return@onSuccess
                    }
                    if (session != null || isManualDexKitScanning() ||
                        !installation.stamp.needsScan(previous, attempted)) return@onSuccess
                    readiness.beginScan(installation.stamp)
                    val next = Session(installation, installation.stamp.changeReason(previous))
                    session = next
                    attempted = installation.stamp
                    start(context, next)
                    showSession()
                }.onFailure {
                    // 模块正在覆盖安装或包信息不可读时不写入完成标记，下次恢复前台重试。
                    readiness.finishChecking(null, it.message ?: "当前安装状态无法确认")
                    HookRuntime.logError("ReaMicro DexKit update check deferred", it)
                }
                notifyBusyChanged(wasBusy)
            }
        }
    }

    fun onPause(activity: Activity) {
        if (resumed.get() !== activity) return
        resumed.clear()
        dismissDialog()
    }

    fun onDestroyed(activity: Activity) = onPause(activity)

    /** 手动扫描共享生命周期和会话，旋转或切换到后台不会丢失结果。 */
    fun requestManualScan(activity: Activity): Boolean {
        if (startupRunning || checking || isBusy()) return false
        val wasBusy = isBusy()
        resumed = WeakReference(activity)
        readiness.beginChecking()
        notifyBusyChanged(wasBusy)
        val context = activity.applicationContext
        worker.execute {
            val installation = runCatching { readInstallation(context) }
            main.post {
                installation.onSuccess { value ->
                    readiness.beginScan(value.stamp)
                    val next = Session(value, "手动重新扫描", manual = true)
                    session = next
                    attempted = value.stamp
                    start(context, next)
                    showSession()
                }.onFailure {
                    readiness.finishChecking(null, it.message ?: "手动扫描安装状态检查失败")
                    HookRuntime.logError("ReaMicro manual DexKit installation check failed", it)
                    android.widget.Toast.makeText(context,
                        "无法读取当前安装状态，请重新打开阅微后扫描", android.widget.Toast.LENGTH_LONG).show()
                }
                notifyBusyChanged(wasBusy)
            }
        }
        return true
    }

    fun isManualScanning(): Boolean = session?.let { it.manual && !it.finished } == true

    /** 启动扫描必须验证开始和结束时的同一安装，才能给当前进程放行自动任务。 */
    private fun recordStartupValidation(context: Context): Installation {
        check(HostAutoScanner.snapshot()?.functionalityComplete == true) { "Startup query generation is incomplete" }
        val installation = readInstallation(context)
        check(installation == startupInstallation) { "启动扫描期间安装状态已变化，请重新打开阅微" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = HostScanUpdateStamp.decode(prefs.getString(COMPLETED, null))
        val pending = HostScanUpdateStamp.decode(prefs.getString(PENDING, null))
        val announce = HostScanAnnouncement.pending(installation.stamp, previous, pending)
        val reason = if (previous == installation.stamp && pending == installation.stamp)
            prefs.getString(PENDING_REASON, null) ?: "后台扫描已完成"
        else installation.stamp.changeReason(previous)
        val edit = prefs.edit().putString(COMPLETED, installation.stamp.encode())
        if (announce) edit.putString(PENDING, installation.stamp.encode()).putString(PENDING_REASON, reason)
        check(edit.commit()) {
            "Cannot persist completed startup query generation"
        }
        if (announce) {
            val report = HostAutoScanner.snapshot()
            main.post {
                if (session == null) session = Session(installation, reason, finished = true, report = report)
                HookRuntime.logAlways("ReaMicro DexKit startup result pending: $reason")
                showSession()
            }
        }
        return installation
    }

    fun isBusy(): Boolean = session?.finished == false

    /** 后台逐本更新只读取线程可见的快照，完成窗口和待确认结果均不参与验证判定。 */
    fun automaticUpdateBlockReason(): String? =
        if (!com.reamicro.fix.reload.ModuleResources.accepting) "模块正在热重载"
        else readiness.automaticUpdateBlockReason()

    /** 仅在自动批次开始前的后台线程复核包状态；逐本检查不再读取包信息。 */
    fun validateAutomaticUpdateInstallation(context: Context): String? {
        automaticUpdateBlockReason()?.let { return it }
        runCatching { readInstallation(context) }.onSuccess {
            readiness.observeInstallation(it.stamp)
        }.onFailure {
            readiness.observeInstallation(null, it.message ?: "当前安装状态无法确认")
            HookRuntime.logError("ReaMicro automatic update installation validation failed", it)
        }
        val reason = automaticUpdateBlockReason()
        if (reason != null) main.post { notifyBusyChanged(isBusy()) }
        return reason
    }

    /** 启动首帧门禁只允许独立扫描窗口先显示，手动扫描不影响宿主页绘制。 */
    fun isStartupDialogShowing(): Boolean = dialog?.isShowing == true && session?.manual == false

    fun addStateListener(listener: () -> Unit) {
        synchronized(stateListeners) { stateListeners += listener }
    }

    private fun notifyBusyChanged(previous: Boolean) {
        val blocked = automaticUpdateBlockReason() != null
        if (previous == isBusy() && automaticBlocked == blocked) return
        automaticBlocked = blocked
        synchronized(stateListeners) { stateListeners.toList() }.forEach { listener ->
            runCatching(listener).onFailure {
                HookRuntime.logError("ReaMicro DexKit state listener failed", it)
            }
        }
    }

    private fun start(context: Context, current: Session) {
        HookRuntime.logAlways("ReaMicro DexKit update scan started: ${current.reason} " +
            "host=${current.installation.stamp.hostUpdatedAt} module=${current.installation.stamp.moduleUpdatedAt}")
        worker.execute {
            var report: HostAutoScanner.Report? = null
            val result = runCatching {
                // 查询包信息后到窗口出现前可能发生覆盖安装，不能给旧模块记录新版本已扫描。
                check(readInstallation(context) == current.installation) { "安装状态已变化，请重新打开阅微" }
                report = HostAutoScanner.scan(
                    current.installation.hostPath, current.installation.dataDir, force = true,
                )
                check(report?.profile != null && report.cached == false && report.functionalityComplete) {
                    report?.runtimeFailures?.firstOrNull() ?: "功能查询未完成，下次打开阅微将重试"
                }
                check(readInstallation(context) == current.installation) { "扫描期间检测到更新，请重新打开阅微" }
                // 所有功能契约通过后记录本次更新；旧目录缺项仍完整保留在扫描报告中。
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(COMPLETED, current.installation.stamp.encode())
                    .putString(PENDING, current.installation.stamp.encode())
                    .putString(PENDING_REASON, current.reason).commit()) {
                    "扫描已完成，但更新时间未保存，下次打开阅微将重试"
                }
            }
            main.post {
                if (session !== current) return@post
                val wasBusy = isBusy()
                current.report = report
                current.error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                current.finished = true
                readiness.finishScan(current.installation.stamp, result.isSuccess, current.error)
                HookRuntime.logAlways("ReaMicro DexKit update scan finished: recorded=${result.isSuccess}")
                result.exceptionOrNull()?.let {
                    HookRuntime.logError("ReaMicro DexKit update scan incomplete", it)
                }
                notifyBusyChanged(wasBusy)
                showSession()
            }
        }
    }

    private fun liveActivity(): Activity? = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun showSession(): Boolean {
        val activity = liveActivity() ?: return false
        val current = session ?: return false
        // 早期保留系统启动画面，UI 查询就绪后再展示宿主弹窗。
        if (startupRunning && !uiReadyChecked) return false
        val nativeReady = HostSettingsDialogs.ready
        val hook = ReaMicroSettingsHook.activeInstanceOrNull()?.takeIf { nativeReady && current.finished }
        if (dialog?.isShowing == true && displayedSession === current &&
            displayedFinished == current.finished && displayedNative == nativeReady &&
            displayedHook === hook) return true
        val onClose = {
            if (session === current && current.finished) {
                val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (HostScanUpdateStamp.decode(prefs.getString(PENDING, null)) == current.installation.stamp) {
                    prefs.edit().remove(PENDING).remove(PENDING_REASON).apply()
                }
                session = null
                dismissDialog()
                HookRuntime.logAlways("ReaMicro DexKit startup result acknowledged")
            }
        }
        val resultText = if (current.finished) listOfNotNull(current.error, current.report?.summary())
            .joinToString("\n").ifBlank { "扫描未完成，请重载后重试" } else null
        val reload = {
            if (session === current && current.finished) {
                onClose()
                main.post { com.reamicro.fix.reload.HostHotReload.request(activity) }
            }
        }
        var actualNative = nativeReady
        val replacement = runCatching {
            if (hook == null) return@runCatching showStartupScanProgress(activity, resultText, reload)
            hook.showDexKitUpdateDialog(
                activity, current.reason, current.finished, current.report, current.error,
                onClose,
            )
        }.recoverCatching {
            HookRuntime.logError("ReaMicro DexKit update dialog failed", it)
            actualNative = false
            showFallbackStartupScanProgress(activity, resultText, reload)
        }.getOrElse {
            // 新窗口未能显示时保留旧窗口，不让扫描过程或结果无提示地消失。
            HookRuntime.logError("ReaMicro DexKit fallback dialog failed", it)
            return false
        }
        val previous = dialog
        dialog = replacement
        displayedSession = current
        displayedFinished = current.finished
        displayedNative = actualNative
        displayedHook = hook
        runCatching { previous?.dismiss() }
        HookRuntime.logAlways("ReaMicro DexKit result dialog shown: finished=${current.finished}")
        return true
    }

    private fun dismissDialog() {
        val previous = dialog
        dialog = null
        displayedSession = null
        displayedFinished = false
        displayedNative = false
        displayedHook = null
        runCatching { previous?.dismiss() }
    }

    private fun readInstallation(context: Context): Installation {
        val manager = context.packageManager
        val host = manager.getPackageInfo(context.packageName, 0)
        // 宿主没有声明模块包可见性时，使用框架给出的已装载 APK，不把不可见当成未安装。
        val module = try {
            manager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val hostPath = requireNotNull(host.applicationInfo?.sourceDir)
        val modulePath = requireNotNull(module?.applicationInfo?.sourceDir ?: InjectedModuleContext.sourceDirOrNull())
        check(hostPath == context.applicationInfo.sourceDir &&
            modulePath == InjectedModuleContext.sourceDirOrNull()) {
            "当前进程仍加载更新前的 APK，请重新打开阅微"
        }
        val hostFile = File(hostPath)
        val moduleFile = File(modulePath)
        check(hostFile.isFile && moduleFile.isFile) { "安装包暂不可读" }
        return Installation(
            HostScanUpdateStamp(
                host.lastUpdateTime.takeIf { it > 0 } ?: hostFile.lastModified(),
                // 包可见性会在桥接唤醒后变化；统一使用已加载 APK 时间，避免热重载误报更新。
                moduleFile.lastModified(),
                fileStamp(hostFile), fileStamp(moduleFile),
            ),
            hostPath,
            context.applicationInfo.dataDir,
        )
    }

    private fun fileStamp(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest("${file.path}\u0000${file.length()}\u0000${file.lastModified()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
