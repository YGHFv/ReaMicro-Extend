package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.hook.settings.CLICKABLE_KT_CLASS
import com.reamicro.fix.hook.settings.COMPOSER_CLASS
import com.reamicro.fix.hook.settings.LOG_PREFIX
import com.reamicro.fix.hook.settings.PADDING_KT_CLASS
import com.reamicro.fix.hook.settings.SPACER_KT_CLASS
import com.reamicro.fix.hook.settings.SPACER_METHOD
import com.reamicro.fix.hook.settings.TEXT_KT_CLASS
import com.reamicro.fix.hook.settings.TEXT_METHOD
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 「我的」页社区卡片的改造：把「书院」通栏按钮变成「书院 ｜ 发现」两个等宽按钮。
 *
 * ## 宿主原样（MT 反编译 CommunityKt.Community 确认）
 *
 * ```
 * Community.kt:66   Row(Modifier.clickable {…}.padding(16.dp), Arrangement.Start, CenterVertically) {
 * Community.kt:70       Image(getBook, Modifier.padding(end = 12.dp).size(24.dp))
 * Community.kt:75       Text(stringResource(getCommunity))      ← 文案「书院」
 * Community.kt:79       Spacer(Modifier.weight(1f))             ← 把箭头推向右侧
 *                       Icon(getNavigateNext, modifier = null, tint = ColorScheme.surfaceContainerHighest)
 *                   }
 * Community.kt:86   SimpleDivider(Modifier.padding(start = 52.dp))
 * Community.kt:88   Row(Modifier.fillMaxWidth(), …) {           ← 下一行，本次要复刻的目标样式
 * Community.kt:92-94   同好格(Modifier.weight(1f).clickable(…).padding(16, 16, 12, 16)) { 图标 文案 Spacer(w) 箭头 }
 * Community.kt:115-116 Box(Modifier.width(0.8.dp).height(56.dp).background(borderVariant))
 * Community.kt:119-121 野社格(Modifier.weight(1f).clickable(…).padding(12, 16, 16, 16)) { 图标 文案 Spacer(w) 箭头 }
 *                   }
 * ```
 *
 * （行号按当前宿主 `CommunityKt.Community` 的 `.line` 标注；同文件里 `笔记` 那行是同样的通栏形状，
 * 也是 `padding-3ABfNKs(16dp)`，因此注入侧的识别状态机必须只在「书院」那一轮生效。）
 *
 * 那个 `weight(1f)` 的 Spacer 让「图标 + 文案」贴左、右箭头贴右，于是整行看起来是一个
 * 通栏按钮。第 4 行「同好 ｜ 野社」用的是两个等权重子块加一条竖中缝，所以视觉上并排。
 * 改造后的第 3 行与它逐节点同构：
 *
 * ```
 * [书图标][书院][Spacer(w1f)][箭头₁] |中缝| [发现图标][发现][Spacer(w1f)][箭头₂]
 * ```
 *
 * 其中箭头₁ / 中缝 / 发现半区由 [renderDiscoverButton] 就地发射，箭头₂ 是宿主原本那个箭头
 * 被换上 clickable（见 [ICON_ARG_MODIFIER]）。
 *
 * ## 为什么不用 `RowKt.Row` 作为锚点
 *
 * Compose 编译器会把 inline 的 `Row { }` **展平**：宿主字节码里没有 `RowKt.Row` 调用，
 * 只有手写的 `rowMeasurePolicy` + `startReusableNode` + `Updater.set-impl`
 * （见 `Community` 的 smali `.line 606-630`、`.line 648-672`）。
 * 因此可用的锚点只有编译器**不会**内联的那几个静态调用：
 *
 * - `String0_commonMainKt.getCommunity(Res$string)` —— 全 APK 只被书院按钮调用一次；
 * - `RowScope.weight$default(RowScope, Modifier, F, Z, I, Object)` —— 书院行撑开 Spacer；
 * - `IconKt.Icon-ww6aTOc(ImageVector, String, Modifier, J, Composer, I, I)` —— 右箭头。
 *
 * 这三个调用在书院行内**按固定顺序相邻出现**，顺序不随宿主重编译改变，比行号或常量池
 * 索引更抗升级，也不受「同好 / 野社」两行共用同一段 Composer 代码的干扰。
 *
 * ## 注入点为什么落在 `Icon-ww6aTOc`
 *
 * 行内节点无法事后改写，只能在某一次 Compose 调用之前抢先发射自己的节点。三个候选里：
 *
 * - `getCommunity` 只是取资源、**没有 Composer 形参**，无法发射节点；
 * - `weight$default` 同样**没有 Composer 形参**；
 * - `Icon-ww6aTOc` 既有 Composer，又正好位于「Spacer 之后、行结束之前」，
 *   在这里发射的节点顺序天然是「…文案、Spacer、箭头₁、中缝、发现半区、箭头₂」，
 *   宿主那个箭头正好落在整行最右，成为发现半区的收尾箭头。
 */
internal class DiscoverCardHook(private val classLoader: ClassLoader) {

    /** 当前是否在 `Community` 方法体内。用计数支持重组时的嵌套。 */
    private val communityDepth = ThreadLocal.withInitial { 0 }

