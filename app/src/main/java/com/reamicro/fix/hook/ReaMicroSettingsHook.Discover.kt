package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
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
import com.reamicro.fix.hook.webdav.TEXT_OVERFLOW_CLASS
import com.reamicro.fix.hook.webdav.TEXT_SECONDARY_SINGLE_LINE_MASK
import com.reamicro.fix.hook.webdav.dp
import com.reamicro.fix.hook.webdav.normalizedAssociationPlatformName
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.search.formatOnlineUpdateTime
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.formatOnlineWordCount
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

// 「发现」页：接在线书源 `exploreUrl` 的分类书单，形态对齐宿主的搜索/筛选页。
//
// ## 版式（自上而下）
//
// ```
// [宿主 AppTopBar: ← 发现           ⚙ ⊞]  ← 本文件：actions 槽挂「配置 / 切换布局」两颗按钮
// [标签][标签][标签]…[▾]                  ← 永远一行，放不下时尾部换成「展开」按钮
// [书单：列表（封面 + 书名 + 作者 + 标签）/ 网格（三列）]
// ```
//
// 书源切换与组合筛选不在页面上占行：都收进顶栏 ⚙ 打开的配置弹窗
// （见 `ReaMicroSettingsHook.DiscoverDialogs.kt` 的 [openDiscoverConfigDialog]）。
//
// ## 顶栏 actions 怎么挂
//
// 宿主 `AppTopBar` 自 2.3.0 beta 起有 `@Composable RowScope.() -> Unit` 的尾随 actions 槽，
// [renderDiscoverTopBar] 把两颗图标按钮做成 composableLambda 填进去。actions 的内容在
// RowScope 里组合，与内容区自建 Row/Column 是同一套规则（见下）。
//
// ## 为什么可以自建容器
//
// 本页内容挂在宿主 `Scaffold` 的 `LazyColumn` 里（`renderHostLazyColumn`），在 item 内部
// 自建 `Row` / `Column` / `Box` 是安全的——`DiscoverButtonRenderer` 顶部那条「注入点不能
// 自建容器」的约束只针对宿主社区卡片那个 `Icon` 兄弟节点位，与整页注入无关。
//
// 但有一条铁律照旧：容器的 content 形参是 `@Composable Scope.() -> Unit`，必须用
// [composableLambda] + `FUNCTION3_CLASS` 且取 `args[1]` 作为内层 Composer；类型错了会在
// LazyList 的 `GapComposer.finalizeCompose` 抛 `Start/end imbalance`，表现是点进页面直接闪退。
//
// ## 文字为什么统一走 [renderDiscoverText]
//
// 宿主 `Text-Nvy7gAk` 的 22 个形参里有一串被 `$default` 掩码控制的默认值，掩码位序不是靠
// 读源码能确定的。这里的做法是**逐字复制**已经跑通的 `renderHostText` 实参序列，只替换
// `text` / `color` / `style` 三处——这三处在 `renderHostText`、`renderHostSupportingText`、
// `renderHostTrailingText` 之间本来就是自由变量（三者的差异正好只有这三处），因此
// `TEXT_DEFAULT_MASK` 的语义保持成立。
//
// 代价是**拿不到 `modifier` 形参**（掩码让它走默认值）。所以内边距与尺寸一律加在外层
// 容器上，不挂文字。单行省略后来改传 `TextOverflow.Ellipsis`（见 [renderDiscoverText]），
// 不再依赖数据侧截断。

/** 页面入口：标签行 + 书单两段（书源切换/布局切换/组合筛选都在顶栏与配置弹窗里）。 */
internal fun ReaMicroSettingsHook.renderDiscoverContent(innerPaddings: Any, composer: Any) {
    val appContext = activityProvider()?.applicationContext
    val listContent = functionProxy("DiscoverList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        // 读取刷新信号建立重组依赖；后台加载完成会 bump 触发本页重渲。
        discoverVersionValue()
        // 源列表只解析本地已经配置好的书源（不联网），首次进入时同步刷新一次。
        // 放在这里而不是 hook 安装时：书源可能在会话中途导入，进页面时重新解析最准。
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

        // 段 1：分类标签（含组合筛选生成的合成分类）。只有一行——放不下时尾部换成「展开」。
        if (current != null && current.kinds.isNotEmpty()) {
            addLazyItem(lazyListScope, DISCOVER_KIND_ROW_ITEM_KEY) { itemComposer ->
                renderDiscoverKindRow(current, selection, itemComposer)
            }
        }

        // 段 2：书单。
        renderDiscoverBooks(lazyListScope, current, selection)
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

// ── 顶栏（标题行右侧的「配置 / 切换布局」按钮） ────────────────────────────────

/**
 * 发现页专用顶栏：标题 + 返回照旧交给宿主 `AppTopBar`，右侧 actions 槽挂两颗图标按钮。
 *
 * actions 槽的 content 是 `@Composable RowScope.() -> Unit`，与本页其它容器同规则。
 * 读 [discoverVersionValue] 建立重组依赖：切换布局后图标要翻面、应用筛选后标签行刷新，
 * 都由同一个版本号驱动。
 */
internal fun ReaMicroSettingsHook.renderDiscoverTopBar(title: String, composer: Any) {
    val actions = composableLambda(DISCOVER_TOP_BAR_ACTIONS_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        discoverVersionValue()
        val tint = schemeColor(inner, "getOnSurfaceVariant", "getOnBackground")
        renderDiscoverConfigButton(tint, inner)
        renderDiscoverLayoutToggle(tint, inner)
        targetUnit()
    }
    invokeAppTopBar(
        title = title,
        composer = composer,
        onBack = { navigateBackFromInjectedRoute() },
        actions = actions,
    )
}

/** 「配置」按钮（齿轮）：点开配置弹窗（书源切换 + 组合筛选）。 */
private fun ReaMicroSettingsHook.renderDiscoverConfigButton(tint: Long, composer: Any) {
    val activity = activityProvider()
    val content = composableLambda(DISCOVER_CONFIG_BUTTON_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverIcon(DiscoverUiIcons.settingsGear(classLoader), tint, inner)
        targetUnit()
    }
    val modifier = clickableModifier(
        paddingSides(
            modifierInstance(),
            start = DISCOVER_LAYOUT_TOGGLE_PADDING_DP,
            top = DISCOVER_ROW_VERTICAL_PADDING_DP,
            end = 0,
            bottom = DISCOVER_ROW_VERTICAL_PADDING_DP,
        ),
        "DiscoverConfig",
    ) {
        if (activity != null) openDiscoverConfigDialog(activity)
    }
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, modifier, alignmentCenter(), false, content, composer, 0, 0)
}

/** 没有任何带 `exploreUrl` 的书源时的兜底提示。 */
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
        )
    }
}

