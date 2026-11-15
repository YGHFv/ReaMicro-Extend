package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.WebDavImportSource
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.callLong
import com.reamicro.fix.hook.webdav.callString
import io.github.libxposed.api.XposedInterface
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/** 来源和账号跟随单个原生导入协程，TXT 转换后的新路径不能作为匹配依据。 */
internal class HostImportSourceBridge(private val drive: WebDavDriveHook) {
    private val activeSource = ThreadLocal<WebDavImportSource?>()
    private val workerSources = Collections.synchronizedMap(WeakHashMap<Any, WebDavImportSource>())

    private fun sourceFor(file: Any?): WebDavImportSource? = file?.let {
        drive.platformFileKeys(it).firstNotNullOfOrNull(drive.pendingWebDavImports::get)
    } ?: activeSource.get()

    private fun release(source: WebDavImportSource) {
        drive.pendingWebDavImports.entries.forEach { (key, value) ->
            if (value === source) drive.pendingWebDavImports.remove(key, source)
        }
        // 每次下载使用独立缓存；只释放该协程的源文件，不触碰账号书库目录。
        runCatching {
            source.localFile.delete()
            source.localFile.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
        }
        ImportCompletionState.release(source.localFile)
        source.finishWorker()
        NativeImportTaskBridge.notifyChanged()
    }

    private fun verifyDirectory(source: WebDavImportSource, value: Any?) {
        source.checkCurrent()
        val directory = File(value?.toString() ?: error("原生导入目录不存在")).canonicalFile
        val uid = directory.parentFile?.name?.toLongOrNull()
        check(directory.name == "books" && uid == source.storageIdentity.accountId) {
            "原生导入目录与下载账号不一致"
        }
        val files = drive.currentContext()?.filesDir?.canonicalFile ?: error("Android context not available")
        check(directory.parentFile?.parentFile == files) { "原生导入目录不在账号书库内" }
    }

