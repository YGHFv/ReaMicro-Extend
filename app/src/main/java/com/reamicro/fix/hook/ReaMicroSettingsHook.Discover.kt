package com.reamicro.fix.hook
import com.reamicro.fix.core.HostDiscoverBindings
import com.reamicro.fix.core.HostUiBindings

import com.reamicro.fix.core.HostAndroidViews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import android.widget.ImageView
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.discover.DiscoverKind
import com.reamicro.fix.discover.DiscoverLayout
import com.reamicro.fix.discover.DiscoverLoadState
import com.reamicro.fix.discover.DiscoverSelection
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState
import com.reamicro.fix.hook.discover.DiscoverUiIcons
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.webdav.OnlineBinaryPayload
import com.reamicro.fix.hook.webdav.dp
import com.reamicro.fix.hook.webdav.normalizedAssociationPlatformName
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTask
import com.reamicro.fix.online.readOnlineSourceBytes
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.online.search.formatOnlineUpdateTime
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.formatOnlineWordCount
import com.reamicro.fix.xposed.HookRuntime

private const val DISCOVER_KIND_ROW_HEIGHT_DP = 42
private const val DISCOVER_KIND_ROW_BOTTOM_DP = 2
private const val DISCOVER_KIND_CONTENT_HEIGHT_DP = DISCOVER_KIND_ROW_HEIGHT_DP - DISCOVER_KIND_ROW_BOTTOM_DP
private const val DISCOVER_TITLE_CATEGORY_TRIM_DP = 12
private const val DISCOVER_CATEGORY_BOOK_GAP_DP = 8

internal fun ReaMicroSettingsHook.renderDiscoverContent(innerPaddings: Any, composer: Any) {
    discoverVersionValue()
    val appContext = activityProvider()?.applicationContext
    val listContent = functionProxy("DiscoverList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()

        discoverVersionValue()

        DiscoverState.ensureSources(appContext)
        val sources = DiscoverState.sources
        if (sources.isEmpty()) {
            renderDiscoverEmptyState(lazyListScope)
            return@functionProxy targetUnit()
        }
        val selection = DiscoverState.selection
        val current = sources.firstOrNull { it.source.id == selection.sourceId }

        renderDiscoverBooks(lazyListScope, current, selection)
        targetUnit()
    }

    val background = backgroundDim(composer)
    val modifier = nativeUi.background(pageModifier(innerPaddings, true), background)
    renderHostLazyColumn(innerPaddings, listContent, composer, extendBottom = true,
        itemSpacing = if (DiscoverState.layout == DiscoverLayout.GRID) DiscoverShelfStyle.GRID_GAP else 0, modifierOverride = modifier)
}

internal fun ReaMicroSettingsHook.renderDiscoverTopBar(title: String, composer: Any) {
    val actions = composableLambda(DISCOVER_TOP_BAR_ACTIONS_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        discoverVersionValue()
        val tint = schemeColor(inner, "getOnBackground", "getOnBackground")
        renderDiscoverConfigButton(tint, inner)
        renderDiscoverLayoutToggle(tint, inner)
        targetUnit()
    }
    val content = composableLambda(0x524D46B0, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        discoverVersionValue()
        invokeAppTopBar(title = title, composer = inner,
            onBack = { navigateBackFromInjectedRoute() }, actions = actions)
        val selection = DiscoverState.selection
        val source = DiscoverState.sources.firstOrNull { it.source.id == selection.sourceId }
        if (source != null && DiscoverState.kindsFor(source).isNotEmpty()) {
            renderDiscoverKindRow(source, selection, inner)
        }
        targetUnit()
    }
    // 原生标题栏为 64 raw dp；收掉底部 12dp 空白，保持标题坐标、状态栏和分类行高度。
    // Arrangement 同步缩短 topBar 测量高度；负 Padding 会抛错，仅 offset 则会留下书籍前空白。
    val arrangement = nativeUi.spacedBy(-DISCOVER_TITLE_CATEGORY_TRIM_DP.toFloat())
    discoverColumn(discoverFillMaxWidth(modifierInstance()), content, composer, arrangement)
}

private fun ReaMicroSettingsHook.renderDiscoverTopBarAction(
    icon: Any?, description: String, tint: Long, composer: Any, name: String, onClick: () -> Unit,
) {
    if (icon == null) return
    val clickable = clickableModifier(modifierInstance(), name, onClick)
    val padded = nativeUi.padding(clickable, 10f, 10f, 10f, 10f)
    renderDiscoverIcon(icon, tint, composer, description, padded)
}

private fun ReaMicroSettingsHook.renderDiscoverConfigButton(tint: Long, composer: Any) {
    val activity = activityProvider()
    renderDiscoverTopBarAction(DiscoverUiIcons.configuration130(classLoader), "筛选管理", tint, composer, "DiscoverConfig") {
        if (activity != null) openDiscoverConfigDialog(activity)
    }
}

