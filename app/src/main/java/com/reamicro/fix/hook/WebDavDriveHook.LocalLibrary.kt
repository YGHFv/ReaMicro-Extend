package com.reamicro.fix.hook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.R
import com.reamicro.fix.webdav.ImportLocalLibraryRowContext
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import com.reamicro.fix.hook.webdav.*

// WebDavDriveHook 的本地书库簇。
//
// 基于 SAF 文档树的目录浏览、增删改、索引与搜索。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
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
        XposedBridge.log("$LOG_PREFIX failed to render local library import row: ${it.stackTraceToString()}")
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
        XposedBridge.log("$LOG_PREFIX failed to render local library import divider: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.localLibraryImportClickCallback(context: ImportLocalLibraryRowContext?): Any =
    functionProxy("LocalLibraryImportBrowse", FUNCTION0_CLASS) {
        runCatching {
            val navGraphScope = context?.navGraphScope ?: return@runCatching
            val coroutineScope = context.coroutineScope ?: return@runCatching
            val sheetState = context.sheetState ?: return@runCatching
            val intentReceiver = context.intentReceiver ?: return@runCatching
            method(BOOK_LIBRARY_SHEET_CLASS, BOOK_LIBRARY_AUTH_ROW_CLICK_METHOD, 5).invoke(
                null,
                navGraphScope,
                BACKUP_TYPE_LOCAL_LIBRARY,
                coroutineScope,
                sheetState,
                intentReceiver,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open local library import row: ${it.stackTraceToString()}")
            context?.navGraphScope?.let { scope -> navigateStorage(scope, BACKUP_TYPE_LOCAL_LIBRARY) }
        }
        targetUnit()
    }

internal fun WebDavDriveHook.scheduleLocalLibrarySearchRefresh(
    viewModel: Any?,
    query: String,
    webDavResults: List<Any>,
    webDavSeq: Long,
    localSeq: Long,
    delayMs: Long,
) {
    Handler(Looper.getMainLooper()).postDelayed({
        Thread({
            runCatching {
                if (
                    webDavHomeSearchSeq.get() != webDavSeq ||
                    localLibraryHomeSearchSeq.get() != localSeq
                ) return@runCatching
                val refreshedLocalResults = if (query.isBlank() || !canShowLocalLibraryEntry()) {
                    emptyList()
                } else {
                    searchLocalLibraryBooks(query)
                }
                if (
                    webDavHomeSearchSeq.get() != webDavSeq ||
                    localLibraryHomeSearchSeq.get() != localSeq
                ) return@runCatching
                Handler(Looper.getMainLooper()).post {
                    if (
                        webDavHomeSearchSeq.get() == webDavSeq &&
                        localLibraryHomeSearchSeq.get() == localSeq
                    ) {
                        rememberHomeSearchSnapshot(webDavResults, refreshedLocalResults)
                        updateHomeWebDavSearchResults(viewModel, webDavResults, refreshedLocalResults, null)
                    }
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX local library delayed search refresh failed: ${it.stackTraceToString()}")
            }
        }, "ReaMicroLocalLibrarySearchRefresh").start()
    }, delayMs)
}

internal fun WebDavDriveHook.enqueueLocalLibraryImport(workerManager: Any, book: Any): Any {
    val name = book.callString("getName").ifBlank { LOCAL_LIBRARY_TITLE }
    val path = cloudPathOf(book)
    val sourceUrl = book.callString("getUrl").ifBlank { localLibraryBookUrl(path) }
    val sourceSize = book.javaClass.methods.firstOrNull {
        it.name == "getSize" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(book) as? Number
    val tracker = workerManager.javaClass.getDeclaredField("tracker")
        .apply { isAccessible = true }
        .get(workerManager)
    rememberWorkerManager(workerManager)
    val id = UUID.randomUUID().toString()
    val stateFlow = tracker.javaClass.methods.first {
        it.name == "createTask" && it.parameterTypes.size == 4
    }.apply { isAccessible = true }.invoke(
        tracker,
        id,
        "download",
        path.ifBlank { name },
        newWorkState("Running", 0, null, null, name),
    )
    val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
        .apply { isAccessible = true }
        .newInstance(id, stateFlow)

    Thread({
        runCatching {
            val entry = localLibraryEntry(path) ?: error("本地书库文件不存在")
            val context = currentContext() ?: error("Android context not available")
            val localFile = importCacheFile(
                context.cacheDir,
                "reamicro-local-library",
                entry.name,
            )
            localFile.parentFile?.mkdirs()
            setTrackedWorkState(tracker, id, "Running", 20, null, null, name)
            context.contentResolver.openInputStream(localDocumentUri(entry))?.use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取本地书库文件")
            setTrackedWorkState(tracker, id, "Running", 80, null, null, name)
            val platformFile = platformFile(localFile)
            rememberPendingWebDavImport(platformFile, localFile, sourceUrl, sourceSize?.toLong() ?: entry.size)
            enqueueNativeImport(workerManager, platformFile)
            setTrackedWorkState(tracker, id, "Success", 100, null, sourceUrl, name)
            logWebDav("local library import queued path=$path")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX local library import failed: ${it.stackTraceToString()}")
            setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "本地书库导入失败", null, name)
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
        XposedBridge.log("$LOG_PREFIX failed to render local library account route: ${throwable.stackTraceToString()}")
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
        cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            null,
            factory,
            fillMaxWidthModifier(),
            null,
            composer,
            0,
            4,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render local library folder rows: ${it.stackTraceToString()}")
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
    return cls(AUTH_YUN115_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long.TYPE,
        List::class.java,
        cls(DIR_CLASS),
    ).apply { isAccessible = true }.newInstance(
        "local-library",
        currentLocalLibraryOrderBy(),
        currentLocalLibraryOrderDirection(),
        LOCAL_LIBRARY_TITLE,
        localLibraryUsedSize(),
        localLibraryUsedSize(),
        listOf(dir),
        dir,
    )
}

internal fun WebDavDriveHook.localLibraryDisplayDir(): Any =
    newDir(LOCAL_LIBRARY_ROOT_PATH, localLibraryFolderSummary())

internal fun WebDavDriveHook.localLibraryPickerDir(): Any =
    newDir(LOCAL_LIBRARY_ROOT_PATH, LOCAL_LIBRARY_PICK_FOLDER_TEXT)

internal fun WebDavDriveHook.localLibraryCloudUserInfo(): Any =
    cls(CLOUD_USER_INFO_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long.TYPE,
    ).apply { isAccessible = true }.newInstance(
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

internal fun WebDavDriveHook.refreshLocalLibrary(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
    val flow = synchronized(localLibraryLock) { localLibraryFlow } ?: return
    runCatching {
        val normalizedPath = localLibraryPathArg(path)
        if (force) clearLocalLibraryListCache()
        val items = listLocalLibrary(normalizedPath)
            .filter { it.isDirectory || isSupportedBookFile(it.name) }
            .map {
                if (it.isDirectory) newLocalLibraryCloudFolder(it) else newLocalLibraryCloudBook(it)
            }
        flow.javaClass.methods.first {
            it.name == "setValue" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(flow, pagingDataFrom(items))
        logWebDav("refresh local library path=$normalizedPath items=${items.size}")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to refresh local library: ${it.stackTraceToString()}")
        runCatching {
            flow.javaClass.methods.first { method ->
                method.name == "setValue" && method.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(flow, emptyPagingData())
        }
    }
}

internal fun WebDavDriveHook.refreshLocalLibraryAsync(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
    Thread {
        refreshLocalLibrary(path, force)
    }.start()
}

internal fun WebDavDriveHook.searchLocalLibraryBooks(query: String): List<Any> {
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    val rootsKey = localLibraryRootsKey()
    val cached = localLibrarySearchIndex?.takeIf {
        it.rootsKey == rootsKey && System.currentTimeMillis() - it.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS
    }
    val entries = cached?.entries ?: buildLocalLibrarySearchIndex(
        rootsKey = rootsKey,
        maxDurationMs = LOCAL_LIBRARY_SEARCH_SYNC_BUDGET_MS,
    ).entries
    ensureLocalLibrarySearchIndexAsync(rootsKey)
    val results = entries.filter { entry ->
        isSupportedBookFile(entry.name) &&
            (
                entry.name.contains(needle, ignoreCase = true) ||
                    localLibraryReadablePath(entry).contains(needle, ignoreCase = true)
                )
    }
    logWebDav("local search query=$needle indexed=${entries.size} results=${results.size}")
    return results
        .distinctBy { it.path }
        .let(::sortLocalLibraryEntries)
        .map { newLocalLibraryCloudBook(it) }
}

internal fun WebDavDriveHook.buildLocalLibrarySearchIndex(
    rootsKey: String,
    maxDurationMs: Long,
): LocalLibrarySearchIndex {
    val deadline = System.currentTimeMillis() + maxDurationMs
    val queue = java.util.ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val indexed = mutableListOf<LocalLibraryEntry>()
    localLibraryRoots().forEach { queue.add(it.path) }
    while (queue.isNotEmpty() && visited.size < LOCAL_LIBRARY_SEARCH_MAX_DIRS) {
        if (System.currentTimeMillis() >= deadline) break
        val path = queue.removeFirst()
        if (!visited.add(path)) continue
        val entries = runCatching { listLocalLibrary(path) }
            .onFailure { XposedBridge.log("$LOG_PREFIX local library index skipped $path: ${it.message}") }
            .getOrDefault(emptyList())
        entries.forEach { entry ->
            when {
                entry.isDirectory -> queue.add(entry.path)
                isSupportedBookFile(entry.name) -> indexed.add(entry)
            }
        }
    }
    val index = LocalLibrarySearchIndex(
        rootsKey = rootsKey,
        entries = indexed.distinctBy { it.path },
        createdAtMs = System.currentTimeMillis(),
        complete = queue.isEmpty(),
    )
    synchronized(localLibrarySearchIndexLock) {
        val current = localLibrarySearchIndex
        if (current == null || current.rootsKey != rootsKey || index.entries.size >= current.entries.size || index.complete) {
            localLibrarySearchIndex = index
        }
    }
    logWebDav("local index roots=${localLibraryRoots().size} scanned=${visited.size} entries=${index.entries.size} complete=${index.complete}")
    return index
}

internal fun WebDavDriveHook.ensureLocalLibrarySearchIndexAsync(rootsKey: String = localLibraryRootsKey()) {
    val current = localLibrarySearchIndex
    if (current != null &&
        current.rootsKey == rootsKey &&
        current.complete &&
        System.currentTimeMillis() - current.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS
    ) return
    synchronized(localLibrarySearchIndexLock) {
        if (localLibrarySearchIndexBuilding) return
        localLibrarySearchIndexBuilding = true
    }
    Thread({
        runCatching {
            buildLocalLibrarySearchIndex(
                rootsKey = rootsKey,
                maxDurationMs = LOCAL_LIBRARY_SEARCH_BACKGROUND_BUDGET_MS,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX local library background index failed: ${it.stackTraceToString()}")
        }
        synchronized(localLibrarySearchIndexLock) {
            localLibrarySearchIndexBuilding = false
        }
    }, "ReaMicroLocalLibraryIndex").start()
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
    return dirs.distinctBy { dir ->
        dir.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(dir)
            ?.toString()
            .orEmpty()
    }
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
        val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        } ?: viewModel.javaClass.methods.first {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        }
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("LocalLibraryStorageTreeState", FUNCTION1_CLASS) { args ->
                val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                cls(CLOUD_STORAGE_UI_STATE_CLASS).declaredMethods.first {
                    it.name == "copy" && it.parameterTypes.size == 2
                }.apply { isAccessible = true }.invoke(
                    state,
                    state.javaClass.methods.first { it.name == "getUserInfo" && it.parameterTypes.isEmpty() }
                        .apply { isAccessible = true }
                        .invoke(state),
                    tree,
                )
            },
        )
        logWebDav("update local library tree path=$path size=${tree.size}")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to update local library storage tree: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.newLocalLibraryCloudFolder(entry: LocalLibraryEntry): Any =
    cls(CLOUD_FOLDER_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        java.lang.Boolean.TYPE,
        java.lang.Long.TYPE,
    ).apply { isAccessible = true }.newInstance(
        entry.name,
        entry.path,
        false,
        entry.updatedAt,
    )

internal fun WebDavDriveHook.newLocalLibraryCloudBook(entry: LocalLibraryEntry): Any {
    val url = localLibraryBookUrl(entry.path)
    return cls(CLOUD_BOOK_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        java.lang.Integer.TYPE,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long.TYPE,
        String::class.java,
        String::class.java,
        cls(FLOW_CLASS),
        cls(FLOW_CLASS),
    ).apply { isAccessible = true }.newInstance(
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
    )
}

internal fun WebDavDriveHook.syntheticLocalLibraryBookEntry(path: String): LocalLibraryEntry =
    LocalLibraryEntry(
        name = path.substringAfterLast(':').decodeLocalPathPart().substringAfterLast('/').ifBlank { LOCAL_LIBRARY_TITLE },
        path = path,
        treeUri = "",
        documentId = "",
        isDirectory = false,
        size = 0L,
        updatedAt = System.currentTimeMillis(),
    )

internal fun WebDavDriveHook.localLibraryBookUrl(path: String): String =
    LOCAL_LIBRARY_SOURCE_PREFIX + path

internal fun WebDavDriveHook.localLibraryPrefs(context: Context?): android.content.SharedPreferences? {
    val appContext = context ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext
    return appContext?.getSharedPreferences(LOCAL_LIBRARY_PREFS, Context.MODE_PRIVATE)
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
}

internal fun WebDavDriveHook.saveLocalLibraryOrderDirection(value: String) {
    val normalized = if (isDescendingOrder(value)) "1" else "0"
    localLibraryPrefs(activityProvider())?.edit()
        ?.putString(KEY_LOCAL_ORDER_DIRECTION, normalized)
        ?.apply()
}

internal fun WebDavDriveHook.localLibraryPathArg(path: String): String =
    path.ifBlank { LOCAL_LIBRARY_ROOT_PATH }.let {
        if (it == "root" || it == "/") LOCAL_LIBRARY_ROOT_PATH else it
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
}

internal fun WebDavDriveHook.clearLocalLibraryListCache() {
    localLibraryListCache.clear()
    localLibrarySearchIndex = null
}

internal fun WebDavDriveHook.localLibraryRootsKey(): String =
    localLibraryFolderUris().joinToString("|")

internal fun WebDavDriveHook.addLocalLibraryFolder(uri: Uri) {
    val value = uri.toString()
    if (value.isBlank()) return
    clearLocalLibraryListCache()
    saveLocalLibraryFolderUris(localLibraryFolderUris() + value)
    saveLocalLibraryBrowseDir(LOCAL_LIBRARY_ROOT_PATH)
    synchronized(localLibraryLock) {
        localLibraryFlow = localLibraryFlow ?: createMutableStateFlow(emptyPagingData())
    }
    updateLocalLibraryStorageTrees(LOCAL_LIBRARY_ROOT_PATH)
    refreshLocalLibraryAsync(LOCAL_LIBRARY_ROOT_PATH)
}

internal fun WebDavDriveHook.removeLocalLibraryFolder(uriString: String) {
    clearLocalLibraryListCache()
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
        XposedBridge.log("$LOG_PREFIX failed to launch local library folder picker: ${it.stackTraceToString()}")
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

internal fun WebDavDriveHook.localLibraryReadablePath(entry: LocalLibraryEntry): String =
    localLibraryReadablePath(entry.treeUri, entry.documentId).ifBlank { entry.name }

internal fun WebDavDriveHook.localLibraryReadablePath(treeUri: String, documentId: String): String {
    val cleanId = documentId.replace('\\', '/')
    return when {
        cleanId.startsWith("primary:", ignoreCase = true) ->
            "0/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
        cleanId.startsWith("home:", ignoreCase = true) ->
            "/Documents/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
        cleanId.contains(':') ->
            "/" + cleanId.substringAfter(':').trim('/').ifBlank { cleanId.substringBefore(':') }
        cleanId.isNotBlank() -> "/$cleanId"
        else -> Uri.parse(treeUri).lastPathSegment.orEmpty()
    }.replace("//", "/").trimEnd('/').let { path ->
        if (path == "0") "0/" else path.ifBlank { "/" }
    }
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
    val normalized = localLibraryPathArg(path)
    if (normalized == LOCAL_LIBRARY_ROOT_PATH) return sortLocalLibraryEntries(localLibraryRoots())
    localLibraryListCache[normalized]?.takeIf {
        System.currentTimeMillis() - it.createdAtMs <= LOCAL_LIBRARY_LIST_CACHE_TTL_MS
    }?.let {
        return sortLocalLibraryEntries(it.entries)
    }
    val context = currentContext() ?: return emptyList()
    val decoded = decodeLocalLibraryPath(normalized) ?: return emptyList()
    val treeUri = Uri.parse(decoded.treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, decoded.documentId)
    val resolver = context.contentResolver
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    return runCatching {
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val docId = cursor.stringAt(docIdIndex)
                    val name = cursor.stringAt(nameIndex)
                    if (docId.isBlank() || name.isBlank()) continue
                    if (name.startsWith(".")) continue
                    val mime = cursor.stringAt(mimeIndex)
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    add(
                        LocalLibraryEntry(
                            name = name,
                            path = encodeLocalLibraryPath(decoded.treeUri, docId),
                            treeUri = decoded.treeUri,
                            documentId = docId,
                            isDirectory = isDirectory,
                            size = cursor.longAt(sizeIndex),
                            updatedAt = cursor.longAt(modifiedIndex).takeIf { it > 0L } ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to list local library $normalized: ${it.stackTraceToString()}")
        emptyList()
    }.also {
        localLibraryListCache[normalized] = LocalLibraryListCache(it, System.currentTimeMillis())
    }.let(::sortLocalLibraryEntries)
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

internal fun WebDavDriveHook.queryDocumentEntry(context: Context, documentUri: Uri, treeUri: String, documentId: String): LocalLibraryEntry? {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    return runCatching {
        context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val mime = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
            LocalLibraryEntry(
                name = name.ifBlank { LOCAL_LIBRARY_TITLE },
                path = encodeLocalLibraryPath(treeUri, documentId),
                treeUri = treeUri,
                documentId = documentId,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)),
                updatedAt = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    .takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
    }.getOrNull()
}

internal fun WebDavDriveHook.queryDocumentName(context: Context, documentUri: Uri): String =
    runCatching {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else ""
        }.orEmpty()
    }.getOrDefault("")

internal fun WebDavDriveHook.encodeLocalLibraryPath(treeUri: String, documentId: String): String =
    "$LOCAL_LIBRARY_PATH_PREFIX${treeUri.localPathPart()}:${documentId.localPathPart()}"

internal fun WebDavDriveHook.decodeLocalLibraryPath(path: String): LocalLibraryPath? {
    if (!path.startsWith(LOCAL_LIBRARY_PATH_PREFIX)) return null
    val body = path.removePrefix(LOCAL_LIBRARY_PATH_PREFIX)
    val parts = body.split(':', limit = 2)
    if (parts.size != 2) return null
    return LocalLibraryPath(
        treeUri = parts[0].decodeLocalPathPart(),
        documentId = parts[1].decodeLocalPathPart(),
    )
}

internal fun WebDavDriveHook.localDocumentUri(entry: LocalLibraryEntry): Uri =
    DocumentsContract.buildDocumentUriUsingTree(Uri.parse(entry.treeUri), entry.documentId)

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
