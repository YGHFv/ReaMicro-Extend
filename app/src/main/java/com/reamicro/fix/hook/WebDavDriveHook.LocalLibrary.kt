package com.reamicro.fix.hook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.reload.cancellableQuery
import java.util.concurrent.CancellationException
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.R
import com.reamicro.fix.cloud.webdav.ImportLocalLibraryRowContext
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.io.File
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.download.importCacheFile
import com.reamicro.fix.cloud.local.localLibraryBookUrl
import com.reamicro.fix.cloud.local.localDocumentUri
import com.reamicro.fix.cloud.local.localLibraryReadablePath
import com.reamicro.fix.cloud.local.localLibraryPathArg
import com.reamicro.fix.cloud.local.localLibraryPrefs
import com.reamicro.fix.cloud.local.queryDocumentName
import com.reamicro.fix.cloud.local.encodeLocalLibraryPath
import com.reamicro.fix.cloud.local.decodeLocalLibraryPath
import com.reamicro.fix.cloud.local.queryDocumentEntry
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.canShowLocalLibraryEntry(): Boolean =
    settingsProvider().canRunLocalLibraryCloud

internal fun WebDavDriveHook.renderImportLocalLibraryRow(composer: Any) {
    val context = importLocalLibraryRow.get()
    runCatching {
        renderImportLocalLibraryDivider(composer)
        pushLocalLibraryIcon()
        try {
            method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_AUTHORIZED_ROW_METHOD, 5).invoke(
                null,
                BACKUP_TYPE_LOCAL_LIBRARY,
                localLibraryAuth(),
                localLibraryImportClickCallback(context),
                composer,
                0,
            )
        } finally {
            popLocalLibraryIcon()
        }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render local library import row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.renderImportLocalLibraryDivider(composer: Any) {
    runCatching {
        method(DIVIDER_KT_CLASS, SIMPLE_DIVIDER_METHOD, 5).invoke(
            null,
            startPaddingModifier(56),
            0L,
            composer,
            0,
            2,
        )
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render local library import divider: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.localLibraryImportClickCallback(context: ImportLocalLibraryRowContext?): Any =
    functionProxy("LocalLibraryImportBrowse", FUNCTION0_CLASS) {
        runCatching {
            val navGraphScope = context?.navGraphScope ?: return@runCatching
            val ui = requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui)
            context.intentReceiver?.let { receiver ->
                ui.method("dismiss").invoke(receiver, ui.field("hide").get(null))
            }
            navigateStorage(navGraphScope, BACKUP_TYPE_LOCAL_LIBRARY)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to open local library import row: ${it.stackTraceToString()}")
            context?.navGraphScope?.let { scope -> navigateStorage(scope, BACKUP_TYPE_LOCAL_LIBRARY) }
        }
        targetUnit()
    }



internal fun WebDavDriveHook.enqueueLocalLibraryImport(workerManager: Any, book: Any): Any {
    val execution = storageExecutionGeneration.get()
    check(!storageClosed && settingsProvider().moduleEnabled) { "模块已停用" }
    val identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())) {
        "账号状态正在变化，请稍后重新导入"
    }
    val context = currentContext() ?: error("Android context not available")
    val name = book.callString("getName").ifBlank { LOCAL_LIBRARY_TITLE }
    val path = cloudPathOf(book)
    val sourceUrl = book.callString("getUrl").ifBlank { localLibraryBookUrl(path) }
    val sourceSize = book.invokeNoArg("getSize") as? Number
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    val tracker = work.tracker(workerManager)
    check(AccountHostRuntime.isCurrentStorage(identity, activityProvider())) { "账号已切换，请重新导入" }
    rememberWorkerManager(workerManager)
    val id = UUID.randomUUID().toString()
    val stateFlow = work.create(tracker, id, "download", path.ifBlank { name }, newWorkState("Running", 0, null, null, name))
    val handle = newNativeWorkHandle(id, requireNotNull(stateFlow))
    val importAborted = java.util.concurrent.atomic.AtomicBoolean(false)
    fun checkActive() {
        if (importAborted.get() || Thread.currentThread().isInterrupted || work.cancelled(tracker, id) ||
            storageClosed || !settingsProvider().moduleEnabled || execution != storageExecutionGeneration.get() ||
            !AccountHostRuntime.isCurrentStorage(identity, activityProvider())) {
            throw java.util.concurrent.CancellationException("账号已切换或本地书库导入已取消")
        }
    }
    com.reamicro.fix.reload.ModuleResources.thread({
        var localFile: File? = null
        var queued = false
        runCatching {
            checkActive()
            val entry = localLibraryEntry(path) ?: error("本地书库文件不存在")
            checkActive()
            val cachedFile = importCacheFile(context.cacheDir, "reamicro-local-library", entry.name)
            localFile = cachedFile
            ImportCompletionState.retain(cachedFile)
            cachedFile.parentFile?.mkdirs()
            setTrackedWorkState(tracker, id, "Running", 20, null, null, name)
            var copied = 0L
            var reported = 20
            context.contentResolver.openInputStream(localDocumentUri(entry))?.use { input ->
                cachedFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        checkActive()
                        output.write(buffer, 0, count)
                        copied += count
                        val progress = if (entry.size > 0L) (20 + copied * 55 / entry.size).toInt().coerceIn(20, 75) else 20
                        if (progress >= reported + 2) {
                            setTrackedWorkState(tracker, id, "Running", progress, null, null, name)
                            reported = progress
                        }
                    }
                }
            } ?: error("无法读取本地书库文件")
            checkActive()
            setTrackedWorkState(tracker, id, "Running", 80, null, null, name)
            val platformFile = platformFile(cachedFile)
            if (!ModuleImportPrecheck.precheck(cachedFile, sourceUrl)) {
                cancelTrackedWork(tracker, id)
                cachedFile.delete()
                logWebDav("local library import cancelled before queue path=$path")
                return@runCatching
            }
            checkActive()
            val source = rememberPendingWebDavImport(platformFile, cachedFile, sourceUrl,
                sourceSize?.toLong() ?: entry.size, identity, ::checkActive)
            checkActive()
            val result = NativeImportTaskBridge.await(workerManager, platformFile, ::checkActive,
                onProgress = { progress -> setTrackedWorkState(tracker, id, "Running", progress, null, null, name) },
                onQueued = { queued = true }, workerFinished = source::isWorkerFinished,
                onCancellationRequested = { importAborted.set(true) })
            runCatching { work.completeImport(tracker, id, result ?: sourceUrl, name) }
                .onFailure { HookRuntime.logError("$LOG_PREFIX completed local import status publication failed", it) }
            logWebDav("local library native import completed path=$path")
        }.onFailure {
            importAborted.set(true)
            if (!queued) {
                localFile?.delete()
                localFile?.parentFile?.takeIf { folder -> folder.isDirectory && folder.listFiles().isNullOrEmpty() }?.delete()
            }
            if (it is java.util.concurrent.CancellationException || !AccountHostRuntime.isCurrentStorage(identity, activityProvider())) {
                cancelTrackedWork(tracker, id)
            } else {
                HookRuntime.log("$LOG_PREFIX local library import failed: ${it.stackTraceToString()}")
                setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "本地书库导入失败", null, name)
            }
        }.also {
            if (!queued) localFile?.let(::forgetPendingWebDavImport)
        }
    }, "ReaMicroLocalLibraryImport").start()
    return handle
}