private fun ReaMicroSettingsHook.renderDiscoverEmptyState(lazyListScope: Any) {
    addLazyItem(lazyListScope, DISCOVER_EMPTY_ITEM_KEY) { itemComposer ->
        val failure = DiscoverState.state as? DiscoverLoadState.Failed
        if (DiscoverState.refreshing || failure != null) {
            renderDiscoverStatusCard(if (failure == null) "正在读取在线源…" else "读取在线源失败",
                failure?.message.orEmpty(),
                if (failure == null) null else { { DiscoverState.reload(activityProvider()?.applicationContext) } },
                itemComposer)
            return@addLazyItem
        }
        renderHostActionCard(
            listOf(
                ActionRow(
                    key = "discover_empty_tip",
                    title = "暂无发现内容",
                    subtitle = "当前书源都没有配置发现页（exploreUrl）",
                ),
            ),
            itemComposer,
            backgroundOverride = backgroundDim(itemComposer),
        )
    }
}

private fun ReaMicroSettingsHook.renderDiscoverLayoutToggle(tint: Long, composer: Any) {
    val activity = activityProvider()
    val grid = DiscoverState.layout == DiscoverLayout.GRID
    val icon = if (grid) DiscoverUiIcons.layoutGrid(classLoader) else DiscoverUiIcons.layoutList(classLoader)
    val description = if (grid) "当前宫格，点击切换为列表" else "当前列表，点击切换为宫格"
    renderDiscoverTopBarAction(icon, description, tint, composer, "DiscoverLayoutToggle") {
        DiscoverState.toggleLayout(activity?.applicationContext)
    }
}

private fun ReaMicroSettingsHook.renderDiscoverKindRow(
    source: DiscoverSource, selection: DiscoverSelection, composer: Any,
) {
    val kinds = DiscoverState.kindsFor(source).distinctBy { it.key }
    val row = composableLambda(DISCOVER_KIND_ROW_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        val scope = args[0]!!
        val list = functionProxy("DiscoverGroups", FUNCTION1_CLASS) { listArgs ->
            val lazyScope = listArgs?.getOrNull(0) ?: return@functionProxy targetUnit()
            kinds.forEachIndexed { index, kind ->
                addLazyItem(lazyScope, DISCOVER_KIND_ROW_KEY + index,
                    "${source.source.id}|${kind.key}") { c ->
                    renderDiscoverKindChip(source, kind, selection, index == 0, c)
                }
            }
            targetUnit()
        }
        val mod = discoverWeight(scope, modifierInstance())

        val initialIndex = kinds.indexOfFirst { it.key == selection.kindKey }.coerceAtLeast(0)
        val state = requireNotNull(requireNotNull(HostDiscoverBindings.current).call("lazyState", initialIndex, inner, 0, 0))
        requireNotNull(HostUiBindings.current).call("lazyRow", mod, state, null, null, null, null, false, null, list, inner, 0, 252)
        renderDiscoverKindExpandButton(inner)
        targetUnit()
    }
    // 列表书目已有 10 UDP 顶部留白；仅宫格另加 8 raw dp，保持 42 UDP 分类点击区域。
    val gap = if (DiscoverState.layout == DiscoverLayout.GRID) DISCOVER_CATEGORY_BOOK_GAP_DP.toFloat() else 0f
    val width = discoverFillMaxWidth(modifierInstance())
    val spaced = nativeUi.padding(width, 0f, 0f, 0f, gap)
    // 外间距在 height 前，原生分组栏的底部 2 UDP 仍包含在 42 UDP 内。
    val sized = discoverHeight(spaced, DISCOVER_KIND_ROW_HEIGHT_DP)
    val mod = paddingSides(sized, 20, 0, 8, DISCOVER_KIND_ROW_BOTTOM_DP)
    discoverRow(mod, row, composer)
}

private fun ReaMicroSettingsHook.renderDiscoverKindExpandButton(composer: Any) {
    val activity = activityProvider()
    val width = nativeUi.width(modifierInstance(), udp(48))
    val sized = discoverHeight(width, DISCOVER_KIND_CONTENT_HEIGHT_DP)
    val modifier = clickableModifier(sized, "DiscoverKindExpand") {
        if (activity != null) openDiscoverKindDialog(activity)
    }
    renderDiscoverIcon(DiscoverUiIcons.chevronDown(classLoader), schemeColor(composer, "getOnBackground", "getOnBackground"),
        composer, "选择发现分类", nativeUi.padding(modifier, udp(12), udp(8), udp(12), udp(8)))
}

private fun ReaMicroSettingsHook.renderDiscoverKindChip(
    source: DiscoverSource, kind: DiscoverKind, selection: DiscoverSelection, first: Boolean, composer: Any,
) {
    val selected = kind.key == selection.kindKey
    val activity = activityProvider()
    val clickable = clickableModifier(modifierInstance(), "DiscoverKind${kind.title}") {
        DiscoverState.select(source.source.id, kind.title, activity?.applicationContext, kind.key)
    }
    val mod = paddingSides(discoverHeight(clickable, DISCOVER_KIND_CONTENT_HEIGHT_DP),
        if (first) 0 else DiscoverShelfStyle.GROUP_HORIZONTAL_PADDING, 0,
        DiscoverShelfStyle.GROUP_HORIZONTAL_PADDING, 0)
    val weight = requireNotNull(HostDiscoverBindings.current).constructor("fontWeight").newInstance(if (selected) 700 else 400)
    val color = schemeColor(composer, if (selected) "getOnBackground" else "getOnSurfaceVariant", "getOnBackground")
    val content = composableLambda(kind.key.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverText(kind.displayTitle, color, textStyle(inner, "getLabelLarge", "getBodyMedium"), inner, fontWeight = weight)
        targetUnit()
    }
    discoverRow(mod, content, composer)
}

