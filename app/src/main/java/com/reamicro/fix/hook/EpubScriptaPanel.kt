package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.widget.Toast
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.epub.editor.CommittedFileFeedback
import com.reamicro.fix.epub.editor.EpubMarkupHighlighter
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.EpubTextSnapshot
import com.reamicro.fix.epub.editor.canEditEpubSource
import com.reamicro.fix.epub.editor.EpubNavigation
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.ModuleUiRuntime
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.CodeEditorController
import top.yukonga.scripta.editor.rememberCodeEditorController
import top.yukonga.scripta.editor.text.TextPosition

internal class EpubScriptaPanel(
    private val activity: Activity,
    private val root: File,
    private val initialPath: String,
    private val bookTitle: String,
    private val openedFilePaths: () -> List<String>,
    private val openedFilesContent: @Composable ((String) -> Unit, (String) -> Unit) -> Unit,
    private val onFileClosed: (String) -> Unit,
    private val onFileOpened: (String) -> Unit,
    private val onShowStructureHome: () -> Unit,
    private val filePaths: () -> List<String>,
    private val fileSearchLabels: () -> Map<String, List<String>>,
    private val onOpenStructureFile: (String) -> Unit,
    private val onPageVisible: (Boolean) -> Unit,
    private val onSaved: suspend (Set<String>) -> Unit,
    private val onClosed: () -> Unit,
    private val startWithSearch: Boolean = false,
    private val uiFontFileProvider: () -> File? = { null },
    private val onFileSearchOpened: () -> Unit = {},
    private val commit: (() -> Unit) -> Unit = { it() },
    private val checkCurrent: () -> Unit = {},
) {
    private val owner = ModuleComposeOwner(activity) { dismissForHostShutdown() }
    private val darkHint = mutableStateOf(HostEmbeddedStyle.isDark(activity))
    private var requestClose: (() -> Unit)? = null
    private var composeView: ComposeView? = null
    @Volatile private var closed = false
    private val returnRoute = StructureEditorReturnRoute()
    @Volatile private var hostShuttingDown = false
    private var appliedWindowPalette: Pair<Int, Boolean>? = null
    private val dialog by lazy { object : Dialog(
        com.reamicro.fix.core.InjectedModuleContext.create(activity),
        com.reamicro.fix.R.style.EpubFullScreenDialog,
    ) {
        @Deprecated("Back must confirm unsaved edits")
        override fun onBackPressed() { requestClose?.invoke() ?: dismiss() }
    } }
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            darkHint.value = HostEmbeddedStyle.isDark(activity)
            configureWindow()
        }
        override fun onLowMemory() = Unit
    }
    fun show() {
        requireEditorCurrent()
        require(canEditEpubSource(initialPath)) { "该文件不是可编辑的文本资源" }
        check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭" }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        val context = com.reamicro.fix.core.InjectedModuleContext.create(activity)
        owner.start()
        try {
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                owner.attach(this)
                setContent { EditorContent(initialPath) }
            }
            composeView = view
            dialog.window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            dialog.setContentView(view)
            dialog.window?.decorView?.findViewById<android.view.View>(android.R.id.content)?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            dialog.setOnDismissListener { cleanup() }
            activity.registerComponentCallbacks(configCallbacks)
            dialog.show()
            configureWindow()
            if (Build.VERSION.SDK_INT >= 33) {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { requestClose?.invoke() ?: dialog.dismiss() }
            }
        } catch (error: Throwable) {
            hostShuttingDown = true
            returnRoute.cancel()
            runCatching { dialog.dismiss() }.onFailure {
                ModuleUiRuntime.recordFailure("编辑器窗口未能关闭")
                error.addSuppressed(it)
            }
            runCatching { cleanup() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun dismissForHostShutdown() {
        if (closed) return
        hostShuttingDown = true
        returnRoute.cancel()
        try { dialog.dismiss() } finally { cleanup() }
    }

    private fun cleanup() {
        if (closed) return
        prepareReturnDestination()
        closed = true
        requestClose = null
        runCatching { activity.unregisterComponentCallbacks(configCallbacks) }.onFailure {
            ModuleUiRuntime.recordFailure("编辑器配置监听未能注销")
            HookRuntime.logError("ReaMicro editor configuration cleanup failed", it)
        }
        owner.close()
        composeView = null
        onPageVisible(false)
        onClosed()
    }

    private fun prepareReturnDestination() {
        val target = returnRoute.takeExitDestination() ?: return
        if (hostShuttingDown || activity.isFinishing || activity.isDestroyed ||
            runCatching { requireEditorCurrent() }.isFailure) return
        runCatching {
            when (target) {
                StructureEditorDestination.Context -> Unit
                StructureEditorDestination.Home -> onShowStructureHome()
                is StructureEditorDestination.File -> onOpenStructureFile(target.path)
            }
        }.onFailure { HookRuntime.logError("ReaMicro editor return page unavailable", it) }
    }

    private fun requireEditorCurrent() {
        check(!closed && !hostShuttingDown && !activity.isFinishing && !activity.isDestroyed) { "编辑器已关闭" }
        checkCurrent()
    }

    private fun canStartEditorAction(): Boolean = runCatching { requireEditorCurrent() }
        .onFailure { Toast.makeText(activity, it.message ?: "请重新打开编辑器", Toast.LENGTH_LONG).show() }
        .isSuccess

    private fun saveTextSource(
        file: File, text: String, snapshot: EpubTextSnapshot, checkCancelled: () -> Unit,
    ): EpubTextSnapshot = EpubTextFiles.save(
        file, text, snapshot,
        commit = { action ->
            commit {
                checkCancelled()
                requireEditorCurrent()
                action()
            }
        },
        checkCancelled = { checkCancelled(); requireEditorCurrent() },
    )

    private fun configureWindow() {
        val native = HostThemeColors.snapshot(activity, darkHint.value)
        configureEpubEdgeToEdgeWindow(dialog.window, android.graphics.Color.TRANSPARENT, native.dark)
        appliedWindowPalette = native.contentArgb to native.dark
    }

    private fun applyWindowPalette(background: Int, dark: Boolean) {
        if (!dialog.isShowing || appliedWindowPalette == (background to dark)) return
        appliedWindowPalette = background to dark
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun EditorContent(initialPath: String) {

        val native = HostThemeColors.snapshot(activity, darkHint.value)
        val p = remember(native) { HostEmbeddedStyle.Palette(native) }
        val hostFonts = remember { HostEmbeddedStyle.fonts(activity) }
        val typography = rememberStructureContentTypography(hostFonts, uiFontFileProvider)
        LaunchedEffect(native.contentArgb, native.dark) { applyWindowPalette(native.contentArgb, native.dark) }
        val controller = rememberCodeEditorController()
        val coroutineScope = rememberCoroutineScope()
        val highlighter = remember { EpubMarkupHighlighter() }
        var currentPath by remember { mutableStateOf(initialPath) }
        var fileSearchVisible by remember { mutableStateOf(false) }
        val editorVisibility = remember { MutableTransitionState(false) }
        var dismissRequested by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            onPageVisible(true)
            editorVisibility.targetState = true
        }
        LaunchedEffect(editorVisibility.isIdle, editorVisibility.currentState, dismissRequested) {
            if (dismissRequested && editorVisibility.isIdle && !editorVisibility.currentState) dialog.dismiss()
        }
        fun dismissWithTransition() {
            if (dismissRequested) return
            prepareReturnDestination()
            dismissRequested = true
            onPageVisible(false)
            editorVisibility.targetState = false
        }
        var snapshot by remember { mutableStateOf<EpubTextSnapshot?>(null) }
        var loading by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
        var confirmClose by remember { mutableStateOf(false) }
        var confirmTarget by remember { mutableStateOf<String?>(null) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var softWrap by remember { mutableStateOf(true) }
        var retry by remember { mutableIntStateOf(0) }
        var searchVisible by remember { mutableStateOf(startWithSearch) }
        var searchQuery by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var searchScope by remember { mutableStateOf(EpubSearchScope.CURRENT) }
        var regex by remember { mutableStateOf(false) }
        var textOnly by remember { mutableStateOf(false) }
        var searchBusy by remember { mutableStateOf(false) }
        var searchMessage by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf(emptyList<EpubSearchDocument>()) }
        var searchFileIndex by remember { mutableIntStateOf(-1) }
        var searchMatchIndex by remember { mutableIntStateOf(-1) }
        var searchGeneration by remember { mutableIntStateOf(0) }
        var pendingSelection by remember { mutableStateOf<PendingSelection?>(null) }

        LaunchedEffect(currentPath, retry) {
            val pathAtLoad = currentPath
            loading = true
            failure = null
            snapshot = null
            controller.setDocument("")
            try {
                val loaded = withContext(ModuleUiRuntime.io) {
                    val job = currentCoroutineContext()
                    requireEditorCurrent()
                    EpubTextFiles.load(EpubTextFiles.resolve(root, pathAtLoad)) {
                        job.ensureActive(); requireEditorCurrent()
                    }.also { com.reamicro.fix.epub.editor.EpubSourceLayoutSafety.requireEditable(it.text) }
                }
                currentCoroutineContext().ensureActive()
                requireEditorCurrent()
                if (currentPath != pathAtLoad || dismissRequested) return@LaunchedEffect
                controller.setDocument(loaded.text)
                snapshot = loaded.snapshot
                onFileOpened(pathAtLoad)
                pendingSelection?.takeIf { it.path == pathAtLoad }?.let { pending ->
                    selectMatch(controller, loaded.text, pending.start, pending.end)
                    pendingSelection = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentPath == pathAtLoad) {
                    failure = error.message ?: "读取失败"
                    pendingSelection = null
                }
            } finally {
                if (currentPath == pathAtLoad) loading = false
            }
        }

        fun cancelNavigation() {
            confirmClose = false
            confirmTarget = null
            pendingSelection = null
            returnRoute.cancel()
        }

        fun openTarget(path: String) {
            if (canEditEpubSource(path)) {
                returnRoute.cancel()
                if (path != currentPath) {
                    loading = true
                    snapshot = null
                    currentPath = path
                }
            } else {
                returnRoute.file(path)
                dismissWithTransition()
            }
        }

        fun finishNavigation(target: String?) {
            returnRoute.takeClosingFile()?.let(onFileClosed)
            if (target == null) dismissWithTransition() else openTarget(target)
        }

        fun close() {
            if (saving || searchBusy || dismissRequested) return
            when {
                drawer.isOpen -> coroutineScope.launch { drawer.close() }
                fileSearchVisible -> fileSearchVisible = false
                confirmClose -> cancelNavigation()
                searchVisible -> searchVisible = false
                controller.isGotoLineVisible -> controller.closeGotoLine()
                controller.isModified -> {
                    confirmTarget = null
                    confirmClose = true
                }
                else -> dismissWithTransition()
            }
        }

        fun navigateHome() {
            if (saving || searchBusy || dismissRequested) return
            returnRoute.home()
            confirmTarget = null
            if (controller.isModified) confirmClose = true else dismissWithTransition()
        }

        SideEffect { requestClose = { close() } }

        fun save(closeAfter: Boolean = false) {
            val original = snapshot ?: return
            if (saving || searchBusy || loading || dismissRequested || failure != null) return
            if (!canStartEditorAction()) return
            if (controller.isComposing) {
                Toast.makeText(activity, "请先确认输入法候选词，再保存", Toast.LENGTH_SHORT).show()
                return
            }
            val pathAtSave = currentPath
            val version = controller.documentVersion
            val text = controller.getText(controller.lineEnding)
            val switchTo = confirmTarget
            saving = true
            confirmClose = false
            coroutineScope.launch {
                var committed = false
                try {
                    val updated = withContext(ModuleUiRuntime.io) {
                        val job = currentCoroutineContext()
                        EpubNavigation.validateSource(pathAtSave, text)
                        saveTextSource(EpubTextFiles.resolve(root, pathAtSave), text, original) { job.ensureActive() }
                    }
                    committed = true
                    requireEditorCurrent()
                    if (currentPath == pathAtSave) {
                        snapshot = updated
                        controller.markSaved(version)
                    }
                    val syncError = runCatching { requireEditorCurrent(); onSaved(setOf(pathAtSave)) }.exceptionOrNull()
                    if (syncError is CancellationException) throw syncError
                    val feedback = CommittedFileFeedback(
                        if (syncError == null) "已保存并同步书架" else "文件已保存")
                    if (syncError != null) feedback.addWarning("书架同步失败")
                    Toast.makeText(activity, feedback.displayMessage, Toast.LENGTH_LONG).show()

                    if (switchTo != null) {
                        confirmTarget = null
                        finishNavigation(switchTo)
                    } else if (closeAfter && !controller.isModified) {
                        finishNavigation(null)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {

                    returnRoute.cancel()
                    confirmTarget = null
                    pendingSelection = null
                    val message = when {
                        error is com.reamicro.fix.epub.editor.EpubWriteNotConfirmed -> error.message.orEmpty()
                        committed -> "文件已保存；后续处理未完成：${error.message.orEmpty()}"
                        else -> "保存失败：${error.message.orEmpty()}"
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                } finally {
                    saving = false
                }
            }
        }

        fun switchTo(path: String) {
            if (path == currentPath || saving || loading || confirmClose || dismissRequested) return
            if (controller.isModified) {
                confirmTarget = path
                confirmClose = true
            } else {
                openTarget(path)
            }
        }

        fun closeOpenedFile(path: String) {
            if (saving || searchBusy || loading || confirmClose || dismissRequested) return
            if (path != currentPath) {
                onFileClosed(path)
                return
            }
            coroutineScope.launch { drawer.close() }
            returnRoute.closeFile(path)
            confirmTarget = openedFilePaths().lastOrNull { it != path }
            if (controller.isModified) confirmClose = true else {
                val target = confirmTarget
                confirmTarget = null
                finishNavigation(target)
            }
        }

        fun searchNow(selectFirst: Boolean) {
            if (searchBusy || loading || saving || confirmClose || dismissRequested) return
            if (!canStartEditorAction()) return
            val query = searchQuery
            if (query.isBlank()) {
                searchResults = emptyList()
                searchFileIndex = -1
                searchMatchIndex = -1
                searchMessage = "请输入查找内容"
                return
            }
            val generation = searchGeneration + 1
            searchGeneration = generation
            val activePath = currentPath
            val activeText = controller.getText()
            searchBusy = true
            searchMessage = "搜索中…"
            coroutineScope.launch {
                try {
                    val documents = withContext(ModuleUiRuntime.io) {
                        currentCoroutineContext().ensureActive()
                        requireEditorCurrent()
                        EpubScriptaSearch.loadDocuments(
                            root = root,
                            currentPath = activePath,
                            currentText = activeText,
                            scope = searchScope,
                            query = query,
                            regex = regex,
                            textOnly = textOnly,
                        )
                    }
                    requireEditorCurrent()
                    if (generation != searchGeneration) return@launch
                    searchResults = documents
                    searchFileIndex = if (selectFirst && documents.isNotEmpty()) 0 else -1
                    searchMatchIndex = if (selectFirst && documents.isNotEmpty()) 0 else -1
                    searchMessage = searchStatus(documents, searchFileIndex, searchMatchIndex)
                    if (selectFirst && documents.isNotEmpty()) {
                        val first = documents.first()
                        val match = first.matches.firstOrNull()
                        if (match != null) {
                            if (first.path == currentPath) {
                                selectMatch(controller, first.text, match.start, match.end)
                            } else {
                                pendingSelection = PendingSelection(first.path, match.start, match.end)
                                switchTo(first.path)
                            }
                        }
                    }

                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == searchGeneration) {
                        searchResults = emptyList()
                        searchFileIndex = -1
                        searchMatchIndex = -1
                        searchMessage = error.message ?: "搜索失败"
                    }
                } finally {
                    if (generation == searchGeneration) searchBusy = false
                }
            }
        }

        fun revealDocument(document: EpubSearchDocument, matchIndex: Int) {
            if (loading || saving || confirmClose || dismissRequested) return
            val match = document.matches.getOrNull(matchIndex) ?: return
            searchFileIndex = searchResults.indexOfFirst { it.path == document.path }.coerceAtLeast(0)
            searchMatchIndex = matchIndex
            if (document.path == currentPath) {
                selectMatch(controller, document.text, match.start, match.end)
            } else {
                pendingSelection = PendingSelection(document.path, match.start, match.end)
                switchTo(document.path)
            }
            searchMessage = searchStatus(searchResults, searchFileIndex, searchMatchIndex)
        }

        fun moveMatch(delta: Int) {
            if (loading || saving || searchBusy || confirmClose || dismissRequested) return
            if (searchResults.isEmpty()) {
                searchNow(true)
                return
            }
            val total = searchResults.sumOf { it.matches.size }
            if (total == 0) return
            var ordinal = 0
            if (searchFileIndex >= 0) {
                ordinal = searchResults.take(searchFileIndex).sumOf { it.matches.size } + searchMatchIndex.coerceAtLeast(0)
            }
            val target = (ordinal + delta + total) % total
            var remaining = target
            searchResults.forEachIndexed { index, document ->
                if (remaining < document.matches.size) {
                    revealDocument(document, remaining)
                    return
                }
                remaining -= document.matches.size
            }
        }

        fun replaceCurrent() {
            if (loading || saving || searchBusy || confirmClose || dismissRequested) return
            if (!canStartEditorAction()) return
            if (searchResults.isEmpty() || searchFileIndex < 0) {
                searchNow(true)
                return
            }
            val document = searchResults.getOrNull(searchFileIndex) ?: return
            val match = document.matches.getOrNull(searchMatchIndex) ?: return
            if (document.path != currentPath) {
                revealDocument(document, searchMatchIndex)
                return
            }
            controller.replaceRange(
                positionAt(controller.getText(), match.start),
                positionAt(controller.getText(), match.end),
                replacement,
            )
            searchResults = emptyList()
            searchFileIndex = -1
            searchMatchIndex = -1
            searchMessage = "已替换 1 处，请重新搜索"
        }

        fun replaceAll() {
            if (searchBusy || loading || saving || confirmClose || dismissRequested) return
            if (!canStartEditorAction()) return
            if (searchQuery.isBlank()) {
                searchMessage = "请输入查找内容"
                return
            }
            if (textOnly) {
                searchMessage = "仅文本模式只支持查找定位"
                return
            }
            val activePath = currentPath
            if (controller.isComposing) {
                searchMessage = "请先确认输入法候选词，再替换"
                return
            }
            val activeText = controller.getText()
            val activeVersion = controller.documentVersion
            val query = searchQuery
            val replacementText = replacement
            val scope = searchScope
            val useRegex = regex
            searchBusy = true
            searchMessage = "替换中…"
            coroutineScope.launch {
                val writtenPaths = linkedSetOf<String>()
                try {
                    val documents = withContext(ModuleUiRuntime.io) {
                        currentCoroutineContext().ensureActive()
                        requireEditorCurrent()
                        EpubScriptaSearch.loadDocuments(root, activePath, activeText, scope, query, useRegex, false)
                    }
                    requireEditorCurrent()
                    check(currentPath == activePath && controller.documentVersion == activeVersion) {
                        "编辑内容已变化，本次替换已取消"
                    }
                    val currentDocument = documents.firstOrNull { it.path == activePath }
                    var changed = 0
                    var replaced = 0
                    if (currentDocument != null) {
                        currentDocument.matches.asReversed().forEach { match ->
                            controller.replaceRange(
                                positionAt(activeText, match.start),
                                positionAt(activeText, match.end),
                                replacementText,
                            )
                        }
                        if (currentDocument.matches.isNotEmpty()) {
                            changed++
                            replaced += currentDocument.matches.size
                        }
                    }
                    val external = documents.filter { it.path != activePath }
                    val saved = withContext(ModuleUiRuntime.io) {
                        val job = currentCoroutineContext()
                        external.map { document ->
                            job.ensureActive()
                            requireEditorCurrent()
                            document to runCatching {
                                val file = EpubTextFiles.resolve(root, document.path)
                                val loaded = EpubTextFiles.load(file) { job.ensureActive(); requireEditorCurrent() }
                                check(document.snapshot?.fingerprint?.sha256 == loaded.snapshot.fingerprint.sha256) {
                                    "文件在查找后已被其他操作修改，未替换"
                                }
                                val next = EpubScriptaSearch.replace(loaded.text, query, replacementText, useRegex, false)
                                if (next.count == 0) 0 else {
                                    saveTextSource(file, next.text, loaded.snapshot) { job.ensureActive() }
                                    writtenPaths += document.path
                                    next.count
                                }
                            }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                HookRuntime.log("ReaMicro Scripta replace failed (${document.path}): ${error.message}")
                                if (error is com.reamicro.fix.epub.editor.EpubWriteNotConfirmed) {
                                    writtenPaths += document.path
                                    -2
                                } else -1
                            }
                        }
                    }
                    requireEditorCurrent()
                    val savedCount = saved.filter { it.second > 0 }
                    val failedCount = saved.count { it.second == -1 }
                    val unconfirmedCount = saved.count { it.second == -2 }
                    replaced += savedCount.sumOf { it.second }
                    changed += savedCount.size

                    val committed = writtenPaths.toSet()
                    val syncError = if (committed.isEmpty()) null else
                        runCatching { requireEditorCurrent(); onSaved(committed) }.exceptionOrNull()
                    if (syncError is CancellationException) throw syncError

                    searchResults = emptyList()
                    searchFileIndex = -1
                    searchMatchIndex = -1
                    val feedback = CommittedFileFeedback(
                        "已替换 $replaced 处，涉及 $changed 个文件；${savedCount.size} 个文件已落盘" +
                            (if (currentDocument?.matches?.isNotEmpty() == true) "；当前文件需点击保存" else ""),
                        changed = committed.isNotEmpty(),
                    )
                    if (failedCount > 0) feedback.addWarning("$failedCount 个文件读取或保存失败")
                    if (unconfirmedCount > 0) feedback.addWarning("$unconfirmedCount 个文件写入已发生但未确认，请重新打开检查，勿重复提交")
                    if (syncError != null) feedback.addWarning("书架同步失败")
                    searchMessage = feedback.displayMessage
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    searchMessage = (if (writtenPaths.isEmpty()) "" else "已有 ${writtenPaths.size} 个文件写入；") +
                        (error.message ?: "替换失败")
                } finally {
                    searchBusy = false
                }
            }
        }

        val bg = p.page
        val textColor = p.text
        val muted = p.caption
        val uiState = StructureScriptaUiState(loading, saving, searchBusy, dismissRequested,
            confirmClose || fileSearchVisible || drawer.isOpen, controller.isModified, snapshot != null,
            controller.canUndo, controller.canRedo)
        val colors = remember(p) { StructureScriptaChrome.colors(p) }
        val safeDrawingInsets = WindowInsets.safeDrawing

        MaterialTheme(colorScheme = native.controlsScheme(), typography = typography) {
            androidx.compose.animation.AnimatedVisibility(
                visibleState = editorVisibility, modifier = Modifier.fillMaxSize(),
                enter = StructureFileMotion.enter(), exit = StructureFileMotion.exit(),
            ) {
            Box(Modifier.fillMaxSize().background(p.content).drawBehind { drawRect(p.page, size = androidx.compose.ui.geometry.Size(size.width, safeDrawingInsets.getTop(this).toFloat())) }.safeDrawingPadding().imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    StructureToolbar(
                        modifier = Modifier.background(p.page),
                        title = bookTitle + if (controller.isModified) " ·" else "", subtitle = currentPath,
                        titleStyle = HostEmbeddedStyle.title(hostFonts),
                        subtitleStyle = HostEmbeddedStyle.caption(hostFonts),
                        titleColor = textColor, subtitleColor = muted,
                        navigationIcon = {
                            IconButton(enabled = !saving && !searchBusy, onClick = { close() }) {
                                Icon(HostEmbeddedIcons.Back, "返回", tint = textColor)
                            }
                        },
                        actions = {
                            IconButton(enabled = !loading && !saving && !searchBusy && !confirmClose && !dismissRequested && !drawer.isOpen,
                                onClick = { onFileSearchOpened(); fileSearchVisible = true }) {
                                Icon(painterResource(R.drawable.ic_structure_search),
                                    stringResource(R.string.epub_structure_search_title), Modifier.size(24.dp), tint = textColor)
                            }
                            IconButton(enabled = !saving && !searchBusy && !confirmClose && !dismissRequested && !drawer.isOpen, onClick = { navigateHome() }) {
                                Icon(painterResource(R.drawable.ic_structure_home), "返回图书结构", Modifier.size(24.dp), tint = textColor)
                            }
                            if (openedFilePaths().isNotEmpty()) IconButton(
                                enabled = !loading && !saving && !searchBusy && !confirmClose && !dismissRequested,
                                onClick = { coroutineScope.launch { drawer.open() } }) {
                                StructureOpenedFilesBadge(openedFilePaths().size, hostFonts, p.native.role("OnSurface"), p.page)
                            }
                        },
                    )
                    HorizontalDivider(thickness = StructureContentStyle.dividerWidth, color = p.divider)

                    ModalNavigationDrawer(
                        modifier = Modifier.weight(1f).fillMaxWidth(), drawerState = drawer,
                        gesturesEnabled = drawer.isOpen,
                        drawerContent = {
                            openedFilesContent(
                                { path ->
                                    coroutineScope.launch { drawer.close() }
                                    pendingSelection = null
                                    switchTo(path)
                                },
                                ::closeOpenedFile,
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                                val showSearchPanel = searchVisible && !loading && failure == null
                                StructureScriptaBody(
                                    searchVisible = showSearchPanel,
                                    modifier = Modifier.fillMaxSize().background(p.content)
                                        .padding(start = StructureContentStyle.inset, end = StructureContentStyle.inset, top = StructureContentStyle.inset),
                                    editor = {
                                        StructureContentCard(p, source = true, contentPadding = PaddingValues(0.dp)) {
                                            StructureScriptaSourceBody(
                                                searchVisible = showSearchPanel,
                                                header = { StructureSourceHeader(currentPath.substringAfterLast('.', "HTML"), uiState, p) },
                                                editor = {
                                                    if (loading || failure != null) {
                                                        Column(Modifier.fillMaxSize().padding(16.dp),
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center) {
                                                            if (loading) StructureDataLoading()
                                                            Text(if (loading) "正在读取文件…" else failure.orEmpty(),
                                                                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                                                                color = if (failure != null) p.error else p.caption)
                                                            if (failure != null) com.reamicro.fix.ui.DialogActionButton(enabled = !saving && !dismissRequested,
                                                                onClick = { retry++ }) { Text("重试", style = MaterialTheme.typography.labelMedium) }
                                                        }
                                                    } else {
                                                        CodeEditor(
                                                            controller = controller,
                                                            modifier = Modifier.fillMaxSize(),
                                                            colors = colors, highlighter = highlighter, softWrap = softWrap,
                                                            readOnly = saving || searchBusy || dismissRequested || confirmClose || fileSearchVisible || drawer.isOpen,
                                                            autoClosePairs = false, consumeSystemInsets = false,
                                                            controlTextStyle = structureEditorControlTextStyle(),
                                                            onFindRequested = { searchVisible = true },
                                                            onFindNext = { moveMatch(1) }, onFindPrevious = { moveMatch(-1) },
                                                            onGotoLineRequested = { searchVisible = false; controller.openGotoLine() },
                                                            symbols = if (showSearchPanel) emptyList() else top.yukonga.scripta.editor.DefaultEditorSymbols,
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    search = {
                                        StructureScriptaSearchPanel(
                                            palette = p,
                                            query = searchQuery, replacement = replacement, scope = searchScope,
                                            regex = regex, textOnly = textOnly, enabled = uiState.canSearch,
                                            processing = searchBusy || saving || dismissRequested,
                                            hasMatches = searchResults.any { it.matches.isNotEmpty() },
                                            status = searchMessage.ifBlank { searchStatus(searchResults, searchFileIndex, searchMatchIndex) },
                                            onQueryChange = { searchQuery = it; searchResults = emptyList(); searchMessage = "" },
                                            onReplacementChange = { replacement = it },
                                            onScopeChange = { searchScope = it; searchResults = emptyList(); searchMessage = "" },
                                            onRegexChange = { regex = it; searchResults = emptyList(); searchMessage = "" },
                                            onTextOnlyChange = { textOnly = it; searchResults = emptyList(); searchMessage = "" },
                                            onSearch = { searchNow(true) }, onPrevious = { moveMatch(-1) }, onNext = { moveMatch(1) },
                                            onReplaceCurrent = { replaceCurrent() }, onReplaceAll = { replaceAll() },
                                            onClose = { searchVisible = false },
                                        )
                                    },
                                    footer = {
                                        StructureEditorFooter(palette = p, state = uiState,
                                            line = controller.caret.line + 1, column = controller.caret.column + 1,
                                            format = "${snapshot?.format?.charset?.name().orEmpty()} · ${controller.lineEnding.name}",
                                            softWrap = softWrap, showDetails = !showSearchPanel,
                                            onUndo = { controller.undo() }, onRedo = { controller.redo() },
                                            onSearch = {
                                                controller.closeGotoLine()
                                                searchVisible = !searchVisible
                                            }, onSave = { save() },
                                            onGotoLine = { searchVisible = false; controller.openGotoLine() }, onSoftWrap = { softWrap = !softWrap })
                                    },
                                )
                            StructureFileSearchOverlay(
                                visible = fileSearchVisible, paths = filePaths(), labels = fileSearchLabels(), palette = p,
                                busy = loading || saving || searchBusy || dismissRequested,
                                onDismiss = { fileSearchVisible = false },
                                onSelect = { path -> fileSearchVisible = false; pendingSelection = null; switchTo(path) },
                            )
                        }
                    }
                }
                StructureScriptaUnsavedPrompt(visible = confirmClose, filename = currentPath.substringAfterLast('/'),
                    switching = confirmTarget != null, palette = p, busy = saving || searchBusy || dismissRequested,
                    onContinue = { cancelNavigation() },
                    onDiscard = {
                        val target = confirmTarget
                        confirmClose = false
                        confirmTarget = null
                        finishNavigation(target)
                    },
                    onSave = { save(closeAfter = confirmTarget == null) })
            }
            }
        }
    }

    private data class PendingSelection(val path: String, val start: Int, val end: Int)
}

private fun searchStatus(results: List<EpubSearchDocument>, fileIndex: Int, matchIndex: Int): String {
    val total = results.sumOf { it.matches.size }
    if (total == 0) return "0 / 0"
    val before = results.take(fileIndex.coerceAtLeast(0)).sumOf { it.matches.size }
    val current = before + matchIndex.coerceAtLeast(0) + 1
    return "$current / $total"
}
private fun normalizeLineEndings(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

private fun normalizedOffset(raw: String, offset: Int): Int {
    val safe = offset.coerceIn(0, raw.length)
    var removed = 0
    var index = 0
    while (index < safe) {
        if (raw[index] == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') removed++
        index++
    }
    return safe - removed
}

private fun positionAt(text: String, offset: Int): TextPosition {

    val safe = offset.coerceIn(0, text.length)
    var line = 0
    var lineStart = 0
    var index = 0
    while (index < safe) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
        index++
    }
    return TextPosition(line, safe - lineStart)
}

private fun selectMatch(controller: CodeEditorController, text: String, start: Int, end: Int) {
    val normalized = normalizeLineEndings(text)
    controller.select(
        positionAt(normalized, normalizedOffset(text, start)),
        positionAt(normalized, normalizedOffset(text, end)),
    )
}
