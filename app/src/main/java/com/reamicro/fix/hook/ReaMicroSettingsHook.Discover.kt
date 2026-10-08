package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
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
import com.reamicro.fix.hook.webdav.ANDROID_VIEW_KT_CLASS
import com.reamicro.fix.hook.webdav.ANDROID_VIEW_METHOD
import com.reamicro.fix.hook.webdav.OnlineBinaryPayload
import com.reamicro.fix.hook.webdav.dp
import com.reamicro.fix.hook.webdav.normalizedAssociationPlatformName
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.search.formatOnlineUpdateTime
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.formatOnlineWordCount
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method

internal fun ReaMicroSettingsHook.renderDiscoverContent(innerPaddings: Any, composer: Any) {

    discoverVersionValue()
    val appContext = activityProvider()?.applicationContext
    val listContent = functionProxy("DiscoverList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()

        discoverVersionValue()

        if (DiscoverState.sources.isEmpty()) {
            DiscoverState.refreshSources(appContext)
        }
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
    val modifier = method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(null,
        pageModifier(innerPaddings, true), background, null, 2, null)
    renderHostLazyColumn(innerPaddings, listContent, composer, extendBottom = true,
        itemSpacing = if (DiscoverState.layout == DiscoverLayout.GRID) DiscoverShelfStyle.GRID_GAP else 0, modifierOverride = modifier)
}

internal fun ReaMicroSettingsHook.renderDiscoverTopBar(title: String, composer: Any) {
    val actions = composableLambda(DISCOVER_TOP_BAR_ACTIONS_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        discoverVersionValue()
        val tint = colorScheme(inner).longMethod("getOnBackground")
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
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(null, discoverFillMaxWidth(modifierInstance()),
        arrangementTop(), alignmentStart(), content, composer, 0, 0)
}

private fun ReaMicroSettingsHook.renderDiscoverTopBarAction(
    icon: Any?, description: String, tint: Long, composer: Any, name: String, onClick: () -> Unit,
) {
    if (icon == null) return
    val shape = cls("androidx.compose.foundation.shape.RoundedCornerShapeKt")
        .getDeclaredMethod("RoundedCornerShape", Integer.TYPE).invoke(null, 50)
    val clipped = method(CLIP_KT_CLASS, "clip", 2).invoke(null, modifierInstance(), shape)
    val ripple = method("androidx.compose.material3.RippleKt", "ripple-Ou1YvPQ\$default", 10)
        .invoke(null, false, 0f, 0L, null, false, false, false, false, 0xff, null)
    val clickable = method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
        null, clipped, null, ripple, false, null, null,
        functionProxy(name, FUNCTION0_CLASS) { onClick(); targetUnit() }, 28, null,
    )
    // 与宿主 2.3.2 AppTopBar 按钮一致：10dp 原始内边距，不额外叠加 udp 缩放。
    val padded = method(PADDING_KT_CLASS, "padding-3ABfNKs", 2).invoke(null, clickable, 10f)
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
    val kinds = DiscoverState.kindsFor(source).distinctBy { it.title }
    val row = composableLambda(DISCOVER_KIND_ROW_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        val scope = args[0]!!
        val list = functionProxy("DiscoverGroups", FUNCTION1_CLASS) { listArgs ->
            val lazyScope = listArgs?.getOrNull(0) ?: return@functionProxy targetUnit()
            kinds.forEachIndexed { index, kind ->
                addLazyItem(lazyScope, DISCOVER_KIND_ROW_KEY + index,
                    "${source.source.id}|${kind.title}") { c ->
                    renderDiscoverKindChip(source, kind, selection, index == 0, c)
                }
            }
            targetUnit()
        }
        val mod = method(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, DISCOVER_ROW_WEIGHT_METHOD, 3)
            .invoke(scope, modifierInstance(), 1f, true)

        val composerClass = cls("androidx.compose.runtime.Composer")
        composerClass.getMethod("startMovableGroup", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(inner, 0x524D46B1, "${source.source.id}|${selection.kindTitle}")
        try {
            val index = kinds.indexOfFirst { it.title == selection.kindTitle }.coerceAtLeast(0)
            val state = method("androidx.compose.foundation.lazy.LazyListStateKt", "rememberLazyListState", 5)
                .invoke(null, index, 0, inner, 0, 0)
            method(LAZY_DSL_KT_CLASS, "LazyRow", 13).invoke(null, mod, state, null, false,
                null, null, null, false, null, list, inner, 0, 508)
        } finally { composerClass.getMethod("endMovableGroup").invoke(inner) }
        renderDiscoverKindExpandButton(inner)
        targetUnit()
    }
    val mod = paddingSides(discoverFillMaxWidth(modifierInstance()), 20, 0, 8, 0)
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(null, mod, arrangementStart(),
        alignmentCenterVertically(), row, composer, 0, 0)
}

private fun ReaMicroSettingsHook.renderDiscoverKindExpandButton(composer: Any) {
    val activity = activityProvider()
    val content = composableLambda(DISCOVER_KIND_EXPAND_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverIcon(DiscoverUiIcons.chevronDown(classLoader), colorScheme(inner).longMethod("getOnBackground"), inner)
        targetUnit()
    }
    val width = method(SIZE_KT_CLASS, WIDTH_METHOD, 2).invoke(null, modifierInstance(), udp(48))
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, width, udp(48))
    val modifier = clickableModifier(sized, "DiscoverKindExpand") {
        if (activity != null) openDiscoverKindDialog(activity)
    }
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, modifier, alignmentCenter(), false, content, composer, 0, 0)
}

