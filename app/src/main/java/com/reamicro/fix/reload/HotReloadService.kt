package com.reamicro.fix.reload

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 只运行在模块应用进程；宿主不持有服务回调，避免跨代际 Binder 引用。 */
internal object HotReloadService : XposedServiceHelper.OnServiceListener {
    // 版本码 82（6aa220a）开始保留进程保护回调；更早版本仍拒绝原地退场。
    private const val FIRST_PROCESS_GUARD_VERSION = 82L
    private val services = CopyOnWriteArrayList<XposedService>()
    private val main = Handler(Looper.getMainLooper())
    private val requests = ConcurrentHashMap<String, Request>()
    private var initialized = false
    private lateinit var context: Context
    private class Request(val uid: Int, val pid: Int, val pkg: String) {
        @Volatile var state = HotReloadContract.PENDING
        @Volatile var message = "正在连接热重载服务"
        val started = SystemClock.elapsedRealtime()
        val lifecycle = ReloadRequestState()
        var service: XposedService? = null
        var connectionTimeout: Runnable? = null
        var receiptTimeout: Runnable? = null
    }

    @Synchronized fun initialize(app: Context) {
        if (initialized) return
        context = app.applicationContext ?: app
        // 清除旧版本保存的升级重试，不再因安装或服务重连自动发起热重载。
        context.getSharedPreferences("reamicro_hot_reload_upgrade", Context.MODE_PRIVATE)
            .edit().clear().apply()
        XposedServiceHelper.registerListener(this)
        initialized = true
    }
    override fun onServiceBind(service: XposedService) {
        services.addIfAbsent(service)
        main.post {
            requests.entries.filter { it.value.lifecycle.phase == ReloadRequestState.Phase.RESERVED }
                .forEach { (id, request) -> submit(id, request) }
            requests.values.filter { it.lifecycle.phase == ReloadRequestState.Phase.UNCONFIRMED }
                .forEach(::reconcileUnconfirmed)
        }
    }
    override fun onServiceDied(service: XposedService) {
        services -= service
        main.post {
            requests.values.filter {
                it.service === service && it.state == HotReloadContract.PENDING
            }.forEach { request ->
                if (request.lifecycle.loseConnection()) markUnconfirmed(request)
                else if (request.lifecycle.phase == ReloadRequestState.Phase.RESERVED) {
                    fail(request, "框架服务已断开，尚未提交热重载")
                }
            }
        }
    }

    @Synchronized fun request(uid: Int, pid: Int, pkg: String): Bundle {
        val existing = requests.entries.firstOrNull {
            it.value.uid == uid && it.value.pid == pid && it.value.state == HotReloadContract.PENDING
        }
        if (existing != null) {
            main.post { reconcileUnconfirmed(existing.value) }
            return reply(existing.key, existing.value)
        }
        requests.entries.removeIf {
            it.value.state != HotReloadContract.PENDING &&
                SystemClock.elapsedRealtime() - it.value.started > 120_000
        }
        val id = UUID.randomUUID().toString()
        val request = Request(uid, pid, pkg)
        requests[id] = request
        main.post {
            submit(id, request)
            if (request.lifecycle.phase == ReloadRequestState.Phase.RESERVED) {
                // Service binding is event driven. One deadline replaces an 80-wakeup polling
                // loop when the framework is unavailable; onServiceBind performs the retry.
                val timeout = Runnable {
                    request.connectionTimeout = null
                    if (request.lifecycle.phase == ReloadRequestState.Phase.RESERVED) {
                        fail(request, "未连接框架服务；请确认模块已启用，阅微未被重启")
                    }
                }
                request.connectionTimeout = timeout
                main.postDelayed(timeout, 8_000L)
            }
        }
        return reply(id, request)
    }

    private fun cancelConnectionTimeout(request: Request) {
        request.connectionTimeout?.let(main::removeCallbacks)
        request.connectionTimeout = null
    }

    private fun cancelReceiptTimeout(request: Request) {
        request.receiptTimeout?.let(main::removeCallbacks)
        request.receiptTimeout = null
    }

    fun status(id: String, uid: Int, pid: Int): Bundle {
        val request = requests[id]?.takeIf { it.uid == uid && it.pid == pid }
            ?: return Bundle().apply {
                putString(HotReloadContract.STATE, HotReloadContract.FAILED)
                putString(HotReloadContract.MESSAGE, "热重载请求已失效")
            }
        main.post { reconcileUnconfirmed(request) }
        return reply(id, request)
    }

    private fun markUnconfirmed(request: Request) {
        cancelReceiptTimeout(request)
        request.message = "框架连接中断，已提交的热重载结果尚未确认；恢复连接后核对状态。"
        HotReloadNotifications.show(context, request.pkg, request.message, false)
    }

