package com.reamicro.fix.hook

import android.app.Activity
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.cloud.webdav.NativeHostHttp
import com.reamicro.fix.cloud.webdav.CleartextScope
import com.reamicro.fix.cloud.webdav.WebDavSyncProtocol
import com.reamicro.fix.cloud.webdav.WebDavSyncTransport
import com.reamicro.fix.cloud.webdav.ShelfSyncTransport
import com.reamicro.fix.cloud.webdav.ShelfSyncRetention
import com.reamicro.fix.cloud.webdav.ModuleConfigSyncSettings
import com.reamicro.fix.cloud.webdav.ModuleConfigStore
import com.reamicro.fix.cloud.webdav.ModuleConfigRemote
import com.reamicro.fix.cloud.webdav.OnlineBookSyncMarker
import com.reamicro.fix.core.HostContinuationObserver
import com.reamicro.fix.cloud.github.GitHubSyncProtocol
import com.reamicro.fix.cloud.github.GitHubSyncTransport
import com.reamicro.fix.cloud.github.GitHubTokenCipher
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
    private val moduleConfigBusy = AtomicBoolean()
    @Volatile private var retiredState: ShelfSyncRetiredState? = null
    private var retentionScope: List<Any?>? = null
    private val baselineRevisionOwners = arrayListOf<Pair<java.lang.ref.WeakReference<Any>, String>>()
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
            hookRetentionGuard()
            hookTransport()
            hookOnlineSyncMarkers()
            hookLocalConfigSave()
            // 关闭扩展后暂停标记配置，不能落回 AWS 签名并把 WebDAV 密码发给别的服务。
            hook(contract.method("isConfigured"), object : HookCallback() {
                override fun before(param: Call) {
                    val config = param.thisObject
                    if (!marked(config)) return
                    if (!enabled(requireNotNull(config))) param.result = false
                    // GitHub 默认分支允许留空，不能沿用原生 S3 对 Access Key 的非空检查。
                    else if (contract.config(requireNotNull(config), "bucket") == GitHubSyncProtocol.BUCKET)
                        param.result = (contract.config(config, "endpoint") as String).isNotBlank() &&
                            (contract.config(config, "secretKey") as String).isNotBlank()
                }
            })
            HookRuntime.deoptimize(contract.method("isConfigured"))
            nativeRoutes.install(::hook)
            check(HookInstallReport.snapshot().any {
                it.feature == "WebDavSyncHook" && it.name == "nativeConfigurationRoute" && it.ok
            }) { "Native WebDAV configuration route did not install" }
            hookEntry()
            val configAction: (Boolean) -> Unit = ::requestModuleConfiguration
            ModuleConfigSyncActions.request = configAction
            com.reamicro.fix.reload.ModuleResources.cleanup {
                if (ModuleConfigSyncActions.request === configAction) ModuleConfigSyncActions.request = null
            }
            val sourceObserver = com.reamicro.fix.online.OnlineSourceStore.observeChanges {
                worker.execute {
                    val activity = activityProvider() ?: return@execute
                    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return@execute
                    val root = File(activity.filesDir, "${identity.accountId}/books").canonicalFile
                    root.listFiles().orEmpty().filter(File::isDirectory).forEach { book ->
                        runCatching {
                            if (book.canonicalFile.parentFile == root && OnlineBookSyncMarker.uuid(book) == book.name)
                                drive.refreshReceivedOnlineSyncBook(book.name, identity)
                        }.onFailure { HookRuntime.logAlways("在线同步标记等待书源就绪后重试") }
                    }
                }
            }
            com.reamicro.fix.reload.ModuleResources.cleanup { sourceObserver.close() }
            HookRuntime.logAlways("ReaMicro WebDAV/GitHub shelf sync installed: native manifest/merge/transport")
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
                    if (enabled(false)) {
                        renderEntry(composer)
                        configurationUi.entryGap(composer)
                    }
                    if (enabled(true)) {
                        renderEntry(composer, true)
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

    private fun renderEntry(composer: Any, github: Boolean = false) {
        val click = nativeUi.callback(contract.type("function0")) { showConfiguration(github) }
        configurationUi.renderEntry(click, composer, if (github) "GitHub" else "WebDAV")
    }

    private fun marked(config: Any?): Boolean = configType.isInstance(config) &&
        contract.config(requireNotNull(config), "bucket") in setOf(WebDavSyncProtocol.BUCKET, GitHubSyncProtocol.BUCKET)

    private fun enabled(github: Boolean): Boolean =
        if (github) settingsProvider().canRunGitHubSync else settingsProvider().canRunWebDavSync

    private fun enabled(config: Any): Boolean =
        enabled(contract.config(config, "bucket") == GitHubSyncProtocol.BUCKET)

    private fun hookRetentionGuard() {
        val equality = contract.method("retentionEquals")
        val planner = contract.method("retentionPlanner")
        val comparison = contract.method("retentionComparison")
        repeat(contract.number("retentionBaselineConstructors")) { index ->
            hook(contract.constructor("retentionBaselineBook$index"), object : HookCallback() {
                override fun after(param: Call) {
                    val book = param.thisObject ?: return
                    val revision = contract.field("retentionBaselineBook.remoteRevision").get(book) ?: return
                    val uuid = contract.field("retentionBaselineBook.uuid").get(book) as String
                    synchronized(baselineRevisionOwners) {
                        baselineRevisionOwners.removeAll { it.first.get() == null }
                        baselineRevisionOwners += java.lang.ref.WeakReference(revision) to uuid
                    }
                }
            })
            HookRuntime.deoptimize(contract.constructor("retentionBaselineBook$index"))
        }
        hook(equality, object : HookCallback() {
            override fun before(param: Call) {
                if (param.args[0] != null) return
                val state = retiredState ?: return
                if (!AccountHostRuntime.isCurrent(state.identity, activityProvider())) return
                val value = param.thisObject ?: return
                val uuid = synchronized(baselineRevisionOwners) {
                    baselineRevisionOwners.lastOrNull { it.first.get() === value }?.second
                } ?: return
                val revision = Triple(contract.field("retentionRevision.timestamp").getLong(value),
                    contract.field("retentionRevision.counter").getLong(value),
                    contract.field("retentionRevision.deviceId").get(value) as String)
                if (revision !in state.revisions[uuid].orEmpty()) return
                // 只放行规划器的基线/缺失比较；不干预远端发布时的修订冲突校验。
                val frames = Thread.currentThread().stackTrace
                val comparisonIndex = frames.indexOfFirst {
                    it.className == comparison.declaringClass.name && it.methodName == comparison.name
                }
                val caller = frames.getOrNull(comparisonIndex + 1)
                if (comparisonIndex >= 0 && caller?.className == planner.declaringClass.name &&
                    caller.methodName == planner.name) param.result = true
            }
        })
        HookRuntime.deoptimize(equality)
        HookRuntime.deoptimize(comparison)
        HookRuntime.deoptimize(planner)
    }

    private fun retentionTransport(config: Any): ShelfSyncTransport {
        val snapshot = settingsProvider()
        val identity = AccountHostRuntime.storageSnapshot(activityProvider())
            ?: error("同步账号状态尚未就绪")
        val scope = listOf(identity.accountId) + listOf("bucket", "endpoint", "prefix", "accessKey").map {
            contract.config(config, it)
        }
        if (retentionScope != scope) {
            retiredState = null
            retentionScope = scope
        }
        val remote = transport(config)
        return ShelfSyncRetention(remote, if (snapshot.syncBookLimitEnabled) snapshot.syncBookLimit else null) { records ->
            check(AccountHostRuntime.isCurrent(identity, activityProvider())) { "同步账号状态已变化" }
            retiredState = ShelfSyncRetiredState(identity, records.groupBy(ShelfSyncRetention::uuid)
                .mapValues { (_, books) -> books.map(ShelfSyncRetention::revision).toSet() })
        }
    }

    private fun transport(config: Any): ShelfSyncTransport {
        if (contract.config(config, "bucket") == GitHubSyncProtocol.BUCKET) {
            val token = GitHubSyncProtocol.token(GitHubTokenCipher.decrypt(contract.config(config, "secretKey") as String))
            return GitHubSyncTransport(contract.config(config, "endpoint") as String,
                contract.config(config, "accessKey") as String, { url, method, body ->
                    http.request(url, method, mapOf("Authorization" to "Bearer $token",
                        "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
                        body?.let { http.stringBody(it.toString(), "application/json") })
                }) { code ->
                val message = when (code) {
                    401 -> "GitHub Token 无效或已过期，请重新配置"
                    403 -> "GitHub 权限不足或请求受限，请检查 Token 权限后稍后重试"
                    404 -> "GitHub 仓库或同步对象不存在，请检查仓库及 Token 权限"
                    412 -> "GitHub 同步版本已变化，请重试同步"
                    429 -> "GitHub 请求受限，请稍后重试"
                    else -> "GitHub 同步请求失败：HTTP $code"
                }
                throw contract.constructor("httpError").newInstance(code, message) as Throwable
            }
        }
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
                    check(enabled(config)) { "此同步开关已关闭" }
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
                    if (!marked(config)) {
                        retiredState = null
                        retentionScope = null
                        return
                    }
                    val args = param.args.clone()
                    // 使用宿主协程恢复协议，不在主线程执行同步网络请求。
                    suspendOperation(param) {
                        check(enabled(config)) { "此同步开关已关闭" }
                        if ((args[0] as? String)?.endsWith("/manifest.cbor") == true &&
                            operation in listOf("getContent", "head", "putBytes", "putProgress")) {
                            prepareOnlineSyncPolicy(config)
                            backupModuleConfiguration(config)
                        }
                        execute(operation, retentionTransport(config), args)
                    }
                }
            })
            HookRuntime.deoptimize(implementation)
        }
    }

    private fun prepareOnlineSyncPolicy(config: Any) {
        val activity = activityProvider() ?: return
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return
        val preferences = activity.getSharedPreferences(com.reamicro.fix.cloud.webdav.ModuleConfigPolicy.PREFS, 0)
        val state = ModuleConfigSyncSettings(activity)
        val skip = state.skipOnlineBooks
        val progressOnly = skip && state.onlineProgressOnly
        val key = "online_policy_" + com.reamicro.fix.cloud.webdav.ModuleConfigCrypto.digest(
            (listOf(identity.accountId) + listOf("bucket", "endpoint", "prefix", "accessKey").map {
                contract.config(config, it)
            }).joinToString("|").toByteArray(Charsets.UTF_8))
        val changed = preferences.getBoolean(key, false) != skip ||
            preferences.getBoolean("${key}_progress", false) != progressOnly
        val root = File(activity.filesDir, "${identity.accountId}/books").canonicalFile
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { book ->
            check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号状态已变化" }
            if (book.canonicalFile.parentFile == root &&
                ((changed && OnlineBookSyncMarker.metadata(book) != null) || File(book, ONLINE_EPUB_SYNC_PENDING).isFile))
                drive.markOnlineEpubChanged(book.name, identity)
        }
        AccountHostRuntime.withCurrentStorage(identity, activity) {
            check(preferences.edit().putBoolean(key, skip).putBoolean("${key}_progress", progressOnly).commit())
        }
    }

    private fun hookOnlineSyncMarkers() {
        val pack = contract.method("onlinePack")
        hook(pack, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable != null) return
                val activity = activityProvider() ?: return
                if (!ModuleConfigSyncSettings(activity).skipOnlineBooks) return
                val identity = AccountHostRuntime.storageSnapshot(activity) ?: return
                val root = (contract.method("toFile").invoke(param.args[0]) as File).canonicalFile
                val output = contract.method("toFile").invoke(param.args[1]) as File
                if (root.parentFile != File(activity.filesDir, "${identity.accountId}/books").canonicalFile ||
                    output.name != "s3-sync-${root.name}.epub") return
                try {
                    val bucket = nativeValues(identity.session)["s3_sync_bucket"]
                    if (bucket !in setOf(WebDavSyncProtocol.BUCKET, GitHubSyncProtocol.BUCKET)) return
                    check(AccountHostRuntime.isCurrentStorage(identity, activity))
                    OnlineBookSyncMarker.write(root, root.name, output, ModuleConfigSyncSettings(activity).onlineProgressOnly)
                } catch (error: Throwable) { param.throwable = error }
            }
        })
        HookRuntime.deoptimize(pack)
        val unpack = contract.method("onlineUnpack")
        hook(unpack, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable != null) return
                val activity = activityProvider() ?: return
                val identity = AccountHostRuntime.storageSnapshot(activity) ?: return
                val archive = contract.method("toFile").invoke(param.args[0]) as File
                if (!archive.name.startsWith("s3-restore-")) return
                val incoming = (contract.method("toFile").invoke(param.args[1]) as File).canonicalFile
                try {
                    val uuid = OnlineBookSyncMarker.uuid(incoming) ?: return
                    check(incoming.name == uuid && AccountHostRuntime.isCurrentStorage(identity, activity))
                    val local = File(activity.filesDir, "${identity.accountId}/books/$uuid").canonicalFile
                    if (local.isDirectory) OnlineBookSyncMarker.mergeLocalContent(incoming, local, uuid)
                } catch (error: Throwable) { param.throwable = error }
            }
        })
        HookRuntime.deoptimize(unpack)
        val restore = contract.method("onlineRestore")
        val observationKey = "reamicro.sync.online.restore"
        hook(restore, object : HookCallback() {
            override fun before(param: Call) {
                val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return
                if ((param.args[contract.number("onlineRestore.uid")] as? Long) != identity.accountId) return
                val book = param.args[contract.number("onlineRestore.book")] ?: return
                val metadata = contract.field("onlineSyncBook.metadata").get(book) ?: return
                val uuid = contract.field("onlineMetadata.uuid").get(metadata) as String
                val once = AtomicBoolean()
                val completed: () -> Unit = {
                    if (once.compareAndSet(false, true)) worker.execute {
                        runCatching { drive.refreshReceivedOnlineSyncBook(uuid, identity) }
                            .onFailure { HookRuntime.logAlways("在线图书标记已保留，书源更新待重试") }
                    }
                }
                param.setObjectExtra(observationKey, completed)
                param.args[param.args.lastIndex] = HostContinuationObserver.observe(loader,
                    requireNotNull(param.args.last()), successOnly = true, completed = completed)
            }
            override fun after(param: Call) {
                val suspended = requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.coroutines).suspended
                if (param.throwable == null && param.result !== suspended) {
                    @Suppress("UNCHECKED_CAST")
                    (param.getObjectExtra(observationKey) as? (() -> Unit))?.invoke()
                }
            }
        })
        HookRuntime.deoptimize(restore)
    }

    private fun backupModuleConfiguration(config: Any) {
        val activity = activityProvider() ?: return
        val state = ModuleConfigSyncSettings(activity.applicationContext)
        if (!state.enabled) return
        val identity = AccountHostRuntime.snapshot(activity) ?: return
        runCatching {
            val selected = state.scopes
            val plain = ModuleConfigStore(activity.applicationContext).create(identity.accountId, selected)
            check(AccountHostRuntime.isCurrent(identity, activity) && state.enabled && state.scopes == selected)
            val remote = guardedConfigTransport(config, identity)
            ModuleConfigRemote(remote, contract.config(config, "prefix") as String, identity.accountId).upload(plain)
        }.onFailure {
            HookRuntime.logAlways("模块配置备份未完成，书架同步继续")
            toast("模块配置备份未完成：${it.message ?: "请手动重试"}")
        }
    }

    private fun guardedConfigTransport(config: Any, identity: AccountHostRuntime.Identity): ShelfSyncTransport {
        val remote = transport(config)
        return object : ShelfSyncTransport by remote {
            private fun verifyCurrent() {
                check(AccountHostRuntime.isCurrent(identity, activityProvider()) && enabled(config)) { "账号或同步状态已变化" }
            }
            override fun get(key: String): com.reamicro.fix.cloud.webdav.WebDavHttpResponse? {
                verifyCurrent()
                return remote.get(key).also { verifyCurrent() }
            }
            override fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
                verifyCurrent()
                remote.put(key, bytes, file, mime, condition)
                verifyCurrent()
            }
        }
    }

    private fun requestModuleConfiguration(restore: Boolean) {
        val activity = activityProvider() ?: return
        val identity = AccountHostRuntime.snapshot(activity) ?: return toast("请先登录阅微账号")
        val state = ModuleConfigSyncSettings(activity.applicationContext)
        if (!state.enabled) return toast("请先开启同步模块配置")
        if (restore && state.backupOnly) return toast("仅备份已开启，不下发模块配置")
        if (!moduleConfigBusy.compareAndSet(false, true)) return toast("模块配置操作正在进行")
        val scopes = state.scopes
        toast(if (restore) "正在手动获取模块配置" else "正在备份模块配置")
        worker.execute {
            val result = runCatching {
                fun checkCurrent() {
                    check(AccountHostRuntime.isCurrent(identity, activity) && state.enabled &&
                        state.scopes == scopes && (!restore || !state.backupOnly)) { "账号或同步配置已变化，操作已停止" }
                }
                checkCurrent()
                val values = nativeValues(identity.session)
                val config = contract.newConfig(
                    values["s3_sync_endpoint"] as? String ?: error("请先配置 WebDAV 或 GitHub 同步"),
                    values["s3_sync_bucket"] as? String ?: "",
                    values["s3_sync_region"] as? String ?: "auto",
                    values["s3_sync_access_key"] as? String ?: "",
                    values["s3_sync_secret_key"] as? String ?: "",
                    values["s3_sync_prefix"] as? String ?: "reamicro",
                )
                check(marked(config) && enabled(config)) { "请先配置并开启 WebDAV 或 GitHub 同步" }
                val local = ModuleConfigStore(activity.applicationContext)
                val before = if (restore) local.create(identity.accountId, scopes) else null
                val remote = ModuleConfigRemote(guardedConfigTransport(config, identity),
                    contract.config(config, "prefix") as String, identity.accountId)
                if (restore) {
                    val plain = remote.download()
                    checkCurrent()
                    check(local.create(identity.accountId, scopes).contentEquals(before)) { "获取期间本机配置已修改，未覆盖，请重新获取" }
                    val count = local.restore(identity.accountId, scopes, plain) { action ->
                        AccountHostRuntime.withCurrentStorage(identity, activity) { checkCurrent(); action() }
                    }
                    "已手动应用 $count 项模块配置"
                } else {
                    val plain = local.create(identity.accountId, scopes)
                    checkCurrent()
                    if (remote.upload(plain)) "模块配置已加密备份" else "模块配置备份已是最新"
                }
            }
            moduleConfigBusy.set(false)
            if (AccountHostRuntime.isCurrent(identity, activity)) {
                result.onSuccess { toast(it) }.onFailure { toast(it.message ?: "模块配置操作失败，未应用") }
            }
        }
    }

    private fun execute(name: String, remote: ShelfSyncTransport, args: Array<Any?>): Any? {
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
            val scopeKeys = listOf("provider", "endpoint", "bucket", "prefix") +
                if (values["s3_sync_bucket"] == GitHubSyncProtocol.BUCKET) listOf("access_key") else emptyList()
            val scopeChanged = scopeKeys.any { name ->
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

    private fun showConfiguration(github: Boolean = false) {
        val activity = activityProvider() ?: return
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return toast("同步账号状态尚未就绪")
        val current = identity.session
        if (!configuring.compareAndSet(false, true)) return
        worker.execute {
            runCatching { nativeValues(current) }.onSuccess { values ->
                main.post {
                    if (activity.isFinishing || activity.isDestroyed || !AccountHostRuntime.isCurrent(identity, activity) ||
                        !enabled(github)) {
                        configuring.set(false)
                    } else {
                        runCatching { renderConfiguration(activity, identity, values, github) }
                            .onFailure { configuring.set(false); toast("打开同步设置失败") }
                    }
                }
            }.onFailure { configuring.set(false); toast("读取同步设置失败") }
        }
    }

    private fun renderConfiguration(activity: Activity, identity: AccountHostRuntime.Identity, values: Map<String, Any?>, github: Boolean) {
        val current = identity.session
        val providerName = if (github) "GitHub" else "WebDAV"
        val bucket = if (github) GitHubSyncProtocol.BUCKET else WebDavSyncProtocol.BUCKET
        val connected = values["s3_sync_bucket"] == bucket
        fun stored(key: String): String = if (connected) (values[key] as? String).orEmpty()
            .takeUnless { it == WebDavSyncProtocol.ANONYMOUS }.orEmpty() else ""
        val initial = WebDavSyncConfigurationPage.Credentials(stored("s3_sync_endpoint"),
            stored("s3_sync_access_key"), if (github && connected)
                runCatching { GitHubTokenCipher.decrypt(stored("s3_sync_secret_key")) }.getOrDefault("")
            else stored("s3_sync_secret_key"))
        val busy = AtomicBoolean()
        val dismissed = AtomicBoolean()
        lateinit var page: WebDavSyncConfigurationPage
        fun valid() {
            check(!dismissed.get()) { "配置已取消，未保存" }
            check(AccountHostRuntime.isCurrent(identity, activity)) { "账号已切换，未保存" }
            check(enabled(github)) { "此同步开关已关闭" }
        }
        fun submit(draft: WebDavSyncConfigurationPage.Credentials) {
            if (busy.get() || dismissed.get()) return
            val url = runCatching {
                valid()
                if (github) {
                    GitHubSyncProtocol.branch(draft.username)
                    GitHubSyncProtocol.token(draft.password)
                    GitHubSyncProtocol.repository(draft.address)
                } else WebDavSyncProtocol.baseUrl(draft.address)
            }.getOrElse { page.error(it.message ?: "$providerName 配置无效"); return }
            if (!github && draft.username.any { it == ':' || it == '\n' || it == '\r' }) {
                page.error("用户名不能包含冒号或换行")
                return
            }
            if (!busy.compareAndSet(false, true)) return
            val secret = runCatching {
                if (github) GitHubTokenCipher.encrypt(GitHubSyncProtocol.token(draft.password))
                else draft.password.ifEmpty { WebDavSyncProtocol.ANONYMOUS }
            }.getOrElse { busy.set(false); page.error("Token 无法加密，未保存配置"); return }
            val next = mapOf(
                "s3_sync_provider" to "S3", "s3_sync_endpoint" to url,
                "s3_sync_bucket" to bucket, "s3_sync_region" to "auto",
                "s3_sync_access_key" to if (github) GitHubSyncProtocol.branch(draft.username) else draft.username.ifEmpty { WebDavSyncProtocol.ANONYMOUS },
                "s3_sync_secret_key" to secret,
                "s3_sync_prefix" to "reamicro", "s3_sync_cloud_config_enabled" to false,
            )
            page.verifying()
            worker.execute {
                val writeStarted = AtomicBoolean()
                val result = runCatching {
                    valid()
                    val config = contract.newConfig(url, bucket, "auto",
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
                            toast("$providerName 已启用，返回书架即可使用原生同步")
                        }.onFailure { page.error(it.message ?: "$providerName 验证失败，原配置未修改") }
                    }
                }
            }
        }
        page = WebDavSyncConfigurationPage(activity, initial, ::submit, {
            dismissed.set(true)
            configuring.set(false)
        }, configurationUi, {
            !dismissed.get() && AccountHostRuntime.isCurrent(identity, activity) && enabled(github)
        }, github)
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

internal class ShelfSyncRetiredState(
    val identity: AccountHostRuntime.Identity,
    val revisions: Map<String, Set<Triple<Long, Long, String>>>,
)