private fun ReaMicroSettingsHook.renderDiscoverKindChip(
    source: DiscoverSource, kind: DiscoverKind, selection: DiscoverSelection, first: Boolean, composer: Any,
) {
    val selected = kind.title == selection.kindTitle
    val activity = activityProvider()
    val content = composableLambda(kind.title.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        val weight = cls("androidx.compose.ui.text.font.FontWeight")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(if (selected) 700 else 400)
        renderDiscoverText(kind.title, colorScheme(inner).longMethod("getOnBackground"),
            textStyle(inner, "getLabelLarge", "getBodyMedium"), inner, fontWeight = weight)
        targetUnit()
    }
    var mod = clickableModifier(modifierInstance(), "DiscoverKind${kind.title}") {
        DiscoverState.select(source.source.id, kind.title, activity?.applicationContext)
    }
    mod = paddingSides(mod, if (first) 0 else DiscoverShelfStyle.GROUP_HORIZONTAL_PADDING,
        DiscoverShelfStyle.GROUP_VERTICAL_PADDING, DiscoverShelfStyle.GROUP_HORIZONTAL_PADDING,
        DiscoverShelfStyle.GROUP_VERTICAL_PADDING)
    if (!selected) mod = method("androidx.compose.ui.draw.AlphaKt", "alpha", 2).invoke(null, mod, .5f)
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, mod, alignmentCenter(), false, content, composer, 0, 0)
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
    val titleColor = colorScheme(composer).longMethod("getOnBackground")
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
            renderDiscoverText(authorLine, metaColor, bodyStyle, colInner)
            renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
            renderDiscoverText(listOf(latestLine, coreLine).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { " " }, metaColor, bodyStyle, colInner)
            renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
            renderDiscoverText(tagLine.ifBlank { " " }, metaColor, labelStyle, colInner)
            targetUnit()
        }

        val textModifier = method(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, DISCOVER_ROW_WEIGHT_METHOD, DISCOVER_ROW_WEIGHT_PARAMETER_COUNT)
            .invoke(rowScopeInstance(), modifierInstance(), 1f, true)
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            textModifier,
            arrangementTop(),
            alignmentStart(),
            textContent,
            inner,
            0,
            0,
        )
        targetUnit()
    }
    val rowModifier = paddingSides(
        clickableModifier(discoverFillMaxWidth(modifierInstance()), "DiscoverBook${book.key.hashCode()}") {
            openDiscoverBook(source, book)
        },
        DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP, DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
        DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP, DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
    )
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        rowModifier,
        arrangementStart(),
        alignmentCenterVertically(),
        rowContent,
        composer,
        0,
        0,
    )
}

private fun ReaMicroSettingsHook.renderDiscoverGridRow(
    source: DiscoverSource,
    books: List<DiscoverBook>,
    composer: Any,
) {
    val titleColor = colorScheme(composer).longMethod("getOnBackground")

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
            val baseModifier = method(
                DISCOVER_ROW_SCOPE_INSTANCE_CLASS, DISCOVER_ROW_WEIGHT_METHOD, DISCOVER_ROW_WEIGHT_PARAMETER_COUNT,
            ).invoke(rowScopeInstance(), modifierInstance(), 1f, true)
            val cellModifier = if (book != null) {
                clickableModifier(baseModifier, "DiscoverGridBook${book.key.hashCode()}") {
                    openDiscoverBook(source, book)
                }
            } else {
                baseModifier
            }
            method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
                null,
                cellModifier,
                arrangementTop(),
                alignmentStart(),
                cellContent,
                inner,
                0,
                0,
            )
        }
        targetUnit()
    }
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        paddingSides(discoverFillMaxWidth(modifierInstance()), 2, 0, 2, 0),
        spacedBy(DISCOVER_GRID_GAP_DP),
        alignmentTop(),
        content,
        composer,
        0,
        0,
    )
}

