package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.R
import com.reamicro.fix.logging.ModuleLogLevel
import com.reamicro.fix.logging.legacyModuleLogLevel
import com.reamicro.fix.webdav.CloudDownloadCancelledException
import com.reamicro.fix.webdav.CancellableWebDavDownload
import com.reamicro.fix.webdav.WebDavBackupSnapshot
import com.reamicro.fix.webdav.WebDavCredentials
import com.reamicro.fix.webdav.WebDavHttpException
import com.reamicro.fix.webdav.WebDavImportSource
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import org.w3c.dom.Element
import com.reamicro.fix.hook.webdav.*

// WebDavDriveHook 的 WebDAV 协议与账号簇。
//
// PROPFIND/MKCOL/MOVE/DELETE/GET/PUT、Alist 兼容、凭据与浏览目录读写、登录与授权。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.canShowWebDavEntry(): Boolean =
    settingsProvider().canRunWebDavCloud

internal fun WebDavDriveHook.shouldAllowCleartext(args: Array<Any?>?): Boolean {
    if (webDavCleartextAllowed.get() == true) return true
    val host = cleartextHostOf(args?.firstOrNull())
    return host.isNotBlank() && webDavCleartextAllowedHosts[host] == true
}

internal fun WebDavDriveHook.cleartextHostOf(value: Any?): String =
    when (value) {
        null -> ""
        is URL -> value.host.orEmpty()
        is URI -> value.host.orEmpty()
        is String -> {
            val text = value.trim()
            if (text.isBlank()) {
                ""
            } else if (text.contains("://")) {
                runCatching { URI(text).host.orEmpty() }
                    .getOrDefault("")
                    .ifBlank { runCatching { URL(text).host.orEmpty() }.getOrDefault("") }
            } else {
                text.substringBefore('/').substringBefore(':').trim()
            }
        }
        else -> cleartextHostOf(value.toString())
    }

internal fun WebDavDriveHook.logCleartextAllowed(value: Any?, via: String) {
    val host = cleartextHostOf(value).ifBlank { "*" }
    if (webDavCleartextAllowedLoggedHosts.putIfAbsent("$via|$host", true) == null) {
        logWebDav("cleartext allowed via=$via host=$host")
    }
}

internal fun WebDavDriveHook.renderSyncWebDavAvailableCard(composer: Any, onAuthorize: Any?) {
    runCatching {
        withWebDavIcon {
            method(AUTH_CARD_CLASS, DRIVE_OTHER_AVAILABLE_CARD_METHOD, 4).invoke(
                null,
                listOf(BACKUP_TYPE_WEBDAV),
                functionProxy("WebDavOnlyAuthorize", FUNCTION1_CLASS) {
                    onAuthorize?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                    targetUnit()
                },
                composer,
                0,
            )
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV sync available card: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.renderSyncWebDavAuthorizedCard(
    composer: Any,
    onSetupDefaultDir: Any?,
    onPick: Any?,
    isRunning: Boolean = false,
): Boolean {
    return runCatching {
        val icon = getComposeWebDavVector() ?: return@runCatching false
        val backupDir = currentWebDavBackupDir()
        webDavRenderingSyncAuthCard.set(true)
        try {
            method(AUTH_CARD_CLASS, DRIVE_AUTH_CARD_METHOD, 8).invoke(
                null,
                icon,
                "上传到 $WEBDAV_TITLE",
                webDavDisplayDir(backupDir),
                isRunning,
                functionProxy("WebDavSyncDefaultDir", FUNCTION0_CLASS) {
                    if (backupDir.isBlank()) {
                        logWebDav("sync default click setup dir")
                        onSetupDefaultDir?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                    } else {
                        logWebDav("sync default click upload dir=$backupDir")
                        onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, backupDir) }
                    }
                    targetUnit()
                },
                functionProxy("WebDavSyncPickDir", FUNCTION0_CLASS) {
                    logWebDav("sync pick click")
                    onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, null) }
                    targetUnit()
                },
                composer,
                0,
            )
            logWebDav("sync auth card rendered dir=$backupDir")
            true
        } finally {
            webDavRenderingSyncAuthCard.remove()
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV sync auth card: ${it.stackTraceToString()}")
    }.getOrDefault(false)
}

