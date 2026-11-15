package com.reamicro.fix.hook

import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Keep native duplicate checks and TXT conversion inside a single account-owned import. */
internal class NativeImportStaging(
    private val host: HostReaderExtrasBindings.Contract,
    private val owner: Owner,
) {
    interface Owner {
        fun enabled(): Boolean
        fun identity(): AccountHostRuntime.Identity
        fun epoch(): Long
        fun check(identity: AccountHostRuntime.Identity, epoch: Long)
        fun booksRoot(identity: AccountHostRuntime.Identity): File
        fun rememberRepository(repository: Any)
    }

    private class Ticket(
        val identity: AccountHostRuntime.Identity,
        val epoch: Long,
        val job: Any?,
    ) {
        val directories = Collections.synchronizedSet(linkedSetOf<File>())
    }

    private val loader = host.type("repository").classLoader
    private val workers = Collections.synchronizedMap(IdentityHashMap<Any, Ticket>())
    private val parents = ConcurrentHashMap<String, Ticket>()
    private val current = ThreadLocal<Ticket?>()

    fun install(handles: MutableList<XposedInterface.HookHandle>) {
        handles += HookRuntime.hookMethod(host.method("importWorkerResume"),
            object : HookCallback(XposedInterface.PRIORITY_HIGHEST) {
                override fun before(param: Call) {
                    val worker = param.thisObject ?: return
                    if (!owner.enabled() && workers[worker] == null) return
                    try {
                        val ticket = workers[worker] ?: Ticket(owner.identity(), owner.epoch(),
                            HostCoroutines.continuationJob(loader, worker)).also { workers[worker] = it }
                        param.setObjectExtra(PREVIOUS, current.get())
                        param.setObjectExtra(TICKET, ticket)
                        current.set(ticket)
                        owner.check(ticket.identity, ticket.epoch)
                        val manager = requireNotNull(host.read("importWorker.manager", worker))
                        owner.rememberRepository(requireNotNull(host.read("worker.repository", manager)))
                    } catch (error: Throwable) { param.throwable = error }
                }

                override fun after(param: Call) {
                    val ticket = param.getObjectExtra(TICKET) as? Ticket ?: return
                    current.set(param.getObjectExtra(PREVIOUS) as? Ticket)
                    if (param.throwable != null || param.result !== HostCoroutines.suspended(loader)) {
                        param.thisObject?.let(workers::remove)
                        release(ticket)
                    }
                }
            })
        handles += HookRuntime.hookMethod(host.method("exists"), object : HookCallback() {
            override fun before(param: Call) {
                val ticket = current.get() ?: return
                try {
                    owner.check(ticket.identity, ticket.epoch)
                    // The native worker would skip identical files before either import hook.
                    // Continue to the module's decision; no other repository caller is changed.
                    param.result = false
                } catch (error: Throwable) { param.throwable = error }
            }
        })
        handles += HookRuntime.hookMethod(host.constructor("txtConverter"), object : HookCallback() {
            override fun before(param: Call) {
                val ticket = current.get() ?: return
                try { stageTxt(param, ticket) } catch (error: Throwable) { param.throwable = error }
            }
        })
        listOf(host.method("importWorkerResume"), host.method("exists"), host.constructor("txtConverter"))
            .forEach(HookRuntime::deoptimize)
        ModuleResources.blockWhile { if (workers.isEmpty()) null else "图书导入仍在完成，请稍后重载" }
        ModuleResources.cleanup {
            synchronized(workers) { workers.values.toSet() }.forEach(::release)
            workers.clear()
            parents.clear()
            current.remove()
        }
    }

    private fun stageTxt(param: HookCallback.Call, ticket: Ticket) {
        owner.check(ticket.identity, ticket.epoch)
        val parent = File(requireNotNull(param.args.getOrNull(1)).toString()).canonicalFile
        if (parents[parent.path] === ticket) return
        val root = owner.booksRoot(ticket.identity)
        check(parent == root && (param.args.getOrNull(2) as? Number)?.toLong() == ticket.identity.accountId) {
            "TXT 转换目录与当前账号不一致"
        }
        val stage = File(root, ".reamicro-txt-import-${UUID.randomUUID()}").absoluteFile
        check(stage.canonicalFile == stage && !stage.exists()) { "TXT 暂存目录已变化" }
        AccountHostRuntime.withCurrentStorage(ticket.identity) {
            owner.check(ticket.identity, ticket.epoch)
            check(root.isDirectory || root.mkdirs()) { "书籍目录不可用" }
            check(stage.mkdir()) { "无法建立 TXT 导入暂存目录" }
        }
        ticket.directories += stage
        parents[stage.path] = ticket
        param.args[1] = requireNotNull(host.method("path").invoke(null, stage.path, false))
    }

    fun ownsTxtDirectory(source: File, identity: AccountHostRuntime.Identity, epoch: Long): Boolean {
        val parent = source.parentFile?.canonicalPath ?: return false
        val ticket = parents[parent] ?: return false
        owner.check(ticket.identity, ticket.epoch)
        return ticket.identity.accountId == identity.accountId && ticket.identity.generation == identity.generation &&
            ticket.epoch == epoch && source.isDirectory && source.canonicalFile == source.absoluteFile
    }

    fun cancel() {
        synchronized(workers) { workers.values.toSet() }.forEach { ticket ->
            ticket.job?.let { job ->
                runCatching { HostCoroutines.cancelJob(loader, job, CancellationException("导入模块已关闭")) }
                    .onFailure { HookRuntime.logError("ReaMicro native import cancellation stopped", it) }
            }
        }
    }

    private fun release(ticket: Ticket) {
        val directories = synchronized(ticket.directories) { ticket.directories.toList().also { ticket.directories.clear() } }
        directories.forEach { directory ->
            parents.remove(directory.path, ticket)
            runCatching {
                val root = owner.booksRoot(ticket.identity)
                check(directory.parentFile == root && directory.canonicalFile == directory &&
                    directory.name.startsWith(".reamicro-txt-import-")) { "TXT 暂存目录已重定向" }
                if (directory.exists()) Files.walk(directory.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        check(!Files.isSymbolicLink(path)) { "TXT 暂存目录包含符号链接" }
                        Files.delete(path)
                    }
                }
            }.onFailure { HookRuntime.logError("ReaMicro TXT staging cleanup stopped", it) }
        }
    }

    private companion object {
        const val TICKET = "ReaMicro.nativeImport.ticket"
        const val PREVIOUS = "ReaMicro.nativeImport.previous"
    }
}