private fun ReaMicroSettingsHook.renderDiscoverBooks(
    lazyListScope: Any,
    source: DiscoverSource?,
    selection: DiscoverSelection,
) {
    when (val state = DiscoverState.state) {
        DiscoverLoadState.Idle, DiscoverLoadState.Loading -> {
            addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY, DISCOVER_STATUS_ITEM_ID) { itemComposer ->
                renderDiscoverStatusCard("正在加载…", selection.kindTitle, null, itemComposer)
            }
        }

        is DiscoverLoadState.Failed -> {
            addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY, DISCOVER_STATUS_ITEM_ID) { itemComposer ->
                renderDiscoverStatusCard("加载失败", state.message, {
                    DiscoverState.reload(activityProvider()?.applicationContext)
                }, itemComposer)
            }
        }

        is DiscoverLoadState.Loaded -> {
            if (state.books.isEmpty()) {
                addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY, DISCOVER_STATUS_ITEM_ID) { itemComposer ->
                    renderDiscoverStatusCard("暂无内容", selection.kindTitle, {
                        DiscoverState.reload(activityProvider()?.applicationContext)
                    }, itemComposer)
                }
                return
            }
            if (source == null) return

            val books = state.books.distinctBy { it.key }
            if (DiscoverState.layout == DiscoverLayout.GRID) {
                books.chunked(DISCOVER_GRID_COLUMNS).forEachIndexed { rowIndex, rowBooks ->
                    addLazyItem(
                        lazyListScope,
                        DISCOVER_GRID_ROW_ITEM_KEY_BASE + rowIndex,
                        "discover_grid_row_${rowBooks.first().key}",
                    ) { itemComposer ->
                        renderDiscoverGridRow(source, rowBooks, itemComposer)
                    }
                }
            } else {

                books.forEachIndexed { index, book ->
                    addLazyItem(
                        lazyListScope,
                        DISCOVER_LIST_ROW_ITEM_KEY_BASE + index,
                        "discover_list_${book.key}",
                    ) { itemComposer ->
                        renderDiscoverListRow(source, book, itemComposer)
                    }
                }
            }
            if (DiscoverState.hasMore) {
                addLazyItem(lazyListScope, DISCOVER_LOAD_MORE_ITEM_KEY, DISCOVER_LOAD_MORE_ITEM_ID) { itemComposer ->
                    renderDiscoverLoadMore(itemComposer)
                }
            }

            addLazyItem(lazyListScope, DISCOVER_TAIL_GAP_ITEM_KEY, DISCOVER_TAIL_GAP_ITEM_ID) { itemComposer ->
                renderDiscoverVGap(itemComposer, DISCOVER_TAIL_GAP_DP)
            }
        }
    }
}

private fun ReaMicroSettingsHook.renderDiscoverLoadMore(composer: Any) {
    val count = (DiscoverState.state as? DiscoverLoadState.Loaded)?.books?.size ?: 0
    val loading = DiscoverState.loadingMore
    val activity = activityProvider()
    renderHostActionCard(
        listOf(
            ActionRow(
                key = "discover_load_more",
                title = if (loading) "正在加载更多…" else "加载更多",
                subtitle = "已加载 $count 本",
                onClick = { if (!DiscoverState.loadingMore) DiscoverState.loadMore(activity?.applicationContext) },
            ),
        ),
        composer,
        backgroundOverride = backgroundDim(composer),
    )
}

                                 private fun ReaMicroSettingsHook.renderDiscoverStatusCard(
    title: String,
    subtitle: String?,
    onRetry: (() -> Unit)?,
    composer: Any,
) {
    renderHostActionCard(
        listOf(
            ActionRow(
                key = "discover_status_${title.hashCode()}",
                title = title,
                subtitle = subtitle?.takeIf { it.isNotBlank() },
                onClick = onRetry,
            ),
        ),
        composer,
        backgroundOverride = backgroundDim(composer),
    )
}

private fun ReaMicroSettingsHook.renderDiscoverListRow(
    source: DiscoverSource,
    book: DiscoverBook,
    composer: Any,
) {
    val titleColor = schemeColor(composer, "getOnBackground", "getOnBackground")
    val metaColor = schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    val info = discoverTagInfo(book, source)
    val titleStyle = textStyle(composer, "getTitleSmall", "getBodyMedium")
    val bodyStyle = textStyle(composer, "getBodyMedium", "getBodySmall")
    val labelStyle = textStyle(composer, "getLabelSmall", "getBodySmall")

    val title = book.name
    val authorLine = book.author.ifBlank { "未知作者" }
    val latestLine = book.lastChapter.takeIf { it.isNotBlank() }?.let { "最新：$it" }.orEmpty()
    val coreLine = discoverCoreTagLine(book, source)
    val tagLine = info.kindText

    val rowContent = composableLambda(DISCOVER_LIST_ROW_KEY + book.key.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverCover(source, book, DISCOVER_LIST_COVER_WIDTH_DP, DISCOVER_LIST_COVER_HEIGHT_DP, inner)
        renderDiscoverSpacer(inner, DISCOVER_LIST_COVER_GAP_DP)
        val textContent = composableLambda(DISCOVER_LIST_TEXT_KEY + book.key.hashCode(), FUNCTION3_CLASS) { colArgs ->
            val colInner = colArgs?.getOrNull(1) ?: return@composableLambda targetUnit()
            renderDiscoverText(title, titleColor, titleStyle, colInner)
            renderDiscoverVGap(colInner, DISCOVER_LIST_LINE_GAP_DP)
            renderDiscoverText(listOf(authorLine, coreLine).filter { it.isNotBlank() }.joinToString(" · "),
                metaColor, bodyStyle, colInner)
            renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
            renderDiscoverText(latestLine.ifBlank { " " }, metaColor, bodyStyle, colInner)
            renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
            renderDiscoverText(tagLine.ifBlank { " " }, metaColor, labelStyle, colInner)
            targetUnit()
        }

        val textModifier = discoverWeight(args[0] ?: rowScopeInstance(), modifierInstance())
        discoverColumn(textModifier, textContent, inner)
        targetUnit()
    }
    val rowModifier = paddingSides(
        clickableModifier(discoverFillMaxWidth(modifierInstance()), "DiscoverBook${book.key.hashCode()}") {
            openDiscoverBook(source, book)
        },
        DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP, DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
        DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP, DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
    )
    discoverRow(rowModifier, rowContent, composer)
}

