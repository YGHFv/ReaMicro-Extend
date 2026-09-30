package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File

internal class ReaderSelectionEditDialog(
    val dialog: Dialog, private val editor: EditText, private val save: TextView,
) {
    var saving = false
        private set
    fun setSaving(value: Boolean) {
        saving = value
        editor.isEnabled = !value
        save.isEnabled = !value
        save.text = if (value) "正在保存…" else "保存"
        dialog.setCancelable(!value)
        dialog.setCanceledOnTouchOutside(!value)
    }
    fun finish(success: Boolean) {
        setSaving(false)
        if (success) dialog.dismiss()
    }
}

/** Separate pure helper: CFI itemref index is not a position in the filtered itemRefs list. */
internal fun readerSelectionSpinePosition(indices: List<Int>, cfiItemRef: Int): Int {
    val matches = indices.indices.filter { indices[it] == cfiItemRef }
    require(matches.size == 1) { "选区所属章节索引不唯一或已变化" }
    return matches.single()
}

private data class ReaderSelectionEditContext(
    val viewModel: Any, val epub: Any, val controller: Any, val selectionState: Any?,
    val quote: String, val startRaw: String, val endRaw: String,
    val startHost: Any, val start: SelectionCfiPoint, val end: SelectionCfiPoint,
    val file: File, val spinePosition: Int, val mapper: HostSelectionSourceMapper,
)

internal fun ReaderHook.openAnchoredSelectionEditor() {
    val activity = activityProvider() ?: return
    if (!canEditReaderSelection()) return
    if (!selectionEditSaving.compareAndSet(false, true)) {
        Toast.makeText(activity, "正在准备或保存上一处修改", Toast.LENGTH_SHORT).show()
        return
    }
    val captured = runCatching {
        val controller = currentSelectionControllerRef?.get() ?: error("当前选区已关闭")
        val payload = callNoArg(controller, "selectedPayload") ?: error("没有可编辑的选区")
        // Keep whitespace exactly as supplied by selectedText(); dictionary normalization is unrelated.
        val quote = callString(payload, "getQuote")
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
        // Resolve with exactly the same target-host resolver used by Epub.read.
        val resolver = epub.javaClass.getDeclaredMethod("resolveHtmlPath", String::class.java).apply { isAccessible = true }
        val resolved = resolver.invoke(epub, href) ?: error("选区所属章节文件不存在")
        val root = File(callNoArg(epub, "getDirectory").toString()).canonicalFile
        val file = File(resolved.toString()).canonicalFile
        require(file.isFile && file != root && file.toPath().startsWith(root.toPath())) { "章节文件超出本书目录" }
        ReaderSelectionEditContext(vm, epub, controller, callNoArg(controller, "getSelection"),
            quote, startRaw, endRaw, startHost, start, end, file, position, mapper)
    }.getOrElse {
        selectionEditSaving.set(false)
        XposedBridge.logError("ReaMicro LSP selection anchor capture failed: ${it.message}")
        Toast.makeText(activity, "无法编辑：${it.message}", Toast.LENGTH_LONG).show()
        return
    }
    Thread({
        var posted = false
        try {
            checkSelectionEditSession(captured.viewModel, captured.epub)
            val loaded = EpubTextFiles.load(captured.file)
            val plan = captured.mapper.map(loaded.text, captured.file.path)
                .plan(captured.start, captured.end, captured.quote)
            activity.runOnUiThread {
                selectionEditSaving.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val now = callNoArg(captured.controller, "selectedPayload")
                if (currentViewModelRef?.get() !== captured.viewModel ||
                    (currentEpubStrong ?: currentEpubRef?.get()) !== captured.epub ||
                    currentSelectionControllerRef?.get() !== captured.controller ||
                    callString(now, "getStartCfi") != captured.startRaw || callString(now, "getEndCfi") != captured.endRaw) {
                    Toast.makeText(activity, "选区或阅读会话已变化，请重新选择", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                showSelectionEditDialog(activity, captured.quote) { edited, handle ->
                    if (edited == captured.quote) { handle.finish(true); return@showSelectionEditDialog }
                    if (!selectionEditSaving.compareAndSet(false, true)) {
                        Toast.makeText(activity, "正在保存上一处修改", Toast.LENGTH_SHORT).show()
                        return@showSelectionEditDialog
                    }
                    handle.setSaving(true)
                    Thread({
                        try {
                            val updated = plan.replace(edited)
                            val result = saveAndReloadReaderSelection(
                                captured.viewModel, captured.epub, captured.spinePosition, captured.startHost,
                            ) {
                                checkSelectionEditSession(captured.viewModel, captured.epub)
                                EpubTextFiles.save(captured.file, updated, loaded.snapshot)
                            }
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                handle.finish(true) // File is committed even when pagination failed.
                                val sameSession = currentViewModelRef?.get() === captured.viewModel &&
                                    (currentEpubStrong ?: currentEpubRef?.get()) === captured.epub
                                if (!sameSession) {
                                    Toast.makeText(activity, "修改已保存；阅读会话已切换，未跳转其他书籍", Toast.LENGTH_LONG).show()
                                } else {
                                    if (currentSelectionControllerRef?.get() === captured.controller &&
                                        callNoArg(captured.controller, "getSelection") === captured.selectionState) {
                                        callNoArg(captured.controller, "clearSelection")
                                    }
                                    val page = result.virtualPage
                                    if (page != null) {
                                        val correction = runCatching {
                                            val flow = XposedHelpers.getObjectField(captured.viewModel, "_pagerCorrection")
                                            XposedHelpers.callMethod(flow, "tryEmit", page) as? Boolean == true
                                        }.getOrDefault(false)
                                        val redrawn = forceRefreshReaderWindow("selection-text-edit")
                                        val message = if (correction && redrawn) "已保存并重排阅读内容"
                                            else "修改已保存、章节已重排；页面定位通知未完成，请重新进入本书"
                                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(activity, "修改已保存，但页面重排未完成，请重新进入本书", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            XposedBridge.logError("ReaMicro LSP anchored selection save failed: ${error.stackTraceToString()}")
                            activity.runOnUiThread {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    handle.finish(false)
                                    Toast.makeText(activity, "未保存：${error.message}（编辑内容已保留）", Toast.LENGTH_LONG).show()
                                }
                            }
                        } finally { selectionEditSaving.set(false) }
                    }, "ReaMicroAnchoredSelectionSave").start()
                }
            }
            posted = true
        } catch (error: Throwable) {
            XposedBridge.logError("ReaMicro LSP anchored selection prepare failed: ${error.stackTraceToString()}")
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed)
                    Toast.makeText(activity, "无法定位选区：${error.message}", Toast.LENGTH_LONG).show()
            }
        } finally { if (!posted) selectionEditSaving.set(false) }
    }, "ReaMicroSelectionPrepare").start()
}

internal fun ReaderHook.checkSelectionEditSession(viewModel: Any, epub: Any) {
    check(currentViewModelRef?.get() === viewModel && (currentEpubStrong ?: currentEpubRef?.get()) === epub) {
        "阅读会话已切换，未写入"
    }
}