package com.reamicro.fix.hook

import com.reamicro.fix.core.typeMatches

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.core.HostEpubRuntimeBindings
import com.reamicro.fix.core.HostReaderScrollBindings
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import com.reamicro.fix.hook.reader.*

internal fun readerUiStatusName(status: Any?): String {
    if (status == null) return ""
    com.reamicro.fix.core.HostReaderGuardBindings.backKey?.statusName(status)?.let { return it }
    // 状态单例属于 typeAlias，不能依赖仅反查 class 条目的通用逻辑名。
    return listOf("Reader", "Menu", "Catalog", "Font", "Highlight", "MarginEditor", "Theme", "TypeSetting")
        .firstOrNull { typeMatches(status.javaClass, "app.zhendong.reamicro.ui.reader.UiStatus\$$it") }
        ?: HostSymbols.logicalSimpleName(status.javaClass.name)
}

internal fun ReaderHook.releaseReaderStrongReferences(reason: String, releaseEpub: Boolean) {
    currentPageStrong = null
    if (releaseEpub) currentEpubStrong = null
    HookRuntime.log("$LOG_PREFIX reader strong references released: $reason releaseEpub=$releaseEpub")
}

internal fun ReaderHook.releaseReaderMemory(reason: String, releaseEpub: Boolean, removeOverlays: Boolean = false) {
    cancelHighlightRefresh()
    cancelSelectionPagerConfirmation()
    currentPageRef = null
    currentPageStrong = null
    currentVisiblePageSignature = null
    currentVisiblePageNumber = null
    lastHandledReaderStatisticsKey = ""
    lastOnDemandPrefetchSpineKey = ""
    onDemandPrefetchInFlight.clear()
    onDemandPrefetchRetryCount.clear()
    onDemandRefreshPending.clear()
    invalidateOnDemandRefreshes()
    renderingEpubPage.remove()
    if (releaseEpub) {
        currentEpubRef = null
        currentEpubStrong = null
        OnlineReaderContextBridge.clear()
    }
    resetFullTextSearchState(reason, removeOverlays = removeOverlays)
    HookRuntime.log("$LOG_PREFIX reader memory released: $reason releaseEpub=$releaseEpub")
}

internal fun ReaderHook.isUninitializedContentDomParent(error: Throwable): Boolean =
    error is UninitializedPropertyAccessException &&
        error.message?.contains("parent") == true

internal fun ReaderHook.fallbackRenderTextWidthDp(contentDom: Any?): Float? =
    runCatching {
        val host = com.reamicro.fix.core.HostReaderLifecycleBindings.current ?: return@runCatching null
        if (contentDom == null || !host.type("dom").isInstance(contentDom)) return@runCatching null
        val textLayout = host.field("dom.layout").get(contentDom) ?: return@runCatching null
        val size = host.field("layout.size").getLong(textLayout)
        val widthPx = (size shr 32).toInt()
        if (widthPx <= 0) return@runCatching 0f
        val dp = (host.method("dp").invoke(null, Integer.valueOf(widthPx)) as Number).toFloat()
        kotlin.math.ceil(dp.toDouble()).toFloat()
    }.getOrNull()

internal fun ReaderHook.handleHomeBookshelfRendered(source: String) {
    val hadReaderSearchState = hasFullTextSearchState()
    if (activeSearchNavigation != null) {
        returnToSearchOrigin(clearNavigation = true, removeBar = false)
    }

    val readerGone = currentViewModelRef?.get() == null
    if (readerGone) {
        currentEpubRef = null
        currentPageRef = null
        currentEpubStrong = null
        currentPageStrong = null
        OnlineReaderContextBridge.clear()
        lastHandledReaderStatisticsKey = ""
        lastOnDemandPrefetchSpineKey = ""
        readerBottomMenuVisible = false
        updateReaderHighlightBookContext("", "", "home rendered: $source", requestRefresh = false)
        if (hadReaderSearchState) {
            resetFullTextSearchState("home rendered: $source", removeOverlays = true)
        }
    }
}

