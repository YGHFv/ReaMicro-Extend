package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.webDavDownloadKey

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.HostRuntimeBindings
import android.app.Activity
import android.content.Intent
import com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException
import com.reamicro.fix.cloud.webdav.HomeSearchRenderContext
import com.reamicro.fix.cloud.webdav.HomeSearchSection
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.URL
import java.util.UUID
import kotlin.math.max
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.cloud.webdav.syntheticWebDavBookEntry
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.hookOnlineCompletionBookLocalSheet() {
    LocalBookMenuBridge(this).install()
}
internal fun WebDavDriveHook.hookWebDavCleartextPolicy() {
    val hooked = mutableListOf<String>()
    fun hookPolicy(method: Method) {
        method.isAccessible = true
        val label = "${method.declaringClass.name}/${method.name}/${method.parameterTypes.size}"
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                if (shouldAllowCleartext(param.args)) {
                    logCleartextAllowed(param.args?.firstOrNull(), label)
                    param.result = true
                }
            }
        })
        hooked += label
    }
    fun hookClass(className: String) {
        runCatching {
            val policyClass = Class.forName(className)
            policyClass.declaredMethods.filter {
                it.name == "isCleartextTrafficPermitted" && it.returnType == Boolean::class.javaPrimitiveType &&
                    (it.parameterTypes.isEmpty() || (it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java))
            }.forEach(::hookPolicy)
        }.onFailure {
            logWebDav("cleartext policy class skipped $className: ${it.message}")
        }
    }
    fun hookCleartextUrlFilter() {
        runCatching {
            val filterClass = Class.forName(ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS)
            filterClass.declaredMethods.filter {
                it.hostNameMatches("checkURLPermitted") &&
                    it.parameterTypes.size == 1 &&
                    URL::class.java.isAssignableFrom(it.parameterTypes[0])
            }.forEach { method ->
                method.isAccessible = true
                HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
                        if (shouldAllowCleartext(param.args)) {
                            logCleartextAllowed(param.args?.firstOrNull(), "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/checkURLPermitted")
                            param.result = null
                        }
                    }
                })
                hooked += "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/${method.name}"
            }
        }.onFailure {
            logWebDav("cleartext url filter skipped $ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS: ${it.message}")
        }
    }
    hookClass(NETWORK_SECURITY_POLICY_CLASS)
    hookCleartextUrlFilter()
    hookClass(ANDROID_OKHTTP_PLATFORM_CLASS)
    HostRuntimeBindings.http?.cleartextPolicies.orEmpty().forEach { method ->
        runCatching { hookPolicy(method) }.onFailure {
            HookRuntime.logError("ReaMicro LSP WebDAV host cleartext policy hook failed", it)
        }
    }
    logWebDav("cleartext policy hook installed: ${hooked.joinToString().ifBlank { "none" }}")
}

