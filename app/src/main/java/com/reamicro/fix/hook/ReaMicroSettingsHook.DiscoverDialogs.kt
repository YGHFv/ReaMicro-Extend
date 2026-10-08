package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.online.download.OnlineBookDownloadMode
import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import com.reamicro.fix.hook.webdav.OnlineDownloadTarget
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState

internal fun ReaMicroSettingsHook.openDiscoverSourceDialog(
    activity: Activity,
    onSourceChanged: (() -> Unit)? = null,
) {
    val sources = DiscoverState.sources
    if (sources.isEmpty()) return
    val currentSourceId = DiscoverState.selection.sourceId
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
            val dialog = Dialog(activity)
            val card = discoverDialogCard(context, colors)
            card.addView(settingsDialogTitle(context, DISCOVER_SOURCE_DIALOG_TITLE, colors))

            val input = settingsDialogInput(
                context,
                DISCOVER_SOURCE_FILTER_HINT,
                singleLine = true,
                colors = colors,
            ).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }

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
                            onSourceChanged?.invoke()
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

            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f, dismissOnThemeChange = true)
        }
    }
}

internal fun ReaMicroSettingsHook.openDiscoverKindDialog(activity: Activity) {
    val selection = DiscoverState.selection
    val entry = DiscoverState.sources.firstOrNull { it.source.id == selection.sourceId } ?: return
    val kinds = DiscoverState.kindsFor(entry)
    if (kinds.isEmpty()) return
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
            val dialog = Dialog(activity)
            val card = discoverDialogCard(context, colors)
            card.addView(settingsDialogTitle(context, DISCOVER_KIND_DIALOG_TITLE, colors))

            kinds.forEach { kind ->
                card.addView(discoverDialogAction(context, kind.title, colors) {
                    dialog.dismiss()
                    DiscoverState.select(entry.source.id, kind.title, activity.applicationContext)
                }.apply {
                    isSelected = kind.title == selection.kindTitle
                    if (isSelected) background = settingsRoundedRect(colors.primarySoft, settingsDp(context, 8))
                })
            }

            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f, dismissOnThemeChange = true)
        }
    }
}

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
            if (selected) colors.primarySoft else Color.TRANSPARENT,
            settingsDp(context, 8),
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 8) }
    }

private fun DiscoverSource.matchesSourceFilter(keyword: String): Boolean {
    if (keyword.isBlank()) return true
    val needle = keyword.lowercase()
    return name.lowercase().contains(needle) ||
        source.id.lowercase().contains(needle) ||
        source.aliases.any { it.lowercase().contains(needle) }
}

private fun DiscoverSource.sourceBadge(): String = buildList {
    source.aliases.forEach { alias ->

        val short = alias.trim().removePrefix(DISCOVER_SOURCE_ALIAS_PREFIX)
        if (short.isNotEmpty() && short.length <= DISCOVER_SOURCE_BADGE_MAX && short !in this) add(short)
    }
}.joinToString(",")

private const val DISCOVER_SOURCE_DIALOG_TITLE = "书源"

private const val DISCOVER_KIND_DIALOG_TITLE = "分类分组"

private const val DISCOVER_SOURCE_FILTER_HINT = "筛选发现源"

private const val DISCOVER_SOURCE_NO_MATCH = "没有匹配的书源"

private const val DISCOVER_SOURCE_CHECKED_MARK = "✓ "

private const val DISCOVER_SOURCE_UNCHECKED_MARK = "\u2003\u2003"

private const val DISCOVER_SOURCE_ALIAS_PREFIX = "online_"

private const val DISCOVER_SOURCE_BADGE_MAX = 10

