package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.online.OnlineReaderContextBridge
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import org.xmlpull.v1.XmlPullParser
import com.reamicro.fix.hook.reader.*

// ReaderHook 的支撑簇。
//
// 反射调用宿主 Compose 构件与协程、取当前书籍与页面、内存回收、各类小工具。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.releaseReaderStrongReferences(reason: String, releaseEpub: Boolean) {
    currentPageStrong = null
    if (releaseEpub) currentEpubStrong = null
    XposedBridge.log("$LOG_PREFIX reader strong references released: $reason releaseEpub=$releaseEpub")
}

internal fun ReaderHook.releaseReaderMemory(reason: String, releaseEpub: Boolean) {
    currentPageRef = null
    currentPageStrong = null
    currentVisiblePageSignature = null
    currentVisiblePageNumber = null
    lastHandledReaderStatisticsKey = ""
    lastOnDemandPrefetchSpineKey = ""
    onDemandPrefetchInFlight.clear()
    onDemandPrefetchRetryCount.clear()
    onDemandRefreshPending.clear()
    onDemandRefreshInFlight.clear()
    renderingEpubPage.remove()
    if (releaseEpub) {
        currentEpubRef = null
        currentEpubStrong = null
        OnlineReaderContextBridge.clear()
    }
    resetFullTextSearchState(reason, removeOverlays = false)
    XposedBridge.log("$LOG_PREFIX reader memory released: $reason releaseEpub=$releaseEpub")
}

internal fun ReaderHook.isUninitializedContentDomParent(error: Throwable): Boolean =
    error is UninitializedPropertyAccessException &&
        error.message?.contains("parent") == true

internal fun ReaderHook.fallbackRenderTextWidthDp(contentDom: Any?): Float? =
    runCatching {
        val textLayout = callNoArg(contentDom, "getTextLayout") ?: return@runCatching null
        val size = callNoArg(textLayout, "getSize") as? Number ?: return@runCatching null
        val widthPx = (size.toLong() shr 32).toInt()
        if (widthPx <= 0) return@runCatching 0f
        val epubWindowClass = classLoader.loadClass(UI_EPUB_WINDOW_CLASS)
        val instance = epubWindowClass.getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
        val dpFloat = epubWindowClass.methods.firstOrNull {
            it.name == "dpFloat" && it.parameterTypes.size == 1
        } ?: return@runCatching widthPx.toFloat()
        val dp = (dpFloat.invoke(instance, Integer.valueOf(widthPx)) as? Number)?.toFloat()
            ?: widthPx.toFloat()
        kotlin.math.ceil(dp.toDouble()).toFloat()
    }.getOrNull()

internal fun ReaderHook.handleHomeBookshelfRendered(source: String) {
    val hadReaderSearchState = hasFullTextSearchState()
    stopReadAloudIfPausedOnLeaveReader("home rendered: $source")
    if (activeSearchNavigation != null) {
        returnToSearchOrigin(clearNavigation = true, removeBar = false)
    }
    removeReadAloudMenuButton()
    // HomeScreen/BookshelfScreen 这两个 Composable 会在阅读页仍在前台时被 Compose 后台重组，
    // 若无条件清空 epub/page 强引用与搜索上下文，会导致"同一本书忽然搜不了"的间歇性失败。
    // 仅当 ReaderViewModel 已销毁（真正离开阅读页，onCleared 未覆盖时的兜底）才清空这些引用；
    // 否则保留，等切书时由 Epub.read 的换书检测或 onCleared 负责重置。
    val readerGone = currentViewModelRef?.get() == null
    if (readerGone) {
        currentEpubRef = null
        currentPageRef = null
        currentEpubStrong = null
        currentPageStrong = null
        OnlineReaderContextBridge.clear()
        lastHandledReaderStatisticsKey = ""
        lastOnDemandPrefetchSpineKey = ""
        bottomReadAloudReceiverRef = null
        bottomReadAloudBookRef = null
        readerBottomMenuVisible = false
        updateReaderHighlightBookContext("", "", "home rendered: $source", requestRefresh = false)
        if (hadReaderSearchState) {
            resetFullTextSearchState("home rendered: $source", removeOverlays = true)
        }
    }
}