internal fun WebDavDriveHook.hookBackupTypeName() {
    runCatching {
        val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        if (host.hasMethod("typeName")) HookRuntime.hookMethod(host.method("typeName"), object : HookCallback() {
            override fun before(param: Call) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (
                    type == BACKUP_TYPE_WEBDAV ||
                    type == BACKUP_TYPE_LOCAL_LIBRARY ||
                    isOnlineCompletionRenderType(type) ||
                    ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                    ((webDavAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_BAIDU) ||
                    ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                    ((webDavBackupCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                    ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                ) {
                    param.result = if (
                        ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                            isOnlineCompletionRenderType(type)
                    ) {
                        onlineCompletionTitleForType(type)
                    } else if (
                        type == BACKUP_TYPE_LOCAL_LIBRARY ||
                        ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                    ) {
                        LOCAL_LIBRARY_TITLE
                    } else {
                        WEBDAV_TITLE
                    }
                }
            }

        })
        // R8 also embeds provider labels directly in composables. The scope identifies
        // module rows; exact native labels prevent changing book titles or arbitrary text.
        HookRuntime.hookMethod(ui.method("text"), object : HookCallback() {
            override fun before(param: Call) {
                val label = param.args[0] as? String ?: return
                val local = (localLibraryIconDepth.get() ?: 0) > 0 || (localLibraryAccountScreenDepth.get() ?: 0) > 0
                val webdav = (webDavIconDepth.get() ?: 0) > 0 || (webDavAccountScreenDepth.get() ?: 0) > 0 ||
                    (webDavBackupCardDepth.get() ?: 0) > 0 || (webDavRunningSyncAuthCardDepth.get() ?: 0) > 0
                val replacement = when {
                    (onlineCompletionCloudTitleDepth.get() ?: 0) > 0 -> onlineCompletionTitleForType(BACKUP_TYPE_WEBDAV)
                    local -> LOCAL_LIBRARY_TITLE
                    webdav -> WEBDAV_TITLE
                    else -> return
                }
                if (label in setOf("百度网盘", "阿里云盘", "115网盘", "本地存储")) param.args[0] = replacement
                else if (label in setOf("上传到 百度网盘", "上传到 阿里云盘", "上传到 115网盘", "上传到 本地存储"))
                    param.args[0] = "上传到 $replacement"
            }
        })
        logWebDav("backup type name hook installed")
    }.onFailure {
        logWebDav("failed to hook WebDAV backup type name: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookHomeCloudResultListRenderContext() {
    runCatching {
        val native = requireNotNull(com.reamicro.fix.core.HostHomeSearchBindings.current)
        val method = native.method("results")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val type = (param.args?.getOrNull(native.slot("results.type")) as? Number)?.toInt() ?: return

                when {
                    isOnlineCompletionRenderType(type) -> {
                        pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type))
                        pushWebDavIcon()
                    }
                    type == BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    type == BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun after(param: Call) {
                val type = (param.args?.getOrNull(native.slot("results.type")) as? Number)?.toInt() ?: return
                when {
                    isOnlineCompletionRenderType(type) -> {
                        popWebDavIcon()
                        popOnlineCompletionCloudTitle()
                    }
                    type == BACKUP_TYPE_WEBDAV -> popWebDavIcon()
                    type == BACKUP_TYPE_LOCAL_LIBRARY -> popLocalLibraryIcon()
                }
            }
        })
        logWebDav("home cloud result render context hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook home cloud result render context: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookOnlineCompletionHomeCloudBookRow() {
    runCatching {
        val native = requireNotNull(com.reamicro.fix.core.HostHomeSearchBindings.current)
        val method = native.method("row")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val book = param.args?.getOrNull(native.slot("row.book")) ?: return
                val path = cloudPathOf(book)
                val target = onlineCompletionSearchTargets[path] ?: return
                val composer = param.args?.getOrNull(native.slot("row.composer")) ?: return
                // 卡片的 Compose 点击语义由 AndroidView 外层 Modifier 提供。
                renderOnlineCompletionCloudBookSearchRow(book, target, composer)
                param.result = targetUnit()
            }
        })
        logWebDav("online completion home CloudBookRow hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook online completion home CloudBookRow: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavRowIcon() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.metadata).method("baidu")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val isSyncWebDavRow = syncAvailableProvider.get() == BACKUP_TYPE_WEBDAV
                if ((localLibraryIconDepth.get() ?: 0) > 0) {
                    getNativeAndroidOsVector()?.let { param.result = it }
                    return
                }
                if ((webDavIconDepth.get() ?: 0) <= 0 && !isSyncWebDavRow) return
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        HookRuntime.log("$LOG_PREFIX WebDAV row icon hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV row icon: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavYun115Icon() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.metadata).method("yun115")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                if (
                    (webDavIconDepth.get() ?: 0) <= 0 &&
                    (onlineCompletionCloudTitleDepth.get() ?: 0) <= 0 &&
                    (localLibraryIconDepth.get() ?: 0) <= 0 &&
                    (webDavBackupCardDepth.get() ?: 0) <= 0 &&
                    (webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0
                ) return
                if ((localLibraryIconDepth.get() ?: 0) > 0) {
                    getNativeAndroidOsVector()?.let { param.result = it }
                    return
                }
                getComposeWebDavVector()?.let { param.result = it }
            }
        })
        HookRuntime.log("$LOG_PREFIX WebDAV 115 icon replacement hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV 115 icon replacement: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookSyncAuthCard() = SyncAuthUiBridge(this).install()
internal fun WebDavDriveHook.hookLocalLibraryFolderPickerResult() {
    runCatching {
        Activity::class.java.declaredMethods.filter {
            it.hostNameMatches("onActivityResult") &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                it.parameterTypes[1] == java.lang.Integer.TYPE &&
                it.parameterTypes[2] == Intent::class.java
        }.forEach { method ->
            method.isAccessible = true
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (requestCode != REQUEST_LOCAL_LIBRARY_DIR) return
                    val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    val data = param.args?.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode == Activity.RESULT_OK && uri != null) {
                        val activity = param.thisObject as? Activity ?: activityProvider()
                        val readPermission = if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        } else 0
                        val writePermission = if ((data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        } else 0
                        runCatching {
                            activity?.contentResolver?.takePersistableUriPermission(uri, readPermission or writePermission)
                        }
                        addLocalLibraryFolder(uri)
                        activity?.toast("已添加书库文件夹")
                    }
                    param.result = null
                }
            })
        }
        HookRuntime.log("$LOG_PREFIX local library folder picker hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook local library folder picker: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookImportCloudAuthorizedRow() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui).method("authorized")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun after(param: Call) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> popWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> popLocalLibraryIcon()
                }
            }
        })
        HookRuntime.log("$LOG_PREFIX WebDAV import authorized row hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV import authorized row: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookImportCloudUnauthRow() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui).method("unauthorized")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val args = param.args ?: return
                val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                    BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                }
            }

            override fun after(param: Call) {
                val args = param.args ?: return
                val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        popWebDavIcon()
                        return
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        popLocalLibraryIcon()
                        return
                    }
                }
                val context = importUnauthRender.get() ?: return
                context.renderedNativeRows += 1
                if (!context.webDavRendered && context.renderedNativeRows >= max(context.expectedNativeRows, 1)) {
                    context.webDavRendered = true
                    val composer = args.getOrNull(2) ?: return
                    renderImportWebDavRow(composer)
                }
            }
        })
        HookRuntime.log("$LOG_PREFIX WebDAV import cloud row hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV import cloud row: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavData() {
    HostStorageDataBridge(this).install()
}
internal fun WebDavDriveHook.hookHomeViewModelDependencies() {
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    try {
        for (role in listOf("home", "storage", "manager", "tracker")) {
            handles += HookRuntime.hookMethod(work.constructor(role), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    val instance = param.thisObject ?: return
                    when (role) {
                        "home" -> rememberHomeViewModelDependencies(instance)
                        "storage" -> rememberCloudStorageRepository(instance)
                        "manager" -> rememberWorkerManager(instance)
                        "tracker" -> workTrackerRef = WeakReference(instance)
                    }
                }
            })
        }
        // 首页可能先显示正在阅读，且 ViewModel 已在模块安装前创建；不能只等书架分页。
        val home = requireNotNull(com.reamicro.fix.core.HostHomeBindings.bookshelf)
        for (role in listOf("screen", "page")) {
            val method = home.method(role)
            val index = method.parameterTypes.indices.single { method.parameterTypes[it] == work.type("home") }
            handles += HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    rememberHomeViewModelDependencies(param.args?.getOrNull(index))
                }
            })
            HookRuntime.deoptimize(method)
        }
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        throw error
    }
    com.reamicro.fix.reload.ModuleResources.cleanup {
        workTrackerRef = null; workerManagerRef = null
        cloudStorageRepositoryRef = null; bookshelfRepositoryRef = null
    }
    HookRuntime.log("$LOG_PREFIX native task dependencies installed")
}

