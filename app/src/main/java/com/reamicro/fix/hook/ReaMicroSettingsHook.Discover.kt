package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
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
    // 真沉浸：底部不消费 navigationBars inset，书单一直铺到屏幕最底，小白条浮在封面之上。
    renderHostLazyColumn(innerPaddings, listContent, composer, extendBottom = true)
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
            // LazyList item key 必须全局唯一：首屏数据没走 loadMore 的 distinctBy，
            // 源里混进重复书目时重复 key 会直接崩，渲染侧再兜一次。
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
                // 一本书一个 item（而不是整张卡片一个 item）。
                //
                // 曾经为了去掉「每 6 本一条空白带」改成整张卡片单 item，结果一次性往 LazyColumn
                // 里插入几百个节点，触发了 Compose 的 `UiApplier.insertBottomUp` →
                // `MutableVector.add` 越界崩溃（`src.length=16 srcPos=5 dstPos=6 length=-3`，
                // 与网格模式那次同族：gapbuffer 原地插入的下标与实际孩子数不一致）。
                // 逐行 item 每次只插入十来个节点，且封面天然懒加载（进入页面不再同时发起几十个请求），
                // 代价是失去卡片背景——与宿主自己的搜索结果页一致，行距也回到页面的统一节奏。
                //
                // 注意：列表模式下每个 item 的结构天然恒定（封面 + 固定数量的文字行），
                // 不像网格行那样会因「本行书数变化」而切换子节点种类，因此不受该崩溃影响。
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
            // 页尾留白。发现页开了真沉浸（`extendBottom = true`，content 一直画到屏幕最底），
            // 最后一张卡片会贴死屏幕底边、被系统手势条压住；固定补一段空白，
            // 让「加载更多」完整浮在小白条上方，滚到底时视觉上也收得住。
            //
            // 恒定的独立 item（永远存在、永远是同一个 Spacer 节点），
            // 不参与网格行内「同构子节点」的约束，因此没有种类切换风险。
            addLazyItem(lazyListScope, DISCOVER_TAIL_GAP_ITEM_KEY, DISCOVER_TAIL_GAP_ITEM_ID) { itemComposer ->
                renderDiscoverVGap(itemComposer, DISCOVER_TAIL_GAP_DP)
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
 * 网格模式的一行。
 *
 * 每格 `weight(1f)` 等分；封面宽度吃格子的实测宽度，封面高度由 `aspectRatio(3:4)`
 * **在布局期**从那个宽度推导——不传任何屏幕尺寸常量（为什么见 [renderDiscoverCover]）。
 *
 * ## 每行固定产出 [DISCOVER_GRID_COLUMNS] 个同构格子（**不要用 Spacer 补位**）
 *
 * 这是 `加载更多` 两次连点闪退（`ArrayIndexOutOfBoundsException: … length=-2/-3`）的真正修复点。
 *
 * 宿主 `LazyList` 的 item 按 key 复用：追加数据后，原来的「末行」（例如 2 本 → 2 格 + 1 个
 * Spacer 占位）会原地变成「3 本 → 3 格」。孩子**数量**没变（都是 3），但位置 2 上的节点**种类**
 * 从 `Spacer` 变成了格子的 `Column`。宿主这套 gapbuffer 运行时（`PostInsertNodeFixup`）
 * 在种类切换时会按**槽位下标**而不是 applier 的孩子下标去调 `insertBottomUp`，算出来的
 * 下标比真实孩子数大，`MutableVector.add` 的 `arraycopy` 长度变成负数直接闪退。
 *
 * 实机取证（`children=` 是探针打印的父 Row 现有子节点）：
 * ```
 * ILLEGAL insert: idx=4 size=2 parent=RowMeasurePolicy 1124x626
 *   children=[Column 354x626, Column 355x0] child=Column 0x0
 * ```
 * 只有 2 个孩子却要插到下标 4。
 *
 * 修法：把「缺书的槽位」也做成**同一个格子代码路径**产出的空 Column，而不是 Spacer。
 * 这样每行的孩子种类与数量恒为 [DISCOVER_GRID_COLUMNS] 个 `Column`，孩子下标与格子下标
 * 一一对应，追加数据时不再发生种类切换。
 */
private fun ReaMicroSettingsHook.renderDiscoverGridRow(
    source: DiscoverSource,
    books: List<DiscoverBook>,
    composer: Any,
) {
    val titleColor = colorScheme(composer).longMethod("getOnBackground")
    val metaColor = schemeColor(composer, "getOnSurfaceVariant", "getOnBackground")
    // 内容 lambda 的 key 按行区分（首书 key）：所有行共用一个常量 key 时，
    // 重组后不同行的组身份无法区分，结构不同的两行可能互相复用槽位。
    val content = composableLambda(DISCOVER_GRID_ROW_KEY + books.first().key.hashCode(), FUNCTION3_CLASS) { args ->
        val inner = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        // 每行固定产出 [DISCOVER_GRID_COLUMNS] 个子节点，且**全部由同一个格子代码路径**生成
        // （同一 composableLambda key + 同一个 Column）。缺书的槽位用「空格子」补齐。
        //
        // 为什么不能用 Spacer 补位（曾经的写法）：
        // 宿主这套 gapbuffer 运行时在「同一 Row 内子节点种类发生变化」时会把
        // `PostInsertNodeFixup` 的下标算成**槽位下标**而不是 applier 的孩子下标。
        // 末行从「2 本书 → 加 2 个 Spacer」变成「3 本书 → 0 个 Spacer」时，位置 2 上
        // Spacer 的组被换成格子的 λ 组，插入下标按槽位算出来是 4、而真实孩子数只有 2，
        // `MutableVector.add` 的数组拷贝 length = size - idx = -2 → 直接闪退
        // （实机取证：`children=[Column 354x626, Column 355x0]` 却要插到 idx=4）。
        // 全部用同构格子后，孩子下标与格子下标一一对应，不再有种类切换。
        repeat(DISCOVER_GRID_COLUMNS) { columnIndex ->
            val book = books.getOrNull(columnIndex)
            val cellContent = composableLambda(DISCOVER_GRID_CELL_KEY + columnIndex, FUNCTION3_CLASS) { cellArgs ->
                val cellInner = cellArgs?.getOrNull(1) ?: return@composableLambda targetUnit()
                if (book != null) {
                    // 封面自带宽下边距（书名间距），不再另插一个 Spacer——见 [renderDiscoverCover]。
                    renderDiscoverCover(source, book, null, null, cellInner, bottomGapDp = DISCOVER_GRID_TITLE_GAP_DP)
                    val titleStyle = textStyle(cellInner, "getBodyMedium", "getBodySmall")
                    val overflow = book.cellTextOverflowLines(titleStyle)
                    renderDiscoverText(
                        book.name,
                        titleColor,
                        titleStyle,
                        cellInner,
                        singleLine = false,
                        maxLines = GRID_TITLE_MAX_LINES,
                        overflow = overflow,
                    )
                    // 作者行永远占位：作者为空时留一个等高空位，**不省略节点**。
                    //
                    // 理由同上：格子内子节点数量必须恒定，追加数据时不能出现节点的增删。
                    // （`cellTextOverflowLines` 把书名压在固定行数、`maxLines` 固定 2 行，
                    // 都是同一个不变量的一部分。）
                    renderDiscoverVGap(cellInner, DISCOVER_GRID_AUTHOR_GAP_DP)
                    if (book.author.isNotBlank()) {
                        renderDiscoverText(
                            book.author,
                            metaColor,
                            textStyle(cellInner, "getLabelSmall", "getBodySmall"),
                            cellInner,
                        )
                    }
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
 * 现在**省略交给 Compose 自己做**：照抄在线补全搜索结果行
 * （`renderOnlineCompletionSecondaryText`）已验证的实参组合——
 * `overflow=Ellipsis`(index 12)、`softWrap=false`、`maxLines=1`(index 14)、
 * `changed=(0, 24960)`、`TEXT_SECONDARY_SINGLE_LINE_MASK`(110586)。
 * 掩码 bit12 清零表示 overflow 形参走显式值；Compose 用真实字体度量截断，
 * 任何设备上省略号都精确落在行末。
 *
 * ## 多行（网格书名）
 *
 * 网格书名限 [GRID_TITLE_MAX_LINES] 行。多行版把 `softWrap` 从「走默认」改成**显式 true**，
 * 即把掩码里 softWrap 那一位（bit13）清掉：110586 → 102394（`DISCOVER_TEXT_MULTILINE_MASK`）。
 * 其余位不动，`maxLines` 与 `minLines` 本来就都是显式值。
 *
 * [maxLines] 传 0 表示沿用调用方给的层数（[singleLine] 决定）；只有 [singleLine] 为 false
 * 且 [maxLines] > 0 时才走多行路径。
 */
private fun ReaMicroSettingsHook.renderDiscoverText(
    text: String,
    color: Long,
    style: Any,
    composer: Any,
    singleLine: Boolean = true,
    maxLines: Int = 0,
    overflow: Int = DISCOVER_TEXT_OVERFLOW_ELLIPSIS,
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
    val lines = if (singleLine) 1 else maxLines
    // `softWrap`：单行必须显式 false（否则长书名会折行，`maxLines=1` 只是封顶）；
    // 多行要显式 true 才能真正折到 maxLines 行。
    val softWrap = !singleLine
    // 掩码里 softWrap（bit13）走默认 ⇔ 单行；多行换成清掉该位的 `DISCOVER_TEXT_MULTILINE_MASK`。
    val mask = if (singleLine) TEXT_SECONDARY_SINGLE_LINE_MASK else DISCOVER_TEXT_MULTILINE_MASK
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
        overflow,
        softWrap,
        lines,
        0,
        null,
        style,
        composer,
        0,
        DISCOVER_TEXT_ELLIPSIS_CHANGED,
        mask,
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
 * ## 尺寸（`widthDp` / `heightDp` 都可空）
 *
 * - 列表行：`widthDp=46, heightDp=62`，固定尺寸。
 * - 网格格：两个都传 **null** —— 宽度 `fillMaxWidth()` 吃格子的 `weight` 结果，
 *   高度挂 `aspectRatio(3:4)` 由**布局期实测宽度**算。
 *
 * 网格封面**不能**再传「用屏幕宽度反算出来的绝对高度」（原因见本文件「已废弃：网格封面高度的
 * 屏幕反算」一节）：绝对高度 + 绝对宽度叠在宿主 `LazyColumn` 的 item 约束上，会出现测量结果与约束
 * 互相打架的退化解（实测 957x589px 的正方形），随后宿主 gapbuffer 追加节点时就越界闪退。
 * `aspectRatio` 是约束驱动的，不依赖任何设备常量，是唯一能跟宿主约束共存的写法。
 */
private fun ReaMicroSettingsHook.renderDiscoverCover(
    source: DiscoverSource,
    book: DiscoverBook,
    widthDp: Int?,
    heightDp: Int?,
    composer: Any,
    bottomGapDp: Int = 0,
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
            loadDiscoverCover(view, source.source, book.coverUrl, book.detailUrl)
        }
        targetUnit()
    }
    var modifier = if (widthDp == null) {
        discoverFillMaxWidth(modifierInstance())
    } else {
        method(SIZE_KT_CLASS, WIDTH_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
            .invoke(null, modifierInstance(), udp(widthDp))
    }
    if (heightDp == null) {
        // 高度交给 3:4 的宽高比：aspectRatio 在布局期用「实测宽度」反推高度，
        // 所以既不会写死设备常量，也不会跟父约束冲突。
        modifier = discoverAspectRatio(modifier, DISCOVER_COVER_ASPECT_WIDTH / DISCOVER_COVER_ASPECT_HEIGHT.toFloat())
    } else {
        modifier = method(SIZE_KT_CLASS, HEIGHT_METHOD, DISCOVER_SIZE_PARAMETER_COUNT)
            .invoke(null, modifier, udp(heightDp))
    }
    if (bottomGapDp > 0) {
        // 间距内联进封面自己的 padding，而不是再插一个 Spacer 节点：少一个子节点，
        // 就少一次 gapbuffer 原地插入的机会（见网格格子里作者行的同款注释）。
        modifier = paddingSides(modifier, start = 0, top = 0, end = 0, bottom = bottomGapDp)
    }
    androidViewMethod().invoke(null, factory, modifier, update, composer, 0, 0)
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
                // downloadDiscoverCover 内部只兜住响应阶段；连接构造等更早的异常会漏到这里，
                // 而「简洁日志」会吞掉 INFO 级输出，灰块会完全无迹可循——必须 logAlways。
                XposedBridge.logAlways(
                    "[ReaMicro] discover cover task failed url=${url.take(200)} " +
                        "error=${it.javaClass.simpleName}: ${it.message.orEmpty()}",
                )
            }
            .getOrNull()
            ?: run {
                // 番茄部分书目的封面 id 落在被 CDN 整体拒绝的命名空间（novel-pic-r / ai），
                // 官网 /page/<bookId> 的 og 封面才是可取的 novel-pic id——直连失败时走这条兜底。
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
            // View 可能已被 LazyColumn 回收并复用给别人，比对 tag 再贴图，避免串图。
            if (imageView.tag == url) imageView.setImageBitmap(bitmap)
        }
    }
}

/** 只有番茄系 CDN 的封面才值得走官网兜底，其它源直接跳过，避免错拿同号封面。 */
internal fun isFanqieCoverUrl(url: String): Boolean =
    url.contains("byteimg.com", ignoreCase = true) ||
        url.contains("fqnovel.com", ignoreCase = true) ||
        url.contains("fanqienovel.com", ignoreCase = true)

/**
 * 官网兜底：从 detailUrl 里取番茄 bookId，抓 `fanqienovel.com/page/<id>` 的 HTML，
 * 取第一个 `novel-pic/<id>`（即书籍封面，实测书页首个该命名空间图片就是封面），
 * 重写成 `p6-novel.byteimg.com/origin/novel-pic/<id>` 免签名直连（实测 200 image/jpeg）。
 * 返回（缓存键, OnlineBinaryPayload）：缓存键用重写后的 URL，同一本书再触发兜底零网络开销；
 * 字节载荷同时供下载链路（EPUB 封面）复用。
 */
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
            // 防盗链兜底：搜索封面能亮而发现页封面黑的源，多半是封面 CDN 校验 Referer。
            // 书源 header / 登录头都没显式给 Referer 时，补图片自身的 origin——
            // 与浏览器地址栏直接打开图片时自动携带的 Referer 一致，已配置的源不受影响。
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
            // 「简洁日志」默认会吞掉 XposedBridge.log 的 INFO 级输出，诊断一律走 logAlways。
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

// ── 已废弃：网格封面高度的屏幕反算 ────────────────────────────────────────────
//
// 这里曾有一个 `discoverGridCoverHeightDp()`：用 `displayMetrics.widthPixels / density`
// 减掉页面左右内边距，除以 3 列，再乘 4/3 得到封面高度（绝对值，单位 dp）。
//
// 它**已被移除，不要恢复**。原因（2026-09-23 实机复现 + 探针取证）：
// 网格格子的封面同时带上「绝对宽度」与「绝对高度」后，宿主的 `LazyColumn` item 约束
// 与这两个绝对值会互相打架——`loadMore` 追加第二批数据时，新行的 `Row` 被测量成
// 957x589px（≈319x196dp，一个正方形），封面实测被压到 299px 而不是正常的 355px。
// 随后宿主 gapbuffer 往这个已绑定固定 child-slot 数的 `Row` 里追加节点，
// `PostInsertNodeFixup` 算错下标 → `MutableVector.add` 数组拷贝 `length=-3` → 闪退。
//
// 现在封面高度一律由 `aspectRatio(3:4)` **在布局期**从实测宽度推导
// （见 [renderDiscoverCover]），不再依赖任何设备常量，也就不存在这个冲突。

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

/** 网格书名的换行测量结果缓存（key = 书名 + 格子宽度 px）。 */
private val discoverCellLinesCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * 网格书名是否放得下 [GRID_TITLE_MAX_LINES] 行；放不下返回 1（只显示一行 + 省略号）。
 *
 * 为什么需要它：网格格子里「作者行」**永远占位**（作者为空时留一个等高空隙），
 * 否则同一格在不同数据下子节点数会变——而宿主这套 gapbuffer 运行时一旦遇到
 * item 内节点数从 N 涨到 M 的原地插入，就会算错下标（`PostInsertNodeFixup` →
 * `MutableVector.add` 数组拷贝 length 为负）直接闪退。
 *
 * 既然格子高度固定，书名区域也必须固定成 [GRID_TITLE_MAX_LINES] 行，放不下的收成一行。
 * 这里**只用真实 `TextPaint` 度量做行数选择**，不逐像素裁字——所以历史笔记里那条
 * 「测量字号与实渲有设备级偏差、不要用 Paint 预截断」的教训在此不适用
 * （那条针对的是把正文提前砍掉几十 dp，这里只决定折一行还是两行）。
 */
private fun DiscoverBook.cellTextOverflowLines(titleStyle: Any): Int {
    val name = name.trim()
    if (name.isEmpty()) return 0
    if (name.length <= GRID_TITLE_MEASURE_SKIP_CHARS) return 0
    val widthPx = gridCellWidthPx() ?: return 0
    val cacheKey = "$widthPx|$name"
    discoverCellLinesCache[cacheKey]?.let { return it }
    val lines = runCatching { measureGridTitleLines(name, titleStyle, widthPx) }.getOrDefault(0)
    discoverCellLinesCache.putBounded(cacheKey, lines)
    return lines
}

/** 网格格子宽度（px）：屏幕宽度减页面内边距与列间距再三等分。 */
private fun gridCellWidthPx(): Int? {
    val metrics = ReaMicroSettingsHook.activeInstanceOrNull()?.activityProvider?.invoke()?.resources?.displayMetrics ?: return null
    if (metrics.density <= 0f || metrics.widthPixels <= 0) return null
    val pagePad = DISCOVER_PAGE_HORIZONTAL_PADDING_DP * metrics.density
    val gap = DISCOVER_GRID_GAP_DP * metrics.density
    val cell = (metrics.widthPixels - pagePad * 2 - gap * (DISCOVER_GRID_COLUMNS - 1)) /
        DISCOVER_GRID_COLUMNS
    return cell.toInt().takeIf { it > 0 }
}

/** 测量用的 `TextPaint` 复用同一个实例，避免每本书都新建。 */
@Volatile
private var cachedGridTitlePaint: android.text.TextPaint? = null

private fun cachedGridTitlePaint(): android.text.TextPaint = cachedGridTitlePaint
    ?: android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).also { cachedGridTitlePaint = it }

/**
 * 放得下两行返回 0，只放得下一行返回 1。
 *
 * 判据：整串宽度 > 单行可用宽 → 需要换行；此时若整串 ≤ 两行可用宽就让它折两行，
 * 否则收成一行（`maxLines=1` + Ellipsis）。
 */
private fun measureGridTitleLines(text: String, style: Any, widthPx: Int): Int {
    val paint = cachedGridTitlePaint()
    val sizeSp = runCatching { style.method0("getFontSize") }.getOrNull().toSpValueOrDefault()
    val scaledDensity = ReaMicroSettingsHook.activeInstanceOrNull()
        ?.activityProvider?.invoke()?.resources?.displayMetrics?.scaledDensity ?: return 0
    paint.textSize = sizeSp * scaledDensity
    val maxWidth = widthPx * GRID_TITLE_MEASURE_WIDTH_RATIO
    val total = paint.measureText(text)
    if (total <= maxWidth) return 0
    return if (total <= maxWidth * GRID_TITLE_MAX_LINES) 0 else 1
}

/** `TextUnit` 是 inline class（装箱后是 long，「sp」类型值在低 32 位）。 */
private fun Any?.toSpValueOrDefault(): Float {
    val raw = this as? Long ?: return GRID_TITLE_FALLBACK_SP
    val value = raw.toInt() and 0x3FFFFFFF
    return if (value > 0) value.toFloat() else GRID_TITLE_FALLBACK_SP
}

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

/**
 * `AspectRatioKt.aspectRatio(Modifier, ratio, matchHeightConstraintsFirst)`。
 *
 * 三参签名（宿主 2.3.2 beta 实证：`aspectRatio(Landroidx/compose/ui/Modifier;FZ)`），
 * 不是 `$default` 版——这里三个形参都显式给。
 */
private fun ReaMicroSettingsHook.discoverAspectRatio(base: Any, ratio: Float): Any =
    method(DISCOVER_ASPECT_RATIO_CLASS, DISCOVER_ASPECT_RATIO_METHOD, DISCOVER_ASPECT_RATIO_PARAMETER_COUNT)
        .invoke(null, base, ratio, false)

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

// ── 番茄官网封面兜底 ────────────────────────────────────────────────────────

/** 番茄官网书籍页基地址。 */
private const val DISCOVER_FANQIE_WEB_BASE = "https://fanqienovel.com"

/** 官网页面对非浏览器 UA 有 WAF，兜底请求带完整浏览器 UA。 */
private const val DISCOVER_COVER_BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

/** 番茄 bookId 是 15~21 位纯数字（书页 URL / API 参数里都是它）。 */
private val DISCOVER_FANQIE_BOOK_ID_REGEX = Regex("""\d{15,21}""")

/** 官网 HTML 里首个 `novel-pic/<hash>` 即书籍封面（签名 URL 里的 hash 与签名无关，可直接复用）。 */
private val DISCOVER_FANQIE_PIC_HASH_REGEX = Regex("""(?i)novel-pic/([0-9a-z]{16,64})""")

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

private const val DISCOVER_TEXT_MULTILINE_MASK = 102394

/** `TextOverflow.Ellipsis` 的 Int（inline class 装箱值）。 */
private const val DISCOVER_TEXT_OVERFLOW_ELLIPSIS = 1

/** 网格书名最多占几行（与作者行的固定占位一起保证格子高度恒定）。 */
private const val GRID_TITLE_MAX_LINES = 2

/** 书名短于这个字符数就不必测量——两行一定能放下。 */
private const val GRID_TITLE_MEASURE_SKIP_CHARS = 16

/** 测量时按格子宽度的这个比例算可用宽（留一点余量，避免刚好卡边）。 */
private const val GRID_TITLE_MEASURE_WIDTH_RATIO = 0.96f

private const val GRID_TITLE_FALLBACK_SP = 14f

private const val DISCOVER_FILL_MAX_WIDTH_DEFAULT_MASK = 1

/** `AspectRatioKt.aspectRatio(Modifier, Float, Boolean)` —— 网格封面高度由宽度推导。 */
private const val DISCOVER_ASPECT_RATIO_CLASS = "androidx.compose.foundation.layout.AspectRatioKt"

private const val DISCOVER_ASPECT_RATIO_METHOD = "aspectRatio"

private const val DISCOVER_ASPECT_RATIO_PARAMETER_COUNT = 3

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

/** LazyList item 的真实 key（字符串）：让追加/切换变成按键插入而不是原位整组替换。 */
private const val DISCOVER_STATUS_ITEM_ID = "discover_status"

private const val DISCOVER_LOAD_MORE_ITEM_ID = "discover_load_more"

/** 页尾留白 item（独立于网格行，恒定存在）。 */
private const val DISCOVER_TAIL_GAP_ITEM_KEY = 0x524D469B

private const val DISCOVER_TAIL_GAP_ITEM_ID = "discover_tail_gap"

/** 页尾留白高度：32dp ≈ 96px（本机 3.0x），足够让末张卡片避开 47px 的手势条。 */
private const val DISCOVER_TAIL_GAP_DP = 32

private const val DISCOVER_LIST_ROW_ITEM_KEY_BASE = 0x524E0000

private const val DISCOVER_GRID_ROW_ITEM_KEY_BASE = 0x524E4000