    /** 本轮构建里是否已见到「书院」文案（`getCommunity` 被调用）。 */
    private val communityTextSeen = ThreadLocal.withInitial { false }

    /** 书院行内 `weight$default` 是否已出现，即行内容已铺开。 */
    private val communityRowOpened = ThreadLocal.withInitial { false }

    /**
     * 书院所在那个 `Row` 的 scope。
     *
     * 书院格/发现格的撑开 Spacer 都是 `RowScope.weight(1f)` 的结果（宿主 `Community` smali
     * `.line 195 / 92 / 119`），在这次调用里捕获它的 receiver，就是当前行的 RowScope。
     * 注入格内容时必须用这一份，`weight(1f)` 才会和「书院」「发现」在同一行里分宽；
     * 自己重新构造一个 RowScope 拿不到行上下文。
     */
    private val communityRowScope = ThreadLocal<Any?>()

    /** 本轮书院行内是否完成注入，避免重组时重复插入。 */
    private val communityInjected = ThreadLocal.withInitial { false }

    // ── 行 → 两格：摘除行级 clickable / 16dp padding 时的交接状态 ──────────────

    /** 书院行行级 `clickable` 的底座 Modifier（`Modifier.Companion`）：用来认出紧随其后的 `padding-3ABfNKs`。 */
    private val communityRowBase = ThreadLocal<Any?>()

    /** 书院行原本的 `onClick`（宿主导航到书院页那次）：摘掉行级 clickable 后交给书院格复用。 */
    private val communityRowClick = ThreadLocal<Any?>()

    /** 已摘掉行级 clickable，正等这一次 `padding-3ABfNKs(16dp)` 把它换成 `fillMaxWidth()`。 */
    private val communityRowStripped = ThreadLocal.withInitial { false }

    /** 已见到 `BookKt.getBook`：下一次 `Image` 就是书院行的书图标，该补本格 modifier。 */
    private val communityBookSeen = ThreadLocal.withInitial { false }

    /** 书院行的「书院」文字已补过本格 modifier（`Text-Nvy7gAk` 每轮只认第一次）。 */
    private val communityLabelDecorated = ThreadLocal.withInitial { false }

    /**
     * 已把书院行的 16dp padding 换成 `fillMaxWidth()`，正等这一行紧接着出现的那个 `Text`。
     *
     * 宿主书院行的节点顺序是「行 modifier（clickable + padding）→ 书图标 Image → 文案 Text」，
     * 所以「行 padding 之后第一个 `Text`」就是「书院」文案——这个位置锚点不依赖任何字符串常量
     * （「书院」是本地化资源，写死中文会在切换语言后失效），也不受 `getCommunity` 是否被
     * Compose 跳过影响（实测靠 `communityTextSeen` 一旦漏掉一轮，文字就退回没有 clickable 的状态，
     * 表现为「点『书院』两个字没反应、却会穿透到整行」）。
     */
    private val communityLabelPending = ThreadLocal.withInitial { false }

    /** 书院行撑开 Spacer 的 `weight(1f)` 结果：用来在 `SpacerKt.Spacer` 里按身份认出它。 */
    private val communitySpacerWeight = ThreadLocal<Any?>()