internal fun WebDavDriveHook.renderImportWebDavRow(composer: Any) {
    runCatching {
        withWebDavIcon {
            method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_UNAUTH_ROW_METHOD, 4).invoke(
                null,
                BACKUP_TYPE_WEBDAV,
                webDavImportClickCallback(),
                composer,
                0,
            )
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV import row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.webDavImportClickCallback(): Any =
    functionProxy("WebDavImportLogin", FUNCTION0_CLASS) {
        showWebDavLoginPage()
        targetUnit()
    }

internal fun WebDavDriveHook.enqueueWebDavDownload(workerManager: Any, book: Any): Any {
    val name = book.javaClass.methods.firstOrNull {
        it.name == "getName" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(book)?.toString().orEmpty().ifBlank { WEBDAV_TITLE }
    val path = cloudPathOf(book)
    val sourceUrl = book.javaClass.methods.firstOrNull {
        it.name == "getUrl" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(book)?.toString().orEmpty()
    val sourceSize = book.javaClass.methods.firstOrNull {
        it.name == "getSize" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(book) as? Number
    val uniqueName = path.ifBlank { name }
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
        uniqueName,
        newWorkState("Running", 0, null, null, name),
    )
    val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
        .apply { isAccessible = true }
        .newInstance(id, stateFlow)
    val localFile = importCacheFile(
        currentContext()?.cacheDir,
        "reamicro-webdav",
        name,
    )
    val downloadKey = webDavDownloadKey(path, name)
    val token = CancellableWebDavDownload(
        id = id,
        key = downloadKey,
        name = name,
        tracker = tracker,
        localFile = localFile,
        createdAtMs = System.currentTimeMillis(),
    )
    runningWebDavDownloads[downloadKey] = token

    val thread = Thread({
        runCatching {
            throwIfWebDavDownloadCancelled(token)
            setTrackedWorkState(tracker, id, "Running", 2, null, null, name)
            var lastProgress = 0
            webDavDownload(path, localFile) { downloadProgress ->
                throwIfWebDavDownloadCancelled(token)
                val progress = (downloadProgress * 70 / 100).coerceIn(2, 70)
                if (progress >= lastProgress + 4 || progress == 70) {
                    lastProgress = progress
                    setTrackedWorkState(tracker, id, "Running", progress, null, null, name)
                }
            }
            throwIfWebDavDownloadCancelled(token)
            setTrackedWorkState(tracker, id, "Running", 78, null, null, name)
            val platformFile = platformFile(localFile)
            rememberPendingWebDavImport(platformFile, localFile, sourceUrl, sourceSize?.toLong())
            setTrackedWorkState(tracker, id, "Running", 90, null, null, name)
            enqueueNativeImport(workerManager, platformFile)
            setTrackedWorkState(tracker, id, "Success", 100, null, sourceUrl.ifBlank { localFile.absolutePath }, name)
            logWebDav("download complete native import queued path=$path url=${sourceUrl.redactWebDavUrl()}")
        }.onFailure {
            if (it is CloudDownloadCancelledException || token.cancelRequested) {
                cancelTrackedWork(tracker, id)
                cleanupDownloadToken(token)
                logWebDav("download cancelled path=$path")
            } else {
                XposedBridge.log("$LOG_PREFIX WebDAV tracked download failed: ${it.stackTraceToString()}")
                setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV download failed", null, name)
            }
        }.also {
            runningWebDavDownloads.remove(downloadKey, token)
            webDavDownloadCancelPrompts.remove(id)
        }
    }, "ReaMicroWebDavDownload")
    token.thread = thread
    thread.start()

    return handle
}

internal fun WebDavDriveHook.enqueueWebDavBackup(workerManager: Any, bookId: Long, dir: String): Any {
    rememberWorkerManager(workerManager)
    val tracker = workerManager.javaClass.getDeclaredField("tracker")
        .apply { isAccessible = true }
        .get(workerManager)
    val normalizedDir = normalizeWebDavPath(dir.ifBlank { currentWebDavBackupDir() })
    cancelExistingBackupTasks(tracker, bookId)
    val id = UUID.randomUUID().toString()
    val stateFlow = tracker.javaClass.methods.first {
        it.name == "createTask" && it.parameterTypes.size == 4
    }.apply { isAccessible = true }.invoke(
        tracker,
        id,
        "backup:$BACKUP_TYPE_YUN115",
        bookId.toString(),
        newWorkState("Running", 0, null, null, null),
    )
    val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
        .apply { isAccessible = true }
        .newInstance(id, stateFlow)

    webDavRunningBackupBookIds[bookId] = true
    Thread({
        try {
            runCatching {
                val book = findLocalBookById(bookId) ?: error("Book $bookId not found")
                val fileName = exportFileName(book)
                val localFile = File(
                    currentContext()?.cacheDir ?: File("/data/local/tmp"),
                    "reamicro-webdav-backup/${System.currentTimeMillis()}_${fileName.safeWebDavFileName()}",
                )
                localFile.parentFile?.mkdirs()
                setTrackedWorkState(tracker, id, "Running", 8, null, null, fileName)
                localFile.outputStream().use { output ->
                    zipBookDirectory(book, output)
                }
                setTrackedWorkState(tracker, id, "Running", 45, null, null, fileName)
                val remotePath = childWebDavPath(normalizedDir, fileName)
                webDavUpload(remotePath, localFile)
                setTrackedWorkState(tracker, id, "Running", 88, null, null, fileName)
                val updatedBook = copyBookWithBackup(book, BACKUP_TYPE_WEBDAV, remotePath, "")
                updateLocalBook(updatedBook)
                val backupBook = newCloudBook(
                    WebDavEntry(
                        name = fileName,
                        path = remotePath,
                        isDirectory = false,
                        size = localFile.length(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                webDavRecentBackups[bookId] = WebDavBackupSnapshot(updatedBook, backupBook)
                updateWebDavBackupViewModels(bookId, updatedBook, backupBook)
                refreshWebDavBackupViewModelsLater(bookId, updatedBook, backupBook)
                refreshWebDavLibrary(parentWebDavPath(remotePath))
                setTrackedWorkState(tracker, id, "Success", 100, null, remotePath, fileName)
                logWebDav("backup uploaded bookId=$bookId remote=$remotePath metadata updated")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX WebDAV tracked backup failed: ${it.stackTraceToString()}")
                setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE)
            }
        } finally {
            webDavRunningBackupBookIds.remove(bookId)
            logWebDav("backup running cleared bookId=$bookId")
        }
    }, "ReaMicroWebDavBackup").start()

    return handle
}

internal fun WebDavDriveHook.tryHandleRunningWebDavDownloadTap(book: Any): Boolean {
    if (!canCancelCloudDownload()) return false
    val path = cloudPathOf(book)
    val name = book.callString("getName").ifBlank { WEBDAV_TITLE }
    val token = runningWebDavDownloads[webDavDownloadKey(path, name)] ?: return false
    val now = System.currentTimeMillis()
    val last = webDavDownloadCancelPrompts[token.id] ?: 0L
    if (now - last > DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS) {
        webDavDownloadCancelPrompts[token.id] = now
        showToast("\u518d\u70b9\u4e00\u6b21\u53d6\u6d88\u4e0b\u8f7d")
        return true
    }
    token.cancelRequested = true
    token.thread?.interrupt()
    cancelTrackedWork(token.tracker, token.id)
    cleanupDownloadToken(token)
    runningWebDavDownloads.remove(token.key, token)
    webDavDownloadCancelPrompts.remove(token.id)
    showToast("\u5df2\u53d6\u6d88\u4e0b\u8f7d\u5e76\u6e05\u7406\u7f13\u5b58")
    return true
}

internal fun WebDavDriveHook.throwIfWebDavDownloadCancelled(token: CancellableWebDavDownload) {
    if (token.cancelRequested || Thread.currentThread().isInterrupted) {
        throw CloudDownloadCancelledException()
    }
}

internal fun WebDavDriveHook.webDavDownloadKey(path: String, name: String): String =
    normalizeWebDavPath(path.ifBlank { name })

internal fun WebDavDriveHook.updateWebDavBackupViewModels(bookId: Long, book: Any, backup: Any) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        Handler(Looper.getMainLooper()).post {
            updateWebDavBackupViewModels(bookId, book, backup)
        }
        return
    }
    val viewModels = synchronized(webDavBackupViewModels) {
        webDavBackupViewModels.mapNotNull { it.get() }.also {
            webDavBackupViewModels.removeAll { ref -> ref.get() == null }
        }
    }
    if (viewModels.isEmpty()) {
        logWebDav("backup ui state update skipped bookId=$bookId no active viewModel")
        return
    }
    viewModels.forEach { viewModel ->
        runCatching {
            val vmBookId = (fieldValue(viewModel, "bookId") as? Number)?.toLong()
                ?: fieldValue(viewModel, "bookId")?.toString()?.toLongOrNull()
                ?: return@runCatching
            if (vmBookId != bookId) return@runCatching
            val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            } ?: viewModel.javaClass.methods.first {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            }
            updateMethod.apply { isAccessible = true }.invoke(
                viewModel,
                functionProxy("WebDavBackupUiState", FUNCTION1_CLASS) { args ->
                    val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                    state.javaClass.methods.first {
                        it.name == "copy" && it.parameterTypes.size == 3
                    }.apply { isAccessible = true }.invoke(state, book, backup, false)
                },
            )
            logWebDav("backup ui state updated bookId=$bookId")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update WebDAV backup ui state: ${it.stackTraceToString()}")
        }
    }
}

internal fun WebDavDriveHook.refreshWebDavBackupViewModelsLater(bookId: Long, book: Any, backup: Any) {
    Handler(Looper.getMainLooper()).postDelayed({
        updateWebDavBackupViewModels(bookId, book, backup)
        logWebDav("backup ui state delayed refresh bookId=$bookId")
    }, 800L)
}

internal fun WebDavDriveHook.renderWebDavLoginRoute(navGraphScope: Any, composer: Any) {
    runCatching {
        val factory = functionProxy("WebDavLoginAndroidView", FUNCTION1_CLASS) { args ->
            val context = args?.getOrNull(0) as? Context
                ?: activityProvider()
                ?: error("No context for WebDAV login route")
            createWebDavLoginRouteView(context, navGraphScope)
        }
        val modifier = fillMaxSizeModifier()
        cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            null,
            factory,
            modifier,
            null,
            composer,
            0,
            4,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV login route: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.renderWebDavAccountRoute(navGraphScope: Any, composer: Any): Boolean {
    markWebDavAccountContext(navGraphScope)
    webDavAccountNavGraphScope.set(navGraphScope)
    webDavAccountScreenDepth.set((webDavAccountScreenDepth.get() ?: 0) + 1)
    try {
        method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_SCREEN_METHOD, 3).invoke(
            null,
            navGraphScope,
            composer,
            0,
        )
        return true
    } catch (throwable: Throwable) {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV account route: ${throwable.stackTraceToString()}")
        return false
    } finally {
        val next = (webDavAccountScreenDepth.get() ?: 0) - 1
        if (next <= 0) {
            webDavAccountScreenDepth.remove()
        } else {
            webDavAccountScreenDepth.set(next)
        }
        webDavAccountNavGraphScope.remove()
    }
}

internal fun WebDavDriveHook.markWebDavAccountContext(navGraphScope: Any) {
    webDavAccountNavGraphScopeStrong = navGraphScope
    webDavAccountNavGraphScopeRef = WeakReference(navGraphScope)
    webDavAccountRouteRenderAtMs = System.currentTimeMillis()
}

internal fun WebDavDriveHook.clearWebDavAccountContext() {
    webDavAccountNavGraphScopeStrong = null
    webDavAccountNavGraphScopeRef = null
    webDavAccountRouteRenderAtMs = 0L
}

internal fun WebDavDriveHook.isWebDavAccountContext(): Boolean =
    (webDavAccountScreenDepth.get() ?: 0) > 0 ||
        webDavAccountNavGraphScope.get() != null ||
        webDavAccountNavGraphScopeStrong != null ||
        webDavAccountNavGraphScopeRef?.get() != null ||
        (System.currentTimeMillis() - webDavAccountRouteRenderAtMs) in 0..ACCOUNT_CONTEXT_GRACE_MS

internal fun WebDavDriveHook.createWebDavLoginRouteView(context: Context, navGraphScope: Any): View {
    val prefs = context.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
    return createWebDavLoginContent(
        context = context,
        prefs = prefs,
        onBack = { popBackStack(navGraphScope) },
        onSaved = {
            popBackStack(navGraphScope)
            Handler(Looper.getMainLooper()).post {
                refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                navigateStorage(navGraphScope)
            }
        },
    )
}

internal fun WebDavDriveHook.webDavAuth(): Any {
    val credentials = webDavCredentials()
    return cls(AUTH_BAIDU_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long.TYPE,
        String::class.java,
        String::class.java,
    ).apply { isAccessible = true }.newInstance(
        credentials?.username.orEmpty(),
        currentWebDavOrderBy(),
        currentWebDavOrderDirection(),
        "webdav",
        0L,
        0L,
        currentWebDavBackupDir(),
        "webdav",
    )
}

internal fun WebDavDriveHook.webDavYun115Auth(): Any {
    val backupDir = currentWebDavBackupDir()
    val dir = newDir(backupDir, webDavDisplayDir(backupDir))
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
        "webdav",
        "user_utime",
        "0",
        WEBDAV_TITLE,
        0L,
        0L,
        listOf(dir),
        dir,
    )
}

internal fun WebDavDriveHook.webDavCloudUserInfo(): Any {
    val credentials = webDavCredentials()
    return cls(CLOUD_USER_INFO_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long.TYPE,
    ).apply { isAccessible = true }.newInstance(
        "webdav",
        credentials?.username?.ifBlank { WEBDAV_TITLE } ?: WEBDAV_TITLE,
        "",
        WEBDAV_TITLE,
        "",
        null,
        0L,
        0L,
    )
}

internal fun WebDavDriveHook.webDavResult(block: () -> Any?): Any =
    runCatching { block() }
        .fold(
            onSuccess = { kotlinResultSuccess(it) },
            onFailure = {
                XposedBridge.log("$LOG_PREFIX WebDAV repository operation failed: ${it.stackTraceToString()}")
                kotlinResultFailure(it)
            },
        )

internal fun WebDavDriveHook.webDavLibraryFlow(): Any =
    synchronized(webDavLibraryLock) {
        webDavLibraryFlow ?: createMutableStateFlow(emptyPagingData()).also {
            webDavLibraryFlow = it
            refreshWebDavLibraryAsync(currentWebDavBrowseDir())
        }
    }

internal fun WebDavDriveHook.webDavAccountAuthFlow(): Any =
    synchronized(webDavAccountAuthLock) {
        webDavAccountAuthFlow ?: createMutableStateFlow(webDavAuth()).also {
            webDavAccountAuthFlow = it
        }
    }

internal fun WebDavDriveHook.updateWebDavAccountAuthFlow() {
    val flow = synchronized(webDavAccountAuthLock) { webDavAccountAuthFlow }
    setMutableStateFlowValue(flow, webDavAuth())
}

internal fun WebDavDriveHook.onWebDavLoginSaved() {
    updateWebDavAccountAuthFlow()
    val path = currentWebDavBrowseDir()
    updateWebDavStorageTrees(path)
    refreshWebDavLibraryAsync(path)
}

internal fun WebDavDriveHook.refreshWebDavLibrary(path: String = currentWebDavBrowseDir()) {
    val flow = synchronized(webDavLibraryLock) { webDavLibraryFlow } ?: return
    runCatching {
        val normalizedPath = normalizeWebDavPath(path.ifBlank { "/" })
        val items = listWebDav(normalizedPath)
            .filter { it.isDirectory || isSupportedBookFile(it.name) }
            .map {
                if (it.isDirectory) newCloudFolder(it) else newCloudBook(it)
            }
        logWebDav("refresh library path=$normalizedPath items=${items.size}")
        flow.javaClass.methods.first {
            it.name == "setValue" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(flow, pagingDataFrom(items))
        logWebDav("refresh library setValue done path=$normalizedPath")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to refresh WebDAV library: ${it.stackTraceToString()}")
        runCatching {
            flow.javaClass.methods.first { method ->
                method.name == "setValue" && method.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(flow, emptyPagingData())
        }
    }
}

internal fun WebDavDriveHook.refreshWebDavLibraryAsync(path: String = currentWebDavBrowseDir()) {
    Thread {
        refreshWebDavLibrary(path)
    }.start()
}

internal fun WebDavDriveHook.searchWebDavBooks(query: String): List<Any> {
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    searchAlistBooks(needle)?.let { entries ->
        logWebDav("OpenList/AList search query=$needle results=${entries.size}")
        return entries
            .distinctBy { normalizeWebDavPath(it.path) }
            .let(::sortWebDavEntries)
            .map { newCloudBook(it) }
    }
    val queue = java.util.ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val results = mutableListOf<WebDavEntry>()
    queue.add("/")
    while (queue.isNotEmpty() && visited.size < WEBDAV_SEARCH_MAX_DIRS) {
        val dir = normalizeWebDavPath(queue.removeFirst())
        if (!visited.add(dir)) continue
        val entries = runCatching { listWebDav(dir) }
            .onFailure { XposedBridge.log("$LOG_PREFIX WebDAV search skipped $dir: ${it.message}") }
            .getOrDefault(emptyList())
        entries.forEach { entry ->
            when {
                entry.isDirectory -> queue.add(entry.path)
                isSupportedBookFile(entry.name) && entry.name.contains(needle, ignoreCase = true) -> results.add(entry)
            }
        }
    }
    logWebDav("search query=$needle scanned=${visited.size} results=${results.size}")
    return results
        .distinctBy { normalizeWebDavPath(it.path) }
        .let(::sortWebDavEntries)
        .map { newCloudBook(it) }
}

internal fun WebDavDriveHook.searchAlistBooks(query: String): List<WebDavEntry>? =
    runCatching {
        val credentials = webDavCredentials() ?: return null
        webDavRemoteClient.searchAlistBooks(
            credentials = credentials,
            query = query,
            resultLimit = HOME_SEARCH_RESULT_LIMIT,
            pageSize = ALIST_SEARCH_PAGE_SIZE,
            maxPages = ALIST_SEARCH_MAX_PAGES,
        )?.mapNotNull { entry ->
            if (!isSupportedBookFile(entry.name)) return@mapNotNull null
            WebDavEntry(
                name = entry.name,
                path = entry.path,
                isDirectory = false,
                size = entry.size,
                updatedAt = entry.updatedAt,
            )
        }
    }.onFailure {
        logWebDav("OpenList/AList search unavailable: ${it.message}")
    }.getOrNull()

internal fun WebDavDriveHook.updateHomeWebDavSearchResults(
    viewModel: Any?,
    webDavResults: List<Any>,
    localResults: List<Any>,
    onlineResults: List<Any>? = null,
) {
    if (viewModel == null) return
    rememberHomeViewModelDependencies(viewModel)
    runCatching {
        val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        } ?: viewModel.javaClass.methods.first {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        }
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("WebDavHomeSearchState", FUNCTION1_CLASS) { args ->
                val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                runCatching {
                    val current = homeCloudSearchResults(state)
                    val merged = linkedMapOf<Any?, Any?>()
                    current?.forEach { (key, value) -> merged[key] = value }
                    if (webDavResults.isEmpty()) {
                        merged.remove(BACKUP_TYPE_WEBDAV)
                    } else {
                        merged[BACKUP_TYPE_WEBDAV] = webDavResults
                    }
                    if (localResults.isEmpty()) {
                        merged.remove(BACKUP_TYPE_LOCAL_LIBRARY)
                    } else {
                        merged[BACKUP_TYPE_LOCAL_LIBRARY] = localResults
                    }
                    if (onlineResults != null) {
                        if (onlineResults.isEmpty()) {
                            merged.remove(BACKUP_TYPE_ONLINE_COMPLETION)
                        } else {
                            merged[BACKUP_TYPE_ONLINE_COMPLETION] = onlineResults
                        }
                    }
                    copyHomeUiStateWithCloudResults(state, merged)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed to copy WebDAV home search state: ${it.stackTraceToString()}")
                }.getOrDefault(state)
            },
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to update WebDAV home search state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.addHomeWebDavSearchSection(lazyListScope: Any, type: Int, title: String, results: List<*>, intentReceiver: Any) {
    runCatching {
        val itemMethod = lazyListScope.javaClass.methods.firstOrNull {
            it.name == "item" && it.parameterTypes.size == 3
        } ?: lazyListScope.javaClass.declaredMethods.first {
            it.name == "item" && it.parameterTypes.size == 3
        }
        itemMethod.isAccessible = true
        itemMethod.invoke(
            lazyListScope,
            "cloud-search-$type-${title.ifBlank { type.toString() }.hashCode()}",
            type,
            functionProxy("WebDavHomeSearchItem", FUNCTION3_CLASS) { args ->
                val item = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                val composer = args.getOrNull(1) ?: return@functionProxy targetUnit()
                val render = {
                    method(HOME_SEARCH_BAR_CLASS, HOME_CLOUD_RESULT_LIST_METHOD, 6).invoke(
                        null,
                        item,
                        type,
                        results,
                        functionProxy("WebDavHomeSearchTap", FUNCTION1_CLASS) { tapArgs ->
                            tapArgs?.getOrNull(0)?.let { book ->
                                if (isOnlineCompletionPath(cloudPathOf(book))) {
                                    handleOnlineCompletionSearchTap(book)
                                } else if (!tryHandleRunningCloudDownloadTap(book, type)) {
                                    homeSearchTapMethod().invoke(
                                        null,
                                        intentReceiver,
                                        book,
                                    )
                                }
                            }
                            targetUnit()
                        },
                        composer,
                        0,
                    )
                }
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    pushLocalLibraryIcon()
                    try {
                        render()
                    } finally {
                        popLocalLibraryIcon()
                    }
                } else if (isOnlineCompletionRenderType(type)) {
                    pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type, title))
                    try {
                        withWebDavIcon { render() }
                    } finally {
                        popOnlineCompletionCloudTitle()
                    }
                } else {
                    withWebDavIcon { render() }
                }
                targetUnit()
            },
        )
        val firstBook = results.firstOrNull()
        val firstType = firstBook?.callInt("getType") ?: 0
        val firstId = firstBook?.callString("getId").orEmpty()
        val firstPath = cloudPathOf(firstBook)
        logWebDav(
            "home search section rendered type=$type results=${results.size} " +
                "firstType=$firstType firstId=$firstId firstPath=$firstPath",
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render WebDAV home search section: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.webDavDirTree(path: String): List<Any> {
    val normalized = normalizeWebDavPath(path.ifBlank { "/" })
    val dirs = mutableListOf<Any>(newDir("/", ROOT_DIR_NAME))
    if (normalized == "/") return dirs
    val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
    var current = ""
    segments.forEach { segment ->
        current = normalizeWebDavPath("$current/$segment")
        dirs.add(newDir(current, segment))
    }
    return dirs
}

internal fun WebDavDriveHook.updateWebDavStorageTrees(path: String) {
    val viewModels = synchronized(webDavStorageViewModels) {
        webDavStorageViewModels.mapNotNull { it.get() }.also {
            webDavStorageViewModels.removeAll { ref -> ref.get() == null }
        }
    }
    viewModels.forEach { updateWebDavStorageTree(it, path) }
}

internal fun WebDavDriveHook.updateWebDavStorageTree(viewModel: Any, path: String) {
    runCatching {
        val tree = webDavDirTree(path)
        val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        } ?: viewModel.javaClass.methods.first {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        }
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("WebDavStorageTreeState", FUNCTION1_CLASS) { args ->
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
        logWebDav("update storage tree path=${normalizeWebDavPath(path)} size=${tree.size}")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to update WebDAV storage tree: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.syntheticWebDavBookEntry(path: String): WebDavEntry {
    val normalized = normalizeWebDavPath(path)
    val name = normalized.substringAfterLast('/').ifBlank { WEBDAV_TITLE }
    logWebDav("getInfo fallback synthetic path=$normalized")
    return WebDavEntry(
        name = name,
        path = normalized,
        isDirectory = false,
        size = 0L,
        updatedAt = System.currentTimeMillis(),
    )
}

internal fun WebDavDriveHook.webDavBookUrl(path: String): String =
    WEBDAV_SOURCE_PREFIX + normalizeWebDavPath(path)

internal fun WebDavDriveHook.webDavLocalBookFlow(url: String): Any {
    if (url.isBlank()) return emptyFlow()
    return runCatching {
        val bookshelf = currentBookshelfRepository() ?: return@runCatching emptyFlow()
        bookshelf.javaClass.methods.first {
            it.name == "liveBookByUrl" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(bookshelf, url)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to create WebDAV local book flow: ${it.stackTraceToString()}")
    }.getOrDefault(emptyFlow())
}

internal fun WebDavDriveHook.webDavWorkFlow(uniqueName: String): Any {
    if (uniqueName.isBlank()) return emptyFlow()
    return runCatching {
        val tracker = currentWorkTracker() ?: return@runCatching emptyFlow()
        tracker.javaClass.methods.first {
            it.name == "getWorkInfosForUniqueWorkFlow" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(tracker, uniqueName)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to create WebDAV work flow: ${it.stackTraceToString()}")
    }.getOrDefault(emptyFlow())
}

internal fun WebDavDriveHook.hasWebDavLogin(context: Context?): Boolean {
    val prefs = webDavPrefs(context) ?: return false
    val hasCredentials = !prefs.getString(KEY_URL, "").isNullOrBlank() &&
        !prefs.getString(KEY_USERNAME, "").isNullOrBlank() &&
        !prefs.getString(KEY_PASSWORD, "").isNullOrBlank()
    return hasCredentials && prefs.getBoolean(KEY_AUTHORIZED, hasCredentials)
}

internal fun WebDavDriveHook.webDavCredentials(): WebDavCredentials? {
    val prefs = webDavPrefs(activityProvider()) ?: return null
    val url = normalizeServerUrl(prefs.getString(KEY_URL, "").orEmpty())
    val username = prefs.getString(KEY_USERNAME, "").orEmpty()
    val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
    return if (url.isBlank() || username.isBlank() || password.isBlank()) {
        null
    } else {
        WebDavCredentials(url, username, password)
    }
}

internal fun WebDavDriveHook.webDavPrefs(context: Context?): android.content.SharedPreferences? {
    val appContext = context ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext
    return appContext?.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
}

internal fun WebDavDriveHook.currentWebDavBrowseDir(): String {
    val prefs = webDavPrefs(activityProvider())
    val saved = prefs?.getString(KEY_BROWSE_DIR, null)
        ?: prefs?.getString(KEY_DIR_LEGACY, null)
        ?: DEFAULT_DIR
    return normalizeWebDavPath(saved.ifBlank { DEFAULT_DIR })
}

internal fun WebDavDriveHook.currentWebDavBackupDir(): String =
    normalizeWebDavPath(webDavPrefs(activityProvider())?.getString(KEY_BACKUP_DIR, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR })

internal fun WebDavDriveHook.currentWebDavOrderBy(): String =
    webDavPrefs(activityProvider())?.getString(KEY_ORDER_BY, WEBDAV_DEFAULT_ORDER_BY)
        ?.takeIf { it in setOf("name", "size", "time") }
        ?: WEBDAV_DEFAULT_ORDER_BY

internal fun WebDavDriveHook.currentWebDavOrderDirection(): String =
    webDavPrefs(activityProvider())?.getString(KEY_ORDER_DIRECTION, DEFAULT_ORDER_DIRECTION_DESC)
        ?.takeIf { it in setOf("0", "1", "ASC", "DESC", "asc", "desc") }
        ?: DEFAULT_ORDER_DIRECTION_DESC

internal fun WebDavDriveHook.webDavDisplayDir(path: String): String {
    val normalized = normalizeWebDavPath(path.ifBlank { "/" })
    if (normalized == "/") return ROOT_DIR_NAME
    return (ROOT_DIR_NAME + normalized)
        .trimEnd('/')
        .replace("/", " \u25cb ")
        .uppercase(Locale.ROOT)
}

internal fun WebDavDriveHook.saveWebDavBrowseDir(path: String) {
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_BROWSE_DIR, normalizeWebDavPath(path.ifBlank { "/" }))
        ?.apply()
}

internal fun WebDavDriveHook.saveWebDavBackupDir(path: String) {
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_BACKUP_DIR, normalizeWebDavPath(path.ifBlank { "/" }))
        ?.apply()
}

internal fun WebDavDriveHook.saveWebDavOrderBy(value: String) {
    val normalized = when (value) {
        "name", "size", "time" -> value
        "file_name", "name_enhanced" -> "name"
        "file_size" -> "size"
        "user_utime", "updated_at", "created_at" -> "time"
        else -> WEBDAV_DEFAULT_ORDER_BY
    }
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_ORDER_BY, normalized)
        ?.apply()
}

internal fun WebDavDriveHook.saveWebDavOrderDirection(value: String) {
    val normalized = if (isDescendingOrder(value)) "1" else "0"
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_ORDER_DIRECTION, normalized)
        ?.apply()
}

internal fun WebDavDriveHook.clearWebDavLogin() {
    webDavPrefs(activityProvider())?.edit()
        ?.remove(KEY_URL)
        ?.remove(KEY_USERNAME)
        ?.remove(KEY_PASSWORD)
        ?.remove(KEY_AUTHORIZED)
        ?.apply()
    synchronized(webDavLibraryLock) {
        webDavLibraryFlow = null
    }
}

internal fun WebDavDriveHook.childWebDavPath(parent: String, name: String): String {
    val cleanName = name.trim().trim('/')
    return normalizeWebDavPath("${normalizeWebDavPath(parent).trimEnd('/')}/$cleanName")
}

internal fun WebDavDriveHook.webDavPathArg(path: String): String =
    if (path.isBlank() || path == "root") "/" else normalizeWebDavPath(path)

internal fun WebDavDriveHook.sortWebDavEntries(entries: List<WebDavEntry>): List<WebDavEntry> =
    sortStorageEntries(
        entries = entries,
        orderBy = currentWebDavOrderBy(),
        direction = currentWebDavOrderDirection(),
        isDirectory = { it.isDirectory },
        name = { it.name },
        size = { it.size },
        updatedAt = { it.updatedAt },
    )

internal fun WebDavDriveHook.listWebDav(path: String): List<WebDavEntry> {
    val credentials = webDavCredentials() ?: return emptyList()
    val normalizedPath = normalizeWebDavPath(path.ifBlank { "/" })
    val requestUrl = webDavRemoteClient.buildUrl(credentials.url, normalizedPath, directory = true)
    logWebDav("PROPFIND ${requestUrl.toString().redactWebDavUrl()}")
    return runCatching {
        val response = webDavRemoteClient.request(
            credentials = credentials,
            method = "PROPFIND",
            path = normalizedPath,
            directory = true,
            body = WEBDAV_PROPFIND_BODY,
            headers = mapOf(
                "Depth" to "1",
                "Content-Type" to "application/xml; charset=utf-8",
            ),
        )
        val code = response.code
        val responseBody = response.bodyString.orEmpty()
        logWebDav("PROPFIND response path=$normalizedPath code=$code bytes=${responseBody.length}")
        if (code !in 200..299 && code != 207) {
            throw WebDavHttpException(code, "WebDAV PROPFIND failed: HTTP $code")
        }
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8)))
        val nodes = document.getElementsByTagNameNS("*", "response")
        val current = normalizedPath
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as? Element ?: return@mapNotNull null
            val href = element.textOf("href")
            val entryPath = normalizeWebDavPath(webDavRemoteClient.pathFromHref(credentials.url, href))
            if (entryPath == current) return@mapNotNull null
            val name = element.textOf("displayname").ifBlank { entryPath.substringAfterLast('/') }
            if (name.isBlank()) return@mapNotNull null
            val isDirectory = element.getElementsByTagNameNS("*", "collection").length > 0
            WebDavEntry(
                name = name,
                path = entryPath,
                isDirectory = isDirectory,
                size = element.textOf("getcontentlength").toLongOrNull() ?: 0L,
                updatedAt = webDavRemoteClient.parseWebDavDate(element.textOf("getlastmodified")),
                rawHref = href,
            )
        }.filterNot { it.name.startsWith(".") }
            .let(::sortWebDavEntries)
    }.getOrElse {
        logWebDav("failed to list $normalizedPath: ${it.stackTraceToString()}")
        if (it is WebDavHttpException && it.code == 404 && normalizedPath != "/") {
            logWebDav("dir $normalizedPath not found, fallback to / without changing saved dirs")
            listWebDav("/")
        } else {
            emptyList()
        }
    }.also { entries ->
        logWebDav("list path=$normalizedPath entries=${entries.size}")
    }
}

internal fun WebDavDriveHook.webDavMkcol(path: String) {
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    val code = webDavRemoteClient.request(credentials, "MKCOL", normalizeWebDavPath(path)).code
    if (code !in 200..299 && code != 405) {
        error("WebDAV MKCOL failed: HTTP $code")
    }
}

internal fun WebDavDriveHook.webDavMove(from: String, to: String) {
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    val code = webDavRemoteClient.request(
        credentials = credentials,
        method = "MOVE",
        path = normalizeWebDavPath(from),
        headers = mapOf(
            "Destination" to webDavRemoteClient.buildUrl(credentials.url, normalizeWebDavPath(to)).toString(),
            "Overwrite" to "T",
        ),
    ).code
    if (code !in 200..299 && code != 207) {
        error("WebDAV MOVE failed: HTTP $code")
    }
}

internal fun WebDavDriveHook.webDavDelete(path: String) {
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    val code = webDavRemoteClient.request(credentials, "DELETE", normalizeWebDavPath(path)).code
    if (code !in 200..299 && code != 404) {
        error("WebDAV DELETE failed: HTTP $code")
    }
}

internal fun WebDavDriveHook.webDavDownload(path: String, outputFile: File, onProgress: ((Int) -> Unit)? = null) {
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    outputFile.parentFile?.mkdirs()
    val normalizedPath = normalizeWebDavPath(path)

    // 先按原路径尝试标准 GET（+ href 兜底）。浏览产生的路径命中此路。
    if (tryWebDavGetWithHref(credentials, normalizedPath, outputFile, onProgress)) return

    // OpenList/AList 场景：搜索走 fs API 返回的路径带挂载点前缀（如 /OnedriveE5/书库/...），
    // 而 WebDAV dav 端点的命名空间根即挂载内容（路径为 /书库/...，无挂载前缀），标准 GET 必 404。
    // 浏览已证明剥掉挂载前缀的路径可用，故这里逐段剥掉开头前缀重试——与具体挂载点名无关，
    // 直接复用浏览验证过的 WebDAV 命名空间。
    val segments = normalizedPath.trim('/').split('/').filter { it.isNotBlank() }
    val maxStrip = minOf(2, segments.size - 1).coerceAtLeast(0)
    for (strip in 1..maxStrip) {
        val candidate = "/" + segments.drop(strip).joinToString("/")
        logWebDav("GET 404 retry with mount prefix stripped ($strip): $candidate")
        if (tryWebDavGetWithHref(credentials, candidate, outputFile, onProgress)) {
            logWebDav("GET 404 recovered by stripping $strip leading segment(s): $normalizedPath -> $candidate")
            return
        }
    }

    // 最后再尝试 AList fs/get 取 raw_url 直链（部分部署 WebDAV 与 fs 命名空间无法互通时的兜底）。
    outputFile.delete()
    if (webDavRemoteClient.tryAlistDownloadFallback(credentials, normalizedPath, outputFile, onProgress)) {
        logWebDav("GET 404 recovered via AList raw_url path=$normalizedPath")
        return
    }

    logWebDav("GET 404 path unresolvable after all fallbacks path=$normalizedPath")
    error("WebDAV GET failed: HTTP 404")
}

// 执行单条路径的 WebDAV GET；404 时用 PROPFIND 父目录 + 骨架匹配拿到服务器原始 href 再下，
// 兼容文件名含全角括号/空白/省略号等重新编码不一致的情况。成功返回 true，
// 404 且无法通过 href 兜底恢复时返回 false（供上层继续尝试剥前缀），其它错误抛出。
internal fun WebDavDriveHook.tryWebDavGetWithHref(
    credentials: WebDavCredentials,
    candidatePath: String,
    outputFile: File,
    onProgress: ((Int) -> Unit)?,
): Boolean {
    outputFile.delete()
    try {
        webDavRemoteClient.requestToFile(credentials, "GET", candidatePath, outputFile, onProgress)
        return true
    } catch (e: IllegalStateException) {
        if (!e.message.orEmpty().contains("HTTP 404")) throw e
        val href = resolveWebDavHref(candidatePath) ?: return false
        if (href.isBlank()) return false
        return runCatching {
            val absoluteUrl = webDavRemoteClient.absoluteUrlFromHref(credentials.url, href)
            logWebDav("GET 404 retry via href from=$candidatePath url=${absoluteUrl.redactWebDavUrl()}")
            outputFile.delete()
            webDavRemoteClient.requestToFileByUrl(credentials, absoluteUrl, outputFile, onProgress)
            true
        }.getOrDefault(false)
    }
}

// host app 渲染列表时会把长文件名截断成带 "..." 或 "…" 的显示名并污染 CloudBook.path。
// 这里用 PROPFIND 列出父目录，按骨架字符模糊匹配真实条目，返回其原始 href。
internal fun WebDavDriveHook.resolveWebDavHref(truncatedPath: String): String? {
    val parent = parentWebDavPath(truncatedPath)
    val truncatedName = truncatedPath.substringAfterLast('/')
    if (truncatedName.isBlank()) return null
    // 兼容 ASCII "..." 与中文省略号 "…"
    val ellipsisIndex = truncatedName.indexOf("...")
    val singleEllipsisIndex = truncatedName.indexOf('…')
    val splitIndex = when {
        ellipsisIndex >= 0 -> ellipsisIndex
        singleEllipsisIndex >= 0 -> singleEllipsisIndex
        else -> -1
    }
    // 无省略号时（如全角括号/空格等编码差异导致的 404），退化为整名骨架精确匹配：
    // prefix=整名骨架、suffix 空，仍能命中服务器上编码不同但字符等价的原始条目。
    val prefix = if (splitIndex < 0) truncatedName else truncatedName.substring(0, splitIndex)
    val suffix = if (splitIndex < 0) "" else truncatedName.substring(splitIndex + if (ellipsisIndex >= 0) 3 else 1)
    // 请求名与服务器名之间存在大量等价字符差异（全角/半角括号、各种空白、"…"/"..."、
    // 省略号位置的空格等），精确 startsWith/endsWith 极易失配。改用「骨架字符」比较：
    // 剥离所有标点、空白、省略号后只保留核心字符（中英文数字），再比对前后缀骨架。
    val prefixSkeleton = skeletonForMatch(prefix)
    val suffixSkeleton = skeletonForMatch(suffix)
    val entries = runCatching { listWebDav(parent) }.getOrNull().orEmpty()
        .filterNot { it.isDirectory }
    // 整名场景优先取骨架完全相等的条目，避免误命中同前缀的更长文件名。
    val match = entries.firstOrNull { entry ->
        entry.name.isNotBlank() && skeletonForMatch(entry.name) == prefixSkeleton && suffixSkeleton.isEmpty()
    } ?: entries.firstOrNull { entry ->
        val nameSkeleton = skeletonForMatch(entry.name)
        entry.name.isNotBlank() &&
            (prefixSkeleton.isEmpty() || nameSkeleton.startsWith(prefixSkeleton)) &&
            (suffixSkeleton.isEmpty() || nameSkeleton.endsWith(suffixSkeleton))
    } ?: run {
        logWebDav("truncated resolve miss name=$truncatedName pre=$prefixSkeleton suf=$suffixSkeleton candidates=${entries.joinToString("|") { it.name }}")
        return null
    }
    // 返回 PROPFIND 原始 href（保留服务器原始百分号编码），供绝对 URL 下载使用。
    val href = match.rawHref.ifBlank { match.path }
    logWebDav("truncated resolve hit name=${match.name} path=${match.path} hasHref=${match.rawHref.isNotBlank()}")
    return href
}

internal fun WebDavDriveHook.webDavUpload(path: String, file: File) {
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    val normalizedPath = normalizeWebDavPath(path)
    ensureWebDavParentDirs(normalizedPath)
    var response = webDavPut(credentials, normalizedPath, file)
    if (response.code in WEBDAV_UPLOAD_RETRY_CODES) {
        logWebDav("PUT retry path=$normalizedPath code=${response.code} body=${response.bodyString.orEmpty().take(160)}")
        runCatching { webDavDelete(normalizedPath) }
            .onFailure { logWebDav("PUT retry delete skipped path=$normalizedPath error=${it.message}") }
        ensureWebDavParentDirs(normalizedPath)
        response = webDavPut(credentials, normalizedPath, file)
    }
    if (response.code !in 200..299 && response.code != 201 && response.code != 204) {
        if (response.code == 405 && webDavRemoteClient.tryAlistUploadFallback(credentials, normalizedPath, file, EPUB_MIME_TYPE)) {
            return
        }
        val detail = response.bodyString.orEmpty().take(200).ifBlank { "no response body" }
        error("WebDAV PUT failed: HTTP ${response.code}, $detail")
    }
}

internal fun WebDavDriveHook.webDavPut(credentials: WebDavCredentials, path: String, file: File) =
    webDavRemoteClient.putFile(credentials, path, file, EPUB_MIME_TYPE)

internal fun WebDavDriveHook.ensureWebDavParentDirs(path: String) {
    val parent = parentWebDavPath(path)
    if (parent == "/" || parent.isBlank()) return
    val segments = parent.trim('/').split('/').filter { it.isNotBlank() }
    var current = ""
    segments.forEach { segment ->
        current = childWebDavPath(current.ifBlank { "/" }, segment)
        runCatching { webDavMkcol(current) }
            .onFailure { logWebDav("MKCOL skipped path=$current error=${it.message}") }
    }
}

internal inline fun <T> WebDavDriveHook.withWebDavCleartextAllowed(block: () -> T): T {
    webDavCleartextAllowed.set(true)
    return try {
        block()
    } finally {
        webDavCleartextAllowed.remove()
    }
}

internal inline fun <T> WebDavDriveHook.withOnlineCleartextAllowed(requestUrl: String, block: () -> T): T {
    rememberCleartextHost(requestUrl)
    return withWebDavCleartextAllowed(block)
}

internal fun WebDavDriveHook.rememberCleartextHost(requestUrl: String) {
    val uri = runCatching { URI(requestUrl) }.getOrNull()
    if (!uri?.scheme.equals("http", ignoreCase = true)) return
    val host = uri?.host.orEmpty().ifBlank {
        runCatching { URL(requestUrl).host }.getOrDefault("")
    }
    if (host.isNotBlank()) {
        webDavCleartextAllowedHosts[host] = true
    }
}

internal fun WebDavDriveHook.parentWebDavPath(path: String): String {
    val normalized = normalizeWebDavPath(path)
    return normalized.substringBeforeLast('/', "").ifBlank { "/" }
}

internal fun WebDavDriveHook.normalizeWebDavPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank() || trimmed == "root") return "/"
    return "/" + trimmed.trim('/').replace(Regex("/{2,}"), "/")
}

