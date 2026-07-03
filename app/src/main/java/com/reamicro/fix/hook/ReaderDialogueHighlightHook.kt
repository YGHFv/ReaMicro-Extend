package com.reamicro.fix.hook

import android.app.Activity
import android.graphics.Color
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightRuleType
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class ReaderDialogueHighlightHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, Field>()
    private val fontFamilyCache = HashMap<String, Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    @Volatile private var lastAppliedLogKey: String = ""

    fun install() {
        CONTENT_DOM_CLASS_CANDIDATES.forEach(::hookContentDom)
    }

    private fun hookContentDom(className: String) {
        runCatching {
            val contentDomClass = cls(className)
            val methods = contentDomClass.declaredMethods.filter { method ->
                method.name == "onContent" && method.parameterTypes.size == 2
            }
            if (methods.isEmpty()) return
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyDialogueHighlight(param.thisObject)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX dialogue highlight hook installed: $className count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX dialogue highlight hook failed: $className ${it.stackTraceToString()}")
        }
    }

    private fun applyDialogueHighlight(contentDom: Any?) {
        if (!settings.snapshot().canHighlightReaderDialogue) return
        runCatching {
            contentDom ?: return
            val content = callNoArg(contentDom, "getContent") ?: return
            val text = callNoArg(content, "getText")?.toString().orEmpty()
            if (text.isBlank()) return
            val highlight = settings.highlightSettings()
            val enabledRules = highlight.rules.filter { it.enabled }
            if (enabledRules.isEmpty()) return
            val rangeObjects = enabledRules.flatMap { rule ->
                val ranges = findRanges(text, rule)
                if (ranges.isEmpty()) return@flatMap emptyList()
                val style = createHighlightSpanStyle(highlight.styleById(rule.styleId)) ?: return@flatMap emptyList()
                ranges.mapNotNull { range -> createAnnotatedRange(style, range.first, range.last) }
            }
            if (rangeObjects.isEmpty()) return
            val originalSpanStyles = callNoArg(content, "getSpanStyles") as? List<*> ?: emptyList<Any>()
            val nextSpanStyles = ArrayList<Any>(originalSpanStyles.size + rangeObjects.size).apply {
                addAll(originalSpanStyles.filterNotNull())
                addAll(rangeObjects)
            }
            val nextContent = newAnnotatedString(content, nextSpanStyles) ?: return
            field(contentDom.javaClass.name, "content").set(contentDom, nextContent)
            logApplied(text, rangeObjects.size)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX dialogue highlight apply failed: ${it.stackTraceToString()}")
        }
    }

    private fun findRanges(text: String, rule: ReaderHighlightRule): List<IntRange> =
        when (rule.type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> findQuoteRanges(text, DOUBLE_QUOTES)
            ReaderHighlightRuleType.SingleQuotePhrase -> findQuoteRanges(text, SINGLE_QUOTES)
        }

    private fun findQuoteRanges(text: String, quotes: Map<Char, Char>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var index = 0
        while (index < text.length) {
            val close = quotes[text[index]]
            if (close == null) {
                index++
                continue
            }
            val end = findClosingQuote(text, index + 1, close)
            if (end > index + 1) {
                ranges.add(index..(end + 1))
                index = end + 1
            } else {
                index++
            }
        }
        return ranges
    }

    private fun findClosingQuote(text: String, start: Int, close: Char): Int {
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (char == '\n' || char == '\r') return -1
            if (char == close) return index
            index++
        }
        return -1
    }

    private fun createHighlightSpanStyle(style: ReaderHighlightStyle): Any? {
        val css = parseCssStyle(style.css)
        val color = composeColor(css.color.ifBlank { style.color })
        val fontSelection = style.fontFamily.ifBlank { settings.fontSettings().globalFamily }
        val fontFamily = resolveFontFamily(fontSelection)
        val constructor = cls(SPAN_STYLE_CLASS).declaredConstructors.firstOrNull { it.parameterTypes.size == 18 }
            ?: return null
        constructor.isAccessible = true
        val mask = if (fontFamily != null) SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR_AND_FONT else SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR
        return constructor.newInstance(
            color,
            0L,
            null,
            null,
            null,
            fontFamily,
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            null,
            null,
            mask,
            null,
        )
    }

    private fun parseCssStyle(css: String): CssStyle {
        if (css.isBlank()) return CssStyle()
        val values = css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) null else part.substring(0, index).trim().lowercase() to part.substring(index + 1).trim()
            }
            .toMap()
        return CssStyle(color = values["color"].orEmpty())
    }

    private fun composeColor(value: String): Long {
        val argb = runCatching { Color.parseColor(value) }.getOrDefault(Color.parseColor(DEFAULT_DIALOGUE_COLOR))
        val packedArgb = argb.toLong() and 0xFFFFFFFFL
        return cls(COLOR_KT_CLASS).declaredMethods.firstOrNull { method ->
            method.name == "Color" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Long::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.invoke(null, packedArgb) as? Long ?: 0L
    }

    private fun createAnnotatedRange(item: Any, start: Int, endExclusive: Int): Any? {
        val rangeClass = cls(ANNOTATED_RANGE_CLASS)
        return runCatching {
            rangeClass.getDeclaredConstructor(Any::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(item, start, endExclusive)
        }.recoverCatching {
            rangeClass.getDeclaredConstructor(
                Any::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).apply { isAccessible = true }
                .newInstance(item, start, endExclusive, "")
        }.getOrNull()
    }

    private fun newAnnotatedString(original: Any, spanStyles: List<Any>): Any? =
        method(ANNOTATED_STRING_EXT_CLASS, "newInstance\$default", 5)
            .invoke(null, original, spanStyles, null, 2, null)

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        synchronized(fontFamilyCache) {
            fontFamilyCache[selection]?.let { return it }
        }
        val family = if (selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF) {
            resolveBuiltinFontFamily(selection)
        } else {
            resolveFontFile(selection)?.let(::resolveFontFamilyFromFile)
        } ?: return null
        synchronized(fontFamilyCache) {
            fontFamilyCache[selection] = family
        }
        return family
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> callNoArg(staticObject(FONT_PROVIDER_CLASS, "INSTANCE"), "builtInSong")
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? {
        fieldObjectOrNull(FONT_FAMILY_CLASS, "Default")?.let { return it }
        for (fieldName in listOf("Companion", "INSTANCE")) {
            val companion = fieldObjectOrNull(FONT_FAMILY_CLASS, fieldName) ?: continue
            callNoArg(companion, "getDefault")?.let { return it }
        }
        return cls(FONT_FAMILY_CLASS).methods.firstOrNull {
            it.name == "getDefault" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(null)
    }

    private fun resolveFontFamilyFromFile(file: File): Any? =
        runCatching {
            val provider = staticObject(FONT_PROVIDER_CLASS, "INSTANCE")
            val fromPath = method(FONT_PROVIDER_CLASS, "fromPath", 2)
            val fonts = listOfNotNull(
                fromPath.invoke(provider, file.absolutePath, fontWeight("getNormal")),
                fromPath.invoke(provider, file.absolutePath, fontWeight("getBold")),
            )
            if (fonts.isEmpty()) return null
            cls(FONT_FAMILY_KT_CLASS).declaredMethods.firstOrNull {
                it.name == "FontFamily" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.apply { isAccessible = true }?.invoke(null, fonts)
        }.onFailure { logResolveFontFamilyFailure(file.absolutePath, it) }.getOrNull()

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
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) dirs.add(defaultDir)
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    private fun fontWeight(methodName: String): Any {
        val clazz = cls(FONT_WEIGHT_CLASS)
        for (fieldName in listOf("INSTANCE", "Companion")) {
            val companion = fieldObjectOrNull(FONT_WEIGHT_CLASS, fieldName) ?: continue
            companion.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(companion)?.let { return it }
        }
        val (fieldName, weight) = when (methodName) {
            "getBold" -> "Bold" to 700
            else -> "Normal" to 400
        }
        fieldObjectOrNull(FONT_WEIGHT_CLASS, fieldName)?.let { return it }
        return clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(weight)
    }

    private fun isFontFileName(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
        }
        XposedBridge.log("$LOG_PREFIX dialogue highlight font failed: $selection ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun logApplied(text: String, count: Int) {
        val key = "${text.length}|$count|${text.take(24)}"
        if (key == lastAppliedLogKey) return
        lastAppliedLogKey = key
        XposedBridge.log("$LOG_PREFIX dialogue highlight applied ranges=$count text=${text.take(24)}")
    }

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

    private fun field(className: String, fieldName: String): Field {
        val cacheKey = "$className#$fieldName"
        return synchronized(fieldCache) {
            fieldCache.getOrPut(cacheKey) {
                cls(className).run {
                    runCatching { getDeclaredField(fieldName) }
                        .recoverCatching { getField(fieldName) }
                        .getOrThrow()
                        .apply { isAccessible = true }
                }
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any =
        cls(className).run {
            runCatching { getDeclaredField(fieldName) }
                .recoverCatching { getField(fieldName) }
                .getOrThrow()
                .apply { isAccessible = true }
                .get(null) ?: error("$className.$fieldName is null")
        }

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

    private fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null
        return target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.invoke(target)
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val DEFAULT_DIALOGUE_COLOR = "#FF9800"
        const val SPAN_STYLE_CLASS = "androidx.compose.ui.text.SpanStyle"
        const val ANNOTATED_RANGE_CLASS = "androidx.compose.ui.text.AnnotatedString\$Range"
        const val ANNOTATED_STRING_EXT_CLASS = "org.epub.utils.AnnotatedStringExtKt"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR = 65534
        const val SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR_AND_FONT = 65502
        val CONTENT_DOM_CLASS_CANDIDATES = listOf(
            "org.epub.html.node.ContentDom",
            "app.zhendong.epub.node.ContentDom",
        )
        val DOUBLE_QUOTES = mapOf(
            '\u201c' to '\u201d',
            '\u300c' to '\u300d',
            '\u300e' to '\u300f',
            '"' to '"',
        )
        val SINGLE_QUOTES = mapOf(
            '\u2018' to '\u2019',
            '\'' to '\'',
        )
    }

    private data class CssStyle(
        val color: String = "",
    )
}
