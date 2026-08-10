package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.ReaderHighlightSettingsSnapshot
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightRuleType
import com.reamicro.fix.settings.ReaderHighlightStyle
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.GZIPInputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.ReaderHighlightPreviewTextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 阅读页高亮设置簇。
//
// 高亮样式与规则的列表页、编辑弹窗、颜色选择、预览绘制，以及从阅读页唤起的
// 高亮规则面板。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
// 借用宿主 item$default 调用时机，在高亮界面 LazyColumn 最前面插入"补全计划"入口。
// onClick 由 ReaderHook 通过 highlightScreenEntryOnClick 提供。
internal fun ReaMicroSettingsHook.insertHighlightScreenEntryItem(lazyListScope: Any) {
    val onClick = highlightScreenEntryOnClick ?: run {
        XposedBridge.log("$LOG_PREFIX highlight entry item skip: onClick null")
        return
    }
    val bookKey = ReaderHighlightBookContext.bookKey
    val bookTitle = ReaderHighlightBookContext.bookTitle
    XposedBridge.log("$LOG_PREFIX highlight entry item inserting bookKey=$bookKey scope=${lazyListScope.javaClass.name}")
    injectingModuleItem.set(true)
    runCatching {
        addLazyItem(lazyListScope, "reader_highlight_screen_entry_${bookKey.hashCode()}".hashCode()) { composer ->
            renderReaderHighlightScreenEntryCard(
                bookKey = bookKey,
                bookTitle = bookTitle,
                composer = composer,
                onClick = onClick,
            )
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to insert highlight screen entry item: ${it.stackTraceToString()}")
    }
    injectingModuleItem.set(false)
}

// 由 ReaderHook 在 HighlightPageContent 主方法 before/after 调用，标记高亮界面渲染区间。
internal fun ReaMicroSettingsHook.beginHighlightScreenBuild(onClick: () -> Unit) {
    highlightScreenEntryOnClick = onClick
    highlightScreenBuildDepth.set((highlightScreenBuildDepth.get() ?: 0) + 1)
    highlightEntryInjected.set(false)
}

internal fun ReaMicroSettingsHook.endHighlightScreenBuild() {
    val depth = ((highlightScreenBuildDepth.get() ?: 0) - 1).coerceAtLeast(0)
    highlightScreenBuildDepth.set(depth)
}

internal fun ReaMicroSettingsHook.renderReaderHighlightSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderHighlightSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val rows = listOf(
            ActionRow(
                key = "reader_highlight_config",
                title = "\u9ad8\u4eae\u6837\u5f0f",
                subtitle = "${highlight.styles.size} \u4e2a\u6837\u5f0f",
                singleLineSubtitle = true,
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightConfigSettings) },
            ),
            ActionRow(
                key = "reader_highlight_text",
                title = "\u9ad8\u4eae\u89c4\u5219",
                subtitle = "${highlight.rules.count { it.enabled }} / ${highlight.rules.size} \u6761\u89c4\u5219",
                singleLineSubtitle = true,
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightTextSettings) },
            ),
        )
        addLazyItem(lazyListScope, READER_HIGHLIGHT_SETTINGS_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderHighlightConfigSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderHighlightConfigSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        fontLibraryVersionValue()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val rows = buildList {
            add(
                ActionRow(
                    key = "reader_highlight_style_add",
                    title = "\u6dfb\u52a0\u914d\u7f6e",
                    subtitle = "\u65b0\u589e\u4e00\u7ec4\u9ad8\u4eae\u6837\u5f0f",
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightStyleDialog(newReaderHighlightStyle()) },
                    onLongClick = ::openReaderHighlightStyleImportPicker,
                ),
            )
            highlight.styles.forEach { style ->
                add(
                    ActionRow(
                        key = "reader_highlight_style_${style.id}",
                        title = style.name,
                        subtitle = highlightStyleSummary(style),
                        trailing = readerHighlightStyleDefaultTrailing(highlight, style),
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightStyleDialog(style) },
                        onLongClick = { exportReaderHighlightStyle(style) },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, READER_HIGHLIGHT_CONFIG_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderHighlightTextSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderHighlightTextSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val globalRows = buildList {
            add(
                ActionRow(
                    key = "reader_highlight_rule_add",
                    title = "\u6dfb\u52a0\u914d\u7f6e",
                    subtitle = "\u65b0\u589e\u4e00\u6761\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightRuleDialog(newReaderHighlightRule()) },
                ),
            )
            highlight.rules.filter { it.bookKey.isBlank() }.forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_highlight_rule_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_highlight_rule_switch_${rule.id}",
                                checked = rule.enabled,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(rule) },
                    ),
                )
            }
        }
        val bookRows = buildList {
            val bookGroups = readerHighlightBookGroups(highlight)
            bookGroups.forEach { group ->
                add(
                    ActionRow(
                        key = "reader_highlight_book_${group.bookKey.hashCode()}",
                        title = group.bookTitle,
                        subtitle = group.subtitle,
                        trailing = "\u5355\u4e66",
                        singleLineSubtitle = true,
                        onClick = {
                            openNestedInjectedRoute(
                                InjectedRoute.ReaderBookHighlightRules(group.bookKey, group.bookTitle),
                            )
                        },
                    ),
                )
            }
            if (isEmpty()) {
                add(
                    ActionRow(
                        key = "reader_highlight_book_empty",
                        title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                        subtitle = "\u5728\u9605\u8bfb\u9875\u9009\u4e2d\u6587\u672c\u540e\u53ef\u6dfb\u52a0\u672c\u4e66\u9ad8\u4eae",
                        singleLineSubtitle = true,
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, READER_HIGHLIGHT_TEXT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(globalRows, itemComposer)
        }
        addLazyItem(lazyListScope, READER_HIGHLIGHT_BOOK_GROUPS_ITEM_KEY) { itemComposer ->
            renderHostActionCard(bookRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderBookHighlightRulesContent(route: InjectedRoute.ReaderBookHighlightRules, innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderBookHighlightRulesList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val rows = buildList {
            add(
                ActionRow(
                    key = "reader_book_highlight_rule_add_${route.bookKey.hashCode()}",
                    title = "\u6dfb\u52a0\u914d\u7f6e",
                    subtitle = "\u65b0\u589e\u672c\u4e66\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                    singleLineSubtitle = true,
                    onClick = {
                        openReaderHighlightRuleDialog(
                            newReaderHighlightRule(route.bookKey, route.bookTitle),
                        )
                    },
                ),
            )
            highlight.bookRules(route.bookKey).forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_book_highlight_rule_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_book_highlight_rule_switch_${rule.id}",
                                checked = rule.enabled,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(rule) },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, READER_HIGHLIGHT_BOOK_RULES_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderBookOnlyHighlightRulesContent(route: InjectedRoute.ReaderBookOnlyHighlightRules, innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderBookOnlyHighlightRulesList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val rows = buildList {
            val bookRules = highlight.bookRules(route.bookKey)
            bookRules.forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_book_only_highlight_rule_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_book_only_highlight_rule_switch_${rule.id}",
                                checked = rule.enabled,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(rule) },
                    ),
                )
            }
            if (bookRules.isEmpty()) {
                add(
                    ActionRow(
                        key = "reader_book_only_highlight_empty_${route.bookKey.hashCode()}",
                        title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                        subtitle = "\u9009\u4e2d\u672c\u4e66\u6587\u672c\u540e\u53ef\u6dfb\u52a0\u9ad8\u4eae\u89c4\u5219",
                        singleLineSubtitle = true,
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, "reader_book_only_rules_${route.bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderBookGlobalHighlightRulesContent(route: InjectedRoute.ReaderBookGlobalHighlightRules, innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderBookGlobalHighlightRulesList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        val highlight = settings.highlightSettings()
        val globalRules = highlight.globalRules()
        val followsGlobal = highlight.bookFollowsGlobalRules(route.bookKey)
        val enabledIds = highlight.effectiveGlobalRuleIdsForBook(route.bookKey)
        val rows = buildList {
            add(
                ActionRow(
                    key = "reader_book_global_follow_${route.bookKey.hashCode()}",
                    title = "\u8ddf\u968f\u5168\u5c40",
                    subtitle = if (followsGlobal) "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u540c\u6b65\u4fee\u6539\u5168\u5c40\u89c4\u5219" else "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u4ec5\u5f71\u54cd\u672c\u4e66",
                    trailingContent = { itemComposer ->
                        renderHostActionSwitch(
                            key = "reader_book_global_follow_switch_${route.bookKey.hashCode()}",
                            checked = followsGlobal,
                            composer = itemComposer,
                        ) { enabled ->
                            settings.setReaderHighlightBookFollowsGlobalRules(route.bookKey, enabled)
                            bumpReaderHighlightVersion()
                        }
                    },
                    singleLineSubtitle = true,
                ),
            )
            globalRules.forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_book_global_rule_${route.bookKey.hashCode()}_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_book_global_rule_switch_${route.bookKey.hashCode()}_${rule.id}",
                                checked = rule.id in enabledIds,
                                composer = itemComposer,
                            ) { enabled ->
                                if (followsGlobal) {
                                    settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                } else {
                                    settings.setReaderHighlightBookGlobalRuleEnabled(route.bookKey, rule.id, enabled)
                                }
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, "reader_book_global_rules_${route.bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

// 在阅微原生高亮界面顶部注入的容器：读取"补全计划"整页状态（快照读，随点击重组）。
// plan==0 时渲染"补全计划"入口卡片；plan>0 时渲染占满全屏的补全计划整页覆盖在原生内容之上。
// 因为读取状态发生在本注入 composable 的重启作用域内，点击改变状态会让本作用域重组切换分支。
internal fun ReaMicroSettingsHook.renderReaderHighlightScreenContainer(
    bookKey: String,
    bookTitle: String,
    composer: Any,
    onEntryClick: () -> Unit,
) {
    readerHighlightVersionValue()
    val plan = readerHighlightScreenPlanValue()
    if (plan > 0) {
        renderReaderHighlightScreenPlanPage(bookKey, bookTitle, composer)
    } else {
        renderReaderHighlightScreenEntryCard(bookKey, bookTitle, composer, onEntryClick)
    }
}

// 在阅微原生高亮界面顶部注入的"补全计划"入口卡片，样式沿用设置里的 ActionRow 行样式。
internal fun ReaMicroSettingsHook.renderReaderHighlightScreenEntryCard(
    bookKey: String,
    bookTitle: String,
    composer: Any,
    onClick: () -> Unit,
) {
    readerHighlightVersionValue()
    val highlight = settings.highlightSettings()
    val enabled = highlight.rules.count { it.enabled }
    val rows = listOf(
        ActionRow(
            key = "reader_highlight_screen_entry_${bookKey.hashCode()}",
            title = "\u8865\u5168\u8ba1\u5212",
            subtitle = "$enabled / ${highlight.rules.size} \u6761\u9ad8\u4eae\u89c4\u5219 \u00b7 ${highlight.styles.size} \u4e2a\u6837\u5f0f",
            singleLineSubtitle = true,
            onClick = onClick,
        ),
    )
    // 用带底部内边距的宿主 Column 包裹卡片，与下方"预设规则"标题拉开间距。
    val content = functionProxy("ReaderHighlightScreenEntryCard", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        renderHostActionCard(rows, innerComposer)
        targetUnit()
    }
    val bottomPaddedModifier = method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
        null,
        modifierInstance(),
        0f,
        0f,
        0f,
        udp(12),
        7,
        null,
    )
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        bottomPaddedModifier,
        arrangementTop(),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

// 通过 LazyListScope.item 在高亮界面 LazyColumn 最前面插入"补全计划"入口。
// 相比在 SectionTitle 前直接渲染，此方式才能真正成为独立的列表项显示在最上方。
internal fun ReaMicroSettingsHook.addReaderHighlightScreenEntryLazyItem(
    lazyListScope: Any,
    bookKey: String,
    bookTitle: String,
    onClick: () -> Unit,
) {
    addLazyItem(lazyListScope, "reader_highlight_screen_entry_${bookKey.hashCode()}".hashCode()) { itemComposer ->
        renderReaderHighlightScreenEntryCard(
            bookKey = bookKey,
            bookTitle = bookTitle,
            composer = itemComposer,
            onClick = onClick,
        )
    }
}

internal fun ReaMicroSettingsHook.renderReaderHighlightRulesSheetFromReader(
    globalRules: Boolean,
    bookKey: String,
    bookTitle: String,
    composer: Any,
    onClose: () -> Unit,
) {
    // 关闭 sheet 时把子页面状态复位到规则列表，避免下次打开仍停留在样式子页。
    val closeSheet: () -> Unit = {
        setReaderHighlightSheetSubPage(0)
        onClose()
    }
    val content = functionProxy("ReaderHighlightRulesSheetContent", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        // 读取子页面状态：0=规则列表，1=高亮样式列表。读值使其可随点击重组翻页。
        val subPage = readerHighlightSheetSubPageValue()
        // 子页面标题与返回行为随状态切换：样式页返回到规则页，规则页返回关闭 sheet。
        val onBack: () -> Unit = if (subPage == 1) {
            { setReaderHighlightSheetSubPage(0) }
        } else {
            closeSheet
        }
        val title = if (subPage == 1) "\u9ad8\u4eae\u6837\u5f0f" else "\u9ad8\u4eae\u89c4\u5219"
        renderReaderSheetBackHandler(innerComposer, onBack)
        renderReaderSheetTopBar(title, innerComposer, onBack)
        if (subPage == 1) {
            renderReaderHighlightSheetStyleList(innerComposer)
        } else {
            renderReaderHighlightRulesSheetList(globalRules, bookKey, bookTitle, innerComposer)
        }
        targetUnit()
    }
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        readerHighlightSheetModifier(composer),
        arrangementTop(),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

// sheet 内的"高亮样式"子页面：与设置里的高亮样式页一致（添加配置 + 各样式行），点击样式打开编辑弹窗。
internal fun ReaMicroSettingsHook.renderReaderHighlightSheetStyleList(composer: Any) {
    val listContent = functionProxy("ReaderHighlightSheetStyleList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        fontLibraryVersionValue()
        val highlight = settings.highlightSettings()
        val rows = buildList {
            add(
                ActionRow(
                    key = "reader_sheet_style_add",
                    title = "\u6dfb\u52a0\u914d\u7f6e",
                    subtitle = "\u65b0\u589e\u4e00\u7ec4\u9ad8\u4eae\u6837\u5f0f",
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightStyleDialog(newReaderHighlightStyle()) },
                    onLongClick = ::openReaderHighlightStyleImportPicker,
                ),
            )
            highlight.styles.forEach { style ->
                add(
                    ActionRow(
                        key = "reader_sheet_style_${style.id}",
                        title = style.name,
                        subtitle = highlightStyleSummary(style),
                        trailing = readerHighlightStyleDefaultTrailing(highlight, style),
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightStyleDialog(style) },
                        onLongClick = { exportReaderHighlightStyle(style) },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, "reader_sheet_style_list".hashCode()) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    method(LAZY_DSL_KT_CLASS, LAZY_COLUMN_METHOD, 13).invoke(
        null,
        readerHighlightSheetListModifier(),
        null,
        null,
        false,
        spacedBy(12),
        null,
        null,
        false,
        null,
        listContent,
        composer,
        0,
        494,
    )
}

// "补全计划"整页：直接替换阅微原生高亮页（HighlightPageContent）Column 里的正文内容。
// 由 ReaderHook 在原生第一个分组标题处调用，并把原生标题/预设行/自定义行全部跳过，
// 使本内容占据原生正文位置（保留原生顶部"高亮"标题栏），实现同一整页内的真实跳转，而非弹窗。
// plan==1：三段式卡片（样式入口 + 全局/预设 + 单书）；plan==2：高亮样式列表。样式全部复用设置卡片。
internal fun ReaMicroSettingsHook.renderReaderHighlightScreenPlanPage(bookKey: String, bookTitle: String, composer: Any) {
    val plan = readerHighlightScreenPlanValue()
    val onBack: () -> Unit = when (plan) {
        2 -> { { setReaderHighlightScreenPlan(1) } }
        else -> { { setReaderHighlightScreenPlan(0) } }
    }
    renderReaderSheetBackHandler(composer, onBack)
    readerHighlightVersionValue()
    // 用一个 Column 承载多张设置卡片，卡片之间留出与设置页一致的间距（spacedBy 12dp）。
    val content = functionProxy("ReaderHighlightPlanContent", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        if (plan == 2) {
            renderHostActionCard(readerHighlightPlanStyleRows(), innerComposer)
        } else {
            renderHostActionCard(readerHighlightSheetStyleRows(), innerComposer)
            renderHostActionCard(readerHighlightSheetGlobalRows(bookKey), innerComposer)
            renderHostActionCard(readerHighlightSheetBookRows(bookKey, bookTitle), innerComposer)
        }
        targetUnit()
    }
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        readerHighlightPlanColumnModifier(),
        spacedBy(12),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

// 补全计划正文 Column 的 modifier：横向留白与设置页一致（16dp）。
internal fun ReaMicroSettingsHook.readerHighlightPlanColumnModifier(): Any {
    val filled = method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4)
        .invoke(null, modifierInstance(), 0f, 1, null)
    return method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5)
        .invoke(null, filled, udp(16), udp(8), 2, null)
}

// 补全计划-高亮样式子页的行：添加配置 + 各样式行，与设置里的高亮样式页一致。
internal fun ReaMicroSettingsHook.readerHighlightPlanStyleRows(): List<ActionRow> {
    val highlight = settings.highlightSettings()
    return buildList {
        add(
            ActionRow(
                key = "reader_plan_style_add",
                title = "\u6dfb\u52a0\u914d\u7f6e",
                subtitle = "\u65b0\u589e\u4e00\u7ec4\u9ad8\u4eae\u6837\u5f0f",
                singleLineSubtitle = true,
                onClick = { openReaderHighlightStyleDialog(newReaderHighlightStyle()) },
                onLongClick = ::openReaderHighlightStyleImportPicker,
            ),
        )
        highlight.styles.forEach { style ->
            add(
                ActionRow(
                    key = "reader_plan_style_${style.id}",
                    title = style.name,
                    subtitle = highlightStyleSummary(style),
                    trailing = readerHighlightStyleDefaultTrailing(highlight, style),
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightStyleDialog(style) },
                    onLongClick = { exportReaderHighlightStyle(style) },
                ),
            )
        }
    }
}

internal fun ReaMicroSettingsHook.renderReaderHighlightRulesSheetList(
    globalRules: Boolean,
    bookKey: String,
    bookTitle: String,
    composer: Any,
) {
    val listContent = functionProxy("ReaderHighlightRulesSheetList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        // 样式行置顶：高亮样式入口作为该规则入口内部的第一张卡。
        val styleRows = readerHighlightSheetStyleRows()
        addLazyItem(lazyListScope, "reader_highlight_sheet_style_${bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(styleRows, itemComposer)
        }
        // 全局（预设）高亮规则。
        val globalRuleRows = readerHighlightSheetGlobalRows(bookKey)
        addLazyItem(lazyListScope, "reader_highlight_sheet_global_${bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(globalRuleRows, itemComposer)
        }
        // 单书规则：仅显示本书的，直接平铺，不再按书名折叠。
        val bookRuleRows = readerHighlightSheetBookRows(bookKey, bookTitle)
        addLazyItem(lazyListScope, "reader_highlight_sheet_book_${bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(bookRuleRows, itemComposer)
        }
        targetUnit()
    }
    method(LAZY_DSL_KT_CLASS, LAZY_COLUMN_METHOD, 13).invoke(
        null,
        readerHighlightSheetListModifier(),
        null,
        null,
        false,
        spacedBy(12),
        null,
        null,
        false,
        null,
        listContent,
        composer,
        0,
        494,
    )
}

// 样式入口行（置顶）：点击在 sheet 内翻到"高亮样式"子页面（不弹窗），与设置页体验一致。
internal fun ReaMicroSettingsHook.readerHighlightSheetStyleRows(): List<ActionRow> {
    val highlight = settings.highlightSettings()
    return listOf(
        ActionRow(
            key = "reader_sheet_style_entry",
            title = "\u9ad8\u4eae\u6837\u5f0f",
            subtitle = "${highlight.styles.size} \u4e2a\u6837\u5f0f",
            trailing = "\u7ba1\u7406",
            singleLineSubtitle = true,
            onClick = { setReaderHighlightSheetSubPage(1) },
        ),
    )
}

// 全局（预设）高亮规则行：跟随全局开关 + 各规则开关。
internal fun ReaMicroSettingsHook.readerHighlightSheetGlobalRows(bookKey: String): List<ActionRow> {
    val highlight = settings.highlightSettings()
    val rules = highlight.globalRules()
    val followsGlobal = highlight.bookFollowsGlobalRules(bookKey)
    val enabledIds = highlight.effectiveGlobalRuleIdsForBook(bookKey)
    return buildList {
        add(
            ActionRow(
                key = "reader_sheet_global_add_${bookKey.hashCode()}",
                title = "\u6dfb\u52a0\u5168\u5c40\u89c4\u5219",
                subtitle = "\u65b0\u589e\u4e00\u6761\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u5168\u5c40\u9ad8\u4eae\u89c4\u5219",
                singleLineSubtitle = true,
                onClick = { openReaderHighlightRuleDialog(newReaderHighlightRule()) },
            ),
        )
        add(
            ActionRow(
                key = "reader_sheet_follow_${bookKey.hashCode()}",
                title = "\u8ddf\u968f\u5168\u5c40",
                subtitle = if (followsGlobal) {
                    "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u540c\u6b65\u4fee\u6539\u5168\u5c40\u89c4\u5219"
                } else {
                    "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u4ec5\u5f71\u54cd\u672c\u4e66"
                },
                trailingContent = { itemComposer ->
                    renderHostActionSwitch(
                        key = "reader_sheet_follow_switch_${bookKey.hashCode()}",
                        checked = followsGlobal,
                        composer = itemComposer,
                    ) { enabled ->
                        settings.setReaderHighlightBookFollowsGlobalRules(bookKey, enabled)
                        bumpReaderHighlightVersion()
                    }
                },
                singleLineSubtitle = true,
            ),
        )
        if (rules.isEmpty()) {
            add(
                ActionRow(
                    key = "reader_sheet_global_empty_${bookKey.hashCode()}",
                    title = "\u6682\u65e0\u5168\u5c40\u89c4\u5219",
                    subtitle = "\u53ef\u5728\u9ad8\u4eae\u7ba1\u7406\u4e2d\u6dfb\u52a0",
                    singleLineSubtitle = true,
                ),
            )
        }
        rules.forEach { rule ->
            add(
                ActionRow(
                    key = "reader_sheet_global_${bookKey.hashCode()}_${rule.id}",
                    title = rule.name,
                    subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                    trailingContent = { itemComposer ->
                        renderHostActionSwitch(
                            key = "reader_sheet_global_switch_${bookKey.hashCode()}_${rule.id}",
                            checked = rule.id in enabledIds,
                            composer = itemComposer,
                        ) { enabled ->
                            if (followsGlobal) {
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                            } else {
                                settings.setReaderHighlightBookGlobalRuleEnabled(bookKey, rule.id, enabled)
                            }
                            bumpReaderHighlightVersion()
                        }
                    },
                    singleLineSubtitle = true,
                ),
            )
        }
    }
}

// 单书规则行：仅本书，平铺显示，第一行为"添加本书规则"。
internal fun ReaMicroSettingsHook.readerHighlightSheetBookRows(bookKey: String, bookTitle: String): List<ActionRow> {
    val highlight = settings.highlightSettings()
    val rules = highlight.rules.filter { it.bookKey.isNotBlank() && it.appliesToBook(bookKey, bookTitle) }
    return buildList {
        add(
            ActionRow(
                key = "reader_sheet_book_add_${bookKey.hashCode()}",
                title = "\u6dfb\u52a0\u672c\u4e66\u89c4\u5219",
                subtitle = "\u65b0\u589e\u672c\u4e66\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                singleLineSubtitle = true,
                onClick = { openReaderHighlightRuleDialog(newReaderHighlightRule(bookKey, bookTitle)) },
            ),
        )
        if (rules.isEmpty()) {
            add(
                ActionRow(
                    key = "reader_sheet_book_empty_${bookKey.hashCode()}",
                    title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                    subtitle = "\u9009\u4e2d\u6587\u5b57\u540e\u53ef\u6dfb\u52a0\u672c\u4e66\u9ad8\u4eae",
                    singleLineSubtitle = true,
                ),
            )
        }
        rules.forEach { rule ->
            add(
                ActionRow(
                    key = "reader_sheet_book_${bookKey.hashCode()}_${rule.id}",
                    title = rule.name,
                    subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                    trailingContent = { itemComposer ->
                        renderHostActionSwitch(
                            key = "reader_sheet_book_switch_${bookKey.hashCode()}_${rule.id}",
                            checked = rule.enabled,
                            composer = itemComposer,
                        ) { enabled ->
                            settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                            bumpReaderHighlightVersion()
                        }
                    },
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightRuleDialog(rule) },
                ),
            )
        }
    }
}

internal fun ReaMicroSettingsHook.renderReaderHighlightColorPickerContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderHighlightColorPickerList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val current = settings.dialogueHighlightSettings().color
        val rows = READER_HIGHLIGHT_COLOR_OPTIONS.map { option ->
            ActionRow(
                key = "reader_dialogue_highlight_color_${option.value}",
                title = option.title,
                subtitle = option.value,
                trailing = if (option.value.equals(current, ignoreCase = true)) "\u5f53\u524d" else null,
                singleLineSubtitle = true,
                onClick = {
                    settings.setReaderDialogueHighlightColor(option.value)
                    navigateBackFromInjectedRoute()
                },
            )
        }
        addLazyItem(lazyListScope, READER_HIGHLIGHT_COLOR_PICKER_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.openReaderHighlightStyleDialog(style: ReaderHighlightStyle) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val nameInput = settingsDialogInput(activity, "\u6837\u5f0f\u540d\u79f0", singleLine = true, colors = colors).apply {
                setText(style.name)
            }
            val colorInput = settingsDialogInput(activity, "\u989c\u8272\uff0c\u4f8b\u5982 #FF9800", singleLine = true, colors = colors).apply {
                setText(style.color)
            }
            var fontSelection = style.fontFamily
            lateinit var syncFontStatus: () -> Unit
            val fontStatus = settingsDialogFontChoiceRow(activity, "", null, fontSelection, colors) {
                openSettingsFontSelectionDialog(
                    activity = activity,
                    title = "\u5b57\u4f53",
                    currentSelection = fontSelection,
                    clearTitle = "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53",
                ) { selection ->
                    fontSelection = selection
                    syncFontStatus.invoke()
                }
            }
            val cssInput = settingsDialogInput(activity, "CSS\uff08\u652f\u6301 color/background-color/border/padding/margin/background-size\uff09", singleLine = false, colors = colors).apply {
                setText(sanitizeReaderHighlightCss(style.css))
                minLines = 2
            }
            val ninePatchInput = settingsDialogInput(activity, "\u56fe\u7247\u8def\u5f84\uff08\u4fdd\u7559\u914d\u7f6e\uff09", singleLine = true, colors = colors).apply {
                setText(style.ninePatchPath)
            }
            val ninePatchSelectButton = settingsDialogButton(
                activity,
                if (style.ninePatchPath.isBlank()) "\u9009\u62e9\u56fe\u7247" else "\u66f4\u6362\u56fe\u7247",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            val setLightDefaultButton = settingsDialogButton(activity, "\u6d45\u8272\u9ed8\u8ba4", colors, SettingsDialogButtonRole.Neutral)
            val setDarkDefaultButton = settingsDialogButton(activity, "\u6df1\u8272\u9ed8\u8ba4", colors, SettingsDialogButtonRole.Neutral)
            val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
            val deleteButton = settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
            val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
            val preview = readerHighlightStylePreview(activity, "\u9884\u89c8\uff1a\u201c\u5bf9\u8bdd\u9ad8\u4eae\u9884\u89c8\u201d", colors)
            lateinit var syncPreviews: () -> Unit
            fun syncImageButtons() {
                ninePatchSelectButton.text = if (ninePatchInput.text?.toString()?.trim().isNullOrBlank()) {
                    "\u9009\u62e9\u56fe\u7247"
                } else {
                    "\u66f4\u6362\u56fe\u7247"
                }
            }
            syncFontStatus = {
                fontStatus.text = "\u5b57\u4f53\uff1a${dialogueHighlightFontSummary(fontSelection)}"
                fontStatus.typeface = androidTypefaceForFontSelection(fontSelection) ?: Typeface.DEFAULT
            }
            syncPreviews = {
                syncReaderHighlightStylePreview(
                    preview,
                    colorInput.text?.toString().orEmpty(),
                    cssInput.text?.toString().orEmpty(),
                    ninePatchInput.text?.toString()?.trim().orEmpty(),
                    style.ninePatchSlice,
                    fontSelection,
                    colors,
                )
            }
            listOf(colorInput, cssInput).forEach { input ->
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = syncPreviews.invoke()
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            ninePatchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    syncImageButtons()
                    syncPreviews.invoke()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            card.addView(settingsDialogTitle(activity, "\u9ad8\u4eae\u6837\u5f0f", colors))
            card.addView(nameInput)
            card.addView(colorInput)
            syncFontStatus.invoke()
            card.addView(fontStatus)
            card.addView(cssInput)
            syncPreviews.invoke()
            card.addView(preview)
            card.addView(ninePatchSelectButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(activity, 6) })
            card.addView(settingsDialogButtonRow(activity, listOf(setLightDefaultButton, setDarkDefaultButton)))
            syncImageButtons()
            val buttons = if (style.id in setOf(
                ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID,
                ModuleSettings.BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID,
            )) {
                listOf(finishButton, cancelButton)
            } else {
                listOf(deleteButton, finishButton, cancelButton)
            }
            card.addView(settingsDialogButtonRow(activity, buttons))
            ninePatchSelectButton.setOnClickListener {
                openHighlightNinePatchPicker(activity, ninePatchInput)
            }
            setLightDefaultButton.setOnClickListener {
                settings.setReaderHighlightDefaultLightStyle(style.id)
                bumpReaderHighlightVersion()
                showToast("\u5df2\u8bbe\u4e3a\u6d45\u8272\u9ed8\u8ba4")
            }
            setDarkDefaultButton.setOnClickListener {
                settings.setReaderHighlightDefaultDarkStyle(style.id)
                bumpReaderHighlightVersion()
                showToast("\u5df2\u8bbe\u4e3a\u6df1\u8272\u9ed8\u8ba4")
            }
            finishButton.setOnClickListener {
                settings.setReaderHighlightStyle(
                    style.copy(
                        name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { style.name },
                        color = colorInput.text?.toString()?.trim().orEmpty(),
                        fontFamily = fontSelection,
                        css = sanitizeReaderHighlightCss(cssInput.text?.toString().orEmpty()),
                        ninePatchPath = ninePatchInput.text?.toString()?.trim().orEmpty(),
                    ),
                )
                bumpReaderHighlightVersion()
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                settings.removeReaderHighlightStyle(style.id)
                bumpReaderHighlightVersion()
                dialog.dismiss()
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX open highlight style dialog failed: ${it.stackTraceToString()}")
        }
    }
}

/**
 * 成书样式编辑弹窗。
 *
 * 与高亮样式弹窗同一套组件与顺序；差别是 CSS 按选择器分段填写，并用 WebView 做所见即所得预览。
 */

internal fun ReaMicroSettingsHook.exportReaderHighlightStyle(style: ReaderHighlightStyle) {        val activity = activityProvider() ?: return
    runCatching {
        val fileName = "reamicro_highlight_style_${safeDownloadName(style.name)}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
        writeTextToDownloads(activity, fileName, readerHighlightStyleExportJson(activity, style).toString(2))
        showToast("\u5df2\u5bfc\u51fa\u9ad8\u4eae\u6837\u5f0f\uff1a$fileName")
    }.onFailure {
        showToast("\u5bfc\u51fa\u9ad8\u4eae\u6837\u5f0f\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX export highlight style failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.readerHighlightStylePreview(
    context: Context,
    message: String,
    colors: SettingsDialogColors,
): TextView =
    ReaderHighlightPreviewTextView(context).apply {
        text = message
        textSize = 15f
        includeFontPadding = true
        setPadding(
            settingsDp(context, 12),
            settingsDp(context, 10),
            settingsDp(context, 12),
            settingsDp(context, 10),
        )
        background = settingsRoundedRect(colors.field, settingsDp(context, 8), colors.border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = settingsDp(context, 10) }
    }

internal fun ReaMicroSettingsHook.syncReaderHighlightStylePreview(
    preview: TextView,
    colorValue: String,
    css: String,
    ninePatchPath: String,
    ninePatchSlice: String,
    fontSelection: String,
    colors: SettingsDialogColors,
) {
    val cssValues = readerHighlightCssProperties(css)
    val textColor = parseSettingsColor(cssValues["color"].orEmpty())
        ?: parseSettingsColor(colorValue)
        ?: colors.title
        val backgroundColor = parseSettingsColor(cssValues["background-color"].orEmpty())
        ?: parseSettingsColor(cssValues["background"].orEmpty())
        ?: colors.field
    preview.setTextColor(textColor)
    preview.background = settingsRoundedRect(colors.field, settingsDp(preview.context, 8), colors.border)
    (preview as? ReaderHighlightPreviewTextView)?.setHighlightPreview(
        css = css,
        path = ninePatchPath,
        slice = ninePatchSlice.ifBlank { reedenNineSlice(css) },
        fallbackColor = backgroundColor,
        hasFallbackFill = cssValues.containsKey("background-color") || cssValues.containsKey("background"),
    )
    preview.textSize = 15f
    val baseTypeface = androidTypefaceForFontSelection(fontSelection) ?: Typeface.DEFAULT
    preview.typeface = Typeface.create(baseTypeface, Typeface.NORMAL)
    preview.paintFlags = preview.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
}

internal fun ReaMicroSettingsHook.readerHighlightCssProperties(css: String): Map<String, String> =
    css.split(';')
        .mapNotNull { part ->
            val index = part.indexOf(':')
            if (index <= 0) {
                null
            } else {
                val key = part.substring(0, index).trim().lowercase()
                val value = part.substring(index + 1).trim()
                if (isSupportedReaderHighlightCssProperty(key, value)) key to value else null
            }
        }
        .toMap()

internal fun ReaMicroSettingsHook.sanitizeReaderHighlightCss(css: String): String =
    css.split(';')
        .mapNotNull { part ->
            val index = part.indexOf(':')
            if (index <= 0) return@mapNotNull null
            val key = part.substring(0, index).trim().lowercase()
            val value = part.substring(index + 1).trim()
            if (key.isBlank() || value.isBlank() || !isSupportedReaderHighlightCssProperty(key, value)) {
                null
            } else {
                "$key: $value"
            }
        }
        .joinToString("; ")

internal fun ReaMicroSettingsHook.isSupportedReaderHighlightCssProperty(key: String, value: String): Boolean {
    if (value.isBlank()) return false
    if (value.contains("url(", ignoreCase = true)) return false
    return when (key) {
        "color", "background", "background-color", "border-color" ->
            parseSettingsColor(value) != null
        "background-size" ->
            isSupportedReaderHighlightBackgroundSize(value)
        "border" ->
            readerHighlightCssSizeRegex.containsMatchIn(value) || readerHighlightCssColorRegex.containsMatchIn(value)
        "font-size" ->
            isReaderHighlightFontSizeValue(value)
        "border-width", "border-radius",
        "padding-left", "padding-top", "padding-right", "padding-bottom",
        "margin-left", "margin-top", "margin-right", "margin-bottom" ->
            isReaderHighlightCssSizeValue(value)
        "padding", "margin" ->
            isReaderHighlightCssBoxValue(value)
        "reeden-background-nine-slice", "--reeden-background-nine-slice" ->
            Regex("""-?\d+(?:\.\d+)?""").findAll(value).count() >= 4
        else -> false
    }
}

internal fun ReaMicroSettingsHook.isReaderHighlightCssSizeValue(value: String): Boolean =
    value.trim().matches(Regex("""\d+(?:\.\d+)?(?:px|dp|em|rem)?""", RegexOption.IGNORE_CASE))

internal fun ReaMicroSettingsHook.isReaderHighlightFontSizeValue(value: String): Boolean =
    value.trim().matches(Regex("""\d+(?:\.\d+)?\s*(?:px|sp|em|rem|pt)?""", RegexOption.IGNORE_CASE))

internal fun ReaMicroSettingsHook.isReaderHighlightCssBoxValue(value: String): Boolean {
    val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return parts.size in 1..4 && parts.all(::isReaderHighlightCssSizeValue)
}

internal fun ReaMicroSettingsHook.isSupportedReaderHighlightBackgroundSize(value: String): Boolean {
    val normalized = value.trim().lowercase().replace(Regex("\\s+"), " ")
    return normalized == "100% 100%" || normalized == "stretch"
}

internal fun ReaMicroSettingsHook.openReaderHighlightStyleImportPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }
        activity.startActivityForResult(intent, HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        showToast("\u6253\u5f00\u9ad8\u4eae\u6837\u5f0f\u5bfc\u5165\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX open highlight style import failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.openHighlightNinePatchPicker(activity: Activity, input: EditText) {
    runCatching {
        pendingHighlightNinePatchInputRef = WeakReference(input)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/octet-stream", "*/*"))
        }
        activity.startActivityForResult(intent, HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        showToast("\u6253\u5f00\u56fe\u7247\u9009\u62e9\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX open highlight image picker failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importHighlightNinePatchFromUri(activity: Activity, uri: Uri) {
    runCatching {
        val target = copyHighlightNinePatchUri(activity, uri)
        pendingHighlightNinePatchInputRef?.get()?.setText(target.absolutePath)
        showToast("\u5df2\u9009\u62e9\u56fe\u7247\uff1a${target.name}")
    }.onFailure {
        showToast(it.message ?: "\u9009\u62e9\u56fe\u7247\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX import highlight image failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importReaderHighlightStyleFromUri(activity: Activity, uri: Uri) {
    runCatching {
        val bytes = activity.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: error("empty highlight style file")
        val imported = readerHighlightImportFromBytes(activity, bytes)
        imported.styles.forEach(settings::setReaderHighlightStyle)
        imported.rules.forEach(settings::setReaderHighlightRule)
        bumpReaderHighlightVersion()
        showToast(
            if (imported.reeden) {
                if (imported.styles.isEmpty() && imported.skippedDuplicates > 0) {
                    "\u5df2\u5b58\u5728 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.skippedDuplicates} \u4e2a"
                } else if (imported.skippedDuplicates > 0) {
                    "\u5df2\u5bfc\u5165 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.size} \u4e2a\uff0c\u8df3\u8fc7 ${imported.skippedDuplicates} \u4e2a\u91cd\u590d"
                } else {
                    "\u5df2\u5bfc\u5165 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.size} \u4e2a"
                }
            } else if (imported.styles.size == 1) {
                "\u5df2\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.first().name}"
            } else {
                "\u5df2\u5bfc\u5165 ${imported.styles.size} \u4e2a\u9ad8\u4eae\u6837\u5f0f"
            },
        )
    }.onFailure {
        showToast("\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f\u5931\u8d25")
        XposedBridge.log("$LOG_PREFIX import highlight style failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.readerHighlightImportFromBytes(activity: Activity, bytes: ByteArray): ReaderHighlightImport {
    if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 1.toByte()
    ) {
        return readerHighlightImportFromReedenRed(activity, bytes)
    }
    return ReaderHighlightImport(
        styles = listOf(readerHighlightStyleFromJson(activity, JSONObject(bytes.toString(Charsets.UTF_8)))),
        rules = emptyList(),
        reeden = false,
    )
}

internal fun ReaMicroSettingsHook.readerHighlightStyleExportJson(activity: Activity, style: ReaderHighlightStyle): JSONObject =
    JSONObject()
        .put("type", "reader_highlight_style")
        .put("version", 1)
        .put("style", readerHighlightStyleJson(style))

internal fun ReaMicroSettingsHook.readerHighlightStyleJson(style: ReaderHighlightStyle): JSONObject =
    JSONObject()
        .put("id", style.id)
        .put("name", style.name)
        .put("color", style.color)
        .put("fontFamily", style.fontFamily)
        .put("css", sanitizeReaderHighlightCss(style.css))
        .put("ninePatchPath", style.ninePatchPath)
        .put("ninePatchSlice", style.ninePatchSlice)
        .apply {
            highlightNinePatchJson(style.ninePatchPath)?.let { put("ninePatchFile", it) }
        }

internal fun ReaMicroSettingsHook.readerHighlightStyleFromJson(activity: Activity, root: JSONObject): ReaderHighlightStyle {
    val item = root.optJSONObject("style") ?: root
    val existingIds = settings.highlightSettings().styles.map { it.id }.toSet()
    val rawId = item.optString("id").takeIf { it.isNotBlank() } ?: uniqueReaderHighlightId("style")
    val id = if (rawId in existingIds || rawId == ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID) {
        uniqueReaderHighlightId("style")
    } else {
        rawId
    }
    return ReaderHighlightStyle(
        id = id,
        name = item.optString("name").ifBlank { "\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f" },
        color = item.optString("color").ifBlank { ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR },
        fontFamily = item.optString("fontFamily"),
        css = sanitizeReaderHighlightCss(item.optString("css")),
        ninePatchPath = restoreHighlightNinePatchFromJson(activity, item.optJSONObject("ninePatchFile"))
            ?: item.optString("ninePatchPath"),
        ninePatchSlice = item.optString("ninePatchSlice"),
    )
}

internal fun ReaMicroSettingsHook.readerHighlightImportFromReedenRed(activity: Activity, bytes: ByteArray): ReaderHighlightImport {
    val json = GZIPInputStream(bytes.copyOfRange(4, bytes.size).inputStream())
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
    val root = JSONObject(json)
    if (root.optString("type") != "highlightRule") error("not a Reeden highlight rule file")
    val data = root.optJSONArray("data") ?: JSONArray()
    val existingStyles = settings.highlightSettings().styles
    val existingIds = existingStyles.map { it.id }.toMutableSet()
    val existingKeys = existingStyles.mapNotNull(::readerHighlightStyleImportKey).toMutableSet()
    val imagePathByHash = HashMap<String, String>()
    val styles = ArrayList<ReaderHighlightStyle>()
    var skippedDuplicates = 0
    for (index in 0 until data.length()) {
        val item = data.optJSONObject(index) ?: continue
        val rawCss = item.optString("styleCssText")
        val css = sanitizeReaderHighlightCss(rawCss)
        val imageData = item.optString("backgroundImageData")
        if (css.isBlank() || imageData.isBlank()) continue
        val imageBytes = runCatching {
            GZIPInputStream(Base64.decode(imageData, Base64.DEFAULT).inputStream()).use { it.readBytes() }
        }.getOrNull() ?: continue
        val hash = md5Hex(imageBytes)
        val importKey = readerHighlightStyleImportKey(css, hash)
        if (!existingKeys.add(importKey)) {
            skippedDuplicates++
            continue
        }
        val imagePath = imagePathByHash.getOrPut(hash) {
            writeReaderHighlightImage(activity, imageBytes, "$hash.png").absolutePath
        }
        var id: String
        var suffix = 0
        do {
            id = "style_reeden_${System.currentTimeMillis()}_${index}_$suffix"
            suffix++
        } while (!existingIds.add(id))
        styles.add(
            ReaderHighlightStyle(
                id = id,
                name = item.optString("name").ifBlank { "Reeden \u9ad8\u4eae\u6837\u5f0f ${styles.size + 1}" },
                color = colorFromReedenCss(rawCss),
                fontFamily = "",
                css = css,
                ninePatchPath = imagePath,
                ninePatchSlice = reedenNineSlice(css),
            ),
        )
    }
    if (styles.isEmpty() && skippedDuplicates == 0) error("no Reeden highlight styles found")
    return ReaderHighlightImport(styles = styles, rules = emptyList(), reeden = true, skippedDuplicates = skippedDuplicates)
}

internal fun ReaMicroSettingsHook.readerHighlightStyleImportKey(style: ReaderHighlightStyle): String? {
    val path = style.ninePatchPath.trim()
    val css = sanitizeReaderHighlightCss(style.css)
    if (css.isBlank() || path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val hash = runCatching { md5Hex(file.readBytes()) }.getOrNull() ?: return null
    return readerHighlightStyleImportKey(css, hash)
}

internal fun ReaMicroSettingsHook.readerHighlightStyleImportKey(css: String, imageHash: String): String =
    "${sanitizeReaderHighlightCss(css)}|${imageHash.uppercase()}"

internal fun ReaMicroSettingsHook.copyHighlightNinePatchUri(activity: Activity, uri: Uri): File {
    val rawName = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
    if (!rawName.endsWith(".png", ignoreCase = true)) {
        error("\u8bf7\u9009\u62e9 PNG \u56fe\u7247")
    }
    val target = if (rawName.endsWith(".9.png", ignoreCase = true)) {
        uniqueHighlightNinePatchFile(activity, sanitizeNinePatchFileName(rawName))
    } else {
        uniqueHighlightImageFile(activity, sanitizeHighlightImageFileName(rawName))
    }
    activity.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().buffered().use { output -> input.copyTo(output) }
    } ?: error("\u65e0\u6cd5\u8bfb\u53d6\u56fe\u7247")
    return target
}

internal fun ReaMicroSettingsHook.highlightNinePatchJson(path: String): JSONObject? {
    val file = File(path)
    if (!file.isFile) return null
    return JSONObject()
        .put("name", file.name)
        .put("mime", "image/png")
        .put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
}

internal fun ReaMicroSettingsHook.restoreHighlightNinePatchFromJson(activity: Activity, item: JSONObject?): String? {
    item ?: return null
    val encoded = item.optString("base64")
    if (encoded.isBlank()) return null
    val name = sanitizeHighlightImageFileName(item.optString("name").ifBlank { "highlight.png" })
    val target = uniqueHighlightImageFile(activity, name)
    target.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
    return target.absolutePath
}

internal fun ReaMicroSettingsHook.uniqueHighlightNinePatchFile(activity: Activity, displayName: String): File {
    val dir = File(activity.filesDir, "reader_highlight_nine_patch").apply { mkdirs() }
    val base = displayName.removeSuffix(".9.png").ifBlank { "highlight" }
    var target = File(dir, "$base.9.png")
    var index = 1
    while (target.exists()) {
        target = File(dir, "${base}_$index.9.png")
        index++
    }
    return target
}

internal fun ReaMicroSettingsHook.writeReaderHighlightImage(activity: Activity, bytes: ByteArray, displayName: String): File =
    uniqueHighlightImageFile(activity, sanitizeHighlightImageFileName(displayName)).apply {
        writeBytes(bytes)
    }

internal fun ReaMicroSettingsHook.sanitizeHighlightImageFileName(name: String): String {
    val cleaned = safeDownloadName(name)
        .removeSuffix(".png")
        .trim('.', ' ')
        .ifBlank { "highlight" }
    return "$cleaned.png"
}

internal fun ReaMicroSettingsHook.uniqueHighlightImageFile(activity: Activity, displayName: String): File {
    val dir = File(activity.filesDir, "reader_highlight_nine_patch").apply { mkdirs() }
    val base = displayName.removeSuffix(".png").ifBlank { "highlight" }
    var target = File(dir, "$base.png")
    var index = 1
    while (target.exists()) {
        target = File(dir, "${base}_$index.png")
        index++
    }
    return target
}

internal fun ReaMicroSettingsHook.openReaderHighlightRuleDialog(rule: ReaderHighlightRule) {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val highlight = settings.highlightSettings()
            val styles = highlight.styles
            val nameInput = settingsDialogInput(activity, "\u89c4\u5219\u540d\u79f0", singleLine = true, colors = colors).apply {
                setText(rule.name)
            }
            val builtInRule = isDefaultReaderHighlightRule(rule.id)
            var selectedType = if (builtInRule) {
                rule.type
            } else {
                when (rule.type) {
                    ReaderHighlightRuleType.FixedText,
                    ReaderHighlightRuleType.Regex -> rule.type
                    else -> ReaderHighlightRuleType.FixedText
                }
            }
            var selectedStyleId = rule.styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
            var selectedDarkStyleId = rule.darkStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
            val typeStatus = settingsDialogStatus(activity, "\u7c7b\u578b\uff1a${highlightRuleTypeTitle(rule)}", colors)
            val patternInput = settingsDialogInput(activity, "\u5339\u914d\u5185\u5bb9", singleLine = false, colors = colors).apply {
                setText(rule.pattern)
                minLines = 2
            }
            lateinit var syncStyleSelection: () -> Unit
            val lightStyleStatus = settingsDialogChoiceRow(
                activity,
                "",
                colors,
            ) {
                openReaderHighlightStyleSelectionDialog(
                    activity = activity,
                    title = "\u9009\u62e9\u6d45\u8272\u6837\u5f0f",
                    styles = styles,
                    currentStyleId = selectedStyleId,
                    colors = colors,
                    defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
                    defaultLabel = "\u6d45\u8272\u9ed8\u8ba4",
                ) { styleId ->
                    selectedStyleId = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
                    syncStyleSelection.invoke()
                }
            }
            val darkStyleStatus = settingsDialogChoiceRow(
                activity,
                "",
                colors,
            ) {
                openReaderHighlightStyleSelectionDialog(
                    activity = activity,
                    title = "\u9009\u62e9\u6df1\u8272\u6837\u5f0f",
                    styles = styles,
                    currentStyleId = selectedDarkStyleId,
                    colors = colors,
                    defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
                    defaultLabel = "\u6df1\u8272\u9ed8\u8ba4",
                ) { styleId ->
                    selectedDarkStyleId = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
                    syncStyleSelection.invoke()
                }
            }
            val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
            val deleteButton = settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
            val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
            fun syncTypeSelection() {
                typeStatus.text = "\u7c7b\u578b\uff1a${highlightRuleTypeTitle(rule.copy(type = selectedType))}"
                val needsPattern = selectedType == ReaderHighlightRuleType.FixedText ||
                    selectedType == ReaderHighlightRuleType.Regex
                patternInput.visibility = if (needsPattern) View.VISIBLE else View.GONE
                patternInput.hint = when (selectedType) {
                    ReaderHighlightRuleType.FixedText -> "\u56fa\u5b9a\u6587\u672c\uff0c\u4f8b\u5982\uff1a\u91cd\u8981"
                    ReaderHighlightRuleType.Regex -> "\u6b63\u5219\u8868\u8fbe\u5f0f\uff0c\u4f8b\u5982\uff1a\\d{4}-\\d{2}-\\d{2}"
                    else -> "\u5339\u914d\u5185\u5bb9"
                }
            }
            syncStyleSelection = {
                lightStyleStatus.text = "\u6d45\u8272\u6837\u5f0f\uff1a${readerHighlightLightStyleLabel(highlight, selectedStyleId)}"
                darkStyleStatus.text = "\u6df1\u8272\u6837\u5f0f\uff1a${readerHighlightDarkStyleLabel(highlight, selectedDarkStyleId)}"
            }
            card.addView(settingsDialogTitle(activity, "\u9ad8\u4eae\u89c4\u5219", colors))
            card.addView(settingsDialogHint(activity, "${highlightRuleTypeTitle(rule)}\uff1a${highlightRuleDescription(rule)}", colors))
            if (builtInRule) {
                card.addView(settingsDialogHint(activity, "\u5185\u7f6e\u89c4\u5219\u4ec5\u652f\u6301\u5207\u6362\u6837\u5f0f\uff0c\u5339\u914d\u6587\u672c\u7531\u6a21\u5757\u5185\u7f6e\u5904\u7406\u3002", colors))
            } else {
                card.addView(nameInput)
                card.addView(typeStatus)
                card.addView(
                    settingsDialogChoiceRow(activity, "\u56fa\u5b9a\u6587\u672c", colors) {
                        selectedType = ReaderHighlightRuleType.FixedText
                        syncTypeSelection()
                    },
                )
                card.addView(
                    settingsDialogChoiceRow(activity, "\u6b63\u5219", colors) {
                        selectedType = ReaderHighlightRuleType.Regex
                        syncTypeSelection()
                    },
                )
                card.addView(patternInput)
                syncTypeSelection()
            }
            syncStyleSelection.invoke()
            card.addView(lightStyleStatus)
            card.addView(darkStyleStatus)
            val buttons = if (builtInRule) {
                listOf(finishButton, cancelButton)
            } else {
                listOf(deleteButton, finishButton, cancelButton)
            }
            card.addView(settingsDialogButtonRow(activity, buttons))
            finishButton.setOnClickListener {
                settings.setReaderHighlightRule(
                    rule.copy(
                        name = if (builtInRule) {
                            rule.name
                        } else {
                            nameInput.text?.toString()?.trim().orEmpty().ifBlank { rule.name }
                        },
                        type = selectedType,
                        styleId = selectedStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID },
                        darkStyleId = selectedDarkStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID },
                        pattern = if (
                            selectedType == ReaderHighlightRuleType.FixedText ||
                            selectedType == ReaderHighlightRuleType.Regex
                        ) {
                            patternInput.text?.toString()?.trim().orEmpty()
                        } else {
                            ""
                        },
                    ),
                )
                bumpReaderHighlightVersion()
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                settings.removeReaderHighlightRule(rule.id)
                bumpReaderHighlightVersion()
                dialog.dismiss()
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX open highlight rule dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.openReaderHighlightStyleSelectionDialog(
    activity: Activity,
    title: String,
    styles: List<ReaderHighlightStyle>,
    currentStyleId: String,
    colors: SettingsDialogColors,
    defaultReferenceId: String,
    defaultLabel: String,
    onSelected: (String) -> Unit,
) {
    val dialog = Dialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, title, colors))
    val highlight = settings.highlightSettings()
    val defaultStyleName = highlight.styleById(defaultReferenceId).name
    card.addView(
        settingsDialogChoiceRow(
            activity,
            if (currentStyleId == defaultReferenceId) {
                "$defaultLabel\uff08$defaultStyleName\uff09  \u5f53\u524d"
            } else {
                "$defaultLabel\uff08$defaultStyleName\uff09"
            },
            colors,
        ) {
            onSelected(defaultReferenceId)
            dialog.dismiss()
        },
    )
    styles.forEach { style ->
        val selected = style.id == currentStyleId
        card.addView(
            settingsDialogChoiceRow(
                activity,
                if (selected) "${style.name}  \u5f53\u524d" else style.name,
                colors,
            ) {
                onSelected(style.id)
                dialog.dismiss()
            },
        )
    }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.92f)
}

internal fun ReaMicroSettingsHook.dialogueHighlightFontSummary(value: String): String {
    if (value.isNotBlank()) return displayFontName(value)
    val global = settings.fontSettings().globalFamily
    return if (global.isBlank()) "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53" else "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53\uff1a${displayFontName(global)}"
}

internal fun ReaMicroSettingsHook.dialogueHighlightColorSummary(value: String): String =
    READER_HIGHLIGHT_COLOR_OPTIONS.firstOrNull { it.value.equals(value, ignoreCase = true) }?.title ?: value

internal fun ReaMicroSettingsHook.readerHighlightSettingsSummary(): String {
    val highlight = settings.highlightSettings()
    return "${highlight.styles.size} \u4e2a\u6837\u5f0f / ${highlight.rules.count { it.enabled }} \u6761\u89c4\u5219"
}

internal fun ReaMicroSettingsHook.readerHighlightBookGroups(highlight: ReaderHighlightSettingsSnapshot): List<ReaderHighlightBookGroupRow> =
    buildList {
        highlight.bookRuleGroups().forEach { group ->
            add(
                ReaderHighlightBookGroupRow(
                    bookKey = group.bookKey,
                    bookTitle = displayReaderBookTitle(group.bookKey, group.bookTitle),
                    subtitle = "${group.enabledCount} / ${group.totalCount} \u6761\u672c\u4e66\u89c4\u5219",
                ),
            )
        }
    }.sortedBy { it.bookTitle }

internal fun ReaMicroSettingsHook.highlightStyleSummary(style: ReaderHighlightStyle): String =
    listOfNotNull(
        dialogueHighlightColorSummary(style.color),
        dialogueHighlightFontSummary(style.fontFamily),
        style.css.takeIf { it.isNotBlank() }?.let { "CSS" },
        style.ninePatchPath.takeIf { it.isNotBlank() }?.let {
            if (style.ninePatchSlice.isNotBlank()) "Reeden \u4e5d\u5bab\u683c\u56fe" else "\u56fe\u7247\u80cc\u666f"
        },
    ).joinToString(" / ")

internal fun ReaMicroSettingsHook.readerHighlightStyleDefaultTrailing(
    highlight: ReaderHighlightSettingsSnapshot,
    style: ReaderHighlightStyle,
): String? =
    buildList {
        if (style.id == highlight.defaultLightStyleId) add("\u6d45\u8272\u9ed8\u8ba4")
        if (style.id == highlight.defaultDarkStyleId) add("\u6df1\u8272\u9ed8\u8ba4")
    }.takeIf { it.isNotEmpty() }?.joinToString(" / ")

internal fun ReaMicroSettingsHook.highlightRuleTypeTitle(rule: ReaderHighlightRule): String =
    when (rule.type) {
        ReaderHighlightRuleType.DoubleQuoteDialogue -> "\u53cc\u5f15\u53f7\u5bf9\u8bdd"
        ReaderHighlightRuleType.SingleQuotePhrase -> "\u5355\u5f15\u53f7\u8bcd\u7ec4"
        ReaderHighlightRuleType.FixedText -> "\u56fa\u5b9a\u6587\u672c"
        ReaderHighlightRuleType.Regex -> "\u6b63\u5219"
    }

internal fun ReaMicroSettingsHook.highlightRuleDescription(rule: ReaderHighlightRule): String =
    when (rule.type) {
        ReaderHighlightRuleType.DoubleQuoteDialogue -> "\u5339\u914d\u540c\u6bb5\u5185\u7684\u201c\u2026\u201d\u300c\u2026\u300d\u300e\u2026\u300f\u548c \"...\""
        ReaderHighlightRuleType.SingleQuotePhrase -> "\u5339\u914d\u540c\u6bb5\u5185\u7684\u2018\u2026\u2019 \u548c '...'"
        ReaderHighlightRuleType.FixedText -> "\u6309\u5b57\u9762\u91cf\u5339\u914d\u56fa\u5b9a\u6587\u672c"
        ReaderHighlightRuleType.Regex -> "\u6309\u6b63\u5219\u8868\u8fbe\u5f0f\u5339\u914d\u6587\u672c"
    }

internal fun ReaMicroSettingsHook.highlightRuleSummary(rule: ReaderHighlightRule): String {
    val base = highlightRuleTypeTitle(rule)
    val pattern = rule.pattern.takeIf {
        rule.type == ReaderHighlightRuleType.FixedText || rule.type == ReaderHighlightRuleType.Regex
    }?.compactOnlineSourceLine()
    return if (pattern.isNullOrBlank()) base else "$base: $pattern"
}

internal fun ReaMicroSettingsHook.highlightRuleStyleSummary(
    highlight: ReaderHighlightSettingsSnapshot,
    rule: ReaderHighlightRule,
): String {
    val light = readerHighlightLightStyleLabel(highlight, rule.styleId)
    val dark = readerHighlightDarkStyleLabel(highlight, rule.darkStyleId)
    return if (dark == light) {
        "\u6d45/\u6df1 $light"
    } else {
        "\u6d45 $light / \u6df1 $dark"
    }
}

internal fun ReaMicroSettingsHook.readerHighlightLightStyleLabel(
    highlight: ReaderHighlightSettingsSnapshot,
    styleId: String,
): String {
    val id = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
    return if (id == ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID) {
        "\u6d45\u8272\u9ed8\u8ba4\uff08${highlight.defaultLightStyle().name}\uff09"
    } else {
        highlight.styleById(id).name
    }
}

internal fun ReaMicroSettingsHook.readerHighlightDarkStyleLabel(
    highlight: ReaderHighlightSettingsSnapshot,
    styleId: String,
): String {
    val id = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
    return if (id == ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID) {
        "\u6df1\u8272\u9ed8\u8ba4\uff08${highlight.defaultDarkStyle().name}\uff09"
    } else {
        highlight.styleById(id).name
    }
}

internal fun ReaMicroSettingsHook.newReaderHighlightStyle(): ReaderHighlightStyle {
    val index = settings.highlightSettings().styles.size + 1
    return ReaderHighlightStyle(
        id = uniqueReaderHighlightId("style"),
        name = "\u9ad8\u4eae\u6837\u5f0f $index",
        color = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
    )
}

internal fun ReaMicroSettingsHook.newReaderHighlightRule(bookKey: String = "", bookTitle: String = ""): ReaderHighlightRule {
    val rules = settings.highlightSettings().rules
    val index = rules.count { it.bookKey == bookKey } + 1
    return ReaderHighlightRule(
        id = uniqueReaderHighlightId("rule"),
        name = if (bookKey.isBlank()) "\u9ad8\u4eae\u89c4\u5219 $index" else "\u672c\u4e66\u89c4\u5219 $index",
        type = ReaderHighlightRuleType.FixedText,
        styleId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
        darkStyleId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
        bookKey = bookKey,
        bookTitle = bookTitle,
    )
}

internal fun ReaMicroSettingsHook.uniqueReaderHighlightId(prefix: String): String {
    val highlight = settings.highlightSettings()
    val existing = highlight.styles.map { it.id }.toSet() + highlight.rules.map { it.id }.toSet()
    var id = "${prefix}_${System.currentTimeMillis()}"
    var suffix = 1
    while (id in existing) {
        id = "${prefix}_${System.currentTimeMillis()}_$suffix"
        suffix++
    }
    return id
}

internal fun ReaMicroSettingsHook.isDefaultReaderHighlightRule(ruleId: String): Boolean =
    ruleId == ModuleSettings.DEFAULT_READER_DOUBLE_QUOTE_RULE_ID ||
        ruleId == ModuleSettings.DEFAULT_READER_SINGLE_QUOTE_RULE_ID

internal fun ReaMicroSettingsHook.importExternalHighlight(activity: Activity, bytes: ByteArray) {
    runCatching {
        val imported = readerHighlightImportFromBytes(activity, bytes)
        imported.styles.forEach(settings::setReaderHighlightStyle)
        imported.rules.forEach(settings::setReaderHighlightRule)
        bumpReaderHighlightVersion()
        showToast(
            if (imported.reeden) {
                "已导入 Reeden 高亮样式：${imported.styles.size} 个"
            } else if (imported.styles.size == 1) {
                "已导入高亮样式：${imported.styles.first().name}"
            } else {
                "已导入 ${imported.styles.size} 个高亮样式"
            },
        )
    }.onFailure {
        showToast("导入高亮样式失败")
        XposedBridge.log("$LOG_PREFIX external highlight import failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.readerHighlightSheetModifier(composer: Any): Any {
    val height = method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(
        null,
        modifierInstance(),
        udp(READER_RULE_SHEET_HEIGHT_DP),
    )
    return method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(
        null,
        height,
        colorScheme(composer).longMethod("getSurface"),
        null,
        2,
        null,
    )
}

internal fun ReaMicroSettingsHook.readerHighlightSheetListModifier(): Any {
    val filled = method(SIZE_KT_CLASS, FILL_MAX_SIZE_DEFAULT_METHOD, 4).invoke(
        null,
        modifierInstance(),
        0f,
        1,
        null,
    )
    return method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5).invoke(
        null,
        filled,
        udp(18),
        0f,
        2,
        null,
    )
}

internal fun ReaMicroSettingsHook.readerHighlightVersionState(): Any {
    readerHighlightVersionUiState?.let { return it }
    return mutableState(0).also { readerHighlightVersionUiState = it }
}

internal fun ReaMicroSettingsHook.readerHighlightVersionValue(): Int =
    (readerHighlightVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpReaderHighlightVersion() {
    val state = readerHighlightVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}

// sheet 子页面导航状态：0=规则列表，1=高亮样式列表。
internal fun ReaMicroSettingsHook.readerHighlightSheetSubPageState(): Any {
    readerHighlightSheetSubPageUiState?.let { return it }
    return mutableState(0).also { readerHighlightSheetSubPageUiState = it }
}

internal fun ReaMicroSettingsHook.readerHighlightSheetSubPageValue(): Int =
    (readerHighlightSheetSubPageState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.setReaderHighlightSheetSubPage(page: Int) {
    val state = readerHighlightSheetSubPageState()
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, page)
}

// "补全计划"整页导航状态：0=原生高亮页，1=规则页，2=高亮样式页。
internal fun ReaMicroSettingsHook.readerHighlightScreenPlanState(): Any {
    readerHighlightScreenPlanUiState?.let { return it }
    return mutableState(0).also { readerHighlightScreenPlanUiState = it }
}

internal fun ReaMicroSettingsHook.readerHighlightScreenPlanValue(): Int =
    (readerHighlightScreenPlanState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.setReaderHighlightScreenPlan(page: Int) {
    val state = readerHighlightScreenPlanState()
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, page)
}