internal fun WebDavDriveHook.hookHomeWebDavSearch() {
    runCatching {
        val events = HostHomeSearchEvents(this)
        val method = events.method
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable != null) return
                val query = events.query(param.args?.getOrNull(0)) ?: return
                rememberHomeViewModelDependencies(param.thisObject)
                param.thisObject?.let { homeSearch.request(it, query) }
            }
        })
        events.deoptimize()
        HookRuntime.log("$LOG_PREFIX WebDAV home search hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV home search: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookHomeSearchResultWebDavSection() {
    runCatching {
        val host = com.reamicro.fix.core.HostHomeSearchBindings.requireCurrent()
        val method = host.method("root")
        val handle = HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                param.setObjectExtra("reamicro.search.previous", homeWebDavSearchRender.get())
                homeWebDavSearchRender.remove()
                val map = param.args[host.slot("root.results")] as? Map<*, *> ?: return
                val sections = buildList {
                    (map[BACKUP_TYPE_WEBDAV] as? List<*>)?.takeIf { it.isNotEmpty() }?.let {
                        add(HomeSearchSection(type = BACKUP_TYPE_WEBDAV, results = it))
                    }
                    (map[BACKUP_TYPE_LOCAL_LIBRARY] as? List<*>)?.takeIf {
                        canShowLocalLibraryEntry() && it.isNotEmpty()
                    }?.let {
                        add(HomeSearchSection(type = BACKUP_TYPE_LOCAL_LIBRARY, results = it))
                    }
                    (map[BACKUP_TYPE_ONLINE_COMPLETION] as? List<*>)?.forEach { item ->
                        val group = item as? OnlineSearchGroup ?: return@forEach
                        if (!homeSearch.isCurrent(group.sequence)) return@forEach
                        runCatching {
                            val title = onlineCompletionGroupTitle(group)
                            val renderType = onlineCompletionRenderTypeForSource(group.source, title)
                            add(
                                HomeSearchSection(
                                    type = renderType,
                                    title = title,
                                    results = onlineCompletionGroupVisibleCloudBooks(group),
                                ),
                            )
                        }.onFailure { error ->
                            HookRuntime.log(
                                "$LOG_PREFIX online completion render section failed source=${group.source.name} " +
                                    "results=${group.results.size} first=${group.results.firstOrNull()?.name.orEmpty()} " +
                                    "error=${error.stackTraceToString()}",
                            )
                        }
                    }
                }

                val intentReceiver = param.args[host.slot("root.receiver")] ?: return
                homeWebDavSearchRender.set(
                    HomeSearchRenderContext(
                        sections = sections,
                        intentReceiver = intentReceiver,
                        generation = webDavHomeSearchSeq.get(),
                        navGraphScope = param.args[host.slot("root.scope")],
                    ),
                )
            }

            override fun after(param: Call) {
                val previous = param.getObjectExtra("reamicro.search.previous") as? HomeSearchRenderContext
                if (previous == null) homeWebDavSearchRender.remove() else homeWebDavSearchRender.set(previous)
            }
        })

        try {
            HostHomeSearchUiBridge(this).install()
        } catch (error: Throwable) {
            runCatching { handle.unhook() }.onFailure(error::addSuppressed)
            throw error
        }
        HookRuntime.log("$LOG_PREFIX WebDAV home search result hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV home search result: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookHomeSearchTapCancellation() {
    HostHomeSearchEvents(this).installTapGuard()
}