internal fun ReaderHook.postRemoveTaggedViews(activity: Activity?, tagValue: Int) {
    val targetActivity = activity ?: activityProvider() ?: return
    val decor = targetActivity.window?.decorView as? ViewGroup ?: return
    val remove = Runnable {
        runCatching {
            removeTaggedViews(decor, tagValue)
            removeTaggedViews(targetActivity.findViewById(android.R.id.content), tagValue)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to remove overlay tag=$tagValue: ${it.stackTraceToString()}")
        }
    }
    if (!com.reamicro.fix.reload.ModuleResources.accepting &&
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    ) remove.run() else readerUiHandler.post(remove)
}

internal fun ReaderHook.isReaderBottomBarDarkLightToggleClick(onClick: Any): Boolean =
    readerBottomBarComposeScope.active && runCatching {
        com.reamicro.fix.core.HostReaderMenuBindings.current?.themeMatches(onClick, currentSessionRef?.get()) == true
    }.getOrDefault(false)

internal fun ReaderHook.sendReaderUiSheetStatus(receiver: Any, statusName: String): Boolean =
    runCatching {
        requireNotNull(com.reamicro.fix.core.HostHighlightUiBindings.current) { "Highlight UI contract unavailable" }
            .send(receiver, statusName)
        true
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX update reader sheet status failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun ReaderHook.hostApplicationContext(): Context? =
    activityProvider()?.applicationContext

internal fun ReaderHook.createReaderStatisticsIntent(page: Any): Any? =
    runCatching {
        requireNotNull(com.reamicro.fix.core.HostReaderLifecycleBindings.current).constructor("Statistics").newInstance(page)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX reader statistics intent failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.udp(value: Int): Float =
    requireNotNull(com.reamicro.fix.core.HostUiBindings.current).call("dpInt", value) as Float

internal fun ReaderHook.cachedUdp(value: Int): Float? {
    synchronized(udpValueCache) {
        udpValueCache[value]?.let { return it }
    }
    val resolved = runCatching { udp(value) }.getOrNull() ?: return null
    synchronized(udpValueCache) {
        udpValueCache[value] = resolved
    }
    return resolved
}

internal fun ReaderHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)

internal fun ReaderHook.cacheThemeColors(composer: Any, captureSearchTheme: Boolean = false) {
    runCatching {
        val colorScheme = hostColorScheme(composer)
        cachedThemeColors = ThemeColors(
            pageBackground = hostThemeColor("getBackgroundAuto", colorScheme),
            cardBackground = hostThemeColor("getBackgroundBright", colorScheme),
            inputBackground = hostThemeColor("getBackgroundBright", colorScheme),
            primaryText = hostColorSchemeColor(colorScheme, "getOnBackground-0d7_KjU"),
            secondaryText = hostThemeColor("getOnBackgroundVariant", colorScheme),
            stroke = hostThemeColor("getBorderVariant", colorScheme),
            chipBackground = hostThemeColor("getBackgroundDim", colorScheme),
            action = hostColorSchemeColor(colorScheme, "getPrimary-0d7_KjU"),
        )
        latestThemeColors = cachedThemeColors
        if (captureSearchTheme) HostThemeColors.readerSnapshot(colorScheme)?.let { snapshot ->
            if (readerSearchTheme != snapshot) {
                readerSearchTheme = snapshot
                searchNavigationView?.updateTheme(snapshot)
            }
        }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX cache host theme colors failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hostColorScheme(composer: Any): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostUiBindings.current).call("colorScheme", composer))
internal fun ReaderHook.hostThemeColor(methodName: String, colorScheme: Any): Int {
    val role = when (methodName) {
        "getBackgroundAuto" -> "backgroundAuto"
        "getBackgroundBright" -> "backgroundBright"
        "getBackgroundDim" -> "backgroundDim"
        "getOnBackgroundVariant" -> "onBackgroundVariant"
        "getBorderVariant" -> "borderVariant"
        else -> error("Unknown reader theme role: $methodName")
    }
    return composeColorToArgb(requireNotNull(com.reamicro.fix.core.HostUiBindings.current).call(role, colorScheme) as Long)
}
internal fun ReaderHook.hostColorSchemeColor(colorScheme: Any, methodName: String): Int {
    val role = when (methodName) {
        "getOnBackground-0d7_KjU" -> "onBackground"
        "getPrimary-0d7_KjU" -> "primary"
        else -> error("Unknown reader scheme role: $methodName")
    }
    return composeColorToArgb(requireNotNull(com.reamicro.fix.core.HostUiBindings.current).field("color.$role").getLong(colorScheme))
}
internal fun ReaderHook.composeColorToArgb(color: Long): Int =
    composeInterop.colorToArgb(color)

internal fun ReaderHook.createNativeEditAction(icon: Any): Any? =
    createSelectionMenuAction(
        if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u7f16\u8f91",
        icon,
        nativeFunction0 {
            openNativeSelectionEditor()
        },
        "create native edit action",
    )

internal fun ReaderHook.editImageVector(): Any? =
    runCatching { com.reamicro.fix.core.HostSelectionBindings.current?.method("editIcon")?.invoke(null) }.getOrNull()
internal fun ReaderHook.nativeFunction0(block: () -> Unit): Any {
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    return ui.callback(ui.type("function0")) {
        com.reamicro.fix.core.InjectedModuleContext.runUiAction(activityProvider(), block)
    }
}
internal fun ReaderHook.resultCardLayoutParams(activity: Activity): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        leftMargin = dp(activity, 14)
        rightMargin = dp(activity, 14)
        bottomMargin = dp(activity, 2)
    }

