package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.app.Dialog
import android.widget.LinearLayout
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.webdav.ONLINE_COMPLETION_ON_DEMAND_INITIAL_CHAPTERS
import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import com.reamicro.fix.hook.webdav.OnlineDownloadTarget
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.download.OnlineBookDownloadMode
import com.reamicro.fix.xposed.HookRuntime

/** The discover sources under the host's search pill, the current one ticked. */
internal fun ReaMicroSettingsHook.openDiscoverSourceDialog(
    activity: Activity,
    onSourceChanged: (() -> Unit)? = null,
) {
    val sources = DiscoverState.sources
    if (sources.isEmpty()) return
    val currentSourceId = DiscoverState.selection.sourceId
    activity.runOnModuleUiThread {
        runCatching {
            val (dialog, card) = discoverSheet(activity, DISCOVER_SOURCE_TITLE)
            val kit = HostFormKit(activity)
            val search = HostSearchField(kit, DISCOVER_SOURCE_FILTER_HINT)
            val list = HostOptionList(kit, DISCOVER_SOURCE_NO_MATCH)
            fun show(keyword: String) = list.show(
                sources.filter { it.matchesSourceFilter(keyword) }.map { entry ->
                    HostOption(entry.name, entry.sourceBadge(), entry.source.id == currentSourceId) {
                        if (!HostSettingsDialogs.acceptsUpdates(dialog)) return@HostOption
                        dialog.dismiss()
                        DiscoverState.selectSource(entry.source.id, activityProvider()?.applicationContext)
                        onSourceChanged?.invoke()
                    }
                },
            )
            search.onQuery(::show)
            show("")
            card.addView(list.body)
            HostSettingsDialogs.setActions(dialog, listOf(discoverCancel(dialog)))
            val form = HostPinnedForm(kit, search, HostFormScroll(activity, card))
            showSettingsDialog(dialog, form, activity, dismissOnThemeChange = true)
        }.onFailure { logDiscoverDialog("source", it) }
    }
}

/** The category groups of the current source. */
internal fun ReaMicroSettingsHook.openDiscoverKindDialog(activity: Activity) {
    val selection = DiscoverState.selection
    val entry = DiscoverState.sources.firstOrNull { it.source.id == selection.sourceId } ?: return
    val kinds = DiscoverState.kindsFor(entry)
    if (kinds.isEmpty()) return
    val current = kinds.indexOfFirst { it.key == selection.kindKey }
    showDiscoverOptions(activity, DISCOVER_KIND_DIALOG_TITLE, kinds.map { it.title }, current) { index ->
        val kind = kinds[index]
        DiscoverState.select(entry.source.id, kind.title, activity.applicationContext, kind.key)
    }
}

