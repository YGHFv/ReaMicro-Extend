package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.settings.SettingsDialogButtonRole
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.online.download.OnlineBookDownloadMode
import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import com.reamicro.fix.hook.webdav.OnlineDownloadTarget
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.discover.DiscoverKind
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState

// 「发现」页的两个选择弹窗：切换书源、选择分类。
//
// ## 为什么是原生 Dialog 而不是宿主 Compose 弹窗
//
// 模块从不反射宿主的 Dialog / Popup / ModalBottomSheet——那套东西的 `@Composable` 入口
// 需要一整套 Composition 上下文，在 hook 里没有可靠的位置去挂；模块一贯的做法是
// 「原生 `android.app.Dialog` + 编程式 View 树」（见 `ReaMicroSettingsHook.Dialogs.kt`）。
//
// 因此这里复用同一族构件：`settingsDialogCard` / `settingsDialogTitle` / `settingsDialogInput` /
// `settingsDialogScroll` / `showSettingsDialog`，配色走 `SettingsDialogColors`——它由
// `updateModuleDialogTheme(composer)` 从宿主当前 ColorScheme 灌进来，所以弹窗跟随宿主主题
// （弹窗打开时注入页还在组合中，主题已经同步过一次）。
//
// ## 关闭与回写的分工
//
// 弹窗自己不持有状态：点中某一项就 `dismiss()`，然后把选择交给 `DiscoverState`；状态变化会
// `bump()` 版本号，注入页据此重组。所以这里不需要把 UI 状态回传给调用方。

/** 书源弹窗：标题 + 筛选框 + 可点列表，选中项带勾选标记。 */
internal fun ReaMicroSettingsHook.openDiscoverSourceDialog(activity: Activity) {
    val sources = DiscoverState.sources
    if (sources.isEmpty()) return
    val currentSourceId = DiscoverState.selection.sourceId
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(context, colors)
            card.addView(settingsDialogTitle(context, DISCOVER_SOURCE_DIALOG_TITLE, colors))

            val input = settingsDialogInput(
                context,
                DISCOVER_SOURCE_FILTER_HINT,
                singleLine = true,
                colors = colors,
            ).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }

            // 列表容器：筛选时整块重建。条目最多几十条，重建代价远低于维护 diff。
            val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            var previous = ""
            fun rebuild(keyword: String) {
                listHost.removeAllViews()
                val filtered = sources.filter { it.matchesSourceFilter(keyword) }
                if (filtered.isEmpty()) {
                    listHost.addView(
                        settingsDialogHint(context, DISCOVER_SOURCE_NO_MATCH, colors),
                    )
                    return
                }
                filtered.forEach { entry ->
                    val selected = entry.source.id == currentSourceId
                    listHost.addView(
                        discoverSourceDialogRow(context, entry, selected, colors) {
                            dialog.dismiss()
                            DiscoverState.selectSource(entry.source.id, activityProvider()?.applicationContext)
                        },
                    )
                }
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val keyword = s?.toString().orEmpty().trim()
                    if (keyword == previous) return
                    previous = keyword
                    rebuild(keyword)
                }
            })

            card.addView(input)
            card.addView(listHost)
            rebuild("")

            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f)
        }
    }
}

/**
 * 分类弹窗：标题 + 三列网格，选中项描边高亮。
 *
 * 与书源弹窗同构，区别只是排布：分类动辄几十个，列表要滚很久，三列网格一屏能看到大半，
 * 这也是参考图里的形态。
 */
internal fun ReaMicroSettingsHook.openDiscoverKindDialog(activity: Activity) {
    val selection = DiscoverState.selection
    val entry = DiscoverState.sources.firstOrNull { it.source.id == selection.sourceId } ?: return
    val kinds = entry.kinds
    if (kinds.isEmpty()) return
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(context, colors)
            card.addView(settingsDialogTitle(context, DISCOVER_KIND_DIALOG_TITLE, colors))

            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            kinds.chunked(DISCOVER_KIND_DIALOG_COLUMNS).forEach { rowKinds ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                rowKinds.forEach { kind ->
                    row.addView(
                        discoverKindDialogCell(context, kind, kind.title == selection.kindTitle, colors) {
                            dialog.dismiss()
                            DiscoverState.select(
                                entry.source.id,
                                kind.title,
                                activityProvider()?.applicationContext,
                            )
                        },
                    )
                }
                // 补齐末行的空位，否则最后一行的格子会因为 weight 摊分而比上面几行宽。
                repeat(DISCOVER_KIND_DIALOG_COLUMNS - rowKinds.size) {
                    row.addView(LinearLayout(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
                grid.addView(row)
            }

            card.addView(grid)
            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f)
        }
    }
}

