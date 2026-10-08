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
import com.reamicro.fix.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.ReaderHighlightPreviewTextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

class ReaMicroSettingsHook(
    internal val classLoader: ClassLoader,
    internal val activityProvider: () -> Activity?,
    internal val settings: XposedModuleSettings,
    internal val onGlobalFontChanged: () -> Unit = {},
) {

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

    internal val highlightScreenBuildDepth = ThreadLocal.withInitial { 0 }
    internal val highlightEntryInjected = ThreadLocal.withInitial { false }

    internal val accountSecurityBuildDepth = ThreadLocal.withInitial { 0 }
    internal val accountSecurityItemCount = ThreadLocal.withInitial { 0 }
    internal val accountSwitchEntryInjected = ThreadLocal.withInitial { false }
    internal val settingsEntryTitleOverride = ThreadLocal.withInitial<String?> { null }
    internal val navigatingModuleRoute = ThreadLocal.withInitial { false }
    internal val poppingInjectedRoute = ThreadLocal.withInitial { false }
    internal val methodCache = mutableMapOf<String, Method>()

    @Volatile internal var highlightScreenEntryOnClick: (() -> Unit)? = null
    internal var lazyItemDefaultMethod: Method? = null

    @Volatile internal var aboutVersionTapCount: Int = 0

    @Volatile internal var aboutVersionUiState: Any? = null
    @Volatile internal var currentSettingsNavGraphScope: Any? = null
    @Volatile internal var currentSettingsNavController: Any? = null

    @Volatile internal var lastKnownNavGraphScope: Any? = null
    @Volatile internal var injectedRouteStack: List<InjectedRoute> = emptyList()
    @Volatile internal var injectedRouteUiState: Any? = null
    @Volatile internal var fontLibraryVersionUiState: Any? = null
    @Volatile internal var onlineSourceVersionUiState: Any? = null
    @Volatile internal var cloudAutomationVersionUiState: Any? = null
    @Volatile internal var cloudAutomationLoading: Boolean = false
    @Volatile internal var cloudAutomationLoaded: Boolean = false
    @Volatile internal var cloudAutomationAccountId: String = ""
    @Volatile internal var cloudAutomationError: String = ""
    @Volatile internal var cloudAutomationTasks: List<com.reamicro.fix.cloud.api.CloudTask> = emptyList()
    @Volatile internal var cloudAutomationCredentials: List<com.reamicro.fix.cloud.api.ReaMicroCredential> = emptyList()
    @Volatile internal var cloudAutomationUpdatingTaskTypes: Set<String> = emptySet()
    @Volatile internal var localAutomationVersionUiState: Any? = null
    internal val localAutomationRequests = LocalAutomationRequestGate()
    @Volatile internal var localAutomationLoaded: Boolean = false
    @Volatile internal var localAutomationAccountId: String = ""
    @Volatile internal var localAutomationError: String = ""
    @Volatile internal var localAutomationTasks: List<com.reamicro.fix.cloud.local.LocalTask> = emptyList()
    @Volatile internal var localAutomationUpdatingTaskTypes: Set<String> = emptySet()
    @Volatile internal var aiApiVersionUiState: Any? = null
    @Volatile internal var readerHighlightVersionUiState: Any? = null
    @Volatile internal var onlineEpubStyleVersionUiState: Any? = null

    @Volatile internal var pendingOnlineEpubPreviewRefresh: Runnable = Runnable {}

    @Volatile internal var pendingOnlineEpubStyleImagePick: ((File) -> Unit)? = null

    internal val onlineEpubHeaderPreviewCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile internal var readerHighlightSheetSubPageUiState: Any? = null

    @Volatile internal var readerHighlightScreenPlanUiState: Any? = null
    @Volatile internal var profileBackgroundVersionUiState: Any? = null

    @Volatile internal var discoverVersionUiState: Any? = null

    @Volatile internal var discoverObservedVersion: Int = -1
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
                "accountSecurityColumn" to ::hookAccountSecurityColumn,
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

    internal inner class SettingsDialogColors(context: Context, paletteOverride: ModuleDialogTheme.Palette? = null) {
        private val palette = paletteOverride ?: ModuleDialogTheme.palette(context)
        val card: Int = palette.pageBackground
        val border: Int = palette.border
        val title: Int = palette.title
        val body: Int = palette.body
        val field: Int = palette.rowBackground
        val primary: Int = palette.primary
        val primarySoft: Int = palette.primarySoft
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
        InjectedRoute.ApiServerSettings,
        InjectedRoute.CloudAutomationSettings,
        InjectedRoute.LocalAutomationSettings,
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

        internal fun activeInstanceOrNull(): ReaMicroSettingsHook? = activeInstance

        fun openDiscoverPage(): Boolean =
            activeInstance?.let { hook ->
                runCatching {

                    hook.injectedRouteStack = emptyList()
                    hook.setInjectedRouteState(null)
                    hook.openInjectedRouteViaHostNavigation(InjectedRoute.Discover)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX open discover page failed: ${it.stackTraceToString()}")
                }.getOrDefault(false)
            } ?: false

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

        fun beginHighlightScreenBuild(onClick: () -> Unit) {
            activeInstance?.beginHighlightScreenBuild(onClick)
        }

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

        fun readerHighlightScreenPlanValue(): Int =
            activeInstance?.runCatching { this.readerHighlightScreenPlanValue() }?.getOrDefault(0) ?: 0

        fun setReaderHighlightScreenPlan(page: Int) {
            activeInstance?.setReaderHighlightScreenPlan(page)
        }

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

    }
}
