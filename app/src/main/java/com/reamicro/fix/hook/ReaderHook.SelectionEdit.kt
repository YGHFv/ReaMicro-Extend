package com.reamicro.fix.hook

import android.app.Dialog
import android.widget.Toast
import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
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
    val file: File, val spinePosition: Int, val mapper: HostSelectionSourceMapper,
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
        val payload = callNoArg(controller, "selectedPayload") ?: error("没有可编辑的选区")

        val quote = callString(payload, "getQuote")
        val selectionState = callNoArg(controller, "getSelection") ?: error("当前选区已关闭")
        require(quote.isNotBlank()) { "没有可编辑的选区文本" }
        val vm = currentViewModelRef?.get() ?: error("阅读会话已结束")
        val epub = currentEpubStrong ?: currentEpubRef?.get() ?: error("图书已关闭")
        val mapper = HostSelectionSourceMapper(classLoader)
        val startRaw = callString(payload, "getStartCfi")
        val endRaw = callString(payload, "getEndCfi")
        val startHost = mapper.hostCfi(startRaw)
        val start = mapper.point(startHost)
        val end = mapper.point(mapper.hostCfi(endRaw))
        require(start.spine == end.spine && start.itemRef == end.itemRef) { "暂不支持跨章节修改，请分章节选择" }
        val refs = (callNoArg(epub, "getItemRefs") as? List<*>).orEmpty().filterNotNull()
        val position = readerSelectionSpinePosition(refs.map { (callNoArg(it, "getIndex") as? Number)?.toInt() ?: -1 }, start.itemRef)
        val href = callString(refs[position], "getHref")
        require(href.isNotBlank()) { "选区所属章节路径为空" }

        val resolver = epub.javaClass.getDeclaredMethod("resolveHtmlPath", String::class.java).apply { isAccessible = true }
        val resolved = resolver.invoke(epub, href) ?: error("选区所属章节文件不存在")
        val root = File(callNoArg(epub, "getDirectory").toString()).canonicalFile
        val file = File(resolved.toString()).canonicalFile
        require(file.isFile && file != root && file.toPath().startsWith(root.toPath())) { "章节文件超出本书目录" }
        val pages = (XposedHelpers.getObjectField(vm, "virtualPages") as? Map<*, *>)
            ?.values?.filterNotNull().orEmpty()
        val document = pages.firstNotNullOfOrNull { page ->
            val range = callNoArg(page, "getRange")
            if (range != null && XposedHelpers.callMethod(range, "contains", startHost) == true)
                callNoArg(page, "getDocument") else null
        } ?: error("选区所属章节尚未完成渲染，请重新选择")
        mapper.bindReadingDocument(document, controller)
        ReaderSelectionEditContext(vm, epub, controller, selectionState,
            quote, startRaw, endRaw, start, end, file, position, mapper)
    }.getOrElse {
        selectionEditPreparing.set(false)
        XposedBridge.logError("ReaMicro LSP selection anchor capture failed: ${it.message}")
        Toast.makeText(activity, "无法编辑：${it.message}", Toast.LENGTH_LONG).show()
        return
    }
    Thread({
        var posted = false
        try {
            checkSelectionEditSession(captured.viewModel, captured.epub)
            val loaded = EpubTextFiles.load(captured.file)

            val sourceMap = captured.mapper.map(loaded.text, captured.file.path)
            captured.mapper.verifyReadingSlices(sourceMap)
            val plan = sourceMap.plan(captured.start, captured.end, captured.quote)
            activity.runOnUiThread {
                try {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (!isSelectionEditCurrent(captured)) {
                        Toast.makeText(activity, "选区或阅读会话已变化，请重新选择", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val editor = showSelectionEditDialog(activity, plan.editorText) { edited, handle ->
                        if (edited == plan.editorText) { handle.finish(true); return@showSelectionEditDialog }
                        if (!selectionEditSaving.compareAndSet(false, true)) {
                            Toast.makeText(activity, "正在保存上一处修改", Toast.LENGTH_SHORT).show()
                            return@showSelectionEditDialog
                        }
                        searchNavigator?.cancel()
                        searchNavigationView?.render(activeSearchNavigation?.currentIndex ?: -1,
                            searchNavigationState?.results?.size ?: 0, false)
                        handle.setSaving(true)
                        val viewportAnchor = runCatching {
                            captureSelectionSaveViewport(captured)
                        }.getOrElse { error ->
                            selectionEditSaving.set(false)
                            handle.finish(false)
                            Toast.makeText(activity, "未保存：${error.message}（编辑内容已保留）", Toast.LENGTH_LONG).show()
                            return@showSelectionEditDialog
                        }
                        Thread({
                            var completionPosted = false
                            try {
                                checkSelectionEditSession(captured.viewModel, captured.epub)
                                val viewportSourceOffset = captured.mapper.viewportSourceOffset(sourceMap, viewportAnchor)
                                val edit = plan.edit(edited)
                                val updated = edit.text
                                val updatedMap = captured.mapper.map(updated, captured.file.path)
                                sourceMap.verifyEditedText(plan, edited, updatedMap)
                                val restoredAnchor = viewportSourceOffset?.let { oldOffset ->
                                    updatedMap.pointAtSourceOffset(
                                        edit.translateOffset(oldOffset), captured.start.spine, captured.start.itemRef,
                                    )?.let { captured.mapper.cfiForPoint(viewportAnchor, it) }
                                }
                                XposedBridge.log("ReaMicro selection anchor remap sourceMapped=${viewportSourceOffset != null} restored=${restoredAnchor != null} from=${viewportAnchor} to=$restoredAnchor")
                                val result = saveAndReloadReaderSelection(
                                    captured.viewModel, captured.epub, captured.spinePosition, viewportAnchor, restoredAnchor,
                                ) {
                                    checkSelectionEditSession(captured.viewModel, captured.epub)
                                    captured.mapper.checkReadingContext()

                                    val documents = (XposedHelpers.getObjectField(captured.viewModel, "virtualPages") as? Map<*, *>)
                                        ?.values.orEmpty().mapNotNull { it?.let { page -> callNoArg(page, "getDocument") } }
                                    captured.mapper.checkReadingDocuments(documents)
                                    EpubTextFiles.save(captured.file, updated, loaded.snapshot)
                                    searchIndexState = null
                                    lastSearchState = null
                                }
                                activity.runOnUiThread {
                                    if (activeSearchNavigation != null || searchNavigationState != null) clearStaleSearchNavigation()
                                    try {
                                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                        handle.finish(true)
                                        val sameSession = currentViewModelRef?.get() === captured.viewModel &&
                                            (currentEpubStrong ?: currentEpubRef?.get()) === captured.epub
                                        if (!sameSession) {
                                            Toast.makeText(activity, "修改已保存；阅读会话已切换，未跳转其他书籍", Toast.LENGTH_LONG).show()
                                        } else {

                                            val page = result.virtualPage
                                            if (page != null) {
                                                val correction = runCatching {

                                                    val initial = XposedHelpers.getObjectField(captured.viewModel, "_initialPagerPage")
                                                    XposedHelpers.callMethod(initial, "setValue", page)
                                                    armSelectionPagerTarget(captured.viewModel, page)
                                                    val flow = XposedHelpers.getObjectField(captured.viewModel, "_pagerCorrection")
                                                    XposedHelpers.callMethod(flow, "tryEmit", page) as? Boolean == true
                                                }.onFailure {
                                                    XposedBridge.logError("ReaMicro selection pager dispatch failed: ${it.message}")
                                                }.getOrDefault(false)
                                                if (!correction) XposedBridge.log("ReaMicro selection pager notification not delivered; awaiting native readback")

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
                                XposedBridge.logError("ReaMicro LSP anchored selection save failed: ${error.stackTraceToString()}")
                                activity.runOnUiThread {
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        handle.finish(false)
                                        Toast.makeText(activity, "未保存：${error.message}（编辑内容已保留）", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } finally { if (!completionPosted) selectionEditSaving.set(false) }
                        }, "ReaMicroAnchoredSelectionSave").start()
                    }
                    clearSelectionAfterEditorShown(captured, editor)
                } catch (error: Throwable) {
                    XposedBridge.logError("ReaMicro selection editor presentation failed: ${error.stackTraceToString()}")
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, "无法打开编辑框：${error.message}", Toast.LENGTH_LONG).show()
                    }
                } finally { selectionEditPreparing.set(false) }
            }
            posted = true
        } catch (error: Throwable) {
            XposedBridge.logError("ReaMicro LSP anchored selection prepare failed: ${error.stackTraceToString()}")
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed)
                    Toast.makeText(activity, "无法定位选区：${error.message}", Toast.LENGTH_LONG).show()
            }
        } finally { if (!posted) selectionEditPreparing.set(false) }
    }, "ReaMicroSelectionPrepare").start()
}

private fun ReaderHook.isSelectionEditCurrent(context: ReaderSelectionEditContext): Boolean {
    if (currentViewModelRef?.get() !== context.viewModel ||
        (currentEpubStrong ?: currentEpubRef?.get()) !== context.epub ||
        currentSelectionControllerRef?.get() !== context.controller) return false
    if (callNoArg(context.controller, "getSelection") !== context.selectionState) return false
    val payload = callNoArg(context.controller, "selectedPayload") ?: return false
    return callString(payload, "getStartCfi") == context.startRaw &&
        callString(payload, "getEndCfi") == context.endRaw &&
        callString(payload, "getQuote") == context.quote
}

private fun ReaderHook.clearSelectionAfterEditorShown(
    context: ReaderSelectionEditContext, editor: ReaderSelectionEditDialog,
) {
    if (!editor.dialog.isShowing || !isSelectionEditCurrent(context)) return
    runCatching { XposedHelpers.callMethod(context.controller, "clearSelection") }
        .onFailure { XposedBridge.logError("ReaMicro selection close after editor failed: ${it.message}") }
}

internal fun ReaderHook.checkSelectionEditSession(viewModel: Any, epub: Any) {
    check(currentViewModelRef?.get() === viewModel && (currentEpubStrong ?: currentEpubRef?.get()) === epub) {
        "阅读会话已切换，未写入"
    }
}

private fun ReaderHook.captureSelectionSaveViewport(context: ReaderSelectionEditContext): Any {
    checkSelectionEditSession(context.viewModel, context.epub)
    val start = context.mapper.hostCfi(context.startRaw)
    val end = context.mapper.hostCfi(context.endRaw)
    val pages = (XposedHelpers.getObjectField(context.viewModel, "virtualPages") as? Map<*, *>)
        ?.values?.filterNotNull() ?: error("当前页仍在加载，请稍后保存")
    val visible = currentReaderPage()?.takeIf { page ->
        pages.any { it === page } && callNoArg(page, "getRange")?.let {
            context.mapper.rangeOverlapsSelection(it, start, end)
        } == true
    }
    val target = visible ?: pages.firstOrNull { page ->
        callNoArg(page, "getRange")?.let { XposedHelpers.callMethod(it, "contains", start) == true } == true
    } ?: error("当前选区页面已变化，请刷新后重新选择")
    context.mapper.checkReadingContext(callNoArg(target, "getDocument") ?: error("阅读文档已失效"))
    val anchor = callNoArg(target, "getStart") ?: error("当前页缺少起始位置")
    val point = context.mapper.anchorPoint(anchor)
    require(point.spine == context.start.spine && point.itemRef == context.start.itemRef) {
        "当前页与选区所属章节不一致"
    }
    XposedBridge.log("ReaMicro selection save viewport source=${if (visible != null) "visible" else "selection"} anchor=$anchor")
    return anchor
}