internal fun WebDavDriveHook.hookWebDavCloudDownload() {
    runCatching {
        val methods = workerEnqueueDownloadMethods()
        check(methods.isNotEmpty()) { "$WORKER_MANAGER_CLASS.$WORKER_ENQUEUE_DOWNLOAD_METHOD not found" }
        methods.forEach { method ->
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    val book = param.args?.getOrNull(0) ?: return
                    val type = book.invokeNoArg("getType") as? Number ?: return
                    if (type.toInt() != BACKUP_TYPE_WEBDAV && type.toInt() != BACKUP_TYPE_LOCAL_LIBRARY) return
                    if (type.toInt() == BACKUP_TYPE_WEBDAV) {
                        val identity = AccountHostRuntime.storageSnapshot(activityProvider())
                        val key = identity?.let { webDavDownloadKey(it, cloudPathOf(book), book.callString("getName")) }
                        key?.let(runningWebDavDownloads::get)?.let { running ->
                            tryHandleRunningWebDavDownloadTap(book)
                            // 同一路径的重复点击不能再启动请求；取消确认仍返回原任务句柄。
                            param.result = running.handle
                            return
                        }
                    }

                    param.result = runCatching {
                        if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) {
                            enqueueLocalLibraryImport(param.thisObject, book)
                        } else {
                            enqueueWebDavDownload(param.thisObject, book)
                        }
                    }.getOrElse {
                        HookRuntime.log("$LOG_PREFIX cloud download/import failed: ${it.stackTraceToString()}")
                        val title = if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) LOCAL_LIBRARY_TITLE else WEBDAV_TITLE
                        newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "import failed", null, title))
                    }
                }

                override fun after(param: Call) {
                    val book = param.args?.getOrNull(0) ?: return
                    val type = book.callInt("getType")
                    if (type !in NATIVE_CLOUD_DOWNLOAD_TYPES) return
                    val handle = param.result ?: return
                    registerNativeCloudDownload(param.thisObject, book, handle, type)
                }
            })
        }
        methods.forEach { HookRuntime.deoptimize(it) }
        HookRuntime.log("$LOG_PREFIX WebDAV cloud download hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud download: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookNativeCloudDownloadCancellation() {
    runCatching {
        val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
        val method = work.method("update")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val id = param.args?.getOrNull(0)?.toString().orEmpty()
                val token = runningNativeCloudDownloadsById[id] ?: return
                val state = param.args?.getOrNull(1) ?: return
                val status = workStateStatusName(state)
                // Native Success follows import completion. A late cancel must not manufacture
                // an import failure or remove files still owned by the native worker.
                if (status == "Success") return
                val previous = work.currentState(token.tracker, token.id)?.let(work::stateName)
                if (previous == "Success") {
                    param.result = null
                    return
                }
                if (token.cancelRequested && status == "Running") {
                    param.throwable = CloudDownloadCancelledException()
                } else if (token.cancelRequested && status == "Error" && previous == "Cancelled") {
                    param.args?.set(1, newWorkState("Cancelled", 100, null, null, token.name))
                }
            }

            override fun after(param: Call) {
                val id = param.args?.getOrNull(0)?.toString().orEmpty()
                val token = runningNativeCloudDownloadsById[id] ?: return
                if (param.throwable != null) return
                val state = work.currentState(token.tracker, token.id) ?: return
                when (workStateStatusName(state)) {
                    "Success", "Error", "Cancelled" -> unregisterNativeCloudDownload(token)
                }
            }
        })
        HookRuntime.log("$LOG_PREFIX native cloud download cancellation hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook native cloud download cancellation: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavImportBookSource() {
    HostImportSourceBridge(this).install()
}
internal fun WebDavDriveHook.hookWebDavCloudBackup() {
    runCatching {
        val method = com.reamicro.fix.core.HostWorkBindings.requireCurrent().method("backup")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val bookId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                if (type != BACKUP_TYPE_WEBDAV) return
                val dir = param.args?.getOrNull(2)?.toString().orEmpty().ifBlank { currentWebDavBackupDir() }
                logWebDav("enqueueBackup intercepted bookId=$bookId dir=$dir")
                param.result = runCatching {
                    enqueueWebDavBackup(param.thisObject, bookId, dir)
                }.getOrElse {
                    HookRuntime.log("$LOG_PREFIX WebDAV backup failed: ${it.stackTraceToString()}")
                    newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE))
                }
            }
        })
        HookRuntime.log("$LOG_PREFIX WebDAV cloud backup hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud backup: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.rememberBookBackupViewModel(viewModel: Any) {
    val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
    check(host.type("vm").isInstance(viewModel))
    rememberWebDavBackupViewModelIdentity(viewModel)
    rememberCloudStorageRepository(host.field("vm.storage").get(viewModel))
    synchronized(webDavBackupViewModels) {
        webDavBackupViewModels.removeAll { it.get() == null || it.get() === viewModel }
        webDavBackupViewModels.add(WeakReference(viewModel))
    }
}
internal fun WebDavDriveHook.hookBookBackupViewModelState() {
    val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    try {
        handles += HookRuntime.hookMethod(host.constructor("vm"), object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable == null) param.thisObject?.let(::rememberBookBackupViewModel)
            }
        })
        // Recomposition also restores VMs created before a module reload.
        handles += HookRuntime.hookMethod(host.method("screen"), object : HookCallback() {
            override fun before(param: Call) {
                val callback = param.thisObject ?: return
                val vm = host.field("screen.vm").get(callback)?.takeIf(host.type("vm")::isInstance) ?: return
                rememberBookBackupViewModel(vm)
            }
        })
        HookRuntime.deoptimize(host.method("screen"))
        com.reamicro.fix.reload.ModuleResources.cleanup {
            synchronized(webDavBackupViewModels) { webDavBackupViewModels.clear() }
            clearWebDavBackupViewModelIdentities()
        }
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        throw error
    }
}