internal fun WebDavDriveHook.showWebDavAuthIntroPage() {
    val activity = activityProvider()
    if (activity == null) {
        XposedBridge.log("$LOG_PREFIX WebDAV auth page skipped: no activity")
        return
    }
    activity.runOnUiThread {
        webDavAuthDialog?.takeIf { it.isShowing }?.dismiss()
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        webDavAuthDialog = dialog
        val oldStatusBarColor = activity.window.statusBarColor
        val oldNavigationBarColor = activity.window.navigationBarColor
        val oldSystemUiVisibility = activity.window.decorView.systemUiVisibility
        activity.window.statusBarColor = Color.WHITE
        activity.window.navigationBarColor = Color.WHITE
        activity.window.decorView.systemUiVisibility =
            oldSystemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        dialog.setContentView(createWebDavAuthIntroView(activity, dialog))
        dialog.setOnDismissListener {
            if (webDavAuthDialog === dialog) {
                webDavAuthDialog = null
            }
            activity.window.statusBarColor = oldStatusBarColor
            activity.window.navigationBarColor = oldNavigationBarColor
            activity.window.decorView.systemUiVisibility = oldSystemUiVisibility
        }
        dialog.show()
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            statusBarColor = Color.WHITE
            navigationBarColor = Color.WHITE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                navigationBarDividerColor = Color.WHITE
            }
            decorView.systemUiVisibility =
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

internal fun WebDavDriveHook.createWebDavAuthIntroView(activity: Activity, dialog: Dialog): View {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    val topBar = FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(56),
        )
    }
    val backButton = WebDavBackButton(activity).apply {
        setOnClickListener { dialog.dismiss() }
        contentDescription = "返回"
        layoutParams = FrameLayout.LayoutParams(activity.dp(48), activity.dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = activity.dp(4)
        }
    }
    val title = TextView(activity).apply {
        text = WEBDAV_TITLE
        setTextColor(Color.rgb(32, 36, 38))
        textSize = 18f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.SERIF
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }
    topBar.addView(backButton)
    topBar.addView(title)

    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(activity.dp(28), 0, activity.dp(28), activity.dp(36))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
    }
    val spacerTop = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, 0, 0.9f)
    }
    val illustration = WebDavEmptyView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(activity.dp(150), activity.dp(116))
    }
    val tips = TextView(activity).apply {
        text = WEBDAV_AUTH_TIPS
        setTextColor(Color.rgb(94, 98, 102))
        textSize = 15f
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = activity.dp(22)
        }
    }
    val button = Button(activity).apply {
        text = "前往登录"
        setTextColor(Color.WHITE)
        textSize = 16f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        background = roundedDrawable(Color.rgb(221, 221, 221), activity.dp(7).toFloat())
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            dialog.dismiss()
            showWebDavLoginPage()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(50),
        ).apply {
            topMargin = activity.dp(26)
        }
    }
    val spacerBottom = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, 0, 1.1f)
    }
    body.addView(spacerTop)
    body.addView(illustration)
    body.addView(tips)
    body.addView(button)
    body.addView(spacerBottom)

    root.addView(topBar)
    root.addView(body)
    return root
}

