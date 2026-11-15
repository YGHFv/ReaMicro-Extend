package com.reamicro.fix.hook

import android.app.Activity
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.cloud.webdav.NativeHostHttp
import com.reamicro.fix.cloud.webdav.CleartextScope
import com.reamicro.fix.cloud.webdav.WebDavSyncProtocol
import com.reamicro.fix.cloud.webdav.WebDavSyncTransport
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostSyncBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.lang.reflect.Executable
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/** 动态绑定宿主同步存储契约，沿用原生书架清单与合并流程。 */
internal class WebDavSyncHook(
    private val loader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
    private val drive: WebDavDriveHook,
) {
    private val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private val worker = com.reamicro.fix.reload.ModuleResources.pool(1) { Thread(it, "ReaMicroWebDavSync").apply { isDaemon = true } }
    private val configuring = AtomicBoolean()
    private val handles = arrayListOf<XposedInterface.HookHandle>()
    private val nativeRoutes = HostWebDavConfigurationRoute(loader, activityProvider)
    private val configurationUi by lazy { HostWebDavConfigurationUi(loader) }
    private val http by lazy {
        NativeHostHttp(loader, object : CleartextScope {
            override fun <T> run(block: () -> T): T = drive.withWebDavCleartextAllowed(block)
        })
    }
    private val contract by lazy { requireNotNull(HostSyncBindings.current) { "Sync contract unavailable" } }
    private val nativeUi by lazy { requireNotNull(HostUiBindings.current) { "Native sync UI unavailable" } }
    private val configType get() = contract.type("config")
    private val unit by lazy { drive.targetUnit() }

    private fun hook(member: Executable, callback: HookCallback) {
        handles += HookRuntime.hookMethod(member, callback)
    }

    fun install() {
        check(handles.isEmpty()) { "WebDAV sync hooks already installed" }
        check(nativeUi.composerClass == contract.type("composer") &&
            nativeUi.type("function2") == contract.type("function2") &&
            nativeUi.method("clickable").parameterTypes[4] == contract.type("function0")) {
            "WebDAV sync and native UI contracts disagree"
        }
        try {
            configurationUi.install(::hook)
            hookTransport()
            hookLocalConfigSave()
            // 关闭扩展后暂停标记配置，不能落回 AWS 签名并把 WebDAV 密码发给别的服务。
            hook(contract.method("isConfigured"), object : HookCallback() {
                override fun before(param: Call) {
                    if (marked(param.thisObject) && !settingsProvider().canRunWebDavSync) param.result = false
                }
            })
            HookRuntime.deoptimize(contract.method("isConfigured"))
            nativeRoutes.install(::hook)
            check(HookInstallReport.snapshot().any {
                it.feature == "WebDavSyncHook" && it.name == "nativeConfigurationRoute" && it.ok
            }) { "Native WebDAV configuration route did not install" }
            hookEntry()
            HookRuntime.logAlways("ReaMicro WebDAV shelf sync installed: native manifest/merge/transport")
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            handles.clear()
            throw error
        }
    }

    private fun hookEntry() {
        val other = contract.method("otherProvider")
        hook(other, object : HookCallback() {
            override fun before(param: Call) {
                if (configurationUi.renderingEntry) return
                val composer = param.args.getOrNull(1)?.takeIf(nativeUi.composerClass::isInstance) ?: return
                // 总是保持该组的位置；开关只改变组内容，避免后续原生槽位错位。
                nativeUi.instance("startGroup", composer, 0x524D5753)
                try {
                    if (settingsProvider().canRunWebDavSync) {
                        renderEntry(composer)
                        configurationUi.entryGap(composer)
                    }
                } finally {
                    nativeUi.instance("endGroup", composer, false)
                }
            }
        })
        HookRuntime.deoptimize(other)
        HookRuntime.deoptimize(contract.method("providerChoices"))
    }

    private fun renderEntry(composer: Any) {
        val click = nativeUi.callback(contract.type("function0")) { showConfiguration() }
        configurationUi.renderEntry(click, composer)
    }

    private fun marked(config: Any?): Boolean = configType.isInstance(config) &&
        contract.config(requireNotNull(config), "bucket") == WebDavSyncProtocol.BUCKET

    private fun transport(config: Any): WebDavSyncTransport {
        val username = contract.config(config, "accessKey") as String
        val password = contract.config(config, "secretKey") as String
        val url = contract.config(config, "endpoint") as String
        drive.rememberCleartextHost(url)
        return WebDavSyncTransport(http, url,
            if (username == WebDavSyncProtocol.ANONYMOUS) "" else username,
            if (password == WebDavSyncProtocol.ANONYMOUS) "" else password,
        ) { code ->
            throw contract.constructor("httpError")
                .newInstance(code, "WebDAV 同步请求失败：HTTP $code") as Throwable
        }
    }

    private fun suspendOperation(param: HookCallback.Call, operation: () -> Any?) {
        try {
            val continuation = requireNotNull(param.args.last())
            val runtime = requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.coroutines)
            val resume = runtime.method("resumeWith")
            val failure = runtime.constructor("failure")
            val suspended = runtime.suspended
            check(resume.declaringClass.isInstance(continuation)) { "Invalid native sync continuation" }
            worker.execute {
                val result = runCatching {
                    HostCoroutines.runBlocking(loader) { operation() }
                }.getOrElse { error -> failure.newInstance(error) }
                runCatching { resume.invoke(continuation, result) }
                    .onFailure { HookRuntime.logError("ReaMicro WebDAV sync continuation failed", it) }
            }
            param.result = suspended
        } catch (error: Throwable) {
            // Adapter logs thrown before-hook errors and runs the original; retain an explicit failure here.
            param.throwable = error
        }
    }

    private fun hookLocalConfigSave() {
        // 原生 S3 编辑页面也可能保存当前配置，不能把标记的 WebDAV 凭据上传到账号。
        val save = contract.method("saveLocal")
        hook(save, object : HookCallback() {
            override fun before(param: Call) {
                val config = param.args[1] ?: return
                if (!marked(config)) return
                val identity = AccountHostRuntime.storageSnapshot(activityProvider())
                if (identity == null) {
                    param.throwable = IllegalStateException("账号状态已变化，未保存同步配置")
                    return
                }
                suspendOperation(param) {
                    val current = requireNotNull(contract.field("saveSession").get(requireNotNull(param.thisObject)))
                    check(current === identity.session && AccountHostRuntime.isCurrent(identity, activityProvider())) {
                        "账号状态已变化，未保存同步配置"
                    }
                    check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                    val names = mapOf("provider" to "provider", "endpoint" to "endpoint", "bucket" to "bucket",
                        "region" to "region", "access_key" to "accessKey", "secret_key" to "secretKey", "prefix" to "prefix")
                    val values = names.map { (key, property) ->
                        val value = contract.config(config, property)
                        "s3_sync_$key" to if (value is Enum<*>) value.name else value
                    }.toMap() + ("s3_sync_cloud_config_enabled" to false)
                    saveValues(current, values, identity)
                    unit
                }
            }
        })
        HookRuntime.deoptimize(save)
    }

    private fun hookTransport() {
        HostSyncBindings.OPERATIONS.forEach { operation ->
            val implementation = contract.method(operation)
            hook(implementation, object : HookCallback() {
                override fun before(param: Call) {
                    val target = param.thisObject ?: return
                    val config = contract.field("transportConfig").get(target) ?: return
                    if (!marked(config)) return
                    val args = param.args.clone()
                    // 使用宿主协程恢复协议，不在主线程执行同步网络请求。
                    suspendOperation(param) {
                        check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
                        execute(operation, transport(config), args)
                    }
                }
            })
            HookRuntime.deoptimize(implementation)
        }
    }

    private fun execute(name: String, remote: WebDavSyncTransport, args: Array<Any?>): Any? {
        val key = args[0] as String
        return when (name) {
            "getContent" -> remote.get(key)?.let { response ->
                contract.constructor("content")
                    .newInstance(response.headers["ETag"], requireNotNull(response.bodyBytes))
            }
            "head" -> remote.head(key)?.let { response ->
                val size = response.headers["Content-Length"]?.toLongOrNull() ?: error("WebDAV 缺少对象大小")
                contract.constructor("head")
                    .newInstance(size, response.headers["ETag"])
            }
            "getBytes" -> remote.get(key)?.bodyBytes ?: throw contract.constructor("httpError")
                .newInstance(404, "WebDAV 同步对象不存在") as Throwable
            "delete" -> { remote.delete(key); unit }
            "putBytes", "putProgress" -> {
                val bytes = args[1] as ByteArray
                remote.put(key, bytes, null, args[2] as String, args[3] as? String)
                if (name == "putProgress") progress(args[4], bytes.size.toLong())
                unit
            }
            "putFile" -> {
                val file = contract.method("toFile").invoke(args[1]) as File
                remote.put(key, null, file, args[2] as String, args[3] as? String)
                progress(args[4], file.length())
                unit
            }
            "getFile" -> {
                val file = contract.method("toFile").invoke(args[1]) as File
                remote.download(key, file)
                progress(args[2], file.length())
                unit
            }
            else -> error("未验证的 WebDAV 同步操作：$name")
        }
    }

    private fun progress(callback: Any?, size: Long) {
        if (callback == null) return
        val invoke = contract.method("progressInvoke")
        // Function3 是 suspend (copied, total, continuation)，第三参不是进度值。
        HostCoroutines.runBlocking(loader) { continuation ->
            invoke.invoke(callback, size, size, continuation)
        }
    }

    private fun preferences(session: Any): Any {
        val store = requireNotNull(contract.field("datastore").get(session))
        val flow = contract.method("getData").invoke(store)
        return requireNotNull(HostCoroutines.invokeTimed(loader,
            contract.method("first"), null, 15_000L, flow))
    }

    private fun nativeValues(session: Any): Map<String, Any?> {
        val prefs = preferences(session)
        return KEYS.associateWith { key ->
            val k = contract.preferenceKey(key)
            contract.method("preferenceGet").invoke(prefs, k)
        }
    }

    private fun saveValues(session: Any, values: Map<String, Any?>, identity: AccountHostRuntime.Identity, beforeWrite: () -> Unit = {}) {
        val change = WebDavPreferencesEdit.create(loader, contract.method("editInvoke"), beforeWrite) { prefs ->
            check(session === identity.session && AccountHostRuntime.isCurrent(identity, activityProvider()) &&
                (contract.method("preferenceGet").invoke(prefs, contract.preferenceKey("token")) as? String).orEmpty() == identity.token) {
                "账号状态已变化，未保存同步配置"
            }
            val scopeChanged = listOf("provider", "endpoint", "bucket", "prefix").any { name ->
                val keyName = "s3_sync_$name"
                val key = contract.preferenceKey(keyName)
                values.containsKey(keyName) && contract.method("preferenceGet").invoke(prefs, key) != values[keyName]
            }
            // 与原生 Session 保存一致：切换存储根后移除旧基线，避免按旧远端状态误判删除。
            if (scopeChanged) {
                for (name in listOf("s3_sync_baseline_cbor", "s3_sync_pending_deletions")) {
                    if (!values.containsKey(name)) contract.method("preferenceRemove").invoke(prefs, contract.preferenceKey(name))
                }
            }
            values.forEach { (name, value) ->
                val key = contract.preferenceKey(name)
                if (value == null) contract.method("preferenceRemove").invoke(prefs, key)
                else contract.method("preferenceSet").invoke(prefs, key, value)
            }
            prefs
        }
        HostCoroutines.invokeTimed(loader, contract.method("edit"), null, 15_000L,
            requireNotNull(contract.field("datastore").get(session)), change)
        check(AccountHostRuntime.isCurrent(identity, activityProvider())) { "账号已切换，同步配置验证已停止" }
        val actual = nativeValues(session)
        check(AccountHostRuntime.isCurrent(identity, activityProvider())) { "账号已切换，同步配置验证已停止" }
        check(values.all {
            val read = actual[it.key]
            if (read is ByteArray && it.value is ByteArray) read.contentEquals(it.value as ByteArray) else read == it.value
        }) { "WebDAV 同步配置回读不一致" }
    }

    private fun showConfiguration() {
        val activity = activityProvider() ?: return
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return toast("同步账号状态尚未就绪")
        val current = identity.session
        if (!configuring.compareAndSet(false, true)) return
        worker.execute {
            runCatching { nativeValues(current) }.onSuccess { values ->
                main.post {
                    if (activity.isFinishing || activity.isDestroyed || !AccountHostRuntime.isCurrent(identity, activity) ||
                        !settingsProvider().canRunWebDavSync) {
                        configuring.set(false)
                    } else {
                        runCatching { renderConfiguration(activity, identity, values) }
                            .onFailure { configuring.set(false); toast("打开同步设置失败") }
                    }
                }
            }.onFailure { configuring.set(false); toast("读取同步设置失败") }
        }
    }

    private fun renderConfiguration(activity: Activity, identity: AccountHostRuntime.Identity, values: Map<String, Any?>) {
        val current = identity.session
        val connected = values["s3_sync_bucket"] == WebDavSyncProtocol.BUCKET
        fun stored(key: String): String = if (connected) (values[key] as? String).orEmpty()
            .takeUnless { it == WebDavSyncProtocol.ANONYMOUS }.orEmpty() else ""
        val initial = WebDavSyncConfigurationPage.Credentials(stored("s3_sync_endpoint"),
            stored("s3_sync_access_key"), stored("s3_sync_secret_key"))
        val busy = AtomicBoolean()
        val dismissed = AtomicBoolean()
        lateinit var page: WebDavSyncConfigurationPage
        fun valid() {
            check(!dismissed.get()) { "配置已取消，未保存" }
            check(AccountHostRuntime.isCurrent(identity, activity)) { "账号已切换，未保存" }
            check(settingsProvider().canRunWebDavSync) { "同步扩展已关闭" }
        }
        fun submit(draft: WebDavSyncConfigurationPage.Credentials) {
            if (busy.get() || dismissed.get()) return
            val url = runCatching { valid(); WebDavSyncProtocol.baseUrl(draft.address) }
                .getOrElse { page.error(it.message ?: "WebDAV 地址无效"); return }
            if (draft.username.any { it == ':' || it == '\n' || it == '\r' }) {
                page.error("用户名不能包含冒号或换行")
                return
            }
            if (!busy.compareAndSet(false, true)) return
            val next = mapOf(
                "s3_sync_provider" to "S3", "s3_sync_endpoint" to url,
                "s3_sync_bucket" to WebDavSyncProtocol.BUCKET, "s3_sync_region" to "auto",
                "s3_sync_access_key" to draft.username.ifEmpty { WebDavSyncProtocol.ANONYMOUS },
                "s3_sync_secret_key" to draft.password.ifEmpty { WebDavSyncProtocol.ANONYMOUS },
                "s3_sync_prefix" to "reamicro", "s3_sync_cloud_config_enabled" to false,
            )
            page.verifying()
            worker.execute {
                val writeStarted = AtomicBoolean()
                val result = runCatching {
                    valid()
                    val config = contract.newConfig(url, WebDavSyncProtocol.BUCKET, "auto",
                        requireNotNull(next["s3_sync_access_key"]) as String,
                        requireNotNull(next["s3_sync_secret_key"]) as String, "reamicro")
                    transport(config).verify()
                    valid()
                    try {
                        saveValues(current, next, identity) { valid(); writeStarted.set(true) }
                    } catch (error: Throwable) {
                        if (writeStarted.get()) {
                            runCatching { valid(); saveValues(current, values, identity) { valid() } }.onFailure(error::addSuppressed)
                        }
                        throw error
                    }
                }
                main.post {
                    busy.set(false)
                    if (!dismissed.get() && AccountHostRuntime.isCurrent(identity, activity)) {
                        result.onSuccess {
                            page.dismiss()
                            toast("WebDAV 已启用，返回书架即可使用原生同步")
                        }.onFailure { page.error(it.message ?: "WebDAV 验证失败，原配置未修改") }
                    }
                }
            }
        }
        page = WebDavSyncConfigurationPage(activity, initial, ::submit, {
            dismissed.set(true)
            configuring.set(false)
        }, configurationUi, {
            !dismissed.get() && AccountHostRuntime.isCurrent(identity, activity) && settingsProvider().canRunWebDavSync
        })
        if (!nativeRoutes.open(page)) {
            page.closeEmbedded()
            toast("同步页面尚未就绪，请重新进入同步设置")
        }
    }

    private fun toast(message: String) {
        main.post { activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } }
    }

    private companion object {
        val KEYS = listOf("s3_sync_provider", "s3_sync_endpoint", "s3_sync_bucket", "s3_sync_region",
            "s3_sync_access_key", "s3_sync_secret_key", "s3_sync_prefix", "s3_sync_cloud_config_enabled",
            "s3_sync_baseline_cbor", "s3_sync_pending_deletions")
    }
}