// ── 顶栏按钮：布局切换 ────────────────────────────────────────────────────────

/**
 * 布局切换按钮（顶栏 actions 槽右数第一颗）。
 *
 * 图标语义是「点它会变成什么」：当前网格态显示列表图标，反之显示网格图标——与宿主自己的
 * 切换按钮一致，也比「显示当前状态」少一次心智换算。
 */
private fun ReaMicroSettingsHook.renderDiscoverLayoutToggle(tint: Long, composer: Any) {
    val activity = activityProvider()
    val icon = if (DiscoverState.layout == DiscoverLayout.GRID) {
        DiscoverUiIcons.layoutList(classLoader)
    } else {
        DiscoverUiIcons.layoutGrid(classLoader)
    }
    val content = composableLambda(DISCOVER_LAYOUT_TOGGLE_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverIcon(icon, tint, inner)
        targetUnit()
    }
    // end 留出与左上角返回键一致的屏幕边距（实测返回键图形距左缘约 18dp，
    // actions 槽自身带约 4dp 内缩，这里再补 10dp 后左右图形边距对齐）。
    val modifier = clickableModifier(
        paddingSides(
            modifierInstance(),
            start = DISCOVER_LAYOUT_TOGGLE_PADDING_DP,
            top = DISCOVER_ROW_VERTICAL_PADDING_DP,
            end = DISCOVER_TOP_BAR_END_PADDING_DP,
            bottom = DISCOVER_ROW_VERTICAL_PADDING_DP,
        ),
        "DiscoverLayoutToggle",
    ) {
        DiscoverState.toggleLayout(activity?.applicationContext)
    }
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, modifier, alignmentCenter(), false, content, composer, 0, 0)
}

// ── 段 1：分类标签行 ────────────────────────────────────────────────────────

/**
 * 分类标签行——**永远一行**。
 *
 * 放得下就全放；放不下就把尾部换成一颗「▾」展开按钮，点开是分类弹窗。
 * 标签列表走 [DiscoverState.kindsFor]：平铺分类之外，当前源应用过组合筛选时
 * 末尾多一颗合成分类（如「字数最多·起点」），选中态与普通标签一致。
 *
 * ## 为什么用预测量而不是等布局结束再判断
 *
 * Compose 里要「渲染完再决定显示几个」需要 `Modifier.layout` / `SubcomposeLayout` 这类
 * 自定义布局，模块拿不到（不编译 Compose，全靠反射调宿主已有函数）。所以反过来做：
 * 先用 `Paint` 把每个标签的宽度量出来（含胶囊内边距与标签间距），加上展开按钮的预留宽度，
 * 算出能放几个，再决定渲染哪几个。测量字号与渲染一致（`labelLarge` = 14sp），
 * 余量由 [DISCOVER_KIND_ROW_SAFETY_DP] 吸收。
 */
private fun ReaMicroSettingsHook.renderDiscoverKindRow(
    source: DiscoverSource,
    selection: DiscoverSelection,
    composer: Any,
) {
    val kinds = DiscoverState.kindsFor(source)
    val visibleCount = visibleKindCount(kinds)
    val overflow = visibleCount < kinds.size

    val content = composableLambda(DISCOVER_KIND_ROW_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        kinds.take(visibleCount).forEachIndexed { index, kind ->
            if (index > 0) renderDiscoverSpacer(inner, DISCOVER_KIND_GAP_DP)
            renderDiscoverKindChip(source, kind, selection, inner)
        }
        if (overflow) {
            if (visibleCount > 0) renderDiscoverSpacer(inner, DISCOVER_KIND_GAP_DP)
            renderDiscoverKindExpandButton(inner)
        }
        targetUnit()
    }
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        discoverFillMaxWidth(
            paddingSides(
                modifierInstance(),
                start = 0,
                top = DISCOVER_KIND_ROW_TOP_GAP_DP,
                end = 0,
                bottom = 0,
            ),
        ),
        arrangementStart(),
        alignmentCenterVertically(),
        content,
        composer,
        0,
        0,
    )
}

/** 尾部那颗「▾」：点开分类弹窗。宽度与胶囊一致，避免它看着像掉队的小图标。 */
private fun ReaMicroSettingsHook.renderDiscoverKindExpandButton(composer: Any) {
    val activity = activityProvider()
    val tint = schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    val content = composableLambda(DISCOVER_KIND_EXPAND_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverIcon(DiscoverUiIcons.chevronDown(classLoader), tint, inner)
        targetUnit()
    }
    val width = method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(DISCOVER_KIND_EXPAND_WIDTH_DP))
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, width, udp(DISCOVER_KIND_CHIP_HEIGHT_DP))
    val modifier = clickableModifier(sized, "DiscoverKindExpand") {
        if (activity != null) openDiscoverKindDialog(activity)
    }
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, modifier, alignmentCenter(), false, content, composer, 0, 0)
}

