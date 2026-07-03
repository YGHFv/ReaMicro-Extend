package com.reamicro.fix.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
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
import java.lang.reflect.Proxy

class ReaderDialogueHighlightHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, Field>()
    private val fontFamilyCache = HashMap<String, Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val ninePatchDrawableCache = HashMap<String, CachedImage>()
    private val rememberedNinePatchRanges = object : LinkedHashMap<String, List<NinePatchRange>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NinePatchRange>>?): Boolean =
            size > MAX_REMEMBERED_NINE_PATCH_TEXTS
    }
    @Volatile private var ninePatchDrawHookInstalled: Boolean = false
    @Volatile private var basicTextDrawHookInstalled: Boolean = false
    @Volatile private var selectionRectPaddingHookInstalled: Boolean = false
    @Volatile private var lastNinePatchLogKey: String = ""
    @Volatile private var lastAppliedLogKey: String = ""

    fun install() {
        CONTENT_DOM_CLASS_CANDIDATES.forEach(::hookContentDom)
        hookJustifyTextNinePatchDraw()
        hookBasicTextNinePatchDraw()
        hookSelectionLineRectsPadding()
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
        hookSelectionLineRectsPadding()
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
            val ninePatchAnnotations = ArrayList<NinePatchAnnotation>()
            val rangeObjects = enabledRules
                .sortedBy { highlightRulePriority(it.type) }
                .flatMap { rule ->
                val ranges = findRanges(
                    text = text,
                    rule = rule,
                    protectedRanges = protectedRanges,
                    singleQuoteRanges = singleQuoteRanges,
                )
                if (ranges.isEmpty()) return@flatMap emptyList()
                val highlightStyle = highlight.styleById(rule.styleIdForTheme(dark))
                val style = createHighlightSpanStyle(highlightStyle, dark) ?: return@flatMap emptyList()
                val ninePatchPath = highlightStyle.ninePatchPathForTheme(dark).trim()
                if (ninePatchPath.isNotBlank()) {
                    val highlightCss = highlightStyle.cssForTheme(dark)
                    val ninePatchSlice = highlightStyle.ninePatchSliceForTheme(dark)
                        .ifBlank { reedenNineSlice(highlightCss) }
                    ranges.forEach { range ->
                        ninePatchAnnotations.add(NinePatchAnnotation(ninePatchPath, ninePatchSlice, highlightCss, range.first, range.last))
                    }
                }
                ranges.mapNotNull { range -> createAnnotatedRange(style, range.first, range.last) }
            }
            if (rangeObjects.isEmpty()) return
            val originalSpanStyles = callNoArg(content, "getSpanStyles") as? List<*> ?: emptyList<Any>()
            val nextSpanStyles = ArrayList<Any>(originalSpanStyles.size + rangeObjects.size).apply {
                addAll(originalSpanStyles.filterNotNull())
                addAll(rangeObjects)
            }
            val nextContent = if (ninePatchAnnotations.isEmpty()) {
                newAnnotatedString(content, nextSpanStyles)
            } else {
                newAnnotatedStringWithNinePatchAnnotations(content, nextSpanStyles, ninePatchAnnotations)
            } ?: return
            field(contentDom.javaClass.name, "content").set(contentDom, nextContent)
            logApplied(text, rangeObjects.size)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX dialogue highlight apply failed: ${it.stackTraceToString()}")
        }
    }

    private fun highlightRulePriority(type: ReaderHighlightRuleType): Int =
        when (type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> 0
            ReaderHighlightRuleType.FixedText -> 1
            ReaderHighlightRuleType.Regex -> 1
            ReaderHighlightRuleType.SingleQuotePhrase -> 2
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
        val fontSize = css.fontSize?.let(::composeSpTextUnit)
        val fontSelection = style.fontFamilyForTheme(dark).ifBlank { settings.fontSettings().globalFamily }
        val fontFamily = resolveFontFamily(fontSelection)
        val fontWeight = css.fontWeight?.let(::resolveFontWeight)
        val fontStyle = css.fontStyle?.let(::resolveFontStyle)
        val textDecoration = css.textDecoration?.let(::resolveTextDecoration)
        val constructor = cls(SPAN_STYLE_CLASS).declaredConstructors.firstOrNull { it.parameterTypes.size == 18 }
            ?: return null
        constructor.isAccessible = true
        var mask = SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR
        if (fontSize != null) mask = mask and SPAN_STYLE_MASK_FONT_SIZE.inv()
        if (fontWeight != null) mask = mask and SPAN_STYLE_MASK_FONT_WEIGHT.inv()
        if (fontStyle != null) mask = mask and SPAN_STYLE_MASK_FONT_STYLE.inv()
        if (fontFamily != null) mask = mask and SPAN_STYLE_MASK_FONT_FAMILY.inv()
        if (css.fontFeatureSettings.isNotBlank()) mask = mask and SPAN_STYLE_MASK_FONT_FEATURE.inv()
        if (background != null) mask = mask and SPAN_STYLE_MASK_BACKGROUND.inv()
        if (textDecoration != null) mask = mask and SPAN_STYLE_MASK_TEXT_DECORATION.inv()
        return constructor.newInstance(
            color,
            fontSize ?: 0L,
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
            fontSize = values["font-size"]?.lowercase(),
        )
    }

    private fun composeSpTextUnit(value: String): Long? {
        val trimmed = value.trim().lowercase()
        if (trimmed.isBlank()) return null
        val number = Regex("""-?\d+(?:\.\d+)?""").find(trimmed)?.value?.toFloatOrNull() ?: return null
        val scaledDensity = activityProvider()?.resources?.displayMetrics?.scaledDensity ?: 1f
        val sp = when {
            trimmed.endsWith("px") -> number / scaledDensity
            trimmed.endsWith("em") -> number * 16f
            trimmed.endsWith("rem") -> number * 16f
            else -> number
        }.coerceIn(1f, 96f)
        return cls(TEXT_UNIT_KT_CLASS).methods.firstOrNull { method ->
            method.name == "getSp" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Float::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.invoke(null, sp) as? Long
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
        val argb = parseAndroidColor(value) ?: Color.parseColor(DEFAULT_DIALOGUE_COLOR)
        val packedArgb = argb.toLong() and 0xFFFFFFFFL
        return cls(COLOR_KT_CLASS).declaredMethods.firstOrNull { method ->
            method.name == "Color" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Long::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.invoke(null, packedArgb) as? Long ?: 0L
    }

    private fun parseAndroidColor(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        runCatching { Color.parseColor(trimmed) }.getOrNull()?.let { return it }
        val groups = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""")
            .find(trimmed)
            ?.groupValues
            ?: return null
        val alpha = groups.getOrNull(4)
            ?.takeIf { it.isNotBlank() }
            ?.toFloatOrNull()
            ?.let { if (it <= 1f) (it * 255).toInt() else it.toInt() }
            ?: 255
        return Color.argb(
            alpha.coerceIn(0, 255),
            groups[1].toInt().coerceIn(0, 255),
            groups[2].toInt().coerceIn(0, 255),
            groups[3].toInt().coerceIn(0, 255),
        )
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

    private fun newAnnotatedStringWithNinePatchAnnotations(
        original: Any,
        spanStyles: List<Any>,
        annotations: List<NinePatchAnnotation>,
    ): Any? =
        runCatching {
            val builderClass = cls(ANNOTATED_STRING_BUILDER_CLASS)
            val builder = builderClass.getDeclaredConstructor(String::class.java)
                .apply { isAccessible = true }
                .newInstance(callNoArg(original, "getText")?.toString().orEmpty())
            val addStringAnnotation = builderClass.methods.first {
                it.name == "addStringAnnotation" && it.parameterTypes.size == 4
            }
            val addStyleMethods = builderClass.methods.filter {
                it.name == "addStyle" && it.parameterTypes.size == 3
            }
            val toAnnotatedString = builderClass.methods.first {
                it.name == "toAnnotatedString" && it.parameterTypes.isEmpty()
            }
            val length = (callNoArg(original, "length") as? Int)
                ?: callNoArg(original, "getLength") as? Int
                ?: callNoArg(original, "getText")?.toString()?.length
                ?: 0
            val originalStringAnnotations = original.javaClass.methods.firstOrNull {
                it.name == "getStringAnnotations" && it.parameterTypes.size == 2
            }?.invoke(original, 0, length) as? List<*> ?: emptyList<Any>()
            originalStringAnnotations.filterNotNull().forEach { range ->
                addStringAnnotation.invoke(
                    builder,
                    callNoArg(range, "getTag")?.toString().orEmpty(),
                    callNoArg(range, "getItem")?.toString().orEmpty(),
                    callNoArg(range, "getStart") as? Int ?: return@forEach,
                    callNoArg(range, "getEnd") as? Int ?: return@forEach,
                )
            }
            annotations.forEach { annotation ->
                addStringAnnotation.invoke(
                    builder,
                    NINE_PATCH_ANNOTATION_TAG,
                    listOf(annotation.path, annotation.slice, annotation.css).joinToString(NINE_PATCH_ANNOTATION_SEPARATOR),
                    annotation.start,
                    annotation.end,
                )
            }
            (callNoArg(original, "getParagraphStyles") as? List<*>)
                ?.filterNotNull()
                ?.forEach { range -> addAnnotatedStyle(builder, addStyleMethods, range) }
            spanStyles.forEach { range -> addAnnotatedStyle(builder, addStyleMethods, range) }
            toAnnotatedString.invoke(builder)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX nine-patch annotation failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun addAnnotatedStyle(builder: Any, addStyleMethods: List<Method>, range: Any) {
        val item = callNoArg(range, "getItem") ?: return
        val start = callNoArg(range, "getStart") as? Int ?: return
        val end = callNoArg(range, "getEnd") as? Int ?: return
        addStyleMethods.firstOrNull { method ->
            method.parameterTypes[0].isAssignableFrom(item.javaClass)
        }?.invoke(builder, item, start, end)
    }

    private fun hookJustifyTextNinePatchDraw() {
        if (ninePatchDrawHookInstalled) return
        runCatching {
            val justifyTextClass = cls(JUSTIFY_TEXT_CLASS)
            val annotatedStringClass = cls(ANNOTATED_STRING_CLASS)
            val methods = justifyTextClass.declaredMethods.filter { method ->
                method.name == "JustifyText" &&
                    method.parameterTypes.size == 8 &&
                    method.parameterTypes[0] == annotatedStringClass
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rememberNinePatchRanges(param.args?.getOrNull(0))
                    }
                })
            }
            ninePatchDrawHookInstalled = methods.isNotEmpty()
            XposedBridge.log("$LOG_PREFIX nine-patch draw hook installed count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX nine-patch draw hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookBasicTextNinePatchDraw() {
        if (basicTextDrawHookInstalled) return
        runCatching {
            val basicTextClass = cls(BASIC_TEXT_CLASS)
            val annotatedStringClass = cls(ANNOTATED_STRING_CLASS)
            val modifierClass = cls(MODIFIER_CLASS)
            val methods = basicTextClass.declaredMethods.filter { method ->
                method.name.contains("BasicText") &&
                    method.parameterTypes.size >= 12 &&
                    method.parameterTypes[0] == annotatedStringClass &&
                    method.parameterTypes[1] == modifierClass
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        injectNinePatchDraw(
                            param = param,
                            onTextLayoutIndex = 3,
                            defaultMaskIndex = param.args?.lastIndex,
                            modifierDefaultMask = BASIC_TEXT_DEFAULT_MODIFIER,
                            onTextLayoutDefaultMask = BASIC_TEXT_DEFAULT_ON_TEXT_LAYOUT,
                        )
                    }
                })
            }
            basicTextDrawHookInstalled = methods.isNotEmpty()
            XposedBridge.log("$LOG_PREFIX basic text nine-patch draw hook installed count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX basic text nine-patch draw hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookSelectionLineRectsPadding() {
        if (selectionRectPaddingHookInstalled) return
        runCatching {
            val bodyClass = cls(BODY_KT_CLASS)
            val methods = bodyClass.declaredMethods.filter { method ->
                method.parameterTypes.size == 2 &&
                    (method.name.contains("selectionLineRects") || method.looksLikeSelectionLineRects())
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rects = param.result as? List<*> ?: return
                        if (rects.isEmpty()) return
                        param.result = expandSelectionRects(rects)
                    }
                })
            }
            selectionRectPaddingHookInstalled = methods.isNotEmpty()
            XposedBridge.log("$LOG_PREFIX selection rect padding hook installed count=${methods.size}")
        }.onFailure {
            if (it.javaClass.name.contains("ClassNotFound")) return
            XposedBridge.log("$LOG_PREFIX selection rect padding hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun Method.looksLikeSelectionLineRects(): Boolean =
        List::class.java.isAssignableFrom(returnType) &&
            parameterTypes.getOrNull(0)?.name == TEXT_LAYOUT_RESULT_CLASS &&
            parameterTypes.getOrNull(1)?.name == TEXT_RANGE_CLASS

    private fun expandSelectionRects(rects: List<*>): List<Any> {
        val density = activityProvider()?.resources?.displayMetrics?.density ?: 1f
        val topPadding = SELECTION_RECT_TOP_PADDING_DP * density
        val bottomPadding = SELECTION_RECT_BOTTOM_PADDING_DP * density
        return rects.mapNotNull { rect ->
            rect ?: return@mapNotNull null
            createGeometryRect(
                left = callNoArg(rect, "getLeft") as? Float ?: return@mapNotNull rect,
                top = (callNoArg(rect, "getTop") as? Float ?: return@mapNotNull rect) - topPadding,
                right = callNoArg(rect, "getRight") as? Float ?: return@mapNotNull rect,
                bottom = (callNoArg(rect, "getBottom") as? Float ?: return@mapNotNull rect) + bottomPadding,
            ) ?: rect
        }
    }

    private fun createGeometryRect(left: Float, top: Float, right: Float, bottom: Float): Any? =
        runCatching {
            cls(GEOMETRY_RECT_CLASS)
                .getDeclaredConstructor(
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .newInstance(left, top, right, bottom)
        }.getOrNull()

    private fun injectNinePatchDraw(
        param: XC_MethodHook.MethodHookParam,
        onTextLayoutIndex: Int,
        defaultMaskIndex: Int?,
        modifierDefaultMask: Int,
        onTextLayoutDefaultMask: Int,
    ) {
        runCatching {
            val annotatedString = param.args?.getOrNull(0) ?: return
            val ranges = ninePatchRanges(annotatedString).ifEmpty { rememberedNinePatchRanges(annotatedString) }
            if (ranges.isEmpty()) return
            val state = NinePatchDrawState(ranges)
            val modifier = drawBehindModifier(param.args?.getOrNull(1), state) ?: return
            val originalOnTextLayout = param.args?.getOrNull(onTextLayoutIndex)
            param.args[1] = modifier
            param.args[onTextLayoutIndex] = function1Proxy("NinePatchTextLayout") { args ->
                state.textLayoutResult = args?.getOrNull(0)
                invokeFunction1(originalOnTextLayout, args?.getOrNull(0))
                targetUnit()
            }
            val defaultMask = defaultMaskIndex?.let { param.args.getOrNull(it) as? Int }
            if (defaultMask != null) {
                param.args[defaultMaskIndex] = defaultMask and modifierDefaultMask.inv() and
                    onTextLayoutDefaultMask.inv()
            }
        }.onFailure {
            logNinePatchDrawFailure("inject", it)
        }
    }

    private fun rememberNinePatchRanges(annotatedString: Any?) {
        annotatedString ?: return
        val ranges = ninePatchRanges(annotatedString)
        if (ranges.isEmpty()) return
        val key = annotatedStringTextKey(annotatedString)
        if (key.isBlank()) return
        synchronized(rememberedNinePatchRanges) {
            rememberedNinePatchRanges[key] = ranges
        }
    }

    private fun rememberedNinePatchRanges(annotatedString: Any): List<NinePatchRange> {
        val key = annotatedStringTextKey(annotatedString)
        if (key.isBlank()) return emptyList()
        synchronized(rememberedNinePatchRanges) {
            return rememberedNinePatchRanges[key].orEmpty()
        }
    }

    private fun annotatedStringTextKey(annotatedString: Any): String =
        callNoArg(annotatedString, "getText")?.toString().orEmpty()

    private fun ninePatchRanges(annotatedString: Any): List<NinePatchRange> {
        val length = (callNoArg(annotatedString, "length") as? Int)
            ?: callNoArg(annotatedString, "getLength") as? Int
            ?: callNoArg(annotatedString, "getText")?.toString()?.length
            ?: 0
        val annotations = annotatedString.javaClass.methods.firstOrNull {
            it.name == "getStringAnnotations" && it.parameterTypes.size == 3
        }?.invoke(annotatedString, NINE_PATCH_ANNOTATION_TAG, 0, length) as? List<*>
            ?: return emptyList()
        return annotations.mapNotNull { range ->
            range ?: return@mapNotNull null
            val value = callNoArg(range, "getItem")?.toString().orEmpty()
            val parts = value.split(NINE_PATCH_ANNOTATION_SEPARATOR, limit = 3)
            val path = parts.getOrNull(0).orEmpty().trim()
            val slice = parts.getOrNull(1).orEmpty().trim()
            val css = parts.getOrNull(2).orEmpty()
            val start = (callNoArg(range, "getStart") as? Int ?: return@mapNotNull null).coerceIn(0, length)
            val end = (callNoArg(range, "getEnd") as? Int ?: return@mapNotNull null).coerceIn(0, length)
            if (path.isBlank() || end <= start) null else NinePatchRange(start, end, path, slice, css)
        }
    }

    private fun drawBehindModifier(baseModifier: Any?, state: NinePatchDrawState): Any? =
        runCatching {
            val modifier = baseModifier ?: modifierInstance()
            cls(DRAW_MODIFIER_KT_CLASS).methods.firstOrNull {
                it.name == "drawBehind" && it.parameterTypes.size == 2
            }?.invoke(null, modifier, function1Proxy("NinePatchDraw") {
                drawNinePatchBackgrounds(it?.getOrNull(0), state)
                targetUnit()
            })
        }.onFailure {
            logNinePatchDrawFailure("modifier", it)
        }.getOrNull()

    private fun drawNinePatchBackgrounds(drawScope: Any?, state: NinePatchDrawState) {
        val layout = state.textLayoutResult ?: return
        val canvas = nativeCanvas(drawScope) ?: return
        val density = activityProvider()?.resources?.displayMetrics?.density ?: 1f
        state.ranges.forEach { range ->
            val image = imageForNinePatch(range.path) ?: return@forEach
            val box = reedenBoxStyle(range.css, density).withMinimumVerticalPadding(
                top = MIN_NINE_PATCH_TOP_PADDING_DP * density,
                bottom = MIN_NINE_PATCH_BOTTOM_PADDING_DP * density,
            )
            val nineSlice = parseNineSlice(range.slice, image.bitmap.width, image.bitmap.height)
            lineRects(layout, range.start, range.end, box).forEach { rect ->
                val saveCount = if (box.radiusPx > 0f) {
                    val path = Path().apply {
                        addRoundRect(RectF(rect), box.radiusPx, box.radiusPx, Path.Direction.CW)
                    }
                    val count = canvas.save()
                    canvas.clipPath(path)
                    count
                } else {
                    -1
                }
                if (nineSlice != null) {
                    drawNineSlice(canvas, image.bitmap, nineSlice, rect)
                } else if (image.bitmap.ninePatchChunk == null) {
                    drawBitmapByBackgroundSize(canvas, image.bitmap, rect, box.backgroundSize)
                } else {
                    image.drawable.bounds = rect
                    image.drawable.draw(canvas)
                }
                if (saveCount >= 0) canvas.restoreToCount(saveCount)
                drawCssBorder(canvas, rect, box)
            }
        }
    }

    private fun lineRects(layout: Any, start: Int, end: Int, box: ReedenBoxStyle): List<Rect> {
        val lineForOffset = layout.javaClass.methods.firstOrNull {
            it.name == "getLineForOffset" && it.parameterTypes.size == 1
        } ?: return emptyList()
        val startLine = lineForOffset.invoke(layout, start) as? Int ?: return emptyList()
        val endLine = lineForOffset.invoke(layout, (end - 1).coerceAtLeast(start)) as? Int ?: startLine
        return (startLine..endLine).mapNotNull { line ->
            val lineStart = callInt(layout, "getLineStart", line) ?: return@mapNotNull null
            val lineEnd = lineEnd(layout, line) ?: return@mapNotNull null
            val localStart = maxOf(start, lineStart)
            val localEnd = minOf(end, lineEnd)
            if (localEnd <= localStart) return@mapNotNull null
            val firstCharRect = charRect(layout, localStart) ?: return@mapNotNull null
            val lastCharRect = charRect(layout, localEnd - 1) ?: return@mapNotNull null
            val left = firstCharRect.left
            val right = lastCharRect.right
            val top = minOf(firstCharRect.top, lastCharRect.top)
            val bottom = maxOf(firstCharRect.bottom, lastCharRect.bottom)
            Rect(
                (left - box.paddingLeftPx - box.marginLeftPx).toInt(),
                (top - box.paddingTopPx - box.marginTopPx).toInt(),
                (right + box.paddingRightPx + box.marginRightPx).toInt(),
                (bottom + box.paddingBottomPx + box.marginBottomPx).toInt(),
            )
        }
    }

    private fun lineEnd(layout: Any, line: Int): Int? {
        layout.javaClass.methods.firstOrNull {
            it.name == "getLineEnd" && it.parameterTypes.size == 2
        }?.let { return it.invoke(layout, line, false) as? Int }
        return layout.javaClass.methods.firstOrNull {
            it.name == "getLineEnd" && it.parameterTypes.size == 1
        }?.invoke(layout, line) as? Int
    }

    private fun charRect(layout: Any, offset: Int): TextBox? {
        val rect = layout.javaClass.methods.firstOrNull {
            it.name == "getBoundingBox" && it.parameterTypes.size == 1
        }?.invoke(layout, offset) ?: return null
        return TextBox(
            left = callNoArg(rect, "getLeft") as? Float ?: return null,
            top = callNoArg(rect, "getTop") as? Float ?: return null,
            right = callNoArg(rect, "getRight") as? Float ?: return null,
            bottom = callNoArg(rect, "getBottom") as? Float ?: return null,
        )
    }

    private fun nativeCanvas(drawScope: Any?): android.graphics.Canvas? {
        val drawContext = callNoArg(drawScope, "getDrawContext") ?: return null
        val composeCanvas = callNoArg(drawContext, "getCanvas") ?: return null
        val canvasKt = cls(ANDROID_CANVAS_KT_CLASS)
        return canvasKt.methods.firstOrNull {
            it.name == "getNativeCanvas" && it.parameterTypes.size == 1
        }?.invoke(null, composeCanvas) as? android.graphics.Canvas
    }

    private fun imageForNinePatch(path: String): CachedImage? {
        val file = File(path)
        if (!file.isFile) return null
        val cacheKey = file.absolutePath
        val modified = file.lastModified()
        synchronized(ninePatchDrawableCache) {
            ninePatchDrawableCache[cacheKey]?.takeIf { it.modified == modified }?.let { return it }
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val drawable = if (bitmap.ninePatchChunk != null) {
            NinePatchDrawable(activityProvider()?.resources, bitmap, bitmap.ninePatchChunk, Rect(), file.name)
        } else {
            BitmapDrawable(activityProvider()?.resources, bitmap)
        }
        val cached = CachedImage(modified, bitmap, drawable)
        synchronized(ninePatchDrawableCache) {
            ninePatchDrawableCache[cacheKey] = cached
        }
        return cached
    }

    private fun parseNineSlice(value: String, bitmapWidth: Int, bitmapHeight: Int): NineSlice? {
        if (value.isBlank()) return null
        val numbers = Regex("""-?\d+(?:\.\d+)?""").findAll(value).map { it.value.toFloat() }.toList()
        if (numbers.size < 4) return null
        val scaleX = if (numbers.size >= 6 && numbers[4] > 0f) bitmapWidth / numbers[4] else 1f
        val scaleY = if (numbers.size >= 6 && numbers[5] > 0f) bitmapHeight / numbers[5] else 1f
        val left = (numbers[0] * scaleX).toInt().coerceIn(0, bitmapWidth)
        val top = (numbers[1] * scaleY).toInt().coerceIn(0, bitmapHeight)
        val right = (numbers[2] * scaleX).toInt().coerceIn(left, bitmapWidth)
        val bottom = (numbers[3] * scaleY).toInt().coerceIn(top, bitmapHeight)
        if (right <= left || bottom <= top) return null
        return NineSlice(left, top, right, bottom)
    }

    private fun drawNineSlice(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, slice: NineSlice, dst: Rect) {
        val srcX = intArrayOf(0, slice.left, slice.right, bitmap.width)
        val srcY = intArrayOf(0, slice.top, slice.bottom, bitmap.height)
        val leftWidth = minOf(slice.left, dst.width() / 2)
        val rightWidth = minOf(bitmap.width - slice.right, dst.width() - leftWidth)
        val topHeight = minOf(slice.top, dst.height() / 2)
        val bottomHeight = minOf(bitmap.height - slice.bottom, dst.height() - topHeight)
        val dstX = intArrayOf(dst.left, dst.left + leftWidth, dst.right - rightWidth, dst.right)
        val dstY = intArrayOf(dst.top, dst.top + topHeight, dst.bottom - bottomHeight, dst.bottom)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val source = Rect(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1])
                val target = Rect(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1])
                if (source.width() > 0 && source.height() > 0 && target.width() > 0 && target.height() > 0) {
                    canvas.drawBitmap(bitmap, source, target, null)
                }
            }
        }
    }

    private fun drawBitmapByBackgroundSize(
        canvas: android.graphics.Canvas,
        bitmap: android.graphics.Bitmap,
        dst: Rect,
        backgroundSize: String,
    ) {
        if (backgroundSize.contains("100% 100%", ignoreCase = true) ||
            backgroundSize.contains("stretch", ignoreCase = true)
        ) {
            canvas.drawBitmap(bitmap, null, dst, null)
            return
        }
        drawCoverBitmap(canvas, bitmap, dst)
    }

    private fun drawCoverBitmap(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, dst: Rect) {
        if (dst.width() <= 0 || dst.height() <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = dst.width().toFloat() / dst.height().toFloat()
        val src = if (srcAspect > dstAspect) {
            val width = (bitmap.height * dstAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / dstAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        canvas.drawBitmap(bitmap, src, dst, null)
    }

    private fun drawCssBorder(canvas: android.graphics.Canvas, rect: Rect, box: ReedenBoxStyle) {
        if (box.borderWidthPx <= 0f || box.borderColor == null || rect.width() <= 0 || rect.height() <= 0) return
        val inset = box.borderWidthPx / 2f
        val borderRect = RectF(rect).apply { inset(inset, inset) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = box.borderWidthPx
            color = box.borderColor
        }
        if (box.radiusPx > 0f) {
            canvas.drawRoundRect(borderRect, box.radiusPx, box.radiusPx, paint)
        } else {
            canvas.drawRect(borderRect, paint)
        }
    }

    private fun reedenBoxStyle(css: String, density: Float): ReedenBoxStyle {
        val values = cssProperties(css)
        val padding = cssBoxEdges(values, "padding", density).scale(REEDEN_BOX_EDGE_SCALE)
        val margin = cssBoxEdges(values, "margin", density).scale(REEDEN_BOX_EDGE_SCALE)
        val border = values["border"].orEmpty()
        val borderWidth = values["border-width"]?.let { cssSizePx(it, density) }
            ?: Regex("""(\d+(?:\.\d+)?)px""").find(border)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { it * density }
            ?: 0f
        val borderColor = parseCssColor(values["border-color"].orEmpty())
            ?: Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""").find(border)?.value?.let(::parseCssColor)
        return ReedenBoxStyle(
            paddingLeftPx = padding.left,
            paddingTopPx = padding.top,
            paddingRightPx = padding.right,
            paddingBottomPx = padding.bottom,
            marginLeftPx = margin.left,
            marginTopPx = margin.top,
            marginRightPx = margin.right,
            marginBottomPx = margin.bottom,
            borderWidthPx = borderWidth,
            borderColor = borderColor,
            radiusPx = values["border-radius"]?.let { cssSizePx(it, density) } ?: 0f,
            backgroundSize = values["background-size"].orEmpty(),
        )
    }

    private fun cssProperties(css: String): Map<String, String> =
        css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) null else part.substring(0, index).trim().lowercase() to part.substring(index + 1).trim()
            }
            .toMap()

    private fun cssBoxEdges(values: Map<String, String>, prefix: String, density: Float): BoxEdges {
        val shorthand = values[prefix].orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { cssSizePx(it, density) }
        val top = values["$prefix-top"]?.let { cssSizePx(it, density) } ?: shorthand.getOrNull(0) ?: 0f
        val right = values["$prefix-right"]?.let { cssSizePx(it, density) } ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
        val bottom = values["$prefix-bottom"]?.let { cssSizePx(it, density) } ?: shorthand.getOrNull(2) ?: shorthand.getOrNull(0) ?: 0f
        val left = values["$prefix-left"]?.let { cssSizePx(it, density) } ?: shorthand.getOrNull(3) ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
        return BoxEdges(left = left, top = top, right = right, bottom = bottom)
    }

    private fun cssSizePx(value: String, density: Float): Float {
        val trimmed = value.trim().lowercase()
        val number = Regex("""-?\d+(?:\.\d+)?""").find(trimmed)?.value?.toFloatOrNull() ?: return 0f
        return when {
            trimmed.endsWith("px") -> number * density
            trimmed.endsWith("dp") -> number * density
            trimmed.endsWith("em") -> number * 16f * density
            trimmed.endsWith("rem") -> number * 16f * density
            else -> number * density
        }.coerceAtLeast(0f)
    }

    private fun parseCssColor(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        runCatching { Color.parseColor(trimmed) }.getOrNull()?.let { return it }
        val groups = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""")
            .find(trimmed)
            ?.groupValues
            ?: return null
        val alpha = groups.getOrNull(4)
            ?.takeIf { it.isNotBlank() }
            ?.toFloatOrNull()
            ?.let { if (it <= 1f) (it * 255).toInt() else it.toInt() }
            ?: 255
        return Color.argb(
            alpha.coerceIn(0, 255),
            groups[1].toInt().coerceIn(0, 255),
            groups[2].toInt().coerceIn(0, 255),
            groups[3].toInt().coerceIn(0, 255),
        )
    }

    private fun reedenNineSlice(css: String): String =
        Regex("""(?:--)?reeden-background-nine-slice\s*:\s*([^;]+)""")
            .findAll(css)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

    private fun modifierInstance(): Any? =
        fieldObjectOrNull(MODIFIER_CLASS, "INSTANCE") ?: fieldObjectOrNull(MODIFIER_CLASS, "Companion")

    private fun function1Proxy(name: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = cls(FUNCTION1_CLASS)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching { block(args) }
                    .onFailure { logNinePatchDrawFailure(name, it) }
                    .getOrElse { targetUnit() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun invokeFunction1(function: Any?, value: Any?) {
        if (function == null) return
        function.javaClass.methods.firstOrNull {
            it.name == "invoke" && it.parameterTypes.size == 1
        }?.invoke(function, value)
    }

    private fun callInt(target: Any, name: String, value: Int): Int? =
        target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1
        }?.invoke(target, value) as? Int

    private fun callFloat(target: Any, name: String, value: Int): Float? =
        target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1
        }?.invoke(target, value) as? Float

    private fun targetUnit(): Any? =
        fieldObjectOrNull(UNIT_CLASS, "INSTANCE")

    private fun logNinePatchDrawFailure(source: String, error: Throwable) {
        val key = "$source|${error.javaClass.name}|${error.message}"
        if (key == lastNinePatchLogKey) return
        lastNinePatchLogKey = key
        XposedBridge.log("$LOG_PREFIX nine-patch draw $source failed: ${error.stackTraceToString()}")
    }

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
        const val ANNOTATED_STRING_CLASS = "androidx.compose.ui.text.AnnotatedString"
        const val ANNOTATED_STRING_BUILDER_CLASS = "androidx.compose.ui.text.AnnotatedString\$Builder"
        const val ANNOTATED_RANGE_CLASS = "androidx.compose.ui.text.AnnotatedString\$Range"
        const val ANNOTATED_STRING_EXT_CLASS = "org.epub.utils.AnnotatedStringExtKt"
        const val JUSTIFY_TEXT_CLASS = "app.zhendong.reamicro.arch.components.JustifyText_androidKt"
        const val BODY_KT_CLASS = "org.epub.ui.BodyKt"
        const val TEXT_LAYOUT_RESULT_CLASS = "androidx.compose.ui.text.TextLayoutResult"
        const val TEXT_RANGE_CLASS = "androidx.compose.ui.text.TextRange"
        const val BASIC_TEXT_CLASS = "androidx.compose.foundation.text.BasicTextKt"
        const val DRAW_MODIFIER_KT_CLASS = "androidx.compose.ui.draw.DrawModifierKt"
        const val ANDROID_CANVAS_KT_CLASS = "androidx.compose.ui.graphics.AndroidCanvas_androidKt"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val GEOMETRY_RECT_CLASS = "androidx.compose.ui.geometry.Rect"
        const val TEXT_UNIT_KT_CLASS = "androidx.compose.ui.unit.TextUnitKt"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val UNIT_CLASS = "kotlin.Unit"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val FONT_STYLE_CLASS = "androidx.compose.ui.text.font.FontStyle"
        const val TEXT_DECORATION_CLASS = "androidx.compose.ui.text.style.TextDecoration"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR = 65534
        const val SPAN_STYLE_MASK_FONT_SIZE = 2
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
        const val JUSTIFY_TEXT_DEFAULT_MODIFIER = 2
        const val JUSTIFY_TEXT_DEFAULT_ON_TEXT_LAYOUT = 16
        const val BASIC_TEXT_DEFAULT_MODIFIER = 2
        const val BASIC_TEXT_DEFAULT_ON_TEXT_LAYOUT = 8
        const val MAX_REMEMBERED_NINE_PATCH_TEXTS = 128
        const val REEDEN_BOX_EDGE_SCALE = 0.78f
        const val SELECTION_RECT_TOP_PADDING_DP = 4f
        const val SELECTION_RECT_BOTTOM_PADDING_DP = 1f
        const val MIN_NINE_PATCH_TOP_PADDING_DP = 2f
        const val MIN_NINE_PATCH_BOTTOM_PADDING_DP = 1f
        const val NINE_PATCH_ANNOTATION_TAG = "reamicro.highlight.ninepatch"
        const val NINE_PATCH_ANNOTATION_SEPARATOR = "\u001F"
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
        val fontSize: String? = null,
    )

    private data class NinePatchAnnotation(
        val path: String,
        val slice: String,
        val css: String,
        val start: Int,
        val end: Int,
    )

    private data class NinePatchRange(
        val start: Int,
        val end: Int,
        val path: String,
        val slice: String,
        val css: String,
    )

    private data class NinePatchDrawState(
        val ranges: List<NinePatchRange>,
        @Volatile var textLayoutResult: Any? = null,
    )

    private data class TextBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private data class NineSlice(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class BoxEdges(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun scale(factor: Float): BoxEdges =
            BoxEdges(
                left = left * factor,
                top = top * factor,
                right = right * factor,
                bottom = bottom * factor,
            )
    }

    private data class ReedenBoxStyle(
        val paddingLeftPx: Float,
        val paddingTopPx: Float,
        val paddingRightPx: Float,
        val paddingBottomPx: Float,
        val marginLeftPx: Float,
        val marginTopPx: Float,
        val marginRightPx: Float,
        val marginBottomPx: Float,
        val borderWidthPx: Float,
        val borderColor: Int?,
        val radiusPx: Float,
        val backgroundSize: String,
    ) {
        fun withMinimumVerticalPadding(top: Float, bottom: Float): ReedenBoxStyle =
            copy(
                paddingTopPx = maxOf(paddingTopPx, top),
                paddingBottomPx = maxOf(paddingBottomPx, bottom),
            )
    }

    private data class CachedImage(
        val modified: Long,
        val bitmap: android.graphics.Bitmap,
        val drawable: Drawable,
    )
}
