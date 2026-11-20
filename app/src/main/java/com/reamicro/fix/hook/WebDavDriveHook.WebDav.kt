package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException
import com.reamicro.fix.cloud.webdav.CancellableWebDavDownload
import com.reamicro.fix.cloud.webdav.WebDavBackupSnapshot
import com.reamicro.fix.cloud.webdav.WebDavCredentials
import com.reamicro.fix.cloud.webdav.WebDavImportSource
import com.reamicro.fix.cloud.webdav.WebDavOperationTicket
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URL
import java.util.UUID
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.homeCloudSearchResults
import com.reamicro.fix.online.download.importCacheFile
import com.reamicro.fix.online.download.cleanupDownloadToken
import com.reamicro.fix.online.download.cancelExistingBackupTasks
import com.reamicro.fix.cloud.webdav.cleartextHostOf
import com.reamicro.fix.cloud.webdav.webDavDisplayDir
import com.reamicro.fix.cloud.webdav.webDavDownloadKey
import com.reamicro.fix.cloud.webdav.throwIfWebDavDownloadCancelled
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.childWebDavPath
import com.reamicro.fix.cloud.webdav.parentWebDavPath
import com.reamicro.fix.cloud.webdav.webDavPrefs
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.CancellationException

internal fun WebDavDriveHook.canShowWebDavEntry(): Boolean =
    settingsProvider().canRunWebDavCloud

internal fun WebDavDriveHook.shouldAllowCleartext(args: Array<Any?>?): Boolean {
    if (webDavCleartextAllowed.get() == true) return true
    val host = cleartextHostOf(args?.firstOrNull())
    return host.isNotBlank() && webDavCleartextAllowedHosts[host] == true
}

internal fun WebDavDriveHook.logCleartextAllowed(value: Any?, via: String) {
    val host = cleartextHostOf(value).ifBlank { "*" }
    if (webDavCleartextAllowedLoggedHosts.putIfAbsent("$via|$host", true) == null) {
        logWebDav("cleartext allowed via=$via host=$host")
    }
}