    fun install() {
        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "communityLifecycle" to ::hookCommunityLifecycle,
                "communityText" to ::hookCommunityText,
                "rowScopeWeight" to ::hookRowScopeWeight,
                "rowTrailingIcon" to ::hookRowTrailingIcon,
                "rowClickable" to ::hookRowClickable,
                "rowPadding" to ::hookRowPadding,
                "rowBookIcon" to ::hookRowBookIcon,
                "rowIconImage" to ::hookRowIconImage,
                "rowLabelText" to ::hookRowLabelText,
                "rowSpacer" to ::hookRowSpacer,
            ),
        )
    }


    // ── 1. Community 方法进出，圈定识别窗口 ────────────────────────────────────

    private fun hookCommunityLifecycle() {
        val target = findCommunityMethod()
            ?: error("$HOST_COMMUNITY.$COMMUNITY_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val depth = (communityDepth.get() ?: 0) + 1
                    communityDepth.set(depth)
                    if (depth == 1) {
                        // 每轮重建都从干净状态开始，避免上次的残留让本轮漏注入。
                        communityTextSeen.set(false)
                        communityRowOpened.set(false)
                        communityInjected.set(false)
                        communityRowScope.remove()
                        communityRowBase.remove()
                        communityRowClick.remove()
                        communityRowStripped.set(false)
                        communityBookSeen.set(false)
                        communityLabelDecorated.set(false)
                        communityLabelPending.set(false)
                        communitySpacerWeight.remove()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val depth = (communityDepth.get() ?: 1) - 1
                    if (depth <= 0) {
                        communityDepth.remove()
                        communityTextSeen.remove()
                        communityRowOpened.remove()
                        communityInjected.remove()
                        communityRowScope.remove()
                        communityRowBase.remove()
                        communityRowClick.remove()
                        communityRowStripped.remove()
                        communityBookSeen.remove()
                        communityLabelDecorated.remove()
                        communityLabelPending.remove()
                        communitySpacerWeight.remove()
                    } else {
                        communityDepth.set(depth)
                    }
                }
            },
        )
    }

    private fun findCommunityMethod(): Method? {
        val clazz = XposedHelpers.findClass(HOST_COMMUNITY, classLoader)
        // Community(NavGraphScope;IZComposer;II)V —— 按名字取参数最多的那个，
        // 避免同文件里的 lambda 辅助方法同名干扰。
        return clazz.declaredMethods
            .filter { it.name == COMMUNITY_METHOD }
            .maxByOrNull { it.parameterTypes.size }
    }

    // ── 2. 标记「书院」文案即将渲染 ───────────────────────────────────────────

    /**
     * `String0_commonMainKt.getCommunity(Res$string)` 是静态单参方法，全 APK 只被书院按钮
     * 调用一次（已用 MT 全量检索确认）。命中它说明接下来这批 Compose 调用属于书院行。
     */
    private fun hookCommunityText() {
        val clazz = XposedHelpers.findClass(STRING0_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == GET_COMMUNITY_METHOD && it.parameterTypes.size == 1
        } ?: error("$STRING0_CLASS.$GET_COMMUNITY_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inCommunity()) {
                        communityTextSeen.set(true)
                    }
                }
            },
        )
    }

    // ── 3. 确认书院行的内容已铺开（撑开 Spacer 的 weight 调用） ──────────────────

    /**
     * 书院行的 `Spacer(weight(1f))` 是一次 `RowScope.weight$default`。它出现在
     * `getCommunity` 之后、右箭头之前，用来确认「书院行确实走到了排版阶段」，
     * 同时它的 receiver 正是书院行那个 `Row` 的 scope——顺手捕获下来给注入用。
     *
     * 返回值（那个 `weight(1f)` Modifier）也一并记下：`SpacerKt.Spacer` 的 modifier 形参
     * 就是它，按**身份**比对即可精确认出书院行那个 Spacer（见 [hookRowSpacer]），
     * 不必依赖调用顺序。
     *
     * 书院行里还会命中本模块自己注入的发现格 Spacer（同一次注入内），因此只取第一次
     * （宿主那个在前），并用 `communityInjected` 把注入之后的调用挡掉。
     */
    private fun hookRowScopeWeight() {
        val clazz = XposedHelpers.findClass(ROW_SCOPE_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == WEIGHT_DEFAULT_METHOD && it.parameterTypes.size == WEIGHT_PARAMETER_COUNT
        } ?: error("$ROW_SCOPE_CLASS.$WEIGHT_DEFAULT_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityTextSeen.get() != true) return
                    if (communityInjected.get() == true) return
                    val scope = param.args?.getOrNull(0) ?: return
                    if (communityRowScope.get() == null) {
                        communityRowScope.set(scope)
                    }
                    if (communityRowOpened.get() != true) {
                        communityRowOpened.set(true)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityTextSeen.get() != true) return
                    if (communityInjected.get() == true) return
                    if (communitySpacerWeight.get() != null) return
                    param.result?.let { communitySpacerWeight.set(it) }
                }
            },
        )
    }

    // ── 4. 在右箭头之前插入「发现」按钮 ──────────────────────────────────────

    /**
     * `Icon-ww6aTOc(ImageVector, String, Modifier, long, Composer, int, int)`。
     *
     * 书院行的右箭头正是这次调用。在它的 `beforeHookedMethod` 里先渲染我们的按钮，节点
     * 顺序就是「…文案、Spacer、发现、右箭头」，右箭头仍留在最右。
     *
     * **必须用首参类型区分重载**：`IconKt` 里同名同参数量的 `Icon-ww6aTOc` 有三个，
     * 首参分别是 `ImageBitmap` / `Painter` / `ImageVector`。`declaredMethods` 的返回顺序
     * 不做保证，只按「名字 + 7 个参数」筛选会随机命中另外两个，从而完全收不到书院的箭头
     * （实测表现就是探针里 `injected=false`、界面毫无变化）。
     */
    private fun hookRowTrailingIcon() {
        val clazz = XposedHelpers.findClass(ICON_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == ICON_METHOD &&
                it.parameterTypes.size == ICON_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        } ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityInjected.get() == true) return
                    if (communityRowOpened.get() != true) return
                    val rowScope = communityRowScope.get() ?: return
                    val composer = param.args?.getOrNull(ICON_ARG_COMPOSER) ?: return
                    communityInjected.set(true)

                    // 先在宿主这个箭头之前铺出「书院格收尾箭头 ｜ 中缝 ｜ 发现格四个节点」，
                    // 再把宿主这个箭头取消——书院格/发现格各自收尾箭头的 modifier 都由我们重建。
                    renderDiscoverButton(
                        classLoader = classLoader,
                        rowScope = rowScope,
                        composer = composer,
                        onHostRowClick = hostRowClickInvoker(),
                        onDiscoverClick = ::openDiscoverPage,
                    )

                    // ★ 整只取消宿主这一次 Icon 调用，由我们重画箭头（见 renderTrailingArrowWithClick）。
                    //
                    // 起初的写法是只改它的 modifier 实参，但实测在本机不传导：`param.args[2]` 确实被
                    // 写成了我们的 CombinedModifier（探针日志为证），箭头却既不染色也不变大，
                    // 结果是「发现」格尾部并排出现两个箭头、右半格变宽、中缝跟着左移。
                    //
                    // 取消整个调用不会破坏组平衡：Icon 是自包含的 @Composable，它内部的
                    // startRestartGroup/endRestartGroup 随着这次调用一起消失，成对地消失。
                    param.result = null
                }
            },
        )
    }

    // ── 5. 书院行 → 两个格子：拆掉行级 clickable / 16dp padding ────────────────

    /**
     * `ClickableKt.clickable-oSLSa3U$default(Modifier, Z, String, Role, MutableInteractionSource,
     * Function0 onClick, I, Object)` —— 宿主社区卡片每一处 `Modifier.clickable{…}` 走的都是它。
     *
     * 书院行原本写的是 `Row(Modifier.clickable{…}.padding(16dp))`：**整行一个 clickable**，
     * 于是按哪都亮整行（用户实测的「书院点击还是一整行」）。这里把这一次 clickable 摘掉，
     * 由左右两个格子各自的 clickable 接手：
     *
     * - 摘掉方式：`param.result = param.args[0]`（把底座 Modifier 原样返回），宿主那次
     *   clickable 调用被整体跳过，行 modifier 只剩后面的 padding —— 紧接着会在 [hookRowPadding]
     *   里一起换掉；
     * - 宿主的 `onClick`（导航到书院页）先存下来，交给书院格复用（见 [hostRowClickInvoker]），
     *   于是「点书院区域进书院页」的行为一字不变。
     *
     * ## 怎么确认「这一次就是书院行」
     *
     * 按 onClick 的 lambda 类名认：宿主的五行按钮各自持有自己的 `remember` 出来的导航 lambda，
     * 类名按**源码行序**编号。实机 logcat 逐行打印 clickable 的 `args[5]` 得到的确切对照是：
     *
     * | lambda | 行 |
     * | --- | --- |
     * | `…Lambda0` | 里程碑 |
     * | `…Lambda1` | 笔记 |
     * | `…Lambda2` | 同步 |
     * | **`…Lambda3`** | **书院 —— 本 hook 的目标** |
     * | `…Lambda4` | 同好 |
     * | `…Lambda5` | 野社 |
     * | `…Lambda6` | 互联 |
     * | `…Lambda7` | 设置 |
     *
     * 早先这里写的是 `Lambda1`，是读 MT 反编译时把 `.line` 注解当成了执行序、误认「笔记」行为
     * 「书院」所致——后果是摘掉的是笔记行的 clickable，而书院行的行级 clickable 毫发无损，
     * 症状与没改一样（按哪都亮整行）。
     *
     * 这正是「读宿主代码」得到的锚点，不依赖调用顺序，也不受部分重组影响；万一宿主升级后类名
     * 变了，这里不命中、行级 clickable 保持原样，只是退回改动前的观感，不会有半成品状态。
     */
    private fun hookRowClickable() {
        val clazz = XposedHelpers.findClass(CLICKABLE_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == HOST_CLICKABLE_DEFAULT_METHOD &&
                it.parameterTypes.size == HOST_CLICKABLE_PARAMETER_COUNT
        } ?: error("$CLICKABLE_KT_CLASS.$HOST_CLICKABLE_DEFAULT_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityRowStripped.get() == true) return
                    runCatching {
                        val args = param.args ?: return@runCatching
                        val onClick = args.getOrNull(HOST_CLICKABLE_ARG_ON_CLICK) ?: return@runCatching
                        if (onClick.javaClass.name != HOST_COMMUNITY_ROW_CLICK) return@runCatching
                        communityRowClick.set(onClick)
                        communityRowBase.set(args.getOrNull(0))
                        communityRowStripped.set(true)
                        // 摘掉行级 clickable：底座 Modifier 原样返回，这次调用被整体跳过。
                        param.result = args[0]
                    }.onFailure { probeFailed("rowClickable", it) }
                }
            },
        )
    }

    /**
     * `PaddingKt.padding-3ABfNKs(Modifier, F)` —— 四面同值的内边距，书院行那次是 `padding(16dp)`。
     *
     * 摘掉行级 clickable 之后，这一行的 padding 收在**行外部**：它在 clickable 里、在行内容之外，
     * 于是格子再怎么补自己的 16dp 也没法把高亮画到那 32dp 上（Compose 的 feedback 只能落在节点自己的
     * 坐标空间里）。宿主第 2 行的做法是**行自己完全没有 padding**，16dp 全在格子里
     * （`Row(Modifier.fillMaxWidth())` + 格子 `padding(16,16,12,16)`），这里照抄：
     * 把 `padding(16dp)` 换成 `Modifier.fillMaxWidth()`。
     *
     * 识别方式**基于对象身份**：上一步摘 clickable 时返回的那个底座 Modifier（`Modifier.Companion`）
     * 正是这次 padding 的接收者，`!==` 即不处理；再加 16dp 值校验与「每轮只认一次」的开关。
     * 全 APK 的 `padding-3ABfNKs` 处处都在调，所以进来先过 `inCommunity()` 这道廉价闸。
     */
    private fun hookRowPadding() {
        val clazz = XposedHelpers.findClass(PADDING_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == PADDING_ALL_METHOD && it.parameterTypes.size == PADDING_ALL_PARAMETER_COUNT
        } ?: error("$PADDING_KT_CLASS.$PADDING_ALL_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityRowStripped.get() != true) return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    runCatching {
                        val args = param.args ?: return@runCatching
                        if (args.getOrNull(0) !== communityRowBase.get()) return@runCatching
                        val value = args.getOrNull(1) as? Float ?: return@runCatching
                        if (value != hook.udp(ROW_PADDING_DP)) return@runCatching
                        communityRowStripped.set(false)
                        // 行 modifier 已铺完，行内容马上开始——下一个 `Text` 就是「书院」文案。
                        communityLabelPending.set(true)
                        param.result = hook.fillMaxWidthModifier(args[0])
                    }.onFailure { probeFailed("rowPadding", it) }
                }
            },
        )
    }

    /**
     * `BookKt.getBook(AppIcons$Colored)` —— 书院行的书图标。
     *
     * 整行只有这一处会取这本书的 `ImageVector`，所以它出现就说明「接下来那次 `Image` 是书院行的图标」。
     * 当个一次性旗标用（见 [hookRowIconImage]），比按行号或调用顺序认更抗宿主升级。
     */
    private fun hookRowBookIcon() {
        val clazz = XposedHelpers.findClass(BOOK_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == BOOK_METHOD && it.parameterTypes.size == 1
        } ?: error("$BOOK_KT_CLASS.$BOOK_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    communityBookSeen.set(true)
                }
            },
        )
    }

    /**
     * `ImageKt.Image(ImageVector, String, Modifier, Alignment, ContentScale, F, ColorFilter, Composer, I, I)`
     * —— 书院行的书图标就是这一次调用。
     *
     * 把它的 modifier 换成 [ReaMicroSettingsHook.leftCellIconModifier]：本格 clickable +
     * `padding(16,16,0,16)` + 宿主那 12dp 图标间距 + `size(24dp)`。尺寸与位置逐项等价，
     * 多出来的是「整格可点、整格高亮」。
     *
     * 为什么整体替换而不是拼接宿主原 modifier：宿主写的是
     * `Modifier.padding(end = 12.dp).size(24.dp)`，两个值都是 smali 里的常量
     * （`.line 72`），重建比拼接少一次 `Modifier.then` 反射。
     */
    private fun hookRowIconImage() {
        val clazz = XposedHelpers.findClass(IMAGE_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == IMAGE_METHOD &&
                it.parameterTypes.size == IMAGE_PARAMETER_COUNT &&
                it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
        } ?: error("$IMAGE_KT_CLASS.$IMAGE_METHOD ImageVector overload not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityBookSeen.get() != true) return
                    if (communityInjected.get() == true) return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    val args = param.args ?: return
                    val composer = composerOf(args) ?: return
                    communityBookSeen.set(false)
                    runCatching {
                        args[IMAGE_ARG_MODIFIER] = hook.leftCellIconModifier(composer, hostRowClickInvoker())
                        // 宿主当前显式传了 modifier（`padding(end=12).size(24)`），掩码 bit2 本来是 0，
                        // 这行是空操作；加上是为了防宿主哪天不再显式传时我们的改写被静默忽略。
                        clearDefaultBit(args, IMAGE_ARG_DEFAULT_MASK, IMAGE_MODIFIER_DEFAULT_BIT)
                    }.onFailure { probeFailed("rowIconImage", it) }
                }
            },
        )
    }

    /**
     * `TextKt.Text-Nvy7gAk(String text, Modifier modifier, …)` —— 书院行的「书院」文案。
     *
     * 宿主这次调用的 `modifier` 形参传的是 **null**（smali 实测），所以直接把本格 modifier
     * （`clickable` + `padding(0,16,0,16)`）写进 `args[1]` 即可。
     *
     * ## 光改 `args[1]` 不够：还要清掉 `$default` 掩码的 bit1
     *
     * 这是本 hook 曾经静默失效的真正原因。Compose 编译器给每个 @Composable 的**最后一个 `int` 形参**
     * 塞的是 `$default` 掩码：**bit k 置位 = 第 k 个值形参取默认值，调用方传的值被实现体忽略**。
     * 宿主源码是 `Text("书院")`，没写 modifier，于是掩码里 **bit1**（modifier 是第 1 个值形参）是置位的
     * —— 这种情况下无论我们把 `args[1]` 改成什么（甚至「取消这次调用 + 用改好的实参重发一遍」，两条路
     * 都试过），实现体都会用默认的 `Modifier`，节点上既没有 clickable、也没有那 32dp 内边距
     * （实测无障碍树里该 TextView 恒为 `clickable=false`、高 62px；而同一批注入的「发现」文字是
     * 158px + `clickable=true`，差别就在掩码）。
     *
     * 所以这里除了写 `args[1]`，还把掩码的 bit1 清掉（`mask and 2.inv()`），让实参真正生效。
     *
     * 同一类坑在本文件里出现过两次，位号按形参下标算：
     *
     * | 调用 | modifier 形参下标 | 需要清掉的位 | 现状 |
     * | --- | --- | --- | --- |
     * | `Icon-ww6aTOc`（行尾箭头） | 2 | bit2 = `0x4` | 已在 [renderCellArrow] 用 mask `0x0` 规避 |
     * | `Image`（书图标） | 2 | bit2 = `0x4` | 宿主本来就传真实 modifier，掩码位是 0；[hookRowIconImage] 仍显式清一遍做防护 |
     * | `Text-Nvy7gAk`（书院文案） | 1 | **bit1 = `0x2`** | 掩码位一直是 1，本 hook 处理 |
     * | `SpacerKt.Spacer`（3 参） | 0 | —— | 该重载**没有默认形参**，最后一个 `int` 是 `$changed` 而非 `$default`，绝不能动 |
     *
     * ## 认哪一次 `Text`
     *
     * 用 [communityLabelPending]——由 [hookRowPadding] 在换掉行 padding 时置位，
     * 于是「行 modifier 之后第一个 `Text`」就是它。比依赖 `getCommunity` 是否被调用更稳
     * （实测靠 `communityTextSeen` 一旦漏掉一轮，文案就退回没有 clickable 的状态），
     * 也不会被同好/野社/互联/设置的文案干扰。
     */
    private fun hookRowLabelText() {
        val clazz = XposedHelpers.findClass(TEXT_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == TEXT_METHOD && it.parameterTypes.size == TEXT_PARAMETER_COUNT
        } ?: error("$TEXT_KT_CLASS.$TEXT_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityLabelDecorated.get() == true) return
                    if (communityLabelPending.get() != true) return
                    val args = param.args ?: return
                    if (args.getOrNull(TEXT_ARG_MODIFIER) != null) return
                    val composer = composerOf(args) ?: return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    communityLabelDecorated.set(true)
                    communityLabelPending.set(false)
                    runCatching {
                        args[TEXT_ARG_MODIFIER] =
                            hook.leftCellLabelModifier(composer, hostRowClickInvoker())
                        // 清 bit1：否则实现体忽略上面这个 modifier 实参（见函数注释）。
                        clearDefaultBit(args, TEXT_ARG_DEFAULT_MASK, TEXT_MODIFIER_DEFAULT_BIT)
                    }.onFailure { probeFailed("rowLabelText", it) }
                }
            },
        )
    }

    /**
     * `SpacerKt.Spacer(Modifier, Composer, I)` —— 书院行那个撑开用 `Spacer(Modifier.weight(1f))`。
     *
     * 靠**身份**认它：`args[0]` 必须正是 [hookRowScopeWeight] 记下的那个 `weight(1f)` 结果。
     * 换成 [ReaMicroSettingsHook.leftCellSpacerModifier] 后，这片留白也成了整格的
     * 「可点 + 高亮」带（原本 `Spacer` 测量恒为 0×0，既点不到也画不出反馈）。
     */
    private fun hookRowSpacer() {
        val clazz = XposedHelpers.findClass(SPACER_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.name == SPACER_METHOD && it.parameterTypes.size == SPACER_PARAMETER_COUNT
        } ?: error("$SPACER_KT_CLASS.$SPACER_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityInjected.get() == true) return
                    val weighted = communitySpacerWeight.get() ?: return
                    val args = param.args ?: return
                    if (args.getOrNull(SPACER_ARG_MODIFIER) !== weighted) return
                    val rowScope = communityRowScope.get() ?: return
                    val composer = composerOf(args) ?: return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    communitySpacerWeight.set(null)
                    runCatching {
                        args[SPACER_ARG_MODIFIER] =
                            hook.leftCellSpacerModifier(rowScope, composer, hostRowClickInvoker())
                    }.onFailure { probeFailed("rowSpacer", it) }
                }
            },
        )
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    /**
     * 书院格的点击 = 宿主行原本的 `onClick`（导航到书院页）。
     *
     * 行级 clickable 已被摘掉，书院格四个节点都挂这一个回调，行为与原状一致。
     * 调的是 lambda 自己的 `invoke()`（`kotlin.jvm.functions.Function0`），失败只记日志。
     *
     * ## 必须「按值捕获」，不能留到调用时再读 [communityRowClick]
     *
     * 这里踩过一次静默失效，症状是「按住书院格整格亮、松手什么都不发生」：indication 来自
     * clickable 自身，照常显示，于是看起来只是「点了没反应」。
     *
     * 根因是时序——[communityRowClick] 是 ThreadLocal，[hookCommunityLifecycle] 在
     * `Community()` **退出时**（`afterHookedMethod`）就把它 `remove()` 了；而我们交给 Compose
     * 的是一个 `() -> Unit`，它在**用户点击的那一刻**（几秒之后、早已过了合成期）才执行。
     * 原先写法在闭包里 `communityRowClick.get()`，那时读到的恒为 `null`，于是 `if` 直接落空、
     * 一声不响地什么都不做。
     *
     * 所以改成在**本函数被调用的当下**（仍在 `Community()` 执行期内）把宿主 lambda 取出来，
     * 由闭包按值持有。
     */
    private fun hostRowClickInvoker(): () -> Unit {
        val click: Any? = communityRowClick.get()
        return {
            if (click != null) {
                runCatching { click.javaClass.getMethod(INVOKE_METHOD).invoke(click) }
                    .onFailure { probeFailed("hostRowClick", it) }
            } else {
                probeFailed("hostRowClick", IllegalStateException("host row onClick lambda not captured"))
            }
        }
    }

    /**
     * 从 Compose 调用的实参里挑出 `Composer`。
     *
     * 各 @Composable 的 Composer 形参位置都不一样（`Image` 第 8、`Text-Nvy7gAk` 第 18、
     * `Spacer` 第 1），按类型找比硬编码下标稳。拿不到就放弃这次改写（保持宿主原样）。
     */
    private fun composerOf(args: Array<Any?>): Any? {
        val cls = composerClass ?: return null
        return args.firstOrNull { it != null && cls.isInstance(it) }
    }

    private val composerClass: Class<*>? by lazy {
        runCatching { XposedHelpers.findClass(COMPOSER_CLASS, classLoader) }.getOrNull()
    }

    /** 渲染期异常只记日志：卡片会被反复重组，异常必须留在我们这一层。 */
    private fun probeFailed(where: String, error: Throwable) {
        XposedBridge.logAlways("$LOG_PREFIX discover $where failed: ${error.message}")
    }

    /**
     * 清掉 Compose `$default` 掩码里「某个值形参取默认值」那一位。
     *
     * 掩码置位时实现体会**忽略调用方传进来的那个实参**，于是我们改写 `args[...]` 完全不生效，
     * 而且是静默的（不抛异常、日志干净、界面只是少了个 clickable）。详见 [hookRowLabelText]。
     *
     * 只适用于**有默认形参**的 @Composable（它们的最后一个 `int` 才是 `$default` 掩码）；
     * 没有默认形参的（如 3 参 `SpacerKt.Spacer`）最后一个 `int` 是 `$changed`，绝不能动 —— 那一位
     * 描述的是「实参有没有变」，清掉会让 Compose 以为 modifier 没变而跳过更新。
     *
     * @param args 被 hook 方法实参数组（`param.args`）
     * @param maskIndex `$default` 掩码在 `param.args` 里的下标（即最后一个形参）
     * @param bit 目标值形参对应的位（第 k 个值形参 → `1 shl k`）
     */
    private fun clearDefaultBit(args: Array<Any?>?, maskIndex: Int, bit: Int) {
        val mask = args?.getOrNull(maskIndex) as? Int ?: return
        args[maskIndex] = mask and bit.inv()
    }

    private fun inCommunity(): Boolean = (communityDepth.get() ?: 0) > 0

    /**
     * 打开「发现」页。
     *
     * 走 [ReaMicroSettingsHook.Companion.openDiscoverPage]：它把 `Route.About` 推上宿主
     * 导航栈，用宿主的 NavHost 页面框架承载我们的内容，返回栈由既有机制接管。
     * 卡片渲染发生在宿主 Compose 里，拿不到 hook 实例，所以只能走这个静态入口。
     */
    private fun openDiscoverPage() {
        if (!ReaMicroSettingsHook.openDiscoverPage()) {
            XposedBridge.log("$LOG_PREFIX discover page unavailable (host navigation not captured yet)")
        }
    }

    private companion object {
        const val FEATURE_ID = "DiscoverCardHook"

        const val HOST_COMMUNITY = "app.zhendong.reamicro.ui.profile.components.CommunityKt"
        const val COMMUNITY_METHOD = "Community"
        const val STRING0_CLASS = "reamicro.composeapp.generated.resources.String0_commonMainKt"
        const val GET_COMMUNITY_METHOD = "getCommunity"
        const val ROW_SCOPE_CLASS = "androidx.compose.foundation.layout.RowScope"
        const val WEIGHT_DEFAULT_METHOD = "weight\$default"
        const val WEIGHT_PARAMETER_COUNT = 6
        const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
        const val ICON_METHOD = "Icon-ww6aTOc"
        const val ICON_PARAMETER_COUNT = 7

        /** `Icon-ww6aTOc` 三个 7 参重载的首参类型，用它区分 ImageVector 版本。 */
        const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"

        /** `ImageKt.Image` 与 `TextKt.Text-Nvy7gAk` 的宿主入口。 */
        const val IMAGE_KT_CLASS = "androidx.compose.foundation.ImageKt"
        const val IMAGE_METHOD = "Image"

        /** `Image` 三个重载（ImageBitmap / Painter / ImageVector）参数个数都是 10。 */
        const val IMAGE_PARAMETER_COUNT = 10
        const val TEXT_PARAMETER_COUNT = 22

        /** `SpacerKt.Spacer(Modifier, Composer, int)`。 */
        const val SPACER_PARAMETER_COUNT = 3

        /** `Icon-ww6aTOc` 的 `Composer` 在第 5 个形参（下标 4）。 */
        const val ICON_ARG_COMPOSER = 4

        /**
         * `Icon-ww6aTOc` 的 `Modifier` 在第 3 个形参（下标 2）。
         *
         * 宿主书院行的箭头传的是 `null`（smali `.line 183` 的 `const/4 v7, 0x0`）；
         * 我们把发现页的 clickable 换进去，宿主这个箭头就成了发现半区的箭头₂。
         * 改动落在 `beforeHookedMethod` 里、Icon 真正执行之前，因此不会触发重组不一致。
         */
        const val ICON_ARG_MODIFIER = 2

        // ── 把书院行拆成两个宿主格子的过程中用到的宿主锚点（全部由 smali 实证） ──────

        /**
         * 宿主社区卡片每处 `Modifier.clickable{…}` 走的那个重载。
         *
         * 与模块自己用的 `clickable-O2vRcR0$default`（带 indication）**不是**同一条：
         * 宿主这条只挂手势、indication 恒为 null（MT 反编译实现体为证）。
         */
        const val HOST_CLICKABLE_DEFAULT_METHOD = "clickable-oSLSa3U\$default"
        const val HOST_CLICKABLE_PARAMETER_COUNT = 8

        /** 上面这条重载里 `onClick: Function0` 是第 6 个形参（下标 5，扩展接收者占 0）。 */
        const val HOST_CLICKABLE_ARG_ON_CLICK = 5

        /**
         * 书院行那次 clickable 的 `onClick` lambda 类名。
         *
         * **宿主按源码行序给导航 lambda 编号**（实机 logcat 逐个打印 onClick 类名得到）：
         *
         * ```
         * Lambda0 = 里程碑    Lambda1 = 笔记      Lambda2 = 同步      Lambda3 = 书院
         * Lambda4 = 同好      Lambda5 = 野社      Lambda6 = 互联      Lambda7 = 设置
         * ```
         *
         * 与「书院」文案的相对位置也能对上：`Lambda3 → padding(16dp) → Text("书院")`。
         *
         * ## 曾经写错的地方（教训）
         *
         * 早先读 MT 反编译的 `CommunityKt` 片段时，看到 `new-instance CommunityKt$$ExternalSyntheticLambda1`
         * 紧挨着 `.line 66` 的注解，就认定那是书院行。实际上 `.line` 注解与执行顺序并不同序，
         * 那一段属于「笔记」行——于是常量写成 `Lambda1`，结果：
         *
         * - 「笔记」格的 clickable 被误摘（该格失去点击）；
         * - 「书院」行的行级 clickable 毫发无损，用户看到的仍是「按哪都亮整行」。
         *
         * 认错的代价是静默的，所以这里保留完整编号表作为对照；宿主升级后只要重新打印一遍
         * onClick 类名即可复核。
         */
        const val HOST_COMMUNITY_ROW_CLICK =
            "app.zhendong.reamicro.ui.profile.components.CommunityKt\$\$ExternalSyntheticLambda3"

        /** `PaddingKt.padding-3ABfNKs(Modifier, F)`：四面同值内边距，书院行那次是 16dp。 */
        const val PADDING_ALL_METHOD = "padding-3ABfNKs"
        const val PADDING_ALL_PARAMETER_COUNT = 2

        /** 书院行 `padding` 的值：宿主源码里的 `16.dp`（smali `const/16 v29, 0x10`）。 */
        const val ROW_PADDING_DP = 16

        /** 书图标的取图入口：`BookKt.getBook(AppIcons$Colored)`，整行只有书院行会调。 */
        const val BOOK_KT_CLASS = "app.zhendong.reamicro.arch.icons.colored.BookKt"
        const val BOOK_METHOD = "getBook"

        /** `ImageKt.Image` 的 `modifier` 在第 3 个形参（下标 2）。 */
        const val IMAGE_ARG_MODIFIER = 2

        /** `Image` 共 10 个形参，最后一个 `int`（下标 9）是 `$default` 掩码。 */
        const val IMAGE_ARG_DEFAULT_MASK = 9

        /** `Image` 的 `modifier` 是第 2 个值形参 → bit2 = `0x4`（同 `Icon-ww6aTOc`）。 */
        const val IMAGE_MODIFIER_DEFAULT_BIT = 0x4

        /** `TextKt.Text-Nvy7gAk(String, Modifier, …)` 的 `modifier` 在第 2 个形参（下标 1）。 */
        const val TEXT_ARG_MODIFIER = 1

        /** `Text-Nvy7gAk` 最后一个形参是 Compose 的 `$default` 掩码（22 个形参的下标 21）。 */
        const val TEXT_ARG_DEFAULT_MASK = 21

        /**
         * `$default` 掩码里「`modifier` 取默认值」那一位：`modifier` 是第 1 个值形参 → bit1 = `0x2`。
         *
         * 置位时实现体会忽略传入的 `modifier`；本 hook 必须把它清掉自己的改写才生效
         * （详见 [hookRowLabelText] 的注释）。
         */
        const val TEXT_MODIFIER_DEFAULT_BIT = 0x2

        /** `SpacerKt.Spacer(Modifier, Composer, I)` 的 `modifier` 是第 1 个形参（下标 0）。 */
        const val SPACER_ARG_MODIFIER = 0

        /** Kotlin 无参 lambda 的调用入口。 */
        const val INVOKE_METHOD = "invoke"
    }
}
