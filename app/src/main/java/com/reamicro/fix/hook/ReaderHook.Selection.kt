package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread



import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiDictionaryPreset
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiApiTestResult
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.canEditReaderSelection(): Boolean =
    settingsProvider().canEditReaderSelection

internal fun ReaderHook.canShowReaderDictionary(): Boolean =
    settingsProvider().canShowReaderDictionary

internal fun ReaderHook.selectionMenuMaxItemsPerRow(actionCount: Int): Int? {
    if (settingsProvider().canUseCompactReaderSelectionMenu) {
        return if (actionCount > 6) 6 else null
    }
    return when {
        actionCount <= 4 -> null
        actionCount <= 6 -> 3
        else -> 4
    }
}

internal fun ReaderHook.renderWrappedSelectionMenu(
    actions: List<Any>,
    tint: Long,
    composer: Any,
    maxPerRow: Int,
    bubbleArrowOnTop: Boolean?,
) {
    val nativeUi = HostNativeUi(classLoader)
    val cellWidth = selectionMenuCellWidthDp(actions)
    // Native items keep at least 56dp in ordinary mode; reduce columns before sizing
    // the bubble so compact actions and long labels cannot overflow its 320dp limit.
    val columns = minOf(maxPerRow.coerceAtLeast(1), (304 / cellWidth).coerceAtLeast(1))
    val rows = actions.chunked(columns)
    val contentWidth = columns * cellWidth + 16
    val contentModifier = nativeUi.padding(
        // 总宽已经包含两侧 8dp，先限宽再内缩，避免 padding 把右侧再撑大 16dp。
        nativeUi.width(nativeUi.modifier(), nativeUi.dp(contentWidth)),
        // 宿主气泡形状的箭头占 10dp；保留原 ce0 内容的单侧避让，不能让第二行落进裁剪区。
        8f, 6f + if (bubbleArrowOnTop == true) 10f else 0f,
        8f, 6f + if (bubbleArrowOnTop == false) 10f else 0f,
    )
    val content = nativeUi.lambda(
        actions.fold(0x524d4234) { key, action -> key * 31 + selectionSymbols().title(action).hashCode() },
        functionProxy("ReaderSelectionB4Column", "kotlin.jvm.functions.Function3") { args ->
            val columnComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            rows.forEachIndexed { rowIndex, rowActions ->
                val rowContent = nativeUi.lambda(
                    0x524d5200 + rowIndex,
                    functionProxy("ReaderSelectionB4Row$rowIndex", KOTLIN_FUNCTION3_CLASS) { rowArgs ->
                        val rowComposer = rowArgs?.getOrNull(1) ?: return@functionProxy targetUnit()
                        rowActions.forEach { action ->
                            renderSelectionGridItem(nativeUi, action, tint, cellWidth, rowComposer)
                        }
                        targetUnit()
                    },
                )
                nativeUi.row(
                    nativeUi.width(
                        nativeUi.modifier(),
                        nativeUi.dp(columns * cellWidth),
                    ),
                    rowContent,
                    columnComposer,
                    null,
                    nativeUi.centerVertically(),
                )
            }
            targetUnit()
        },
    )
    nativeUi.column(
        contentModifier,
        nativeUi.spacedBy(2f),
        nativeUi.start(),
        content,
        composer,
    )
}

private fun ReaderHook.renderSelectionGridItem(
    nativeUi: HostNativeUi,
    action: Any,
    tint: Long,
    cellWidth: Int,
    composer: Any,
) {
    val symbols = selectionSymbols()
    val title = symbols.title(action)
    val icon = symbols.icon(action)
    val callback = symbols.callback(action)
    // Reuse the host item, including its typography, compact icon sizing and tap feedback.
    symbols.method("item").invoke(null, title, icon, tint, callback,
        nativeUi.width(nativeUi.modifier(), nativeUi.dp(cellWidth)), composer, 0)
}

private fun ReaderHook.selectionMenuCellWidthDp(actions: List<Any>): Int {
    val maxTitleWidth = actions.maxOfOrNull { action ->
        selectionSymbols().title(action).sumOf { character ->
            if (character.code <= 0x7f) 8 else 14
        }
    } ?: 0
    if (!settingsProvider().canUseCompactReaderSelectionMenu) {
        return (maxTitleWidth + 18).coerceIn(56, 100)
    }
    return (maxTitleWidth + 42).coerceIn(56, 112)
}