/** A titled host option list that closes on a pick, or on its cancel button with nothing changed. */
private fun ReaMicroSettingsHook.showDiscoverOptions(
    activity: Activity,
    title: String,
    labels: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    activity.runOnModuleUiThread {
        runCatching {
            val (dialog, card) = discoverSheet(activity, title)
            val list = HostOptionList(HostFormKit(activity))
            list.show(labels.mapIndexed { index, label ->
                HostOption(label, selected = index == selected) {
                    if (!HostSettingsDialogs.acceptsUpdates(dialog)) return@HostOption
                    dialog.dismiss()
                    onPick(index)
                }
            })
            card.addView(list.body)
            HostSettingsDialogs.setActions(dialog, listOf(discoverCancel(dialog)))
            showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
        }.onFailure { logDiscoverDialog("option", it) }
    }
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

private const val DISCOVER_SOURCE_TITLE = "书源"

private const val DISCOVER_KIND_DIALOG_TITLE = "分类分组"

private const val DISCOVER_SOURCE_FILTER_HINT = "筛选发现源"

private const val DISCOVER_SOURCE_NO_MATCH = "没有匹配的书源"

private const val DISCOVER_SOURCE_ALIAS_PREFIX = "online_"

private const val DISCOVER_SOURCE_BADGE_MAX = 10

/** The discover source and, when it combines filters, what each of them is set to. */
internal fun ReaMicroSettingsHook.openDiscoverConfigDialog(activity: Activity) {
    if (DiscoverState.sources.isEmpty()) return
    activity.runOnModuleUiThread {
        runCatching {
            val (dialog, card) = discoverSheet(activity, DISCOVER_CONFIG_DIALOG_TITLE)
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            card.addView(content)
            var resetFilter: () -> Unit = {}
            var applyFilter: () -> Unit = {}
            HostSettingsDialogs.setActions(dialog, listOf(
                HostSettingsDialogs.Action(DISCOVER_CONFIG_RESET, nativeButton = true) { resetFilter() },
                HostSettingsDialogs.Action(DISCOVER_CONFIG_APPLY, nativeButton = true) { applyFilter() },
            ))

            fun rebuild() {
                val sourceId = DiscoverState.selection.sourceId
                val source = DiscoverState.sources.firstOrNull { it.source.id == sourceId }
                val filter = source?.filter
                val working = mutableMapOf<String, String>()
                if (filter != null) working.putAll(DiscoverState.filterSelection(sourceId))

                fun show() {
                    val form = HostForm(activity)
                    val sourceName = source?.name.orEmpty().ifBlank { DISCOVER_NO_SOURCE_TEXT }
                    form.valueRow(form.card(top = 0f), DISCOVER_SOURCE_TITLE, sourceName) {
                        openDiscoverSourceDialog(activity) { rebuild() }
                    }
                    if (filter == null) {
                        form.empty(DISCOVER_CONFIG_NO_FILTER)
                    } else {
                        form.heading(DISCOVER_CONFIG_FILTER_SECTION)
                        val groups = form.card(top = 0f)
                        filter.groups.forEachIndexed { index, group ->
                            if (index > 0) form.divider(groups)
                            val current = working[group.name].orEmpty()
                            val titles = group.options.map { it.title }
                            form.valueRow(groups, group.name, current.ifBlank { DISCOVER_CONFIG_OPTION_FALLBACK }) {
                                showDiscoverOptions(activity, group.name, titles, titles.indexOf(current)) { picked ->
                                    working[group.name] = titles[picked]
                                    show()
                                }
                            }
                        }
                    }
                    content.removeAllViews()
                    content.addView(form.body)
                }
                resetFilter = {
                    if (filter != null) {
                        working.clear()
                        working.putAll(filter.defaultSelection())
                        show()
                    }
                }
                applyFilter = {
                    if (filter != null) {
                        DiscoverState.applyFilterSelection(
                            sourceId,
                            working,
                            activityProvider()?.applicationContext,
                        )
                    }
                    dialog.dismiss()
                }
                show()
            }

            rebuild()
            showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
        }.onFailure { logDiscoverDialog("config", it) }
    }
}

private const val DISCOVER_CONFIG_DIALOG_TITLE = "筛选管理"

private const val DISCOVER_CONFIG_FILTER_SECTION = "筛选条件"

private const val DISCOVER_CONFIG_APPLY = "应用"

private const val DISCOVER_CONFIG_RESET = "重置"

private const val DISCOVER_CONFIG_NO_FILTER = "当前书源不支持组合筛选"

private const val DISCOVER_NO_SOURCE_TEXT = "未选择书源"

private const val DISCOVER_CONFIG_OPTION_FALLBACK = "全部"

/** A discovered book: who wrote it and where it is from, what is known about it, and how to download it. */
internal fun ReaMicroSettingsHook.openDiscoverBookDialog(activity: Activity, source: DiscoverSource, book: DiscoverBook) {
    activity.runOnModuleUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnModuleUiThread
        runCatching {
            val (dialog, card) = discoverSheet(activity, book.name)
            HostSettingsDialogs.setTitleStyle(dialog, "titleMedium")
            val form = HostForm(activity)
            val byline = listOf(book.author, source.name).filter { it.isNotBlank() }.joinToString(" · ")
            if (byline.isNotEmpty()) form.lead(byline)
            val facts = discoverBookFacts(book, source)
            val synopsis = book.intro.trim()
            if (facts.isNotEmpty() || synopsis.isNotEmpty()) {
                val top = if (byline.isEmpty()) 0f else DISCOVER_BOOK_INFO_TOP
                form.body.addView(HostBookInfo(form.kit, facts, synopsis), form.kit.params(top = top))
            }
            val policy = OnlineSourceDownloadPolicyStore.load(activity.applicationContext, source.source.id)
            var mode = if (policy.preferOnDemandLoading) OnlineBookDownloadMode.ON_DEMAND else OnlineBookDownloadMode.FULL
            form.choices(
                DISCOVER_BOOK_MODE_TITLE,
                listOf(
                    OnlineBookDownloadMode.ON_DEMAND.name to DISCOVER_BOOK_ON_DEMAND,
                    OnlineBookDownloadMode.FULL.name to DISCOVER_BOOK_FULL,
                ),
                mode.name,
                DISCOVER_BOOK_MODE_NOTE,
            ) { mode = OnlineBookDownloadMode.valueOf(it) }
            card.addView(form.body)

            fun startDownload() {
                if (!com.reamicro.fix.online.OnlineSourceStore.isCurrent(source.source)) {
                    showToast("在线源已更新或删除，请重新选择书籍")
                    dialog.dismiss()
                    return
                }
                val hook = WebDavDriveHook.activeInstance
                if (hook == null) {
                    showToast("在线书源模块未就绪")
                    return
                }
                dialog.dismiss()
                runCatching { hook.startOnlineCompletionDownload(discoverDownloadTarget(source, book), mode) }
                    .onFailure {
                        HookRuntime.log("$DISCOVER_LOG_PREFIX start download failed: ${it.message}")
                        showToast("无法启动下载，请重试")
                    }
            }
            HostSettingsDialogs.setActions(dialog, listOf(
                discoverCancel(dialog),
                HostSettingsDialogs.Action(DISCOVER_BOOK_DOWNLOAD, nativeButton = true) {
                    if (HostSettingsDialogs.acceptsUpdates(dialog)) startDownload()
                },
            ))
            showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
        }.onFailure {
            logDiscoverDialog("book", it)
            showToast("无法打开书籍面板，请重试")
        }
    }
}

