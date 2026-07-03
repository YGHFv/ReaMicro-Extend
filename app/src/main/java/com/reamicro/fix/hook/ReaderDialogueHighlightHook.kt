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
import com.reamicro.fix.compat.ReaMicroHostCompat
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.ReaderHighlightRuleType
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ReaderDialogueHighlightHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val hostCompat = ReaMicroHostCompat(classLoader)
    private val fontFamilyCache = HashMap<String, Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val ninePatchDrawableCache = HashMap<String, CachedImage>()
    private val reedenBoxStyleCache = object : LinkedHashMap<String, ReedenBoxStyle>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReedenBoxStyle>?): Boolean =
            size > MAX_CACHED_REEDEN_STYLES
    }
    private val nineSliceCache = object : LinkedHashMap<String, NineSlice?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NineSlice?>?): Boolean =
            size > MAX_CACHED_NINE_SLICES
    }
    private val rememberedNinePatchRanges = object : LinkedHashMap<String, RememberedNinePatchText>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RememberedNinePatchText>?): Boolean =
            size > MAX_REMEMBERED_NINE_PATCH_TEXTS
    }
    private val derivedNinePatchRanges = object : LinkedHashMap<String, List<NinePatchRange>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NinePatchRange>>?): Boolean =
            size > MAX_DERIVED_NINE_PATCH_TEXTS
    }
    @Volatile private var ninePatchDrawHookInstalled: Boolean = false
    @Volatile private var basicTextDrawHookInstalled: Boolean = false
    @Volatile private var activityConfigurationHookInstalled: Boolean = false
    @Volatile private var lastObservedNightMode: Boolean? = null
    @Volatile private var lastHighlightRuntimeKey: String = ""
    @Volatile private var lastNinePatchLogKey: String = ""
    @Volatile private var lastAppliedLogKey: String = ""
    @Volatile private var lastHighlightPerformanceLogAtMs: Long = 0L

    fun install() {
        hostCompat.contentDomClassCandidates().forEach(::hookContentDom)
        hookJustifyTextNinePatchDraw()
        hookBasicTextNinePatchDraw()
        hookActivityConfigurationChanges()
    }

    private fun hookContentDom(className: String) {
        runCatching {
            val methods = hostCompat.contentDomOnContentMethods(className)
            if (methods.isEmpty()) return
            methods.forEach { method ->
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
        checkHighlightRuntimeVersion()
        runCatching {
            contentDom ?: return
            val content = callNoArg(contentDom, "getContent") ?: return
            val text = callNoArg(content, "getText")?.toString().orEmpty()
            val nextContent = highlightedAnnotatedString(content, contentDom) ?: return
            field(contentDom.javaClass.name, "content").set(contentDom, nextContent)
            logApplied(text, highlightMarkerRanges(nextContent).size)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX dialogue highlight apply failed: ${it.stackTraceToString()}")
        }
    }

    private fun refreshAnnotatedStringForCurrentTheme(annotatedString: Any?): Any? {
        checkHighlightRuntimeVersion()
        annotatedString ?: return null
        val markerCount = highlightMarkerRanges(annotatedString).size
        val rawNineRanges = ninePatchRanges(annotatedString)
        val currentMarker = hasCurrentReaderHighlightMarker(annotatedString)
        if (markerCount == 0 && rawNineRanges.isEmpty()) return null
        if (currentMarker) return null
        val rebuilt = highlightedAnnotatedString(annotatedString, contentDom = null)
        if (rebuilt != null) return rebuilt
        return refreshedStaleNinePatchAnnotatedString(annotatedString)
    }

    private fun highlightedAnnotatedString(original: Any, contentDom: Any?): Any? {
        val snapshot = settings.snapshot()
        if (!snapshot.canHighlightReaderDialogue) return null
        val text = callNoArg(original, "getText")?.toString().orEmpty()
        if (text.isBlank()) return null
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
        if (enabledRules.isEmpty()) return null
        val dark = isNightMode()
        val protectedRanges = contentDom?.let { protectedStyledElementRanges(it, text) }.orEmpty()
        val singleQuoteRanges = enabledRules
            .filter { it.type == ReaderHighlightRuleType.SingleQuotePhrase }
            .flatMap { findQuoteRanges(text, SINGLE_QUOTES) }
        val ninePatchAnnotations = ArrayList<NinePatchAnnotation>()
        val markerRanges = ArrayList<IntRange>()
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
                markerRanges.addAll(ranges)
                ranges.mapNotNull { range -> createAnnotatedRange(style, range.first, range.last) }
            }
        if (rangeObjects.isEmpty()) return null
        val originalSpanStyles = callNoArg(original, "getSpanStyles") as? List<*> ?: emptyList<Any>()
        val nextSpanStyles = ArrayList<Any>(originalSpanStyles.size + rangeObjects.size).apply {
            addAll(originalSpanStyles.filterNotNull())
            addAll(rangeObjects)
        }
        logHighlightPerformance {
            "apply text=${text.length} highlights=${markerRanges.size} ninePatch=${ninePatchAnnotations.size} rules=${enabledRules.size}"
        }
        return newAnnotatedStringWithNinePatchAnnotations(original, nextSpanStyles, ninePatchAnnotations, markerRanges)
            ?: newAnnotatedString(original, nextSpanStyles)
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
        val fontSelection = style.fontFamilyForTheme(dark).ifBlank { settings.fontSettings().globalFamily }
        val fontFamily = resolveFontFamily(fontSelection)
        val constructor = cls(SPAN_STYLE_CLASS).declaredConstructors.firstOrNull { it.parameterTypes.size == 18 }
            ?: return null
        constructor.isAccessible = true
        var mask = SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR
        if (fontFamily != null) mask = mask and SPAN_STYLE_MASK_FONT_FAMILY.inv()
        if (background != null) mask = mask and SPAN_STYLE_MASK_BACKGROUND.inv()
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
            background ?: 0L,
            null,
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
        )
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
        highlightRanges: List<IntRange> = emptyList(),
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
                val tag = callNoArg(range, "getTag")?.toString().orEmpty()
                if (tag == NINE_PATCH_ANNOTATION_TAG || tag == HIGHLIGHT_ANNOTATION_TAG) return@forEach
                addStringAnnotation.invoke(
                    builder,
                    tag,
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
            val token = highlightMarkerToken()
            highlightRanges.forEach { range ->
                addStringAnnotation.invoke(
                    builder,
                    HIGHLIGHT_ANNOTATION_TAG,
                    token,
                    range.first,
                    range.last,
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
            val methods = hostCompat.justifyTextMethods()
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshAnnotatedStringForCurrentTheme(param.args?.getOrNull(0))?.let { param.args[0] = it }
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

    private fun hookActivityConfigurationChanges() {
        if (activityConfigurationHookInstalled) return
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let(::handleReaderNightModeObserved)
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let(::handleReaderNightModeObserved)
                }
            })
            activityConfigurationHookInstalled = true
            XposedBridge.log("$LOG_PREFIX reader night-mode refresh hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader night-mode refresh hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun handleReaderNightModeObserved(activity: Activity) {
        val current = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val previous = lastObservedNightMode
        lastObservedNightMode = current
        if (previous == null || previous == current) return
        if (ReaderHighlightBookContext.bookKey.isBlank()) return
        if (!settings.snapshot().canHighlightReaderDialogue) return
        ReaderHighlightBookContext.bumpVersion("night-mode", requestRefresh = false)
        checkHighlightRuntimeVersion()
        activity.window?.decorView?.post { activity.window?.decorView?.invalidate() }
    }

    private fun checkHighlightRuntimeVersion() {
        val key = highlightMarkerToken()
        if (key == lastHighlightRuntimeKey) return
        lastHighlightRuntimeKey = key
        clearHighlightRuntimeCaches()
    }

    private fun clearHighlightRuntimeCaches() {
        synchronized(ninePatchDrawableCache) { ninePatchDrawableCache.clear() }
        synchronized(reedenBoxStyleCache) { reedenBoxStyleCache.clear() }
        synchronized(nineSliceCache) { nineSliceCache.clear() }
        synchronized(rememberedNinePatchRanges) { rememberedNinePatchRanges.clear() }
        synchronized(derivedNinePatchRanges) { derivedNinePatchRanges.clear() }
        lastNinePatchLogKey = ""
        lastAppliedLogKey = ""
    }

    private fun hookBasicTextNinePatchDraw() {
        if (basicTextDrawHookInstalled) return
        runCatching {
            val methods = hostCompat.basicTextMethods()
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshAnnotatedStringForCurrentTheme(param.args?.getOrNull(0))?.let { param.args[0] = it }
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

    private fun injectNinePatchDraw(
        param: XC_MethodHook.MethodHookParam,
        onTextLayoutIndex: Int,
        defaultMaskIndex: Int?,
        modifierDefaultMask: Int,
        onTextLayoutDefaultMask: Int,
    ) {
        runCatching {
            checkHighlightRuntimeVersion()
            val annotatedString = param.args?.getOrNull(0) ?: return
            val directRanges = currentNinePatchRanges(annotatedString)
            val rememberedRanges = if (directRanges.isEmpty()) rememberedNinePatchRanges(annotatedString) else emptyList()
            val ranges = directRanges.ifEmpty { rememberedRanges }
            if (ranges.isEmpty()) return
            val state = NinePatchDrawState(ranges, highlightMarkerToken())
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
        checkHighlightRuntimeVersion()
        annotatedString ?: return
        val ranges = currentNinePatchRanges(annotatedString)
        if (ranges.isEmpty()) return
        val key = annotatedStringTextKey(annotatedString)
        if (key.isBlank()) return
        synchronized(rememberedNinePatchRanges) {
            rememberedNinePatchRanges[key] = RememberedNinePatchText(key, ranges)
        }
    }

    private fun rememberedNinePatchRanges(annotatedString: Any): List<NinePatchRange> {
        checkHighlightRuntimeVersion()
        val key = annotatedStringTextKey(annotatedString)
        if (key.isBlank()) return emptyList()
        synchronized(rememberedNinePatchRanges) {
            rememberedNinePatchRanges[key]?.ranges?.let { return it }
        }
        synchronized(derivedNinePatchRanges) {
            derivedNinePatchRanges[key]?.let { return it }
        }
        val derived = deriveRememberedNinePatchRanges(key)
        if (derived.isNotEmpty()) {
            synchronized(derivedNinePatchRanges) {
                derivedNinePatchRanges[key] = derived
            }
        }
        return derived
    }

    private fun annotatedStringTextKey(annotatedString: Any): String =
        callNoArg(annotatedString, "getText")?.toString().orEmpty()

    private fun currentNinePatchRanges(annotatedString: Any): List<NinePatchRange> =
        if (hasCurrentReaderHighlightMarker(annotatedString)) ninePatchRanges(annotatedString) else emptyList()

    private fun hasReaderHighlightMarker(annotatedString: Any): Boolean =
        highlightMarkerRanges(annotatedString).isNotEmpty()

    private fun hasCurrentReaderHighlightMarker(annotatedString: Any): Boolean {
        val token = highlightMarkerToken()
        return stringAnnotations(annotatedString, HIGHLIGHT_ANNOTATION_TAG)
            .any { callNoArg(it, "getItem")?.toString().orEmpty() == token }
    }

    private fun highlightMarkerRanges(annotatedString: Any): List<IntRange> =
        stringAnnotations(annotatedString, HIGHLIGHT_ANNOTATION_TAG).mapNotNull { range ->
            val start = callNoArg(range, "getStart") as? Int ?: return@mapNotNull null
            val end = callNoArg(range, "getEnd") as? Int ?: return@mapNotNull null
            if (end > start) exclusiveRange(start, end) else null
        }

    private fun highlightMarkerToken(): String =
        "${ReaderHighlightBookContext.version()}|${isNightMode()}"

    private fun currentHighlightRuntimeKey(): String =
        lastHighlightRuntimeKey.ifBlank { highlightMarkerToken() }

    private fun stringAnnotations(annotatedString: Any, tag: String): List<*> {
        val length = (callNoArg(annotatedString, "length") as? Int)
            ?: callNoArg(annotatedString, "getLength") as? Int
            ?: callNoArg(annotatedString, "getText")?.toString()?.length
            ?: 0
        return annotatedString.javaClass.methods.firstOrNull {
            it.name == "getStringAnnotations" && it.parameterTypes.size == 3
        }?.invoke(annotatedString, tag, 0, length) as? List<*> ?: emptyList<Any>()
    }

    private fun refreshedStaleNinePatchAnnotatedString(annotatedString: Any): Any? {
        val staleRanges = ninePatchRanges(annotatedString)
        if (staleRanges.isEmpty()) return null
        val dark = isNightMode()
        val refreshed = staleRanges.mapNotNull { range ->
            val style = currentStyleForStaleNinePatchRange(range, dark) ?: return@mapNotNull null
            val css = style.cssForTheme(dark)
            val spanStyle = createHighlightSpanStyle(style, dark) ?: return@mapNotNull null
            val path = style.ninePatchPathForTheme(dark).trim()
            val slice = style.ninePatchSliceForTheme(dark).ifBlank { reedenNineSlice(css) }
            RefreshedNinePatchRange(
                annotation = path.takeIf { it.isNotBlank() }
                    ?.let { NinePatchAnnotation(it, slice, css, range.start, range.end) },
                spanRange = createAnnotatedRange(spanStyle, range.start, range.end) ?: return@mapNotNull null,
                markerRange = exclusiveRange(range.start, range.end),
            )
        }
        if (refreshed.isEmpty()) return null
        val originalSpanStyles = callNoArg(annotatedString, "getSpanStyles") as? List<*> ?: emptyList<Any>()
        val nextSpanStyles = ArrayList<Any>(originalSpanStyles.size + refreshed.size).apply {
            addAll(originalSpanStyles.filterNotNull())
            addAll(refreshed.map { it.spanRange })
        }
        return newAnnotatedStringWithNinePatchAnnotations(
            original = annotatedString,
            spanStyles = nextSpanStyles,
            annotations = refreshed.mapNotNull { it.annotation },
            highlightRanges = refreshed.map { it.markerRange },
        )
    }

    private fun currentStyleForStaleNinePatchRange(range: NinePatchRange, dark: Boolean): ReaderHighlightStyle? {
        val snapshot = settings.snapshot()
        if (!snapshot.canHighlightReaderDialogue) return null
        val highlight = settings.highlightSettings()
        val currentBookKey = ReaderHighlightBookContext.bookKey
        val enabledRules = highlight.rules.filter { rule ->
            rule.enabled && (rule.bookKey.isBlank() || rule.bookKey == currentBookKey)
        }
        if (enabledRules.isEmpty()) return null
        val staleStyleIds = highlight.styles
            .filter { style -> styleMatchesStaleNinePatchRange(style, range) }
            .map { it.id }
            .toSet()
        val rule = if (staleStyleIds.isNotEmpty()) {
            enabledRules.firstOrNull { it.styleId in staleStyleIds || it.darkStyleId in staleStyleIds }
        } else {
            null
        } ?: enabledRules.firstOrNull { it.type == ReaderHighlightRuleType.DoubleQuoteDialogue }
            ?: enabledRules.first()
        val nextStyle = highlight.styleById(rule.styleIdForTheme(dark))
        return nextStyle
    }

    private fun styleMatchesStaleNinePatchRange(style: ReaderHighlightStyle, range: NinePatchRange): Boolean {
        val paths = listOf(style.ninePatchPath, style.darkNinePatchPath).filter { it.isNotBlank() }
        val cssValues = listOf(style.css, style.darkCss).filter { it.isNotBlank() }
        return paths.any { it == range.path } || cssValues.any { it == range.css }
    }

    private fun deriveRememberedNinePatchRanges(currentText: String): List<NinePatchRange> {
        if (currentText.isBlank()) return emptyList()
        val current = normalizedTextWithSourceMap(currentText)
        if (current.text.isBlank()) return emptyList()
        val remembered = synchronized(rememberedNinePatchRanges) {
            rememberedNinePatchRanges.values.toList().asReversed()
        }
        remembered.forEach { source ->
            val mapped = mapRememberedRangesToCurrentPage(source, current)
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyList()
    }

    private fun mapRememberedRangesToCurrentPage(
        source: RememberedNinePatchText,
        current: NormalizedText,
    ): List<NinePatchRange> {
        val original = normalizedTextWithSourceMap(source.text)
        if (original.text.isBlank()) return emptyList()
        val currentStartInOriginal = original.text.indexOf(current.text)
        if (currentStartInOriginal < 0) return emptyList()
        val currentEndInOriginal = currentStartInOriginal + current.text.length
        val mapped = source.ranges.mapNotNull { range ->
            val normalizedStart = original.sourceIndices.indexOfFirst { it >= range.start }
            val normalizedEnd = original.sourceIndices.indexOfLast { it < range.end } + 1
            if (normalizedStart < 0 || normalizedEnd <= normalizedStart) return@mapNotNull null
            val overlapStart = maxOf(normalizedStart, currentStartInOriginal)
            val overlapEnd = minOf(normalizedEnd, currentEndInOriginal)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val localStart = overlapStart - currentStartInOriginal
            val localEnd = overlapEnd - currentStartInOriginal
            range.copy(
                start = current.sourceIndices.getOrNull(localStart) ?: return@mapNotNull null,
                end = (current.sourceIndices.getOrNull(localEnd - 1) ?: return@mapNotNull null) + 1,
            )
        }
        return mapped
    }

    private fun normalizedTextWithSourceMap(value: String): NormalizedText {
        val builder = StringBuilder(value.length)
        val indices = ArrayList<Int>(value.length)
        value.forEachIndexed { index, char ->
            if (!char.isWhitespace() && char != '\u200B' && char != '\u200C' && char != '\u200D' && char != '\uFEFF') {
                builder.append(char)
                indices.add(index)
            }
        }
        return NormalizedText(builder.toString(), indices)
    }

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
        if (state.token != currentHighlightRuntimeKey()) return
        val layout = state.textLayoutResult ?: return
        val canvas = nativeCanvas(drawScope) ?: return
        val density = activityProvider()?.resources?.displayMetrics?.density ?: 1f
        val rectsByRange = cachedLineRects(layout, density, state)
        state.ranges.forEachIndexed { index, range ->
            val image = imageForNinePatch(range.path)
            if (image == null) return@forEachIndexed
            val box = reedenBoxStyle(range.css, density)
            val nineSlice = parseNineSlice(range.slice, image.bitmap.width, image.bitmap.height)
            val rects = rectsByRange.getOrNull(index).orEmpty()
            rects.forEach { rect ->
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

    private fun cachedLineRects(layout: Any, density: Float, state: NinePatchDrawState): List<List<Rect>> {
        val densityBits = density.toBits()
        synchronized(state) {
            val cached = state.cachedLineRects
            if (state.cachedLayoutResult === layout && state.cachedDensityBits == densityBits && cached != null) {
                state.rectCacheHits += 1
                logHighlightPerformance {
                    "draw ninePatch=${state.ranges.size} rectCache=hit hits=${state.rectCacheHits} builds=${state.rectCacheBuilds} rects=${state.rectLineCalculations}"
                }
                return cached
            }
            val next = state.ranges.map { range ->
                lineRects(layout, range.start, range.end, reedenBoxStyle(range.css, density))
            }
            state.cachedLayoutResult = layout
            state.cachedDensityBits = densityBits
            state.cachedLineRects = next
            state.rectCacheBuilds += 1
            state.rectLineCalculations += next.sumOf { it.size }
            logHighlightPerformance {
                "draw ninePatch=${state.ranges.size} rectCache=miss hits=${state.rectCacheHits} builds=${state.rectCacheBuilds} rects=${state.rectLineCalculations}"
            }
            return next
        }
    }

    private fun lineRects(layout: Any, start: Int, end: Int, box: ReedenBoxStyle): List<Rect> {
        val lineForOffset = layout.javaClass.methods.firstOrNull {
            it.name == "getLineForOffset" && it.parameterTypes.size == 1
        } ?: return emptyList()
        val startLine = runCatching { lineForOffset.invoke(layout, start) as? Int }.getOrNull()
            ?: return emptyList()
        val endLine = runCatching { lineForOffset.invoke(layout, (end - 1).coerceAtLeast(start)) as? Int }.getOrNull()
            ?: startLine
        val lineCount = callNoArg(layout, "getLineCount") as? Int
        if (lineCount != null && lineCount <= 0) return emptyList()
        val firstLine = if (lineCount != null) startLine.coerceIn(0, lineCount - 1) else startLine
        val lastLine = if (lineCount != null) endLine.coerceIn(0, lineCount - 1) else endLine
        if (lastLine < firstLine) return emptyList()
        return (firstLine..lastLine).mapNotNull { line ->
            val lineStart = callInt(layout, "getLineStart", line)
                ?: return@mapNotNull null
            val lineEnd = lineEnd(layout, line)
                ?: return@mapNotNull null
            val localStart = maxOf(start, lineStart)
            val localEnd = minOf(end, lineEnd)
            if (localEnd <= localStart) return@mapNotNull null
            val left = charRect(layout, localStart)?.left ?: return@mapNotNull null
            val right = charRect(layout, localEnd - 1)?.right ?: return@mapNotNull null
            val top = callFloat(layout, "getLineTop", line) ?: charRect(layout, localStart)?.top ?: return@mapNotNull null
            val bottom = callFloat(layout, "getLineBottom", line) ?: charRect(layout, localStart)?.bottom ?: return@mapNotNull null
            Rect(
                (left - box.paddingLeftPx - box.marginLeftPx).toInt(),
                (top - box.paddingTopPx - box.marginTopPx).toInt(),
                (right + box.paddingRightPx + box.marginRightPx).toInt(),
                (bottom + box.paddingBottomPx + box.marginBottomPx).toInt(),
            )
        }
    }

    private fun lineEnd(layout: Any, line: Int): Int? {
        instanceMethod(layout, "getLineEnd", 2)?.let { method ->
            return runCatching { method.invoke(layout, line, false) as? Int }.getOrNull()
        }
        val method = instanceMethod(layout, "getLineEnd", 1) ?: return null
        return runCatching { method.invoke(layout, line) as? Int }.getOrNull()
    }

    private fun charRect(layout: Any, offset: Int): TextBox? {
        val method = instanceMethod(layout, "getBoundingBox", 1) ?: return null
        val rect = runCatching { method.invoke(layout, offset) }.getOrNull() ?: return null
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
        val cacheKey = "$bitmapWidth:$bitmapHeight:$value"
        synchronized(nineSliceCache) {
            if (nineSliceCache.containsKey(cacheKey)) return nineSliceCache[cacheKey]
        }
        val parsed = parseNineSliceUncached(value, bitmapWidth, bitmapHeight)
        synchronized(nineSliceCache) {
            nineSliceCache[cacheKey] = parsed
        }
        return parsed
    }

    private fun parseNineSliceUncached(value: String, bitmapWidth: Int, bitmapHeight: Int): NineSlice? {
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
        val cacheKey = "${density.toBits()}:$css"
        synchronized(reedenBoxStyleCache) {
            reedenBoxStyleCache[cacheKey]?.let { return it }
        }
        val style = reedenBoxStyleUncached(css, density)
        synchronized(reedenBoxStyleCache) {
            reedenBoxStyleCache[cacheKey] = style
        }
        return style
    }

    private fun reedenBoxStyleUncached(css: String, density: Float): ReedenBoxStyle {
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
                if (index <= 0) {
                    null
                } else {
                    val key = part.substring(0, index).trim().lowercase()
                    val value = part.substring(index + 1).trim()
                    if (isSupportedReaderHighlightCssProperty(key, value)) key to value else null
                }
            }
            .toMap()

    private fun isSupportedReaderHighlightCssProperty(key: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (value.contains("url(", ignoreCase = true)) return false
        return when (key) {
            "color", "background", "background-color", "border-color" ->
                parseCssColor(value) != null
            "background-size" ->
                isSupportedReaderHighlightBackgroundSize(value)
            "border" ->
                readerHighlightCssSizeRegex.containsMatchIn(value) || readerHighlightCssColorRegex.containsMatchIn(value)
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

    private val readerHighlightCssSizeRegex = Regex("""\b\d+(?:\.\d+)?(?:px|dp|em|rem)?\b""", RegexOption.IGNORE_CASE)
    private val readerHighlightCssColorRegex = Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""")

    private fun isReaderHighlightCssSizeValue(value: String): Boolean =
        value.trim().matches(Regex("""\d+(?:\.\d+)?(?:px|dp|em|rem)?""", RegexOption.IGNORE_CASE))

    private fun isReaderHighlightCssBoxValue(value: String): Boolean {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.size in 1..4 && parts.all(::isReaderHighlightCssSizeValue)
    }

    private fun isSupportedReaderHighlightBackgroundSize(value: String): Boolean {
        val normalized = value.trim().lowercase().replace(Regex("\\s+"), " ")
        return normalized == "100% 100%" || normalized == "stretch"
    }

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
        instanceMethod(target, name, 1)?.let { method ->
            runCatching { method.invoke(target, value) as? Int }.getOrNull()
        }

    private fun callFloat(target: Any, name: String, value: Int): Float? =
        instanceMethod(target, name, 1)?.let { method ->
            runCatching { method.invoke(target, value) as? Float }.getOrNull()
        }

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

    private inline fun logHighlightPerformance(message: () -> String) {
        if (!settings.snapshot().canLogReaderHighlightPerformance) return
        val now = System.currentTimeMillis()
        if (now - lastHighlightPerformanceLogAtMs < HIGHLIGHT_PERFORMANCE_LOG_INTERVAL_MS) return
        lastHighlightPerformanceLogAtMs = now
        XposedBridge.log("$LOG_PREFIX highlight-perf ${message()}")
    }

    private fun cls(className: String): Class<*> =
        hostCompat.cls(className)

    private fun method(className: String, methodName: String, parameterCount: Int): Method =
        hostCompat.method(className, methodName, parameterCount)

    private fun field(className: String, fieldName: String) =
        hostCompat.field(className, fieldName)

    private fun instanceMethod(target: Any, methodName: String, parameterCount: Int): Method? =
        hostCompat.instanceMethod(target, methodName, parameterCount)

    private fun staticObject(className: String, fieldName: String): Any =
        cls(className).run {
            runCatching { getDeclaredField(fieldName) }
                .recoverCatching { getField(fieldName) }
                .getOrThrow()
                .apply { isAccessible = true }
                .get(null) ?: error("$className.$fieldName is null")
        }

    private fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        hostCompat.fieldObjectOrNull(className, fieldName)

    private fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null
        val method = instanceMethod(target, name, 0) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val DEFAULT_DIALOGUE_COLOR = "#FF9800"
        const val SPAN_STYLE_CLASS = ReaMicroHostCompat.ReaderHighlight.SPAN_STYLE_CLASS
        const val ANNOTATED_STRING_BUILDER_CLASS = ReaMicroHostCompat.ReaderHighlight.ANNOTATED_STRING_BUILDER_CLASS
        const val ANNOTATED_RANGE_CLASS = ReaMicroHostCompat.ReaderHighlight.ANNOTATED_RANGE_CLASS
        const val ANNOTATED_STRING_EXT_CLASS = ReaMicroHostCompat.ReaderHighlight.ANNOTATED_STRING_EXT_CLASS
        const val DRAW_MODIFIER_KT_CLASS = ReaMicroHostCompat.ReaderHighlight.DRAW_MODIFIER_KT_CLASS
        const val ANDROID_CANVAS_KT_CLASS = ReaMicroHostCompat.ReaderHighlight.ANDROID_CANVAS_KT_CLASS
        const val COLOR_KT_CLASS = ReaMicroHostCompat.ReaderHighlight.COLOR_KT_CLASS
        const val MODIFIER_CLASS = ReaMicroHostCompat.ReaderHighlight.MODIFIER_CLASS
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val UNIT_CLASS = "kotlin.Unit"
        const val FONT_PROVIDER_CLASS = ReaMicroHostCompat.ReaderHighlight.FONT_PROVIDER_CLASS
        const val FONT_FAMILY_CLASS = ReaMicroHostCompat.ReaderHighlight.FONT_FAMILY_CLASS
        const val FONT_FAMILY_KT_CLASS = ReaMicroHostCompat.ReaderHighlight.FONT_FAMILY_KT_CLASS
        const val FONT_WEIGHT_CLASS = ReaMicroHostCompat.ReaderHighlight.FONT_WEIGHT_CLASS
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val SPAN_STYLE_DEFAULT_MASK_EXCEPT_COLOR = 65534
        const val SPAN_STYLE_MASK_FONT_FAMILY = 32
        const val SPAN_STYLE_MASK_BACKGROUND = 2048
        const val JUSTIFY_TEXT_DEFAULT_MODIFIER = 2
        const val JUSTIFY_TEXT_DEFAULT_ON_TEXT_LAYOUT = 16
        const val BASIC_TEXT_DEFAULT_MODIFIER = 2
        const val BASIC_TEXT_DEFAULT_ON_TEXT_LAYOUT = 8
        const val MAX_REMEMBERED_NINE_PATCH_TEXTS = 128
        const val MAX_DERIVED_NINE_PATCH_TEXTS = 128
        const val MAX_CACHED_REEDEN_STYLES = 64
        const val MAX_CACHED_NINE_SLICES = 64
        const val HIGHLIGHT_PERFORMANCE_LOG_INTERVAL_MS = 1500L
        const val REEDEN_BOX_EDGE_SCALE = 0.78f
        const val HIGHLIGHT_ANNOTATION_TAG = "reamicro.highlight.span"
        const val NINE_PATCH_ANNOTATION_TAG = "reamicro.highlight.ninepatch"
        const val NINE_PATCH_ANNOTATION_SEPARATOR = "\u001F"
        const val MARKUP_TEXT = "#text"
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
        val token: String,
        @Volatile var textLayoutResult: Any? = null,
        @Volatile var cachedLayoutResult: Any? = null,
        @Volatile var cachedDensityBits: Int = 0,
        @Volatile var cachedLineRects: List<List<Rect>>? = null,
        var rectCacheHits: Int = 0,
        var rectCacheBuilds: Int = 0,
        var rectLineCalculations: Int = 0,
    )

    private data class RememberedNinePatchText(
        val text: String,
        val ranges: List<NinePatchRange>,
    )

    private data class RefreshedNinePatchRange(
        val annotation: NinePatchAnnotation?,
        val spanRange: Any,
        val markerRange: IntRange,
    )

    private data class NormalizedText(
        val text: String,
        val sourceIndices: List<Int>,
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
    )

    private data class CachedImage(
        val modified: Long,
        val bitmap: android.graphics.Bitmap,
        val drawable: Drawable,
    )
}