/**
 * 书源条目。
 *
 * 文案按「勾选标记 + 源名 +（标识）」拼，与参考图一致：
 * 勾选标记只占位不参与语义；源名是主信息；括号里是对得上书源文件的一组标识
 * （是否需要登录、以及书写别名），用来区分同名源。
 */
private fun ReaMicroSettingsHook.discoverSourceDialogRow(
    context: Context,
    entry: DiscoverSource,
    selected: Boolean,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        text = buildString {
            append(if (selected) DISCOVER_SOURCE_CHECKED_MARK else DISCOVER_SOURCE_UNCHECKED_MARK)
            append(entry.name)
            val badge = entry.sourceBadge()
            if (badge.isNotBlank()) append("（$badge）")
        }
        textSize = 14f
        setTextColor(if (selected) colors.primaryText else colors.title)
        setSingleLine(false)
        setPadding(
            settingsDp(context, 12),
            settingsDp(context, 10),
            settingsDp(context, 12),
            settingsDp(context, 10),
        )
        background = settingsRoundedRect(
            if (selected) colors.primarySoft else colors.field,
            settingsDp(context, 8),
            if (selected) colors.primary else colors.border,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 8) }
    }

/** 分类格子：三等分宽，选中态换成描边 + 主色文字。 */
private fun ReaMicroSettingsHook.discoverKindDialogCell(
    context: Context,
    kind: DiscoverKind,
    selected: Boolean,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        text = kind.title
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (selected) colors.primaryText else colors.title)
        setPadding(
            settingsDp(context, 6),
            settingsDp(context, 12),
            settingsDp(context, 6),
            settingsDp(context, 12),
        )
        background = settingsRoundedRect(
            if (selected) colors.card else Color.TRANSPARENT,
            settingsDp(context, 8),
            if (selected) colors.primary else Color.TRANSPARENT,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = settingsDp(context, 5)
            marginEnd = settingsDp(context, 5)
            bottomMargin = settingsDp(context, 8)
        }
    }

/**
 * 筛选：匹配源名与标识。
 *
 * 只做大小写不敏感的子串匹配——书源列表通常十几个，用户输入的也多是名字里连续的一小段，
 * 上模糊匹配（分词、拼音）只会让结果更难预测。
 */
private fun DiscoverSource.matchesSourceFilter(keyword: String): Boolean {
    if (keyword.isBlank()) return true
    val needle = keyword.lowercase()
    return name.lowercase().contains(needle) ||
        source.id.lowercase().contains(needle) ||
        source.aliases.any { it.lowercase().contains(needle) }
}

/** 括号里的标识：`api` 说明这个源要走登录态拿数据，其余是**短**书写别名。 */
private fun DiscoverSource.sourceBadge(): String = buildList {
    if (source.hasLoginConfig) add(DISCOVER_SOURCE_BADGE_API)
    source.aliases.forEach { alias ->
        // 别名里混着 `online_<16 位哈希>` 这类内部标识，既长又对用户无意义，直接滤掉。
        val short = alias.trim().removePrefix(DISCOVER_SOURCE_ALIAS_PREFIX)
        if (short.isNotEmpty() && short.length <= DISCOVER_SOURCE_BADGE_MAX && short !in this) add(short)
    }
}.joinToString(",")

private const val DISCOVER_SOURCE_DIALOG_TITLE = "书源"

private const val DISCOVER_KIND_DIALOG_TITLE = "选择"

private const val DISCOVER_SOURCE_FILTER_HINT = "筛选发现源"

private const val DISCOVER_SOURCE_NO_MATCH = "没有匹配的书源"

private const val DISCOVER_SOURCE_CHECKED_MARK = "✓ "

/** 未选中时用等宽空白占位，让两行文案左边缘对齐。 */
private const val DISCOVER_SOURCE_UNCHECKED_MARK = "\u2003\u2003"

private const val DISCOVER_SOURCE_BADGE_API = "api"

/** 书写别名里 `online_` 是导入时写入的内部前缀，展示前剥掉。 */
private const val DISCOVER_SOURCE_ALIAS_PREFIX = "online_"