internal fun WebDavDriveHook.hookBookBackupWebDavCard() {
    runCatching {
        val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
        val method = host.method("content")
        HookRuntime.hookMethod(method, object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
            override fun before(param: Call) {
                val book = param.args?.getOrNull(0)
                val cloudBook = param.args?.getOrNull(1)
                val type = cloudBook?.let { (host.field("cloud.type").get(it) as Number).toInt() } ?: 0
                val bookBackupType = bookBackupTypeOf(book)
                val backupId = bookBackupIdOf(book)
                val bookId = book?.let { host.field("book.id").getLong(it) } ?: 0L
                val recent = webDavRecentBackups[bookId]?.takeIf { snapshot ->
                    AccountHostRuntime.isCurrentStorage(snapshot.storageIdentity, activityProvider()) &&
                        book?.callLong("getUid") == snapshot.storageIdentity.accountId &&
                        book.callString("getUuid") == snapshot.uuid
                }
                val seq = bookBackupContentLogSeq.incrementAndGet()
                if (seq <= 12) {
                    logWebDav(
                        "backup content render#$seq bookId=$bookId " +
                            "bookType=$bookBackupType backupId=$backupId cloudType=$type " +
                            "cloudPath=${cloudBook?.callString("getPath").orEmpty()} recent=${recent != null}",
                    )
                }
                if (recent != null && bookBackupType != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_WEBDAV) {
                    param.args?.set(0, recent.book)
                    param.args?.set(1, copyCloudBookWithType(recent.backup, host.number("provider.yun115")))
                    logWebDav("backup content recent WebDAV card path=${recent.backup.callString("getPath")}")
                    pushWebDavBackupCard()
                    param.setObjectExtra("reamicro.backup.pushed", true)
                    return
                }
                if (bookBackupType == BACKUP_TYPE_WEBDAV || type == BACKUP_TYPE_WEBDAV) {
                    logWebDav(
                        "backup content bookType=$bookBackupType cloudType=$type " +
                            "name=${cloudBook?.callString("getName").orEmpty()} path=${cloudBook?.callString("getPath").orEmpty()}",
                    )
                }
                when {
                    type == BACKUP_TYPE_WEBDAV -> {
                        cloudBook ?: return
                        param.args?.set(1, copyCloudBookWithType(cloudBook, host.number("provider.yun115")))
                        logWebDav("backup content mapped WebDAV card path=${cloudBook.callString("getPath")}")
                        pushWebDavBackupCard()
                        param.setObjectExtra("reamicro.backup.pushed", true)
                    }
                    bookBackupType == BACKUP_TYPE_WEBDAV -> {
                        if (backupId.isBlank()) return
                        val fallbackBook = newCloudBook(syntheticWebDavBookEntry(backupId))
                        param.args?.set(1, copyCloudBookWithType(fallbackBook, host.number("provider.yun115")))
                        logWebDav("backup content synthesized WebDAV card path=$backupId")
                        pushWebDavBackupCard()
                        param.setObjectExtra("reamicro.backup.pushed", true)
                    }
                }
            }

            override fun after(param: Call) {
                if (param.getObjectExtra("reamicro.backup.pushed") == true) {
                    popWebDavBackupCard()
                }
            }
        })
        HookRuntime.deoptimize(method)
        HookRuntime.deoptimize(host.method("screen"))
        HookRuntime.log("$LOG_PREFIX WebDAV backup card hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV backup card: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavYun115BackupCard() {
    runCatching {
        val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
        val method = host.method("yun115")
        HookRuntime.hookMethod(method, object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
            override fun before(param: Call) {
                val cloudBook = param.args?.getOrNull(0) ?: return
                if ((host.field("cloud.type").get(cloudBook) as Number).toInt() != BACKUP_TYPE_WEBDAV) return
                param.args?.set(0, copyCloudBookWithType(cloudBook, host.number("provider.yun115")))
                pushWebDavBackupCard()
                param.setObjectExtra("reamicro.backup.pushed", true)
                logWebDav("Yun115 card mapped WebDAV path=${cloudBook.callString("getPath")}")
            }

            override fun after(param: Call) {
                if (param.getObjectExtra("reamicro.backup.pushed") == true) {
                    popWebDavBackupCard()
                }
            }
        })
        HookRuntime.deoptimize(host.method("content"))
        HookRuntime.log("$LOG_PREFIX WebDAV Yun115 backup card hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV Yun115 backup card: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavStrings() {
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    val dynamic = requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui) { "Storage UI contract unavailable" }
    runCatching {
        val methods = listOf(dynamic.method("string"))
        check(methods.isNotEmpty()) { "No native string resource methods" }
        methods.forEach { method ->
            method.isAccessible = true
            handles += HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    when {
                        (localLibraryDefaultFolderDepth.get() ?: 0) > 0 -> {
                            val index = localLibraryDefaultFolderStringIndex.get() ?: 0
                            localLibraryDefaultFolderStringIndex.set(index + 1)
                            param.result = if (index == 0) LOCAL_LIBRARY_FOLDER_TITLE else ""
                        }
                        (localLibraryCloudTitleDepth.get() ?: 0) > 0 -> param.result = LOCAL_LIBRARY_TITLE
                        (webDavCloudTitleDepth.get() ?: 0) > 0 -> param.result = WEBDAV_TITLE
                        (onlineCompletionCloudTitleDepth.get() ?: 0) > 0 -> {
                            param.result = onlineCompletionCloudTitleText.get().orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
                        }
                        webDavNotAuthTipPending.get() == true -> {
                            webDavNotAuthTipPending.set(false)
                            param.result = WEBDAV_AUTH_TIPS
                        }
                    }
                }

                override fun after(param: Call) {
                    val resource = param.args.getOrNull(0)
                    val key = resource?.let { dynamic.field("resourceKey").get(it) as String }.orEmpty()
                    if ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && key.removePrefix("string:") == STRING_KEY_UPLOAD_TO_115) {
                        param.result = "上传到 $WEBDAV_TITLE"
                    }
                }
            })
            HookRuntime.deoptimize(method)
        }
        HookRuntime.log("$LOG_PREFIX WebDAV cloud storage string hook installed")
    }.onFailure { error ->
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud storage strings: ${error.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavTitle() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).method("bar")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> webDavCloudTitleDepth.set((webDavCloudTitleDepth.get() ?: 0) + 1)
                    BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudTitleDepth.set((localLibraryCloudTitleDepth.get() ?: 0) + 1)
                }
            }

            override fun after(param: Call) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                when (type) {
                    BACKUP_TYPE_WEBDAV -> {
                        val next = (webDavCloudTitleDepth.get() ?: 0) - 1
                        if (next <= 0) webDavCloudTitleDepth.remove() else webDavCloudTitleDepth.set(next)
                    }
                    BACKUP_TYPE_LOCAL_LIBRARY -> {
                        val next = (localLibraryCloudTitleDepth.get() ?: 0) - 1
                        if (next <= 0) localLibraryCloudTitleDepth.remove() else localLibraryCloudTitleDepth.set(next)
                    }
                }
            }
        })
        HookRuntime.deoptimize(method)
        HookRuntime.log("$LOG_PREFIX WebDAV cloud storage title hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud storage title: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavRefresh() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).method("screen")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun after(param: Call) {
                val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                if (type == BACKUP_TYPE_WEBDAV || type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    refreshCloudStorageScreen(type)
                }
            }
        })
        HookRuntime.deoptimize(method)
        HookRuntime.log("$LOG_PREFIX WebDAV cloud storage refresh hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud storage refresh: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavAuthTips() {
    runCatching {
        val method = requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui).method("notAuth")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    param.result = null
                    return
                }
                if (type != BACKUP_TYPE_WEBDAV) return
                param.args?.set(0, BACKUP_TYPE_BAIDU)
                param.args[3] = ((param.args[3] as Int) and 14.inv()) or 6
                param.setObjectExtra("reamicro.storage.notAuth", true)
                webDavNotAuthDepth.set((webDavNotAuthDepth.get() ?: 0) + 1)
                webDavNotAuthTipPending.set(true)
            }

            override fun after(param: Call) {
                if (param.getObjectExtra("reamicro.storage.notAuth") != true) return
                val next = (webDavNotAuthDepth.get() ?: 0) - 1
                if (next <= 0) {
                    webDavNotAuthDepth.remove()
                    webDavNotAuthTipPending.remove()
                } else {
                    webDavNotAuthDepth.set(next)
                }
            }
        })
        HookRuntime.deoptimize(method)
        HookRuntime.log("$LOG_PREFIX WebDAV cloud not-auth hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud not-auth: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookCloudStorageWebDavViewModelState() {
    runCatching {
        val constructor = requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).constructor("vm")
        HookRuntime.hookMethod(constructor, object : HookCallback() {
            override fun after(param: Call) {
                val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                val viewModel = param.thisObject ?: return
                synchronized(webDavStorageViewModels) {
                    webDavStorageViewModels.removeAll { it.get() == null || it.get() === viewModel }
                    webDavStorageViewModels.add(WeakReference(viewModel))
                }
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    updateLocalLibraryStorageTree(viewModel, currentLocalLibraryBrowseDir())
                    refreshLocalLibraryAsync(currentLocalLibraryBrowseDir(), force = true)
                    logWebDav("local library cloud storage viewModel registered")
                } else {
                    updateWebDavStorageTree(viewModel, currentWebDavBrowseDir())
                    refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                    logWebDav("WebDAV cloud storage viewModel registered")
                }
            }
        })
        HookRuntime.deoptimize(constructor)
        HookRuntime.log("$LOG_PREFIX WebDAV cloud storage state hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV cloud storage state: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavCloudTap() {
    HostStorageTapBridge(this).install()
}
private fun WebDavDriveHook.workerEnqueueDownloadMethods(): List<java.lang.reflect.Method> =
    listOf(com.reamicro.fix.core.HostWorkBindings.requireCurrent().method("download"))