private fun ReaMicroSettingsHook.renderDiscoverGridRow(
    source: DiscoverSource,
    books: List<DiscoverBook>,
    composer: Any,
) {
    val titleColor = schemeColor(composer, "getOnBackground", "getOnBackground")

    val content = composableLambda(DISCOVER_GRID_ROW_KEY + books.first().key.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()

        repeat(DISCOVER_GRID_COLUMNS) { columnIndex ->
            val book = books.getOrNull(columnIndex)
            val cellContent = composableLambda(DISCOVER_GRID_CELL_KEY + columnIndex, FUNCTION3_CLASS) { cellArgs ->
                val cellInner = cellArgs?.getOrNull(1) ?: return@composableLambda targetUnit()
                if (book != null) {

                    renderDiscoverCover(source, book, null, null, cellInner, bottomGapDp = DISCOVER_GRID_TITLE_GAP_DP)
                    val titleStyle = textStyle(cellInner, "getLabelMedium", "getBodySmall")
                    renderDiscoverText(
                        book.name,
                        titleColor,
                        titleStyle,
                        cellInner,
                        singleLine = false,
                        maxLines = GRID_TITLE_MAX_LINES,
                        reserveLines = false,
                    )

                }
                targetUnit()
            }
            val baseModifier = discoverWeight(args[0] ?: rowScopeInstance(), modifierInstance())
            val cellModifier = if (book != null) {
                clickableModifier(baseModifier, "DiscoverGridBook${book.key.hashCode()}") {
                    openDiscoverBook(source, book)
                }
            } else {
                baseModifier
            }
            discoverColumn(cellModifier, cellContent, inner)
        }
        targetUnit()
    }
    discoverRow(paddingSides(discoverFillMaxWidth(modifierInstance()), 2, 0, 2, 0),
        content, composer, spacedBy(DISCOVER_GRID_GAP_DP), alignmentTop())
}

private fun ReaMicroSettingsHook.renderDiscoverText(
    text: String, color: Long, style: Any, composer: Any,
    singleLine: Boolean = true, maxLines: Int = 0, reserveLines: Boolean = false,
    fontWeight: Any? = null,
    modifier: Any? = null,
) {
    val spec = DiscoverTextSpec.resolve(singleLine, maxLines, reserveLines)
    val mask = if (fontWeight == null) DiscoverTextSpec.DEFAULT_MASK
        else DiscoverTextSpec.DEFAULT_MASK and (1 shl 6).inv()
    val modifierMask = if (modifier == null) mask else mask and (1 shl 1).inv()
    requireNotNull(HostUiBindings.current).call("text",
        text, modifier, color, 0L, fontWeight, null, 0L, null, null, 0L,
        DiscoverTextSpec.ELLIPSIS, spec.softWrap, spec.maxLines, spec.minLines, null,
        style, composer, 0, DISCOVER_TEXT_ELLIPSIS_CHANGED, modifierMask)
}

private fun ReaMicroSettingsHook.renderDiscoverSpacer(composer: Any, widthDp: Int) {
    nativeUi.spacer(nativeUi.width(modifierInstance(), udp(widthDp)), composer)
}

private fun ReaMicroSettingsHook.renderDiscoverVGap(composer: Any, heightDp: Int) {
    nativeUi.spacer(nativeUi.height(modifierInstance(), udp(heightDp)), composer)
}

private fun ReaMicroSettingsHook.renderDiscoverIcon(
    icon: Any?, tint: Long, composer: Any, description: String? = null, modifier: Any = modifierInstance(),
) {
    if (icon == null) return
    iconVectorMethod().invoke(
        null,
        icon,
        description,
        modifier,
        tint,
        composer,
        DISCOVER_ICON_CHANGED_MASK,
        DISCOVER_ICON_DEFAULT_MASK,
    )
}