internal fun WebDavDriveHook.showWebDavLoginPage() {
    val activity = activityProvider()
    if (activity == null) {
        XposedBridge.log("$LOG_PREFIX WebDAV login skipped: no activity")
        return
    }
    activity.runOnUiThread {
        webDavLoginDialog?.takeIf { it.isShowing }?.dismiss()
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        webDavLoginDialog = dialog
        val oldStatusBarColor = activity.window.statusBarColor
        val oldNavigationBarColor = activity.window.navigationBarColor
        val oldSystemUiVisibility = activity.window.decorView.systemUiVisibility
        val oldActivityNavContrast = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced
        } else {
            null
        }
        val oldActivityStatusContrast = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced
        } else {
            null
        }
        activity.window.statusBarColor = Color.WHITE
        activity.window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        activity.window.decorView.systemUiVisibility =
            oldSystemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val prefs = activity.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
        dialog.setContentView(createWebDavLoginView(activity, dialog, prefs))
        dialog.window?.apply {
            attributes = attributes.apply { windowAnimations = 0 }
            setWindowAnimations(0)
        }
        dialog.setOnDismissListener {
            if (webDavLoginDialog === dialog) {
                webDavLoginDialog = null
            }
            activity.window.statusBarColor = oldStatusBarColor
            activity.window.navigationBarColor = oldNavigationBarColor
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                oldActivityNavContrast?.let { activity.window.isNavigationBarContrastEnforced = it }
                oldActivityStatusContrast?.let { activity.window.isStatusBarContrastEnforced = it }
            }
            activity.window.decorView.systemUiVisibility = oldSystemUiVisibility
        }
        dialog.show()
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            attributes = attributes.apply { windowAnimations = 0 }
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            statusBarColor = Color.WHITE
            navigationBarColor = Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                navigationBarDividerColor = Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
            decorView.systemUiVisibility =
                decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