/** 一个分类胶囊：选中态用宿主主色填充，未选中**不画底色**（纯文字）。 */
private fun ReaMicroSettingsHook.renderDiscoverKindChip(
    source: DiscoverSource,
    kind: DiscoverKind,
    selection: DiscoverSelection,
    composer: Any,
) {
    val scheme = colorScheme(composer)
    val selected = kind.title == selection.kindTitle
    val foreground = if (selected) {
        scheme.longMethod("getOnPrimary")
    } else {
        schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    }
    val activity = activityProvider()
    val shape = method(SHAPE_KT_CLASS, ROUNDED_SHAPE_METHOD, 0).invoke(null)
    val clipped = method(CLIP_KT_CLASS, CLIP_METHOD, 2).invoke(null, modifierInstance(), shape)
    val padded = method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5).invoke(
        null,
        clipped,
        udp(DISCOVER_KIND_CHIP_PADDING_DP),
        0f,
        2,
        null,
    )
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, padded, udp(DISCOVER_KIND_CHIP_HEIGHT_DP))
    // 未选中不铺底色——宿主这套主题下 surfaceVariant 是淡黄色，观感偏脏。
    // 选中态用宿主主题主色填充，并把 `shape` **显式传进 background**（mask = 0）：
    // background 的默认 shape 是 RectangleShape，之前靠 `clip` 裁剪会让底色棱角发方，
    // 与同一行的其它元素不同构（用户报的「选中底色太方」）。
    val filled = if (selected) {
        method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5)
            .invoke(null, sized, scheme.longMethod("getPrimary"), shape, 0, null)
    } else {
        sized
    }
    val clickable = clickableModifier(filled, "DiscoverKind${kind.title}") {
        DiscoverState.select(source.source.id, kind.title, activity?.applicationContext)
    }
    val content = composableLambda(kind.title.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        renderDiscoverText(kind.title, foreground, textStyle(inner, "getLabelLarge", "getBodyMedium"), inner)
        targetUnit()
    }
    method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(null, clickable, alignmentCenter(), false, content, composer, 0, 0)
}

// ── 段 3：书单 ──────────────────────────────────────────────────────────────

/** 加载态 / 失败态 / 空态 / 列表 / 网格。 */
private fun ReaMicroSettingsHook.renderDiscoverBooks(
    lazyListScope: Any,
    source: DiscoverSource?,
    selection: DiscoverSelection,
) {
    when (val state = DiscoverState.state) {
        DiscoverLoadState.Idle, DiscoverLoadState.Loading -> {
            addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY) { itemComposer ->
                renderDiscoverStatusCard("正在加载…", selection.kindTitle, null, itemComposer)
            }
        }

        is DiscoverLoadState.Failed -> {
            addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY) { itemComposer ->
                renderDiscoverStatusCard("加载失败", state.message, {
                    DiscoverState.reload(activityProvider()?.applicationContext)
                }, itemComposer)
            }
        }

        is DiscoverLoadState.Loaded -> {
            if (state.books.isEmpty()) {
                addLazyItem(lazyListScope, DISCOVER_BOOK_STATUS_ITEM_KEY) { itemComposer ->
                    renderDiscoverStatusCard("暂无内容", selection.kindTitle, {
                        DiscoverState.reload(activityProvider()?.applicationContext)
                    }, itemComposer)
                }
                return
            }
            if (source == null) return
            if (DiscoverState.layout == DiscoverLayout.GRID) {
                state.books.chunked(DISCOVER_GRID_COLUMNS).forEachIndexed { rowIndex, rowBooks ->
                    addLazyItem(lazyListScope, DISCOVER_GRID_ROW_ITEM_KEY_BASE + rowIndex) { itemComposer ->
                        renderDiscoverGridRow(source, rowBooks, itemComposer)
                    }
                }
            } else {
                // 一本书一个 item（而不是整张卡片一个 item）。
                //
                // 曾经为了去掉「每 6 本一条空白带」改成整张卡片单 item，结果一次性往 LazyColumn
                // 里插入几百个节点，触发了 Compose 的 `UiApplier.insertBottomUp` →
                // `MutableVector.add` 越界崩溃（`srcPos=5 dstPos=6 length=-3`）。
                // 逐行 item 每次只插入十来个节点，且封面天然懒加载（进入页面不再同时发起几十个请求），
                // 代价是失去卡片背景——与宿主自己的搜索结果页一致，行距也回到页面的统一节奏。
                state.books.forEachIndexed { index, book ->
                    addLazyItem(lazyListScope, DISCOVER_LIST_ROW_ITEM_KEY_BASE + index) { itemComposer ->
                        renderDiscoverListRow(source, book, itemComposer)
                    }
                }
            }
            if (DiscoverState.hasMore) {
                addLazyItem(lazyListScope, DISCOVER_LOAD_MORE_ITEM_KEY) { itemComposer ->
                    renderDiscoverLoadMore(itemComposer)
                }
            }
        }
    }
}

/**
 * 书单末尾的「加载更多」行。
 *
 * Legado 的发现页是滚动到底自动翻页，那需要 LazyListState（反射拿不到），所以翻页入口
 * 做成显式按钮；加载中同一行变成状态文案，空页后由 `hasMore=false` 收起整行。
 */
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
    )
}

/** 加载 / 失败 / 空的统一卡片，整块可点表示重试。 */private fun ReaMicroSettingsHook.renderDiscoverStatusCard(
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
    )
}