private fun WebDavDriveHook.invokeWorkerEnqueueDownload(workerManager: Any, book: Any): Any? =
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().method("download").invoke(workerManager, book, null)

internal fun WebDavDriveHook.hookWebDavAccountScreenReuse() {
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    try {
        for ((owner, name, key) in listOf(
            Triple(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_SCREEN_METHOD, "webdav"),
            Triple(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_SCREEN_METHOD, "local"),
        )) {
            val screen = method(owner, name, 3)
            val scope = if (key == "webdav") webDavAccountNavGraphScope else localLibraryAccountNavGraphScope
            val depth = if (key == "webdav") webDavAccountScreenDepth else localLibraryAccountScreenDepth
            handles += HookRuntime.hookMethod(screen, object : HookCallback() {
                override fun before(param: Call) {
                    if (scope.get() == null) return
                    param.setObjectExtra("reamicro.storage.account.$key", true)
                    depth.set((depth.get() ?: 0) + 1)
                }
                override fun after(param: Call) {
                    if (param.getObjectExtra("reamicro.storage.account.$key") != true) return
                    val next = (depth.get() ?: 0) - 1
                    if (next <= 0) depth.remove() else depth.set(next)
                }
            })
            HookRuntime.deoptimize(screen)
        }
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        throw error
    }
}

