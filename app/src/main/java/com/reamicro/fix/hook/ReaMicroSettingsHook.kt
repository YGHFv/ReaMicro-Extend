package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.NinePatchDrawable
import android.widget.EditText
import android.widget.TextView
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.core.ComposeInterop
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.ReaderHighlightPreviewTextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

/**
 * Injects the module settings UI into the host About/Settings surface.
 *
 * The host is Compose, but this module cannot compile against host internals, so most UI is
 * created through reflected Compose calls and small value objects kept in this class.
 */
class ReaMicroSettingsHook(
    internal val classLoader: ClassLoader,
    internal val activityProvider: () -> Activity?,
    internal val settings: XposedModuleSettings,
    internal val onGlobalFontChanged: () -> Unit = {},
) {
    // Compose 反射互操作的共用实现，避免各 hook 各存一份逐渐漂移的副本。
    internal val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = ::cls,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    internal val accountController = AccountCompletionController(classLoader, activityProvider)
    internal val settingsBuildDepth = ThreadLocal.withInitial { 0 }
    internal val itemCount = ThreadLocal.withInitial { 0 }
    internal val injectingModuleItem = ThreadLocal.withInitial { false }
    // 高亮界面（ReaderHighlightScreen）LazyColumn 渲染期间的深度与去重标志。
    // 借用宿主自身调用 LazyListScope.item$default 的时机注入"补全计划"入口。
    internal val highlightScreenBuildDepth = ThreadLocal.withInitial { 0 }
    internal val highlightEntryInjected = ThreadLocal.withInitial { false }
    internal val settingsEntryTitleOverride = ThreadLocal.withInitial<String?> { null }
    internal val navigatingModuleRoute = ThreadLocal.withInitial { false }
    internal val poppingInjectedRoute = ThreadLocal.withInitial { false }
    internal val methodCache = mutableMapOf<String, Method>()
    // ReaderHook 在进入高亮界面前设置的入口点击回调（打开三段式高亮规则 sheet）。
    @Volatile internal var highlightScreenEntryOnClick: (() -> Unit)? = null
    internal var lazyItemDefaultMethod: Method? = null
    @Volatile internal var currentSettingsNavGraphScope: Any? = null
    @Volatile internal var currentSettingsNavController: Any? = null
    // 全 app 唯一的 NavGraphScope（setup() 里 new 一次，reader/设置共用）。
    // 阅读页没有设置页的 NavGraphScope 捕获时机，用它作为导航兜底 scope。
    @Volatile internal var lastKnownNavGraphScope: Any? = null
    @Volatile internal var injectedRouteStack: List<InjectedRoute> = emptyList()
    @Volatile internal var injectedRouteUiState: Any? = null
    @Volatile internal var fontLibraryVersionUiState: Any? = null
    @Volatile internal var onlineSourceVersionUiState: Any? = null
    @Volatile internal var aiApiVersionUiState: Any? = null
    @Volatile internal var readerHighlightVersionUiState: Any? = null
    @Volatile internal var onlineEpubStyleVersionUiState: Any? = null
    // 成书样式弹窗里 CSS 预览的防抖任务，逐字输入时只保留最后一次刷新。
    @Volatile internal var pendingOnlineEpubPreviewRefresh: Runnable = Runnable {}
    // 样式关联图片选择的回调，选图 Activity 返回后回填到弹窗。
    @Volatile internal var pendingOnlineEpubStyleImagePick: ((File) -> Unit)? = null
    // 头图蒙版合成结果缓存，key 为样式 + 原图，避免每次输入 CSS 都重算上百万像素。
    internal val onlineEpubHeaderPreviewCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // 阅读页高亮规则 sheet 内的子页面导航状态：0=规则列表，1=高亮样式列表。
    // 借用 mutableState 让 sheet 的 content lambda 读取后可随点击重组，实现 sheet 内翻页而非弹窗。
    @Volatile internal var readerHighlightSheetSubPageUiState: Any? = null
    // 阅微原生高亮页（ReaderHighlightScreen）内的"补全计划"页面导航状态：
    // 0=原生高亮页，1=补全计划规则页，2=补全计划-高亮样式页。
    // 点击"补全计划"时置 1，令 HighlightPageContent 改渲染我们的补全计划内容（同一整页跳转，不用弹窗）。
    @Volatile internal var readerHighlightScreenPlanUiState: Any? = null
    @Volatile internal var profileBackgroundVersionUiState: Any? = null
    @Volatile internal var pendingDeleteFontUiState: Any? = null
    @Volatile internal var lastFontImportToken: String = ""
    @Volatile internal var lastFontImportAtMs: Long = 0L
    @Volatile internal var lastOnlineSourceImportToken: String = ""
    @Volatile internal var lastOnlineSourceImportAtMs: Long = 0L
    @Volatile internal var lastExternalImportToken: String = ""
    @Volatile internal var lastExternalImportAtMs: Long = 0L
    @Volatile internal var pendingDeleteTtsSourceId: String = ""
    @Volatile internal var pendingDeleteTtsSourceAtMs: Long = 0L
    @Volatile internal var pendingHighlightNinePatchInputRef: WeakReference<EditText>? = null
    internal val previewFontFamilyCache = HashMap<String, Any>()
    internal val failedPreviewFontFamilyLogKeys = HashSet<String>()
    internal val fontFilesCacheLock = Any()
    @Volatile internal var cachedFontFilesVersion: Int = Int.MIN_VALUE
    @Volatile internal var cachedFontFilesAtMs: Long = 0L
    @Volatile internal var cachedFontFiles: List<File> = emptyList()
    @Volatile internal var suppressRotationSnapshotSyncUntilMs: Long = 0L
    @Volatile internal var rotationUiState: RotationUiState? = null
    @Volatile internal var accountListVersionUiState: Any? = null
    @Volatile internal var associationExpandedUiState: Any? = null
    @Volatile internal var readerExpandedUiState: Any? = null
    @Volatile internal var fontExpandedUiState: Any? = null
    @Volatile internal var accountExpandedUiState: Any? = null
    @Volatile internal var cloudExpandedUiState: Any? = null
    @Volatile internal var accountSwitchExpandedUiState: Any? = null
    @Volatile internal var accountDataExportExpandedUiState: Any? = null
    @Volatile internal var rotationExpandedUiState: Any? = null

    fun install() {
        activeInstance = this
        // 逐个登记安装结果，宿主升级后靠启动汇总定位掉线的 hook。
        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "stringResource" to ::hookStringResource,
                "navGraphScope" to ::hookNavGraphScope,
                "aboutScreen" to ::hookAboutScreen,
                "settingsListBuilder" to ::hookSettingsListBuilder,
                "lazyListItem" to ::hookLazyListItem,
                "fontDocumentPickerResult" to ::hookFontDocumentPickerResult,
                "externalSourceImportIntent" to ::hookExternalSourceImportIntent,
                "hostAccountSignOut" to ::hookHostAccountSignOut,
                "accountSecurityScreen" to ::hookAccountSecurityScreen,
            ),
        )
    }

    internal inner class ReaderHighlightPreviewTextView(context: Context) : TextView(context) {
        private var previewCss: String = ""
        private var previewPath: String = ""
        private var previewSlice: String = ""
        private var previewFallbackColor: Int = Color.TRANSPARENT
        private var previewHasFallbackFill: Boolean = false
        private var previewBitmap: Bitmap? = null
        private var previewNinePatchDrawable: NinePatchDrawable? = null
        private var previewBitmapPath: String = ""
        private var previewDrawLogKey: String = ""

        fun setHighlightPreview(
            css: String,
            path: String,
            slice: String,
            fallbackColor: Int,
            hasFallbackFill: Boolean,
        ) {
            previewCss = css
            previewPath = path
            previewSlice = slice
            previewFallbackColor = fallbackColor
            previewHasFallbackFill = hasFallbackFill
            invalidatePreviewBitmap()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            drawPreviewLineBackgrounds(canvas)
            super.onDraw(canvas)
        }

        private fun invalidatePreviewBitmap() {
            if (previewPath == previewBitmapPath && (previewBitmap != null || previewPath.isBlank())) return
            previewBitmapPath = previewPath
            previewBitmap = null
            previewNinePatchDrawable = null
            previewDrawLogKey = ""
            val assetName = previewPath.removePrefix("asset://").takeIf { it != previewPath }
            val bitmap = ReaderHighlightImageAssets.decodeBitmap(previewPath, context, LOG_PREFIX) ?: return
            previewBitmap = bitmap
            XposedBridge.log("$LOG_PREFIX highlight preview bitmap loaded path=$previewPath size=${bitmap.width}x${bitmap.height} alpha=${bitmap.hasAlpha()}")
            if (bitmap.ninePatchChunk != null) {
                previewNinePatchDrawable = NinePatchDrawable(resources, bitmap, bitmap.ninePatchChunk, Rect(), assetName ?: File(previewPath).name)
            }
        }

        private fun drawPreviewLineBackgrounds(canvas: Canvas) {
            val layout = layout ?: run {
                logPreviewDrawOnce("layout-null|$previewPath", "highlight preview draw skipped: layout unavailable path=$previewPath")
                return
            }
            val bitmap = previewBitmap
            val hasImage = bitmap != null
            if (!hasImage && !previewHasFallbackFill) {
                logPreviewDrawOnce("empty|$previewPath", "highlight preview draw skipped: no image/fallback path=$previewPath")
                return
            }
            val box = previewBoxStyle(previewCss, resources.displayMetrics.density)
            val nineSlice = bitmap?.let { parsePreviewNineSlice(previewSlice, it.width, it.height) }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = previewFallbackColor }
            var drawnRects = 0
            for (line in 0 until layout.lineCount) {
                val lineLeft = layout.getLineLeft(line)
                val lineRight = layout.getLineRight(line)
                if (lineRight <= lineLeft) continue
                val rect = Rect(
                    (totalPaddingLeft + lineLeft - box.paddingLeftPx - box.marginLeftPx).toInt(),
                    (totalPaddingTop + layout.getLineTop(line) - box.paddingTopPx - box.marginTopPx).toInt(),
                    (totalPaddingLeft + lineRight + box.paddingRightPx + box.marginRightPx).toInt(),
                    (totalPaddingTop + layout.getLineBottom(line) + box.paddingBottomPx + box.marginBottomPx).toInt(),
                )
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val saveCount = if (box.radiusPx > 0f) {
                    val path = android.graphics.Path().apply {
                        addRoundRect(RectF(rect), box.radiusPx, box.radiusPx, android.graphics.Path.Direction.CW)
                    }
                    val count = canvas.save()
                    canvas.clipPath(path)
                    count
                } else {
                    -1
                }
                if (bitmap != null) {
                    when {
                        previewNinePatchDrawable != null -> {
                            previewNinePatchDrawable?.bounds = rect
                            previewNinePatchDrawable?.draw(canvas)
                        }
                        nineSlice != null -> drawPreviewNineSlice(canvas, bitmap, nineSlice, rect)
                        else -> drawPreviewBitmapByBackgroundSize(canvas, bitmap, rect, box.backgroundSize)
                    }
                } else {
                    canvas.drawRect(rect, fillPaint)
                }
                if (saveCount >= 0) canvas.restoreToCount(saveCount)
                drawPreviewCssBorder(canvas, rect, box)
                drawnRects += 1
            }
            logPreviewDrawOnce(
                "draw|$previewPath|$drawnRects|${bitmap?.width}x${bitmap?.height}",
                "highlight preview draw path=$previewPath rects=$drawnRects bitmap=${bitmap?.width}x${bitmap?.height} size=${width}x${height}",
            )
        }

        private fun logPreviewDrawOnce(key: String, message: String) {
            if (previewDrawLogKey == key) return
            previewDrawLogKey = key
            XposedBridge.log("$LOG_PREFIX $message")
        }
    }

    internal val readerHighlightCssSizeRegex = Regex("""\b\d+(?:\.\d+)?(?:px|dp|em|rem)?\b""", RegexOption.IGNORE_CASE)
    internal val readerHighlightCssColorRegex = Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""")

    internal inner class SettingsDialogColors(context: Context) {
        private val palette = ModuleDialogTheme.palette(context)
        val card: Int = palette.pageBackground
        val border: Int = palette.border
        val title: Int = palette.title
        val body: Int = palette.body
        val field: Int = palette.rowBackground
        val primary: Int = palette.primary
        val primarySoft: Int = palette.rowBackground
        val primaryText: Int = palette.primaryText
        val neutralSoft: Int = palette.rowBackground
        val neutralText: Int = palette.neutralText
        val destructiveSoft: Int = palette.rowBackground
        val destructiveText: Int = palette.destructiveText
    }

    @Volatile internal var injectedRouteGetter: java.lang.reflect.Method? = null
    @Volatile internal var injectedRouteSetter: java.lang.reflect.Method? = null

    internal val MODULE_CHILD_ROUTES = setOf(
        InjectedRoute.AssociationCompletionSettings,
        InjectedRoute.ReaderCompletionSettings,
        InjectedRoute.CloudCompletionSettings,
        InjectedRoute.RotationCompletionSettings,
        InjectedRoute.OnlineCompletionSettings,
        InjectedRoute.OnlineDownloadStyleSettings,
        InjectedRoute.AiConfigSettings,
        InjectedRoute.FontSettings,
        InjectedRoute.AboutCompletion,
        InjectedRoute.ProfileBackgroundSettings,
    )

    internal val READER_CHILD_ROUTES = setOf(
        InjectedRoute.ReaderReadAloudSettings,
        InjectedRoute.ReaderSelectionMenuSettings,
        InjectedRoute.ReaderHighlightSettings,
        InjectedRoute.ReaderHighlightConfigSettings,
        InjectedRoute.ReaderHighlightTextSettings,
        InjectedRoute.ReaderHighlightColorPicker,
        InjectedRoute.FontPicker(FontPickerTarget.DialogueHighlight),
    )

    internal val AI_CHILD_ROUTES = setOf(
        InjectedRoute.DictionarySettings,
        InjectedRoute.DictionaryApiPicker,
        InjectedRoute.DictionaryPresetPicker,
        InjectedRoute.ImageSettings,
        InjectedRoute.ImageApiPicker,
        InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Cover),
        InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Banner),
    )

    companion object {
        @Volatile private var activeInstance: ReaMicroSettingsHook? = null

        // 从阅读页原生高亮界面点击"补全计划"进入完整聚合页（复用宿主 NavHost 页面框架，遵循宿主返回）。
        fun openReaderCompletionPlanFromReader(
            bookKey: String,
            bookTitle: String,
            navGraphScope: Any?,
        ): Boolean =
            activeInstance?.openInjectedRouteViaHostNavigation(
                InjectedRoute.ReaderCompletionPlan(bookKey, bookTitle),
                navGraphScope,
            ) ?: false

        fun openReaderBookGlobalHighlightRulesFromReader(
            bookKey: String,
            bookTitle: String,
            navGraphScope: Any?,
        ): Boolean =
            activeInstance?.openInjectedRouteViaHostNavigation(
                InjectedRoute.ReaderBookGlobalHighlightRules(bookKey, bookTitle),
                navGraphScope,
            ) ?: false

        fun openReaderBookOnlyHighlightRulesFromReader(
            bookKey: String,
            bookTitle: String,
            navGraphScope: Any?,
        ): Boolean =
            activeInstance?.openInjectedRouteViaHostNavigation(
                InjectedRoute.ReaderBookOnlyHighlightRules(bookKey, bookTitle),
                navGraphScope,
            ) ?: false

        fun renderReaderHighlightScreenEntryCard(
            bookKey: String,
            bookTitle: String,
            composer: Any,
            onClick: () -> Unit,
        ): Boolean =
            activeInstance?.runCatching {
                this.renderReaderHighlightScreenEntryCard(
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    composer = composer,
                    onClick = onClick,
                )
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight screen entry card render failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        // 高亮界面顶部注入容器：plan==0 显示入口卡片，plan>0 显示补全计划整页（同一高亮页内跳转）。
        fun renderReaderHighlightScreenContainer(
            bookKey: String,
            bookTitle: String,
            composer: Any,
            onEntryClick: () -> Unit,
        ): Boolean =
            activeInstance?.runCatching {
                this.renderReaderHighlightScreenContainer(
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    composer = composer,
                    onEntryClick = onEntryClick,
                )
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight screen container render failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        // 在阅微原生高亮界面的 LazyColumn 上，向列表最前面插入一个"补全计划"入口 item。
        fun addReaderHighlightScreenEntryLazyItem(
            lazyListScope: Any,
            bookKey: String,
            bookTitle: String,
            onClick: () -> Unit,
        ): Boolean =
            activeInstance?.runCatching {
                this.addReaderHighlightScreenEntryLazyItem(
                    lazyListScope = lazyListScope,
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    onClick = onClick,
                )
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight screen entry lazy item failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        // 进入高亮界面 LazyColumn 渲染前调用，标记区间并提供入口点击回调。
        fun beginHighlightScreenBuild(onClick: () -> Unit) {
            activeInstance?.beginHighlightScreenBuild(onClick)
        }

        // 高亮界面 LazyColumn 渲染结束后调用，结束区间。
        fun endHighlightScreenBuild() {
            activeInstance?.endHighlightScreenBuild()
        }

        fun renderReaderHighlightRulesSheetFromReader(
            globalRules: Boolean,
            bookKey: String,
            bookTitle: String,
            composer: Any,
            onClose: () -> Unit,
        ): Boolean =
            activeInstance?.runCatching {
                this.renderReaderHighlightRulesSheetFromReader(
                    globalRules = globalRules,
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    composer = composer,
                    onClose = onClose,
                )
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight rules sheet render failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        // 当前是否处于"补全计划"整页（1=规则页，2=样式页）。0 表示原生高亮页。
        fun readerHighlightScreenPlanValue(): Int =
            activeInstance?.runCatching { this.readerHighlightScreenPlanValue() }?.getOrDefault(0) ?: 0

        // 设置"补全计划"整页导航状态。点击入口置 1；返回置 0 回到原生高亮页。
        fun setReaderHighlightScreenPlan(page: Int) {
            activeInstance?.setReaderHighlightScreenPlan(page)
        }

        // 在阅微原生高亮页（HighlightPageContent）位置渲染"补全计划"整页内容，替换原生内容。
        fun renderReaderHighlightScreenPlanPage(
            bookKey: String,
            bookTitle: String,
            composer: Any,
        ): Boolean =
            activeInstance?.runCatching {
                this.renderReaderHighlightScreenPlanPage(bookKey, bookTitle, composer)
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight plan page render failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        // 2.2 更名后的设置列表构建 lambda；旧版名称不存在时会回退到签名匹配。
    }
}