internal fun WebDavDriveHook.createWebDavLoginView(
    activity: Activity,
    dialog: Dialog,
    prefs: android.content.SharedPreferences,
): View =
    createWebDavLoginContent(
        context = activity,
        prefs = prefs,
        onBack = { dialog.dismiss() },
        onSaved = { dialog.dismiss() },
    )

internal fun WebDavDriveHook.createWebDavLoginContent(
    context: Context,
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit,
    onSaved: () -> Unit,
): View {
    val scroll = ScrollView(context).apply {
        setBackgroundColor(Color.WHITE)
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(0, 0, 0, context.dp(20))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val topBar = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.statusBarHeight() + context.dp(64),
        )
    }
    val backButton = WebDavBackButton(context).apply {
        setOnClickListener { onBack() }
        contentDescription = "返回"
        layoutParams = FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.START).apply {
            leftMargin = context.dp(18)
            topMargin = context.statusBarHeight() + context.dp(8)
        }
    }
    topBar.addView(backButton)

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(28), context.dp(52), context.dp(28), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val brand = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = context.dp(28)
        }
    }
    brand.addView(WebDavLogoView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(42), context.dp(42))
    })
    brand.addView(TextView(context).apply {
        text = WEBDAV_TITLE
        setTextColor(Color.rgb(53, 112, 196))
        textSize = 28f
        typeface = android.graphics.Typeface.SERIF
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = context.dp(18)
        }
    })
    val title = TextView(context).apply {
        text = "欢迎登录 WebDAV 账号"
        setTextColor(Color.rgb(34, 38, 40))
        textSize = 26f
        typeface = android.graphics.Typeface.SERIF
        includeFontPadding = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = context.dp(22)
        }
    }
    val server = webDavEditText(context, "服务器地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
        setText(prefs.getString(KEY_URL, "").orEmpty())
    }
    val username = webDavEditText(context, "账号", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL).apply {
        setText(prefs.getString(KEY_USERNAME, "").orEmpty())
    }
    val password = webDavEditText(context, "密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val note = TextView(context).apply {
        text = "登录信息仅保存在本机。"
        setTextColor(Color.rgb(139, 143, 148))
        textSize = 15f
        includeFontPadding = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = context.dp(8)
            leftMargin = context.dp(2)
            rightMargin = context.dp(2)
        }
    }
    val submit = Button(context).apply {
        text = "授权并登录"
        setTextColor(Color.WHITE)
        textSize = 16f
        isEnabled = false
        isAllCaps = false
        typeface = android.graphics.Typeface.SERIF
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        background = roundedDrawable(Color.rgb(221, 221, 221), context.dp(8).toFloat())
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(50),
        ).apply {
            topMargin = context.dp(22)
        }
        setOnClickListener {
            val url = normalizeServerUrl(server.text?.toString().orEmpty())
            val user = username.text?.toString().orEmpty().trim()
            val pass = password.text?.toString().orEmpty()
            when {
                url.isBlank() -> context.toast("请输入服务器地址")
                user.isBlank() -> context.toast("请输入账号")
                pass.isBlank() -> context.toast("请输入密码")
                else -> {
                    prefs.edit()
                        .putString(KEY_URL, url)
                        .putString(KEY_USERNAME, user)
                        .putString(KEY_PASSWORD, pass)
                        .putString(KEY_BROWSE_DIR, DEFAULT_DIR)
                        .putBoolean(KEY_AUTHORIZED, true)
                        .apply()
                    XposedBridge.log("$LOG_PREFIX WebDAV login saved: ${url.redactWebDavUrl()}")
                    onWebDavLoginSaved()
                    context.toast("WebDAV 已保存")
                    onSaved()
                }
            }
        }
    }
    fun updateSubmitState() {
        val enabled = server.text?.toString()?.trim()?.isNotEmpty() == true &&
            username.text?.toString()?.trim()?.isNotEmpty() == true &&
            password.text?.toString()?.isNotEmpty() == true
        submit.isEnabled = enabled
        submit.background = roundedDrawable(
            if (enabled) Color.rgb(75, 175, 167) else Color.rgb(221, 221, 221),
            context.dp(8).toFloat(),
        )
        submit.alpha = if (enabled) 1f else 0.72f
    }
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            updateSubmitState()
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    server.addTextChangedListener(watcher)
    username.addTextChangedListener(watcher)
    password.addTextChangedListener(watcher)
    updateSubmitState()

    content.addView(brand)
    content.addView(title)
    content.addView(server)
    content.addView(username)
    content.addView(password)
    content.addView(note)
    content.addView(submit)
    root.addView(topBar)
    root.addView(content)
    scroll.addView(root)
    return scroll
}

