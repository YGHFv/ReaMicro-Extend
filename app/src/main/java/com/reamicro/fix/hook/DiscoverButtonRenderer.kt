package com.reamicro.fix.hook

import com.reamicro.fix.hook.discover.DiscoverIcon
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method

// 把「书院」那一行改造成「书院 ｜ 发现」——**结构与下一行「同好 ｜ 野社」严格对齐**。
//
// ## 宿主原样（MT 反编译当前 host 的 CommunityKt.Community 逐行核对）
//
// 社区卡片里通栏行、通栏行、双栏行依次排列，两两之间夹一条 SimpleDivider：
//
// ```smali
// // 通栏行（笔记、书院两行都是这个形状）
// Row(Modifier.clickable {…}.padding(16.dp), Arrangement.Start, CenterVertically)  // .line 44 / 66
//     Image(getBook, Modifier.padding(end = 12.dp).size(24.dp))                    // .line 70-72
//     Text("书院", style = TitleSmall, color = OnBackground)                       // .line 75
//     Spacer(Modifier.weight(1f))                                                  // .line 79
//     Icon(getNavigateNext, modifier = null, tint = surfaceContainerHighest)       // 行尾箭头
// SimpleDivider(Modifier.padding(start = 52.dp))                                   // .line 86
//
// // 双栏行
// Row(Modifier.fillMaxWidth(), Arrangement.Start, CenterVertically)                // .line 88
//     同好格: Modifier.weight(1f).clickable {…}.padding(16, 16, 12, 16)            // .line 92-94
//     中缝                                                                         // .line 115-116
//     野社格: Modifier.weight(1f).clickable {…}.padding(12, 16, 16, 16)            // .line 119-121
// ```
//
// 中缝是宿主原样（`.line 115-116`）：
// `Box(Modifier.width(getUdp(0.8)).height(getUdp(0x38 = 56)).background(ThemeKt.getBorderVariant(colorScheme)))`
// —— 无 content 的 3 参 `BoxKt.Box`，尺寸 0.8dp × 56dp。两个格子各 `weight(1f)`，
// 所以它落在行内容的正中，且高度正好顶满整行。
//
// ## 几何为什么能自动对齐（不依赖屏幕密度）
//
// 行 1 自身那个 `padding(16.dp)` 恰好就是格子「朝外那一侧」的 padding —— 左右各 16dp，
// 与同好格的 `start = 16`、野社格的 `end = 16` 完全一致。**因此在注入侧只需要补「朝中缝
// 那一侧」的 12dp**：
//
// - 书院格把自己朝内的 `end = 12` 交给箭头₁（宿主箭头本来 `modifier = null`，补一个纯 padding）；
// - 发现格把自己朝内的 `start = 12` 交给前置图标；图标自带宿主同款 `end = 12`（图标与文字的间距）。
//
// 于是两半的固定宽度都是 `12 + 24 + 12 + 文字 + 24`（书院那半的 12 挂在箭头上），**严格相等**，
// 两个 `weight(1f)` 撑开 Spacer 平分剩余宽度 ⇒ 中缝落在行内容正中，与下一行的中缝同一条竖线，
// 且「发现」的图标/文字与「野社」逐像素对齐。全程只用宿主 smali 里的 16 / 12 / 24 / 56。
//
// ## 中缝高度：测量 24dp + 绘制 56dp
//
// 行 1 的 16dp padding 在**行外部**（格子的 16dp 在格子内部），所以行内容高度只有 24dp
// （= 图标 size，文本行高比它矮）。若把中缝直接做成 56dp，这一行会被撑到 16 + 56 + 16 = 88dp，
// 与「同好 ｜ 野社」不再等高。
//
// 因此中缝的**测量高度取 24dp**（与图标等高，行高仍是 16 + 24 + 16 = 56dp，与双栏行一致），
// 再用 `ScaleKt.scale(1f, 56f / 24f)` 这个 graphicsLayer 变换把**绘制高度**拉到宿主的 56dp。
// graphicsLayer 只影响绘制、不参与测量，所以竖线观感与宿主那一条完全一致，行高却不受影响。
//
// ## 最终节点序列（注入后整行的形状，与第 2 行逐节点同构）
//
// ```
// 书院格: [书图标][书院][Spacer(w1f)][箭头₁]  |中缝 0.8×56dp|  发现格: [发现图标][发现][Spacer(w1f)][箭头₂]
//          └ 宿主原有四个节点，本次只改各自 modifier：各补 [clickable][格内边距] ┘   └── 本次注入 ──┘
// ```
//
// 书院格的 `clickable` 复用宿主行原本那个 `onClick`（导航到书院页），发现格的走
// [DiscoverCardHook.openDiscoverPage]；两格各持一个 `MutableInteractionSource`。
//
// ## 按压反馈：两半都做成「一个格子」，与「同好 ｜ 野社」同构
//
// 宿主第 2 行的两个格子是这样写的（`Community.kt:88-121`，smali `.line 88-121`）：
//
// ```smali
// Row(Modifier.fillMaxWidth(), …)                             // 行自身没有任何 padding
//     同好格: Modifier.weight(1f).clickable(…).padding(16, 16, 12, 16)
//     Box(0.8dp × 56dp).background(borderVariant)              // 中缝
//     野社格: Modifier.weight(1f).clickable(…).padding(12, 16, 16, 16)
// ```
//
// 关键在**格的 padding 在 clickable 之内**：于是格子的按压高亮是「整格」——56dp 高、
// 半行宽，连 16dp 内边距一起亮。而书院行原本是
// `Row(Modifier.clickable{…}.padding(16dp))`：整个行是**一个** clickable，按哪都亮整行。
//
// 本次改造把书院行拆成与第 2 行完全同构的两个格子（见 [DiscoverCardHook] 的
// `rowClickable` / `rowPadding` / `rowIconImage` / `rowLabelText` / `rowSpacer` 五个 hook）：
//
// ```smali
// 行 modifier:  clickable(…).padding(16dp)   →   fillMaxWidth()      // 行级 clickable 与 16dp 全撤
// 书院格(左):   书图标 + 文字 + Spacer + 箭头  各补 [clickable][padding(16,16,0,16) 等]
// 发现格(右):   本次注入的四个节点            各补 [clickable][padding(12,16,12,16) 等]
// ```
//
// 行高与视觉位置**逐项不变**：原来 16(行 padding) + 24(内容) + 16(行 padding) = 56dp，
// 现在 16(格子竖直 padding) + 24 + 16 = 56dp；书院行的 16dp 水平内边距由书图标自己的
// `padding(start = 16dp)` 接替，行末 16dp 由发现格箭头的 `padding(end = 16dp)` 接替。
//
// 要让每个节点的按压高亮 = 整格，仍需两条同时成立（沿用上一轮已验证的结论）：
//
// 1. **同一格的节点共用一个** `MutableInteractionSource`（[sharedInteractionSource]）。两格各一个：
//    共用一个的话按左格会把右格一起点亮；
// 2. **节点要自带 indication**，且 clickable 必须在 modifier 链**首**。
//
//    宿主社区卡里每个 `Modifier.clickable{…}` 编译出来都是
//    `ClickableKt.clickable-oSLSa3U$default`，实参 `mask=0xf` + 后面四项全 0 ——
//    也就是 `interactionSource` 与 indication 都走默认值。它的实现体（MT 读 dex_method 原文）是
//    `ClickableElement(interactionSource, null /*IndicationNodeFactory*/, true, enabled, …)`；
//    第三个 boolean 是 **true**，而 `ClickableKt.clickable-XHw0xAI` 上挂着一条弃用注解：
//    “Replaced with new overload that only supports IndicationNodeFactory instances inside
//    LocalIndication, and does not use composed”。合起来说明：这个重载让 **ClickableNode
//    自己在节点内部消费 `LocalIndication`**，宿主格子那「一整块按压高亮」来源在此。
//
//    模块这条链拿不到 ClickableNode 内部，只能自己把 indication 取出来交给
//    `clickable-O2vRcR0(Modifier, source, indication, …)`，indication 取 `LocalIndication.current`
//    ——与宿主是**同一个** Indication 对象，实机按下态逐像素相同（同为整格平铺的浅灰状态层，
//    不是波纹）。链首位置决定了 feedback 的坐标空间 = 整个节点，因此 `padding(…, 16, …)`
//    那 16dp 竖直内边距也在高亮范围内。详见 [discoverClickable]。
//
// 3. **同一格四个节点的高度必须一致**，否则四块高亮拼不成整块。文本节点的内高默认是文字行高
//    （`TitleSmall` ≈ 20.67dp）而不是 24dp，比图标/留白/箭头矮，会在文字上下各留一条白缝
//    ——用 [textNodeCellHeight] 把文本节点补到 24dp 内高。
//
// 实机核对结论（像素实测）：按住任一格 → 该半格整块变浅灰 `(236,235,235)`，横向 x60–610 /
// x611–1159、纵向 y1704–1870（168px = 56dp，与下「同好｜野社」行严格等高），另一格完全不变，
// 松手即熄。与宿主同好格按下态 bbox（549×167px）同构。
//
// ## 为什么不能自建容器
//
// 注入点在 `IconKt.Icon-ww6aTOc` 的 `beforeHookedMethod`——此刻 `Composer` 处在宿主已开好的
// **可复用节点作用域**内（`startReusableNode` 之后、`endNode` 之前）。在这里再调
// `RowKt.Row`/带 content 的 `BoxKt.Box` 会新开不匹配的组，
// `finalizeCompose` 抛 Start/end imbalance，界面一重组就闪退。
// 所以只能**就地发射兄弟节点**：本文件所有 `Image/Icon/Spacer` 调用都是「与宿主同层」的
// 平铺节点，唯独中缝那个 `BoxKt.Box(Modifier, Composer, I)` 是无 content 的 3 参重载
// （MT 实测 `BoxKt` 里确有 `Box(Modifier; Composer; I)V`，宿主中缝用的正是它），
// 它自身不新开 restart/replace 组，可以安全调用。
//
// ## 两条不能违反的规约
//
// 1. `ImageKt.Image` 的 `contentScale` / `alignment` **绝不能传 null**：二者都在渲染线程的
//    draw 阶段才解引用，`runCatching` 拦不住，直接崩进程（详见 [renderDiscoverIcon] 注释）。
// 2. Compose 调用的 `changed` 掩码不能填 0（除宿主自己填 0 的中缝 Box），否则整棵子树被跳过。

