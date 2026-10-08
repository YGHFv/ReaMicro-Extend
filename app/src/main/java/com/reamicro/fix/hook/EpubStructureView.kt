package com.reamicro.fix.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.reamicro.fix.R
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.EpubStorageFiles
import com.reamicro.fix.epub.editor.EpubMetadataFields
import com.reamicro.fix.epub.editor.EpubChapterNames
import com.reamicro.fix.epub.editor.EpubChapterTitlePreview
import com.reamicro.fix.epub.editor.FontPreviewSamples
import com.reamicro.fix.epub.editor.epubPreviewSampleSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class EpubStructureView(
    private val activity: Activity,
    private val root: File,
    private val bookTitle: String,
    private val request: (String, Array<out String?>) -> String,
    private val openScripta: (String, Boolean) -> Unit,

    private val close: () -> Unit,
    private val uiFontFileProvider: () -> File? = { null },
    private val onSurfaceChanged: (Int) -> Unit = {},
) {
    private val owner = ModuleComposeOwner(activity, close)
    private var revision by mutableIntStateOf(0)
    private var externalWriteBusy by mutableStateOf(false)
    private val opened = mutableStateListOf<String>()
    private var files by mutableStateOf(emptyList<Entry>())
    private var current by mutableStateOf<String?>(null)
    private var availablePaths by mutableStateOf<List<String>>(emptyList())
    private var requestedPath by mutableStateOf<String?>(null)
    private var chapterTitles by mutableStateOf<Map<String, List<String>>>(emptyMap())
    private var availableLabels by mutableStateOf<Map<String, List<String>>>(emptyMap())
    fun fileSearchLabels(): Map<String, List<String>> = availableLabels
    fun filePaths(): List<String> = availablePaths
    fun requestOpenFile(path: String) { requestedPath = path }
    private var homeRequest by mutableIntStateOf(0)
    private val navigationScrollStates = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    fun showHome() { homeRequest++ }
    fun openedFilePaths(): List<String> = opened.toList()
    fun recordOpenedText(path: String) {
        StructureOpenedFiles.record(opened, path)
    }
    private val snackbar = SnackbarHostState()
    private var dark by mutableStateOf(StructureHome130Style.isDark(activity))
    private val palette get() = StructureHome130Style.palette(activity, dark)
    private val hostFonts by lazy { StructureHome130Style.fonts(activity) }
    private var back: (() -> Boolean)? = null
    private var view: ComposeView? = null
    private var unsupportedToast: Toast? = null
    private fun showUnsupportedFile() {
        unsupportedToast?.cancel()
        val message = view?.context?.getString(R.string.epub_structure_unsupported)
            ?: "暂未支持预览此格式"
        unsupportedToast = Toast.makeText(activity, message, Toast.LENGTH_SHORT).also { it.show() }
    }
    fun create(): View {
        owner.start()
        return try {
            ComposeView(InjectedModuleContext.create(activity)).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { this@EpubStructureView.StructureContent() }
            }.also { view = it }
        } catch (e: Throwable) { owner.close(); throw e }
    }
    fun bindWindow(window: android.view.Window?) {

        window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            findViewById<View>(android.R.id.content)?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
        }
    }
    fun refresh() { revision++ }
    fun setExternalBusy(value: Boolean) { externalWriteBusy = value }
    suspend fun showFeedback(message: String, warning: Boolean = false) {
        if (message.isBlank()) return
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message, withDismissAction = warning,
            duration = if (warning) SnackbarDuration.Long else SnackbarDuration.Short)
    }
    suspend fun showWriteFeedback(result: JSONObject) {
        val warning = result.optString("warning")
        showFeedback(warning.ifBlank { result.optString("message") }, warning.isNotBlank())
    }
    fun updateTheme() { dark = StructureHome130Style.isDark(activity) }
    fun handleBack(): Boolean = back?.invoke() ?: false
    fun dispose() {
        unsupportedToast?.cancel()
        unsupportedToast = null
        back = null
        view?.disposeComposition()
        owner.close()
        view = null
    }
    private suspend fun call(name: String, vararg args: String?): JSONObject = withContext(Dispatchers.IO) {
        val text = request(name, args)
        val value = if (text.isBlank() || text == "kotlin.Unit") JSONObject() else JSONObject(text)
        check(!value.has("ok") || value.optBoolean("ok")) { value.optString("message", "操作失败") }
        value
    }
    private data class Entry(
        val path: String, val name: String, val group: String, val kind: String,
        val size: String, val native: Boolean,
    )
    private fun entries(array: JSONArray): List<Entry> = (0 until array.length()).map { index ->
        val o = array.getJSONObject(index)
        Entry(o.getString("path"), o.getString("name"), o.optString("groupPath"), o.optString("kind"),
            o.optString("sizeText"), o.optBoolean("nativeEditor"))
    }
    private data class PreparedPreview(val entry: Entry, val revision: Int, val data: Result<JSONObject>?)
    private data class Prompt(
        val title: String, val value: String, val allowEmpty: Boolean = false,
        val note: String? = null, val sourceMode: Boolean = false,
        val submit: suspend (String) -> Unit,
    )
    private fun textPrompt(
        title: String, value: String, allowEmpty: Boolean = false, sourceMode: Boolean = false,
        note: String? = null, submit: suspend (String) -> Unit,
    ) = Prompt(title, value, allowEmpty, note, sourceMode, submit)

    @Composable private fun StructureContent() {

        val themed = palette.controlsScheme()
        val uiFont by produceState<FontFamily?>(null, uiFontFileProvider) {
            value = withContext(Dispatchers.IO) { runCatching {
                uiFontFileProvider()?.takeIf { it.isFile }?.let { FontFamily(Typeface.createFromFile(it)) }
            }.getOrNull() }
        }
        val typography = remember(uiFont, hostFonts) { StructureHome130Style.typography(hostFonts, uiFont) }
        MaterialTheme(colorScheme = themed, typography = typography) {
            val scope = rememberCoroutineScope()
            var metadata by remember { mutableStateOf(JSONObject()) }
            var displayBookTitle by remember { mutableStateOf(bookTitle) }
            var metadataLoading by remember { mutableStateOf(false) }
            var loading by remember { mutableStateOf(true) }
            var failure by remember { mutableStateOf<String?>(null) }
            var preparedPreview by remember { mutableStateOf<PreparedPreview?>(null) }
            var previewLoading by remember { mutableStateOf(false) }
            var previewGeneration by remember { mutableIntStateOf(0) }
            var revealedPath by remember { mutableStateOf<String?>(null) }
            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            val drawer = rememberDrawerState(DrawerValue.Closed)
            var sheet by remember { mutableStateOf<Entry?>(null) }
            var prompt by remember { mutableStateOf<Prompt?>(null) }
            var promptError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(prompt) { promptError = null }
            var confirmDelete by remember(sheet?.path) { mutableStateOf(false) }
            var operationBusy by remember { mutableStateOf(false) }
            val busy = operationBusy || externalWriteBusy

            var previewSamples by remember { mutableStateOf(FontPreviewSamples.defaults) }
            var fileSearchVisible by remember { mutableStateOf(false) }
            val file = files.firstOrNull { it.path == current }
            val pageColor = palette.page

            LaunchedEffect(pageColor) { onSurfaceChanged(pageColor.toArgb()) }

            val treeScroll = rememberLazyListState()

            val groupedFiles = remember(files) { files.groupBy { it.group } }

            fun perform(operation: suspend () -> Unit) {
                if (busy) return
                operationBusy = true
                scope.launch {
                    try { operation() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        refresh()
                        scope.launch { showFeedback(e.message ?: "操作失败", warning = true) }
                    }
                    finally { operationBusy = false }
                }
            }
            fun afterWrite(result: JSONObject) {
                refresh()
                scope.launch { showWriteFeedback(result) }
            }
            fun editCssSource(entry: Entry) {
                perform {
                    val data = call("cssSource", entry.path)
                    val expected = data.getString("sourceHash")
                    prompt = textPrompt("CSS 源码 · ${entry.name}", data.getString("text"),
                        allowEmpty = true, sourceMode = true,
                        note = "编辑整个文件；保留编码和 BOM，保存前检查文件版本与括号结构。") { next ->
                        afterWrite(call("saveCssSource", entry.path, expected, next))
                    }
                }
            }
            fun editCssValue(entry: Entry, rule: JSONObject, row: JSONObject, hash: String) {
                if (!row.optBoolean("editable")) {
                    scope.launch { snackbar.showSnackbar("此属性值过长，不能在弹窗中安全编辑") }
                    return
                }
                prompt = Prompt(
                    title = "${row.optString("summary")} · ${row.optString("name")}",
                    value = row.optString("rawValue"),
                    note = "选择器：${rule.optString("selector")}",
                ) { next ->
                    afterWrite(call("saveCssValue", entry.path, hash, rule.getInt("id").toString(),
                        row.getInt("id").toString(), next))
                }
            }
            fun editCssSelector(entry: Entry, rule: JSONObject, hash: String) {
                if (!rule.optBoolean("editableSelector")) {
                    scope.launch { snackbar.showSnackbar("选择器过长，请使用源码编辑") }
                    return
                }
                prompt = textPrompt("CSS 选择器", rule.getString("selector"),
                    note = rule.optString("context").takeIf { it.isNotBlank() }?.let { "作用域：$it" }) { next ->
                    afterWrite(call("saveCssSelector", entry.path, hash, rule.getInt("id").toString(), next))
                }
            }
            fun select(entry: Entry) {
                if (busy) return

                if (!StructureFileOpenPolicy.canOpen(entry.name, entry.kind, entry.native)) {
                    showUnsupportedFile()
                    return
                }
                unsupportedToast?.cancel()
                revealedPath = null
                if (entry.native) {

                    current = StructureEditorContext.previewBehindEditor(current, files.firstOrNull { it.path == current }?.kind)
                    openScripta(entry.path, false)
                } else {
                    current = entry.path
                }

                scope.launch { drawer.close() }
            }
            fun locate(entry: Entry) {
                if (busy) return
                if (StructureFileOpenPolicy.canOpen(entry.name, entry.kind, entry.native)) {
                    select(entry)
                    return
                }

                current = null
                collapsed[entry.group] = false
                revealedPath = entry.path
                scope.launch {
                    drawer.close()
                    withFrameNanos { }
                    var index = 0
                    for ((group, entries) in groupedFiles) {
                        if (group == entry.group) {
                            index += 1 + entries.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                            break
                        }
                        index += 1 + if (collapsed[group] == true) 0 else entries.size
                    }
                    treeScroll.animateScrollToItem(index)
                    showUnsupportedFile()
                }
            }
            LaunchedEffect(homeRequest) {
                if (homeRequest > 0) { current = null; fileSearchVisible = false; prompt = null }
            }
            LaunchedEffect(requestedPath, loading, busy) {
                if (loading || busy) return@LaunchedEffect
                val path = requestedPath ?: return@LaunchedEffect
                requestedPath = null
                val entry = files.firstOrNull { it.path == path }
                if (entry != null) locate(entry)
                else showFeedback("文件已不存在：$path", warning = true)
            }
            fun requestNew(group: String) {
                prompt = textPrompt("新建文件", "") { name -> afterWrite(call("createTextFile", group, name)) }
            }
            LaunchedEffect(revision) {
                loading = files.isEmpty()
                metadataLoading = true
                val loadRevision = revision
                val started = android.os.SystemClock.elapsedRealtime()
                try {
                    val (data, loadedFiles) = withContext(Dispatchers.IO) {
                        val snapshot = call("initialData")
                        snapshot to entries(snapshot.getJSONArray("files"))
                    }
                    files = loadedFiles
                    chapterTitles = emptyMap()
                    availableLabels = loadedFiles.associate { it.path to StructureFileSearch.contentNames(it.name) }
                    availablePaths = loadedFiles.map { it.path }
                    displayBookTitle = data.optString("title").ifBlank { bookTitle }
                    val valid = files.map { it.path }.toSet()
                    opened.removeAll { it !in valid }
                    navigationScrollStates.keys.retainAll(valid)
                    if (current !in valid) current = null
                    failure = null
                    loading = false
                    com.reamicro.fix.xposed.XposedBridge.log(
                        "ReaMicro structure listing count=${files.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}",
                    )
                    try {
                        val details = call("initialMetadata")
                        metadata = details.optJSONObject("metadata") ?: JSONObject()
                        displayBookTitle = details.optString("title").ifBlank { displayBookTitle }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        com.reamicro.fix.xposed.XposedBridge.log("ReaMicro structure metadata deferred failure: ${e.message}")
                        metadataLoading = false
                        snackbar.showSnackbar("文件列表已加载，元数据读取失败：${e.message.orEmpty()}")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: "读取失败" }
                finally {
                    if (revision == loadRevision) { loading = false; metadataLoading = false }
                }
            }
            LaunchedEffect(files, revision) {
                if (files.isEmpty()) return@LaunchedEffect
                try {
                    val inventory = files
                    chapterTitles = withContext(Dispatchers.IO) {
                        val context = coroutineContext
                        val toc = EpubChapterNames.toc(root, inventory.map { it.path }) { context.ensureActive() }
                        inventory.filter { it.native }.associate { entry ->
                            context.ensureActive()

                            val fallback = if (toc[entry.path].orEmpty().isNotEmpty()) "" else runCatching {
                                EpubChapterTitlePreview.read(EpubTextFiles.resolve(root, entry.path)) { context.ensureActive() }
                            }.getOrElse { if (it is CancellationException) throw it else "" }
                            entry.path to (toc[entry.path].orEmpty() + listOf(fallback).filter { it.isNotBlank() }).distinct()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    com.reamicro.fix.xposed.XposedBridge.log("ReaMicro chapter label indexing failed: ${e.message}")
                }
            }
            LaunchedEffect(files, chapterTitles, metadata) {
                availableLabels = files.associate { entry -> entry.path to StructureFileSearch.contentNames(
                    entry.name, cover = entry.path == metadata.optString("coverPath"),
                    banner = entry.path == metadata.optString("bannerPath")) + chapterTitles[entry.path].orEmpty() }
            }
            LaunchedEffect(current, revision, files, loading) {
                val generation = ++previewGeneration
                previewLoading = false
                preparedPreview = null
                val pathAtLoad = current
                if (pathAtLoad == null) {
                    return@LaunchedEffect
                }
                val target = files.firstOrNull { it.path == pathAtLoad } ?: return@LaunchedEffect
                if (loading || target.native) return@LaunchedEffect
                previewLoading = target.name.endsWith(".opf", true) || target.kind == "css" || target.kind == "navigation"
                try {
                    val data = when {
                        target.name.endsWith(".opf", true) -> structurePreviewResult {
                            call("getMetadata", pathAtLoad).getJSONObject("metadata")
                        }
                        target.kind == "css" -> structurePreviewResult { call("cssRules", pathAtLoad) }
                        target.kind == "navigation" -> structurePreviewResult { call("navigation", pathAtLoad) }
                        else -> null
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (current != pathAtLoad) return@LaunchedEffect
                    preparedPreview = PreparedPreview(target, revision, data)
                    StructureOpenedFiles.record(opened, pathAtLoad)
                } finally { if (generation == previewGeneration) previewLoading = false }
            }
            SideEffect {
                back = {
                    when {
                        busy -> true
                        fileSearchVisible -> { fileSearchVisible = false; true }
                        prompt != null -> { prompt = null; true }
                        confirmDelete -> { confirmDelete = false; true }
                        sheet != null -> { sheet = null; true }
                        drawer.isOpen -> { scope.launch { drawer.close() }; true }
                        current != null -> { current = null; true }
                        else -> false
                    }
                }
            }
            LaunchedEffect(opened.size) { if (opened.isEmpty()) drawer.close() }
                Scaffold(
                    containerColor = pageColor,
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        StructureToolbar(
                            title = displayBookTitle, subtitle = file?.path,
                            titleStyle = StructureHome130Style.title(hostFonts),
                            subtitleStyle = StructureHome130Style.caption(hostFonts),
                            titleColor = palette.text, subtitleColor = palette.caption,
                            modifier = Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets),
                            navigationIcon = {
                                IconButton(enabled = !busy, onClick = { if (current != null) current = null else close() }) {
                                    Icon(StructureHome130Icons.Back, "返回")
                                }
                            },
                            actions = {
                                IconButton(enabled = !busy && !loading && prompt == null && !fileSearchVisible,
                                    onClick = { fileSearchVisible = true }) {
                                    Icon(painterResource(R.drawable.ic_structure_search),
                                        stringResource(R.string.epub_structure_search_title),
                                        Modifier.size(24.dp), tint = palette.text)
                                }
                                if (file != null) IconButton(enabled = !busy, onClick = { prompt = null; current = null }) {
                                    Icon(painterResource(R.drawable.ic_structure_home), "返回图书结构", Modifier.size(24.dp), tint = palette.text)
                                }
                                if (opened.isNotEmpty()) IconButton(enabled = !busy && prompt == null, onClick = { scope.launch { drawer.open() } }) {
                                    StructureOpenedFilesBadge(opened.size, hostFonts, palette.native.role("OnSurface"), palette.page)
                                }

                            },
                        )
                    },
                ) { padding ->

                    val contentPadding = PaddingValues(top = padding.calculateTopPadding())
                    Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
                        .background(palette.content)) {

                    HorizontalDivider(thickness = .8.dp, color = palette.divider)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                    ModalNavigationDrawer(
                        modifier = Modifier.fillMaxSize(),
                        drawerState = drawer, gesturesEnabled = opened.isNotEmpty(),
                        drawerContent = {
                            OpenedFilesContent(
                                onOpen = { path -> files.firstOrNull { it.path == path }?.let(::select) },
                                onClose = ::closeOpened,
                                onLongClick = { path -> sheet = files.firstOrNull { it.path == path } },
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                        when {
                            loading -> StructureDataLoading(Modifier.align(Alignment.Center))
                            failure != null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(failure!!, color = palette.caption)
                                TextButton(onClick = { refresh() }) { Text("重试") }
                            }
                            else -> {
                                if (current == null || preparedPreview == null) LazyColumn(state = treeScroll, modifier = Modifier.fillMaxSize()) {
                                    if (files.isEmpty()) item { Text("没有文件", Modifier.padding(32.dp), color = palette.caption) }
                                    groupedFiles.forEach { (group, list) ->
                                        item(key = "folder:$group", contentType = "folder") {
                                            FolderRow(
                                                group = group,
                                                collapsed = collapsed[group] == true,
                                                enabled = !busy,
                                                onToggle = { collapsed[group] = !(collapsed[group] ?: false) },
                                                onImport = { perform { call("pickFile", group) } },
                                                onNew = { requestNew(group) }, modifier = structureItemAnimation(),
                                            )
                                        }
                                        if (collapsed[group] != true) itemsIndexed(
                                            list, key = { _, entry -> "file:${entry.path}" },
                                            contentType = { _, _ -> "file" },
                                        ) { index, entry ->
                                            FileRow(
                                                entry, entry.path == metadata.optString("coverPath"), entry.path == metadata.optString("bannerPath"),
                                                showDivider = index < list.lastIndex,
                                                onClick = { select(entry) },
                                                onLongClick = { sheet = entry },
                                                modifier = structureItemAnimation().then(
                                                    if (entry.path == revealedPath) Modifier.background(palette.primarySoft)
                                                    else Modifier),
                                            )
                                        }
                                    }

                                    item(key = "tree-bottom") { Spacer(Modifier.navigationBarsPadding()) }
                                }
                            }
                        }
                        StructureFileTransition(
                            target = preparedPreview.takeIf { current != null && failure == null },
                            modifier = Modifier.fillMaxSize(),
                            contentKey = { it.entry.path },
                        ) { scene ->
                            val file = scene.entry
                            Box(Modifier.fillMaxSize().background(palette.content)
                                .pointerInput(file.path) { detectTapGestures { } }) {
                            when {
                            file.kind == "image" -> ImagePage(file, scene.revision)
                            file.kind == "font" -> FontPage(file, scene.revision, previewSamples, !busy) { index ->
                                val (label, value) = previewSamples[index]
                                prompt = textPrompt("编辑字体预览 · $label", value, allowEmpty = true,
                                    note = "仅修改本次预览文字，不会修改字体文件。切换字体后可继续对比，关闭图书结构后恢复默认。",
                                ) { next ->
                                    previewSamples = FontPreviewSamples.edit(previewSamples, index, next)
                                    scope.launch { showFeedback("预览文字已更新，字体文件未修改") }
                                }
                            }
                            file.kind == "navigation" -> NavigationPage(file, scene.data,
                                editSource = { openScripta(file.path, false) },
                                openChapter = { path -> files.firstOrNull { it.path == path }?.let(::select) })
                            file.name.endsWith(".opf", true) -> MetadataPage(file, scene.data) { key, label, value ->
                                prompt = textPrompt(label, value, allowEmpty = EpubMetadataFields.allowsEmpty(key)) { next ->
                                    afterWrite(call("saveMetadataField", file.path, key, next, value))
                                }
                            }
                            file.kind == "css" -> CssPage(file, scene.data,
                                editValue = { rule, row, hash -> editCssValue(file, rule, row, hash) },
                                editSelector = { rule, hash -> editCssSelector(file, rule, hash) },
                                editSource = { editCssSource(file) })

                            else -> LaunchedEffect(file.path) {
                                closeOpened(file.path)
                                showUnsupportedFile()
                            }
                        }
                            }
                        }
                        if ((metadataLoading || previewLoading) && !loading && file != null) LinearProgressIndicator(
                            Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        )
                        if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)),
                            contentAlignment = Alignment.Center) { StructureDataLoading() }

                        }
                    }
                        val editorModel = prompt?.let { editing ->
                            remember(editing) {
                                StructureEditorModel(editing, editing.title, editing.value, editing.allowEmpty,
                                    editing.sourceMode, editing.note)
                            }
                        }
                        StructureEditorOverlay(editorModel, palette, busy, promptError,
                            onDismiss = { if (!busy) prompt = null },
                            onEdited = { promptError = null },
                            onSave = { value ->
                                val editing = prompt
                                if (editing != null && !busy) {
                                    operationBusy = true
                                    scope.launch {
                                        try {
                                            editing.submit(value)
                                            if (prompt === editing) prompt = null
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) {
                                            promptError = e.message ?: "保存失败"
                                            refresh()
                                        } finally { operationBusy = false }
                                    }
                                }
                            })
                        StructureFileSearchOverlay(
                            visible = fileSearchVisible, paths = availablePaths, labels = availableLabels, palette = palette, busy = busy,
                            onDismiss = { if (!busy) fileSearchVisible = false },
                            onSelect = { path ->
                                if (!busy) {
                                    fileSearchVisible = false
                                    files.firstOrNull { it.path == path }?.let { locate(it) }
                                }
                            },
                        )
                    }
                    }
                }

            sheet?.let { selected ->
                ModalBottomSheet(
                    onDismissRequest = { if (!busy) sheet = null },
                    containerColor = palette.content,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    contentWindowInsets = { BottomSheetDefaults.windowInsets.exclude(WindowInsets.systemBars) },
                    dragHandle = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Spacer(Modifier.padding(top = 14.dp, bottom = 16.dp).size(40.dp, 4.dp)
                                .clip(RoundedCornerShape(1.dp)).background(palette.surfaceHighest))
                        }
                    },
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 8.dp)
                        .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            EntryIcon(selected, Modifier.size(30.dp, 36.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 2.dp)) {
                                StructureTruncatedText(selected.name.substringBeforeLast('.', selected.name),
                                    StructureHome130Style.fileName(hostFonts), palette.text, Modifier.fillMaxWidth())
                                EntrySummary(selected, selected.path == metadata.optString("coverPath"), selected.path == metadata.optString("bannerPath"))
                            }
                        }

                        FileActionCard {
                            SheetAction("重命名", StructureIcons.DriveFileRenameOutline, !busy) {
                                sheet = null
                                prompt = textPrompt("重命名", selected.name) { next ->
                                    val result = call("renameFile", selected.path, next)
                                    val renamed = result.getString("path")
                                    val remap = result.optJSONObject("renamedPaths") ?: JSONObject().put(selected.path, renamed)
                                    val mapped = opened.map { remap.optString(it, it) }.distinct()
                                    opened.clear(); opened.addAll(mapped)
                                    current = current?.let { remap.optString(it, it) }
                                    for (old in remap.keys()) navigationScrollStates.remove(old)?.let { state ->
                                        navigationScrollStates[remap.getString(old)] = state
                                    }
                                    afterWrite(result)
                                }
                            }
                            if (selected.kind == "image") {
                                StructureBookDetailDivider(activity.classLoader, palette)
                                SheetAction("设置封面", StructureHome130Icons.ImageOutline, !busy) {
                                    sheet = null
                                    perform { afterWrite(call("setCover", selected.path)) }
                                }
                                StructureBookDetailDivider(activity.classLoader, palette)
                                SheetAction("设置横幅", StructureIcons.Panorama, !busy) {
                                    sheet = null
                                    perform { afterWrite(call("setBanner", selected.path)) }
                                }
                            }
                        }

                        AnimatedContent(targetState = confirmDelete, label = "confirm") { confirmed ->
                            FileActionCard(confirmed = confirmed) {
                                SheetAction(if (confirmed) "确定删除吗？" else "删除文件",
                                    if (confirmed) StructureHome130Icons.DeleteForever else StructureHome130Icons.DeleteOutline,
                                    !busy, destructive = true, confirmed = confirmed, chevron = !confirmed) {
                                    if (!confirmed) confirmDelete = true
                                    else perform {
                                        val result = call("deleteFile", selected.path)
                                        closeOpened(selected.path)
                                        sheet = null
                                        afterWrite(result)
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    @Composable private fun FolderRow(
        group: String, collapsed: Boolean, enabled: Boolean,
        onToggle: () -> Unit, onImport: () -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier,
    ) {
        val canImport = remember(group, revision) {
            runCatching { File(root, group).isDirectory }.getOrDefault(false)
        }
        Column(modifier.fillMaxWidth().background(palette.folder)) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    enabled = enabled, onClick = onToggle, onLongClick = onNew,
                    onLongClickLabel = "在此目录新建文件",
                ).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StructureTruncatedText(
                    group.ifBlank { "EPUB-DIR" }.uppercase(Locale.ROOT),
                    StructureHome130Style.caption(hostFonts), palette.caption,
                    Modifier.weight(1f),
                )
                if (canImport) Icon(
                    StructureHome130Icons.Import, "导入到此目录",
                    Modifier.padding(end = 8.dp).clip(CircleShape).size(16.dp).clickable(
                        enabled = enabled, role = Role.Button, onClickLabel = "导入到此目录",
                        onClick = onImport,
                    ),
                    tint = palette.icon,
                )
                Icon(
                    if (collapsed) StructureHome130Icons.Collapsed else StructureHome130Icons.Expanded,
                    if (collapsed) "展开目录" else "折叠目录", Modifier.size(16.dp),
                    tint = palette.icon,
                )
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .8.dp, color = palette.divider)
        }
    }

    @Composable private fun FileRow(
        file: Entry, cover: Boolean, banner: Boolean, showDivider: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            Row(Modifier.fillMaxWidth().padding(start = 32.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.Top) {
                EntryIcon(file, Modifier.padding(horizontal = 16.dp, vertical = 14.dp).size(30.dp, 36.dp))
                Column(Modifier.weight(1f).padding(top = 16.dp, bottom = 16.dp, end = 16.dp)) {
                    StructureTruncatedText(file.name.substringBeforeLast('.', file.name),
                        StructureHome130Style.fileName(hostFonts), palette.text, Modifier.fillMaxWidth())
                    EntrySummary(file, cover, banner)
                }

            }
            if (showDivider) HorizontalDivider(Modifier.padding(start = 94.dp), thickness = .8.dp, color = palette.divider)
        }
    }
    private fun LazyItemScope.structureItemAnimation(): Modifier = Modifier.animateItem(
        fadeInSpec = spring(stiffness = 400f),
        placementSpec = spring(stiffness = 400f, visibilityThreshold = IntOffset(1, 1)),
        fadeOutSpec = spring(stiffness = 400f),
    )

    @Composable private fun EntryIcon(file: Entry, modifier: Modifier) {
        val order = EpubStorageFiles.structure130TypeOrder(file.name)
        Box(modifier.clip(RoundedCornerShape(3.dp))) {
            if (order == 8) Thumbnail(file)
            else {
                val drawable = when (order) {
                    1 -> R.drawable.ic_file_opf
                    2 -> R.drawable.ic_file_ncx
                    3 -> R.drawable.ic_file_xml
                    4 -> R.drawable.ic_file_css
                    7 -> R.drawable.ic_file_bookmark
                    9 -> R.drawable.ic_file_html
                    10 -> R.drawable.ic_file_font
                    else -> R.drawable.ic_file_xml
                }
                Image(painterResource(drawable), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }

    @Composable private fun EntrySummary(file: Entry, cover: Boolean = false, banner: Boolean = false) {
        val order = EpubStorageFiles.structure130TypeOrder(file.name)
        val dimensions by produceState("", file.path, revision) {
            value = if (order != 8) "" else withContext(Dispatchers.IO) { runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(EpubTextFiles.resolve(root, file.path).path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) "${bounds.outWidth} x ${bounds.outHeight}" else ""
            }.getOrDefault("") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val contentName = StructureFileLabels.name(file.name, cover, banner)
            val chapterName = chapterTitles[file.path].orEmpty().firstOrNull().orEmpty()
            Text(if (file.kind == "navigation") "目录结构" else contentName,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    cover && order == 8 -> palette.primary
                    banner && order == 8 -> palette.native.role("Tertiary").takeIf { it != palette.primary }
                        ?: (if (palette.dark) Color(0xFFFFCC80) else Color(0xFF9C5700)).let { if (it == palette.primary) Color(0xFF00897B) else it }
                    else -> palette.caption
                })
            if (file.native && chapterName.isNotBlank()) StructureTruncatedText(chapterName,
                MaterialTheme.typography.bodySmall, palette.caption, Modifier.weight(1f, fill = false))
            if (dimensions.isNotBlank()) Text(dimensions,
                style = MaterialTheme.typography.bodySmall, color = palette.caption)
            if (order in setOf(8, 9, 10)) Text(file.size,
                style = MaterialTheme.typography.bodySmall, color = palette.caption)
        }
    }

    fun closeOpened(path: String) {
        opened.remove(path)
        if (current == path) current = opened.lastOrNull { candidate ->
            files.any { it.path == candidate && !it.native }
        }
    }

    @Composable fun OpenedFilesContent(
        onOpen: (String) -> Unit, onClose: (String) -> Unit,
        onLongClick: ((String) -> Unit)? = null,
    ) {
        LazyColumn(Modifier.fillMaxWidth(.67f).fillMaxHeight().background(palette.content)) {
            items(opened.toList(), key = { it }) { path ->
                val entry = files.firstOrNull { it.path == path }
                if (entry != null) OpenedFileRow(entry, modifier = structureItemAnimation(),
                    onOpen = { onOpen(path) }, onClose = { onClose(path) },
                    onLongClick = onLongClick?.let { action -> { action(path) } })
            }
            item(key = "opened-bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    @Composable private fun OpenedFileRow(
        file: Entry, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit, onLongClick: (() -> Unit)?,
    ) {
        Column(modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(file, Modifier.size(20.dp, 24.dp))
                StructureTruncatedText(file.name.substringBeforeLast('.', file.name),
                    StructureHome130Style.drawerName(hostFonts), palette.native.role("OnSurface"),
                    Modifier.weight(1f).padding(horizontal = 16.dp))
                IconButton(onClick = onClose) {
                    Icon(StructureHome130Icons.Close, "关闭文件", Modifier.size(20.dp), tint = palette.text)
                }
            }
            HorizontalDivider(Modifier.padding(start = 52.dp), thickness = .8.dp, color = palette.divider)
        }
    }

    @Composable private fun SheetAction(
        label: String, icon: ImageVector, enabled: Boolean,
        destructive: Boolean = false, confirmed: Boolean = false, chevron: Boolean = true, onClick: () -> Unit,
    ) {
        val foreground = if (confirmed) palette.native.role("OnError") else if (destructive) palette.error else palette.text
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(start = 18.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.padding(end = 16.dp).size(20.dp), tint = foreground)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = foreground)
            if (chevron) Icon(StructureHome130Icons.NavigateNext, null,
                Modifier.padding(top = 2.dp).size(24.dp), tint = palette.surfaceHighest)
        }
    }

    @Composable private fun FileActionCard(confirmed: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (confirmed) palette.error else palette.page)
            .border(.8.dp, if (confirmed) palette.error else palette.borderVariant, RoundedCornerShape(8.dp)),
            content = content)
    }

    @Composable private fun Thumbnail(file: Entry) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, revision) {
            value = null
            value = withContext(Dispatchers.IO) { runCatching { decodeImage(file, 128) }.getOrNull() }
        }
        bitmap?.let { Image(it.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    @Composable private fun ImagePage(file: Entry, revision: Int) {
        val decoded by produceState<Result<android.graphics.Bitmap>?>(null, file.path, revision) {
            value = null
            value = withContext(Dispatchers.IO) { runCatching { decodeImage(file, 2048) } }
        }
        val bitmap = decoded?.getOrNull()
        var scale by remember(file.path) { mutableFloatStateOf(1f) }
        var offset by remember(file.path) { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(0.dp))
            .pointerInput(file.path) { detectTapGestures(onDoubleTap = {
                scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero
            }) }.pointerInput(file.path) { detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            } }, contentAlignment = Alignment.Center) {
            bitmap?.let { b ->
                Image(b.asImageBitmap(), file.name, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x.coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                    translationY = offset.y.coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2)
                }, contentScale = ContentScale.Fit)
            } ?: if (decoded == null) StructureDataLoading() else Text(
                decoded?.exceptionOrNull()?.message ?: "无法显示图片",
                Modifier.padding(24.dp), color = palette.error,
            )
        }
    }
    @Composable private fun FontPage(
        file: Entry, revision: Int, samples: List<Pair<String, String>>,
        enabled: Boolean, editText: (Int) -> Unit,
    ) {

        var loaded by remember(file.path, revision) { mutableStateOf<Result<FontFamily>?>(null) }
        LaunchedEffect(file.path, revision) {
            loaded = withContext(Dispatchers.IO) { runCatching {
                EpubTextFiles.resolve(root, file.path).inputStream().use { input ->

                    FontFamily(Typeface.Builder(input.fd).build()
                        ?: error("字体格式不支持或文件已损坏"))
                }
            } }
        }
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
            return
        }
        if (loaded!!.isFailure) {
            Text(loaded!!.exceptionOrNull()?.message ?: "无法加载字体", Modifier.padding(24.dp),
                color = palette.error)
            return
        }
        val family = loaded!!.getOrThrow()
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(samples) { index, (label, sample) ->
                Column(Modifier.fillMaxWidth().clickable(
                    enabled = enabled, role = Role.Button, onClickLabel = "编辑字体预览文本",
                    onClick = { editText(index) },
                ).padding(16.dp)) {
                    Text(label, color = palette.caption, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = .8.dp, color = palette.divider)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("EX$index. $sample", Modifier.weight(1f), fontFamily = family,
                            style = MaterialTheme.typography.titleSmall, color = palette.text)
                        Icon(StructureHome130Icons.Edit2, "编辑字体预览文本",
                            Modifier.padding(start = 16.dp, top = 2.dp).size(16.dp), tint = palette.icon)
                    }
                }
            }
            item(key = "font-bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    private fun decodeImage(file: Entry, maxEdge: Int): android.graphics.Bitmap {
        val resolved = EpubTextFiles.resolve(root, file.path)
        if (resolved.extension.equals("svg", true)) {
            return com.reamicro.fix.epub.editor.hostSvgPreview(activity.classLoader, resolved, maxEdge)
        }
        val path = resolved.path
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, size)
        val sample = epubPreviewSampleSize(size.outWidth, size.outHeight, maxEdge,
            maxEdge.toLong() * maxEdge)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败")
    }
    @Composable private fun NavigationPage(
        file: Entry, result: Result<JSONObject>?, editSource: () -> Unit, openChapter: (String) -> Unit,
    ) {
        val rows = remember(result) {
            result?.getOrNull()?.optJSONArray("rows")?.let { array ->
                (0 until array.length()).map { index -> index to array.getJSONObject(index) }
            }.orEmpty()
        }
        val hostScale = StructureBookDetailDividerStyle.scale(activity.classLoader)
        val navigationScroll = remember(file.path) {
            navigationScrollStates.getOrPut(file.path) { androidx.compose.foundation.lazy.LazyListState() }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            StructureSectionHeader("目录 · ${rows.size}", palette.text) {
                IconButton(onClick = editSource) {
                    Icon(painterResource(R.drawable.ic_structure_host_edit), "编辑目录源文件",
                        Modifier.size((20f * hostScale).dp), tint = if (palette.dark) palette.text else Color.Black)
                }
            }
            val error = result?.exceptionOrNull()
            if (error != null) Text(error.message ?: "目录读取失败，可打开源码修复", Modifier.padding(16.dp), color = palette.error)
            else if (result == null) StructureDataLoading()
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = navigationScroll,
                contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (rows.isEmpty()) item { Text("目录为空，可通过源码编辑",
                    Modifier.padding(vertical = 16.dp), color = palette.caption) }
                items(rows, key = { it.first }) { (_, row) ->
                    val target = row.optString("path")
                    StructureContentCard(palette, onClick = { openChapter(target) }, enabled = target.isNotBlank()) {
                        StructureTruncatedText(row.optString("href").ifBlank { "[无章节链接]" },
                            MaterialTheme.typography.bodySmall, palette.caption, Modifier.fillMaxWidth())
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.dp, color = palette.borderVariant)
                        Text(row.optString("title"), style = structureChapterTextStyle(),
                            color = palette.text)
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    @Composable private fun MetadataPage(file: Entry, result: Result<JSONObject>?, edit: (String, String, String) -> Unit) {
        val data = result?.getOrNull()
        val error = result?.exceptionOrNull()?.let { it.message?.takeIf(String::isNotBlank) ?: "读取失败" }
        if (error != null) Text(error!!, Modifier.padding(16.dp), color = palette.error)
        else if (data == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "metadata-top") { Spacer(Modifier) }
            items(metadataFields, key = { it.first }) { (key, label) ->
                val value = data!!.optString(key)
                if (key == "uuid") MetadataIdentifierCard(data!!.optJSONObject("uniqueIdentifier") ?: JSONObject())
                else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { edit(key, label, value) }
                    .background(palette.page)
                    .border(.5.dp, palette.borderHigh, RoundedCornerShape(8.dp))
                    .padding(16.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = palette.caption)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.dp, color = palette.borderVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(value.ifBlank { "[未设置]" }, Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                            color = if (value.isBlank()) palette.caption else palette.text)
                        Icon(StructureHome130Icons.Edit2, "编辑$label",
                            Modifier.padding(start = 16.dp, top = 2.dp).size(16.dp), tint = palette.icon)
                    }
                }
            }
            item(key = "metadata-bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    @Composable private fun MetadataIdentifierCard(identifier: JSONObject) {
        val value = identifier.optString("value")
        val error = identifier.optString("error")
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(palette.page)
            .border(.5.dp, palette.borderHigh, RoundedCornerShape(8.dp))
            .padding(16.dp)) {
            Text("标识 dc:identifier",
                style = MaterialTheme.typography.bodySmall, color = palette.caption)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.dp, color = palette.borderVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                StructureTruncatedText(value.ifBlank { if (error.isNotBlank()) "读取失败" else "[未设置]" },
                    MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                    if (value.isBlank()) palette.caption else palette.text, Modifier.weight(1f))
                Box(Modifier.padding(start = 16.dp, top = 2.dp).size(16.dp)
                    .clickable(enabled = value.isNotBlank() && error.isBlank(), role = androidx.compose.ui.semantics.Role.Button) {
                    val copied = runCatching {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                        clipboard.setPrimaryClip(ClipData.newPlainText("标识", value))
                    }
                    scope.launch {
                        showFeedback(if (copied.isSuccess) "已复制标识" else "复制失败，请重试")
                    }
                }) {
                    Icon(StructureHome130Icons.Copy, "复制标识", Modifier.size(16.dp),
                        tint = if (value.isBlank() || error.isNotBlank()) palette.caption else palette.icon)
                }
            }
        }
    }
    @Composable private fun CssPage(
        file: Entry, result: Result<JSONObject>?,
        editValue: (JSONObject, JSONObject, String) -> Unit,
        editSelector: (JSONObject, String) -> Unit,
        editSource: () -> Unit,
    ) {
        val data = result?.getOrNull()
        val error = result?.exceptionOrNull()?.let { it.message?.takeIf(String::isNotBlank) ?: "读取失败" }
        if (error != null) Column(Modifier.padding(16.dp)) {
            Text(error!!, color = palette.error)
            TextButton(onClick = editSource) { Text("编辑 CSS 源码") }
        }
        else if (data == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
        else {
            val rules = data!!.getJSONArray("rules")
            val sourceHash = data!!.getString("sourceHash")
            LazyColumn(Modifier.fillMaxSize()) {
                if (rules.length() == 0) item {
                    Column(Modifier.padding(16.dp)) {
                        Text("没有可显示的样式规则", color = palette.caption)
                        TextButton(onClick = editSource) { Text("编辑 CSS 源码") }
                    }
                }
                items(rules.length()) { index ->
                    val rule = rules.getJSONObject(index)
                    val rows = rule.getJSONArray("rows")
                    Column(Modifier.padding(16.dp).fillMaxWidth()
                        .border(StructureContentStyle.dividerWidth, palette.surfaceHighest, RoundedCornerShape(StructureContentStyle.corner))) {
                        Column(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { editSelector(rule, sourceHash) }, onLongClick = editSource,
                            onLongClickLabel = "编辑整个 CSS 文件",
                        ).padding(16.dp)) {
                            if (rule.optString("context").isNotBlank()) Text(rule.optString("context"),
                                style = MaterialTheme.typography.bodySmall, color = palette.caption,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(rule.optString("selector"), Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium, color = Color(0xFFB8860B),
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Icon(StructureHome130Icons.Edit2, "编辑选择器",
                                    Modifier.padding(start = 16.dp, top = 2.dp).size(16.dp), tint = palette.icon)
                            }
                            if (rule.optString("comment").isNotBlank()) Text(rule.optString("comment"),
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                                color = palette.caption, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(thickness = .8.dp, color = palette.divider)
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (rows.length() == 0) Text("此规则没有声明，可长按选择器编辑源码",
                                style = MaterialTheme.typography.bodySmall, color = palette.caption)
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                    .clickable { editValue(rule, row, sourceHash) }
                                    .padding(vertical = 4.dp)) {
                                    Text(row.optString("summary"), style = MaterialTheme.typography.bodySmall, color = palette.caption)
                                    if (row.optString("comment").isNotBlank()) Text(row.optString("comment"),
                                        style = MaterialTheme.typography.bodySmall, color = palette.caption,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Text(row.optString("name"), Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                                            color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(row.optString("value") + if (row.optBoolean("important")) " !important" else "",
                                            Modifier.weight(1.2f).padding(horizontal = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium, color = palette.text,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Icon(StructureHome130Icons.Edit2, "编辑 ${row.optString("name")}",
                                            Modifier.padding(top = 2.dp).size(16.dp), tint = palette.icon)
                                    }
                                    if (row.optString("computedValue").isNotBlank()) Text(
                                        "解析值：${row.optString("computedValue")}",
                                        style = MaterialTheme.typography.bodySmall, color = palette.caption,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item(key = "css-bottom") { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
    private companion object {
        val metadataFields = EpubMetadataFields.all.map { it.key to it.label }
    }
}

@Composable internal fun StructureDataLoading(modifier: Modifier = Modifier) {
    val palette = StructureHome130Style.palette(LocalContext.current,
        LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
    CircularProgressIndicator(
        modifier = modifier,
        color = palette.primary,
        trackColor = palette.divider,
        strokeWidth = 2.dp,
    )
}