internal fun ReaderHook.compactSelectionMenuActions(actions: List<Any>): List<Any> {
    val compact = actions.map { action -> compactSelectionMenuAction(action) ?: action }
    return if (compact.indices.any { index -> compact[index] !== actions[index] }) {
        ArrayList(compact)
    } else {
        actions
    }
}

internal fun ReaderHook.compactSelectionMenuAction(action: Any): Any? {
    val title = selectionSymbols().title(action)
    if (title.isBlank()) return null
    val icon = selectionSymbols().icon(action) ?: return null
    val callback = selectionMenuActionCallback(action) ?: return null
    return createSelectionMenuAction("", icon, callback, "compact native selection action")
}

internal fun ReaderHook.selectionMenuActionCallback(action: Any): Any? =
    selectionSymbols().callback(action)

private fun selectionSymbols() = requireNotNull(com.reamicro.fix.core.HostSelectionBindings.current) {
    "Selection contract unavailable"
}

internal fun ReaderHook.createNativeDictionaryAction(icon: Any): Any? =
    createSelectionMenuAction(
        if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u8bcd\u5178",
        icon,
        nativeFunction0 {
            openNativeSelectionDictionary()
        },
        "create native dictionary action",
    )

internal fun ReaderHook.createSelectionMenuAction(title: String, icon: Any, callback: Any, logLabel: String): Any? =
    runCatching {
        selectionSymbols().action(title, icon, callback)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX $logLabel failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.openNativeSelectionEditor() = openAnchoredSelectionEditor()

internal fun ReaderHook.openNativeSelectionDictionary() {
    val activity = activityProvider() ?: return
    if (!canShowReaderDictionary()) return
    val quote = currentNativeSelectionPayload().quote
    if (quote.isBlank()) {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
        return
    }
    val config = AiApiStore.dictionaryApi(activity.applicationContext)
    if (config == null) {
        Toast.makeText(activity, "\u8bf7\u5148\u5728 API \u914d\u7f6e\u4e2d\u6dfb\u52a0\u6216\u9009\u62e9 API", Toast.LENGTH_SHORT).show()
        return
    }
    val settings = AiApiStore.dictionarySettings(activity.applicationContext)
    val initialPreset = AiApiStore.dictionaryPreset(activity.applicationContext, settings.presetId)
    activity.runOnModuleUiThread {
        val handle = showDictionaryDialog(activity, quote, config, initialPreset) { dialogHandle, selectedPreset ->
            val latestSettings = AiApiStore.dictionarySettings(activity.applicationContext)
            if (!latestSettings.singleUsePreset) {
                AiApiStore.setDictionaryPresetId(activity.applicationContext, selectedPreset.id)
            }
            requestDictionaryPreset(activity, config, quote, selectedPreset, dialogHandle)
        }
        requestDictionaryPreset(activity, config, quote, initialPreset, handle)
    }
}

internal fun ReaderHook.requestDictionaryPreset(
    activity: Activity,
    config: AiApiConfig,
    quote: String,
    preset: AiDictionaryPreset,
    handle: DictionaryDialogHandle,
) {
    val requestId = ++handle.requestId
    handle.currentPresetId = preset.id
    handle.footerLabel.text = preset.name
    handle.body.text = "\u6b63\u5728\u89e3\u6790..."
    handle.body.setTextColor(handle.colors.primaryText)
    com.reamicro.fix.reload.ModuleResources.thread({
        val result = runCatching { AiApiStore.dictionary(activity.applicationContext, config, quote, preset) }
            .onFailure { HookRuntime.log("$LOG_PREFIX dictionary request failed: ${it.stackTraceToString()}") }
            .getOrElse { error -> AiApiTestResult(false, error.message ?: error.javaClass.simpleName) }
        activity.runOnModuleUiThread {
            if (!activity.isFinishing &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed) &&
                requestId == handle.requestId
            ) {
                updateDictionaryDialog(handle, result.success, result.message)
            }
        }
    }, "ReaMicroDictionary").start()
}

internal fun ReaderHook.currentNativeSelectionPayload(): NativeSelectionPayload {
    val controller = currentSelectionControllerRef?.get()
    val symbols = selectionSymbols()
    val payload = controller?.takeIf { symbols.type("controller").isInstance(it) }?.let { symbols.method("payload").invoke(it) }
    fun text(property: String): String = payload?.let { symbols.field("payload.$property").get(it) as? String }.orEmpty()
    val quote = text("quote").ifBlank { controller?.let { symbols.method("text").invoke(it) as? String }.orEmpty() }
    return NativeSelectionPayload(controller = controller, quote = quote.trim(), startCfi = text("startCfi"), endCfi = text("endCfi"))
}