internal fun WebDavDriveHook.webDavEditText(context: Context, hintText: String, inputTypeValue: Int): EditText =
    EditText(context).apply {
        hint = hintText
        setHintTextColor(Color.rgb(166, 166, 166))
        setTextColor(Color.rgb(34, 38, 40))
        textSize = 16f
        typeface = android.graphics.Typeface.SERIF
        includeFontPadding = false
        inputType = inputTypeValue
        setSingleLine(true)
        background = roundedDrawable(Color.rgb(247, 247, 247), context.dp(8).toFloat())
        setPadding(context.dp(16), 0, context.dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(52),
        ).apply {
            topMargin = context.dp(12)
        }
    }

internal fun WebDavDriveHook.pushWebDavIcon() {
    webDavIconDepth.set((webDavIconDepth.get() ?: 0) + 1)
}

internal fun WebDavDriveHook.popWebDavIcon() {
    val next = (webDavIconDepth.get() ?: 0) - 1
    if (next <= 0) {
        webDavIconDepth.remove()
    } else {
        webDavIconDepth.set(next)
    }
}

internal fun WebDavDriveHook.pushWebDavBackupCard() {
    webDavBackupCardDepth.set((webDavBackupCardDepth.get() ?: 0) + 1)
}

internal fun WebDavDriveHook.popWebDavBackupCard() {
    val next = (webDavBackupCardDepth.get() ?: 0) - 1
    if (next <= 0) {
        webDavBackupCardDepth.remove()
    } else {
        webDavBackupCardDepth.set(next)
    }
}