internal fun ReaderHook.postRemoveTaggedViews(activity: Activity?, tagValue: Int) {
    val targetActivity = activity ?: activityProvider() ?: return
    val decor = targetActivity.window?.decorView as? ViewGroup ?: return
    decor.post {
        runCatching {
            removeTaggedViews(decor, tagValue)
            removeTaggedViews(targetActivity.findViewById(android.R.id.content), tagValue)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to remove overlay tag=$tagValue: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaderHook.isReaderBottomBarDarkLightToggleClick(onClick: Any): Boolean =
    runCatching {
        val cls = onClick.javaClass
        val className = cls.name
        if (!className.contains("ReaderBottomBarKt") ||
            !className.contains("ExternalSyntheticLambda")
        ) {
            return@runCatching false
        }
        val fields = cls.declaredFields
        val fieldTypes = fields.map { it.type.name }.toSet()
        if (fieldTypes.contains(NAV_GRAPH_SCOPE_CLASS)) return@runCatching false
        fieldTypes.contains("kotlinx.coroutines.CoroutineScope") &&
            fieldTypes.contains("androidx.compose.runtime.MutableState") &&
            fieldTypes.contains(SESSION_CLASS) &&
            fields.any { it.type == java.lang.Boolean.TYPE }
    }.getOrDefault(false)

internal fun ReaderHook.isReaderBottomBarBackClick(onClick: Any): Boolean =
    runCatching {
        val cls = onClick.javaClass
        val className = cls.name
        if (!className.contains("ReaderBottomBarKt") ||
            !className.contains("ExternalSyntheticLambda")
        ) {
            return@runCatching false
        }
        cls.declaredFields.any { it.type.name == NAV_GRAPH_SCOPE_CLASS }
    }.getOrDefault(false)

internal fun ReaderHook.isInReaderFamilySheetCompose(): Boolean =
    Thread.currentThread().stackTrace.any { frame ->
        frame.className == READER_FAMILY_EPUB_CLASS ||
            frame.className == READER_FAMILY_USER_CLASS ||
            frame.className == READER_FAMILY_BUILD_IN_CLASS
    }

internal fun ReaderHook.sendReaderUiSheetStatus(receiver: Any, statusName: String): Boolean =
    runCatching {
        val status = readerSheetStatus(statusName)
        val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$UpdateUISheetStatus")
        val intent = intentClass.declaredConstructors.first { it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
            .newInstance(status)
        receiver.javaClass.methods.first {
            it.name == "intent" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(receiver, intent)
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX update reader sheet status failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun ReaderHook.readerSheetStatus(statusName: String): Any =
    classLoader.loadClass("$UI_SHEET_STATUS_CLASS\$$statusName")
        .getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)
        ?: error("UiSheetStatus.$statusName unavailable")

internal fun ReaderHook.isReaderSheetStatus(status: Any?, statusName: String): Boolean =
    runCatching { status == readerSheetStatus(statusName) || status?.toString() == statusName }.getOrDefault(false)

internal fun ReaderHook.extractNavGraphScopeFromLambda(onClick: Any): Any? =
    onClick.javaClass.declaredFields.firstNotNullOfOrNull { field ->
        runCatching {
            field.isAccessible = true
            field.get(onClick)?.takeIf { it.javaClass.name == NAV_GRAPH_SCOPE_CLASS }
        }.getOrNull()
    }

internal fun ReaderHook.hostApplicationContext(): Context? =
    activityProvider()?.applicationContext ?: readAloudHighlightReceiverContextRef?.get()

internal fun ReaderHook.createReaderStatisticsIntent(page: Any): Any? =
    runCatching {
        val pageClass = classLoader.loadClass(EPUB_PAGE_CLASS)
        val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$Statistics")
        intentClass.getDeclaredConstructor(pageClass).newInstance(page)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud statistics intent failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.setReaderStatisticsTimer(viewModel: Any, durationDeltaMs: Long) {
    val timer = System.currentTimeMillis() - durationDeltaMs.coerceAtLeast(0L)
    val fieldSet = runCatching {
        findDeclaredField(viewModel.javaClass, "_timer")?.let { field ->
            field.isAccessible = true
            field.setLong(viewModel, timer)
            true
        } == true
    }.getOrDefault(false)
    if (fieldSet) return
    runCatching {
        val accessor = viewModel.javaClass.declaredMethods.firstOrNull { method ->
            method.name == "access\$set_timer\$p" && method.parameterTypes.size == 2
        } ?: return
        accessor.isAccessible = true
        accessor.invoke(null, viewModel, timer)
    }.onFailure { error ->
        XposedBridge.log("$LOG_PREFIX read aloud statistics timer failed: ${error.stackTraceToString()}")
    }
}

internal fun ReaderHook.findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
    var current: Class<*>? = type
    while (current != null) {
        val field = current.declaredFields.firstOrNull { it.name == name }
        if (field != null) return field
        current = current.superclass
    }
    return null
}

internal fun ReaderHook.arrangementSpacedBy(dp: Int): Any =
    ARRANGEMENT_SPACED_BY_METHOD_CANDIDATES.firstNotNullOfOrNull { name ->
        runCatching {
            staticObject(ARRANGEMENT_CLASS, "INSTANCE").javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1
            }?.invoke(staticObject(ARRANGEMENT_CLASS, "INSTANCE"), udp(dp))
        }.getOrNull()
    } ?: error("Arrangement.spacedBy method not found")

internal fun ReaderHook.sectionTitleModifier(top: Int, bottom: Int): Any =
    composeMethod(PADDING_KT_CLASS, PADDING_DEFAULT_METHOD, 7).invoke(
        null,
        modifierInstance(),
        0f,
        udp(top),
        0f,
        udp(bottom),
        5,
        null,
    )

internal fun ReaderHook.fillMaxWidth(modifier: Any): Any =
    composeMethod(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, modifier, 0f, 1, null)

internal fun ReaderHook.alignmentTop(): Any =
    callNoArg(staticObject(ALIGNMENT_CLASS, "INSTANCE"), "getTop") ?: error("Alignment.Top not found")

internal fun ReaderHook.modifierInstance(): Any =
    staticObject(MODIFIER_CLASS, "INSTANCE")

internal fun ReaderHook.udp(value: Int): Float =
    udpMethod().invoke(null, value) as Float

/** dp→px 换算方法。每次 loadClass + declaredMethods 全扫太贵，解析一次存下来。 */
private fun ReaderHook.udpMethod(): Method =
    synchronized(composeMethodCache) {
        composeMethodCache.getOrPut("$UNIT_EXT_KT_CLASS#$UDP_METHOD/int") {
            classLoader.loadClass(UNIT_EXT_KT_CLASS).declaredMethods.first {
                it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }.apply { isAccessible = true }
        }
    }

/**
 * 换算结果按 dp 值缓存，失败返回 null。
 *
 * 供每帧级别的热路径使用（例如挂在 Modifier.height() 上的 hook）：那里连一次反射调用
 * 都不该有。换算结果只取决于 dp 值，进程内不变。
 */
internal fun ReaderHook.cachedUdp(value: Int): Float? {
    synchronized(udpValueCache) {
        udpValueCache[value]?.let { return it }
    }
    val resolved = runCatching { udp(value) }.getOrNull() ?: return null
    synchronized(udpValueCache) {
        udpValueCache[value] = resolved
    }
    return resolved
}

internal fun ReaderHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)

internal fun ReaderHook.composeMethod(className: String, methodName: String, parameterCount: Int): Method {
    val cacheKey = "$className#$methodName/$parameterCount"
    return synchronized(composeMethodCache) {
        composeMethodCache.getOrPut(cacheKey) {
            classLoader.loadClass(className).declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.size == parameterCount
            }?.apply { isAccessible = true }
                ?: error("$className.$methodName/$parameterCount not found")
        }
    }
}

internal fun ReaderHook.cacheThemeColors(composer: Any) {
    runCatching {
        val colorScheme = hostColorScheme(composer)
        cachedThemeColors = ThemeColors(
            pageBackground = hostThemeColor("getBackgroundAuto", colorScheme),
            cardBackground = hostThemeColor("getBackgroundBright", colorScheme),
            inputBackground = hostThemeColor("getBackgroundBright", colorScheme),
            primaryText = hostColorSchemeColor(colorScheme, "getOnBackground-0d7_KjU"),
            secondaryText = hostThemeColor("getOnBackgroundVariant", colorScheme),
            stroke = hostThemeColor("getBorderVariant", colorScheme),
            chipBackground = hostThemeColor("getBackgroundDim", colorScheme),
            action = hostColorSchemeColor(colorScheme, "getPrimary-0d7_KjU"),
        )
        latestThemeColors = cachedThemeColors
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX cache host theme colors failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hostColorScheme(composer: Any): Any {
    val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
    val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
    return composeMethod(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
        ?: error("MaterialTheme.colorScheme unavailable")
}

internal fun ReaderHook.hostThemeColor(methodName: String, colorScheme: Any): Int =
    composeColorToArgb(
        composeMethod(THEME_KT_CLASS, methodName, 1).invoke(null, colorScheme) as Long,
    )

internal fun ReaderHook.hostColorSchemeColor(colorScheme: Any, methodName: String): Int =
    composeColorToArgb(
        colorScheme.javaClass.methods.first {
            it.name == methodName && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(colorScheme) as Long,
    )

internal fun ReaderHook.composeColorToArgb(color: Long): Int =
    composeInterop.colorToArgb(color)

internal fun ReaderHook.staticInt(className: String, fieldName: String): Int =
    classLoader.loadClass(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

internal fun ReaderHook.staticObject(className: String, fieldName: String): Any {
    val clazz = classLoader.loadClass(className)
    val field = runCatching { clazz.getDeclaredField(fieldName) }
        .recoverCatching { clazz.getField(fieldName) }
        .recoverCatching { clazz.getDeclaredField("Companion") }
        .getOrElse {
            val fields = clazz.declaredFields.joinToString { field -> "${field.name}:${field.type.name}" }
            error("$className.$fieldName not found; fields=[$fields]")
        }
    field.isAccessible = true
    return field.get(null)
}

internal fun ReaderHook.createNativeEditAction(icon: Any): Any? =
    createSelectionMenuAction(
        if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u7f16\u8f91",
        icon,
        nativeFunction0 {
            openNativeSelectionEditor()
        },
        "create native edit action",
    )

internal fun ReaderHook.editImageVector(): Any? =
    runCatching {
        val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
        classLoader.loadClass(EDIT_ICON_CLASS).declaredMethods.firstOrNull {
            it.name == "getEdit" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(null, outlined)
    }.getOrNull()

internal fun ReaderHook.nativeFunction0(block: () -> Unit): Any {
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

internal fun ReaderHook.targetKotlinUnit(): Any? =
    runCatching { classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()

internal fun ReaderHook.resultCardLayoutParams(activity: Activity): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        leftMargin = dp(activity, 14)
        rightMargin = dp(activity, 14)
        bottomMargin = dp(activity, 2)
    }

internal fun ReaderHook.currentReadingTarget(): ReadingTarget? {
    val page = currentPageRef?.get() ?: return null
    val cfi = callString(page, "getAnchor")
        .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
        .takeIf { it.isNotBlank() }
        ?: return null
    return ReadingTarget(
        cfi = cfi,
        chapterIndex = (callNoArg(page, "getChapterIndex") as? Int) ?: 0,
        title = callString(callNoArg(page, "getChapter"), "getTitle").ifBlank { "\u539f\u6765\u8fdb\u5ea6" },
        summary = callString(page, "getSummary"),
    )
}

internal fun ReaderHook.forceRefreshReaderWindow(source: String): Boolean {
    val viewModel = currentViewModelRef?.get() ?: return false
    return runCatching {
        val windowKey = XposedHelpers.getObjectField(viewModel, "windowKey")
        XposedHelpers.callMethod(windowKey, "setValue", "reamicro_${source}_${System.currentTimeMillis()}")
        XposedBridge.log("$LOG_PREFIX reader window refresh requested: $source")
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader window refresh failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
}

internal fun ReaderHook.idOrNull(mark: Any?): Long? =
    mark?.let(::searchResultHighlightMarkId)

internal fun ReaderHook.dispatchTapDirection(receiver: Any?, viewModel: Any?, next: Boolean): Boolean =
    runCatching {
        val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$TapDirection")
        val direction = System.currentTimeMillis() * if (next) 1L else -1L
        val intent = intentClass.getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(direction)
        dispatchReaderIntent(receiver, viewModel, intent, if (next) "TapDirectionNext" else "TapDirectionPrev")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX full-text search tap direction correction failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun ReaderHook.textRange(start: Int, end: Int): Long? =
    runCatching {
        val cls = classLoader.loadClass("androidx.compose.ui.text.TextRangeKt")
        val method = cls.methods.firstOrNull {
            it.name == "TextRange" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return@runCatching null
        (method.invoke(null, start, end) as? Number)?.toLong()
    }.getOrNull()

internal fun ReaderHook.markColorTokenToColor(token: String): Long? =
    runCatching {
        val cls = classLoader.loadClass("org.epub.ui.BodyKt")
        val method = cls.methods.firstOrNull {
            it.name == "markColorTokenToColor" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return@runCatching null
        (method.invoke(null, token) as? Number)?.toLong()
    }.getOrNull()

internal fun ReaderHook.companionObject(cls: Class<*>): Any? {
    for (fieldName in listOf("INSTANCE", "Companion")) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }
    }
    return cls.declaredFields.firstNotNullOfOrNull { field ->
        runCatching {
            field.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.takeIf { it.type.name.endsWith("\$Companion") || it.type.simpleName == "Companion" }
                ?.apply { isAccessible = true }
                ?.get(null)
        }.getOrNull()
    }
}

internal fun ReaderHook.removeTaggedViews(root: ViewGroup?, tagValue: Int) {
    if (root == null) return
    for (index in root.childCount - 1 downTo 0) {
        val child = root.getChildAt(index) ?: continue
        if (child.tag == tagValue) {
            root.removeViewAt(index)
        } else {
            removeTaggedViews(child as? ViewGroup, tagValue)
        }
    }
}

internal fun ReaderHook.dispatchReaderIntent(receiver: Any?, viewModel: Any?, intent: Any, label: String): Boolean {
    val targetViewModel = viewModel
    if (targetViewModel != null) {
        val viewModelIntent = targetViewModel.javaClass.methods
            .filter { method ->
                method.name == "onIntent" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(intent.javaClass)
            }
            .sortedByDescending { method ->
                if (method.parameterTypes[0].name == READER_UI_INTENT_CLASS) 1 else 0
            }
            .firstOrNull()
        if (viewModelIntent != null) {
            viewModelIntent.invoke(targetViewModel, intent)
            XposedBridge.log("$LOG_PREFIX reader intent $label sent to ReaderViewModel.onIntent")
            return true
        }
    }
    if (receiver != null) {
        val receiverIntent = receiver.javaClass.methods.firstOrNull {
            it.name == "intent" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(intent.javaClass)
        }
        if (receiverIntent != null) {
            receiverIntent.invoke(receiver, intent)
            XposedBridge.log("$LOG_PREFIX reader intent $label sent to receiver")
            return true
        }
    }
    XposedBridge.log(
        "$LOG_PREFIX reader intent $label dispatch failed: " +
            "viewModel=${targetViewModel?.javaClass?.name.orEmpty()} receiver=${receiver?.javaClass?.name.orEmpty()}",
    )
    return false
}

internal fun ReaderHook.parseNcxTitlePaths(root: File, file: File): Map<String, String> = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val stack = ArrayList<TocNode>()
    val result = linkedMapOf<String, String>()
    var captureText = false
    val text = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                "navpoint" -> stack.add(TocNode())
                "content" -> stack.lastOrNull()?.href = normalizeHref(parser.getAttributeValue(null, "src").orEmpty())
                "text" -> if (stack.isNotEmpty()) {
                    captureText = true
                    text.setLength(0)
                }
            }
            XmlPullParser.TEXT -> if (captureText) text.append(parser.text.orEmpty())
            XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                "text" -> if (captureText) {
                    stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                    captureText = false
                }
                "navpoint" -> if (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.lastIndex)
                    putTocNodeTitlePath(result, root, file, stack, node)
                }
            }
        }
        parser.next()
    }
    result
}.getOrDefault(emptyMap())

internal fun ReaderHook.parseNavTitlePaths(root: File, file: File): Map<String, String> = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val stack = ArrayList<TocNode>()
    val result = linkedMapOf<String, String>()
    var tocNavDepth = 0
    var captureDepth = 0
    val text = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.lowercase(Locale.ROOT)
                if (tocNavDepth > 0) tocNavDepth++
                if (name == "nav" && tocNavDepth == 0 && tagHasTocType(parser)) tocNavDepth = 1
                if (captureDepth > 0) captureDepth++
                when {
                    tocNavDepth == 0 -> Unit
                    name == "li" -> stack.add(TocNode())
                    name == "a" && stack.isNotEmpty() -> {
                        stack.last().href = normalizeHref(parser.getAttributeValue(null, "href").orEmpty())
                        captureDepth = 1
                        text.setLength(0)
                    }
                }
            }
            XmlPullParser.TEXT -> if (captureDepth > 0) text.append(parser.text.orEmpty())
            XmlPullParser.END_TAG -> {
                val name = parser.name.lowercase(Locale.ROOT)
                if (captureDepth > 0) {
                    captureDepth--
                    if (captureDepth == 0) {
                        stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                    }
                }
                if (tocNavDepth > 0 && name == "li" && stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.lastIndex)
                    putTocNodeTitlePath(result, root, file, stack, node)
                }
                if (tocNavDepth > 0) tocNavDepth--
            }
        }
        parser.next()
    }
    result
}.getOrDefault(emptyMap())