/**
 * 静态入口：把「书院」行补成「书院 ｜ 发现」两个等宽格子。
 *
 * 无实例（设置页 hook 尚未安装）时直接跳过——卡片是宿主核心页面，宁可少一个按钮，
 * 也不能让异常穿透到宿主的 Compose 渲染栈。
 *
 * @param rowScope 书院行那个 `Row` 的 scope，由 [DiscoverCardHook] 在
 *   `RowScope.weight$default` 命中时捕获（与宿主撑开 Spacer 用的是同一份）。
 * @param composer 宿主右箭头那次 `Icon-ww6aTOc` 的 Composer，取自其真实形参。
 * @param onHostRowClick 书院格要复用的点击：宿主行原本那个 `onClick`（导航到书院页），
 *   由 [DiscoverCardHook] 在摘除行级 clickable 时截获并传进来。
 * @param onDiscoverClick 发现格的点击：推入模块自己的发现页。
 */
internal fun renderDiscoverButton(
    classLoader: ClassLoader,
    rowScope: Any,
    composer: Any,
    onHostRowClick: () -> Unit,
    onDiscoverClick: () -> Unit,
) {
    val hook = ReaMicroSettingsHook.activeInstanceOrNull()
    if (hook == null) {
        XposedBridge.log("$LOG_PREFIX discover button skipped: settings hook instance is NULL")
        return
    }
    runCatching {
        with(hook) { renderCommunityRow(rowScope, composer, onHostRowClick, onDiscoverClick) }
    }.onFailure {
        XposedBridge.logAlways("$LOG_PREFIX discover button failed: ${it.stackTraceToString()}")
    }
}

/**
 * 发射「书院格收尾箭头 → 中缝 → 发现格四节点」。复用 [ReaMicroSettingsHook] 的反射工具簇，
 * 因此必须是它的扩展。
 *
 * 书院格的三个前置节点（书图标 / 文字 / Spacer）由宿主原样发射，它们的 modifier 在
 * [DiscoverCardHook] 的对应 hook 里就地补上本格的 `[clickable][格内边距]`（见
 * [leftCellIconModifier] / [leftCellLabelModifier] / [leftCellSpacerModifier]），
 * 因此这里只负责书院格尾部那个箭头，以及整个发现格。
 */
internal fun ReaMicroSettingsHook.renderCommunityRow(
    rowScope: Any,
    composer: Any,
    onHostRowClick: () -> Unit,
    onDiscoverClick: () -> Unit,
) {
    val icon: Any? = runCatching { DiscoverIcon(classLoader).imageVectorOrNull() }.getOrNull()

    // 两格各一个交互源：同一格的几个节点共用它，按压反馈才会「整格一次选中」。
    // 两格共用同一个源会让按左格把右格也一起点亮，所以必须分开。
    val leftSource = sharedInteractionSource(CELL_LEFT)
    val rightSource = sharedInteractionSource(CELL_RIGHT)

    // ① 书院格尾部箭头（宿主箭头同款外观 + 本格 clickable + 朝中缝的 12dp 内边距）
    renderCellArrow(composer, leftSource, "ReaMicroLeftCellArrow", CELL_LEFT_ARROW_END_PADDING_DP, onHostRowClick)

    // ② 中缝：宿主原样构造，0.8dp × 56dp（行里最高的节点就是它，行高因此仍是 56dp）
    renderVerticalDivider(composer)

    // ③ 发现格：[图标][文字][Spacer(weight)]，与野社格内容同构；每个节点都带本格 clickable
    //    （共用 rightSource + 自带 LocalIndication），使整个右半格（含 16dp 内边距与留白）
    //    既是同一块可点、也是同一块高亮
    if (icon != null) renderDiscoverIcon(icon, composer, rightSource, onDiscoverClick)
    renderDiscoverLabel(
        DISCOVER_LABEL,
        textNodeCellHeight(
            paddingSides(
                discoverClickable(clickableBase(), "ReaMicroDiscoverLabel", composer, rightSource, onDiscoverClick),
                start = 0,
                top = CELL_VERTICAL_PADDING_DP,
                end = 0,
                bottom = CELL_VERTICAL_PADDING_DP,
            ),
        ),
        composer,
    )
    renderDiscoverFiller(rowScope, composer, rightSource, onDiscoverClick)

    // ④ 发现格尾部箭头：自己发射一个与宿主箭头外观完全一致的 Icon，但挂上本格 clickable。
    //
    // 为什么不直接改宿主箭头那次的 modifier 实参：实测在本机不生效——`param.args[2]` 确实被
    // 写成了我们的 CombinedModifier（探针日志为证），但箭头既没染色也没变大，说明改动没有
    // 传导进 Icon 的执行。所以改走「调用方直接取消宿主那次 Icon，由我们重画一个」这条路
    // （见 [DiscoverCardHook] 里的 `param.result = null`）：我们自己传 modifier 是验证过有效的
    // （发现图标的 clickable 就来自这条路），且节点顺序与宿主原本的箭头完全一致，布局零位移。
    renderCellArrow(composer, rightSource, "ReaMicroDiscoverArrow", CELL_RIGHT_ARROW_END_PADDING_DP, onDiscoverClick)
}

/**
 * 格子的收尾箭头。
 *
 * 两格的箭头共用本函数：外观（同一个 `NavigateNext` 图标、同一个 tint、同样的 changed 掩码）
 * 逐项相同，差别只在 `clickable` 挂哪一个 `MutableInteractionSource` / 回调，以及**朝内那一侧**
 * 的内边距——书院格是 12dp（同好格的 `padding(16,16,12,16)` 第三位），发现格是 16dp
 * （野社格的 `padding(12,16,16,16)` 第三位），也就是整个格子的收尾内边距。
 */
