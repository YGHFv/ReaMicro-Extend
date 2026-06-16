package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method

class ReaMicroGlobalFontHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val methodCache = HashMap<String, Method>()
    private val fontFamilyCache = HashMap<String, Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val readerTextDepth = ThreadLocal.withInitial { 0 }
    private val fontPreviewDepth = ThreadLocal.withInitial { 0 }
    private val appliedLogKeys = HashSet<String>()
    @Volatile private var cachedGlobalFont: CachedGlobalFont? = null

    fun install() {
        hookReaderTextScopes()
        hookReaderFontPreviewScopes()
        hookThemeSerifFont()
        hookMaterialTextFallback()
    }

    fun invalidateGlobalFontCache() {
        cachedGlobalFont = null
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
                        if (args[fontFamilyIndex] != null) return
                        if (isReaderTextScope()) return
                        if (isFontPreviewScope() && styleHasExplicitFontFamily(args, textStyleIndex)) return
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
        cachedGlobalFont?.takeIf { now - it.atMs < GLOBAL_FONT_CACHE_WINDOW_MS }?.let { return it.font }
        val resolved = resolveGlobalUiFont()
        cachedGlobalFont = CachedGlobalFont(now, resolved)
        return resolved
    }

    private fun resolveGlobalUiFont(): ResolvedGlobalFont? {
        if (!settings.snapshot().canUseFontSettings) return null
        val selection = settings.fontSettings().globalFamily
        if (selection.isBlank()) return null
        val family = resolveFontFamily(selection) ?: return null
        return ResolvedGlobalFont(selection, family)
    }

    private fun resolveFontFamily(selection: String): Any? {
        synchronized(fontFamilyCache) {
            fontFamilyCache[selection]?.let { return it }
        }
        if (isBuiltinFontSelection(selection)) {
            return resolveBuiltinFontFamily(selection)?.also { family ->
                synchronized(fontFamilyCache) {
                    fontFamilyCache[selection] = family
                }
            }
        }
        val file = resolveFontFile(selection) ?: return null
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
                fontFamilyCache[selection] = family
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

    private fun resolveFontFile(selection: String): File? {
        val direct = File(selection)
        if (direct.isFile && isFontFileName(direct.name)) return direct
        val name = direct.name
        if (!isFontFileName(name)) return null
        val root = activityProvider()?.filesDir ?: return null
        return fontDirectories(root)
            .asSequence()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.isFile && it.name == name && isFontFileName(it.name) }
    }

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

    private data class CachedGlobalFont(
        val atMs: Long,
        val font: ResolvedGlobalFont?,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val HOST_TEXT_STYLE_KT_CLASS = "app.zhendong.reamicro.arch.theme.TextStyleKt"
        const val READER_SCREEN_KT_CLASS = "app.zhendong.reamicro.ui.reader.ReaderScreenKt"
        const val READER_FAMILY_USER_KT_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderFamilyUserKt"
        const val MATERIAL_TEXT_KT_CLASS = "androidx.compose.material3.TextKt"
        const val TEXT_STYLE_CLASS = "androidx.compose.ui.text.TextStyle"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val GLOBAL_FONT_CACHE_WINDOW_MS = 300L

        fun isBuiltinFontSelection(selection: String): Boolean =
            selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF
    }
}