internal fun ReaderHook.tagHasTocType(parser: XmlPullParser): Boolean {
    for (index in 0 until parser.attributeCount) {
        val name = parser.getAttributeName(index).orEmpty()
        val value = parser.getAttributeValue(index).orEmpty()
        if ((name == "type" || name.endsWith(":type") || name == "role") &&
            value.lowercase(Locale.ROOT).contains("toc")) {
            return true
        }
    }
    return false
}

internal fun ReaderHook.putTocNodeTitlePath(
    result: MutableMap<String, String>,
    root: File,
    source: File,
    stack: List<TocNode>,
    node: TocNode,
) {
    val title = node.title.normalizeChapterTitle()
    val href = normalizeCatalogHref(node.href)
    if (title.isBlank() || href.isBlank()) return
    val fileHref = href.substringBefore('#')
    val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() }
    val target = File(source.parentFile ?: root, fileHref).canonicalFileSafe() ?: File(source.parentFile ?: root, fileHref)
    val key = normalizePath(relativePath(root, target)) + anchor.orEmpty().let { if (it.isBlank()) "" else "#$it" }
    val fileKey = key.substringBefore('#')
    val path = (stack.map { it.title.normalizeChapterTitle() } + title)
        .filter { it.isNotBlank() }
        .dedupeAdjacent()
        .joinToString(" ")
    if (key.isNotBlank() && path.isNotBlank()) {
        result.putIfAbsent(key, path)
        result.putIfAbsent(fileKey, path)
    }
}

