package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import com.reamicro.fix.hook.reader.*

// 阅读页划词菜单簇。
//
// 精简选择菜单、词典与 AI 释义、划词朗读。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.canEditReaderSelection(): Boolean =
    settingsProvider().canEditReaderSelection

internal fun ReaderHook.canShowReaderDictionary(): Boolean =
    settingsProvider().canShowReaderDictionary

internal fun ReaderHook.restoreTranslateFlipStyleIfScrollCrashed(session: Any, source: String) {
    if (!isPreviousScrollCrashPending()) return
    Thread {
        runCatching {
            forceTranslateFlipStyle(session)
            clearScrollCrashPending("fallback updated by $source")
            val activity = activityProvider()
            activity?.runOnUiThread {
                Toast.makeText(activity, "\u5df2\u81ea\u52a8\u5207\u6362\u4e3a\u5e73\u79fb\u7ffb\u9875", Toast.LENGTH_SHORT).show()
            }
            XposedBridge.log("$LOG_PREFIX scroll crash fallback switched flip_style to translate from $source")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX scroll crash fallback update failed from $source: ${it.stackTraceToString()}")
        }
    }.apply {
        name = "ReaMicroScrollCrashFallback"
        isDaemon = true
        start()
    }
}

internal fun ReaderHook.forceTranslateFlipStyle(session: Any) {
    val prefKeysClass = classLoader.loadClass(PREF_KEYS_CLASS)
    val prefKeys = prefKeysClass.getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)
    val flipStyleKey = prefKeysClass.methods.first {
        it.name == "getFLIP_STYLE" && it.parameterTypes.isEmpty()
    }.invoke(prefKeys)
    val method = session.javaClass.methods.first {
        it.name == "update" && it.parameterTypes.size == 3
    }.apply { isAccessible = true }
    invokeSuspendBlocking(method, session, flipStyleKey, Integer.valueOf(FLIP_STYLE_TRANSLATE))
}

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

internal fun ReaderHook.renderWrappedSelectionMenu(actions: List<Any>, tint: Long, composer: Any, maxPerRow: Int) {
    val content = functionProxy("ReaderSelectionFlowRow", KOTLIN_FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        actions.forEach { action ->
            renderNativeSelectionMenuItem(action, tint, innerComposer)
        }
        targetUnit()
    }
    composeMethod(FLOW_LAYOUT_KT_CLASS, FLOW_ROW_METHOD, 10).invoke(
        null,
        selectionMenuPaddingModifier(),
        arrangementSpacedBy(2),
        arrangementSpacedBy(2),
        null,
        maxPerRow,
        Int.MAX_VALUE,
        content,
        composer,
        0,
        8,
    )
}

internal fun ReaderHook.renderNativeSelectionMenuItem(action: Any, tint: Long, composer: Any) {
    nativeSelectionMenuItemMethod().invoke(
        null,
        callString(action, "getTitle"),
        callNoArg(action, "getIcon") ?: return,
        tint,
        selectionMenuActionCallback(action) ?: return,
        composer,
        0,
    )
}