private fun ReaMicroSettingsHook.renderCellArrow(
    composer: Any,
    interactionSource: Any?,
    clickName: String,
    trailingPaddingDp: Int,
    onClick: () -> Unit,
) {
    val arrow = navigateNextImageVector() ?: return
    val modifier = paddingSides(
        discoverClickable(clickableBase(), clickName, composer, interactionSource, onClick),
        start = 0,
        top = CELL_VERTICAL_PADDING_DP,
        end = trailingPaddingDp,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    iconVectorMethod().invoke(
        null,
        arrow,                                      // imageVector
        null,                                       // contentDescription：宿主同为 null
        modifier,                                   // 本格 clickable + 格内边距
        colorScheme(composer).longMethod(SURFACE_CONTAINER_HIGHEST_METHOD),  // tint，宿主同款 v34
        composer,
        ICON_CHANGED_MASK,                          // 宿主同款 0x30
        ICON_DEFAULT_MASK,                          // 必须 0：0x4 会让 Compose 丢掉我们的 modifier
    )
}

/**
 * 宿主箭头用的 `ImageVector`：`NavigateNextKt.getNavigateNext(Icons$AutoMirrored$Filled.INSTANCE)`。
 *
 * 用宿主自己的图标实例，才能保证箭头₁ 与箭头₂ 是同一个 `ImageVector`，视觉上不会有任何差异。
 */
private fun ReaMicroSettingsHook.navigateNextImageVector(): Any? =
    runCatching {
        method(NAVIGATE_NEXT_ICON_CLASS, NAVIGATE_NEXT_METHOD, NAVIGATE_NEXT_PARAMETER_COUNT).invoke(
            null,
            staticObject(ICONS_AUTO_MIRRORED_FILLED_OBJECT, "INSTANCE"),
        )
    }.getOrNull()

/** `IconKt.Icon-ww6aTOc` 的 ImageVector 版（三个 7 参重载里首参为本类型）。 */
private var cachedIconVectorMethod: Method? = null

private fun ReaMicroSettingsHook.iconVectorMethod(): Method =
    cachedIconVectorMethod ?: synchronized(DiscoverImageMethodLock) {
        cachedIconVectorMethod ?: cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
            it.name == ICON_METHOD &&
                it.parameterTypes.size == ICON_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        }?.apply { isAccessible = true }
            ?.also { cachedIconVectorMethod = it }
            ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
    }

// ── ② 中缝 ────────────────────────────────────────────────────────────────

/**
 * 中缝：宿主原样的 `Modifier.width(udp(0.8)).height(udp(56)).background(borderVariant)` + 无 content 的 `Box`。
 *
 * ## 为什么现在能直接写宿主那个 56dp
 *
 * 宿主双栏行里，中缝是**格子的兄弟节点**，行自身没有 padding，行高 = max(格子 56dp, 中缝 56dp) = 56dp，
 * 所以中缝正好顶满整行。
 *
 * 书院行原本不是这个形状：`Row(Modifier.clickable{…}.padding(16dp))` 把 16dp 放在**行外部**，
 * 行内容只有 24dp（图标高）；那时中缝写 56dp 会把自己变成行里最高的节点，整行被撑到 88dp，
 * 与双栏行不再等高，所以早先只能用「测量 24dp + `scale(1f, 56/24)` 放大绘制」绕过去。
 *
 * 本次按用户要求把两半都改成宿主格子结构（格子的 16dp 竖直内边距在 clickable 内部，
 * 于是每个节点本身就是 56dp 高），行里最高的节点已经是 56dp —— 中缝照抄宿主的 56dp 即可，
 * 不再需要 scale，观感与宿主那条竖线完全一致，少一层 graphicsLayer 变换。
 *
 * ## 其余逐条对齐宿主 smali（`.line 115-116`）
 *
 * - 宽度 `const-wide 0x3fe999999999999a` → `getUdp(Double)` = 0.8dp；
 * - 高度 `const/16 0x38` = 56dp；
 * - 颜色是宿主主题的 `ThemeKt.getBorderVariant(ColorScheme)`（eink 主题下宿主会换成
 *   `Color.White.copy(0.3f)`，这里不区分，普通主题观感完全一致）；
 * - `background-bw27NRU$default` 的 mask = 2 表示 `shape` 走默认（RectangleShape），与宿主相同；
 * - `BoxKt.Box(modifier, composer, 0)` 是宿主的原样值（宿主中缝的 changed 就是 0）：changed = 0
 *   在这里是安全的，因为该 3 参重载没有 content lambda，跳过也不影响已经挂上的 Modifier 节点。
 */
private fun ReaMicroSettingsHook.renderVerticalDivider(composer: Any) {
    val width = method(SIZE_KT_CLASS, WIDTH_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, modifierInstance(), udp(DIVIDER_WIDTH_DP))
    val height = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, width, udp(DIVIDER_HEIGHT_DP))
    val colored = method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, BACKGROUND_PARAMETER_COUNT).invoke(
        null,
        height,
        borderVariant(composer),
        null,                               // shape = null + mask = 2 → RectangleShape
        BACKGROUND_SHAPE_DEFAULT_MASK,
        null,
    )
    method(BOX_KT_CLASS, BOX_METHOD, BOX_PARAMETER_COUNT).invoke(null, colored, composer, 0)
}

/** `ThemeKt.getBorderVariant(ColorScheme): long`——宿主的中缝/描边色，普通主题下就是这条竖线的颜色。 */
private fun ReaMicroSettingsHook.borderVariant(composer: Any): Long =
    method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, colorScheme(composer)) as Long

// ── ② 书院格（左格）：给宿主原有节点就地补上本格 clickable 与内边距 ────────────

/**
 * 书院格前置图标（宿主的书图标 `Image`）的 modifier。
 *
 * 链序：`clickable(…).padding(16, 16, 0, 16).padding(0, 0, 12, 0).size(24dp)`，
 * 逐位对齐同好格的 `Modifier.weight(1f).clickable(…).padding(16, 16, 12, 16)`：
 *
 * - `start = 16dp` 与 `top = bottom = 16dp`：格子的朝外内边距与竖直内边距。书院行原先靠
 *   **行自己**的 `padding(16dp)` 提供这三边；本次行 padding 已撤（见 [DiscoverCardHook] 的
 *   `rowPadding`），改由本格各节点自带，视觉位置逐像素不变（16 + 24 + 16 = 56dp 与原来一致）；
 * - `end = 12dp`：图标与文字的间距，宿主图标本身带 `Modifier.padding(end = 12.dp)`（smali `.line 72`）。
 *   这里把它并进我们重建的链里，因此**宿主原 modifier 被整条替换**——尺寸与间距逐项等价
 *   （12dp 间距 + 24dp 图标），只是多挂了 clickable 与竖直内边距。
 *
 * clickable 仍在链首，所以整格高亮把这 16dp 与 12dp 全都覆盖在内。
 */
internal fun ReaMicroSettingsHook.leftCellIconModifier(composer: Any, onClick: () -> Unit): Any =
    sizedSquare(
        paddingSides(
            paddingSides(
                discoverClickable(
                    clickableBase(),
                    "ReaMicroLeftCellIcon",
                    composer,
                    sharedInteractionSource(CELL_LEFT),
                    onClick,
                ),
                start = CELL_OUTER_PADDING_DP,
                top = CELL_VERTICAL_PADDING_DP,
                end = 0,
                bottom = CELL_VERTICAL_PADDING_DP,
            ),
            start = 0,
            top = 0,
            end = ICON_LABEL_GAP_PADDING_DP,
            bottom = 0,
        ),
        DISCOVER_ICON_SIZE_DP,
    )

/**
 * 书院格文案（宿主的「书院」`Text`）的 modifier：
 * `clickable(…).padding(0, 16, 0, 16).height(24dp).wrapContentHeight(CenterVertically)`。
 *
 * 宿主这次 `Text-Nvy7gAk` 的 `modifier` 形参传的是 **null**（smali `.line 74` 前 `const/4 v4, 0x0`），
 * 所以直接整体替换即可，不需要拼接。
 *
 * 末尾那两个尺寸修饰符是本次补的，**不是可选的美化**：没有它们，文本节点内高等于文字行高
 * （约 20.67dp），比同格图标/留白/箭头的 24dp 矮，按压高亮在文字上下各留一条白缝。
 * 完整推导与实测数据见 [textNodeCellHeight]。
 */
