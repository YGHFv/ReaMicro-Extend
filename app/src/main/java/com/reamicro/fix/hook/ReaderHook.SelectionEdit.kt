package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.core.HostEpubRuntimeBindings
import com.reamicro.fix.core.HostSelectionBindings
import com.reamicro.fix.core.HostReaderWindowBindings

import android.app.Dialog
import android.widget.Toast
import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.xposed.HookRuntime
import java.io.File

internal class ReaderSelectionEditDialog(
    val dialog: Dialog, private val onSavingChanged: (Boolean) -> Unit,
) {
    var saving = false
        private set
    fun setSaving(value: Boolean) {
        saving = value
        onSavingChanged(value)
        dialog.setCancelable(!value)
        dialog.setCanceledOnTouchOutside(!value)
    }
    fun finish(success: Boolean) {
        setSaving(false)
        if (success) dialog.dismiss()
    }
}

internal fun readerSelectionSpinePosition(indices: List<Int>, cfiItemRef: Int): Int {
    val matches = indices.indices.filter { indices[it] == cfiItemRef }
    require(matches.size == 1) { "选区所属章节索引不唯一或已变化" }
    return matches.single()
}

private data class ReaderSelectionEditContext(
    val viewModel: Any, val epub: Any, val controller: Any, val selectionState: Any,
    val quote: String, val startRaw: String, val endRaw: String,
    val start: SelectionCfiPoint, val end: SelectionCfiPoint,
    val root: File, val file: File, val spinePosition: Int, val mapper: HostSelectionSourceMapper,
)