/** 别名超过这个长度就不展示（内部哈希 id 动辄 16 位）。 */
private const val DISCOVER_SOURCE_BADGE_MAX = 10

/** 分类弹窗的列数，与参考图一致。 */
private const val DISCOVER_KIND_DIALOG_COLUMNS = 3

/**
 * 下载确认弹窗：标题是书名，下面三颗按钮（整本下载 / 逐章加载 / 取消）。
 *
 * 为什么不直接按书源偏好静默开始下载：点下去什么反馈都没有（部分 ROM 上 Toast 还会被吞），
 * 用户看到的就是「点了没反应」。这里先给一次明确确认 + 模式选择，再把任务交给宿主既有的
 * 在线补全下载链路（限流、通知、任务记录都由那条链路负责）。
 */
internal fun ReaMicroSettingsHook.openDiscoverDownloadDialog(
    activity: Activity,
    source: DiscoverSource,
    book: DiscoverBook,
) {
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(context, colors)

            val fullButton = settingsDialogButton(context, "整本下载", colors, SettingsDialogButtonRole.Primary)
            val onDemandButton = settingsDialogButton(context, "逐章加载", colors, SettingsDialogButtonRole.Neutral)
            val cancelButton = settingsDialogButton(context, "取消", colors, SettingsDialogButtonRole.Neutral)

            fun startDownload(mode: OnlineBookDownloadMode) {
                dialog.dismiss()
                val hook = com.reamicro.fix.hook.WebDavDriveHook.activeInstance
                if (hook == null) {
                    showToast("在线书源模块未就绪")
                    return
                }
                runCatching { hook.startOnlineCompletionDownload(discoverDownloadTarget(source, book), mode) }
                    .onFailure { XposedBridge.log("$DISCOVER_LOG_PREFIX start download failed: ${it.message}") }
            }

            card.addView(settingsDialogTitle(context, book.name.ifBlank { DISCOVER_DOWNLOAD_TITLE }, colors))
            val subtitle = listOf(book.author, source.name).filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                card.addView(settingsDialogHint(context, subtitle, colors))
            }
            // 与书单行同一套标签串（状态/字数/章节/平台/时间/分类），下载前信息对齐列表所见。
            val tagLine = discoverTagLine(book, source)
            if (tagLine.isNotBlank()) {
                card.addView(settingsDialogHint(context, tagLine, colors))
            }
            // 简介：弹窗里能看个大概再决定下不下；书源给的简介动辄几百字，截到概要即可。
            if (book.intro.isNotBlank()) {
                val intro = book.intro.trim()
                val text = if (intro.length > DISCOVER_INTRO_MAX_CHARS) {
                    intro.take(DISCOVER_INTRO_MAX_CHARS) + DISCOVER_INTRO_ELLIPSIS
                } else {
                    intro
                }
                card.addView(settingsDialogHint(context, text, colors))
            }
            card.addView(settingsDialogButtonRow(context, listOf(fullButton, onDemandButton, cancelButton)))

            fullButton.setOnClickListener { startDownload(OnlineBookDownloadMode.FULL) }
            onDemandButton.setOnClickListener { startDownload(OnlineBookDownloadMode.ON_DEMAND) }
            cancelButton.setOnClickListener { dialog.dismiss() }

            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity)
        }.onFailure {
            XposedBridge.log("$DISCOVER_LOG_PREFIX download dialog failed: ${it.stackTraceToString()}")
        }
    }
}

/** 把发现页条目折成在线补全链路认识的下载目标。 */
private fun discoverDownloadTarget(source: DiscoverSource, book: DiscoverBook): OnlineDownloadTarget =
    OnlineDownloadTarget(
        source = source.source,
        query = book.name,
        result = OnlineBookSearchResult(
            sourceName = source.name,
            name = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            detailUrl = book.detailUrl,
            intro = book.intro,
            chapterCount = book.chapterCount.filter { it.isDigit() }.toIntOrNull() ?: 0,
            status = book.status,
            wordCount = book.wordCount,
            updateTime = book.updateTime,
            platformName = "",
        ),
    )

private const val DISCOVER_DOWNLOAD_TITLE = "下载"

/** 弹窗里简介的截断长度（字符）。 */
private const val DISCOVER_INTRO_MAX_CHARS = 150

private const val DISCOVER_INTRO_ELLIPSIS = "…"

private const val DISCOVER_LOG_PREFIX = "[ReaMicro]"