internal fun ReaMicroSettingsHook.openDiscoverConfigDialog(activity: Activity) {
    if (DiscoverState.sources.isEmpty()) return
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
            val dialog = Dialog(activity)
            val card = discoverDialogCard(context, colors)

            val titleView = settingsDialogTitle(context, DISCOVER_CONFIG_DIALOG_TITLE, colors)
            titleView.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { bottomMargin = 0 }
            val sourceButton = TextView(context).apply {
                textSize = 15f
                setTextColor(colors.title)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(
                    settingsDp(context, 8),
                    settingsDp(context, 4),
                    0,
                    settingsDp(context, 4),
                )
            }
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView)
                addView(sourceButton)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = settingsDp(context, 14) }
            }
            card.addView(titleRow)

            val filterHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            card.addView(filterHost)

            fun rebuild() {
                val sourceId = DiscoverState.selection.sourceId
                val source = DiscoverState.sources.firstOrNull { it.source.id == sourceId }
                sourceButton.text = buildString {
                    append(source?.name.orEmpty().ifBlank { DISCOVER_NO_SOURCE_TEXT })
                    append(DISCOVER_CONFIG_SOURCE_SUFFIX)
                }
                filterHost.removeAllViews()
                val filter = source?.filter
                if (source == null || filter == null) {
                    filterHost.addView(settingsDialogHint(context, DISCOVER_CONFIG_NO_FILTER, colors))
                    return
                }

                val working = DiscoverState.filterSelection(sourceId).toMutableMap()

                fun buildFilterSection() {
                    filterHost.removeAllViews()
                    filterHost.addView(
                        discoverConfigSectionLabel(context, DISCOVER_CONFIG_FILTER_SECTION, colors),
                    )
                    filter.groups.forEach { group ->
                        filterHost.addView(
                            discoverConfigGroupRow(context, group.name, working[group.name].orEmpty(), colors) {
                                openDiscoverFilterOptionDialog(
                                    activity,
                                    group,
                                    working[group.name].orEmpty(),
                                ) { picked ->
                                    working[group.name] = picked
                                    buildFilterSection()
                                }
                            },
                        )
                    }
                    filterHost.addView(
                        discoverConfigApplyRow(
                            context,
                            colors,
                            onReset = {
                                working.clear()
                                working.putAll(filter.defaultSelection())
                                buildFilterSection()
                            },
                            onApply = {
                                DiscoverState.applyFilterSelection(
                                    sourceId,
                                    working,
                                    activityProvider()?.applicationContext,
                                )
                                dialog.dismiss()
                            },
                        ),
                    )
                }
                buildFilterSection()
            }

            sourceButton.setOnClickListener {
                openDiscoverSourceDialog(activity) { rebuild() }
            }
            rebuild()

            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f, dismissOnThemeChange = true)
        }.onFailure {
            XposedBridge.log("$DISCOVER_LOG_PREFIX config dialog failed: ${it.stackTraceToString()}")
        }
    }
}

private fun ReaMicroSettingsHook.discoverConfigSectionLabel(
    context: Context,
    text: String,
    colors: SettingsDialogColors,
): TextView =
    TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.START
        setTextColor(colors.body)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 10) }
    }

