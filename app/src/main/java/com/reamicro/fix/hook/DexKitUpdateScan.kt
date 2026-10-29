package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostScanUpdateStamp
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 只在前台主页面展示；扫描任务不持有 Activity，重建页面时接回同一次任务。 */
internal object DexKitUpdateScan {
    private const val PREFS = "reamicro_fix_dexkit_updates"
    private const val COMPLETED = "completed_update_v1"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ReaMicro-DexKit-Update").apply { isDaemon = true }
    }
    private var resumed = WeakReference<Activity>(null)
    private var dialog: Dialog? = null
    private var session: Session? = null
    private var attempted: HostScanUpdateStamp? = null
    private var checking = false

    private data class Installation(
        val stamp: HostScanUpdateStamp,
        val hostPath: String,
        val dataDir: String,
    )

    private class Session(
        val installation: Installation,
        val reason: String,
        var finished: Boolean = false,
        var report: HostAutoScanner.Report? = null,
        var error: String? = null,
    )

    fun onResume(activity: Activity) {
        resumed = WeakReference(activity)
        if (session != null) {
            showSession()
            return
        }
        if (checking || isManualDexKitScanning()) return
        checking = true
        val context = activity.applicationContext
        worker.execute {
            val result = runCatching {
                val installation = readInstallation(context)
                val previous = HostScanUpdateStamp.decode(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COMPLETED, null),
                )
                installation to previous
            }
            main.post {
                checking = false
                result.onSuccess { (installation, previous) ->
                    if (liveActivity() == null || session != null || isManualDexKitScanning() ||
                        !installation.stamp.needsScan(previous, attempted)) return@onSuccess
                    val next = Session(installation, installation.stamp.changeReason(previous))
                    session = next
                    if (showSession()) {
                        attempted = installation.stamp
                        start(context, next)
                    } else {
                        session = null
                    }
                }.onFailure {
                    // 模块正在覆盖安装或包信息不可读时不写入完成标记，下次恢复前台重试。
                    XposedBridge.logError("ReaMicro DexKit update check deferred", it)
                }
            }
        }
    }

    fun onPause(activity: Activity) {
        if (resumed.get() !== activity) return
        resumed.clear()
        dismissDialog()
    }

    fun onDestroyed(activity: Activity) = onPause(activity)

    fun isBusy(): Boolean = session?.finished == false

    private fun start(context: Context, current: Session) {
        XposedBridge.logAlways("ReaMicro DexKit update scan started: ${current.reason} " +
            "host=${current.installation.stamp.hostUpdatedAt} module=${current.installation.stamp.moduleUpdatedAt}")
        worker.execute {
            var report: HostAutoScanner.Report? = null
            val result = runCatching {
                // 查询包信息后到窗口出现前可能发生覆盖安装，不能给旧模块记录新版本已扫描。
                check(readInstallation(context) == current.installation) { "安装状态已变化，请重新打开阅微" }
                report = HostAutoScanner.scan(
                    current.installation.hostPath, current.installation.dataDir, force = true,
                )
                check(report?.profile != null && report.cached == false) { "扫描未完成，下次打开阅微将重试" }
                check(readInstallation(context) == current.installation) { "扫描期间检测到更新，请重新打开阅微" }
                // 有定位失败仍保留报告并记一次完成；弹窗只显示摘要，避免反复打扰。
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(COMPLETED, current.installation.stamp.encode()).commit()) {
                    "扫描已完成，但更新时间未保存，下次打开阅微将重试"
                }
            }
            main.post {
                if (session !== current) return@post
                current.report = report
                current.error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                current.finished = true
                XposedBridge.logAlways("ReaMicro DexKit update scan finished: recorded=${result.isSuccess}")
                result.exceptionOrNull()?.let {
                    XposedBridge.logError("ReaMicro DexKit update scan incomplete", it)
                }
                showSession()
            }
        }
    }

    private fun liveActivity(): Activity? = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun showSession(): Boolean {
        val activity = liveActivity() ?: return false
        val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return false
        val current = session ?: return false
        dismissDialog()
        return runCatching {
            dialog = hook.showDexKitUpdateDialog(
                activity, current.reason, current.finished, current.report, current.error,
            ) {
                if (session === current && current.finished) {
                    session = null
                    dismissDialog()
                }
            }
            true
        }.getOrElse {
            XposedBridge.logError("ReaMicro DexKit update dialog failed", it)
            false
        }
    }

    private fun dismissDialog() {
        val previous = dialog
        dialog = null
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
                module?.lastUpdateTime?.takeIf { it > 0 } ?: moduleFile.lastModified(),
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