internal fun ReaderHook.currentReadingTarget(): ReadingTarget? {
    currentViewModelRef?.get()?.let { vm ->
        searchScrollBridge?.readingTarget(vm)?.let { return it }
    }
    currentScrollElement?.let { element ->
        val scroll = HostReaderScrollBindings.current
        if (scroll != null && scroll.type("element").isInstance(element)) {
            val id = scroll.field("element.id").get(element)?.toString().orEmpty()
            if (id.isNotBlank()) return ReadingTarget(id,
                (scroll.field("element.chapter").get(element) as? Number)?.toInt() ?: 0,
                scroll.field("element.title").get(element)?.toString().orEmpty(), "")
        }
    }
    val nativePage = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        currentViewModelRef?.get()?.let { searchPageBridge?.page(it) } else null
    val page = nativePage ?: currentPageRef?.get()
    if (page == null) {
        val lastCfi = runCatching {
            currentViewModelRef?.get()?.let { com.reamicro.fix.core.HostReaderPositionBindings.current?.field("vm.visible")?.get(it) } as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: currentViewModelRef?.get()?.let { searchPageBridge?.progressCfi(it) }
        if (lastCfi != null) {
            return ReadingTarget(lastCfi, 0, "原来进度", "")
        }
    }
    if (page == null) return null
    val host = HostEpubRuntimeBindings.current ?: return null
    val cfi = host.anchor(page).takeIf { it.isNotBlank() } ?: return null
    val state = currentViewModelRef?.get()?.let { host.state(it) }
    val catalog = host.list("state.catalog", state)
    return ReadingTarget(
        cfi = cfi,
        chapterIndex = resolveCatalogIndexForCfi(cfi, catalog) ?: host.int("state.catalogIndex", state) ?: 0,
        title = host.text("page.chapter", page).ifBlank { "原来进度" },
        summary = host.text("page.summary", page),
    )
}

internal fun ReaderHook.forceRefreshReaderWindow(source: String): Boolean {
    val vm = currentViewModelRef?.get() ?: return false
    val host = HostEpubRuntimeBindings.current ?: return false
    val key = "reamicro_${source}_${System.currentTimeMillis()}"
    val dispatched = runCatching {
        dispatchReaderIntent(null, vm, host.constructor("UpdateWindowKey").newInstance(key), "UpdateWindowKey")
    }.onFailure { HookRuntime.logError("$LOG_PREFIX reader window intent failed", it) }.getOrDefault(false)
    if (dispatched) return true
    return runCatching {
        val flow = requireNotNull(host.value("vm.windowKey", vm))
        host.method("flow.set").invoke(flow, key)
        true
    }.onFailure { HookRuntime.logError("$LOG_PREFIX reader window refresh failed", it) }.getOrDefault(false)
}

internal fun ReaderHook.idOrNull(mark: Any?): Long? =
    mark?.let(::searchResultHighlightMarkId)

internal fun ReaderHook.textRange(start: Int, end: Int): Long? {
    if (start < 0 || end < start) return null
    return com.reamicro.fix.core.HostReaderRecordBindings.current?.method("range")?.invoke(null, start, end) as? Long
}
internal fun ReaderHook.markColorTokenToColor(token: String): Long? =
    runCatching {
        com.reamicro.fix.core.HostReaderRecordBindings.current?.method("color")?.invoke(null, token) as? Long
    }.getOrNull()
internal fun ReaderHook.removeTaggedViews(root: ViewGroup?, tagValue: Int) {
    if (root == null) return
    for (index in root.childCount - 1 downTo 0) {
        val child = root.getChildAt(index) ?: continue
        if (child.tag == tagValue) {
            root.removeViewAt(index)
        } else {
            removeTaggedViews(child as? ViewGroup, tagValue)
        }
    }
}

internal fun ReaderHook.dispatchReaderIntent(receiver: Any?, viewModel: Any?, intent: Any, label: String): Boolean {
    val host = com.reamicro.fix.core.HostReaderLifecycleBindings.current ?: return false
    if (viewModel != null && host.type("vm").isInstance(viewModel)) {
        host.method("dispatch").invoke(viewModel, intent)
        HookRuntime.log("$LOG_PREFIX reader intent $label sent to ReaderViewModel")
        return true
    }
    val send = host.method("send")
    if (receiver != null && send.declaringClass.isInstance(receiver)) {
        send.invoke(receiver, intent)
        HookRuntime.log("$LOG_PREFIX reader intent $label sent to receiver")
        return true
    }
    HookRuntime.log(
        "$LOG_PREFIX reader intent $label dispatch failed: " +
            "viewModel=${viewModel?.javaClass?.name.orEmpty()} receiver=${receiver?.javaClass?.name.orEmpty()}",
    )
    return false
}

internal fun ReaderHook.parseNcxTitlePaths(root: File, file: File): Map<String, List<String>> = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val stack = ArrayList<TocNode>()
    val result = linkedMapOf<String, List<String>>()
    var captureText = false
    val text = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                "navpoint" -> stack.add(TocNode())
                "content" -> stack.lastOrNull()?.href = normalizeHref(parser.getAttributeValue(null, "src").orEmpty())
                "text" -> if (stack.isNotEmpty()) {
                    captureText = true
                    text.setLength(0)
                }
            }
            XmlPullParser.TEXT -> if (captureText) text.append(parser.text.orEmpty())
            XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                "text" -> if (captureText) {
                    stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                    captureText = false
                }
                "navpoint" -> if (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.lastIndex)
                    putTocNodeTitlePath(result, root, file, stack, node)
                }
            }
        }
        parser.next()
    }
    result
}.getOrDefault(emptyMap())