/** 列表模式的一本书：封面 +（书名 / 作者·最新 / 状态·字数·平台 / 分类标签）。 */
private fun ReaMicroSettingsHook.renderDiscoverListRow(
    source: DiscoverSource,
    book: DiscoverBook,
    composer: Any,
) {
    val titleColor = colorScheme(composer).longMethod("getOnBackground")
    val metaColor = schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    val info = discoverTagInfo(book, source)
    val titleStyle = textStyle(composer, "getTitleSmall", "getBodyMedium")
    val bodyStyle = textStyle(composer, "getBodySmall", "getBodyMedium")
    val labelStyle = textStyle(composer, "getLabelSmall", "getBodySmall")
    // 单行省略交给 Compose 自己做（[renderDiscoverText] 传了 TextOverflow.Ellipsis）。
    // 不要再用 Paint 预截断：测量字号/字体与真实渲染存在设备级偏差，预截断会让省略号
    // 提前好几十 dp 落地，而且 Compose 看到的是已带 `…` 的短串，不会再补省略。
    val title = book.name
    val latestLine = book.latestLine()
    val coreLine = discoverCoreTagLine(book, source)
    val tagLine = info.kindText

    val rowContent = composableLambda(DISCOVER_LIST_ROW_KEY + book.key.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        if (book.coverUrl.isNotBlank()) {
            renderDiscoverCover(source, book, DISCOVER_LIST_COVER_WIDTH_DP, DISCOVER_LIST_COVER_HEIGHT_DP, inner)
            renderDiscoverSpacer(inner, DISCOVER_LIST_COVER_GAP_DP)
        }
        val textContent = composableLambda(DISCOVER_LIST_TEXT_KEY + book.key.hashCode(), FUNCTION3_CLASS) { colArgs ->
            val colInner = colArgs?.getOrNull(1) ?: return@composableLambda targetUnit()
            renderDiscoverText(title, titleColor, titleStyle, colInner)
            if (latestLine.isNotBlank()) {
                renderDiscoverVGap(colInner, DISCOVER_LIST_LINE_GAP_DP)
                renderDiscoverText(latestLine, metaColor, bodyStyle, colInner)
            }
            if (coreLine.isNotBlank()) {
                renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
                renderDiscoverText(coreLine, metaColor, bodyStyle, colInner)
            }
            if (tagLine.isNotBlank()) {
                renderDiscoverVGap(colInner, DISCOVER_LIST_TAG_GAP_DP)
                renderDiscoverText(tagLine, metaColor, labelStyle, colInner)
            }
            targetUnit()
        }
        // 文字列要吃掉封面之外的剩余宽度：weight 是 parent-data，必须挂在 Column 自己身上。
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
    val rowModifier = clickableModifier(
        discoverFillMaxWidth(
            paddingSides(
                modifierInstance(),
                start = DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP,
                top = DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
                end = DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP,
                bottom = DISCOVER_LIST_ROW_VERTICAL_PADDING_DP,
            ),
        ),
        "DiscoverBook${book.key.hashCode()}",
    ) {
        openDiscoverBook(source, book)
    }
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

/**
 * 网格模式的一行（最多三本）。
 *
 * 每格 `weight(1f)` 等分，封面宽度由格子宽度决定，所以封面高度必须按可用宽度**反算**
 * ——见 [discoverGridCoverHeightDp]。
 */
private fun ReaMicroSettingsHook.renderDiscoverGridRow(
    source: DiscoverSource,
    books: List<DiscoverBook>,
    composer: Any,
) {
    val titleColor = colorScheme(composer).longMethod("getOnBackground")
    val metaColor = schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    val coverHeightDp = discoverGridCoverHeightDp()

    val content = composableLambda(DISCOVER_GRID_ROW_KEY, FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        books.forEach { book ->
            val cellContent = composableLambda(DISCOVER_GRID_CELL_KEY + book.key.hashCode(), FUNCTION3_CLASS) { cellArgs ->
                val cellInner = cellArgs?.getOrNull(1) ?: return@composableLambda targetUnit()
                renderDiscoverCover(source, book, null, coverHeightDp, cellInner)
                renderDiscoverVGap(cellInner, DISCOVER_GRID_TITLE_GAP_DP)
                renderDiscoverText(
                    book.name,
                    titleColor,
                    textStyle(cellInner, "getBodyMedium", "getBodySmall"),
                    cellInner,
                )
                if (book.author.isNotBlank()) {
                    renderDiscoverVGap(cellInner, DISCOVER_GRID_AUTHOR_GAP_DP)
                    renderDiscoverText(
                        book.author,
                        metaColor,
                        textStyle(cellInner, "getLabelSmall", "getBodySmall"),
                        cellInner,
                    )
                }
                targetUnit()
            }
            val cellModifier = clickableModifier(
                method(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, DISCOVER_ROW_WEIGHT_METHOD, DISCOVER_ROW_WEIGHT_PARAMETER_COUNT)
                    .invoke(rowScopeInstance(), modifierInstance(), 1f, true),
                "DiscoverGridBook${book.key.hashCode()}",
            ) {
                openDiscoverBook(source, book)
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
        // 末行不满三格时补等宽占位，否则前面几格会因为 weight 摊分而变宽、与上一行错位。
        repeat(DISCOVER_GRID_COLUMNS - books.size) {
            val filler = method(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, DISCOVER_ROW_WEIGHT_METHOD, DISCOVER_ROW_WEIGHT_PARAMETER_COUNT)
                .invoke(rowScopeInstance(), modifierInstance(), 1f, true)
            method(SPACER_KT_CLASS, SPACER_METHOD, DISCOVER_SPACER_PARAMETER_COUNT).invoke(null, filler, inner, 0)
        }
        targetUnit()
    }
    method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
        null,
        discoverFillMaxWidth(modifierInstance()),
        spacedBy(DISCOVER_GRID_GAP_DP),
        alignmentTop(),
        content,
        composer,
        0,
        0,
    )
}

// ── 通用小件 ────────────────────────────────────────────────────────────────

/**
 * 通用文字：实参序列逐字复制自宿主 `Text-Nvy7gAk`，只放开 `text` / `color` / `style`。
 *
 * 22 个实参一个都不能少也不能错位（掩码假定其余形参走默认值）。
 *
 * ## 单行为什么带 `TextOverflow.Ellipsis`
 *
 * 曾用 `fitText`（Paint 预量宽度）做数据侧截断，但测量画笔的字号/字体与实际渲染存在
 * 设备级偏差（宿主可在组合里覆盖 Density 的 fontScale，声明 12sp 实渲 10sp 左右），
 * 截断点会提前百分之十几——同一份书单在测试机上正好顶到行末、在窄屏机上却大片提前收尾。
 *
 * 现在**单行省略交给 Compose 自己做**：照抄在线补全搜索结果行
 * （`renderOnlineCompletionSecondaryText`）已验证的实参组合——
 * `overflow=Ellipsis`(index 12)、`softWrap=false`、`maxLines=1`(index 14)、
 * `changed=(0, 24960)`、`TEXT_SECONDARY_SINGLE_LINE_MASK`(110586)。
 * 掩码 bit12 清零表示 overflow 形参走显式值；Compose 用真实字体度量截断，
 * 任何设备上省略号都精确落在行末。
 */
private fun ReaMicroSettingsHook.renderDiscoverText(
    text: String,
    color: Long,
    style: Any,
    composer: Any,
    singleLine: Boolean = true,
) {
    if (!singleLine) {
        method(TEXT_KT_CLASS, TEXT_METHOD, DISCOVER_TEXT_PARAMETER_COUNT).invoke(
            null,
            text,
            null,
            color,
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            0,
            false,
            0,
            0,
            null,
            style,
            composer,
            0,
            0,
            TEXT_DEFAULT_MASK,
        )
        return
    }
    method(TEXT_KT_CLASS, TEXT_METHOD, DISCOVER_TEXT_PARAMETER_COUNT).invoke(
        null,
        text,
        null,
        color,
        null,
        0L,
        null,
        null,
        null,
        0L,
        null,
        null,
        0L,
        // index 12：`TextOverflow` 是 Int inline class（0=Clip / 1=Ellipsis）。
        discoverTextOverflowEllipsis(),
        false,
        1,
        0,
        null,
        style,
        composer,
        0,
        DISCOVER_TEXT_ELLIPSIS_CHANGED,
        TEXT_SECONDARY_SINGLE_LINE_MASK,
    )
}

/** `TextOverflow.Ellipsis` 的 Int 形态（inline class 装箱后就是 Int）。 */
private fun ReaMicroSettingsHook.discoverTextOverflowEllipsis(): Int =
    staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getEllipsis") as Int

/** 固定宽度的占位。宿主没有导出 `Spacer` 的宽度工厂，用 `width` 修饰符撑开。 */
private fun ReaMicroSettingsHook.renderDiscoverSpacer(composer: Any, widthDp: Int) {
    val modifier = method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(widthDp))
    method(SPACER_KT_CLASS, SPACER_METHOD, DISCOVER_SPACER_PARAMETER_COUNT).invoke(null, modifier, composer, 0)
}

/**
 * 纵向占位：给 `height` 修饰符。
 *
 * [renderDiscoverSpacer] 挂的是 `width`——在 Row（横向主轴）里正确，但放进 Column
 * （纵向主轴）后高度恒为 0，**间距整个消失**。网格封面和书名贴死、列表四行挤成一片，
 * 都是它造成的。纵向间距一律用这个函数。
 */
private fun ReaMicroSettingsHook.renderDiscoverVGap(composer: Any, heightDp: Int) {
    val modifier = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(heightDp))
    method(SPACER_KT_CLASS, SPACER_METHOD, DISCOVER_SPACER_PARAMETER_COUNT).invoke(null, modifier, composer, 0)
}

/** 图标：走宿主 `Icon`，颜色由 tint 决定（矢量自身的填充色会被 `ColorFilter.tint` 覆盖）。 */
private fun ReaMicroSettingsHook.renderDiscoverIcon(icon: Any?, tint: Long, composer: Any) {
    if (icon == null) return
    iconVectorMethod().invoke(
        null,
        icon,
        null,
        modifierInstance(),
        tint,
        composer,
        DISCOVER_ICON_CHANGED_MASK,
        DISCOVER_ICON_DEFAULT_MASK,
    )
}

/**
 * 网络封面。
 *
 * 只有原生 `ImageView` 这一条路：模块从没把 Bitmap 包成 Compose 的 `ImageBitmap`，
 * 而宿主在线补全的搜索结果行已经有一份成熟的封面加载器
 * （`WebDavDriveHook.loadOnlineCompletionSearchCover`，带书源 header / 登录态 / 明文放行），
 * 这里用 `AndroidView` 把 ImageView 塞进 Compose，直接复用那条链路。
 *
 * 尺寸由调用方给：`widthDp` 为 null 表示占满可用宽度（网格用），否则固定尺寸（列表用）。
 */
private fun ReaMicroSettingsHook.renderDiscoverCover(
    source: DiscoverSource,
    book: DiscoverBook,
    widthDp: Int?,
    heightDp: Int,
    composer: Any,
) {
    val factory = functionProxy("DiscoverCoverFactory", FUNCTION1_CLASS) { args ->
        val context = args?.getOrNull(0) as? Context ?: activityProvider()
            ?: error("no context for discover cover")
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedDrawable(
                DISCOVER_COVER_PLACEHOLDER_COLOR,
                context.dp(DISCOVER_COVER_CORNER_DP).toFloat(),
            )
        }
    }
    val update = functionProxy("DiscoverCoverUpdate", FUNCTION1_CLASS) { args ->
        val view = args?.getOrNull(0) as? ImageView
        if (view != null && book.coverUrl.isNotBlank()) {
            loadDiscoverCover(view, source.source, book.coverUrl)
        }
        targetUnit()
    }
    val base = if (widthDp == null) {
        discoverFillMaxWidth(modifierInstance())
    } else {
        method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
            .invoke(null, modifierInstance(), udp(widthDp))
    }
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
        .invoke(null, base, udp(heightDp))
    androidViewMethod().invoke(null, factory, sized, update, composer, 0, 0)
}