internal fun ReaderHook.normalizeHref(value: String): String =
    runCatching { URLDecoder.decode(value.trim(), "UTF-8") }
        .getOrDefault(value.trim())
        .substringBefore('?')
        .replace('\uFEFF', ' ')
        .trim()

internal fun ReaderHook.currentBookCoverUri(context: CatalogContext): String {
    val root = currentEpubRoot()
    listOf(
        currentUiStateBook(),
        context.book,
        bottomReadAloudBookRef?.get(),
        bottomSearchBookRef?.get(),
        lastCatalogContext?.book,
    ).filterNotNull().forEach { book ->
        coverUriFromBook(book, root)?.let { return prepareCoverUriForService(it) }
    }
    return root?.let(::coverUriFromEpubRoot)?.let(::prepareCoverUriForService).orEmpty()
}

internal fun ReaderHook.prepareCoverUriForService(value: String): String {
    val raw = value.trim()
    if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return raw
    if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
    val bytes = when {
        raw.startsWith("content://", ignoreCase = true) -> runCatching {
            activityProvider()?.contentResolver?.openInputStream(android.net.Uri.parse(raw))?.use { it.readBytes() }
        }.getOrNull()
        raw.startsWith("file://", ignoreCase = true) -> runCatching {
            File(android.net.Uri.parse(raw).path.orEmpty()).takeIf { it.isFile }?.readBytes()
        }.getOrNull()
        else -> runCatching {
            File(raw).takeIf { it.isFile }?.readBytes()
        }.getOrNull()
    } ?: return raw
    return coverDataUriForService(bytes) ?: raw
}

