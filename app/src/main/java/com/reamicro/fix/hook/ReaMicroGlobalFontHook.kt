package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method

class ReaMicroGlobalFontHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val methodCache = HashMap<String, Method>()
    private val fontFamilyCache = HashMap<String, Any>()
    private val androidTypefaceCache = HashMap<String, Typeface>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val readerTextDepth = ThreadLocal.withInitial { 0 }
    private val fontPreviewDepth = ThreadLocal.withInitial { 0 }
    private val appliedLogKeys = HashSet<String>()
    @Volatile private var cachedGlobalFont: CachedGlobalFont? = null

    fun install() {
        EmbeddedHostUi.bindUiFontResolver(::globalAndroidTypeface)
        hookReaderTextScopes()
        hookReaderFontPreviewScopes()
        hookThemeSerifFont()
        hookMaterialTextFallback()
        hookAndroidDialogTextFallback()
    }

    fun invalidateGlobalFontCache() {
        cachedGlobalFont = null
        synchronized(fontFamilyCache) { fontFamilyCache.clear() }
        synchronized(androidTypefaceCache) {
            androidTypefaceCache.clear()
        }
    }

    private fun hookThemeSerifFont() {
        runCatching {
            val textStyleClass = cls(HOST_TEXT_STYLE_KT_CLASS)
            val methods = textStyleClass.declaredMethods.filter {
                it.name == "rememberSerifFontFamily" &&
                    it.returnType.name == FONT_FAMILY_CLASS
            }
            if (methods.isEmpty()) error("rememberSerifFontFamily not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        EmbeddedHostUi.captureTypography(classLoader, param.args?.firstOrNull())
                        val resolved = resolveGlobalUiFontCached() ?: return
                        param.result = resolved.family
                        logApplied("theme", resolved.selection)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX global UI font theme hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX global UI font theme hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderTextScopes() {
        runCatching {
            val methods = cls(READER_SCREEN_KT_CLASS).declaredMethods.filter { method ->
                method.name == "ReaderScreen" ||
                    method.name.startsWith("ReaderScreen\$") ||
                    method.name == "ReaderToolBox" ||
                    method.name.startsWith("ReaderToolBox\$") ||
                    method.name == "InitEpubWindow" ||
                    method.name.startsWith("InitEpubWindow\$") ||
                    method.name == "BottomSheet" ||
                    method.name.startsWith("BottomSheet\$")
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        readerTextDepth.set(readerDepth() + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        readerTextDepth.set((readerDepth() - 1).coerceAtLeast(0))
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX global UI font reader text scope hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX global UI font reader text scope hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderFontPreviewScopes() {
        runCatching {
            val methods = cls(READER_FAMILY_USER_KT_CLASS).declaredMethods.filter { method ->
                method.name == "FamilyRow" || method.name.startsWith("FamilyRow$")
            }
            if (methods.isEmpty()) error("ReaderFamilyUser FamilyRow not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        fontPreviewDepth.set(fontPreviewDepth() + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        fontPreviewDepth.set((fontPreviewDepth() - 1).coerceAtLeast(0))
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX global UI font preview scope hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX global UI font preview scope hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookMaterialTextFallback() {
        runCatching {
            val textKtClass = cls(MATERIAL_TEXT_KT_CLASS)
            val methods = textKtClass.declaredMethods.filter { method ->
                method.name.startsWith("Text") &&
                    method.parameterTypes.any { it.name == FONT_FAMILY_CLASS } &&
                    method.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType
            }
            if (methods.isEmpty()) error("Material Text methods with FontFamily not found")
            methods.forEach { method ->
                val fontFamilyIndex = method.parameterTypes.indexOfFirst { it.name == FONT_FAMILY_CLASS }
                if (fontFamilyIndex < 0) return@forEach
                val textStyleIndex = method.parameterTypes.indexOfFirst { it.name == TEXT_STYLE_CLASS }
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        if (fontFamilyIndex >= args.size) return
                        if (isReaderTextScope()) return
                        if (
                            isFontPreviewScope() &&
                            (args[fontFamilyIndex] != null || styleHasExplicitFontFamily(args, textStyleIndex))
                        ) return
                        val resolved = resolveGlobalUiFontCached() ?: return
                        args[fontFamilyIndex] = resolved.family
                        clearDefaultMask(args, fontFamilyIndex)
                        logApplied("text", resolved.selection)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX global UI font Material Text hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX global UI font Material Text hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookAndroidDialogTextFallback() {
        runCatching {
            XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    applyGlobalFontToDialog(dialog)
                }
            })
            XposedBridge.log("$LOG_PREFIX global UI font Android Dialog hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX global UI font Android Dialog hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun applyGlobalFontToDialog(dialog: Dialog) {
        val decor = dialog.window?.decorView ?: return
        val resolved = resolveGlobalAndroidTypefaceCached() ?: return
        applyGlobalFontToViewTree(decor, resolved.typeface, resolved.selection == "host")
        decor.post { applyGlobalFontToViewTree(decor, resolved.typeface, resolved.selection == "host") }
        decor.postDelayed({ applyGlobalFontToViewTree(decor, resolved.typeface, resolved.selection == "host") }, 120L)
        logApplied("dialog", resolved.selection)
    }

    private fun applyGlobalFontToViewTree(view: View, baseTypeface: Typeface, nativeFollow: Boolean) {
        if (view is TextView) applyGlobalFontToTextView(view, baseTypeface, nativeFollow)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyGlobalFontToViewTree(view.getChildAt(index), baseTypeface, nativeFollow)
            }
        }
    }

    private fun applyGlobalFontToTextView(textView: TextView, baseTypeface: Typeface, nativeFollow: Boolean) {
        if (textView.tag == "reamicro-font-preview") return
        val current = textView.typeface
        if (current === Typeface.MONOSPACE || current == Typeface.MONOSPACE) return
        val style = current?.style ?: Typeface.NORMAL
        textView.typeface = if (nativeFollow && (style and Typeface.BOLD) != 0)
            EmbeddedHostUi.nativeBoldTypeface(textView.context) ?: Typeface.create(baseTypeface, style)
        else Typeface.create(baseTypeface, style)
    }

    private fun clearDefaultMask(args: Array<Any?>, parameterIndex: Int) {
        if (parameterIndex !in 0..30) return
        val maskIndex = args.lastIndex
        val mask = (args.getOrNull(maskIndex) as? Number)?.toInt() ?: return
        args[maskIndex] = mask and (1 shl parameterIndex).inv()
    }

    private fun isReaderTextScope(): Boolean =
        readerDepth() > 0

    private fun isFontPreviewScope(): Boolean =
        fontPreviewDepth() > 0

    private fun styleHasExplicitFontFamily(args: Array<Any?>, textStyleIndex: Int): Boolean {
        if (textStyleIndex !in args.indices) return false
        val textStyle = args[textStyleIndex] ?: return false
        return runCatching {
            textStyle.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    (it.name == "getFontFamily" || it.name.startsWith("getFontFamily-"))
            }?.invoke(textStyle) != null
        }.getOrDefault(false)
    }

    private fun readerDepth(): Int =
        readerTextDepth.get() ?: 0

    private fun fontPreviewDepth(): Int =
        fontPreviewDepth.get() ?: 0

    private fun resolveGlobalUiFontCached(): ResolvedGlobalFont? {
        val now = System.currentTimeMillis()
        val selection = settings.fontSettings().globalFamily
        cachedGlobalFont?.takeIf { now - it.atMs < GLOBAL_FONT_CACHE_WINDOW_MS && it.selection == selection }?.let { return it.font }
        val resolved = resolveGlobalUiFont()
        cachedGlobalFont = CachedGlobalFont(now, resolved, selection)
        return resolved
    }

    private fun resolveGlobalUiFont(): ResolvedGlobalFont? {
        if (!settings.snapshot().canUseFontSettings) return null
        val selection = settings.fontSettings().globalFamily
        if (selection.isBlank()) return null
        val family = resolveFontFamily(selection) ?: return null
        return ResolvedGlobalFont(selection, family)
    }

    private fun resolveGlobalAndroidTypefaceCached(): ResolvedAndroidTypeface? {
        val selection = if (settings.snapshot().canUseFontSettings) settings.fontSettings().globalFamily else ""
        if (selection.isBlank()) return activityProvider()?.let(EmbeddedHostUi::nativeTypeface)?.let { ResolvedAndroidTypeface("host", it) }
        val typeface = resolveAndroidTypeface(selection) ?: return activityProvider()?.let(EmbeddedHostUi::nativeTypeface)?.let { ResolvedAndroidTypeface("host", it) }
        return ResolvedAndroidTypeface(selection, typeface)
    }

    internal fun globalAndroidTypeface(): Typeface? = resolveGlobalAndroidTypefaceCached()?.typeface

    private fun resolveAndroidTypeface(selection: String): Typeface? {
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val key = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        synchronized(androidTypefaceCache) {
            androidTypefaceCache[key]?.let { return it }
        }
        val resolved = when (selection) {
            FAMILY_SYSTEM -> Typeface.DEFAULT
            FAMILY_SOURCE_HAN_SERIF -> activityProvider()?.let(EmbeddedHostUi::nativeTypeface) ?: Typeface.SERIF
            else -> {
                val file = resolvedFile ?: return null
                runCatching { Typeface.createFromFile(file) }
                    .onFailure { logResolveFontFamilyFailure(selection, it) }
                    .getOrNull()
            }
        } ?: return null
        synchronized(androidTypefaceCache) {
            androidTypefaceCache[key] = resolved
        }
        return resolved
    }

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val cacheKey = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        synchronized(fontFamilyCache) {
            fontFamilyCache[cacheKey]?.let { return it }
        }
        if (isBuiltinFontSelection(selection)) {
            return resolveBuiltinFontFamily(selection)?.also { family ->
                synchronized(fontFamilyCache) {
                    fontFamilyCache[cacheKey] = family
                }
            }
        }
        val file = resolvedFile ?: return null
        return runCatching {
            val provider = staticObject(FONT_PROVIDER_CLASS, "INSTANCE")
            val normal = fontWeight("getNormal")
            val bold = fontWeight("getBold")
            val fromPath = method(FONT_PROVIDER_CLASS, "fromPath", 2)
            val fonts = listOfNotNull(
                fromPath.invoke(provider, file.absolutePath, normal),
                fromPath.invoke(provider, file.absolutePath, bold),
            )
            if (fonts.isEmpty()) return null
            val family = cls(FONT_FAMILY_KT_CLASS).declaredMethods.first {
                it.name == "FontFamily" &&
                    it.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }.invoke(null, fonts)
            synchronized(fontFamilyCache) {
                fontFamilyCache[cacheKey] = family
            }
            family
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> callMethod(staticObject(FONT_PROVIDER_CLASS, "INSTANCE"), "builtInSong")
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? {
        fieldObjectOrNull(FONT_FAMILY_CLASS, "Default")?.let { return it }
        for (fieldName in listOf("Companion", "INSTANCE")) {
            val companion = fieldObjectOrNull(FONT_FAMILY_CLASS, fieldName) ?: continue
            callMethod(companion, "getDefault")?.let { return it }
        }
        return staticMethod(FONT_FAMILY_CLASS, "getDefault")?.invoke(null)
    }

    private fun fontWeight(methodName: String): Any {
        val clazz = cls(FONT_WEIGHT_CLASS)
        companionFontWeight(clazz, methodName)?.let { return it }
        val (fieldName, weight) = when (methodName) {
            "getBold" -> "Bold" to 700
            else -> "Normal" to 400
        }
        staticFontWeight(clazz, fieldName)?.let { return it }
        return clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(weight)
    }

    private fun companionFontWeight(clazz: Class<*>, methodName: String): Any? {
        for (fieldName in listOf("INSTANCE", "Companion")) {
            val companion = runCatching {
                clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
            }.recoverCatching {
                clazz.getField(fieldName).apply { isAccessible = true }.get(null)
            }.getOrNull() ?: continue
            val method = companion.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: continue
            return method.invoke(companion)
        }
        return null
    }

    private fun staticFontWeight(clazz: Class<*>, fieldName: String): Any? =
        runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }.recoverCatching {
            clazz.getField(fieldName).apply { isAccessible = true }.get(null)
        }.getOrNull()

    private fun resolveFontFile(selection: String): File? =
        com.reamicro.fix.epub.editor.FontSelectionResolution.file(selection,
            activityProvider()?.filesDir?.let(::fontDirectories).orEmpty())
    private fun fontDirectories(filesDir: File): List<File> {
        val dirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { File(it, "fonts") }
            ?.toMutableList()
            ?: mutableListOf()
        val defaultDir = File(File(filesDir, "0"), "fonts")
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) {
            dirs.add(defaultDir)
        }
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    private fun isFontFileName(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
        }
        XposedBridge.log(
            "$LOG_PREFIX global UI font resolve failed: " +
                "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun logApplied(source: String, selection: String) {
        val key = "$source|$selection"
        synchronized(appliedLogKeys) {
            if (!appliedLogKeys.add(key)) return
        }
        XposedBridge.log("$LOG_PREFIX global UI font applied ($source): ${displayFontName(selection)}")
    }

    private fun displayFontName(value: String): String =
        when (value) {
            FAMILY_SYSTEM -> "系统字体"
            FAMILY_SOURCE_HAN_SERIF -> "思源宋体"
            else -> File(value).name.substringBeforeLast('.', File(value).name)
        }

    private fun callMethod(target: Any?, name: String): Any? {
        if (target == null) return null
        return target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.invoke(target)
    }

    private fun staticMethod(className: String, name: String): Method? =
        cls(className).methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }

    private fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        runCatching {
            cls(className).run {
                runCatching { getDeclaredField(fieldName) }
                    .recoverCatching { getField(fieldName) }
                    .getOrThrow()
                    .apply { isAccessible = true }
                    .get(null)
            }
        }.getOrNull()

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: cls(className).methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.size == parameterCount
                    }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any =
        cls(className).run {
            runCatching { getDeclaredField(fieldName) }
                .recoverCatching { getField(fieldName) }
                .getOrThrow()
                .apply { isAccessible = true }
                .get(null)
        }

    private data class ResolvedGlobalFont(
        val selection: String,
        val family: Any,
    )

    private data class ResolvedAndroidTypeface(
        val selection: String,
        val typeface: Typeface,
    )

    private data class CachedGlobalFont(
        val atMs: Long,
        val font: ResolvedGlobalFont?,
        val selection: String,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val HOST_TEXT_STYLE_KT_CLASS = HostClasses.Host.HOST_TEXT_STYLE_KT
        const val READER_SCREEN_KT_CLASS = HostClasses.Host.READER_SCREEN_KT
        const val READER_FAMILY_USER_KT_CLASS = HostClasses.Host.READER_FAMILY_USER
        const val MATERIAL_TEXT_KT_CLASS = HostClasses.Compose.TEXT_KT
        const val TEXT_STYLE_CLASS = HostClasses.Compose.TEXT_STYLE
        const val FONT_PROVIDER_CLASS = HostClasses.Epub.FONT_PROVIDER
        const val FONT_FAMILY_CLASS = HostClasses.Compose.FONT_FAMILY
        const val FONT_FAMILY_KT_CLASS = HostClasses.Compose.FONT_FAMILY_KT
        const val FONT_WEIGHT_CLASS = HostClasses.Compose.FONT_WEIGHT
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val GLOBAL_FONT_CACHE_WINDOW_MS = 300L

        fun isBuiltinFontSelection(selection: String): Boolean =
            selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF
    }
}