internal fun ReaMicroSettingsHook.leftCellLabelModifier(composer: Any, onClick: () -> Unit): Any =
    textNodeCellHeight(
        paddingSides(
            discoverClickable(
                clickableBase(),
                "ReaMicroLeftCellLabel",
                composer,
                sharedInteractionSource(CELL_LEFT),
                onClick,
            ),
            start = 0,
            top = CELL_VERTICAL_PADDING_DP,
            end = 0,
            bottom = CELL_VERTICAL_PADDING_DP,
        ),
    )

/**
 * 书院格撑开 Spacer（宿主原有的 `Spacer(Modifier.weight(1f))`）的 modifier。
 *
 * `weight(1f).clickable(…).padding(0, 16, 0, 16).height(24dp)`，链序理由与
 * [renderDiscoverFiller] 完全相同：weight 必须在最外层（parent-data 要能被 `Row` 读到），
 * clickable 在 padding 外面（高亮范围 = 分配宽度 × 56dp），height 垫在最内
 * （`SpacerKt.Spacer` 自身测量恒为 0×0，不垫高度就既点不到、也画不出反馈，
 * 而且那片留白会穿透到别处）。
 */
internal fun ReaMicroSettingsHook.leftCellSpacerModifier(
    rowScope: Any,
    composer: Any,
    onClick: () -> Unit,
): Any {
    val weighted = method(ROW_SCOPE_INSTANCE_CLASS, ROW_WEIGHT_METHOD, ROW_WEIGHT_PARAMETER_COUNT)
        .invoke(rowScope, modifierInstance(), 1f, true)
    val clickable = discoverClickable(
        weighted,
        "ReaMicroLeftCellSpacer",
        composer,
        sharedInteractionSource(CELL_LEFT),
        onClick,
    )
    return method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT).invoke(
        null,
        paddingSides(
            clickable,
            start = 0,
            top = CELL_VERTICAL_PADDING_DP,
            end = 0,
            bottom = CELL_VERTICAL_PADDING_DP,
        ),
        udp(DISCOVER_ICON_SIZE_DP),
    )
}

/** `size-3ABfNKs(Modifier, dp)`：正方形边长，宿主各图标用的就是这个 `size(24.dp)`。 */
private fun ReaMicroSettingsHook.sizedSquare(base: Any, dp: Int): Any =
    method(SIZE_KT_CLASS, SIZE_METHOD, SIZE_PARAMETER_COUNT).invoke(null, base, udp(dp))

/**
 * 把**文本节点**顶到与同格其它节点一样高：`height(24dp).wrapContentHeight(CenterVertically)`。
 *
 * ## 为什么非补不可（这是「按压高亮有白缝」的唯一原因）
 *
 * 宿主的一个格子是 `Modifier.weight(1f).clickable{…}.padding(16,16,12,16)`：**一个** clickable
 * 包住整格，按压反馈是**一整块矩形**。我们没法在注入点造容器（见本文件顶部「为什么不能自建容器」），
 * 只能把一格的四个兄弟节点各自挂上 clickable，靠「四块矩形无缝拼成一整块」来等价还原。
 *
 * 拼合的前提是**四块高度必须完全一致**，而它们并不一致：
 *
 * | 节点 | 内高来源 | 节点高 |
 * | --- | --- | --- |
 * | 图标 | `size(24dp)` | 16 + 24 + 16 = **56dp** |
 * | 留白 Spacer | 显式 `height(24dp)` | **56dp** |
 * | 收尾箭头 | `Icon` 自带 `size(24dp)` | **56dp** |
 * | **文本** | 只有 `padding(0,16,0,16)`，内高 = `TitleSmall` 行高 ≈ **20.67dp** | **52.67dp** |
 *
 * 于是文本那一段的高亮比左右两段各矮约 1.67dp（实机 3px / 5px），在两处留下**白缝** ——
 * 这正是用户报的「点击后文本上下有白色」，也是它看着不像宿主那种「整块压下去」的原因。
 *
 * ## 两个修饰符缺一不可
 *
 * - `height(24dp)`：把节点内高从「文字行高」顶到 24dp，两侧 padding 之外正好 56dp；
 * - `wrapContentHeight(CenterVertically)`：`height` 只给固定约束，`BasicText` 的段落是**贴顶**摆放的，
 *   单加 `height` 会让文字整体上移约 1.67dp。`wrapContentHeight` 先把 min 约束放松到内容自然高度、
 *   再按 `CenterVertically` 在 24dp 内居中，于是**字形位置与宿主逐像素一致**（实机：文字行
 *   y1772–1804 前后不变），只有高亮矩形的上下边被撑到整格。
 *
 * 本函数只是把这两个宿主自带的 `SizeKt` 修饰符接起来，不引入任何自造参数。
 */
private fun ReaMicroSettingsHook.textNodeCellHeight(base: Any): Any {
    val fixed = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, base, udp(DISCOVER_ICON_SIZE_DP))
    return method(SIZE_KT_CLASS, WRAP_CONTENT_HEIGHT_METHOD, WRAP_CONTENT_HEIGHT_PARAMETER_COUNT)
        .invoke(null, fixed, alignmentCenterVertically(), false)
}

/**
 * `SizeKt.fillMaxWidth$default(Modifier, F, I, Object)`：宿主第 2 行行级 modifier 用的就是它
 * （smali `.line 88`：`fillMaxWidth$default(Modifier.Companion, 0f, 1, null)`，mask = 1 表示
 * `fraction` 取默认值 1f）。
 *
 * 模块这里用它把书院行的 `padding(16dp)` 换掉——见 [DiscoverCardHook.hookRowPadding]。
 * 直接调 `$default` 是因为宿主的字节码里只有这一条，单独的非 `$default` 版本可能被 R8 裁掉。
 */
internal fun ReaMicroSettingsHook.fillMaxWidthModifier(base: Any): Any =
    method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, FILL_MAX_WIDTH_DEFAULT_PARAMETER_COUNT)
        .invoke(null, base, 0f, FILL_MAX_WIDTH_DEFAULT_MASK, null)

// ── ③ 发现半区 ────────────────────────────────────────────────────────────

/**
 * 发现格的前置图标，modifier 链为 `clickable{…}.padding(12, 16, 12, 16).size(24dp)`，
 * 与野社格的图标同尺寸、同留白，并且自带整格竖直内边距（见下）。
 *
 * 三个方向的 padding 各对应宿主的一处（都用宿主自己的常量，不做像素反推）：
 *
 * - `start = 12dp`：野社格朝中缝一侧的 padding（宿主 smali `.line 121` 的 `padding(12,16,16,16)` 第一位）；
 * - `end = 12dp`：宿主每个图标自带的 `Modifier.padding(end = 12.dp)`（`.line 72 / 100 / 127`），
 *   也就是图标与文字之间的间距，少了它「发现」会比「野社」靠左 12dp；
 * - `top = bottom = 16dp`：野社格 `padding(12,16,16,16)` 的第二、四位。**这 16dp 在 clickable 内部**，
 *   所以节点的坐标空间就是 56dp 高的整格，按压高亮于是与宿主格子一样顶满整行
 *   （原先这 16dp 由宿主的 `Row(Modifier.clickable{…}.padding(16dp))` 提供、位于行外部，
 *   反馈只能画在 24dp 的内容带上）。
 *
 * 两者相加 = 24dp，与「书院格朝内那侧的 12dp（挂在箭头上）+ 朝外那侧 16dp（挂在书图标上）」
 * 配平，两格固定宽度严格相等，中缝因此落在行内容正中。
 *
 * `clickable` 必须比 `padding`/`size` **更靠外**（链首），热区与高亮范围才含这几段留白 —— 与宿主格子
 * `clickable{…}.padding(12,16,16,16)` 同构。写成链尾会让范围收缩到 24dp 的图标本体，
 * 图标与文字之间会裂出一道 12dp 的死缝，详见函数体内注释。
 *
 * ## 两个参数绝对不能传 null（两轮崩溃的血泪教训，均为实测堆栈定案）
 *
 * 宿主调的是**非-$default 的 10 参 `Image`**，说明 `alignment` 与 `contentScale` 都是
 * 宿主显式传的非空对象（Kotlin 非空类型不可能合法传 null）。从反射侧传 null 不会立刻
 * 报错——会一直潜伏到**渲染线程的 draw 阶段**才炸，`runCatching` 完全拦不住：
 *
 * ```
 * // 第一轮（contentScale = null）
 * NullPointerException: ContentScale.computeScaleFactor-H7hwNQA(...)
 *   at GraphicsLayerOwnerLayer.drawLayer
 * // 第二轮（alignment = null）
 * NullPointerException: Alignment.align-KFBX0sM(long, long, LayoutDirection)
 *   at androidx.compose.ui.draw.PainterNode.draw(PainterModifier.kt:330)
 * ```
 *
 * 所以这里 alignment / contentScale 一律取宿主自己的非 null 实例：
 * `alignmentCenter()` 是模块既有辅助函数（书源页在用），`hostContentScale()` 取宿主
 * `ContentScale$Companion.Crop`。其余参数对齐宿主实参：contentDescription / colorFilter
 * 为 null（宿主同为 null，此二者是可空参数）、alpha = 1f（`DefaultAlpha`，传 0 会全透明）。
 */