private fun ReaMicroSettingsHook.discoverConfigGroupRow(
    context: Context,
    groupName: String,
    selectedTitle: String,
    colors: SettingsDialogColors,
    onClick: () -> Unit,
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = settingsDp(context, 52)
    setPadding(0, settingsDp(context, 12), 0, settingsDp(context, 12))
    addView(TextView(context).apply {
        text = groupName
        textSize = 15f
        setTextColor(colors.title)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(TextView(context).apply {
        text = selectedTitle.ifBlank { DISCOVER_CONFIG_OPTION_FALLBACK }
        textSize = 14f
        gravity = Gravity.END
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(colors.body)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(TextView(context).apply {
        text = " ›"
        textSize = 18f
        setTextColor(colors.body)
    })
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private fun ReaMicroSettingsHook.discoverConfigApplyRow(
    context: Context,
    colors: SettingsDialogColors,
    onReset: () -> Unit,
    onApply: () -> Unit,
): LinearLayout {
    val resetButton = discoverTextButton(context, DISCOVER_CONFIG_RESET, colors, onReset)
    val applyButton = discoverTextButton(context, DISCOVER_CONFIG_APPLY, colors, onApply)
    return settingsDialogButtonRow(context, listOf(resetButton, applyButton)).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = settingsDp(context, 6) }
    }
}

private fun ReaMicroSettingsHook.openDiscoverFilterOptionDialog(
    activity: Activity,
    group: com.reamicro.fix.discover.DiscoverFilterGroup,
    currentTitle: String,
    onPick: (String) -> Unit,
) {
    activity.runOnUiThread {
        runCatching {
            val context: Context = activity
            val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
            val dialog = Dialog(activity)
            val card = discoverDialogCard(context, colors)
            card.addView(settingsDialogTitle(context, group.name, colors))
            val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            group.options.forEach { option ->
                val selected = option.title == currentTitle
                listHost.addView(
                    TextView(context).apply {
                        text = buildString {
                            append(if (selected) DISCOVER_SOURCE_CHECKED_MARK else DISCOVER_SOURCE_UNCHECKED_MARK)
                            append(option.title)
                        }
                        textSize = 14f
                        setTextColor(if (selected) colors.primaryText else colors.title)
                        setSingleLine(true)
                        setPadding(
                            settingsDp(context, 12),
                            settingsDp(context, 10),
                            settingsDp(context, 12),
                            settingsDp(context, 10),
                        )
                        background = settingsRoundedRect(
                            if (selected) colors.primarySoft else Color.TRANSPARENT,
                            settingsDp(context, 8),
                        )
                        setOnClickListener {
                            dialog.dismiss()
                            onPick(option.title)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = settingsDp(context, 8) }
                    },
                )
            }
            card.addView(listHost)
            showSettingsDialog(dialog, settingsDialogScroll(context, card), activity, 0.92f, dismissOnThemeChange = true)
        }
    }
}

private const val DISCOVER_CONFIG_DIALOG_TITLE = "筛选管理"

private const val DISCOVER_CONFIG_FILTER_SECTION = "筛选条件"

private const val DISCOVER_CONFIG_APPLY = "应用"

private const val DISCOVER_CONFIG_RESET = "重置"

private const val DISCOVER_CONFIG_NO_FILTER = "当前书源不支持组合筛选"

private const val DISCOVER_NO_SOURCE_TEXT = "未选择书源"

private const val DISCOVER_CONFIG_SOURCE_SUFFIX = " ▾"

private const val DISCOVER_CONFIG_OPTION_FALLBACK = "全部"

internal fun ReaMicroSettingsHook.openDiscoverBookDialog(activity: Activity, source: DiscoverSource, book: DiscoverBook) {
    activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        runCatching {
            val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
            val dialog = Dialog(activity)
            val card = discoverDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, book.name, colors))
            card.addView(settingsDialogHint(activity, listOf(book.author, source.name).filter { it.isNotBlank() }.joinToString(" · "), colors))
            val tags = discoverTagLine(book, source)
            if (tags.isNotBlank()) card.addView(settingsDialogHint(activity, tags, colors))
            if (book.intro.isNotBlank()) {
                val introView = settingsDialogHint(activity, book.intro.trim(), colors).apply {
                    setTextIsSelectable(true)
                }
                val introScroll = object : ScrollView(activity) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val maximum = introView.lineHeight * 6
                        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
                            else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
                    }
                }.apply {
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(introView, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                }
                card.addView(introScroll, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { bottomMargin = settingsDp(activity, 10) })
            }
            fun startDownload(mode: OnlineBookDownloadMode) {
                val hook = WebDavDriveHook.activeInstance
                if (hook == null) {
                    showToast("在线书源模块未就绪")
                    return
                }
                dialog.dismiss()
                runCatching { hook.startOnlineCompletionDownload(discoverDownloadTarget(source, book), mode) }
                    .onFailure {
                        XposedBridge.log("$DISCOVER_LOG_PREFIX start download failed: ${it.message}")
                        showToast("无法启动下载，请重试")
                    }
            }
            val close = discoverTextButton(activity, "关闭", colors) { dialog.dismiss() }
            val onDemand = discoverTextButton(activity, "逐章加载", colors) {
                startDownload(OnlineBookDownloadMode.ON_DEMAND)
            }
            val full = discoverTextButton(activity, "整本下载", colors) {
                startDownload(OnlineBookDownloadMode.FULL)
            }
            card.addView(settingsDialogButtonRow(activity, listOf(close, onDemand, full)))
            showSettingsDialog(dialog, card, activity, dismissOnThemeChange = true)
        }.onFailure {
            XposedBridge.log("$DISCOVER_LOG_PREFIX book dialog failed: ${it.message}")
            showToast("无法打开书籍面板，请重试")
        }
    }
}

private fun ReaMicroSettingsHook.discoverDialogCard(context: Context, colors: SettingsDialogColors): LinearLayout =
    settingsDialogCard(context, colors).apply {
        background = settingsRoundedRect(colors.card, settingsDp(context, 16))
    }

private fun ReaMicroSettingsHook.discoverDialogAction(
    context: Context, title: String, colors: SettingsDialogColors, onClick: () -> Unit,
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    minimumHeight = settingsDp(context, 52)
    setPadding(settingsDp(context, 12), settingsDp(context, 12), settingsDp(context, 12), settingsDp(context, 12))
    addView(TextView(context).apply {
        text = title
        textSize = 15f
        setTextColor(colors.title)
    })
    setOnClickListener { onClick() }
}

private fun ReaMicroSettingsHook.discoverTextButton(
    context: Context, title: String, colors: SettingsDialogColors, onClick: () -> Unit,
): TextView = TextView(context).apply {
    text = title
    textSize = 14f
    gravity = Gravity.CENTER
    minimumHeight = settingsDp(context, 48)
    setTextColor(colors.primaryText)
    setOnClickListener { onClick() }
}

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

private const val DISCOVER_LOG_PREFIX = "[ReaMicro]"
