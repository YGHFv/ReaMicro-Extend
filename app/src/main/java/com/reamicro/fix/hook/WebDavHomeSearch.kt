package com.reamicro.fix.hook

import android.os.Looper
import android.os.SystemClock
import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.hook.webdav.HOME_SEARCH_DEBOUNCE_MS
import com.reamicro.fix.hook.webdav.OnlineSearchGroup
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference

/** 查询可并行执行，宿主状态的读取和合并只在主线程进行。 */
internal class WebDavHomeSearch(private val drive: WebDavDriveHook) {
    private data class Request(
        val sequence: Long,
        val query: String,
        val viewModel: WeakReference<Any>,
        val identity: AccountHostRuntime.Identity?,
        val execution: Long,
    )
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val webDav = LatestSearchWorker("ReaMicroWebDavHomeSearch")
    private val local = LatestSearchWorker("ReaMicroLocalLibraryHomeSearch")
    private val online = LatestSearchWorker("ReaMicroOnlineHomeSearch")
    @Volatile private var current: Request? = null
    private var webDavResults: List<Any> = emptyList()
    private var localResults: List<Any> = emptyList()
    private var debounce: Runnable? = null
    private var localUpdate: Runnable? = null
    @Volatile private var closed = false

    fun request(viewModel: Any, query: String, origin: String = "input") {
        val execution = drive.storageExecutionGeneration.get()
        if (closed || drive.storageClosed || !drive.settingsProvider().moduleEnabled) return
        val request = Request(drive.webDavHomeSearchSeq.incrementAndGet(), query.trim(), WeakReference(viewModel),
            AccountHostRuntime.storageSnapshot(drive.activityProvider()), execution)
        HookRuntime.logAlways("ReaMicro home search request sequence=${request.sequence} origin=$origin " +
            "previous=${current?.sequence} chars=${request.query.length}")
        onMain {
            if (request.sequence != drive.webDavHomeSearchSeq.get() || drive.storageClosed || closed) return@onMain
            cancelWorkers()
            current = request
            if (drive.storagePaused) return@onMain
            webDavResults = emptyList()
            localResults = emptyList()
            val target = request.viewModel.get() ?: return@onMain
            drive.updateHomeWebDavSearchResults(target, emptyList(), emptyList(), emptyList()) { isCurrent(request) }
            if (request.query.isEmpty() || !isCurrent(request)) return@onMain
            drive.updateHomeOnlineCompletionSearchResults(target,
                drive.onlineCompletionPendingSearchGroups(request.query).map { it.copy(sequence = request.sequence) }) {
                isCurrent(request)
            }
            val task = Runnable {
                debounce = null
                if (!isCurrent(request)) return@Runnable
                start(webDav, request, "WebDAV") { ticket ->
                    val results = if (drive.settingsProvider().canRunWebDavCloud && hasWebDavLogin(drive.currentContext())) {
                        drive.searchWebDavBooks(request.query)
                    } else emptyList()
                    publish(request, ticket) {
                        webDavResults = results
                        merge(request)
                    }
                }
                searchLocal(request, extendIndex = true)
                start(online, request, "online") { ticket ->
                    drive.searchOnlineCompletionSourcesProgressively(request.query) { snapshot ->
                        publishOnline(request, ticket, snapshot)
                    }
                }
            }
            debounce = task
            if (!main.postDelayed(task, HOME_SEARCH_DEBOUNCE_MS)) debounce = null
        }
    }

    fun localChanged(generation: Long, indexReady: Boolean) {
        onMain {
            val request = current ?: return@onMain
            if (!isCurrent(request) || generation != drive.localLibraryCacheGeneration.get()) return@onMain
            localUpdate?.let(main::removeCallbacks)
            val task = Runnable {
                localUpdate = null
                if (isCurrent(request) && generation == drive.localLibraryCacheGeneration.get()) {
                    searchLocal(request, extendIndex = !indexReady)
                }
            }
            localUpdate = task
            if (!main.post(task)) localUpdate = null
        }
    }

    fun pause() {
        val sequence = drive.webDavHomeSearchSeq.incrementAndGet()
        onMain { if (drive.webDavHomeSearchSeq.get() == sequence) cancelWorkers() }
    }

    /** Invalidate before the main-thread settings notification can be delayed or coalesced. */
    fun invalidateExecution() {
        drive.webDavHomeSearchSeq.incrementAndGet()
        webDav.cancel()
        local.cancel()
        online.cancel()
        if (drive.onlineSearchTasksState.isInitialized()) drive.onlineSearchTasks.cancelAll()
    }

