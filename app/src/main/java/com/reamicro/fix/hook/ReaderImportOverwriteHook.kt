package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostReaderPositionBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import com.reamicro.fix.online.download.OnlineCompletionDirectoryPublication
import com.reamicro.fix.online.download.copyOnlineCompletionFileAtomically
import com.reamicro.fix.online.download.publishOnlineCompletionDirectory

internal object OnlineCompletionImportSignal {
    private data class Mark(val createdAtMs: Long)
    private val marks = ConcurrentHashMap<String, Mark>()

    private fun keys(uuid: String, title: String, uri: String, identity: AccountHostRuntime.Identity): List<String> {
        val prefix = "${identity.accountId}:${identity.generation}|"
        return buildList {
            uuid.trim().takeIf(String::isNotBlank)?.let { add("${prefix}uuid:$it") }
            uri.trim().takeIf(String::isNotBlank)?.let { add("${prefix}uri:$it") }
            if (isEmpty()) title.trim().replace(Regex("\\s+"), " ").takeIf(String::isNotBlank)
                ?.let { add("${prefix}title:$it") }
        }
    }

    fun remember(uuid: String, title: String, uri: String, identity: AccountHostRuntime.Identity) {
        cleanup()
        check(AccountHostRuntime.isCurrentStorage(identity)) { "账号已切换，请重新导入" }
        val mark = Mark(System.currentTimeMillis())
        keys(uuid, title, uri, identity).forEach { marks[it] = mark }
    }

    fun forget(uuid: String, title: String, uri: String, identity: AccountHostRuntime.Identity) {
        keys(uuid, title, uri, identity).forEach(marks::remove)
    }

    fun matches(uuid: String, title: String, uri: String): Boolean {
        cleanup()
        val identity = AccountHostRuntime.storageSnapshot() ?: return false
        return keys(uuid, title, uri, identity).any(marks::containsKey)
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        marks.entries.removeAll { (_, mark) -> now - mark.createdAtMs > 30 * 60_000L }
    }
}

/** Both hosts normalize identifiers on every OPF read; a prefixed UUID loses its identity. */
internal fun newEpubImportStagingUuid(booksDirectory: File, targetUuid: String): String {
    repeat(8) {
        val uuid = UUID.randomUUID().toString()
        if (uuid != targetUuid && !File(booksDirectory, uuid).exists()) return uuid
    }
    error("无法分配独立导入目录，请重试")
}

class ReaderImportOverwriteHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private lateinit var host: com.reamicro.fix.core.HostReaderExtrasBindings.Contract
    private var repositoryRef: WeakReference<Any>? = null
    private val pendingPreImportDecisions = ModuleImportDecisionMemory<PreImportDecision>(PRE_IMPORT_DECISION_TTL_MS)
    private val activeImport = ThreadLocal<ImportRun?>()
    private val activeRequestEpoch = ThreadLocal<Long?>()
    private val cancellationEpoch = AtomicLong()
    private val importRuns = Collections.synchronizedMap(IdentityHashMap<Any, ImportRun>())
    private val runningImports = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<ImportRun, Boolean>()))
    private val stagedEpubDirectories = ConcurrentHashMap<String, EpubStage>()
    private lateinit var nativeStaging: NativeImportStaging

    /** Called synchronously with the settings gate; reenabling cannot revive an older request. */
    fun onModuleEnabledChanged(enabled: Boolean) {
        if (enabled) return
        cancellationEpoch.incrementAndGet()
        if (::nativeStaging.isInitialized) nativeStaging.cancel()
        val running = synchronized(runningImports) { runningImports.toList() }
        running.forEach { run ->
            run.nativeJob?.let { job ->
                runCatching { HostCoroutines.cancelJob(classLoader, job, CancellationException("导入模块已关闭")) }
                    .onFailure { HookRuntime.logError("$LOG_PREFIX native import cancellation stopped", it) }
            }
        }
    }

    fun install(): Boolean {
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        return runCatching {
            host = requireNotNull(com.reamicro.fix.core.HostReaderExtrasBindings.importOverwrite) { "Import overwrite contract unavailable" }
            requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.files) { "Native file contract unavailable" }
            ReaMicroBookMetadataSync.requireAvailable()
            host.constructors("repository.").forEach { constructor ->
                handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        repositoryRef = WeakReference(param.thisObject)
                        ReaMicroBookMetadataSync.rememberBookshelfRepository(param.thisObject)
                    }
                })
            }
            hookWorkerManagerRepositoryCapture(handles)

            // An import already rewritten by this generation must still cancel/restore while the master gate is off.
            HookRuntime.withManagementHooks {
                nativeStaging = NativeImportStaging(host, object : NativeImportStaging.Owner {
                    override fun enabled() = settingsProvider().canRunReaderOverwriteCheck
                    override fun identity() = storageIdentity()
                    override fun epoch() = cancellationEpoch.get()
                    override fun check(identity: AccountHostRuntime.Identity, epoch: Long) = checkStorageIdentity(identity, epoch)
                    override fun booksRoot(identity: AccountHostRuntime.Identity) = nativeBooksRoot(identity)
                    override fun rememberRepository(repository: Any) {
                        repositoryRef = WeakReference(repository)
                        ReaMicroBookMetadataSync.rememberBookshelfRepository(repository)
                    }
                })
                nativeStaging.install(handles)
                hookNativeImport(handles)
                hookNativeTxtImport(handles)
                hookImportCommits(handles)
                hookEpubFileManagerImport(handles)
            }
            ModuleImportPrecheck.attach(this)
            ModuleResources.blockWhile {
                stagedEpubDirectories.entries.removeAll { System.currentTimeMillis() - it.value.createdAtMs > PRE_IMPORT_DECISION_TTL_MS }
                if (runningImports.isEmpty() && stagedEpubDirectories.isEmpty()) null else "图书导入仍在完成，请稍后重载"
            }
            ModuleResources.cleanup {
                pendingPreImportDecisions.clear()
                importRuns.clear()
                activeImport.remove()
                activeRequestEpoch.remove()
                stagedEpubDirectories.clear()
                ModuleImportPrecheck.detach(this)
            }
            HookRuntime.log("$LOG_PREFIX reader overwrite check hook installed")
            true
        }.onFailure {
            handles.asReversed().forEach { handle -> runCatching { handle.unhook() } }
            HookRuntime.log("$LOG_PREFIX failed to hook reader overwrite check: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun hookWorkerManagerRepositoryCapture(handles: MutableList<io.github.libxposed.api.XposedInterface.HookHandle>) {
        host.constructors("worker.").forEach { constructor ->
            handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    host.field("worker.repository").get(param.thisObject)?.let { repository ->
                        repositoryRef = WeakReference(repository)
                        ReaMicroBookMetadataSync.rememberBookshelfRepository(repository)
                    }
                }
            })
        }
    }

    private class ImportRun(
        val identity: AccountHostRuntime.Identity,
        val epoch: Long,
        val repository: Any,
        val uuid: String,
        val oldBook: Any?,
        var metadata: BookMetadataPatch,
        val nativeJob: Any?,
        val independent: Boolean = false,
    ) {
        val closed = AtomicBoolean()
        @Volatile var bookCommitted = false
        @Volatile var preparedBook: Any? = null
        var publication: OnlineCompletionDirectoryPublication? = null
    }

    private data class EpubStage(
        val identity: AccountHostRuntime.Identity,
        val epoch: Long,
        val title: String,
        val originalUuid: String,
        val targetUuid: String,
        val temporaryUuid: String,
        val preDecision: PreImportDecision?,
        val booksDirectory: Any,
        val source: Any,
        val sourceOpf: Any,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private fun bindImportRun(param: HookCallback.Call, run: ImportRun) {
        param.setObjectExtra(RUN_EXTRA, run)
        param.setObjectExtra(PREVIOUS_RUN_EXTRA, activeImport.get())
        activeImport.set(run)
        if (param.getObjectExtra(REQUEST_EPOCH_EXTRA) == null) bindRequestEpoch(param, run.epoch)
    }

    private fun bindRequestEpoch(param: HookCallback.Call, epoch: Long) {
        param.setObjectExtra(REQUEST_EPOCH_EXTRA, epoch)
        param.setObjectExtra(PREVIOUS_EPOCH_EXTRA, activeRequestEpoch.get())
        activeRequestEpoch.set(epoch)
    }

    private fun restoreRequestEpoch(param: HookCallback.Call) {
        if (param.getObjectExtra(REQUEST_EPOCH_EXTRA) != null) {
            activeRequestEpoch.set(param.getObjectExtra(PREVIOUS_EPOCH_EXTRA) as? Long)
        }
    }

    private fun hookNativeImport(handles: MutableList<XposedInterface.HookHandle>) {
        for (role in listOf("importContinuation", "txtContinuation")) {
            handles += HookRuntime.hookMethod(host.constructor(role), object : HookCallback() {
                override fun after(param: Call) {
                    val run = activeImport.get() ?: return
                    if (param.throwable == null && param.args.firstOrNull() === run.repository) {
                        importRuns[requireNotNull(param.thisObject)] = run
                    }
                }
            })
        }
        val imported = host.method("import")
        handles += HookRuntime.hookMethod(imported, object : HookCallback(XposedInterface.PRIORITY_LOWEST) {
            override fun before(param: Call) {
                try {
                    param.setObjectExtra(INITIAL_DIRECTORY_EXTRA, param.args.getOrNull(host.index("import.directory")))
                    val continuation = param.args.getOrNull(host.index("import.continuation"))
                    val resumed = continuation?.takeIf { host.type("importContinuation").isInstance(it) }
                        ?.let(importRuns::get)
                    if (resumed != null) {
                        bindImportRun(param, resumed)
                        checkStorageIdentity(resumed.identity, resumed.epoch)
                        return
                    }
                    val opf = param.args.getOrNull(host.index("import.opf")) ?: return
                    val incoming = param.args.getOrNull(host.index("import.directory"))
                    val incomingStage = incoming?.let { stagedEpubDirectories[File(it.toString()).canonicalPath] }
                    if (!settingsProvider().canRunReaderOverwriteCheck) {
                        if (incomingStage != null) throw CancellationException("导入模块已关闭")
                        return
                    }
                    bindRequestEpoch(param, cancellationEpoch.get())
                    incomingStage?.let { stage ->
                        checkStorageIdentity(stage.identity, stage.epoch)
                    }
                    val repository = requireNotNull(param.thisObject)
                    val identity = storageIdentity()
                    val incomingFile = requireNotNull(incoming).let { File(it.toString()).canonicalFile }
                    if (incomingFile.parentFile != nativeBooksRoot(identity) || !incomingFile.isDirectory) {
                        throw CancellationException("导入目录与当前账号不一致或已取消，请重新导入")
                    }
                    repositoryRef = WeakReference(repository)
                    ReaMicroBookMetadataSync.rememberBookshelfRepository(repository)
                    val source = param.args.getOrNull(host.index("import.source"))
                    val uri = param.args.getOrNull(host.index("import.uri"))?.toString().orEmpty().trim()
                    val title = resolveImportTitle(opf, source) ?: "未命名"
                    val originalUuid = resolveImportUuid(opf).orEmpty()
                    require(originalUuid.isNotBlank()) { "导入图书缺少有效标识" }
                    val preDecision = consumePreImportDecision(originalUuid, title, identity)
                    val conflict = if (preDecision == null) findImportConflict(repository, originalUuid, uri, title, identity) else null
                    val oldBook = preDecision?.oldBook ?: conflict?.oldBook
                    val decision = preDecision?.decision ?: conflict?.let { showOverwriteConfirm(it.oldTitle, title, identity) }
                    checkStorageIdentity(identity)
                    if (decision == OverwriteDecision.CANCEL || decision == OverwriteDecision.PASSTHROUGH) {
                        throw CancellationException("导入已取消")
                    }
                    val overwriteBook = if (decision == OverwriteDecision.OVERWRITE) {
                        requireNotNull(oldBook) { "覆盖图书不存在" }
                        ReaMicroBookMetadataSync.latestBook(repository, oldBook)
                            ?: error("原图书已变化，请重新导入")
                    } else null
                    val targetUuid = when (decision) {
                        OverwriteDecision.OVERWRITE -> requireNotNull(overwriteBook.uuidOrNull())
                        OverwriteDecision.INDEPENDENT -> if (originalUuid == preDecision?.oldUuid || preDecision == null) {
                            UUID.randomUUID().toString()
                        } else originalUuid
                        else -> originalUuid
                    }
                    val newOpf = opf.withUuid(targetUuid)
                    param.args[host.index("import.opf")] = newOpf
                    if (overwriteBook != null) {
                        overwriteBook.uriOrNull()?.let { param.args[host.index("import.uri")] = it }
                    } else if (decision == OverwriteDecision.INDEPENDENT && uri.isNotBlank()) {
                        param.args[host.index("import.uri")] = "$uri#reamicro-independent-$targetUuid"
                    }
                    val incomingDirectory = param.args.getOrNull(host.index("import.directory"))
                    val metadata = ReaMicroBookMetadataSync.metadataFromOpf(newOpf, incomingDirectory)
                        .mergeWith(preDecision?.metadataPatch ?: BookMetadataPatch())
                        .withSize(importBookSize(param.args))
                    val originalCompletion = requireNotNull(continuation) { "原生导入协程不存在" }
                    val run = ImportRun(identity, requireNotNull(activeRequestEpoch.get()), repository, targetUuid,
                        overwriteBook, metadata, HostCoroutines.continuationJob(classLoader, originalCompletion),
                        independent = decision == OverwriteDecision.INDEPENDENT)
                    runningImports += run
                    bindImportRun(param, run)
                    param.args[host.index("import.directory")] = prepareImportDirectory(incomingDirectory, targetUuid, newOpf, run)
                    guardNativeImportProgress(param, identity)
                    HostCoroutines.observeContinuation(
                        classLoader, param, host.index("import.continuation"),
                        onCompleted = { result, success -> finishImport(run, result, success) },
                    )
                    HookRuntime.log("$LOG_PREFIX import prepared: overwrite=${overwriteBook != null}, independent=${decision == OverwriteDecision.INDEPENDENT}")
                } catch (failure: Throwable) {
                    param.throwable = failure
                    if (failure !is CancellationException) HookRuntime.logError("$LOG_PREFIX import stopped before native overwrite", failure)
                }
            }

            override fun after(param: Call) {
                finishNativeImport(param)
            }
        })
        HookRuntime.deoptimize(imported)
    }

    private fun finishNativeImport(param: HookCallback.Call) {
        val run = param.getObjectExtra(RUN_EXTRA) as? ImportRun
        try {
            if (run != null && (param.throwable != null || param.result !== HostCoroutines.suspended(classLoader))) {
                finishImport(run, param.result, param.throwable == null)
            }
            if (param.throwable != null) cleanupStagedEpubDirectory(param.getObjectExtra(INITIAL_DIRECTORY_EXTRA))
        } finally {
            try {
                if (run != null) activeImport.set(param.getObjectExtra(PREVIOUS_RUN_EXTRA) as? ImportRun)
                restoreRequestEpoch(param)
            } finally {
                HostCoroutines.finishInvocationContinuations(classLoader, param)
            }
        }
    }

    private fun hookNativeTxtImport(handles: MutableList<XposedInterface.HookHandle>) {
        val method = host.method("txt")
        handles += HookRuntime.hookMethod(method, object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
            override fun before(param: Call) {
                try { prepareTxtImport(param) } catch (error: Throwable) {
                    param.throwable = error
                    if (error !is CancellationException) HookRuntime.logError("$LOG_PREFIX TXT import stopped before book commit", error)
                }
            }
            override fun after(param: Call) = finishNativeImport(param)
        })
        handles += HookRuntime.hookMethod(method, object : HookCallback(XposedInterface.PRIORITY_LOWEST) {
            override fun before(param: Call) {
                val run = activeImport.get() ?: return
                if (param.thisObject !== run.repository || param.args.getOrNull(host.index("txt.book")) == null) return
                try {
                    checkStorageIdentity(run.identity, run.epoch)
                    val slot = host.index("txt.uri")
                    val uri = param.args.getOrNull(slot)?.toString().orEmpty()
                    if (run.oldBook != null) run.oldBook.uriOrNull()?.let { param.args[slot] = it }
                    else if (run.independent && uri.isNotBlank()) param.args[slot] = "$uri#reamicro-independent-${run.uuid}"
                    run.metadata = run.metadata.withSize((param.args.getOrNull(host.index("txt.size")) as? Number)?.toLong())
                } catch (error: Throwable) { param.throwable = error }
            }
        })
        HookRuntime.deoptimize(method)
    }

    private fun prepareTxtImport(param: HookCallback.Call) {
        val originalCompletion = param.args.getOrNull(host.index("txt.continuation"))
        val resumed = originalCompletion?.takeIf(::isImportContinuation)?.let(importRuns::get)
        if (resumed != null) {
            bindImportRun(param, resumed)
            checkStorageIdentity(resumed.identity, resumed.epoch)
            return
        }
        if (!settingsProvider().canRunReaderOverwriteCheck) return
        bindRequestEpoch(param, cancellationEpoch.get())
        val identity = storageIdentity()
        val book = requireNotNull(param.args.getOrNull(host.index("txt.book")))
        val sourcePath = requireNotNull(param.args.getOrNull(host.index("txt.directory")))
        val source = File(sourcePath.toString()).canonicalFile
        val epoch = requireNotNull(activeRequestEpoch.get())
        check(book.longOrNull("uid") == identity.accountId && nativeStaging.ownsTxtDirectory(source, identity, epoch)) {
            "TXT 导入暂存目录与当前账号不一致"
        }
        val opf = requireNotNull(obtainOpf(sourcePath)) { "TXT 转换内容不可用" }
        val uuid = requireNotNull(book.uuidOrNull())
        val title = book.titleOrNull() ?: resolveImportTitle(opf, null) ?: "未命名"
        val repository = requireNotNull(param.thisObject)
        val uri = param.args.getOrNull(host.index("txt.uri"))?.toString().orEmpty()
        val preDecision = consumePreImportDecision(uuid, title, identity)
        val conflict = if (preDecision == null) findImportConflict(repository, uuid, uri, title, identity) else null
        val decision = preDecision?.decision ?: conflict?.let { showOverwriteConfirm(it.oldTitle, title, identity) }
        if (decision == OverwriteDecision.CANCEL || decision == OverwriteDecision.PASSTHROUGH) throw CancellationException("导入已取消")
        val old = if (decision == OverwriteDecision.OVERWRITE) {
            ReaMicroBookMetadataSync.latestBook(repository, requireNotNull(preDecision?.oldBook ?: conflict?.oldBook))
                ?: error("原图书已变化，请重新导入")
        } else null
        val targetUuid = old?.uuidOrNull() ?: if (decision == OverwriteDecision.INDEPENDENT) UUID.randomUUID().toString() else uuid
        val newOpf = opf.withUuid(targetUuid)
        val metadata = ReaMicroBookMetadataSync.metadataFromOpf(newOpf, sourcePath)
            .mergeWith(preDecision?.metadataPatch ?: BookMetadataPatch()).withSize(book.longOrNull("size"))
        val completion = requireNotNull(originalCompletion)
        val run = ImportRun(identity, epoch, repository, targetUuid, old, metadata,
            HostCoroutines.continuationJob(classLoader, completion), decision == OverwriteDecision.INDEPENDENT)
        bindTxtRun(param, run, book, sourcePath, newOpf)
    }

    private fun bindTxtRun(param: HookCallback.Call, run: ImportRun, book: Any, source: Any, opf: Any) {
        runningImports += run
        bindImportRun(param, run)
        param.args[host.index("txt.book")] = requireNotNull(HostReaderPositionBindings.current).copy("book", book,
            mapOf("id" to 0L, "uuid" to run.uuid, "uri" to File(nativeBooksRoot(run.identity), run.uuid).path))
        param.args[host.index("txt.directory")] = prepareImportDirectory(source, run.uuid, opf, run, nativeBooksRoot(run.identity))
        HostCoroutines.observeContinuation(classLoader, param, host.index("txt.continuation"),
            onCompleted = { result, success -> finishImport(run, result, success) })
    }

    private fun isImportContinuation(value: Any): Boolean =
        host.type("importContinuation").isInstance(value) || host.type("txtContinuation").isInstance(value)

    private fun importRunForCommit(param: HookCallback.Call): ImportRun? {
        val continuation = param.args.lastOrNull() ?: return null
        if (!isImportContinuation(continuation)) return null
        val run = importRuns[continuation] ?: return null
        if (host.read("repository.dao", run.repository) !== param.thisObject) return null
        checkStorageIdentity(run.identity, run.epoch)
        return run
    }

    private fun bookProperties(book: Any): Map<String, Any?> = requireNotNull(HostReaderPositionBindings.current)
        .properties("book").mapValues { it.value.get(book) }

    private fun patchedImportBook(run: ImportRun, book: Any, preserveLatestOwner: Boolean = false): Any {
        val before = bookProperties(book)
        check(before["uid"] == run.identity.accountId && before["uuid"] == run.uuid) { "原生导入图书与确认目标不一致" }
        val owner = if (preserveLatestOwner && run.oldBook != null) {
            ReaMicroBookMetadataSync.latestBook(run.repository, run.oldBook) ?: error("原图书记录已变化，请重新导入")
        } else run.oldBook
        checkStorageIdentity(run.identity, run.epoch)
        val changes = BookImportOverwritePolicy.changes(before, owner?.let(::bookProperties), run.metadata,
            System.currentTimeMillis(), preserveLatestOwner)
        return requireNotNull(HostReaderPositionBindings.current).copy("book", book, changes)
            .also { run.preparedBook = it }
    }

    private fun markImportBookCommitted(run: ImportRun, book: Any, success: Boolean) {
        if (!success) return
        run.preparedBook = book
        run.bookCommitted = true
    }

    private fun hookImportCommits(handles: MutableList<XposedInterface.HookHandle>) {
        // Patching the native copy before its equality check also handles a content/size-only update.
        handles += HookRuntime.hookMethod(host.method("bookCopy"), object : HookCallback() {
            override fun after(param: Call) {
                val run = activeImport.get() ?: return
                val old = run.oldBook ?: return
                val source = param.args.firstOrNull() ?: return
                if (param.throwable != null || source.longOrNull("id") != old.longOrNull("id") ||
                    source.longOrNull("uid") != run.identity.accountId || source.uuidOrNull() != run.uuid) return
                try {
                    checkStorageIdentity(run.identity)
                    param.result = patchedImportBook(run, requireNotNull(param.result))
                } catch (failure: Throwable) { param.throwable = failure }
            }
        })
        for (role in listOf("daoUpdate", "daoInsert")) {
            val commit = host.method(role)
            handles += HookRuntime.hookMethod(commit, object : HookCallback(XposedInterface.PRIORITY_LOWEST) {
                override fun before(param: Call) {
                    try {
                        val run = importRunForCommit(param) ?: return
                        val book = patchedImportBook(run, requireNotNull(param.args.firstOrNull()), preserveLatestOwner = true)
                        param.args[0] = book
                        param.setObjectExtra(RUN_EXTRA, run)
                        param.setObjectExtra(COMMIT_BOOK_EXTRA, book)
                        val replacePlaceholder = role == "daoInsert" && run.oldBook != null
                        val oldId = run.oldBook.longOrNull("id")
                        HostCoroutines.observeContinuation(
                            classLoader, param, param.args.lastIndex,
                            onCompleted = { _, success -> markImportBookCommitted(run, book, success) },
                            transformSuccess = { value -> if (replacePlaceholder) requireNotNull(oldId) else value },
                        )
                        if (replacePlaceholder) {
                            // Keep the primary key and its child records; an INSERT OR REPLACE can delete them.
                            val result = host.method("daoUpdate").invoke(param.thisObject, *param.args)
                            param.result = if (result === HostCoroutines.suspended(classLoader)) result else requireNotNull(oldId)
                        }
                    } catch (failure: Throwable) { param.throwable = failure }
                }
                override fun after(param: Call) {
                    try {
                        val run = param.getObjectExtra(RUN_EXTRA) as? ImportRun ?: return
                        val book = param.getObjectExtra(COMMIT_BOOK_EXTRA) ?: return
                        if (param.result !== HostCoroutines.suspended(classLoader)) {
                            markImportBookCommitted(run, book, param.throwable == null)
                        }
                    } finally {
                        HostCoroutines.finishInvocationContinuations(classLoader, param)
                    }
                }
            })
            HookRuntime.deoptimize(commit)
        }
        handles += HookRuntime.hookMethod(host.method("daoDelete"), object : HookCallback() {
            override fun before(param: Call) {
                try {
                    val run = importRunForCommit(param) ?: return
                    val oldId = run.oldBook.longOrNull("id") ?: return
                    check((param.args.firstOrNull() as? Number)?.toLong() == oldId) { "覆盖导入的原书主键已变化" }
                    // The native placeholder branch and native error cleanup must not delete the original owner.
                    param.result = HostCoroutines.unit(classLoader)
                } catch (failure: Throwable) { param.throwable = failure }
            }
        })
        HookRuntime.deoptimize(host.method("daoDelete"))
        HookRuntime.deoptimize(host.method("bookCopy"))
    }

    private fun finishImport(run: ImportRun, result: Any?, success: Boolean) {
        if (!run.closed.compareAndSet(false, true)) return
        var keepContent = false
        try {
            val id = if (result != null && host.type("pair").isInstance(result)) {
                (host.read("pair.second", result) as? Number)?.toLong()
            } else null
            val oldId = run.oldBook.longOrNull("id")
            val completed = success && id != null && id > 0L && (oldId == null || id == oldId)
            // A successful Pair is produced after the native row/catalog work. Verification,
            // notification or cancellation after that boundary cannot undo its content files.
            keepContent = ImportCompletionState.keepPublishedContent(completed, run.bookCommitted && run.oldBook != null)
            if (keepContent) run.publication?.complete()
            if (completed) {
                if (AccountHostRuntime.isCurrentStorage(run.identity, activityProvider())) {
                    val expected = run.preparedBook ?: run.oldBook
                    if (expected != null) {
                        val stored = if (run.oldBook == null) {
                            findExistingBookByUuid(run.repository, run.uuid, run.identity)
                                ?.takeIf { it.longOrNull("id") == id }
                        } else ReaMicroBookMetadataSync.latestBook(run.repository, expected)
                            ?: error("导入完成后未读到原图书")
                        requireNotNull(stored) { "导入完成后未读到书架记录" }
                        val patch = run.metadata.withPreservedAssociation(expected.longOrNull("cloudId"),
                            host.read("book.publisher", expected) as? String)
                        check(ReaMicroBookMetadataSync.verifyPatch(stored, if (run.oldBook == null) stored else expected, patch)) {
                            "导入完成回读与提交元数据不同"
                        }
                    }
                }
                run.publication?.complete()
                HookRuntime.log("$LOG_PREFIX import committed and verified: overwrite=${run.oldBook != null}")
            } else if (!keepContent) {
                run.publication?.rollback()
                HookRuntime.log("$LOG_PREFIX import cancelled before book commit; staged files rolled back")
            } else {
                // The content row is already durable; retain its matching files if a later auxiliary step fails.
                run.publication?.complete()
                HookRuntime.log("$LOG_PREFIX import ended after content commit; original book owner retained")
            }
        } catch (failure: Throwable) {
            if (keepContent) runCatching { run.publication?.complete() }
            else runCatching { run.publication?.rollback() }.onFailure(failure::addSuppressed)
            HookRuntime.logError("$LOG_PREFIX import completion verification stopped", failure)
        } finally {
            synchronized(importRuns) { importRuns.entries.removeAll { it.value === run } }
            runningImports -= run
        }
    }

    private fun hookEpubFileManagerImport(handles: MutableList<XposedInterface.HookHandle>) {
        val imported = host.method("epubImport")
        handles += HookRuntime.hookMethod(imported, object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
            override fun before(param: Call) {
                try {
                    if (!settingsProvider().canRunReaderOverwriteCheck) return
                    bindRequestEpoch(param, cancellationEpoch.get())
                    val identity = storageIdentity()
                    val source = param.args.getOrNull(host.index("epubImport.source")) ?: return
                    val opf = obtainOpf(source) ?: return
                    val title = resolveImportTitle(opf, null) ?: "未命名"
                    val uuid = resolveImportUuid(opf).orEmpty()
                    require(uuid.isNotBlank()) { "导入图书缺少有效标识" }
                    // Online downloads already parse into private staging and own their publication transaction.
                    val booksDirectory = requireNotNull(param.args.getOrNull(host.index("epubImport.directory")))
                    val nativeBooksRoot = nativeBooksRoot(identity)
                    if (OnlineCompletionImportSignal.matches(uuid, title, "") &&
                        File(booksDirectory.toString()).canonicalFile != nativeBooksRoot) return
                    if (File(booksDirectory.toString()).canonicalFile != nativeBooksRoot) {
                        throw CancellationException("EPUB导入目录与当前账号不一致，请重新导入")
                    }
                    val remembered = consumePreImportDecision(uuid, title, identity)
                    val conflict = if (remembered == null) repositoryRef?.get()?.let {
                        findImportConflict(it, uuid, "", title, identity)
                    } else null
                    val preDecision = remembered ?: conflict?.let {
                        val choice = showOverwriteConfirm(it.oldTitle, title, identity)
                        preDecisionOf(choice, it, opf, source, identity)
                    }
                    checkStorageIdentity(identity)
                    if (preDecision?.decision == OverwriteDecision.CANCEL || preDecision?.decision == OverwriteDecision.PASSTHROUGH) {
                        throw CancellationException("导入已取消")
                    }
                    val targetUuid = when (preDecision?.decision) {
                        OverwriteDecision.OVERWRITE -> requireNotNull(preDecision.oldUuid)
                        OverwriteDecision.INDEPENDENT -> UUID.randomUUID().toString()
                        else -> uuid
                    }
                    val temporaryUuid = newEpubImportStagingUuid(nativeBooksRoot, targetUuid)
                    val stage = EpubStage(identity, requireNotNull(activeRequestEpoch.get()), title, uuid, targetUuid,
                        temporaryUuid, preDecision, booksDirectory, source, opf)
                    param.setObjectExtra(EPUB_STAGE_EXTRA, stage)
                    opf.withUuid(temporaryUuid).overwriteToRoot(source, identity)
                } catch (failure: Throwable) {
                    param.throwable = failure
                    if (failure !is CancellationException) HookRuntime.logError("$LOG_PREFIX EPUB import stopped before native overwrite", failure)
                }
            }
            override fun after(param: Call) {
                val stage = param.getObjectExtra(EPUB_STAGE_EXTRA) as? EpubStage
                try {
                    if (stage == null) return
                    if (param.throwable != null) return
                    checkStorageIdentity(stage.identity, stage.epoch)
                    val result = requireNotNull(param.result)
                    val directory = requireNotNull(host.read("pair.first", result))
                    val opf = requireNotNull(host.read("pair.second", result))
                    val expected = requireNotNull(host.method("resolve").invoke(stage.booksDirectory, stage.temporaryUuid, true))
                    check(File(directory.toString()).canonicalFile == File(expected.toString()).canonicalFile) { "原生EPUB暂存目录发生变化" }
                    opf.withUuid(stage.targetUuid).overwriteToRoot(directory, stage.identity)
                    stagedEpubDirectories[File(directory.toString()).canonicalPath] = stage
                    stage.preDecision?.let { rememberPreImportDecision(stage.targetUuid, stage.title, it) }
                    HookRuntime.log("$LOG_PREFIX EPUB staged and verified: prechecked=${stage.preDecision != null}")
                } catch (failure: Throwable) {
                    param.throwable = failure
                    HookRuntime.logError("$LOG_PREFIX EPUB staging completion stopped", failure)
                } finally {
                    if (stage != null && AccountHostRuntime.isCurrentStorage(stage.identity, activityProvider())) {
                        runCatching {
                            AccountHostRuntime.withCurrentStorage(stage.identity) {
                                stage.sourceOpf.withUuid(stage.originalUuid)
                                host.method("overwrite").invoke(stage.sourceOpf, stage.source)
                                // Some parsers can share their in-memory OPF; the returned object stays at the target UUID.
                                if (param.throwable == null) host.read("pair.second", param.result)?.withUuid(stage.targetUuid)
                            }
                        }.onFailure { HookRuntime.logError("$LOG_PREFIX EPUB source identifier restore stopped", it) }
                    }
                    if (stage != null && param.throwable != null && AccountHostRuntime.isCurrentStorage(stage.identity, activityProvider())) {
                        runCatching {
                            val path = host.method("resolve").invoke(stage.booksDirectory, stage.temporaryUuid, true) ?: return@runCatching
                            val directory = File(path.toString()).canonicalFile
                            val parent = File(stage.booksDirectory.toString()).canonicalFile
                            check(directory.name == stage.temporaryUuid && directory.parentFile == parent)
                            if (directory.exists()) deleteImportPath(directory, stage.identity)
                        }.onFailure { HookRuntime.logError("$LOG_PREFIX EPUB staging cleanup stopped", it) }
                    }
                    restoreRequestEpoch(param)
                }
            }
        })
        HookRuntime.deoptimize(imported)
    }

    private fun findImportConflict(
        repository: Any, uuid: String, uriOverride: String, title: String, identity: AccountHostRuntime.Identity,
    ): ImportConflict? {
        checkStorageIdentity(identity)
        val uuidBook = if (uuid.isNotBlank()) findExistingBookByUuid(repository, uuid, identity) else null
        val duplicateByUuid = uuidBook != null
        val urlBook = if (uuidBook == null && uriOverride.isNotBlank()) {
            findExistingBookByUrl(repository, uriOverride, identity)
        } else {
            null
        }
        val titleBook = if (uuidBook == null && urlBook == null) findExistingBookByExactTitle(repository, title, identity) else null
        val existingBook = selectImportConflict(uuidBook, urlBook, titleBook)
        if (!duplicateByUuid && urlBook == null && titleBook == null) return null
        HookRuntime.log("$LOG_PREFIX import conflict matched: uuid=$duplicateByUuid, source=${urlBook != null}, title=${titleBook != null}")
        return ImportConflict(
            oldBook = existingBook,
            oldTitle = existingBook.titleOrNull() ?: title,
            oldUuid = existingBook.uuidOrNull(),
            oldUri = existingBook.uriOrNull(),
        )
    }

    private fun findExistingBookByUrl(repository: Any, url: String, identity: AccountHostRuntime.Identity): Any? =
        listExistingBooks(repository, identity).firstOrNull { it.uriOrNull() == url && (it.longOrNull("id") ?: 0L) > 0L }

    private fun firstFlowValue(flow: Any): Any? = invokeSuspendBlocking(host.method("first"), null, flow)

    private fun resolveImportTitle(opf: Any?, file: Any?): String? =
        (host.read("title.value", host.read("metadata.title", host.read("opf.metadata", opf))) as? String)
            ?.takeIf(String::isNotBlank) ?: resolveImportTitleFromFile(file)

    private fun resolveImportUuid(opf: Any?): String? =
        (host.read("identifier.value", host.read("metadata.uuid", host.read("opf.metadata", opf))) as? String)
            ?.takeIf(String::isNotBlank) ?: resolveReaMicroMd5Identifier(opf)

    private fun resolveReaMicroMd5Identifier(opf: Any?): String? {
        val metadata = host.read("opf.metadata", opf) ?: return null
        val metas = host.read("metadata.metas", metadata) as? Iterable<*> ?: return null
        return metas.firstNotNullOfOrNull { meta ->
            if (!(host.read("meta.property", meta) as? String).equals("reamicro:md5", ignoreCase = true)) return@firstNotNullOfOrNull null
            (host.read("meta.value", meta) as? String)?.trim()?.takeIf(REAMICRO_MD5_REGEX::matches)
        }
    }

    private fun showOverwriteConfirm(
        oldTitle: String, newTitle: String, identity: AccountHostRuntime.Identity,
    ): OverwriteDecision {
        checkStorageIdentity(identity)
        val activity = activityProvider() ?: run {
            HookRuntime.log("$LOG_PREFIX no activity for overwrite confirmation; refusing silent overwrite")
            return OverwriteDecision.CANCEL
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HookRuntime.log("$LOG_PREFIX overwrite confirmation requested on main thread; refusing silent overwrite")
            return OverwriteDecision.CANCEL
        }
        val latch = CountDownLatch(1)
        var decision = OverwriteDecision.CANCEL
        var shownDialog: Dialog? = null
        activity.runOnModuleUiThread {
            if (activity.isFinishing || activity.isDestroyed || !settingsProvider().canRunReaderOverwriteCheck ||
                !AccountHostRuntime.isCurrentStorage(identity, activity)) {
                latch.countDown()
                return@runOnModuleUiThread
            }
            runCatching {
                shownDialog = showOverwriteDialog(activity, oldTitle, newTitle, identity) { result ->
                    decision = if (AccountHostRuntime.isCurrentStorage(identity, activity)) result else OverwriteDecision.CANCEL
                    latch.countDown()
                }
                HookRuntime.log("$LOG_PREFIX overwrite confirmation requested")
            }.onFailure {
                HookRuntime.logError("$LOG_PREFIX native overwrite confirmation unavailable", it)
                latch.countDown()
            }
        }
        try {
            while (!latch.await(100L, TimeUnit.MILLISECONDS)) {
                checkStorageIdentity(identity)
                if (activity.isFinishing || activity.isDestroyed) throw CancellationException("导入确认页面已关闭")
            }
            checkStorageIdentity(identity)
            HookRuntime.log("$LOG_PREFIX overwrite confirmation resolved: $decision")
            return decision
        } finally {
            activity.runOnModuleUiThread { shownDialog?.takeIf(Dialog::isShowing)?.dismiss() }
        }
    }

    private fun showOverwriteDialog(
        activity: Activity,
        oldTitle: String,
        newTitle: String,
        identity: AccountHostRuntime.Identity,
        onResult: (OverwriteDecision) -> Unit,
    ): Dialog = HostImportOverwriteDialogs.show(activity, oldTitle, newTitle,
        isCurrent = { !activity.isFinishing && !activity.isDestroyed && settingsProvider().canRunReaderOverwriteCheck &&
            AccountHostRuntime.isCurrentStorage(identity, activity) },
        onResult = { choice ->
            onResult(when (choice) {
                HostImportOverwriteDialogs.Choice.COVER -> OverwriteDecision.OVERWRITE
                HostImportOverwriteDialogs.Choice.IMPORT_NEW -> OverwriteDecision.INDEPENDENT
                HostImportOverwriteDialogs.Choice.CANCEL -> OverwriteDecision.CANCEL
            })
        },
    )

    private fun findExistingBookByExactTitle(
        repository: Any, title: String, identity: AccountHostRuntime.Identity,
    ): Any? {
        val normalized = title.normalizedBookTitle()
        if (normalized.isBlank()) return null
        return listExistingBooks(repository, identity).firstOrNull { book ->
            book.titleOrNull()?.normalizedBookTitle() == normalized && (book.longOrNull("id") ?: 0L) > 0L
        }
    }

    private fun storageIdentity(): AccountHostRuntime.Identity =
        requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())) { "账号状态正在变化，请稍后重新导入" }

    private fun nativeBooksRoot(identity: AccountHostRuntime.Identity): File {
        val context = activityProvider() ?: currentApplicationContext() ?: error("导入存储环境暂不可用")
        return File(context.filesDir, "${identity.accountId}/books").canonicalFile
    }

    private fun checkStorageIdentity(identity: AccountHostRuntime.Identity, epoch: Long? = activeRequestEpoch.get()) {
        if (Thread.currentThread().isInterrupted || !ModuleResources.accepting || !settingsProvider().canRunReaderOverwriteCheck) {
            throw CancellationException("导入模块已关闭，请重新导入")
        }
        if (epoch != null && epoch != cancellationEpoch.get()) throw CancellationException("原导入请求已取消，请重新导入")
        if (!AccountHostRuntime.isCurrentStorage(identity, activityProvider())) {
            throw CancellationException("账号已切换，请重新导入")
        }
    }

    private fun guardNativeImportProgress(param: HookCallback.Call, identity: AccountHostRuntime.Identity) {
        val epoch = requireNotNull(activeRequestEpoch.get())
        val index = host.index("import.progress")
        val previous = param.args.getOrNull(index)
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        param.args[index] = ui.function(host.type("function1"), fallback = { throw CancellationException("导入模块状态已变化") }) { arguments ->
            // Both hosts emit progress(10) after UserFlow.first and before the first UID-bound DAO call.
            ImportCompletionState.forwardProgress(
                (arguments?.firstOrNull() as? Number)?.toInt() ?: 0,
                checkCurrent = { checkStorageIdentity(identity, epoch) },
                notify = { if (previous != null) ui.method("invoke1").invoke(previous, *arguments.orEmpty()) },
                afterCommitError = { HookRuntime.logError("$LOG_PREFIX final import progress notification failed after commit", it) },
            )
            HostCoroutines.unit(classLoader)
        }
    }

    private fun listExistingBooks(repository: Any, identity: AccountHostRuntime.Identity): Iterable<*> {
        checkStorageIdentity(identity)
        val dao = requireNotNull(host.read("repository.dao", repository))
        val flow = requireNotNull(host.method("list").invoke(dao, identity.accountId))
        val books = firstFlowValue(flow) as? Iterable<*> ?: error("Import book list returned an unexpected value")
        checkStorageIdentity(identity)
        return books.filterNotNull().filter { it.longOrNull("uid") == identity.accountId }
    }

    private fun findExistingBookByUuid(repository: Any, uuid: String, identity: AccountHostRuntime.Identity): Any? {
        checkStorageIdentity(identity)
        val dao = requireNotNull(host.read("repository.dao", repository))
        val values = mapOf<Int, Any?>(host.index("find.uid") to identity.accountId, host.index("find.uuid") to uuid)
        val arguments = host.method("find").parameterTypes.indices
            .filter { it != host.index("find.continuation") }.map(values::getValue)
        val book = invokeSuspendBlocking(host.method("find"), dao, *arguments.toTypedArray())
        checkStorageIdentity(identity)
        return book?.takeIf { it.longOrNull("uid") == identity.accountId && (it.longOrNull("id") ?: 0L) > 0L }
    }
    private fun Any?.titleOrNull(): String? = stringOrNull("title")
    private fun Any?.uuidOrNull(): String? = stringOrNull("uuid")
    private fun Any?.uriOrNull(): String? = stringOrNull("uri")
    private fun Any?.longOrNull(property: String): Long? = (host.read("book.$property", this) as? Number)?.toLong()
    private fun Any?.stringOrNull(property: String): String? = (host.read("book.$property", this) as? String)?.takeIf(String::isNotBlank)

    private fun String.normalizedBookTitle(): String =
        trim().replace(Regex("\\s+"), " ")

    private inline fun <T> withRequestEpoch(action: () -> T): T {
        val previous = activeRequestEpoch.get()
        activeRequestEpoch.set(cancellationEpoch.get())
        return try { action() } finally { activeRequestEpoch.set(previous) }
    }

    internal fun precheckModuleImport(epubFile: File, sourceUri: String): Boolean = withRequestEpoch { runCatching {
        if (!settingsProvider().canRunReaderOverwriteCheck) return@runCatching true
        if (!epubFile.isFile) return@runCatching true
        val repository = repositoryRef?.get() ?: return@runCatching true
        val identity = storageIdentity()
        val root = okioPathOf(epubFile) ?: return@runCatching true
        val opf = obtainOpf(root) ?: return@runCatching true
        val uuid = resolveImportUuid(opf).orEmpty()
        val title = resolveImportTitle(opf, null) ?: return@runCatching true
        val conflict = findImportConflict(repository, uuid, sourceUri, title, identity) ?: return@runCatching true
        val decision = showOverwriteConfirm(conflict.oldTitle, title, identity)
        checkStorageIdentity(identity)
        when (decision) {
            OverwriteDecision.CANCEL, OverwriteDecision.PASSTHROUGH -> false
            OverwriteDecision.OVERWRITE, OverwriteDecision.INDEPENDENT -> {
                rememberPreImportDecision(uuid, title, preDecisionOf(decision, conflict, opf, null, identity))
                true
            }
        }
    }.onFailure {
        if (it !is CancellationException) HookRuntime.logError("$LOG_PREFIX module import precheck stopped", it)
    }.getOrDefault(false) }

    internal fun prepareModuleDirectoryImport(
        directory: File, sourceUri: String, fallbackUuid: String, fallbackTitle: String, continuingUuid: String?,
        repository: Any,
    ): ModuleDirectoryImportPlan = withRequestEpoch {
        if (!settingsProvider().canRunReaderOverwriteCheck) return@withRequestEpoch ModuleDirectoryImportPlan(true, fallbackUuid, fallbackTitle)
        val identity = storageIdentity()
        // 复用下载流程取得的宿主服务；热重载后的构造器缓存可能尚未恢复。
        require(host.type("repository").isInstance(repository)) { "阅微导入服务类型不匹配" }
        val root = requireNotNull(okioPathOf(directory))
        val opf = requireNotNull(obtainOpf(root)) { "导入内容不可用" }
        val uuid = resolveImportUuid(opf) ?: fallbackUuid
        val title = resolveImportTitle(opf, null) ?: fallbackTitle
        val conflict = if (continuingUuid == null) findImportConflict(repository, uuid, sourceUri, title, identity) else {
            val book = requireNotNull(findExistingBookByUuid(repository, continuingUuid, identity)) {
                "本次下载的书架记录已变化，请重新下载"
            }
            ImportConflict(book, book.titleOrNull() ?: title, book.uuidOrNull(), book.uriOrNull())
        }
        val choice = if (continuingUuid != null) OverwriteDecision.OVERWRITE
            else conflict?.let { showOverwriteConfirm(it.oldTitle, title, identity) }
        if (choice == OverwriteDecision.CANCEL || choice == OverwriteDecision.PASSTHROUGH) {
            return@withRequestEpoch ModuleDirectoryImportPlan(false, uuid, title)
        }
        checkStorageIdentity(identity)
        val targetUuid = when (choice) {
            OverwriteDecision.OVERWRITE -> requireNotNull(conflict?.oldUuid)
            OverwriteDecision.INDEPENDENT -> UUID.randomUUID().toString()
            else -> uuid
        }
        val prepared = opf.withUuid(targetUuid)
        prepared.overwriteToRoot(root, identity)
        val decision = if (choice == null) null else preDecisionOf(choice, requireNotNull(conflict), prepared, root, identity)
        if (decision != null) rememberPreImportDecision(targetUuid, title, decision)
        ModuleDirectoryImportPlan(true, targetUuid, title, decision)
    }

    internal fun discardModuleDirectoryImport(plan: ModuleDirectoryImportPlan) {
        (plan.token as? PreImportDecision)?.let(pendingPreImportDecisions::forget)
    }

    private fun preDecisionOf(
        decision: OverwriteDecision,
        conflict: ImportConflict,
        opf: Any,
        root: Any?,
        identity: AccountHostRuntime.Identity,
    ) = PreImportDecision(decision, conflict.oldTitle, conflict.oldUuid, conflict.oldUri, conflict.oldBook,
        ReaMicroBookMetadataSync.metadataFromOpf(opf, root), identity, requireNotNull(activeRequestEpoch.get()))

    private fun okioPathOf(file: File): Any? = host.method("path").invoke(null, file.absolutePath, false)

    private fun sourceFileName(source: Any?): String = localFile(source)?.name
        ?: source?.toString()?.let { File(it).name }.orEmpty()

    private fun rememberPreImportDecision(uuid: String, title: String, decision: PreImportDecision) {
        checkStorageIdentity(decision.storageIdentity)
        pendingPreImportDecisions.remember(decision.storageIdentity.accountId, decision.storageIdentity.generation, uuid, title, decision)
    }

    private fun consumePreImportDecision(uuid: String, title: String, identity: AccountHostRuntime.Identity): PreImportDecision? =
        pendingPreImportDecisions.consume(identity.accountId, identity.generation, uuid, title)?.also {
            checkStorageIdentity(it.storageIdentity, it.epoch)
        }

    private fun importSibling(bookDir: Any?, uuid: String, targetParent: File? = null): Pair<Any, Pair<File, File>> {
        val sourcePath = requireNotNull(bookDir) { "Import directory unavailable" }
        val parentPath = requireNotNull(host.method("parent").invoke(sourcePath)) { "Import parent unavailable" }
        val sourceParent = File(parentPath.toString()).canonicalFile
        val parent = targetParent ?: sourceParent
        val targetPath = if (targetParent == null) requireNotNull(host.method("resolve").invoke(parentPath, uuid, true))
            else requireNotNull(okioPathOf(File(parent, uuid)))
        val source = File(sourcePath.toString()).canonicalFile
        val target = File(targetPath.toString()).canonicalFile
        check(source.parentFile == sourceParent && target.parentFile == parent && target != parent) { "Invalid import directory" }
        check(source.isDirectory) { "Import source directory missing" }
        return targetPath to (source to target)
    }
    private fun prepareImportDirectory(bookDir: Any?, uuid: String, opf: Any, run: ImportRun, targetParent: File? = null): Any {
        val (targetPath, directories) = importSibling(bookDir, uuid, targetParent)
        val (source, target) = directories
        checkStorageIdentity(run.identity)
        // No directory is replaced here, so existing local cover bytes remain in place.
        if (source == target) return targetPath
        val stage = File(requireNotNull(target.parentFile), ".${target.name}.reamicro-stage-${UUID.randomUUID()}")
        try {
            Files.walk(source.toPath()).use { paths ->
                paths.forEach { path ->
                    checkStorageIdentity(run.identity)
                    check(!Files.isSymbolicLink(path)) { "导入目录包含符号链接" }
                    val destination = stage.toPath().resolve(source.toPath().relativize(path)).toFile()
                    if (Files.isDirectory(path)) {
                        AccountHostRuntime.withCurrentStorage(run.identity) { check(destination.isDirectory || destination.mkdirs()) }
                    } else {
                        copyOnlineCompletionFileAtomically(path.toFile(), destination,
                            commit = { action -> AccountHostRuntime.withCurrentStorage(run.identity, action = action) },
                            checkCurrent = { checkStorageIdentity(run.identity) })
                    }
                }
            }
            checkStorageIdentity(run.identity)
            run.oldBook?.let { original ->
                preserveImportCoverFile(
                    target, stage, bookProperties(original)["cover"] as? String, run.metadata.cover,
                    commit = { action -> AccountHostRuntime.withCurrentStorage(run.identity, action = action) },
                    checkCurrent = { checkStorageIdentity(run.identity) },
                )?.let { preserved -> run.metadata = run.metadata.copy(cover = preserved) }
            }
            host.method("overwrite").invoke(opf, requireNotNull(okioPathOf(stage)))
            run.publication = publishOnlineCompletionDirectory(stage, target,
                commit = { action -> AccountHostRuntime.withCurrentStorage(run.identity, action = action) },
                checkCurrent = { check(AccountHostRuntime.isCurrentStorage(run.identity, activityProvider())) { "账号已切换" } })
            // Only the module's unique EPUB temporary directory can be discarded before completion.
            stagedEpubDirectories.remove(source.canonicalPath)?.let { stage ->
                check(stage.identity.accountId == run.identity.accountId && stage.identity.generation == run.identity.generation)
                deleteImportPath(source, run.identity)
            }
            return targetPath
        } finally {
            if (stage.exists()) Files.walk(stage.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private fun deleteImportPath(file: File, identity: AccountHostRuntime.Identity) {
        check(AccountHostRuntime.isCurrentStorage(identity, activityProvider())) { "清理导入暂存时账号已变化" }
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                AccountHostRuntime.withCurrentStorage(identity) { Files.delete(path) }
            }
        }
    }

    private fun cleanupStagedEpubDirectory(value: Any?) {
        val directory = value?.let { File(it.toString()).canonicalFile } ?: return
        val stage = stagedEpubDirectories.remove(directory.path) ?: return
        runCatching {
            val parent = File(stage.booksDirectory.toString()).canonicalFile
            check(directory.parentFile == parent && directory.name == stage.temporaryUuid) { "导入暂存目录发生变化" }
            if (directory.exists()) deleteImportPath(directory, stage.identity)
        }.onFailure { HookRuntime.logError("$LOG_PREFIX cancelled EPUB staging cleanup stopped", it) }
    }
    private fun obtainOpf(root: Any): Any? = runCatching {
        host.method("obtain").invoke(null, root)
    }.onFailure { HookRuntime.logError("$LOG_PREFIX import OPF read failed", it) }.getOrNull()

    private fun Any.overwriteToRoot(root: Any, identity: AccountHostRuntime.Identity): Any = apply {
        checkStorageIdentity(identity)
        host.method("overwrite").invoke(this, root)
        checkStorageIdentity(identity)
    }

    private fun Any.withUuid(uuid: String): Any = apply {
        val metadata = requireNotNull(host.read("opf.metadata", this)) { "Import metadata unavailable" }
        val identifier = requireNotNull(host.read("metadata.uuid", metadata)) { "Import UUID unavailable" }
        host.field("identifier.value").set(identifier, uuid)
        check(host.field("identifier.value").get(identifier) == uuid) { "Import UUID could not be rewritten" }
    }

    private fun resolveImportTitleFromFile(file: Any?): String? = sourceFileName(file).substringBeforeLast('.')
        .takeIf(String::isNotBlank)
    private fun importBookSize(args: Array<Any?>?): Long? =
        (args?.getOrNull(host.index("import.size")) as? Number)?.toLong()?.takeIf { it > 0L }
            ?: platformFileSize(args?.getOrNull(host.index("import.source")))
    private fun localFile(source: Any?): File? = when {
        source == null -> null
        source is File -> source
        host.type("path").isInstance(source) -> File(source.toString())
        host.type("platform").isInstance(source) -> com.reamicro.fix.core.HostRuntimeBindings.files?.file(source)
        else -> null
    }
    private fun platformFileSize(file: Any?): Long? = localFile(file)?.takeIf(File::isFile)?.length()?.takeIf { it > 0L }
    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? =
        com.reamicro.fix.core.HostCoroutines.invoke(classLoader, method, target, *args)

    private enum class OverwriteDecision {
        OVERWRITE,
        INDEPENDENT,
        CANCEL,

        PASSTHROUGH,
    }

    private data class ImportConflict(
        val oldBook: Any?,
        val oldTitle: String,
        val oldUuid: String?,
        val oldUri: String?,
    )

    private data class PreImportDecision(
        val decision: OverwriteDecision,
        val oldTitle: String,
        val oldUuid: String?,
        val oldUri: String?,
        val oldBook: Any?,
        val metadataPatch: BookMetadataPatch,
        val storageIdentity: AccountHostRuntime.Identity,
        val epoch: Long,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val RUN_EXTRA = "reamicro.import.run"
        const val PREVIOUS_RUN_EXTRA = "reamicro.import.previous.run"
        const val REQUEST_EPOCH_EXTRA = "reamicro.import.request.epoch"
        const val PREVIOUS_EPOCH_EXTRA = "reamicro.import.previous.epoch"
        const val COMMIT_BOOK_EXTRA = "reamicro.import.commit.book"
        const val EPUB_STAGE_EXTRA = "reamicro.epub.stage"
        const val INITIAL_DIRECTORY_EXTRA = "reamicro.import.initial.directory"
        const val PRE_IMPORT_DECISION_TTL_MS = 120_000L

        val REAMICRO_MD5_REGEX = Regex("^[0-9a-fA-F]{32}$")
    }
}