private fun ReaMicroSettingsHook.renderDiscoverCover(
    source: DiscoverSource,
    book: DiscoverBook,
    widthDp: Int?,
    heightDp: Double?,
    composer: Any,
    bottomGapDp: Int = 0,
) {
    val factory = functionProxy("DiscoverCoverFactory", FUNCTION1_CLASS) { args ->
        val context = args?.getOrNull(0) as? Context ?: activityProvider()
            ?: error("no context for discover cover")
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            cropToPadding = true
            val palette = discoverDialogPalette(context)
            background = HostSurfaceDrawable(palette.rowBackground, palette.border,
                EmbeddedHostUi.dp(context, DISCOVER_COVER_CORNER_DP.toFloat()), 0f)
            foreground = HostSurfaceDrawable(0, palette.border, EmbeddedHostUi.dp(context, DISCOVER_COVER_CORNER_DP.toFloat()), EmbeddedHostUi.dp(context, .4f))
            val inset = EmbeddedHostUi.dp(context, if (widthDp == null) 4f else 2f).toInt()
            setPadding(inset, inset, inset, inset)
            clipToOutline = true
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) { cancelDiscoverCover(this@apply) }
            })
        }
    }
    val update = functionProxy("DiscoverCoverUpdate", FUNCTION1_CLASS) { args ->
        val view = args?.getOrNull(0) as? ImageView
        if (view != null) {
            val palette = discoverDialogPalette(view.context)
            view.background = HostSurfaceDrawable(palette.rowBackground, palette.border,
                EmbeddedHostUi.dp(view.context, DISCOVER_COVER_CORNER_DP.toFloat()), 0f)
            view.foreground = HostSurfaceDrawable(0, palette.border, EmbeddedHostUi.dp(view.context, DISCOVER_COVER_CORNER_DP.toFloat()), EmbeddedHostUi.dp(view.context, .4f))
            val inset = EmbeddedHostUi.dp(view.context, if (widthDp == null) 4f else 2f).toInt()
            view.setPadding(inset, inset, inset, inset)
            if (book.coverUrl.isNotBlank()) loadDiscoverCover(view, source.source, book.coverUrl, book.detailUrl)
            else { cancelDiscoverCover(view); view.setImageDrawable(null); view.contentDescription = "暂无封面" }
        }
        targetUnit()
    }
    val coverBase = if (bottomGapDp > 0) paddingSides(modifierInstance(), 0, 0, 0, bottomGapDp)
        else modifierInstance()
    var modifier = if (widthDp == null) {
        discoverFillMaxWidth(coverBase)
    } else {
        nativeUi.width(coverBase, udp(widthDp))
    }
    if (heightDp == null) {

        modifier = discoverAspectRatio(modifier, DiscoverShelfStyle.COVER_RATIO)
    } else {
        modifier = nativeUi.height(modifier, udp(heightDp))
    }
    HostAndroidViews(classLoader).render(factory, modifier, composer, update)
}

private val discoverCoverCache = object : android.util.LruCache<String, Bitmap>(DISCOVER_COVER_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, (value.byteCount + 1023) / 1024)
}
private val discoverCoverMain = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())

private val discoverCoverPool = com.reamicro.fix.reload.ModuleResources.pool(
    DISCOVER_COVER_THREADS, DISCOVER_COVER_THREADS, 30, java.util.concurrent.TimeUnit.SECONDS,
    java.util.concurrent.ArrayBlockingQueue(48),
) { runnable ->
    Thread(runnable, "ReaMicroDiscoverCover").apply { isDaemon = true }
}

private class DiscoverCoverRequest(val key: String, val operation: OnlineSourceTask) {
    var task: java.util.concurrent.FutureTask<Unit>? = null
    fun cancel() {
        operation.close()
        task?.cancel(true)
        discoverCoverPool.purge()
    }
}

private fun cancelDiscoverCover(view: ImageView) {
    (view.tag as? DiscoverCoverRequest)?.cancel()
    view.tag = null
}

internal fun loadDiscoverCover(imageView: ImageView, source: OnlineSourceEntry, coverUrl: String, detailUrl: String) {
    val hook = WebDavDriveHook.activeInstance ?: return
    if (!OnlineSourceStore.isCurrent(source)) { cancelDiscoverCover(imageView); return }
    val key = "${source.id}|${source.revision}|$coverUrl|$detailUrl"
    val previous = imageView.tag as? DiscoverCoverRequest
    if (previous?.key == key || imageView.tag == key) return
    cancelDiscoverCover(imageView)
    imageView.setImageDrawable(null)
    discoverCoverCache.get(key)?.let {
        imageView.tag = key
        imageView.setImageBitmap(it)
        imageView.contentDescription = "封面"
        return
    }
    val request = DiscoverCoverRequest(key, OnlineSourceTask(source))
    imageView.tag = request
    imageView.contentDescription = "正在加载封面"
    val task = java.util.concurrent.FutureTask<Unit> {
        val bitmap = runCatching { request.operation.run {
            val url = hook.normalizeOnlineCoverUrl(source, sourceBaseUrl(source), coverUrl).trim()
            if (url.isBlank()) null else downloadDiscoverCover(hook, source, url)
                ?: if (isFanqieCoverUrl(url)) downloadDiscoverCoverViaWebPage(hook, detailUrl)
                    ?.second?.bytes?.let(::decodeDiscoverCoverBytes) else null
        } }.getOrNull()
        if (request.operation.isCurrent && bitmap != null) discoverCoverCache.put(key, bitmap)
        val current = request.operation.isCurrent
        request.operation.close()
        discoverCoverMain.post {
            if (imageView.tag !== request) return@post
            imageView.tag = key
            if (current && bitmap != null && OnlineSourceStore.isCurrent(source)) {
                imageView.setImageBitmap(bitmap)
                imageView.contentDescription = "封面"
            } else imageView.contentDescription = "封面加载失败"
        }
    }
    request.task = task
    try { discoverCoverPool.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) {
        request.cancel()
        imageView.tag = null
        imageView.contentDescription = "封面等待加载"
    }
}