    fun disable() {
        val sequence = drive.webDavHomeSearchSeq.incrementAndGet()
        onMain {
            if (drive.webDavHomeSearchSeq.get() != sequence || closed) return@onMain
            cancelWorkers()
            webDavResults = emptyList()
            localResults = emptyList()
            current?.viewModel?.get()?.let { target ->
                drive.updateHomeWebDavSearchResults(target, emptyList(), emptyList(), emptyList()) {
                    !closed && !drive.storageClosed && sequence == drive.webDavHomeSearchSeq.get()
                }
            }
            current = null
        }
    }

    fun resume() {
        onMain {
            val saved = current ?: return@onMain
            if (saved.execution != drive.storageExecutionGeneration.get()) return@onMain
            val target = saved.viewModel.get() ?: return@onMain
            if (saved.query.isNotEmpty()) request(target, saved.query, origin = "resume")
        }
    }

    fun clear() {
        val sequence = drive.webDavHomeSearchSeq.incrementAndGet()
        onMain {
            if (drive.webDavHomeSearchSeq.get() != sequence) return@onMain
            cancelWorkers()
            current = null
            webDavResults = emptyList()
            localResults = emptyList()
        }
    }

    fun close() {
        closed = true
        drive.webDavHomeSearchSeq.incrementAndGet()
        cancelWorkers()
        current = null
        webDavResults = emptyList()
        localResults = emptyList()
        main.removeCallbacksAndMessages(null)
        webDav.close()
        local.close()
        online.close()
    }

    fun isCurrent(sequence: Long): Boolean = current?.let {
        it.sequence == sequence && it.query.isNotEmpty() && isCurrent(it)
    } == true

    private fun searchLocal(request: Request, extendIndex: Boolean) {
        start(local, request, "local library") { ticket ->
            val generation = drive.localLibraryCacheGeneration.get()
            val results = if (drive.canShowLocalLibraryEntry()) {
                drive.searchLocalLibraryBooks(request.query, extendIndex)
            } else emptyList()
            publish(request, ticket) {
                if (generation == drive.localLibraryCacheGeneration.get()) {
                    localResults = results
                    merge(request)
                }
            }
        }
    }

    private fun merge(request: Request) {
        request.viewModel.get()?.let {
            drive.updateHomeWebDavSearchResults(it, webDavResults, localResults, null) { isCurrent(request) }
        }
    }

    private fun publishOnline(request: Request, ticket: LatestSearchWorker.Ticket, results: List<OnlineSearchGroup>) {
        publish(request, ticket) {
            request.viewModel.get()?.let {
                drive.updateHomeOnlineCompletionSearchResults(it, results.map { group ->
                    group.copy(sequence = request.sequence)
                }) { isCurrent(request) }
            }
        }
    }

    private fun publish(request: Request, ticket: LatestSearchWorker.Ticket, action: () -> Unit) {
        if (!ticket.isCurrent() || !isCurrent(request)) return
        main.postAtTime({ if (isCurrent(request) && ticket.isCurrent()) action() }, request, SystemClock.uptimeMillis())
    }

    private fun start(worker: LatestSearchWorker, request: Request, label: String, action: (LatestSearchWorker.Ticket) -> Unit) {
        if (!isCurrent(request)) return
        try {
            worker.submitIf({ isCurrent(request) }) { ticket ->
                try {
                    if (ticket.isCurrent()) action(ticket)
                } catch (error: Throwable) {
                    TaskCancellation.rethrowIfCancelled(error)
                    HookRuntime.logError("ReaMicro $label home search failed", error)
                }
            }
        } catch (error: Throwable) {
            if (ModuleResources.accepting && !drive.storageClosed) {
                HookRuntime.logError("ReaMicro $label home search start failed", error)
            }
        }
    }

    private fun isCurrent(request: Request): Boolean =
        current === request && request.sequence == drive.webDavHomeSearchSeq.get() &&
            request.execution == drive.storageExecutionGeneration.get() &&
            !drive.storagePaused && !drive.storageClosed && !closed && drive.settingsProvider().moduleEnabled &&
            request.viewModel.get() != null && (request.query.isEmpty() ||
                request.identity?.let { AccountHostRuntime.isCurrentStorage(it, drive.activityProvider()) } == true)

    private fun cancelWorkers() {
        current?.let(main::removeCallbacksAndMessages)
        debounce?.let(main::removeCallbacks)
        localUpdate?.let(main::removeCallbacks)
        debounce = null
        localUpdate = null
        webDav.cancel()
        local.cancel()
        online.cancel()
        if (drive.onlineSearchTasksState.isInitialized()) drive.onlineSearchTasks.cancelAll()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}