private fun ReaMicroSettingsHook.renderDiscoverText(
    text: String, color: Long, style: Any, composer: Any,
    singleLine: Boolean = true, maxLines: Int = 0, reserveLines: Boolean = false,
    fontWeight: Any? = null,
) {
    val spec = DiscoverTextSpec.resolve(singleLine, maxLines, reserveLines)
    val mask = if (fontWeight == null) DiscoverTextSpec.DEFAULT_MASK
        else DiscoverTextSpec.DEFAULT_MASK and (1 shl 6).inv()
    method(TEXT_KT_CLASS, TEXT_METHOD, DISCOVER_TEXT_PARAMETER_COUNT).invoke(null,
        text, null, color, null, 0L, null, fontWeight, null, 0L, null, null, 0L,
        DiscoverTextSpec.ELLIPSIS, spec.softWrap, spec.maxLines, spec.minLines, null,
        style, composer, 0, DISCOVER_TEXT_ELLIPSIS_CHANGED, mask)
}

private fun ReaMicroSettingsHook.renderDiscoverSpacer(composer: Any, widthDp: Int) {
    val modifier = method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(widthDp))
    method(SPACER_KT_CLASS, SPACER_METHOD, DISCOVER_SPACER_PARAMETER_COUNT).invoke(null, modifier, composer, 0)
}

private fun ReaMicroSettingsHook.renderDiscoverVGap(composer: Any, heightDp: Int) {
    val modifier = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(heightDp))
    method(SPACER_KT_CLASS, SPACER_METHOD, DISCOVER_SPACER_PARAMETER_COUNT).invoke(null, modifier, composer, 0)
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
            else { view.tag = null; view.setImageDrawable(null) }
        }
        targetUnit()
    }
    val coverBase = if (bottomGapDp > 0) paddingSides(modifierInstance(), 0, 0, 0, bottomGapDp)
        else modifierInstance()
    var modifier = if (widthDp == null) {
        discoverFillMaxWidth(coverBase)
    } else {
        method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
            .invoke(null, coverBase, udp(widthDp))
    }
    if (heightDp == null) {

        modifier = discoverAspectRatio(modifier, DiscoverShelfStyle.COVER_RATIO)
    } else {
        modifier = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
            .invoke(null, modifier, udp(heightDp))
    }
    androidViewMethod().invoke(null, factory, modifier, update, composer, 0, 0)
}

