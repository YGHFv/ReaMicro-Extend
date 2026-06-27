package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.JavascriptInterface
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.notification.cancelOnlineCompletionNotificationIfDone
import com.reamicro.fix.notification.onlineCompletionDownloadBigText
import com.reamicro.fix.notification.onlineCompletionDownloadText
import com.reamicro.fix.notification.onlineCompletionDownloadTitle
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XCallback
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element

class WebDavDriveHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private val methodCache = mutableMapOf<String, Method>()
    private val syncAuthCardRender = ThreadLocal<SyncAuthCardRenderContext?>()
    private val importUnauthRender = ThreadLocal<ImportUnauthRenderContext?>()
    private val importLocalLibraryRow = ThreadLocal<ImportLocalLibraryRowContext?>()
    private val webDavIconDepth = ThreadLocal<Int>()
    private val syncAvailableTypes = ThreadLocal<List<Int>?>()
    private val syncAvailableRowIndex = ThreadLocal<Int>()
    private val webDavCloudTitleDepth = ThreadLocal<Int>()
    private val webDavCloudScreenDepth = ThreadLocal<Int>()
    private val webDavCloudTreeDepth = ThreadLocal<Int>()
    private val webDavAccountScreenDepth = ThreadLocal<Int>()
    private val webDavAccountNavGraphScope = ThreadLocal<Any?>()
    private val localLibraryIconDepth = ThreadLocal<Int>()
    private val localLibraryCloudTitleDepth = ThreadLocal<Int>()
    private val localLibraryCloudScreenDepth = ThreadLocal<Int>()
    private val onlineCompletionCloudTitleDepth = ThreadLocal<Int>()
    private val onlineCompletionCloudTitleText = ThreadLocal<String?>()
    private val onlineCompletionRenderTypesBySource = ConcurrentHashMap<String, Int>()
    private val onlineCompletionRenderSourceByType = ConcurrentHashMap<Int, String>()
    private val onlineCompletionRenderTitles = ConcurrentHashMap<Int, String>()
    private val localLibraryCloudTreeDepth = ThreadLocal<Int>()
    private val localLibraryAccountScreenDepth = ThreadLocal<Int>()
    private val localLibraryAccountNavGraphScope = ThreadLocal<Any?>()
    private val localLibraryAuthorizedDetailDepth = ThreadLocal<Int>()
    private val localLibraryDefaultFolderDepth = ThreadLocal<Int>()
    private val localLibraryDefaultFolderStringIndex = ThreadLocal<Int>()
    private val webDavNotAuthDepth = ThreadLocal<Int>()
    private val webDavNotAuthTipPending = ThreadLocal<Boolean>()
    private val syncWebDavAuthRenderedBeforeAvailable = ThreadLocal<Boolean>()
    private val webDavRenderingSyncAuthCard = ThreadLocal<Boolean>()
    private val syncAuthCardContentOwnsContext = ThreadLocal<Boolean>()
    private val webDavRunningBackupPending = ThreadLocal<Boolean>()
    private val webDavRunningSyncAuthCardDepth = ThreadLocal<Int>()
    private val homeWebDavSearchRender = ThreadLocal<HomeSearchRenderContext?>()
    private val cloudBookRowExtendedDisplay = ThreadLocal<CloudBookRowExtendedDisplayContext?>()
    private val onlineCompletionBookRowInfoDepth = ThreadLocal<Int>()
    private val onlineCompletionLocalSheetBook = ThreadLocal<Any?>()
    private val onlineCompletionLocalSheetDepth = ThreadLocal<Int>()
    private val onlineCompletionUpdateRowInjecting = ThreadLocal<Boolean>()
    private val webDavBackupCardDepth = ThreadLocal<Int>()
    private var webDavAccountNavGraphScopeRef: WeakReference<Any>? = null
    private var localLibraryAccountNavGraphScopeRef: WeakReference<Any>? = null
    @Volatile private var webDavAccountRouteRenderAtMs: Long = 0L
    @Volatile private var localLibraryAccountRouteRenderAtMs: Long = 0L
    private var composeWebDavVector: Any? = null
    private var webDavLoginDialog: Dialog? = null
    private var webDavAuthDialog: Dialog? = null
    private val webDavLibraryLock = Any()
    private var webDavLibraryFlow: Any? = null
    private val localLibraryLock = Any()
    private var localLibraryFlow: Any? = null
    private val webDavAccountAuthLock = Any()
    private var webDavAccountAuthFlow: Any? = null
    private val localLibraryAccountAuthLock = Any()
    private var localLibraryAccountAuthFlow: Any? = null
    private var webDavHttpClient: Any? = null
    private val webDavCleartextAllowed = ThreadLocal<Boolean>()
    private val webDavCleartextAllowedHosts = ConcurrentHashMap<String, Boolean>()
    private val webDavCleartextAllowedLoggedHosts = ConcurrentHashMap<String, Boolean>()
    private val webDavStorageViewModels = mutableListOf<WeakReference<Any>>()
    private var cloudStorageRepositoryRef: WeakReference<Any>? = null
    private var bookshelfRepositoryRef: WeakReference<Any>? = null
    private var workerManagerRef: WeakReference<Any>? = null
    private var workTrackerRef: WeakReference<Any>? = null
    private val pendingWebDavImports = ConcurrentHashMap<String, WebDavImportSource>()
    private val runningWebDavDownloads = ConcurrentHashMap<String, CancellableWebDavDownload>()
    private val runningNativeCloudDownloads = ConcurrentHashMap<String, NativeCloudDownload>()
    private val runningNativeCloudDownloadsById = ConcurrentHashMap<String, NativeCloudDownload>()
    private val webDavDownloadCancelPrompts = ConcurrentHashMap<String, Long>()
    private val webDavHomeSearchSeq = AtomicLong(0L)
    private val localLibraryHomeSearchSeq = AtomicLong(0L)
    private val onlineCompletionHomeSearchSeq = AtomicLong(0L)
    private val onlineCompletionSearchTargets = ConcurrentHashMap<String, OnlineDownloadTarget>()
    private val onlineCompletionRunningDownloads = ConcurrentHashMap<String, OnlineCompletionDownloadTask>()
    private val onlineCompletionRunningDownloadsByNotificationId = ConcurrentHashMap<Int, OnlineCompletionDownloadTask>()
    private val onlineCompletionRunningUpdates = ConcurrentHashMap<String, Boolean>()
    private val onlineCompletionPublisherCleanupIds = ConcurrentHashMap.newKeySet<String>()
    private val onlineCompletionImportLock = ReentrantLock(true)
    private val onlineCompletionNotificationIds = AtomicInteger(4300)
    private val onlineCompletionNotificationBlockedLogged = AtomicBoolean(false)
    private val onlineCompletionCancelReceiverRegistered = AtomicBoolean(false)
    private val onlineCompletionModuleActivityPromptAt = ConcurrentHashMap<Int, Long>()
    @Volatile private var lastHomeSearchWebDavResults: List<Any> = emptyList()
    @Volatile private var lastHomeSearchLocalResults: List<Any> = emptyList()
    private val cloudStorageScreenRefreshAt = ConcurrentHashMap<String, Long>()
    private val localLibraryListCache = ConcurrentHashMap<String, LocalLibraryListCache>()
    private val localLibrarySearchIndexLock = Any()
    @Volatile private var localLibrarySearchIndex: LocalLibrarySearchIndex? = null
    @Volatile private var localLibrarySearchIndexBuilding: Boolean = false
    private val webDavBackupViewModels = mutableListOf<WeakReference<Any>>()
    private val webDavRunningBackupBookIds = ConcurrentHashMap<Long, Boolean>()
    private val webDavRecentBackups = ConcurrentHashMap<Long, WebDavBackupSnapshot>()
    private val bookBackupContentLogSeq = AtomicLong(0L)

    fun install() {
        logWebDav("install start")
        hookWebDavCleartextPolicy()
        hookBackupTypeName()
        hookOnlineCompletionBookRowDuration()
        hookOnlineCompletionBookLocalSheet()
        hookHomeCloudResultListRenderContext()
        hookOnlineCompletionHomeCloudBookRow()
        hookHomeViewModelDependencies()
        hookWebDavRowIcon()
        hookWebDavYun115Icon()
        hookWebDavFileFolderIcon()
        hookWebDavCloudTreeIcon()
        hookImportCloudRowLabels()
        hookImportCloudRowDetail()
        hookCloudBookRowExtendedDisplay()
        hookLocalLibraryFolderPickerResult()
        hookWebDavStorageNavigate()
        hookThirdLoginWebDavRoute()
        hookWebDavAccountScreenReuse()
        hookWebDavAccountSettingComponents()
        hookWebDavAccountAuthFlow()
        hookWebDavAccountTopBarTitle()
        hookWebDavAccountDefaultFolderLambda()
        hookCloudStorageWebDavData()
        hookCloudStorageWebDavStrings()
        hookCloudStorageWebDavTitle()
        hookCloudStorageWebDavScreenScope()
        hookCloudStorageWebDavAuthTips()
        hookCloudStorageWebDavViewModelState()
        hookWebDavCloudTap()
        hookNativeCloudDownloadCancellation()
        hookSyncAuthCard()
        hookSyncAuthCardContent()
        hookDriveAuthCard()
        hookDriveOtherAvailableCard()
        hookWebDavSyncUploadRunningCard()
        hookImportAuthorizedList()
        hookImportLocalLibraryRow()
        hookImportUnauthList()
        hookImportCloudAuthorizedRow()
        hookImportCloudUnauthRow()
        hookWebDavCloudDownload()
        hookWebDavImportBookSource()
        hookWebDavCloudBackup()
        hookBookBackupViewModelState()
        hookBookBackupWebDavCard()
        hookWebDavYun115BackupCard()
        hookHomeWebDavSearch()
        hookHomeSearchTapCancellation()
        hookHomeSearchResultWebDavSection()
        hookThirdAccountWebDavRoute()
    }

    fun cleanupStartupCacheIfNeeded(context: Context) {
        ensureOnlineCompletionCancelReceiver(context)
        if (!settingsProvider().canRunStartupCacheCleanup) return
        if (!startupCacheCleanupStarted.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({
                runCatching {
                    val cacheDir = context.applicationContext?.cacheDir ?: context.cacheDir
                    val targets = listOf(
                        File(cacheDir, "downloads"),
                        File(cacheDir, "reamicro-webdav"),
                        File(cacheDir, "reamicro-local-library"),
                        File(cacheDir, "reamicro-webdav-backup"),
                        File(cacheDir, ONLINE_COMPLETION_CACHE_ROOT),
                    ) + staleTopLevelImportCacheDirs(cacheDir)
                    var deletedFiles = 0
                    var deletedBytes = 0L
                    targets.forEach { target ->
                        if (!target.exists()) return@forEach
                        if (isActiveOnlineCompletionCacheDir(target)) return@forEach
                        val stat = CacheDeleteStat()
                        deleteCachePath(target, stat)
                        deletedFiles += stat.files
                        deletedBytes += stat.bytes
                    }
                    logWebDav("startup cache cleanup files=$deletedFiles bytes=$deletedBytes")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX startup cache cleanup failed: ${it.stackTraceToString()}")
                }
            }, "ReaMicroCacheCleanup").start()
        }, STARTUP_CACHE_CLEANUP_DELAY_MS)
    }

    private fun ensureOnlineCompletionCancelReceiver(context: Context) {
        if (!onlineCompletionCancelReceiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val appContext = context.applicationContext ?: context
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ONLINE_COMPLETION_CANCEL_ACTION) return
                    val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                    val key = intent.getStringExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY).orEmpty()
                    requestOnlineCompletionDownloadCancel(receiverContext, id, key)
                }
            }
            val filter = IntentFilter(ONLINE_COMPLETION_CANCEL_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            logWebDav("online completion cancel receiver registered package=${appContext.packageName}")
        }.onFailure {
            onlineCompletionCancelReceiverRegistered.set(false)
            XposedBridge.log("$LOG_PREFIX online completion cancel receiver failed: ${it.stackTraceToString()}")
        }
    }

    private fun isActiveOnlineCompletionCacheDir(target: File): Boolean {
        val activeDirs = onlineCompletionRunningDownloads.values
            .map { it.cacheDir.absoluteFile }
            .toList()
        if (activeDirs.isEmpty()) return false
        val canonicalTarget = runCatching { target.canonicalFile }.getOrDefault(target.absoluteFile)
        return activeDirs.any { active ->
            val canonicalActive = runCatching { active.canonicalFile }.getOrDefault(active.absoluteFile)
            canonicalActive == canonicalTarget || isChildPath(canonicalActive, canonicalTarget)
        }
    }

    private fun canShowWebDavEntry(): Boolean =
        settingsProvider().canRunWebDavCloud

    private fun canShowLocalLibraryEntry(): Boolean =
        settingsProvider().canRunLocalLibraryCloud

    private fun canUseCloudExtendedDisplay(): Boolean =
        settingsProvider().canUseCloudExtendedDisplay

    private fun canCancelCloudDownload(): Boolean =
        settingsProvider().canCancelCloudDownload

    private fun hookOnlineCompletionBookRowDuration() {
        runCatching {
            val bookRowInfo = cls(BOOK_ROW_INFO_CLASS).declaredMethods.first {
                it.name == BOOK_ROW_INFO_METHOD &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[1].name == BOOK_CLASS
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(bookRowInfo, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(1) ?: return
                    if (!isOnlineCompletionLocalBook(book)) return
                    cleanupOnlineCompletionBookPublisherIfNeeded(book)
                    onlineCompletionBookRowInfoDepth.set((onlineCompletionBookRowInfoDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (onlineCompletionBookRowInfoDepth.get() ?: 0) - 1
                    if (next <= 0) onlineCompletionBookRowInfoDepth.remove() else onlineCompletionBookRowInfoDepth.set(next)
                }
            })

            val secondToHours = cls(TIME_EXT_KT_CLASS).declaredMethods.first {
                it.name == SECOND_TO_HOURS_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(secondToHours, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((onlineCompletionBookRowInfoDepth.get() ?: 0) > 0) {
                        param.result = ""
                    }
                }
            })
            logWebDav("online completion book row duration hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook online completion book row duration: ${it.stackTraceToString()}")
        }
    }

    private fun hookOnlineCompletionBookLocalSheet() {
        runCatching {
            val sheetClass = cls(BOOK_LOCAL_SHEET_CLASS)
            sheetClass.declaredMethods
                .filter { method ->
                    (method.name == BOOK_LOCAL_SHEET_METHOD && method.parameterTypes.size == 5) ||
                        (method.name == BOOK_LOCAL_SHEET_CONTENT_METHOD &&
                            method.parameterTypes.firstOrNull()?.name == BOOK_CLASS)
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            onlineCompletionLocalSheetDepth.set((onlineCompletionLocalSheetDepth.get() ?: 0) + 1)
                            onlineCompletionLocalSheetBook.set(param.args?.getOrNull(0))
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val nextDepth = ((onlineCompletionLocalSheetDepth.get() ?: 0) - 1).coerceAtLeast(0)
                            onlineCompletionLocalSheetDepth.set(nextDepth)
                            if (nextDepth == 0) onlineCompletionLocalSheetBook.remove()
                        }
                    })
                }

            val fileBackupMethods = sheetClass.declaredMethods.filter {
                it.name == FILE_BACKUP_METHOD && it.parameterTypes.size == 5
            }
            fileBackupMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (onlineCompletionUpdateRowInjecting.get() == true) return
                        val book = onlineCompletionLocalSheetBook.get() ?: return
                        val info = onlineImportedBookSourceInfo(book) ?: return
                        val composer = param.args?.getOrNull(3) ?: return
                        onlineCompletionUpdateRowInjecting.set(true)
                        runCatching {
                            renderOnlineCompletionUpdateDivider(composer)
                            renderOnlineCompletionUpdateRow(book, info, composer)
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX online completion update row render failed: ${it.stackTraceToString()}")
                        }
                        onlineCompletionUpdateRowInjecting.remove()
                    }
                })
            }
            logWebDav("online completion local sheet hook installed backups=${fileBackupMethods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook online completion local sheet: ${it.stackTraceToString()}")
        }
    }

    private fun renderOnlineCompletionUpdateRow(book: Any, info: OnlineImportedBookSourceInfo, composer: Any) {
        val modifier = paddingModifier(
            clickableModifier(modifierInstance(), "OnlineCompletionChapterUpdate") {
                startOnlineCompletionChapterUpdate(book, info)
            },
            start = 18,
            top = 16,
            end = 12,
            bottom = 16,
        )
        val content = functionProxy("OnlineCompletionUpdateRowContent", FUNCTION3_CLASS) { args ->
            val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
            onlineCompletionUpdateImageVector()?.let { image ->
                renderOnlineCompletionIcon(
                    image = image,
                    modifier = sizeModifier(
                        paddingModifier(
                            modifierInstance(),
                            start = 0,
                            top = 0,
                            end = 16,
                            bottom = 0,
                        ),
                        20,
                    ),
                    tint = colorScheme(innerComposer).longMethod("getOnBackground"),
                    composer = innerComposer,
                )
            }
            renderOnlineCompletionPrimaryText(
                text = "数据更新",
                modifier = rowWeightModifier(rowScope, modifierInstance()),
                composer = innerComposer,
            )
            renderOnlineCompletionSecondaryText(
                text = info.sourceName.ifBlank { "未知源" },
                composer = innerComposer,
            )
            navigateNextImageVector()?.let { image ->
                renderOnlineCompletionIcon(
                    image = image,
                    modifier = paddingModifier(
                        modifierInstance(),
                        start = 0,
                        top = 2,
                        end = 0,
                        bottom = 0,
                    ),
                    tint = colorScheme(innerComposer).longMethod("getSurfaceContainerHighest"),
                    composer = innerComposer,
                )
            }
            targetUnit()
        }
        method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
            null,
            modifier,
            arrangementStart(),
            alignmentCenterVertically(),
            content,
            composer,
            384,
            0,
        )
    }

    private fun renderOnlineCompletionPrimaryText(text: String, modifier: Any?, composer: Any) {
        method(MATERIAL3_TEXT_CLASS, MATERIAL3_TEXT_METHOD, 22).invoke(
            null,
            text,
            modifier,
            colorScheme(composer).longMethod("getOnBackground"),
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            0,
            false,
            0,
            0,
            null,
            typography(composer).method0("getLabelLarge"),
            composer,
            0,
            0,
            TEXT_DEFAULT_MASK_WITH_MODIFIER,
        )
    }

    private fun renderOnlineCompletionSecondaryText(text: String, composer: Any) {
        method(MATERIAL3_TEXT_CLASS, MATERIAL3_TEXT_METHOD, 22).invoke(
            null,
            text,
            null,
            themeOnBackgroundVariant(composer),
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            textOverflowEllipsis(),
            false,
            1,
            0,
            null,
            typography(composer).method0("getBodyMedium"),
            composer,
            0,
            24960,
            TEXT_SECONDARY_SINGLE_LINE_MASK,
        )
    }

    private fun renderOnlineCompletionIcon(image: Any, modifier: Any, tint: Long, composer: Any) {
        iconImageVectorMethod().invoke(
            null,
            image,
            null,
            modifier,
            tint,
            composer,
            48,
            0,
        )
    }

    private fun iconImageVectorMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$ICON_KT_CLASS#$ICON_METHOD/ImageVector") {
                cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
                    it.name == ICON_METHOD &&
                        it.parameterTypes.size == 7 &&
                        it.parameterTypes.firstOrNull()?.name == IMAGE_VECTOR_CLASS
                }?.apply { isAccessible = true }
                    ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
            }
        }

    private fun renderOnlineCompletionUpdateDivider(composer: Any) {
        simpleDividerMethod().invoke(
            null,
            method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
                null,
                modifierInstance(),
                udp(54),
                0f,
                0f,
                0f,
                14,
                null,
            ),
            0L,
            composer,
            0,
            2,
        )
    }

    private fun rowWeightModifier(rowScope: Any, modifier: Any): Any =
        rowScope.javaClass.methods.first {
            it.name == "weight" && it.parameterTypes.size == 3
        }.invoke(rowScope, modifier, 1f, true)

    private fun sizeModifier(baseModifier: Any, size: Int): Any =
        method(SIZE_KT_CLASS, SIZE_METHOD, 2).invoke(null, baseModifier, udp(size))

    private fun paddingModifier(
        baseModifier: Any,
        start: Int,
        top: Int,
        end: Int,
        bottom: Int,
    ): Any =
        method(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
            null,
            baseModifier,
            udp(start),
            udp(top),
            udp(end),
            udp(bottom),
        )

    private fun clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
        method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
            null,
            baseModifier,
            null,
            null,
            false,
            null,
            null,
            functionProxy(name, FUNCTION0_CLASS) {
                onClick()
                targetUnit()
            },
            28,
            null,
        )

    private fun arrangementStart(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getStart")

    private fun alignmentCenterVertically(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenterVertically")

    private fun themeOnBackgroundVariant(composer: Any): Long =
        method(THEME_KT_CLASS, "getOnBackgroundVariant", 1).invoke(null, colorScheme(composer)) as Long

    private fun textOverflowEllipsis(): Int =
        staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getEllipsis") as Int

    private fun onlineCompletionUpdateImageVector(): Any? =
        listOf(
            Triple("androidx.compose.material.icons.filled.RefreshKt", "getRefresh", ICONS_FILLED_CLASS),
            Triple("androidx.compose.material.icons.filled.SyncKt", "getSync", ICONS_FILLED_CLASS),
            Triple(EDIT_ICON_CLASS, "getEdit", ICONS_OUTLINED_CLASS),
        ).firstNotNullOfOrNull { (className, methodName, holderClass) ->
            runCatching {
                method(className, methodName, 1).invoke(null, staticObject(holderClass, "INSTANCE"))
            }.getOrNull()
        }

    private fun navigateNextImageVector(): Any? =
        runCatching {
            method(NAVIGATE_NEXT_ICON_CLASS, "getNavigateNext", 1).invoke(
                null,
                staticObject(ICONS_AUTO_MIRRORED_FILLED_CLASS, "INSTANCE"),
            )
        }.getOrNull()

    private fun colorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
    }

    private fun typography(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
    }

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun simpleDividerMethod(): Method =
        synchronized(methodCache) {
            methodCache.getOrPut("$DIVIDER_KT_CLASS#SimpleDivider/5") {
                cls(DIVIDER_KT_CLASS).declaredMethods.firstOrNull {
                    it.name.contains("SimpleDivider") && it.parameterTypes.size == 5
                }?.apply { isAccessible = true }
                    ?: error("SimpleDivider method not found")
            }
        }

    private fun hookWebDavCleartextPolicy() {
        val hooked = mutableListOf<String>()
        fun hookClass(className: String, loader: ClassLoader? = null) {
            runCatching {
                val policyClass = if (loader == null) Class.forName(className) else XposedHelpers.findClass(className, loader)
                policyClass.declaredMethods.filter {
                    it.name == "isCleartextTrafficPermitted" &&
                        (it.parameterTypes.isEmpty() || (it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java))
                }.forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldAllowCleartext(param.args)) {
                                logCleartextAllowed(param.args?.firstOrNull(), "$className/${method.parameterTypes.size}")
                                param.result = true
                            }
                        }
                    })
                    hooked += "$className/${method.parameterTypes.size}"
                }
            }.onFailure {
                logWebDav("cleartext policy class skipped $className: ${it.message}")
            }
        }
        fun hookCleartextUrlFilter() {
            runCatching {
                val filterClass = Class.forName(ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS)
                filterClass.declaredMethods.filter {
                    it.name == "checkURLPermitted" &&
                        it.parameterTypes.size == 1 &&
                        URL::class.java.isAssignableFrom(it.parameterTypes[0])
                }.forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldAllowCleartext(param.args)) {
                                logCleartextAllowed(param.args?.firstOrNull(), "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/checkURLPermitted")
                                param.result = null
                            }
                        }
                    })
                    hooked += "$ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS/${method.name}"
                }
            }.onFailure {
                logWebDav("cleartext url filter skipped $ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS: ${it.message}")
            }
        }
        hookClass(NETWORK_SECURITY_POLICY_CLASS)
        hookCleartextUrlFilter()
        hookClass(ANDROID_OKHTTP_PLATFORM_CLASS)
        hookClass(OKHTTP_PLATFORM_CLASS, classLoader)
        hookClass(OKHTTP_ANDROID_PLATFORM_CLASS, classLoader)
        hookClass(OKHTTP_ANDROID10_PLATFORM_CLASS, classLoader)
        logWebDav("cleartext policy hook installed: ${hooked.joinToString().ifBlank { "none" }}")
    }

    private fun shouldAllowCleartext(args: Array<Any?>?): Boolean {
        if (webDavCleartextAllowed.get() == true) return true
        val host = cleartextHostOf(args?.firstOrNull())
        return host.isNotBlank() && webDavCleartextAllowedHosts[host] == true
    }

    private fun cleartextHostOf(value: Any?): String =
        when (value) {
            null -> ""
            is URL -> value.host.orEmpty()
            is URI -> value.host.orEmpty()
            is String -> {
                val text = value.trim()
                if (text.isBlank()) {
                    ""
                } else if (text.contains("://")) {
                    runCatching { URI(text).host.orEmpty() }
                        .getOrDefault("")
                        .ifBlank { runCatching { URL(text).host.orEmpty() }.getOrDefault("") }
                } else {
                    text.substringBefore('/').substringBefore(':').trim()
                }
            }
            else -> cleartextHostOf(value.toString())
        }

    private fun logCleartextAllowed(value: Any?, via: String) {
        val host = cleartextHostOf(value).ifBlank { "*" }
        if (webDavCleartextAllowedLoggedHosts.putIfAbsent("$via|$host", true) == null) {
            logWebDav("cleartext allowed via=$via host=$host")
        }
    }

    private fun hookBackupTypeName() {
        runCatching {
            val method = cls(BACKUP_TYPE_CLASS).declaredMethods.first {
                it.name == BACKUP_TYPE_NAME_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (
                        type == BACKUP_TYPE_WEBDAV ||
                        type == BACKUP_TYPE_LOCAL_LIBRARY ||
                        isOnlineCompletionRenderType(type) ||
                        ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                        ((webDavAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_BAIDU) ||
                        ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                        ((webDavBackupCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115) ||
                        ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                    ) {
                        param.result = if (
                            ((onlineCompletionCloudTitleDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_WEBDAV) ||
                                isOnlineCompletionRenderType(type)
                        ) {
                            onlineCompletionTitleForType(type)
                        } else if (
                            type == BACKUP_TYPE_LOCAL_LIBRARY ||
                            ((localLibraryAccountScreenDepth.get() ?: 0) > 0 && type == BACKUP_TYPE_YUN115)
                        ) {
                            LOCAL_LIBRARY_TITLE
                        } else {
                            WEBDAV_TITLE
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val types = syncAvailableTypes.get() ?: return
                    val current = syncAvailableRowIndex.get() ?: 0
                    if (current < types.size) {
                        syncAvailableRowIndex.set(current + 1)
                    }
                }
            })
            logWebDav("backup type name hook installed")
        }.onFailure {
            logWebDav("failed to hook WebDAV backup type name: ${it.stackTraceToString()}")
        }
    }

    private fun hookHomeCloudResultListRenderContext() {
        runCatching {
            val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
                it.name == HOME_CLOUD_RESULT_LIST_METHOD && it.parameterTypes.size == 6
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    if (!isOnlineCompletionRenderType(type)) return
                    pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type))
                    pushWebDavIcon()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    if (!isOnlineCompletionRenderType(type)) return
                    popWebDavIcon()
                    popOnlineCompletionCloudTitle()
                }
            })
            logWebDav("home cloud result render context hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook home cloud result render context: ${it.stackTraceToString()}")
        }
    }

    private fun hookOnlineCompletionHomeCloudBookRow() {
        runCatching {
            val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
                it.name == HOME_CLOUD_BOOK_ROW_METHOD && it.parameterTypes.size == 7
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(1) ?: return
                    val path = cloudPathOf(book)
                    val target = onlineCompletionSearchTargets[path] ?: return
                    val composer = param.args?.getOrNull(4) ?: return
                    renderOnlineCompletionCloudBookSearchRow(book, target, composer)
                    param.result = targetUnit()
                }
            })
            logWebDav("online completion home CloudBookRow hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook online completion home CloudBookRow: ${it.stackTraceToString()}")
        }
    }

    private fun renderOnlineCompletionCloudBookSearchRow(book: Any, target: OnlineDownloadTarget, composer: Any) {
        val factory = functionProxy("OnlineCompletionSearchRowAndroidView", FUNCTION1_CLASS) { args ->
            val context = args?.getOrNull(0) as? Context
                ?: activityProvider()
                ?: error("No context for online completion search row")
            createOnlineCompletionSearchRowView(context, book, target)
        }
        runCatching {
            cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
                it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
            }.apply { isAccessible = true }.invoke(
                null,
                factory,
                fillMaxWidthModifier(),
                null,
                composer,
                0,
                4,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render online completion search row: ${it.stackTraceToString()}")
        }
    }

    private fun createOnlineCompletionSearchRowView(
        context: Context,
        book: Any,
        target: OnlineDownloadTarget,
    ): View {
        val primary = context.resolveThemeColor(android.R.attr.textColorPrimary, Color.rgb(34, 34, 34))
        val secondary = context.resolveThemeColor(android.R.attr.textColorSecondary, Color.rgb(102, 102, 102))
        val tertiary = Color.rgb(132, 132, 132)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(92)
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(12))
            setOnClickListener { handleOnlineCompletionSearchTap(book) }
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedDrawable(Color.rgb(232, 232, 232), context.dp(4).toFloat())
        }
        row.addView(cover, LinearLayout.LayoutParams(context.dp(48), context.dp(66)).apply {
            rightMargin = context.dp(14)
        })
        loadOnlineCompletionSearchCover(cover, target.result.coverUrl)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(TextView(context).apply {
            text = target.result.name.ifBlank { "未命名" }
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        texts.addView(TextView(context).apply {
            text = target.result.author.ifBlank { "未知作者" }
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, context.dp(5), 0, 0)
        })
        texts.addView(TextView(context).apply {
            text = onlineCompletionSearchMetaLine(target.result)
            setTextColor(tertiary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, context.dp(5), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun loadOnlineCompletionSearchCover(imageView: ImageView, coverUrl: String) {
        val url = coverUrl.trim()
        if (url.isBlank()) return
        Thread({
            runCatching {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 4_000
                    readTimeout = 6_000
                }
                connection.getInputStream().use { input -> BitmapFactory.decodeStream(input) }
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    Handler(Looper.getMainLooper()).post {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }.onFailure {
                logWebDav("online completion search cover failed url=${url.take(120)} error=${it.message.orEmpty()}")
            }
        }, "ReaMicroOnlineSearchCover").start()
    }

    private fun hookWebDavRowIcon() {
        runCatching {
            val method = cls(BAIDU_ICON_CLASS).declaredMethods.first {
                it.name == BAIDU_ICON_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val syncTypes = syncAvailableTypes.get()
                    val syncIndex = syncAvailableRowIndex.get() ?: 0
                    val isSyncWebDavRow = syncTypes?.getOrNull(syncIndex) == BACKUP_TYPE_WEBDAV
                    if ((localLibraryIconDepth.get() ?: 0) > 0) {
                        getNativeAndroidOsVector()?.let { param.result = it }
                        return
                    }
                    if ((webDavIconDepth.get() ?: 0) <= 0 && !isSyncWebDavRow) return
                    getComposeWebDavVector()?.let { param.result = it }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV row icon hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV row icon: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavYun115Icon() {
        runCatching {
            val method = cls(YUN115_ICON_CLASS).declaredMethods.first {
                it.name == YUN115_ICON_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (
                        (webDavIconDepth.get() ?: 0) <= 0 &&
                        (onlineCompletionCloudTitleDepth.get() ?: 0) <= 0 &&
                        (localLibraryIconDepth.get() ?: 0) <= 0 &&
                        (webDavBackupCardDepth.get() ?: 0) <= 0 &&
                        (webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0
                    ) return
                    if ((localLibraryIconDepth.get() ?: 0) > 0) {
                        getNativeAndroidOsVector()?.let { param.result = it }
                        return
                    }
                    getComposeWebDavVector()?.let { param.result = it }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV 115 icon replacement hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV 115 icon replacement: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavFileFolderIcon() {
        runCatching {
            val method = cls(FILE_FOLDER_ICON_CLASS).declaredMethods.first {
                it.name == FILE_FOLDER_ICON_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((localLibraryCloudScreenDepth.get() ?: 0) > 0) {
                        getNativeAndroidOsVector()?.let { param.result = it }
                        return
                    }
                    if ((webDavCloudScreenDepth.get() ?: 0) <= 0) return
                    getComposeWebDavVector()?.let { param.result = it }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV folder icon hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV folder icon: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavCloudTreeIcon() {
        runCatching {
            val cloudTreeMethod = cls(CLOUD_TREE_CLASS).declaredMethods.first {
                it.name == CLOUD_TREE_METHOD &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes[0] == java.lang.Integer.TYPE
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(cloudTreeMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> webDavCloudTreeDepth.set((webDavCloudTreeDepth.get() ?: 0) + 1)
                        BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudTreeDepth.set((localLibraryCloudTreeDepth.get() ?: 0) + 1)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> {
                            val next = (webDavCloudTreeDepth.get() ?: 0) - 1
                            if (next <= 0) webDavCloudTreeDepth.remove() else webDavCloudTreeDepth.set(next)
                        }
                        BACKUP_TYPE_LOCAL_LIBRARY -> {
                            val next = (localLibraryCloudTreeDepth.get() ?: 0) - 1
                            if (next <= 0) localLibraryCloudTreeDepth.remove() else localLibraryCloudTreeDepth.set(next)
                        }
                    }
                }
            })

            val androidOsMethod = cls(ANDROID_OS_ICON_CLASS).declaredMethods.first {
                it.name == ANDROID_OS_ICON_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(androidOsMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((localLibraryCloudTreeDepth.get() ?: 0) > 0) return
                    if ((webDavCloudTreeDepth.get() ?: 0) <= 0) return
                    getComposeWebDavVector()?.let { param.result = it }
                }
            })
            logWebDav("cloud tree icon hook installed")
        }.onFailure {
            logWebDav("failed to hook cloud tree icon: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportCloudRowLabels() {
        listOf(CLOUD_UNAUTH_LABEL_METHOD, CLOUD_AUTHORIZED_LABEL_METHOD).forEach { labelMethod ->
            runCatching {
                val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                    it.name == labelMethod &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == String::class.java
                }.apply { isAccessible = true }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((localLibraryIconDepth.get() ?: 0) > 0) {
                            param.args?.set(0, LOCAL_LIBRARY_TITLE)
                            return
                        }
                        if ((webDavIconDepth.get() ?: 0) <= 0) return
                        param.args?.set(0, WEBDAV_TITLE)
                    }
                })
                XposedBridge.log("$LOG_PREFIX WebDAV import row label hook installed: $labelMethod")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import row label $labelMethod: ${it.stackTraceToString()}")
            }
        }
    }

    private fun hookSyncAuthCard() {
        runCatching {
            val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == AUTH_CARD_METHOD && it.parameterTypes.size == 6
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    syncAuthCardRender.set(
                        SyncAuthCardRenderContext(
                            bookId = (param.args?.getOrNull(0) as? Number)?.toLong(),
                            onSetupDefaultDir = param.args?.getOrNull(2),
                            onPick = param.args?.getOrNull(3),
                        ),
                    )
                    syncWebDavAuthRenderedBeforeAvailable.remove()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    syncAuthCardRender.remove()
                    syncWebDavAuthRenderedBeforeAvailable.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV sync AuthCard hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync AuthCard: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportCloudRowDetail() {
        runCatching {
            val detail = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == CLOUD_AUTHORIZED_DETAIL_METHOD &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(detail, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((localLibraryIconDepth.get() ?: 0) <= 0) return
                    param.args?.set(0, LOCAL_LIBRARY_BROWSE_TEXT)
                    param.args?.set(1, "")
                    localLibraryAuthorizedDetailDepth.set((localLibraryAuthorizedDetailDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (localLibraryAuthorizedDetailDepth.get() ?: 0) - 1
                    if (next <= 0) localLibraryAuthorizedDetailDepth.remove() else localLibraryAuthorizedDetailDepth.set(next)
                }
            })

            cls(MATERIAL3_TEXT_CLASS).declaredMethods.filter {
                it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java
            }.forEach { textMethod ->
                textMethod.isAccessible = true
                XposedBridge.hookMethod(textMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyCloudBookExtendedDisplayText(param)
                        if ((localLibraryDefaultFolderDepth.get() ?: 0) > 0 &&
                            param.args?.getOrNull(0)?.toString().isNullOrEmpty()
                        ) {
                            param.result = null
                            return
                        }
                        if ((localLibraryAuthorizedDetailDepth.get() ?: 0) > 0 &&
                            param.args?.getOrNull(0)?.toString() == "$LOCAL_LIBRARY_BROWSE_TEXT / "
                        ) {
                            param.args?.set(0, LOCAL_LIBRARY_BROWSE_TEXT)
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX local library import row detail hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local library import row detail: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudBookRowExtendedDisplay() {
        runCatching {
            val method = cls(CLOUD_BOOK_LIST_CLASS).declaredMethods.first {
                it.name == CLOUD_BOOK_ROW_METHOD &&
                    it.parameterTypes.size == 7 &&
                    it.parameterTypes[1].name == CLOUD_BOOK_CLASS
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!canUseCloudExtendedDisplay()) return
                    val book = param.args?.getOrNull(1) ?: return
                    if (isOnlineCompletionPath(cloudPathOf(book))) return
                    val updatedAt = book.callLong("getUpdatedAt")
                    if (updatedAt <= 0L) return
                    cloudBookRowExtendedDisplay.set(
                        CloudBookRowExtendedDisplayContext(
                            updatedAt = updatedAt,
                            extensionLabel = cloudBookExtensionLabel(book.callString("getExtension")),
                        ),
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    cloudBookRowExtendedDisplay.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX cloud book row extended display hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook cloud book row extended display: ${it.stackTraceToString()}")
        }
    }

    private fun applyCloudBookExtendedDisplayText(param: XC_MethodHook.MethodHookParam) {
        val context = cloudBookRowExtendedDisplay.get() ?: return
        val args = param.args ?: return
        context.textIndex += 1
        val text = args.getOrNull(0)?.toString().orEmpty()
        if (context.textIndex == 2 && text == context.extensionLabel) {
            context.extensionSuppressed = true
            param.result = null
            return
        }
        if (context.textIndex != 3) return
        if (!context.extensionSuppressed) return
        if (text.isBlank() || text.contains('\n')) return
        val time = formatCloudUpdatedTime(context.updatedAt)
        if (time.isBlank()) return
        args[0] = "${context.extensionLabel}  $text\n$time"
        if (args.size > 14 && args[14] is Number) {
            args[14] = 2
        }
    }

    private fun hookLocalLibraryFolderPickerResult() {
        runCatching {
            Activity::class.java.declaredMethods.filter {
                it.name == "onActivityResult" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == java.lang.Integer.TYPE &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE &&
                    it.parameterTypes[2] == Intent::class.java
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                        if (requestCode != REQUEST_LOCAL_LIBRARY_DIR) return
                        val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                        val data = param.args?.getOrNull(2) as? Intent
                        val uri = data?.data
                        if (resultCode == Activity.RESULT_OK && uri != null) {
                            val activity = param.thisObject as? Activity ?: activityProvider()
                            val flags = data.flags and (
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            runCatching {
                                activity?.contentResolver?.takePersistableUriPermission(
                                    uri,
                                    flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                                )
                            }
                            addLocalLibraryFolder(uri)
                            activity?.toast("已添加书库文件夹")
                        }
                        param.result = null
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX local library folder picker hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local library folder picker: ${it.stackTraceToString()}")
        }
    }

    private fun hookSyncAuthCardContent() {
        runCatching {
            val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == AUTH_CARD_CONTENT_METHOD && it.parameterTypes.size == 11
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val hasOuterContext = syncAuthCardRender.get() != null
                    syncAuthCardContentOwnsContext.set(!hasOuterContext)
                    if (!hasOuterContext) {
                        syncAuthCardRender.set(
                            SyncAuthCardRenderContext(
                                onSetupDefaultDir = args.getOrNull(6),
                                onPick = args.getOrNull(5),
                            ),
                        )
                        syncWebDavAuthRenderedBeforeAvailable.remove()
                    }
                    val backupType = (args.getOrNull(0) as? Number)?.toInt()
                    val isRunning = args.getOrNull(8) as? Boolean == true
                    val isWebDavBookRunning = syncAuthCardRender.get()?.bookId
                        ?.let { webDavRunningBackupBookIds.containsKey(it) } == true
                    val isWebDavRunning = isRunning && (
                        (backupType == BACKUP_TYPE_YUN115 && (webDavRunningBackupPending.get() == true || isWebDavBookRunning)) ||
                            backupType == BACKUP_TYPE_WEBDAV
                        )
                    if (isWebDavRunning) {
                        args[0] = BACKUP_TYPE_YUN115
                        webDavRunningBackupPending.set(true)
                        webDavRunningSyncAuthCardDepth.set((webDavRunningSyncAuthCardDepth.get() ?: 0) + 1)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0) {
                        val next = (webDavRunningSyncAuthCardDepth.get() ?: 0) - 1
                        if (next <= 0) {
                            webDavRunningSyncAuthCardDepth.remove()
                        } else {
                            webDavRunningSyncAuthCardDepth.set(next)
                        }
                    }
                    webDavRunningBackupPending.remove()
                    if (syncAuthCardContentOwnsContext.get() == true) {
                        syncAuthCardRender.remove()
                        syncWebDavAuthRenderedBeforeAvailable.remove()
                    }
                    syncAuthCardContentOwnsContext.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV sync AuthCard content hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync AuthCard content: ${it.stackTraceToString()}")
        }
    }

    private fun hookDriveAuthCard() {
        runCatching {
            val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == DRIVE_AUTH_CARD_METHOD && it.parameterTypes.size == 8
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0) return
                    val args = param.args ?: return
                    val backupDir = currentWebDavBackupDir()
                    getComposeWebDavVector()?.let { args[0] = it }
                    args[1] = "上传到 $WEBDAV_TITLE"
                    args[2] = webDavDisplayDir(backupDir)
                    args[3] = true
                    val context = syncAuthCardRender.get()
                    args[4] = functionProxy("WebDavRunningDefaultDir", FUNCTION0_CLASS) {
                        if (backupDir.isBlank()) {
                            context?.onSetupDefaultDir?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                        } else {
                            context?.onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, backupDir) }
                        }
                        targetUnit()
                    }
                    args[5] = functionProxy("WebDavRunningPickDir", FUNCTION0_CLASS) {
                        context?.onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, null) }
                        targetUnit()
                    }
                    logWebDav("sync running DriveAuthCard mapped dir=$backupDir")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (webDavRenderingSyncAuthCard.get() == true) return
                    if (param.args?.getOrNull(3) as? Boolean == true) return
                    val context = syncAuthCardRender.get() ?: return
                    context.nativeAuthRowsSeen += 1
                    if (context.bookId?.let { webDavRunningBackupBookIds.containsKey(it) } == true) return
                    if (context.webDavRendered || !canShowWebDavEntry() || !hasWebDavLogin(currentContext())) return
                    val composer = param.args?.getOrNull(6) ?: return
                    context.webDavRendered = renderSyncWebDavAuthorizedCard(
                        composer = composer,
                        onSetupDefaultDir = context.onSetupDefaultDir,
                        onPick = context.onPick,
                    )
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV sync auth drive card hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync auth drive card: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavSyncUploadRunningCard() {
        runCatching {
            val getBackupType = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == "getBackupType" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(getBackupType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val snapshot = param.args?.getOrNull(0) ?: return
                    val kind = snapshot.callString("getKind")
                    if (kind == "backup:$BACKUP_TYPE_WEBDAV") {
                        webDavRunningBackupPending.set(true)
                        param.result = BACKUP_TYPE_YUN115
                        logWebDav("sync running backup type mapped kind=$kind")
                    }
                }
            })

            val yun115StateValue = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == "AuthCard\$lambda\$4" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(yun115StateValue, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0) return
                    param.result = webDavYun115Auth()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV sync running upload card hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync running upload card: ${it.stackTraceToString()}")
        }
    }

    private fun hookDriveOtherAvailableCard() {
        runCatching {
            val method = cls(AUTH_CARD_CLASS).declaredMethods.first {
                it.name == DRIVE_OTHER_AVAILABLE_CARD_METHOD && it.parameterTypes.size == 4
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val context = syncAuthCardRender.get()
                    if (
                        hasWebDavLogin(currentContext()) &&
                        canShowWebDavEntry() &&
                        context != null &&
                        !context.webDavRendered &&
                        context.nativeAuthRowsSeen == 0 &&
                        context.bookId?.let { webDavRunningBackupBookIds.containsKey(it) } != true &&
                        syncWebDavAuthRenderedBeforeAvailable.get() != true &&
                        (webDavIconDepth.get() ?: 0) <= 0
                    ) {
                        val rendered = renderSyncWebDavAuthorizedCard(
                            composer = args.getOrNull(2) ?: return,
                            onSetupDefaultDir = context.onSetupDefaultDir,
                            onPick = context.onPick,
                        )
                        if (rendered) {
                            context.webDavRendered = true
                            syncWebDavAuthRenderedBeforeAvailable.set(true)
                        }
                    }
                    val types = (args.getOrNull(0) as? Iterable<*>)?.mapNotNull {
                        (it as? Number)?.toInt()
                    }.orEmpty()
                    if ((webDavIconDepth.get() ?: 0) <= 0 && types.isNotEmpty()) {
                        val nativeTypes = types.filter { it != BACKUP_TYPE_WEBDAV }
                        val renderTypes = if (hasWebDavLogin(currentContext()) || !canShowWebDavEntry()) {
                            nativeTypes
                        } else {
                            nativeTypes + BACKUP_TYPE_WEBDAV
                        }
                        args[0] = renderTypes
                        syncAvailableTypes.set(renderTypes)
                        syncAvailableRowIndex.set(0)
                        if (BACKUP_TYPE_WEBDAV in renderTypes) {
                            syncAuthCardRender.get()?.webDavRendered = true
                        }
                    }
                    args[1]?.let { args[1] = wrapAuthorizeCallback(it) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((webDavIconDepth.get() ?: 0) <= 0) {
                        syncAvailableTypes.remove()
                        syncAvailableRowIndex.remove()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV sync available drive hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV sync available drive: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportAuthorizedList() {
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == BOOK_LIBRARY_AUTH_LIST_METHOD && it.parameterTypes.size == 9
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val rows = (args.getOrNull(1) as? Iterable<*>)?.toList().orEmpty()
                    val rowTypes = rows.mapNotNull { row ->
                        runCatching {
                            val first = row?.javaClass?.methods?.firstOrNull {
                                it.name == "component1" && it.parameterTypes.isEmpty()
                            }?.apply { isAccessible = true }?.invoke(row) as? Number
                            first?.toInt()
                        }.getOrNull()
                    }
                    val additions = mutableListOf<Any>()
                    if (canShowWebDavEntry() && hasWebDavLogin(currentContext()) && BACKUP_TYPE_WEBDAV !in rowTypes) {
                        additions.add(newKotlinPair(BACKUP_TYPE_WEBDAV, webDavAuth()))
                    }
                    if (additions.isNotEmpty()) {
                        args[1] = rows + additions
                    }
                    if (canShowLocalLibraryEntry()) {
                        importLocalLibraryRow.set(
                            ImportLocalLibraryRowContext(
                                navGraphScope = args.getOrNull(2),
                                coroutineScope = args.getOrNull(3),
                                sheetState = args.getOrNull(4),
                                intentReceiver = args.getOrNull(5),
                            ),
                        )
                    } else {
                        importLocalLibraryRow.remove()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    importLocalLibraryRow.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV import authorized list hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import authorized list: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportLocalLibraryRow() {
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == BOOK_LIBRARY_LOCAL_METHOD && it.parameterTypes.size == 3
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val composer = param.args?.getOrNull(1) ?: return
                    if (!canShowLocalLibraryEntry()) return
                    renderImportLocalLibraryRow(composer)
                }
            })
            XposedBridge.log("$LOG_PREFIX local library import row hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local library import row: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportUnauthList() {
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == BOOK_LIBRARY_UNAUTH_LIST_METHOD && it.parameterTypes.size == 8
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val types = (args.getOrNull(0) as? Iterable<*>)?.mapNotNull {
                        (it as? Number)?.toInt()
                    }.orEmpty()
                    val loggedIn = hasWebDavLogin(currentContext())
                    val webDavInNativeList = BACKUP_TYPE_WEBDAV in types
                    val shouldAppendWebDav = canShowWebDavEntry() && !loggedIn && !webDavInNativeList && types.isNotEmpty()
                    val renderTypes = if (loggedIn) {
                        types.filter {
                            (it != BACKUP_TYPE_WEBDAV || canShowWebDavEntry()) &&
                                (it != BACKUP_TYPE_LOCAL_LIBRARY || canShowLocalLibraryEntry())
                        }
                    } else {
                        types.filter {
                            (it != BACKUP_TYPE_WEBDAV || canShowWebDavEntry()) &&
                                (it != BACKUP_TYPE_LOCAL_LIBRARY || canShowLocalLibraryEntry())
                        }
                    }
                    if (shouldAppendWebDav) {
                        args[0] = renderTypes + BACKUP_TYPE_WEBDAV
                    } else if (renderTypes.size != types.size) {
                        args[0] = renderTypes
                    }
                    importUnauthRender.set(
                        ImportUnauthRenderContext(
                            expectedNativeRows = renderTypes.count { it != BACKUP_TYPE_WEBDAV },
                            webDavRendered = loggedIn || webDavInNativeList || shouldAppendWebDav,
                        ),
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    importUnauthRender.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV import unauth list hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import unauth list: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportCloudAuthorizedRow() {
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == CLOUD_AUTHORIZED_ROW_METHOD && it.parameterTypes.size == 5
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                        BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> popWebDavIcon()
                        BACKUP_TYPE_LOCAL_LIBRARY -> popLocalLibraryIcon()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV import authorized row hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import authorized row: ${it.stackTraceToString()}")
        }
    }

    private fun hookImportCloudUnauthRow() {
        runCatching {
            val method = cls(BOOK_LIBRARY_SHEET_CLASS).declaredMethods.first {
                it.name == CLOUD_UNAUTH_ROW_METHOD && it.parameterTypes.size == 4
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> pushWebDavIcon()
                        BACKUP_TYPE_LOCAL_LIBRARY -> pushLocalLibraryIcon()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val type = (args.getOrNull(0) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> {
                            popWebDavIcon()
                            return
                        }
                        BACKUP_TYPE_LOCAL_LIBRARY -> {
                            popLocalLibraryIcon()
                            return
                        }
                    }
                    val context = importUnauthRender.get() ?: return
                    context.renderedNativeRows += 1
                    if (!context.webDavRendered && context.renderedNativeRows >= max(context.expectedNativeRows, 1)) {
                        context.webDavRendered = true
                        val composer = args.getOrNull(2) ?: return
                        renderImportWebDavRow(composer)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV import cloud row hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import cloud row: ${it.stackTraceToString()}")
        }
    }

    private fun renderSyncWebDavAvailableCard(composer: Any, onAuthorize: Any?) {
        runCatching {
            withWebDavIcon {
                method(AUTH_CARD_CLASS, DRIVE_OTHER_AVAILABLE_CARD_METHOD, 4).invoke(
                    null,
                    listOf(BACKUP_TYPE_WEBDAV),
                    functionProxy("WebDavOnlyAuthorize", FUNCTION1_CLASS) {
                        onAuthorize?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                        targetUnit()
                    },
                    composer,
                    0,
                )
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV sync available card: ${it.stackTraceToString()}")
        }
    }

    private fun renderSyncWebDavAuthorizedCard(
        composer: Any,
        onSetupDefaultDir: Any?,
        onPick: Any?,
        isRunning: Boolean = false,
    ): Boolean {
        return runCatching {
            val icon = getComposeWebDavVector() ?: return@runCatching false
            val backupDir = currentWebDavBackupDir()
            webDavRenderingSyncAuthCard.set(true)
            try {
                method(AUTH_CARD_CLASS, DRIVE_AUTH_CARD_METHOD, 8).invoke(
                    null,
                    icon,
                    "上传到 $WEBDAV_TITLE",
                    webDavDisplayDir(backupDir),
                    isRunning,
                    functionProxy("WebDavSyncDefaultDir", FUNCTION0_CLASS) {
                        if (backupDir.isBlank()) {
                            logWebDav("sync default click setup dir")
                            onSetupDefaultDir?.let { invokeFunction1(it, BACKUP_TYPE_WEBDAV) }
                        } else {
                            logWebDav("sync default click upload dir=$backupDir")
                            onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, backupDir) }
                        }
                        targetUnit()
                    },
                    functionProxy("WebDavSyncPickDir", FUNCTION0_CLASS) {
                        logWebDav("sync pick click")
                        onPick?.let { invokeFunction2(it, BACKUP_TYPE_WEBDAV, null) }
                        targetUnit()
                    },
                    composer,
                    0,
                )
                logWebDav("sync auth card rendered dir=$backupDir")
                true
            } finally {
                webDavRenderingSyncAuthCard.remove()
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV sync auth card: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun renderImportWebDavRow(composer: Any) {
        runCatching {
            withWebDavIcon {
                method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_UNAUTH_ROW_METHOD, 4).invoke(
                    null,
                    BACKUP_TYPE_WEBDAV,
                    webDavImportClickCallback(),
                    composer,
                    0,
                )
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV import row: ${it.stackTraceToString()}")
        }
    }

    private fun renderImportLocalLibraryRow(composer: Any) {
        val context = importLocalLibraryRow.get()
        runCatching {
            renderImportLocalLibraryDivider(composer)
            pushLocalLibraryIcon()
            try {
                method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_AUTHORIZED_ROW_METHOD, 5).invoke(
                    null,
                    BACKUP_TYPE_LOCAL_LIBRARY,
                    localLibraryAuth(),
                    localLibraryImportClickCallback(context),
                    composer,
                    0,
                )
            } finally {
                popLocalLibraryIcon()
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local library import row: ${it.stackTraceToString()}")
        }
    }

    private fun renderImportLocalLibraryDivider(composer: Any) {
        runCatching {
            method(DIVIDER_KT_CLASS, SIMPLE_DIVIDER_METHOD, 5).invoke(
                null,
                startPaddingModifier(56),
                0L,
                composer,
                0,
                2,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local library import divider: ${it.stackTraceToString()}")
        }
    }

    private fun wrapAuthorizeCallback(original: Any): Any =
        functionProxy("WebDavAuthorize", FUNCTION1_CLASS) { args ->
            invokeFunction1(original, args?.getOrNull(0))
            targetUnit()
        }

    private fun webDavImportClickCallback(): Any =
        functionProxy("WebDavImportLogin", FUNCTION0_CLASS) {
            showWebDavLoginPage()
            targetUnit()
        }

    private fun localLibraryImportClickCallback(context: ImportLocalLibraryRowContext?): Any =
        functionProxy("LocalLibraryImportBrowse", FUNCTION0_CLASS) {
            runCatching {
                val navGraphScope = context?.navGraphScope ?: return@runCatching
                val coroutineScope = context.coroutineScope ?: return@runCatching
                val sheetState = context.sheetState ?: return@runCatching
                val intentReceiver = context.intentReceiver ?: return@runCatching
                method(BOOK_LIBRARY_SHEET_CLASS, BOOK_LIBRARY_AUTH_ROW_CLICK_METHOD, 5).invoke(
                    null,
                    navGraphScope,
                    BACKUP_TYPE_LOCAL_LIBRARY,
                    coroutineScope,
                    sheetState,
                    intentReceiver,
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open local library import row: ${it.stackTraceToString()}")
                context?.navGraphScope?.let { scope -> navigateStorage(scope, BACKUP_TYPE_LOCAL_LIBRARY) }
            }
            targetUnit()
        }

    private fun hookWebDavStorageNavigate() {
        runCatching {
            val method = cls(NAV_GRAPH_SCOPE_CLASS).declaredMethods.first {
                it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = param.args?.getOrNull(0) ?: return
                    if (route.javaClass.name != ROUTE_STORAGE_CLASS) return
                    val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                        ?.invoke(route) as? Number ?: return
                    if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) return
                    if (type.toInt() != BACKUP_TYPE_WEBDAV && type.toInt() != BACKUP_TYPE_LOCAL_LIBRARY) return
                    if (hasWebDavLogin(currentContext())) return
                    param.args?.set(0, newThirdLoginRoute())
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV storage navigate hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV storage navigate: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudStorageWebDavData() {
        runCatching {
            val repositoryClass = cls(CLOUD_STORAGE_REPOSITORY_CLASS)
            repositoryClass.declaredMethods.filter {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Integer.TYPE &&
                    it.name in setOf(CLOUD_STORAGE_GET_AUTH, CLOUD_STORAGE_GET_USER_INFO, CLOUD_STORAGE_GET_LIBRARY)
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                        if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                        rememberCloudStorageRepository(param.thisObject)
                        param.result = when (method.name) {
                            CLOUD_STORAGE_GET_AUTH -> flowOf(if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryAuth() else webDavAuth())
                            CLOUD_STORAGE_GET_USER_INFO -> flowOf(
                                if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryCloudUserInfo() else webDavCloudUserInfo(),
                            )
                            CLOUD_STORAGE_GET_LIBRARY -> if (type == BACKUP_TYPE_LOCAL_LIBRARY) localLibraryFlow() else webDavLibraryFlow()
                            else -> null
                        }
                    }
                })
            }
            repositoryClass.declaredMethods.filter {
                it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == java.lang.Integer.TYPE &&
                    (
                        it.name.contains("openFolder") ||
                            it.name.contains("getFolderList") ||
                            it.name.contains("createFolder") ||
                            it.name.contains("move") ||
                            it.name.contains("rename") ||
                            it.name.contains("delete") ||
                            it.name.contains("getInfo") ||
                            it.name.contains("search") ||
                            it.name.contains("setupBackupDir")
                    )
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                        if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                        rememberCloudStorageRepository(param.thisObject)
                        param.result = webDavResult {
                            if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                                when {
                                    method.name.contains("openFolder") -> {
                                        val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                        saveLocalLibraryBrowseDir(path)
                                        updateLocalLibraryStorageTrees(path)
                                        refreshLocalLibrary(path)
                                        targetUnit()
                                    }
                                    method.name.contains("getFolderList") -> {
                                        val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                        listLocalLibrary(path)
                                            .filter { it.isDirectory }
                                            .map { newLocalLibraryCloudFolder(it) }
                                    }
                                    method.name.contains("createFolder") -> {
                                        val parentPath = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                        val name = param.args?.getOrNull(2)?.toString().orEmpty()
                                        createLocalLibraryFolder(parentPath, name)
                                        refreshLocalLibrary(parentPath)
                                        targetUnit()
                                    }
                                    method.name.contains("move") -> {
                                        val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                        val dest = localLibraryPathArg(param.args?.getOrNull(2)?.toString().orEmpty())
                                        moveLocalLibraryEntry(path, dest)
                                        refreshLocalLibrary(dest)
                                        targetUnit()
                                    }
                                    method.name.contains("rename") -> {
                                        val book = param.args?.getOrNull(1)
                                        val path = localLibraryPathArg(cloudPathOf(book))
                                        val newName = param.args?.getOrNull(2)?.toString().orEmpty()
                                        renameLocalLibraryEntry(path, newName)
                                        refreshLocalLibrary(parentLocalLibraryPath(path))
                                        targetUnit()
                                    }
                                    method.name.contains("delete") -> {
                                        val target = param.args?.getOrNull(1)
                                        val path = localLibraryPathArg(cloudPathOf(target).ifBlank { target?.toString().orEmpty() })
                                        deleteLocalLibraryEntry(path)
                                        refreshLocalLibrary(parentLocalLibraryPath(path))
                                        targetUnit()
                                    }
                                    method.name.contains("getInfo") -> {
                                        val path = localLibraryPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                        val entry = localLibraryEntry(path) ?: syntheticLocalLibraryBookEntry(path)
                                        newLocalLibraryCloudBook(entry)
                                    }
                                    method.name.contains("search") -> {
                                        val query = param.args?.getOrNull(1)?.toString().orEmpty()
                                        searchLocalLibraryBooks(query)
                                    }
                                    method.name.contains("setupBackupDir") -> {
                                        launchLocalLibraryFolderPicker()
                                        targetUnit()
                                    }
                                    else -> null
                                }
                            } else when {
                            method.name.contains("openFolder") -> {
                                val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                saveWebDavBrowseDir(path)
                                updateWebDavStorageTrees(path)
                                refreshWebDavLibrary(path)
                                targetUnit()
                            }
                            method.name.contains("getFolderList") -> {
                                val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                listWebDav(path)
                                    .filter { it.isDirectory }
                                    .map { newCloudFolder(it) }
                            }
                            method.name.contains("createFolder") -> {
                                val parentPath = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                val name = param.args?.getOrNull(2)?.toString().orEmpty()
                                webDavMkcol(childWebDavPath(parentPath, name))
                                refreshWebDavLibrary(parentPath)
                                targetUnit()
                            }
                            method.name.contains("move") -> {
                                val path = param.args?.getOrNull(1)?.toString().orEmpty()
                                val dest = param.args?.getOrNull(2)?.toString().orEmpty()
                                webDavMove(path, childWebDavPath(dest, normalizeWebDavPath(path).substringAfterLast('/')))
                                refreshWebDavLibrary(parentWebDavPath(dest))
                                targetUnit()
                            }
                            method.name.contains("rename") -> {
                                val book = param.args?.getOrNull(1)
                                val path = cloudPathOf(book)
                                val newName = param.args?.getOrNull(2)?.toString().orEmpty()
                                webDavMove(path, childWebDavPath(parentWebDavPath(path), newName))
                                refreshWebDavLibrary(parentWebDavPath(path))
                                targetUnit()
                            }
                            method.name.contains("delete") -> {
                                val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                webDavDelete(path)
                                refreshWebDavLibrary(parentWebDavPath(path))
                                targetUnit()
                            }
                            method.name.contains("getInfo") -> {
                                val path = webDavPathArg(param.args?.getOrNull(1)?.toString().orEmpty())
                                val entry = listWebDav(parentWebDavPath(path))
                                    .firstOrNull {
                                        normalizeWebDavPath(it.path) == normalizeWebDavPath(path) &&
                                            !it.isDirectory &&
                                            isSupportedBookFile(it.name)
                                    }
                                    ?: syntheticWebDavBookEntry(path)
                                logWebDav("getInfo path=$path found=${entry.path}")
                                newCloudBook(entry)
                            }
                            method.name.contains("search") -> {
                                val query = param.args?.getOrNull(1)?.toString().orEmpty()
                                searchWebDavBooks(query)
                            }
                            method.name.contains("setupBackupDir") -> {
                                val folders = (param.args?.getOrNull(1) as? List<*>).orEmpty()
                                val path = folders.lastOrNull()?.let { folder ->
                                    folder.javaClass.methods.firstOrNull {
                                        it.name == "getPath" && it.parameterTypes.isEmpty()
                                    }?.apply { isAccessible = true }?.invoke(folder)?.toString()
                                }.orEmpty()
                                if (path.isNotBlank()) {
                                    saveWebDavBackupDir(path)
                                }
                                targetUnit()
                            }
                            else -> null
                            }
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV cloud storage data hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage data: ${it.stackTraceToString()}")
        }
    }

    private fun hookHomeViewModelDependencies() {
        runCatching {
            cls(HOME_VIEW_MODEL_CLASS).declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberHomeViewModelDependencies(param.thisObject)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX home viewModel dependency hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook home viewModel dependencies: ${it.stackTraceToString()}")
        }
    }

    private fun hookHomeWebDavSearch() {
        runCatching {
            val method = cls(HOME_VIEW_MODEL_CLASS).declaredMethods.first {
                it.name == HOME_SEARCH_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    rememberHomeViewModelDependencies(param.thisObject)
                    val query = param.args?.getOrNull(0)?.toString().orEmpty()
                    val seq = webDavHomeSearchSeq.incrementAndGet()
                    val localSeq = localLibraryHomeSearchSeq.incrementAndGet()
                    val onlineSeq = onlineCompletionHomeSearchSeq.incrementAndGet()
                    Thread({
                        runCatching {
                            if (query.isNotBlank()) {
                                Thread.sleep(HOME_SEARCH_DEBOUNCE_MS)
                                if (onlineCompletionHomeSearchSeq.get() != onlineSeq) return@runCatching
                            }
                            val onlineResults = if (query.isBlank()) emptyList() else searchOnlineCompletionSources(query)
                            if (onlineCompletionHomeSearchSeq.get() != onlineSeq) return@runCatching
                            Handler(Looper.getMainLooper()).post {
                                if (onlineCompletionHomeSearchSeq.get() == onlineSeq) {
                                    updateHomeOnlineCompletionSearchResults(param.thisObject, onlineResults)
                                }
                            }
                            logWebDav("home online search query=$query online=${onlineResults.size}")
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX online completion home search failed: ${it.stackTraceToString()}")
                        }
                    }, "ReaMicroOnlineCompletionHomeSearch").start()
                    Thread({
                        runCatching {
                            if (query.isNotBlank()) {
                                Thread.sleep(HOME_SEARCH_DEBOUNCE_MS)
                                if (
                                    webDavHomeSearchSeq.get() != seq ||
                                    localLibraryHomeSearchSeq.get() != localSeq
                                ) {
                                    return@runCatching
                                }
                            }
                            val webDavResults = if (query.isBlank() || !hasWebDavLogin(currentContext())) {
                                emptyList()
                            } else {
                                searchWebDavBooks(query)
                            }
                            val localResults = if (query.isBlank() || !canShowLocalLibraryEntry()) {
                                emptyList()
                            } else {
                                searchLocalLibraryBooks(query)
                            }
                            if (webDavHomeSearchSeq.get() != seq) return@runCatching
                            if (localLibraryHomeSearchSeq.get() != localSeq) return@runCatching
                            rememberHomeSearchSnapshot(webDavResults, localResults)
                            Handler(Looper.getMainLooper()).post {
                                if (
                                    webDavHomeSearchSeq.get() == seq &&
                                    localLibraryHomeSearchSeq.get() == localSeq
                                ) {
                                    updateHomeWebDavSearchResults(param.thisObject, webDavResults, localResults, null)
                                }
                            }
                            scheduleLocalLibrarySearchRefresh(param.thisObject, query, webDavResults, seq, localSeq, LOCAL_LIBRARY_SEARCH_REFRESH_DELAY_MS)
                            scheduleLocalLibrarySearchRefresh(param.thisObject, query, webDavResults, seq, localSeq, LOCAL_LIBRARY_SEARCH_LATE_REFRESH_DELAY_MS)
                            logWebDav("home search query=$query webdav=${webDavResults.size} local=${localResults.size}")
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX WebDAV home search failed: ${it.stackTraceToString()}")
                        }
                    }, "ReaMicroWebDavHomeSearch").start()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV home search hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV home search: ${it.stackTraceToString()}")
        }
    }

    private fun hookHomeSearchResultWebDavSection() {
        runCatching {
            val method = cls(HOME_SEARCH_BAR_CLASS).declaredMethods.first {
                it.name == HOME_SEARCH_RESULT_LAZY_METHOD && it.parameterTypes.size == 5
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val map = param.args?.getOrNull(2) as? Map<*, *> ?: return
                    val sections = buildList {
                        (map[BACKUP_TYPE_WEBDAV] as? List<*>)?.takeIf { it.isNotEmpty() }?.let {
                            add(HomeSearchSection(type = BACKUP_TYPE_WEBDAV, results = it))
                        }
                        (map[BACKUP_TYPE_LOCAL_LIBRARY] as? List<*>)?.takeIf {
                            canShowLocalLibraryEntry() && it.isNotEmpty()
                        }?.let {
                            add(HomeSearchSection(type = BACKUP_TYPE_LOCAL_LIBRARY, results = it))
                        }
                        (map[BACKUP_TYPE_ONLINE_COMPLETION] as? List<*>)?.forEach { item ->
                            val group = item as? OnlineSearchGroup ?: return@forEach
                            val title = onlineCompletionGroupTitle(group)
                            val renderType = onlineCompletionRenderTypeForSource(group.source, title)
                            add(
                                HomeSearchSection(
                                    type = renderType,
                                    title = title,
                                    results = onlineCompletionGroupVisibleCloudBooks(group),
                                ),
                            )
                        }
                    }
                    if (sections.isEmpty()) return
                    val intentReceiver = param.args?.getOrNull(3) ?: return
                    homeWebDavSearchRender.set(HomeSearchRenderContext(sections, intentReceiver))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    homeWebDavSearchRender.remove()
                }
            })

            val footer = cls(FOOTER_CLASS).declaredMethods.first {
                it.name == FOOTER_METHOD && it.parameterTypes.size == 2
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(footer, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = homeWebDavSearchRender.get() ?: return
                    if (context.rendered) return
                    val lazyListScope = param.args?.getOrNull(0) ?: return
                    context.rendered = true
                    context.sections.forEach { section ->
                        addHomeWebDavSearchSection(lazyListScope, section.type, section.title, section.results, context.intentReceiver)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV home search result hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV home search result: ${it.stackTraceToString()}")
        }
    }

    private fun hookHomeSearchTapCancellation() {
        runCatching {
            val method = method(HOME_SEARCH_BAR_CLASS, HOME_SEARCH_TAP_METHOD, 2)
            XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(1) ?: return
                    val type = book.callInt("getType")
                    if (isOnlineCompletionPath(cloudPathOf(book))) {
                        handleOnlineCompletionSearchTap(book)
                        param.result = targetUnit()
                        return
                    }
                    if (tryHandleRunningCloudDownloadTap(book, type)) {
                        param.result = targetUnit()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX home search download cancellation hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook home search download cancellation: ${it.stackTraceToString()}")
        }
    }

    private fun scheduleLocalLibrarySearchRefresh(
        viewModel: Any?,
        query: String,
        webDavResults: List<Any>,
        webDavSeq: Long,
        localSeq: Long,
        delayMs: Long,
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({
                runCatching {
                    if (
                        webDavHomeSearchSeq.get() != webDavSeq ||
                        localLibraryHomeSearchSeq.get() != localSeq
                    ) return@runCatching
                    val refreshedLocalResults = if (query.isBlank() || !canShowLocalLibraryEntry()) {
                        emptyList()
                    } else {
                        searchLocalLibraryBooks(query)
                    }
                    if (
                        webDavHomeSearchSeq.get() != webDavSeq ||
                        localLibraryHomeSearchSeq.get() != localSeq
                    ) return@runCatching
                    Handler(Looper.getMainLooper()).post {
                        if (
                            webDavHomeSearchSeq.get() == webDavSeq &&
                            localLibraryHomeSearchSeq.get() == localSeq
                        ) {
                            rememberHomeSearchSnapshot(webDavResults, refreshedLocalResults)
                            updateHomeWebDavSearchResults(viewModel, webDavResults, refreshedLocalResults, null)
                        }
                    }
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX local library delayed search refresh failed: ${it.stackTraceToString()}")
                }
            }, "ReaMicroLocalLibrarySearchRefresh").start()
        }, delayMs)
    }

    private fun rememberHomeSearchSnapshot(
        webDavResults: List<Any>,
        localResults: List<Any>,
    ) {
        lastHomeSearchWebDavResults = webDavResults
        lastHomeSearchLocalResults = localResults
    }

    private fun hookWebDavCloudDownload() {
        runCatching {
            val method = cls(WORKER_MANAGER_CLASS).declaredMethods.first {
                it.name == WORKER_ENQUEUE_DOWNLOAD_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == CLOUD_BOOK_CLASS
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    val type = book.javaClass.methods.firstOrNull {
                        it.name == "getType" && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true }?.invoke(book) as? Number ?: return
                    if (type.toInt() != BACKUP_TYPE_WEBDAV && type.toInt() != BACKUP_TYPE_LOCAL_LIBRARY) return

                    param.result = runCatching {
                        if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) {
                            enqueueLocalLibraryImport(param.thisObject, book)
                        } else {
                            enqueueWebDavDownload(param.thisObject, book)
                        }
                    }.getOrElse {
                        XposedBridge.log("$LOG_PREFIX cloud download/import failed: ${it.stackTraceToString()}")
                        val title = if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) LOCAL_LIBRARY_TITLE else WEBDAV_TITLE
                        newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "import failed", null, title))
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    val type = book.callInt("getType")
                    if (type !in NATIVE_CLOUD_DOWNLOAD_TYPES) return
                    val handle = param.result ?: return
                    registerNativeCloudDownload(param.thisObject, book, handle, type)
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud download hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud download: ${it.stackTraceToString()}")
        }
    }

    private fun hookNativeCloudDownloadCancellation() {
        runCatching {
            val method = cls(WORK_TRACKER_CLASS).declaredMethods.first {
                it.name == "setState" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val id = param.args?.getOrNull(0)?.toString().orEmpty()
                    val token = runningNativeCloudDownloadsById[id] ?: return
                    val state = param.args?.getOrNull(1) ?: return
                    val status = workStateStatusName(state)
                    if (token.cancelRequested && (status == "Running" || status == "Success")) {
                        throw CloudDownloadCancelledException()
                    }
                    if (token.cancelRequested && status == "Error") {
                        param.args?.set(1, newWorkState("Cancelled", 100, null, null, token.name))
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = param.args?.getOrNull(0)?.toString().orEmpty()
                    val token = runningNativeCloudDownloadsById[id] ?: return
                    val state = param.args?.getOrNull(1) ?: return
                    when (workStateStatusName(state)) {
                        "Success", "Error", "Cancelled" -> unregisterNativeCloudDownload(token)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX native cloud download cancellation hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook native cloud download cancellation: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavImportBookSource() {
        runCatching {
            val repositoryClass = cls(BOOKSHELF_REPOSITORY_CLASS)
            repositoryClass.declaredMethods.filter {
                it.name == BOOKSHELF_IMPORT_BOOK_METHOD && it.parameterTypes.size == 6
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rememberBookshelfRepository(param.thisObject)
                        val platformFile = param.args?.getOrNull(0) ?: return
                        val keys = platformFileKeys(platformFile)
                        val source = keys.firstNotNullOfOrNull { pendingWebDavImports[it] } ?: return
                        if (source.sourceUrl.isNotBlank()) {
                            param.args?.set(3, source.sourceUrl)
                        }
                        source.sourceSize?.takeIf { it > 0L }?.let {
                            param.args?.set(4, java.lang.Long.valueOf(it))
                        }
                        logWebDav("importBook source override url=${source.sourceUrl.redactWebDavUrl()} size=${source.sourceSize ?: 0}")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val platformFile = param.args?.getOrNull(0) ?: return
                        platformFileKeys(platformFile).forEach { pendingWebDavImports.remove(it) }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV import source hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV import source: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavCloudBackup() {
        runCatching {
            val method = cls(WORKER_MANAGER_CLASS).declaredMethods.first {
                it.name == WORKER_ENQUEUE_BACKUP_METHOD &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE &&
                    it.parameterTypes[2] == String::class.java
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bookId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    if (type != BACKUP_TYPE_WEBDAV) return
                    val dir = param.args?.getOrNull(2)?.toString().orEmpty().ifBlank { currentWebDavBackupDir() }
                    logWebDav("enqueueBackup intercepted bookId=$bookId dir=$dir")
                    param.result = runCatching {
                        enqueueWebDavBackup(param.thisObject, bookId, dir)
                    }.getOrElse {
                        XposedBridge.log("$LOG_PREFIX WebDAV backup failed: ${it.stackTraceToString()}")
                        newWorkHandle(UUID.randomUUID().toString(), newWorkState("Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE))
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud backup hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud backup: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookBackupViewModelState() {
        runCatching {
            cls(BOOK_BACKUP_VIEW_MODEL_CLASS).declaredConstructors
                .filter { it.parameterTypes.size == 3 && it.parameterTypes[0] == java.lang.Long.TYPE }
                .forEach { constructor ->
                    constructor.isAccessible = true
                    XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            rememberCloudStorageRepository(param.args?.getOrNull(2))
                            val viewModel = param.thisObject ?: return
                            synchronized(webDavBackupViewModels) {
                                webDavBackupViewModels.removeAll { it.get() == null || it.get() === viewModel }
                                webDavBackupViewModels.add(WeakReference(viewModel))
                            }
                            logWebDav("BookBackupViewModel registered bookId=${param.args?.getOrNull(0)}")
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX WebDAV book backup viewModel hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV book backup viewModel: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookBackupWebDavCard() {
        runCatching {
            val method = method(BOOK_BACKUP_SCREEN_CLASS, BOOK_BACKUP_CONTENT_METHOD, 9)
            XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0)
                    val cloudBook = param.args?.getOrNull(1)
                    val type = cloudBook?.callInt("getType") ?: 0
                    val bookBackupType = bookBackupTypeOf(book)
                    val backupId = bookBackupIdOf(book)
                    val bookId = book?.callLong("getId") ?: 0L
                    val recent = webDavRecentBackups[bookId]
                    val seq = bookBackupContentLogSeq.incrementAndGet()
                    if (seq <= 12) {
                        logWebDav(
                            "backup content render#$seq bookId=$bookId " +
                                "bookType=$bookBackupType backupId=$backupId cloudType=$type " +
                                "cloudPath=${cloudBook?.callString("getPath").orEmpty()} recent=${recent != null}",
                        )
                    }
                    if (recent != null && bookBackupType != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_WEBDAV) {
                        param.args?.set(0, recent.book)
                        param.args?.set(1, copyCloudBookWithType(recent.backup, BACKUP_TYPE_YUN115))
                        logWebDav("backup content recent WebDAV card path=${recent.backup.callString("getPath")}")
                        pushWebDavBackupCard()
                        return
                    }
                    if (bookBackupType == BACKUP_TYPE_WEBDAV || type == BACKUP_TYPE_WEBDAV) {
                        logWebDav(
                            "backup content bookType=$bookBackupType cloudType=$type " +
                                "name=${cloudBook?.callString("getName").orEmpty()} path=${cloudBook?.callString("getPath").orEmpty()}",
                        )
                    }
                    when {
                        type == BACKUP_TYPE_WEBDAV -> {
                            cloudBook ?: return
                            param.args?.set(1, copyCloudBookWithType(cloudBook, BACKUP_TYPE_YUN115))
                            logWebDav("backup content mapped WebDAV card path=${cloudBook.callString("getPath")}")
                            pushWebDavBackupCard()
                        }
                        bookBackupType == BACKUP_TYPE_WEBDAV -> {
                            if (backupId.isBlank()) return
                            val fallbackBook = newCloudBook(syntheticWebDavBookEntry(backupId))
                            param.args?.set(1, copyCloudBookWithType(fallbackBook, BACKUP_TYPE_YUN115))
                            logWebDav("backup content synthesized WebDAV card path=$backupId")
                            pushWebDavBackupCard()
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((webDavBackupCardDepth.get() ?: 0) > 0) {
                        popWebDavBackupCard()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV backup card hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV backup card: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavYun115BackupCard() {
        runCatching {
            val method = method(DRIVE_CARD_CLASS, YUN115_NET_DISK_CARD_METHOD, 5)
            XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cloudBook = param.args?.getOrNull(0) ?: return
                    if (cloudBook.callInt("getType") != BACKUP_TYPE_WEBDAV) return
                    param.args?.set(0, copyCloudBookWithType(cloudBook, BACKUP_TYPE_YUN115))
                    pushWebDavBackupCard()
                    logWebDav("Yun115 card mapped WebDAV path=${cloudBook.callString("getPath")}")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((webDavBackupCardDepth.get() ?: 0) > 0) {
                        popWebDavBackupCard()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV Yun115 backup card hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV Yun115 backup card: ${it.stackTraceToString()}")
        }
    }

    private fun enqueueWebDavDownload(workerManager: Any, book: Any): Any {
        val name = book.javaClass.methods.firstOrNull {
            it.name == "getName" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(book)?.toString().orEmpty().ifBlank { WEBDAV_TITLE }
        val path = cloudPathOf(book)
        val sourceUrl = book.javaClass.methods.firstOrNull {
            it.name == "getUrl" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(book)?.toString().orEmpty()
        val sourceSize = book.javaClass.methods.firstOrNull {
            it.name == "getSize" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(book) as? Number
        val uniqueName = path.ifBlank { name }
        val tracker = workerManager.javaClass.getDeclaredField("tracker")
            .apply { isAccessible = true }
            .get(workerManager)
        rememberWorkerManager(workerManager)
        val id = UUID.randomUUID().toString()
        val stateFlow = tracker.javaClass.methods.first {
            it.name == "createTask" && it.parameterTypes.size == 4
        }.apply { isAccessible = true }.invoke(
            tracker,
            id,
            "download",
            uniqueName,
            newWorkState("Running", 0, null, null, name),
        )
        val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
            .apply { isAccessible = true }
            .newInstance(id, stateFlow)
        val localFile = importCacheFile(
            currentContext()?.cacheDir,
            "reamicro-webdav",
            name,
        )
        val downloadKey = webDavDownloadKey(path, name)
        val token = CancellableWebDavDownload(
            id = id,
            key = downloadKey,
            name = name,
            tracker = tracker,
            localFile = localFile,
            createdAtMs = System.currentTimeMillis(),
        )
        runningWebDavDownloads[downloadKey] = token

        val thread = Thread({
            runCatching {
                throwIfWebDavDownloadCancelled(token)
                setTrackedWorkState(tracker, id, "Running", 2, null, null, name)
                var lastProgress = 0
                webDavDownload(path, localFile) { downloadProgress ->
                    throwIfWebDavDownloadCancelled(token)
                    val progress = (downloadProgress * 70 / 100).coerceIn(2, 70)
                    if (progress >= lastProgress + 4 || progress == 70) {
                        lastProgress = progress
                        setTrackedWorkState(tracker, id, "Running", progress, null, null, name)
                    }
                }
                throwIfWebDavDownloadCancelled(token)
                setTrackedWorkState(tracker, id, "Running", 78, null, null, name)
                val platformFile = platformFile(localFile)
                rememberPendingWebDavImport(platformFile, localFile, sourceUrl, sourceSize?.toLong())
                setTrackedWorkState(tracker, id, "Running", 90, null, null, name)
                enqueueNativeImport(workerManager, platformFile)
                setTrackedWorkState(tracker, id, "Success", 100, null, sourceUrl.ifBlank { localFile.absolutePath }, name)
                logWebDav("download complete native import queued path=$path url=${sourceUrl.redactWebDavUrl()}")
            }.onFailure {
                if (it is CloudDownloadCancelledException || token.cancelRequested) {
                    cancelTrackedWork(tracker, id)
                    cleanupDownloadToken(token)
                    logWebDav("download cancelled path=$path")
                } else {
                    XposedBridge.log("$LOG_PREFIX WebDAV tracked download failed: ${it.stackTraceToString()}")
                    setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV download failed", null, name)
                }
            }.also {
                runningWebDavDownloads.remove(downloadKey, token)
                webDavDownloadCancelPrompts.remove(id)
            }
        }, "ReaMicroWebDavDownload")
        token.thread = thread
        thread.start()

        return handle
    }

    private fun enqueueLocalLibraryImport(workerManager: Any, book: Any): Any {
        val name = book.callString("getName").ifBlank { LOCAL_LIBRARY_TITLE }
        val path = cloudPathOf(book)
        val sourceUrl = book.callString("getUrl").ifBlank { localLibraryBookUrl(path) }
        val sourceSize = book.javaClass.methods.firstOrNull {
            it.name == "getSize" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(book) as? Number
        val tracker = workerManager.javaClass.getDeclaredField("tracker")
            .apply { isAccessible = true }
            .get(workerManager)
        rememberWorkerManager(workerManager)
        val id = UUID.randomUUID().toString()
        val stateFlow = tracker.javaClass.methods.first {
            it.name == "createTask" && it.parameterTypes.size == 4
        }.apply { isAccessible = true }.invoke(
            tracker,
            id,
            "download",
            path.ifBlank { name },
            newWorkState("Running", 0, null, null, name),
        )
        val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
            .apply { isAccessible = true }
            .newInstance(id, stateFlow)

        Thread({
            runCatching {
                val entry = localLibraryEntry(path) ?: error("本地书库文件不存在")
                val context = currentContext() ?: error("Android context not available")
                val localFile = importCacheFile(
                    context.cacheDir,
                    "reamicro-local-library",
                    entry.name,
                )
                localFile.parentFile?.mkdirs()
                setTrackedWorkState(tracker, id, "Running", 20, null, null, name)
                context.contentResolver.openInputStream(localDocumentUri(entry))?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取本地书库文件")
                setTrackedWorkState(tracker, id, "Running", 80, null, null, name)
                val platformFile = platformFile(localFile)
                rememberPendingWebDavImport(platformFile, localFile, sourceUrl, sourceSize?.toLong() ?: entry.size)
                enqueueNativeImport(workerManager, platformFile)
                setTrackedWorkState(tracker, id, "Success", 100, null, sourceUrl, name)
                logWebDav("local library import queued path=$path")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX local library import failed: ${it.stackTraceToString()}")
                setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "本地书库导入失败", null, name)
            }
        }, "ReaMicroLocalLibraryImport").start()

        return handle
    }

    private fun enqueueWebDavBackup(workerManager: Any, bookId: Long, dir: String): Any {
        rememberWorkerManager(workerManager)
        val tracker = workerManager.javaClass.getDeclaredField("tracker")
            .apply { isAccessible = true }
            .get(workerManager)
        val normalizedDir = normalizeWebDavPath(dir.ifBlank { currentWebDavBackupDir() })
        cancelExistingBackupTasks(tracker, bookId)
        val id = UUID.randomUUID().toString()
        val stateFlow = tracker.javaClass.methods.first {
            it.name == "createTask" && it.parameterTypes.size == 4
        }.apply { isAccessible = true }.invoke(
            tracker,
            id,
            "backup:$BACKUP_TYPE_YUN115",
            bookId.toString(),
            newWorkState("Running", 0, null, null, null),
        )
        val handle = cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
            .apply { isAccessible = true }
            .newInstance(id, stateFlow)

        webDavRunningBackupBookIds[bookId] = true
        Thread({
            try {
                runCatching {
                    val book = findLocalBookById(bookId) ?: error("Book $bookId not found")
                    val fileName = exportFileName(book)
                    val localFile = File(
                        currentContext()?.cacheDir ?: File("/data/local/tmp"),
                        "reamicro-webdav-backup/${System.currentTimeMillis()}_${fileName.safeWebDavFileName()}",
                    )
                    localFile.parentFile?.mkdirs()
                    setTrackedWorkState(tracker, id, "Running", 8, null, null, fileName)
                    localFile.outputStream().use { output ->
                        zipBookDirectory(book, output)
                    }
                    setTrackedWorkState(tracker, id, "Running", 45, null, null, fileName)
                    val remotePath = childWebDavPath(normalizedDir, fileName)
                    webDavUpload(remotePath, localFile)
                    setTrackedWorkState(tracker, id, "Running", 88, null, null, fileName)
                    val updatedBook = copyBookWithBackup(book, BACKUP_TYPE_WEBDAV, remotePath, "")
                    updateLocalBook(updatedBook)
                    val backupBook = newCloudBook(
                        WebDavEntry(
                            name = fileName,
                            path = remotePath,
                            isDirectory = false,
                            size = localFile.length(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    webDavRecentBackups[bookId] = WebDavBackupSnapshot(updatedBook, backupBook)
                    updateWebDavBackupViewModels(bookId, updatedBook, backupBook)
                    refreshWebDavBackupViewModelsLater(bookId, updatedBook, backupBook)
                    refreshWebDavLibrary(parentWebDavPath(remotePath))
                    setTrackedWorkState(tracker, id, "Success", 100, null, remotePath, fileName)
                    logWebDav("backup uploaded bookId=$bookId remote=$remotePath metadata updated")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX WebDAV tracked backup failed: ${it.stackTraceToString()}")
                    setTrackedWorkState(tracker, id, "Error", 100, it.message ?: "WebDAV backup failed", null, WEBDAV_TITLE)
                }
            } finally {
                webDavRunningBackupBookIds.remove(bookId)
                logWebDav("backup running cleared bookId=$bookId")
            }
        }, "ReaMicroWebDavBackup").start()

        return handle
    }

    private fun setTrackedWorkState(
        tracker: Any,
        id: String,
        statusName: String,
        progress: Int,
        error: String?,
        result: String?,
        name: String?,
    ) {
        runCatching {
            tracker.javaClass.methods.first {
                it.name == "setState" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }.invoke(
                tracker,
                id,
                newWorkState(statusName, progress.coerceIn(0, 100), error, result, name),
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update WebDAV work state: ${it.stackTraceToString()}")
        }
    }

    private fun tryHandleRunningCloudDownloadTap(book: Any, type: Int): Boolean {
        if (type in NATIVE_CLOUD_DOWNLOAD_TYPES && tryHandleRunningNativeCloudDownloadTap(book, type)) {
            return true
        }
        if (type == BACKUP_TYPE_WEBDAV && tryHandleRunningWebDavDownloadTap(book)) {
            return true
        }
        return false
    }

    private fun tryHandleRunningWebDavDownloadTap(book: Any): Boolean {
        if (!canCancelCloudDownload()) return false
        val path = cloudPathOf(book)
        val name = book.callString("getName").ifBlank { WEBDAV_TITLE }
        val token = runningWebDavDownloads[webDavDownloadKey(path, name)] ?: return false
        val now = System.currentTimeMillis()
        val last = webDavDownloadCancelPrompts[token.id] ?: 0L
        if (now - last > DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS) {
            webDavDownloadCancelPrompts[token.id] = now
            showToast("\u518d\u70b9\u4e00\u6b21\u53d6\u6d88\u4e0b\u8f7d")
            return true
        }
        token.cancelRequested = true
        token.thread?.interrupt()
        cancelTrackedWork(token.tracker, token.id)
        cleanupDownloadToken(token)
        runningWebDavDownloads.remove(token.key, token)
        webDavDownloadCancelPrompts.remove(token.id)
        showToast("\u5df2\u53d6\u6d88\u4e0b\u8f7d\u5e76\u6e05\u7406\u7f13\u5b58")
        return true
    }

    private fun tryHandleRunningNativeCloudDownloadTap(book: Any, type: Int): Boolean {
        if (!canCancelCloudDownload()) return false
        val name = book.callString("getName").ifBlank { cloudTypeTitle(type) }
        val token = runningNativeCloudDownloads[nativeCloudDownloadKey(book, type)] ?: return false
        val now = System.currentTimeMillis()
        val last = webDavDownloadCancelPrompts[token.id] ?: 0L
        if (now - last > DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS) {
            webDavDownloadCancelPrompts[token.id] = now
            showToast("\u518d\u70b9\u4e00\u6b21\u53d6\u6d88\u4e0b\u8f7d")
            return true
        }
        token.cancelRequested = true
        cancelTrackedWork(token.tracker, token.id)
        deleteCachePath(token.cacheDir, CacheDeleteStat())
        webDavDownloadCancelPrompts.remove(token.id)
        showToast("\u5df2\u53d6\u6d88\u4e0b\u8f7d\u5e76\u6e05\u7406\u7f13\u5b58")
        logWebDav("native download cancel requested type=$type name=$name id=${token.id}")
        return true
    }

    private fun registerNativeCloudDownload(workerManager: Any?, book: Any, handle: Any, type: Int) {
        val id = handle.callString("getId")
        if (id.isBlank()) return
        val tracker = runCatching {
            workerManager?.javaClass?.getDeclaredField("tracker")?.apply { isAccessible = true }?.get(workerManager)
        }.getOrNull() ?: workTrackerRef?.get() ?: return
        rememberWorkerManager(workerManager)
        val cacheDir = File(currentContext()?.cacheDir ?: File("/data/local/tmp"), "downloads/$id")
        val token = NativeCloudDownload(
            id = id,
            key = nativeCloudDownloadKey(book, type),
            name = book.callString("getName").ifBlank { cloudTypeTitle(type) },
            type = type,
            tracker = tracker,
            cacheDir = cacheDir,
        )
        runningNativeCloudDownloads[token.key]?.let(::unregisterNativeCloudDownload)
        runningNativeCloudDownloads[token.key] = token
        runningNativeCloudDownloadsById[id] = token
        logWebDav("native download registered type=$type id=$id key=${token.key}")
    }

    private fun unregisterNativeCloudDownload(token: NativeCloudDownload) {
        runningNativeCloudDownloads.remove(token.key, token)
        runningNativeCloudDownloadsById.remove(token.id, token)
        webDavDownloadCancelPrompts.remove(token.id)
    }

    private fun throwIfWebDavDownloadCancelled(token: CancellableWebDavDownload) {
        if (token.cancelRequested || Thread.currentThread().isInterrupted) {
            throw CloudDownloadCancelledException()
        }
    }

    private fun throwIfOnlineCompletionDownloadCancelled(task: OnlineCompletionDownloadTask) {
        if (task.cancelRequested || Thread.currentThread().isInterrupted) {
            throw OnlineCompletionDownloadCancelledException()
        }
    }

    private fun requestOnlineCompletionDownloadCancel(context: Context, notificationId: Int, key: String) {
        val task = onlineCompletionRunningDownloadsByNotificationId[notificationId]
            ?: key.takeIf { it.isNotBlank() }?.let { onlineCompletionRunningDownloads[it] }
        if (task == null) {
            cancelOnlineCompletionHostNotification(context, notificationId)
            logWebDav("online completion cancel ignored missing task id=$notificationId key=$key")
            return
        }
        task.cancelRequested = true
        task.thread?.interrupt()
        cancelOnlineCompletionTrackedWork(task)
        cleanupOnlineCompletionDownloadTask(task)
        onlineCompletionRunningDownloads.remove(task.key, task)
        onlineCompletionRunningDownloadsByNotificationId.remove(task.notificationId, task)
        cancelOnlineCompletionNotification(context, task.notificationId, task.key)
        logWebDav("online completion cancel requested id=${task.notificationId} book=${task.name}")
    }

    private fun cancelOnlineCompletionTrackedWork(task: OnlineCompletionDownloadTask) {
        val tracker = task.tracker ?: return
        val workId = task.workId ?: return
        cancelTrackedWork(tracker, workId)
    }

    private fun cancelTrackedWork(tracker: Any, id: String) {
        runCatching {
            tracker.javaClass.methods.first {
                it.name == "cancelWorkById" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(tracker, id)
        }.onFailure {
            setTrackedWorkState(tracker, id, "Cancelled", 100, null, null, null)
        }
    }

    private fun cleanupDownloadToken(token: CancellableWebDavDownload) {
        runCatching {
            token.localFile.delete()
            token.localFile.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
        }.onFailure {
            logWebDav("failed to cleanup cancelled download cache: ${it.message}")
        }
    }

    private fun cleanupOnlineCompletionDownloadTask(task: OnlineCompletionDownloadTask) {
        runCatching {
            val stat = CacheDeleteStat()
            deleteCachePath(task.cacheDir, stat)
            logWebDav(
                "online completion cache cleanup id=${task.notificationId} " +
                    "files=${stat.files} bytes=${stat.bytes} dir=${task.cacheDir.absolutePath}",
            )
        }.onFailure {
            logWebDav("online completion cache cleanup failed id=${task.notificationId}: ${it.message}")
        }
    }

    private fun deleteCachePath(file: File, stat: CacheDeleteStat) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteCachePath(it, stat) }
        } else {
            stat.files += 1
            stat.bytes += file.length().coerceAtLeast(0L)
        }
        file.delete()
    }

    private fun isChildPath(child: File, parent: File): Boolean {
        val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
        return child.path.startsWith(parentPath)
    }

    private fun webDavDownloadKey(path: String, name: String): String =
        normalizeWebDavPath(path.ifBlank { name })

    private fun nativeCloudDownloadKey(book: Any, type: Int): String {
        val id = book.callString("getId")
        val fallback = cloudPathOf(book).ifBlank { book.callString("getName") }
        return "$type:${id.ifBlank { fallback }}"
    }

    private fun workStateStatusName(state: Any): String =
        state.invokeNoArg("getStatus")?.toString().orEmpty()

    private fun cloudTypeTitle(type: Int): String =
        when (type) {
            BACKUP_TYPE_BAIDU -> "\u767e\u5ea6\u7f51\u76d8"
            BACKUP_TYPE_YUN115 -> "115"
            BACKUP_TYPE_ALIYUN -> "\u963f\u91cc\u4e91\u76d8"
            else -> "\u4e91\u76d8"
        }

    private fun staleTopLevelImportCacheDirs(cacheDir: File): List<File> {
        val cutoff = System.currentTimeMillis() - STALE_IMPORT_CACHE_MIN_AGE_MS
        return cacheDir.listFiles()
            ?.filter { it.isDirectory && UUID_DIR_REGEX.matches(it.name) && it.lastModified() in 1 until cutoff }
            .orEmpty()
    }

    private fun showToast(message: String) {
        val activity = activityProvider()
        if (activity != null) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        Handler(Looper.getMainLooper()).post {
            currentContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun cancelExistingBackupTasks(tracker: Any, bookId: Long) {
        runCatching {
            val worksFlow = tracker.javaClass.methods.first {
                it.name == "works" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(tracker)
            val works = worksFlow?.javaClass?.methods?.firstOrNull {
                it.name == "getValue" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(worksFlow) as? Iterable<*> ?: return
            val ids = works.mapNotNull { work ->
                val kind = work?.javaClass?.methods?.firstOrNull { it.name == "getKind" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(work)
                    ?.toString()
                val label = work?.javaClass?.methods?.firstOrNull { it.name == "getLabel" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(work)
                    ?.toString()
                if (kind?.startsWith("backup") == true && label == bookId.toString()) {
                    work.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                        ?.apply { isAccessible = true }
                        ?.invoke(work)
                        ?.toString()
                } else {
                    null
                }
            }
            val cancel = tracker.javaClass.methods.firstOrNull {
                it.name == "cancelWorkById" && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }
            ids.forEach { cancel?.invoke(tracker, it) }
            tracker.javaClass.methods.firstOrNull {
                it.name == "pruneWork" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(tracker)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to cancel old WebDAV backup tasks: ${it.stackTraceToString()}")
        }
    }

    private fun findLocalBookById(bookId: Long): Any? {
        val bookshelf = currentBookshelfRepository()
            ?: error("BookshelfRepository not available for WebDAV backup")
        val findMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "findBookById" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }
            .apply { isAccessible = true }
        return invokeSuspendBlocking(findMethod, bookshelf, bookId)
    }

    private fun findLocalBookByUuid(uid: Long, uuid: String): Any? {
        if (uid <= 0L || uuid.isBlank()) return null
        val bookshelf = currentBookshelfRepository() ?: return null
        val bookDao = fieldValue(bookshelf, "bookDao") ?: return null
        val findMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
            .firstOrNull { candidate ->
                candidate.name == "findByUidAndUuid" &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0] == java.lang.Long.TYPE &&
                    candidate.parameterTypes[1] == String::class.java
            }
            ?.apply { isAccessible = true }
            ?: return null
        return invokeSuspendBlocking(findMethod, bookDao, uid, uuid)
    }

    private fun updateLocalBook(book: Any) {
        val bookshelf = currentBookshelfRepository()
            ?: error("BookshelfRepository not available for WebDAV update")
        val updateMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .first {
                it.name == BOOKSHELF_UPDATE_BOOK_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == BOOK_CLASS
            }
            .apply { isAccessible = true }
        invokeSuspendBlocking(updateMethod, bookshelf, book)
    }

    private fun copyBookWithBackup(book: Any, backupType: Int, backupId: String, backupCode: String): Any {
        return copyBookWithBackupAndPublisher(
            book = book,
            backupType = backupType,
            backupId = backupId,
            backupCode = backupCode,
            publisher = book.callString("getPublisher"),
        )
    }

    private fun copyBookWithBackupAndPublisher(
        book: Any,
        backupType: Int,
        backupId: String,
        backupCode: String,
        publisher: String,
        cover: String? = null,
        size: Long? = null,
        updated: Long? = null,
    ): Any {
        val copyMethod = book.javaClass.methods.firstOrNull {
            it.name == "copy" && it.parameterTypes.size == 24
        } ?: book.javaClass.methods.first {
            it.name == "copy" && it.parameterTypes.size == 23
        }
        copyMethod.isAccessible = true
        val args = mutableListOf<Any?>(
            book.callLong("getId"),
            book.callString("getUuid"),
            book.callLong("getUid"),
            book.callString("getTitle"),
            book.callString("getSubtitle"),
            book.callString("getAuthor"),
            cover?.takeIf { it.isNotBlank() } ?: book.callString("getCover"),
            size?.takeIf { it > 0L } ?: book.callLong("getSize"),
            book.callString("getUri"),
            book.callString("getGroup"),
            book.callLong("getCreated"),
            book.callInt("getCfiVersion"),
        )
        if (copyMethod.parameterTypes.size == 24) {
            args.add(book.callInt("getEmbeddedFonts"))
        }
        args.addAll(
            listOf(
                book.callString("getEpubcfi"),
                book.callString("getChapter"),
                book.callFloat("getProgress"),
                book.callLong("getTotal"),
                book.callLong("getFinished"),
                updated?.takeIf { it > 0L } ?: book.callLong("getUpdated"),
                book.callLong("getCloudId"),
                backupType,
                backupId,
                backupCode,
                publisher,
            ),
        )
        return copyMethod.invoke(book, *args.toTypedArray())
    }

    private fun copyCloudBookWithType(book: Any, type: Int): Any {
        val copyMethod = book.javaClass.methods.first {
            it.name == "copy" && it.parameterTypes.size == 10
        }.apply { isAccessible = true }
        return copyMethod.invoke(
            book,
            book.callString("getId"),
            book.callString("getName"),
            type,
            book.callString("getUrl"),
            book.callLong("getSize"),
            book.callLong("getUpdatedAt"),
            book.callString("getPath"),
            book.callString("getHash"),
            book.invokeNoArg("getLocal") ?: emptyFlow(),
            book.invokeNoArg("getWorkers") ?: emptyFlow(),
        )
    }

    private fun updateWebDavBackupViewModels(bookId: Long, book: Any, backup: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                updateWebDavBackupViewModels(bookId, book, backup)
            }
            return
        }
        val viewModels = synchronized(webDavBackupViewModels) {
            webDavBackupViewModels.mapNotNull { it.get() }.also {
                webDavBackupViewModels.removeAll { ref -> ref.get() == null }
            }
        }
        if (viewModels.isEmpty()) {
            logWebDav("backup ui state update skipped bookId=$bookId no active viewModel")
            return
        }
        viewModels.forEach { viewModel ->
            runCatching {
                val vmBookId = (fieldValue(viewModel, "bookId") as? Number)?.toLong()
                    ?: fieldValue(viewModel, "bookId")?.toString()?.toLongOrNull()
                    ?: return@runCatching
                if (vmBookId != bookId) return@runCatching
                val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                    it.name == "updateUiState" && it.parameterTypes.size == 1
                } ?: viewModel.javaClass.methods.first {
                    it.name == "updateUiState" && it.parameterTypes.size == 1
                }
                updateMethod.apply { isAccessible = true }.invoke(
                    viewModel,
                    functionProxy("WebDavBackupUiState", FUNCTION1_CLASS) { args ->
                        val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                        state.javaClass.methods.first {
                            it.name == "copy" && it.parameterTypes.size == 3
                        }.apply { isAccessible = true }.invoke(state, book, backup, false)
                    },
                )
                logWebDav("backup ui state updated bookId=$bookId")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to update WebDAV backup ui state: ${it.stackTraceToString()}")
            }
        }
    }

    private fun refreshWebDavBackupViewModelsLater(bookId: Long, book: Any, backup: Any) {
        Handler(Looper.getMainLooper()).postDelayed({
            updateWebDavBackupViewModels(bookId, book, backup)
            logWebDav("backup ui state delayed refresh bookId=$bookId")
        }, 800L)
    }

    private fun zipBookDirectory(book: Any, output: OutputStream) {
        val root = bookDirectory(book)
        check(root.isDirectory) { "Local book data not found" }
        ZipOutputStream(output.buffered()).use { zip ->
            val mimetype = File(root, "mimetype")
            if (mimetype.isFile) {
                addStoredFile(zip, mimetype, "mimetype")
            }
            root.walkTopDown()
                .filter { it.isFile && it != mimetype }
                .forEach { file ->
                    addDeflatedFile(zip, file, root.toPath().relativize(file.toPath()).toString().replace('\\', '/'))
                }
        }
    }

    private fun addStoredFile(zip: ZipOutputStream, file: File, name: String) {
        val bytes = file.readBytes()
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addDeflatedFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun bookDirectory(book: Any): File {
        val context = currentContext() ?: error("Android context not available")
        val uid = book.callLong("getUid")
        val uuid = book.callString("getUuid")
        return File(context.filesDir, "$uid/books/$uuid")
    }

    private fun exportFileName(book: Any): String =
        "${book.callString("getTitle").safeWebDavFileName()}.epub"

    private fun hookCloudStorageWebDavStrings() {
        runCatching {
            cls(STRING_RESOURCES_KT_CLASS).declaredMethods.filter {
                it.name == STRING_RESOURCE_METHOD && it.returnType == String::class.java
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        when {
                            (localLibraryDefaultFolderDepth.get() ?: 0) > 0 -> {
                                val index = localLibraryDefaultFolderStringIndex.get() ?: 0
                                localLibraryDefaultFolderStringIndex.set(index + 1)
                                param.result = if (index == 0) LOCAL_LIBRARY_FOLDER_TITLE else ""
                            }
                            (localLibraryCloudTitleDepth.get() ?: 0) > 0 -> param.result = LOCAL_LIBRARY_TITLE
                            (webDavCloudTitleDepth.get() ?: 0) > 0 -> param.result = WEBDAV_TITLE
                            (onlineCompletionCloudTitleDepth.get() ?: 0) > 0 -> {
                                param.result = onlineCompletionCloudTitleText.get().orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
                            }
                            webDavNotAuthTipPending.get() == true -> {
                                webDavNotAuthTipPending.set(false)
                                param.result = WEBDAV_AUTH_TIPS
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args?.getOrNull(0)?.callString("getKey").orEmpty()
                        if ((webDavRunningSyncAuthCardDepth.get() ?: 0) > 0 && key == STRING_KEY_UPLOAD_TO_115) {
                            param.result = "上传到 $WEBDAV_TITLE"
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV cloud storage string hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage strings: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudStorageWebDavTitle() {
        runCatching {
            val method = cls(CLOUD_STORAGE_SCREEN_CLASS).declaredMethods.first {
                it.name == CLOUD_STORAGE_BAR_METHOD && it.parameterTypes.size == 5
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> webDavCloudTitleDepth.set((webDavCloudTitleDepth.get() ?: 0) + 1)
                        BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudTitleDepth.set((localLibraryCloudTitleDepth.get() ?: 0) + 1)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> {
                            val next = (webDavCloudTitleDepth.get() ?: 0) - 1
                            if (next <= 0) webDavCloudTitleDepth.remove() else webDavCloudTitleDepth.set(next)
                        }
                        BACKUP_TYPE_LOCAL_LIBRARY -> {
                            val next = (localLibraryCloudTitleDepth.get() ?: 0) - 1
                            if (next <= 0) localLibraryCloudTitleDepth.remove() else localLibraryCloudTitleDepth.set(next)
                        }
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud storage title hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage title: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudStorageWebDavScreenScope() {
        runCatching {
            val method = cls(CLOUD_STORAGE_SCREEN_CLASS).declaredMethods.first {
                it.name == CLOUD_STORAGE_SCREEN_METHOD && it.parameterTypes.size == 4
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> webDavCloudScreenDepth.set((webDavCloudScreenDepth.get() ?: 0) + 1)
                        BACKUP_TYPE_LOCAL_LIBRARY -> localLibraryCloudScreenDepth.set((localLibraryCloudScreenDepth.get() ?: 0) + 1)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    when (type) {
                        BACKUP_TYPE_WEBDAV -> {
                            val next = (webDavCloudScreenDepth.get() ?: 0) - 1
                            if (next <= 0) webDavCloudScreenDepth.remove() else webDavCloudScreenDepth.set(next)
                            refreshCloudStorageScreen(type)
                        }
                        BACKUP_TYPE_LOCAL_LIBRARY -> {
                            val next = (localLibraryCloudScreenDepth.get() ?: 0) - 1
                            if (next <= 0) localLibraryCloudScreenDepth.remove() else localLibraryCloudScreenDepth.set(next)
                            refreshCloudStorageScreen(type)
                        }
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud storage screen scope hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage screen scope: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudStorageWebDavAuthTips() {
        runCatching {
            val method = cls(NOT_AUTH_CLASS).declaredMethods.first {
                it.name == NOT_AUTH_METHOD && it.parameterTypes.size == 4
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                        param.result = targetUnit()
                        return
                    }
                    if (type != BACKUP_TYPE_WEBDAV) return
                    param.args?.set(0, BACKUP_TYPE_BAIDU)
                    webDavNotAuthDepth.set((webDavNotAuthDepth.get() ?: 0) + 1)
                    webDavNotAuthTipPending.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (webDavNotAuthDepth.get() ?: 0) - 1
                    if (next <= 0) {
                        webDavNotAuthDepth.remove()
                        webDavNotAuthTipPending.remove()
                    } else {
                        webDavNotAuthDepth.set(next)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud not-auth hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud not-auth: ${it.stackTraceToString()}")
        }
    }

    private fun hookCloudStorageWebDavViewModelState() {
        runCatching {
            val constructor = cls(CLOUD_STORAGE_VIEW_MODEL_CLASS).declaredConstructors.first {
                it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == java.lang.Integer.TYPE
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    if (type != BACKUP_TYPE_WEBDAV && type != BACKUP_TYPE_LOCAL_LIBRARY) return
                    val viewModel = param.thisObject ?: return
                    synchronized(webDavStorageViewModels) {
                        webDavStorageViewModels.removeAll { it.get() == null || it.get() === viewModel }
                        webDavStorageViewModels.add(WeakReference(viewModel))
                    }
                    if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                        updateLocalLibraryStorageTree(viewModel, currentLocalLibraryBrowseDir())
                        refreshLocalLibraryAsync(currentLocalLibraryBrowseDir(), force = true)
                        logWebDav("local library cloud storage viewModel registered")
                    } else {
                        updateWebDavStorageTree(viewModel, currentWebDavBrowseDir())
                        refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                        logWebDav("WebDAV cloud storage viewModel registered")
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud storage state hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud storage state: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavCloudTap() {
        runCatching {
            val method = cls(CLOUD_STORAGE_VIEW_MODEL_CLASS).declaredMethods.first {
                it.name == CLOUD_STORAGE_ON_INTENT_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == CLOUD_STORAGE_UI_EVENT_CLASS
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val viewModel = param.thisObject ?: return
                    val type = viewModel.javaClass.methods.firstOrNull {
                        it.name == "getType" && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true }?.invoke(viewModel) as? Number ?: return
                    val typeInt = type.toInt()

                    val intent = param.args?.getOrNull(0) ?: return
                    if (intent.javaClass.name != CLOUD_STORAGE_UI_EVENT_TAP_CLASS) return
                    val book = intent.javaClass.methods.firstOrNull {
                        it.name == "getBook" && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true }?.invoke(intent) ?: return

                    if (tryHandleRunningCloudDownloadTap(book, typeInt)) {
                        param.result = null
                        return
                    }
                    if (typeInt != BACKUP_TYPE_WEBDAV) return

                    Thread({
                        runCatching {
                            val workerManager = viewModel.javaClass.methods.first {
                                it.name == "getWorkerManager" && it.parameterTypes.isEmpty()
                            }.apply { isAccessible = true }.invoke(viewModel)
                            val enqueueDownload = this@WebDavDriveHook.method(
                                WORKER_MANAGER_CLASS,
                                WORKER_ENQUEUE_DOWNLOAD_METHOD,
                                1,
                            )
                            enqueueDownload.invoke(workerManager, book)
                            logWebDav("tap queued cloud import type=$typeInt path=${cloudPathOf(book)}")
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX cloud tap download failed: ${it.stackTraceToString()}")
                        }
                    }, "ReaMicroWebDavTap").start()
                    param.result = null
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV cloud tap hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV cloud tap: ${it.stackTraceToString()}")
        }
    }

    private fun hookThirdAccountWebDavRoute() {
        runCatching {
            val methods = routeMethods(THIRD_ACCOUNT_ROUTE_METHOD, THIRD_ACCOUNT_ROUTE_METHOD_LEGACY)
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val backStackEntry = param.args?.getOrNull(2) ?: return
                        val route = runCatching { navBackStackEntryToThirdAccount(backStackEntry) }.getOrNull() ?: return
                        val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                            ?.invoke(route) as? Number ?: return
                        val navGraphScope = param.args?.getOrNull(0) ?: return
                        if (type.toInt() == BACKUP_TYPE_LOCAL_LIBRARY) {
                            clearWebDavAccountContext()
                            markLocalLibraryAccountContext(navGraphScope)
                            val composer = param.args?.getOrNull(3) ?: return
                            renderLocalLibraryAccountRoute(navGraphScope, composer)
                            param.result = targetUnit()
                            return
                        }
                        if (type.toInt() != BACKUP_TYPE_WEBDAV) {
                            clearWebDavAccountContext()
                            clearLocalLibraryAccountContext()
                            return
                        }
                        clearLocalLibraryAccountContext()
                        markWebDavAccountContext(navGraphScope)
                        val composer = param.args?.getOrNull(3) ?: return
                        renderWebDavAccountRoute(navGraphScope, composer)
                        param.result = targetUnit()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV third account route hook installed: ${methods.joinToString { it.name }}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV third account route: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavAccountScreenReuse() {
        runCatching {
            val method = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_SCREEN_METHOD, 3)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (webDavAccountNavGraphScope.get() == null) return
                    webDavAccountScreenDepth.set((webDavAccountScreenDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (webDavAccountScreenDepth.get() ?: 0) - 1
                    if (next <= 0) {
                        webDavAccountScreenDepth.remove()
                    } else {
                        webDavAccountScreenDepth.set(next)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV account screen reuse hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account screen reuse: ${it.stackTraceToString()}")
        }
        runCatching {
            val method = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_SCREEN_METHOD, 3)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (localLibraryAccountNavGraphScope.get() == null) return
                    localLibraryAccountScreenDepth.set((localLibraryAccountScreenDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (localLibraryAccountScreenDepth.get() ?: 0) - 1
                    if (next <= 0) {
                        localLibraryAccountScreenDepth.remove()
                    } else {
                        localLibraryAccountScreenDepth.set(next)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX local library account screen reuse hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local library account screen reuse: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavAccountSettingComponents() {
        runCatching {
            val defaultFolder = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
            XposedBridge.hookMethod(defaultFolder, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isWebDavAccountContext()) return
                    val navGraphScope = webDavAccountNavGraphScope.get() ?: webDavAccountNavGraphScopeRef?.get()
                    param.args?.set(0, currentWebDavBackupDir())
                    param.args?.set(1, functionProxy("WebDavAccountDefaultFolder", FUNCTION0_CLASS) {
                        navGraphScope?.let { navigateCloudFolder(it) }
                        targetUnit()
                    })
                }
            })

            val logout = method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_LOGOUT_METHOD, 3)
            XposedBridge.hookMethod(logout, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isWebDavAccountContext()) return
                    val navGraphScope = webDavAccountNavGraphScope.get() ?: webDavAccountNavGraphScopeRef?.get()
                    param.args?.set(0, functionProxy("WebDavAccountLogout", FUNCTION0_CLASS) {
                        clearWebDavLogin()
                        clearWebDavAccountContext()
                        navGraphScope?.let { scope ->
                            if (!navigateHome(scope)) {
                                popBackStack(scope)
                            }
                        }
                        targetUnit()
                    })
                }
            })

            listOf(BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD, BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
                val query = method(BAIDU_ACCOUNT_SCREEN_CLASS, methodName, 4)
                XposedBridge.hookMethod(query, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isWebDavAccountContext()) return
                        val isOrderBy = methodName == BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD
                        param.args?.set(0, if (isOrderBy) currentWebDavOrderBy() else currentWebDavOrderDirection())
                        param.args?.set(1, functionProxy("WebDavAccountQuery", FUNCTION1_CLASS) { args ->
                            val value = args?.getOrNull(0)?.toString().orEmpty()
                            if (isOrderBy) {
                                saveWebDavOrderBy(value)
                            } else {
                                saveWebDavOrderDirection(value)
                            }
                            updateWebDavAccountAuthFlow()
                            refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                            targetUnit()
                        })
                    }
                })
            }
            val y115DefaultFolder = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_DEFAULT_FOLDER_METHOD, 4)
            XposedBridge.hookMethod(y115DefaultFolder, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isLocalLibraryAccountContext()) return
                    param.args?.set(0, localLibraryPickerDir())
                    param.args?.set(1, functionProxy("LocalLibraryDefaultFolder", FUNCTION0_CLASS) {
                        launchLocalLibraryFolderPicker()
                        targetUnit()
                    })
                    localLibraryDefaultFolderDepth.set((localLibraryDefaultFolderDepth.get() ?: 0) + 1)
                    localLibraryDefaultFolderStringIndex.set(0)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val next = (localLibraryDefaultFolderDepth.get() ?: 0) - 1
                    if (next <= 0) {
                        localLibraryDefaultFolderDepth.remove()
                        localLibraryDefaultFolderStringIndex.remove()
                    } else {
                        localLibraryDefaultFolderDepth.set(next)
                    }
                    if (!isLocalLibraryAccountContext()) return
                    val composer = param.args?.getOrNull(2) ?: return
                    renderLocalLibraryFolderRows(composer)
                }
            })
            val y115Logout = method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_LOGOUT_METHOD, 3)
            XposedBridge.hookMethod(y115Logout, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isLocalLibraryAccountContext()) return
                    param.result = targetUnit()
                }
            })
            listOf(Y115_ACCOUNT_QUERY_ORDER_BY_METHOD, Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD).forEach { methodName ->
                val query = method(Y115_ACCOUNT_SCREEN_CLASS, methodName, 4)
                XposedBridge.hookMethod(query, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isLocalLibraryAccountContext()) return
                        val isOrderBy = methodName == Y115_ACCOUNT_QUERY_ORDER_BY_METHOD
                        param.args?.set(0, if (isOrderBy) currentLocalLibraryOrderBy() else currentLocalLibraryOrderDirection())
                        param.args?.set(1, functionProxy("LocalLibraryAccountQuery", FUNCTION1_CLASS) { args ->
                            val value = args?.getOrNull(0)?.toString().orEmpty()
                            if (isOrderBy) {
                                saveLocalLibraryOrderBy(value)
                            } else {
                                saveLocalLibraryOrderDirection(value)
                            }
                            updateLocalLibraryAccountAuthFlow()
                            refreshLocalLibraryAsync(currentLocalLibraryBrowseDir())
                            targetUnit()
                        })
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV account setting component hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account setting components: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavAccountAuthFlow() {
        runCatching {
            val method = method(BAIDU_VIEW_MODEL_CLASS, BAIDU_VIEW_MODEL_GET_AUTH_METHOD, 0)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isWebDavAccountContext()) return
                    param.result = webDavAccountAuthFlow()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV account auth flow hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account auth flow: ${it.stackTraceToString()}")
        }
        runCatching {
            val method = method(Y115_VIEW_MODEL_CLASS, Y115_VIEW_MODEL_GET_AUTH_METHOD, 0)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isLocalLibraryAccountContext()) return
                    param.result = localLibraryAccountAuthFlow()
                }
            })
            XposedBridge.log("$LOG_PREFIX local library account auth flow hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local library account auth flow: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavAccountTopBarTitle() {
        runCatching {
            val method = method(APP_TOP_BAR_CLASS, APP_TOP_BAR_METHOD, 8)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val title = param.args?.getOrNull(0)?.toString().orEmpty()
                    if (isLocalLibraryAccountContext()) {
                        param.args?.set(0, LOCAL_LIBRARY_TITLE)
                        return
                    }
                    if (!isWebDavAccountContext() || !isBaiduTitle(title)) return
                    param.args?.set(0, WEBDAV_TITLE)
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV account top bar title hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account top bar title: ${it.stackTraceToString()}")
        }
    }

    private fun hookWebDavAccountDefaultFolderLambda() {
        runCatching {
            val method = cls(BAIDU_ACCOUNT_SCREEN_CLASS).declaredMethods.first {
                it.name == BAIDU_ACCOUNT_DEFAULT_FOLDER_LAMBDA_METHOD && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val navGraphScope = param.args?.getOrNull(0) ?: return
                    if ((webDavAccountScreenDepth.get() ?: 0) <= 0 && webDavAccountNavGraphScopeRef?.get() !== navGraphScope) return
                    navigateCloudFolder(navGraphScope)
                    param.result = targetUnit()
                }
            })
            XposedBridge.log("$LOG_PREFIX WebDAV account default-folder lambda hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV account default-folder lambda: ${it.stackTraceToString()}")
        }
    }

    private fun hookThirdLoginWebDavRoute() {
        runCatching {
            val methods = routeMethods(THIRD_LOGIN_ROUTE_METHOD, THIRD_LOGIN_ROUTE_METHOD_LEGACY)
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val backStackEntry = param.args?.getOrNull(2) ?: return
                        val route = runCatching { navBackStackEntryToThirdLogin(backStackEntry) }.getOrNull() ?: return
                        val type = route.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterTypes.isEmpty() }
                            ?.invoke(route) as? Number ?: return
                        if (type.toInt() != BACKUP_TYPE_WEBDAV) return
                        val navGraphScope = param.args?.getOrNull(0) ?: return
                        val composer = param.args?.getOrNull(3) ?: return
                        renderWebDavLoginRoute(navGraphScope, composer)
                        param.result = targetUnit()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX WebDAV third login route hook installed: ${methods.joinToString { it.name }}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook WebDAV third login route: ${it.stackTraceToString()}")
        }
    }

    private fun routeMethods(vararg names: String): List<Method> {
        val appClass = cls(APP_KT_CLASS)
        val methods = names.distinct().mapNotNull { name ->
            appClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 5
            }?.apply { isAccessible = true }
        }
        if (methods.isEmpty()) {
            error("No route methods found for ${names.joinToString()}")
        }
        return methods
    }

    private fun navBackStackEntryToThirdLogin(backStackEntry: Any): Any {
        val reflectionClass = cls(KOTLIN_REFLECTION_CLASS)
        val routeClass = cls(ROUTE_THIRD_LOGIN_CLASS)
        val kClass = reflectionClass.getDeclaredMethod("getOrCreateKotlinClass", Class::class.java)
            .apply { isAccessible = true }
            .invoke(null, routeClass)
        return cls(NAV_BACK_STACK_ENTRY_KT_CLASS).declaredMethods.first {
            it.name == NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD && it.parameterTypes.size == 2
        }.apply { isAccessible = true }
            .invoke(null, backStackEntry, kClass)
    }

    private fun navBackStackEntryToThirdAccount(backStackEntry: Any): Any {
        val reflectionClass = cls(KOTLIN_REFLECTION_CLASS)
        val routeClass = cls(ROUTE_THIRD_ACCOUNT_CLASS)
        val kClass = reflectionClass.getDeclaredMethod("getOrCreateKotlinClass", Class::class.java)
            .apply { isAccessible = true }
            .invoke(null, routeClass)
        return cls(NAV_BACK_STACK_ENTRY_KT_CLASS).declaredMethods.first {
            it.name == NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD && it.parameterTypes.size == 2
        }.apply { isAccessible = true }
            .invoke(null, backStackEntry, kClass)
    }

    private fun renderWebDavLoginRoute(navGraphScope: Any, composer: Any) {
        runCatching {
            val factory = functionProxy("WebDavLoginAndroidView", FUNCTION1_CLASS) { args ->
                val context = args?.getOrNull(0) as? Context
                    ?: activityProvider()
                    ?: error("No context for WebDAV login route")
                createWebDavLoginRouteView(context, navGraphScope)
            }
            val modifier = fillMaxSizeModifier()
            cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
                it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
            }.apply { isAccessible = true }.invoke(
                null,
                factory,
                modifier,
                null,
                composer,
                0,
                4,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV login route: ${it.stackTraceToString()}")
        }
    }

    private fun renderWebDavAccountRoute(navGraphScope: Any, composer: Any) {
        markWebDavAccountContext(navGraphScope)
        webDavAccountNavGraphScope.set(navGraphScope)
        webDavAccountScreenDepth.set((webDavAccountScreenDepth.get() ?: 0) + 1)
        try {
            method(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_SCREEN_METHOD, 3).invoke(
                null,
                navGraphScope,
                composer,
                0,
            )
        } catch (throwable: Throwable) {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV account route: ${throwable.stackTraceToString()}")
        } finally {
            val next = (webDavAccountScreenDepth.get() ?: 0) - 1
            if (next <= 0) {
                webDavAccountScreenDepth.remove()
            } else {
                webDavAccountScreenDepth.set(next)
            }
            webDavAccountNavGraphScope.remove()
        }
    }

    private fun renderLocalLibraryAccountRoute(navGraphScope: Any, composer: Any) {
        markLocalLibraryAccountContext(navGraphScope)
        localLibraryAccountNavGraphScope.set(navGraphScope)
        localLibraryAccountScreenDepth.set((localLibraryAccountScreenDepth.get() ?: 0) + 1)
        try {
            method(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_SCREEN_METHOD, 3).invoke(
                null,
                navGraphScope,
                composer,
                0,
            )
        } catch (throwable: Throwable) {
            XposedBridge.log("$LOG_PREFIX failed to render local library account route: ${throwable.stackTraceToString()}")
        } finally {
            val next = (localLibraryAccountScreenDepth.get() ?: 0) - 1
            if (next <= 0) {
                localLibraryAccountScreenDepth.remove()
            } else {
                localLibraryAccountScreenDepth.set(next)
            }
            localLibraryAccountNavGraphScope.remove()
        }
    }

    private fun renderLocalLibraryFolderRows(composer: Any) {
        val roots = localLibraryRoots()
        if (roots.isEmpty()) return
        runCatching {
            val factory = functionProxy("LocalLibraryFolderRowsAndroidView", FUNCTION1_CLASS) { args ->
                val context = args?.getOrNull(0) as? Context
                    ?: activityProvider()
                    ?: error("No context for local library folder rows")
                createLocalLibraryFolderRowsView(context, roots)
            }
            cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
                it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
            }.apply { isAccessible = true }.invoke(
                null,
                factory,
                fillMaxWidthModifier(),
                null,
                composer,
                0,
                4,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local library folder rows: ${it.stackTraceToString()}")
        }
    }

    private fun createLocalLibraryFolderRowsView(context: Context, roots: List<LocalLibraryEntry>): View {
        val primaryColor = context.resolveThemeColor(android.R.attr.textColorPrimary, Color.rgb(35, 35, 35))
        val actionColor = context.resolveThemeColor(android.R.attr.colorAccent, Color.rgb(180, 64, 64))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            translationY = -context.dp(14).toFloat()
            setPadding(context.dp(52), 0, context.dp(16), context.dp(4))
            roots.forEach { root ->
                addView(createLocalLibraryFolderRowView(context, root, primaryColor, actionColor, this))
            }
        }
    }

    private fun createLocalLibraryFolderRowView(
        context: Context,
        entry: LocalLibraryEntry,
        primaryColor: Int,
        actionColor: Int,
        parent: LinearLayout,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(32)
            setPadding(0, 0, 0, 0)
        }
        row.addView(TextView(context).apply {
            text = localLibraryReadablePath(entry)
            setTextColor(primaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = LOCAL_LIBRARY_REMOVE_TEXT
            setTextColor(actionColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minWidth = context.dp(48)
            setPadding(context.dp(12), context.dp(6), 0, context.dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.getChildAt(1).setOnClickListener {
            removeLocalLibraryFolder(entry.treeUri)
            parent.removeView(row)
            context.toast(LOCAL_LIBRARY_REMOVED_TOAST)
        }
        return row
    }

    private fun markWebDavAccountContext(navGraphScope: Any) {
        webDavAccountNavGraphScopeRef = WeakReference(navGraphScope)
        webDavAccountRouteRenderAtMs = System.currentTimeMillis()
    }

    private fun clearWebDavAccountContext() {
        webDavAccountNavGraphScopeRef = null
        webDavAccountRouteRenderAtMs = 0L
    }

    private fun markLocalLibraryAccountContext(navGraphScope: Any) {
        localLibraryAccountNavGraphScopeRef = WeakReference(navGraphScope)
        localLibraryAccountRouteRenderAtMs = System.currentTimeMillis()
    }

    private fun clearLocalLibraryAccountContext() {
        localLibraryAccountNavGraphScopeRef = null
        localLibraryAccountRouteRenderAtMs = 0L
    }

    private fun isWebDavAccountContext(): Boolean =
        (webDavAccountScreenDepth.get() ?: 0) > 0 ||
            webDavAccountNavGraphScope.get() != null ||
            webDavAccountNavGraphScopeRef?.get() != null ||
            (System.currentTimeMillis() - webDavAccountRouteRenderAtMs) in 0..ACCOUNT_CONTEXT_GRACE_MS

    private fun isLocalLibraryAccountContext(): Boolean =
        (localLibraryAccountScreenDepth.get() ?: 0) > 0 ||
            localLibraryAccountNavGraphScope.get() != null ||
            localLibraryAccountNavGraphScopeRef?.get() != null ||
            (System.currentTimeMillis() - localLibraryAccountRouteRenderAtMs) in 0..ACCOUNT_CONTEXT_GRACE_MS

    private fun isBaiduTitle(title: String): Boolean =
        title.contains("百度") || title.contains("Baidu")

    private fun createWebDavLoginRouteView(context: Context, navGraphScope: Any): View {
        val prefs = context.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
        return createWebDavLoginContent(
            context = context,
            prefs = prefs,
            onBack = { popBackStack(navGraphScope) },
            onSaved = {
                popBackStack(navGraphScope)
                Handler(Looper.getMainLooper()).post {
                    refreshWebDavLibraryAsync(currentWebDavBrowseDir())
                    navigateStorage(navGraphScope)
                }
            },
        )
    }

    private fun fillMaxSizeModifier(): Any {
        val modifier = emptyModifier()
        return runCatching {
            cls(SIZE_KT_CLASS).declaredMethods.first {
            it.name == FILL_MAX_SIZE_METHOD && it.parameterTypes.size == 2
            }.apply { isAccessible = true }.invoke(null, modifier, 1f)
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to apply fillMaxSize modifier: ${it.stackTraceToString()}")
            modifier
        }
    }

    private fun fillMaxWidthModifier(): Any {
        val modifier = emptyModifier()
        return runCatching {
            cls(SIZE_KT_CLASS).declaredMethods.first {
                it.name == FILL_MAX_WIDTH_METHOD && it.parameterTypes.size == 2
            }.apply { isAccessible = true }.invoke(null, modifier, 1f)
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to apply fillMaxWidth modifier: ${it.stackTraceToString()}")
            modifier
        }
    }

    private fun startPaddingModifier(startDp: Int): Any {
        val modifier = emptyModifier()
        return runCatching {
            cls(PADDING_KT_CLASS).declaredMethods.first {
                it.name == PADDING_ABSOLUTE_DEFAULT_METHOD && it.parameterTypes.size == 7
            }.apply { isAccessible = true }.invoke(
                null,
                modifier,
                udp(startDp),
                0f,
                0f,
                0f,
                14,
                null,
            )
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to apply start padding modifier: ${it.stackTraceToString()}")
            modifier
        }
    }

    private fun udp(value: Int): Float =
        runCatching {
            cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
                it.name == UDP_METHOD && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
            }.apply { isAccessible = true }.invoke(null, value) as Float
        }.getOrDefault(value.toFloat())

    private fun emptyModifier(): Any {
        val modifierClass = cls(MODIFIER_CLASS)
        return runCatching {
            modifierClass.getField("INSTANCE").apply { isAccessible = true }.get(null)
        }.recoverCatching {
            modifierClass.getField("Companion").apply { isAccessible = true }.get(null)
        }.recoverCatching {
            cls("$MODIFIER_CLASS\$Companion").getDeclaredField("\$\$INSTANCE")
                .apply { isAccessible = true }
                .get(null)
        }.getOrThrow()
    }

    private fun newThirdLoginRoute(): Any =
        cls(ROUTE_THIRD_LOGIN_CLASS).getDeclaredConstructor(java.lang.Integer.TYPE)
            .apply { isAccessible = true }
            .newInstance(BACKUP_TYPE_WEBDAV)

    private fun newStorageRoute(type: Int = BACKUP_TYPE_WEBDAV): Any =
        cls(ROUTE_STORAGE_CLASS).getDeclaredConstructor(java.lang.Integer.TYPE)
            .apply { isAccessible = true }
            .newInstance(type)

    private fun newHomeRoute(): Any =
        cls(ROUTE_HOME_CLASS).getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

    private fun newCloudFolderRoute(path: String? = null, bookId: Long? = null, type: Int = BACKUP_TYPE_WEBDAV): Any =
        cls(ROUTE_CLOUD_FOLDER_CLASS).getDeclaredConstructor(
            java.lang.Integer.TYPE,
            String::class.java,
            java.lang.Long::class.java,
        ).apply { isAccessible = true }.newInstance(
            type,
            path,
            bookId,
        )

    private fun navigateStorage(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
        navGraphScope.javaClass.methods.firstOrNull {
            it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
        }?.apply { isAccessible = true }?.invoke(navGraphScope, newStorageRoute(type), null, null)
    }

    private fun navigateCloudFolder(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
        navGraphScope.javaClass.methods.firstOrNull {
            it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
        }?.apply { isAccessible = true }?.invoke(navGraphScope, newCloudFolderRoute(type = type), null, null)
    }

    private fun navigateHome(navGraphScope: Any): Boolean =
        runCatching {
            navGraphScope.javaClass.methods.first {
                it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
            }.apply { isAccessible = true }.invoke(navGraphScope, newHomeRoute(), null, null)
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to navigate WebDAV logout home: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun popBackStack(navGraphScope: Any) {
        navGraphScope.javaClass.methods.firstOrNull {
            it.name == "popBackStack" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(navGraphScope)
    }

    private fun flowOf(value: Any): Any {
        val method = cls(FLOW_KT_CLASS).methods.first {
            it.name == FLOW_OF_METHOD && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
        }
        return method.invoke(null, arrayOf(value))
    }

    private fun emptyBaiduAuth(): Any =
        cls(AUTH_BAIDU_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }.newInstance(
            "",
            "time",
            "1",
            "",
            0L,
            0L,
            "/",
            "",
        )

    private fun webDavAuth(): Any {
        val credentials = webDavCredentials()
        return cls(AUTH_BAIDU_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }.newInstance(
            credentials?.username.orEmpty(),
            currentWebDavOrderBy(),
            currentWebDavOrderDirection(),
            "webdav",
            0L,
            0L,
            currentWebDavBackupDir(),
            "webdav",
        )
    }

    private fun webDavYun115Auth(): Any {
        val backupDir = currentWebDavBackupDir()
        val dir = newDir(backupDir, webDavDisplayDir(backupDir))
        return cls(AUTH_YUN115_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            List::class.java,
            cls(DIR_CLASS),
        ).apply { isAccessible = true }.newInstance(
            "webdav",
            "user_utime",
            "0",
            WEBDAV_TITLE,
            0L,
            0L,
            listOf(dir),
            dir,
        )
    }

    private fun localLibraryAuth(): Any {
        val dir = localLibraryDisplayDir()
        return cls(AUTH_YUN115_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            List::class.java,
            cls(DIR_CLASS),
        ).apply { isAccessible = true }.newInstance(
            "local-library",
            currentLocalLibraryOrderBy(),
            currentLocalLibraryOrderDirection(),
            LOCAL_LIBRARY_TITLE,
            localLibraryUsedSize(),
            localLibraryUsedSize(),
            listOf(dir),
            dir,
        )
    }

    private fun localLibraryDisplayDir(): Any =
        newDir(LOCAL_LIBRARY_ROOT_PATH, localLibraryFolderSummary())

    private fun localLibraryPickerDir(): Any =
        newDir(LOCAL_LIBRARY_ROOT_PATH, LOCAL_LIBRARY_PICK_FOLDER_TEXT)

    private fun emptyCloudUserInfo(): Any =
        cls(CLOUD_USER_INFO_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.newInstance("", "", "", "", "", null, 0L, 0L)

    private fun webDavCloudUserInfo(): Any {
        val credentials = webDavCredentials()
        return cls(CLOUD_USER_INFO_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.newInstance(
            "webdav",
            credentials?.username?.ifBlank { WEBDAV_TITLE } ?: WEBDAV_TITLE,
            "",
            WEBDAV_TITLE,
            "",
            null,
            0L,
            0L,
        )
    }

    private fun localLibraryCloudUserInfo(): Any =
        cls(CLOUD_USER_INFO_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.newInstance(
            "local-library",
            LOCAL_LIBRARY_TITLE,
            "",
            LOCAL_LIBRARY_TITLE,
            "",
            null,
            localLibraryUsedSize(),
            localLibraryUsedSize(),
        )

    private fun emptyPagingData(): Any {
        val pagingDataClass = cls(PAGING_DATA_CLASS)
        val companion = pagingDataClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        return companion.javaClass.methods.first {
            it.name == PAGING_DATA_EMPTY_METHOD && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(companion)
    }

    private fun pagingDataFrom(items: List<Any>): Any {
        val pagingDataClass = cls(PAGING_DATA_CLASS)
        val companion = pagingDataClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val idle = idleLoadStates()
        return companion.javaClass.methods.firstOrNull {
            it.name == "from" &&
                it.parameterTypes.size == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1].name == LOAD_STATES_CLASS
        }?.apply { isAccessible = true }?.invoke(companion, items, idle)
            ?: companion.javaClass.methods.first {
                it.name == "from" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
            }.apply { isAccessible = true }.invoke(companion, items)
    }

    private fun idleLoadStates(): Any {
        val loadStatesClass = cls(LOAD_STATES_CLASS)
        val companion = runCatching {
            loadStatesClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        }.getOrElse {
            loadStatesClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }
        return companion.javaClass.methods.first {
            it.name == "getIDLE" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(companion)
    }

    private fun emptyFlow(): Any =
        cls(FLOW_KT_CLASS).methods.first {
            it.name == "emptyFlow" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(null)

    private fun webDavResult(block: () -> Any?): Any =
        runCatching { block() }
            .fold(
                onSuccess = { kotlinResultSuccess(it) },
                onFailure = {
                    XposedBridge.log("$LOG_PREFIX WebDAV repository operation failed: ${it.stackTraceToString()}")
                    kotlinResultFailure(it)
                },
            )

    private fun kotlinResultSuccess(value: Any?): Any =
        cls(KOTLIN_RESULT_CLASS).declaredMethods.first {
            it.name.contains("constructor") && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(null, value)

    private fun kotlinResultFailure(throwable: Throwable): Any {
        val failure = cls(KOTLIN_RESULT_KT_CLASS).declaredMethods.first {
            it.name == "createFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(null, throwable)
        return kotlinResultSuccess(failure)
    }

    private fun webDavLibraryFlow(): Any =
        synchronized(webDavLibraryLock) {
            webDavLibraryFlow ?: createMutableStateFlow(emptyPagingData()).also {
                webDavLibraryFlow = it
                refreshWebDavLibraryAsync(currentWebDavBrowseDir())
            }
        }

    private fun localLibraryFlow(): Any =
        synchronized(localLibraryLock) {
            localLibraryFlow ?: createMutableStateFlow(emptyPagingData()).also {
                localLibraryFlow = it
                refreshLocalLibraryAsync(currentLocalLibraryBrowseDir())
            }
        }

    private fun webDavAccountAuthFlow(): Any =
        synchronized(webDavAccountAuthLock) {
            webDavAccountAuthFlow ?: createMutableStateFlow(webDavAuth()).also {
                webDavAccountAuthFlow = it
            }
        }

    private fun localLibraryAccountAuthFlow(): Any =
        synchronized(localLibraryAccountAuthLock) {
            localLibraryAccountAuthFlow ?: createMutableStateFlow(localLibraryAuth()).also {
                localLibraryAccountAuthFlow = it
            }
        }

    private fun createMutableStateFlow(value: Any): Any =
        cls(STATE_FLOW_KT_CLASS).methods.first {
            it.name == "MutableStateFlow" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(null, value)

    private fun setMutableStateFlowValue(flow: Any?, value: Any) {
        flow?.javaClass?.methods?.firstOrNull {
            it.name == "setValue" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(flow, value)
    }

    private fun updateWebDavAccountAuthFlow() {
        val flow = synchronized(webDavAccountAuthLock) { webDavAccountAuthFlow }
        setMutableStateFlowValue(flow, webDavAuth())
    }

    private fun onWebDavLoginSaved() {
        updateWebDavAccountAuthFlow()
        val path = currentWebDavBrowseDir()
        updateWebDavStorageTrees(path)
        refreshWebDavLibraryAsync(path)
    }

    private fun updateLocalLibraryAccountAuthFlow() {
        val flow = synchronized(localLibraryAccountAuthLock) { localLibraryAccountAuthFlow }
        setMutableStateFlowValue(flow, localLibraryAuth())
    }

    private fun refreshWebDavLibrary(path: String = currentWebDavBrowseDir()) {
        val flow = synchronized(webDavLibraryLock) { webDavLibraryFlow } ?: return
        runCatching {
            val normalizedPath = normalizeWebDavPath(path.ifBlank { "/" })
            val items = listWebDav(normalizedPath)
                .filter { it.isDirectory || isSupportedBookFile(it.name) }
                .map {
                    if (it.isDirectory) newCloudFolder(it) else newCloudBook(it)
                }
            logWebDav("refresh library path=$normalizedPath items=${items.size}")
            flow.javaClass.methods.first {
                it.name == "setValue" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(flow, pagingDataFrom(items))
            logWebDav("refresh library setValue done path=$normalizedPath")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to refresh WebDAV library: ${it.stackTraceToString()}")
            runCatching {
                flow.javaClass.methods.first { method ->
                    method.name == "setValue" && method.parameterTypes.size == 1
                }.apply { isAccessible = true }.invoke(flow, emptyPagingData())
            }
        }
    }

    private fun refreshWebDavLibraryAsync(path: String = currentWebDavBrowseDir()) {
        Thread {
            refreshWebDavLibrary(path)
        }.start()
    }

    private fun refreshLocalLibrary(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
        val flow = synchronized(localLibraryLock) { localLibraryFlow } ?: return
        runCatching {
            val normalizedPath = localLibraryPathArg(path)
            if (force) clearLocalLibraryListCache()
            val items = listLocalLibrary(normalizedPath)
                .filter { it.isDirectory || isSupportedBookFile(it.name) }
                .map {
                    if (it.isDirectory) newLocalLibraryCloudFolder(it) else newLocalLibraryCloudBook(it)
                }
            flow.javaClass.methods.first {
                it.name == "setValue" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(flow, pagingDataFrom(items))
            logWebDav("refresh local library path=$normalizedPath items=${items.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to refresh local library: ${it.stackTraceToString()}")
            runCatching {
                flow.javaClass.methods.first { method ->
                    method.name == "setValue" && method.parameterTypes.size == 1
                }.apply { isAccessible = true }.invoke(flow, emptyPagingData())
            }
        }
    }

    private fun refreshLocalLibraryAsync(path: String = currentLocalLibraryBrowseDir(), force: Boolean = false) {
        Thread {
            refreshLocalLibrary(path, force)
        }.start()
    }

    private fun refreshCloudStorageScreen(type: Int) {
        val path = when (type) {
            BACKUP_TYPE_WEBDAV -> currentWebDavBrowseDir()
            BACKUP_TYPE_LOCAL_LIBRARY -> currentLocalLibraryBrowseDir()
            else -> return
        }
        val key = "$type:$path"
        val now = System.currentTimeMillis()
        val last = cloudStorageScreenRefreshAt[key] ?: 0L
        if (now - last < CLOUD_STORAGE_SCREEN_REFRESH_DEBOUNCE_MS) return
        cloudStorageScreenRefreshAt[key] = now
        when (type) {
            BACKUP_TYPE_WEBDAV -> {
                updateWebDavStorageTrees(path)
                refreshWebDavLibraryAsync(path)
            }
            BACKUP_TYPE_LOCAL_LIBRARY -> {
                updateLocalLibraryStorageTrees(path)
                refreshLocalLibraryAsync(path, force = true)
            }
        }
        logWebDav("cloud storage screen refresh scheduled type=$type path=$path")
    }

    private fun searchWebDavBooks(query: String): List<Any> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val queue = java.util.ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val results = mutableListOf<WebDavEntry>()
        queue.add("/")
        while (queue.isNotEmpty() && visited.size < WEBDAV_SEARCH_MAX_DIRS) {
            val dir = normalizeWebDavPath(queue.removeFirst())
            if (!visited.add(dir)) continue
            val entries = runCatching { listWebDav(dir) }
                .onFailure { XposedBridge.log("$LOG_PREFIX WebDAV search skipped $dir: ${it.message}") }
                .getOrDefault(emptyList())
            entries.forEach { entry ->
                when {
                    entry.isDirectory -> queue.add(entry.path)
                    isSupportedBookFile(entry.name) && entry.name.contains(needle, ignoreCase = true) -> results.add(entry)
                }
            }
        }
        logWebDav("search query=$needle scanned=${visited.size} results=${results.size}")
        return results
            .distinctBy { normalizeWebDavPath(it.path) }
            .let(::sortWebDavEntries)
            .map { newCloudBook(it) }
    }

    private fun searchLocalLibraryBooks(query: String): List<Any> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val rootsKey = localLibraryRootsKey()
        val cached = localLibrarySearchIndex?.takeIf {
            it.rootsKey == rootsKey && System.currentTimeMillis() - it.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS
        }
        val entries = cached?.entries ?: buildLocalLibrarySearchIndex(
            rootsKey = rootsKey,
            maxDurationMs = LOCAL_LIBRARY_SEARCH_SYNC_BUDGET_MS,
        ).entries
        ensureLocalLibrarySearchIndexAsync(rootsKey)
        val results = entries.filter { entry ->
            isSupportedBookFile(entry.name) &&
                (
                    entry.name.contains(needle, ignoreCase = true) ||
                        localLibraryReadablePath(entry).contains(needle, ignoreCase = true)
                    )
        }
        logWebDav("local search query=$needle indexed=${entries.size} results=${results.size}")
        return results
            .distinctBy { it.path }
            .let(::sortLocalLibraryEntries)
            .map { newLocalLibraryCloudBook(it) }
    }

    private fun searchOnlineCompletionSources(query: String): List<Any> {
        val context = currentContext() ?: return emptyList()
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val sources = OnlineSourceStore.list(context)
            .filter { source -> prefs.getBoolean(ModuleSettings.onlineSourceKey(source.id), false) }
        logWebDav("online completion search query=$needle sources=${sources.size}")
        onlineCompletionSearchTargets.clear()
        val selectedSources = sources.take(HOME_SEARCH_RESULT_LIMIT)
        return searchOnlineCompletionSourcesConcurrent(selectedSources, needle)
    }

    private fun searchOnlineCompletionSourcesConcurrent(
        sources: List<OnlineSourceEntry>,
        query: String,
    ): List<OnlineSearchGroup> {
        if (sources.isEmpty()) return emptyList()
        val latch = CountDownLatch(sources.size)
        val groups = arrayOfNulls<OnlineSearchGroup>(sources.size)
        sources.forEachIndexed { index, source ->
            Thread({
                try {
                    groups[index] = searchOnlineCompletionSource(source, query)
                } finally {
                    latch.countDown()
                }
            }, "ReaMicroOnlineSourceSearch-${source.id.takeLast(6)}").start()
        }
        latch.await(ONLINE_COMPLETION_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return groups.mapIndexed { index, group ->
            group ?: OnlineSearchGroup(sources[index], query, emptyList(), "搜索超时")
        }
    }

    private fun searchOnlineCompletionSource(source: OnlineSourceEntry, query: String): OnlineSearchGroup {
        if (source.searchUrl.isBlank()) {
            return OnlineSearchGroup(source, query, emptyList(), "源缺少 searchUrl")
        }
        return runCatching {
            val requestUrl = buildOnlineSearchUrl(source, query)
            if (requestUrl.isBlank()) error("暂不支持脚本型 searchUrl")
            val response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
                requestOnlineSearch(source, requestUrl)
            }
            val results = parseOnlineSearchResults(source, query, response)
                .distinctBy { "${it.name}|${it.author}|${it.detailUrl}" }
                .take(ONLINE_COMPLETION_RESULT_LIMIT)
            logWebDav("online completion source ok name=${source.name} results=${results.size} first=${results.firstOrNull()?.name.orEmpty()}")
            OnlineSearchGroup(source, query, results, "")
        }.getOrElse {
            logWebDav("online completion source failed name=${source.name} error=${it.message}")
            OnlineSearchGroup(source, query, emptyList(), it.message.orEmpty().ifBlank { "搜索失败" })
        }
    }

    private fun buildOnlineSearchUrl(source: OnlineSourceEntry, query: String): String {
        val text = source.searchUrl.trim()
        val raw = if (text.startsWith("@js:", ignoreCase = true) || text.startsWith("<js>", ignoreCase = true)) {
            text
        } else {
            text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        if (raw.startsWith("@js:", ignoreCase = true) || raw.startsWith("<js>", ignoreCase = true)) {
            return buildOnlineSearchUrlFromJs(source, raw, query)
        }
        val urlPart = raw.substringBefore(",{").substringBefore(", {").trim()
        val resolved = applyOnlineTemplate(urlPart, null, sourceBaseUrl(source), query, encodeQuery = true)
        return resolveOnlineUrl(sourceBaseUrl(source), resolved)
    }

    private fun buildOnlineSearchUrlFromJs(source: OnlineSourceEntry, raw: String, query: String): String {
        val templates = Regex("""return\s+`([^`]+)`""").findAll(raw).map { it.groupValues[1] }.toList()
        val selected = when {
            templates.isEmpty() -> return ""
            query.all { it.isDigit() } -> templates.first()
            else -> templates.last()
        }
        return resolveOnlineUrl(
            sourceBaseUrl(source),
            applyOnlineTemplate(selected, null, sourceBaseUrl(source), query, encodeQuery = true),
        )
    }

    private fun requestOnlineSearch(source: OnlineSourceEntry, requestUrl: String): OnlineHttpResponse {
        if (requestUrl.startsWith("data:", ignoreCase = true)) {
            return OnlineHttpResponse(requestUrl, onlineDataUrlStringResponse(requestUrl))
        }
        return withOnlineCleartextAllowed(requestUrl) {
            fun execute(authRetried: Boolean): OnlineHttpResponse {
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = onlineConnectTimeoutMillis(source)
                    readTimeout = onlineReadTimeoutMillis(source)
                    setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                    setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,*/*")
                    parseOnlineHeaders(source.header).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                    OnlineSourceAuth.requestHeaders(currentContext(), source).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code in 200..299) return OnlineHttpResponse(connection.url.toString(), body)
                    if ((code == 401 || code == 403) && !authRetried && source.hasLoginConfig) {
                        val login = OnlineSourceAuth.loginWithSavedCredentials(currentContext(), source)
                        logWebDav(
                            "online completion auth retry source=${source.name} code=$code " +
                                "success=${login.success} message=${login.message}",
                        )
                        if (login.success) return execute(authRetried = true)
                    }
                    val authHint = if (code == 401 || code == 403) "：未登录或会员权限不足" else ""
                    error("HTTP $code$authHint")
                } finally {
                    connection.disconnect()
                }
            }
            execute(authRetried = false)
        }
    }

    private fun onlineDataUrlStringResponse(url: String): String {
        val dataPart = url.substringAfter("base64,", "")
            .substringBefore(",{")
            .substringBefore(";")
            .trim()
        if (dataPart.isBlank()) return ""
        val bytes = Base64.decode(dataPart, Base64.DEFAULT)
        return if (url.substringAfter(dataPart, "").contains(""""type"""")) {
            bytes.toLowerHexString()
        } else {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun parseOnlineHeaders(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { key -> json.optString(key, "") }
        }.getOrElse {
            text.lineSequence()
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null else line.take(index).trim() to line.substring(index + 1).trim()
                }
                .toMap()
        }
    }

    private fun parseOnlineSearchResults(
        source: OnlineSourceEntry,
        query: String,
        response: OnlineHttpResponse,
    ): List<OnlineBookSearchResult> {
        val body = response.body.trim()
        if (body.isBlank()) return emptyList()
        return if (body.startsWith("{") || body.startsWith("[")) {
            parseOnlineJsonResults(source, response.url, body)
        } else {
            parseOnlineHtmlResults(source, query, response.url, body)
        }
    }

    private fun parseOnlineJsonResults(
        source: OnlineSourceEntry,
        baseUrl: String,
        body: String,
    ): List<OnlineBookSearchResult> {
        val root = parseOnlineJsonRoot(body) ?: return emptyList()
        parseOnlineJsonResultsByRule(source, baseUrl, root).takeIf { it.isNotEmpty() }?.let { return it }
        val results = mutableListOf<OnlineBookSearchResult>()
        fun visit(value: Any?) {
            if (results.size >= ONLINE_COMPLETION_RESULT_LIMIT) return
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    jsonObjectToOnlineResult(source, baseUrl, value)?.let(results::add)
                    value.keys().asSequence().forEach { key ->
                        val child = value.opt(key)
                        if (child is JSONArray || child is JSONObject) visit(child)
                    }
                }
            }
        }
        visit(root)
        return results
    }

    private fun parseOnlineJsonRoot(body: String): Any? =
        runCatching {
            val text = body.trim()
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }.getOrNull()

    private fun parseOnlineJsonResultsByRule(
        source: OnlineSourceEntry,
        baseUrl: String,
        root: Any,
    ): List<OnlineBookSearchResult> {
        val rule = runCatching { JSONObject(source.ruleSearch) }.getOrNull() ?: return emptyList()
        val nodes = onlineJsonRuleValues(root, rule.optString("bookList", ""))
        if (nodes.isEmpty()) return emptyList()
        return nodes.mapNotNull { node ->
            val name = onlineRuleValue(node, rule.optString("name", ""), baseUrl).cleanOnlineText()
            if (name.isBlank() || name.length > 120) return@mapNotNull null
            val author = onlineRuleValue(node, rule.optString("author", ""), baseUrl).cleanOnlineText()
            val detail = onlineRuleValue(node, rule.optString("bookUrl", ""), baseUrl)
            val cover = onlineRuleValue(node, rule.optString("coverUrl", ""), baseUrl)
            val intro = onlineRuleValue(node, rule.optString("intro", ""), baseUrl).cleanOnlineText()
            val meta = onlineResultMetadata(source, node, rule, baseUrl)
            OnlineBookSearchResult(
                sourceName = source.name,
                name = name,
                author = author,
                coverUrl = resolveOnlineUrl(baseUrl, cover),
                detailUrl = resolveOnlineUrl(baseUrl, detail),
                intro = intro,
                chapterCount = meta.chapterCount,
                status = meta.status,
                wordCount = meta.wordCount,
                updateTime = meta.updateTime,
            )
        }.distinctBy { "${it.name}|${it.author}|${it.detailUrl}" }
            .take(ONLINE_COMPLETION_RESULT_LIMIT)
    }

    private fun jsonObjectToOnlineResult(
        source: OnlineSourceEntry,
        baseUrl: String,
        json: JSONObject,
    ): OnlineBookSearchResult? {
        val name = firstJsonString(json, "bookName", "name", "title", "bookTitle", "novelName").cleanOnlineText()
        if (name.isBlank() || name.length > 120) return null
        val author = firstJsonString(json, "author", "bookAuthor", "writer", "authorName").cleanOnlineText()
        val detail = firstJsonString(json, "bookUrl", "detailUrl", "url", "href", "link")
        val cover = firstJsonString(json, "coverUrl", "cover", "img", "image", "bookCover")
        val intro = firstJsonString(json, "intro", "desc", "description", "bookDesc").cleanOnlineText()
        val meta = onlineResultMetadata(source, json, null, baseUrl)
        return OnlineBookSearchResult(
            sourceName = source.name,
            name = name,
            author = author,
            coverUrl = resolveOnlineUrl(baseUrl, cover),
            detailUrl = resolveOnlineUrl(baseUrl, detail),
            intro = intro,
            chapterCount = meta.chapterCount,
            status = meta.status,
            wordCount = meta.wordCount,
            updateTime = meta.updateTime,
        )
    }

    private fun parseOnlineHtmlResults(
        source: OnlineSourceEntry,
        query: String,
        baseUrl: String,
        html: String,
    ): List<OnlineBookSearchResult> {
        val results = mutableListOf<OnlineBookSearchResult>()
        val anchorRegex = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
        anchorRegex.findAll(html).forEach { match ->
            if (results.size >= ONLINE_COMPLETION_RESULT_LIMIT) return@forEach
            val title = match.groupValues[2].cleanOnlineText()
            if (title.isBlank() || title.length > 120) return@forEach
            if (!title.contains(query, ignoreCase = true) && results.size >= 3) return@forEach
            val href = match.groupValues[1].trim()
            val start = (match.range.first - 240).coerceAtLeast(0)
            val end = (match.range.last + 360).coerceAtMost(html.length)
            val window = html.substring(start, end)
            val author = Regex("""(?is)(?:作者|author)\s*[:：]?\s*</?[^>]*>\s*([^<\s]{1,40})""")
                .find(window)?.groupValues?.getOrNull(1).orEmpty().cleanOnlineText()
            val cover = Regex("""(?is)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
                .find(window)?.groupValues?.getOrNull(1).orEmpty()
            results.add(
                OnlineBookSearchResult(
                    sourceName = source.name,
                    name = title,
                    author = author,
                    coverUrl = resolveOnlineUrl(baseUrl, cover),
                    detailUrl = resolveOnlineUrl(baseUrl, href),
                    intro = "",
                ),
            )
        }
        return results
    }

    private fun firstJsonString(json: JSONObject, vararg names: String): String =
        names.asSequence()
            .map { json.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()

    private fun onlineResultMetadata(
        source: OnlineSourceEntry,
        node: Any?,
        rule: JSONObject?,
        baseUrl: String,
        chapterCountFallback: Int = 0,
    ): OnlineResultMetadata {
        val kind = rule?.optString("kind", "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { onlineRuleValue(node, it, baseUrl) }
            .orEmpty()
        val wordCount = rule?.optString("wordCount", "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { onlineRuleValue(node, it, baseUrl) }
            .orEmpty()
            .ifBlank { onlineFirstJsonString(node, ONLINE_WORD_COUNT_FIELDS) }
        val updateTime = rule?.optString("updateTime", "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { onlineRuleValue(node, it, baseUrl) }
            .orEmpty()
            .ifBlank { onlineFirstJsonString(node, ONLINE_UPDATE_TIME_FIELDS) }
        val chapterCount = onlineFirstJsonInt(node, ONLINE_CHAPTER_COUNT_FIELDS)
            .takeIf { it > 0 }
            ?: chapterCountFallback.coerceAtLeast(0)
        val status = onlineCompletionStatusText(kind, node)
        logOnlineMetadataGaps(source, node, status, wordCount, updateTime, chapterCount)
        return OnlineResultMetadata(
            status = status,
            wordCount = formatOnlineWordCount(wordCount),
            updateTime = formatOnlineUpdateTime(updateTime),
            chapterCount = chapterCount,
        )
    }

    private fun logOnlineMetadataGaps(
        source: OnlineSourceEntry,
        node: Any?,
        status: String,
        wordCount: String,
        updateTime: String,
        chapterCount: Int,
    ) {
        if (status.isNotBlank() && wordCount.isNotBlank() && updateTime.isNotBlank() && chapterCount > 0) return
        val keys = (node as? JSONObject)?.keys()?.asSequence()?.take(24)?.joinToString(",").orEmpty()
        logWebDav(
            "online metadata partial source=${source.name} " +
                "status=${status.isNotBlank()} words=${wordCount.isNotBlank()} " +
                "update=${updateTime.isNotBlank()} chapters=${chapterCount > 0} keys=$keys",
        )
    }

    private fun onlineFirstJsonString(node: Any?, fields: List<String>): String =
        fields.asSequence()
            .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
            .map { onlineJsonString(node, it).cleanOnlineText() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()

    private fun onlineFirstJsonInt(node: Any?, fields: List<String>): Int =
        fields.asSequence()
            .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
            .mapNotNull { rule ->
                onlineJsonString(node, rule)
                    .replace(",", "")
                    .trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.toDoubleOrNull()
                    ?.toInt()
            }
            .firstOrNull { it > 0 }
            ?: 0

    private fun onlineCompletionSearchMetaLine(result: OnlineBookSearchResult): String =
        listOfNotNull(
            result.status.ifBlank { null },
            result.wordCount.ifBlank { null },
            result.chapterCount.takeIf { it > 0 }?.let { "${it}章" },
            result.updateTime.ifBlank { null },
        ).joinToString(" / ").ifBlank { result.sourceName.ifBlank { ONLINE_COMPLETION_TITLE } }

    private fun onlineCompletionStatusText(kind: String, node: Any?): String {
        val text = kind.cleanOnlineText()
        statusTextFromHumanText(text)?.let { return it }
        onlineFirstJsonString(node, ONLINE_STATUS_TEXT_FIELDS)
            .takeIf { it.isNotBlank() }
            ?.let { statusTextFromHumanText(it) }
            ?.let { return it }
        onlineJsonString(node, "$..creation_status").trim().takeIf { it.isNotBlank() }?.let { value ->
            return when (value.toIntOrNull()) {
                1 -> "连载"
                4 -> "断更"
                else -> "完结"
            }
        }
        onlineJsonString(node, "$..tomato_book_status").trim().takeIf { it.isNotBlank() }?.let { value ->
            return when (value.toIntOrNull()) {
                3 -> "下架"
                else -> ""
            }
        }
        listOf("is_finish", "isFinished", "finished", "complete", "completed").forEach { field ->
            val value = onlineJsonString(node, "$..$field").trim().lowercase(Locale.ROOT)
            if (value.isNotBlank()) {
                return if (value in setOf("true", "1", "yes")) "完结" else "连载"
            }
        }
        val statusSource = text.ifBlank {
            sequenceOf(
                "status",
                "bookStatus",
                "book_status",
                "creation_status",
                "tomato_book_status",
                "is_finish",
                "complete",
                "finished",
            ).map { onlineJsonString(node, it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
        val normalized = statusSource.lowercase(Locale.ROOT)
        return when {
            statusSource.contains("断更") -> "断更"
            statusSource.contains("下架") -> "下架"
            statusSource.contains("连载") || normalized in setOf("1", "serial", "ongoing", "false") -> "连载"
            statusSource.contains("完结") || statusSource.contains("已完") ||
                normalized in setOf("0", "2", "3", "4", "finish", "finished", "complete", "completed", "true") -> "完结"
            else -> ""
        }
    }

    private fun statusTextFromHumanText(text: String): String? =
        when {
            text.contains("断更") -> "断更"
            text.contains("下架") -> "下架"
            text.contains("连载") -> "连载"
            text.contains("完结") || text.contains("已完") -> "完结"
            else -> null
        }

    private fun formatOnlineWordCount(raw: String): String {
        val text = raw.cleanOnlineText()
        if (text.isBlank()) return ""
        if (text.contains("字")) return text
        val number = text.replace(",", "").trim().toLongOrNull() ?: return text
        return if (number >= 10_000L) {
            val value = number / 10_000.0
            val formatted = if (number % 10_000L == 0L) {
                (number / 10_000L).toString()
            } else {
                String.format(Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')
            }
            "${formatted}万字"
        } else {
            "${number}字"
        }
    }

    private fun formatOnlineUpdateTime(raw: String): String {
        val text = raw.cleanOnlineText()
        if (text.isBlank()) return ""
        val numeric = text.toLongOrNull()
        if (numeric != null && numeric > 0L) {
            val millis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
            return SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(java.util.Date(millis))
        }
        val normalized = text.replace('T', ' ').replace(Regex("""\.\d+Z?$"""), "").trim()
        val parsed = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
        ).asSequence().mapNotNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(normalized) }.getOrNull()
        }.firstOrNull()
        return if (parsed != null) {
            SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(parsed)
        } else {
            normalized.take(10).replace('-', ' ').replace('/', ' ')
        }
    }

    private fun onlineRuleValue(node: Any?, rawRule: String, baseUrl: String, query: String? = null): String {
        var rule = rawRule.trim()
        if (rule.isBlank() || node == null || node == JSONObject.NULL) return ""
        if (rule.startsWith("<js>", ignoreCase = true)) return ""
        if (rule.startsWith("@js:", ignoreCase = true)) {
            if (rule.contains("replaceCover", ignoreCase = true)) {
                return replaceFanqieCover(onlineRuleValue(node, "thumb_url", baseUrl))
            }
            return ""
        }
        rule = rule.substringBefore("\n<js>", rule).substringBefore("\n@js:", rule).trim()
        if (rule.contains("{{") && rule.contains("}}")) {
            return applyOnlineTemplate(rule, node, baseUrl, query)
        }
        val parts = rule.split("##", limit = 3)
        val selector = parts.firstOrNull().orEmpty().trim()
        val value = selector.split("||")
            .asSequence()
            .map { candidate -> onlineJsonString(node, candidate.trim()) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (parts.size >= 2 && parts[1].isNotBlank()) {
            return runCatching {
                value.replace(Regex(parts[1]), parts.getOrNull(2).orEmpty())
            }.getOrDefault(value)
        }
        return value
    }

    private fun applyOnlineTemplate(
        raw: String,
        node: Any?,
        baseUrl: String,
        query: String?,
        page: Int = 1,
        encodeQuery: Boolean = false,
    ): String {
        var text = raw
            .replace("{{(page-1)*10}}", ((page - 1) * 10).toString())
            .replace("{{(page - 1) * 10}}", ((page - 1) * 10).toString())
            .replace("{{page}}", page.toString())
        if (query != null) {
            val key = if (encodeQuery) URLEncoder.encode(query, "UTF-8") else query
            listOf("{{key}}", "{{keyword}}", "{{searchKey}}", "{key}", "{keyword}", "%s").forEach { token ->
                text = text.replace(token, key, ignoreCase = true)
            }
        }
        return Regex("""\{\{([^{}]+)\}\}""").replace(text) { match ->
            val expr = match.groupValues[1].trim()
            when {
                Regex("""^baseUrl\s*\+\s*['"]([^'"]*)['"]$""").matchEntire(expr) != null ->
                    baseUrl.trimEnd('/') + Regex("""^baseUrl\s*\+\s*['"]([^'"]*)['"]$""")
                        .matchEntire(expr)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty()
                expr.startsWith("$") || expr.startsWith(".") -> onlineJsonString(node, expr)
                expr.equals("page", ignoreCase = true) -> page.toString()
                expr.equals("key", ignoreCase = true) || expr.equals("keyword", ignoreCase = true) ->
                    query?.let { if (encodeQuery) URLEncoder.encode(it, "UTF-8") else it }.orEmpty()
                else -> ""
            }
        }
    }

    private fun onlineJsonString(node: Any?, rule: String): String =
        onlineJsonValues(node, rule).firstOrNull()?.let(::onlineJsonPrimitive).orEmpty()

    private fun onlineJsonInt(node: Any?, vararg rules: String): Int =
        rules.asSequence()
            .map { onlineJsonString(node, it).trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            ?.toDoubleOrNull()
            ?.toInt()
            ?: 0

    private fun onlineJsonRuleValues(node: Any?, rule: String): List<Any?> {
        if (node == null || rule.isBlank()) return emptyList()
        onlineJsonCandidateRoots(node).forEach { candidate ->
            val values = onlineJsonValues(candidate, rule).flatMap(::onlineJsonRuleItems)
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    private fun onlineJsonRuleItems(value: Any?): List<Any?> =
        when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            else -> listOfNotNull(value).filter { it != JSONObject.NULL }
        }

    private fun onlineJsonCandidateRoots(node: Any?): List<Any?> {
        val roots = linkedSetOf<Any?>()
        fun add(value: Any?) {
            if (value != null && value != JSONObject.NULL) roots.add(value)
        }
        add(node)
        if (node is JSONObject) {
            listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
                add(node.opt(key))
            }
            (node.opt("data") as? JSONObject)?.let { data ->
                listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
                    add(data.opt(key))
                }
            }
        }
        return roots.toList()
    }

    private fun onlineJsonValues(node: Any?, rawRule: String): List<Any?> {
        val rule = rawRule.trim()
        if (node == null || rule.isBlank()) return emptyList()
        return rule.split("||")
            .asSequence()
            .map { onlineJsonValuesSingle(node, it.trim()) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun onlineJsonValuesSingle(node: Any?, rawRule: String): List<Any?> {
        if (node == null || rawRule.isBlank()) return emptyList()
        if (rawRule.startsWith("$..")) {
            val name = rawRule.removePrefix("$..").substringBefore('.').substringBefore('[')
            return onlineJsonRecursiveValues(node, name)
        }
        var path = rawRule
        if (path.startsWith("$.")) path = path.drop(2)
        else if (path.startsWith(".")) path = path.drop(1)
        else if (path == "$") return listOf(node)
        if (path.isBlank()) return listOf(node)
        var current: List<Any?> = listOf(node)
        path.split('.').filter { it.isNotBlank() }.forEach { token ->
            current = current.flatMap { value -> onlineJsonStep(value, token) }
            if (current.isEmpty()) return emptyList()
        }
        return current.filter { it != null && it != JSONObject.NULL }
    }

    private fun onlineJsonStep(value: Any?, token: String): List<Any?> {
        if (value == null || value == JSONObject.NULL) return emptyList()
        if (token == "*" || token == "*[*]") {
            val children = when (value) {
                is JSONObject -> value.keys().asSequence().map { value.opt(it) }.toList()
                is JSONArray -> (0 until value.length()).map { value.opt(it) }
                else -> emptyList()
            }
            return if (token.endsWith("[*]")) children.flatMap { onlineJsonArrayItems(it) } else children
        }
        val match = Regex("""^([^\[]+)(?:\[(\d+|\*)])?$""").matchEntire(token) ?: return emptyList()
        val name = match.groupValues[1]
        val index = match.groupValues.getOrNull(2).orEmpty()
        val values = when (value) {
            is JSONObject -> listOf(value.opt(name))
            is JSONArray -> (0 until value.length()).mapNotNull { idx ->
                (value.opt(idx) as? JSONObject)?.opt(name)
            }
            else -> emptyList()
        }
        return when (index) {
            "" -> values
            "*" -> values.flatMap { onlineJsonArrayItems(it) }
            else -> values.mapNotNull { arrayValue ->
                (arrayValue as? JSONArray)?.opt(index.toIntOrNull() ?: return@mapNotNull null)
            }
        }
    }

    private fun onlineJsonArrayItems(value: Any?): List<Any?> =
        when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is JSONObject -> value.keys().asSequence().map { value.opt(it) }.toList()
            null, JSONObject.NULL -> emptyList()
            else -> listOf(value)
        }

    private fun onlineJsonRecursiveValues(value: Any?, name: String): List<Any?> {
        val results = mutableListOf<Any?>()
        fun visit(item: Any?) {
            when (item) {
                is JSONObject -> {
                    if (item.has(name)) results.add(item.opt(name))
                    item.keys().asSequence().forEach { visit(item.opt(it)) }
                }
                is JSONArray -> for (index in 0 until item.length()) visit(item.opt(index))
            }
        }
        visit(value)
        return results.filter { it != null && it != JSONObject.NULL }
    }

    private fun onlineJsonPrimitive(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> value.toString()
        }.trim()

    private fun replaceFanqieCover(raw: String): String {
        val clean = raw.trim().substringBefore('~').substringBefore('?').trim()
        if (clean.isBlank()) return ""
        val path = runCatching {
            val normalized = if (clean.startsWith("//")) "https:$clean" else clean
            if (normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true)
            ) {
                URI(normalized).rawPath.orEmpty().trimStart('/')
            } else {
                normalized.trimStart('/')
            }
        }.getOrDefault(clean.trimStart('/'))
            .removePrefix("origin/")
            .removePrefix("/origin/")
            .trimStart('/')
        val imagePath = when {
            path.isBlank() -> return ""
            path.startsWith("novel-pic/", ignoreCase = true) -> path
            path.startsWith("novel-images/", ignoreCase = true) -> path
            path.startsWith("novel-static/", ignoreCase = true) -> path
            path.contains('/') -> path
            else -> "novel-pic/$path"
        }
        return "https://p6-novel.byteimg.com/origin/$imagePath"
    }

    private fun sourceBaseUrl(source: OnlineSourceEntry): String =
        Regex("""https?://[^\s#]+""").find(source.sourceUrl)?.value.orEmpty()

    private fun resolveOnlineUrl(baseUrl: String, value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        return runCatching {
            when {
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
                baseUrl.isBlank() -> raw
                else -> URL(URL(baseUrl), raw).toString()
            }
        }.getOrDefault(raw)
    }

    private fun String.cleanOnlineText(): String =
        replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun buildLocalLibrarySearchIndex(
        rootsKey: String,
        maxDurationMs: Long,
    ): LocalLibrarySearchIndex {
        val deadline = System.currentTimeMillis() + maxDurationMs
        val queue = java.util.ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val indexed = mutableListOf<LocalLibraryEntry>()
        localLibraryRoots().forEach { queue.add(it.path) }
        while (queue.isNotEmpty() && visited.size < LOCAL_LIBRARY_SEARCH_MAX_DIRS) {
            if (System.currentTimeMillis() >= deadline) break
            val path = queue.removeFirst()
            if (!visited.add(path)) continue
            val entries = runCatching { listLocalLibrary(path) }
                .onFailure { XposedBridge.log("$LOG_PREFIX local library index skipped $path: ${it.message}") }
                .getOrDefault(emptyList())
            entries.forEach { entry ->
                when {
                    entry.isDirectory -> queue.add(entry.path)
                    isSupportedBookFile(entry.name) -> indexed.add(entry)
                }
            }
        }
        val index = LocalLibrarySearchIndex(
            rootsKey = rootsKey,
            entries = indexed.distinctBy { it.path },
            createdAtMs = System.currentTimeMillis(),
            complete = queue.isEmpty(),
        )
        synchronized(localLibrarySearchIndexLock) {
            val current = localLibrarySearchIndex
            if (current == null || current.rootsKey != rootsKey || index.entries.size >= current.entries.size || index.complete) {
                localLibrarySearchIndex = index
            }
        }
        logWebDav("local index roots=${localLibraryRoots().size} scanned=${visited.size} entries=${index.entries.size} complete=${index.complete}")
        return index
    }

    private fun ensureLocalLibrarySearchIndexAsync(rootsKey: String = localLibraryRootsKey()) {
        val current = localLibrarySearchIndex
        if (current != null &&
            current.rootsKey == rootsKey &&
            current.complete &&
            System.currentTimeMillis() - current.createdAtMs <= LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS
        ) return
        synchronized(localLibrarySearchIndexLock) {
            if (localLibrarySearchIndexBuilding) return
            localLibrarySearchIndexBuilding = true
        }
        Thread({
            runCatching {
                buildLocalLibrarySearchIndex(
                    rootsKey = rootsKey,
                    maxDurationMs = LOCAL_LIBRARY_SEARCH_BACKGROUND_BUDGET_MS,
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX local library background index failed: ${it.stackTraceToString()}")
            }
            synchronized(localLibrarySearchIndexLock) {
                localLibrarySearchIndexBuilding = false
            }
        }, "ReaMicroLocalLibraryIndex").start()
    }

    private fun updateHomeWebDavSearchResults(
        viewModel: Any?,
        webDavResults: List<Any>,
        localResults: List<Any>,
        onlineResults: List<Any>? = null,
    ) {
        if (viewModel == null) return
        rememberHomeViewModelDependencies(viewModel)
        runCatching {
            val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            } ?: viewModel.javaClass.methods.first {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            }
            updateMethod.apply { isAccessible = true }.invoke(
                viewModel,
                functionProxy("WebDavHomeSearchState", FUNCTION1_CLASS) { args ->
                    val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                    runCatching {
                        val current = homeCloudSearchResults(state)
                        val merged = linkedMapOf<Any?, Any?>()
                        current?.forEach { (key, value) -> merged[key] = value }
                        if (webDavResults.isEmpty()) {
                            merged.remove(BACKUP_TYPE_WEBDAV)
                        } else {
                            merged[BACKUP_TYPE_WEBDAV] = webDavResults
                        }
                        if (localResults.isEmpty()) {
                            merged.remove(BACKUP_TYPE_LOCAL_LIBRARY)
                        } else {
                            merged[BACKUP_TYPE_LOCAL_LIBRARY] = localResults
                        }
                        if (onlineResults != null) {
                            if (onlineResults.isEmpty()) {
                                merged.remove(BACKUP_TYPE_ONLINE_COMPLETION)
                            } else {
                                merged[BACKUP_TYPE_ONLINE_COMPLETION] = onlineResults
                            }
                        }
                        copyHomeUiStateWithCloudResults(state, merged)
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX failed to copy WebDAV home search state: ${it.stackTraceToString()}")
                    }.getOrDefault(state)
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update WebDAV home search state: ${it.stackTraceToString()}")
        }
    }

    private fun updateHomeOnlineCompletionSearchResults(viewModel: Any?, onlineResults: List<Any>) {
        if (viewModel == null) return
        rememberHomeViewModelDependencies(viewModel)
        runCatching {
            val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            } ?: viewModel.javaClass.methods.first {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            }
            updateMethod.apply { isAccessible = true }.invoke(
                viewModel,
                functionProxy("OnlineCompletionHomeSearchState", FUNCTION1_CLASS) { args ->
                    val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                    runCatching {
                        val current = homeCloudSearchResults(state)
                        val merged = linkedMapOf<Any?, Any?>()
                        current?.forEach { (key, value) -> merged[key] = value }
                        if (onlineResults.isEmpty()) {
                            merged.remove(BACKUP_TYPE_ONLINE_COMPLETION)
                        } else {
                            merged[BACKUP_TYPE_ONLINE_COMPLETION] = onlineResults
                        }
                        copyHomeUiStateWithCloudResults(state, merged)
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX failed to copy online completion search state: ${it.stackTraceToString()}")
                    }.getOrDefault(state)
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update online completion search state: ${it.stackTraceToString()}")
        }
    }

    private fun homeCloudSearchResults(state: Any): Map<*, *>? =
        state.javaClass.methods.firstOrNull {
            it.name == "getCloudSearchResults" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(state) as? Map<*, *>

    private fun copyHomeUiStateWithCloudResults(state: Any, cloudResults: Map<Any?, Any?>): Any {
        val currentCloudResults = homeCloudSearchResults(state)
        val stateClass = state.javaClass
        val copyMethods = stateClass.methods
            .filter { it.name == "copy" && it.returnType == stateClass && it.parameterTypes.isNotEmpty() }
            .sortedByDescending { it.parameterTypes.size }
        val failures = mutableListOf<String>()
        copyMethods.forEach { copyMethod ->
            runCatching {
                copyMethod.isAccessible = true
                val args = Array<Any?>(copyMethod.parameterTypes.size) { index ->
                    state.invokeNoArg("component${index + 1}")
                }
                val cloudIndex = args.indexOfFirst { it === currentCloudResults }
                    .takeIf { it >= 0 }
                    ?: homeCloudSearchResultsComponentIndex(state, copyMethod.parameterTypes.size)
                    ?: copyMethod.parameterTypes.mapIndexedNotNull { index, type ->
                        index.takeIf { Map::class.java.isAssignableFrom(type) }
                    }.singleOrNull()
                    ?: throw IllegalStateException("cloudSearchResults parameter not found for ${copyMethod.parameterTypes.size}-arg HomeUiState.copy")
                args[cloudIndex] = cloudResults
                val copied = copyMethod.invoke(state, *args)
                if (stateClass.isInstance(copied)) return copied
                failures += "${copyMethod.parameterTypes.size}: returned ${copied?.javaClass?.name}"
            }.onFailure {
                failures += "${copyMethod.parameterTypes.size}: ${it.javaClass.name}: ${it.message}"
            }
        }
        XposedBridge.log("$LOG_PREFIX HomeUiState dynamic copy failed: ${failures.joinToString(" | ")}")
        return copyHomeUiStateWithCloudResultsLegacy(state, cloudResults)
    }

    private fun homeCloudSearchResultsComponentIndex(state: Any, parameterCount: Int): Int? {
        val current = homeCloudSearchResults(state) ?: return null
        for (index in 0 until parameterCount) {
            val component = state.invokeNoArg("component${index + 1}")
            if (component === current) return index
        }
        return null
    }

    private fun copyHomeUiStateWithCloudResultsLegacy(state: Any, cloudResults: Map<Any?, Any?>): Any {
        val copyMethod = state.javaClass.methods.firstOrNull {
            it.name == "copy" && it.parameterTypes.size == 13
        }?.apply { isAccessible = true } ?: return state
        return copyMethod.invoke(
            state,
            state.invokeNoArg("getModel"),
            state.invokeNoArg("getUser"),
            state.invokeNoArg("getSearching"),
            state.invokeNoArg("getShowLibrary"),
            state.invokeNoArg("getGroups"),
            state.invokeNoArg("getHasBooks"),
            state.invokeNoArg("getLocalSearchResults"),
            cloudResults,
            state.invokeNoArg("getPasswordRequest"),
            state.invokeNoArg("getOldBooksToMigrate"),
            state.invokeNoArg("getMigrationStatus"),
            state.invokeNoArg("getMigrationResult"),
            state.invokeNoArg("isMigrating"),
        )
    }

    private fun addHomeWebDavSearchSection(lazyListScope: Any, type: Int, title: String, results: List<*>, intentReceiver: Any) {
        runCatching {
            val itemMethod = lazyListScope.javaClass.methods.firstOrNull {
                it.name == "item" && it.parameterTypes.size == 3
            } ?: lazyListScope.javaClass.declaredMethods.first {
                it.name == "item" && it.parameterTypes.size == 3
            }
            itemMethod.isAccessible = true
            itemMethod.invoke(
                lazyListScope,
                "cloud-search-$type-${title.ifBlank { type.toString() }.hashCode()}",
                type,
                functionProxy("WebDavHomeSearchItem", FUNCTION3_CLASS) { args ->
                    val item = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                    val composer = args.getOrNull(1) ?: return@functionProxy targetUnit()
                    val render = {
                        method(HOME_SEARCH_BAR_CLASS, HOME_CLOUD_RESULT_LIST_METHOD, 6).invoke(
                            null,
                            item,
                            type,
                            results,
                            functionProxy("WebDavHomeSearchTap", FUNCTION1_CLASS) { tapArgs ->
                                tapArgs?.getOrNull(0)?.let { book ->
                                    if (isOnlineCompletionPath(cloudPathOf(book))) {
                                        handleOnlineCompletionSearchTap(book)
                                    } else if (!tryHandleRunningCloudDownloadTap(book, type)) {
                                        method(HOME_SEARCH_BAR_CLASS, HOME_SEARCH_TAP_METHOD, 2).invoke(
                                            null,
                                            intentReceiver,
                                            book,
                                        )
                                    }
                                }
                                targetUnit()
                            },
                            composer,
                            0,
                        )
                    }
                    if (type == BACKUP_TYPE_LOCAL_LIBRARY) {
                        pushLocalLibraryIcon()
                        try {
                            render()
                        } finally {
                            popLocalLibraryIcon()
                        }
                    } else if (isOnlineCompletionRenderType(type)) {
                        pushOnlineCompletionCloudTitle(onlineCompletionTitleForType(type, title))
                        try {
                            withWebDavIcon { render() }
                        } finally {
                            popOnlineCompletionCloudTitle()
                        }
                    } else {
                        withWebDavIcon { render() }
                    }
                    targetUnit()
                },
            )
            val firstBook = results.firstOrNull()
            val firstType = firstBook?.callInt("getType") ?: 0
            val firstId = firstBook?.callString("getId").orEmpty()
            val firstPath = cloudPathOf(firstBook)
            logWebDav(
                "home search section rendered type=$type results=${results.size} " +
                    "firstType=$firstType firstId=$firstId firstPath=$firstPath",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render WebDAV home search section: ${it.stackTraceToString()}")
        }
    }

    private fun newCloudFolder(entry: WebDavEntry): Any =
        cls(CLOUD_FOLDER_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.newInstance(
            entry.name,
            normalizeWebDavPath(entry.path),
            false,
            entry.updatedAt,
        )

    private fun newDir(id: String, name: String): Any =
        cls(DIR_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }.newInstance(id, name)

    private fun webDavDirTree(path: String): List<Any> {
        val normalized = normalizeWebDavPath(path.ifBlank { "/" })
        val dirs = mutableListOf<Any>(newDir("/", ROOT_DIR_NAME))
        if (normalized == "/") return dirs
        val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
        var current = ""
        segments.forEach { segment ->
            current = normalizeWebDavPath("$current/$segment")
            dirs.add(newDir(current, segment))
        }
        return dirs
    }

    private fun localLibraryDirTree(path: String): List<Any> {
        val normalized = localLibraryPathArg(path)
        val dirs = mutableListOf<Any>(newDir(LOCAL_LIBRARY_ROOT_PATH, ROOT_DIR_NAME))
        if (normalized == LOCAL_LIBRARY_ROOT_PATH) return dirs
        val roots = localLibraryRoots()
        val root = roots.firstOrNull { it.path == normalized }
        if (root != null) {
            dirs.add(newDir(root.path, root.name))
            return dirs
        }
        val entry = localLibraryEntry(normalized)
        val parentRoot = roots.firstOrNull { entry?.treeUri == it.treeUri }
        parentRoot?.let { dirs.add(newDir(it.path, it.name)) }
        entry?.let { dirs.add(newDir(it.path, it.name)) }
        return dirs.distinctBy { dir ->
            dir.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(dir)
                ?.toString()
                .orEmpty()
        }
    }

    private fun updateWebDavStorageTrees(path: String) {
        val viewModels = synchronized(webDavStorageViewModels) {
            webDavStorageViewModels.mapNotNull { it.get() }.also {
                webDavStorageViewModels.removeAll { ref -> ref.get() == null }
            }
        }
        viewModels.forEach { updateWebDavStorageTree(it, path) }
    }

    private fun updateWebDavStorageTree(viewModel: Any, path: String) {
        runCatching {
            val tree = webDavDirTree(path)
            val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            } ?: viewModel.javaClass.methods.first {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            }
            updateMethod.apply { isAccessible = true }.invoke(
                viewModel,
                functionProxy("WebDavStorageTreeState", FUNCTION1_CLASS) { args ->
                    val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                    cls(CLOUD_STORAGE_UI_STATE_CLASS).declaredMethods.first {
                        it.name == "copy" && it.parameterTypes.size == 2
                    }.apply { isAccessible = true }.invoke(
                        state,
                        state.javaClass.methods.first { it.name == "getUserInfo" && it.parameterTypes.isEmpty() }
                            .apply { isAccessible = true }
                            .invoke(state),
                        tree,
                    )
                },
            )
            logWebDav("update storage tree path=${normalizeWebDavPath(path)} size=${tree.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update WebDAV storage tree: ${it.stackTraceToString()}")
        }
    }

    private fun updateLocalLibraryStorageTrees(path: String) {
        val viewModels = synchronized(webDavStorageViewModels) {
            webDavStorageViewModels.mapNotNull { it.get() }.also {
                webDavStorageViewModels.removeAll { ref -> ref.get() == null }
            }
        }
        viewModels.forEach { updateLocalLibraryStorageTree(it, path) }
    }

    private fun updateLocalLibraryStorageTree(viewModel: Any, path: String) {
        runCatching {
            val tree = localLibraryDirTree(path)
            val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            } ?: viewModel.javaClass.methods.first {
                it.name == "updateUiState" && it.parameterTypes.size == 1
            }
            updateMethod.apply { isAccessible = true }.invoke(
                viewModel,
                functionProxy("LocalLibraryStorageTreeState", FUNCTION1_CLASS) { args ->
                    val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                    cls(CLOUD_STORAGE_UI_STATE_CLASS).declaredMethods.first {
                        it.name == "copy" && it.parameterTypes.size == 2
                    }.apply { isAccessible = true }.invoke(
                        state,
                        state.javaClass.methods.first { it.name == "getUserInfo" && it.parameterTypes.isEmpty() }
                            .apply { isAccessible = true }
                            .invoke(state),
                        tree,
                    )
                },
            )
            logWebDav("update local library tree path=$path size=${tree.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update local library storage tree: ${it.stackTraceToString()}")
        }
    }

    private fun newCloudBook(entry: WebDavEntry): Any {
        val path = normalizeWebDavPath(entry.path)
        val url = webDavBookUrl(path)
        return cls(CLOUD_BOOK_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            java.lang.Integer.TYPE,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
            cls(FLOW_CLASS),
            cls(FLOW_CLASS),
        ).apply { isAccessible = true }.newInstance(
            path,
            entry.name,
            BACKUP_TYPE_WEBDAV,
            url,
            entry.size,
            entry.updatedAt,
            path,
            "",
            webDavLocalBookFlow(url),
            webDavWorkFlow(path),
        )
    }

    private fun newLocalLibraryCloudFolder(entry: LocalLibraryEntry): Any =
        cls(CLOUD_FOLDER_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.newInstance(
            entry.name,
            entry.path,
            false,
            entry.updatedAt,
        )

    private fun newLocalLibraryCloudBook(entry: LocalLibraryEntry): Any {
        val url = localLibraryBookUrl(entry.path)
        return cls(CLOUD_BOOK_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            java.lang.Integer.TYPE,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
            cls(FLOW_CLASS),
            cls(FLOW_CLASS),
        ).apply { isAccessible = true }.newInstance(
            entry.path,
            entry.name,
            BACKUP_TYPE_LOCAL_LIBRARY,
            url,
            entry.size,
            entry.updatedAt,
            entry.path,
            "",
            webDavLocalBookFlow(url),
            webDavWorkFlow(entry.path),
        )
    }

    private fun newOnlineCompletionSourceCloudBook(group: OnlineSearchGroup): Any {
        val encodedQuery = URLEncoder.encode(group.query, "UTF-8")
        val path = "$ONLINE_COMPLETION_SOURCE_PREFIX${group.source.id}?q=$encodedQuery"
        val best = group.results.firstOrNull()
        if (best != null) {
            onlineCompletionSearchTargets[path] = OnlineDownloadTarget(group.source, group.query, best)
        }
        val status = when {
            group.error.isNotBlank() -> group.error
            best == null -> "无结果"
            else -> best.name
        }
        val subtitle = listOfNotNull(
            best?.author?.takeIf { it.isNotBlank() }?.let { "作者 $it" },
            group.source.name.takeIf { it.isNotBlank() },
            if (best != null && group.results.size > 1) "${group.results.size} 条结果" else null,
            if (best == null) group.source.sourceUrl.takeIf { it.isNotBlank() } else null,
        ).joinToString(" · ")
        return newOnlineCompletionCloudBook(
            path = path,
            name = status,
            subtitle = subtitle,
            result = best,
        )
    }

    private fun onlineCompletionGroupTitle(group: OnlineSearchGroup): String =
        "$ONLINE_COMPLETION_TITLE-${group.source.name.ifBlank { "未知来源" }}"

    private fun onlineCompletionRenderTypeForSource(source: OnlineSourceEntry, title: String): Int {
        val sourceId = source.id.ifBlank { source.name }
        val type = onlineCompletionRenderTypesBySource[sourceId] ?: synchronized(onlineCompletionRenderTypesBySource) {
            onlineCompletionRenderTypesBySource[sourceId] ?: run {
                var candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE +
                    ((sourceId.hashCode() and Int.MAX_VALUE) % ONLINE_COMPLETION_RENDER_TYPE_BUCKETS)
                while (true) {
                    val existing = onlineCompletionRenderSourceByType[candidate]
                    if (existing == null || existing == sourceId) {
                        onlineCompletionRenderSourceByType[candidate] = sourceId
                        onlineCompletionRenderTypesBySource[sourceId] = candidate
                        break
                    }
                    candidate += 1
                    if (candidate >= ONLINE_COMPLETION_RENDER_TYPE_BASE + ONLINE_COMPLETION_RENDER_TYPE_BUCKETS) {
                        candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE
                    }
                }
                candidate
            }
        }
        onlineCompletionRenderTitles[type] = title.ifBlank { ONLINE_COMPLETION_TITLE }
        return type
    }

    private fun isOnlineCompletionRenderType(type: Int): Boolean =
        onlineCompletionRenderTitles.containsKey(type)

    private fun onlineCompletionTitleForType(type: Int, fallback: String = ONLINE_COMPLETION_TITLE): String =
        onlineCompletionRenderTitles[type].orEmpty()
            .ifBlank { onlineCompletionCloudTitleText.get().orEmpty() }
            .ifBlank { fallback }
            .ifBlank { ONLINE_COMPLETION_TITLE }

    private fun onlineCompletionGroupVisibleCloudBooks(group: OnlineSearchGroup): List<Any> {
        if (group.results.isEmpty()) return listOf(newOnlineCompletionSourceCloudBook(group))
        return group.results.take(ONLINE_COMPLETION_RESULT_LIMIT).mapIndexed { index, result ->
            newOnlineCompletionResultCloudBook(group.source, group.query, result, index)
        }
    }

    private fun newOnlineCompletionResultCloudBook(
        source: OnlineSourceEntry,
        query: String,
        result: OnlineBookSearchResult,
        index: Int,
    ): Any {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val path = "$ONLINE_COMPLETION_BOOK_PREFIX${source.id}/$index?q=$encodedQuery"
        onlineCompletionSearchTargets[path] = OnlineDownloadTarget(source, query, result)
        return newOnlineCompletionCloudBook(
            path = path,
            name = result.name,
            subtitle = onlineCompletionSearchMetaLine(result),
            result = result,
        )
    }

    private fun newOnlineCompletionCloudBook(
        path: String,
        name: String,
        subtitle: String,
        result: OnlineBookSearchResult? = null,
    ): Any {
        val localFlow = result?.let {
            runCatching { flowOf(newOnlineCompletionLocalBook(path, it)) }
                .onFailure { error ->
                    XposedBridge.log("$LOG_PREFIX online completion synthetic book failed: ${error.stackTraceToString()}")
                }
                .getOrNull()
        } ?: emptyFlow()
        return cls(CLOUD_BOOK_CLASS).getDeclaredConstructor(
            String::class.java,
            String::class.java,
            java.lang.Integer.TYPE,
            String::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            String::class.java,
            cls(FLOW_CLASS),
            cls(FLOW_CLASS),
        ).apply { isAccessible = true }.newInstance(
            path,
            name,
            BACKUP_TYPE_ONLINE_COMPLETION,
            path,
            0L,
            System.currentTimeMillis(),
            path,
            subtitle,
            localFlow,
            webDavWorkFlow(path),
        )
    }

    private fun newOnlineCompletionLocalBook(path: String, result: OnlineBookSearchResult): Any {
        val now = System.currentTimeMillis()
        val bookClass = cls(BOOK_CLASS)
        val constructor = bookClass.declaredConstructors
            .filter { it.parameterTypes.size == 24 || it.parameterTypes.size == 23 }
            .maxByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }
            ?: error("Book constructor not found")
        val args = mutableListOf<Any?>(
            0L,
            UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString(),
            0L,
            result.name,
            "",
            result.author,
            result.coverUrl,
            0L,
            result.detailUrl.ifBlank { path },
            "",
            now,
            0,
        )
        if (constructor.parameterTypes.size == 24) {
            args.add(0)
        }
        args.addAll(
            listOf(
                "",
                "",
                0f,
                0L,
                0L,
                now,
                0L,
                BACKUP_TYPE_ONLINE_COMPLETION,
                path,
                "",
                result.sourceName,
            ),
        )
        return constructor.newInstance(*args.toTypedArray())
    }

    private fun syntheticWebDavBookEntry(path: String): WebDavEntry {
        val normalized = normalizeWebDavPath(path)
        val name = normalized.substringAfterLast('/').ifBlank { WEBDAV_TITLE }
        logWebDav("getInfo fallback synthetic path=$normalized")
        return WebDavEntry(
            name = name,
            path = normalized,
            isDirectory = false,
            size = 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun syntheticLocalLibraryBookEntry(path: String): LocalLibraryEntry =
        LocalLibraryEntry(
            name = path.substringAfterLast(':').decodeLocalPathPart().substringAfterLast('/').ifBlank { LOCAL_LIBRARY_TITLE },
            path = path,
            treeUri = "",
            documentId = "",
            isDirectory = false,
            size = 0L,
            updatedAt = System.currentTimeMillis(),
        )

    private fun webDavBookUrl(path: String): String =
        WEBDAV_SOURCE_PREFIX + normalizeWebDavPath(path)

    private fun localLibraryBookUrl(path: String): String =
        LOCAL_LIBRARY_SOURCE_PREFIX + path

    private fun emptyCloudBook(): Any =
        cls(CLOUD_BOOK_CLASS).getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
            .let { companion ->
                companion.javaClass.methods.first { it.name == "getEmpty" && it.parameterTypes.isEmpty() }
                    .apply { isAccessible = true }
                    .invoke(companion)
            }

    private fun isSupportedBookFile(name: String): Boolean =
        BOOK_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private fun cloudPathOf(value: Any?): String =
        value?.javaClass?.methods?.firstOrNull {
            it.name == "getPath" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(value)?.toString().orEmpty()

    private fun isOnlineCompletionPath(path: String): Boolean =
        path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) || path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)

    private fun isOnlineCompletionLocalBook(book: Any): Boolean =
        isOnlineCompletionPath(bookBackupIdOf(book)) ||
            isOnlineCompletionPath(book.callString("getUri")) ||
            isOnlineCompletionUuid(book.callString("getUuid")) ||
            (bookBackupTypeOf(book) == BACKUP_TYPE_ONLINE_COMPLETION &&
                book.callString("getBackupCode").startsWith("online_"))

    private fun isOnlineCompletionUuid(uuid: String): Boolean =
        uuid.startsWith(ONLINE_COMPLETION_UUID_PREFIX)

    private fun cleanupOnlineCompletionBookPublisherIfNeeded(book: Any) {
        val uuid = book.callString("getUuid")
        if (!isOnlineCompletionUuid(uuid)) return
        val publisher = book.callString("getPublisher")
        if (publisher.isBlank()) return
        if (!onlineCompletionPublisherCleanupIds.add(uuid)) return
        Thread({
            runCatching {
                val latest = findLocalBookByUuid(book.callLong("getUid"), uuid) ?: book
                if (latest.callString("getPublisher").isBlank()) return@runCatching
                val updated = copyBookWithBackupAndPublisher(
                    book = latest,
                    backupType = BACKUP_TYPE_ONLINE_COMPLETION,
                    backupId = bookBackupIdOf(latest).ifBlank { onlineImportedBookBackupIdFromBook(latest) },
                    backupCode = latest.callString("getBackupCode").ifBlank { onlineSourceIdFromUuid(uuid) },
                    publisher = "",
                )
                updateLocalBook(updated)
                logWebDav("online completion stale publisher cleared uuid=$uuid oldPublisher=$publisher")
            }.onFailure {
                onlineCompletionPublisherCleanupIds.remove(uuid)
                XposedBridge.log("$LOG_PREFIX online completion publisher cleanup failed: ${it.stackTraceToString()}")
            }
        }, "ReaMicroOnlinePublisherCleanup").start()
    }

    private fun onlineImportedBookSourceInfo(book: Any): OnlineImportedBookSourceInfo? {
        if (!isOnlineCompletionLocalBook(book)) return null
        val context = currentContext()
        val backupId = bookBackupIdOf(book)
        val backupCode = book.callString("getBackupCode")
        val uuid = book.callString("getUuid")
        val sourceId = onlineSourceIdFromEncodedValue(backupCode)
            .ifBlank { onlineSourceIdFromEncodedValue(backupId) }
            .ifBlank { onlineSourceIdFromUuid(uuid) }
        val sourceById = context?.let { appContext ->
            OnlineSourceStore.list(appContext).firstOrNull { source -> source.id == sourceId }
        }
        val detailUrl = onlineCompletionQueryParameter(backupId, "detail")
            .ifBlank { book.callString("getUri").takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
        val sourceName = sourceById?.name.orEmpty()
            .ifBlank { onlineCompletionQueryParameter(backupId, "name") }
            .ifBlank { book.callString("getPublisher") }
            .ifBlank { sourceId }
        return OnlineImportedBookSourceInfo(
            sourceId = sourceId,
            sourceName = sourceName.ifBlank { "未知源" },
            detailUrl = detailUrl,
            source = sourceById,
        )
    }

    private fun startOnlineCompletionChapterUpdate(book: Any, info: OnlineImportedBookSourceInfo) {
        val context = currentContext()
        if (context == null) {
            showToast("无法启动章节更新：缺少 Context")
            return
        }
        val source = info.source ?: currentContext()?.let { context ->
            OnlineSourceStore.list(context).firstOrNull { source -> source.id == info.sourceId }
        }
        if (source == null) {
            showToast("在线补全源不可用：${info.sourceName}")
            return
        }
        val title = book.callString("getTitle").ifBlank { "未命名图书" }
        val detailUrl = info.detailUrl
            .ifBlank { book.callString("getUri").takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
        if (detailUrl.isBlank()) {
            showToast("缺少在线源详情地址，无法更新章节")
            return
        }
        val key = "${book.callString("getUuid")}|${source.id}|$detailUrl"
        if (onlineCompletionRunningUpdates.putIfAbsent(key, true) == true) {
            showToast("正在更新：$title")
            return
        }
        val notificationId = onlineCompletionNotificationIds.incrementAndGet()
        val tracker = currentWorkTracker()
        val workId = tracker?.let { createOnlineCompletionTrackedTask(it, "$title 更新") }
        val progressNotifier = OnlineCompletionProgressNotifier(
            context = context,
            notificationId = notificationId,
            notificationKey = "update:$key",
            cancellable = false,
            bookName = title,
            tracker = tracker,
            workId = workId,
        )
        progressNotifier.running(0, "准备更新", force = true)
        showToast("开始检查更新：$title")
        Thread({
            runCatching {
                val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
                val target = OnlineDownloadTarget(
                    source = source,
                    query = title,
                    result = OnlineBookSearchResult(
                        sourceName = source.name,
                        name = title,
                        author = latest.callString("getAuthor").ifBlank { book.callString("getAuthor") },
                        coverUrl = latest.callString("getCover").ifBlank { book.callString("getCover") },
                        detailUrl = detailUrl,
                        intro = "",
                    ),
                )
                val result = updateOnlineCompletionBookIncrementally(
                    book = latest,
                    target = target,
                    onProgress = { progress, message ->
                        progressNotifier.running(progress, message)
                    },
                )
                val message = when {
                    result.added <= 0 -> "已是最新章节"
                    result.failed > 0 -> "已更新 ${result.added} 章，${result.failed} 章失败"
                    else -> "已更新 ${result.added} 章"
                }
                progressNotifier.finish(message, detailUrl, success = true)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "$message：$title", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX online completion update failed: ${it.stackTraceToString()}")
                progressNotifier.finish(it.message ?: "章节更新失败", null, success = false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "章节更新失败：${it.message ?: title}", Toast.LENGTH_SHORT).show()
                }
            }.also {
                onlineCompletionRunningUpdates.remove(key)
            }
        }, "ReaMicroOnlineCompletionUpdate").start()
    }

    private fun updateOnlineCompletionBookIncrementally(
        book: Any,
        target: OnlineDownloadTarget,
        onProgress: (Int, String) -> Unit,
    ): OnlineChapterUpdateResult {
        val bookDir = bookDirectory(book).canonicalFile
        val textDir = File(bookDir, "OEBPS/Text")
        val contentOpf = File(bookDir, "OEBPS/content.opf")
        val tocNcx = File(bookDir, "OEBPS/toc.ncx")
        if (!bookDir.isDirectory || !textDir.isDirectory || !contentOpf.isFile || !tocNcx.isFile) {
            error("当前图书不是在线补全生成的标准 EPUB，暂不能增量更新")
        }
        val existingCount = onlineCompletionLocalChapterCount(bookDir)
        if (existingCount <= 0) {
            error("未找到本地章节文件，暂不能增量更新")
        }
        val tocSnapshot = loadOnlineCompletionToc(target) { progress, message ->
            onProgress(progress.coerceAtMost(12), message)
        }
        val remoteChapters = tocSnapshot.chapters
        if (remoteChapters.size <= existingCount) {
            logWebDav(
                "online completion update no new chapters title=${target.result.name} " +
                    "local=$existingCount remote=${remoteChapters.size}",
            )
            return OnlineChapterUpdateResult(0, 0)
        }
        val newRemoteChapters = remoteChapters.drop(existingCount)
        logWebDav(
            "online completion update start title=${target.result.name} " +
                "local=$existingCount remote=${remoteChapters.size} new=${newRemoteChapters.size}",
        )
        val downloaded = MutableList<OnlineDownloadedChapter?>(newRemoteChapters.size) { null }
        val attempts = IntArray(newRemoteChapters.size)
        val failed = linkedSetOf<Int>()
        val failureMessages = mutableMapOf<Int, String>()

        fun attemptChapter(offset: Int, immediateRetry: Boolean): Boolean {
            val chapter = newRemoteChapters[offset]
            val remoteIndex = existingCount + offset
            val maxAttemptsThisRound = if (immediateRetry) 2 else 1
            var roundAttempts = 0
            while (attempts[offset] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT && roundAttempts < maxAttemptsThisRound) {
                attempts[offset]++
                roundAttempts++
                val attempt = attempts[offset]
                runCatching {
                    downloadOnlineChapter(target, tocSnapshot.detail.url, tocSnapshot.detail.body, chapter, remoteIndex)
                }.onSuccess { chapterContent ->
                    downloaded[offset] = chapterContent
                    failed.remove(offset)
                    failureMessages.remove(offset)
                    logWebDav(
                        "online completion update chapter downloaded ${offset + 1}/${newRemoteChapters.size} " +
                            "remote=${remoteIndex + 1}/${remoteChapters.size} attempt=$attempt " +
                            "content=${chapterContent.content.length}",
                    )
                    return true
                }.onFailure { error ->
                    failed.add(offset)
                    failureMessages[offset] = error.message.orEmpty().ifBlank { error.javaClass.name }
                    logWebDav(
                        "online completion update chapter failed ${offset + 1}/${newRemoteChapters.size} " +
                            "remote=${remoteIndex + 1}/${remoteChapters.size} " +
                            "attempt=$attempt/${ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT} " +
                            "title=${chapter.title} error=${error.javaClass.name}: ${error.message.orEmpty()}",
                    )
                }
            }
            return false
        }

        newRemoteChapters.forEachIndexed { offset, _ ->
            val progress = 15 + ((offset + 1) * 68 / newRemoteChapters.size.coerceAtLeast(1))
            onProgress(progress, "下载新增章节 ${offset + 1}/${newRemoteChapters.size}")
            attemptChapter(offset, immediateRetry = true)
        }
        if (failed.any { attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }) {
            val retryCandidates = failed.filter { attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }
            onProgress(84, "等待重试新增章节 ${retryCandidates.size} 章")
            Thread.sleep(ONLINE_COMPLETION_RETRY_DELAY_MS)
            retryCandidates.forEachIndexed { retryIndex, offset ->
                val progress = 84 + ((retryIndex + 1) * 4 / retryCandidates.size.coerceAtLeast(1))
                onProgress(progress, "重试新增章节 ${offset + 1}/${newRemoteChapters.size}")
                attemptChapter(offset, immediateRetry = false)
            }
        }
        val successCount = downloaded.count { it != null }
        if (successCount <= 0) {
            error("新增章节全部下载失败，已停止更新：${target.result.name}")
        }
        val newDownloadedChapters = newRemoteChapters.mapIndexed { offset, chapter ->
            downloaded[offset] ?: OnlineDownloadedChapter(
                title = chapter.title.ifBlank { "第 ${existingCount + offset + 1} 章" },
                content = "本章下载失败，已按在线源重试 ${attempts[offset]} 次。\n\n${failureMessages[offset].orEmpty()}",
                volumeTitle = chapter.volumeTitle,
                level = chapter.level,
            )
        }
        onProgress(90, "写入新增章节")
        appendOnlineCompletionChapters(
            bookDir = bookDir,
            target = target,
            existingCount = existingCount,
            remoteChapters = remoteChapters,
            newChapters = newDownloadedChapters,
        )
        val newSize = bookDirectorySize(bookDir)
        val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
        refreshOnlineCompletionCatalogTables(latest, bookDir)
        val updated = copyBookWithBackupAndPublisher(
            book = latest,
            backupType = BACKUP_TYPE_ONLINE_COMPLETION,
            backupId = bookBackupIdOf(latest).ifBlank { onlineImportedBookBackupId(target) },
            backupCode = latest.callString("getBackupCode").ifBlank { target.source.id },
            publisher = "",
            cover = latest.callString("getCover").ifBlank { target.result.coverUrl },
            size = newSize,
            updated = System.currentTimeMillis(),
        )
        updateLocalBook(updated)
        logWebDav(
            "online completion update complete title=${target.result.name} " +
                "added=$successCount failed=${failed.size} total=${remoteChapters.size} size=$newSize",
        )
        return OnlineChapterUpdateResult(successCount, failed.size)
    }

    private fun refreshOnlineCompletionCatalogTables(book: Any, bookDir: File) {
        val bookId = book.callLong("getId")
        if (bookId <= 0L) {
            logWebDav("online completion catalog refresh skipped: invalid bookId title=${book.callString("getTitle")}")
            return
        }
        val bookshelf = currentBookshelfRepository() ?: error("阅微书架服务暂不可用，无法刷新目录")
        val itemRefDao = fieldValue(bookshelf, "bookItemRefDao") ?: error("阅微目录条目 DAO 不可用")
        val chapterDao = fieldValue(bookshelf, "bookChapterDao") ?: error("阅微章节 DAO 不可用")
        val managerClass = cls(EPUB_FILE_MANAGER_CLASS)
        val manager = runCatching {
            managerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull() ?: error("阅微 EpubFileManager INSTANCE 未找到")
        val bookPath = okioPath(bookDir.canonicalFile)
        val opf = obtainOnlineCompletionOpf(bookPath)
        val itemRefs = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
            .first {
                it.name == "getItemRefs" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }
            .apply { isAccessible = true }
            .invoke(manager, java.lang.Long.valueOf(bookId), opf, bookPath) as? List<*>
            ?: emptyList<Any>()
        if (itemRefs.isEmpty()) {
            error("阅微目录条目解析为空，已阻止刷新目录表")
        }
        val chapters = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
            .first {
                it.name == "getChapters" &&
                    it.parameterTypes.size == 3 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            .apply { isAccessible = true }
            .invoke(manager, itemRefs, opf, bookPath) as? List<*>
            ?: emptyList<Any>()
        if (chapters.isEmpty()) {
            error("阅微章节目录解析为空，已阻止刷新目录表")
        }
        replaceOnlineCompletionDaoRows(itemRefDao, bookId, itemRefs, "BookItemRef")
        replaceOnlineCompletionDaoRows(chapterDao, bookId, chapters, "BookChapter")
        logWebDav(
            "online completion catalog tables refreshed bookId=$bookId " +
                "itemRefs=${itemRefs.size} chapters=${chapters.size}",
        )
    }

    private fun replaceOnlineCompletionDaoRows(dao: Any, bookId: Long, rows: List<*>, label: String) {
        val deleteMethod = (dao.javaClass.methods.asSequence() + dao.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "deleteByBookId" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }
            .apply { isAccessible = true }
        val upsertMethod = (dao.javaClass.methods.asSequence() + dao.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "upsert" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].isArray
            }
            .apply { isAccessible = true }
        val componentType = upsertMethod.parameterTypes[0].componentType
        val array = java.lang.reflect.Array.newInstance(componentType, rows.size)
        rows.forEachIndexed { index, row -> java.lang.reflect.Array.set(array, index, row) }
        invokeSuspendBlocking(deleteMethod, dao, java.lang.Long.valueOf(bookId))
        invokeSuspendBlocking(upsertMethod, dao, array)
        logWebDav("online completion $label rows replaced bookId=$bookId count=${rows.size}")
    }

    private fun onlineSourceIdFromEncodedValue(value: String): String {
        val text = value.trim()
        if (text.isBlank()) return ""
        if (text.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) {
            return runCatching { Uri.parse(text).host.orEmpty() }.getOrDefault("")
        }
        return text.takeUnless { it.contains("://") }.orEmpty()
    }

    private fun onlineCompletionQueryParameter(value: String, name: String): String =
        runCatching {
            if (!value.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) return@runCatching ""
            Uri.parse(value).getQueryParameter(name).orEmpty()
        }.getOrDefault("")

    private fun handleOnlineCompletionSearchTap(book: Any): Boolean {
        val path = cloudPathOf(book)
        logWebDav("online completion tap path=$path")
        return when {
            path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) -> {
                onlineCompletionSearchTargets[path]?.let { target ->
                    startOnlineCompletionDownload(target)
                    return true
                }
                Toast.makeText(
                    currentContext(),
                    "该在线补全源暂无可下载结果",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
            path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX) -> {
                val target = onlineCompletionSearchTargets[path]
                if (target == null) {
                    logWebDav("online completion target expired path=$path known=${onlineCompletionSearchTargets.size}")
                    Toast.makeText(currentContext(), "在线补全结果已过期，请重新搜索", Toast.LENGTH_SHORT).show()
                } else {
                    startOnlineCompletionDownload(target)
                }
                true
            }
            else -> false
        }
    }

    private fun startOnlineCompletionDownload(target: OnlineDownloadTarget) {
        val context = currentContext()
        if (context == null) {
            showToast("无法启动在线补全下载：缺少 Context")
            return
        }
        ensureOnlineCompletionCancelReceiver(context)
        val key = "${target.source.id}|${target.result.detailUrl}|${target.result.name}"
        if (onlineCompletionRunningDownloads.containsKey(key)) {
            showToast("正在下载：${target.result.name}")
            return
        }
        val notificationId = onlineCompletionNotificationIds.incrementAndGet()
        val tracker = currentWorkTracker()
        val workId = tracker?.let { createOnlineCompletionTrackedTask(it, target.result.name) }
        val cacheDir = File(
            context.cacheDir ?: File("/data/local/tmp"),
            "$ONLINE_COMPLETION_CACHE_ROOT/${System.currentTimeMillis()}_${UUID.randomUUID()}",
        )
        val task = OnlineCompletionDownloadTask(
            notificationId = notificationId,
            key = key,
            name = target.result.name,
            cacheDir = cacheDir,
            tracker = tracker,
            workId = workId,
        )
        if (onlineCompletionRunningDownloads.putIfAbsent(key, task) != null) {
            cancelOnlineCompletionTrackedWork(task)
            showToast("正在下载：${target.result.name}")
            return
        }
        onlineCompletionRunningDownloadsByNotificationId[notificationId] = task
        showToast("已开始下载：${target.result.name}")
        logWebDav(
            "online completion download start source=${target.source.name} " +
                "book=${target.result.name} detail=${target.result.detailUrl}",
        )
        val progressNotifier = OnlineCompletionProgressNotifier(
            context = context,
            notificationId = notificationId,
            notificationKey = key,
            cancellable = true,
            bookName = target.result.name,
            tracker = tracker,
            workId = workId,
        )
        val notificationReady = progressNotifier.running(0, "准备下载", force = true)
        if (!notificationReady) {
            showToast("阅微通知权限未开启，下载继续在后台进行")
        }
        val thread = Thread({
            var partialImportThread: Thread? = null
            runCatching {
                throwIfOnlineCompletionDownloadCancelled(task)
                val localFile = File(task.cacheDir, "${safeOnlineFileName(target.result.name)}.epub")
                task.cacheDir.mkdirs()
                downloadOnlineCompletionBook(
                    target = target,
                    outputFile = localFile,
                    task = task,
                    onProgress = { progress, message ->
                        throwIfOnlineCompletionDownloadCancelled(task)
                        progressNotifier.running(progress, message)
                    },
                    onPartialReady = { partialFile, count ->
                        throwIfOnlineCompletionDownloadCancelled(task)
                        progressNotifier.running(48, "正在导入前 $count 章", force = true)
                        partialImportThread = Thread({
                            if (task.cancelRequested) return@Thread
                            val partialImported = runCatching {
                                throwIfOnlineCompletionDownloadCancelled(task)
                                val importResult = importOnlineCompletionBook(
                                    file = partialFile,
                                    target = target,
                                    onWaiting = {
                                        throwIfOnlineCompletionDownloadCancelled(task)
                                        progressNotifier.running(48, "等待导入队列：前 $count 章", force = true)
                                    },
                                    onStarted = {
                                        throwIfOnlineCompletionDownloadCancelled(task)
                                        progressNotifier.running(48, "正在导入前 $count 章", force = true)
                                    },
                                )
                                importResult.bookDir?.let { importedDir ->
                                    task.importedBookDir = importedDir
                                    flushOnlineCompletionDownloadedChapters(task)
                                }
                                importResult.imported
                            }.onFailure { error ->
                                if (error is OnlineCompletionDownloadCancelledException || task.cancelRequested) {
                                    return@Thread
                                }
                                logWebDav(
                                    "online completion partial import failed but download continues: " +
                                        "${error.message ?: error.javaClass.name}",
                                )
                            }.getOrDefault(false)
                            val continueMessage = if (partialImported) {
                                "已导入前 $count 章，继续下载"
                            } else {
                                "前 $count 章已写入，继续下载"
                            }
                            progressNotifier.running(50, continueMessage, force = true)
                        }, "ReaMicroOnlineCompletionPartialImport").also {
                            it.start()
                        }
                    },
                )
                throwIfOnlineCompletionDownloadCancelled(task)
                partialImportThread?.takeIf { it.isAlive }?.let { thread ->
                    progressNotifier.running(90, "等待前 100 章导入完成", force = true)
                    thread.join()
                }
                throwIfOnlineCompletionDownloadCancelled(task)
                if (task.importedBookDir != null) {
                    progressNotifier.running(94, "写入剩余章节", force = true)
                    flushOnlineCompletionDownloadedChapters(task)
                    syncOnlineCompletionImportedBookSize(target)
                    logWebDav(
                        "online completion final import skipped; chapters written to imported dir=${task.importedBookDir}",
                    )
                } else {
                    progressNotifier.running(94, "正在导入完整章节", force = true)
                    importOnlineCompletionBook(
                        file = localFile,
                        target = target,
                        onWaiting = {
                            throwIfOnlineCompletionDownloadCancelled(task)
                            progressNotifier.running(94, "等待导入队列", force = true)
                        },
                        onStarted = {
                            throwIfOnlineCompletionDownloadCancelled(task)
                            progressNotifier.running(94, "正在导入完整章节", force = true)
                        },
                    )
                }
                throwIfOnlineCompletionDownloadCancelled(task)
                progressNotifier.finish("已导入阅微", target.result.detailUrl, success = true)
                logWebDav(
                    "online completion download complete file=${localFile.absolutePath} " +
                        "size=${localFile.length()} importedDir=${task.importedBookDir}",
                )
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "已导入：${target.result.name}", Toast.LENGTH_SHORT).show()
                }
                cleanupOnlineCompletionDownloadTask(task)
            }.onFailure {
                if (it is OnlineCompletionDownloadCancelledException || task.cancelRequested) {
                    cancelOnlineCompletionTrackedWork(task)
                    cleanupOnlineCompletionDownloadTask(task)
                    cancelOnlineCompletionNotification(context, task.notificationId, task.key)
                    logWebDav("online completion download cancelled book=${target.result.name}")
                } else {
                    XposedBridge.log("$LOG_PREFIX online completion download failed: ${it.stackTraceToString()}")
                    cleanupOnlineCompletionDownloadTask(task)
                    progressNotifier.finish(it.message ?: "下载失败", null, success = false)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "在线补全下载失败：${it.message ?: target.result.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.also {
                onlineCompletionRunningDownloads.remove(key, task)
                onlineCompletionRunningDownloadsByNotificationId.remove(notificationId, task)
            }
        }, "ReaMicroOnlineCompletionDownload")
        task.thread = thread
        thread.start()
    }

    private inner class OnlineCompletionProgressNotifier(
        private val context: Context,
        private val notificationId: Int,
        private val notificationKey: String,
        private val cancellable: Boolean,
        private val bookName: String,
        private val tracker: Any?,
        private val workId: String?,
    ) {
        private var lastNotificationAtMs = 0L
        private var lastProgress = -1
        private var lastMessage = ""

        @Synchronized
        fun running(progress: Int, message: String, force: Boolean = false): Boolean {
            val now = System.currentTimeMillis()
            val compactMessage = message.replace(Regex("\\s+"), " ").trim()
            val displayProgress = onlineCompletionDisplayProgress(progress, compactMessage)
            val progressChanged = displayProgress != lastProgress
            val phaseChanged = compactMessage != lastMessage &&
                !compactMessage.startsWith("下载章节 ") &&
                !compactMessage.startsWith("重试章节 ")
            val shouldSend = force ||
                progressChanged ||
                phaseChanged ||
                now - lastNotificationAtMs >= ONLINE_COMPLETION_NOTIFICATION_MIN_INTERVAL_MS ||
                displayProgress >= 100
            if (!shouldSend) {
                return true
            }
            lastNotificationAtMs = now
            lastProgress = displayProgress
            lastMessage = compactMessage
            if (tracker != null && workId != null) {
                setTrackedWorkState(tracker, workId, "Running", displayProgress, null, null, "$bookName：$compactMessage")
            }
            logWebDav(
                "online completion progress notify id=$notificationId progress=$displayProgress raw=$progress " +
                    "force=$force progressChanged=$progressChanged phaseChanged=$phaseChanged text=$compactMessage",
            )
            return updateOnlineCompletionNotification(
                context,
                notificationId,
                notificationKey,
                cancellable,
                bookName,
                compactMessage,
                displayProgress,
                false,
            )
        }

        @Synchronized
        fun finish(message: String, data: String?, success: Boolean) {
            if (tracker != null && workId != null) {
                setTrackedWorkState(
                    tracker,
                    workId,
                    if (success) "Success" else "Error",
                    100,
                    if (success) null else message,
                    data,
                    bookName,
                )
            }
            updateOnlineCompletionNotification(context, notificationId, notificationKey, cancellable, bookName, message, 100, true)
        }
    }

    private fun createOnlineCompletionTrackedTask(tracker: Any, bookName: String): String? =
        runCatching {
            val id = "online-${UUID.randomUUID()}"
            tracker.javaClass.methods.first {
                it.name == "createTask" && it.parameterTypes.size == 4
            }.apply { isAccessible = true }.invoke(
                tracker,
                id,
                "download",
                "$ONLINE_COMPLETION_TITLE-$bookName",
                newWorkState("Running", 0, null, null, bookName),
            )
            logWebDav("online completion tracked task created id=$id book=$bookName")
            id
        }.getOrElse {
            logWebDav("online completion tracked task failed: ${it.message ?: it.javaClass.name}")
            null
        }

    private fun onlineCompletionDisplayProgress(progress: Int, message: String): Int {
        val match = ONLINE_COMPLETION_PROGRESS_CHAPTER_REGEX.find(message) ?: return progress.coerceIn(0, 100)
        val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
        val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
        if (total <= 0) return progress.coerceIn(0, 100)
        return ((current.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun downloadOnlineCompletionBook(
        target: OnlineDownloadTarget,
        outputFile: File,
        task: OnlineCompletionDownloadTask,
        onProgress: (Int, String) -> Unit,
        onPartialReady: (File, Int) -> Unit,
    ) {
        throwIfOnlineCompletionDownloadCancelled(task)
        val tocSnapshot = loadOnlineCompletionToc(target, onProgress)
        throwIfOnlineCompletionDownloadCancelled(task)
        val detail = tocSnapshot.detail
        val chapters = tocSnapshot.chapters
        logWebDav(
            "online completion chapters=${chapters.size} " +
                "first=${chapters.firstOrNull()?.title.orEmpty()} firstUrl=${chapters.firstOrNull()?.url.orEmpty()}",
        )
        onProgress(5, "开始下载章节 0/${chapters.size}")
        val downloaded = MutableList<OnlineDownloadedChapter?>(chapters.size) { null }
        val attempts = IntArray(chapters.size)
        val failed = linkedSetOf<Int>()
        val failureMessages = mutableMapOf<Int, String>()
        val cover = target.result.coverUrl.takeIf { it.isNotBlank() }?.let { coverUrl ->
            runCatching {
                throwIfOnlineCompletionDownloadCancelled(task)
                OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                    throwIfOnlineCompletionDownloadCancelled(task)
                    downloadOnlineBytes(target.source, coverUrl)
                }
            }.onSuccess { payload ->
                logWebDav(
                    "online completion cover downloaded url=${coverUrl.take(160)} " +
                        "bytes=${payload.bytes.size} type=${payload.mimeType.ifBlank { "unknown" }}",
                )
            }.onFailure { error ->
                logWebDav(
                    "online completion cover failed url=${coverUrl.take(160)} " +
                        "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }.getOrNull()
        }
        val shouldImportFirstBatch = chapters.size > ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD
        var firstBatchImported = false
        fun downloadedCount(): Int = downloaded.count { it != null }

        fun maybeImportFirstBatch(progress: Int, processedCount: Int) {
            throwIfOnlineCompletionDownloadCancelled(task)
            if (!shouldImportFirstBatch || firstBatchImported) return
            val firstBatch = downloaded.take(ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS)
            if (
                firstBatch.size < ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS ||
                processedCount < ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS ||
                firstBatch.all { it == null }
            ) return
            firstBatchImported = true
            val partialFile = File(
                outputFile.parentFile ?: outputFile.absoluteFile.parentFile ?: File("/data/local/tmp"),
                "${outputFile.nameWithoutExtension}_first_${ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS}.epub",
            )
            onProgress(progress, "生成前 ${ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS} 章 EPUB")
            throwIfOnlineCompletionDownloadCancelled(task)
            val partialChapters = chapters.mapIndexed { index, chapter ->
                downloaded[index] ?: onlineCompletionPendingChapter(chapter, index)
            }
            writeOnlineCompletionEpub(partialFile, target, partialChapters, cover)
            logWebDav(
                "online completion partial epub ready path=${partialFile.absolutePath} " +
                    "downloaded=${firstBatch.size} toc=${partialChapters.size}",
            )
            onPartialReady(partialFile, ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS)
        }

        fun attemptChapter(index: Int, immediateRetry: Boolean): Boolean {
            throwIfOnlineCompletionDownloadCancelled(task)
            val chapter = chapters[index]
            val maxAttemptsThisRound = if (immediateRetry) 2 else 1
            var roundAttempts = 0
            while (attempts[index] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT && roundAttempts < maxAttemptsThisRound) {
                throwIfOnlineCompletionDownloadCancelled(task)
                attempts[index]++
                roundAttempts++
                val attempt = attempts[index]
                runCatching {
                    throwIfOnlineCompletionDownloadCancelled(task)
                    downloadOnlineChapter(target, detail.url, detail.body, chapter, index)
                }.onSuccess { chapterContent ->
                    downloaded[index] = chapterContent
                    task.downloadedChapters[index] = chapterContent
                    writeOnlineCompletionChapterToImportedBookIfReady(task, index, chapterContent)
                    failed.remove(index)
                    failureMessages.remove(index)
                    if (index == 0 || (index + 1) % 20 == 0 || index == chapters.lastIndex || attempt > 1) {
                        logWebDav(
                            "online completion chapter downloaded ${index + 1}/${chapters.size} " +
                                "attempt=$attempt content=${chapterContent.content.length} url=${chapter.url.take(120)}",
                        )
                    }
                    return true
                }.onFailure { error ->
                    failed.add(index)
                    failureMessages[index] = error.message.orEmpty().ifBlank { error.javaClass.name }
                    logWebDav(
                        "online completion chapter failed ${index + 1}/${chapters.size} " +
                            "attempt=$attempt/${ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT} " +
                            "title=${chapter.title} url=${chapter.url.take(120)} " +
                            "error=${error.javaClass.name}: ${error.message.orEmpty()}",
                    )
                }
            }
            return false
        }

        chapters.forEachIndexed { index, chapter ->
            throwIfOnlineCompletionDownloadCancelled(task)
            val progress = 5 + ((index + 1) * 78 / chapters.size.coerceAtLeast(1))
            onProgress(progress, "下载章节 ${index + 1}/${chapters.size}")
            attemptChapter(index, immediateRetry = true)
            maybeImportFirstBatch(progress, index + 1)
        }
        if (failed.any { attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }) {
            val retryCandidates = failed.filter { attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }
            onProgress(84, "等待重试失败章节 ${retryCandidates.size} 章")
            logWebDav(
                "online completion delayed retry scheduled failed=${retryCandidates.size} " +
                    "downloaded=${downloadedCount()}/${chapters.size}",
            )
            Thread.sleep(ONLINE_COMPLETION_RETRY_DELAY_MS)
            retryCandidates.forEachIndexed { retryIndex, chapterIndex ->
                throwIfOnlineCompletionDownloadCancelled(task)
                val progress = 84 + ((retryIndex + 1) * 4 / retryCandidates.size.coerceAtLeast(1))
                onProgress(progress, "重试章节 ${chapterIndex + 1}/${chapters.size}")
                attemptChapter(chapterIndex, immediateRetry = false)
                maybeImportFirstBatch(progress, chapters.size)
            }
        }
        val successCount = downloadedCount()
        if (successCount <= 0) {
            error("所有章节下载失败，已停止导入：${target.result.name}")
        }
        if (failed.isNotEmpty()) {
            onProgress(88, "仍有 ${failed.size} 章失败，继续生成 EPUB")
            logWebDav(
                "online completion chapters still failed count=${failed.size} " +
                    "success=$successCount/${chapters.size} failures=" +
                    failed.take(20).joinToString { "${it + 1}:${failureMessages[it].orEmpty()}" },
            )
        }
        val finalChapters = chapters.mapIndexed { index, chapter ->
            downloaded[index] ?: OnlineDownloadedChapter(
                title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                content = "本章下载失败，已按在线源重试 ${attempts[index]} 次。\n\n${failureMessages[index].orEmpty()}",
            )
        }
        onProgress(88, "生成 EPUB")
        throwIfOnlineCompletionDownloadCancelled(task)
        writeOnlineCompletionEpub(outputFile, target, finalChapters, cover)
        logWebDav(
            "online completion epub ready path=${outputFile.absolutePath} " +
                "chapters=${finalChapters.size} success=$successCount failed=${failed.size}",
        )
    }

    private fun loadOnlineCompletionToc(
        target: OnlineDownloadTarget,
        onProgress: (Int, String) -> Unit,
    ): OnlineTocSnapshot {
        val detailUrl = target.result.detailUrl.ifBlank { error("搜索结果缺少详情页地址") }
        onProgress(3, "读取详情页")
        val detail = OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
            requestOnlineSearch(target.source, detailUrl)
        }
        logWebDav("online completion detail ok url=${detail.url} bytes=${detail.body.length}")
        val tocUrl = buildOnlineTocUrl(target.source, detail.url, detail.body)
        logWebDav("online completion toc url=${tocUrl.ifBlank { detail.url }}")
        val toc = if (tocUrl.isBlank() || tocUrl == detail.url) {
            detail
        } else {
            onProgress(4, "读取目录")
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                requestOnlineSearch(target.source, tocUrl)
            }
        }
        val chapters = parseOnlineToc(target.source, target.result, toc.url, toc.body)
        if (chapters.isEmpty()) {
            val hint = if (target.result.chapterCount > 0) {
                "，搜索结果标记 ${target.result.chapterCount} 章，可能未登录或源端未缓存章节"
            } else {
                ""
            }
            error("在线源目录为空$hint，已停止导入：${target.source.name}")
        }
        enrichOnlineTargetFromDetail(target, detail.body, detail.url, chapters.size)
        return OnlineTocSnapshot(detail = detail, toc = toc, chapters = chapters)
    }

    private fun enrichOnlineTargetFromDetail(
        target: OnlineDownloadTarget,
        detailBody: String,
        detailUrl: String,
        chapterCount: Int,
    ) {
        val root = parseOnlineJsonRoot(detailBody) ?: return
        val bookInfoRule = runCatching { JSONObject(target.source.ruleBookInfo) }.getOrNull()
        val node = bookInfoRule?.optString("init", "").orEmpty()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
            ?: root
        val meta = onlineResultMetadata(
            source = target.source,
            node = node,
            rule = bookInfoRule,
            baseUrl = detailUrl,
            chapterCountFallback = chapterCount,
        )
        val current = target.result
        val enriched = current.copy(
            author = current.author.ifBlank {
                bookInfoRule?.optString("author", "").orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { onlineRuleValue(node, it, detailUrl).cleanOnlineText() }
                    .orEmpty()
                    .ifBlank { onlineFirstJsonString(node, listOf("author", "bookAuthor", "authorName", "writer")) }
            },
            coverUrl = current.coverUrl.ifBlank {
                bookInfoRule?.optString("coverUrl", "").orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { resolveOnlineUrl(detailUrl, onlineRuleValue(node, it, detailUrl)) }
                    .orEmpty()
                    .ifBlank { resolveOnlineUrl(detailUrl, onlineFirstJsonString(node, listOf("cover", "coverUrl", "thumb_url", "thumb_uri", "bookCover"))) }
            },
            intro = current.intro.ifBlank {
                bookInfoRule?.optString("intro", "").orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { onlineRuleValue(node, it, detailUrl).cleanOnlineText() }
                    .orEmpty()
                    .ifBlank { onlineFirstJsonString(node, listOf("intro", "abstract", "description", "bookDesc")) }
            },
            chapterCount = current.chapterCount.takeIf { it > 0 } ?: meta.chapterCount,
            status = current.status.ifBlank { meta.status },
            wordCount = current.wordCount.ifBlank { meta.wordCount },
            updateTime = current.updateTime.ifBlank { meta.updateTime },
        )
        target.result = enriched
        logWebDav(
            "online metadata enriched source=${target.source.name} book=${enriched.name} " +
                "status=${enriched.status} words=${enriched.wordCount} " +
                "chapters=${enriched.chapterCount} update=${enriched.updateTime}",
        )
    }

    private fun downloadOnlineChapter(
        target: OnlineDownloadTarget,
        detailUrl: String,
        detailBody: String,
        chapter: OnlineChapter,
        index: Int,
    ): OnlineDownloadedChapter {
        val body = if (chapter.url == detailUrl) {
            detailBody
        } else {
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                requestOnlineSearch(target.source, chapter.url)
            }.body
        }
        val content = extractOnlineChapterContent(target.source, chapter.url, body)
        if (content.isBlank()) {
            error("章节正文为空 body=${body.length}")
        }
        return OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
            content = content,
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
        )
    }

    private fun buildOnlineTocUrl(
        source: OnlineSourceEntry,
        detailUrl: String,
        detailBody: String,
    ): String {
        val rule = runCatching { JSONObject(source.ruleBookInfo) }.getOrNull() ?: return ""
        val tocRule = rule.optString("tocUrl", "").trim()
        if (tocRule.isBlank()) return ""
        val root = parseOnlineJsonRoot(detailBody) ?: return resolveOnlineUrl(detailUrl, tocRule)
        val node = rule.optString("init", "").trim()
            .takeIf { it.isNotBlank() }
            ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
            ?: root
        return resolveOnlineUrl(detailUrl, onlineRuleValue(node, tocRule, detailUrl))
    }

    private fun parseOnlineToc(
        source: OnlineSourceEntry,
        result: OnlineBookSearchResult,
        baseUrl: String,
        html: String,
    ): List<OnlineChapter> {
        val body = html.trim()
        if (body.startsWith("{") || body.startsWith("[")) {
            val root = parseOnlineJsonRoot(body)
            val chapters = root?.let { parseOnlineJsonToc(source, result, baseUrl, it) }.orEmpty()
            if (chapters.isNotEmpty()) return chapters
        }
        val anchors = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
            .findAll(html)
            .mapNotNull { match ->
                val title = match.groupValues[2].cleanOnlineText()
                if (title.isBlank() || title.length > 80) return@mapNotNull null
                val href = resolveOnlineUrl(baseUrl, match.groupValues[1])
                if (href.isBlank()) return@mapNotNull null
                val looksChapter = ONLINE_CHAPTER_TITLE_REGEX.containsMatchIn(title) ||
                    href.contains("chapter", ignoreCase = true) ||
                    href.contains("read", ignoreCase = true)
                if (!looksChapter) return@mapNotNull null
                OnlineChapter(title, href)
            }
            .distinctBy { it.url }
            .toList()
        if (anchors.isNotEmpty()) return anchors
        return Regex("""(?is)<option\b[^>]*\bvalue\s*=\s*["']([^"']+)["'][^>]*>(.*?)</option>""")
            .findAll(html)
            .mapNotNull { match ->
                val title = match.groupValues[2].cleanOnlineText()
                val href = resolveOnlineUrl(baseUrl, match.groupValues[1])
                if (title.isBlank() || href.isBlank()) null else OnlineChapter(title, href)
            }
            .distinctBy { it.url }
            .toList()
    }

    private fun parseOnlineJsonToc(
        source: OnlineSourceEntry,
        result: OnlineBookSearchResult,
        baseUrl: String,
        root: Any,
    ): List<OnlineChapter> {
        val rule = runCatching { JSONObject(source.ruleToc) }.getOrNull() ?: return emptyList()
        val nodes = onlineJsonRuleValues(root, rule.optString("chapterList", ""))
        logWebDav(
            "online completion toc nodes=${nodes.size} rule=${rule.optString("chapterList", "").take(120)}",
        )
        if (nodes.isEmpty()) return emptyList()
        val titleRule = rule.optString("chapterName", "")
        val urlRule = rule.optString("chapterUrl", "")
        val isVolumeRule = rule.optString("isVolume", "")
        var currentVolumeTitle = ""
        return nodes.mapIndexedNotNull { index, node ->
            val title = onlineRuleValue(node, titleRule, baseUrl).cleanOnlineText()
                .ifBlank { "第 ${index + 1} 章" }
            if (isOnlineTocVolumeNode(node, isVolumeRule, title, baseUrl)) {
                currentVolumeTitle = title
                return@mapIndexedNotNull null
            }
            val ruleRawUrl = onlineRuleValue(node, urlRule, baseUrl)
            val fallbackRawUrl = if (ruleRawUrl.isBlank()) fallbackOnlineChapterRawUrl(node) else ""
            val rawUrl = ruleRawUrl.ifBlank { fallbackRawUrl }
            if (index == 0) {
                logWebDav(
                    "online completion toc first title=$title urlRuleLen=${urlRule.length} " +
                        "ruleRaw=${ruleRawUrl.take(80)} fallbackRaw=${fallbackRawUrl.take(80)}",
                )
            }
            val url = buildOnlineChapterUrl(source, baseUrl, urlRule, rawUrl)
            val volumeTitle = onlineChapterVolumeTitle(node).ifBlank { currentVolumeTitle }
            if (url.isBlank()) null else OnlineChapter(
                title = title,
                url = url,
                volumeTitle = volumeTitle,
                level = if (volumeTitle.isBlank()) 0 else 1,
            )
        }.distinctBy { it.url }
    }

    private fun isOnlineTocVolumeNode(node: Any?, isVolumeRule: String, title: String, baseUrl: String): Boolean {
        val explicit = isVolumeRule.trim()
            .takeIf { it.isNotBlank() }
            ?.let { onlineRuleValue(node, it, baseUrl).trim().lowercase(Locale.ROOT) }
        if (explicit != null) {
            return explicit == "true" || explicit == "1" || explicit == "yes" || explicit == "volume"
        }
        return title.isNotBlank() &&
            !ONLINE_CHAPTER_TITLE_REGEX.containsMatchIn(title) &&
            ONLINE_VOLUME_TITLE_REGEX.containsMatchIn(title)
    }

    private fun onlineChapterVolumeTitle(node: Any?): String =
        listOf(
            "$.volume_name",
            "$.volumeName",
            "$.volume",
            "$.part_name",
            "$.partName",
            "$.section_name",
            "$.sectionName",
            "$.group_name",
            "$.groupName",
            "volume_name",
            "volumeName",
            "volume",
            "part_name",
            "partName",
            "section_name",
            "sectionName",
            "group_name",
            "groupName",
        ).asSequence()
            .map { onlineJsonString(node, it).cleanOnlineText() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .orEmpty()

    private fun fallbackOnlineChapterRawUrl(node: Any?): String =
        listOf("$.itemId", "$.item_id", "$.chapter_id", "$.id", "$.url", "$.href", "itemId", "chapter_id", "id")
            .asSequence()
            .map { onlineJsonString(node, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun buildOnlineChapterUrl(
        source: OnlineSourceEntry,
        baseUrl: String,
        urlRule: String,
        rawUrl: String,
    ): String {
        val value = rawUrl.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        if (urlRule.contains("<js>", ignoreCase = true) || urlRule.contains("@js:", ignoreCase = true)) {
            evaluateOnlineChapterUrlJs(urlRule, value)?.let { return it }
        }
        if (value.isNotBlank()) {
            onlineContentProxyTemplate(source)?.let { template ->
                return resolveOnlineUrl(baseUrl, template.replace("\${itemId}", value))
            }
        }
        return resolveOnlineUrl(baseUrl, value)
    }

    private fun evaluateOnlineChapterUrlJs(urlRule: String, result: String): String? {
        if (!urlRule.contains("java.base64Encode(result)", ignoreCase = true)) return null
        val template = Regex("`([^`]*java\\.base64Encode\\(result\\)[^`]*)`")
            .find(urlRule)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val encoded = Base64.encodeToString(result.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return template.replace("\${java.base64Encode(result)}", encoded)
    }

    private fun onlineContentProxyTemplate(source: OnlineSourceEntry): String? {
        val contentRule = runCatching { JSONObject(source.ruleContent).optString("content", "") }.getOrNull().orEmpty()
        return extractOnlineBacktickTemplates(contentRule)
            .firstOrNull { it.contains("\${itemId}") }
    }

    private fun extractOnlineBacktickTemplates(rule: String): List<String> {
        if (rule.isBlank()) return emptyList()
        val templates = mutableListOf<String>()
        var index = 0
        while (index < rule.length) {
            val start = rule.indexOf('`', index)
            if (start < 0) break
            val builder = StringBuilder()
            var cursor = start + 1
            var escaped = false
            while (cursor < rule.length) {
                val char = rule[cursor]
                when {
                    escaped -> {
                        builder.append(char)
                        escaped = false
                    }
                    char == '\\' -> {
                        builder.append(char)
                        escaped = true
                    }
                    char == '`' -> break
                    else -> builder.append(char)
                }
                cursor++
            }
            if (cursor >= rule.length) break
            templates.add(builder.toString())
            index = cursor + 1
        }
        return templates
    }

    private fun extractOnlineChapterContent(source: OnlineSourceEntry, baseUrl: String, body: String): String {
        val contentRule = runCatching { JSONObject(source.ruleContent).optString("content", "") }.getOrNull().orEmpty()
        if (contentRule.startsWith("<js>", ignoreCase = true)) {
            evaluateOnlineContentJs(source, contentRule, body)?.let { return normalizeOnlineChapterText(it) }
        }
        val text = body.trim()
        if (text.startsWith("{") || text.startsWith("[")) {
            val root = parseOnlineJsonRoot(text)
            if (root != null) {
                val rule = runCatching { JSONObject(source.ruleContent) }.getOrNull()
                val jsonContentRule = rule?.optString("content", "").orEmpty()
                val rawContent = when {
                    jsonContentRule.isNotBlank() && !jsonContentRule.startsWith("<js>", ignoreCase = true) ->
                        onlineRuleValue(root, jsonContentRule, baseUrl)
                    else -> ""
                }.ifBlank {
                    onlineJsonString(root, "$.data.content")
                        .ifBlank { onlineJsonString(root, "$.chapter.text") }
                        .ifBlank { onlineJsonString(root, "$..content") }
                        .ifBlank { onlineJsonString(root, "$..text") }
                }
                return normalizeOnlineChapterText(rawContent)
            }
        }
        return extractOnlineHtmlChapterContent(body)
    }

    private fun evaluateOnlineContentJs(
        source: OnlineSourceEntry,
        contentRule: String,
        result: String,
    ): String? {
        if (!contentRule.contains("java.ajax", ignoreCase = true)) return null
        val itemId = if (contentRule.contains("java.hexDecodeToString(result)", ignoreCase = true)) {
            result.hexDecodeToString()
        } else {
            result
        }.trim()
        if (itemId.isBlank()) return null
        val template = onlineContentProxyTemplate(source) ?: return null
        val requestUrl = template.replace("\${itemId}", itemId)
        val response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            requestOnlineSearch(source, requestUrl)
        }
        val root = parseOnlineJsonRoot(response.body) ?: return response.body
        return onlineJsonString(root, "$.data.content")
            .ifBlank { onlineJsonString(root, "$.chapter.text") }
            .ifBlank { onlineJsonString(root, "$..content") }
            .ifBlank { response.body }
    }

    private fun extractOnlineHtmlChapterContent(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        val container = listOf(
            Regex("""(?is)<article\b[^>]*>(.*?)</article>"""),
            Regex("""(?is)<div\b[^>]*(?:id|class)\s*=\s*["'][^"']*(?:content|chapter|reader|read)[^"']*["'][^>]*>(.*?)</div>"""),
            Regex("""(?is)<body\b[^>]*>(.*?)</body>"""),
        ).asSequence()
            .mapNotNull { it.find(cleaned)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?: cleaned
        return container
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)</div\\s*>"), "\n")
            .let(::normalizeOnlineChapterText)
    }

    private fun normalizeOnlineChapterText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)</div\\s*>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t\\r\\f]+"), " ")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun ByteArray.toLowerHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexDecodeToString(): String {
        val clean = trim().filterNot { it.isWhitespace() }
        if (clean.length < 2 || clean.length % 2 != 0) return this
        return runCatching {
            val bytes = ByteArray(clean.length / 2) { index ->
                clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.UTF_8)
        }.getOrDefault(this)
    }

    private fun downloadOnlineBytes(source: OnlineSourceEntry, requestUrl: String): OnlineBinaryPayload {
        return withOnlineCleartextAllowed(requestUrl) {
            fun execute(authRetried: Boolean): OnlineBinaryPayload {
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = onlineConnectTimeoutMillis(source)
                    readTimeout = onlineReadTimeoutMillis(source)
                    setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                    parseOnlineHeaders(source.header).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                    OnlineSourceAuth.requestHeaders(currentContext(), source).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                }
                try {
                    val code = connection.responseCode
                    if (code in 200..299) {
                        return OnlineBinaryPayload(
                            bytes = connection.inputStream.use { it.readBytes() },
                            mimeType = connection.contentType?.substringBefore(';')?.trim().orEmpty(),
                            url = connection.url.toString(),
                        )
                    }
                    if ((code == 401 || code == 403) && !authRetried && source.hasLoginConfig) {
                        val login = OnlineSourceAuth.loginWithSavedCredentials(currentContext(), source)
                        logWebDav(
                            "online completion binary auth retry source=${source.name} code=$code " +
                                "success=${login.success} message=${login.message}",
                        )
                        if (login.success) return execute(authRetried = true)
                    }
                    val authHint = if (code == 401 || code == 403) "：未登录或会员权限不足" else ""
                    error("HTTP $code$authHint")
                } finally {
                    connection.disconnect()
                }
            }
            execute(authRetried = false)
        }
    }

    private fun onlineReadTimeoutMillis(source: OnlineSourceEntry): Int =
        source.respondTime.coerceIn(15_000, 300_000)

    private fun onlineConnectTimeoutMillis(source: OnlineSourceEntry): Int =
        onlineReadTimeoutMillis(source).coerceAtMost(30_000).coerceAtLeast(10_000)

    private fun onlineCompletionLocalChapterCount(bookDir: File): Int {
        val textDir = File(bookDir, "OEBPS/Text")
        return textDir.listFiles()
            ?.asSequence()
            ?.mapNotNull { file ->
                ONLINE_COMPLETION_CHAPTER_FILE_REGEX.matchEntire(file.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            ?.maxOrNull()
            ?: 0
    }

    private fun appendOnlineCompletionChapters(
        bookDir: File,
        target: OnlineDownloadTarget,
        existingCount: Int,
        remoteChapters: List<OnlineChapter>,
        newChapters: List<OnlineDownloadedChapter>,
    ) {
        val root = bookDir.canonicalFile
        val textDir = File(root, "OEBPS/Text").canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!textDir.path.startsWith(rootPrefix)) {
            error("EPUB Text directory escapes book dir")
        }
        textDir.mkdirs()
        newChapters.forEachIndexed { offset, chapter ->
            val order = existingCount + offset + 1
            val file = File(textDir, "chapter_${order.toString().padStart(4, '0')}.xhtml").canonicalFile
            if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
            file.writeText(chapterXhtml(chapter.title, chapter.content), Charsets.UTF_8)
        }
        val tocChapters = remoteChapters.map { chapter ->
            OnlineDownloadedChapter(
                title = chapter.title,
                content = "",
                volumeTitle = chapter.volumeTitle,
                level = chapter.level,
            )
        }
        val coverExt = onlineCompletionExistingCoverExt(root)
        File(root, "OEBPS/toc.ncx").writeText(onlineTocNcx(target, tocChapters), Charsets.UTF_8)
        File(root, "OEBPS/content.opf").writeText(
            onlineContentOpf(
                target = target,
                chapters = tocChapters,
                coverExt = coverExt ?: "jpg",
                hasCover = coverExt != null,
            ),
            Charsets.UTF_8,
        )
    }

    private fun onlineCompletionPendingChapter(chapter: OnlineChapter, index: Int): OnlineDownloadedChapter =
        OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
            content = "本章正在后台下载中。\n\n退出本书后重新进入，可刷新已经下载完成的正文。",
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
        )

    private fun writeOnlineCompletionChapterToImportedBookIfReady(
        task: OnlineCompletionDownloadTask,
        index: Int,
        chapter: OnlineDownloadedChapter,
    ) {
        val bookDir = task.importedBookDir ?: return
        task.bookDirWriteLock.lock()
        try {
            throwIfOnlineCompletionDownloadCancelled(task)
            writeOnlineCompletionChapterToBookDir(bookDir, index, chapter)
        } finally {
            task.bookDirWriteLock.unlock()
        }
    }

    private fun flushOnlineCompletionDownloadedChapters(task: OnlineCompletionDownloadTask) {
        val bookDir = task.importedBookDir ?: return
        val chapters = task.downloadedChapters.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
        if (chapters.isEmpty()) return
        task.bookDirWriteLock.lock()
        try {
            throwIfOnlineCompletionDownloadCancelled(task)
            chapters.forEach { (index, chapter) ->
                writeOnlineCompletionChapterToBookDir(bookDir, index, chapter)
            }
            logWebDav(
                "online completion flushed downloaded chapters to imported dir=" +
                    "${bookDir.absolutePath} count=${chapters.size}",
            )
        } finally {
            task.bookDirWriteLock.unlock()
        }
    }

    private fun writeOnlineCompletionChapterToBookDir(
        bookDir: File,
        index: Int,
        chapter: OnlineDownloadedChapter,
    ) {
        val root = bookDir.canonicalFile
        val textDir = File(root, "OEBPS/Text").canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!textDir.path.startsWith(rootPrefix)) {
            error("EPUB Text directory escapes book dir")
        }
        textDir.mkdirs()
        val file = File(textDir, "chapter_${(index + 1).toString().padStart(4, '0')}.xhtml").canonicalFile
        if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
        val temp = File(textDir, "${file.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}").canonicalFile
        if (!temp.path.startsWith(rootPrefix)) error("EPUB temp chapter path escapes book dir")
        temp.writeText(chapterXhtml(chapter.title, chapter.content), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.delete()
            error("无法替换章节文件：${file.name}")
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        if (index == 0 || (index + 1) % 20 == 0) {
            logWebDav("online completion chapter file updated ${index + 1} path=${file.absolutePath}")
        }
    }

    private fun onlineCompletionExistingCoverExt(bookDir: File): String? =
        File(bookDir, "OEBPS/Images").listFiles()
            ?.firstOrNull { file ->
                file.isFile && file.nameWithoutExtension.equals("cover", ignoreCase = true)
            }
            ?.extension
            ?.takeIf { it.isNotBlank() }

    private fun bookDirectorySize(bookDir: File): Long =
        bookDir.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { total, file -> total + file.length() }

    private fun writeOnlineCompletionEpub(
        file: File,
        target: OnlineDownloadTarget,
        chapters: List<OnlineDownloadedChapter>,
        cover: OnlineBinaryPayload?,
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            writeStoredTextZipEntry(zip, "mimetype", "application/epub+zip")
            writeTextZipEntry(
                zip,
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val coverExt = onlineCoverExt(cover)
            cover?.let {
                writeBytesZipEntry(zip, "OEBPS/Images/cover.$coverExt", it.bytes)
                writeTextZipEntry(zip, "OEBPS/Text/cover.xhtml", onlineCoverXhtml(target, coverExt))
            }
            chapters.forEachIndexed { index, chapter ->
                writeTextZipEntry(
                    zip,
                    "OEBPS/Text/chapter_${(index + 1).toString().padStart(4, '0')}.xhtml",
                    chapterXhtml(chapter.title, chapter.content),
                )
            }
            writeTextZipEntry(zip, "OEBPS/toc.ncx", onlineTocNcx(target, chapters))
            writeTextZipEntry(zip, "OEBPS/content.opf", onlineContentOpf(target, chapters, coverExt, cover != null))
        }
    }

    private fun importOnlineCompletionBook(
        file: File,
        target: OnlineDownloadTarget,
        onWaiting: (() -> Unit)? = null,
        onStarted: (() -> Unit)? = null,
    ): OnlineCompletionImportResult {
        var locked = onlineCompletionImportLock.tryLock()
        if (!locked) {
            logWebDav("online completion import queued book=${target.result.name} file=${file.name}")
            onWaiting?.invoke()
            onlineCompletionImportLock.lock()
            locked = true
        }
        return try {
            onStarted?.invoke()
            importOnlineCompletionBookLocked(file, target)
        } finally {
            if (locked) onlineCompletionImportLock.unlock()
        }
    }

    private fun importOnlineCompletionBookLocked(file: File, target: OnlineDownloadTarget): OnlineCompletionImportResult {
        val bookshelf = currentBookshelfRepository() ?: error("阅微导入服务暂不可用，请先打开书架后重试")
        val sourceUrl = target.result.detailUrl.ifBlank { target.source.sourceUrl }
        val importUuid = onlineBookUuid(target)
        val importTitle = target.result.name
        OnlineCompletionImportSignal.remember(importUuid, importTitle, sourceUrl)
        return try {
            val unzipDirFile = unzipOnlineCompletionEpub(file)
            val unzipDir = okioPath(unzipDirFile)
            val booksDir = currentOnlineCompletionBooksDir(bookshelf)
            val imported = importOnlineCompletionEpubDirectory(unzipDir, booksDir)
            val bookDir = imported.first
            val opf = imported.second
            val platformFile = platformFile(file)
            rememberPendingWebDavImport(
                platformFile = platformFile,
                localFile = file,
                sourceUrl = sourceUrl,
                sourceSize = file.length(),
            )
            logWebDav(
                "online completion epub imported dir=$bookDir " +
                    "title=${callStringPath(opf, "metadata", "title", "value")} " +
                    "uuid=${callStringPath(opf, "metadata", "uuid", "value")}",
            )
            val importMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
                .first { it.name == "importBook" && it.parameterTypes.size == 6 }
                .apply { isAccessible = true }
            val result = invokeSuspendBlocking(
                importMethod,
                bookshelf,
                platformFile,
                bookDir,
                opf,
                sourceUrl,
                java.lang.Long.valueOf(file.length()),
            )
            syncOnlineCompletionImportedBookMetadata(
                bookshelf = bookshelf,
                target = target,
                sourceUrl = sourceUrl,
            )
            logWebDav("online completion importBook result=$result file=${file.absolutePath} size=${file.length()}")
            if (result != true) {
                logWebDav(
                    "online completion importBook returned non-true after epub directory import; " +
                        "treating as non-fatal result=$result file=${file.absolutePath}",
                )
            }
            OnlineCompletionImportResult(
                imported = true,
                bookDir = runCatching { File(bookDir.toString()).canonicalFile }.getOrNull(),
            )
        } finally {
            OnlineCompletionImportSignal.forget(importUuid, importTitle, sourceUrl)
        }
    }

    private fun syncOnlineCompletionImportedBookMetadata(bookshelf: Any, target: OnlineDownloadTarget, sourceUrl: String) {
        runCatching {
            val imported = findLocalBookByUrl(bookshelf, sourceUrl)
                ?: findLocalBookByUrl(bookshelf, target.result.detailUrl)
                ?: return
            val updated = copyBookWithBackupAndPublisher(
                book = imported,
                backupType = BACKUP_TYPE_ONLINE_COMPLETION,
                backupId = onlineImportedBookBackupId(target),
                backupCode = target.source.id,
                publisher = "",
                cover = imported.callString("getCover").ifBlank { target.result.coverUrl },
            )
            updateLocalBook(updated)
            logWebDav(
                "online completion metadata synced uuid=${updated.callString("getUuid")} " +
                    "backupId=${bookBackupIdOf(updated)} source=${target.source.name} " +
                    "cover=${updated.callString("getCover").take(120)}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX online completion metadata sync failed: ${it.stackTraceToString()}")
        }
    }

    private fun syncOnlineCompletionImportedBookSize(target: OnlineDownloadTarget) {
        runCatching {
            val bookshelf = currentBookshelfRepository() ?: return
            val sourceUrl = target.result.detailUrl.ifBlank { target.source.sourceUrl }
            val imported = findLocalBookByUrl(bookshelf, sourceUrl)
                ?: findLocalBookByUrl(bookshelf, target.result.detailUrl)
                ?: return
            val bookDir = bookDirectory(imported).canonicalFile
            val size = bookDirectorySize(bookDir)
            val updated = copyBookWithBackupAndPublisher(
                book = imported,
                backupType = BACKUP_TYPE_ONLINE_COMPLETION,
                backupId = bookBackupIdOf(imported).ifBlank { onlineImportedBookBackupId(target) },
                backupCode = imported.callString("getBackupCode").ifBlank { target.source.id },
                publisher = "",
                cover = imported.callString("getCover").ifBlank { target.result.coverUrl },
                size = size,
                updated = System.currentTimeMillis(),
            )
            updateLocalBook(updated)
            logWebDav(
                "online completion imported book size synced uuid=${updated.callString("getUuid")} size=$size",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX online completion size sync failed: ${it.stackTraceToString()}")
        }
    }

    private fun findLocalBookByUrl(bookshelf: Any, url: String): Any? {
        if (url.isBlank()) return null
        val findMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == "findBookByUrl" && it.parameterTypes.size == 2 }
            ?.apply { isAccessible = true }
            ?: return null
        return invokeSuspendBlocking(findMethod, bookshelf, url)
    }

    private fun currentOnlineCompletionBooksDir(bookshelf: Any): Any {
        val userStorage = fieldValue(bookshelf, "userStorage")
            ?: bookshelf.javaClass.methods.firstOrNull { it.name == "getUserStorage" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(bookshelf)
            ?: error("阅微书库目录不可用：UserStorage 未找到")
        val defaultMethod = (userStorage.javaClass.methods.asSequence() + userStorage.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name == "userBooksDir\$default" &&
                    Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 4
            }
        if (defaultMethod != null) {
            defaultMethod.isAccessible = true
            return defaultMethod.invoke(null, userStorage, null, 1, null)
                ?: error("阅微书库目录不可用：userBooksDir 返回空")
        }
        val uid = fieldValue(userStorage, "uid")
        val method = (userStorage.javaClass.methods.asSequence() + userStorage.javaClass.declaredMethods.asSequence())
            .first { it.name == "userBooksDir" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        return method.invoke(userStorage, uid) ?: error("阅微书库目录不可用：userBooksDir 返回空")
    }

    private fun importOnlineCompletionEpubDirectory(unzipDir: Any, booksDir: Any): Pair<Any, Any> {
        val managerClass = cls(EPUB_FILE_MANAGER_CLASS)
        val manager = runCatching {
            managerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull() ?: error("阅微 EpubFileManager INSTANCE 未找到")
        val importMethod = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
            .firstOrNull { candidate ->
                candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes.all { it.name == OKIO_PATH_CLASS } &&
                    candidate.returnType.name == KOTLIN_PAIR_CLASS
            }
            ?.apply { isAccessible = true }
            ?: error("阅微 EpubFileManager.import 方法未找到")
        val pair = importMethod.invoke(manager, unzipDir, booksDir)
            ?: error("阅微 EpubFileManager.import 返回空")
        val first = pair.javaClass.methods.first { it.name == "getFirst" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }
            .invoke(pair)
            ?: error("阅微 EpubFileManager.import 未返回 bookDir")
        val second = pair.javaClass.methods.first { it.name == "getSecond" && it.parameterTypes.isEmpty() }
            .apply { isAccessible = true }
            .invoke(pair)
            ?: error("阅微 EpubFileManager.import 未返回 OPF")
        return first to second
    }

    private fun unzipOnlineCompletionEpub(file: File): File {
        val outputDir = File(
            file.parentFile ?: file.absoluteFile.parentFile ?: File("/data/local/tmp"),
            "${file.nameWithoutExtension}_unzipped",
        )
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()
        val root = outputDir.canonicalFile
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    val target = File(root, entry.name).canonicalFile
                    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
                    if (target != root && !target.path.startsWith(rootPrefix)) {
                        error("EPUB entry escapes output dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> zip.copyTo(out) }
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
        logWebDav("online completion epub unzipped dir=${outputDir.absolutePath}")
        return outputDir
    }

    private fun okioPath(file: File): Any =
        cls(OKIO_PATH_CLASS).getDeclaredMethod("get", File::class.java)
            .apply { isAccessible = true }
            .invoke(null, file)

    private fun obtainOnlineCompletionOpf(bookDir: Any): Any {
        val opfClass = cls(OPF_CLASS)
        val companion = sequenceOf("INSTANCE", "Companion")
            .mapNotNull { fieldName ->
                runCatching {
                    opfClass.companionField(fieldName)?.let { field ->
                        field.isAccessible = true
                        field.get(null)
                    }
                }.getOrNull()
            }
            .firstOrNull()
            ?: (opfClass.declaredFields.asSequence() + opfClass.fields.asSequence())
                .filter { Modifier.isStatic(it.modifiers) && it.type.name.contains("Opf\$Companion") }
                .mapNotNull { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(null)
                    }.getOrNull()
                }
                .firstOrNull()
            ?: error(
                "EPUB OPF Companion 未找到: fields=" +
                    opfClass.declaredFields.joinToString { "${it.name}:${it.type.name}" },
            )
        val obtain = (companion.javaClass.methods.asSequence() + companion.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == "obtain" && it.parameterTypes.size == 1 }
            ?: error(
                "EPUB OPF obtain 方法未找到: companion=${companion.javaClass.name}, methods=" +
                    companion.javaClass.declaredMethods.joinToString { "${it.name}/${it.parameterTypes.size}" },
            )
        obtain.isAccessible = true
        return obtain.invoke(companion, bookDir) ?: error("EPUB OPF 解析失败")
    }

    private fun Class<*>.companionField(name: String) =
        runCatching { getDeclaredField(name) }
            .recoverCatching { getField(name) }
            .getOrNull()

    private fun onlineCompletionCancelPendingIntent(context: Context, id: Int, key: String): PendingIntent {
        val intent = Intent(ONLINE_COMPLETION_CANCEL_ACTION).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    private fun cancelOnlineCompletionNotification(context: Context, id: Int, key: String) {
        cancelOnlineCompletionHostNotification(context, id)
        sendOnlineCompletionCancelBroadcastToModule(context, id, key)
    }

    private fun cancelOnlineCompletionHostNotification(context: Context, id: Int) {
        if (id <= 0) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.cancel(id)
        }.onFailure {
            logWebDav("online completion host notification cancel failed id=$id: ${it.message}")
        }
    }

    private fun sendOnlineCompletionCancelBroadcastToModule(context: Context, id: Int, key: String): Boolean =
        runCatching {
            val intent = Intent(ONLINE_COMPLETION_CANCEL_ACTION).apply {
                setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
            }
            context.sendBroadcast(intent)
            true
        }.getOrElse {
            logWebDav("online completion module notification cancel failed id=$id: ${it.message}")
            false
        }

    private fun updateOnlineCompletionNotification(
        context: Context,
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Boolean {
        val moduleRelaySent = sendOnlineCompletionNotificationViaModule(
            context,
            id,
            key,
            cancellable,
            title,
            text,
            progress,
            done,
        )
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            if (!manager.areNotificationsEnabled()) {
                if (onlineCompletionNotificationBlockedLogged.compareAndSet(false, true)) {
                    logWebDav(
                        "online completion host notification blocked by system for ${context.packageName}, " +
                            "moduleRelaySent=$moduleRelaySent",
                    )
                }
                return moduleRelaySent
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ONLINE_COMPLETION_NOTIFICATION_CHANNEL,
                        ONLINE_COMPLETION_TITLE,
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(onlineCompletionDownloadTitle(progress, text))
                .setContentText(onlineCompletionDownloadText(title, text))
                .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setProgress(100, progress.coerceIn(0, 100), false)
            if (!done && cancellable) {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "\u53d6\u6d88",
                    onlineCompletionCancelPendingIntent(context, id, key),
                )
            }
            manager.notify(id, builder.build())
            cancelOnlineCompletionNotificationIfDone(manager, id, done)
            logWebDav("online completion host notification posted id=$id moduleRelaySent=$moduleRelaySent")
            true
        }.getOrElse {
            logWebDav("online completion notification failed: ${it.message ?: it.javaClass.name}")
            moduleRelaySent
        }
    }

    private fun sendOnlineCompletionNotificationViaModule(
        context: Context,
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Boolean {
        val broadcastSent = sendOnlineCompletionNotificationBroadcast(context, id, key, cancellable, title, text, progress, done)
        val shouldStartActivity = shouldStartOnlineCompletionNotificationActivity(id, done, broadcastSent)
        val activitySent = if (shouldStartActivity) {
            startOnlineCompletionNotificationActivity(context, id, key, cancellable, title, text, progress, done)
        } else {
            false
        }
        if (done) onlineCompletionModuleActivityPromptAt.remove(id)
        if (broadcastSent) {
            return true
        }
        if (activitySent) {
            return true
        }
        return false
    }

    private fun shouldStartOnlineCompletionNotificationActivity(
        id: Int,
        done: Boolean,
        broadcastSent: Boolean,
    ): Boolean {
        if (done) return !broadcastSent
        val now = System.currentTimeMillis()
        val last = onlineCompletionModuleActivityPromptAt[id] ?: 0L
        if (last > 0L && broadcastSent) return false
        if (last > 0L && now - last < ONLINE_COMPLETION_MODULE_ACTIVITY_RETRY_MS) return false
        onlineCompletionModuleActivityPromptAt[id] = now
        return true
    }

    private fun startOnlineCompletionNotificationActivity(
        context: Context,
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Boolean =
        runCatching {
            val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done).apply {
                setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_ACTIVITY_CLASS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            logWebDav(
                "online completion module notification activity start sent " +
                    "id=$id progress=$progress done=$done",
            )
            true
        }.getOrElse {
            logWebDav("online completion module notification activity failed: ${it.message ?: it.javaClass.name}")
            false
        }

    private fun sendOnlineCompletionNotificationBroadcast(
        context: Context,
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Boolean =
        runCatching {
            val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done).apply {
                setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS)
            }
            context.sendBroadcast(intent)
            logWebDav(
                "online completion module notification broadcast sent " +
                    "id=$id progress=$progress done=$done",
            )
            true
        }.getOrElse {
            logWebDav("online completion module notification relay failed: ${it.message ?: it.javaClass.name}")
            false
        }

    private fun onlineCompletionNotificationIntent(
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Intent =
        Intent(ONLINE_COMPLETION_NOTIFICATION_ACTION).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_CANCELLABLE, cancellable)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_TITLE, title)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_TEXT, text)
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_PROGRESS, progress.coerceIn(0, 100))
                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_DONE, done)
        }

    private fun writeStoredTextZipEntry(zip: ZipOutputStream, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeTextZipEntry(zip: ZipOutputStream, path: String, text: String) =
        writeBytesZipEntry(zip, path, text.toByteArray(Charsets.UTF_8))

    private fun writeBytesZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun chapterXhtml(title: String, content: String): String {
        val bodyLines = stripDuplicatedChapterTitle(title, content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList())
        val paragraphs = bodyLines.joinToString("\n") { "<p>${it.xmlEscape()}</p>" }
        val heading = chapterHeadingHtml(title)
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${title.xmlEscape()}</title><style type="text/css">
body{line-height:1.75;}
h1{font-size:1.12em;line-height:1.45;text-align:center;margin:1.1em 0 .9em;font-weight:600;}
p{margin:.75em 0;}
</style></head>
<body><h1>$heading</h1>
$paragraphs
</body>
</html>"""
    }

    private fun stripDuplicatedChapterTitle(title: String, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val titleKey = title.normalizedChapterTitleKey()
        if (titleKey.isBlank()) return lines
        val parts = splitChapterHeading(title)
        val mutable = lines.toMutableList()
        stripChapterTitlePrefix(mutable.firstOrNull().orEmpty(), title)?.let { remainder ->
            if (remainder.isBlank()) mutable.removeAt(0) else mutable[0] = remainder
            return mutable
        }
        if (mutable.size >= 2 && (mutable[0] + mutable[1]).normalizedChapterTitleKey() == titleKey) {
            mutable.removeAt(0)
            mutable.removeAt(0)
            return mutable
        }
        if (parts.size >= 2 && mutable.size >= 2) {
            stripChapterTitlePrefix(mutable[0], parts.first())?.takeIf { it.isBlank() }?.let {
                mutable.removeAt(0)
                stripChapterTitlePrefix(mutable.firstOrNull().orEmpty(), parts.drop(1).joinToString(""))?.let { remainder ->
                    if (remainder.isBlank()) mutable.removeAt(0) else mutable[0] = remainder
                }
                return mutable
            }
        }
        val scanCount = mutable.size.coerceAtMost(ONLINE_CHAPTER_TITLE_SCAN_LINES)
        for (index in 0 until (scanCount - 1).coerceAtLeast(0)) {
            if ((mutable[index] + mutable[index + 1]).normalizedChapterTitleKey() == titleKey) {
                mutable.removeAt(index + 1)
                mutable.removeAt(index)
                return mutable
            }
        }
        if (parts.size >= 2) {
            val titlePrefix = parts.first()
            val titleSuffix = parts.drop(1).joinToString("")
            for (index in 0 until (scanCount - 1).coerceAtLeast(0)) {
                val firstRemainder = stripChapterTitlePrefix(mutable[index], titlePrefix)
                if (firstRemainder != null && firstRemainder.isBlank()) {
                    val secondRemainder = stripChapterTitlePrefix(mutable[index + 1], titleSuffix)
                    if (secondRemainder != null) {
                        if (secondRemainder.isBlank()) {
                            mutable.removeAt(index + 1)
                        } else {
                            mutable[index + 1] = secondRemainder
                        }
                        mutable.removeAt(index)
                        return mutable
                    }
                }
            }
        }
        for (index in 0 until scanCount) {
            stripChapterTitlePrefix(mutable.getOrNull(index).orEmpty(), title)?.let { remainder ->
                if (remainder.isBlank()) {
                    mutable.removeAt(index)
                } else {
                    mutable[index] = remainder
                }
                return mutable
            }
        }
        return mutable
    }

    private fun stripChapterTitlePrefix(line: String, title: String): String? {
        val target = title.normalizedChapterTitleKey()
        if (line.isBlank() || target.isBlank()) return null
        var targetIndex = 0
        var removeEnd = -1
        for (index in line.indices) {
            val key = line[index].toString().normalizedChapterTitleKey()
            if (key.isBlank()) {
                if (targetIndex == 0) continue
                continue
            }
            if (targetIndex >= target.length) break
            if (key != target[targetIndex].toString()) return null
            targetIndex += 1
            removeEnd = index + 1
            if (targetIndex == target.length) break
        }
        if (targetIndex != target.length || removeEnd < 0) return null
        return line.substring(removeEnd).trimChapterHeadingSeparator()
    }

    private fun chapterHeadingHtml(title: String): String =
        splitChapterHeading(title).joinToString("<br/>") { it.xmlEscape() }

    private fun splitChapterHeading(title: String): List<String> {
        val clean = title.trim()
        if (clean.isBlank()) return listOf("")
        ONLINE_CHAPTER_HEADING_SPLIT_REGEX.matchEntire(clean)?.let { match ->
            val prefix = match.groupValues[1].trim()
            val suffix = match.groupValues[2].trimChapterHeadingSeparator()
            if (prefix.isNotBlank() && suffix.isNotBlank()) return listOf(prefix, suffix)
        }
        ONLINE_SPECIAL_HEADING_SPLIT_REGEX.matchEntire(clean)?.let { match ->
            val prefix = match.groupValues[1].trim()
            val suffix = match.groupValues[2].trimChapterHeadingSeparator()
            if (prefix.isNotBlank() && suffix.isNotBlank()) return listOf(prefix, suffix)
        }
        return listOf(clean)
    }

    private fun String.trimChapterHeadingSeparator(): String =
        trim().trimStart { it.isWhitespace() || it in "　:：/／、，,。.!！?？-—_；;]" }

    private fun String.normalizedChapterTitleKey(): String =
        trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s　:：/／、，,。.!！?？；;《》\"'“”‘’\\-—_]+"), "")

    private fun onlineTocNcx(target: OnlineDownloadTarget, chapters: List<OnlineDownloadedChapter>): String {
        var playOrder = 1
        fun chapterPoint(index: Int, chapter: OnlineDownloadedChapter): String {
            val order = index + 1
            val pointOrder = playOrder++
            return """<navPoint id="chapter$order" playOrder="$pointOrder"><navLabel><text>${chapter.title.xmlEscape()}</text></navLabel><content src="Text/chapter_${order.toString().padStart(4, '0')}.xhtml"/></navPoint>"""
        }
        val points = StringBuilder()
        var index = 0
        while (index < chapters.size) {
            val volumeTitle = chapters[index].volumeTitle.trim()
            if (volumeTitle.isBlank()) {
                points.append(chapterPoint(index, chapters[index]))
                index += 1
                continue
            }
            val startIndex = index
            val volumeOrder = playOrder++
            val children = StringBuilder()
            while (index < chapters.size && chapters[index].volumeTitle.trim() == volumeTitle) {
                children.append(chapterPoint(index, chapters[index]))
                index += 1
            }
            val href = "Text/chapter_${(startIndex + 1).toString().padStart(4, '0')}.xhtml"
            points.append(
                """<navPoint id="volume$volumeOrder" playOrder="$volumeOrder"><navLabel><text>${volumeTitle.xmlEscape()}</text></navLabel><content src="$href"/>$children</navPoint>""",
            )
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="${onlineBookUuid(target).xmlEscape()}"/></head>
<docTitle><text>${target.result.name.xmlEscape()}</text></docTitle>
<navMap>$points</navMap>
</ncx>"""
    }

    private fun onlineContentOpf(
        target: OnlineDownloadTarget,
        chapters: List<OnlineDownloadedChapter>,
        coverExt: String,
        hasCover: Boolean,
    ): String {
        val manifestChapters = chapters.indices.joinToString("") { index ->
            val order = index + 1
            """<item id="chapter$order" href="Text/chapter_${order.toString().padStart(4, '0')}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = chapters.indices.joinToString("") { index -> """<itemref idref="chapter${index + 1}"/>""" }
        val coverManifest = if (hasCover) {
            """<item id="cover-image" href="Images/cover.$coverExt" media-type="${coverMimeType(coverExt)}" properties="cover-image"/><item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>"""
        } else {
            ""
        }
        val coverMeta = if (hasCover) """<meta name="cover" content="cover-image"/>""" else ""
        val coverGuide = if (hasCover) """<guide><reference type="cover" title="Cover" href="Text/cover.xhtml"/></guide>""" else ""
        val onlineMeta = onlineSourceMetadataOpf(target)
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
<dc:identifier id="BookId">${onlineBookUuid(target).xmlEscape()}</dc:identifier>
<dc:title>${target.result.name.xmlEscape()}</dc:title>
<dc:creator>${target.result.author.xmlEscape()}</dc:creator>
<dc:language>zh-CN</dc:language>
$coverMeta
$onlineMeta
</metadata>
<manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>$coverManifest$manifestChapters</manifest>
<spine toc="ncx">$spine</spine>
$coverGuide
</package>"""
    }

    private fun onlineCoverXhtml(target: OnlineDownloadTarget, coverExt: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${target.result.name.xmlEscape()}</title><style type="text/css">
body{margin:0;padding:0;text-align:center;}
img{max-width:100%;max-height:100%;height:auto;}
</style></head>
<body><img alt="${target.result.name.xmlEscape()}" src="../Images/cover.$coverExt"/></body>
</html>"""

    private fun onlineBookUuid(target: OnlineDownloadTarget): String =
        "reamicro-online-${target.source.id}-${(target.result.detailUrl.ifBlank { target.result.name }).hashCode().toUInt()}"

    private fun onlineSourceMetadataOpf(target: OnlineDownloadTarget): String =
        listOf(
            "reamicro-online-source-id" to target.source.id,
            "reamicro-online-source-name" to target.source.name,
            "reamicro-online-detail-url" to target.result.detailUrl,
        ).joinToString("\n") { (name, value) ->
            """<meta name="${name.xmlEscape()}" content="${value.xmlEscape()}"/>"""
        }

    private fun onlineImportedBookBackupId(target: OnlineDownloadTarget): String =
        ONLINE_COMPLETION_BOOK_PREFIX +
            URLEncoder.encode(target.source.id, "UTF-8") +
            "?name=${URLEncoder.encode(target.source.name, "UTF-8")}" +
            "&detail=${URLEncoder.encode(target.result.detailUrl.ifBlank { target.source.sourceUrl }, "UTF-8")}"

    private fun onlineImportedBookBackupIdFromBook(book: Any): String {
        val uuid = book.callString("getUuid")
        val sourceId = onlineSourceIdFromUuid(uuid)
        val sourceName = book.callString("getPublisher")
        val detailUrl = book.callString("getUri")
        return ONLINE_COMPLETION_BOOK_PREFIX +
            URLEncoder.encode(sourceId, "UTF-8") +
            "?name=${URLEncoder.encode(sourceName, "UTF-8")}" +
            "&detail=${URLEncoder.encode(detailUrl, "UTF-8")}"
    }

    private fun onlineSourceIdFromUuid(uuid: String): String =
        uuid.removePrefix(ONLINE_COMPLETION_UUID_PREFIX)
            .substringBeforeLast('-', "")
            .ifBlank { "unknown" }

    private fun onlineCoverExt(cover: OnlineBinaryPayload?): String =
        cover?.let {
            onlineCoverExtFromMime(it.mimeType)
                ?: onlineCoverExtFromBytes(it.bytes)
                ?: onlineCoverExtFromUrl(it.url)
        } ?: "jpg"

    private fun onlineCoverExtFromMime(mimeType: String): String? =
        when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }

    private fun onlineCoverExtFromBytes(bytes: ByteArray): String? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "png"
            bytes.size >= 6 &&
                bytes[0] == 0x47.toByte() &&
                bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "gif"
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() &&
                bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() &&
                bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() &&
                bytes[11] == 0x50.toByte() -> "webp"
            else -> null
        }

    private fun onlineCoverExtFromUrl(url: String): String =
        url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
            ?.let { if (it == "jpeg") "jpg" else it }
            ?: "jpg"

    private fun coverMimeType(ext: String): String =
        when (ext.lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

    private fun safeOnlineFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "_")
            .trim()
            .take(80)
            .ifBlank { "online_completion" }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun rememberCloudStorageRepository(repository: Any?) {
        if (repository == null) return
        cloudStorageRepositoryRef = WeakReference(repository)
        fieldValue(repository, "bookshelfRepository")?.let {
            bookshelfRepositoryRef = WeakReference(it)
        }
        fieldValue(repository, "workerManager")?.let {
            rememberWorkerManager(it)
        }
    }

    private fun rememberHomeViewModelDependencies(viewModel: Any?) {
        if (viewModel == null) return
        val repository = fieldValue(viewModel, "repository")
        if (repository != null && bookshelfRepositoryRef?.get() !== repository) {
            rememberBookshelfRepository(repository)
            logWebDav("home viewModel bookshelf repository registered")
        }
        fieldValue(viewModel, "cloudStorageRepository")?.let { cloudRepository ->
            if (cloudStorageRepositoryRef?.get() !== cloudRepository) {
                rememberCloudStorageRepository(cloudRepository)
                logWebDav("home viewModel cloud repository registered")
            }
        }
        fieldValue(viewModel, "workers")?.let { workerManager ->
            if (workerManagerRef?.get() !== workerManager) {
                rememberWorkerManager(workerManager)
                logWebDav("home viewModel worker manager registered")
            }
        }
    }

    private fun rememberBookshelfRepository(repository: Any?) {
        if (repository == null) return
        bookshelfRepositoryRef = WeakReference(repository)
    }

    private fun rememberWorkerManager(workerManager: Any?) {
        if (workerManager == null) return
        workerManagerRef = WeakReference(workerManager)
        fieldValue(workerManager, "bookshelf")?.let {
            bookshelfRepositoryRef = WeakReference(it)
        }
        fieldValue(workerManager, "tracker")?.let {
            workTrackerRef = WeakReference(it)
        }
    }

    private fun currentBookshelfRepository(): Any? =
        bookshelfRepositoryRef?.get()
            ?: cloudStorageRepositoryRef?.get()?.let { fieldValue(it, "bookshelfRepository") }
            ?: workerManagerRef?.get()?.let { fieldValue(it, "bookshelf") }

    private fun currentWorkTracker(): Any? =
        workTrackerRef?.get()
            ?: workerManagerRef?.get()?.let { fieldValue(it, "tracker") }

    private fun fieldValue(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (name in names) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return runCatching { field.get(target) }.getOrNull()
                }
                type = type.superclass
            }
        }
        return null
    }

    private fun bookBackupTypeOf(book: Any?): Int {
        if (book == null) return 0
        val methodValue = book.callInt("getBackupType")
        if (methodValue != 0) return methodValue
        val field = fieldValue(book, "backupType") ?: return 0
        return (field as? Number)?.toInt() ?: field.toString().toIntOrNull() ?: 0
    }

    private fun bookBackupIdOf(book: Any?): String {
        if (book == null) return ""
        val methodValue = book.callString("getBackupId")
        if (methodValue.isNotBlank()) return methodValue
        return fieldValue(book, "backupId")?.toString().orEmpty()
    }

    private fun webDavLocalBookFlow(url: String): Any {
        if (url.isBlank()) return emptyFlow()
        return runCatching {
            val bookshelf = currentBookshelfRepository() ?: return@runCatching emptyFlow()
            bookshelf.javaClass.methods.first {
                it.name == "liveBookByUrl" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(bookshelf, url)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to create WebDAV local book flow: ${it.stackTraceToString()}")
        }.getOrDefault(emptyFlow())
    }

    private fun webDavWorkFlow(uniqueName: String): Any {
        if (uniqueName.isBlank()) return emptyFlow()
        return runCatching {
            val tracker = currentWorkTracker() ?: return@runCatching emptyFlow()
            tracker.javaClass.methods.first {
                it.name == "getWorkInfosForUniqueWorkFlow" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(tracker, uniqueName)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to create WebDAV work flow: ${it.stackTraceToString()}")
        }.getOrDefault(emptyFlow())
    }

    private fun hasWebDavLogin(context: Context?): Boolean {
        val prefs = webDavPrefs(context) ?: return false
        val hasCredentials = !prefs.getString(KEY_URL, "").isNullOrBlank() &&
            !prefs.getString(KEY_USERNAME, "").isNullOrBlank() &&
            !prefs.getString(KEY_PASSWORD, "").isNullOrBlank()
        return hasCredentials && prefs.getBoolean(KEY_AUTHORIZED, hasCredentials)
    }

    private fun webDavCredentials(): WebDavCredentials? {
        val prefs = webDavPrefs(activityProvider()) ?: return null
        val url = normalizeServerUrl(prefs.getString(KEY_URL, "").orEmpty())
        val username = prefs.getString(KEY_USERNAME, "").orEmpty()
        val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        return if (url.isBlank() || username.isBlank() || password.isBlank()) {
            null
        } else {
            WebDavCredentials(url, username, password)
        }
    }

    private fun webDavPrefs(context: Context?): android.content.SharedPreferences? {
        val appContext = context ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.applicationContext
        return appContext?.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
    }

    private fun localLibraryPrefs(context: Context?): android.content.SharedPreferences? {
        val appContext = context ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.applicationContext
        return appContext?.getSharedPreferences(LOCAL_LIBRARY_PREFS, Context.MODE_PRIVATE)
    }

    private fun currentContext(): Context? =
        activityProvider() ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.applicationContext

    private fun currentWebDavBrowseDir(): String {
        val prefs = webDavPrefs(activityProvider())
        val saved = prefs?.getString(KEY_BROWSE_DIR, null)
            ?: prefs?.getString(KEY_DIR_LEGACY, null)
            ?: DEFAULT_DIR
        return normalizeWebDavPath(saved.ifBlank { DEFAULT_DIR })
    }

    private fun currentLocalLibraryBrowseDir(): String =
        localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_BROWSE_DIR, LOCAL_LIBRARY_ROOT_PATH)
            ?.ifBlank { LOCAL_LIBRARY_ROOT_PATH }
            ?: LOCAL_LIBRARY_ROOT_PATH

    private fun currentWebDavBackupDir(): String =
        normalizeWebDavPath(webDavPrefs(activityProvider())?.getString(KEY_BACKUP_DIR, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR })

    private fun currentWebDavOrderBy(): String =
        webDavPrefs(activityProvider())?.getString(KEY_ORDER_BY, WEBDAV_DEFAULT_ORDER_BY)
            ?.takeIf { it in setOf("name", "size", "time") }
            ?: WEBDAV_DEFAULT_ORDER_BY

    private fun currentWebDavOrderDirection(): String =
        webDavPrefs(activityProvider())?.getString(KEY_ORDER_DIRECTION, DEFAULT_ORDER_DIRECTION_DESC)
            ?.takeIf { it in setOf("0", "1", "ASC", "DESC", "asc", "desc") }
            ?: DEFAULT_ORDER_DIRECTION_DESC

    private fun currentLocalLibraryOrderBy(): String =
        localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_ORDER_BY, LOCAL_LIBRARY_DEFAULT_ORDER_BY)
            ?.takeIf { it in setOf("file_name", "file_size", "user_utime", "name", "size", "time") }
            ?: LOCAL_LIBRARY_DEFAULT_ORDER_BY

    private fun currentLocalLibraryOrderDirection(): String =
        localLibraryPrefs(activityProvider())?.getString(KEY_LOCAL_ORDER_DIRECTION, DEFAULT_ORDER_DIRECTION_ASC)
            ?.takeIf { it in setOf("0", "1", "ASC", "DESC", "asc", "desc") }
            ?: DEFAULT_ORDER_DIRECTION_ASC

    private fun webDavDisplayDir(path: String): String {
        val normalized = normalizeWebDavPath(path.ifBlank { "/" })
        if (normalized == "/") return ROOT_DIR_NAME
        return (ROOT_DIR_NAME + normalized)
            .trimEnd('/')
            .replace("/", " \u25cb ")
            .uppercase(Locale.ROOT)
    }

    private fun saveWebDavBrowseDir(path: String) {
        webDavPrefs(activityProvider())?.edit()
            ?.putString(KEY_BROWSE_DIR, normalizeWebDavPath(path.ifBlank { "/" }))
            ?.apply()
    }

    private fun saveLocalLibraryBrowseDir(path: String) {
        localLibraryPrefs(activityProvider())?.edit()
            ?.putString(KEY_LOCAL_BROWSE_DIR, localLibraryPathArg(path))
            ?.apply()
    }

    private fun saveWebDavBackupDir(path: String) {
        webDavPrefs(activityProvider())?.edit()
            ?.putString(KEY_BACKUP_DIR, normalizeWebDavPath(path.ifBlank { "/" }))
            ?.apply()
    }

    private fun saveWebDavOrderBy(value: String) {
        val normalized = when (value) {
            "name", "size", "time" -> value
            "file_name", "name_enhanced" -> "name"
            "file_size" -> "size"
            "user_utime", "updated_at", "created_at" -> "time"
            else -> WEBDAV_DEFAULT_ORDER_BY
        }
        webDavPrefs(activityProvider())?.edit()
            ?.putString(KEY_ORDER_BY, normalized)
            ?.apply()
    }

    private fun saveWebDavOrderDirection(value: String) {
        val normalized = if (isDescendingOrder(value)) "1" else "0"
        webDavPrefs(activityProvider())?.edit()
            ?.putString(KEY_ORDER_DIRECTION, normalized)
            ?.apply()
    }

    private fun saveLocalLibraryOrderBy(value: String) {
        val normalized = when (value) {
            "file_name", "file_size", "user_utime" -> value
            "name", "name_enhanced" -> "file_name"
            "size" -> "file_size"
            "time", "updated_at", "created_at" -> "user_utime"
            else -> LOCAL_LIBRARY_DEFAULT_ORDER_BY
        }
        localLibraryPrefs(activityProvider())?.edit()
            ?.putString(KEY_LOCAL_ORDER_BY, normalized)
            ?.apply()
    }

    private fun saveLocalLibraryOrderDirection(value: String) {
        val normalized = if (isDescendingOrder(value)) "1" else "0"
        localLibraryPrefs(activityProvider())?.edit()
            ?.putString(KEY_LOCAL_ORDER_DIRECTION, normalized)
            ?.apply()
    }

    private fun clearWebDavLogin() {
        webDavPrefs(activityProvider())?.edit()
            ?.remove(KEY_URL)
            ?.remove(KEY_USERNAME)
            ?.remove(KEY_PASSWORD)
            ?.remove(KEY_AUTHORIZED)
            ?.apply()
        synchronized(webDavLibraryLock) {
            webDavLibraryFlow = null
        }
    }

    private fun childWebDavPath(parent: String, name: String): String {
        val cleanName = name.trim().trim('/')
        return normalizeWebDavPath("${normalizeWebDavPath(parent).trimEnd('/')}/$cleanName")
    }

    private fun webDavPathArg(path: String): String =
        if (path.isBlank() || path == "root") "/" else normalizeWebDavPath(path)

    private fun localLibraryPathArg(path: String): String =
        path.ifBlank { LOCAL_LIBRARY_ROOT_PATH }.let {
            if (it == "root" || it == "/") LOCAL_LIBRARY_ROOT_PATH else it
        }

    private fun localLibraryFolderUris(): List<String> =
        localLibraryPrefs(activityProvider())
            ?.getString(KEY_LOCAL_FOLDER_URIS, "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun saveLocalLibraryFolderUris(uris: List<String>) {
        localLibraryPrefs(activityProvider())?.edit()
            ?.putString(KEY_LOCAL_FOLDER_URIS, uris.distinct().joinToString("\n"))
            ?.apply()
    }

    private fun clearLocalLibraryListCache() {
        localLibraryListCache.clear()
        localLibrarySearchIndex = null
    }

    private fun localLibraryRootsKey(): String =
        localLibraryFolderUris().joinToString("|")

    private fun addLocalLibraryFolder(uri: Uri) {
        val value = uri.toString()
        if (value.isBlank()) return
        clearLocalLibraryListCache()
        saveLocalLibraryFolderUris(localLibraryFolderUris() + value)
        saveLocalLibraryBrowseDir(LOCAL_LIBRARY_ROOT_PATH)
        synchronized(localLibraryLock) {
            localLibraryFlow = localLibraryFlow ?: createMutableStateFlow(emptyPagingData())
        }
        updateLocalLibraryStorageTrees(LOCAL_LIBRARY_ROOT_PATH)
        refreshLocalLibraryAsync(LOCAL_LIBRARY_ROOT_PATH)
    }

    private fun removeLocalLibraryFolder(uriString: String) {
        clearLocalLibraryListCache()
        val next = localLibraryFolderUris().filter { it != uriString }
        saveLocalLibraryFolderUris(next)
        saveLocalLibraryBrowseDir(LOCAL_LIBRARY_ROOT_PATH)
        runCatching {
            val uri = Uri.parse(uriString)
            currentContext()?.contentResolver?.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        synchronized(localLibraryLock) {
            localLibraryFlow = localLibraryFlow ?: createMutableStateFlow(emptyPagingData())
        }
        updateLocalLibraryStorageTrees(LOCAL_LIBRARY_ROOT_PATH)
        refreshLocalLibraryAsync(LOCAL_LIBRARY_ROOT_PATH)
    }

    private fun launchLocalLibraryFolderPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
            activity.startActivityForResult(intent, REQUEST_LOCAL_LIBRARY_DIR)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to launch local library folder picker: ${it.stackTraceToString()}")
            activity.toast("无法打开目录选择")
        }
    }

    private fun localLibraryFolderSummary(): String {
        val count = localLibraryFolderUris().size
        return if (count <= 0) LOCAL_LIBRARY_PICK_FOLDER_TEXT else "$count 个文件夹"
    }

    private fun localLibraryUsedSize(): Long =
        localLibraryRoots().sumOf { it.size.coerceAtLeast(0L) }

    private fun localLibraryRoots(): List<LocalLibraryEntry> {
        val context = currentContext() ?: return emptyList()
        return localLibraryFolderUris().mapNotNull { raw ->
            val treeUri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@mapNotNull null
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return@mapNotNull null
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val name = queryDocumentName(context, documentUri).ifBlank {
                treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { LOCAL_LIBRARY_TITLE } ?: LOCAL_LIBRARY_TITLE
            }
            LocalLibraryEntry(
                name = name,
                path = encodeLocalLibraryPath(treeUri.toString(), docId),
                treeUri = treeUri.toString(),
                documentId = docId,
                isDirectory = true,
                size = 0L,
                updatedAt = System.currentTimeMillis(),
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun localLibraryReadablePath(entry: LocalLibraryEntry): String =
        localLibraryReadablePath(entry.treeUri, entry.documentId).ifBlank { entry.name }

    private fun localLibraryReadablePath(treeUri: String, documentId: String): String {
        val cleanId = documentId.replace('\\', '/')
        return when {
            cleanId.startsWith("primary:", ignoreCase = true) ->
                "0/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
            cleanId.startsWith("home:", ignoreCase = true) ->
                "/Documents/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
            cleanId.contains(':') ->
                "/" + cleanId.substringAfter(':').trim('/').ifBlank { cleanId.substringBefore(':') }
            cleanId.isNotBlank() -> "/$cleanId"
            else -> Uri.parse(treeUri).lastPathSegment.orEmpty()
        }.replace("//", "/").trimEnd('/').let { path ->
            if (path == "0") "0/" else path.ifBlank { "/" }
        }
    }

    private fun isDescendingOrder(direction: String): Boolean =
        direction == "1" || direction.equals("DESC", ignoreCase = true) || direction.equals("desc", ignoreCase = true)

    private fun orderKind(orderBy: String): String =
        when (orderBy) {
            "size", "file_size" -> "size"
            "time", "user_utime", "updated_at", "created_at" -> "time"
            else -> "name"
        }

    private fun <T> sortStorageEntries(
        entries: List<T>,
        orderBy: String,
        direction: String,
        isDirectory: (T) -> Boolean,
        name: (T) -> String,
        size: (T) -> Long,
        updatedAt: (T) -> Long,
    ): List<T> {
        val fieldComparator = when (orderKind(orderBy)) {
            "size" -> compareBy<T> { size(it) }.thenBy { name(it).lowercase(Locale.ROOT) }
            "time" -> compareBy<T> { updatedAt(it) }.thenBy { name(it).lowercase(Locale.ROOT) }
            else -> compareBy { name(it).lowercase(Locale.ROOT) }
        }
        val comparator = if (isDescendingOrder(direction)) fieldComparator.reversed() else fieldComparator
        return entries.filter(isDirectory).sortedWith(comparator) + entries.filterNot(isDirectory).sortedWith(comparator)
    }

    private fun sortWebDavEntries(entries: List<WebDavEntry>): List<WebDavEntry> =
        sortStorageEntries(
            entries = entries,
            orderBy = currentWebDavOrderBy(),
            direction = currentWebDavOrderDirection(),
            isDirectory = { it.isDirectory },
            name = { it.name },
            size = { it.size },
            updatedAt = { it.updatedAt },
        )

    private fun sortLocalLibraryEntries(entries: List<LocalLibraryEntry>): List<LocalLibraryEntry> =
        sortStorageEntries(
            entries = entries,
            orderBy = currentLocalLibraryOrderBy(),
            direction = currentLocalLibraryOrderDirection(),
            isDirectory = { it.isDirectory },
            name = { it.name },
            size = { it.size },
            updatedAt = { it.updatedAt },
        )

    private fun listLocalLibrary(path: String): List<LocalLibraryEntry> {
        val normalized = localLibraryPathArg(path)
        if (normalized == LOCAL_LIBRARY_ROOT_PATH) return sortLocalLibraryEntries(localLibraryRoots())
        localLibraryListCache[normalized]?.takeIf {
            System.currentTimeMillis() - it.createdAtMs <= LOCAL_LIBRARY_LIST_CACHE_TTL_MS
        }?.let {
            return sortLocalLibraryEntries(it.entries)
        }
        val context = currentContext() ?: return emptyList()
        val decoded = decodeLocalLibraryPath(normalized) ?: return emptyList()
        val treeUri = Uri.parse(decoded.treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, decoded.documentId)
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val docIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val docId = cursor.stringAt(docIdIndex)
                        val name = cursor.stringAt(nameIndex)
                        if (docId.isBlank() || name.isBlank()) continue
                        if (name.startsWith(".")) continue
                        val mime = cursor.stringAt(mimeIndex)
                        val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        add(
                            LocalLibraryEntry(
                                name = name,
                                path = encodeLocalLibraryPath(decoded.treeUri, docId),
                                treeUri = decoded.treeUri,
                                documentId = docId,
                                isDirectory = isDirectory,
                                size = cursor.longAt(sizeIndex),
                                updatedAt = cursor.longAt(modifiedIndex).takeIf { it > 0L } ?: System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to list local library $normalized: ${it.stackTraceToString()}")
            emptyList()
        }.also {
            localLibraryListCache[normalized] = LocalLibraryListCache(it, System.currentTimeMillis())
        }.let(::sortLocalLibraryEntries)
    }

    private fun localLibraryEntry(path: String): LocalLibraryEntry? {
        val decoded = decodeLocalLibraryPath(path) ?: return null
        val context = currentContext() ?: return null
        val treeUri = Uri.parse(decoded.treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, decoded.documentId)
        return queryDocumentEntry(context, documentUri, decoded.treeUri, decoded.documentId)
    }

    private fun createLocalLibraryFolder(parentPath: String, name: String) {
        val context = currentContext() ?: error("Android context not available")
        val parent = decodeLocalLibraryPath(parentPath) ?: error("Invalid local library folder")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(parent.treeUri), parent.documentId)
        DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name.trim().ifBlank { "新建文件夹" },
        ) ?: error("Create folder failed")
        clearLocalLibraryListCache()
    }

    private fun renameLocalLibraryEntry(path: String, newName: String) {
        val context = currentContext() ?: error("Android context not available")
        val entry = localLibraryEntry(path) ?: error("File not found")
        val documentUri = localDocumentUri(entry)
        val targetName = if (entry.isDirectory || newName.contains('.')) {
            newName.trim()
        } else {
            val extension = entry.name.substringAfterLast('.', "")
            if (extension.isBlank()) newName.trim() else "${newName.trim()}.$extension"
        }
        DocumentsContract.renameDocument(context.contentResolver, documentUri, targetName)
            ?: error("Rename failed")
        clearLocalLibraryListCache()
    }

    private fun deleteLocalLibraryEntry(path: String) {
        val context = currentContext() ?: error("Android context not available")
        val entry = localLibraryEntry(path) ?: error("File not found")
        if (!DocumentsContract.deleteDocument(context.contentResolver, localDocumentUri(entry))) {
            error("Delete failed")
        }
        clearLocalLibraryListCache()
    }

    private fun moveLocalLibraryEntry(path: String, destPath: String) {
        val context = currentContext() ?: error("Android context not available")
        val entry = localLibraryEntry(path) ?: error("File not found")
        val sourceParent = parentLocalLibraryPath(path).let(::decodeLocalLibraryPath)
            ?: error("Invalid source parent")
        val dest = decodeLocalLibraryPath(destPath) ?: error("Invalid target folder")
        val resolver = context.contentResolver
        val moved = DocumentsContract.moveDocument(
            resolver,
            localDocumentUri(entry),
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(sourceParent.treeUri), sourceParent.documentId),
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(dest.treeUri), dest.documentId),
        )
        if (moved == null) error("Move failed")
        clearLocalLibraryListCache()
    }

    private fun parentLocalLibraryPath(path: String): String {
        val decoded = decodeLocalLibraryPath(path) ?: return LOCAL_LIBRARY_ROOT_PATH
        val root = localLibraryRoots().firstOrNull { it.treeUri == decoded.treeUri }
        if (root == null || root.documentId == decoded.documentId) return LOCAL_LIBRARY_ROOT_PATH
        val parentId = decoded.documentId.substringBeforeLast('/', missingDelimiterValue = root.documentId)
        return encodeLocalLibraryPath(decoded.treeUri, parentId)
    }

    private fun queryDocumentEntry(context: Context, documentUri: Uri, treeUri: String, documentId: String): LocalLibraryEntry? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mime = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                LocalLibraryEntry(
                    name = name.ifBlank { LOCAL_LIBRARY_TITLE },
                    path = encodeLocalLibraryPath(treeUri, documentId),
                    treeUri = treeUri,
                    documentId = documentId,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)),
                    updatedAt = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                        .takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
            }
        }.getOrNull()
    }

    private fun queryDocumentName(context: Context, documentUri: Uri): String =
        runCatching {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else ""
            }.orEmpty()
        }.getOrDefault("")

    private fun encodeLocalLibraryPath(treeUri: String, documentId: String): String =
        "$LOCAL_LIBRARY_PATH_PREFIX${treeUri.localPathPart()}:${documentId.localPathPart()}"

    private fun decodeLocalLibraryPath(path: String): LocalLibraryPath? {
        if (!path.startsWith(LOCAL_LIBRARY_PATH_PREFIX)) return null
        val body = path.removePrefix(LOCAL_LIBRARY_PATH_PREFIX)
        val parts = body.split(':', limit = 2)
        if (parts.size != 2) return null
        return LocalLibraryPath(
            treeUri = parts[0].decodeLocalPathPart(),
            documentId = parts[1].decodeLocalPathPart(),
        )
    }

    private fun localDocumentUri(entry: LocalLibraryEntry): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(entry.treeUri), entry.documentId)

    private fun listWebDav(path: String): List<WebDavEntry> {
        val credentials = webDavCredentials() ?: return emptyList()
        val normalizedPath = normalizeWebDavPath(path.ifBlank { "/" })
        val requestUrl = buildWebDavUrl(credentials.url, normalizedPath, directory = true)
        logWebDav("PROPFIND ${requestUrl.toString().redactWebDavUrl()}")
        return runCatching {
            val response = webDavRequest(
                credentials = credentials,
                method = "PROPFIND",
                path = normalizedPath,
                directory = true,
                body = WEBDAV_PROPFIND_BODY,
                headers = mapOf(
                    "Depth" to "1",
                    "Content-Type" to "application/xml; charset=utf-8",
                ),
            )
            val code = response.code
            val responseBody = response.bodyString.orEmpty()
            logWebDav("PROPFIND response path=$normalizedPath code=$code bytes=${responseBody.length}")
            if (code !in 200..299 && code != 207) {
                throw WebDavHttpException(code, "WebDAV PROPFIND failed: HTTP $code")
            }
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8)))
            val nodes = document.getElementsByTagNameNS("*", "response")
            val current = normalizedPath
            (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as? Element ?: return@mapNotNull null
                val href = element.textOf("href")
                val entryPath = normalizeWebDavPath(pathFromHref(credentials.url, href))
                if (entryPath == current) return@mapNotNull null
                val name = element.textOf("displayname").ifBlank { entryPath.substringAfterLast('/') }
                if (name.isBlank()) return@mapNotNull null
                val isDirectory = element.getElementsByTagNameNS("*", "collection").length > 0
                WebDavEntry(
                    name = name,
                    path = entryPath,
                    isDirectory = isDirectory,
                    size = element.textOf("getcontentlength").toLongOrNull() ?: 0L,
                    updatedAt = parseWebDavDate(element.textOf("getlastmodified")),
                )
            }.filterNot { it.name.startsWith(".") }
                .let(::sortWebDavEntries)
        }.getOrElse {
            logWebDav("failed to list $normalizedPath: ${it.stackTraceToString()}")
            if (it is WebDavHttpException && it.code == 404 && normalizedPath != "/") {
                logWebDav("dir $normalizedPath not found, fallback to / without changing saved dirs")
                listWebDav("/")
            } else {
                emptyList()
            }
        }.also { entries ->
            logWebDav("list path=$normalizedPath entries=${entries.size}")
        }
    }

    private fun webDavMkcol(path: String) {
        val credentials = webDavCredentials() ?: error("WebDAV not authorized")
        val code = webDavRequest(credentials, "MKCOL", normalizeWebDavPath(path)).code
        if (code !in 200..299 && code != 405) {
            error("WebDAV MKCOL failed: HTTP $code")
        }
    }

    private fun webDavMove(from: String, to: String) {
        val credentials = webDavCredentials() ?: error("WebDAV not authorized")
        val code = webDavRequest(
            credentials = credentials,
            method = "MOVE",
            path = normalizeWebDavPath(from),
            headers = mapOf(
                "Destination" to buildWebDavUrl(credentials.url, normalizeWebDavPath(to)).toString(),
                "Overwrite" to "T",
            ),
        ).code
        if (code !in 200..299 && code != 207) {
            error("WebDAV MOVE failed: HTTP $code")
        }
    }

    private fun webDavDelete(path: String) {
        val credentials = webDavCredentials() ?: error("WebDAV not authorized")
        val code = webDavRequest(credentials, "DELETE", normalizeWebDavPath(path)).code
        if (code !in 200..299 && code != 404) {
            error("WebDAV DELETE failed: HTTP $code")
        }
    }

    private fun webDavDownload(path: String, outputFile: File, onProgress: ((Int) -> Unit)? = null) {
        val credentials = webDavCredentials() ?: error("WebDAV not authorized")
        outputFile.parentFile?.mkdirs()
        webDavRequestToFile(credentials, "GET", normalizeWebDavPath(path), outputFile, onProgress)
    }

    private fun webDavUpload(path: String, file: File) {
        val credentials = webDavCredentials() ?: error("WebDAV not authorized")
        val normalizedPath = normalizeWebDavPath(path)
        ensureWebDavParentDirs(normalizedPath)
        var response = webDavPut(credentials, normalizedPath, file)
        if (response.code in WEBDAV_UPLOAD_RETRY_CODES) {
            logWebDav("PUT retry path=$normalizedPath code=${response.code} body=${response.bodyString.orEmpty().take(160)}")
            runCatching { webDavDelete(normalizedPath) }
                .onFailure { logWebDav("PUT retry delete skipped path=$normalizedPath error=${it.message}") }
            ensureWebDavParentDirs(normalizedPath)
            response = webDavPut(credentials, normalizedPath, file)
        }
        if (response.code !in 200..299 && response.code != 201 && response.code != 204) {
            if (response.code == 405 && tryAlistUploadFallback(credentials, normalizedPath, file)) {
                return
            }
            val detail = response.bodyString.orEmpty().take(200).ifBlank { "no response body" }
            error("WebDAV PUT failed: HTTP ${response.code}, $detail")
        }
    }

    private fun webDavPut(credentials: WebDavCredentials, path: String, file: File): WebDavHttpResponse =
        executeOkHttpRequest(
            credentials = credentials,
            method = "PUT",
            path = path,
            directory = false,
            body = null,
            headers = mapOf(
                "Content-Type" to EPUB_MIME_TYPE,
                "Overwrite" to "T",
            ),
            requestBodyOverride = newOkHttpFileRequestBody(file, EPUB_MIME_TYPE),
        )

    private fun ensureWebDavParentDirs(path: String) {
        val parent = parentWebDavPath(path)
        if (parent == "/" || parent.isBlank()) return
        val segments = parent.trim('/').split('/').filter { it.isNotBlank() }
        var current = ""
        segments.forEach { segment ->
            current = childWebDavPath(current.ifBlank { "/" }, segment)
            runCatching { webDavMkcol(current) }
                .onFailure { logWebDav("MKCOL skipped path=$current error=${it.message}") }
        }
    }

    private fun tryAlistUploadFallback(credentials: WebDavCredentials, path: String, file: File): Boolean =
        runCatching {
            val apiBase = alistApiBaseUrl(credentials.url) ?: return false
            val token = alistToken(apiBase, credentials.username, credentials.password) ?: return false
            val upload = executeRawOkHttpRequest(
                url = "$apiBase/fs/put",
                method = "PUT",
                headers = mapOf(
                    "Authorization" to token,
                    "File-Path" to normalizeWebDavPath(path),
                    "As-Task" to "false",
                    "Content-Type" to EPUB_MIME_TYPE,
                ),
                requestBodyOverride = newOkHttpFileRequestBody(file, EPUB_MIME_TYPE),
            )
            val ok = upload.code in 200..299 && alistResponseOk(upload.bodyString.orEmpty())
            logWebDav("AList upload fallback path=$path code=${upload.code} ok=$ok body=${upload.bodyString.orEmpty().take(160)}")
            ok
        }.onFailure {
            logWebDav("AList upload fallback failed path=$path error=${it.message}")
        }.getOrDefault(false)

    private fun alistApiBaseUrl(webDavUrl: String): String? =
        runCatching {
            val uri = URI(webDavUrl.trimEnd('/'))
            val path = uri.path.orEmpty().trimEnd('/')
            if (!path.endsWith("/dav", ignoreCase = true)) return null
            val apiPath = path.removeSuffix("/dav").ifBlank { "" } + "/api"
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, apiPath, null, null).toString().trimEnd('/')
        }.getOrNull()

    private fun alistToken(apiBase: String, username: String, password: String): String? {
        val login = executeRawOkHttpRequest(
            url = "$apiBase/auth/login",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            requestBodyOverride = newOkHttpStringRequestBody(
                """{"username":${username.jsonQuote()},"password":${password.jsonQuote()}}""",
                "application/json; charset=utf-8",
            ),
        )
        if (login.code !in 200..299) {
            logWebDav("AList login failed code=${login.code} body=${login.bodyString.orEmpty().take(160)}")
            return null
        }
        return Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(login.bodyString.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun alistResponseOk(body: String): Boolean =
        Regex(""""code"\s*:\s*200""").containsMatchIn(body) ||
            Regex(""""message"\s*:\s*"success"""", RegexOption.IGNORE_CASE).containsMatchIn(body)

    private fun webDavRequest(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        directory: Boolean = false,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebDavHttpResponse {
        val response = executeOkHttpRequest(credentials, method, path, directory, body, headers)
        return WebDavHttpResponse(
            code = response.code,
            bodyString = response.bodyString ?: "",
        )
    }

    private fun webDavRequestToFile(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val response = executeOkHttpRequest(credentials, method, path, false, null, emptyMap(), outputFile, onProgress)
        if (response.code !in 200..299) {
            error("WebDAV $method failed: HTTP ${response.code}")
        }
    }

    private fun executeOkHttpRequest(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        directory: Boolean,
        body: String?,
        headers: Map<String, String>,
        outputFile: File? = null,
        onProgress: ((Int) -> Unit)? = null,
        requestBodyOverride: Any? = null,
    ): WebDavHttpResponse {
        val url = buildWebDavUrl(credentials.url, path, directory).toString()
        val requestBuilder = cls(OKHTTP_REQUEST_BUILDER_CLASS).getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        requestBuilder.javaClass.getDeclaredMethod("url", String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, url)

        val token = Base64.encodeToString(
            "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, "Authorization", "Basic $token")
        headers.forEach { (name, value) ->
            requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(requestBuilder, name, value)
        }

        val requestBody = requestBodyOverride ?: body?.let { newOkHttpRequestBody(it) }
        requestBuilder.javaClass.getDeclaredMethod("method", String::class.java, cls(OKHTTP_REQUEST_BODY_CLASS))
            .apply { isAccessible = true }
            .invoke(requestBuilder, method, requestBody)
        val request = requestBuilder.javaClass.getDeclaredMethod("build")
            .apply { isAccessible = true }
            .invoke(requestBuilder)

        val client = okHttpClient()
        val call = client.javaClass.getDeclaredMethod("newCall", cls(OKHTTP_REQUEST_CLASS))
            .apply { isAccessible = true }
            .invoke(client, request)
        val response = withWebDavCleartextAllowed {
            call.javaClass.getDeclaredMethod("execute")
                .apply { isAccessible = true }
                .invoke(call)
        }
        try {
            val code = response.javaClass.getDeclaredMethod("code")
                .apply { isAccessible = true }
                .invoke(response) as Int
            val bodyObj = response.javaClass.getDeclaredMethod("body")
                .apply { isAccessible = true }
                .invoke(response)
            val bodyString = if (outputFile == null) {
                bodyObj?.javaClass?.methods?.firstOrNull { it.name == "string" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(bodyObj)
                    ?.toString()
            } else {
                if (code in 200..299) {
                    val input = bodyObj?.javaClass?.methods?.firstOrNull { it.name == "byteStream" && it.parameterTypes.isEmpty() }
                        ?.apply { isAccessible = true }
                        ?.invoke(bodyObj) as? java.io.InputStream
                        ?: error("WebDAV $method response has no body")
                    val totalBytes = bodyObj.javaClass.methods.firstOrNull {
                        it.name == "contentLength" && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true }?.invoke(bodyObj) as? Long ?: -1L
                    input.use { source ->
                        outputFile.outputStream().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                copied += read
                                if (totalBytes > 0L) {
                                    onProgress?.invoke(((copied * 100L) / totalBytes).toInt().coerceIn(0, 100))
                                }
                            }
                            onProgress?.invoke(100)
                        }
                    }
                }
                null
            }
            logWebDav("$method response path=${normalizeWebDavPath(path)} code=$code")
            return WebDavHttpResponse(code, bodyString)
        } finally {
            runCatching {
                response.javaClass.getDeclaredMethod("close")
                    .apply { isAccessible = true }
                    .invoke(response)
            }
        }
    }

    private fun executeRawOkHttpRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        requestBodyOverride: Any? = null,
    ): WebDavHttpResponse {
        val requestBuilder = cls(OKHTTP_REQUEST_BUILDER_CLASS).getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        requestBuilder.javaClass.getDeclaredMethod("url", String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, url)
        headers.forEach { (name, value) ->
            requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(requestBuilder, name, value)
        }
        requestBuilder.javaClass.getDeclaredMethod("method", String::class.java, cls(OKHTTP_REQUEST_BODY_CLASS))
            .apply { isAccessible = true }
            .invoke(requestBuilder, method, requestBodyOverride)
        val request = requestBuilder.javaClass.getDeclaredMethod("build")
            .apply { isAccessible = true }
            .invoke(requestBuilder)
        val call = okHttpClient().javaClass.getDeclaredMethod("newCall", cls(OKHTTP_REQUEST_CLASS))
            .apply { isAccessible = true }
            .invoke(okHttpClient(), request)
        val response = withWebDavCleartextAllowed {
            call.javaClass.getDeclaredMethod("execute")
                .apply { isAccessible = true }
                .invoke(call)
        }
        try {
            val code = response.javaClass.getDeclaredMethod("code")
                .apply { isAccessible = true }
                .invoke(response) as Int
            val bodyObj = response.javaClass.getDeclaredMethod("body")
                .apply { isAccessible = true }
                .invoke(response)
            val bodyString = bodyObj?.javaClass?.methods?.firstOrNull {
                it.name == "string" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(bodyObj)?.toString()
            return WebDavHttpResponse(code, bodyString)
        } finally {
            runCatching {
                response.javaClass.getDeclaredMethod("close")
                    .apply { isAccessible = true }
                    .invoke(response)
            }
        }
    }

    private fun okHttpClient(): Any =
        webDavHttpClient ?: synchronized(this) {
            webDavHttpClient ?: cls(OKHTTP_CLIENT_CLASS).getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
                .also { webDavHttpClient = it }
        }

    private fun newOkHttpRequestBody(content: String): Any =
        cls(OKHTTP_REQUEST_BODY_CLASS).getDeclaredMethod(
            "create",
            String::class.java,
            cls(OKHTTP_MEDIA_TYPE_CLASS),
        ).apply { isAccessible = true }.invoke(null, content, null)

    private fun newOkHttpStringRequestBody(content: String, mimeType: String): Any {
        val mediaType = cls(OKHTTP_MEDIA_TYPE_CLASS).getDeclaredMethod("get", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, mimeType)
        val requestBodyClass = cls(OKHTTP_REQUEST_BODY_CLASS)
        return requestBodyClass.declaredMethods.firstOrNull {
            it.name == "create" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java
        }?.apply { isAccessible = true }?.invoke(null, content, mediaType)
            ?: requestBodyClass.declaredMethods.first {
                it.name == "create" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == String::class.java
            }.apply { isAccessible = true }.invoke(null, mediaType, content)
    }

    private fun newOkHttpFileRequestBody(file: File, mimeType: String): Any {
        val mediaType = cls(OKHTTP_MEDIA_TYPE_CLASS).getDeclaredMethod("get", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, mimeType)
        val requestBodyClass = cls(OKHTTP_REQUEST_BODY_CLASS)
        return requestBodyClass.declaredMethods.firstOrNull {
            it.name == "create" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == File::class.java
        }?.apply { isAccessible = true }?.invoke(null, file, mediaType)
            ?: requestBodyClass.declaredMethods.first {
                it.name == "create" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == File::class.java
            }.apply { isAccessible = true }.invoke(null, mediaType, file)
    }

    private inline fun <T> withWebDavCleartextAllowed(block: () -> T): T {
        webDavCleartextAllowed.set(true)
        return try {
            block()
        } finally {
            webDavCleartextAllowed.remove()
        }
    }

    private inline fun <T> withOnlineCleartextAllowed(requestUrl: String, block: () -> T): T {
        rememberCleartextHost(requestUrl)
        return withWebDavCleartextAllowed(block)
    }

    private fun rememberCleartextHost(requestUrl: String) {
        val uri = runCatching { URI(requestUrl) }.getOrNull()
        if (!uri?.scheme.equals("http", ignoreCase = true)) return
        val host = uri?.host.orEmpty().ifBlank {
            runCatching { URL(requestUrl).host }.getOrDefault("")
        }
        if (host.isNotBlank()) {
            webDavCleartextAllowedHosts[host] = true
        }
    }

    private fun buildWebDavUrl(baseUrl: String, path: String, directory: Boolean = false): URL {
        val base = URI(baseUrl.trimEnd('/') + "/")
        val segments = normalizeWebDavPath(path).trim('/').split('/').filter { it.isNotBlank() }
        val encodedPath = segments.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val suffix = if (directory && encodedPath.isNotBlank()) "/" else ""
        return base.resolve(encodedPath + suffix).toURL()
    }

    private fun pathFromHref(baseUrl: String, href: String): String {
        val basePath = runCatching { URI(baseUrl).rawPath.orEmpty() }.getOrDefault("")
        val rawPath = runCatching { URI(href).rawPath }.getOrNull()
            ?: runCatching { URL(href).path }.getOrNull()
            ?: href
        val decodedBase = URLDecoder.decode(basePath, "UTF-8").trimEnd('/')
        val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
        val relative = if (decodedBase.isNotBlank() && decodedPath.startsWith(decodedBase)) {
            decodedPath.removePrefix(decodedBase)
        } else {
            decodedPath
        }
        return relative.ifBlank { "/" }
    }

    private fun parentWebDavPath(path: String): String {
        val normalized = normalizeWebDavPath(path)
        return normalized.substringBeforeLast('/', "").ifBlank { "/" }
    }

    private fun normalizeWebDavPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank() || trimmed == "root") return "/"
        return "/" + trimmed.trim('/').replace(Regex("/{2,}"), "/")
    }

    private fun parseWebDavDate(value: String): Long =
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(value)?.time ?: 0L
        }.getOrDefault(0L)

    private fun Element.textOf(localName: String): String {
        val nodes = getElementsByTagNameNS("*", localName)
        return nodes.item(0)?.textContent?.trim().orEmpty()
    }

    private fun showWebDavAuthIntroPage() {
        val activity = activityProvider()
        if (activity == null) {
            XposedBridge.log("$LOG_PREFIX WebDAV auth page skipped: no activity")
            return
        }
        activity.runOnUiThread {
            webDavAuthDialog?.takeIf { it.isShowing }?.dismiss()
            val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }
            webDavAuthDialog = dialog
            val oldStatusBarColor = activity.window.statusBarColor
            val oldNavigationBarColor = activity.window.navigationBarColor
            val oldSystemUiVisibility = activity.window.decorView.systemUiVisibility
            activity.window.statusBarColor = Color.WHITE
            activity.window.navigationBarColor = Color.WHITE
            activity.window.decorView.systemUiVisibility =
                oldSystemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

            dialog.setContentView(createWebDavAuthIntroView(activity, dialog))
            dialog.setOnDismissListener {
                if (webDavAuthDialog === dialog) {
                    webDavAuthDialog = null
                }
                activity.window.statusBarColor = oldStatusBarColor
                activity.window.navigationBarColor = oldNavigationBarColor
                activity.window.decorView.systemUiVisibility = oldSystemUiVisibility
            }
            dialog.show()
            dialog.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                statusBarColor = Color.WHITE
                navigationBarColor = Color.WHITE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    navigationBarDividerColor = Color.WHITE
                }
                decorView.systemUiVisibility =
                    decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    private fun createWebDavAuthIntroView(activity: Activity, dialog: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val topBar = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(56),
            )
        }
        val backButton = WebDavBackButton(activity).apply {
            setOnClickListener { dialog.dismiss() }
            contentDescription = "返回"
            layoutParams = FrameLayout.LayoutParams(activity.dp(48), activity.dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = activity.dp(4)
            }
        }
        val title = TextView(activity).apply {
            text = WEBDAV_TITLE
            setTextColor(Color.rgb(32, 36, 38))
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.SERIF
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        topBar.addView(backButton)
        topBar.addView(title)

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(28), 0, activity.dp(28), activity.dp(36))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val spacerTop = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.9f)
        }
        val illustration = WebDavEmptyView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(150), activity.dp(116))
        }
        val tips = TextView(activity).apply {
            text = WEBDAV_AUTH_TIPS
            setTextColor(Color.rgb(94, 98, 102))
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.dp(22)
            }
        }
        val button = Button(activity).apply {
            text = "前往登录"
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            includeFontPadding = false
            minHeight = 0
            minWidth = 0
            background = roundedDrawable(Color.rgb(221, 221, 221), activity.dp(7).toFloat())
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                dialog.dismiss()
                showWebDavLoginPage()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(50),
            ).apply {
                topMargin = activity.dp(26)
            }
        }
        val spacerBottom = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1.1f)
        }
        body.addView(spacerTop)
        body.addView(illustration)
        body.addView(tips)
        body.addView(button)
        body.addView(spacerBottom)

        root.addView(topBar)
        root.addView(body)
        return root
    }

    private fun showWebDavLoginPage() {
        val activity = activityProvider()
        if (activity == null) {
            XposedBridge.log("$LOG_PREFIX WebDAV login skipped: no activity")
            return
        }
        activity.runOnUiThread {
            webDavLoginDialog?.takeIf { it.isShowing }?.dismiss()
            val dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }
            webDavLoginDialog = dialog
            val oldStatusBarColor = activity.window.statusBarColor
            val oldNavigationBarColor = activity.window.navigationBarColor
            val oldSystemUiVisibility = activity.window.decorView.systemUiVisibility
            val oldActivityNavContrast = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced
            } else {
                null
            }
            val oldActivityStatusContrast = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                activity.window.isStatusBarContrastEnforced
            } else {
                null
            }
            activity.window.statusBarColor = Color.WHITE
            activity.window.navigationBarColor = Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
                activity.window.isStatusBarContrastEnforced = false
            }
            activity.window.decorView.systemUiVisibility =
                oldSystemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val prefs = activity.getSharedPreferences(WEBDAV_PREFS, Context.MODE_PRIVATE)
            dialog.setContentView(createWebDavLoginView(activity, dialog, prefs))
            dialog.window?.apply {
                attributes = attributes.apply { windowAnimations = 0 }
                setWindowAnimations(0)
            }
            dialog.setOnDismissListener {
                if (webDavLoginDialog === dialog) {
                    webDavLoginDialog = null
                }
                activity.window.statusBarColor = oldStatusBarColor
                activity.window.navigationBarColor = oldNavigationBarColor
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    oldActivityNavContrast?.let { activity.window.isNavigationBarContrastEnforced = it }
                    oldActivityStatusContrast?.let { activity.window.isStatusBarContrastEnforced = it }
                }
                activity.window.decorView.systemUiVisibility = oldSystemUiVisibility
            }
            dialog.show()
            dialog.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                attributes = attributes.apply { windowAnimations = 0 }
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                statusBarColor = Color.WHITE
                navigationBarColor = Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    navigationBarDividerColor = Color.TRANSPARENT
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                    isStatusBarContrastEnforced = false
                }
                decorView.systemUiVisibility =
                    decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    private fun createWebDavLoginView(
        activity: Activity,
        dialog: Dialog,
        prefs: android.content.SharedPreferences,
    ): View =
        createWebDavLoginContent(
            context = activity,
            prefs = prefs,
            onBack = { dialog.dismiss() },
            onSaved = { dialog.dismiss() },
        )

    private fun createWebDavLoginContent(
        context: Context,
        prefs: android.content.SharedPreferences,
        onBack: () -> Unit,
        onSaved: () -> Unit,
    ): View {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, context.dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val topBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.statusBarHeight() + context.dp(64),
            )
        }
        val backButton = WebDavBackButton(context).apply {
            setOnClickListener { onBack() }
            contentDescription = "返回"
            layoutParams = FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.START).apply {
                leftMargin = context.dp(18)
                topMargin = context.statusBarHeight() + context.dp(8)
            }
        }
        topBar.addView(backButton)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(28), context.dp(52), context.dp(28), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = context.dp(28)
            }
        }
        brand.addView(WebDavLogoView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(42), context.dp(42))
        })
        brand.addView(TextView(context).apply {
            text = WEBDAV_TITLE
            setTextColor(Color.rgb(53, 112, 196))
            textSize = 28f
            typeface = android.graphics.Typeface.SERIF
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = context.dp(18)
            }
        })
        val title = TextView(context).apply {
            text = "欢迎登录 WebDAV 账号"
            setTextColor(Color.rgb(34, 38, 40))
            textSize = 26f
            typeface = android.graphics.Typeface.SERIF
            includeFontPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = context.dp(22)
            }
        }
        val server = webDavEditText(context, "服务器地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(prefs.getString(KEY_URL, "").orEmpty())
        }
        val username = webDavEditText(context, "账号", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL).apply {
            setText(prefs.getString(KEY_USERNAME, "").orEmpty())
        }
        val password = webDavEditText(context, "密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val note = TextView(context).apply {
            text = "登录信息仅保存在本机。"
            setTextColor(Color.rgb(139, 143, 148))
            textSize = 15f
            includeFontPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(8)
                leftMargin = context.dp(2)
                rightMargin = context.dp(2)
            }
        }
        val submit = Button(context).apply {
            text = "授权并登录"
            setTextColor(Color.WHITE)
            textSize = 16f
            isEnabled = false
            isAllCaps = false
            typeface = android.graphics.Typeface.SERIF
            includeFontPadding = false
            minHeight = 0
            minWidth = 0
            background = roundedDrawable(Color.rgb(221, 221, 221), context.dp(8).toFloat())
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(50),
            ).apply {
                topMargin = context.dp(22)
            }
            setOnClickListener {
                val url = normalizeServerUrl(server.text?.toString().orEmpty())
                val user = username.text?.toString().orEmpty().trim()
                val pass = password.text?.toString().orEmpty()
                when {
                    url.isBlank() -> context.toast("请输入服务器地址")
                    user.isBlank() -> context.toast("请输入账号")
                    pass.isBlank() -> context.toast("请输入密码")
                    else -> {
                        prefs.edit()
                            .putString(KEY_URL, url)
                            .putString(KEY_USERNAME, user)
                            .putString(KEY_PASSWORD, pass)
                            .putString(KEY_BROWSE_DIR, DEFAULT_DIR)
                            .putBoolean(KEY_AUTHORIZED, true)
                            .apply()
                        XposedBridge.log("$LOG_PREFIX WebDAV login saved: ${url.redactWebDavUrl()}")
                        onWebDavLoginSaved()
                        context.toast("WebDAV 已保存")
                        onSaved()
                    }
                }
            }
        }
        fun updateSubmitState() {
            val enabled = server.text?.toString()?.trim()?.isNotEmpty() == true &&
                username.text?.toString()?.trim()?.isNotEmpty() == true &&
                password.text?.toString()?.isNotEmpty() == true
            submit.isEnabled = enabled
            submit.background = roundedDrawable(
                if (enabled) Color.rgb(75, 175, 167) else Color.rgb(221, 221, 221),
                context.dp(8).toFloat(),
            )
            submit.alpha = if (enabled) 1f else 0.72f
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSubmitState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        server.addTextChangedListener(watcher)
        username.addTextChangedListener(watcher)
        password.addTextChangedListener(watcher)
        updateSubmitState()

        content.addView(brand)
        content.addView(title)
        content.addView(server)
        content.addView(username)
        content.addView(password)
        content.addView(note)
        content.addView(submit)
        root.addView(topBar)
        root.addView(content)
        scroll.addView(root)
        return scroll
    }

    private fun webDavEditText(context: Context, hintText: String, inputTypeValue: Int): EditText =
        EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.rgb(166, 166, 166))
            setTextColor(Color.rgb(34, 38, 40))
            textSize = 16f
            typeface = android.graphics.Typeface.SERIF
            includeFontPadding = false
            inputType = inputTypeValue
            setSingleLine(true)
            background = roundedDrawable(Color.rgb(247, 247, 247), context.dp(8).toFloat())
            setPadding(context.dp(16), 0, context.dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(52),
            ).apply {
                topMargin = context.dp(12)
            }
        }

    private fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun Context.statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun roundedDrawable(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }

    private fun Context.toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun Context.resolveThemeColor(attr: Int, fallback: Int): Int {
        val typedArray = obtainStyledAttributes(intArrayOf(attr))
        return try {
            typedArray.getColor(0, fallback)
        } finally {
            typedArray.recycle()
        }
    }

    private fun pushWebDavIcon() {
        webDavIconDepth.set((webDavIconDepth.get() ?: 0) + 1)
    }

    private fun popWebDavIcon() {
        val next = (webDavIconDepth.get() ?: 0) - 1
        if (next <= 0) {
            webDavIconDepth.remove()
        } else {
            webDavIconDepth.set(next)
        }
    }

    private fun pushLocalLibraryIcon() {
        localLibraryIconDepth.set((localLibraryIconDepth.get() ?: 0) + 1)
    }

    private fun popLocalLibraryIcon() {
        val next = (localLibraryIconDepth.get() ?: 0) - 1
        if (next <= 0) {
            localLibraryIconDepth.remove()
        } else {
            localLibraryIconDepth.set(next)
        }
    }

    private fun pushOnlineCompletionCloudTitle(title: String = ONLINE_COMPLETION_TITLE) {
        onlineCompletionCloudTitleDepth.set((onlineCompletionCloudTitleDepth.get() ?: 0) + 1)
        onlineCompletionCloudTitleText.set(title.ifBlank { ONLINE_COMPLETION_TITLE })
    }

    private fun popOnlineCompletionCloudTitle() {
        val next = (onlineCompletionCloudTitleDepth.get() ?: 0) - 1
        if (next <= 0) {
            onlineCompletionCloudTitleDepth.remove()
            onlineCompletionCloudTitleText.remove()
        } else {
            onlineCompletionCloudTitleDepth.set(next)
        }
    }

    private fun pushWebDavBackupCard() {
        webDavBackupCardDepth.set((webDavBackupCardDepth.get() ?: 0) + 1)
    }

    private fun popWebDavBackupCard() {
        val next = (webDavBackupCardDepth.get() ?: 0) - 1
        if (next <= 0) {
            webDavBackupCardDepth.remove()
        } else {
            webDavBackupCardDepth.set(next)
        }
    }

    private inline fun <T> withWebDavIcon(block: () -> T): T {
        pushWebDavIcon()
        return try {
            block()
        } finally {
            popWebDavIcon()
        }
    }

    private fun getComposeWebDavVector(): Any? {
        composeWebDavVector?.let { return it }
        return runCatching {
            val builderClass = cls(IMAGE_VECTOR_BUILDER_CLASS)
            val markerClass = cls(DEFAULT_CONSTRUCTOR_MARKER_CLASS)
            val builder = builderClass.getDeclaredConstructor(
                String::class.java,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Long.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE,
                markerClass,
            ).apply { isAccessible = true }.newInstance(
                "Colored.WebDAV",
                24f,
                24f,
                24f,
                24f,
                0L,
                0,
                false,
                224,
                null,
            )
            addVectorPath(builderClass, builder, WEBDAV_ICON_BODY_PATH, 0xFF4BAFA7L)
            addVectorPath(builderClass, builder, WEBDAV_ICON_CLOUD_PATH, 0xFFFFFFFFL)
            addVectorPath(builderClass, builder, WEBDAV_ICON_SLOT_PATH, 0xFFFFFFFFL, 0.84f)
            val imageVector = builderClass.getDeclaredMethod("build")
                .apply { isAccessible = true }
                .invoke(builder)
            composeWebDavVector = imageVector
            imageVector
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to build WebDAV compose icon: ${it.stackTraceToString()}")
        }.getOrNull()
    }

    private fun getNativeAndroidOsVector(): Any? =
        runCatching {
            method(ANDROID_OS_ICON_CLASS, ANDROID_OS_ICON_METHOD, 1).invoke(
                null,
                cls("app.zhendong.reamicro.arch.icons.AppIcons\$Colored")
                    .getDeclaredField("INSTANCE")
                    .apply { isAccessible = true }
                    .get(null),
            )
        }.getOrNull()

    private fun addVectorPath(
        builderClass: Class<*>,
        builder: Any,
        pathData: String,
        argb: Long,
        alpha: Float = 1f,
    ) {
        val vectorKt = cls(VECTOR_KT_CLASS)
        val nodes = vectorKt.getDeclaredMethod("addPathNodes", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, pathData)
        val fillType = vectorKt.getDeclaredMethod("getDefaultFillType")
            .apply { isAccessible = true }
            .invoke(null) as Int
        val strokeCap = vectorKt.getDeclaredMethod("getDefaultStrokeLineCap")
            .apply { isAccessible = true }
            .invoke(null) as Int
        val strokeJoin = vectorKt.getDeclaredMethod("getDefaultStrokeLineJoin")
            .apply { isAccessible = true }
            .invoke(null) as Int
        val fill = solidColor(argb)
        val addPath = builderClass.declaredMethods.first {
            it.name.contains("addPath") &&
                !it.name.contains("default") &&
                it.parameterTypes.size == 14
        }.apply { isAccessible = true }
        addPath.invoke(
            builder,
            nodes,
            fillType,
            "",
            fill,
            alpha,
            null,
            1f,
            0f,
            strokeCap,
            strokeJoin,
            4f,
            0f,
            1f,
            0f,
        )
    }

    private fun solidColor(argb: Long): Any {
        val colorKt = cls(COLOR_KT_CLASS)
        val colorMethod = colorKt.declaredMethods.firstOrNull {
            it.name == "Color" && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
        } ?: colorKt.declaredMethods.first {
            it.name == "Color" && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
        }
        colorMethod.isAccessible = true
        val color = if (colorMethod.parameterTypes[0] == java.lang.Long.TYPE) {
            colorMethod.invoke(null, argb) as Long
        } else {
            colorMethod.invoke(null, argb.toInt()) as Long
        }
        return cls(SOLID_COLOR_CLASS).getDeclaredConstructor(
            java.lang.Long.TYPE,
            cls(DEFAULT_CONSTRUCTOR_MARKER_CLASS),
        ).apply { isAccessible = true }.newInstance(color, null)
    }

    private fun String.redactWebDavUrl(): String =
        replace(Regex("""//([^/@]+)@"""), "//***@")

    private fun String.safeWebDavFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "book" }

    private fun importCacheFile(cacheDir: File?, root: String, fileName: String): File =
        File(
            File(cacheDir ?: File("/data/local/tmp"), "${root}/${System.currentTimeMillis()}_${UUID.randomUUID()}"),
            fileName.safeWebDavFileName(),
        )

    private fun String.localPathPart(): String =
        Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun String.decodeLocalPathPart(): String =
        runCatching {
            String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault(this)

    private fun formatCloudUpdatedTime(updatedAt: Long): String =
        runCatching {
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(java.util.Date(updatedAt))
        }.getOrDefault("")

    private fun cloudBookExtensionLabel(extension: String): String =
        if (extension.equals("epub", ignoreCase = true)) "ePub" else extension.uppercase(Locale.ROOT)

    private fun android.database.Cursor.stringAt(index: Int): String =
        if (index < 0 || isNull(index)) "" else getString(index).orEmpty()

    private fun android.database.Cursor.longAt(index: Int): Long =
        if (index < 0 || isNull(index)) 0L else getLong(index)

    private fun logWebDav(message: String) {
        XposedBridge.log("$LOG_PREFIX WebDAV $message")
        runCatching { Log.i(LOG_TAG, message) }
    }

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val candidates = cls(className).methods.asSequence() + cls(className).declaredMethods.asSequence()
                candidates.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = cls(className)
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

    private fun staticInt(className: String, fieldName: String): Int =
        cls(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

    private fun Any.method0(name: String): Any =
        javaClass.methods.first {
            it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
        }.apply { isAccessible = true }.invoke(this)

    private fun Any.longMethod(name: String): Long =
        method0(name) as Long

    private fun invokeFunction1(function: Any, arg: Any?) {
        val invoke = function.javaClass.methods.firstOrNull {
            it.name == "invoke" && it.parameterTypes.size == 1
        } ?: function.javaClass.declaredMethods.first {
            it.name == "invoke" && it.parameterTypes.size == 1
        }
        invoke.isAccessible = true
        invoke.invoke(function, arg)
    }

    private fun invokeFunction2(function: Any, first: Any?, second: Any?) {
        val invoke = function.javaClass.methods.firstOrNull {
            it.name == "invoke" && it.parameterTypes.size == 2
        } ?: function.javaClass.declaredMethods.first {
            it.name == "invoke" && it.parameterTypes.size == 2
        }
        invoke.isAccessible = true
        invoke.invoke(function, first, second)
    }

    private fun Any.callString(methodName: String): String =
        javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(this)
            ?.toString()
            .orEmpty()

    private fun callStringPath(root: Any?, vararg methodNames: String): String {
        var current = root ?: return ""
        methodNames.forEach { name ->
            current = current.javaClass.methods.firstOrNull { it.name == "get${name.replaceFirstChar(Char::titlecase)}" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(current)
                ?: return ""
        }
        return current.toString()
    }

    private fun Any.callLong(methodName: String): Long {
        val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(this)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun Any.callInt(methodName: String): Int {
        val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(this)
        return (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
    }

    private fun Any.callFloat(methodName: String): Float {
        val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(this)
        return (value as? Number)?.toFloat() ?: value?.toString()?.toFloatOrNull() ?: 0f
    }

    private fun Any.invokeNoArg(methodName: String): Any? =
        javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(this)

    private fun newKotlinPair(first: Any?, second: Any?): Any =
        cls(KOTLIN_PAIR_CLASS).getDeclaredConstructor(Any::class.java, Any::class.java)
            .apply { isAccessible = true }
            .newInstance(first, second)

    private fun platformFile(file: File): Any =
        cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.first {
            it.name == PLATFORM_FILE_METHOD && it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java
        }.apply { isAccessible = true }.invoke(null, file)

    private fun enqueueNativeImport(workerManager: Any, platformFile: Any): Any? {
        val enqueueImport = workerManager.javaClass.methods.firstOrNull {
            it.name == WORKER_ENQUEUE_IMPORT_METHOD && it.parameterTypes.size == 1
        } ?: workerManager.javaClass.declaredMethods.first {
            it.name == WORKER_ENQUEUE_IMPORT_METHOD && it.parameterTypes.size == 1
        }
        enqueueImport.isAccessible = true
        return enqueueImport.invoke(workerManager, platformFile)
    }

    private fun rememberPendingWebDavImport(platformFile: Any, localFile: File, sourceUrl: String, sourceSize: Long?) {
        val source = WebDavImportSource(sourceUrl, sourceSize)
        val keys = (platformFileKeys(platformFile) + localFile.absolutePath + runCatching { localFile.canonicalPath }.getOrNull())
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
        keys.forEach { pendingWebDavImports[it] = source }
        logWebDav("pending import keys=${keys.size} url=${sourceUrl.redactWebDavUrl()}")
    }

    private fun platformFileKeys(platformFile: Any): List<String> =
        listOfNotNull(
            platformFileString(platformFile, PLATFORM_FILE_GET_PATH_METHOD),
            platformFileString(platformFile, PLATFORM_FILE_ABSOLUTE_PATH_METHOD),
        ).filter { it.isNotBlank() }.distinct()

    private fun platformFileString(platformFile: Any, methodName: String): String? =
        runCatching {
            cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, platformFile)?.toString()
        }.getOrNull()

    private fun importWebDavDownloadedBook(
        workerManager: Any,
        platformFile: Any,
        sourceUrl: String,
        sourceSize: Long?,
    ): Any? {
        rememberWorkerManager(workerManager)
        val bookshelf = currentBookshelfRepository()
            ?: error("BookshelfRepository not available for WebDAV import")
        val importMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "importBook" && it.parameterTypes.size == 6
            }
            .apply { isAccessible = true }
        return invokeSuspendBlocking(
            importMethod,
            bookshelf,
            platformFile,
            null,
            null,
            sourceUrl.ifBlank { null },
            sourceSize?.takeIf { it > 0L }?.let { java.lang.Long.valueOf(it) },
        )
    }

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = cls(KOTLIN_CONTINUATION_CLASS)
        val throwOnFailure = cls(KOTLIN_RESULT_KT_CLASS).declaredMethods.first {
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
                        error = if (it is InvocationTargetException) {
                            it.targetException ?: it
                        } else {
                            it
                        }
                    }
                    latch.countDown()
                    targetUnit()
                }
                "toString" -> "ReaMicroWebDavContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val fullArgs = args.toMutableList().apply { add(continuation) }.toTypedArray()
        val returned = try {
            method.invoke(target, *fullArgs)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended()) {
            return returned
        }
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun emptyCoroutineContext(): Any =
        cls(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS).getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

    private fun coroutineSuspended(): Any =
        runCatching {
            cls(KOTLIN_INTRINSICS_CLASS).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            (cls(KOTLIN_COROUTINE_SINGLETONS_CLASS).enumConstants ?: emptyArray()).first { value ->
                value.toString() == "COROUTINE_SUSPENDED"
            }
        }

    private fun newWorkHandle(id: String, state: Any): Any =
        cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
            .apply { isAccessible = true }
            .newInstance(id, createMutableStateFlow(state))

    private fun newWorkState(statusName: String, progress: Int, error: String?, result: String?, name: String?): Any {
        val status = cls(WORK_STATUS_CLASS).getDeclaredMethod("valueOf", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, statusName)
        return cls(WORK_STATE_CLASS).getDeclaredConstructor(
            cls(WORK_STATUS_CLASS),
            java.lang.Integer.TYPE,
            String::class.java,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }.newInstance(status, progress, error, result, name)
    }

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = cls(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching { block(args) }
                    .onFailure { XposedBridge.log("$LOG_PREFIX failed in $name callback: ${it.stackTraceToString()}") }
                    .getOrElse { targetUnit() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun targetUnit(): Any? = runCatching {
        val clazz = cls("kotlin.Unit")
        val field = clazz.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.get(null)
    }.getOrNull()

    private data class SyncAuthCardRenderContext(
        val bookId: Long? = null,
        val onSetupDefaultDir: Any? = null,
        val onPick: Any? = null,
        var webDavRendered: Boolean = false,
        var nativeAuthRowsSeen: Int = 0,
    )

    private data class ImportUnauthRenderContext(
        val expectedNativeRows: Int,
        var renderedNativeRows: Int = 0,
        var webDavRendered: Boolean = false,
    )

    private data class ImportLocalLibraryRowContext(
        val navGraphScope: Any?,
        val coroutineScope: Any?,
        val sheetState: Any?,
        val intentReceiver: Any?,
    )

    private data class HomeSearchRenderContext(
        val sections: List<HomeSearchSection>,
        val intentReceiver: Any,
        var rendered: Boolean = false,
    )

    private data class HomeSearchSection(
        val type: Int,
        val title: String = "",
        val results: List<*>,
    )

    private data class OnlineHttpResponse(
        val url: String,
        val body: String,
    )

    private data class OnlineTocSnapshot(
        val detail: OnlineHttpResponse,
        val toc: OnlineHttpResponse,
        val chapters: List<OnlineChapter>,
    )

    private data class OnlineSearchGroup(
        val source: OnlineSourceEntry,
        val query: String,
        val results: List<OnlineBookSearchResult>,
        val error: String,
    )

    private data class OnlineBookSearchResult(
        val sourceName: String,
        val name: String,
        val author: String,
        val coverUrl: String,
        val detailUrl: String,
        val intro: String,
        val chapterCount: Int = 0,
        val status: String = "",
        val wordCount: String = "",
        val updateTime: String = "",
    )

    private data class OnlineResultMetadata(
        val status: String = "",
        val wordCount: String = "",
        val updateTime: String = "",
        val chapterCount: Int = 0,
    )

    private data class OnlineDownloadTarget(
        val source: OnlineSourceEntry,
        val query: String,
        var result: OnlineBookSearchResult,
    )

    private data class OnlineImportedBookSourceInfo(
        val sourceId: String,
        val sourceName: String,
        val detailUrl: String,
        val source: OnlineSourceEntry?,
    )

    private data class OnlineChapterUpdateResult(
        val added: Int,
        val failed: Int,
    )

    private data class OnlineCompletionImportResult(
        val imported: Boolean,
        val bookDir: File?,
    )

    private data class OnlineChapter(
        val title: String,
        val url: String,
        val volumeTitle: String = "",
        val level: Int = 0,
    )

    private data class OnlineDownloadedChapter(
        val title: String,
        val content: String,
        val volumeTitle: String = "",
        val level: Int = 0,
    )

    private data class OnlineBinaryPayload(
        val bytes: ByteArray,
        val mimeType: String,
        val url: String,
    )

    private data class WebDavBackupSnapshot(
        val book: Any,
        val backup: Any,
    )

    private data class WebDavCredentials(
        val url: String,
        val username: String,
        val password: String,
    )

    private data class WebDavEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val updatedAt: Long,
    )

    private data class LocalLibraryEntry(
        val name: String,
        val path: String,
        val treeUri: String,
        val documentId: String,
        val isDirectory: Boolean,
        val size: Long,
        val updatedAt: Long,
    )

    private data class LocalLibraryPath(
        val treeUri: String,
        val documentId: String,
    )

    private data class LocalLibraryListCache(
        val entries: List<LocalLibraryEntry>,
        val createdAtMs: Long,
    )

    private data class LocalLibrarySearchIndex(
        val rootsKey: String,
        val entries: List<LocalLibraryEntry>,
        val createdAtMs: Long,
        val complete: Boolean,
    )

    private data class CloudBookRowExtendedDisplayContext(
        val updatedAt: Long,
        val extensionLabel: String,
        var textIndex: Int = 0,
        var extensionSuppressed: Boolean = false,
    )

    private data class WebDavHttpResponse(
        val code: Int,
        val bodyString: String?,
    )

    private data class WebDavImportSource(
        val sourceUrl: String,
        val sourceSize: Long?,
    )

    private class CancellableWebDavDownload(
        val id: String,
        val key: String,
        val name: String,
        val tracker: Any,
        val localFile: File,
        val createdAtMs: Long,
    ) {
        @Volatile var cancelRequested: Boolean = false
        @Volatile var thread: Thread? = null
    }

    private class NativeCloudDownload(
        val id: String,
        val key: String,
        val name: String,
        val type: Int,
        val tracker: Any,
        val cacheDir: File,
    ) {
        @Volatile var cancelRequested: Boolean = false
    }

    private class OnlineCompletionDownloadTask(
        val notificationId: Int,
        val key: String,
        val name: String,
        val cacheDir: File,
        val tracker: Any?,
        val workId: String?,
    ) {
        @Volatile var cancelRequested: Boolean = false
        @Volatile var thread: Thread? = null
        @Volatile var importedBookDir: File? = null
        val downloadedChapters = ConcurrentHashMap<Int, OnlineDownloadedChapter>()
        val bookDirWriteLock = ReentrantLock()
    }

    private class CacheDeleteStat(
        var files: Int = 0,
        var bytes: Long = 0L,
    )

    private class WebDavHttpException(
        val code: Int,
        message: String,
    ) : IllegalStateException(message)

    private class CloudDownloadCancelledException : CancellationException("download cancelled")
    private class OnlineCompletionDownloadCancelledException : CancellationException("online completion download cancelled")

    private class WebDavBackButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 38, 40)
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val iconSize = dp(32).toFloat()
            val scale = iconSize / 24f
            val dx = (width - iconSize) / 2f
            val dy = (height - iconSize) / 2f
            path.reset()
            path.moveTo(20f, 11f)
            path.lineTo(7.83f, 11f)
            path.lineTo(13.42f, 5.41f)
            path.lineTo(12f, 4f)
            path.lineTo(4f, 12f)
            path.lineTo(12f, 20f)
            path.lineTo(13.42f, 18.59f)
            path.lineTo(7.83f, 13f)
            path.lineTo(20f, 13f)
            path.close()
            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class WebDavLogoView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(75, 175, 167)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)

            paint.color = Color.WHITE
            path.reset()
            path.moveTo(w * 0.28f, h * 0.55f)
            path.cubicTo(w * 0.28f, h * 0.45f, w * 0.38f, h * 0.38f, w * 0.49f, h * 0.41f)
            path.cubicTo(w * 0.54f, h * 0.31f, w * 0.68f, h * 0.28f, w * 0.77f, h * 0.38f)
            path.cubicTo(w * 0.87f, h * 0.39f, w * 0.94f, h * 0.47f, w * 0.94f, h * 0.57f)
            path.cubicTo(w * 0.94f, h * 0.68f, w * 0.85f, h * 0.76f, w * 0.74f, h * 0.76f)
            path.lineTo(w * 0.34f, h * 0.76f)
            path.cubicTo(w * 0.25f, h * 0.76f, w * 0.20f, h * 0.67f, w * 0.28f, h * 0.55f)
            canvas.drawPath(path, paint)

            paint.alpha = 214
            rect.set(w * 0.26f, h * 0.82f, w * 0.74f, h * 0.88f)
            canvas.drawRoundRect(rect, dp(2).toFloat(), dp(2).toFloat(), paint)
            paint.alpha = 255
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class WebDavEmptyView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(243, 246, 247)
            rect.set(w * 0.17f, h * 0.27f, w * 0.83f, h * 0.86f)
            canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)

            paint.color = Color.rgb(225, 232, 234)
            rect.set(w * 0.25f, h * 0.18f, w * 0.75f, h * 0.38f)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)

            paint.color = Color.rgb(75, 175, 167)
            rect.set(w * 0.36f, h * 0.45f, w * 0.64f, h * 0.68f)
            canvas.drawRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), paint)

            paint.color = Color.WHITE
            path.reset()
            path.moveTo(w * 0.43f, h * 0.57f)
            path.cubicTo(w * 0.43f, h * 0.51f, w * 0.49f, h * 0.47f, w * 0.55f, h * 0.49f)
            path.cubicTo(w * 0.57f, h * 0.45f, w * 0.63f, h * 0.43f, w * 0.68f, h * 0.47f)
            path.cubicTo(w * 0.74f, h * 0.47f, w * 0.78f, h * 0.51f, w * 0.78f, h * 0.57f)
            path.cubicTo(w * 0.78f, h * 0.63f, w * 0.73f, h * 0.67f, w * 0.67f, h * 0.67f)
            path.lineTo(w * 0.47f, h * 0.67f)
            path.cubicTo(w * 0.44f, h * 0.67f, w * 0.40f, h * 0.63f, w * 0.43f, h * 0.57f)
            canvas.drawPath(path, paint)

            paint.color = Color.rgb(214, 222, 224)
            rect.set(w * 0.31f, h * 0.76f, w * 0.69f, h * 0.79f)
            canvas.drawRoundRect(rect, dp(2).toFloat(), dp(2).toFloat(), paint)
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class WebDavLoginBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val prefs: android.content.SharedPreferences,
        private val closeWithSlide: () -> Unit,
    ) {
        @JavascriptInterface
        fun back() {
            activity.runOnUiThread {
                closeWithSlide()
            }
        }

        @JavascriptInterface
        fun closeNow() {
            activity.runOnUiThread {
                dialog.dismiss()
            }
        }

        @JavascriptInterface
        fun save(rawUrl: String?, rawUsername: String?, rawPassword: String?) {
            val url = normalizeServerUrl(rawUrl.orEmpty())
            val username = rawUsername.orEmpty().trim()
            val password = rawPassword.orEmpty()
            activity.runOnUiThread {
                when {
                    url.isBlank() -> activity.toast("请输入服务器地址")
                    username.isBlank() -> activity.toast("请输入账号")
                    password.isBlank() -> activity.toast("请输入密码")
                    else -> {
                        prefs.edit()
                            .putString(KEY_URL, url)
                            .putString(KEY_USERNAME, username)
                            .putString(KEY_PASSWORD, password)
                            .putString(KEY_BROWSE_DIR, DEFAULT_DIR)
                            .putBoolean(KEY_AUTHORIZED, true)
                            .apply()
                        XposedBridge.log("$LOG_PREFIX WebDAV login saved: ${url.redactWebDavUrl()}")
                        activity.toast("WebDAV 已保存")
                        dialog.dismiss()
                    }
                }
            }
        }

        private fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        private fun Activity.toast(message: String) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        private fun String.redactWebDavUrl(): String =
            replace(Regex("""//([^/@]+)@"""), "//***@")
    }

    private fun webDavLoginHtml(url: String, username: String): String {
        val escapedUrl = url.htmlAttrEscape()
        val escapedUsername = username.htmlAttrEscape()
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <style>
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                html, body {
                  margin: 0;
                  width: 100%;
                  height: 100%;
                  min-height: 100vh;
                  background: #ffffff;
                  color: #222628;
                  font-family: "Noto Serif CJK SC", "Songti SC", STSong, SimSun, serif;
                  -webkit-text-size-adjust: 100%;
                  text-size-adjust: 100%;
                  overflow-x: hidden;
                }
                body { padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom); }
                body {
                  transform: translateX(0);
                  opacity: 1;
                  transition: transform 260ms cubic-bezier(.2, 0, 0, 1), opacity 220ms linear;
                  will-change: transform, opacity;
                }
                body.closing {
                  transform: translateX(100%);
                  opacity: 0.98;
                }
                .topbar {
                  height: 64px;
                  display: flex;
                  align-items: center;
                  padding: 0 14px;
                }
                .back {
                  width: 40px;
                  height: 40px;
                  border: none;
                  border-radius: 20px;
                  background: transparent;
                  padding: 8px;
                  display: grid;
                  place-items: center;
                }
                main {
                  padding: 18px 28px 32px;
                  max-width: 500px;
                  margin: 0 auto;
                }
                .brand {
                  display: flex;
                  align-items: center;
                  gap: 14px;
                  margin-bottom: 28px;
                }
                .logo {
                  width: 34px;
                  height: 34px;
                  border-radius: 7px;
                  background: #4bafa7;
                  display: grid;
                  place-items: center;
                }
                .brand span {
                  color: #3570c4;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 24px;
                  line-height: 1;
                  font-weight: 600;
                  letter-spacing: 0;
                }
                h1 {
                  margin: 0 0 26px;
                  color: #222628;
                  font-size: 24px;
                  line-height: 1.28;
                  font-weight: 700;
                  letter-spacing: 0;
                }
                .field { margin-top: 12px; }
                input {
                  width: 100%;
                  height: 52px;
                  border: 0;
                  border-radius: 8px;
                  outline: none;
                  background: #f7f7f7;
                  color: #222628;
                  font-family: inherit;
                  font-size: 16px;
                  line-height: 52px;
                  padding: 0 16px;
                  letter-spacing: 0;
                  font-weight: 600;
                }
                input::placeholder { color: #a6a6a6; }
                .note {
                  margin: 10px 2px 0;
                  color: #8b8f94;
                  font-size: 14px;
                  line-height: 1.5;
                }
                .submit {
                  width: 100%;
                  height: 50px;
                  margin-top: 22px;
                  border: 0;
                  border-radius: 8px;
                  background: #dddddd;
                  color: #ffffff;
                  font-family: inherit;
                  font-size: 16px;
                  font-weight: 700;
                  letter-spacing: 0;
                }
              </style>
            </head>
            <body>
              <div class="topbar">
                <button class="back" type="button" aria-label="返回" onclick="ReaMicroWebDav.back()">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M20 11H7.83L13.42 5.41L12 4L4 12L12 20L13.42 18.59L7.83 13H20V11Z" fill="#222628"/>
                  </svg>
                </button>
              </div>
              <main>
                <div class="brand">
                  <div class="logo" aria-hidden="true">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                      <path d="M8.6 14.4c-1.35 0-2.35-.96-2.35-2.15 0-1.22 1-2.12 2.34-2.12.23 0 .47.03.7.1C9.95 8.9 11.1 8.15 12.45 8.15c1.74 0 3.13 1.13 3.5 2.73 1.08.15 1.92 1.03 1.92 2.12 0 1.2-.97 2.15-2.22 2.15H8.6Z" fill="white"/>
                      <rect x="6.9" y="17" width="10.2" height="1.35" rx=".65" fill="white" opacity=".84"/>
                    </svg>
                  </div>
                  <span>WebDAV</span>
                </div>
                <h1>欢迎登录 WebDAV 账号</h1>
                <div class="field">
                  <input id="server" value="$escapedUrl" autocomplete="url" inputmode="url" placeholder="服务器地址">
                </div>
                <div class="field">
                  <input id="username" value="$escapedUsername" autocomplete="username" placeholder="账号">
                </div>
                <div class="field">
                  <input id="password" autocomplete="current-password" placeholder="密码" type="password">
                </div>
                <p class="note">登录信息仅保存在本机。</p>
                <button class="submit" type="button" onclick="submitWebDav()">授权并登录</button>
              </main>
              <script>
                let closing = false;
                window.ReaMicroClose = function() {
                  if (closing) return;
                  closing = true;
                  document.body.classList.add('closing');
                  setTimeout(function() {
                    ReaMicroWebDav.closeNow();
                  }, 230);
                };
                function submitWebDav() {
                  ReaMicroWebDav.save(
                    document.getElementById('server').value,
                    document.getElementById('username').value,
                    document.getElementById('password').value
                  );
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.htmlAttrEscape(): String =
        replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun String.jsonQuote(): String =
        buildString {
            append('"')
            this@jsonQuote.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

    private companion object {
        const val LOG_TAG = "ReaMicroWebDAV"
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BACKUP_TYPE_CLASS = "app.zhendong.reamicro.constants.BackupType"
        const val BACKUP_TYPE_NAME_METHOD = "getName"
        const val AUTH_CARD_CLASS = "app.zhendong.reamicro.ui.backup.components.AuthCardKt"
        const val AUTH_CARD_METHOD = "AuthCard"
        const val AUTH_CARD_CONTENT_METHOD = "AuthCard\$lambda\$5"
        const val DRIVE_AUTH_CARD_METHOD = "DriveAuthCard"
        const val DRIVE_OTHER_AVAILABLE_CARD_METHOD = "DriveOtherAvailableCard"
        const val BOOK_LIBRARY_SHEET_CLASS = "app.zhendong.reamicro.ui.home.components.BookLibrarySheetKt"
        const val BOOK_LIBRARY_AUTH_LIST_METHOD = "BookLibrarySheet\$lambda\$9\$0\$1"
        const val BOOK_LIBRARY_AUTH_ROW_CLICK_METHOD = "BookLibrarySheet\$lambda\$9\$0\$1\$0\$1\$0\$0"
        const val BOOK_LIBRARY_LOCAL_METHOD = "LocalLibrary"
        const val BOOK_LIBRARY_UNAUTH_LIST_METHOD = "BookLibrarySheet\$lambda\$9\$0\$2\$0\$1"
        const val CLOUD_AUTHORIZED_ROW_METHOD = "CloudAuthorizedRow"
        const val CLOUD_AUTHORIZED_LABEL_METHOD = "CloudAuthorizedRow\$lambda\$1"
        const val CLOUD_UNAUTH_ROW_METHOD = "CloudUnauthRow"
        const val CLOUD_UNAUTH_LABEL_METHOD = "CloudUnauthRow\$lambda\$1"
        const val CLOUD_AUTHORIZED_DETAIL_METHOD = "CloudAuthorizedRow\$lambda\$3"
        const val CLOUD_STORAGE_SCREEN_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageScreenKt"
        const val CLOUD_STORAGE_SCREEN_METHOD = "CloudStorageScreen"
        const val CLOUD_STORAGE_BAR_METHOD = "CloudStorageBar"
        const val CLOUD_STORAGE_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageViewModel"
        const val CLOUD_STORAGE_UI_STATE_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageUiState"
        const val CLOUD_STORAGE_UI_EVENT_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageUIEvent"
        const val CLOUD_STORAGE_UI_EVENT_TAP_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageUIEvent\$Tap"
        const val CLOUD_STORAGE_ON_INTENT_METHOD = "onIntent"
        const val CLOUD_STORAGE_REPOSITORY_CLASS = "app.zhendong.reamicro.repository.storage.CloudStorageRepository"
        const val CLOUD_STORAGE_GET_AUTH = "getAuth"
        const val CLOUD_STORAGE_GET_USER_INFO = "getUserInfo"
        const val CLOUD_STORAGE_GET_LIBRARY = "getLibrary"
        const val CLOUD_TREE_CLASS = "app.zhendong.reamicro.ui.storage.components.CloudTreeKt"
        const val CLOUD_TREE_METHOD = "CloudTree"
        const val HOME_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.home.HomeViewModel"
        const val HOME_SEARCH_METHOD = "search"
        const val HOME_SEARCH_BAR_CLASS = "app.zhendong.reamicro.ui.home.components.HomeSearchBarKt"
        const val HOME_SEARCH_RESULT_LAZY_METHOD = "SearchResult\$lambda\$0\$0"
        const val HOME_CLOUD_RESULT_LIST_METHOD = "CloudResultList"
        const val HOME_CLOUD_BOOK_ROW_METHOD = "CloudBookRow"
        const val HOME_SEARCH_TAP_METHOD = "SearchResult\$lambda\$0\$0\$1\$0\$0\$0\$0"
        const val BOOK_LOCAL_SHEET_CLASS = "app.zhendong.reamicro.ui.home.components.BookLocalSheetKt"
        const val BOOK_LOCAL_SHEET_METHOD = "BookLocalSheet"
        const val BOOK_LOCAL_SHEET_CONTENT_METHOD = "BookLocalSheet\$lambda\$2"
        const val FILE_BACKUP_METHOD = "FileBackup"
        const val FOOTER_CLASS = "app.zhendong.reamicro.arch.components.item.FooterKt"
        const val FOOTER_METHOD = "footer"
        const val CLOUD_BOOK_LIST_CLASS = "app.zhendong.reamicro.ui.storage.components.CloudBookListKt"
        const val CLOUD_BOOK_ROW_METHOD = "CloudBookRow"
        const val CLOUD_BOOK_CLASS = "app.zhendong.reamicro.data.storage.CloudBook"
        const val CLOUD_FOLDER_CLASS = "app.zhendong.reamicro.data.storage.CloudFolder"
        const val BOOK_CLASS = "app.zhendong.reamicro.data.db.entity.Book"
        const val BOOK_ROW_INFO_CLASS = "app.zhendong.reamicro.ui.storage.components.BookRowInfoKt"
        const val BOOK_ROW_INFO_METHOD = "BookRowInfo"
        const val TIME_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.TimeExtKt"
        const val SECOND_TO_HOURS_METHOD = "secondToHours"
        const val DRIVE_CARD_CLASS = "app.zhendong.reamicro.ui.backup.components.DriveCardKt"
        const val YUN115_NET_DISK_CARD_METHOD = "Yun115NetDiskCard"
        const val BOOK_BACKUP_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.backup.BookBackupViewModel"
        const val BOOK_BACKUP_SCREEN_CLASS = "app.zhendong.reamicro.ui.backup.BookBackupScreenKt"
        const val BOOK_BACKUP_CONTENT_METHOD = "BookBackupContent"
        const val BOOKSHELF_REPOSITORY_CLASS = "app.zhendong.reamicro.repository.BookshelfRepository"
        const val BOOKSHELF_IMPORT_BOOK_METHOD = "importBook"
        const val BOOKSHELF_UPDATE_BOOK_METHOD = "updateBook"
        const val OPF_CLASS = "org.epub.structure.opf.Opf"
        const val EPUB_FILE_MANAGER_CLASS = "app.zhendong.reamicro.arch.EpubFileManager"
        const val OKIO_PATH_CLASS = "okio.Path"
        const val WORKER_MANAGER_CLASS = "app.zhendong.reamicro.arch.WorkerManager"
        const val WORK_TRACKER_CLASS = "app.zhendong.reamicro.arch.WorkTracker"
        const val WORKER_ENQUEUE_DOWNLOAD_METHOD = "enqueueDownload"
        const val WORKER_ENQUEUE_BACKUP_METHOD = "enqueueBackup"
        const val WORKER_ENQUEUE_IMPORT_METHOD = "enqueueImport"
        const val WORK_HANDLE_CLASS = "app.zhendong.reamicro.arch.WorkHandle"
        const val WORK_STATE_CLASS = "app.zhendong.reamicro.arch.WorkState"
        const val WORK_STATUS_CLASS = "app.zhendong.reamicro.arch.WorkStatus"
        const val NOT_AUTH_CLASS = "app.zhendong.reamicro.ui.storage.components.NotAuthKt"
        const val NOT_AUTH_METHOD = "NotAuth"
        const val APP_KT_CLASS = "app.zhendong.reamicro.AppKt"
        const val NAV_GRAPH_SCOPE_CLASS = "app.zhendong.reamicro.NavGraphScope"
        const val NAVIGATE_METHOD = "navigate"
        const val THIRD_LOGIN_ROUTE_METHOD = "setup\$lambda\$0\$17"
        const val THIRD_LOGIN_ROUTE_METHOD_LEGACY = "setup\$lambda\$0\$16"
        const val THIRD_ACCOUNT_ROUTE_METHOD = "setup\$lambda\$0\$18"
        const val THIRD_ACCOUNT_ROUTE_METHOD_LEGACY = "setup\$lambda\$0\$17"
        const val ROUTE_HOME_CLASS = "app.zhendong.reamicro.Route\$Home"
        const val ROUTE_STORAGE_CLASS = "app.zhendong.reamicro.Route\$Storage"
        const val ROUTE_CLOUD_FOLDER_CLASS = "app.zhendong.reamicro.Route\$CloudFolder"
        const val ROUTE_THIRD_LOGIN_CLASS = "app.zhendong.reamicro.Route\$ThirdLogin"
        const val ROUTE_THIRD_ACCOUNT_CLASS = "app.zhendong.reamicro.Route\$ThirdAccount"
        const val NAV_BACK_STACK_ENTRY_KT_CLASS = "androidx.navigation.NavBackStackEntryKt"
        const val NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD = "toRoute"
        const val KOTLIN_REFLECTION_CLASS = "kotlin.jvm.internal.Reflection"
        const val STRING_RESOURCES_KT_CLASS = "org.jetbrains.compose.resources.StringResourcesKt"
        const val STRING_RESOURCE_METHOD = "stringResource"
        const val APP_TOP_BAR_CLASS = "app.zhendong.reamicro.arch.components.AppTopBarKt"
        const val APP_TOP_BAR_METHOD = "AppTopBar"
        const val KOTLIN_RESULT_CLASS = "kotlin.Result"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val FLOW_KT_CLASS = "kotlinx.coroutines.flow.FlowKt"
        const val FLOW_CLASS = "kotlinx.coroutines.flow.Flow"
        const val STATE_FLOW_CLASS = "kotlinx.coroutines.flow.StateFlow"
        const val STATE_FLOW_KT_CLASS = "kotlinx.coroutines.flow.StateFlowKt"
        const val FLOW_OF_METHOD = "flowOf"
        const val LOAD_STATES_CLASS = "androidx.paging.LoadStates"
        const val PLATFORM_FILE_ANDROID_KT_CLASS = "io.github.vinceglb.filekit.PlatformFile_androidKt"
        const val PLATFORM_FILE_METHOD = "PlatformFile"
        const val PLATFORM_FILE_GET_PATH_METHOD = "getPath"
        const val PLATFORM_FILE_ABSOLUTE_PATH_METHOD = "absolutePath"
        const val AUTH_BAIDU_CLASS = "app.zhendong.reamicro.data.third.Auth\$BaiduAuth"
        const val AUTH_YUN115_CLASS = "app.zhendong.reamicro.data.third.Auth\$Yun115Auth"
        const val BAIDU_ACCOUNT_SCREEN_CLASS = "app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskAccountScreenKt"
        const val BAIDU_ACCOUNT_SCREEN_METHOD = "BaiduNetDiskAccountScreen"
        const val BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD = "DefaultFolder"
        const val BAIDU_ACCOUNT_LOGOUT_METHOD = "LogOut"
        const val BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD = "QueryOrderBy"
        const val BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD = "QueryOrderDirection"
        const val BAIDU_ACCOUNT_DEFAULT_FOLDER_LAMBDA_METHOD = "BaiduNetDiskAccountScreen\$lambda\$0\$0\$1\$0\$0\$0"
        const val BAIDU_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskViewModel"
        const val BAIDU_VIEW_MODEL_GET_AUTH_METHOD = "getAuth"
        const val Y115_ACCOUNT_SCREEN_CLASS = "app.zhendong.reamicro.ui.storage.c115.Y115NetDiskAccountScreenKt"
        const val Y115_ACCOUNT_SCREEN_METHOD = "Y115NetDiskAccountScreen"
        const val Y115_ACCOUNT_DEFAULT_FOLDER_METHOD = "DefaultFolder"
        const val Y115_ACCOUNT_LOGOUT_METHOD = "LogOut"
        const val Y115_ACCOUNT_QUERY_ORDER_BY_METHOD = "QueryOrderBy"
        const val Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD = "QueryOrderDirection"
        const val Y115_ACCOUNT_LIBRARY_BLOCK_METHOD = "Y115NetDiskAccountScreen\$lambda\$0\$0\$1\$1"
        const val Y115_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.storage.c115.Y115NetDiskViewModel"
        const val Y115_VIEW_MODEL_GET_AUTH_METHOD = "getAuth"
        const val DIR_CLASS = "app.zhendong.reamicro.data.third.Dir"
        const val CLOUD_USER_INFO_CLASS = "app.zhendong.reamicro.data.storage.CloudUserInfo"
        const val PAGING_DATA_CLASS = "androidx.paging.PagingData"
        const val PAGING_DATA_EMPTY_METHOD = "empty"
        const val BAIDU_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.BaiduNetdiskKt"
        const val BAIDU_ICON_METHOD = "getBaiduNetdisk"
        const val YUN115_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.Yun115Kt"
        const val YUN115_ICON_METHOD = "getYun115"
        const val FILE_FOLDER_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.FileFolderKt"
        const val FILE_FOLDER_ICON_METHOD = "getFileFolder"
        const val ANDROID_OS_ICON_CLASS = "app.zhendong.reamicro.arch.icons.colored.AndroidOsKt"
        const val ANDROID_OS_ICON_METHOD = "getAndroidOs"
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val KOTLIN_PAIR_CLASS = "kotlin.Pair"
        const val ROW_KT_CLASS = "androidx.compose.foundation.layout.RowKt"
        const val ROW_METHOD = "Row"
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val TEXT_OVERFLOW_CLASS = "androidx.compose.ui.text.style.TextOverflow"
        const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
        const val ICON_METHOD = "Icon-ww6aTOc"
        const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"
        const val EDIT_ICON_CLASS = "androidx.compose.material.icons.outlined.EditKt"
        const val NAVIGATE_NEXT_ICON_CLASS = "androidx.compose.material.icons.automirrored.filled.NavigateNextKt"
        const val ICONS_FILLED_CLASS = "androidx.compose.material.icons.Icons\$Filled"
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val ICONS_AUTO_MIRRORED_FILLED_CLASS = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"
        const val MATERIAL3_TEXT_METHOD = "Text-Nvy7gAk"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val CLICKABLE_KT_CLASS = "androidx.compose.foundation.ClickableKt"
        const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
        const val IMAGE_VECTOR_BUILDER_CLASS = "androidx.compose.ui.graphics.vector.ImageVector\$Builder"
        const val VECTOR_KT_CLASS = "androidx.compose.ui.graphics.vector.VectorKt"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val SOLID_COLOR_CLASS = "androidx.compose.ui.graphics.SolidColor"
        const val DEFAULT_CONSTRUCTOR_MARKER_CLASS = "kotlin.jvm.internal.DefaultConstructorMarker"
        const val ANDROID_VIEW_KT_CLASS = "androidx.compose.ui.viewinterop.AndroidView_androidKt"
        const val ANDROID_VIEW_METHOD = "AndroidView"
        const val MATERIAL3_TEXT_CLASS = "androidx.compose.material3.TextKt"
        const val DIVIDER_KT_CLASS = "app.zhendong.reamicro.arch.components.DividerKt"
        const val SIMPLE_DIVIDER_METHOD = "SimpleDivider-iJQMabo"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val SIZE_METHOD = "size-3ABfNKs"
        const val FILL_MAX_SIZE_METHOD = "fillMaxSize"
        const val FILL_MAX_WIDTH_METHOD = "fillMaxWidth"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_METHOD = "padding-qDBjuR0"
        const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val TEXT_DEFAULT_MASK_WITH_MODIFIER = 131064
        const val TEXT_SECONDARY_SINGLE_LINE_MASK = 110586
        const val OKHTTP_CLIENT_CLASS = "okhttp3.OkHttpClient"
        const val OKHTTP_REQUEST_CLASS = "okhttp3.Request"
        const val OKHTTP_REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"
        const val OKHTTP_REQUEST_BODY_CLASS = "okhttp3.RequestBody"
        const val OKHTTP_MEDIA_TYPE_CLASS = "okhttp3.MediaType"
        const val NETWORK_SECURITY_POLICY_CLASS = "android.security.NetworkSecurityPolicy"
        const val ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS = "com.android.okhttp.HttpHandler\$CleartextURLFilter"
        const val ANDROID_OKHTTP_PLATFORM_CLASS = "com.android.okhttp.internal.Platform"
        const val OKHTTP_PLATFORM_CLASS = "okhttp3.internal.platform.Platform"
        const val OKHTTP_ANDROID_PLATFORM_CLASS = "okhttp3.internal.platform.AndroidPlatform"
        const val OKHTTP_ANDROID10_PLATFORM_CLASS = "okhttp3.internal.platform.Android10Platform"
        const val BACKUP_TYPE_WEBDAV = 8
        const val BACKUP_TYPE_LOCAL_LIBRARY = 9
        const val BACKUP_TYPE_ONLINE_COMPLETION = 10
        const val ONLINE_COMPLETION_RENDER_TYPE_BASE = 10000
        const val ONLINE_COMPLETION_RENDER_TYPE_BUCKETS = 100000
        const val BACKUP_TYPE_BAIDU = 1
        const val BACKUP_TYPE_YUN115 = 2
        const val BACKUP_TYPE_ALIYUN = 4
        const val ONLINE_COMPLETION_SOURCE_PREFIX = "reamicro-online-source://"
        const val ONLINE_COMPLETION_BOOK_PREFIX = "reamicro-online-book://"
        const val ONLINE_COMPLETION_UUID_PREFIX = "reamicro-online-"
        const val ONLINE_COMPLETION_CACHE_ROOT = "reamicro-online-completion"
        const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
        const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
        const val ONLINE_COMPLETION_NOTIFICATION_ACTION = "com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION"
        const val ONLINE_COMPLETION_CANCEL_ACTION = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
        const val ONLINE_COMPLETION_NOTIFICATION_ACTIVITY_CLASS =
            "com.reamicro.fix.notification.OnlineCompletionNotificationActivity"
        const val ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS =
            "com.reamicro.fix.notification.OnlineCompletionNotificationReceiver"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID = "id"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY = "key"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_CANCELLABLE = "cancellable"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_TITLE = "title"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_TEXT = "text"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_PROGRESS = "progress"
        const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_DONE = "done"
        const val WEBDAV_TITLE = "WebDAV"
        const val LOCAL_LIBRARY_TITLE = "\u672c\u5730\u4e66\u5e93"
        const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
        const val LOCAL_LIBRARY_BROWSE_TEXT = "\u6d4f\u89c8\u6587\u4ef6"
        const val LOCAL_LIBRARY_FOLDER_TITLE = "\u4e66\u5e93\u6587\u4ef6\u5939"
        const val LOCAL_LIBRARY_PICK_FOLDER_TEXT = "\u9009\u62e9\u76ee\u5f55"
        const val LOCAL_LIBRARY_REMOVE_TEXT = "\u79fb\u9664"
        const val LOCAL_LIBRARY_REMOVED_TOAST = "\u5df2\u79fb\u9664"
        const val ACCOUNT_CONTEXT_GRACE_MS = 3000L
        const val ROOT_DIR_NAME = "\u6839\u76ee\u5f55"
        const val EPUB_MIME_TYPE = "application/epub+zip"
        const val WEBDAV_AUTH_TIPS = "登录 WebDAV 账号浏览网盘书籍"
        const val WEBDAV_PREFS = "reamicro_fix_webdav"
        const val LOCAL_LIBRARY_PREFS = "reamicro_fix_local_library"
        const val KEY_URL = "url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_DIR_LEGACY = "dir"
        const val KEY_BROWSE_DIR = "browse_dir"
        const val KEY_BACKUP_DIR = "backup_dir"
        const val KEY_AUTHORIZED = "authorized"
        const val KEY_ORDER_BY = "order_by"
        const val KEY_ORDER_DIRECTION = "order_direction"
        const val KEY_LOCAL_FOLDER_URIS = "folder_uris"
        const val KEY_LOCAL_BROWSE_DIR = "browse_dir"
        const val KEY_LOCAL_ORDER_BY = "order_by"
        const val KEY_LOCAL_ORDER_DIRECTION = "order_direction"
        const val DEFAULT_DIR = "/"
        const val WEBDAV_DEFAULT_ORDER_BY = "time"
        const val LOCAL_LIBRARY_DEFAULT_ORDER_BY = "file_name"
        const val DEFAULT_ORDER_DIRECTION_ASC = "0"
        const val DEFAULT_ORDER_DIRECTION_DESC = "1"
        const val WEBDAV_SOURCE_PREFIX = "webdav://reamicro"
        const val LOCAL_LIBRARY_SOURCE_PREFIX = "local-library://reamicro/"
        const val LOCAL_LIBRARY_ROOT_PATH = "/"
        const val LOCAL_LIBRARY_PATH_PREFIX = "local:"
        const val WEBDAV_SEARCH_MAX_DIRS = 300
        const val LOCAL_LIBRARY_SEARCH_MAX_DIRS = 500
        const val LOCAL_LIBRARY_LIST_CACHE_TTL_MS = 30_000L
        const val LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS = 5 * 60_000L
        const val LOCAL_LIBRARY_SEARCH_SYNC_BUDGET_MS = 1_800L
        const val LOCAL_LIBRARY_SEARCH_BACKGROUND_BUDGET_MS = 8_000L
        const val LOCAL_LIBRARY_SEARCH_REFRESH_DELAY_MS = 1_600L
        const val LOCAL_LIBRARY_SEARCH_LATE_REFRESH_DELAY_MS = 4_500L
        const val HOME_SEARCH_RESULT_LIMIT = 10
        const val ONLINE_COMPLETION_RESULT_LIMIT = 8
        const val ONLINE_COMPLETION_MAX_CHAPTERS = 500
        const val ONLINE_COMPLETION_SEARCH_TIMEOUT_MS = 8_000L
        const val ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD = 200
        const val ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS = 100
        const val ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT = 3
        const val ONLINE_COMPLETION_RETRY_DELAY_MS = 6_000L
        const val ONLINE_COMPLETION_NOTIFICATION_MIN_INTERVAL_MS = 1_000L
        const val ONLINE_COMPLETION_MODULE_ACTIVITY_RETRY_MS = 15_000L
        const val CLOUD_STORAGE_SCREEN_REFRESH_DEBOUNCE_MS = 1_000L
        const val STRING_KEY_UPLOAD_TO_115 = "upload_to_115"
        const val HOME_SEARCH_DEBOUNCE_MS = 250L
        const val DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS = 2_500L
        const val STARTUP_CACHE_CLEANUP_DELAY_MS = 1_500L
        const val STALE_IMPORT_CACHE_MIN_AGE_MS = 60 * 60_000L
        const val REQUEST_LOCAL_LIBRARY_DIR = 8931
        val startupCacheCleanupStarted = AtomicBoolean(false)
        val NATIVE_CLOUD_DOWNLOAD_TYPES = setOf(BACKUP_TYPE_BAIDU, BACKUP_TYPE_YUN115, BACKUP_TYPE_ALIYUN)
        val ONLINE_WORD_COUNT_FIELDS = listOf(
            "wordCount",
            "word_count",
            "word_number",
            "wordNum",
            "word_num",
            "words",
            "total_words",
            "totalWords",
        )
        val ONLINE_UPDATE_TIME_FIELDS = listOf(
            "updateTime",
            "updated_at",
            "update_time",
            "last_chapter_update_time",
            "last_chapter_first_pass_time",
            "latest_chapter_update_time",
            "latest_update_time",
            "firstPassTime",
            "first_pass_time",
        )
        val ONLINE_CHAPTER_COUNT_FIELDS = listOf(
            "chapterCount",
            "chapter_count",
            "chapter_count_total",
            "total_chapters",
            "totalChapter",
            "total_chapter",
            "chapter_num",
            "chapterNum",
            "chapters_count",
            "latest_chapter_index",
        )
        val ONLINE_STATUS_TEXT_FIELDS = listOf(
            "status",
            "bookStatus",
            "book_status",
            "creation_status",
            "tomato_book_status",
            "serial_status",
            "is_finish",
            "isFinished",
            "finished",
            "complete",
            "completed",
        )
        val UUID_DIR_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val ONLINE_CHAPTER_TITLE_REGEX = Regex("(第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*[章节卷回集部篇]|chapter\\s*\\d+)", RegexOption.IGNORE_CASE)
        val ONLINE_VOLUME_TITLE_REGEX = Regex("(第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*卷|正文|番外|后日谈|外传|分卷|volume\\s*\\d+|part\\s*\\d+)", RegexOption.IGNORE_CASE)
        val ONLINE_CHAPTER_HEADING_SPLIT_REGEX =
            Regex("^(第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*[章节卷回集部篇])\\s*[:：/／、，,。.!！?？\\-—_；;]*\\s*(.+)$")
        val ONLINE_SPECIAL_HEADING_SPLIT_REGEX =
            Regex("^(番外|后日谈|后记|序章|楔子|终章)\\s*[:：/／、，,。.!！?？\\-—_；;]*\\s*(.+)$")
        val ONLINE_COMPLETION_PROGRESS_CHAPTER_REGEX =
            Regex("(?:下载|重试)?章节\\s*(\\d+)\\s*/\\s*(\\d+)")
        val ONLINE_COMPLETION_CHAPTER_FILE_REGEX = Regex("""chapter_(\d+)\.xhtml""", RegexOption.IGNORE_CASE)
        const val ONLINE_CHAPTER_TITLE_SCAN_LINES = 8
        val BOOK_EXTENSIONS = setOf(".epub", ".mobi", ".azw3", ".txt")
        val WEBDAV_UPLOAD_RETRY_CODES = setOf(405, 409, 412, 423)
        const val WEBDAV_PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
        const val WEBDAV_ICON_BODY_PATH = "M5 3.8h14c0.7 0 1.2 0.5 1.2 1.2v14c0 0.7-0.5 1.2-1.2 1.2H5c-0.7 0-1.2-0.5-1.2-1.2V5c0-0.7 0.5-1.2 1.2-1.2z"
        const val WEBDAV_ICON_CLOUD_PATH = "M8.6 13.8c-1.2 0-2.1-0.9-2.1-2s0.9-2 2.1-2c0.2 0 0.4 0 0.6 0.1C9.8 8.7 10.9 8 12.2 8c1.6 0 3 1.1 3.3 2.6c1 0.1 1.8 1 1.8 2c0 1.1-0.9 2-2 2H8.6z"
        const val WEBDAV_ICON_SLOT_PATH = "M7 17h10v1.1H7z"
    }
}