// ── 封面加载（缓存 + 并发闸门 + 降采样） ─────────────────────────────────────

/** 封面内存缓存，按 KB 计——书单条目常在几十本量级，全尺寸 Bitmap 留十几 MB 足够。 */
private val discoverCoverCache = object : android.util.LruCache<String, Bitmap>(DISCOVER_COVER_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/** 封面下载线程池：固定四条。快速滚动时新进入视口的行是突发性的，无上限的裸线程会把网络与解码同时打满。 */
private val discoverCoverPool = java.util.concurrent.Executors.newFixedThreadPool(DISCOVER_COVER_THREADS) { runnable ->
    Thread(runnable, "ReaMicroDiscoverCover").apply { isDaemon = true }
}

/**
 * 加载一张封面。
 *
 * 请求参数与 `loadOnlineCompletionSearchCover` 完全同一套（书源 header / 登录态 / 明文放行 /
 * `data:image` 内联图），多出两件事：
 *
 * 1. **内存缓存**——滚动卡顿的主因就是每个新建的 `AndroidView` 都要重新走一遍网络：
 *    行滚出视口被回收，滚回来又是全新 View、全新请求。命中缓存后回滚是零开销的。
 * 2. **按显示尺寸降采样**——列表封面只有 46×62dp，原图常是几百像素宽，全尺寸解码既慢又占内存。
 */
private fun loadDiscoverCover(imageView: ImageView, source: OnlineSourceEntry, coverUrl: String) {
    val hook = WebDavDriveHook.activeInstance ?: return
    val url = runCatching { hook.normalizeOnlineCoverUrl(source, sourceBaseUrl(source), coverUrl) }
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
        val bitmap = runCatching { downloadDiscoverCover(hook, source, url) }.getOrNull() ?: return@execute
        discoverCoverCache.put(url, bitmap)
        Handler(Looper.getMainLooper()).post {
            // View 可能已被 LazyColumn 回收并复用给别人，比对 tag 再贴图，避免串图。
            if (imageView.tag == url) imageView.setImageBitmap(bitmap)
        }
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
            hook.parseOnlineHeaders(source.header).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
            OnlineSourceAuth.requestHeaders(currentApplicationContext(), source, url).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
        }
    }
    return try {
        connection.inputStream.use { decodeDiscoverCoverBytes(it.readBytes()) }
    } finally {
        connection.disconnect()
    }
}