internal fun isFanqieCoverUrl(url: String): Boolean =
    url.contains("byteimg.com", ignoreCase = true) ||
        url.contains("fqnovel.com", ignoreCase = true) ||
        url.contains("fanqienovel.com", ignoreCase = true)

internal fun downloadDiscoverCoverViaWebPage(hook: WebDavDriveHook, detailUrl: String): Pair<String, OnlineBinaryPayload>? {
    TaskCancellation.checkCurrent()
    val bookId = DISCOVER_FANQIE_BOOK_ID_REGEX.find(detailUrl)?.value.orEmpty()
    if (bookId.isBlank()) return null
    val html = hook.withOnlineCleartextAllowed(DISCOVER_FANQIE_WEB_BASE) {
        val connection = URL("$DISCOVER_FANQIE_WEB_BASE/page/$bookId").openConnection() as HttpURLConnection
        val cancellation = TaskCancellation.current()?.register(connection::disconnect)
        try {
            TaskCancellation.checkCurrent()
            connection.requestMethod = "GET"
            connection.connectTimeout = DISCOVER_COVER_CONNECT_TIMEOUT_MS
            connection.readTimeout = DISCOVER_COVER_READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", DISCOVER_COVER_BROWSER_UA)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            if (connection.responseCode !in 200..299) return@withOnlineCleartextAllowed null
            connection.inputStream.use(::readOnlineSourceBytes).toString(Charsets.UTF_8)
        } finally {
            cancellation?.close()
            connection.disconnect()
        }
    } ?: return null
    val picId = DISCOVER_FANQIE_PIC_HASH_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
    if (picId.isBlank()) return null
    val originUrl = "https://p6-novel.byteimg.com/origin/novel-pic/$picId"
    val connection = URL(originUrl).openConnection() as HttpURLConnection
    val cancellation = TaskCancellation.current()?.register(connection::disconnect)
    try {
        TaskCancellation.checkCurrent()
        connection.requestMethod = "GET"
        connection.connectTimeout = DISCOVER_COVER_CONNECT_TIMEOUT_MS
        connection.readTimeout = DISCOVER_COVER_READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", DISCOVER_COVER_BROWSER_UA)
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        if (connection.responseCode !in 200..299) {
            HookRuntime.logAlways(
                "[ReaMicro] discover cover web fallback http ${connection.responseCode} url=${originUrl.take(200)}",
            )
            return null
        }
        val bytes = connection.inputStream.use(::readOnlineSourceBytes)
        if (bytes.isEmpty()) return null
        return originUrl to OnlineBinaryPayload(
            bytes = bytes,
            mimeType = connection.contentType.orEmpty().substringBefore(';'),
            url = originUrl,
        )
    } finally {
        cancellation?.close()
        connection.disconnect()
    }
}

private fun downloadDiscoverCover(hook: WebDavDriveHook, source: OnlineSourceEntry, url: String): Bitmap? {
    TaskCancellation.checkCurrent()
    if (url.startsWith("data:image/", true)) {
        require(url.length <= 24 * 1024 * 1024) { "封面数据过大" }
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val bytes = if (url.substring(0, comma).contains(";base64", true))
            android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
        else java.net.URLDecoder.decode(url.substring(comma + 1), "UTF-8").toByteArray(Charsets.UTF_8)
        return decodeDiscoverCoverBytes(bytes)
    }
    val connection = hook.withOnlineCleartextAllowed(url) {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DISCOVER_COVER_CONNECT_TIMEOUT_MS
            readTimeout = DISCOVER_COVER_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            val sourceHeaders = hook.parseOnlineHeaders(source.header)
            sourceHeaders.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
            val authHeaders = OnlineSourceAuth.requestHeaders(currentApplicationContext(), source, url)
            authHeaders.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }

            val hasReferer = (sourceHeaders.keys + authHeaders.keys)
                .any { it.equals("Referer", ignoreCase = true) }
            if (!hasReferer) {
                runCatching {
                    val uri = URI(url)
                    if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                        setRequestProperty("Referer", "${uri.scheme}://${uri.host}/")
                    }
                }
            }
        }
    }
    val cancellation = TaskCancellation.current()?.register(connection::disconnect)
    return try {
        TaskCancellation.checkCurrent()
        val code = connection.responseCode
        if (code !in 200..299) {

            HookRuntime.logAlways("[ReaMicro] discover cover http $code url=${url.take(200)}")
            null
        } else {
            val bytes = connection.inputStream.use(::readOnlineSourceBytes)
            decodeDiscoverCoverBytes(bytes) ?: run {
                HookRuntime.logAlways(
                    "[ReaMicro] discover cover decode failed bytes=${bytes.size} " +
                        "head=${bytes.take(12).joinToString(" ") { "%02x".format(it) }} url=${url.take(200)}",
                )
                null
            }
        }
    } catch (t: Throwable) {
        TaskCancellation.rethrowIfCancelled(t)
        HookRuntime.logAlways(
            "[ReaMicro] discover cover failed url=${url.take(200)} " +
                "error=${t.javaClass.simpleName}: ${t.message.orEmpty()}",
        )
        null
    } finally {
        cancellation?.close()
        connection.disconnect()
    }
}