internal fun WebDavDriveHook.hookWebDavAccountSettingComponents() {
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    val methods = linkedSetOf<java.lang.reflect.Method>()
    fun component(owner: String, name: String, count: Int): java.lang.reflect.Method = method(owner, name, count).also(methods::add)
    runCatching {
        val defaultFolder = component(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
        handles += HookRuntime.hookMethod(defaultFolder, object : HookCallback() {
            override fun before(param: Call) {
                if (!isWebDavAccountContext()) return
                val navGraphScope = webDavAccountNavGraphScope.get()
                    ?: webDavAccountNavGraphScopeStrong
                    ?: webDavAccountNavGraphScopeRef?.get()
                param.args?.set(0, currentWebDavBackupDir())
                param.args?.set(1, functionProxy("WebDavAccountDefaultFolder", FUNCTION0_CLASS) {
                    navGraphScope?.let { navigateCloudFolder(it) }
                    targetUnit()
                })
                param.args[param.args.lastIndex] = ((param.args.last() as Int) and 126.inv()) or 54
            }
        })

        val logout = component(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_LOGOUT_METHOD, 3)
        handles += HookRuntime.hookMethod(logout, object : HookCallback() {
            override fun before(param: Call) {
                if (!isWebDavAccountContext()) return
                val navGraphScope = webDavAccountNavGraphScope.get()
                    ?: webDavAccountNavGraphScopeStrong
                    ?: webDavAccountNavGraphScopeRef?.get()
                param.args?.set(0, functionProxy("WebDavAccountLogout", FUNCTION0_CLASS) {
                    clearWebDavLogin()
                    clearWebDavAccountContext()
                    navGraphScope?.let { scope ->
                        if (!navigateHome(scope)) {
                            popBackStack(scope)
                        }
                    }
                    targetUnit()
                })
                param.args[2] = ((param.args[2] as Int) and 14.inv()) or 6
            }
        })

        listOf(BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD, BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
            val query = component(BAIDU_ACCOUNT_SCREEN_CLASS, methodName, 4)
            handles += HookRuntime.hookMethod(query, object : HookCallback() {
                override fun before(param: Call) {
                    if (!isWebDavAccountContext()) return
                    val isOrderBy = methodName == BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD
                    param.args?.set(0, if (isOrderBy) currentWebDavOrderBy() else currentWebDavOrderDirection())
                    param.args?.set(1, functionProxy("WebDavAccountQuery", FUNCTION1_CLASS) { args ->
                        val value = args?.getOrNull(0)?.toString().orEmpty()
                        if (isOrderBy) {
                            saveWebDavOrderBy(value)
                        } else {
                            saveWebDavOrderDirection(value)
                        }
                        updateWebDavAccountAuthFlow()
                        refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                        targetUnit()
                    })
                    param.args[3] = ((param.args[3] as Int) and 126.inv()) or 54
                }
            })
        }
        val y115DefaultFolder = component(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
        handles += HookRuntime.hookMethod(y115DefaultFolder, object : HookCallback() {
            override fun before(param: Call) {
                if (!isLocalLibraryAccountContext()) return
                param.args?.set(0, localLibraryPickerDir())
                param.args?.set(1, functionProxy("LocalLibraryDefaultFolder", FUNCTION0_CLASS) {
                    launchLocalLibraryFolderPicker()
                    targetUnit()
                })
                param.args[3] = ((param.args[3] as Int) and 126.inv()) or 54
                param.setObjectExtra("reamicro.storage.localFolder", true)
                localLibraryDefaultFolderDepth.set((localLibraryDefaultFolderDepth.get() ?: 0) + 1)
                localLibraryDefaultFolderStringIndex.set(0)
            }

            override fun after(param: Call) {
                if (param.getObjectExtra("reamicro.storage.localFolder") != true) return
                val next = (localLibraryDefaultFolderDepth.get() ?: 0) - 1
                if (next <= 0) {
                    localLibraryDefaultFolderDepth.remove()
                    localLibraryDefaultFolderStringIndex.remove()
                } else {
                    localLibraryDefaultFolderDepth.set(next)
                }
                if (param.throwable != null || !isLocalLibraryAccountContext()) return
                val composer = param.args?.getOrNull(2) ?: return
                val native = com.reamicro.fix.core.HostUiBindings.current
                if (native != null) {
                    native.instance("startGroup", composer, 0x524D7352)
                    try { renderLocalLibraryFolderRows(composer) }
                    finally { native.instance("endGroup", composer, false) }
                } else renderLocalLibraryFolderRows(composer)
            }
        })
        val y115Logout = component(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_LOGOUT_METHOD, 3)
        handles += HookRuntime.hookMethod(y115Logout, object : HookCallback() {
            override fun before(param: Call) {
                if (!isLocalLibraryAccountContext()) return
                param.result = null
            }
        })
        listOf(Y115_ACCOUNT_QUERY_ORDER_BY_METHOD, Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
            val query = component(Y115_ACCOUNT_SCREEN_CLASS, methodName, 4)
            handles += HookRuntime.hookMethod(query, object : HookCallback() {
                override fun before(param: Call) {
                    if (!isLocalLibraryAccountContext()) return
                    val isOrderBy = methodName == Y115_ACCOUNT_QUERY_ORDER_BY_METHOD
                    param.args?.set(0, if (isOrderBy) currentLocalLibraryOrderBy() else currentLocalLibraryOrderDirection())
                    param.args?.set(1, functionProxy("LocalLibraryAccountQuery", FUNCTION1_CLASS) { args ->
                        val value = args?.getOrNull(0)?.toString().orEmpty()
                        if (isOrderBy) {
                            saveLocalLibraryOrderBy(value)
                        } else {
                            saveLocalLibraryOrderDirection(value)
                        }
                        updateLocalLibraryAccountAuthFlow()
                        refreshLocalLibraryAsync(currentLocalLibraryBrowseDir())
                        targetUnit()
                    })
                    param.args[3] = ((param.args[3] as Int) and 126.inv()) or 54
                }
            })
        }
        methods.forEach(HookRuntime::deoptimize)
        HookRuntime.log("$LOG_PREFIX WebDAV account setting component hook installed")
    }.onFailure { error ->
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV account setting components: ${error.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavAccountAuthFlow() {
    val c = requireNotNull(com.reamicro.fix.core.HostStorageBindings.account) { "Native account contract unavailable" }
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.records) { "Account records unavailable" }
    val flows = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    fun capture(key: String, vm: Any?) {
        if (vm == null || !c.type("$key.vm").isInstance(vm)) return
        c.field("$key.auth").get(vm)?.let { flows[it] = key }
    }
    try {
        for (key in listOf("webdav", "local")) {
            val ctor = c.constructor("$key.vm")
            handles += HookRuntime.hookMethod(ctor, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    capture(key, param.thisObject)
                }
            })
            HookRuntime.deoptimize(ctor)
        }
        for ((acquire, keys) in listOf("webdav", "local").groupBy { c.method("$it.acquireVm") }) {
            handles += HookRuntime.hookMethod(acquire, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) keys.forEach { capture(it, param.result) }
                }
            })
            HookRuntime.deoptimize(acquire)
        }
        for ((content, keys) in listOf("webdav", "local").groupBy { c.method("$it.content") }) {
            handles += HookRuntime.hookMethod(content, object : HookCallback() {
                override fun before(param: Call) {
                    val callback = param.thisObject ?: return
                    keys.forEach { capture(it, c.field("$it.contentVm").get(callback)) }
                }
            })
            HookRuntime.deoptimize(content)
        }
        for (collect in listOf("webdav", "local").map { c.method("$it.collect") }.distinct()) {
            handles += HookRuntime.hookMethod(collect, object : HookCallback() {
                override fun before(param: Call) {
                    val original = param.args[0] ?: return
                    val key = flows[original] ?: return
                    if (key == "webdav" && !isWebDavAccountContext() || key == "local" && !isLocalLibraryAccountContext()) return
                    try {
                        param.args[0] = if (key == "webdav") webDavAccountAuthFlow() else localLibraryAccountAuthFlow()
                        param.args[1] = if (key == "webdav") webDavAuth() else localLibraryAuth()
                        param.args[3] = ((param.args[3] as Int) and 126.inv()) or 54
                    } catch (error: Throwable) { param.throwable = error }
                }
            })
            HookRuntime.deoptimize(collect)
        }
        listOf("webdav", "local").map { c.method("$it.screen") }.distinct().forEach(HookRuntime::deoptimize)
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
        throw error
    }
}

