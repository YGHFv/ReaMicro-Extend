package com.reamicro.fix.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightBookContext
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
        val snapshot = settings.snapshot()
        if (!snapshot.canHighlightReaderDialogue) return
        runCatching {
            contentDom ?: return
            val content = callNoArg(contentDom, "getContent") ?: return
            val text = callNoArg(content, "getText")?.toString().orEmpty()
            if (text.isBlank()) return
            val highlight = settings.highlightSettings()
            val currentBookKey = ReaderHighlightBookContext.bookKey
            val enabledRules = highlight.rules.filter { rule ->
                if (!rule.enabled) return@filter false
                if (rule.bookKey.isBlank()) {
                    snapshot.canHighlightReaderDialogue
                } else {
                    rule.bookKey == currentBookKey
                }
            }
            if (enabledRules.isEmpty()) return
            val dark = isNightMode()
            val protectedRanges = protectedStyledElementRanges(contentDom, text)
            val singleQuoteRanges = enabledRules
                .filter { it.type == ReaderHighlightRuleType.SingleQuotePhrase }
                .flatMap { findQuoteRanges(text, SINGLE_QUOTES) }
            val rangeObjects = enabledRules.flatMap { rule ->
                val ranges = findRanges(
                    text = text,
                    rule = rule,
                    protectedRanges = protectedRanges,
                    singleQuoteRanges = singleQuoteRanges,
                )
                if (ranges.isEmpty()) return@flatMap emptyList()
                val style = createHighlightSpanStyle(highlight.styleById(rule.styleId), dark) ?: return@flatMap emptyList()
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

    private fun findRanges(
        text: String,
        rule: ReaderHighlightRule,
        protectedRanges: List<IntRange>,
        singleQuoteRanges: List<IntRange>,
    ): List<IntRange> =
        when (rule.type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> {
                val excluded = protectedRanges + singleQuoteRanges
                findQuoteRanges(text, DOUBLE_QUOTES).flatMap { range -> subtractRanges(range, excluded) }
            }
            ReaderHighlightRuleType.SingleQuotePhrase -> findQuoteRanges(text, SINGLE_QUOTES)
            ReaderHighlightRuleType.FixedText -> findFixedTextRanges(text, rule.pattern)
            ReaderHighlightRuleType.Regex -> findRegexRanges(text, rule.pattern)
        }

    private fun protectedStyledElementRanges(contentDom: Any, renderedText: String): List<IntRange> {
        val children = callNoArg(contentDom, "getChildren") as? List<*> ?: return emptyList()
        val ranges = ArrayList<IntRange>()
        val text = StringBuilder(renderedText.length)
        children.filterNotNull().forEach { child ->
            appendNodeTextRanges(child, text, ranges)
        }
        if (text.length != renderedText.length || text.toString() != renderedText) {
            logProtectedRangeMismatch(text.length, renderedText.length)
            return emptyList()
        }
        return ranges
    }

    private fun appendNodeTextRanges(node: Any, text: StringBuilder, ranges: MutableList<IntRange>) {
        val tag = callNoArg(node, "getTag")?.toString().orEmpty()
        val children = callNoArg(node, "getChildren") as? List<*>
        if (children.isNullOrEmpty()) {
            text.append(callNoArg(node, "getText")?.toString().orEmpty())
            return
        }
        val start = text.length
        children.filterNotNull().forEach { child ->
            appendNodeTextRanges(child, text, ranges)
        }
        val end = text.length
        if (end > start && shouldProtectStyledElement(node, tag)) {
            ranges.add(exclusiveRange(start, end))
        }
    }

    private fun shouldProtectStyledElement(node: Any, tag: String): Boolean {
        if (tag.isBlank() || tag == MARKUP_TEXT) return false
        val isNonStyleSpan = runCatching {
            node.javaClass.methods.firstOrNull {
                it.name == "isNonStyleSpan" && it.parameterTypes.isEmpty()
            }?.invoke(node) as? Boolean
        }.getOrNull() == true
        if (isNonStyleSpan) return false
        if (tag in UNPROTECTED_INLINE_TAGS) return false
        return true
    }

    private fun subtractRanges(source: IntRange, exclusions: List<IntRange>): List<IntRange> {
        val normalized = exclusions
            .mapNotNull { overlap(source, it) }
            .sortedBy { it.first }
        if (normalized.isEmpty()) return listOf(source)
        val result = ArrayList<IntRange>()
        var cursor = source.first
        normalized.forEach { excluded ->
            if (excluded.first > cursor) result.add(exclusiveRange(cursor, excluded.first))
            cursor = maxOf(cursor, excluded.last)
        }
        if (cursor < source.last) result.add(exclusiveRange(cursor, source.last))
        return result.filter { it.last > it.first }
    }

    private fun overlap(left: IntRange, right: IntRange): IntRange? {
        val start = maxOf(left.first, right.first)
        val end = minOf(left.last, right.last)
        return if (end > start) exclusiveRange(start, end) else null
    }

    private fun exclusiveRange(start: Int, endExclusive: Int): IntRange =
        start..endExclusive

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

    private fun findFixedTextRanges(text: String, pattern: String): List<IntRange> {
        if (pattern.isBlank()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var index = text.indexOf(pattern)
        while (index >= 0) {
            ranges.add(exclusiveRange(index, index + pattern.length))
            index = text.indexOf(pattern, index + pattern.length)
        }
        return ranges
    }

    private fun findRegexRanges(text: String, pattern: String): List<IntRange> {
        if (pattern.isBlank()) return emptyList()
        return runCatching {
            Regex(pattern).findAll(text)
                .mapNotNull { match ->
                    val start = match.range.first
                    val end = match.range.last + 1
                    if (end > start) exclusiveRange(start, end) else null
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun createHighlightSpanStyle(style: ReaderHighlightStyle, dark: Boolean): Any? {
        val css = parseCssStyle(style.cssForTheme(dark))
        val color = composeColor(css.color.ifBlank { style.colorForTheme(dark) })
        val background = css.backgroundColor.takeIf { it.isNotBlank() }?.let(::composeColor)
        val fontSelection = style.fontFamilyForTheme(dark).ifBlank { settings.fontSettings().globalFamily }
        val fontFamily = resolveFontFamily(fontSelection)
        val fontWeight = css.fontWeight?.let(::resolveFontWeight)
        val fontStyle = css.fontStyle?.let(::resolveFontStyle)
        val textDecoration = css.textDecoration?.let(::resolveTextDecoration)
        val constructor = cls(SPAN_STYLE_CLASS).declaredConstructors.firstOrNull { it.parameterTypes.size == 18 }
            ?: return null
        constructor.isAccessible = true
        var mask = SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR
        if (fontWeight != null) mask = mask and SPAN_STYLE_MASK_FONT_WEIGHT.inv()
        if (fontStyle != null) mask = mask and SPAN_STYLE_MASK_FONT_STYLE.inv()
        if (fontFamily != null) mask = mask and SPAN_STYLE_MASK_FONT_FAMILY.inv()
        if (css.fontFeatureSettings.isNotBlank()) mask = mask and SPAN_STYLE_MASK_FONT_FEATURE.inv()
        if (background != null) mask = mask and SPAN_STYLE_MASK_BACKGROUND.inv()
        if (textDecoration != null) mask = mask and SPAN_STYLE_MASK_TEXT_DECORATION.inv()
        return constructor.newInstance(
            color,
            0L,
            fontWeight,
            fontStyle,
            null,
            fontFamily,
            css.fontFeatureSettings.ifBlank { null },
            0L,
            null,
            null,
            null,
            background ?: 0L,
            textDecoration,
            null,
            null,
            null,
            mask,
            null,
        )
    }

    private fun isNightMode(): Boolean {
        val context = activityProvider() ?: return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun parseCssStyle(css: String): CssStyle {
        if (css.isBlank()) return CssStyle()
        val values = css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) null else part.substring(0, index).trim().lowercase() to part.substring(index + 1).trim()
            }
            .toMap()
        return CssStyle(
            color = values["color"].orEmpty(),
            backgroundColor = values["background-color"].orEmpty().ifBlank { values["background"].orEmpty() },
            fontWeight = values["font-weight"]?.lowercase(),
            fontStyle = values["font-style"]?.lowercase(),
            fontFeatureSettings = values["font-feature-settings"].orEmpty(),
            textDecoration = values["text-decoration"]?.lowercase(),
        )
    }

    private fun resolveFontWeight(value: String): Any? {
        val normalized = value.trim().lowercase()
        val methodName = when (normalized) {
            "bold", "bolder", "700" -> "getBold"
            "normal", "400" -> "getNormal"
            "100" -> "getW100"
            "200" -> "getW200"
            "300" -> "getW300"
            "500" -> "getW500"
            "600" -> "getW600"
            "800" -> "getW800"
            "900" -> "getW900"
            else -> null
        }
        if (methodName != null) return fontWeight(methodName)
        val weight = normalized.toIntOrNull()?.coerceIn(1, 1000) ?: return null
        return cls(FONT_WEIGHT_CLASS).getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(weight)
    }

    private fun resolveFontStyle(value: String): Any? {
        val styleValue = when (value.trim().lowercase()) {
            "italic", "oblique" -> FONT_STYLE_ITALIC
            "normal" -> FONT_STYLE_NORMAL
            else -> return null
        }
        return cls(FONT_STYLE_CLASS).declaredMethods.firstOrNull { method ->
            method.name.contains("box") && method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.invoke(null, styleValue)
    }

    private fun resolveTextDecoration(value: String): Any? {
        val mask = value.split(Regex("\\s+"))
            .fold(0) { current, part ->
                when (part.trim().lowercase()) {
                    "underline" -> current or TEXT_DECORATION_UNDERLINE
                    "line-through", "linethrough" -> current or TEXT_DECORATION_LINE_THROUGH
                    "none" -> current
                    else -> current
                }
            }
        if (mask == 0) return null
        return cls(TEXT_DECORATION_CLASS)
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(mask)
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

    private fun logProtectedRangeMismatch(mappedLength: Int, renderedLength: Int) {
        val key = "protected-range-mismatch|$mappedLength|$renderedLength"
        if (key == lastAppliedLogKey) return
        lastAppliedLogKey = key
        XposedBridge.log("$LOG_PREFIX dialogue highlight protected ranges skipped mapped=$mappedLength rendered=$renderedLength")
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
        const val FONT_STYLE_CLASS = "androidx.compose.ui.text.font.FontStyle"
        const val TEXT_DECORATION_CLASS = "androidx.compose.ui.text.style.TextDecoration"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR = 65534
        const val SPAN_STYLE_MASK_FONT_WEIGHT = 4
        const val SPAN_STYLE_MASK_FONT_STYLE = 8
        const val SPAN_STYLE_MASK_FONT_FAMILY = 32
        const val SPAN_STYLE_MASK_FONT_FEATURE = 64
        const val SPAN_STYLE_MASK_BACKGROUND = 2048
        const val SPAN_STYLE_MASK_TEXT_DECORATION = 4096
        const val FONT_STYLE_NORMAL = 0
        const val FONT_STYLE_ITALIC = 1
        const val TEXT_DECORATION_UNDERLINE = 1
        const val TEXT_DECORATION_LINE_THROUGH = 2
        const val MARKUP_TEXT = "#text"
        val CONTENT_DOM_CLASS_CANDIDATES = listOf(
            "org.epub.html.node.ContentDom",
            "app.zhendong.epub.node.ContentDom",
        )
        val UNPROTECTED_INLINE_TAGS = emptySet<String>()
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
        val backgroundColor: String = "",
        val fontWeight: String? = null,
        val fontStyle: String? = null,
        val fontFeatureSettings: String = "",
        val textDecoration: String? = null,
    )
}