/** 先量边界再选 `inSampleSize`：解码内存比全尺寸小一半以上，肉眼看不出差别。 */
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

// ── 计算与工具 ──────────────────────────────────────────────────────────────

/**
 * 标签行能放下几个。
 *
 * 至少留一个：哪怕一个都放不下也要显示第一个标签，否则整行会变成孤零零一颗「展开」按钮。
 */
private fun ReaMicroSettingsHook.visibleKindCount(kinds: List<DiscoverKind>): Int {
    if (kinds.isEmpty()) return 0
    val activity = activityProvider() ?: return kinds.size
    val metrics = activity.resources.displayMetrics
    val density = metrics.density
    if (density <= 0f || metrics.widthPixels <= 0) return kinds.size

    // 页面左右各 16dp（pageModifier 里写死的），再留一段余量吸收 letterSpacing 与全局字体差异。
    val availablePx = metrics.widthPixels -
        (DISCOVER_PAGE_HORIZONTAL_PADDING_DP * 2 + DISCOVER_KIND_ROW_SAFETY_DP) * density
    if (availablePx <= 0f) return kinds.size

    val paint = discoverMeasurePaint(DISCOVER_KIND_TEXT_SIZE_SP)
    val chipPadding = DISCOVER_KIND_CHIP_PADDING_DP * 2 * density
    val gap = DISCOVER_KIND_GAP_DP * density
    val expandWidth = (DISCOVER_KIND_EXPAND_WIDTH_DP + DISCOVER_KIND_GAP_DP) * density

    var used = 0f
    for (index in kinds.indices) {
        val chipWidth = paint.measureText(kinds[index].title) + chipPadding
        val next = if (index == 0) chipWidth else used + gap + chipWidth
        // 后面还有标签时必须给「展开」按钮留位置，否则它会顶出屏幕。
        val reserve = if (index < kinds.lastIndex) expandWidth else 0f
        if (next + reserve > availablePx) return index.coerceAtLeast(1)
        used = next
    }
    return kinds.size
}

/** 网格封面高度：按可用宽度反算，保持 3:4 的封面比例。 */
private fun ReaMicroSettingsHook.discoverGridCoverHeightDp(): Int {
    val activity = activityProvider() ?: return DISCOVER_GRID_COVER_FALLBACK_HEIGHT_DP
    val metrics = activity.resources.displayMetrics
    val density = metrics.density
    if (density <= 0f || metrics.widthPixels <= 0) return DISCOVER_GRID_COVER_FALLBACK_HEIGHT_DP
    val contentDp = metrics.widthPixels / density - DISCOVER_PAGE_HORIZONTAL_PADDING_DP * 2
    val cellDp = (contentDp - DISCOVER_GRID_GAP_DP * (DISCOVER_GRID_COLUMNS - 1)) / DISCOVER_GRID_COLUMNS
    return (cellDp * DISCOVER_COVER_ASPECT_HEIGHT / DISCOVER_COVER_ASPECT_WIDTH)
        .toInt()
        .coerceIn(DISCOVER_GRID_COVER_MIN_HEIGHT_DP, DISCOVER_GRID_COVER_MAX_HEIGHT_DP)
}

/**
 * 书单行的标签串。
 *
 * 顺序与在线补全搜索结果的元信息列一致：
 * **状态 → 字数 → 章节数 → 平台 → 更新时间 → 其余（分类等）**。
 * 取不到的整段跳过，不占位。**不在这里截断**——单行省略由 [renderDiscoverText] 交给 Compose 处理。
 *
 * 结果按「源 + 书」缓存：滚动时每一帧都要重算，而里面的平台识别要跑正则与映射表。
 */
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

/**
 * 一本书识别出来的「平台名 + 剩余分类文本」。
 *
 * 分类字段常把平台写在最前（`qidian 都市`）。识别出平台后要把那段 token 从分类里去掉，
 * 否则同一件事会在标签串里出现两次（「起点中文网 / qidian 都市」）。
 */
private class DiscoverTagInfo(val platformName: String, val kindText: String)

private val discoverTagInfoCache = java.util.concurrent.ConcurrentHashMap<String, DiscoverTagInfo>()

private val discoverTagLineCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun discoverCacheKey(book: DiscoverBook, source: DiscoverSource): String =
    "${source.source.id}|${book.key}"

/** 缓存超过上限就整块清掉——书单条目量级只有几十，不需要精细淘汰。 */
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

/**
 * 平台识别的实际逻辑。
 *
 * 用「关联」功能那张识别表（[normalizedAssociationPlatformName]）：分类词 → 详情地址 →
 * 封面地址 → 书源名，命中才认。**命中判定必须是 `mapped != clean`**——那个函数识别不出来时
 * 会把原样字符串返回，直接采用会把「都市」这种普通分类当成平台名。
 */
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

/** 第二行：作者 · 最新：<最新章节>。缺哪个就只显示另一个。 */
private fun DiscoverBook.latestLine(): String = buildList {
    if (author.isNotBlank()) add(author)
    if (lastChapter.isNotBlank()) add("最新：$lastChapter")
}.joinToString(" \u00b7 ")

/**
 * 第三行：状态 / 字数 / 平台。
 *
 * 更新时间与分类不再混在这一行——第四行（分类标签）与「最新章节」已经把新鲜度交代清楚。
 */
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

/**
 * 测量画笔：一个实例按需换字号，字体取模块的全局字体。
 *
 * 只服务分类标签行的溢出预测量。与真实渲染仍可能存在设备级偏差，
 * 所以文字本身的省略一律交给 Compose（[renderDiscoverText]），不要再用画笔结果截断正文。
 */
private fun ReaMicroSettingsHook.discoverMeasurePaint(sizeSp: Float): Paint {
    val scaledDensity = activityProvider()?.resources?.displayMetrics?.scaledDensity ?: 1f
    val paint = cachedMeasurePaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
        runCatching { typeface = WebDavDriveHook.activeInstance?.globalTypefaceProvider?.invoke() }
        cachedMeasurePaint = this
    }
    paint.textSize = sizeSp * scaledDensity
    paint.letterSpacing = DISCOVER_TEXT_LETTER_SPACING_EM
    return paint
}