private const val DISCOVER_BOOK_INFO_TOP = 12f

private const val DISCOVER_BOOK_MODE_TITLE = "下载方式"

private const val DISCOVER_BOOK_ON_DEMAND = "逐章加载"

private const val DISCOVER_BOOK_FULL = "整本下载"

private const val DISCOVER_BOOK_MODE_NOTE =
    "逐章加载先下载前 $ONLINE_COMPLETION_ON_DEMAND_INITIAL_CHAPTERS 章，其余章节阅读时再加载。默认选中该书源设置的下载方式。"

private const val DISCOVER_BOOK_DOWNLOAD = "下载"

private const val DISCOVER_DIALOG_CANCEL = "取消"

/** A host dialog and the card its form goes in, under [title]. */
private fun ReaMicroSettingsHook.discoverSheet(activity: Activity, title: String): Pair<Dialog, LinearLayout> {
    val colors = SettingsDialogColors(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, title, colors))
    return settingsDialog(activity) to card
}

private fun discoverCancel(dialog: Dialog) =
    HostSettingsDialogs.Action(DISCOVER_DIALOG_CANCEL, nativeButton = true) { dialog.dismiss() }

private fun logDiscoverDialog(name: String, failure: Throwable) =
    HookRuntime.log("$DISCOVER_LOG_PREFIX $name dialog failed: ${failure.stackTraceToString()}")

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