internal fun WebDavDriveHook.hookWebDavAccountTopBarTitle() {
    runCatching {

        val method = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).method("topBar")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val title = param.args?.getOrNull(0)?.toString().orEmpty()
                val replacement = when {
                    isLocalLibraryAccountContext() -> LOCAL_LIBRARY_TITLE
                    isWebDavAccountContext() && isBaiduTitle(title) -> WEBDAV_TITLE
                    else -> return
                }
                param.args[0] = replacement
                if (com.reamicro.fix.core.HostUiBindings.current?.method("topBar") == method) {
                    param.args[9] = ((param.args[9] as Int) and 14.inv()) or 6
                    param.args[10] = (param.args[10] as Int) and 1.inv()
                }
            }
        })
        HookRuntime.deoptimize(method)
        HookRuntime.log("$LOG_PREFIX WebDAV account top bar title hook installed")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to hook WebDAV account top bar title: ${it.stackTraceToString()}")
    }.getOrThrow()
}

internal fun WebDavDriveHook.hookWebDavAccountDefaultFolderLambda() {
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.account) { "Native account contract unavailable" }
    check(com.reamicro.fix.core.HookInstallReport.snapshot().any {
        it.feature == FEATURE_ID && it.name == "webDavAccountSettingComponents" && it.ok
    }) { "Native account default-folder callback was not installed" }
}
