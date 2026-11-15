package com.reamicro.fix.hook



import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.online.FanqieParagraphCommentApi
import com.reamicro.fix.online.OnlineParagraphCommentCacheStore
import com.reamicro.fix.online.OnlineParagraphCommentInjectionPlan
import com.reamicro.fix.online.OnlineParagraphCommentInjectionPlanner
import com.reamicro.fix.online.OnlineParagraphCommentRuntimePayload
import com.reamicro.fix.online.OnlineParagraphCommentRuntimePayloadCodec
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.reader.CrossParagraphRangeMapper
import com.reamicro.fix.reader.DialogueHighlightRangeFinder
import com.reamicro.fix.reader.HighlightDelimiterRangeFinder
import com.reamicro.fix.reader.HighlightParagraphIndex
import com.reamicro.fix.reader.ReaderHighlightRegex
import com.reamicro.fix.reader.HighlightOwnedRanges
import com.reamicro.fix.settings.ReaderHighlightSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.ReaderHighlightRuleType
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File

class ReaderDialogueHighlightHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val commentBindings get() = requireNotNull(com.reamicro.fix.core.HostParagraphCommentBindings.current)
    private val fontFamilyCache = ReaderFontFamilyCache<Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val onlineParagraphCommentDebugKeys = HashSet<String>()
    private val imageHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())
    private val imageWorker = com.reamicro.fix.reload.ModuleResources.pool(
        1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(32),
        { task -> Thread(task, "ReaMicroHighlightImage").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }
    private val imageCache = ReaderHighlightImageCache(
        clock = android.os.SystemClock::elapsedRealtime,
        submit = { task -> imageWorker.execute { task() } },
        deliver = { task -> imageHandler.post { task() } },
        load = ::loadNinePatchImage,
        weight = { image -> image.bitmap.allocationByteCount.toLong() },
        changed = ::invalidateImageNodes,
        isInUse = ::isImageInUse,
    )
    private val textNodeDrawStates = ReaderTextDrawCache<NinePatchDrawState>()
    private val annotationRangesCache = ReaderIdentityCache<Map<String, List<Any>>>()
    private val refreshedTextCache = ReaderIdentityCache<Any>()
    private val paragraphIndex = HighlightParagraphIndex<Any>()
    private val highlightStyleCache = object : LinkedHashMap<Triple<ReaderHighlightStyle, Boolean, String>, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<ReaderHighlightStyle, Boolean, String>, Any>?): Boolean = size > 64
    }
    private var enabledRuleKey: Triple<ReaderHighlightSettingsSnapshot, String, String>? = null
    private var enabledRuleSnapshot: List<ReaderHighlightRule> = emptyList()
    private val textBindings get() = requireNotNull(com.reamicro.fix.core.HostHighlightTextBindings.current)
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
    @Volatile private var basicTextDrawHookInstalled: Boolean = false
    @Volatile private var activityConfigurationHookInstalled: Boolean = false
    @Volatile private var lastObservedNightMode: Boolean? = null
    @Volatile private var lastHighlightRuntimeKey: String = ""
    @Volatile private var lastNinePatchLogKey: String = ""
    @Volatile private var lastAppliedLogKey: String = ""
    @Volatile private var lastHighlightPerformanceLogAtMs: Long = 0L
    fun install() {
        com.reamicro.fix.reload.ModuleResources.cleanup { paragraphIndex.clear() }
        com.reamicro.fix.core.HookInstallReport.installAll("ReaderDialogueHighlightHook", listOf(
            "content" to ::hookContentDom,
            "basicText" to ::hookBasicTextNinePatchDraw,
            "textNode" to ::hookTextNodeNinePatchDraw,
            "configuration" to ::hookActivityConfigurationChanges,
        ))
    }

    fun releaseMemory(reason: String) {
        clearHighlightRuntimeCaches()
        paragraphIndex.clear()
        imageCache.clear()
        imageWorker.queue.clear()
        fontFamilyCache.clear()
        synchronized(failedFontFamilyLogKeys) { failedFontFamilyLogKeys.clear() }
        synchronized(onlineParagraphCommentDebugKeys) { onlineParagraphCommentDebugKeys.clear() }
        logHighlightInfo("dialogue highlight memory released: $reason")
    }

    private fun hookContentDom() {
        runCatching {
            val symbols = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current) {
                "Highlight DOM contract unavailable"
            }
            val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                for (role in listOf("document.parse", "document.body")) {
                    handles += HookRuntime.hookMethod(symbols.method(role), object : HookCallback() {
                        override fun after(param: Call) {
                            if (param.throwable == null) registerParagraphRoot(param.result)
                        }
                    })
                }
                handles += HookRuntime.hookMethod(symbols.method("container.measure"), object : HookCallback() {
                    override fun before(param: Call) {
                        // A chapter loaded before hot reload may never parse again.
                        // Retain its logical child order without measuring neighboring text.
                        registerParagraphRoot(param.thisObject)
                    }
                })
                handles += HookRuntime.hookMethod(symbols.method("measure"), object : HookCallback() {
                    override fun before(param: Call) {
                        // First round has complete content, before font metrics and adaptive spacing.
                        if (param.args[symbols.roundIndex] == 0) applyDialogueHighlight(param.thisObject)
                    }
                })
                handles += HookRuntime.hookMethod(symbols.method("onMeasure"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable == null) applyOnlineParagraphComments(param.thisObject)
                    }
                })
                symbols.documentCallers().forEach(HookRuntime::deoptimize)
                HookRuntime.deoptimize(symbols.method("container.measure"))
                HookRuntime.deoptimize(symbols.method("onMeasure"))
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
                throw error
            }
            HookRuntime.logAlways("$LOG_PREFIX dialogue highlight before native adaptive measure installed")
        }.onFailure {
            HookRuntime.logError("$LOG_PREFIX dialogue highlight hook failed", it)
        }.getOrThrow()
    }
    private fun applyDialogueHighlight(contentDom: Any?) {
        val expectedVersion = highlightMarkerToken()
        checkHighlightRuntimeVersion()
        runCatching {
            contentDom ?: return
            val symbols = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current)
            val contentField = symbols.field("content")
            val content = contentField.get(contentDom) ?: return
            val text = annotatedStringText(content)
            val nextContent = highlightedAnnotatedString(content, contentDom) ?: return
            if (highlightMarkerToken() != expectedVersion) return
            contentField.set(contentDom, nextContent)
            logApplied(text, highlightMarkerRanges(nextContent).size)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX dialogue highlight apply failed: ${it.stackTraceToString()}")
        }
    }

    private fun applyOnlineParagraphComments(contentDom: Any?) {
        runCatching {
            if (!ONLINE_PARAGRAPH_COMMENTS_RUNTIME_ENABLED) return
            contentDom ?: return
            val activity = activityProvider() ?: return logOnlineParagraphCommentDebug("skip:no-activity")
            val context = activity.applicationContext
            val reader = OnlineReaderContextBridge.snapshot()
                ?: return logOnlineParagraphCommentDebug("skip:no-reader-context")
            val metadata = OnlineOnDemandMetadataStore.read(reader.bookDir)
                ?: return logOnlineParagraphCommentDebug("skip:no-metadata:${reader.bookDir.absolutePath}")
            val sourceId = metadata.sourceId.trim()
            if (sourceId.isBlank()) return logOnlineParagraphCommentDebug("skip:blank-source")
            if (!OnlineSourceDownloadPolicyStore.load(context, sourceId).paragraphCommentsEnabled) {
                return logOnlineParagraphCommentDebug("skip:disabled:$sourceId")
            }

            val locationSpine = contentDomSpineIndex(contentDom)
            val spineIndex = locationSpine
                ?: reader.spineIndex.takeIf { it >= 0 }
                ?: return logOnlineParagraphCommentDebug("skip:no-spine")
            val chapter = metadata.chapter(spineIndex)
                ?: return logOnlineParagraphCommentDebug("skip:no-chapter:$spineIndex")
            val bookId = FanqieParagraphCommentApi.bookId(metadata.detailUrl)
            val itemId = FanqieParagraphCommentApi.itemId(chapter.url)
            if (bookId.isBlank() || itemId.isBlank()) {
                return logOnlineParagraphCommentDebug("skip:identifiers:$spineIndex:${bookId.isNotBlank()}:${itemId.isNotBlank()}")
            }
            val cache = OnlineParagraphCommentCacheStore.read(
                rootDir = context.filesDir,
                sourceId = sourceId,
                bookId = bookId,
                itemId = itemId,
            ) ?: return logOnlineParagraphCommentDebug("skip:no-cache:$spineIndex:$bookId:$itemId")
            if (
                cache.sourceId != sourceId ||
                cache.bookId != bookId ||
                cache.itemId != itemId ||
                cache.comments.isEmpty()
            ) {
                return logOnlineParagraphCommentDebug(
                    "skip:cache-mismatch:$spineIndex:${cache.comments.size}:" +
                        "${cache.sourceId == sourceId}:${cache.bookId == bookId}:${cache.itemId == itemId}",
                )
            }

            val originalContent = textBindingsContent(contentDom)
                ?: return logOnlineParagraphCommentDebug("skip:no-content:$spineIndex")
            val originalText = annotatedStringText(originalContent)
            val originalPlaceholderMap = commentBindings.field("dom.images").get(contentDom) as? Map<*, *>
                ?: return logOnlineParagraphCommentDebug("skip:no-placeholder-map:$spineIndex")
            val originalPlaceholders = commentBindings.field("dom.placeholders").get(contentDom) as? Map<*, *>
                ?: return logOnlineParagraphCommentDebug("skip:no-placeholders:$spineIndex")
            val existingIds = onlineCommentRuntimeIds(originalContent, originalPlaceholderMap)
            val plan = OnlineParagraphCommentInjectionPlanner.plan(
                text = originalText,
                comments = cache.comments,
                existingIds = existingIds,
            ) ?: return logOnlineParagraphCommentDebug(
                "skip:no-plan:$spineIndex:text=${originalText.length}:comments=${cache.comments.size}:existing=${existingIds.size}",
            )
            val update = buildOnlineParagraphCommentUpdate(
                contentDom = contentDom,
                originalContent = originalContent,
                originalPlaceholderMap = originalPlaceholderMap,
                originalPlaceholders = originalPlaceholders,
                plan = plan,
            ) ?: return
            replaceOnlineParagraphCommentFields(contentDom, update)
            HookRuntime.log(
                "$LOG_PREFIX online paragraph comments injected spine=$spineIndex count=${plan.insertions.size}",
            )
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX online paragraph comment injection failed: ${it.stackTraceToString()}")
        }
    }

    private fun textBindingsContent(contentDom: Any): Any? =
        requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current).field("content").get(contentDom)

    private fun contentDomSpineIndex(contentDom: Any): Int? {
        val location = commentBindings.field("dom.location").get(contentDom) ?: return null
        val cfi = commentBindings.method("cfiText").invoke(location) as String
        val itemRef = com.reamicro.fix.reader.SearchCfiAddress.parse(cfi)?.steps?.getOrNull(1) ?: return null
        return OnlineReaderContextBridge.chapterIndexFromItemRef(itemRef)
    }

    private fun onlineCommentRuntimeIds(content: Any, placeholderMap: Map<*, *>): Set<String> {
        val result = placeholderMap.keys
            .mapNotNull { it?.toString() }
            .filterTo(linkedSetOf()) { it.startsWith(OnlineParagraphCommentInjectionPlanner.RUNTIME_ID_PREFIX) }
        stringAnnotations(content, INLINE_CONTENT_ANNOTATION_TAG).filterNotNull().forEach { range ->
            val tag = annotatedRangeTag(range)
            val item = annotatedRangeString(range)
            if (
                tag == INLINE_CONTENT_ANNOTATION_TAG &&
                item.startsWith(OnlineParagraphCommentInjectionPlanner.RUNTIME_ID_PREFIX)
            ) {
                result += item
            }
        }
        return result
    }

    private fun buildOnlineParagraphCommentUpdate(
        contentDom: Any,
        originalContent: Any,
        originalPlaceholderMap: Map<*, *>,
        originalPlaceholders: Map<*, *>,
        plan: OnlineParagraphCommentInjectionPlan,
    ): OnlineParagraphCommentUpdate? = runCatching {
        val annotations = allAnnotatedRanges(originalContent).map { range ->
            shiftedAnnotatedRange(range, plan)
                ?: error("无法迁移 AnnotatedString range")
        }.toMutableList()
        val placeholderMap = HashMap<Any, Any>(originalPlaceholderMap.size + plan.insertions.size)
        originalPlaceholderMap.forEach { (key, value) ->
            if (key != null && value != null) placeholderMap[key] = value
        }
        val placeholders = HashMap<Any, Any>(originalPlaceholders.size + plan.insertions.size)
        originalPlaceholders.forEach { (key, value) ->
            if (key == null || value == null) return@forEach
            placeholders[key] = shiftedAnnotatedRange(value, plan)
                ?: error("无法迁移已有 Placeholder range")
        }
        val fontSize = onlineCommentFontSize(contentDom)
        val composePlaceholder = createOnlineCommentComposePlaceholder(fontSize)
            ?: error("无法创建 Compose Placeholder")
        plan.insertions.forEach { insertion ->
            val offset = plan.insertedOffset(insertion)
            val end = offset + 1
            val encodedComment = encodeOnlineCommentAnnotation(insertion.stableId, insertion.comment)
                ?: error("无法创建 CommentAnnotation")
            annotations += createTaggedAnnotatedRange(
                encodedComment,
                offset,
                end,
                COMMENT_ANNOTATION_TAG,
            ) ?: error("无法创建评论 annotation range")
            annotations += createTaggedAnnotatedRange(
                insertion.stableId,
                offset,
                end,
                INLINE_CONTENT_ANNOTATION_TAG,
            ) ?: error("无法创建 inline annotation range")
            placeholderMap[insertion.stableId] = createOnlineCommentImagePlaceholder(
                runtimeId = insertion.stableId,
                fontSize = fontSize,
                annotation = encodedComment,
            ) ?: error("无法创建 ImagePlaceholder")
            placeholders[insertion.stableId] = createTaggedAnnotatedRange(
                composePlaceholder,
                offset,
                end,
                INLINE_CONTENT_ANNOTATION_TAG,
            ) ?: error("无法创建 Placeholder range")
        }
        val annotatedString = textBindings.annotated(plan.text, annotations)
        OnlineParagraphCommentUpdate(annotatedString, placeholderMap, placeholders)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX online paragraph comment build failed: ${it.stackTraceToString()}")
    }.getOrNull()

    private fun allAnnotatedRanges(content: Any): List<Any> = textBindings.annotations(content)
    private fun shiftedAnnotatedRange(range: Any, plan: OnlineParagraphCommentInjectionPlan): Any? {
        val item = annotatedRangeItem(range) ?: return null
        val start = annotatedRangeStart(range) ?: return null
        val end = annotatedRangeEnd(range) ?: return null
        val tag = annotatedRangeTag(range)
        return createTaggedAnnotatedRange(
            item = item,
            start = plan.shiftedOffset(start, includeInsertionAtOffset = true),
            endExclusive = plan.shiftedOffset(end, includeInsertionAtOffset = true),
            tag = tag,
        )
    }

    private fun createTaggedAnnotatedRange(item: Any, start: Int, endExclusive: Int, tag: String): Any? =
        textBindings.range(item, start, endExclusive, tag)

    private fun encodeOnlineCommentAnnotation(
        runtimeId: String,
        comment: com.reamicro.fix.online.OnlineParagraphCommentCount,
    ): String? = runCatching {
        val payload = OnlineParagraphCommentRuntimePayloadCodec.encode(
            OnlineParagraphCommentRuntimePayload(
                sourceId = comment.sourceId,
                bookId = comment.bookId,
                itemId = comment.itemId,
                paraIndex = comment.paraIndex,
                paragraphText = comment.paragraphText,
                paragraphHash = comment.paragraphHash,
                runtimeId = runtimeId,
            ),
        )
        commentBindings.encode(comment.paragraphText, payload, ONLINE_COMMENT_ANNOTATION_PREFIX, runtimeId)
    }.getOrNull()

    private fun onlineCommentFontSize(contentDom: Any): Float = commentBindings.fontSize(contentDom)
    private fun createOnlineCommentComposePlaceholder(fontSize: Float): Any? =
        runCatching { commentBindings.placeholder(fontSize) }.getOrNull()
    private fun createOnlineCommentImagePlaceholder(runtimeId: String, fontSize: Float, annotation: String): Any? =
        runCatching { commentBindings.image(runtimeId, fontSize, annotation) }.getOrNull()

    private fun replaceOnlineParagraphCommentFields(contentDom: Any, update: OnlineParagraphCommentUpdate) {
        synchronized(contentDom) {
            val contentField = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current).field("content")
            val placeholderMapField = commentBindings.field("dom.images")
            val placeholdersField = commentBindings.field("dom.placeholders")
            val originalContent = contentField.get(contentDom)
            val originalPlaceholderMap = placeholderMapField.get(contentDom)
            val originalPlaceholders = placeholdersField.get(contentDom)
            runCatching {
                placeholderMapField.set(contentDom, update.placeholderMap)
                placeholdersField.set(contentDom, update.placeholders)
                contentField.set(contentDom, update.content)
            }.onFailure { error ->
                runCatching { contentField.set(contentDom, originalContent) }
                runCatching { placeholderMapField.set(contentDom, originalPlaceholderMap) }
                runCatching { placeholdersField.set(contentDom, originalPlaceholders) }
                throw error
            }
        }
    }

    private fun refreshAnnotatedStringForCurrentTheme(annotatedString: Any?): Any? {
        val expectedVersion = highlightMarkerToken()
        checkHighlightRuntimeVersion()
        annotatedString ?: return null
        val refreshed = refreshedTextCache.getOrBuild(annotatedString) {
            if (hasCurrentReaderHighlightMarker(annotatedString) && settings.snapshot().canHighlightReaderDialogue) {
                return@getOrBuild null
            }
            val markerCount = highlightMarkerRanges(annotatedString).size
            val rawNineRanges = ninePatchRanges(annotatedString)
            if (markerCount == 0 && rawNineRanges.isEmpty() && !hasOwnedHighlightStyle(annotatedString)) {
                return@getOrBuild null
            }
            highlightedAnnotatedString(annotatedString, contentDom = null)
        }
        return refreshed.takeIf { highlightMarkerToken() == expectedVersion }
    }

    private fun highlightedAnnotatedString(original: Any, contentDom: Any?): Any? {
        val cleaned by lazy(LazyThreadSafetyMode.NONE) {
            if (highlightMarkerRanges(original).isEmpty() && ninePatchRanges(original).isEmpty() && !hasOwnedHighlightStyle(original)) null
            else newAnnotatedStringWithNinePatchAnnotations(original, spanStylesWithoutInjectedHighlights(original), emptyList())
        }
        val snapshot = settings.snapshot()
        if (!snapshot.canHighlightReaderDialogue) return cleaned
        val text = annotatedStringText(original)
        if (text.isBlank()) return null
        val highlight = settings.highlightSettings()
        val currentBookKey = ReaderHighlightBookContext.bookKey
        val currentBookTitle = ReaderHighlightBookContext.bookTitle
        val enabledRules = enabledHighlightRules(highlight, currentBookKey, currentBookTitle)
        if (enabledRules.isEmpty()) return cleaned
        val dark = isNightMode()
        val protectedRanges = contentDom?.let { protectedStyledElementRanges(it, text) }.orEmpty()
        val paragraphContext by lazy(LazyThreadSafetyMode.NONE) {
            contentDom?.let { dialogueParagraphContext(it, text) }
        }

        val singleQuoteRanges = if (enabledRules.any { it.type == ReaderHighlightRuleType.SingleQuotePhrase }) {
            DialogueHighlightRangeFinder.findQuoteRanges(text, SINGLE_QUOTES)
        } else {
            emptyList()
        }
        val doubleQuoteRanges = if (enabledRules.any { it.type == ReaderHighlightRuleType.DoubleQuoteDialogue }) {
            findDoubleQuoteDialogueRanges(text, paragraphContext)
        } else {
            emptyList()
        }
        val ninePatchAnnotations = ArrayList<NinePatchAnnotation>()
        val markerRanges = ArrayList<IntRange>()
        val rangeObjects = enabledRules
            .flatMap { rule ->
                val ownershipTag = highlightOwnershipTag(rule)
                val retained = if (contentDom == null) ownedRuleRanges(original, ownershipTag) else emptyList()
                val ranges = retained.ifEmpty { findRanges(
                    text = text,
                    rule = rule,
                    protectedRanges = protectedRanges,
                    singleQuoteRanges = singleQuoteRanges,
                    doubleQuoteRanges = doubleQuoteRanges,
                    paragraphContext = { paragraphContext },
                ) }
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
                ranges.mapNotNull { range -> createTaggedAnnotatedRange(style, range.first, range.last, ownershipTag) }
            }
        if (rangeObjects.isEmpty()) return cleaned
        val originalSpanStyles = spanStylesWithoutInjectedHighlights(original)
        val nextSpanStyles = ArrayList<Any>(originalSpanStyles.size + rangeObjects.size).apply {
            addAll(originalSpanStyles)
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
            ReaderHighlightRuleType.Range -> 1
            ReaderHighlightRuleType.SingleQuotePhrase -> 2
        }

    private fun highlightOwnershipTag(rule: ReaderHighlightRule): String =
        "$OWNED_SPAN_TAG|${ReaderHighlightBookContext.bookKey.length}:${ReaderHighlightBookContext.bookKey}|${rule.id.length}:${rule.id}|${rule.type}|${rule.pattern.length}:${rule.pattern}|${rule.allowCrossParagraph}"

    private fun hasOwnedHighlightStyle(text: Any): Boolean =
        annotatedStringSpanStyles(text).any {
            HighlightOwnedRanges.isOwned(annotatedRangeTag(it))
        }

    private fun ownedRuleRanges(text: Any, tag: String): List<IntRange> =
        annotatedStringSpanStyles(text).mapNotNull { range ->
            if (annotatedRangeTag(range) != tag) return@mapNotNull null
            val start = annotatedRangeStart(range) ?: return@mapNotNull null
            val end = annotatedRangeEnd(range) ?: return@mapNotNull null
            if (end > start) start..end else null
        }

    @Synchronized
    private fun enabledHighlightRules(highlight: ReaderHighlightSettingsSnapshot, bookKey: String, bookTitle: String): List<ReaderHighlightRule> {
        val key = Triple(highlight, bookKey, bookTitle)
        if (enabledRuleKey == key) return enabledRuleSnapshot
        val globalIds = highlight.globalRulesEnabledForBook(bookKey)
        val rules = highlight.rules.filter { rule ->
            rule.enabled && if (rule.bookKey.isBlank()) globalIds == null || rule.id in globalIds
            else rule.appliesToBook(bookKey, bookTitle)
        }.sortedBy { highlightRulePriority(it.type) }
        enabledRuleKey = key
        enabledRuleSnapshot = rules
        return rules
    }

    private fun findRanges(
        text: String,
        rule: ReaderHighlightRule,
        protectedRanges: List<IntRange>,
        singleQuoteRanges: List<IntRange>,
        doubleQuoteRanges: List<IntRange>,
        paragraphContext: () -> DialogueParagraphContext?,
    ): List<IntRange> =
        when (rule.type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> {
                val excluded = protectedRanges + singleQuoteRanges
                doubleQuoteRanges.flatMap { range -> subtractRanges(range, excluded) }
            }

            ReaderHighlightRuleType.SingleQuotePhrase -> singleQuoteRanges
            ReaderHighlightRuleType.FixedText -> findFixedTextRanges(text, rule.pattern)
            ReaderHighlightRuleType.Regex -> withCrossParagraph(rule, text, paragraphContext) { target ->
                findRegexRanges(
                    text = target,
                    pattern = rule.pattern,
                    dotMatchesNewline = rule.allowCrossParagraph,
                )
            }
            ReaderHighlightRuleType.Range -> withCrossParagraph(rule, text, paragraphContext) { target ->
                HighlightDelimiterRangeFinder.findRanges(
                    text = target,
                    pattern = rule.pattern,
                    maxParagraphs = crossParagraphLimit(rule),
                )
            }
        }

    private fun withCrossParagraph(
        rule: ReaderHighlightRule,
        text: String,
        paragraphContext: () -> DialogueParagraphContext?,
        find: (String) -> List<IntRange>,
    ): List<IntRange> {
        if (!rule.allowCrossParagraph || !rule.supportsCrossParagraph) return find(text)
        val context = paragraphContext() ?: return find(text)
        return CrossParagraphRangeMapper.mapToCurrentSegment(
            segments = context.segments,
            currentSegmentIndex = context.currentIndex,
            find = find,
        )
    }

    private fun crossParagraphLimit(rule: ReaderHighlightRule): Int =
        if (rule.allowCrossParagraph && rule.supportsCrossParagraph) {
            ModuleSettings.READER_HIGHLIGHT_CROSS_PARAGRAPH_LIMIT
        } else {
            1
        }

    private fun findDoubleQuoteDialogueRanges(
        text: String,
        context: DialogueParagraphContext?,
    ): List<IntRange> {
        if (context == null) {
            return DialogueHighlightRangeFinder.findQuoteRanges(text, DOUBLE_QUOTES, MAX_DOUBLE_QUOTE_DIALOGUE_PARAGRAPHS)
        }
        return DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = context.segments,
            currentSegmentIndex = context.currentIndex,
            quotes = DOUBLE_QUOTES,
            maxParagraphs = MAX_DOUBLE_QUOTE_DIALOGUE_PARAGRAPHS,
        )
    }

    private fun dialogueParagraphContext(contentDom: Any, renderedText: String): DialogueParagraphContext? {
        val symbols = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current)
        val radius = MAX_DOUBLE_QUOTE_DIALOGUE_PARAGRAPHS - 1
        val window = paragraphIndex.window(contentDom, radius) ?: run {
            // Reload may encounter an already parsed document before getBody runs again.
            registerParagraphRoot(symbols.field("parent").get(contentDom))
            paragraphIndex.window(contentDom, radius)
        } ?: return null
        return DialogueParagraphContext(
            window.elements.mapIndexed { index, sibling ->
                // Offsets for the current segment must refer to its rendered text, including
                // native inline placeholders. Other segments provide matching context only.
                if (index == window.currentIndex) renderedText else paragraphContextText(sibling)
            },
            window.currentIndex,
        )
    }
    private fun registerParagraphRoot(root: Any?) {
        root ?: return
        val required = settings.snapshot().canHighlightReaderDialogue && enabledHighlightRules(
            settings.highlightSettings(), ReaderHighlightBookContext.bookKey, ReaderHighlightBookContext.bookTitle,
        ).any { it.type == ReaderHighlightRuleType.DoubleQuoteDialogue || it.allowCrossParagraph && it.supportsCrossParagraph }
        val symbols = com.reamicro.fix.core.HostHighlightDomBindings.current ?: return
        if (!symbols.type("container").isInstance(root)) return
        runCatching {
            paragraphIndex.register(root, symbols.type("dom")::isInstance, symbols::paragraphChildren, required)
        }.onFailure { HookRuntime.logError("$LOG_PREFIX paragraph order unavailable", it) }
    }
    private fun paragraphContextText(contentDom: Any): String {
        val symbols = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current)
        val nativeText = com.reamicro.fix.core.HostHighlightTextBindings.current
        val measured = symbols.field("content").get(contentDom)
        if (measured != null && nativeText != null) {
            val text = nativeText.text(measured)
            if (text.isNotEmpty()) return text
        }
        return projectParagraph(contentDom).text
    }

    private fun projectParagraph(
        contentDom: Any, protectStyles: Boolean = false,
    ): com.reamicro.fix.reader.HighlightContextProjection.Projection {
        val symbols = requireNotNull(com.reamicro.fix.core.HostHighlightDomBindings.current)
        return com.reamicro.fix.reader.HostParagraphProjection.project(contentDom,
            protect = { node -> protectStyles && shouldProtectStyledElement(symbols.tag(node)) &&
                symbols.children(node).isNotEmpty() })
    }

    private fun protectedStyledElementRanges(contentDom: Any, renderedText: String): List<IntRange> {
        val projected = projectParagraph(contentDom, protectStyles = true)
        if (projected.text != renderedText) {
            logProtectedRangeMismatch(projected.text.length, renderedText.length)
            return emptyList()
        }
        return projected.protectedRanges
    }

    private fun shouldProtectStyledElement(tag: String): Boolean =
        tag.isNotBlank() && tag != MARKUP_TEXT && tag !in UNPROTECTED_INLINE_TAGS

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

    private fun findRegexRanges(text: String, pattern: String, dotMatchesNewline: Boolean = false): List<IntRange> {
        val result = ReaderHighlightRegex.find(text, pattern, dotMatchesNewline)
        result.error?.let(::reportRegexIssue)
        return result.ranges
    }

    private val regexIssues = LinkedHashSet<String>()
    private fun reportRegexIssue(message: String) {
        synchronized(regexIssues) {
            if (!regexIssues.add(message)) return
            if (regexIssues.size > 32) regexIssues.iterator().run { next(); remove() }
        }
        HookRuntime.log("$LOG_PREFIX highlight regex skipped: $message")
        imageHandler.post {
            activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
                android.widget.Toast.makeText(it, "$message；可在高亮规则中编辑或关闭", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createHighlightSpanStyle(style: ReaderHighlightStyle, dark: Boolean): Any? {
        val fontSelection = style.fontFamilyForTheme(dark).ifBlank { settings.fontSettings().globalFamily }
        val fontFile = if (fontSelection.isBlank() || fontSelection == FAMILY_SYSTEM || fontSelection == FAMILY_SOURCE_HAN_SERIF) null
            else resolveFontFile(fontSelection)
        val fontKey = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(fontSelection, fontFile)
        val key = Triple(style, dark, fontKey)
        synchronized(highlightStyleCache) { highlightStyleCache[key]?.let { return it } }
        val css = parseCssStyle(style.cssForTheme(dark))
        val color = composeColor(css.color.ifBlank { style.colorForTheme(dark) })
        val background = css.backgroundColor.takeIf { it.isNotBlank() }?.let(::composeColor)
        val fontSize = parseCssFontSize(css.fontSize)
        val fontFamily = resolveFontFamily(fontSelection, fontFile)
        val created = textBindings.span(color, fontSize, fontFamily, background)
        return created.also {
            synchronized(highlightStyleCache) { highlightStyleCache[key] = it }
        }
    }

    private fun spanStylesWithoutInjectedHighlights(annotatedString: Any): List<Any> =
        HighlightOwnedRanges.keepHostRanges(annotatedStringSpanStyles(annotatedString), ::annotatedRangeTag)

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
            fontSize = values["font-size"].orEmpty(),
        )
    }

    private fun parseCssFontSize(value: String): Long? {
        val trimmed = value.trim().lowercase()
        if (trimmed.isBlank()) return null
        val match = Regex("""^(-?\d+(?:\.\d+)?)\s*(px|sp|em|rem|pt)?$""").find(trimmed) ?: return null
        val number = match.groupValues[1].toFloatOrNull() ?: return null
        if (number <= 0f) return null
        return when (val unit = match.groupValues[2]) {
            "em", "rem" -> packEmTextUnit(number.coerceIn(0.1f, 10f))
            else -> {
                val sp = if (unit == "pt") number * 96f / 72f else number
                packSpTextUnit(sp.coerceIn(1f, 200f))
            }
        }
    }

    private fun packSpTextUnit(sp: Float): Long? = packTextUnit(0x100000000L, sp)
    private fun packEmTextUnit(em: Float): Long? = packTextUnit(0x200000000L, em)
    private fun packTextUnit(kind: Long, amount: Float): Long? =
        runCatching { textBindings.method("unit").invoke(null, amount, kind) as Long }.getOrNull()
    private fun composeColor(value: String): Long {
        val argb = parseAndroidColor(value) ?: Color.parseColor(DEFAULT_DIALOGUE_COLOR)
        return com.reamicro.fix.reader.ReaderColorPacking.srgb(argb)
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

    private fun newAnnotatedString(original: Any, spanStyles: List<Any>): Any? {
        val spanStyleClass = textBindings.type("span")
        val ranges = allAnnotatedRanges(original)
            .filter { range -> !spanStyleClass.isInstance(annotatedRangeItem(range)) }
            .toMutableList()
        ranges.addAll(spanStyles)
        return newAnnotatedStringFromRanges(original, ranges)
    }

    private fun newAnnotatedStringWithNinePatchAnnotations(
        original: Any,
        spanStyles: List<Any>,
        annotations: List<NinePatchAnnotation>,
        highlightRanges: List<IntRange> = emptyList(),
    ): Any? =
        runCatching {
            val spanStyleClass = textBindings.type("span")
            val ranges = allAnnotatedRanges(original)
                .filter { range ->
                    val item = annotatedRangeItem(range)
                        ?: error("AnnotatedString range 缺少 item")
                    val tag = annotatedRangeTag(range)
                    !spanStyleClass.isInstance(item) &&
                        tag != NINE_PATCH_ANNOTATION_TAG &&
                        tag != HIGHLIGHT_ANNOTATION_TAG
                }
                .toMutableList()
            ranges.addAll(spanStyles)
            annotations.forEach { annotation ->
                ranges += createTaggedAnnotatedRange(
                    item = listOf(annotation.path, annotation.slice, annotation.css)
                        .joinToString(NINE_PATCH_ANNOTATION_SEPARATOR),
                    start = annotation.start,
                    endExclusive = annotation.end,
                    tag = NINE_PATCH_ANNOTATION_TAG,
                ) ?: error("无法创建九宫格 annotation range")
            }
            val token = highlightMarkerToken()
            highlightRanges.forEach { range ->
                ranges += createTaggedAnnotatedRange(
                    item = token,
                    start = range.first,
                    endExclusive = range.last,
                    tag = HIGHLIGHT_ANNOTATION_TAG,
                ) ?: error("无法创建高亮 annotation range")
            }
            newAnnotatedStringFromRanges(original, ranges)
                ?: error("无法重建 AnnotatedString")
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX nine-patch annotation failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun hookActivityConfigurationChanges() {
        if (activityConfigurationHookInstalled) return
        runCatching {
            HookRuntime.hookAllMethods(Activity::class.java, "onResume", object : HookCallback() {
                override fun after(param: Call) {
                    (param.thisObject as? Activity)?.let(::handleReaderNightModeObserved)
                }
            })
            HookRuntime.hookAllMethods(Activity::class.java, "onConfigurationChanged", object : HookCallback() {
                override fun after(param: Call) {
                    (param.thisObject as? Activity)?.let(::handleReaderNightModeObserved)
                }
            })
            activityConfigurationHookInstalled = true
            logHighlightInfo("reader night-mode refresh hook installed")
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX reader night-mode refresh hook failed: ${it.stackTraceToString()}")
        }.getOrThrow()
    }

    private fun handleReaderNightModeObserved(activity: Activity) {
        val current = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val previous = lastObservedNightMode
        lastObservedNightMode = current
        if (previous == null || previous == current) return
        if (ReaderHighlightBookContext.bookKey.isBlank()) return
        if (!settings.snapshot().canHighlightReaderDialogue) return
        ReaderHighlightBookContext.bumpVersion("night-mode")
        checkHighlightRuntimeVersion()
        logHighlightInfo("reader night-mode highlight refresh requested dark=$current")
    }

    private fun checkHighlightRuntimeVersion() {
        val key = highlightMarkerToken()
        if (key == lastHighlightRuntimeKey) return
        synchronized(this) {
            val latest = highlightMarkerToken()
            if (latest == lastHighlightRuntimeKey) return
            clearHighlightRuntimeCaches()
            lastHighlightRuntimeKey = latest
        }
    }

    private fun clearHighlightRuntimeCaches() {
        // 图片按资源路径和修改时间失效，切书或规则版本变化不必重复解码。
        textNodeDrawStates.clear()
        annotationRangesCache.clear()
        refreshedTextCache.clear()
        synchronized(reedenBoxStyleCache) { reedenBoxStyleCache.clear() }
        synchronized(nineSliceCache) { nineSliceCache.clear() }
        synchronized(rememberedNinePatchRanges) { rememberedNinePatchRanges.clear() }
        synchronized(derivedNinePatchRanges) { derivedNinePatchRanges.clear() }
        synchronized(highlightStyleCache) { highlightStyleCache.clear() }
        synchronized(this) { enabledRuleKey = null; enabledRuleSnapshot = emptyList() }
        lastNinePatchLogKey = ""
        lastAppliedLogKey = ""
    }

    private fun hookBasicTextNinePatchDraw() {
        if (basicTextDrawHookInstalled) return
        runCatching {
            val symbols = textBindings
            val textIndex = symbols.index("basic.text")
            val flagsIndex = symbols.index("basic.composer") + 1 + textIndex / 10
            val dirtyMask = 0x0E shl (3 * (textIndex % 10))
            val handle = HookRuntime.hookMethod(symbols.method("basic"), object : HookCallback() {
                override fun before(param: Call) {
                    refreshAnnotatedStringForCurrentTheme(param.args[textIndex])?.let {
                        param.args[textIndex] = it
                        val flags = param.args[flagsIndex] as Int
                        param.args[flagsIndex] = flags and dirtyMask.inv()
                    }
                    rememberNinePatchRanges(param.args[textIndex])
                }
            })
            try {
                HookRuntime.deoptimize(symbols.method("content"))
                basicTextDrawHookInstalled = true
            } catch (error: Throwable) {
                handle.unhook()
                throw error
            }
            logHighlightInfo("basic text nine-patch draw hook installed")
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX basic text nine-patch draw hook failed: ${it.stackTraceToString()}")
        }.getOrThrow()
    }
    private fun hookTextNodeNinePatchDraw() {
        runCatching {
            val symbols = textBindings
            val draw = symbols.method("draw")
            val layoutCache = symbols.method("layoutCache")
            // 直接复用真实文本节点及其布局，不改 onTextLayout；回调变化会让宿主重新测量。
            val handle = HookRuntime.hookMethod(draw, object : HookCallback() {
                override fun before(param: Call) {
                    runCatching {
                        val node = param.thisObject ?: return
                        val scope = param.args[0] ?: return
                        // 只跳过附加背景绘制，BasicText 的旧标记清理仍正常执行。
                        if (ReaderHighlightBookContext.bookKey.isBlank() ||
                            !settings.snapshot().canHighlightReaderDialogue || !isTextNodeAttached(node)
                        ) {
                            textNodeDrawStates.remove(node)
                            return
                        }
                        checkHighlightRuntimeVersion()
                        val cache = layoutCache.invoke(node, scope)
                        val layout = symbols.field("cache.layout").get(cache) ?: return
                        val token = currentHighlightRuntimeKey()
                        val state = textNodeDrawStates.getOrBuild(node, layout, token) {
                            val layoutInput = symbols.field("layout.input").get(layout) ?: return@getOrBuild null
                            val text = symbols.field("input.text").get(layoutInput) ?: return@getOrBuild null
                            val ranges = currentNinePatchRanges(text).ifEmpty { rememberedNinePatchRanges(text) }
                            logHighlightPerformance {
                                "text-node layout ranges=${ranges.size} text=${annotatedStringLength(text)}"
                            }
                            if (ranges.isEmpty()) null else NinePatchDrawState(ranges, token)
                        } ?: return
                        drawNinePatchBackgrounds(scope, layout, state)
                    }.onFailure { logNinePatchDrawFailure("text-node", it) }
                }
            })
            try {
                HookRuntime.deoptimize(symbols.method("drawDirect"))
            } catch (error: Throwable) {
                handle.unhook()
                throw error
            }
        }.onFailure {
            logNinePatchDrawFailure("text-node-install", it)
        }.getOrThrow()
    }

    private fun rememberNinePatchRanges(annotatedString: Any?) {
        checkHighlightRuntimeVersion()
        annotatedString ?: return
        val ranges = currentNinePatchRanges(annotatedString)
        if (ranges.isEmpty()) return
        val key = annotatedStringTextKey(annotatedString)
        if (key.isBlank()) return
        synchronized(rememberedNinePatchRanges) {
            if (rememberedNinePatchRanges[key]?.ranges == ranges) return
        }
        val normalized = normalizedTextWithSourceMap(key)
        synchronized(rememberedNinePatchRanges) {
            rememberedNinePatchRanges[key] = RememberedNinePatchText(key, ranges, normalized)
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
            if (derivedNinePatchRanges.containsKey(key)) return derivedNinePatchRanges[key].orEmpty()
        }
        val derived = deriveRememberedNinePatchRanges(key)

        synchronized(derivedNinePatchRanges) {
            derivedNinePatchRanges[key] = derived
        }
        return derived
    }

    private fun annotatedStringTextKey(annotatedString: Any): String =
        annotatedStringText(annotatedString)

    private fun currentNinePatchRanges(annotatedString: Any): List<NinePatchRange> =
        if (hasCurrentReaderHighlightMarker(annotatedString)) ninePatchRanges(annotatedString) else emptyList()

    private fun hasCurrentReaderHighlightMarker(annotatedString: Any): Boolean {
        val token = highlightMarkerToken()
        return stringAnnotations(annotatedString, HIGHLIGHT_ANNOTATION_TAG)
            .any { annotatedRangeString(it) == token }
    }

    private fun highlightMarkerRanges(annotatedString: Any): List<IntRange> =
        stringAnnotations(annotatedString, HIGHLIGHT_ANNOTATION_TAG).mapNotNull { range ->
            val start = annotatedRangeStart(range) ?: return@mapNotNull null
            val end = annotatedRangeEnd(range) ?: return@mapNotNull null
            if (end > start) exclusiveRange(start, end) else null
        }

    private fun highlightMarkerToken(): String =
        "${ReaderHighlightBookContext.bookKey}|${ReaderHighlightBookContext.version()}|${isNightMode()}|${settings.snapshot().canHighlightReaderDialogue}"

    private fun currentHighlightRuntimeKey(): String =
        lastHighlightRuntimeKey.ifBlank { highlightMarkerToken() }

    private fun stringAnnotations(annotatedString: Any, tag: String): List<Any> {
        val ranges = annotationRangesCache.getOrBuild(annotatedString) {
            val tagged = HashMap<String, MutableList<Any>>()
            allAnnotatedRanges(annotatedString).forEach { range ->
                val rangeTag = annotatedRangeTag(range)
                if (rangeTag == HIGHLIGHT_ANNOTATION_TAG || rangeTag == NINE_PATCH_ANNOTATION_TAG ||
                    rangeTag == INLINE_CONTENT_ANNOTATION_TAG
                ) {
                    tagged.getOrPut(rangeTag) { ArrayList() }.add(range)
                }
            }
            tagged
        }
        return ranges?.get(tag).orEmpty()
    }

    private fun deriveRememberedNinePatchRanges(currentText: String): List<NinePatchRange> {
        if (currentText.isBlank()) return emptyList()

        val remembered = synchronized(rememberedNinePatchRanges) {
            if (rememberedNinePatchRanges.isEmpty()) return emptyList()
            rememberedNinePatchRanges.values.toList().asReversed()
        }
        val current = normalizedTextWithSourceMap(currentText)
        if (current.text.isBlank()) return emptyList()
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
        val original = source.normalized
        if (original.text.isBlank()) return emptyList()
        val currentStartInOriginal = original.text.indexOf(current.text)
        if (currentStartInOriginal < 0) return emptyList()
        val currentEndInOriginal = currentStartInOriginal + current.text.length
        val mapped = source.ranges.mapNotNull { range ->
            val normalizedStart = original.firstAtOrAfter(range.start)
            val lastBefore = original.lastBefore(range.end)
            if (normalizedStart < 0 || lastBefore < 0) return@mapNotNull null
            val normalizedEnd = lastBefore + 1
            if (normalizedEnd <= normalizedStart) return@mapNotNull null
            val overlapStart = maxOf(normalizedStart, currentStartInOriginal)
            val overlapEnd = minOf(normalizedEnd, currentEndInOriginal)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val localStart = overlapStart - currentStartInOriginal
            val localEnd = overlapEnd - currentStartInOriginal
            range.copy(
                start = current.sourceAt(localStart) ?: return@mapNotNull null,
                end = (current.sourceAt(localEnd - 1) ?: return@mapNotNull null) + 1,
            )
        }
        return mapped
    }

    private fun normalizedTextWithSourceMap(value: String): NormalizedText {
        val builder = StringBuilder(value.length)
        val indices = IntArray(value.length)
        var count = 0
        value.forEachIndexed { index, char ->
            if (!char.isWhitespace() && char != '\u200B' && char != '\u200C' && char != '\u200D' && char != '\uFEFF') {
                builder.append(char)
                indices[count] = index
                count++
            }
        }
        return NormalizedText(builder.toString(), indices, count)
    }

    private fun ninePatchRanges(annotatedString: Any): List<NinePatchRange> {
        val length = annotatedStringLength(annotatedString)
        val annotations = stringAnnotations(annotatedString, NINE_PATCH_ANNOTATION_TAG)
        return annotations.mapNotNull { range ->
            range ?: return@mapNotNull null
            val value = annotatedRangeString(range)
            val parts = value.split(NINE_PATCH_ANNOTATION_SEPARATOR, limit = 3)
            val path = parts.getOrNull(0).orEmpty().trim()
            val slice = parts.getOrNull(1).orEmpty().trim()
            val css = parts.getOrNull(2).orEmpty()
            val start = (annotatedRangeStart(range) ?: return@mapNotNull null).coerceIn(0, length)
            val end = (annotatedRangeEnd(range) ?: return@mapNotNull null).coerceIn(0, length)
            if (path.isBlank() || end <= start) null else NinePatchRange(start, end, path, slice, css)
        }
    }

    private fun drawNinePatchBackgrounds(drawScope: Any?, layout: Any, state: NinePatchDrawState) {
        if (state.ranges.isEmpty()) return
        if (state.token != currentHighlightRuntimeKey()) return
        val canvas = nativeCanvas(drawScope) ?: return
        val density = activityProvider()?.resources?.displayMetrics?.density ?: 1f
        val rectsByRange = cachedLineRects(layout, density, state)
        state.ranges.forEachIndexed { index, range ->
            val image = imageCache.get(range.path)
            if (image == null) return@forEachIndexed
            val box = reedenBoxStyle(range.css, density)
            val nineSlice = parseNineSlice(range.slice, image.bitmap.width, image.bitmap.height)
            val rects = rectsByRange.getOrNull(index).orEmpty()
            rects.forEach { rect ->
                val saveCount = if (box.radiusPx > 0f) {

                    state.clipRectF.set(rect)
                    state.clipPath.rewind()
                    state.clipPath.addRoundRect(state.clipRectF, box.radiusPx, box.radiusPx, Path.Direction.CW)
                    val count = canvas.save()
                    canvas.clipPath(state.clipPath)
                    count
                } else {
                    -1
                }
                if (nineSlice != null) {
                    drawNineSlice(canvas, image.bitmap, nineSlice, rect, state)
                } else if (image.bitmap.ninePatchChunk == null) {
                    drawBitmapByBackgroundSize(canvas, image.bitmap, rect, box.backgroundSize, state)
                } else {
                    image.drawable.bounds = rect
                    image.drawable.draw(canvas)
                }
                if (saveCount >= 0) canvas.restoreToCount(saveCount)
                drawCssBorder(canvas, rect, box, state)
            }
        }
    }

    private fun cachedLineRects(layout: Any, density: Float, state: NinePatchDrawState): List<List<Rect>> {
        val densityBits = density.toBits()
        synchronized(state) {
            val cached = state.cachedLineRects
            // state 随布局身份变化重建，只需再检查密度，不额外持有 TextLayoutResult。
            if (state.cachedDensityBits == densityBits && cached != null) {
                state.rectCacheHits += 1
                logHighlightPerformance {
                    "draw ninePatch=${state.ranges.size} rectCache=hit hits=${state.rectCacheHits} builds=${state.rectCacheBuilds} rects=${state.rectLineCalculations}"
                }
                return cached
            }
            val next = state.ranges.map { range ->
                lineRects(layout, range.start, range.end, reedenBoxStyle(range.css, density))
            }
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
        val symbols = textBindings
        val paragraph = symbols.field("layout.paragraph").get(layout) ?: return emptyList()
        val count = symbols.field("paragraph.lineCount").getInt(paragraph)
        if (count <= 0 || end <= start) return emptyList()
        val first = (symbols.method("lineForOffset").invoke(paragraph, start) as Int).coerceIn(0, count - 1)
        val last = (symbols.method("lineForOffset").invoke(paragraph, end - 1) as Int).coerceIn(0, count - 1)
        if (last < first) return emptyList()
        return (first..last).mapNotNull { line ->
            val lineStart = symbols.method("lineStart").invoke(layout, line) as Int
            val lineEnd = symbols.method("lineEnd").invoke(paragraph, line, false) as Int
            val localStart = maxOf(start, lineStart)
            val localEnd = minOf(end, lineEnd)
            if (localEnd <= localStart) return@mapNotNull null
            // Include every character box so mixed RTL/LTR text keeps the full painted extent.
            var bounds: RectF? = null
            for (offset in localStart until localEnd) {
                val rect = symbols.method("bounds").invoke(layout, offset)
                val native = symbols.method("rect").invoke(null, rect) as RectF
                if (bounds == null) bounds = RectF(native) else bounds.union(native)
            }
            val result = bounds ?: return@mapNotNull null
            Rect(
                (result.left - box.paddingLeftPx - box.marginLeftPx).toInt(),
                (result.top - box.paddingTopPx - box.marginTopPx).toInt(),
                (result.right + box.paddingRightPx + box.marginRightPx).toInt(),
                (result.bottom + box.paddingBottomPx + box.marginBottomPx).toInt(),
            )
        }
    }
    private fun nativeCanvas(drawScope: Any?): android.graphics.Canvas? {
        if (drawScope == null) return null
        val symbols = textBindings
        val draw = symbols.field("scope.draw").get(drawScope) ?: return null
        val context = symbols.field("draw.context").get(draw) ?: return null
        val canvas = symbols.method("canvas").invoke(context) ?: return null
        return symbols.field("canvas.native").get(canvas) as? android.graphics.Canvas
    }
    private fun invalidateImageNodes(path: String) {
        val activity = activityProvider() ?: return
        if (activity.isFinishing || activity.isDestroyed || ReaderHighlightBookContext.bookKey.isBlank()) return
        val token = highlightMarkerToken()
        val nodes = textNodeDrawStates.snapshot().filter { (_, state) ->
            state.token == token && state.ranges.any { it.path == path }
        }.map { it.first }
        nodes.forEach { node ->
            runCatching {
                if (isTextNodeAttached(node)) {
                    textBindings.method("invalidate").invoke(null, node)
                }
            }.onFailure { logNinePatchDrawFailure("image-ready", it) }
        }
    }

    private fun isImageInUse(path: String): Boolean {
        if (ReaderHighlightBookContext.bookKey.isBlank()) return false
        val token = highlightMarkerToken()
        return textNodeDrawStates.snapshot().any { (node, state) ->
            state.token == token && state.ranges.any { it.path == path } && isTextNodeAttached(node)
        }
    }

    private fun isTextNodeAttached(node: Any): Boolean =
        textBindings.field("modifier.attached").getBoolean(node)

    private fun loadNinePatchImage(path: String, previous: CachedImage?): CachedImage? {
        val assetName = path.removePrefix("asset://").takeIf { it != path }
        val file = assetName?.let { null } ?: File(path)
        val modified = assetName?.hashCode()?.toLong() ?: file!!.takeIf { it.isFile }?.lastModified() ?: -1L
        if (previous != null && previous.modified == modified) return previous
        val bitmap = ReaderHighlightImageAssets.decodeBitmap(path, activityProvider()?.applicationContext, LOG_PREFIX)
            ?: return null
        val drawable = if (bitmap.ninePatchChunk != null) {
            NinePatchDrawable(activityProvider()?.resources, bitmap, bitmap.ninePatchChunk, Rect(), assetName ?: file!!.name)
        } else {
            BitmapDrawable(activityProvider()?.resources, bitmap)
        }
        return CachedImage(modified, bitmap, drawable)
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

    private fun drawNineSlice(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, slice: NineSlice, dst: Rect, state: NinePatchDrawState) {
        val srcX = state.srcX.apply { this[0] = 0; this[1] = slice.left; this[2] = slice.right; this[3] = bitmap.width }
        val srcY = state.srcY.apply { this[0] = 0; this[1] = slice.top; this[2] = slice.bottom; this[3] = bitmap.height }
        val leftWidth = minOf(slice.left, dst.width() / 2)
        val rightWidth = minOf(bitmap.width - slice.right, dst.width() - leftWidth)
        val topHeight = minOf(slice.top, dst.height() / 2)
        val bottomHeight = minOf(bitmap.height - slice.bottom, dst.height() - topHeight)
        val dstX = state.dstX.apply { this[0] = dst.left; this[1] = dst.left + leftWidth; this[2] = dst.right - rightWidth; this[3] = dst.right }
        val dstY = state.dstY.apply { this[0] = dst.top; this[1] = dst.top + topHeight; this[2] = dst.bottom - bottomHeight; this[3] = dst.bottom }
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val source = state.sourceRect.apply { set(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1]) }
                val target = state.targetRect.apply { set(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1]) }
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
        state: NinePatchDrawState,
    ) {
        if (backgroundSize.contains("100% 100%", ignoreCase = true) ||
            backgroundSize.contains("stretch", ignoreCase = true)
        ) {
            canvas.drawBitmap(bitmap, null, dst, null)
            return
        }
        drawCoverBitmap(canvas, bitmap, dst, state.sourceRect)
    }

    private fun drawCoverBitmap(canvas: android.graphics.Canvas, bitmap: android.graphics.Bitmap, dst: Rect, src: Rect) {
        if (dst.width() <= 0 || dst.height() <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = dst.width().toFloat() / dst.height().toFloat()
        if (srcAspect > dstAspect) {
            val width = (bitmap.height * dstAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - width) / 2
            src.set(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / dstAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - height) / 2
            src.set(0, top, bitmap.width, top + height)
        }
        canvas.drawBitmap(bitmap, src, dst, null)
    }

    private fun drawCssBorder(canvas: android.graphics.Canvas, rect: Rect, box: ReedenBoxStyle, state: NinePatchDrawState) {
        if (box.borderWidthPx <= 0f || box.borderColor == null || rect.width() <= 0 || rect.height() <= 0) return
        val inset = box.borderWidthPx / 2f
        val borderRect = state.borderRect.apply { set(rect); inset(inset, inset) }
        val paint = state.borderPaint.apply {
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

    private fun logNinePatchDrawFailure(source: String, error: Throwable) {
        val key = "$source|${error.javaClass.name}|${error.message}"
        if (key == lastNinePatchLogKey) return
        lastNinePatchLogKey = key
        HookRuntime.log("$LOG_PREFIX nine-patch draw $source failed: ${error.stackTraceToString()}")
    }

    private fun resolveFontFamily(selection: String, file: File?): Any? {
        if (selection.isBlank()) return null
        val key = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, file)
        return fontFamilyCache.getOrLoad(key) {
            if (selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF) {
                resolveBuiltinFontFamily(selection)
            } else file?.let(::resolveFontFamilyFromFile)
        }
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> com.reamicro.fix.core.HostFontFamilies.song(classLoader)
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? =
        com.reamicro.fix.core.HostFontFamilies.system(classLoader)

    private fun resolveFontFamilyFromFile(file: File): Any? =
        runCatching {
            com.reamicro.fix.core.HostFontFamilies.fromFile(classLoader, file)
        }.onFailure { logResolveFontFamilyFailure(file.absolutePath, it) }.getOrNull()

    private fun resolveFontFile(selection: String): File? {
        val activity = activityProvider() ?: return null
        val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull() ?: return null
        val file = runCatching { com.reamicro.fix.epub.editor.AccountFontFiles.resolve(
            activity.filesDir, identity.accountId, selection,
        ) }.getOrNull() ?: return null
        return file.takeIf { runCatching { AccountHostRuntime.isCurrentStorage(identity, activity) }.getOrDefault(false) }
    }

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
        }
        HookRuntime.log("$LOG_PREFIX dialogue highlight font failed: $selection ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun logOnlineParagraphCommentDebug(message: String) {
        synchronized(onlineParagraphCommentDebugKeys) {
            if (!onlineParagraphCommentDebugKeys.add(message)) return
        }
        logHighlightInfo("online paragraph comments $message")
    }

    private fun logApplied(text: String, count: Int) {
        val key = "${text.length}|$count|${text.take(24)}"
        if (key == lastAppliedLogKey) return
        lastAppliedLogKey = key
        logHighlightInfo("dialogue highlight applied ranges=$count text=${text.take(24)}")
    }

    private fun logProtectedRangeMismatch(mappedLength: Int, renderedLength: Int) {
        val key = "protected-range-mismatch|$mappedLength|$renderedLength"
        if (key == lastAppliedLogKey) return
        lastAppliedLogKey = key
        logHighlightInfo("dialogue highlight protected ranges skipped mapped=$mappedLength rendered=$renderedLength")
    }

    private inline fun logHighlightPerformance(message: () -> String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHighlightPerformanceLogAtMs < HIGHLIGHT_PERFORMANCE_LOG_INTERVAL_MS) return
        if (!settings.snapshot().canLogReaderHighlightPerformance) return
        lastHighlightPerformanceLogAtMs = now
        HookRuntime.log("$LOG_PREFIX highlight-perf ${message()}")
    }

    private fun logHighlightInfo(message: String) {
        if (!settings.snapshot().canLogReaderHighlightPerformance) return
        HookRuntime.log("$LOG_PREFIX $message")
    }

    private fun annotatedStringText(annotatedString: Any): String = textBindings.text(annotatedString)
    private fun annotatedStringLength(annotatedString: Any): Int = annotatedStringText(annotatedString).length
    private fun annotatedStringSpanStyles(annotatedString: Any): List<Any> =
        allAnnotatedRanges(annotatedString).filter { textBindings.type("span").isInstance(annotatedRangeItem(it)) }
    private fun annotatedRangeItem(range: Any): Any? = textBindings.field("range.item").get(range)
    private fun annotatedRangeStart(range: Any): Int? = textBindings.field("range.start").getInt(range)
    private fun annotatedRangeEnd(range: Any): Int? = textBindings.field("range.end").getInt(range)
    private fun annotatedRangeTag(range: Any): String = textBindings.field("range.tag").get(range) as String
    private fun annotatedRangeString(range: Any): String = annotatedRangeValue(range)?.toString().orEmpty()
    private fun annotatedRangeValue(range: Any): Any? {
        val item = annotatedRangeItem(range) ?: return null
        return if (textBindings.type("string").isInstance(item)) textBindings.field("string.value").get(item) else item
    }
    private fun newAnnotatedStringFromRanges(original: Any, ranges: List<Any>): Any? =
        textBindings.annotated(annotatedStringText(original), ranges)

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"

        const val ONLINE_PARAGRAPH_COMMENTS_RUNTIME_ENABLED = false
        const val DEFAULT_DIALOGUE_COLOR = "#FF9800"
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val MAX_REMEMBERED_NINE_PATCH_TEXTS = 128
        const val MAX_DERIVED_NINE_PATCH_TEXTS = 128
        const val MAX_CACHED_REEDEN_STYLES = 64
        const val MAX_CACHED_NINE_SLICES = 64

        const val HIGHLIGHT_PERFORMANCE_LOG_INTERVAL_MS = 1500L
        const val REEDEN_BOX_EDGE_SCALE = 0.78f

        const val MAX_DOUBLE_QUOTE_DIALOGUE_PARAGRAPHS = ModuleSettings.READER_HIGHLIGHT_CROSS_PARAGRAPH_LIMIT
        const val HIGHLIGHT_ANNOTATION_TAG = "reamicro.highlight.span"
        const val OWNED_SPAN_TAG = HighlightOwnedRanges.TAG
        const val NINE_PATCH_ANNOTATION_TAG = "reamicro.highlight.ninepatch"
        const val COMMENT_ANNOTATION_TAG = "#comment"
        const val INLINE_CONTENT_ANNOTATION_TAG = HostClasses.Compose.INLINE_CONTENT_ANNOTATION_TAG
        const val ONLINE_COMMENT_ANNOTATION_PREFIX = "reamicro-online-comment"
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

    private data class OnlineParagraphCommentUpdate(
        val content: Any,
        val placeholderMap: HashMap<Any, Any>,
        val placeholders: HashMap<Any, Any>,
    )

    private data class CssStyle(
        val color: String = "",
        val backgroundColor: String = "",
        val fontSize: String = "",
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

    private class NinePatchDrawState(
        val ranges: List<NinePatchRange>,
        val token: String,
    ) {
        @Volatile var cachedDensityBits: Int = 0
        @Volatile var cachedLineRects: List<List<Rect>>? = null
        var rectCacheHits: Int = 0
        var rectCacheBuilds: Int = 0
        var rectLineCalculations: Int = 0

        val clipPath: Path = Path()
        val clipRectF: RectF = RectF()
        // 可变绘制对象只由各自的文本节点在绘制线程复用。
        val srcX = IntArray(4)
        val srcY = IntArray(4)
        val dstX = IntArray(4)
        val dstY = IntArray(4)
        val sourceRect = Rect()
        val targetRect = Rect()
        val borderRect = RectF()
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private data class RememberedNinePatchText(
        val text: String,
        val ranges: List<NinePatchRange>,
        val normalized: NormalizedText,
    )

    private data class NormalizedText(
        val text: String,
        val sourceIndices: IntArray,
        val size: Int,
    ) {

        fun firstAtOrAfter(sourceIndex: Int): Int {
            var low = 0
            var high = size - 1
            var found = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (sourceIndices[mid] >= sourceIndex) {
                    found = mid
                    high = mid - 1
                } else {
                    low = mid + 1
                }
            }
            return found
        }

        fun lastBefore(sourceIndexExclusive: Int): Int {
            var low = 0
            var high = size - 1
            var found = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (sourceIndices[mid] < sourceIndexExclusive) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return found
        }

        fun sourceAt(normalizedIndex: Int): Int? =
            if (normalizedIndex in 0 until size) sourceIndices[normalizedIndex] else null

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private data class DialogueParagraphContext(
        val segments: List<String>,
        val currentIndex: Int,
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