internal inline fun <T> WebDavDriveHook.withWebDavIcon(block: () -> T): T {
    pushWebDavIcon()
    return try {
        block()
    } finally {
        popWebDavIcon()
    }
}

internal fun WebDavDriveHook.getComposeWebDavVector(): Any? {
    composeWebDavVector?.let { return it }
    return runCatching {
        val builderClass = cls(IMAGE_VECTOR_BUILDER_CLASS)
        val markerClass = cls(DEFAULT_CONSTRUCTOR_MARKER_CLASS)
        val builder = builderClass.getDeclaredConstructor(
            String::class.java,
            java.lang.Float.TYPE,
            java.lang.Float.TYPE,
            java.lang.Float.TYPE,
            java.lang.Float.TYPE,
            java.lang.Long.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Integer.TYPE,
            markerClass,
        ).apply { isAccessible = true }.newInstance(
            "Colored.WebDAV",
            24f,
            24f,
            24f,
            24f,
            0L,
            0,
            false,
            224,
            null,
        )
        addVectorPath(builderClass, builder, WEBDAV_ICON_BODY_PATH, 0xFF4BAFA7L)
        addVectorPath(builderClass, builder, WEBDAV_ICON_CLOUD_PATH, 0xFFFFFFFFL)
        addVectorPath(builderClass, builder, WEBDAV_ICON_SLOT_PATH, 0xFFFFFFFFL, 0.84f)
        val imageVector = builderClass.getDeclaredMethod("build")
            .apply { isAccessible = true }
            .invoke(builder)
        composeWebDavVector = imageVector
        imageVector
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to build WebDAV compose icon: ${it.stackTraceToString()}")
    }.getOrNull()
}