internal fun ReaderHook.parseNavTitlePaths(root: File, file: File): Map<String, List<String>> = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val stack = ArrayList<TocNode>()
    val result = linkedMapOf<String, List<String>>()
    var tocNavDepth = 0
    var captureDepth = 0
    val text = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.lowercase(Locale.ROOT)
                if (tocNavDepth > 0) tocNavDepth++
                if (name == "nav" && tocNavDepth == 0 && tagHasTocType(parser)) tocNavDepth = 1
                if (captureDepth > 0) captureDepth++
                when {
                    tocNavDepth == 0 -> Unit
                    name == "li" -> stack.add(TocNode())
                    (name == "a" || name == "span") && stack.isNotEmpty() && captureDepth == 0 -> {
                        if (name == "a") stack.last().href = normalizeHref(parser.getAttributeValue(null, "href").orEmpty())
                        captureDepth = 1
                        text.setLength(0)
                    }
                }
            }
            XmlPullParser.TEXT -> if (captureDepth > 0) text.append(parser.text.orEmpty())
            XmlPullParser.END_TAG -> {
                val name = parser.name.lowercase(Locale.ROOT)
                if (captureDepth > 0) {
                    captureDepth--
                    if (captureDepth == 0) {
                        stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                    }
                }
                if (tocNavDepth > 0 && name == "li" && stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.lastIndex)
                    putTocNodeTitlePath(result, root, file, stack, node)
                }
                if (tocNavDepth > 0) tocNavDepth--
            }
        }
        parser.next()
    }
    result
}.getOrDefault(emptyMap())