internal fun WebDavDriveHook.renderLocalLibraryAccountRoute(navGraphScope: Any, composer: Any): Boolean {
    markLocalLibraryAccountContext(navGraphScope)
    localLibraryAccountNavGraphScope.set(navGraphScope)
    localLibraryAccountScreenDepth.set((localLibraryAccountScreenDepth.get() ?: 0) + 1)
    try {
        method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_SCREEN_METHOD, 3).invoke(
            null,
            navGraphScope,
            composer,
            0,
        )
        return true
    } catch (throwable: Throwable) {
        HookRuntime.log("$LOG_PREFIX failed to render local library account route: ${throwable.stackTraceToString()}")
        return false
    } finally {
        val next = (localLibraryAccountScreenDepth.get() ?: 0) - 1
        if (next <= 0) {
            localLibraryAccountScreenDepth.remove()
        } else {
            localLibraryAccountScreenDepth.set(next)
        }
        localLibraryAccountNavGraphScope.remove()
    }
}

internal fun WebDavDriveHook.renderLocalLibraryFolderRows(composer: Any) {
    val roots = localLibraryRoots()
    if (roots.isEmpty()) return
    runCatching {
        val factory = functionProxy("LocalLibraryFolderRowsAndroidView", FUNCTION1_CLASS) { args ->
            val context = args?.getOrNull(0) as? Context
                ?: activityProvider()
                ?: error("No context for local library folder rows")
            createLocalLibraryFolderRowsView(context, roots)
        }
        androidViews.render(factory, fillMaxWidthModifier(), composer, null)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render local library folder rows: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.createLocalLibraryFolderRowsView(context: Context, roots: List<LocalLibraryEntry>): View {
    val primaryColor = context.resolveThemeColor(android.R.attr.textColorPrimary, Color.rgb(35, 35, 35))
    val actionColor = context.resolveThemeColor(android.R.attr.colorAccent, Color.rgb(180, 64, 64))
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        translationY = -context.dp(14).toFloat()
        setPadding(context.dp(52), 0, context.dp(16), context.dp(4))
        roots.forEach { root ->
            addView(createLocalLibraryFolderRowView(context, root, primaryColor, actionColor, this))
        }
    }
}

internal fun WebDavDriveHook.createLocalLibraryFolderRowView(
    context: Context,
    entry: LocalLibraryEntry,
    primaryColor: Int,
    actionColor: Int,
    parent: LinearLayout,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(32)
        setPadding(0, 0, 0, 0)
    }
    row.addView(TextView(context).apply {
        text = localLibraryReadablePath(entry)
        setTextColor(primaryColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(TextView(context).apply {
        text = LOCAL_LIBRARY_REMOVE_TEXT
        setTextColor(actionColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minWidth = context.dp(48)
        setPadding(context.dp(12), context.dp(6), 0, context.dp(6))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    row.getChildAt(1).setOnClickListener {
        removeLocalLibraryFolder(entry.treeUri)
        parent.removeView(row)
        context.toast(LOCAL_LIBRARY_REMOVED_TOAST)
    }
    return row
}

internal fun WebDavDriveHook.markLocalLibraryAccountContext(navGraphScope: Any) {
    localLibraryAccountNavGraphScopeStrong = navGraphScope
    localLibraryAccountNavGraphScopeRef = WeakReference(navGraphScope)
    localLibraryAccountRouteRenderAtMs = System.currentTimeMillis()
}

internal fun WebDavDriveHook.clearLocalLibraryAccountContext() {
    localLibraryAccountNavGraphScopeStrong = null
    localLibraryAccountNavGraphScopeRef = null
    localLibraryAccountRouteRenderAtMs = 0L
}

internal fun WebDavDriveHook.isLocalLibraryAccountContext(): Boolean =
    (localLibraryAccountScreenDepth.get() ?: 0) > 0 ||
        localLibraryAccountNavGraphScope.get() != null ||
        localLibraryAccountNavGraphScopeStrong != null ||
        localLibraryAccountNavGraphScopeRef?.get() != null ||
        (System.currentTimeMillis() - localLibraryAccountRouteRenderAtMs) in 0..ACCOUNT_CONTEXT_GRACE_MS

internal fun WebDavDriveHook.localLibraryAuth(): Any {
    val dir = localLibraryDisplayDir()
    return com.reamicro.fix.core.HostStorageRecords.localAuth(cls(AUTH_YUN115_CLASS), cls(DIR_CLASS), arrayOf(
        "local-library", currentLocalLibraryOrderBy(), currentLocalLibraryOrderDirection(), LOCAL_LIBRARY_TITLE,
        localLibraryUsedSize(), localLibraryUsedSize(), listOf(dir), dir,
    ))
}

internal fun WebDavDriveHook.localLibraryDisplayDir(): Any =
    newDir(LOCAL_LIBRARY_ROOT_PATH, localLibraryFolderSummary())

internal fun WebDavDriveHook.localLibraryPickerDir(): Any =
    newDir(LOCAL_LIBRARY_ROOT_PATH, LOCAL_LIBRARY_PICK_FOLDER_TEXT)

internal fun WebDavDriveHook.localLibraryCloudUserInfo(): Any =
    newCloudUserInfo(
        "local-library",
        LOCAL_LIBRARY_TITLE,
        "",
        LOCAL_LIBRARY_TITLE,
        "",
        null,
        localLibraryUsedSize(),
        localLibraryUsedSize(),
    )

internal fun WebDavDriveHook.localLibraryFlow(): Any =
    synchronized(localLibraryLock) {
        localLibraryFlow ?: createMutableStateFlow(emptyPagingData()).also {
            localLibraryFlow = it
            refreshLocalLibraryAsync(currentLocalLibraryBrowseDir())
        }
    }

internal fun WebDavDriveHook.localLibraryAccountAuthFlow(): Any =
    synchronized(localLibraryAccountAuthLock) {
        localLibraryAccountAuthFlow ?: createMutableStateFlow(localLibraryAuth()).also {
            localLibraryAccountAuthFlow = it
        }
    }

internal fun WebDavDriveHook.updateLocalLibraryAccountAuthFlow() {
    val flow = synchronized(localLibraryAccountAuthLock) { localLibraryAccountAuthFlow }
    setMutableStateFlowValue(flow, localLibraryAuth())
}

private data class LocalLibraryRefresh(
    val sequence: Long,
    val generation: Long,
    val path: String,
    val order: String,
    val direction: String,
    val flow: Any,
)

private fun WebDavDriveHook.localLibraryRefresh(path: String, force: Boolean): LocalLibraryRefresh? {
    if (storageClosed || storagePaused || !settingsProvider().moduleEnabled) return null
    val normalized = localLibraryPathArg(path)
    if (normalized != localLibraryPathArg(currentLocalLibraryBrowseDir())) return null
    val flow = synchronized(localLibraryLock) { localLibraryFlow } ?: return null
    if (force) clearLocalLibraryListCache()
    val sequence = localLibraryRefreshSequence.incrementAndGet()
    return LocalLibraryRefresh(sequence, localLibraryCacheGeneration.get(), normalized,
        currentLocalLibraryOrderBy(), currentLocalLibraryOrderDirection(), flow)
}

private fun WebDavDriveHook.isCurrent(request: LocalLibraryRefresh): Boolean =
    !storageClosed && !storagePaused && settingsProvider().moduleEnabled &&
        request.sequence == localLibraryRefreshSequence.get() &&
        request.generation == localLibraryCacheGeneration.get() &&
        request.path == localLibraryPathArg(currentLocalLibraryBrowseDir()) &&
        request.order == currentLocalLibraryOrderBy() && request.direction == currentLocalLibraryOrderDirection() &&
        synchronized(localLibraryLock) { localLibraryFlow === request.flow }

private fun WebDavDriveHook.loadLocalLibrary(request: LocalLibraryRefresh, current: () -> Boolean) {
    try {
        TaskCancellation.checkCurrent()
        if (!current() || !isCurrent(request)) return
        val items = listLocalLibrary(request.path)
            .filter { it.isDirectory || isSupportedBookFile(it.name) }
            .map {
                TaskCancellation.checkCurrent()
                if (it.isDirectory) newLocalLibraryCloudFolder(it) else newLocalLibraryCloudBook(it)
            }
        TaskCancellation.checkCurrent()
        if (!current() || !isCurrent(request)) return
        val paging = pagingDataFrom(items)
        postStorageUi {
            if (current() && isCurrent(request)) {
                setMutableStateFlowValue(request.flow, paging)
                logWebDav("refresh local library items=${items.size}")
            }
        }
    } catch (error: Throwable) {
        TaskCancellation.rethrowIfCancelled(error)
        HookRuntime.logError("$LOG_PREFIX failed to refresh local library", error)
    }
}

internal fun WebDavDriveHook.refreshLocalLibrary(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
    refreshLocalLibraryAsync(path, force)
}

internal fun WebDavDriveHook.refreshLocalLibraryAsync(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
    if (storageClosed || storagePaused) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        postStorageUi { refreshLocalLibraryAsync(path, force) }
        return
    }
    val request = localLibraryRefresh(path, force) ?: return
    try {
        localRefreshWorker.submitIf({ isCurrent(request) }) { ticket ->
            loadLocalLibrary(request, ticket::isCurrent)
        }
    } catch (error: Throwable) {
        if (ModuleResources.accepting && !storageClosed) {
            HookRuntime.logError("$LOG_PREFIX local library refresh start failed", error)
        }
    }
}

internal fun WebDavDriveHook.searchLocalLibraryBooks(query: String, extendIndex: Boolean = true): List<Any> {
    TaskCancellation.checkCurrent()
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val generation = localLibraryCacheGeneration.get()
    val rootsKey = localLibraryRootsKey()
    val cached = localLibrarySearchIndex?.takeIf {
        it.generation == generation && it.rootsKey == rootsKey &&
            SystemClock.elapsedRealtime() - it.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS
    }
    val entries = cached?.entries ?: buildLocalLibrarySearchIndex(
        rootsKey, LOCAL_LIBRARY_SEARCH_SYNC_BUDGET_MS, generation,
    ).entries
    if (extendIndex) ensureLocalLibrarySearchIndexAsync(rootsKey)
    checkLocalLibraryGeneration(generation)
    return entries.filter { entry ->
        TaskCancellation.checkCurrent()
        isSupportedBookFile(entry.name) && (entry.name.contains(needle, ignoreCase = true) ||
            localLibraryReadablePath(entry).contains(needle, ignoreCase = true))
    }.distinctBy { it.path }.let(::sortLocalLibraryEntries).map {
        checkLocalLibraryGeneration(generation)
        newLocalLibraryCloudBook(it)
    }
}

private fun WebDavDriveHook.checkLocalLibraryGeneration(generation: Long) {
    TaskCancellation.checkCurrent()
    if (generation != localLibraryCacheGeneration.get() || storageClosed || !settingsProvider().moduleEnabled) {
        throw CancellationException("Local library changed")
    }
}

internal fun WebDavDriveHook.buildLocalLibrarySearchIndex(
    rootsKey: String,
    maxDurationMs: Long,
    generation: Long = localLibraryCacheGeneration.get(),
): LocalLibrarySearchIndex {
    checkLocalLibraryGeneration(generation)
    val deadline = SystemClock.elapsedRealtime() + maxDurationMs
    val queue = java.util.ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val indexed = mutableListOf<LocalLibraryEntry>()
    var failedDirectory = false
    val roots = localLibraryRoots()
    roots.forEach { queue.add(it.path) }
    while (queue.isNotEmpty() && visited.size < LOCAL_LIBRARY_SEARCH_MAX_DIRS) {
        checkLocalLibraryGeneration(generation)
        if (SystemClock.elapsedRealtime() >= deadline) break
        val path = queue.removeFirst()
        if (!visited.add(path)) continue
        val entries = try { listLocalLibrary(path) } catch (error: Throwable) {
            TaskCancellation.rethrowIfCancelled(error)
            failedDirectory = true
            HookRuntime.logError("$LOG_PREFIX local library index directory failed", error)
            emptyList()
        }
        entries.forEach { entry ->
            checkLocalLibraryGeneration(generation)
            when {
                entry.isDirectory -> queue.add(entry.path)
                isSupportedBookFile(entry.name) -> indexed.add(entry)
            }
        }
    }
    checkLocalLibraryGeneration(generation)
    val index = LocalLibrarySearchIndex(rootsKey, indexed.distinctBy { it.path },
        SystemClock.elapsedRealtime(), queue.isEmpty() && !failedDirectory, generation)
    synchronized(localLibrarySearchIndexLock) {
        checkLocalLibraryGeneration(generation)
        val current = localLibrarySearchIndex
        if (current == null || current.generation != generation || current.rootsKey != rootsKey ||
            index.entries.size >= current.entries.size || index.complete) {
            localLibrarySearchIndex = index
        }
    }
    logWebDav("local index roots=${roots.size} scanned=${visited.size} entries=${index.entries.size} complete=${index.complete}")
    return index
}

internal fun WebDavDriveHook.ensureLocalLibrarySearchIndexAsync(rootsKey: String = localLibraryRootsKey()) {
    if (storageClosed || storagePaused || !settingsProvider().moduleEnabled) return
    val generation = localLibraryCacheGeneration.get()
    val token = Any()
    synchronized(localLibrarySearchIndexLock) {
        if (generation != localLibraryCacheGeneration.get() || storageClosed || storagePaused ||
            !settingsProvider().moduleEnabled) return
        val current = localLibrarySearchIndex
        if (current != null && current.generation == generation && current.rootsKey == rootsKey && current.complete &&
            SystemClock.elapsedRealtime() - current.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS) return
        if (localLibraryIndexRequest != null) return
        localLibraryIndexRequest = token
    }
    fun active(): Boolean = !storageClosed && !storagePaused && settingsProvider().moduleEnabled &&
        generation == localLibraryCacheGeneration.get() &&
        synchronized(localLibrarySearchIndexLock) { localLibraryIndexRequest === token }
    fun release() = synchronized(localLibrarySearchIndexLock) {
        if (localLibraryIndexRequest === token) localLibraryIndexRequest = null
    }
    try {
        val accepted = localIndexWorker.submitIf(::active) { ticket ->
            try {
                buildLocalLibrarySearchIndex(rootsKey, LOCAL_LIBRARY_SEARCH_BACKGROUND_BUDGET_MS, generation)
                if (ticket.isCurrent() && active()) homeSearch.localChanged(generation, indexReady = true)
            } catch (error: Throwable) {
                TaskCancellation.rethrowIfCancelled(error)
                HookRuntime.logError("$LOG_PREFIX local library background index failed", error)
            } finally { release() }
        }
        if (accepted == null) release()
    } catch (error: Throwable) {
        release()
        if (ModuleResources.accepting && !storageClosed) {
            HookRuntime.logError("$LOG_PREFIX local library index start failed", error)
        }
    }
}

internal fun WebDavDriveHook.localLibraryDirTree(path: String): List<Any> {
    val normalized = localLibraryPathArg(path)
    val dirs = mutableListOf<Any>(newDir(LOCAL_LIBRARY_ROOT_PATH, ROOT_DIR_NAME))
    if (normalized == LOCAL_LIBRARY_ROOT_PATH) return dirs
    val roots = localLibraryRoots()
    val root = roots.firstOrNull { it.path == normalized }
    if (root != null) {
        dirs.add(newDir(root.path, root.name))
        return dirs
    }
    val entry = localLibraryEntry(normalized)
    val parentRoot = roots.firstOrNull { entry?.treeUri == it.treeUri }
    parentRoot?.let { dirs.add(newDir(it.path, it.name)) }
    entry?.let { dirs.add(newDir(it.path, it.name)) }
    return dirs.distinctBy { dir -> dir.callString("getId") }
}

internal fun WebDavDriveHook.updateLocalLibraryStorageTrees(path: String) {
    val viewModels = synchronized(webDavStorageViewModels) {
        webDavStorageViewModels.mapNotNull { it.get() }.also {
            webDavStorageViewModels.removeAll { ref -> ref.get() == null }
        }
    }
    viewModels.forEach { updateLocalLibraryStorageTree(it, path) }
}

internal fun WebDavDriveHook.updateLocalLibraryStorageTree(viewModel: Any, path: String) {
    runCatching {
        val tree = localLibraryDirTree(path)
        val updateMethod = requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).method("update")
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("LocalLibraryStorageTreeState", FUNCTION1_CLASS, fallback = { args -> args?.firstOrNull() }) { args ->
                val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                storageStateWithTree(state, tree)
            },
        )
        logWebDav("update local library tree path=$path size=${tree.size}")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to update local library storage tree: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.newLocalLibraryCloudFolder(entry: LocalLibraryEntry): Any =
    newStorageFolder(
        entry.name,
        entry.path,
        false,
        entry.updatedAt,
    )

internal fun WebDavDriveHook.newLocalLibraryCloudBook(entry: LocalLibraryEntry): Any {
    val url = localLibraryBookUrl(entry.path)
    return com.reamicro.fix.core.HostStorageRecords.book(cls(CLOUD_BOOK_CLASS), cls(FLOW_CLASS), arrayOf(
        entry.path,
        entry.name,
        BACKUP_TYPE_LOCAL_LIBRARY,
        url,
        entry.size,
        entry.updatedAt,
        entry.path,
        "",
        webDavLocalBookFlow(url),
        webDavWorkFlow(entry.path),
    ))
}

internal fun WebDavDriveHook.currentLocalLibraryBrowseDir(): String =
    localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_BROWSE_DIR, LOCAL_LIBRARY_ROOT_PATH)
        ?.ifBlank { LOCAL_LIBRARY_ROOT_PATH }
        ?: LOCAL_LIBRARY_ROOT_PATH

internal fun WebDavDriveHook.currentLocalLibraryOrderBy(): String =
    localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_ORDER_BY, LOCAL_LIBRARY_DEFAULT_ORDER_BY)
        ?.takeIf { it in setOf("file_name", "file_size", "user_utime", "name", "size", "time") }
        ?: LOCAL_LIBRARY_DEFAULT_ORDER_BY

internal fun WebDavDriveHook.currentLocalLibraryOrderDirection(): String =
    localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_ORDER_DIRECTION, DEFAULT_ORDER_DIRECTION_ASC)
        ?.takeIf { it in setOf("0", "1", "ASC", "DESC", "asc", "desc") }
        ?: DEFAULT_ORDER_DIRECTION_ASC

internal fun WebDavDriveHook.saveLocalLibraryBrowseDir(path: String) {
    localLibraryPrefs(activityProvider())?.edit()
        ?.putString(KEY_LOCAL_BROWSE_DIR, localLibraryPathArg(path))
        ?.apply()
    invalidateLocalLibraryRefresh()
}

internal fun WebDavDriveHook.saveLocalLibraryOrderBy(value: String) {
    val normalized = when (value) {
        "file_name", "file_size", "user_utime" -> value
        "name", "name_enhanced" -> "file_name"
        "size" -> "file_size"
        "time", "updated_at", "created_at" -> "user_utime"
        else -> LOCAL_LIBRARY_DEFAULT_ORDER_BY
    }
    localLibraryPrefs(activityProvider())?.edit()
        ?.putString(KEY_LOCAL_ORDER_BY, normalized)
        ?.apply()
    invalidateLocalLibraryRefresh()
}

internal fun WebDavDriveHook.saveLocalLibraryOrderDirection(value: String) {
    val normalized = if (isDescendingOrder(value)) "1" else "0"
    localLibraryPrefs(activityProvider())?.edit()
        ?.putString(KEY_LOCAL_ORDER_DIRECTION, normalized)
        ?.apply()
    invalidateLocalLibraryRefresh()
}

private fun WebDavDriveHook.invalidateLocalLibraryRefresh() {
    val sequence = localLibraryRefreshSequence.incrementAndGet()
    if (localRefreshWorkerState.isInitialized()) {
        localRefreshWorker.cancelIf { localLibraryRefreshSequence.get() == sequence }
    }
}

internal fun WebDavDriveHook.localLibraryFolderUris(): List<String> =
    localLibraryPrefs(activityProvider())
        ?.getString(KEY_LOCAL_FOLDER_URIS, "")
        .orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun WebDavDriveHook.saveLocalLibraryFolderUris(uris: List<String>) {
    localLibraryPrefs(activityProvider())?.edit()
        ?.putString(KEY_LOCAL_FOLDER_URIS, uris.distinct().joinToString("\n"))
        ?.apply()
    clearLocalLibraryListCache()
}

internal fun WebDavDriveHook.clearLocalLibraryListCache() {
    val generation = synchronized(localLibrarySearchIndexLock) {
        localLibraryListCache.clear()
        localLibrarySearchIndex = null
        localLibraryIndexRequest = null
        localLibraryCacheGeneration.incrementAndGet()
    }
    if (localIndexWorkerState.isInitialized()) {
        localIndexWorker.cancelIf { synchronized(localLibrarySearchIndexLock) { localLibraryIndexRequest == null } }
    }
    if (homeSearchState.isInitialized()) homeSearch.localChanged(generation, indexReady = false)
}

internal fun WebDavDriveHook.localLibraryRootsKey(): String =
    localLibraryFolderUris().joinToString("|")

internal fun WebDavDriveHook.addLocalLibraryFolder(uri: Uri) {
    val value = uri.toString()
    if (value.isBlank()) return
    saveLocalLibraryFolderUris(localLibraryFolderUris() + value)
    saveLocalLibraryBrowseDir(LOCAL_LIBRARY_ROOT_PATH)
    synchronized(localLibraryLock) {
        localLibraryFlow = localLibraryFlow ?: createMutableStateFlow(emptyPagingData())
    }
    updateLocalLibraryStorageTrees(LOCAL_LIBRARY_ROOT_PATH)
    refreshLocalLibraryAsync(LOCAL_LIBRARY_ROOT_PATH)
}

internal fun WebDavDriveHook.removeLocalLibraryFolder(uriString: String) {
    val next = localLibraryFolderUris().filter { it != uriString }
    saveLocalLibraryFolderUris(next)
    saveLocalLibraryBrowseDir(LOCAL_LIBRARY_ROOT_PATH)
    runCatching {
        val uri = Uri.parse(uriString)
        currentContext()?.contentResolver?.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    synchronized(localLibraryLock) {
        localLibraryFlow = localLibraryFlow ?: createMutableStateFlow(emptyPagingData())
    }
    updateLocalLibraryStorageTrees(LOCAL_LIBRARY_ROOT_PATH)
    refreshLocalLibraryAsync(LOCAL_LIBRARY_ROOT_PATH)
}

internal fun WebDavDriveHook.launchLocalLibraryFolderPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        activity.startActivityForResult(intent, REQUEST_LOCAL_LIBRARY_DIR)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to launch local library folder picker: ${it.stackTraceToString()}")
        activity.toast("无法打开目录选择")
    }
}

internal fun WebDavDriveHook.localLibraryFolderSummary(): String {
    val count = localLibraryFolderUris().size
    return if (count <= 0) LOCAL_LIBRARY_PICK_FOLDER_TEXT else "$count 个文件夹"
}

internal fun WebDavDriveHook.localLibraryUsedSize(): Long =
    localLibraryRoots().sumOf { it.size.coerceAtLeast(0L) }

internal fun WebDavDriveHook.localLibraryRoots(): List<LocalLibraryEntry> {
    val context = currentContext() ?: return emptyList()
    return localLibraryFolderUris().mapNotNull { raw ->
        TaskCancellation.checkCurrent()
        val treeUri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@mapNotNull null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return@mapNotNull null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val name = queryDocumentName(context, documentUri).ifBlank {
            treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { LOCAL_LIBRARY_TITLE } ?: LOCAL_LIBRARY_TITLE
        }
        LocalLibraryEntry(
            name = name,
            path = encodeLocalLibraryPath(treeUri.toString(), docId),
            treeUri = treeUri.toString(),
            documentId = docId,
            isDirectory = true,
            size = 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

internal fun WebDavDriveHook.sortLocalLibraryEntries(entries: List<LocalLibraryEntry>): List<LocalLibraryEntry> =
    sortStorageEntries(
        entries = entries,
        orderBy = currentLocalLibraryOrderBy(),
        direction = currentLocalLibraryOrderDirection(),
        isDirectory = { it.isDirectory },
        name = { it.name },
        size = { it.size },
        updatedAt = { it.updatedAt },
    )

internal fun WebDavDriveHook.listLocalLibrary(path: String): List<LocalLibraryEntry> {
    val generation = localLibraryCacheGeneration.get()
    checkLocalLibraryGeneration(generation)
    val normalized = localLibraryPathArg(path)
    if (normalized == LOCAL_LIBRARY_ROOT_PATH) {
        return sortLocalLibraryEntries(localLibraryRoots()).also { checkLocalLibraryGeneration(generation) }
    }
    localLibraryListCache[normalized]?.takeIf {
        it.generation == generation && SystemClock.elapsedRealtime() - it.createdAtMs <= LOCAL_LIBRARY_LIST_CACHE_TTL_MS
    }?.let { return sortLocalLibraryEntries(it.entries).also { checkLocalLibraryGeneration(generation) } }
    val context = currentContext() ?: error("Android context not available")
    val decoded = decodeLocalLibraryPath(normalized) ?: error("Invalid local library folder")
    val treeUri = Uri.parse(decoded.treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, decoded.documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val entries = context.contentResolver.cancellableQuery(childrenUri, projection) { result ->
        val cursor = requireNotNull(result) { "本地书库目录暂不可用" }
        val docIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        buildList {
            while (cursor.moveToNext()) {
                checkLocalLibraryGeneration(generation)
                val docId = cursor.stringAt(docIdIndex)
                val name = cursor.stringAt(nameIndex)
                if (docId.isBlank() || name.isBlank() || name.startsWith(".")) continue
                add(LocalLibraryEntry(name, encodeLocalLibraryPath(decoded.treeUri, docId), decoded.treeUri, docId,
                    cursor.stringAt(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                    cursor.longAt(sizeIndex), cursor.longAt(modifiedIndex).takeIf { it > 0L } ?: System.currentTimeMillis()))
            }
        }
    }
    synchronized(localLibrarySearchIndexLock) {
        checkLocalLibraryGeneration(generation)
        localLibraryListCache[normalized] = LocalLibraryListCache(entries, SystemClock.elapsedRealtime(), generation)
    }
    return sortLocalLibraryEntries(entries)
}

internal fun WebDavDriveHook.localLibraryEntry(path: String): LocalLibraryEntry? {
    val decoded = decodeLocalLibraryPath(path) ?: return null
    val context = currentContext() ?: return null
    val treeUri = Uri.parse(decoded.treeUri)
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, decoded.documentId)
    return queryDocumentEntry(context, documentUri, decoded.treeUri, decoded.documentId)
}

internal fun WebDavDriveHook.createLocalLibraryFolder(parentPath: String, name: String) {
    val context = currentContext() ?: error("Android context not available")
    val parent = decodeLocalLibraryPath(parentPath) ?: error("Invalid local library folder")
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(parent.treeUri), parent.documentId)
    DocumentsContract.createDocument(
        context.contentResolver,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        name.trim().ifBlank { "新建文件夹" },
    ) ?: error("Create folder failed")
    clearLocalLibraryListCache()
}

internal fun WebDavDriveHook.renameLocalLibraryEntry(path: String, newName: String) {
    val context = currentContext() ?: error("Android context not available")
    val entry = localLibraryEntry(path) ?: error("File not found")
    val documentUri = localDocumentUri(entry)
    val targetName = if (entry.isDirectory || newName.contains('.')) {
        newName.trim()
    } else {
        val extension = entry.name.substringAfterLast('.', "")
        if (extension.isBlank()) newName.trim() else "${newName.trim()}.$extension"
    }
    DocumentsContract.renameDocument(context.contentResolver, documentUri, targetName)
        ?: error("Rename failed")
    clearLocalLibraryListCache()
}

internal fun WebDavDriveHook.deleteLocalLibraryEntry(path: String) {
    val context = currentContext() ?: error("Android context not available")
    val entry = localLibraryEntry(path) ?: error("File not found")
    if (!DocumentsContract.deleteDocument(context.contentResolver, localDocumentUri(entry))) {
        error("Delete failed")
    }
    clearLocalLibraryListCache()
}

internal fun WebDavDriveHook.moveLocalLibraryEntry(path: String, destPath: String) {
    val context = currentContext() ?: error("Android context not available")
    val entry = localLibraryEntry(path) ?: error("File not found")
    val sourceParent = parentLocalLibraryPath(path).let(::decodeLocalLibraryPath)
        ?: error("Invalid source parent")
    val dest = decodeLocalLibraryPath(destPath) ?: error("Invalid target folder")
    val resolver = context.contentResolver
    val moved = DocumentsContract.moveDocument(
        resolver,
        localDocumentUri(entry),
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(sourceParent.treeUri), sourceParent.documentId),
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(dest.treeUri), dest.documentId),
    )
    if (moved == null) error("Move failed")
    clearLocalLibraryListCache()
}

internal fun WebDavDriveHook.parentLocalLibraryPath(path: String): String {
    val decoded = decodeLocalLibraryPath(path) ?: return LOCAL_LIBRARY_ROOT_PATH
    val root = localLibraryRoots().firstOrNull { it.treeUri == decoded.treeUri }
    if (root == null || root.documentId == decoded.documentId) return LOCAL_LIBRARY_ROOT_PATH
    val parentId = decoded.documentId.substringBeforeLast('/', missingDelimiterValue = root.documentId)
    return encodeLocalLibraryPath(decoded.treeUri, parentId)
}

internal fun WebDavDriveHook.pushLocalLibraryIcon() {
    localLibraryIconDepth.set((localLibraryIconDepth.get() ?: 0) + 1)
}

internal fun WebDavDriveHook.popLocalLibraryIcon() {
    val next = (localLibraryIconDepth.get() ?: 0) - 1
    if (next <= 0) {
        localLibraryIconDepth.remove()
    } else {
        localLibraryIconDepth.set(next)
    }
}