internal fun WebDavDriveHook.logWebDav(
    message: String,
    level: ModuleLogLevel = legacyModuleLogLevel(message),
) {
    when (level) {
        ModuleLogLevel.ERROR -> XposedBridge.logError("$LOG_PREFIX WebDAV ERROR $message")
        ModuleLogLevel.WARN,
        ModuleLogLevel.INFO,
        -> XposedBridge.log("$LOG_PREFIX WebDAV $message")
    }
}

internal fun WebDavDriveHook.rememberPendingWebDavImport(platformFile: Any, localFile: File, sourceUrl: String, sourceSize: Long?) {
    val source = WebDavImportSource(sourceUrl, sourceSize)
    val keys = (platformFileKeys(platformFile) + localFile.absolutePath + runCatching { localFile.canonicalPath }.getOrNull())
        .filterNotNull()
        .filter { it.isNotBlank() }
        .distinct()
    keys.forEach { pendingWebDavImports[it] = source }
    logWebDav("pending import keys=${keys.size} url=${sourceUrl.redactWebDavUrl()}")
}

internal fun WebDavDriveHook.importWebDavDownloadedBook(
    workerManager: Any,
    platformFile: Any,
    sourceUrl: String,
    sourceSize: Long?,
): Any? {
    rememberWorkerManager(workerManager)
    val bookshelf = currentBookshelfRepository()
        ?: error("BookshelfRepository not available for WebDAV import")
    val importMethod = findImportBookMethod(bookshelf)
    return invokeSuspendBlocking(
        importMethod,
        bookshelf,
        *importBookArgs(
            importMethod,
            platformFile,
            null,
            null,
            sourceUrl.ifBlank { null },
            sourceSize?.takeIf { it > 0L }?.let { java.lang.Long.valueOf(it) },
        ),
    )
}

/**
 * 定位宿主 BookshelfRepository 的 epub 版 importBook 方法，兼容新旧签名。
 * 2.2.0：importBook(PlatformFile, Path, Opf, String, Long, Continuation) 共 6 参。
 * 2.3.0：新增进度回调 importBook(PlatformFile, Path, Opf, String, Long, Function1<Int,Unit>, Continuation) 共 7 参。
 * 方法名精确匹配（importTxtBook / importBook$default / importBook$lambda 名称均不同），
 * 要求末参为 Continuation，取参数最少者，避免旧写法 size==6 在 2.3.0 匹配失败。
 */

internal fun WebDavDriveHook.webDavLoginHtml(url: String, username: String): String {
    val escapedUrl = url.htmlAttrEscape()
    val escapedUsername = username.htmlAttrEscape()
    return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <style>
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                html, body {
                  margin: 0;
                  width: 100%;
                  height: 100%;
                  min-height: 100vh;
                  background: #ffffff;
                  color: #222628;
                  font-family: "Noto Serif CJK SC", "Songti SC", STSong, SimSun, serif;
                  -webkit-text-size-adjust: 100%;
                  text-size-adjust: 100%;
                  overflow-x: hidden;
                }
                body { padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom); }
                body {
                  transform: translateX(0);
                  opacity: 1;
                  transition: transform 260ms cubic-bezier(.2, 0, 0, 1), opacity 220ms linear;
                  will-change: transform, opacity;
                }
                body.closing {
                  transform: translateX(100%);
                  opacity: 0.98;
                }
                .topbar {
                  height: 64px;
                  display: flex;
                  align-items: center;
                  padding: 0 14px;
                }
                .back {
                  width: 40px;
                  height: 40px;
                  border: none;
                  border-radius: 20px;
                  background: transparent;
                  padding: 8px;
                  display: grid;
                  place-items: center;
                }
                main {
                  padding: 18px 28px 32px;
                  max-width: 500px;
                  margin: 0 auto;
                }
                .brand {
                  display: flex;
                  align-items: center;
                  gap: 14px;
                  margin-bottom: 28px;
                }
                .logo {
                  width: 34px;
                  height: 34px;
                  border-radius: 7px;
                  background: #4bafa7;
                  display: grid;
                  place-items: center;
                }
                .brand span {
                  color: #3570c4;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 24px;
                  line-height: 1;
                  font-weight: 600;
                  letter-spacing: 0;
                }
                h1 {
                  margin: 0 0 26px;
                  color: #222628;
                  font-size: 24px;
                  line-height: 1.28;
                  font-weight: 700;
                  letter-spacing: 0;
                }
                .field { margin-top: 12px; }
                input {
                  width: 100%;
                  height: 52px;
                  border: 0;
                  border-radius: 8px;
                  outline: none;
                  background: #f7f7f7;
                  color: #222628;
                  font-family: inherit;
                  font-size: 16px;
                  line-height: 52px;
                  padding: 0 16px;
                  letter-spacing: 0;
                  font-weight: 600;
                }
                input::placeholder { color: #a6a6a6; }
                .note {
                  margin: 10px 2px 0;
                  color: #8b8f94;
                  font-size: 14px;
                  line-height: 1.5;
                }
                .submit {
                  width: 100%;
                  height: 50px;
                  margin-top: 22px;
                  border: 0;
                  border-radius: 8px;
                  background: #dddddd;
                  color: #ffffff;
                  font-family: inherit;
                  font-size: 16px;
                  font-weight: 700;
                  letter-spacing: 0;
                }
              </style>
            </head>
            <body>
              <div class="topbar">
                <button class="back" type="button" aria-label="返回" onclick="ReaMicroWebDav.back()">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M20 11H7.83L13.42 5.41L12 4L4 12L12 20L13.42 18.59L7.83 13H20V11Z" fill="#222628"/>
                  </svg>
                </button>
              </div>
              <main>
                <div class="brand">
                  <div class="logo" aria-hidden="true">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                      <path d="M8.6 14.4c-1.35 0-2.35-.96-2.35-2.15 0-1.22 1-2.12 2.34-2.12.23 0 .47.03.7.1C9.95 8.9 11.1 8.15 12.45 8.15c1.74 0 3.13 1.13 3.5 2.73 1.08.15 1.92 1.03 1.92 2.12 0 1.2-.97 2.15-2.22 2.15H8.6Z" fill="white"/>
                      <rect x="6.9" y="17" width="10.2" height="1.35" rx=".65" fill="white" opacity=".84"/>
                    </svg>
                  </div>
                  <span>WebDAV</span>
                </div>
                <h1>欢迎登录 WebDAV 账号</h1>
                <div class="field">
                  <input id="server" value="$escapedUrl" autocomplete="url" inputmode="url" placeholder="服务器地址">
                </div>
                <div class="field">
                  <input id="username" value="$escapedUsername" autocomplete="username" placeholder="账号">
                </div>
                <div class="field">
                  <input id="password" autocomplete="current-password" placeholder="密码" type="password">
                </div>
                <p class="note">登录信息仅保存在本机。</p>
                <button class="submit" type="button" onclick="submitWebDav()">授权并登录</button>
              </main>
              <script>
                let closing = false;
                window.ReaMicroClose = function() {
                  if (closing) return;
                  closing = true;
                  document.body.classList.add('closing');
                  setTimeout(function() {
                    ReaMicroWebDav.closeNow();
                  }, 230);
                };
                function submitWebDav() {
                  ReaMicroWebDav.save(
                    document.getElementById('server').value,
                    document.getElementById('username').value,
                    document.getElementById('password').value
                  );
                }
              </script>
            </body>
            </html>
        """.trimIndent()
}