internal fun ReaderHook.tagHasTocType(parser: XmlPullParser): Boolean {
    for (index in 0 until parser.attributeCount) {
        val name = parser.getAttributeName(index).orEmpty()
        val value = parser.getAttributeValue(index).orEmpty()
        if ((name == "type" || name.endsWith(":type") || name == "role") &&
            value.lowercase(Locale.ROOT).contains("toc")) {
            return true
        }
    }
    return false
}

internal fun ReaderHook.putTocNodeTitlePath(
    result: MutableMap<String, List<String>>,
    root: File,
    source: File,
    stack: List<TocNode>,
    node: TocNode,
) {
    val title = node.title.normalizeChapterTitle()
    val href = normalizeCatalogHref(node.href)
    if (title.isBlank() || href.isBlank()) return
    val fileHref = href.substringBefore('#')
    val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() }
    val target = File(source.parentFile ?: root, fileHref).canonicalFileSafe() ?: File(source.parentFile ?: root, fileHref)
    val key = normalizePath(relativePath(root, target)) + anchor.orEmpty().let { if (it.isBlank()) "" else "#$it" }
    val fileKey = key.substringBefore('#')
    val path = (stack.map { it.title.normalizeChapterTitle() } + title)
        .filter { it.isNotBlank() }
        .dedupeAdjacent()
    if (key.isNotBlank() && path.isNotEmpty()) {
        result.putIfAbsent(key, path)
        result.putIfAbsent(fileKey, path)
    }
}

internal fun ReaderHook.normalizeHref(value: String): String =
    runCatching { URLDecoder.decode(value.trim(), "UTF-8") }
        .getOrDefault(value.trim())
        .substringBefore('?')
        .replace('\uFEFF', ' ')
        .trim()

internal fun ReaderHook.coverUriFromBook(book: Any, root: File?): String? =
    HostEpubRuntimeBindings.current?.text("book.cover", book)?.let { normalizeCoverCandidate(it, root) }

internal fun ReaderHook.normalizeCoverCandidate(value: String, root: File?): String? {
    val raw = value.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
    if (raw.startsWith("data:", ignoreCase = true) ||
        raw.startsWith("content://", ignoreCase = true) ||
        raw.startsWith("file://", ignoreCase = true) ||
        raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true)
    ) {
        return raw
    }
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    File(decoded).takeIf { it.isFile && it.isCoverImageFile() }?.let { return it.absolutePath }
    val normalized = normalizePath(decoded.substringBefore('#'))
    if (root != null && normalized.isNotBlank()) {
        coverFileForHref(root, root, normalized)?.let { return it.absolutePath }
    }
    return null
}

internal fun ReaderHook.findOpfCoverFile(root: File): File? {
    for (opf in root.walkTopDown().filter { it.isFile && it.extension.equals("opf", ignoreCase = true) }) {
        val href = opfCoverHref(opf) ?: continue
        val base = opf.parentFile ?: root
        coverFileForHref(base, root, href)?.let { return it }
    }
    return null
}

internal fun ReaderHook.opfCoverHref(opf: File): String? = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(opf.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val manifest = linkedMapOf<String, String>()
    var coverId = ""
    var coverImageHref = ""
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) {
            when (parser.name.lowercase(Locale.ROOT)) {
                "meta" -> {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    if (name.equals("cover", ignoreCase = true)) {
                        coverId = parser.getAttributeValue(null, "content").orEmpty()
                    }
                }
                "item" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    val href = parser.getAttributeValue(null, "href").orEmpty()
                    val properties = parser.getAttributeValue(null, "properties").orEmpty()
                    if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
                    if (href.isNotBlank() && properties.split(Regex("\\s+")).any { it == "cover-image" }) {
                        coverImageHref = href
                    }
                }
            }
        }
        parser.next()
    }
    coverImageHref.ifBlank { manifest[coverId].orEmpty() }.takeIf { it.isNotBlank() }
}.getOrNull()