internal fun ReaderHook.coverDataUriForService(bytes: ByteArray): String? =
    runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        val sample = coverSampleSizeForService(options.outWidth, options.outHeight)
        val bitmap = BitmapFactory.Options().run {
            inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        } ?: return@runCatching null
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        bitmap.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX read aloud cover encode failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.coverSampleSizeForService(width: Int, height: Int): Int {
    var sample = 1
    var maxEdge = maxOf(width, height)
    while (maxEdge / sample > READ_ALOUD_COVER_MAX_EDGE_PX) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

internal fun ReaderHook.coverUriFromBook(book: Any, root: File?): String? {
    val methodCandidates = listOf(
        "getCover",
        "getCoverUrl",
        "getCoverUri",
        "getCoverPath",
        "getImg",
        "getImage",
        "getImageUrl",
        "getThumbnail",
        "getThumbnailUrl",
    )
    methodCandidates
        .asSequence()
        .map { callString(book, it) }
        .mapNotNull { normalizeCoverCandidate(it, root) }
        .firstOrNull()
        ?.let { return it }

    val fieldCandidates = setOf(
        "cover",
        "coverUrl",
        "coverUri",
        "coverPath",
        "img",
        "image",
        "imageUrl",
        "thumbnail",
        "thumbnailUrl",
    )
    return (book.javaClass.fields.asSequence() + book.javaClass.declaredFields.asSequence())
        .filter { it.name in fieldCandidates }
        .mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                normalizeCoverCandidate(field.get(book)?.toString().orEmpty(), root)
            }.getOrNull()
        }
        .firstOrNull()
}