/**
 * 点击一本发现书：先给一次下载确认，再交给在线补全的下载流程。
 *
 * 发现页书单与在线搜索结果是同一类东西（同一个源、同一套规则解析出来的条目），所以下载
 * 完全复用宿主那条链路（限流、通知、任务记录都在那边）。这里只负责两件事：确认地址可用，
 * 以及把「点了没反应」这个体感问题去掉——见 [openDiscoverDownloadDialog]。
 */
private fun ReaMicroSettingsHook.openDiscoverBook(source: DiscoverSource, book: DiscoverBook) {
    val activity = activityProvider() ?: return
    if (book.detailUrl.isBlank()) {
        logDiscover("book has no detail url: ${book.name}")
        return
    }
    if (WebDavDriveHook.activeInstance == null) {
        logDiscover("online hook unavailable, cannot download: ${book.name}")
        return
    }
    openDiscoverDownloadDialog(activity, source, book)
}

private fun ReaMicroSettingsHook.logDiscover(message: String) {
    XposedBridge.log("$LOG_PREFIX discover $message")
}

// ── 反射小工具 ──────────────────────────────────────────────────────────────

private fun ReaMicroSettingsHook.schemeColor(composer: Any, name: String, fallback: String): Long =
    runCatching { colorScheme(composer).longMethod(name) }
        .getOrElse { colorScheme(composer).longMethod(fallback) }

private fun ReaMicroSettingsHook.textStyle(composer: Any, name: String, fallback: String): Any =
    runCatching { typography(composer).method0(name) }
        .getOrElse { typography(composer).method0(fallback) }

/** `fillMaxWidth`。刻意与 `DiscoverButtonRenderer.fillMaxWidthModifier` 区分开命名，避免同包重载歧义。 */
private fun ReaMicroSettingsHook.discoverFillMaxWidth(base: Any): Any =
    method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, DISCOVER_FILL_MAX_WIDTH_PARAMETER_COUNT)
        .invoke(null, base, 0f, DISCOVER_FILL_MAX_WIDTH_DEFAULT_MASK, null)

private fun ReaMicroSettingsHook.paddingSides(base: Any, start: Int, top: Int, end: Int, bottom: Int): Any =
    method(PADDING_KT_CLASS, DISCOVER_PADDING_SIDES_METHOD, DISCOVER_PADDING_SIDES_PARAMETER_COUNT)
        .invoke(null, base, udp(start), udp(top), udp(end), udp(bottom))

private fun ReaMicroSettingsHook.alignmentTop(): Any =
    staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getTop")

/**
 * `RowScopeInstance` 是宿主 Compose 的单例，`weight` 只往 Modifier 上挂 parent-data，
 * 与实例状态无关，因此可以直接取单例而不必从宿主某次 `Row` 调用里捕获 scope。
 */
private fun ReaMicroSettingsHook.rowScopeInstance(): Any =
    cachedRowScopeInstance ?: staticObject(DISCOVER_ROW_SCOPE_INSTANCE_CLASS, "INSTANCE")
        .also { cachedRowScopeInstance = it }

@Volatile
private var cachedRowScopeInstance: Any? = null

@Volatile
private var cachedIconVectorMethod: Method? = null

/**
 * `IconKt.Icon-ww6aTOc` 的 ImageVector 版。
 *
 * `Icon` 与 `Image` 一样按首参分三个重载（`ImageBitmap` / `Painter` / `ImageVector`），
 * **参数个数都是 7**，只按名字 + 个数取会拿到 ImageBitmap 那个，运行时报
 * `argument 1 has type ImageBitmap, got ImageVector`——而且异常会被 functionProxy 吞掉，
 * 症状是「图标整个不显示」而不是崩溃，很容易误判成图标构建失败。
 */
private fun ReaMicroSettingsHook.iconVectorMethod(): Method =
    cachedIconVectorMethod ?: synchronized(DiscoverPageLock) {
        cachedIconVectorMethod ?: cls(DISCOVER_ICON_KT_CLASS).declaredMethods.first {
            it.name == DISCOVER_ICON_METHOD &&
                it.parameterTypes.size == DISCOVER_ICON_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == DISCOVER_IMAGE_VECTOR_CLASS
        }.apply { isAccessible = true }.also { cachedIconVectorMethod = it }
    }

/** 文本测量画笔，见 [discoverMeasurePaint]。 */
@Volatile
private var cachedMeasurePaint: Paint? = null

@Volatile
private var cachedAndroidViewMethod: Method? = null

/** `AndroidView` 只有 6 参这一个重载，缓存下来避免每次组合都扫一遍方法表。 */
private fun ReaMicroSettingsHook.androidViewMethod(): Method =
    cachedAndroidViewMethod ?: synchronized(DiscoverPageLock) {
        cachedAndroidViewMethod ?: cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == DISCOVER_ANDROID_VIEW_PARAMETER_COUNT
        }.apply { isAccessible = true }.also { cachedAndroidViewMethod = it }
    }

private val DiscoverPageLock = Any()

// ── 常量 ────────────────────────────────────────────────────────────────────
//
// 一律用 DISCOVER_ 前缀：settings 常量文件里有同名的 `*_PARAMETER_COUNT` / `ICON_METHOD`
// 一类 internal 顶层常量，同包内同名会构成重复声明。

private const val LOG_PREFIX = "[ReaMicro]"

/** 标签/平台解析与标签串的缓存条目上限——书单一次几十本，超了整块清空即可。 */
private const val DISCOVER_CACHE_MAX_ENTRIES = 600

/** 封面内存缓存容量（KB）。 */
private const val DISCOVER_COVER_CACHE_SIZE_KB = 12 * 1024

/** 封面下载并发数。 */
private const val DISCOVER_COVER_THREADS = 4

private const val DISCOVER_COVER_CONNECT_TIMEOUT_MS = 4_000

private const val DISCOVER_COVER_READ_TIMEOUT_MS = 6_000

/** 封面解码目标尺寸（px），取显示尺寸的两倍，高清屏不糊即可。 */
private const val DISCOVER_COVER_TARGET_WIDTH_PX = 276

private const val DISCOVER_COVER_TARGET_HEIGHT_PX = 372

