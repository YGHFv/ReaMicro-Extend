package com.reamicro.fix.hook

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.reamicro.fix.R
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.epub.editor.EpubChapterTitlePreview
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.epubPreviewSampleSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Native, module-owned port of host 1.3 BookManager's navigation/view structure.
 * X4/o: grouped files + .67 drawer; Y4/j: opened-file identity/order; X4/p/v: viewers.
 * The old WebView remains only for advanced source editing/global find-replace.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class EpubStructureView(
    private val activity: Activity,
    private val root: File,
    private val request: (String, Array<out String?>) -> String,
    private val openScripta: (String) -> Unit,
    private val openLegacy: (String?, Boolean) -> Unit,
    private val close: () -> Unit,
    private val uiFontFileProvider: () -> File? = { null },
) {
    private val owner = ModuleComposeOwner(activity, close)
    private var revision by mutableIntStateOf(0)
    private var colors by mutableStateOf(ModuleDialogTheme.palette(activity))
    private var back: (() -> Boolean)? = null
    private var view: ComposeView? = null
    // IO-only access under a cancellable mutex: no title-scan burst during fast scrolling.
    private val titleReadMutex = Mutex()
    private val chapterTitleCache = object : LinkedHashMap<Pair<Int, String>, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, String>, String>?): Boolean = size > 256
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
        // WindowRecomposer is installed on the window content root, not only on ComposeView.
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
    fun updateTheme() { colors = ModuleDialogTheme.palette(activity) }
    fun handleBack(): Boolean = back?.invoke() ?: false
    fun dispose() {
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
        val size: String, val native: Boolean, val editable: Boolean,
    )
    private fun entries(array: JSONArray): List<Entry> = (0 until array.length()).map { index ->
        val o = array.getJSONObject(index)
        Entry(o.getString("path"), o.getString("name"), o.optString("groupPath"), o.optString("kind"),
            o.optString("sizeText"), o.optBoolean("nativeEditor"), o.optBoolean("editable"))
    }
    private data class Prompt(val title: String, val value: String, val submit: suspend (String) -> Unit)

    @Composable private fun StructureContent() {
        val p = colors
        val scheme = StructureHostStyle.apply(if (androidx.core.graphics.ColorUtils.calculateLuminance(p.pageBackground) < .5)
            darkColorScheme() else lightColorScheme())
        val themed = scheme.copy(
            background = Color(p.pageBackground), surface = Color(p.pageBackground),
            surfaceContainer = Color(p.rowBackground), surfaceContainerLow = Color(p.rowBackground),
            onSurface = Color(p.title), onSurfaceVariant = Color(p.body),
            outlineVariant = Color(p.border), primary = Color(p.primary), onPrimary = Color.White,
        )
        val uiFont by produceState<FontFamily?>(null, uiFontFileProvider) {
            value = withContext(Dispatchers.IO) { runCatching {
                uiFontFileProvider()?.takeIf { it.isFile }?.let { FontFamily(Typeface.createFromFile(it)) }
            }.getOrNull() }
        }
        val typography = remember(uiFont) {
            Typography().let { original ->
                val family = uiFont ?: FontFamily.Default
                original.copy(
                    displayLarge = original.displayLarge.copy(fontFamily = family),
                    displayMedium = original.displayMedium.copy(fontFamily = family),
                    displaySmall = original.displaySmall.copy(fontFamily = family),
                    headlineLarge = original.headlineLarge.copy(fontFamily = family),
                    headlineMedium = original.headlineMedium.copy(fontFamily = family),
                    headlineSmall = original.headlineSmall.copy(fontFamily = family),
                    titleLarge = original.titleLarge.copy(fontFamily = family),
                    titleMedium = original.titleMedium.copy(fontFamily = family),
                    titleSmall = original.titleSmall.copy(fontFamily = family),
                    bodyLarge = original.bodyLarge.copy(fontFamily = family),
                    bodyMedium = original.bodyMedium.copy(fontFamily = family),
                    bodySmall = original.bodySmall.copy(fontFamily = family),
                    labelLarge = original.labelLarge.copy(fontFamily = family),
                    labelMedium = original.labelMedium.copy(fontFamily = family),
                    labelSmall = original.labelSmall.copy(fontFamily = family),
                )
            }
        }
        MaterialTheme(colorScheme = themed, typography = typography) {
            val scope = rememberCoroutineScope()
            var files by remember { mutableStateOf(emptyList<Entry>()) }
            var metadata by remember { mutableStateOf(JSONObject()) }
            var loading by remember { mutableStateOf(true) }
            var failure by remember { mutableStateOf<String?>(null) }
            var current by remember { mutableStateOf<String?>(null) }
            val opened = remember { mutableStateListOf<String>() }
            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            val drawer = rememberDrawerState(DrawerValue.Closed)
            var sheet by remember { mutableStateOf<Entry?>(null) }
            var prompt by remember { mutableStateOf<Prompt?>(null) }
            var confirmDelete by remember { mutableStateOf<Entry?>(null) }
            var busy by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            val file = files.firstOrNull { it.path == current }
            val snackbar = remember { SnackbarHostState() }
            val treeScroll = rememberLazyListState()
            val groupedFiles = remember(files) { files.groupBy { it.group } }

            fun perform(operation: suspend () -> Unit) {
                if (busy) return
                scope.launch {
                    busy = true
                    try { operation() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作失败") }
                    finally { busy = false }
                }
            }
            fun select(entry: Entry) {
                if (busy) return
                if (entry.path !in opened) opened.add(entry.path)
                if (entry.native) {
                    openScripta(entry.path)
                } else current = entry.path
                scope.launch { drawer.close() }
            }
            fun closeOpened(path: String) {
                opened.remove(path)
                if (current == path) current = opened.lastOrNull()
            }
            fun requestNew(group: String) {
                prompt = Prompt("新建文件", "") { name -> call("createTextFile", group, name); refresh() }
            }
            LaunchedEffect(revision) {
                loading = files.isEmpty()
                try {
                    val (data, loadedFiles) = withContext(Dispatchers.IO) {
                        val snapshot = call("initialData")
                        snapshot to entries(snapshot.getJSONArray("files"))
                    }
                    files = loadedFiles
                    metadata = data.optJSONObject("metadata") ?: JSONObject()
                    val valid = files.map { it.path }.toSet()
                    opened.removeAll { it !in valid }
                    if (current !in valid) current = null
                    failure = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: "读取失败" }
                finally { loading = false }
            }
            SideEffect {
                back = {
                    when {
                        busy -> true
                        prompt != null -> { prompt = null; true }
                        confirmDelete != null -> { confirmDelete = null; true }
                        sheet != null -> { sheet = null; true }
                        drawer.isOpen -> { scope.launch { drawer.close() }; true }
                        current != null -> { current = null; true }
                        else -> false
                    }
                }
            }
            ModalNavigationDrawer(
                drawerState = drawer, gesturesEnabled = opened.isNotEmpty(),
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width((LocalConfiguration.current.screenWidthDp * .67f).dp),
                        drawerContainerColor = Color(p.pageBackground),
                    ) {
                        Text("已打开文件", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                        HorizontalDivider(color = Color(p.border))
                        LazyColumn {
                            item {
                                NavigationDrawerItem(
                                    label = { Text("书籍存储") }, selected = current == null,
                                    onClick = { current = null; scope.launch { drawer.close() } },
                                    icon = { Icon(StructureIcons.Folder, null) },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            items(opened.toList(), key = { it }) { path ->
                                val item = files.firstOrNull { it.path == path }
                                if (item != null) Row(
                                    Modifier.fillMaxWidth().background(if (current == path) Color(p.primarySoft) else Color.Transparent)
                                        .combinedClickable(onClick = { select(item) }, onLongClick = { sheet = item })
                                        .padding(start = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                        Text(item.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold)
                                        Text(item.group.ifBlank { "EPUB-DIR" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = Color(p.body), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { closeOpened(path) }) { Icon(StructureIcons.Close, "关闭文件") }
                                }
                            }
                        }
                    }
                },
            ) {
                Scaffold(
                    containerColor = Color(p.pageBackground),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(file?.name ?: "书籍存储", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (file != null) Text(file.group.ifBlank { "EPUB-DIR" },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = Color(p.body))
                                }
                            },
                            navigationIcon = {
                                IconButton(enabled = !busy, onClick = { if (current != null) current = null else close() }) {
                                    Icon(StructureIcons.ArrowBack, "返回")
                                }
                            },
                            actions = {
                                if (opened.isNotEmpty()) IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Icon(StructureIcons.FolderOpen, "已打开文件")
                                }
                                if (file?.editable == true) IconButton(enabled = !busy, onClick = { openLegacy(file.path, false) }) {
                                    Icon(StructureIcons.Edit, "源码编辑")
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(StructureIcons.MoreVert, "更多") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("刷新") }, onClick = { menu = false; refresh() })
                                        DropdownMenuItem(text = { Text("全书查找替换 · 原编辑器") },
                                            onClick = { menu = false; openLegacy(file?.path, false) })
                                        DropdownMenuItem(text = { Text("完整元数据与封面") },
                                            onClick = { menu = false; openLegacy(null, true) })
                                        if (file != null) DropdownMenuItem(text = { Text("文件操作") },
                                            onClick = { menu = false; sheet = file })
                                        else DropdownMenuItem(text = { Text("新建文件") },
                                            onClick = { menu = false; requestNew("") })
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(p.pageBackground)),
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                        when {
                            loading -> StructureDataLoading(Modifier.align(Alignment.Center))
                            failure != null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(failure!!, color = Color(p.body))
                                TextButton(onClick = { refresh() }) { Text("重试") }
                            }
                            file == null -> {
                                LazyColumn(state = treeScroll, modifier = Modifier.fillMaxSize()) {
                                    if (files.isEmpty()) item { Text("没有文件", Modifier.padding(32.dp), color = Color(p.body)) }
                                    groupedFiles.forEach { (group, list) ->
                                        item(key = "folder:$group") {
                                            Row(Modifier.fillMaxWidth().combinedClickable(
                                                onClick = { collapsed[group] = !(collapsed[group] ?: false) },
                                            ).padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(group.ifBlank { "EPUB-DIR" }.uppercase(Locale.ROOT),
                                                    Modifier.weight(1f), color = Color(p.body),
                                                    style = MaterialTheme.typography.labelMedium)
                                                IconButton(onClick = { perform { call("pickFile", group) } },
                                                    modifier = Modifier.size(32.dp)) { Icon(StructureIcons.FileUpload, "导入到此目录", Modifier.size(16.dp)) }
                                                IconButton(onClick = { requestNew(group) }, modifier = Modifier.size(32.dp)) {
                                                    Icon(StructureIcons.Add, "新建文件", Modifier.size(16.dp))
                                                }
                                                Icon(if (collapsed[group] == true) StructureIcons.ChevronRight else StructureIcons.ExpandMore,
                                                    "展开或折叠", Modifier.size(16.dp), tint = Color(p.body))
                                            }
                                        }
                                        if (collapsed[group] != true) items(list, key = { "file:${it.path}" }) { entry ->
                                            FileRow(entry, entry.path == metadata.optString("coverPath"),
                                                onClick = { select(entry) }, onMore = { sheet = entry })
                                        }
                                    }
                                }
                            }
                            file.kind == "image" -> ImagePage(file, revision)
                            file.kind == "font" -> FontPage(file)
                            file.name.endsWith(".opf", true) -> MetadataPage(file, revision) { key, label, value ->
                                prompt = Prompt(label, value) { next ->
                                    call("saveMetadataField", file.path, key, next, value); refresh()
                                }
                            }
                            file.kind == "css" -> CssPage(file, revision)
                            else -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(file.name, style = MaterialTheme.typography.titleMedium)
                                Text(file.size, color = Color(p.body), modifier = Modifier.padding(8.dp))
                                TextButton(onClick = { if (file.native) openScripta(file.path) else openLegacy(file.path, false) }) {
                                    Text("打开源码编辑器")
                                }
                            }
                        }
                        if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)),
                            contentAlignment = Alignment.Center) { StructureDataLoading() }
                    }
                }
            }
            sheet?.let { selected ->
                ModalBottomSheet(onDismissRequest = { if (!busy) sheet = null },
                    containerColor = Color(p.pageBackground), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Text(selected.name, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.titleMedium)
                    Text(selected.path, Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = Color(p.body), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = Color(p.border))
                    ListItem(
                        headlineContent = { Text("重命名") }, leadingContent = { Icon(StructureIcons.DriveFileRenameOutline, null) },
                        modifier = Modifier.combinedClickable(onClick = {
                            sheet = null
                            prompt = Prompt("重命名", selected.name) { next ->
                                call("renameFile", selected.path, next)
                                val renamed = selected.group.let { if (it.isBlank()) next else "$it/$next" }
                                val index = opened.indexOf(selected.path)
                                if (index >= 0) opened[index] = renamed
                                if (current == selected.path) current = renamed
                                refresh()
                            }
                        }),
                    )
                    if (selected.kind == "image") {
                        ListItem(headlineContent = { Text("设为封面") }, leadingContent = { Icon(StructureIcons.Image, null) },
                            modifier = Modifier.combinedClickable(onClick = { sheet = null; perform { call("setCover", selected.path); refresh() } }))
                        ListItem(headlineContent = { Text("设为横幅") }, leadingContent = { Icon(StructureIcons.Panorama, null) },
                            modifier = Modifier.combinedClickable(onClick = { sheet = null; perform { call("setBanner", selected.path); refresh() } }))
                    }
                    if (selected.editable) ListItem(headlineContent = { Text("原编辑器 · 全书查找替换") },
                        leadingContent = { Icon(StructureIcons.FindReplace, null) },
                        modifier = Modifier.combinedClickable(onClick = { sheet = null; openLegacy(selected.path, false) }))
                    ListItem(headlineContent = { Text("删除", color = Color(p.destructiveText)) },
                        leadingContent = { Icon(StructureIcons.DeleteOutline, null, tint = Color(p.destructiveText)) },
                        modifier = Modifier.combinedClickable(onClick = { sheet = null; confirmDelete = selected }))
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
            confirmDelete?.let { target ->
                AlertDialog(onDismissRequest = { if (!busy) confirmDelete = null },
                    title = { Text("删除文件") },
                    text = { Text("确定删除“${target.name}”？此操作不可撤销。") },
                    confirmButton = { TextButton(enabled = !busy, onClick = {
                        confirmDelete = null; perform { call("deleteFile", target.path); closeOpened(target.path); refresh() }
                    }) { Text("删除", color = Color(p.destructiveText)) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } })
            }
            prompt?.let { edit ->
                var value by remember(edit) { mutableStateOf(edit.value) }
                AlertDialog(onDismissRequest = { if (!busy) prompt = null },
                    title = { Text(edit.title) },
                    text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth()) },
                    confirmButton = { TextButton(enabled = !busy, onClick = {
                        perform { edit.submit(value); prompt = null }
                    }) { Text("保存") } },
                    dismissButton = { TextButton(enabled = !busy, onClick = { prompt = null }) { Text("取消") } })
            }
        }
    }

    @Composable private fun FileRow(file: Entry, cover: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
        val p = colors
        val titleRevision = revision
        val chapterTitle by produceState("", file.path, titleRevision) {
            value = ""
            if (file.kind == "html") {
                value = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    titleReadMutex.withLock {
                        context.ensureActive()
                        val key = titleRevision to file.path
                        chapterTitleCache[key] ?: try {
                            val title = EpubChapterTitlePreview.read(EpubTextFiles.resolve(root, file.path)) {
                                context.ensureActive()
                            }
                            context.ensureActive()
                            chapterTitleCache[key] = title
                            title
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            chapterTitleCache[key] = ""
                            ""
                        }
                    }
                }
            }
        }
        val dimensions by produceState("", file.path, revision) {
            value = if (file.kind != "image") "" else withContext(Dispatchers.IO) { runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(EpubTextFiles.resolve(root, file.path).path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) "${bounds.outWidth} × ${bounds.outHeight}" else ""
            }.getOrDefault("") }
        }
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore)) {
            Row(Modifier.fillMaxWidth().padding(start = 32.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).size(30.dp, 36.dp).clip(RoundedCornerShape(3.dp))) {
                    if (file.kind == "image") Thumbnail(file)
                    else {
                        val drawable = when {
                            file.name.endsWith(".opf", true) -> R.drawable.epub_host130_opf
                            file.name.endsWith(".ncx", true) -> R.drawable.epub_host130_ncx
                            file.kind == "xml" -> R.drawable.epub_host130_xml
                            file.kind == "html" -> R.drawable.epub_host130_html
                            file.kind == "css" -> R.drawable.epub_host130_css
                            file.kind == "font" -> R.drawable.epub_host130_font
                            else -> R.drawable.epub_host130_xml
                        }
                        Image(painterResource(drawable), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
                Column(Modifier.weight(1f).padding(top = 16.dp, bottom = 16.dp, end = 16.dp)) {
                    Text(file.name.substringBeforeLast('.', file.name), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold, color = Color(p.title))
                    if (chapterTitle.isNotBlank()) {
                        Text(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = Color(p.body))
                    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (cover) "封面" else file.name.substringAfterLast('.').uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall, color = if (cover) Color(p.primary) else Color(p.body))
                        if (dimensions.isNotBlank()) Text(dimensions,
                            style = MaterialTheme.typography.labelSmall, color = Color(p.body))
                        if (file.kind in setOf("html", "font", "image")) Text(file.size,
                            style = MaterialTheme.typography.labelSmall, color = Color(p.body))
                    }
                }
                if (file.kind == "image") IconButton(onClick = onMore) { Icon(StructureIcons.MoreVert, "图片操作", tint = Color(p.body)) }
            }
            HorizontalDivider(Modifier.padding(start = 32.dp), color = Color(p.border))
        }
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
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
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
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error,
            )
        }
    }
    @Composable private fun FontPage(file: Entry) {
        val loaded by produceState<Result<FontFamily>?>(null, file.path) {
            value = null
            value = withContext(Dispatchers.IO) { runCatching {
                FontFamily(Typeface.createFromFile(EpubTextFiles.resolve(root, file.path)))
            } }
        }
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
            return
        }
        if (loaded!!.isFailure) {
            Text(loaded!!.exceptionOrNull()?.message ?: "无法加载字体", Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error)
            return
        }
        val family = loaded!!.getOrThrow()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(fontSamples) { (label, text) ->
                Column {
                    Text(label, color = Color(colors.body), style = MaterialTheme.typography.labelMedium)
                    Text(text, fontFamily = family, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    private fun decodeImage(file: Entry, maxEdge: Int): android.graphics.Bitmap {
        val path = EpubTextFiles.resolve(root, file.path).path
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, size)
        val sample = epubPreviewSampleSize(size.outWidth, size.outHeight, maxEdge,
            maxEdge.toLong() * maxEdge)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败")
    }
    @Composable private fun MetadataPage(file: Entry, revision: Int, edit: (String, String, String) -> Unit) {
        var data by remember(file.path) { mutableStateOf<JSONObject?>(null) }
        var error by remember(file.path) { mutableStateOf<String?>(null) }
        LaunchedEffect(file.path, revision) {
            try { data = call("getMetadata", file.path).getJSONObject("metadata"); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
        if (error != null) Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        else if (data == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(metadataFields) { (key, label) ->
                val value = data!!.optString(key)
                Surface(onClick = { edit(key, label, value) }, shape = RoundedCornerShape(8.dp),
                    color = Color(colors.rowBackground), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(colors.body))
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(colors.border))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(value.ifBlank { "[未设置]" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                color = Color(if (value.isBlank()) colors.body else colors.title))
                            Icon(StructureIcons.Edit, "编辑$label", Modifier.padding(start = 16.dp).size(16.dp),
                                tint = Color(colors.title).copy(alpha = .3f))
                        }
                    }
                }
            }
        }
    }
    @Composable private fun CssPage(file: Entry, revision: Int) {
        var rules by remember(file.path) { mutableStateOf<JSONArray?>(null) }
        var error by remember(file.path) { mutableStateOf<String?>(null) }
        val collapsed = remember(file.path) { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(file.path, revision) {
            try { rules = call("cssRules", file.path).getJSONArray("rules"); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
        if (error != null) Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        else if (rules == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StructureDataLoading() }
        else LazyColumn(Modifier.fillMaxSize()) {
            if (rules!!.length() == 0) item { Text("没有可显示的样式规则，可从右上角查看源码", Modifier.padding(16.dp)) }
            items(rules!!.length()) { index ->
                val rule = rules!!.getJSONObject(index)
                Column {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { collapsed[index] = !(collapsed[index] ?: false) })
                        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.optString("selector"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Icon(if (collapsed[index] == true) StructureIcons.ExpandMore else StructureIcons.ExpandLess, "展开样式")
                    }
                    AnimatedVisibility(collapsed[index] != true) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            val rows = rule.optJSONArray("rows") ?: JSONArray()
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.optString("name"), style = MaterialTheme.typography.bodyMedium)
                                        Text(row.optString("summary"), color = Color(colors.body), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(row.optString("value"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(colors.border))
                }
            }
        }
    }
    private companion object {
        val metadataFields = listOf("title" to "标题", "subtitle" to "副标题", "author" to "作者",
            "language" to "语言", "publisher" to "出版", "date" to "出版日期")
        // Exact samples are populated from X4/p's existing port by the build-time source update.
        val fontSamples = listOf(
            "中文简体 Simplified Chinese" to "狐狸说：“重要的东西用眼睛是看不见的，只有用心才能看清。”",
            "中文繁体 Traditional Chinese" to "狐狸說：“重要的東西用眼睛是看不見的，只有用心才能看清。”",
            "英语 English" to "The fox said, \"What is essential is invisible to the eyes; only with the heart can one see clearly.\"",
            "日语 Japanese" to "狐は言った。「大切なものは目には見えない。心でしか見えない。」",
            "韩语 Korean" to "여우가 말했다. \"중요한 것은 눈으로는 보이지 않는다. 오직 마음으로만 볼 수 있다.\"",
        )
    }
}

/** Same Material3 primitive/parameters as 2.3.2 arch.components.DataLoading. */
@Composable internal fun StructureDataLoading(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeWidth = 2.dp,
    )
}