internal fun ReaderHook.contentDomPlainText(contentDom: Any?): String =
    runCatching {
        contentDom ?: return@runCatching ""
        requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current).text(contentDom)
    }.getOrDefault("")

internal fun ReaderHook.selectionOffsetInDocument(text: String, quote: String): Int {
    val exact = text.indexOf(quote)
    if (exact >= 0) return exact
    val compactQuote = quote.replace(Regex("\\s+"), " ").trim()
    if (compactQuote.length >= 12) {
        val prefix = compactQuote.take(32)
        val prefixOffset = text.indexOf(prefix)
        if (prefixOffset >= 0) return prefixOffset
    }
    val shortPrefix = quote.take(16).trim()
    if (shortPrefix.length >= 6) {
        val shortOffset = text.indexOf(shortPrefix)
        if (shortOffset >= 0) return shortOffset
    }
    return -1
}

internal fun ReaderHook.showSelectionEditDialog(
    activity: Activity, text: String, fonts: ReaderSelectionEditorFonts,
    onSave: (String, ReaderSelectionEditDialog) -> Unit,
): ReaderSelectionEditDialog = ReaderThoughtSelectionEditor(activity, text, fonts, onSave).also { it.show() }.handle

internal fun ReaderHook.showDictionaryDialog(
    activity: Activity,
    selectedText: String,
    config: AiApiConfig,
    initialPreset: AiDictionaryPreset,
    onPresetSelected: (DictionaryDialogHandle, AiDictionaryPreset) -> Unit,
): DictionaryDialogHandle {
    val colors = DialogColors(activity)
    val dialog = Dialog(activity)
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()
    val title = TextView(activity).apply {
        text = selectedText
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.primaryText)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val body = TextView(activity).apply {
        text = "\u6b63\u5728\u89e3\u6790..."
        textSize = 16f
        setLineSpacing(dp(3).toFloat(), 1.0f)
        setTextColor(colors.primaryText)
        setTextIsSelectable(true)
    }
    val footerLabel = TextView(activity).apply {
        text = initialPreset.name
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.accent)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(6), dp(12), dp(6))
    }
    val model = TextView(activity).apply {
        text = config.model
        textSize = 13f
        setTextColor(colors.secondaryText)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(14))
        background = GradientDrawable().apply {
            setColor(colors.cardBackground)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), colors.stroke)
        }
        addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        addView(ScrollView(activity).apply {
            setPadding(0, dp(12), 0, dp(10))
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(168),
        ))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(footerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(model, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCanceledOnTouchOutside(true)
    com.reamicro.fix.ui.DialogWindowStyle.show(dialog, activity, root, Gravity.BOTTOM)
    val handle = DictionaryDialogHandle(dialog, body, footerLabel, colors, initialPreset.id)
    footerLabel.setOnClickListener {
        showDictionaryPresetPicker(activity, handle) { preset ->
            onPresetSelected(handle, preset)
        }
    }
    return handle
}

internal fun ReaderHook.updateDictionaryDialog(handle: DictionaryDialogHandle, success: Boolean, message: String) {
    if (!handle.dialog.isShowing) return
    handle.body.text = message.ifBlank { "\u672a\u83b7\u53d6\u5230\u8bcd\u5178\u7ed3\u679c" }
    handle.body.setTextColor(if (success) handle.colors.primaryText else handle.colors.accent)
}

internal fun ReaderHook.showDictionaryPresetPicker(
    activity: Activity,
    handle: DictionaryDialogHandle,
    onSelected: (AiDictionaryPreset) -> Unit,
) {
    val presets = AiApiStore.dictionaryPresets(activity.applicationContext)
    if (presets.isEmpty()) return
    val colors = handle.colors
    val dialog = Dialog(activity)
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()
    val currentPresetId = handle.currentPresetId
    val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable().apply {
            setColor(colors.cardBackground)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), colors.stroke)
        }
    }
    presets.forEach { preset ->
        list.addView(TextView(activity).apply {
            val selected = preset.id == currentPresetId
            text = if (selected) "\u2713 ${preset.name}" else preset.name
            textSize = 16f
            setTextColor(if (selected) colors.accent else colors.primaryText)
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                dialog.dismiss()
                if (handle.dialog.isShowing && preset.id != currentPresetId) {
                    onSelected(preset)
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCanceledOnTouchOutside(true)
    com.reamicro.fix.ui.DialogWindowStyle.show(dialog, activity, root, Gravity.BOTTOM)
}