private fun decodeDiscoverCoverBytes(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= DISCOVER_COVER_TARGET_WIDTH_PX ||
        bounds.outHeight / (sample * 2) >= DISCOVER_COVER_TARGET_HEIGHT_PX
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun ReaMicroSettingsHook.discoverTagLine(book: DiscoverBook, source: DiscoverSource): String {
    val cacheKey = discoverCacheKey(book, source)
    discoverTagLineCache[cacheKey]?.let { return it }
    val info = discoverTagInfo(book, source)
    val line = buildList {
        book.status.takeIf { it.isNotBlank() }?.let { add(it) }
        formatOnlineWordCount(book.wordCount)
            .takeIf { it.isNotBlank() && !it.startsWith("-") }
            ?.let { add(it) }
        val chapters = book.chapterCount.filter { it.isDigit() }
        if (chapters.isNotBlank()) add("${chapters}章")
        info.platformName.takeIf { it.isNotBlank() }?.let { add(it) }
        formatOnlineUpdateTime(book.updateTime).takeIf { it.isNotBlank() }?.let { add(it) }
        info.kindText.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(DISCOVER_TAG_SEPARATOR)
    discoverTagLineCache.putBounded(cacheKey, line)
    return line
}

private class DiscoverTagInfo(val platformName: String, val kindText: String)

private val discoverTagInfoCache = java.util.concurrent.ConcurrentHashMap<String, DiscoverTagInfo>()

private val discoverTagLineCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun discoverCacheKey(book: DiscoverBook, source: DiscoverSource): String =
    "${source.source.id}|${source.source.revision}|${book.hashCode()}"

private fun <V> java.util.concurrent.ConcurrentHashMap<String, V>.putBounded(key: String, value: V) {
    if (size > DISCOVER_CACHE_MAX_ENTRIES) clear()
    put(key, value)
}

private fun ReaMicroSettingsHook.discoverTagInfo(book: DiscoverBook, source: DiscoverSource): DiscoverTagInfo {
    val cacheKey = discoverCacheKey(book, source)
    discoverTagInfoCache[cacheKey]?.let { return it }
    val resolved = runCatching { resolveDiscoverTagInfo(book, source) }
        .getOrDefault(DiscoverTagInfo("", book.kind))
    discoverTagInfoCache.putBounded(cacheKey, resolved)
    return resolved
}

private fun resolveDiscoverTagInfo(book: DiscoverBook, source: DiscoverSource): DiscoverTagInfo {
    val tokens = book.kind.split(' ', '\u3000', '/', '|', '\t')
    tokens.forEachIndexed { index, raw ->
        val clean = raw.trim().trim('/', '／', ',', '，')
        if (clean.isEmpty()) return@forEachIndexed
        val mapped = clean.normalizedAssociationPlatformName()
        if (mapped.isNotBlank() && mapped != clean) {
            val rest = tokens.filterIndexed { i, _ -> i != index }
                .joinToString(" ")
                .trim()
                .trim(',', '，', ' ', '\u3000')
            return DiscoverTagInfo(mapped, rest)
        }
    }
    listOf(book.detailUrl, book.coverUrl, source.name).forEach { raw ->
        val clean = raw.trim()
        if (clean.isEmpty()) return@forEach
        val mapped = clean.normalizedAssociationPlatformName()
        if (mapped.isNotBlank() && mapped != clean) return DiscoverTagInfo(mapped, book.kind)
    }
    return DiscoverTagInfo("", book.kind)
}

internal fun ReaMicroSettingsHook.discoverCoreTagLine(book: DiscoverBook, source: DiscoverSource): String {
    val info = discoverTagInfo(book, source)
    return buildList {
        book.status.takeIf { it.isNotBlank() }?.let { add(it) }
        formatOnlineWordCount(book.wordCount)
            .takeIf { it.isNotBlank() && !it.startsWith("-") }
            ?.let { add(it) }
        info.platformName.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(DISCOVER_TAG_SEPARATOR)
}

internal fun ReaMicroSettingsHook.openDiscoverBook(source: DiscoverSource, book: DiscoverBook) {
    val activity = activityProvider() ?: return
    if (book.detailUrl.isBlank()) {
        logDiscover("book has no detail url: ${book.name}")
        return
    }
    openDiscoverBookDialog(activity, source, book)
}

private fun ReaMicroSettingsHook.logDiscover(message: String) {
    HookRuntime.log("$LOG_PREFIX discover $message")
}

private fun ReaMicroSettingsHook.schemeColor(composer: Any, name: String, fallback: String): Long {
    val ui = requireNotNull(HostUiBindings.current)
    val colors = requireNotNull(ui.call("colorScheme", composer))
    fun field(value: String) = ui.field("color." + value.removePrefix("get").replaceFirstChar { it.lowercase() })
    return runCatching { field(name).getLong(colors) }.getOrElse { field(fallback).getLong(colors) }
}

private fun ReaMicroSettingsHook.textStyle(composer: Any, name: String, fallback: String): Any {
    val ui = requireNotNull(HostUiBindings.current)
    val typography = requireNotNull(ui.call("typography", composer))
    fun field(value: String) = ui.field("typography." + value.removePrefix("get").replaceFirstChar { it.lowercase() })
    return requireNotNull(runCatching { field(name).get(typography) }.getOrElse { field(fallback).get(typography) })
}

private fun ReaMicroSettingsHook.discoverFillMaxWidth(base: Any): Any = nativeUi.fillWidth(base)

private fun ReaMicroSettingsHook.discoverHeight(base: Any, heightDp: Int): Any = nativeUi.height(base, udp(heightDp))

private fun ReaMicroSettingsHook.discoverAspectRatio(base: Any, ratio: Float): Any =
    requireNotNull(requireNotNull(HostDiscoverBindings.current).call("aspectRatio", base, ratio))

private fun ReaMicroSettingsHook.paddingSides(base: Any, start: Int, top: Int, end: Int, bottom: Int): Any =
    nativeUi.padding(base, udp(start), udp(top), udp(end), udp(bottom))

private fun ReaMicroSettingsHook.alignmentTop(): Any =
    requireNotNull(HostDiscoverBindings.current).constructor("verticalAlignment").newInstance(-1f)

private fun ReaMicroSettingsHook.discoverWeight(scope: Any, base: Any): Any = nativeUi.weight(scope, base)

private fun ReaMicroSettingsHook.discoverColumn(
    modifier: Any, content: Any, composer: Any, arrangement: Any = arrangementTop(),
) {
    nativeUi.column(modifier, arrangement, alignmentStart(), content, composer)
}

private fun ReaMicroSettingsHook.discoverRow(
    modifier: Any, content: Any, composer: Any, arrangement: Any? = null, alignment: Any? = null,
) {
    nativeUi.row(modifier, content, composer, arrangement, alignment)
}

private fun ReaMicroSettingsHook.rowScopeInstance(): Any = requireNotNull(HostUiBindings.current).value("rowScope")

private fun ReaMicroSettingsHook.iconVectorMethod() = requireNotNull(HostDiscoverBindings.current).method("icon")

private const val LOG_PREFIX = "[ReaMicro]"

private const val DISCOVER_CACHE_MAX_ENTRIES = 600

private const val DISCOVER_COVER_CACHE_SIZE_KB = 12 * 1024

private const val DISCOVER_COVER_THREADS = 4

private const val DISCOVER_COVER_CONNECT_TIMEOUT_MS = 4_000

private const val DISCOVER_FANQIE_WEB_BASE = "https://fanqienovel.com"

private const val DISCOVER_COVER_BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

private val DISCOVER_FANQIE_BOOK_ID_REGEX = Regex("""\d{15,21}""")

private val DISCOVER_FANQIE_PIC_HASH_REGEX = Regex("""(?i)novel-pic/([0-9a-z]{16,64})""")

private const val DISCOVER_COVER_READ_TIMEOUT_MS = 6_000

private const val DISCOVER_COVER_TARGET_WIDTH_PX = 276

private const val DISCOVER_COVER_TARGET_HEIGHT_PX = 372

private const val DISCOVER_LIST_COVER_WIDTH_DP = DiscoverShelfStyle.LIST_COVER_WIDTH

private const val DISCOVER_LIST_COVER_HEIGHT_DP = DiscoverShelfStyle.LIST_COVER_HEIGHT

private const val DISCOVER_LIST_COVER_GAP_DP = 16

private const val DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP = 4

private const val DISCOVER_LIST_ROW_VERTICAL_PADDING_DP = 10

private const val DISCOVER_LIST_LINE_GAP_DP = 4

private const val DISCOVER_LIST_TAG_GAP_DP = 6

private const val DISCOVER_GRID_COLUMNS = 3

private const val DISCOVER_GRID_GAP_DP = DiscoverShelfStyle.GRID_GAP

private const val DISCOVER_GRID_TITLE_GAP_DP = 6

private const val DISCOVER_COVER_CORNER_DP = 4

private const val DISCOVER_TAG_SEPARATOR = " / "

private const val DISCOVER_TEXT_ELLIPSIS_CHANGED = 24960

private const val GRID_TITLE_MAX_LINES = 4

private const val DISCOVER_ICON_CHANGED_MASK = 0

private const val DISCOVER_ICON_DEFAULT_MASK = 0x0

private const val DISCOVER_TOP_BAR_ACTIONS_KEY = 0x524D46A0

private const val DISCOVER_KIND_ROW_KEY = 0x524D4691

private const val DISCOVER_BOOK_STATUS_ITEM_KEY = 0x524D4693

private const val DISCOVER_LIST_ROW_KEY = 0x524D4695

private const val DISCOVER_LIST_TEXT_KEY = 0x524D4696

private const val DISCOVER_GRID_ROW_KEY = 0x524D4698

private const val DISCOVER_GRID_CELL_KEY = 0x524D4699

private const val DISCOVER_LOAD_MORE_ITEM_KEY = 0x524D469A

private const val DISCOVER_STATUS_ITEM_ID = "discover_status"

private const val DISCOVER_LOAD_MORE_ITEM_ID = "discover_load_more"

private const val DISCOVER_TAIL_GAP_ITEM_KEY = 0x524D469B

private const val DISCOVER_TAIL_GAP_ITEM_ID = "discover_tail_gap"

private const val DISCOVER_TAIL_GAP_DP = 32

private const val DISCOVER_LIST_ROW_ITEM_KEY_BASE = 0x524E0000

private const val DISCOVER_GRID_ROW_ITEM_KEY_BASE = 0x524E4000