internal fun ReaderHook.openAnchoredSelectionEditor() {
    val activity = activityProvider() ?: return
    if (!canEditReaderSelection()) return
    if (selectionEditSaving.get() || !selectionEditPreparing.compareAndSet(false, true)) {
        Toast.makeText(activity, "正在准备或保存上一处修改", Toast.LENGTH_SHORT).show()
        return
    }
    val captured = runCatching {
        val controller = currentSelectionControllerRef?.get() ?: error("当前选区已关闭")
        val selection = requireNotNull(HostSelectionBindings.current)
        val host = requireNotNull(HostEpubRuntimeBindings.current)
        val window = requireNotNull(HostReaderWindowBindings.current)
        val payload = selection.method("payload").invoke(controller) ?: error("没有可编辑的选区")

        val quote = (selection.field("payload.quote").get(payload) as String)
        val selectionState = selection.method("selection").invoke(controller) ?: error("当前选区已关闭")
        require(quote.isNotBlank()) { "没有可编辑的选区文本" }
        val vm = currentViewModelRef?.get() ?: error("阅读会话已结束")
        val epub = currentEpubStrong ?: currentEpubRef?.get() ?: error("图书已关闭")
        val mapper = HostSelectionSourceMapper()
        val startRaw = (selection.field("payload.startCfi").get(payload) as String)
        val endRaw = (selection.field("payload.endCfi").get(payload) as String)
        val startHost = mapper.hostCfi(startRaw)
        val start = mapper.point(startHost)
        val end = mapper.point(mapper.hostCfi(endRaw))
        require(start.spine == end.spine && start.itemRef == end.itemRef) { "暂不支持跨章节修改，请分章节选择" }
        val refs = host.list("epub.itemRefs", epub)
        val position = readerSelectionSpinePosition(refs.map { host.int("item.index", it) ?: -1 }, start.itemRef)
        val href = host.text("item.href", refs[position])
        require(href.isNotBlank()) { "选区所属章节路径为空" }

        val resolver = host.method("resolvePath")
        val resolved = resolver.invoke(epub, href) ?: error("选区所属章节文件不存在")
        val root = File(host.text("epub.directory", epub)).canonicalFile
        val file = File(resolved.toString()).canonicalFile
        require(file.isFile && file != root && file.toPath().startsWith(root.toPath())) { "章节文件超出本书目录" }
        val pages = window.pages(vm).values.filterNotNull()
        val document = pages.firstNotNullOfOrNull { page ->
            if (window.contains(page, startHost)) host.value("page.document", page) else null
        } ?: error("选区所属章节尚未完成渲染，请重新选择")
        mapper.bindReadingDocument(document, controller)
        ReaderSelectionEditContext(vm, epub, controller, selectionState,
            quote, startRaw, endRaw, start, end, root, file, position, mapper)
    }.getOrElse {
        selectionEditPreparing.set(false)
        HookRuntime.logError("ReaMicro LSP selection anchor capture failed: ${it.message}")
        Toast.makeText(activity, "无法编辑：${it.message}", Toast.LENGTH_LONG).show()
        return
    }
    com.reamicro.fix.reload.ModuleResources.thread({
        var posted = false
        try {
            val checkPreparing = {
                checkSelectionEditSession(captured.viewModel, captured.epub)
                if (readerActivityPaused || !isActivityResumedProvider() || activity.isFinishing || activity.isDestroyed) {
                    throw java.util.concurrent.CancellationException("阅读页已离开，请重新选择")
                }
            }
            checkPreparing()
            val loaded = EpubTextFiles.load(captured.file, checkCancelled = checkPreparing)

            val sourceMap = captured.mapper.map(loaded.text, captured.file.path)
            checkPreparing()
            captured.mapper.verifyReadingSlices(sourceMap)
            val plan = sourceMap.plan(captured.start, captured.end, captured.quote)
            val fonts = ReaderSelectionEditorFonts.load(activity)
            checkPreparing()
            activity.runOnModuleUiThread {
                try {
                    if (activity.isFinishing || activity.isDestroyed || readerActivityPaused ||
                        !isActivityResumedProvider()) return@runOnModuleUiThread
                    if (!isSelectionEditCurrent(captured)) {
                        Toast.makeText(activity, "选区或阅读会话已变化，请重新选择", Toast.LENGTH_LONG).show()
                        return@runOnModuleUiThread
                    }
                    val editor = showSelectionEditDialog(activity, plan.editorText, fonts) { edited, handle ->
                        if (edited == plan.editorText) { handle.finish(true); return@showSelectionEditDialog }
                        if (!selectionEditSaving.compareAndSet(false, true)) {
                            Toast.makeText(activity, "正在保存上一处修改", Toast.LENGTH_SHORT).show()
                            return@showSelectionEditDialog
                        }
                        handle.setSaving(true)
                        val viewportAnchor = runCatching {
                            captureSelectionSaveViewport(captured)
                        }.getOrElse { error ->
                            selectionEditSaving.set(false)
                            handle.finish(false)
                            Toast.makeText(activity, "未保存：${error.message}（编辑内容已保留）", Toast.LENGTH_LONG).show()
                            return@showSelectionEditDialog
                        }
                        cancelSearchPageWork()
                        searchNavigator?.cancel()
                        searchNavigationView?.render(activeSearchNavigation?.currentIndex ?: -1,
                            searchNavigationState?.results?.size ?: 0, false)
                        com.reamicro.fix.reload.ModuleResources.thread({
                            var completionPosted = false
                            try {
                                checkSelectionEditSession(captured.viewModel, captured.epub)
                                val viewportSourceOffset = captured.mapper.viewportSourceOffset(sourceMap, viewportAnchor)
                                val edit = plan.edit(edited)
                                val updated = edit.text
                                val updatedMap = captured.mapper.map(updated, captured.file.path)
                                checkSelectionEditSession(captured.viewModel, captured.epub)
                                sourceMap.verifyEditedText(plan, edited, updatedMap)
                                val restoredAnchor = viewportSourceOffset?.let { oldOffset ->
                                    updatedMap.pointAtSourceOffset(
                                        edit.translateOffset(oldOffset), captured.start.spine, captured.start.itemRef,
                                    )?.let { captured.mapper.cfiForPoint(viewportAnchor, it) }
                                }
                                HookRuntime.log("ReaMicro selection anchor remap sourceMapped=${viewportSourceOffset != null} restored=${restoredAnchor != null} from=${viewportAnchor} to=$restoredAnchor")
                                val result = saveAndReloadReaderSelection(
                                    captured.viewModel, captured.epub, captured.spinePosition, viewportAnchor, restoredAnchor,
                                ) {
                                    checkSelectionEditSession(captured.viewModel, captured.epub)
                                    captured.mapper.checkReadingContext()

                                    val documents = requireNotNull(HostReaderWindowBindings.current).pages(captured.viewModel)
                                        .values.mapNotNull { requireNotNull(HostEpubRuntimeBindings.current).value("page.document", it) }
                                    captured.mapper.checkReadingDocuments(documents)
                                    EpubTextFiles.save(captured.file, updated, loaded.snapshot,
                                        commit = { action ->
                                            // Match online directory publication's book lock. Mapping,
                                            // encoding, and native repagination stay outside this section.
                                            com.reamicro.fix.online.download.OnlineOnDemandMetadataStore
                                                .resourceEdit(captured.root) { action() }
                                        },
                                        checkCancelled = {
                                            checkSelectionEditSession(captured.viewModel, captured.epub)
                                        })
                                    if (currentViewModelRef?.get() === captured.viewModel &&
                                        (currentEpubStrong ?: currentEpubRef?.get()) === captured.epub) {
                                        searchDocumentCache.clear()
                                        searchIndexState = null
                                        lastSearchState = null
                                    }
                                }
                                activity.runOnModuleUiThread {
                                    try {
                                        if (activity.isFinishing || activity.isDestroyed) return@runOnModuleUiThread
                                        handle.finish(true)
                                        val sameSession = currentViewModelRef?.get() === captured.viewModel &&
                                            (currentEpubStrong ?: currentEpubRef?.get()) === captured.epub
                                        if (sameSession && (activeSearchNavigation != null || searchNavigationState != null)) {
                                            clearStaleSearchNavigation()
                                        }
                                        if (!sameSession) {
                                            Toast.makeText(activity, "修改已保存；阅读会话已切换，未跳转其他书籍", Toast.LENGTH_LONG).show()
                                        } else {

                                            val page = result.virtualPage
                                            if (page != null) {
                                                val correction = armSelectionPagerTarget(captured.viewModel, page)
                                                if (!correction) HookRuntime.log("ReaMicro selection pager awaiting foreground or native readback")

                                                val message = "已保存并重排阅读内容"
                                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(activity, "修改已保存；${result.refreshError ?: "页面重排未完成，请重新进入本书"}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } finally { selectionEditSaving.set(false) }
                                }
                                completionPosted = true
                            } catch (error: Throwable) {
                                HookRuntime.logError("ReaMicro LSP anchored selection save failed: ${error.stackTraceToString()}")
                                activity.runOnModuleUiThread {
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        handle.finish(false)
                                        val writeUnconfirmed = error is EpubWriteNotConfirmed
                                        if (writeUnconfirmed && currentViewModelRef?.get() === captured.viewModel &&
                                            (currentEpubStrong ?: currentEpubRef?.get()) === captured.epub) {
                                            searchDocumentCache.clear()
                                            searchIndexState = null
                                            lastSearchState = null
                                            if (activeSearchNavigation != null || searchNavigationState != null) {
                                                clearStaleSearchNavigation()
                                            }
                                        }
                                        val message = if (writeUnconfirmed) {
                                            "${error.message ?: "文件写入已发生，但结果尚未确认，请重新进入本书检查"}（编辑内容已保留）"
                                        } else "未保存：${error.message}（编辑内容已保留）"
                                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } finally { if (!completionPosted) selectionEditSaving.set(false) }
                        }, "ReaMicroAnchoredSelectionSave").start()
                    }
                    clearSelectionAfterEditorShown(captured, editor)
                } catch (error: Throwable) {
                    HookRuntime.logError("ReaMicro selection editor presentation failed: ${error.stackTraceToString()}")
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, "无法打开编辑框：${error.message}", Toast.LENGTH_LONG).show()
                    }
                } finally { selectionEditPreparing.set(false) }
            }
            posted = true
        } catch (error: Throwable) {
            if (error is java.util.concurrent.CancellationException) {
                HookRuntime.log("ReaMicro selection preparation cancelled: ${error.message}")
            } else HookRuntime.logError("ReaMicro LSP anchored selection prepare failed: ${error.stackTraceToString()}")
            activity.runOnModuleUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && !readerActivityPaused &&
                    isActivityResumedProvider() && currentViewModelRef?.get() === captured.viewModel)
                    Toast.makeText(activity, "无法定位选区：${error.message}", Toast.LENGTH_LONG).show()
            }
        } finally { if (!posted) selectionEditPreparing.set(false) }
    }, "ReaMicroSelectionPrepare").start()
}

private fun ReaderHook.isSelectionEditCurrent(context: ReaderSelectionEditContext): Boolean {
    if (currentViewModelRef?.get() !== context.viewModel ||
        (currentEpubStrong ?: currentEpubRef?.get()) !== context.epub ||
        currentSelectionControllerRef?.get() !== context.controller) return false
    val selection = HostSelectionBindings.current ?: return false
    if (selection.method("selection").invoke(context.controller) !== context.selectionState) return false
    val payload = selection.method("payload").invoke(context.controller) ?: return false
    return (selection.field("payload.startCfi").get(payload) as String) == context.startRaw &&
        (selection.field("payload.endCfi").get(payload) as String) == context.endRaw &&
        (selection.field("payload.quote").get(payload) as String) == context.quote
}

private fun ReaderHook.clearSelectionAfterEditorShown(
    context: ReaderSelectionEditContext, editor: ReaderSelectionEditDialog,
) {
    if (!editor.dialog.isShowing || !isSelectionEditCurrent(context)) return
    runCatching { requireNotNull(com.reamicro.fix.core.HostSelectionBindings.current).method("clear").invoke(context.controller) }
        .onFailure { HookRuntime.logError("ReaMicro selection close after editor failed: ${it.message}") }
}

internal fun ReaderHook.checkSelectionEditSession(viewModel: Any, epub: Any) {
    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("选区编辑任务已取消")
    check(currentViewModelRef?.get() === viewModel && (currentEpubStrong ?: currentEpubRef?.get()) === epub) {
        "阅读会话已切换"
    }
}

private fun ReaderHook.captureSelectionSaveViewport(context: ReaderSelectionEditContext): Any {
    checkSelectionEditSession(context.viewModel, context.epub)
    val start = context.mapper.hostCfi(context.startRaw)
    val end = context.mapper.hostCfi(context.endRaw)
    val window = requireNotNull(HostReaderWindowBindings.current)
    val host = requireNotNull(HostEpubRuntimeBindings.current)
    val pages = window.pages(context.viewModel).values.filterNotNull()
    val visible = currentReaderPage()?.takeIf { page ->
        pages.any { it === page } && window.field("page.range").get(page)?.let {
            context.mapper.rangeOverlapsSelection(it, start, end)
        } == true
    }
    val target = visible ?: pages.firstOrNull { window.contains(it, start) }
        ?: error("当前选区页面已变化，请刷新后重新选择")
    context.mapper.checkReadingContext(host.value("page.document", target) ?: error("阅读文档已失效"))
    val anchor = window.start(target)
    val point = context.mapper.anchorPoint(anchor)
    require(point.spine == context.start.spine && point.itemRef == context.start.itemRef) {
        "当前页与选区所属章节不一致"
    }
    HookRuntime.log("ReaMicro selection save viewport source=${if (visible != null) "visible" else "selection"} anchor=$anchor")
    return anchor
}
