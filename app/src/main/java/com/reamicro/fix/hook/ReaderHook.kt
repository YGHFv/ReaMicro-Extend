package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets

class ReaderHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private var nativeSelectionHookInstalled: Boolean = false
    private var currentSelectionControllerRef: WeakReference<Any>? = null
    private var currentEpubRef: WeakReference<Any>? = null
    private var currentPageRef: WeakReference<Any>? = null

    fun install() {
        installNativeSelectionHooks()
        hookReaderViewModel()
    }

    private fun canEditReaderSelection(): Boolean =
        settingsProvider().canEditReaderSelection

    private fun hookReaderViewModel() {
        runCatching {
            val cls = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel created")
                }
            })
            XposedBridge.hookAllMethods(cls, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel cleared")
                    currentSelectionControllerRef = null
                    currentEpubRef = null
                    currentPageRef = null
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX ReaderViewModel hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun installNativeSelectionHooks() {
        if (nativeSelectionHookInstalled) return
        nativeSelectionHookInstalled = true
        hookNativeSelectionController()
        hookNativeSelectionMenu()
        hookCurrentEpub()
        hookCurrentEpubPage()
    }

    private fun hookNativeSelectionController() {
        runCatching {
            val controllerClass = classLoader.loadClass("org.epub.ui.EpubSelectionController")
            XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentSelectionControllerRef = WeakReference(param.thisObject)
                }
            })
            controllerClass.declaredMethods
                .filter { method ->
                    method.name.contains("Selection", ignoreCase = true) ||
                        method.name == "selectedPayload" ||
                        method.name == "selectedText"
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            currentSelectionControllerRef = WeakReference(param.thisObject)
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX native EpubSelectionController hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX native selection controller hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookNativeSelectionMenu() {
        runCatching {
            val bodyClass = classLoader.loadClass("org.epub.ui.BodyKt")
            val menuMethods = bodyClass.declaredMethods.filter {
                it.name == "SelectionBubbleMenu" &&
                    it.parameterTypes.size >= 2 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (menuMethods.isEmpty()) error("SelectionBubbleMenu not found")
            menuMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!canEditReaderSelection()) return
                        val original = (param.args?.getOrNull(0) as? List<*>) ?: return
                        if (original.any { callString(it, "getTitle") == "\u7f16\u8f91" }) return
                        val icon = editImageVector() ?: original.firstNotNullOfOrNull { callNoArg(it, "getIcon") } ?: return
                        val action = createNativeEditAction(icon) ?: return
                        param.args[0] = ArrayList<Any>(original.filterNotNull()).apply { add(action) }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu hook installed: ${menuMethods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX native selection menu hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun createNativeEditAction(icon: Any): Any? =
        runCatching {
            val actionClass = classLoader.loadClass("org.epub.ui.SelectionMenuAction")
            val constructor = actionClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                ?: return@runCatching null
            constructor.isAccessible = true
            constructor.newInstance(
                "\u7f16\u8f91",
                icon,
                nativeFunction0 {
                    openNativeSelectionEditor()
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX create native edit action failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun editImageVector(): Any? =
        runCatching {
            val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
            classLoader.loadClass(EDIT_ICON_CLASS).declaredMethods.firstOrNull {
                it.name == "getEdit" && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, outlined)
        }.getOrNull()

    private fun nativeFunction0(block: () -> Unit): Any {
        val function0Class = classLoader.loadClass("kotlin.jvm.functions.Function0")
        return Proxy.newProxyInstance(classLoader, arrayOf(function0Class)) { _, method, _ ->
            when (method.name) {
                "invoke" -> {
                    block()
                    targetKotlinUnit()
                }
                "toString" -> "ReaMicroEditAction"
                "hashCode" -> System.identityHashCode(block)
                "equals" -> false
                else -> null
            }
        }
    }

    private fun targetKotlinUnit(): Any? =
        runCatching { classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()

    private fun hookCurrentEpub() {
        runCatching {
            val epubClass = classLoader.loadClass("app.zhendong.reamicro.data.epub.Epub")
            epubClass.declaredMethods
                .filter { it.name == "read" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            currentEpubRef = WeakReference(param.thisObject)
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX current Epub hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX current Epub hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookCurrentEpubPage() {
        runCatching {
            val containerClass = classLoader.loadClass("app.zhendong.reamicro.ui.reader.components.EpubContainerKt")
            containerClass.declaredMethods
                .filter { it.name == "EpubContainer" && it.parameterTypes.size >= 2 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            currentPageRef = WeakReference(param.args?.getOrNull(1))
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX current EpubPage hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX current EpubPage hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun openNativeSelectionEditor() {
        val activity = activityProvider() ?: return
        if (!canEditReaderSelection()) return
        val controller = currentSelectionControllerRef?.get()
        val payload = callNoArg(controller, "selectedPayload")
        val quote = callString(payload, "getQuote").ifBlank { callString(controller, "selectedText") }
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

    private fun showSelectionEditDialog(activity: Activity, text: String, onSave: (String) -> Unit) {
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
        focusThoughtStyleEditor(activity, editor)
    }

    private fun createThoughtStyleEditor(
        activity: Activity,
        text: String,
        colors: DialogColors,
        dp: (Int) -> Int,
    ): EditText =
        EditText(activity).apply {
            setText(text)
            setSelection(length())
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            hint = "\u8bb0\u5f55\u6b64\u523b\u7684\u60f3\u6cd5"
            textSize = 18f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, dp(10))
            background = null
        }

    private fun thoughtStyleSaveButton(
        activity: Activity,
        colors: DialogColors,
        dp: (Int) -> Int,
    ): TextView =
        TextView(activity).apply {
            text = "\u4fdd\u5b58"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.actionText)
            setPadding(dp(20), dp(4), dp(20), dp(4))
            background = GradientDrawable().apply {
                setColor(colors.actionBackground)
                cornerRadius = dp(16).toFloat()
            }
            minWidth = dp(80)
            minHeight = dp(32)
        }

    private fun focusThoughtStyleEditor(activity: Activity, editor: EditText) {
        editor.requestFocus()
        editor.postDelayed(
            {
                editor.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            },
            120L,
        )
    }

    private fun writeSelectionTextBack(oldText: String, newText: String): Boolean {
        val files = candidateCurrentTextFiles()
        if (files.isEmpty()) return false
        return files.any { file -> replaceUniqueTextInFile(file, oldText, newText) }
    }

    private fun candidateCurrentTextFiles(): List<File> {
        val root = currentEpubRef?.get()
            ?.let { callNoArg(it, "getDirectory")?.toString() }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?: return emptyList()
        val page = currentPageRef?.get()
        val spineIndex = (callNoArg(page, "getSpineIndex") as? Int) ?: -1
        val itemRefs = (currentEpubRef?.get()?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.toList().orEmpty()
        val currentHref = itemRefs.firstOrNull { (callNoArg(it, "getSpineIndex") as? Int) == spineIndex }
            ?.let { callString(it, "getHref") }
            ?.takeIf { it.isNotBlank() }
        val currentFile = currentHref?.let { File(root, it).canonicalFileSafe() }?.takeIf { it.isTextContentFile() }
        val allTextFiles by lazy {
            root.walkTopDown()
                .filter { it.isFile && it.isTextContentFile() }
                .map { it.canonicalFileSafe() ?: it }
                .toList()
        }
        return buildList {
            if (currentFile != null) add(currentFile)
            addAll(allTextFiles.filter { currentFile == null || it.absolutePath != currentFile.absolutePath })
        }
    }

    private fun replaceUniqueTextInFile(file: File, oldText: String, newText: String): Boolean {
        val content = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val candidates = listOf(oldText, escapeXmlText(oldText)).distinct().filter { it.isNotBlank() }
        for (candidate in candidates) {
            val first = content.indexOf(candidate)
            if (first < 0) continue
            if (content.indexOf(candidate, first + candidate.length) >= 0) return false
            val replacement = if (candidate == oldText) newText else escapeXmlText(newText)
            file.writeText(content.replaceRange(first, first + candidate.length, replacement), StandardCharsets.UTF_8)
            return true
        }
        return false
    }

    private fun File.isTextContentFile(): Boolean =
        isFile && extension.lowercase() in setOf("xhtml", "html", "htm", "xml", "txt")

    private fun File.canonicalFileSafe(): File? = runCatching { canonicalFile }.getOrNull()

    private fun escapeXmlText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun callNoArg(target: Any?, name: String): Any? =
        runCatching {
            target?.javaClass?.methods?.firstOrNull {
                it.parameterTypes.isEmpty() && it.name == name
            }?.invoke(target)
        }.getOrNull()

    private fun callString(target: Any?, name: String): String =
        callNoArg(target, name)?.toString().orEmpty()

    private companion object {
        const val READER_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        const val EDIT_ICON_CLASS = "androidx.compose.material.icons.outlined.EditKt"
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val LOG_PREFIX = "ReaMicro LSP"
    }

    private class DialogColors(context: Context) {
        private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val cardBackground: Int = if (night) Color.rgb(38, 38, 38) else Color.WHITE
        val inputBackground: Int = if (night) Color.rgb(44, 44, 44) else Color.WHITE
        val primaryText: Int = if (night) Color.rgb(238, 238, 238) else Color.rgb(25, 25, 25)
        val secondaryText: Int = if (night) Color.rgb(170, 170, 170) else Color.rgb(118, 118, 118)
        val stroke: Int = if (night) Color.rgb(68, 68, 68) else Color.rgb(228, 228, 228)
        val inputStroke: Int = if (night) Color.rgb(236, 162, 100) else Color.rgb(238, 118, 62)
        val actionBackground: Int = if (night) Color.rgb(180, 112, 64) else Color.rgb(238, 118, 62)
        val actionText: Int = Color.WHITE
        val accent: Int = if (night) Color.rgb(236, 183, 102) else Color.rgb(171, 105, 38)
    }
}
