package com.reamicro.fix.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class ReaderSearchNavigator(private val reader: ReaderHook) {
    private data class Request(val result: FullTextSearchResult, val index: Int, val book: String,
        val generation: Long, val vm: Any, val epub: Any, val mode: Int)
    private val main = Handler(Looper.getMainLooper())
    private data class Work(val value: Request, val ticket: LatestSearchWorker.Ticket) {
        val sequence: Long get() = ticket.sequence
    }
    private val worker = LatestSearchWorker("ReaMicroSearchNavigation")
    private var deadline: Runnable? = null
    private val resolver = ReaderSearchResolver(reader)
    @Volatile var requestedIndex: Int? = null
        private set
    @Volatile var cursorIndex: Int? = null
        private set
    @Volatile var failedIndex: Int? = null
        private set
    @Volatile private var cacheReset = false

    fun cancel() {
        deadline?.let(main::removeCallbacks)
        deadline = null
        worker.cancel()
        requestedIndex = null
        cursorIndex = null
        failedIndex = null
        cacheReset = true
    }
    fun submit(result: FullTextSearchResult, index: Int): Boolean {
        val vm = reader.currentViewModelRef?.get() ?: return false
        val epub = reader.currentEpubStrong ?: reader.currentEpubRef?.get() ?: return false
        val book = reader.searchNavigationState?.bookKey ?: return false
        val mode = reader.currentSearchPagerMode ?: return false
        deadline?.let(main::removeCallbacks)
        requestedIndex = index
        cursorIndex = index
        failedIndex = null
        val request = Request(result, index, book, reader.searchStateGeneration, vm, epub, mode)
        val ticket = worker.submit { ticket ->
            val work = Work(request, ticket)
            try {
                if (cacheReset) { resolver.clear(); cacheReset = false }
                navigate(work)
            } catch (error: Throwable) {
                fail(work, error)
            } finally {
                finishRequest(work)
            }
        }
        deadline = Runnable {
            if (ticket.isCurrent() && requestedIndex != null) {
                worker.cancel()
                requestedIndex = null
                failedIndex = index
                reader.clearHostSearchJump()
                reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
            }
        }.also { main.postDelayed(it, 8_000) }
        reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
        return true
    }
    private fun valid(r: Request): Boolean = !reader.selectionEditSaving.get() && reader.currentViewModelRef?.get() === r.vm &&
        (reader.currentEpubStrong ?: reader.currentEpubRef?.get()) === r.epub &&
        reader.searchStateGeneration == r.generation && reader.searchNavigationState?.bookKey == r.book &&
        reader.currentSearchPagerMode == r.mode
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        check(main.post(task)) { "阅读界面已关闭" }
        return try { task.get(3, TimeUnit.SECONDS) }
        finally { if (!task.isDone) task.cancel(false) }
    }
    private fun navigate(work: Work) {
        val r = work.value
        val startedAt = SystemClock.elapsedRealtime()
        if (!valid(r) || !work.ticket.isCurrent()) return
        Thread.sleep(80)
        if (!valid(r) || !work.ticket.isCurrent()) return
        val loaded = onMain {
            if (valid(r) && work.ticket.isCurrent()) reader.searchScrollBridge?.snapshot(r.vm) else null
        }
        val resolved = resolver.resolve(r.result, r.index, r.vm, r.epub, loaded) { valid(r) && work.ticket.isCurrent() }
        XposedBridge.log("ReaMicro search resolved seq=${work.sequence} index=${r.index} ms=${SystemClock.elapsedRealtime() - startedAt}")
        if (!dispatch(work, resolved)) return
        val arrived = awaitPage(work, resolved)
        onMain {
            if (!valid(r) || !work.ticket.isCurrent()) return@onMain
            check(arrived) { "宿主未确认目标页，请重试或重新搜索" }
            acknowledge(work, resolved)
            XposedBridge.log("ReaMicro search navigation acknowledged seq=${work.sequence} index=${r.index} ranges=${resolved.ranges.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }
    private fun dispatch(work: Work, resolved: ResolvedSearchTarget): Boolean = onMain {
        val r = work.value
        if (!valid(r) || !work.ticket.isCurrent()) return@onMain false
        reader.cancelSelectionPagerConfirmation()

        check(reader.showSearchReaderContent(r.vm)) { "无法关闭阅读菜单，请重新进入阅读页" }
        reader.clearHostSearchJump()
        reader.pendingSearchReturnCaptures = 0
        if (visible(r.vm, resolved)) return@onMain true
        if (resolved.flipStyle == 3 && reader.searchScrollBridge?.command(r.vm, resolved) != null) return@onMain true
        val mark = reader.createSearchResultHighlightMark(resolved.result, r.index)
        val anchor = resolved.scrollElementId ?: resolved.start
        check(reader.jumpToSearchCfi(r.result.intentReceiver, r.vm, anchor.toString(), mark,
            r.result.chapterIndex.coerceAtLeast(0), r.result.chapterTitle, r.result.snippet)) { "宿主未接受搜索跳转" }
        XposedBridge.log("ReaMicro search navigation dispatched seq=${work.sequence} index=${r.index}")
        true
    }
    private fun acknowledge(work: Work, resolved: ResolvedSearchTarget) {
        val r = work.value
        reader.pendingSearchReturnCaptures = 0
        val mark = reader.createSearchResultHighlightMark(resolved.result, r.index) ?: error("命中范围无法绘制")
        if (resolved.flipStyle != 3) reader.searchPageBridge?.page(r.vm)?.let { page ->

            reader.currentPageRef = java.lang.ref.WeakReference(page)
            reader.currentPageStrong = page
            reader.currentScrollElement = null
        }
        reader.applySearchResultHighlight(r.vm, mark, resolved.ranges, r.epub, r.generation)
        if (!valid(r) || !work.ticket.isCurrent()) return
        reader.activeSearchNavigation = reader.activeSearchNavigation?.copy(currentIndex = r.index)
        requestedIndex = null
        failedIndex = null
        reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
    }
    private fun fail(work: Work, error: Throwable) {
        val r = work.value
        if (!valid(r) || !work.ticket.isCurrent()) return
        XposedBridge.log("ReaMicro search navigation failed seq=${work.sequence} type=${(error.cause ?: error).javaClass.simpleName}")
        main.post {
            if (!valid(r) || !work.ticket.isCurrent()) return@post
            requestedIndex = null
            failedIndex = r.index
            reader.pendingSearchReturnCaptures = 0
            reader.clearHostSearchJump()
            reader.clearSearchResultHighlight(r.vm)
            val cause = error.cause ?: error
            reader.activityProvider()?.let {
                reader.ensureSearchNavigationBar(it)
                Toast.makeText(it, cause.message ?: "搜索定位未完成，请重新搜索", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun finishRequest(work: Work) {
        main.post {

            if (!work.ticket.isCurrent()) return@post
            deadline?.let(main::removeCallbacks)
            deadline = null
            if (requestedIndex == null) return@post
            requestedIndex = null
            failedIndex = work.value.index
            reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
        }
    }
    private fun visible(vm: Any, target: ResolvedSearchTarget): Boolean = if (target.flipStyle == 3)
        reader.searchScrollBridge?.visible(vm, target) == true else contains(reader.searchPageBridge?.page(vm), target.start)
    private fun contains(page: Any?, cfi: Any): Boolean {
        val range = page?.let { reader.callNoArg(it, "getRange") } ?: return false
        return XposedHelpers.callMethod(range, "contains", cfi) == true
    }
    private fun awaitPage(work: Work, target: ResolvedSearchTarget): Boolean {
        val r = work.value
        val started = SystemClock.elapsedRealtime()
        var absoluteSent = false
        var scrollSent = false
        while (valid(r) && work.ticket.isCurrent() && SystemClock.elapsedRealtime() - started < 6_000) {
            if (target.flipStyle == 3 && !scrollSent && work.ticket.isCurrent()) {
                val command = onMain { if (valid(r) && work.ticket.isCurrent()) reader.searchScrollBridge?.command(r.vm, target) else null }
                if (command != null) {
                    scrollSent = true
                    reader.searchScrollBridge?.execute(command) { valid(r) && work.ticket.isCurrent() }
                }
            }
            val state = onMain {
                if (!valid(r) || !work.ticket.isCurrent()) return@onMain 2
                if (visible(r.vm, target)) return@onMain 1
                val seed = XposedHelpers.getObjectField(r.vm, "jumpSeed")?.let { reader.callNoArg(it, "getValue") }?.toString()
                if (!absoluteSent && work.ticket.isCurrent() && seed == target.start.toString() &&
                    target.flipStyle != null && target.flipStyle != 3 && SystemClock.elapsedRealtime() - started >= 200) {
                    val pages = XposedHelpers.getObjectField(r.vm, "virtualPages") as? Map<*, *>
                    val key = pages?.entries?.firstOrNull { contains(it.value, target.start) }?.key as? Number
                    val progress = reader.callNoArg(r.vm, "getProgress")
                    if (key != null && progress != null) {
                        absoluteSent = true
                        val sent = XposedHelpers.callMethod(progress, "tryEmit", key.toInt()) == true
                        XposedBridge.log("ReaMicro search absolute page dispatch seq=${work.sequence} target=${key.toInt()} accepted=$sent")
                    }
                }
                0
            }
            if (state != 0) return state == 1
            Thread.sleep(60)
        }
        return false
    }
}