private val discoverCoverCache = object : android.util.LruCache<String, Bitmap>(DISCOVER_COVER_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private val discoverCoverPool = java.util.concurrent.Executors.newFixedThreadPool(DISCOVER_COVER_THREADS) { runnable ->
    Thread(runnable, "ReaMicroDiscoverCover").apply { isDaemon = true }
}

private fun loadDiscoverCover(imageView: ImageView, source: OnlineSourceEntry, coverUrl: String, detailUrl: String) {
    val hook = WebDavDriveHook.activeInstance ?: return
    val url = runCatching { hook.normalizeOnlineCoverUrl(source, sourceBaseUrl(source), coverUrl) }
        .onFailure {
            XposedBridge.logAlways(
                "[ReaMicro] discover cover normalize failed raw=${coverUrl.take(200)} " +
                    "error=${it.javaClass.simpleName}: ${it.message.orEmpty()}",
            )
        }
        .getOrNull()
        .orEmpty()
        .trim()
    if (url.isBlank()) return
    if (imageView.tag == url) return
    imageView.tag = url
    discoverCoverCache.get(url)?.let {
        imageView.setImageBitmap(it)
        return
    }
    discoverCoverPool.execute {
        var cacheKey = url
        val bitmap = runCatching { downloadDiscoverCover(hook, source, url) }
            .onFailure {

                XposedBridge.logAlways(
                    "[ReaMicro] discover cover task failed url=${url.take(200)} " +
                        "error=${it.javaClass.simpleName}: ${it.message.orEmpty()}",
                )
            }
            .getOrNull()
            ?: run {

                if (!isFanqieCoverUrl(url)) return@run null
                runCatching { downloadDiscoverCoverViaWebPage(hook, detailUrl) }
                    .onFailure {
                        XposedBridge.logAlways(
                            "[ReaMicro] discover cover web fallback failed detail=${detailUrl.take(160)} " +
                                "error=${it.javaClass.simpleName}: ${it.message.orEmpty()}",
                        )
                    }
                    .getOrNull()
                    ?.also { cacheKey = it.first }
                    ?.second?.bytes?.let(::decodeDiscoverCoverBytes)
            }
            ?: return@execute
        discoverCoverCache.put(cacheKey, bitmap)
        Handler(Looper.getMainLooper()).post {

            if (imageView.tag == url) imageView.setImageBitmap(bitmap)
        }
    }
}

internal fun isFanqieCoverUrl(url: String): Boolean =
    url.contains("byteimg.com", ignoreCase = true) ||
        url.contains("fqnovel.com", ignoreCase = true) ||
        url.contains("fanqienovel.com", ignoreCase = true)

internal fun downloadDiscoverCoverViaWebPage(hook: WebDavDriveHook, detailUrl: String): Pair<String, OnlineBinaryPayload>? {
    val bookId = DISCOVER_FANQIE_BOOK_ID_REGEX.find(detailUrl)?.value.orEmpty()
    if (bookId.isBlank()) return null
    val html = hook.withOnlineCleartextAllowed(DISCOVER_FANQIE_WEB_BASE) {
        val connection = URL("$DISCOVER_FANQIE_WEB_BASE/page/$bookId").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = DISCOVER_COVER_CONNECT_TIMEOUT_MS
            connection.readTimeout = DISCOVER_COVER_READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", DISCOVER_COVER_BROWSER_UA)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            if (connection.responseCode !in 200..299) return@withOnlineCleartextAllowed null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    } ?: return null
    val picId = DISCOVER_FANQIE_PIC_HASH_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
    if (picId.isBlank()) return null
    val originUrl = "https://p6-novel.byteimg.com/origin/novel-pic/$picId"
    val connection = URL(originUrl).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = DISCOVER_COVER_CONNECT_TIMEOUT_MS
        connection.readTimeout = DISCOVER_COVER_READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", DISCOVER_COVER_BROWSER_UA)
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        if (connection.responseCode !in 200..299) {
            XposedBridge.logAlways(
                "[ReaMicro] discover cover web fallback http ${connection.responseCode} url=${originUrl.take(200)}",
            )
            return null
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null
        return originUrl to OnlineBinaryPayload(
            bytes = bytes,
            mimeType = connection.contentType.orEmpty().substringBefore(';'),
            url = originUrl,
        )
    } finally {
        connection.disconnect()
    }
}

private fun downloadDiscoverCover(hook: WebDavDriveHook, source: OnlineSourceEntry, url: String): Bitmap? {
    decodeOnlineDataImage(url)?.let { return it }
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
    return try {
        val code = connection.responseCode
        if (code !in 200..299) {

            XposedBridge.logAlways("[ReaMicro] discover cover http $code url=${url.take(200)}")
            null
        } else {
            val bytes = connection.inputStream.use { it.readBytes() }
            decodeDiscoverCoverBytes(bytes) ?: run {
                XposedBridge.logAlways(
                    "[ReaMicro] discover cover decode failed bytes=${bytes.size} " +
                        "head=${bytes.take(12).joinToString(" ") { "%02x".format(it) }} url=${url.take(200)}",
                )
                null
            }
        }
    } catch (t: Throwable) {
        XposedBridge.logAlways(
            "[ReaMicro] discover cover failed url=${url.take(200)} " +
                "error=${t.javaClass.simpleName}: ${t.message.orEmpty()}",
        )
        null
    } finally {
        connection.disconnect()
    }
}

private fun decodeDiscoverCoverBytes(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= DISCOVER_COVER_TARGET_WIDTH_PX &&
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
    "${source.source.id}|${book.key}"

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

private fun ReaMicroSettingsHook.openDiscoverBook(source: DiscoverSource, book: DiscoverBook) {
    val activity = activityProvider() ?: return
    if (book.detailUrl.isBlank()) {
        logDiscover("book has no detail url: ${book.name}")
        return
    }
    openDiscoverBookDialog(activity, source, book)
}

private fun ReaMicroSettingsHook.logDiscover(message: String) {
    XposedBridge.log("$LOG_PREFIX discover $message")
}

private fun ReaMicroSettingsHook.schemeColor(composer: Any, name: String, fallback: String): Long =
    runCatching { colorScheme(composer).longMethod(name) }
        .getOrElse { colorScheme(composer).longMethod(fallback) }

private fun ReaMicroSettingsHook.textStyle(composer: Any, name: String, fallback: String): Any =
    runCatching { typography(composer).method0(name) }
        .getOrElse { typography(composer).method0(fallback) }

private fun ReaMicroSettingsHook.discoverFillMaxWidth(base: Any): Any =
    method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, DISCOVER_FILL_MAX_WIDTH_PARAMETER_COUNT)
        .invoke(null, base, 0f, DISCOVER_FILL_MAX_WIDTH_DEFAULT_MASK, null)

private fun ReaMicroSettingsHook.discoverAspectRatio(base: Any, ratio: Float): Any =
    method(DISCOVER_ASPECT_RATIO_CLASS, DISCOVER_ASPECT_RATIO_METHOD, DISCOVER_ASPECT_RATIO_PARAMETER_COUNT)
        .invoke(null, base, ratio, false)

private fun ReaMicroSettingsHook.paddingSides(base: Any, start: Int, top: Int, end: Int, bottom: Int): Any =
    method(PADDING_KT_CLASS, DISCOVER_PADDING_SIDES_METHOD, DISCOVER_PADDING_SIDES_PARAMETER_COUNT)
        .invoke(null, base, udp(start), udp(top), udp(end), udp(bottom))

private fun ReaMicroSettingsHook.alignmentTop(): Any =
    staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getTop")

private fun ReaMicroSettingsHook.rowScopeInstance(): Any =
    cachedRowScopeInstance ?: staticObject(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, "INSTANCE")
        .also { cachedRowScopeInstance = it }

@Volatile
private var cachedRowScopeInstance: Any? = null

@Volatile
private var cachedIconVectorMethod: Method? = null

private fun ReaMicroSettingsHook.iconVectorMethod(): Method =
    cachedIconVectorMethod ?: synchronized(DiscoverPageLock) {
        cachedIconVectorMethod ?: cls(DISCOVER_ICON_KT_CLASS).declaredMethods.first {
            it.name == DISCOVER_ICON_METHOD &&
                it.parameterTypes.size == DISCOVER_ICON_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == DISCOVER_IMAGE_VECTOR_CLASS
        }.apply { isAccessible = true }.also { cachedIconVectorMethod = it }
    }

@Volatile
private var cachedAndroidViewMethod: Method? = null

private fun ReaMicroSettingsHook.androidViewMethod(): Method =
    cachedAndroidViewMethod ?: synchronized(DiscoverPageLock) {
        cachedAndroidViewMethod ?: cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == DISCOVER_ANDROID_VIEW_PARAMETER_COUNT
        }.apply { isAccessible = true }.also { cachedAndroidViewMethod = it }
    }

private val DiscoverPageLock = Any()

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

private const val DISCOVER_TEXT_PARAMETER_COUNT = 22

private const val DISCOVER_TEXT_ELLIPSIS_CHANGED = 24960

private const val DISCOVER_SIZE_PARAMETER_COUNT = 2

private const val DISCOVER_PADDING_SIDES_METHOD = "padding-qDBjuR0"

private const val DISCOVER_PADDING_SIDES_PARAMETER_COUNT = 5

private const val DISCOVER_SPACER_PARAMETER_COUNT = 3

private const val DISCOVER_ROW_WEIGHT_PARAMETER_COUNT = 3

private const val DISCOVER_ICON_PARAMETER_COUNT = 7

private const val DISCOVER_ANDROID_VIEW_PARAMETER_COUNT = 6

private const val DISCOVER_FILL_MAX_WIDTH_PARAMETER_COUNT = 4

private const val GRID_TITLE_MAX_LINES = 4

private const val DISCOVER_FILL_MAX_WIDTH_DEFAULT_MASK = 1

private const val DISCOVER_ASPECT_RATIO_CLASS = "androidx.compose.foundation.layout.AspectRatioKt"

private const val DISCOVER_ASPECT_RATIO_METHOD = "aspectRatio"

private const val DISCOVER_ASPECT_RATIO_PARAMETER_COUNT = 3

private const val DISCOVER_ROW_SCOPE_INSTANCE_CLASS = "androidx.compose.foundation.layout.RowScopeInstance"

private const val DISCOVER_ROW_WEIGHT_METHOD = "weight"

private const val DISCOVER_ICON_KT_CLASS = "androidx.compose.material3.IconKt"

private const val DISCOVER_ICON_METHOD = "Icon-ww6aTOc"

private const val DISCOVER_IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"

private const val DISCOVER_ICON_CHANGED_MASK = 0

private const val DISCOVER_ICON_DEFAULT_MASK = 0x0

private const val DISCOVER_TOP_BAR_ACTIONS_KEY = 0x524D46A0

private const val DISCOVER_KIND_ROW_KEY = 0x524D4691

private const val DISCOVER_KIND_EXPAND_KEY = 0x524D4692

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