internal fun WebDavDriveHook.renderSyncWebDavAuthorizedCard(
    composer: Any,
    onSetupDefaultDir: Any?,
    onPick: Any?,
    isRunning: Boolean = false,
): Boolean {
    return runCatching {
        val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.syncUi)
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        val icon = getComposeWebDavVector() ?: return@runCatching false
        val backupDir = currentWebDavBackupDir()
        val previous = webDavRenderingSyncAuthCard.get()
        webDavRenderingSyncAuthCard.set(true)
        try {
            host.call("card",
                icon,
                "上传到 $WEBDAV_TITLE",
                webDavDisplayDir(backupDir),
                isRunning,
                ui.callback(host.type("function0")) {
                    if (backupDir.isBlank()) {
                        logWebDav("sync default click setup dir")
                        onSetupDefaultDir?.let { host.method("invoke1").invoke(it, BACKUP_TYPE_WEBDAV) }
                    } else {
                        logWebDav("sync default click upload dir=$backupDir")
                        onPick?.let { host.method("invoke2").invoke(it, BACKUP_TYPE_WEBDAV, backupDir) }
                    }
                },
                ui.callback(host.type("function0")) {
                    logWebDav("sync pick click")
                    onPick?.let { host.method("invoke2").invoke(it, BACKUP_TYPE_WEBDAV, null) }
                },
                composer,
                0,
            )
            logWebDav("sync auth card rendered dir=$backupDir")
            true
        } finally {
            if (previous == null) webDavRenderingSyncAuthCard.remove() else webDavRenderingSyncAuthCard.set(previous)
        }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render WebDAV sync auth card: ${it.stackTraceToString()}")
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
        HookRuntime.log("$LOG_PREFIX failed to render WebDAV import row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.webDavImportClickCallback(): Any =
    functionProxy("WebDavImportLogin", FUNCTION0_CLASS) {
        showWebDavLoginPage()
        targetUnit()
    }

internal fun WebDavDriveHook.enqueueWebDavDownload(workerManager: Any, book: Any): Any {
    val execution = storageExecutionGeneration.get()
    check(!storageClosed && settingsProvider().canRunWebDavCloud) { "WebDAV 已停用" }
    val operation = webDavOperationTicket()
    val context = currentContext() ?: error("Android context not available")
    val name = book.callString("getName").ifBlank { WEBDAV_TITLE }
    val path = cloudPathOf(book)
    val sourceUrl = book.callString("getUrl")
    val sourceSize = book.invokeNoArg("getSize") as? Number
    val uniqueName = path.ifBlank { name }
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    val tracker = work.tracker(workerManager)
    checkWebDavOperation(operation)
    rememberWorkerManager(workerManager)
    val id = UUID.randomUUID().toString()
    val stateFlow = work.create(tracker, id, "download", uniqueName, newWorkState("Running", 0, null, null, name))
    val handle = newNativeWorkHandle(id, requireNotNull(stateFlow))
    val localFile = importCacheFile(context.cacheDir, "reamicro-webdav", name)
    val downloadKey = webDavDownloadKey(operation.storageIdentity, path, name)
    val token = CancellableWebDavDownload(
        storageIdentity = operation.storageIdentity,
        id = id, key = downloadKey, name = name, tracker = tracker,
        localFile = localFile, createdAtMs = System.currentTimeMillis(), handle = handle,
    )
    fun checkActive() {
        if (storageClosed || !settingsProvider().canRunWebDavCloud || execution != storageExecutionGeneration.get()) {
            throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException("WebDAV 或模块状态已变化")
        }
        throwIfWebDavDownloadCancelled(token)
        if (work.cancelled(tracker, id)) throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException()
        checkWebDavOperation(operation)
    }
    runningWebDavDownloads[downloadKey] = token

    val thread = com.reamicro.fix.reload.ModuleResources.thread({
        var queued = false
        ImportCompletionState.retain(localFile)
        runCatching {
            checkActive()
            setTrackedWorkState(tracker, id, "Running", 2, null, null, name)
            var lastProgress = 0
            webDavDownload(path, localFile, operation.credentials, ::checkActive) { downloadProgress ->
                checkActive()
                val progress = (downloadProgress * 70 / 100).coerceIn(2, 70)
                if (progress >= lastProgress + 4 || progress == 70) {
                    lastProgress = progress
                    setTrackedWorkState(tracker, id, "Running", progress, null, null, name)
                }
            }
            checkActive()
            setTrackedWorkState(tracker, id, "Running", 78, null, null, name)
            val platformFile = platformFile(localFile)
            if (!ModuleImportPrecheck.precheck(localFile, sourceUrl)) {
                cancelTrackedWork(tracker, id)
                cleanupDownloadToken(token)
                logWebDav("download import cancelled before native queue path=$path")
                return@runCatching
            }
            checkActive()
            val source = rememberPendingWebDavImport(platformFile, localFile, sourceUrl, sourceSize?.toLong(),
                operation.storageIdentity, ::checkActive)
            setTrackedWorkState(tracker, id, "Running", 80, null, null, name)
            checkActive()
            // 队列调用前保留源 ticket；真实 worker/normal/TXT 路径继续校验同一账号。
            val result = NativeImportTaskBridge.await(workerManager, platformFile, ::checkActive,
                onProgress = { progress -> setTrackedWorkState(tracker, id, "Running", progress, null, null, name) },
                onQueued = { queued = true }, workerFinished = source::isWorkerFinished,
                onCancellationRequested = { token.cancelRequested = true })
            runCatching { work.completeImport(tracker, id, result ?: sourceUrl.ifBlank { localFile.absolutePath }, name) }
                .onFailure { HookRuntime.logError("$LOG_PREFIX completed WebDAV import status publication failed", it) }
            logWebDav("download and native import completed path=$path url=${sourceUrl.redactWebDavUrl()}")
        }.onFailure {
            val cancelled = it is java.util.concurrent.CancellationException || token.cancelRequested ||
                !AccountHostRuntime.isCurrentStorage(operation.storageIdentity, activityProvider())
            token.cancelRequested = true
            if (!queued) cleanupDownloadToken(token)
            if (cancelled) {
                cancelTrackedWork(tracker, id)
                logWebDav("download cancelled path=$path")
            } else {
                HookRuntime.log("$LOG_PREFIX WebDAV tracked download failed: ${it.stackTraceToString()}")
                setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV download failed", null, name)
            }
        }.also {
            if (!queued) forgetPendingWebDavImport(localFile)
            runningWebDavDownloads.remove(downloadKey, token)
            webDavDownloadCancelPrompts.remove(id)
        }
    }, "ReaMicroWebDavDownload")
    token.thread = thread
    thread.start()
    return handle
}

internal fun WebDavDriveHook.enqueueWebDavBackup(workerManager: Any, bookId: Long, dir: String): Any {
    val operation = webDavOperationTicket()
    val context = currentContext() ?: error("Android context not available")
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    val bookshelf = work.bookshelf(workerManager)
    checkWebDavOperation(operation)
    rememberWorkerManager(workerManager)
    val tracker = work.tracker(workerManager)
    val normalizedDir = normalizeWebDavPath(dir.ifBlank { operation.backupDir })
    cancelExistingBackupTasks(tracker, bookId)
    val id = UUID.randomUUID().toString()
    val stateFlow = work.create(tracker, id, "backup:$BACKUP_TYPE_WEBDAV", bookId.toString(), newWorkState("Running", 0, null, null, null))
    val handle = newNativeWorkHandle(id, requireNotNull(stateFlow))
    fun checkActive() {
        if (Thread.currentThread().isInterrupted || work.cancelled(tracker, id)) {
            throw java.util.concurrent.CancellationException("WebDAV backup cancelled")
        }
        checkWebDavOperation(operation)
    }
    com.reamicro.fix.reload.ModuleResources.thread({
        var archive: File? = null
        try {
            runCatching {
                checkActive()
                val book = findLocalBookById(bookId, operation.storageIdentity, bookshelf, ::checkActive)
                    ?: error("Book $bookId not found for this account")
                val uuid = book.callString("getUuid")
                check(uuid.isNotBlank() && File(uuid).name == uuid && uuid != "." && uuid != "..") { "图书目录无效" }
                val originalDirectory = bookDirectory(book).canonicalFile
                check(originalDirectory.parentFile == File(context.filesDir, "${operation.storageIdentity.accountId}/books").canonicalFile) {
                    "图书目录与备份账号不一致"
                }
                val fileName = exportFileName(book)
                val localFile = File(context.cacheDir, "reamicro-webdav-backup/${UUID.randomUUID()}_${fileName.safeWebDavFileName()}")
                archive = localFile
                localFile.parentFile?.mkdirs()
                setTrackedWorkState(tracker, id, "Running", 8, null, null, fileName)
                localFile.outputStream().use { output -> zipBookDirectory(book, output, ::checkActive) }
                checkActive()
                setTrackedWorkState(tracker, id, "Running", 45, null, null, fileName)
                val remotePath = childWebDavPath(normalizedDir, fileName)
                webDavUpload(remotePath, localFile, operation.credentials, ::checkActive)
                checkActive()
                setTrackedWorkState(tracker, id, "Running", 88, null, null, fileName)
                val updatedBook = copyBookWithBackup(book, BACKUP_TYPE_WEBDAV, remotePath, "")
                updateLocalBook(updatedBook, operation.storageIdentity, bookshelf, ::checkActive)
                checkActive()
                val backupBook = newCloudBook(WebDavEntry(fileName, remotePath, false, localFile.length(), System.currentTimeMillis()))
                AccountHostRuntime.withCurrentStorage(operation.storageIdentity, activityProvider()) {
                    checkActive()
                    webDavRecentBackups[bookId] = WebDavBackupSnapshot(updatedBook, backupBook, operation.storageIdentity, uuid)
                }
                updateWebDavBackupViewModels(bookId, updatedBook, backupBook, operation.storageIdentity, uuid)
                refreshWebDavBackupViewModelsLater(bookId, updatedBook, backupBook, operation.storageIdentity, uuid)
                checkActive()
                refreshWebDavLibrary(parentWebDavPath(remotePath))
                checkActive()
                setTrackedWorkState(tracker, id, "Success", 100, null, remotePath, fileName)
                logWebDav("backup uploaded bookId=$bookId remote=$remotePath metadata updated")
            }.onFailure {
                if (it is java.util.concurrent.CancellationException || work.cancelled(tracker, id) ||
                    !AccountHostRuntime.isCurrentStorage(operation.storageIdentity, activityProvider())) {
                    work.cancel(tracker, id)
                    logWebDav("backup cancelled bookId=$bookId")
                } else {
                    HookRuntime.log("$LOG_PREFIX WebDAV tracked backup failed: ${it.stackTraceToString()}")
                    setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE)
                }
            }
        } finally {
            archive?.delete()
            logWebDav("backup running cleared bookId=$bookId")
        }
    }, "ReaMicroWebDavBackup").start()
    return handle
}

internal fun WebDavDriveHook.tryHandleRunningWebDavDownloadTap(book: Any): Boolean {
    if (!canCancelCloudDownload()) return false
    val path = cloudPathOf(book)
    val name = book.callString("getName").ifBlank { WEBDAV_TITLE }
    val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return false
    val token = runningWebDavDownloads[webDavDownloadKey(identity, path, name)] ?: return false
    if (!AccountHostRuntime.isCurrentStorage(token.storageIdentity, activityProvider())) return false
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
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    val stateFlow = work.field("handle.state").get(token.handle)
    val state = requireNotNull(work.method("flow.get").invoke(stateFlow))
    val status = work.stateName(state)
    HookRuntime.logAlways("ReaMicro WebDAV cancellation verified status=$status cacheExists=${token.localFile.exists()}")
    runningWebDavDownloads.remove(token.key, token)
    webDavDownloadCancelPrompts.remove(token.id)
    showToast("\u5df2\u53d6\u6d88\u4e0b\u8f7d\u5e76\u6e05\u7406\u7f13\u5b58")
    return true
}

private val webDavBackupViewModelAccounts = java.util.Collections.synchronizedMap(
    java.util.WeakHashMap<Any, AccountHostRuntime.Identity>(),
)

internal fun WebDavDriveHook.rememberWebDavBackupViewModelIdentity(viewModel: Any) {
    val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return
    synchronized(webDavBackupViewModelAccounts) { webDavBackupViewModelAccounts.putIfAbsent(viewModel, identity) }
}

internal fun clearWebDavBackupViewModelIdentities() = webDavBackupViewModelAccounts.clear()

internal fun WebDavDriveHook.updateWebDavBackupViewModels(
    bookId: Long, book: Any, backup: Any,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    uuid: String = book.callString("getUuid"),
) {
    if (storagePaused || storageClosed || !AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider()) ||
        book.callLong("getUid") != storageIdentity.accountId || book.callString("getUuid") != uuid) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        postStorageUi { updateWebDavBackupViewModels(bookId, book, backup, storageIdentity, uuid) }
        return
    }
    val viewModels = synchronized(webDavBackupViewModels) {
        webDavBackupViewModels.mapNotNull { it.get() }.also { webDavBackupViewModels.removeAll { ref -> ref.get() == null } }
    }
    viewModels.forEach { viewModel ->
        runCatching {
            val owner = webDavBackupViewModelAccounts[viewModel] ?: return@runCatching
            if (owner.accountId != storageIdentity.accountId || owner.generation != storageIdentity.generation ||
                !AccountHostRuntime.isCurrentStorage(owner, activityProvider())) return@runCatching
            val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup)
            if (host.field("vm.bookId").getLong(viewModel) != bookId) return@runCatching
            val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
            host.method("update").invoke(viewModel, ui.function(host.type("function"), fallback = { args -> args?.firstOrNull() }) { args ->
                val before = args?.firstOrNull() ?: return@function null
                if (!AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) return@function before
                val visibleBook = host.field("state.book").get(before)
                if (visibleBook != null && (visibleBook.callLong("getUid") != storageIdentity.accountId ||
                        visibleBook.callString("getUuid") != uuid)) return@function before
                host.backupState(book, backup, false)
            })
        }.onFailure { HookRuntime.log("$LOG_PREFIX failed to update WebDAV backup ui state: ${it.stackTraceToString()}") }
    }
}