/** 测量画笔的字间距（em），吸收 Compose `TextStyle` 的 letterSpacing。 */
private const val DISCOVER_TEXT_LETTER_SPACING_EM = 0.033f

/** 页面左右内边距（`pageModifier` 里写的就是 16dp），用于预估标签行与网格的可用宽度。 */
private const val DISCOVER_PAGE_HORIZONTAL_PADDING_DP = 16


private const val DISCOVER_ROW_VERTICAL_PADDING_DP = 10


private const val DISCOVER_LAYOUT_TOGGLE_PADDING_DP = 12

/** 顶栏最右按钮的额外右边距：与左上角返回键距屏幕左缘的观感对齐（见调用处注释）。 */
private const val DISCOVER_TOP_BAR_END_PADDING_DP = 10

private const val DISCOVER_KIND_ROW_TOP_GAP_DP = 6

/** 标签行余量：吸收 `letterSpacing` 与宿主全局字体替换带来的测量偏差。 */
private const val DISCOVER_KIND_ROW_SAFETY_DP = 12

private const val DISCOVER_KIND_GAP_DP = 8

private const val DISCOVER_KIND_CHIP_HEIGHT_DP = 30

private const val DISCOVER_KIND_CHIP_PADDING_DP = 12

/** 标签文字字号，与宿主 `labelLarge` 的 14sp 对齐（仅用于预测量）。 */
private const val DISCOVER_KIND_TEXT_SIZE_SP = 14f

private const val DISCOVER_KIND_EXPAND_WIDTH_DP = 32

private const val DISCOVER_LIST_COVER_WIDTH_DP = 46

private const val DISCOVER_LIST_COVER_HEIGHT_DP = 62

private const val DISCOVER_LIST_COVER_GAP_DP = 12

private const val DISCOVER_LIST_ROW_HORIZONTAL_PADDING_DP = 8

/** 列表行上下内边距：6dp 偏松、3dp 又太挤（用户实测反馈），取中间值。 */
private const val DISCOVER_LIST_ROW_VERTICAL_PADDING_DP = 4

private const val DISCOVER_LIST_LINE_GAP_DP = 4

private const val DISCOVER_LIST_TAG_GAP_DP = 6

private const val DISCOVER_GRID_COLUMNS = 3

private const val DISCOVER_GRID_GAP_DP = 10

private const val DISCOVER_GRID_TITLE_GAP_DP = 6

private const val DISCOVER_GRID_AUTHOR_GAP_DP = 2

private const val DISCOVER_COVER_ASPECT_WIDTH = 3

private const val DISCOVER_COVER_ASPECT_HEIGHT = 4

private const val DISCOVER_GRID_COVER_FALLBACK_HEIGHT_DP = 150

private const val DISCOVER_GRID_COVER_MIN_HEIGHT_DP = 90

private const val DISCOVER_GRID_COVER_MAX_HEIGHT_DP = 260

private const val DISCOVER_COVER_CORNER_DP = 4

/** 封面占位底色，与在线搜索结果的封面占位同色。 */
private const val DISCOVER_COVER_PLACEHOLDER_COLOR = 0xFFE8E8E8.toInt()



/** 标签串的分隔符，与在线补全搜索结果的元信息行一致。 */
private const val DISCOVER_TAG_SEPARATOR = " / "

/** 平台名（这里取书源名）在标签串里的最大字数。 */
private const val DISCOVER_TEXT_PARAMETER_COUNT = 22

/** 单行省略组合的第二个 changed 槽位（照抄在线搜索结果行的实证值）。 */
private const val DISCOVER_TEXT_ELLIPSIS_CHANGED = 24960

private const val DISCOVER_SIZE_PARAMETER_COUNT = 2

private const val DISCOVER_PADDING_SIDES_METHOD = "padding-qDBjuR0"

private const val DISCOVER_PADDING_SIDES_PARAMETER_COUNT = 5

private const val DISCOVER_SPACER_PARAMETER_COUNT = 3

private const val DISCOVER_ROW_WEIGHT_PARAMETER_COUNT = 3

private const val DISCOVER_ICON_PARAMETER_COUNT = 7

private const val DISCOVER_ANDROID_VIEW_PARAMETER_COUNT = 6

private const val DISCOVER_FILL_MAX_WIDTH_PARAMETER_COUNT = 4

private const val DISCOVER_FILL_MAX_WIDTH_DEFAULT_MASK = 1

private const val DISCOVER_ROW_SCOPE_INSTANCE_CLASS = "androidx.compose.foundation.layout.RowScopeInstance"

private const val DISCOVER_ROW_WEIGHT_METHOD = "weight"

private const val DISCOVER_ICON_KT_CLASS = "androidx.compose.material3.IconKt"

private const val DISCOVER_ICON_METHOD = "Icon-ww6aTOc"

/** `Icon` / `Image` 三个重载的首参类型，用它区分 ImageVector 版本。 */
private const val DISCOVER_IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"

private const val DISCOVER_ICON_CHANGED_MASK = 0x30

/** 必须 0：宿主箭头那次用的 0x4 会让 Compose 丢掉我们传进去的 modifier。 */
private const val DISCOVER_ICON_DEFAULT_MASK = 0x0




private const val DISCOVER_LAYOUT_TOGGLE_KEY = 0x524D4690

private const val DISCOVER_TOP_BAR_ACTIONS_KEY = 0x524D46A0

private const val DISCOVER_CONFIG_BUTTON_KEY = 0x524D46A1

private const val DISCOVER_KIND_ROW_KEY = 0x524D4691

private const val DISCOVER_KIND_EXPAND_KEY = 0x524D4692

private const val DISCOVER_BOOK_STATUS_ITEM_KEY = 0x524D4693

private const val DISCOVER_LIST_ROW_KEY = 0x524D4695

private const val DISCOVER_LIST_TEXT_KEY = 0x524D4696

private const val DISCOVER_LIST_TAG_KEY = 0x524D4697

private const val DISCOVER_GRID_ROW_KEY = 0x524D4698

private const val DISCOVER_GRID_CELL_KEY = 0x524D4699

private const val DISCOVER_LOAD_MORE_ITEM_KEY = 0x524D469A

private const val DISCOVER_LIST_ROW_ITEM_KEY_BASE = 0x524E0000

private const val DISCOVER_GRID_ROW_ITEM_KEY_BASE = 0x524E4000