    private fun reconcileUnconfirmed(request: Request) {
        if (request.lifecycle.phase != ReloadRequestState.Phase.UNCONFIRMED) return
        // A disconnected Binder is not a negative reload receipt. Keep deduplicating until a
        // current framework target confirms it is idle; do not infer success from its version.
        val targets = runCatching {
            services.filter { it.apiVersion >= XposedService.API_102 }.flatMap { service ->
                service.runningTargets.filter {
                    it.uid == request.uid && it.pid == request.pid && it.processName == request.pkg
                }
            }
        }.getOrNull() ?: return
        val target = targets.singleOrNull() ?: return
        if (target.state != HookedTarget.State.RELOADING) {
            fail(request, "框架目标已恢复空闲，上次热重载结果未确认；可以重新发起。")
        }
    }

    private fun reply(id: String, request: Request) = Bundle().apply {
        putString(HotReloadContract.ID, id)
        putString(HotReloadContract.STATE, request.state)
        putString(HotReloadContract.MESSAGE, request.message)
    }

    private fun fail(request: Request, message: String) {
        if (!request.lifecycle.complete(false)) return
        cancelConnectionTimeout(request)
        cancelReceiptTimeout(request)
        request.message = message
        request.state = HotReloadContract.FAILED
        android.util.Log.e("ReaMicroHotReload", "pid=${request.pid} $message")
        HotReloadNotifications.show(context, request.pkg, message, true)
    }

    private fun submit(id: String, request: Request) {
        if (request.lifecycle.phase != ReloadRequestState.Phase.RESERVED) return
        if (services.isEmpty()) {
            request.message = "正在连接热重载服务"
            if (SystemClock.elapsedRealtime() - request.started >= 8_000) {
                fail(request, "未连接框架服务；请确认模块已启用，阅微未被重启")
            }
            return
        }
        runCatching {
            val available = services.filter { it.apiVersion >= XposedService.API_102 }
            check(available.isNotEmpty()) { "当前框架服务不支持 API102 热重载" }
            // PID/UID 仅用于筛选当前调用方；提交的是框架签发的目标句柄，而不是伪造身份。
            val matches = available.flatMap { service ->
                service.runningTargets.filter {
                    it.uid == request.uid && it.pid == request.pid && it.processName == request.pkg
                }.map { service to it }
            }
            check(matches.size == 1) { "无法唯一确定当前阅微的框架目标，未执行热重载" }
            val (service, target) = matches.single()
            check(target.loadedVersionCode >= FIRST_PROCESS_GUARD_VERSION) {
                "旧模块的崩溃上传拦截需要重新加载；请完全退出阅微后重新打开，以启用新版保护"
            }
            check(target.state != HookedTarget.State.RELOADING) { "框架正在重载，请稍后再试" }
            if (!request.lifecycle.submit()) return
            cancelConnectionTimeout(request)
            request.service = service
            request.message = "框架正在热重载"
            android.util.Log.i("ReaMicroHotReload", "request pid=${request.pid} state=${target.state} " +
                "framework=${service.frameworkName} ${service.frameworkVersion} api=${service.apiVersion}")
            HotReloadNotifications.show(context, request.pkg, "正在卸载旧回调并装载新模块，请稍候。", false)
            service.hotReloadModule(target, Bundle().apply {
                putBoolean(HotReloadContract.IN_PLACE, true)
                putString(HotReloadContract.ID, id)
            }) { _, result ->
                main.post {
                    when (result.status()) {
                        HotReloadResult.Status.SUCCEEDED -> {
                            if (!request.lifecycle.complete(true)) return@post
                            cancelReceiptTimeout(request)
                            request.message = "API102 原地热重载完成"
                            request.state = HotReloadContract.SUCCEEDED
                            android.util.Log.i("ReaMicroHotReload", "SUCCEEDED pid=${request.pid}")
                            HotReloadNotifications.dismiss(context)
                        }
                        HotReloadResult.Status.FAILED -> fail(request, result.message()?.let {
                            "热重载失败：${it.take(300)}"
                        } ?: "旧代际未同意退场；请等待任务结束后重试")
                        HotReloadResult.Status.UNSUPPORTED -> fail(request,
                            "框架无法提供新代际；可先更新模块再试，阅微未被重启")
                        HotReloadResult.Status.IN_PROGRESS -> fail(request, "已有热重载正在执行")
                        HotReloadResult.Status.PROCESS_DIED -> fail(request, "框架报告阅微进程已退出")
                    }
                }
            }
            val timeout = Runnable {
                request.receiptTimeout = null
                if (request.state == HotReloadContract.PENDING) {
                    // 未收到回执不能当成失败并放开重复提交，晚到结果仍归属于原请求。
                    request.message = "框架尚未回报结果，请继续等待；不会自动重启阅微。"
                    HotReloadNotifications.show(context, request.pkg, request.message, false)
                }
            }
            request.receiptTimeout = timeout
            main.postDelayed(timeout, 90_000)
        }.onFailure {
            if (it is UnsupportedOperationException || it is SecurityException ||
                it is IllegalArgumentException) {
                // Local capability/permission/argument rejection means no handoff occurred.
                fail(request, it.message ?: "框架拒绝了热重载请求")
            } else if (request.lifecycle.loseConnection()) {
                markUnconfirmed(request)
                reconcileUnconfirmed(request)
            } else {
                fail(request, it.message ?: "热重载请求失败，阅微未被重启")
            }
        }
    }
}
