package com.reamicro.fix.hook

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.BitmapFactory

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout

import android.widget.Toast
import com.reamicro.fix.epub.editor.EpubStorageFiles
import com.reamicro.fix.epub.editor.CommittedFileFeedback
import kotlinx.coroutines.*
import com.reamicro.fix.epub.editor.HostOpfIdentifier
import com.reamicro.fix.epub.editor.EpubOpfMetadata
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.usesScriptaEpubEditor
import com.reamicro.fix.epub.editor.canEditEpubSource
import com.reamicro.fix.epub.editor.EpubNavigation
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.WeakHashMap

internal class EpubBookPanel(
    private val activity: Activity,
    private val root: File,
    private val bookTitle: String,
    private var book: Any?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val fontSettingsProvider: () -> FontSettingsSnapshot = { FontSettingsSnapshot() },
) {
    private val dialog by lazy { Dialog(com.reamicro.fix.core.InjectedModuleContext.create(activity), com.reamicro.fix.R.style.EpubFullScreenDialog) }
    private var nativeEditor: EpubScriptaPanel? = null
    private var structure: EpubStructureView? = null
    private var structureView: View? = null
    @Volatile private var disposed = false
    private val storage = EpubStorageFiles(root)
    private var currentBookTitle = bookTitle.trim().ifBlank { root.name }
    private var listingSnapshot: List<File>? = null
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge by lazy { Bridge() }
    private lateinit var container: FrameLayout

    private var appliedWindowPalette: Pair<Int, Boolean>? = null
    private val themeCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            updateWindowTheme((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        }
        override fun onLowMemory() = Unit
    }
    private var pendingImportGroupPath: String? = null
    private var importInProgress = false

    @Volatile private var metadataChanged: Boolean = false
    private val globalUiFontFile: File? by lazy { resolveGlobalUiFontFile() }

    fun show() {
        synchronized(activePanels) {
            if (activePanels[activity]?.dialog?.isShowing == true) return
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        container = FrameLayout(activity).apply { setBackgroundColor(pageBackground()) }
        structure = EpubStructureView(
            activity = activity,
            root = root,
            bookTitle = currentBookTitle,
            request = { name, args ->
                synchronized(bridge) {
                    when (name) {
                        "initialData" -> bridge.initialData()
                        "initialMetadata" -> bridge.initialMetadata()
                        "cssRules" -> bridge.cssRules(args.getOrNull(0))
                        "cssSource" -> bridge.cssSource(args.getOrNull(0))
                        "saveCssValue" -> bridge.saveCssValue(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2),
                            args.getOrNull(3), args.getOrNull(4))
                        "saveCssSelector" -> bridge.saveCssSelector(args.getOrNull(0), args.getOrNull(1),
                            args.getOrNull(2), args.getOrNull(3))
                        "saveCssSource" -> bridge.saveCssSource(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2))
                        "getMetadata" -> bridge.getMetadata(args.getOrNull(0))
                        "navigation" -> bridge.navigation(args.getOrNull(0))
                        "saveMetadataField" -> bridge.saveMetadataField(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2), args.getOrNull(3))
                        "renameFile" -> bridge.renameFile(args.getOrNull(0), args.getOrNull(1))
                        "deleteFile" -> bridge.deleteFile(args.getOrNull(0))
                        "createTextFile" -> bridge.createTextFile(args.getOrNull(0), args.getOrNull(1))
                        "setCover" -> bridge.setCover(args.getOrNull(0))
                        "setBanner" -> bridge.setBanner(args.getOrNull(0))
                        "pickFile" -> { bridge.pickFile(args.getOrNull(0)); "" }
                        else -> error("不支持的书籍存储操作：$name")
                    }
                }
            },
            openScripta = ::openScripta,
            close = { dialog.dismiss() },
            uiFontFileProvider = { globalUiFontFile },
            onSurfaceChanged = { background ->
                applyWindowPalette(background, StructureHome130Style.palette(activity, isNightMode()).dark)
            },
        )
        structureView = structure!!.create()
        container.addView(structureView, FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(container)
        structure?.bindWindow(dialog.window)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            if (structure?.handleBack() != true) dialog.dismiss()
            true
        }
        dialog.setOnDismissListener {
            disposed = true
            pendingImportGroupPath = null
            panelScope.cancel()
            nativeEditor?.dismissForHostShutdown()
            nativeEditor = null
            structure?.dispose()
            structure = null
            structureView = null
            synchronized(activePanels) { activePanels.remove(activity) }
            runCatching { activity.unregisterComponentCallbacks(themeCallbacks) }

            if (metadataChanged && !activity.isFinishing && !activity.isDestroyed) {
                activity.window?.decorView?.postDelayed({
                    runCatching {
                        if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX metadata refresh failed: ${it.stackTraceToString()}")
                    }
                }, 250L)
            }
        }
        synchronized(activePanels) { activePanels[activity] = this }
        activity.registerComponentCallbacks(themeCallbacks)
        try {
            dialog.show()
            configureWindow()
            if (Build.VERSION.SDK_INT >= 33) {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { if (structure?.handleBack() != true) dialog.dismiss() }
            }
        } catch (e: Throwable) {
            disposed = true
            panelScope.cancel()
            structure?.dispose()
            synchronized(activePanels) { activePanels.remove(activity) }
            runCatching { activity.unregisterComponentCallbacks(themeCallbacks) }
            throw e
        }
    }

    private fun configureWindow() {
        val nativePalette = StructureHome130Style.palette(activity, isNightMode())
        val background = nativePalette.page.toArgb()
        val dark = nativePalette.dark
        configureEpubEdgeToEdgeWindow(dialog.window, background, dark)
        appliedWindowPalette = background to dark
    }
    private fun applyWindowPalette(background: Int, dark: Boolean) {
        if (!dialog.isShowing) return
        val next = background to dark
        if (appliedWindowPalette == next) return
        appliedWindowPalette = next
        if (::container.isInitialized) container.setBackgroundColor(background)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(background))
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    private fun updateWindowTheme(dark: Boolean) {
        activity.runOnUiThread {
            structure?.updateTheme()
            val nativePalette = StructureHome130Style.palette(activity, dark)
            applyWindowPalette(nativePalette.page.toArgb(), nativePalette.dark)
        }
    }

    private fun isNightMode(): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun openScripta(path: String, startWithSearch: Boolean) {
        if (!dialog.isShowing || nativeEditor != null || activity.isFinishing) return
        require(canEditEpubSource(path)) { "不是可编辑的章节或目录资源" }
        runCatching {
            EpubScriptaPanel(
                activity = activity,
                root = root,
                initialPath = path,
                bookTitle = currentBookTitle,
                openedFilePaths = { structure?.openedFilePaths().orEmpty() },
                openedFilesContent = { onOpen, onClose -> structure?.OpenedFilesContent(onOpen, onClose) },
                onFileClosed = { path -> structure?.closeOpened(path) },
                onFileOpened = { path -> structure?.recordOpenedText(path) },
                onShowStructureHome = { structure?.showHome() },
                filePaths = { structure?.filePaths().orEmpty() },
                fileSearchLabels = { structure?.fileSearchLabels().orEmpty() },
                onOpenStructureFile = { path -> structure?.requestOpenFile(path) },
                startWithSearch = startWithSearch,
                uiFontFileProvider = { globalUiFontFile },
                onSaved = { committedPaths ->
                    require(committedPaths.isNotEmpty())
                    try {
                        withContext(Dispatchers.IO) { synchronized(bridge) { refreshBookFileCache() } }
                    } finally {

                        structure?.refresh()
                    }
                },
                onClosed = { nativeEditor = null },
            ).also { nativeEditor = it }.show()
        }.onFailure {
            nativeEditor = null
            XposedBridge.log("$LOG_PREFIX Scripta open failed: ${it.stackTraceToString()}")
            activity.toast("编辑器打开失败：${it.message}")
        }
    }

    private inner class Bridge {
        fun cssRules(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            com.reamicro.fix.epub.editor.editableCssPreview(activity.classLoader, file).toString()
        }
        fun cssSource(path: String?): String = safeJson {
            val loaded = com.reamicro.fix.epub.editor.EpubCssEditor.load(resolveChild(path.orEmpty()), sourceDialog = true)
            JSONObject().put("ok", true).put("text", loaded.text)
                .put("sourceHash", loaded.snapshot.fingerprint.sha256).toString()
        }
        fun saveCssValue(path: String?, hash: String?, rule: String?, declaration: String?, value: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            val saved = com.reamicro.fix.epub.editor.EpubCssEditor.saveValue(file, hash.orEmpty(),
                rule?.toIntOrNull() ?: error("CSS 规则位置无效"),
                declaration?.toIntOrNull() ?: error("CSS 声明位置无效"), value.orEmpty())
            cssSaveResult(file, saved)
        }
        fun saveCssSelector(path: String?, hash: String?, rule: String?, value: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            val saved = com.reamicro.fix.epub.editor.EpubCssEditor.saveSelector(file, hash.orEmpty(),
                rule?.toIntOrNull() ?: error("CSS 规则位置无效"), value.orEmpty())
            cssSaveResult(file, saved)
        }
        fun saveCssSource(path: String?, hash: String?, value: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            val saved = com.reamicro.fix.epub.editor.EpubCssEditor.saveSource(file, hash.orEmpty(), value.orEmpty())
            cssSaveResult(file, saved)
        }
        private fun cssSaveResult(file: File, saved: com.reamicro.fix.epub.editor.EpubCssEditor.Saved): String =
            committedResult(
                if (saved.changed) "CSS 已保存" else "内容未变化，无需保存",
                changed = saved.changed,
                data = JSONObject().put("sourceHash", saved.snapshot.fingerprint.sha256),
            ) { feedback ->
                if (saved.changed) {
                    feedback.followUp("样式缓存刷新失败（可重新打开阅读页）") {
                        com.reamicro.fix.epub.editor.invalidateHostCssPreview(activity.classLoader, file)
                    }
                    feedback.followUp("书架同步失败") { refreshBookFileCache() }
                }
            }.toString()

        fun navigation(path: String?): String = safeJson {
            val rows = EpubNavigation.read(root, path.orEmpty(), allFiles().map(::relativePath)
                .filter(StructureFileVisibility::isVisible))
            JSONObject().put("ok", true).put("rows", JSONArray().apply {
                rows.forEach { row -> put(JSONObject().put("title", row.title).put("href", row.href)
                    .put("path", row.path.orEmpty()).put("depth", row.depth)) }
            }).toString()
        }
        fun getMetadata(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile && file.extension.equals("opf", true)) { "不是 OPF 文件" }
            JSONObject().put("ok", true).put("metadata", metadataJson(file)).toString()
        }
        fun saveMetadataField(path: String?, field: String?, value: String?, expected: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile && file.extension.equals("opf", true)) { "不是 OPF 文件" }
            val key = field.orEmpty()
            val loaded = EpubTextFiles.load(file)
            val nextValue = value.orEmpty()
            com.reamicro.fix.epub.editor.EpubMetadataFields.validate(key, nextValue)
            val currentValue = EpubOpfMetadata.read(loaded.text)[key]
            require(currentValue == expected.orEmpty()) {
                "此字段已被其他操作修改，请重新打开"
            }
            val isPrimaryOpf = file == opfFile()
            val changed = currentValue != nextValue
            if (changed) {
                val next = EpubOpfMetadata.update(loaded.text, key, nextValue)
                check(EpubOpfMetadata.read(next)[key] == nextValue) { "元数据修改未通过预校验，未写入" }
                EpubTextFiles.save(file, next, loaded.snapshot)
            }
            val data = JSONObject()
            committedResult(if (changed) "元数据已保存" else "内容未变化，无需保存",
                changed = changed, data = data) { feedback ->

                if (isPrimaryOpf && key == "title") currentBookTitle = nextValue.trim()
                if (changed) feedback.followUp("书架同步失败") {
                    if (isPrimaryOpf) {

                        val patch = when (key) {
                            "title" -> BookMetadataPatch(title = nextValue)
                            "subtitle" -> BookMetadataPatch(subtitle = nextValue)
                            "author" -> BookMetadataPatch(author = nextValue)
                            "publisher" -> BookMetadataPatch(publisher = nextValue)
                            else -> BookMetadataPatch()
                        }
                        syncPatch(patch.copy(size = bookDirectorySize()))
                    } else refreshBookFileCache()
                }
                feedback.followUp("元数据预览刷新失败") { data.put("metadata", metadataJson(file)) }
            }.toString()
        }
        fun initialData(): String = safeJson {
            val files = allFiles()
            listingSnapshot = files
            JSONObject()
                .put("title", currentBookTitle)
                .put("status", "已解包 ${files.count { StructureFileVisibility.isVisible(relativePath(it)) }} 个可见文件")
                .put("files", filesJson(files))
                .toString()
        }
        fun initialMetadata(): String = safeJson {
            JSONObject().put("title", currentBookTitle)
                .put("metadata", metadataJson(files = listingSnapshot)).toString()
        }
        private fun editResource(path: String, nextName: String?): String = safeJson {
            val previousBook = book
            val oldCover = callBookString("getCover")
            val oldSize = runCatching { previousBook?.javaClass?.getMethod("getSize")?.invoke(previousBook) as? Number }.getOrNull()?.toLong()
                ?: bookDirectorySize()
            val result = com.reamicro.fix.online.download.OnlineOnDemandMetadataStore.resourceEdit(root) {
                val storageIdentity = HostOpfIdentifier.read(activity.classLoader, root).value
                com.reamicro.fix.epub.editor.EpubResourceTransaction.execute(storage, path, nextName,
                    File(activity.filesDir, "reamicro-resource-backups"), oldCover,
                    commitBook = { cover ->
                        check(HostOpfIdentifier.read(activity.classLoader, root).value == storageIdentity) { "宿主存储标识发生变化，联动已取消" }
                        if (book != null) syncPatch(BookMetadataPatch(cover = cover, size = bookDirectorySize()))
                    },
                    rollbackBook = { if (previousBook != null) { book = previousBook; syncPatch(BookMetadataPatch(cover = oldCover, size = oldSize)) } },
                    checkpoint = { if (disposed) throw CancellationException("图书结构已关闭") })
            }
            val data = JSONObject().put("path", result.path.orEmpty()).put("linkedFiles", result.changedPaths.size)
                .put("resourceBackup", result.backup.path).put("renamedPaths", JSONObject(result.renamedPaths))
            committedResult(if (nextName == null) "已删除并更新引用" else "已重命名并更新引用", data = data) { feedback ->
                feedback.followUp("结构预览刷新失败") { data.put("metadata", metadataJson()) }
            }.toString()
        }
        fun renameFile(path: String?, nextName: String?): String = editResource(path.orEmpty(), nextName.orEmpty())
        fun deleteFile(path: String?): String = editResource(path.orEmpty(), null)

        fun createTextFile(groupPath: String?, name: String?): String = safeJson {
            val target = storage.create(groupPath.orEmpty(), name.orEmpty())
            committedResult("已新建文件", data = JSONObject().put("path", storage.relative(target))).toString()
        }

        fun setCover(path: String?): String = safeJson {
            val image = resolveChild(path.orEmpty())
            require(image.isFile) { "文件不存在" }
            require(fileKind(image) == "image") { "不是图片文件" }
            requireImageReadable(image)
            val coverPath = relativePath(image)
            updateCoverHref(image)
            val data = JSONObject()
            committedResult("已设为封面", data = data) { feedback ->
                feedback.followUp("书架同步失败") {
                    syncPatch(BookMetadataPatch(cover = coverPath, size = bookDirectorySize()))
                }
                feedback.followUp("封面预览刷新失败") { data.put("metadata", metadataJson()) }
            }.toString()
        }

        fun setBanner(path: String?): String = safeJson {
            val image = resolveChild(path.orEmpty())
            require(image.isFile) { "文件不存在" }
            require(fileKind(image) == "image") { "不是图片文件" }
            requireImageReadable(image)
            val coverPath = bannerBaseCoverPath()
            val target = resolveChild(com.reamicro.fix.epub.editor.EpubBannerVariants.primary(coverPath, activity.classLoader))
            target.parentFile?.mkdirs()
            requireInsideRoot(target)
            com.reamicro.fix.epub.editor.VerifiedFileIO.copyAtomic(image, target)
            committedResult("已设为横幅", data = JSONObject().put("path", relativePath(target))) { feedback ->
                feedback.followUp("横幅时间戳更新失败") {
                    check(target.setLastModified(System.currentTimeMillis()))
                }
                feedback.followUp("书架同步失败") { refreshBookFileCache(coverPathOverride = coverPath) }
            }.toString()
        }
        fun pickFile(groupPath: String?) {
            activity.runOnUiThread {
                if (disposed || pendingImportGroupPath != null || importInProgress) return@runOnUiThread
                pendingImportGroupPath = groupPath.orEmpty()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                runCatching {
                    activity.startActivityForResult(intent, REQUEST_IMPORT_FILE)
                }.onFailure {
                    pendingImportGroupPath = null
                    activity.toast("无法打开文件选择器")
                    XposedBridge.log("$LOG_PREFIX file editor import picker failed: ${it.stackTraceToString()}")
                }
            }
        }

        private fun safeJson(block: () -> String): String =
            runCatching(block).getOrElse {
                if (it is CancellationException) throw it
                XposedBridge.log("$LOG_PREFIX file panel bridge failed: ${it.stackTraceToString()}")
                JSONObject()
                    .put("ok", false)
                    .put("committed", it is com.reamicro.fix.epub.editor.EpubWriteNotConfirmed)
                    .put("message", it.message.orEmpty().ifBlank { "操作失败" })
                    .toString()
            }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_IMPORT_FILE || disposed) return

        val group = pendingImportGroupPath ?: return
        pendingImportGroupPath = null
        if (resultCode != Activity.RESULT_OK || importInProgress) return
        val uri = data?.data ?: return activity.toast("未选择文件")
        importInProgress = true
        structure?.setExternalBusy(true)
        panelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ctx = coroutineContext
                    synchronized(bridge) {
                        val name = displayName(uri).ifBlank { "imported-file" }
                        val target = activity.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取文件" }
                            storage.importFile(group, name, input) { ctx.ensureActive() }
                        }
                        committedResult("已导入：${storage.relative(target)}")
                    }
                }
                if (!disposed) {
                    structure?.refresh()

                    panelScope.launch { structure?.showWriteFeedback(result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!disposed) panelScope.launch {
                    structure?.showFeedback("导入失败：${e.message}", warning = true)
                }
            } finally {
                importInProgress = false
                structure?.setExternalBusy(false)
            }
        }
    }

    private fun committedResult(
        message: String,
        changed: Boolean = true,
        data: JSONObject = JSONObject(),
        followUp: (CommittedFileFeedback) -> Unit = { feedback ->
            if (changed) feedback.followUp("书架同步失败") { refreshBookFileCache() }
        },
    ): JSONObject {
        if (changed) {
            listingSnapshot = null
            metadataChanged = true
        }
        val feedback = CommittedFileFeedback(message, changed) { error ->
            XposedBridge.log("$LOG_PREFIX post-commit refresh failed: ${error.stackTraceToString()}")
        }
        feedback.followUp("后续刷新未完成") { followUp(feedback) }
        return data.put("ok", true).put("committed", changed).put("changed", changed)
            .put("message", feedback.message).put("warning", feedback.warning)
    }

    private fun requireImageReadable(file: File) {
        if (file.extension.equals("svg", true)) {
            com.reamicro.fix.epub.editor.hostSvgPreview(activity.classLoader, file, 96).recycle()
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片不能解码，未修改封面或横幅" }
        }
    }

    private fun updateCoverHref(newCover: File) {
        val opf = opfFile() ?: error("未找到 OPF 文件")
        val relative = newCover.relativeTo(opf.parentFile ?: root).invariantSeparatorsPath
        val href = java.net.URI(null, null, relative, null).toASCIIString()
        val loaded = EpubTextFiles.load(opf)
        val next = EpubOpfMetadata.updateCover(loaded.text, href, mimeType(newCover))
        check(EpubOpfMetadata.coverHref(next) == href) { "封面引用修改未通过预校验，未写入" }
        EpubTextFiles.save(opf, next, loaded.snapshot)
    }

    private fun filesJson(files: List<File> = allFiles()): JSONArray =
        JSONArray().apply {
            val visible = files.filter { StructureFileVisibility.isVisible(relativePath(it)) }
            val navigationPaths = EpubNavigation.paths(root, visible.map(::relativePath))
            visible.forEach { file -> put(fileJson(file, relativePath(file) in navigationPaths)) }
        }

    private fun fileJson(file: File, navigation: Boolean = file.extension.equals("ncx", true)): JSONObject {
        val relative = relativePath(file)
        val groupPath = relative.substringBeforeLast("/", "")
        val kind = fileKind(file)
        val size = file.length()
        return JSONObject()
            .put("path", relative)
            .put("name", file.name)
            .put("groupPath", groupPath)
            .put("kind", if (navigation) "navigation" else kind)
            .put("size", size)
            .put("sizeText", formatFileSize(size))
            .put("nativeEditor", !navigation && usesScriptaEpubEditor(relative))
    }

    private fun metadataJson(opfOverride: File? = null, files: List<File>? = null): JSONObject {

        val opf = opfOverride ?: opfFile(files)
        val content = opf?.let { runCatching { EpubTextFiles.load(it).text }.getOrDefault("") }.orEmpty()
        val fields = runCatching { EpubOpfMetadata.read(content) }.getOrDefault(emptyMap())
        val opfCover = opf?.let { findCoverFile(it, files, content) }
        val hostCover = callBookString("getCover").takeIf(::isBookRelativePath)?.let { path ->
            runCatching { resolveChild(path).takeIf { it.isFile && fileKind(it) == "image" } }.getOrNull()
        }
        val cover = hostCover ?: opfCover
        val identityJson = JSONObject()

        val displayedIdentifier = runCatching {
            val raw = if (book != null) callBookString("getUuid")
                else HostOpfIdentifier.read(activity.classLoader, root).value
            BookIdentifierText.display(raw)
        }
        displayedIdentifier.onSuccess { identityJson.put("value", it) }
            .onFailure { identityJson.put("error", it.message ?: "宿主标识读取失败") }
        return JSONObject()
            .put("uniqueIdentifier", identityJson)
            .put("uuid", displayedIdentifier.getOrDefault(""))
            .put("opfPath", opf?.let(::relativePath).orEmpty())
            .put("language", fields["language"].orEmpty())
            .put("date", fields["date"].orEmpty())
            .put("title", fields["title"] ?: tagText(content, "dc:title"))
            .put("author", fields["author"] ?: tagText(content, "dc:creator"))
            .put("subtitle", fields["subtitle"] ?: tagText(content, "dc:subtitle"))
            .put("publisher", fields["publisher"] ?: tagText(content, "dc:publisher"))
            .put("opfPath", opf?.let(::relativePath).orEmpty())
            .put("coverPath", cover?.let(::relativePath).orEmpty())
            .put("bannerPath", com.reamicro.fix.epub.editor.EpubBannerVariants.existing(
                callBookString("getCover").takeIf(::isBookRelativePath) ?: cover?.let(::relativePath).orEmpty(),
                (files ?: allFiles()).map(::relativePath).toSet(), activity.classLoader).orEmpty())
    }

    private fun opfFile(files: List<File>? = null): File? {
        val container = resolveChild("META-INF/container.xml")
        if (container.isFile) {
            val text = EpubTextFiles.load(container, MAX_TEXT_BYTES).text
            val href = EpubOpfMetadata.packagePath(text)
            if (!href.isNullOrBlank()) {
                return resolveChild(href).takeIf { it.isFile }
                    ?: resolveChild(Uri.decode(href)).takeIf { it.isFile }
                    ?: error("container.xml 指向的 OPF 不存在")
            }
        }
        return (files ?: allFiles()).firstOrNull { it.extension.equals("opf", true) }
    }

    private fun refreshBookFileCache(coverPathOverride: String? = null) {
        syncPatch(BookMetadataPatch(
            cover = coverPathOverride?.takeIf { it.isNotBlank() },
            size = bookDirectorySize(),
        ))
    }

    private fun syncPatch(patch: BookMetadataPatch) {
        listingSnapshot = null
        metadataChanged = true
        val original = book ?: error("文件已保存，但没有可同步的书架记录")
        val repository = ReaMicroBookMetadataSync.currentBookshelfRepository()
        check(ReaMicroBookMetadataSync.syncBookMetadata(repository, original, patch)) {
            "文件已保存，但书架数据库回读未确认；请刷新后检查，不要重复提交"
        }
        book = repository?.let { ReaMicroBookMetadataSync.latestBook(it, original) }
            ?: error("文件已保存，但无法刷新书架记录")
    }

    private fun bookDirectorySize(): Long = storage.byteSize()

    private fun bannerBaseCoverPath(): String {
        val metadataCover = runCatching { metadataJson().optString("coverPath") }.getOrDefault("")
            .replace('\\', '/')
            .trim()
        val bookCover = callBookString("getCover")
            .replace('\\', '/')
            .trim()
            .takeIf(::isBookRelativePath)
        return bookCover
            ?: metadataCover.takeIf { it.isNotBlank() }
            ?: opfFile()?.let { findCoverFile(it) }?.let(::relativePath)
            ?: error("未找到封面，无法生成横幅命名")
    }

    private fun isBookRelativePath(value: String): Boolean =
        value.isNotBlank() &&
            !value.contains("://") &&
            !value.startsWith("/") &&
            !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)

    private fun callBookString(methodName: String): String =
        runCatching {
            book?.javaClass?.methods?.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(book)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")

    private fun findCoverFile(opf: File, files: List<File>? = null, content: String = EpubTextFiles.load(opf).text): File? {
        val href = EpubOpfMetadata.coverHref(content)
        if (href.isNotBlank()) {
            val file = File(opf.parentFile ?: root, Uri.decode(href.substringBefore('#'))).canonicalFile
            requireInsideRoot(file)
            return file.takeIf { it.isFile }
        }
        return null
    }

    private fun tagText(content: String, tag: String, attrName: String, attrValue: String): String =
        Regex("<$tag\\b[^>]*\\b$attrName=[\"']${Regex.escape(attrValue)}[\"'][^>]*\\bcontent=[\"']([^\"']*)[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeXmlText)
            .orEmpty()

    private fun decodeXmlText(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun allFiles(): List<File> = storage.visibleFiles()

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0).orEmpty()
            } else {
                uri.lastPathSegment.orEmpty().substringAfterLast('/')
            }
        } finally {
            cursor?.close()
        }
    }

    private fun resolveChild(path: String): File = storage.child(path)

    private fun requireInsideRoot(file: File) { storage.relative(file) }

    private fun relativePath(file: File): String = storage.relative(file)

    private fun pageBackground(): Int = StructureHome130Style.palette(activity, isNightMode()).page.toArgb()
    private fun resolveGlobalUiFontFile(): File? {
        if (!settingsProvider().canUseFontSettings) return null
        val selection = fontSettingsProvider().globalFamily.trim()
        if (selection.isBlank() || selection == GLOBAL_FONT_SYSTEM || selection == GLOBAL_FONT_SERIF) return null
        val direct = File(selection)
        if (direct.isFile && isGlobalFontFile(direct.name)) return direct
        val name = direct.name
        if (!isGlobalFontFile(name)) return null
        return fontDirectories(activity.filesDir)
            .asSequence()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.isFile && it.name == name && isGlobalFontFile(it.name) }
    }

    private fun fontDirectories(filesDir: File): List<File> {
        val dirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { File(it, "fonts") }
            ?.toMutableList()
            ?: mutableListOf()
        val defaultDir = File(File(filesDir, "0"), "fonts")
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) dirs.add(defaultDir)
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    private fun isGlobalFontFile(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

    private fun Activity.toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val LOG_PREFIX = "ReaMicro FilePanel"
        const val REQUEST_IMPORT_FILE = 0x46E1
        private const val MAX_TEXT_BYTES = 5L * 1024L * 1024L

        private const val GLOBAL_FONT_SYSTEM = "system"
        private const val GLOBAL_FONT_SERIF = "serif"
        private val activePanels = WeakHashMap<Activity, EpubBookPanel>()
        private val REAMICRO_MD5_REGEX = Regex("^[0-9a-fA-F]{32}$")
        private val TEXT_EXTENSIONS = setOf("html", "htm", "xhtml", "xml", "opf", "ncx", "css", "txt", "js", "json", "svg", "md")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

        fun dispatchActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            synchronized(activePanels) {
                activePanels[activity]
            }?.onActivityResult(requestCode, resultCode, data)
        }

        private fun fileKind(file: File): String {
            val ext = file.extension.lowercase(Locale.ROOT)
            return when {
                ext in IMAGE_EXTENSIONS -> "image"
                ext in FONT_EXTENSIONS -> "font"
                ext in setOf("html", "htm", "xhtml") -> "html"
                ext == "css" -> "css"
                ext in setOf("xml", "opf", "ncx", "svg") -> "xml"
                else -> "other"
            }
        }

        private fun mimeType(file: File): String =
            when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "image/jpeg"
            }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.0fKB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1fMB", mb)
        }

        private fun tagText(content: String, tag: String): String =
            Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&amp;", "&")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.trim()
                .orEmpty()
    }
}