internal fun ReaderHook.nativeSelectionMenuItemMethod(): Method =
    synchronized(composeMethodCache) {
        composeMethodCache.getOrPut("org.epub.ui.BodyKt#SelectionMenuItem/6") {
            classLoader.loadClass("org.epub.ui.BodyKt").declaredMethods.firstOrNull { method ->
                method.name.contains("SelectionMenuItem") &&
                    method.parameterTypes.size == 6 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[2] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: error("SelectionMenuItem method not found")
        }
    }

internal fun ReaderHook.selectionMenuPaddingModifier(): Any =
    composeMethod(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
        null,
        modifierInstance(),
        udp(8),
        udp(6),
        udp(8),
        udp(6),
    )

internal fun ReaderHook.compactSelectionMenuActions(actions: List<Any>): List<Any> {
    val compact = actions.map { action -> compactSelectionMenuAction(action) ?: action }
    return if (compact.indices.any { index -> compact[index] !== actions[index] }) {
        ArrayList(compact)
    } else {
        actions
    }
}

internal fun ReaderHook.compactSelectionMenuAction(action: Any): Any? {
    val title = callString(action, "getTitle")
    if (title.isBlank()) return null
    val icon = callNoArg(action, "getIcon") ?: return null
    val callback = selectionMenuActionCallback(action) ?: return null
    return createSelectionMenuAction("", icon, callback, "compact native selection action")
}

internal fun ReaderHook.selectionMenuActionCallback(action: Any): Any? {
    val function0Class = runCatching { classLoader.loadClass(KOTLIN_FUNCTION0_CLASS) }.getOrNull() ?: return null
    val preferredNames = listOf("getOnClick", "getOnTap", "getAction", "getCallback", "component3")
    preferredNames.forEach { name ->
        callNoArg(action, name)?.takeIf { function0Class.isInstance(it) }?.let { return it }
    }
    return (action.javaClass.methods.asSequence() + action.javaClass.declaredMethods.asSequence())
        .filter { method ->
            method.parameterTypes.isEmpty() && function0Class.isAssignableFrom(method.returnType)
        }
        .firstNotNullOfOrNull { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(action)
            }.getOrNull()?.takeIf { function0Class.isInstance(it) }
        }
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
        val actionClass = classLoader.loadClass("org.epub.ui.SelectionMenuAction")
        val constructor = actionClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
            ?: return@runCatching null
        constructor.isAccessible = true
        constructor.newInstance(
            title,
            icon,
            callback,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX $logLabel failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.dictionaryImageVector(): Any? {
    val outlined = runCatching {
        classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
    }.getOrNull() ?: return null
    return listOf(
        DICTIONARY_ICON_TRANSLATE_CLASS to "getTranslate",
        DICTIONARY_ICON_MENU_BOOK_CLASS to "getMenuBook",
        DICTIONARY_ICON_AUTO_STORIES_CLASS to "getAutoStories",
        DICTIONARY_ICON_BOOK_CLASS to "getBook",
    ).firstNotNullOfOrNull { (className, methodName) ->
        runCatching {
            classLoader.loadClass(className).declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, outlined)
        }.getOrNull()
    }
}

internal fun ReaderHook.openNativeSelectionEditor() {
    val activity = activityProvider() ?: return
    if (!canEditReaderSelection()) return
    val selection = currentNativeSelectionPayload()
    val controller = selection.controller
    val quote = selection.quote
    if (quote.isBlank()) {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
        return
    }
    activity.runOnUiThread {
        showSelectionEditDialog(activity, quote) { edited ->
            if (edited == quote) {
                Toast.makeText(activity, "\u6587\u672c\u672a\u4fee\u6539", Toast.LENGTH_SHORT).show()
                return@showSelectionEditDialog
            }
            val result = runCatching { writeSelectionTextBack(quote, edited) }
                .onFailure { XposedBridge.log("$LOG_PREFIX selection edit save failed: ${it.stackTraceToString()}") }
                .getOrDefault(false)
            if (result) {
                callNoArg(controller, "clearSelection")
                Toast.makeText(activity, "\u5df2\u4fdd\u5b58\u5230 EPUB", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "\u672a\u5728\u5f53\u524d EPUB \u6587\u4ef6\u4e2d\u627e\u5230\u552f\u4e00\u5339\u914d\u6587\u672c", Toast.LENGTH_LONG).show()
            }
        }
    }
}

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
    activity.runOnUiThread {
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
    Thread({
        val result = runCatching { AiApiStore.dictionary(activity.applicationContext, config, quote, preset) }
            .onFailure { XposedBridge.log("$LOG_PREFIX dictionary request failed: ${it.stackTraceToString()}") }
            .getOrElse { error -> AiApiTestResult(false, error.message ?: error.javaClass.simpleName) }
        activity.runOnUiThread {
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
    val payload = callNoArg(controller, "selectedPayload")
    val quote = callString(payload, "getQuote").ifBlank { callString(controller, "selectedText") }
    return NativeSelectionPayload(
        controller = controller,
        quote = quote.trim(),
        startCfi = callString(payload, "getStartCfi"),
        endCfi = callString(payload, "getEndCfi"),
    )
}

internal fun ReaderHook.contentDomPlainText(contentDom: Any?): String =
    runCatching {
        val annotated = callNoArg(contentDom, "getContent") ?: return@runCatching ""
        callNoArg(annotated, "getText")?.toString()
            ?: (annotated as? CharSequence)?.toString()
            ?: annotated.toString()
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

internal fun ReaderHook.showSelectionEditDialog(activity: Activity, text: String, onSave: (String) -> Unit) {
    val colors = DialogColors(activity)
    val dialog = Dialog(activity)
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()
    val editor = createThoughtStyleEditor(activity, text, colors, ::dp)
    val save = thoughtStyleSaveButton(activity, colors, ::dp).apply {
        setOnClickListener {
            dialog.dismiss()
            onSave(editor.text?.toString().orEmpty())
        }
    }
    val inputCard = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(14))
        background = GradientDrawable().apply {
            setColor(colors.inputBackground)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(2), colors.inputStroke)
        }
        addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(138),
            ),
        )
        addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(save)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(12))
        addView(
            inputCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dialog.dismiss()
            true
        } else {
            false
        }
    }
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setGravity(Gravity.BOTTOM)
        decorView.setPadding(0, 0, 0, 0)
        setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    focusEditorAndShowKeyboard(activity, editor)
}

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
    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setGravity(Gravity.BOTTOM)
        decorView.setPadding(0, 0, 0, 0)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
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
    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setGravity(Gravity.BOTTOM)
        decorView.setPadding(0, 0, 0, 0)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

internal fun ReaderHook.writeSelectionTextBack(oldText: String, newText: String): Boolean {
    val files = candidateCurrentTextFiles()
    if (files.isEmpty()) return false
    return files.any { file -> replaceUniqueTextInFile(file, oldText, newText) }
}