private fun ReaMicroSettingsHook.renderDiscoverIcon(
    icon: Any,
    composer: Any,
    interactionSource: Any?,
    onDiscoverClick: () -> Unit,
) {
    // clickable 必须比 padding/size **更靠外**：`Modifier.clickable{…}.padding(…).size(…)`。
    // 这正是宿主格子的写法（`Modifier.clickable{…}.padding(12,16,16,16)`）。
    //
    // 关键在按压反馈的坐标空间：indication 是挂在 `ClickableElement` 上的（见 [discoverClickable]），
    // 它绘制时用**自己所在链位置的坐标空间**。放在链首 → 空间 = 整个节点
    // （12dp + 图标 + 12dp 共 48dp 宽、16 + 24 + 16 = 56dp 高），内边距一并被覆盖；
    // 反过来写成 `padding(…).size(…).clickable{…}`（clickable 落链尾）空间就收缩到 `size(24.dp)` 那块，
    // 实测按下态在「图标」和「发现」文字之间裂开一道 36px（12dp）的缝、两段高亮各自成块，
    // 正是用户报的「图标文本和箭头都会独立选中」。
    //
    // 顺带：可点范围同理，12dp 内边距也在热区内，不会穿透到宿主书院行的 clickable 上。
    val clickable = discoverClickable(clickableBase(), "ReaMicroDiscoverIcon", composer, interactionSource, onDiscoverClick)
    val padded = paddingSides(
        clickable,
        start = DIVIDER_SIDE_PADDING_DP,
        top = CELL_VERTICAL_PADDING_DP,
        end = ICON_LABEL_GAP_PADDING_DP,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    val sized = method(SIZE_KT_CLASS, SIZE_METHOD, SIZE_PARAMETER_COUNT).invoke(null, padded, udp(DISCOVER_ICON_SIZE_DP))
    imageVectorMethod().invoke(
        null,
        icon,                // imageVector
        null,                // contentDescription：宿主同为 null（可空参数）
        sized,               // modifier：clickable 包住 12dp + 图标 + 12dp，整块 48×24dp 都是一个热区
        alignmentCenter(),   // alignment：非空参数，宿主 Alignment.Center
        hostContentScale(),  // contentScale：非空参数，宿主 Crop
        1f,                  // alpha：DefaultAlpha，0 会全透明
        null,                // colorFilter：宿主同为 null（可空参数）
        composer,
        IMAGE_CHANGED_MASK,  // 宿主同款 0x30
        IMAGE_CHANGED2_MASK, // 宿主同款 0x78
    )
}

/**
 * 宿主 `ContentScale$Companion.Crop`（非 null 的 `ContentScale`）。
 *
 * 取自宿主自身而非自造对象，保证与其它图标用的是同一实现，也避开服务端 Instant 化问题。
 * 只求非 null；`Crop` 与图标默认的 `Fit` 在 24dp 正方形画布上渲染结果一致。
 *
 * 该 Companion 是 Kotlin `object`，**实例字段名是 `$$INSTANCE` 而不是 `INSTANCE`**
 * （实测报错原文：`ContentScale$Companion.INSTANCE not found; fields=[$$INSTANCE:..., Crop:...]`）。
 * 既然它已把 `Crop` 直接暴露为静态字段，直接取字段比「取实例再调 `getCrop()`」少一次反射。
 */
private fun ReaMicroSettingsHook.hostContentScale(): Any =
    cachedHostContentScale ?: synchronized(DiscoverImageMethodLock) {
        cachedHostContentScale ?: staticObject(CONTENT_SCALE_COMPANION_CLASS, CONTENT_SCALE_CROP_FIELD)
            .also { cachedHostContentScale = it }
    }

@Volatile
private var cachedHostContentScale: Any? = null

/**
 * `ImageKt.Image(ImageVector, String, Modifier, Alignment, ContentScale, float, ColorFilter, Composer, int, int)`。
 *
 * `Image` 有三个重载，首参分别是 `ImageBitmap` / `Painter` / `ImageVector`，**都是 10 参**。
 * 必须同时限定「参数个数 = 10」与「首参是 ImageVector」，否则会命中另外两个版本。
 *
 * 每次渲染都做一次 `declaredMethods` 过滤代价偏高，这里用文件级缓存只解析一次。
 */
private var cachedImageVectorMethod: Method? = null

private fun ReaMicroSettingsHook.imageVectorMethod(): Method =
    cachedImageVectorMethod ?: synchronized(DiscoverImageMethodLock) {
        cachedImageVectorMethod ?: cls(IMAGE_KT_CLASS).declaredMethods.firstOrNull {
            it.name == IMAGE_METHOD &&
                it.parameterTypes.size == IMAGE_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        }?.apply { isAccessible = true }
            ?.also { cachedImageVectorMethod = it }
            ?: error("$IMAGE_KT_CLASS.$IMAGE_METHOD ImageVector overload not found")
    }

private val DiscoverImageMethodLock = Any()

/**
 * 文案：`TitleSmall` + `onBackground`，与宿主「书院 / 同好 / 野社」逐项一致。
 *
 * `Text-Nvy7gAk` 是宿主当前版本的实际入口（MT 反编译确认 22 参，倒数第三位是 `TextStyle`），
 * 因此这里传 `null` 给 `onTextLayout`、样式对象走 `getTitleSmall`。
 *
 * modifier 只带 clickable、不带 weight：宽度由文字本身决定，剩余空间交给后面的撑开 Spacer，
 * 这与野社格里「文字紧贴图标、箭头靠右」的排布完全一致。
 */
private fun ReaMicroSettingsHook.renderDiscoverLabel(text: String, modifier: Any, composer: Any) {
    method(TEXT_KT_CLASS, TEXT_METHOD, TEXT_PARAMETER_COUNT).invoke(
        null,
        text,
        modifier,
        colorScheme(composer).longMethod("getOnBackground"),
        null,               // fontSize
        0L,                 // fontStyle
        null,               // fontWeight
        null,               // fontFamily
        null,               // letterSpacing
        0L,                 // textDecoration
        null,               // textAlign
        null,               // lineHeight
        0L,                 // overflow
        0,                  // softWrap
        false,              // maxLines
        1,
        1,
        null,               // onTextLayout
        typography(composer).method0("getTitleSmall"),
        composer,
        0,
        0,
        DISCOVER_LABEL_MASK,
    )
}

/**
 * 撑开 Spacer：`Modifier.weight(1f).clickable(…).padding(0, 16, 0, 16).height(24dp)` + `SpacerKt.Spacer`。
 *
 * 这是「发现」格能占满右半边、并把中缝顶到正中的关键——它与书院格里宿主原有的那个
 * `Spacer(weight(1f))` 平分剩余宽度。weight 用的必须是书院行那个 Row 的 scope
 * （由 [DiscoverCardHook] 捕获后传进来），自己造 scope 拿不到行上下文。
 *
 * 带上 clickable 是因为这块空白占了发现格的大半；不带的话点到留白会落到宿主书院行的点击上，
 * 表现就是「点发现周围却进了书院」。
 *
 * ## 链序为什么是 `weight → clickable → padding → height`
 *
 * - `weight` 必须在**最外层**：它是 parent-data 元素，由 `Row` 读取来分配宽度，放在里面
 *   （例如被 clickable 包住）会读不到，格子宽度失控；
 * - `clickable` 要在 `padding` **外面**：indication 用它所在节点的坐标空间绘制，这个位置拿到的
 *   正是「分配宽度 × (24 + 16 + 16)dp」的整格矩形，与宿主格子一致；
 * - `padding(0,16,0,16)` 是野社格竖直两侧的 16dp（`padding(12,16,16,16)` 的第二、四位）；
 * - `height(24dp)` 放最内：`SpacerKt.Spacer` 自身测量恒为 **0×0**，不垫高度的话
 *   padding 之后也只有 32dp 高、且命中测试拿不到高度。这 24dp 与图标等高，
 *   补上后整格正好 16 + 24 + 16 = 56dp，与「同好 ｜ 野社」严格相等。
 */
private fun ReaMicroSettingsHook.renderDiscoverFiller(
    rowScope: Any,
    composer: Any,
    interactionSource: Any?,
    onDiscoverClick: () -> Unit,
) {
    val weighted = method(ROW_SCOPE_INSTANCE_CLASS, ROW_WEIGHT_METHOD, ROW_WEIGHT_PARAMETER_COUNT)
        .invoke(rowScope, modifierInstance(), 1f, true)
    val clickable = discoverClickable(weighted, "ReaMicroDiscoverFiller", composer, interactionSource, onDiscoverClick)
    val padded = paddingSides(
        clickable,
        start = 0,
        top = CELL_VERTICAL_PADDING_DP,
        end = 0,
        bottom = CELL_VERTICAL_PADDING_DP,
    )
    val sized = method(SIZE_KT_CLASS, HEIGHT_METHOD, SIZE_PARAMETER_COUNT)
        .invoke(null, padded, udp(DISCOVER_ICON_SIZE_DP))
    method(SPACER_KT_CLASS, SPACER_METHOD, SPACER_PARAMETER_COUNT).invoke(null, sized, composer, 0)
}

// ── Modifier 与点击 ────────────────────────────────────────────────────────

/**
 * 发现半区节点的 clickable Modifier —— **带真正的 `Indication`**。
 *
 * ## 为什么模块不直接照抄宿主那个重载
 *
 * 宿主社区卡片里每个 `Modifier.clickable{…}` 用的是
 * `clickable-oSLSa3U$default(Modifier, Z, String, Role, MutableInteractionSource, Function0, I, Object)`，
 * 实参是 `mask=0xf`、后四项全 0（dex_method 原文）。它的实现体只有两句：
 *
 * ```smali
 * ClickableElement(interactionSource, null /* IndicationNodeFactory */, 1, enabled, …, onClick)
 * Modifier.then(该元素)
 * ```
 *
 * 注意第三个实参是 **`1`（true）**：`ClickableKt.clickable-XHw0xAI` 上的弃用注解写着
 * “Replaced with new overload that only supports IndicationNodeFactory instances inside
 * LocalIndication, and does not use composed” —— 这个新重载把「读 `LocalIndication`」搬进了
 * **ClickableNode 内部**（节点实现 `CompositionLocalConsumerModifierNode`），调用方不再需要（也无法）
 * 传 Indication。宿主格子那一整块按压高亮就是这么来的。
 *
 * 模块这条链只能拿到 Modifier，拿不到 ClickableNode 内部，所以**自己**把 indication 取出来，
 * 走带 `Indication` 实参的那个重载：
 *
 * ```smali
 * ClickableKt;->clickable-O2vRcR0$default(
 *     Modifier, MutableInteractionSource, Indication, Z, String, Role, Function0, I, Object)
 * ```
 *
 * 其实现体在 `indication is IndicationNodeFactory` 分支里构造
 * `ClickableElement(interactionSource, 该 Indication, 0, enabled, …)` —— 与宿主那条路径**终点相同**，
 * 只是布尔位相反（`0` = 不要自己读 LocalIndication，用我传的这个）。
 *
 * indication 取自 `LocalIndication.current`，与宿主 ClickableNode 内部读的是同一个 CompositionLocal，
 * 所以拿到的是**同一个对象**，观感天然一致。实机按下态两边逐像素相同：整格平铺的浅灰状态层
 * `(236,235,235)`，逐帧抓取也确认**不是波纹**（帧 2 起即为实心矩形，无圆形扩散轮廓）。
 *
 * ## 「整块选中」由三件事各解决一块
 *
 * 1. **反馈的范围** = indication 所在节点的坐标空间。本函数返回的 Modifier 被调用方放在链首
 *    （`clickable(…).padding(…).size(…)`，与宿主格子的 `clickable{…}.padding(…)` 同构），
 *    因此这个坐标空间就是**整个节点**：图标那段 `start/end = 12dp` 的内边距也一并覆盖，
 *    四个节点的选中区因此首尾相接、不再出现裂缝。
 * 2. **四块同时亮** = 四个节点共用同一个 `MutableInteractionSource`（见 [sharedInteractionSource]）。
 *    若各传 `null`，Compose 会给每个节点各造一个源，按到谁只有谁亮。
 * 3. **四块等高** = 文本节点必须补到 24dp 内高（见 [textNodeCellHeight]）。少了这一条，
 *    文字那段的高亮比左右两段矮，拼出来的整块会在文字上下各留一条白缝。
 *
 * ## mask 位序（宿主 smali 反证，不是猜的）
 *
 * `$default` 掩码的 bit k 对应**第 k 个值形参**（扩展接收者不占位）。本重载的值形参是
 * `interactionSource(0) / indication(1) / enabled(2) / onClickLabel(3) / role(4) / onClick(5)`。
 * 模块既有的 [ReaMicroSettingsHook.clickableModifier] 传 mask = 28（`0b11100`）：把
 * enabled/label/role 三位交给默认值、保留 interactionSource/indication 两位用实参 ——
 * 与本函数的意图完全一致，位序由此互证。这里沿用同一个 28。
 *
 * 反馈半径不会跑飞：`CommonRippleNode` 的半径来自节点自身尺寸（`RippleNode.rippleSize` +
 * `targetRadius`，与按压点无关），`Press` 里的坐标只作为动画起点，`RippleAnimation` 会把它向
 * 节点中心收敛，最终圆心落在各节点自己的中心、半径内接于节点，越界部分极小。
 */
private fun ReaMicroSettingsHook.discoverClickable(
    base: Any,
    name: String,
    composer: Any,
    interactionSource: Any?,
    onClick: () -> Unit,
): Any =
    method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, CLICKABLE_DEFAULT_PARAMETER_COUNT).invoke(
        null,
        base,
        interactionSource,          // 发现半区四个节点共用同一个，pressed 态才会同步
        localIndication(composer),  // 与宿主格子同一套反馈；取不到时退回「无反馈」的旧行为
        false,                      // enabled：mask bit2 已置位，此值被忽略、取默认 true
        null,                       // onClickLabel：同上
        null,                       // role：同上
        functionProxy(name, FUNCTION0_CLASS) {
            onClick()
            targetUnit()
        },
        CLICKABLE_DEFAULT_MASK,
        null,
    )

/**
 * `LocalIndication.current`，即宿主当前主题的按压反馈实现。
 *
 * 读取方式照抄宿主自己的 composed 工厂（smali 实证）：
 * `IndicationKt.getLocalIndication()` 拿到 `ProvidableCompositionLocal`，
 * 再由 `Composer->consume(CompositionLocal)` 取出当前值。
 *
 * 只能在组合中调用（需要 Composer）。反射失败时只记一次日志并返回 null，
 * 调用方据此退回「不带反馈」的旧行为，不会让异常穿透到宿主的 Compose 渲染栈。
 */
private fun ReaMicroSettingsHook.localIndication(composer: Any): Any? {
    if (localIndicationUnavailable) return null
    return runCatching {
        val local = method(INDICATION_KT_CLASS, LOCAL_INDICATION_GETTER, 0).invoke(null)
        method(COMPOSER_CLASS, COMPOSER_CONSUME_METHOD, 1).invoke(composer, local)
    }.onFailure {
        // 卡片会被反复重组，失败只记一次，避免日志被刷屏。
        localIndicationUnavailable = true
        XposedBridge.logAlways("$LOG_PREFIX local indication unavailable: ${it.message}")
    }.getOrNull()
}

/** `LocalIndication` 反射不可用后置位：后续直接退回「无反馈」的旧行为，不再重试。 */
@Volatile
private var localIndicationUnavailable = false

/**
 * 同一格内所有节点共用的 `MutableInteractionSource`（书院格 / 发现格各一个，见 [CELL_LEFT] / [CELL_RIGHT]）。
 *
 * 走宿主自己的工厂 `androidx.compose.foundation.interaction.InteractionSourceKt.MutableInteractionSource()`
 * （MT 反编译确认：`public static final`、零形参、实现体就是 `new MutableInteractionSourceImpl()`），
 * 不自己实现接口 —— 宿主 Compose 版本的接口成员数量不做保证，代理类容易在版本变动后失灵。
 *
 * 只造一次并常驻：交互源是有状态的（按下/抬起要发同一条 `PressInteraction`），
 * 每次重组都换新实例会让「按着不放时发生重组」丢掉 pressed 态。
 *
 * 两格**不能**共用同一个源：那样按书院格会把发现格一起点亮（pressed 态是源上的状态）。
 *
 * 反射失败时返回 null，调用方退回「一个节点一个 source」的旧行为（各自独立波纹），
 * 不会让异常穿透到宿主的 Compose 渲染栈。
 */
private fun ReaMicroSettingsHook.sharedInteractionSource(cell: Int): Any? {
    cachedDiscoverInteractionSources.getOrNull(cell)?.let { return it }
    if (discoverInteractionSourceUnavailable) return null
    return synchronized(DiscoverImageMethodLock) {
        cachedDiscoverInteractionSources.getOrNull(cell)?.let { return@synchronized it }
        if (discoverInteractionSourceUnavailable) return@synchronized null
        runCatching {
            method(
                INTERACTION_SOURCE_KT_CLASS,
                INTERACTION_SOURCE_FACTORY_METHOD,
                INTERACTION_SOURCE_FACTORY_PARAMETER_COUNT,
            ).invoke(null)
        }.onFailure {
            // 卡片会被反复重组，失败只记一次，避免日志被刷屏。
            discoverInteractionSourceUnavailable = true
            XposedBridge.logAlways("$LOG_PREFIX shared interaction source unavailable: ${it.message}")
        }.getOrNull()?.also { cachedDiscoverInteractionSources[cell] = it }
    }
}

/** 两格的交互源缓存：下标 0 = 书院格，1 = 发现格（见 [CELL_LEFT] / [CELL_RIGHT]）。 */
private val cachedDiscoverInteractionSources = arrayOfNulls<Any>(2)

/** 工厂反射失败后置位：后续直接退回「各节点一个 source」的旧行为，不再重试。 */
@Volatile
private var discoverInteractionSourceUnavailable = false

/**
 * clickable 的底座 Modifier：一条**非空**的 Modifier 链（`padding(0)`，不改变任何布局）。
 *
 * 为什么不用 `Modifier.Companion` 当底座：实测发现「挂在空 Modifier 上的 clickable」
 * 拿不到点击 —— accessibility 树里根本没有它的节点，而挂在 `size(...)`/`weight(...)` 上的
 * 都能看到。空底座走的是 `Modifier.Companion.then(ClickableElement)`，返回的链只有一个元素，
 * 与其它节点在 materialize 时的处理路径不同。这里垫一个零值 padding 元素，
 * 让文字与箭头也和图标、Spacer 走同一条路径。
 */
private fun ReaMicroSettingsHook.clickableBase(): Any =
    paddingSides(modifierInstance(), start = 0, top = 0, end = 0, bottom = 0)

/** 四边独立内边距，全部显式传值。`padding-qDBjuR0(Modifier, F, F, F, F)`。 */
private fun ReaMicroSettingsHook.paddingSides(base: Any, start: Int, top: Int, end: Int, bottom: Int): Any =
    method(PADDING_KT_CLASS, PADDING_SIDES_METHOD, PADDING_SIDES_PARAMETER_COUNT).invoke(
        null,
        base,
        udp(start),
        udp(top),
        udp(end),
        udp(bottom),
    )

// ── 常量 ──────────────────────────────────────────────────────────────────

private const val DISCOVER_LABEL = "发现"

private const val DISCOVER_ICON_SIZE_DP = 24

/**
 * 中缝两侧的「朝内 padding」= 12dp。
 *
 * 宿主双栏格里，同好格是 `padding(16, 16, 12, 16)`、野社格是 `padding(12, 16, 16, 16)`：
 * 朝外那一侧 16dp、朝中缝那一侧 12dp。改造后两个格子各自负责自己那一侧，
 * 因此这 12dp 分别挂在书院格的收尾箭头与发现格的前置图标上。
 */
private const val DIVIDER_SIDE_PADDING_DP = 12

/** 格子的竖直内边距 = 16dp（同好/野社两格 `padding(…, 16, …, 16)` 的第二、四位）。 */
private const val CELL_VERTICAL_PADDING_DP = 16

/** 格子朝外那一侧的内边距 = 16dp（同好格首位、野社格第三位）。 */
private const val CELL_OUTER_PADDING_DP = 16

/** 书院格（左格）收尾箭头朝中缝一侧的内边距 = 12dp（同好格 `padding(16,16,12,16)` 的第三位）。 */
private const val CELL_LEFT_ARROW_END_PADDING_DP = 12

/** 发现格（右格）收尾箭头朝外一侧的内边距 = 16dp（野社格 `padding(12,16,16,16)` 的第三位）。 */
private const val CELL_RIGHT_ARROW_END_PADDING_DP = 16

/** 两格的交互源下标：0 = 书院格，1 = 发现格（见 [sharedInteractionSource]）。 */
private const val CELL_LEFT = 0
private const val CELL_RIGHT = 1

/** 宿主每个图标自带的 `Modifier.padding(end = 12.dp)`（smali `.line 72 / 100 / 127`）：图标与文字的间距。 */
private const val ICON_LABEL_GAP_PADDING_DP = 12

/** 中缝宽度，照抄宿主 `0.8.dp`（`const-wide 0x3fe999999999999a`）。 */
private const val DIVIDER_WIDTH_DP = 0.8

/**
 * 中缝高度 = 56dp，宿主中缝的原始高度（`const/16 0x38`）。
 *
 * 改造后行里每个节点本身就是 56dp（24dp 内容 + 上下各 16dp 格内边距），
 * 中缝照抄宿主值即可顶满整行，不需要早先那套「测量 24dp + scale 放大绘制」的绕行方案。
 */
private const val DIVIDER_HEIGHT_DP = 56

/**
 * `Image` 的 changed / changed2 掩码，照抄宿主 `CommunityKt` 每一处 `Image` 调用的实参
 * （smali 实测 `const/16 0x30` + `const/16 0x78`）。绝不能填 0。
 */
private const val IMAGE_CHANGED_MASK = 0x30
private const val IMAGE_CHANGED2_MASK = 0x78

/** 宿主箭头 `Icon-ww6aTOc` 的 `$changed`（smali 实测 `0x30`）。 */
private const val ICON_CHANGED_MASK = 0x30

/**
 * `Icon-ww6aTOc` 末位那个 `$default` 掩码：**必须 0**，即所有形参都用调用方显式传入的值。
 *
 * 宿主书院行/同好行/野社行的箭头源码都没写 `modifier`，所以编译器给的是 `0x4`
 * —— bit2 = 第 2 个形参 `modifier` 走默认值（smali 里那个形参寄存器是 `const/4 v4, 0x0`）。
 * 我们**照抄这个 0x4 就踩了坑**：它会让 Compose 直接忽略我们传进去的 modifier，
 * 于是箭头₁ 的 12dp 内边距、箭头₂ 的 clickable 全部静默失效。
 *
 * 后果是精确可算的：书院半区的固定宽度少 12dp，两个 `weight(1f)` 撑开的剩余空间不变，
 * 中缝因此整体左移 12 / 2 = 6dp = 18px（1220px 宽、密度 3.0 截图上实测正好 18px）。
 * 改成 0 之后 modifier 形参生效，两半固定宽度严格相等，中缝落回行内容正中。
 */
private const val ICON_DEFAULT_MASK = 0x0

private const val ROW_WEIGHT_PARAMETER_COUNT = 3

/** `Image` 三个重载（ImageBitmap / Painter / ImageVector）参数个数都是 10。 */
private const val IMAGE_PARAMETER_COUNT = 10
private const val TEXT_PARAMETER_COUNT = 22

/**
 * 文案用的 `Text-Nvy7gAk` mask：模块既有的 `TEXT_SINGLE_LINE_MASK`（73722）**清掉 bit1**。
 *
 * mask 的 bit k 表示「第 k 个参数（0-based）取默认值」。`Text-Nvy7gAk` 的第 0 个参数是
 * `text`（必需参数），**第 1 个就是 `modifier`**。既有的单行 mask 把 bit1 置了 1，
 * 等于告诉 Compose「modifier 用默认值」——于是**挂在文字上的 clickable 会被整个丢掉**：
 * 文字照常渲染，但点上去毫无反应，accessibility 树里也不会有它的节点
 * （实测发现文字所在区间没有任何 clickable 节点，点击直接穿透到宿主整行的点击上，
 * 表现就是「点『发现』却进了书院」）。
 *
 * 73722 = 0x11FBA，最低 nibble 是 0xA，bit1 正是置位的；清掉后为 73720 = 0x11FB8。
 * 其余位不动，单行/样式行为与设置页保持一致。
 */
private const val DISCOVER_LABEL_MASK = 73720

/** `Icon-ww6aTOc(ImageVector, String, Modifier, long, Composer, int, int)`。 */
private const val ICON_PARAMETER_COUNT = 7

/** `SpacerKt.Spacer(Modifier, Composer, int)`。 */
private const val SPACER_PARAMETER_COUNT = 3

/** `BoxKt.Box(Modifier, Composer, int)`——无 content 的那个重载，宿主中缝用的就是它。 */
private const val BOX_PARAMETER_COUNT = 3

/** `background-bw27NRU$default(Modifier, long, Shape, int, Object)`。 */
private const val BACKGROUND_PARAMETER_COUNT = 5

/** `background` 的 mask：仅 bit1（shape）置位 → shape 取默认 RectangleShape。 */
private const val BACKGROUND_SHAPE_DEFAULT_MASK = 2

/**
 * 带 Indication 的 clickable：`clickable-O2vRcR0$default`，共 9 个形参
 * （Modifier + 6 个值形参 + mask + marker）。方法名复用 [CLICKABLE_DEFAULT_METHOD]。
 */
private const val CLICKABLE_DEFAULT_PARAMETER_COUNT = 9

/**
 * 上面这条 `$default` 的 mask = 28（`0b11100`）：把 `enabled`(bit2) / `onClickLabel`(bit3) /
 * `role`(bit4) 三位交给默认值，保留 `interactionSource`(bit0) 与 `indication`(bit1) 用调用方实参。
 *
 * 与模块既有的 [ReaMicroSettingsHook.clickableModifier] 取同一个值，位序因此互证；
 * 位序依据详见 [discoverClickable] 的注释。
 */
private const val CLICKABLE_DEFAULT_MASK = 28

/** `IndicationKt.getLocalIndication()`：宿主主题的按压反馈实现（Material3 下即 `ripple()`）。 */
private const val INDICATION_KT_CLASS = "androidx.compose.foundation.IndicationKt"
private const val LOCAL_INDICATION_GETTER = "getLocalIndication"

/** `Composer.consume(CompositionLocal)`：宿主 Compose 版本在组合中读取 CompositionLocal 的入口。 */
private const val COMPOSER_CONSUME_METHOD = "consume"

/** `InteractionSourceKt.MutableInteractionSource()`：宿主 Compose 自带的交互源工厂（零形参）。 */
private const val INTERACTION_SOURCE_KT_CLASS = "androidx.compose.foundation.interaction.InteractionSourceKt"
private const val INTERACTION_SOURCE_FACTORY_METHOD = "MutableInteractionSource"
private const val INTERACTION_SOURCE_FACTORY_PARAMETER_COUNT = 0

/** `size-3ABfNKs` / `width-3ABfNKs` / `height-3ABfNKs` 都是 `(Modifier, float)` 两参。 */
private const val SIZE_PARAMETER_COUNT = 2

private const val NAVIGATE_NEXT_METHOD = "getNavigateNext"
private const val NAVIGATE_NEXT_PARAMETER_COUNT = 1

/** `Icons$AutoMirrored$Filled` 的 JVM 名（MT 检索确认存在；`$` 需在 Kotlin 字符串里转义）。 */
private const val ICONS_AUTO_MIRRORED_FILLED_OBJECT = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"

/** 箭头 tint：`ColorScheme.getSurfaceContainerHighest-0d7_KjU()`（宿主箭头 v34 的来源）。 */
private const val SURFACE_CONTAINER_HIGHEST_METHOD = "getSurfaceContainerHighest-0d7_KjU"

private const val IMAGE_KT_CLASS = "androidx.compose.foundation.ImageKt"
private const val IMAGE_METHOD = "Image"

private const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
private const val ICON_METHOD = "Icon-ww6aTOc"

/** `Image` / `Icon` 重载的首参类型，用它区分 ImageVector 版本。 */
private const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"
private const val ROW_SCOPE_INSTANCE_CLASS = "androidx.compose.foundation.layout.RowScopeInstance"
private const val ROW_WEIGHT_METHOD = "weight"

/** 宿主 `ContentScale$Companion`，用于取一个非 null 的 ContentScale（`Image` 传 null 会崩）。 */
private const val CONTENT_SCALE_COMPANION_CLASS = "androidx.compose.ui.layout.ContentScale\$Companion"

/** Companion 上直接暴露的 ContentScale 静态字段名。 */
private const val CONTENT_SCALE_CROP_FIELD = "Crop"

/** `padding-qDBjuR0(Modifier, F, F, F, F)`：四边独立（start/top/end/bottom）。 */
private const val PADDING_SIDES_METHOD = "padding-qDBjuR0"
private const val PADDING_SIDES_PARAMETER_COUNT = 5

/** `size-3ABfNKs(Modifier, float)`：正方形边长。 */
private const val SIZE_METHOD = "size-3ABfNKs"

/**
 * `SizeKt.wrapContentHeight(Modifier, Alignment$Vertical, Z)`：在**已被固定高度**的约束里
 * 放松 min 约束、让内容按自身高度测量后再按 `align` 摆放。
 * 与 `height-3ABfNKs` 配合即「节点固定 56dp 高，内容竖直居中」。用途见 [textNodeCellHeight]。
 */
private const val WRAP_CONTENT_HEIGHT_METHOD = "wrapContentHeight"
private const val WRAP_CONTENT_HEIGHT_PARAMETER_COUNT = 3

/** `fillMaxWidth$default(Modifier, F, I, Object)`，mask = 1 → `fraction` 取默认的 1f。 */
private const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
private const val FILL_MAX_WIDTH_DEFAULT_PARAMETER_COUNT = 4
private const val FILL_MAX_WIDTH_DEFAULT_MASK = 1