internal fun ReaderHook.normalizeCoverCandidate(value: String, root: File?): String? {
    val raw = value.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
    if (raw.startsWith("data:", ignoreCase = true) ||
        raw.startsWith("content://", ignoreCase = true) ||
        raw.startsWith("file://", ignoreCase = true) ||
        raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true)
    ) {
        return raw
    }
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    File(decoded).takeIf { it.isFile && it.isCoverImageFile() }?.let { return it.absolutePath }
    val normalized = normalizePath(decoded.substringBefore('#'))
    if (root != null && normalized.isNotBlank()) {
        coverFileForHref(root, root, normalized)?.let { return it.absolutePath }
    }
    return null
}

internal fun ReaderHook.findOpfCoverFile(root: File): File? {
    for (opf in root.walkTopDown().filter { it.isFile && it.extension.equals("opf", ignoreCase = true) }) {
        val href = opfCoverHref(opf) ?: continue
        val base = opf.parentFile ?: root
        coverFileForHref(base, root, href)?.let { return it }
    }
    return null
}

internal fun ReaderHook.opfCoverHref(opf: File): String? = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(opf.inputStream().bufferedReader(StandardCharsets.UTF_8))
    val manifest = linkedMapOf<String, String>()
    var coverId = ""
    var coverImageHref = ""
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) {
            when (parser.name.lowercase(Locale.ROOT)) {
                "meta" -> {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    if (name.equals("cover", ignoreCase = true)) {
                        coverId = parser.getAttributeValue(null, "content").orEmpty()
                    }
                }
                "item" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    val href = parser.getAttributeValue(null, "href").orEmpty()
                    val properties = parser.getAttributeValue(null, "properties").orEmpty()
                    if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
                    if (href.isNotBlank() && properties.split(Regex("\\s+")).any { it == "cover-image" }) {
                        coverImageHref = href
                    }
                }
            }
        }
        parser.next()
    }
    coverImageHref.ifBlank { manifest[coverId].orEmpty() }.takeIf { it.isNotBlank() }
}.getOrNull()