internal fun ReaderHook.coverFileForHref(base: File, root: File, href: String): File? {
    val normalized = normalizePath(URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
    if (normalized.isBlank()) return null
    val candidates = buildList {
        add(File(base, normalized))
        add(File(root, normalized))
        if ('/' in normalized) add(File(root, normalized.substringAfter('/')))
        if ('/' in normalized) add(File(root, normalized.substringAfterLast('/')))
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate.canonicalFileSafe()?.takeIf { it.isFile && it.isCoverImageFile() }
    }
}

internal fun ReaderHook.htmlAnchorTextStart(raw: String, anchor: String): Int? {
    val decoded = normalizeHref(anchor)
    val escaped = Regex.escape(decoded)
    val match = Regex("""(?is)<[^>]+\b(?:id|name)\s*=\s*(['"])$escaped\1[^>]*>""")
        .find(raw) ?: return null
    return htmlToSearchText(raw.substring(0, match.range.first)).length
}

internal fun ReaderHook.bodyOnlyHtml(value: String): String =
    Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?: value
            .replace(Regex("<head\\b[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<title\\b[\\s\\S]*?</title>", RegexOption.IGNORE_CASE), " ")

internal fun ReaderHook.bookTitle(context: CatalogContext): String =
    readableBookTitle(
        readableBookTitleFromBook(currentUiStateBook()),
        readableBookTitleFromBook(context.book),
        titleFromCurrentEpubRoot(),
    )

internal fun ReaderHook.fallbackCurrentBookTitle(): String {
    val title = readableBookTitle(
        readableBookTitleFromBook(currentUiStateBook()),
        lastCatalogContext?.let { readableBookTitleFromBook(it.book) }.orEmpty(),
        titleFromCurrentEpubRoot(),
    )
    if (title.isNotBlank()) return title
    return fallbackCurrentChapterTitle()
}

internal fun ReaderHook.currentUiStateBook(): Any? {
    val vm = currentViewModelRef?.get() ?: return null
    val host = HostEpubRuntimeBindings.current ?: return null
    return host.value("state.book", host.state(vm))
}
internal fun ReaderHook.readableBookTitleFromBook(book: Any?): String {
    val host = HostEpubRuntimeBindings.current ?: return ""
    return readableBookTitle(*listOf("title", "subtitle", "uri", "uuid").map { host.text("book.$it", book) }.toTypedArray())
}
internal fun ReaderHook.bookKeyFromBook(book: Any?, title: String): String {
    val host = HostEpubRuntimeBindings.current
    return listOf(currentEpubRoot()?.absolutePath.orEmpty(), host?.text("book.id", book).orEmpty(),
        host?.text("book.uuid", book).orEmpty(), title).filter { it.isNotBlank() }.joinToString("|")
}

internal fun ReaderHook.readableBookTitle(vararg candidates: String): String =
    candidates
        .map { cleanBookTitleCandidate(it) }
        .firstOrNull { it.isNotBlank() && !isInternalBookTitle(it) && !isGenericBookTitle(it) }
        .orEmpty()

internal fun ReaderHook.cleanBookTitleCandidate(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val segment = trimmed
        .substringAfterLast('|')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".epub")
        .removeSuffix(".EPUB")
        .trim()
    return segment
}

internal fun ReaderHook.isInternalBookTitle(value: String): Boolean =
    value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
        value.matches(Regex("^[0-9a-fA-F]{16,}$")) ||
        value.contains('|')

internal fun ReaderHook.isGenericBookTitle(value: String): Boolean =
    value == "\u672c\u4e66" || value == "\u56fe\u4e66"

internal fun ReaderHook.bookKey(context: CatalogContext): String = bookKeyFromBook(context.book, bookTitle(context))

internal fun ReaderHook.createThoughtStyleEditor(
    activity: Activity,
    text: String,
    colors: DialogColors,
    dp: (Int) -> Int,
): EditText =
    EditText(activity).apply {
        setText(text)
        setSelection(length())
        setTextColor(colors.primaryText)
        setHintTextColor(colors.secondaryText)
        hint = "\u8bb0\u5f55\u6b64\u523b\u7684\u60f3\u6cd5"
        textSize = 18f
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        maxLines = 8
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(0, 0, 0, dp(10))
        background = null
        EmbeddedHostUi.thoughtInput(this)
    }