internal fun WebDavDriveHook.refreshWebDavBackupViewModelsLater(
    bookId: Long, book: Any, backup: Any,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    uuid: String = book.callString("getUuid"),
) {
    postStorageUi(800L) {
        if (!AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) return@postStorageUi
        val current = webDavRecentBackups[bookId]
        if (current?.book !== book || current.backup !== backup || current.uuid != uuid ||
            current.storageIdentity.generation != storageIdentity.generation) return@postStorageUi
        updateWebDavBackupViewModels(bookId, book, backup, storageIdentity, uuid)
    }
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
        androidViews.render(factory, modifier, composer, null)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render WebDAV login route: ${it.stackTraceToString()}")
    }.getOrThrow()
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
        HookRuntime.log("$LOG_PREFIX failed to render WebDAV account route: ${throwable.stackTraceToString()}")
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
        colors = WebDavPageColors(context),
        onBack = { popBackStack(navGraphScope) },
        onSaved = {
            popBackStack(navGraphScope)
            postStorageUi {
                refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                navigateStorage(navGraphScope)
            }
        },
    )
}

internal fun WebDavDriveHook.webDavAuth(): Any {
    val credentials = webDavCredentials()
    return newBaiduAuth(
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
    return requireNotNull(com.reamicro.fix.core.HostStorageBindings.records).record("localAuth", mapOf(
        "token" to "webdav", "orderBy" to "user_utime", "orderDirection" to "0", "nickname" to WEBDAV_TITLE,
        "usedSize" to 0L, "totalSize" to 0L, "libraryDir" to listOf(dir), "backupDir" to dir,
    ))
}

internal fun WebDavDriveHook.webDavCloudUserInfo(): Any {
    val credentials = webDavCredentials()
    return newCloudUserInfo(
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

internal fun WebDavDriveHook.webDavResult(block: () -> Any?): Any? =
    runCatching { block() }
        .fold(
            onSuccess = { kotlinResultSuccess(it) },
            onFailure = {
                HookRuntime.log("$LOG_PREFIX WebDAV repository operation failed: ${it.stackTraceToString()}")
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
    invalidateWebDavConnection()
    updateWebDavAccountAuthFlow()
    val path = currentWebDavBrowseDir()
    updateWebDavStorageTrees(path)
    refreshWebDavLibraryAsync(path)
}

private data class WebDavRefresh(
    val sequence: Long,
    val connection: Long,
    val path: String,
    val order: String,
    val direction: String,
    val flow: Any,
    val credentials: WebDavCredentials,
)

private fun WebDavDriveHook.webDavRefresh(path: String): WebDavRefresh? {
    if (storageClosed || storagePaused || !settingsProvider().canRunWebDavCloud) return null
    val normalized = normalizeWebDavPath(path.ifBlank { "/" })
    if (normalized != currentWebDavBrowseDir()) return null
    val flow = synchronized(webDavLibraryLock) { webDavLibraryFlow } ?: return null
    val credentials = webDavCredentials() ?: return null
    return WebDavRefresh(webDavRefreshSequence.incrementAndGet(), webDavConnectionGeneration.get(),
        normalized, currentWebDavOrderBy(), currentWebDavOrderDirection(), flow, credentials)
}

private fun WebDavDriveHook.isCurrent(request: WebDavRefresh): Boolean =
    !storageClosed && !storagePaused && settingsProvider().canRunWebDavCloud &&
        request.sequence == webDavRefreshSequence.get() &&
        request.connection == webDavConnectionGeneration.get() && request.path == currentWebDavBrowseDir() &&
        request.order == currentWebDavOrderBy() && request.direction == currentWebDavOrderDirection() &&
        synchronized(webDavLibraryLock) { webDavLibraryFlow === request.flow }

private fun WebDavDriveHook.loadWebDavLibrary(request: WebDavRefresh, ticket: LatestSearchWorker.Ticket) {
    try {
        TaskCancellation.checkCurrent()
        if (!ticket.isCurrent() || !isCurrent(request)) return
        val items = loadWebDavEntries(request.path, request.credentials)
            .filter { it.isDirectory || isSupportedBookFile(it.name) }
            .map {
                TaskCancellation.checkCurrent()
                if (it.isDirectory) newCloudFolder(it) else newCloudBook(it)
            }
        TaskCancellation.checkCurrent()
        if (!ticket.isCurrent() || !isCurrent(request)) return
        val paging = pagingDataFrom(items)
        postStorageUi {
            if (ticket.isCurrent() && isCurrent(request)) {
                setMutableStateFlowValue(request.flow, paging)
                logWebDav("refresh library path=${request.path} items=${items.size}")
            }
        }
    } catch (error: Throwable) {
        TaskCancellation.rethrowIfCancelled(error)
        HookRuntime.logError("$LOG_PREFIX failed to refresh WebDAV library", error)
    }
}

internal fun WebDavDriveHook.refreshWebDavLibrary(path: String = currentWebDavBrowseDir()) {
    refreshWebDavLibraryAsync(path)
}

internal fun WebDavDriveHook.refreshWebDavLibraryAsync(path: String = currentWebDavBrowseDir()) {
    if (storageClosed || storagePaused) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        postStorageUi { refreshWebDavLibraryAsync(path) }
        return
    }
    val request = webDavRefresh(path) ?: return
    try {
        webDavRefreshWorker.submitIf({ isCurrent(request) }) { ticket -> loadWebDavLibrary(request, ticket) }
    } catch (error: Throwable) {
        if (ModuleResources.accepting && !storageClosed) {
            HookRuntime.logError("$LOG_PREFIX WebDAV refresh start failed", error)
        }
    }
}

internal fun WebDavDriveHook.searchWebDavBooks(query: String): List<Any> {
    TaskCancellation.checkCurrent()
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    val generation = webDavConnectionGeneration.get()
    val credentials = webDavCredentials() ?: return emptyList()
    fun checkConnection() {
        TaskCancellation.checkCurrent()
        if (storageClosed || generation != webDavConnectionGeneration.get()) {
            throw CancellationException("WebDAV connection changed")
        }
    }
    searchAlistBooks(needle)?.let { entries ->
        checkConnection()
        logWebDav("OpenList/AList search query=$needle results=${entries.size}")
        return entries
            .distinctBy { normalizeWebDavPath(it.path) }
            .let(::sortWebDavEntries)
            .map { checkConnection(); newCloudBook(it) }
    }
    val queue = java.util.ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val results = mutableListOf<WebDavEntry>()
    queue.add("/")
    while (queue.isNotEmpty() && visited.size < WEBDAV_SEARCH_MAX_DIRS) {
        checkConnection()
        val dir = normalizeWebDavPath(queue.removeFirst())
        if (!visited.add(dir)) continue
        val entries = try { loadWebDavEntries(dir, credentials) } catch (error: Throwable) {
            TaskCancellation.rethrowIfCancelled(error)
            checkConnection()
            HookRuntime.log("$LOG_PREFIX WebDAV search skipped $dir: ${error.message}")
            emptyList()
        }
        entries.forEach { entry ->
            checkConnection()
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
        .map { checkConnection(); newCloudBook(it) }
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
        TaskCancellation.rethrowIfCancelled(it)
        logWebDav("OpenList/AList search unavailable: ${it.message}")
    }.getOrNull()

internal fun WebDavDriveHook.updateHomeWebDavSearchResults(
    viewModel: Any?,
    webDavResults: List<Any>,
    localResults: List<Any>,
    onlineResults: List<Any>? = null,
    isCurrent: () -> Boolean = { !storageClosed && settingsProvider().moduleEnabled },
) {
    if (viewModel == null || !isCurrent()) return
    rememberHomeViewModelDependencies(viewModel)
    runCatching {
        com.reamicro.fix.core.HostHomeSearchBindings.requireCurrent().update(viewModel) { state ->
                if (!isCurrent()) return@update state
                runCatching {
                    val current = homeCloudSearchResults(state)
                    val merged = com.reamicro.fix.core.mergeHomeSearchResults(current, mapOf(
                        BACKUP_TYPE_WEBDAV to webDavResults,
                        BACKUP_TYPE_LOCAL_LIBRARY to localResults,
                        BACKUP_TYPE_ONLINE_COMPLETION to onlineResults,
                    ))
                    if (!isCurrent() || merged == current) state else copyHomeUiStateWithCloudResults(state, merged)
                }.onFailure {
                    HookRuntime.log("$LOG_PREFIX failed to copy WebDAV home search state: ${it.stackTraceToString()}")
                }.getOrDefault(state)
        }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to update WebDAV home search state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.addHomeWebDavSearchSection(
    lazyListScope: Any,
    type: Int,
    title: String,
    results: List<*>,
    intentReceiver: Any,
    generation: Long,
    navGraphScope: Any? = null,
) {
    runCatching {
        val host = com.reamicro.fix.core.HostHomeSearchBindings.requireCurrent()
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        host.method("item").invoke(
            lazyListScope,
            "cloud-search-$generation-$type-${title.ifBlank { type.toString() }.hashCode()}",
            type,
            ui.callback(host.type("function3")) { args ->
                val item = args?.getOrNull(0) ?: return@callback
                val composer = args.getOrNull(1) ?: return@callback
                val render = {
                    val call = arrayOfNulls<Any>(6)
                    call[host.slot("results.scope")] = item
                    call[host.slot("results.type")] = type
                    call[host.slot("results.list")] = results
                    call[host.slot("results.callback")] = ui.callback(host.type("function1")) { tapArgs ->
                        tapArgs?.getOrNull(0)?.let { book ->
                            if (isOnlineCompletionPath(cloudPathOf(book))) {
                                handleOnlineCompletionSearchTap(book)
                            } else if (!tryHandleRunningCloudDownloadTap(book, type)) {
                                HostHomeSearchEvents.open(intentReceiver, book)
                            }
                        }
                    }
                    call[4] = composer
                    call[5] = 0
                    host.method("results").invoke(null, *call)
                }
                if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                    pushLocalLibraryIcon()
                    try { render() } finally { popLocalLibraryIcon() }
                } else if (isOnlineCompletionRenderType(type)) {
                    pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type, title))
                    try { withWebDavIcon { render() } } finally { popOnlineCompletionCloudTitle() }
                } else {
                    withWebDavIcon { render() }
                }
            },
        )
        logWebDav("home search section rendered type=$type results=${results.size}")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render WebDAV home search section: ${it.stackTraceToString()}")
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
        val updateMethod = requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).method("update")
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("WebDavStorageTreeState", FUNCTION1_CLASS, fallback = { args -> args?.firstOrNull() }) { args ->
                val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                storageStateWithTree(state, tree)
            },
        )
        logWebDav("update storage tree path=${normalizeWebDavPath(path)} size=${tree.size}")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to update WebDAV storage tree: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.webDavLocalBookFlow(url: String): Any {
    if (url.isBlank()) return emptyFlow()
    return runCatching {
        val bookshelf = currentBookshelfRepository() ?: return@runCatching emptyFlow()
        requireNotNull(com.reamicro.fix.core.HostStorageBindings.library).method("liveUri").invoke(bookshelf, url)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to create WebDAV local book flow: ${it.stackTraceToString()}")
    }.getOrDefault(emptyFlow())
}

internal fun WebDavDriveHook.webDavWorkFlow(uniqueName: String): Any {
    if (uniqueName.isBlank()) return emptyFlow()
    return runCatching {
        val tracker = currentWorkTracker() ?: return@runCatching emptyFlow()
        com.reamicro.fix.core.HostWorkBindings.requireCurrent().unique(tracker, uniqueName)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to create WebDAV work flow: ${it.stackTraceToString()}")
    }.getOrDefault(emptyFlow())
}

internal fun WebDavDriveHook.webDavCredentials(): WebDavCredentials? {
    val values = webDavPrefs(activityProvider())?.all ?: return null
    val url = normalizeServerUrl(values[KEY_URL] as? String ?: "")
    val username = values[KEY_USERNAME] as? String ?: ""
    val password = values[KEY_PASSWORD] as? String ?: ""
    return if (url.isBlank() || username.isBlank() || password.isBlank()) {
        null
    } else {
        WebDavCredentials(url, username, password)
    }
}

internal fun WebDavDriveHook.webDavOperationTicket(): WebDavOperationTicket {
    val identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())) {
        "账号状态正在变化，请稍后重新操作"
    }
    return AccountHostRuntime.withCurrentStorage(identity, activityProvider()) {
        val values = webDavPrefs(activityProvider())?.all ?: error("WebDAV not authorized")
        val credentials = WebDavCredentials(
            normalizeServerUrl(values[KEY_URL] as? String ?: ""),
            values[KEY_USERNAME] as? String ?: "", values[KEY_PASSWORD] as? String ?: "",
        )
        check(credentials.url.isNotBlank() && credentials.username.isNotBlank() && credentials.password.isNotBlank()) {
            "WebDAV not authorized"
        }
        WebDavOperationTicket(identity, credentials, webDavConnectionGeneration.get(),
            normalizeWebDavPath((values[KEY_BACKUP_DIR] as? String).orEmpty().ifBlank { DEFAULT_DIR }))
    }
}

internal fun WebDavDriveHook.checkWebDavOperation(operation: WebDavOperationTicket) {
    if (storageClosed || !settingsProvider().canRunWebDavCloud || Thread.currentThread().isInterrupted ||
        !AccountHostRuntime.isCurrentStorage(operation.storageIdentity, activityProvider()) ||
        operation.connectionGeneration != webDavConnectionGeneration.get() || webDavCredentials() != operation.credentials) {
        throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException("账号或 WebDAV 连接已变化，请重新操作")
    }
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

internal fun WebDavDriveHook.saveWebDavBrowseDir(path: String) {
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_BROWSE_DIR, normalizeWebDavPath(path.ifBlank { "/" }))
        ?.apply()
    invalidateWebDavRefresh()
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
    invalidateWebDavRefresh()
}

internal fun WebDavDriveHook.saveWebDavOrderDirection(value: String) {
    val normalized = if (isDescendingOrder(value)) "1" else "0"
    webDavPrefs(activityProvider())?.edit()
        ?.putString(KEY_ORDER_DIRECTION, normalized)
        ?.apply()
    invalidateWebDavRefresh()
}

private fun WebDavDriveHook.invalidateWebDavRefresh() {
    val sequence = webDavRefreshSequence.incrementAndGet()
    if (webDavRefreshWorkerState.isInitialized()) {
        webDavRefreshWorker.cancelIf { webDavRefreshSequence.get() == sequence }
    }
}

private fun WebDavDriveHook.invalidateWebDavConnection() {
    webDavConnectionGeneration.incrementAndGet()
    invalidateWebDavRefresh()
    if (homeSearchState.isInitialized()) {
        homeSearch.pause()
        if (!storagePaused && !storageClosed) homeSearch.resume()
    }
}

internal fun WebDavDriveHook.clearWebDavLogin() {
    webDavPrefs(activityProvider())?.edit()
        ?.remove(KEY_URL)
        ?.remove(KEY_USERNAME)
        ?.remove(KEY_PASSWORD)
        ?.remove(KEY_AUTHORIZED)
        ?.apply()
    invalidateWebDavConnection()
    synchronized(webDavLibraryLock) {
        webDavLibraryFlow = null
    }
}

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
    TaskCancellation.checkCurrent()
    val credentials = webDavCredentials() ?: error("WebDAV not authorized")
    return loadWebDavEntries(path, credentials)
}

private fun WebDavDriveHook.loadWebDavEntries(path: String, credentials: WebDavCredentials): List<WebDavEntry> {
    TaskCancellation.checkCurrent()
    val normalizedPath = normalizeWebDavPath(path.ifBlank { "/" })
    val requestUrl = webDavRemoteClient.buildUrl(credentials.url, normalizedPath, directory = true)
    logWebDav("PROPFIND ${requestUrl.toString().redactWebDavUrl()}")
    return webDavRemoteClient.list(credentials, normalizedPath).map { resource ->
        WebDavEntry(resource.name, resource.path, resource.isDirectory, resource.size,
            resource.updatedAt, resource.rawHref)
    }.let(::sortWebDavEntries).also { entries ->
        TaskCancellation.checkCurrent()
        logWebDav("list path=$normalizedPath entries=${entries.size}")
    }
}

internal fun WebDavDriveHook.webDavMkcol(
    path: String, credentials: WebDavCredentials = webDavCredentials() ?: error("WebDAV not authorized"),
) {
    webDavRemoteClient.ensureCollection(credentials, normalizeWebDavPath(path))
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

internal fun WebDavDriveHook.webDavDelete(
    path: String, credentials: WebDavCredentials = webDavCredentials() ?: error("WebDAV not authorized"),
    checkActive: () -> Unit = {},
) {
    checkActive()
    val code = webDavRemoteClient.request(credentials, "DELETE", normalizeWebDavPath(path)).code
    if (code !in 200..299 && code != 404) {
        error("WebDAV DELETE failed: HTTP $code")
    }
    checkActive()
    // 远端删除成功后仅撤销当前账号同路径的临时卡片。
    val deletedPath = normalizeWebDavPath(path)
    webDavRecentBackups.entries.forEach { (bookId, snapshot) ->
        if (AccountHostRuntime.isCurrentStorage(snapshot.storageIdentity, activityProvider()) &&
            normalizeWebDavPath(snapshot.backup.callString("getPath")) == deletedPath) {
            webDavRecentBackups.remove(bookId, snapshot)
        }
    }
}

internal fun WebDavDriveHook.webDavDownload(path: String, outputFile: File, onProgress: ((Int) -> Unit)? = null) =
    webDavDownload(path, outputFile, webDavCredentials() ?: error("WebDAV not authorized"), onProgress = onProgress)

internal fun WebDavDriveHook.webDavDownload(
    path: String, outputFile: File, credentials: WebDavCredentials,
    checkActive: () -> Unit = {}, onProgress: ((Int) -> Unit)? = null,
) {
    checkActive()
    outputFile.parentFile?.mkdirs()
    val normalizedPath = normalizeWebDavPath(path)

    if (tryWebDavGetWithHref(credentials, normalizedPath, outputFile, onProgress, checkActive)) return

    val segments = normalizedPath.trim('/').split('/').filter { it.isNotBlank() }
    val maxStrip = minOf(2, segments.size - 1).coerceAtLeast(0)
    for (strip in 1..maxStrip) {
        val candidate = "/" + segments.drop(strip).joinToString("/")
        logWebDav("GET 404 retry with mount prefix stripped ($strip): $candidate")
        if (tryWebDavGetWithHref(credentials, candidate, outputFile, onProgress, checkActive)) {
            logWebDav("GET 404 recovered by stripping $strip leading segment(s): $normalizedPath -> $candidate")
            return
        }
    }

    outputFile.delete()
    checkActive()
    if (webDavRemoteClient.tryAlistDownloadFallback(credentials, normalizedPath, outputFile, onProgress)) {
        checkActive()
        logWebDav("GET 404 recovered via AList raw_url path=$normalizedPath")
        return
    }

    checkActive()
    logWebDav("GET 404 path unresolvable after all fallbacks path=$normalizedPath")
    error("WebDAV GET failed: HTTP 404")
}

internal fun WebDavDriveHook.tryWebDavGetWithHref(
    credentials: WebDavCredentials,
    candidatePath: String,
    outputFile: File,
    onProgress: ((Int) -> Unit)?, checkActive: () -> Unit = {},
): Boolean {
    checkActive()
    outputFile.delete()
    try {
        webDavRemoteClient.requestToFile(credentials, "GET", candidatePath, outputFile, onProgress)
        checkActive()
        return true
    } catch (e: IllegalStateException) {
        checkActive()
        if (!e.message.orEmpty().contains("HTTP 404")) throw e
        val href = resolveWebDavHref(candidatePath, credentials, checkActive) ?: return false
        if (href.isBlank()) return false
        return try {
            checkActive()
            val absoluteUrl = webDavRemoteClient.absoluteUrlFromHref(credentials.url, href)
            logWebDav("GET 404 retry via href from=$candidatePath url=${absoluteUrl.redactWebDavUrl()}")
            outputFile.delete()
            webDavRemoteClient.requestToFileByUrl(credentials, absoluteUrl, outputFile, onProgress)
            checkActive()
            true
        } catch (failure: Throwable) {
            checkActive()
            if (failure is java.util.concurrent.CancellationException) throw failure
            false
        }
    }
}

internal fun WebDavDriveHook.resolveWebDavHref(
    truncatedPath: String, credentials: WebDavCredentials = webDavCredentials() ?: error("WebDAV not authorized"),
    checkActive: () -> Unit = {},
): String? {
    checkActive()
    val parent = parentWebDavPath(truncatedPath)
    val truncatedName = truncatedPath.substringAfterLast('/')
    if (truncatedName.isBlank()) return null

    val ellipsisIndex = truncatedName.indexOf("...")
    val singleEllipsisIndex = truncatedName.indexOf('…')
    val splitIndex = when {
        ellipsisIndex >= 0 -> ellipsisIndex
        singleEllipsisIndex >= 0 -> singleEllipsisIndex
        else -> -1
    }

    val prefix = if (splitIndex < 0) truncatedName else truncatedName.substring(0, splitIndex)
    val suffix = if (splitIndex < 0) "" else truncatedName.substring(splitIndex + if (ellipsisIndex >= 0) 3 else 1)

    val prefixSkeleton = skeletonForMatch(prefix)
    val suffixSkeleton = skeletonForMatch(suffix)
    val entries = runCatching { loadWebDavEntries(parent, credentials) }.onFailure { checkActive() }.getOrNull().orEmpty()
        .filterNot { it.isDirectory }
    checkActive()

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

    val href = match.rawHref.ifBlank { match.path }
    logWebDav("truncated resolve hit name=${match.name} path=${match.path} hasHref=${match.rawHref.isNotBlank()}")
    return href
}

internal fun WebDavDriveHook.webDavUpload(path: String, file: File, checkActive: () -> Unit = {}) =
    webDavUpload(path, file, webDavCredentials() ?: error("WebDAV not authorized"), checkActive)

internal fun WebDavDriveHook.webDavUpload(
    path: String, file: File, credentials: WebDavCredentials, checkActive: () -> Unit = {},
) {
    checkActive()
    val normalizedPath = normalizeWebDavPath(path)
    ensureWebDavParentDirs(normalizedPath, credentials, checkActive)
    checkActive()
    var response = webDavPut(credentials, normalizedPath, file)
    checkActive()
    if (response.code in WEBDAV_UPLOAD_RETRY_CODES) {
        logWebDav("PUT retry path=$normalizedPath code=${response.code} body=${response.bodyString.orEmpty().take(160)}")
        checkActive()
        runCatching { webDavDelete(normalizedPath, credentials, checkActive) }
            .onFailure { checkActive(); logWebDav("PUT retry delete skipped path=$normalizedPath error=${it.message}") }
        ensureWebDavParentDirs(normalizedPath, credentials, checkActive)
        checkActive()
        response = webDavPut(credentials, normalizedPath, file)
        checkActive()
    }
    if (response.code !in 200..299 && response.code != 201 && response.code != 204) {
        if (response.code == 405 && webDavRemoteClient.tryAlistUploadFallback(credentials, normalizedPath, file, EPUB_MIME_TYPE)) {
            checkActive()
            return
        }
        val detail = response.bodyString.orEmpty().take(200).ifBlank { "no response body" }
        error("WebDAV PUT failed: HTTP ${response.code}, $detail")
    }
}

internal fun WebDavDriveHook.webDavPut(credentials: WebDavCredentials, path: String, file: File) =
    webDavRemoteClient.putFile(credentials, path, file, EPUB_MIME_TYPE)

internal fun WebDavDriveHook.ensureWebDavParentDirs(
    path: String, credentials: WebDavCredentials = webDavCredentials() ?: error("WebDAV not authorized"),
    checkActive: () -> Unit = {},
) {
    checkActive()
    val parent = parentWebDavPath(path)
    if (parent == "/" || parent.isBlank()) return
    val segments = parent.trim('/').split('/').filter { it.isNotBlank() }
    var current = ""
    segments.forEach { segment ->
        current = childWebDavPath(current.ifBlank { "/" }, segment)
        checkActive()
        runCatching { webDavMkcol(current, credentials) }
            .onFailure { checkActive(); logWebDav("MKCOL skipped path=$current error=${it.message}") }
        checkActive()
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

internal fun webDavSystemUiVisibility(old: Int, dark: Boolean, layoutInScreen: Boolean): Int {
    val lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    var flags = old and lightMask.inv()
    if (layoutInScreen) {
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    if (!dark) flags = flags or lightMask
    return flags
}

internal fun applyWebDavSystemBarAppearance(window: Window, dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(
        if (dark) 0 else lightBars,
        lightBars,
    )
}

internal fun WebDavDriveHook.showWebDavAuthIntroPage() {
    val activity = activityProvider()
    if (activity == null) {
        HookRuntime.log("$LOG_PREFIX WebDAV auth page skipped: no activity")
        return
    }
    postStorageUi {
        if (storageActivityRef?.get() !== activity) return@postStorageUi
        webDavAuthDialog?.takeIf { it.isShowing }?.dismiss()
        val colors = WebDavPageColors(activity)
        val dialog = Dialog(
            activity,
            if (colors.dark) {
                android.R.style.Theme_Material_NoActionBar
            } else {
                android.R.style.Theme_Material_Light_NoActionBar
            },
        ).apply {
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
        activity.window.statusBarColor = colors.pageBackground
        activity.window.navigationBarColor = colors.pageBackground
        activity.window.decorView.systemUiVisibility =
            webDavSystemUiVisibility(oldSystemUiVisibility, colors.dark, layoutInScreen = false)

        dialog.setContentView(createWebDavAuthIntroView(activity, dialog, colors))
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
            setBackgroundDrawable(ColorDrawable(colors.pageBackground))
            statusBarColor = colors.pageBackground
            navigationBarColor = colors.pageBackground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                navigationBarDividerColor = colors.pageBackground
            }
            decorView.systemUiVisibility =
                webDavSystemUiVisibility(decorView.systemUiVisibility, colors.dark, layoutInScreen = false)
            applyWebDavSystemBarAppearance(this, colors.dark)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

internal fun WebDavDriveHook.createWebDavAuthIntroView(
    activity: Activity,
    dialog: Dialog,
    colors: WebDavPageColors,
): View {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.pageBackground)
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
    val backButton = WebDavBackButton(activity, colors.backIcon).apply {
        setOnClickListener { dialog.dismiss() }
        contentDescription = "返回"
        layoutParams = FrameLayout.LayoutParams(activity.dp(48), activity.dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = activity.dp(4)
        }
    }
    val title = TextView(activity).apply {
        text = WEBDAV_TITLE
        setTextColor(colors.titleText)
        textSize = 18f
        gravity = Gravity.CENTER
        typeface = EmbeddedHostUi.uiTypeface(context) ?: android.graphics.Typeface.SERIF
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
    val illustration = WebDavEmptyView(activity, colors).apply {
        layoutParams = LinearLayout.LayoutParams(activity.dp(150), activity.dp(116))
    }
    val tips = TextView(activity).apply {
        text = WEBDAV_AUTH_TIPS
        setTextColor(colors.bodyText)
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
        setTextColor(colors.actionText)
        textSize = 16f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        background = roundedDrawable(colors.neutralButton, activity.dp(7).toFloat())
        com.reamicro.fix.ui.applyDialogActionStyle(this, activity)
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
        HookRuntime.log("$LOG_PREFIX WebDAV login skipped: no activity")
        return
    }
    postStorageUi {
        if (storageActivityRef?.get() !== activity) return@postStorageUi
        webDavLoginDialog?.takeIf { it.isShowing }?.dismiss()
        val colors = WebDavPageColors(activity)

        val dialog = Dialog(
            activity,
            if (colors.dark) android.R.style.Theme_Material_NoActionBar else 0,
        ).apply {
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
        activity.window.statusBarColor = colors.pageBackground
        activity.window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        activity.window.decorView.systemUiVisibility =
            webDavSystemUiVisibility(oldSystemUiVisibility, colors.dark, layoutInScreen = true)
        val prefs = activity.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
        dialog.setContentView(createWebDavLoginView(activity, dialog, prefs, colors))
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
            setBackgroundDrawable(ColorDrawable(colors.pageBackground))
            statusBarColor = colors.pageBackground
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
            decorView.systemUiVisibility =
                webDavSystemUiVisibility(decorView.systemUiVisibility, colors.dark, layoutInScreen = true)
            applyWebDavSystemBarAppearance(this, colors.dark)
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
    colors: WebDavPageColors,
): View =
    createWebDavLoginContent(
        context = activity,
        prefs = prefs,
        colors = colors,
        onBack = { dialog.dismiss() },
        onSaved = { dialog.dismiss() },
    )

internal fun WebDavDriveHook.createWebDavLoginContent(
    context: Context,
    prefs: android.content.SharedPreferences,
    colors: WebDavPageColors,
    onBack: () -> Unit,
    onSaved: () -> Unit,
): View {
    val scroll = ScrollView(context).apply {
        setBackgroundColor(colors.pageBackground)
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.pageBackground)
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
    val backButton = WebDavBackButton(context, colors.backIcon).apply {
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
    brand.addView(WebDavLogoView(context, colors.accent).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(42), context.dp(42))
    })
    brand.addView(TextView(context).apply {
        text = WEBDAV_TITLE
        setTextColor(colors.brandTitle)
        textSize = 28f
        typeface = EmbeddedHostUi.uiTypeface(context) ?: android.graphics.Typeface.SERIF
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
        setTextColor(colors.primaryText)
        textSize = 26f
        typeface = EmbeddedHostUi.uiTypeface(context) ?: android.graphics.Typeface.SERIF
        includeFontPadding = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = context.dp(22)
        }
    }
    val server = webDavEditText(context, "服务器地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, colors).apply {
        setText(prefs.getString(KEY_URL, "").orEmpty())
    }
    val username = webDavEditText(context, "账号", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL, colors).apply {
        setText(prefs.getString(KEY_USERNAME, "").orEmpty())
    }
    val password = webDavEditText(context, "密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, colors)
    val note = TextView(context).apply {
        text = "登录信息仅保存在本机。"
        setTextColor(colors.noteText)
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
        setTextColor(colors.actionText)
        textSize = 16f
        isEnabled = false
        isAllCaps = false
        typeface = EmbeddedHostUi.uiTypeface(context) ?: android.graphics.Typeface.SERIF
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        background = roundedDrawable(colors.neutralButton, context.dp(8).toFloat())
        com.reamicro.fix.ui.applyDialogActionStyle(this, context)
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
                    HookRuntime.log("$LOG_PREFIX WebDAV login saved: ${url.redactWebDavUrl()}")
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

internal fun WebDavDriveHook.webDavEditText(
    context: Context,
    hintText: String,
    inputTypeValue: Int,
    colors: WebDavPageColors,
): EditText =
    EditText(context).apply {
        hint = hintText
        setHintTextColor(colors.hintText)
        setTextColor(colors.primaryText)
        textSize = 16f
        typeface = EmbeddedHostUi.uiTypeface(context) ?: android.graphics.Typeface.SERIF
        includeFontPadding = false
        inputType = inputTypeValue
        setSingleLine(true)
        background = roundedDrawable(colors.inputBackground, context.dp(8).toFloat())
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
    return runCatching { HostWebDavVector().build().also { composeWebDavVector = it } }
        .onFailure { HookRuntime.logError("$LOG_PREFIX WebDAV vector binding failed", it) }.getOrNull()
}

internal fun WebDavDriveHook.rememberPendingWebDavImport(
    platformFile: Any, localFile: File, sourceUrl: String, sourceSize: Long?,
    storageIdentity: AccountHostRuntime.Identity,
    checkActive: () -> Unit = {},
): WebDavImportSource {
    val execution = storageExecutionGeneration.get()
    val source = WebDavImportSource(sourceUrl, sourceSize, storageIdentity, localFile) {
        if (storageClosed || !settingsProvider().moduleEnabled || execution != storageExecutionGeneration.get()) {
            throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException("模块状态已变化，已停止原生导入")
        }
        checkActive()
    }
    source.checkCurrent()
    val keys = (platformFileKeys(platformFile) + localFile.absolutePath + runCatching { localFile.canonicalPath }.getOrNull())
        .filterNotNull().filter { it.isNotBlank() }.distinct()
    AccountHostRuntime.withCurrentStorage(storageIdentity, activityProvider()) {
        source.checkCurrent()
        keys.forEach { pendingWebDavImports[it] = source }
    }
    logWebDav("pending import keys=${keys.size} url=${sourceUrl.redactWebDavUrl()}")
    return source
}

internal fun WebDavDriveHook.forgetPendingWebDavImport(localFile: File) {
    pendingWebDavImports.entries.forEach { (key, source) ->
        if (source.localFile == localFile) pendingWebDavImports.remove(key, source)
    }
    ImportCompletionState.release(localFile)
}