internal fun ReaderHook.coverFileForHref(base: File, root: File, href: String): File? {
    val normalized = normalizePath(URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
    if (normalized.isBlank()) return null
    val candidates = buildList {
        add(File(base, normalized))
        add(File(root, normalized))
        if ('/' in normalized) add(File(root, normalized.substringAfter('/')))
        if ('/' in normalized) add(File(root, normalized.substringAfterLast('/')))
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate.canonicalFileSafe()?.takeIf { it.isFile && it.isCoverImageFile() }
    }
}

internal fun ReaderHook.htmlAnchorTextStart(raw: String, anchor: String): Int? {
    val decoded = normalizeHref(anchor)
    val escaped = Regex.escape(decoded)
    val match = Regex("""(?is)<[^>]+\b(?:id|name)\s*=\s*(['"])$escaped\1[^>]*>""")
        .find(raw) ?: return null
    return htmlToSearchText(raw.substring(0, match.range.first)).length
}

internal fun ReaderHook.snippetFor(text: String, start: Int, end: Int): SearchSnippet {
    val compactRadius = if (text.any { it.code > 127 }) {
        SEARCH_CJK_SNIPPET_RADIUS + SEARCH_SNIPPET_EXTRA_RADIUS
    } else {
        SEARCH_SNIPPET_RADIUS + SEARCH_SNIPPET_EXTRA_RADIUS
    }
    val from = (start - compactRadius).coerceAtLeast(0)
    val to = (end + compactRadius).coerceAtMost(text.length)
    val prefix = if (from > 0) "\u2026" else ""
    val suffix = if (to < text.length) "\u2026" else ""
    val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
    val leadingTrim = text.substring(from, start).length -
        text.substring(from, start).replace(Regex("^\\s+"), "").length
    val matchStart = prefix.length + (start - from - leadingTrim).coerceAtLeast(0)
    val matchEnd = (matchStart + (end - start)).coerceAtMost(prefix.length + body.length)
    return SearchSnippet(prefix + body + suffix, matchStart, matchEnd)
}

internal fun ReaderHook.bodyOnlyHtml(value: String): String =
    Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?: value
            .replace(Regex("<head\\b[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<title\\b[\\s\\S]*?</title>", RegexOption.IGNORE_CASE), " ")

internal fun ReaderHook.bookTitle(context: CatalogContext): String =
    readableBookTitle(
        callString(currentPageRef?.get(), "getBookTitle"),
        readableBookTitleFromBook(currentUiStateBook()),
        readableBookTitleFromBook(context.book),
        titleFromCurrentEpubRoot(),
    )

internal fun ReaderHook.fallbackCurrentBookTitle(): String {
    val title = readableBookTitle(
        callString(currentPageRef?.get(), "getBookTitle"),
        readableBookTitleFromBook(currentUiStateBook()),
        lastCatalogContext?.let { readableBookTitleFromBook(it.book) }.orEmpty(),
        titleFromCurrentEpubRoot(),
    )
    if (title.isNotBlank()) return title
    return fallbackCurrentChapterTitle()
}

internal fun ReaderHook.currentUiStateBook(): Any? {
    val viewModel = currentViewModelRef?.get() ?: return null
    val uiStateFlow = callNoArg(viewModel, "getUiState") ?: return null
    val uiState = callNoArg(uiStateFlow, "getValue") ?: return null
    return callNoArg(uiState, "getBook")
}

internal fun ReaderHook.readableBookTitleFromBook(book: Any?): String =
    readableBookTitle(
        callString(book, "getTitle"),
        callString(book, "getSubtitle"),
        callString(book, "getUri"),
        callString(book, "getUuid"),
    )

internal fun ReaderHook.bookKeyFromBook(book: Any, title: String): String =
    listOf(
        currentEpubRoot()?.absolutePath.orEmpty(),
        callString(book, "getId"),
        callString(book, "getBookId"),
        title,
    ).filter { it.isNotBlank() }.joinToString(separator = "|")

internal fun ReaderHook.readableBookTitle(vararg candidates: String): String =
    candidates
        .map { cleanBookTitleCandidate(it) }
        .firstOrNull { it.isNotBlank() && !isInternalBookTitle(it) && !isGenericBookTitle(it) }
        .orEmpty()

internal fun ReaderHook.cleanBookTitleCandidate(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val segment = trimmed
        .substringAfterLast('|')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".epub")
        .removeSuffix(".EPUB")
        .trim()
    return segment
}

internal fun ReaderHook.isInternalBookTitle(value: String): Boolean =
    value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
        value.matches(Regex("^[0-9a-fA-F]{16,}$")) ||
        value.contains('|')

internal fun ReaderHook.isGenericBookTitle(value: String): Boolean =
    value == "\u672c\u4e66" || value == "\u56fe\u4e66"

internal fun ReaderHook.bookKey(context: CatalogContext): String =
    listOf(
        currentEpubRoot()?.absolutePath.orEmpty(),
        callString(context.book, "getId"),
        callString(context.book, "getBookId"),
        bookTitle(context),
    ).filter { it.isNotBlank() }.joinToString(separator = "|")