internal fun ReaderHook.thoughtStyleSaveButton(
    activity: Activity,
    colors: DialogColors,
    dp: (Int) -> Int,
): TextView =
    TextView(activity).apply {
        text = "\u4fdd\u5b58"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(colors.actionText)
        setPadding(dp(20), dp(4), dp(20), dp(4))
        background = GradientDrawable().apply {
            setColor(colors.actionBackground)
            cornerRadius = dp(16).toFloat()
        }
        minWidth = dp(80)
        minHeight = dp(32)
    }

internal fun ReaderHook.focusEditorAndShowKeyboard(activity: Activity, editor: EditText) {
    editor.requestFocus()
    readerUiHandler.postDelayed(
        {
            if (activity.isFinishing || activity.isDestroyed || !editor.isAttachedToWindow) return@postDelayed
            editor.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        },
        120L,
    )
}

internal fun ReaderHook.hideKeyboard(view: View) {
    runCatching {
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
}

internal fun ReaderHook.candidateCurrentTextFiles(): List<File> {
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return emptyList()
    val host = HostEpubRuntimeBindings.current ?: return emptyList()
    val root = host.text("epub.directory", epub).takeIf { it.isNotBlank() }?.let(::File)
        ?.takeIf { it.isDirectory } ?: return emptyList()
    val itemRefs = host.list("epub.itemRefs", epub)
    val currentFile = currentTextContentFile(root, itemRefs)
    val allTextFiles by lazy {
        root.walkTopDown()
            .filter { it.isFile && it.isTextContentFile() }
            .map { it.canonicalFileSafe() ?: it }
            .toList()
    }
    return buildList {
        if (currentFile != null) add(currentFile)
        addAll(allTextFiles.filter { currentFile == null || it.absolutePath != currentFile.absolutePath })
    }
}

internal fun ReaderHook.currentTextContentFile(root: File, itemRefs: Iterable<*>): File? {
    val page = currentReaderPage() ?: return null
    val href = pageHrefCandidates(page).firstOrNull() ?: run {
        logCurrentPageProbe(root, itemRefs, "page-not-in-spine-table")
        return null
    }
    return searchFileForHref(root, href).also { file ->
        if (file == null) logCurrentPageProbe(root, itemRefs, "href-not-found:$href")
    }
}
internal fun ReaderHook.pageHrefCandidates(page: Any): List<String> {
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return emptyList()
    val host = HostEpubRuntimeBindings.current ?: return emptyList()
    val position = epubPageSpine(page) ?: return emptyList()
    val item = host.list("epub.itemRefs", epub).getOrNull(position) ?: return emptyList()
    return listOfNotNull(host.text("item.href", item).takeIf { it.isNotBlank() })
}
internal fun ReaderHook.logCurrentPageProbe(root: File, itemRefs: Iterable<*>, reason: String) {
    val page = currentReaderPage()
    val host = HostEpubRuntimeBindings.current ?: return
    val key = listOf(reason, root.absolutePath, host.anchor(page), host.text("page.summary", page).take(48)).joinToString("|")
    if (key == currentPageProbeLogKey) return
    currentPageProbeLogKey = key
    val pageDump = listOf("anchor", "start", "end", "chapter").joinToString(";") { role ->
        "$role=${host.text("page.$role", page).take(120)}"
    }
    val itemDump = itemRefs.take(8).joinToString(";") { item ->
        "href=${host.text("item.href", item)},index=${host.int("item.index", item)},spine=${host.int("item.spineIndex", item)}"
    }
    HookRuntime.log("$LOG_PREFIX reader current file probe reason=$reason page=[$pageDump] itemRefs=[$itemDump]")
}

internal fun ReaderHook.replaceUniqueTextInFile(file: File, oldText: String, newText: String): Boolean {
    val loaded = com.reamicro.fix.epub.editor.EpubTextFiles.load(file)
    val updated = com.reamicro.fix.epub.editor.replaceUniqueSelectionText(loaded.text, oldText, newText)
        ?: return false
    com.reamicro.fix.epub.editor.EpubTextFiles.save(file, updated, loaded.snapshot)
    return true
}
internal fun ReaderHook.escapeXmlText(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun ReaderHook.emptyCoroutineContext(): Any =
    com.reamicro.fix.core.HostCoroutines.emptyContext(classLoader)
internal fun ReaderHook.targetUnit(): Any =
    com.reamicro.fix.core.HostCoroutines.unit(classLoader)