    /** Bound source tickets must still cancel a queued native import after the master is off. */
    fun install() = HookRuntime.withManagementHooks {
        val c = requireNotNull(HostStorageBindings.imports) { "Import source contract unavailable" }
        requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.files) { "Native platform files unavailable" }
        val originalFile = c.field("originalFile")
        val invoke = c.method("resume")
        val normal = c.method("normal")
        val defaults = c.method("defaults")
        val txt = c.method("txt")
        val txtDefaults = c.method("txtDefaults")
        val directoryImport = c.method("epubDirectory")
        val coroutines = requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.coroutines)
        val withContext = coroutines.method("withContext")
        val handles = arrayListOf<XposedInterface.HookHandle>()
        try {
            val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
            handles += HookRuntime.hookMethod(work.method("update"), object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
                override fun before(param: Call) {
                    val tracker = param.thisObject ?: return
                    val id = param.args.firstOrNull() as? String ?: return
                    val next = param.args.getOrNull(1) ?: return
                    val lock = work.trackerLock(tracker)
                    lock.lock()
                    try {
                        if (!work.acceptsNativeUpdate(tracker, id, next)) {
                            // A cancelled worker must stop at its next progress boundary. A completed
                            // worker may still refresh its UI; that must retain its durable Success.
                            val previous = work.currentState(tracker, id)?.let(work::stateName)
                            if (previous == "Cancelled" && work.stateName(next) == "Running") {
                                param.throwable = java.util.concurrent.CancellationException("导入任务已取消")
                            } else {
                                param.result = null
                            }
                        }
                        param.setObjectExtra("reamicro.import.work.lock", lock)
                    } catch (failure: Throwable) {
                        lock.unlock()
                        throw failure
                    }
                }
                override fun after(param: Call) {
                    try {
                        (param.getObjectExtra("reamicro.import.work.lock") as? java.util.concurrent.locks.ReentrantLock)?.unlock()
                    } finally {
                        NativeImportTaskBridge.notifyChanged()
                    }
                }
            })
            HookRuntime.deoptimize(work.method("update"))
            handles += HookRuntime.hookMethod(invoke, object : HookCallback() {
                override fun before(param: Call) {
                    val owner = param.thisObject ?: return
                    val file = originalFile.get(owner) ?: return
                    val source = workerSources[owner] ?: sourceFor(file) ?: return
                    workerSources[owner] = source
                    param.setObjectExtra("reamicro.storage.import.bound", true)
                    param.setObjectExtra("reamicro.storage.import.previous", activeSource.get())
                    activeSource.set(source)
                    val resumed = param.args.firstOrNull()
                    val imported = resumed != null && c.type("pair").isInstance(resumed) &&
                        (c.field("pair.first").get(resumed) is Boolean) &&
                        ((c.field("pair.second").get(resumed) as? Number)?.toLong() ?: 0L) > 0L
                    // The native repository has already returned its committed row. Let the worker
                    // publish that result; the next book in an archive still rechecks its own source.
                    if (!imported) runCatching { source.checkCurrent() }.onFailure { param.throwable = it }
                }

                override fun after(param: Call) {
                    if (param.getObjectExtra("reamicro.storage.import.bound") != true) return
                    activeSource.set(param.getObjectExtra("reamicro.storage.import.previous") as? WebDavImportSource)
                    // 挂起时保留来源，恢复执行仍校验同一账号；完成或抛错才清理。
                    if (param.throwable != null || param.result === drive.targetUnit()) {
                        val owner = param.thisObject ?: return
                        workerSources.remove(owner)?.let(::release)
                    }
                }
            })
            handles += HookRuntime.hookMethod(defaults, object : HookCallback() {
                override fun before(param: Call) {
                    val source = sourceFor(param.args.getOrNull(c.value("file"))) ?: return
                    runCatching {
                        source.checkCurrent()
                        param.args[c.value("url")] = source.sourceUrl
                        var mask = (param.args[c.value("mask")] as Int) and c.value("urlBit").inv()
                        source.sourceSize?.takeIf { it > 0 }?.let {
                            param.args[c.value("size")] = it
                            mask = mask and c.value("sizeBit").inv()
                        }
                        param.args[c.value("mask")] = mask
                        drive.rememberBookshelfRepository(param.args[0])
                    }.onFailure { param.throwable = it }
                }
            })
            handles += HookRuntime.hookMethod(withContext, object : HookCallback() {
                override fun before(param: Call) {
                    val source = activeSource.get() ?: return
                    runCatching {
                        source.checkCurrent()
                        val index = withContext.parameterTypes.indices.single {
                            withContext.parameterTypes[it] == coroutines.type("function2")
                        }
                        val block = param.args.getOrNull(index) ?: error("原生导入任务不存在")
                        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
                        param.args[index] = ui.function(coroutines.type("function2"), fallback = {
                            throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException("导入模块状态已变化")
                        }) { arguments ->
                            source.checkCurrent()
                            val previous = activeSource.get()
                            activeSource.set(source)
                            try {
                                coroutines.method("invoke").invoke(block, *arguments.orEmpty())
                            } finally {
                                activeSource.set(previous)
                            }
                        }
                    }.onFailure { param.throwable = it }
                }
            })
            handles += HookRuntime.hookMethod(directoryImport, object : HookCallback() {
                override fun before(param: Call) {
                    val source = activeSource.get() ?: return
                    runCatching { verifyDirectory(source, param.args.getOrNull(1)) }
                        .onFailure { param.throwable = it }
                }
            })
            handles += HookRuntime.hookMethod(normal, object : HookCallback() {
                override fun before(param: Call) {
                    val source = sourceFor(param.args.getOrNull(c.value("normalFile"))) ?: return
                    runCatching {
                        source.checkCurrent()
                        val index = c.value("normalProgress")
                        val previous = param.args.getOrNull(index)
                        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
                        param.args[index] = ui.function(c.type("function1"), fallback = {
                            throw com.reamicro.fix.cloud.webdav.CloudDownloadCancelledException("导入模块状态已变化")
                        }) { arguments ->
                            // 两版宿主在 UserFlow.first 后、首次访问 UID DAO 前回调 progress(10)。
                            ImportCompletionState.forwardProgress(
                                (arguments?.firstOrNull() as? Number)?.toInt() ?: 0,
                                checkCurrent = source::checkCurrent,
                                notify = { if (previous != null) ui.method("invoke1").invoke(previous, *arguments.orEmpty()) },
                                afterCommitError = { HookRuntime.logError("ReaMicro final native import progress failed after commit", it) },
                            )
                            drive.targetUnit()
                        }
                    }.onFailure { param.throwable = it }
                }
            })
            handles += HookRuntime.hookMethod(txt, object : HookCallback() {
                override fun before(param: Call) {
                    val source = activeSource.get() ?: return
                    runCatching {
                        source.checkCurrent()
                        val book = param.args.firstOrNull() ?: error("TXT 图书记录不存在")
                        check(book.callLong("getUid") == source.storageIdentity.accountId) { "TXT 导入账号已变化" }
                        val directory = File(param.args.getOrNull(1)?.toString() ?: error("TXT 导入目录不存在")).canonicalFile
                        verifyDirectory(source, directory.parentFile)
                        check(directory.name == book.callString("getUuid")) { "TXT 导入目录与图书记录不一致" }
                        param.args[c.value("txtUrl")] = source.sourceUrl
                        source.sourceSize?.takeIf { it > 0 }?.let { param.args[c.value("txtSize")] = it }
                    }.onFailure { param.throwable = it }
                }
            })
            listOf(invoke, normal, defaults, txt, txtDefaults, directoryImport, withContext).forEach(HookRuntime::deoptimize)
            com.reamicro.fix.reload.ModuleResources.cleanup { workerSources.clear(); activeSource.remove() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
}