internal fun ReaderHook.createThoughtStyleEditor(
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

internal fun ReaderHook.thoughtStyleSaveButton(
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

internal fun ReaderHook.focusEditorAndShowKeyboard(activity: Activity, editor: EditText) {
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

internal fun ReaderHook.hideKeyboard(view: View) {
    runCatching {
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
}

internal fun ReaderHook.candidateCurrentTextFiles(): List<File> {
    val root = currentEpubRef?.get()
        ?.let { callNoArg(it, "getDirectory")?.toString() }
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory }
        ?: return emptyList()
    val itemRefs = (currentEpubRef?.get()?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.toList().orEmpty()
    val currentFile = currentTextContentFile(root, itemRefs)
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

internal fun ReaderHook.currentTextContentFile(root: File, itemRefs: Iterable<*>): File? {
    val page = currentPageRef?.get() ?: return null
    readAloudPageHrefCandidates(page).forEach { href ->
        searchFileForHref(root, href)?.let { file ->
            XposedBridge.log("$LOG_PREFIX read aloud current file by href href=$href file=${relativePath(root, file)}")
            return file
        }
    }
    val cfi = callString(page, "getAnchor")
        .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
    val cfiItemRefIndex = Regex("""epubcfi\(/\d+/(\d+)""")
        .find(cfi)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: -1
    if (cfiItemRefIndex >= 0) {
        val byCfi = itemRefs.firstOrNull { item ->
            (callNoArg(item, "getIndex") as? Number)?.toInt() == cfiItemRefIndex ||
                (callNoArg(item, "getSpineIndex") as? Number)?.toInt() == cfiItemRefIndex
        }?.let { item ->
            searchFileForHref(root, callString(item, "getHref"))
        }
        if (byCfi != null) {
            XposedBridge.log(
                "$LOG_PREFIX read aloud current file by cfi itemRef=$cfiItemRefIndex file=${relativePath(root, byCfi)}",
            )
            return byCfi
        }
    }
    val spineIndex = (callNoArg(page, "getSpineIndex") as? Number)?.toInt() ?: -1
    if (spineIndex < 0) {
        logReadAloudCurrentPageProbe(root, itemRefs, "missing-spine-index")
        return null
    }
    val currentHref = itemRefs.firstOrNull {
        (callNoArg(it, "getSpineIndex") as? Number)?.toInt() == spineIndex ||
            (callNoArg(it, "getIndex") as? Number)?.toInt() == spineIndex
    }
        ?.let { callString(it, "getHref") }
        ?.takeIf { it.isNotBlank() }
        ?: run {
            logReadAloudCurrentPageProbe(root, itemRefs, "spine-index-not-in-itemRefs")
            return null
        }
    return searchFileForHref(root, currentHref).also { file ->
        if (file == null) logReadAloudCurrentPageProbe(root, itemRefs, "href-not-found:$currentHref")
    }
}

internal fun ReaderHook.replaceUniqueTextInFile(file: File, oldText: String, newText: String): Boolean {
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

internal fun ReaderHook.escapeXmlText(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun ReaderHook.invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
    val latch = CountDownLatch(1)
    var value: Any? = null
    var error: Throwable? = null
    val continuationClass = XposedHelpers.findClass(KOTLIN_CONTINUATION_CLASS, classLoader)
    val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
        it.name == "throwOnFailure" && it.parameterTypes.size == 1
    }.apply { isAccessible = true }
    val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
        when (proxyMethod.name) {
            "getContext" -> emptyCoroutineContext()
            "resumeWith" -> {
                val result = proxyArgs?.getOrNull(0)
                runCatching {
                    throwOnFailure.invoke(null, result)
                    value = result
                }.onFailure {
                    error = if (it is InvocationTargetException) it.targetException ?: it else it
                }
                latch.countDown()
                targetUnit()
            }
            "toString" -> "ReaMicroReaderContinuation"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === proxyArgs?.getOrNull(0)
            else -> null
        }
    }
    val returned = try {
        method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
    if (returned !== coroutineSuspended()) return returned
    latch.await()
    error?.let { throw it }
    return value
}

internal fun ReaderHook.emptyCoroutineContext(): Any =
    XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
        .getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)

internal fun ReaderHook.coroutineSuspended(): Any =
    runCatching {
        XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
            it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(null)
    }.getOrElse {
        (XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader).enumConstants ?: emptyArray())
            .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
    }

internal fun ReaderHook.targetUnit(): Any? = runCatching {
    XposedHelpers.findClass(KOTLIN_UNIT_CLASS, classLoader)
        .getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)
}.getOrNull()

/**
 * 无参方法调用。方法查找按「类名#方法名」缓存，包括查不到的负结果。
 *
 * ReaderHook 簇有一百多个调用点，其中不少在排版/绘制路径上。原先每次调用都要
 * javaClass.methods 全量扫一遍（ART 每次返回新的 Method[] 副本），是热路径上的
 * 固定税，也是 GC 的持续来源。
 */
internal fun ReaderHook.callNoArg(target: Any?, name: String): Any? {
    target ?: return null
    val method = noArgMethod(target, name) ?: return null
    return runCatching { method.invoke(target) }.getOrNull()
}

private fun ReaderHook.noArgMethod(target: Any, name: String): Method? {
    val cacheKey = "${target.javaClass.name}#$name"
    synchronized(noArgMethodCache) {
        if (noArgMethodCache.containsKey(cacheKey)) return noArgMethodCache[cacheKey]
    }
    val found = runCatching {
        target.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.name == name
        }?.apply { isAccessible = true }
    }.getOrNull()
    synchronized(noArgMethodCache) {
        noArgMethodCache[cacheKey] = found
    }
    return found
}

internal fun ReaderHook.callString(target: Any?, name: String): String =
    callNoArg(target, name)?.toString().orEmpty()

internal fun ReaderHook.setString(target: Any, name: String, value: String) {
    target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
    }?.invoke(target, value)
}

internal fun ReaderHook.setInt(target: Any, name: String, value: Int) {
    target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
    }?.invoke(target, value)
}

internal fun ReaderHook.setFloat(target: Any, name: String, value: Float) {
    target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Float::class.javaPrimitiveType
    }?.invoke(target, value)
}
