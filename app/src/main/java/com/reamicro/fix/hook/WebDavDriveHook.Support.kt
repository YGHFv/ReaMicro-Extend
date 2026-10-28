package com.reamicro.fix.hook

import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostClass

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostNameMatches


import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Xml
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.online.OnlineSourceDailyLimitException
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceScriptCompat
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.online.download.isPermanentOnlineChapterFailure
import com.reamicro.fix.online.download.onlineHttpRetryDelayMs
import com.reamicro.fix.online.download.onlineHttpRetryKind
import com.reamicro.fix.online.download.OnlineHttpRetryKind
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.cloud.webdav.OnlineDownloadedChapter
import com.reamicro.fix.online.download.OnlineOnDemandBridge
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import org.xmlpull.v1.XmlPullParser
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.epub.onlineImportedBookBackupId
import com.reamicro.fix.online.search.onlineFirstJsonString
import com.reamicro.fix.online.search.homeCloudSearchResults
import com.reamicro.fix.online.search.onlineCompletionSearchMetaLine
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.parseOnlineJsonRoot
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.online.download.ensureNcxSpineToc
import com.reamicro.fix.online.download.onlineChapterUpdatePlan
import com.reamicro.fix.online.download.readOnlineCompletionFailedChapters
import com.reamicro.fix.online.download.remapOnlineCompletionFailures
import com.reamicro.fix.online.download.detectInvalidOnlineCompletionChapters
import com.reamicro.fix.online.download.writeOnlineCompletionFailedChapters
import com.reamicro.fix.online.download.sleepOnlineCompletionRetryDelay
import com.reamicro.fix.online.download.onlineCompletionRetryDelayMs
import com.reamicro.fix.online.download.onlineCompletionFailedChapter
import com.reamicro.fix.online.download.mergeOnlineCompletionFailedChapters
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.webDavBookUrl
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.core.typeMatches

internal fun WebDavDriveHook.install() {
    logWebDav("install start")
    WebDavDriveHook.activeInstance = this
    OnlineOnDemandBridge.attach(::downloadOnlineCompletionOnDemandChapter)

    HookInstallReport.installAll(
        FEATURE_ID,
        listOf(
            "webDavCleartextPolicy" to ::hookWebDavCleartextPolicy,
            "backupTypeName" to ::hookBackupTypeName,
            "onlineCompletionBookRowDuration" to ::hookOnlineCompletionBookRowDuration,
            "onlineCompletionBookLocalSheet" to ::hookOnlineCompletionBookLocalSheet,
            "homeCloudResultListRenderContext" to ::hookHomeCloudResultListRenderContext,
            "onlineCompletionHomeCloudBookRow" to ::hookOnlineCompletionHomeCloudBookRow,
            "homeViewModelDependencies" to ::hookHomeViewModelDependencies,
            "webDavRowIcon" to ::hookWebDavRowIcon,
            "webDavYun115Icon" to ::hookWebDavYun115Icon,
            "webDavCloudTreeIcon" to ::hookWebDavCloudTreeIcon,
            "importCloudRowLabels" to ::hookImportCloudRowLabels,
            "importCloudRowDetail" to ::hookImportCloudRowDetail,
            "cloudBookRowExtendedDisplay" to ::hookCloudBookRowExtendedDisplay,
            "localLibraryFolderPickerResult" to ::hookLocalLibraryFolderPickerResult,
            "webDavStorageNavigate" to ::hookWebDavStorageNavigate,
            "thirdLoginWebDavRoute" to ::hookThirdLoginWebDavRoute,
            "webDavAccountScreenReuse" to ::hookWebDavAccountScreenReuse,
            "webDavAccountSettingComponents" to ::hookWebDavAccountSettingComponents,
            "webDavAccountAuthFlow" to ::hookWebDavAccountAuthFlow,
            "webDavAccountTopBarTitle" to ::hookWebDavAccountTopBarTitle,
            "webDavAccountDefaultFolderLambda" to ::hookWebDavAccountDefaultFolderLambda,
            "cloudStorageWebDavData" to ::hookCloudStorageWebDavData,
            "cloudStorageWebDavStrings" to ::hookCloudStorageWebDavStrings,
            "cloudStorageWebDavTitle" to ::hookCloudStorageWebDavTitle,
            "cloudStorageWebDavRefresh" to ::hookCloudStorageWebDavRefresh,
            "cloudStorageWebDavAuthTips" to ::hookCloudStorageWebDavAuthTips,
            "cloudStorageWebDavViewModelState" to ::hookCloudStorageWebDavViewModelState,
            "webDavCloudTap" to ::hookWebDavCloudTap,
            "nativeCloudDownloadCancellation" to ::hookNativeCloudDownloadCancellation,
            "syncAuthCard" to ::hookSyncAuthCard,
            "syncAuthCardContent" to ::hookSyncAuthCardContent,
            "driveAuthCard" to ::hookDriveAuthCard,
            "driveOtherAvailableCard" to ::hookDriveOtherAvailableCard,
            "webDavSyncUploadRunningCard" to ::hookWebDavSyncUploadRunningCard,
            "importAuthorizedList" to ::hookImportAuthorizedList,
            "importLocalLibraryRow" to ::hookImportLocalLibraryRow,
            "importUnauthList" to ::hookImportUnauthList,
            "importCloudAuthorizedRow" to ::hookImportCloudAuthorizedRow,
            "importCloudUnauthRow" to ::hookImportCloudUnauthRow,
            "webDavCloudDownload" to ::hookWebDavCloudDownload,
            "webDavImportBookSource" to ::hookWebDavImportBookSource,
            "webDavCloudBackup" to ::hookWebDavCloudBackup,
            "bookBackupViewModelState" to ::hookBookBackupViewModelState,
            "bookBackupWebDavCard" to ::hookBookBackupWebDavCard,
            "webDavYun115BackupCard" to ::hookWebDavYun115BackupCard,
            "homeWebDavSearch" to ::hookHomeWebDavSearch,
            "homeSearchTapCancellation" to ::hookHomeSearchTapCancellation,
            "homeSearchResultWebDavSection" to ::hookHomeSearchResultWebDavSection,
            "thirdAccountWebDavRoute" to ::hookThirdAccountWebDavRoute,
        ).filterNot { (name, _) ->
            com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4" &&
                name in setOf(
                    "backupTypeName",
                    "onlineCompletionBookRowDuration",
                    "webDavCloudTreeIcon",
                    "importCloudRowLabels",
                    "importCloudRowDetail",
                    "cloudBookRowExtendedDisplay",
                    "cloudStorageWebDavStrings",
                    "cloudStorageWebDavAuthTips",
                    "syncAuthCardContent",
                    "webDavSyncUploadRunningCard",
                    "importAuthorizedList",
                    "importLocalLibraryRow",
                    "importUnauthList",
                    "webDavStorageNavigate",
                    "thirdLoginWebDavRoute",
                    "thirdAccountWebDavRoute",
                )
        },
    )
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        HookInstallReport.install(FEATURE_ID, "b4RegisteredStorageRoutes") {
            B4StorageRouteBridge(this).install()
        }
        HookInstallReport.install(FEATURE_ID, "b4StorageUi") {
            B4StorageUiBridge(this).install()
        }
    }
    if (com.reamicro.fix.core.HostSymbols.profileId == "2.3.3-b2") B2StorageUiBridge(this).install()
}

internal fun WebDavDriveHook.ensureOnlineCompletionCancelReceiver(context: Context) {
    if (!onlineCompletionCancelReceiverRegistered.compareAndSet(false, true)) return
    runCatching {
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    ONLINE_COMPLETION_CANCEL_ACTION -> {
                        val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                        val key = intent.getStringExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY).orEmpty()
                        requestOnlineCompletionDownloadCancel(receiverContext, id, key)
                    }
                    ONLINE_COMPLETION_HEARTBEAT_ACTION -> {
                        val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                        logWebDav("online completion host heartbeat id=$id active=${onlineCompletionRunningDownloads.size}")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ONLINE_COMPLETION_CANCEL_ACTION)
            addAction(ONLINE_COMPLETION_HEARTBEAT_ACTION)
        }
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

internal fun WebDavDriveHook.canUseCloudExtendedDisplay(): Boolean =
    settingsProvider().canUseCloudExtendedDisplay

internal fun isBookLocalSheetBookContextMethod(method: Method): Boolean {
    if (method.hostNameMatches(BOOK_LOCAL_SHEET_METHOD) && method.parameterTypes.size == 5) return true

    return method.name.startsWith("BookLocalSheet\$lambda\$") &&
        typeMatches(method.parameterTypes.firstOrNull(), BOOK_CLASS) &&
        method.parameterTypes.any { it.name == COMPOSER_CLASS }
}

internal fun WebDavDriveHook.textOverflowEllipsis(): Int =
    staticObject(TEXT_OVERFLOW_CLASS, "INSTANCE").method0("getEllipsis") as Int

internal fun WebDavDriveHook.simpleDividerMethod(): Method =
    synchronized(methodCache) {
        methodCache.getOrPut("$DIVIDER_KT_CLASS#SimpleDivider/5") {
            cls(DIVIDER_KT_CLASS).declaredMethods.firstOrNull {
                it.name.contains("SimpleDivider") && it.parameterTypes.size == 5
            }?.apply { isAccessible = true }
                ?: error("SimpleDivider method not found")
        }
    }

internal fun decodeOnlineDataImage(url: String): Bitmap? {
    if (!url.startsWith("data:image/", ignoreCase = true)) return null
    val commaIndex = url.indexOf(',')
    if (commaIndex <= 0 || commaIndex >= url.length - 1) return null
    val metadata = url.take(commaIndex).lowercase(Locale.ROOT)
    val payload = url.substring(commaIndex + 1)
    val bytes = if (metadata.contains(";base64")) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

internal fun WebDavDriveHook.applyCloudBookExtendedDisplayText(param: XC_MethodHook.MethodHookParam) {
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

internal fun WebDavDriveHook.wrapAuthorizeCallback(original: Any): Any =
    functionProxy("WebDavAuthorize", FUNCTION1_CLASS) { args ->
        invokeFunction1(original, args?.getOrNull(0))
        targetUnit()
    }

internal fun WebDavDriveHook.setTrackedWorkState(
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
            it.hostNameMatches("setState") && it.parameterTypes.size == 2
        }.apply { isAccessible = true }.invoke(
            tracker,
            id,
            newWorkState(statusName, progress.coerceIn(0, 100), error, result, name),
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to update WebDAV work state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.cancelOnlineCompletionTrackedWork(task: OnlineCompletionDownloadTask) {
    val tracker = task.tracker ?: return
    val workId = task.workId ?: return
    cancelTrackedWork(tracker, workId)
}

internal fun WebDavDriveHook.cancelTrackedWork(tracker: Any, id: String) {
    runCatching {
        tracker.javaClass.methods.first {
            it.hostNameMatches("cancelWorkById") && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(tracker, id)
    }.onFailure {
        setTrackedWorkState(tracker, id, "Cancelled", 100, null, null, null)
    }
}

internal fun isChildPath(child: File, parent: File): Boolean {
    val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
    return child.path.startsWith(parentPath)
}

internal fun workStateStatusName(state: Any): String =
    state.invokeNoArg("getStatus")?.toString().orEmpty()

internal fun cloudTypeTitle(type: Int): String =
    when (type) {
        BACKUP_TYPE_BAIDU -> "\u767e\u5ea6\u7f51\u76d8"
        BACKUP_TYPE_YUN115 -> "115"
        BACKUP_TYPE_ALIYUN -> "\u963f\u91cc\u4e91\u76d8"
        else -> "\u4e91\u76d8"
    }

internal fun WebDavDriveHook.findLocalBookById(bookId: Long): Any? {
    val bookshelf = currentBookshelfRepository()
        ?: error("BookshelfRepository not available for WebDAV backup")
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        val dao = bookshelf.javaClass.hostField("bookDao").get(bookshelf)
        val method = dao.javaClass.getDeclaredMethod(
            "b", Long::class.javaPrimitiveType, cls("kotlin.coroutines.Continuation"),
        )
        return invokeSuspendBlocking(method, dao, bookId)
    }
    val findMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .first {
            it.hostNameMatches("findBookById") &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == java.lang.Long.TYPE
        }
        .apply { isAccessible = true }
    return invokeSuspendBlocking(findMethod, bookshelf, bookId)
}

internal fun WebDavDriveHook.findLocalBookByUuid(uid: Long, uuid: String): Any? {
    if (uid <= 0L || uuid.isBlank()) return null
    val bookshelf = currentBookshelfRepository() ?: return null
    val bookDao = fieldValue(bookshelf, "bookDao") ?: return null
    val findMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
        .firstOrNull { candidate ->
            candidate.hostNameMatches("findByUidAndUuid") &&
                candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == java.lang.Long.TYPE &&
                candidate.parameterTypes[1] == String::class.java
        }
        ?.apply { isAccessible = true }
        ?: return null
    return invokeSuspendBlocking(findMethod, bookDao, uid, uuid)
}

internal fun WebDavDriveHook.updateLocalBook(book: Any) {
    val bookshelf = currentBookshelfRepository()
        ?: error("BookshelfRepository not available for WebDAV update")
    val updateMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .first {
            it.hostNameMatches(BOOKSHELF_UPDATE_BOOK_METHOD) &&
                it.parameterTypes.size == 2 &&
                typeMatches(it.parameterTypes[0], BOOK_CLASS)
        }
        .apply { isAccessible = true }
    invokeSuspendBlocking(updateMethod, bookshelf, book)
}

internal fun copyBookWithBackup(book: Any, backupType: Int, backupId: String, backupCode: String): Any {
    return copyBookWithBackupAndPublisher(
        book = book,
        backupType = backupType,
        backupId = backupId,
        backupCode = backupCode,
        publisher = book.callString("getPublisher"),
    )
}

internal fun copyBookWithBackupAndPublisher(
    book: Any,
    backupType: Int,
    backupId: String,
    backupCode: String,
    publisher: String,
    cover: String? = null,
    size: Long? = null,
    updated: Long? = null,
    pinnedAt: Long? = null,
    cloudId: Long? = null,
): Any {
    return BookCopyCompat.copy(
        book,
        BookCopyPatch(
            cover = cover,
            size = size,
            updated = updated,
            pinnedAt = pinnedAt,
            cloudId = cloudId,
            backupType = backupType,
            backupId = backupId,
            backupCode = backupCode,
            publisher = publisher,
        ),
    )
}

internal fun WebDavDriveHook.copyCloudBookWithType(book: Any, type: Int): Any {
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        val props = listOf("id", "name", "type", "url", "size", "updatedAt", "path", "hash", "local", "workers")
        val values = props.map { book.javaClass.hostField(it).get(book) }.toMutableList()
        values[2] = type
        return book.javaClass.getDeclaredConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java, String::class.java,
            cls(FLOW_CLASS), cls(FLOW_CLASS),
        ).newInstance(*values.toTypedArray())
    }
    val copyMethod = book.javaClass.methods.first {
        it.hostNameMatches("copy") && it.parameterTypes.size == 10
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

internal fun WebDavDriveHook.zipBookDirectory(book: Any, output: OutputStream) {
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

internal fun addStoredFile(zip: ZipOutputStream, file: File, name: String) {
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

internal fun addDeflatedFile(zip: ZipOutputStream, file: File, name: String) {
    val patchedOpf = if (name.endsWith(".opf", ignoreCase = true)) patchedOpfBytes(file) else null
    if (patchedOpf != null) {
        addDeflatedBytes(zip, patchedOpf, name)
        return
    }
    zip.putNextEntry(ZipEntry(name))
    BufferedInputStream(FileInputStream(file)).use { input ->
        input.copyTo(zip)
    }
    zip.closeEntry()
}

internal fun addDeflatedBytes(zip: ZipOutputStream, bytes: ByteArray, name: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(bytes)
    zip.closeEntry()
}

internal fun patchedOpfBytes(file: File): ByteArray? {
    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
    val patched = ensureNcxSpineToc(content)
    return patched.takeIf { it != content }?.toByteArray(Charsets.UTF_8)
}

internal fun WebDavDriveHook.bookDirectory(book: Any): File {
    val context = currentContext() ?: error("Android context not available")
    val uid = book.callLong("getUid")
    val uuid = book.callString("getUuid")
    return File(context.filesDir, "$uid/books/$uuid")
}

internal fun exportFileName(book: Any): String =
    "${book.callString("getTitle").safeWebDavFileName()}.epub"

internal fun WebDavDriveHook.setupRouteMethods(): List<Method> {
    val appClass = cls(APP_KT_CLASS)
    val methods = appClass.declaredMethods
        .filter { it.name.startsWith(SETUP_ROUTE_METHOD_PREFIX) && it.parameterTypes.size == 5 }
        .onEach { it.isAccessible = true }
    if (methods.isEmpty()) {
        error("No setup route methods found")
    }
    return methods
}

internal fun isRouteDestination(backStackEntry: Any, routeClassName: String): Boolean {
    val destination = runCatching {
        backStackEntry.javaClass.methods.firstOrNull {
            it.hostNameMatches("getDestination") && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(backStackEntry)
    }.getOrNull() ?: return false
    val route = runCatching {
        destination.javaClass.methods.firstOrNull {
            it.hostNameMatches("getRoute") && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(destination)?.toString().orEmpty()
    }.getOrDefault("")
    if (route.isBlank()) return false
    val dottedClassName = routeClassName.replace('$', '.')
    val simpleName = routeClassName.substringAfterLast('$')
    return route.contains(routeClassName) ||
        route.contains(dottedClassName) ||
        route.contains(simpleName)
}

internal fun WebDavDriveHook.navBackStackEntryToThirdLogin(backStackEntry: Any): Any {
    val reflectionClass = cls(KOTLIN_REFLECTION_CLASS)
    val routeClass = cls(ROUTE_THIRD_LOGIN_CLASS)
    val kClass = reflectionClass.hostMethod("getOrCreateKotlinClass", Class::class.java)
        .apply { isAccessible = true }
        .invoke(null, routeClass)
    return cls(NAV_BACK_STACK_ENTRY_KT_CLASS).declaredMethods.first {
        it.hostNameMatches(NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD) && it.parameterTypes.size == 2
    }.apply { isAccessible = true }
        .invoke(null, backStackEntry, kClass)
}

internal fun WebDavDriveHook.navBackStackEntryToThirdAccount(backStackEntry: Any): Any {
    val reflectionClass = cls(KOTLIN_REFLECTION_CLASS)
    val routeClass = cls(ROUTE_THIRD_ACCOUNT_CLASS)
    val kClass = reflectionClass.hostMethod("getOrCreateKotlinClass", Class::class.java)
        .apply { isAccessible = true }
        .invoke(null, routeClass)
    return cls(NAV_BACK_STACK_ENTRY_KT_CLASS).declaredMethods.first {
        it.hostNameMatches(NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD) && it.parameterTypes.size == 2
    }.apply { isAccessible = true }
        .invoke(null, backStackEntry, kClass)
}

internal fun isBaiduTitle(title: String): Boolean =
    title.contains("百度") || title.contains("Baidu")

internal fun WebDavDriveHook.newThirdLoginRoute(): Any =
    cls(ROUTE_THIRD_LOGIN_CLASS).getDeclaredConstructor(java.lang.Integer.TYPE)
        .apply { isAccessible = true }
        .newInstance(BACKUP_TYPE_WEBDAV)

internal fun WebDavDriveHook.newStorageRoute(type: Int = BACKUP_TYPE_WEBDAV): Any =
    cls(ROUTE_STORAGE_CLASS).getDeclaredConstructor(java.lang.Integer.TYPE)
        .apply { isAccessible = true }
        .newInstance(type)

internal fun WebDavDriveHook.newHomeRoute(): Any =
    cls(ROUTE_HOME_CLASS).hostField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)

internal fun WebDavDriveHook.newCloudFolderRoute(path: String? = null, bookId: Long? = null, type: Int = BACKUP_TYPE_WEBDAV): Any {
    val route = cls(ROUTE_CLOUD_FOLDER_CLASS)
    if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
        return route.getDeclaredConstructor(
            Int::class.javaPrimitiveType, String::class.java, java.lang.Long::class.java,
            Int::class.javaPrimitiveType,
        ).newInstance(type, path, bookId, 0)
    }
    return route.getDeclaredConstructor(
        java.lang.Integer.TYPE,
        String::class.java,
        java.lang.Long::class.java,
    ).apply { isAccessible = true }.newInstance(
        type,
        path,
        bookId,
    )
}

internal fun WebDavDriveHook.navigateStorage(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newStorageRoute(type))
}

internal fun WebDavDriveHook.navigateCloudFolder(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newCloudFolderRoute(type = type))
}

internal fun WebDavDriveHook.navigateHome(navGraphScope: Any): Boolean =
    runCatching {
        com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newHomeRoute())
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to navigate WebDAV logout home: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun popBackStack(navGraphScope: Any) {
    com.reamicro.fix.core.HostNavigation.pop(navGraphScope)
}

internal fun WebDavDriveHook.flowOf(value: Any): Any {
    if (com.reamicro.fix.core.HostNativeAbi.hasNativePaging(com.reamicro.fix.core.HostSymbols.profileId)) return com.reamicro.fix.core.B2PagingData(classLoader).singleFlow(value)
    val method = cls(FLOW_KT_CLASS).methods.first {
        it.hostNameMatches(FLOW_OF_METHOD) && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
    }
    return method.invoke(null, arrayOf(value))
}

internal fun WebDavDriveHook.emptyBaiduAuth(): Any =
    newBaiduAuth(
        "",
        "time",
        "1",
        "",
        0L,
        0L,
        "/",
        "",
    )

internal fun WebDavDriveHook.emptyCloudUserInfo(): Any =
    newCloudUserInfo("", "", "", "", "", null, 0L, 0L)

internal fun WebDavDriveHook.emptyPagingData(): Any {
    if (com.reamicro.fix.core.HostNativeAbi.hasNativePaging(com.reamicro.fix.core.HostSymbols.profileId)) return com.reamicro.fix.core.B2PagingData(classLoader).from(emptyList())
    val pagingDataClass = cls(PAGING_DATA_CLASS)
    val companion = pagingDataClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
    return companion.javaClass.methods.first {
        it.hostNameMatches(PAGING_DATA_EMPTY_METHOD) && it.parameterTypes.isEmpty()
    }.apply { isAccessible = true }.invoke(companion)
}

internal fun WebDavDriveHook.pagingDataFrom(items: List<Any>): Any {
    if (com.reamicro.fix.core.HostNativeAbi.hasNativePaging(com.reamicro.fix.core.HostSymbols.profileId)) return com.reamicro.fix.core.B2PagingData(classLoader).from(items)
    val pagingDataClass = cls(PAGING_DATA_CLASS)
    val companion = pagingDataClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
    val idle = idleLoadStates()
    return companion.javaClass.methods.firstOrNull {
        it.hostNameMatches("from") &&
            it.parameterTypes.size == 2 &&
            List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
            typeMatches(it.parameterTypes[1], LOAD_STATES_CLASS)
    }?.apply { isAccessible = true }?.invoke(companion, items, idle)
        ?: companion.javaClass.methods.first {
            it.hostNameMatches("from") && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        }.apply { isAccessible = true }.invoke(companion, items)
}

internal fun WebDavDriveHook.idleLoadStates(): Any {
    val loadStatesClass = cls(LOAD_STATES_CLASS)
    val companion = runCatching {
        loadStatesClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
    }.getOrElse {
        loadStatesClass.hostField("INSTANCE").apply { isAccessible = true }.get(null)
    }
    return companion.javaClass.methods.first {
        it.hostNameMatches("getIDLE") && it.parameterTypes.isEmpty()
    }.apply { isAccessible = true }.invoke(companion)
}

internal fun WebDavDriveHook.emptyFlow(): Any =
    if (com.reamicro.fix.core.HostNativeAbi.hasNativePaging(com.reamicro.fix.core.HostSymbols.profileId)) cls("ReaMicroHost.EmptyFlow").getDeclaredField("a").get(null)
    else cls(FLOW_KT_CLASS).methods.first {
        it.hostNameMatches("emptyFlow") && it.parameterTypes.isEmpty()
    }.apply { isAccessible = true }.invoke(null)

internal fun WebDavDriveHook.kotlinResultSuccess(value: Any?): Any? =
    if (com.reamicro.fix.core.HostSymbols.abiProfileId in setOf("2.3.3-b2", "2.3.3-b4")) value
    else cls(KOTLIN_RESULT_CLASS).declaredMethods.first {
        it.name.contains("constructor") && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, value)

internal fun WebDavDriveHook.kotlinResultFailure(throwable: Throwable): Any {
    if (com.reamicro.fix.core.HostSymbols.abiProfileId in setOf("2.3.3-b2", "2.3.3-b4")) {
        return cls("kotlin.Result\$Failure").getDeclaredConstructor(Throwable::class.java).newInstance(throwable)
    }
    val failure = cls(KOTLIN_RESULT_KT_CLASS).declaredMethods.first {
        it.hostNameMatches("createFailure") && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, throwable)
    return requireNotNull(kotlinResultSuccess(failure))
}

internal fun WebDavDriveHook.createMutableStateFlow(value: Any): Any =
    if (com.reamicro.fix.core.HostNativeAbi.hasNativePaging(com.reamicro.fix.core.HostSymbols.profileId)) {
        // 此处输入均非 null，原生 StateFlowImpl 构造器无需 NULL 哨兵转换。
        cls("kotlinx.coroutines.flow.StateFlowImpl").getDeclaredConstructor(Any::class.java).newInstance(value)
    } else cls(STATE_FLOW_KT_CLASS).methods.first {
        it.hostNameMatches("MutableStateFlow") && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, value)

internal fun setMutableStateFlowValue(flow: Any?, value: Any) {
    flow?.javaClass?.methods?.firstOrNull {
        it.hostNameMatches("setValue") && it.parameterTypes.size == 1
    }?.apply { isAccessible = true }?.invoke(flow, value)
}

internal fun WebDavDriveHook.refreshCloudStorageScreen(type: Int) {
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

internal fun enabledOnlineCompletionSources(context: Context): List<OnlineSourceEntry> {
    val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    return OnlineSourceStore.list(context)
        .filter { source -> prefs.getBoolean(ModuleSettings.onlineSourceKey(source.id), false) }
}

internal fun logOnlineMetadataGaps(
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

internal fun onlineCompletionPlatformName(node: Any?, kind: String): String {
    val raw = onlineFirstJsonString(
        node,
        listOf(
            "platform",
            "sourcePlatform",
            "source_platform",
            "displaySourceName",
            "display_source_name",
            "sourceName",
            "source_name",
            "bookSource",
            "origin",
        ),
    ).ifBlank {
        kind.cleanOnlineText().split(Regex("\\s+")).firstOrNull().orEmpty()
    }
    return raw.normalizedAssociationPlatformName()
}

internal fun copyHomeUiStateWithCloudResults(state: Any, cloudResults: Map<Any?, Any?>): Any {
    if (com.reamicro.fix.core.HostHomeSearchState.isB4(state)) {
        return com.reamicro.fix.core.HostHomeSearchState.copyCloudResults(state, cloudResults)
    }
    val currentCloudResults = homeCloudSearchResults(state)
    val stateClass = state.javaClass
    val copyMethods = stateClass.methods
        .filter { it.hostNameMatches("copy") && it.returnType == stateClass && it.parameterTypes.isNotEmpty() }
        .sortedByDescending { it.parameterTypes.size }
    val expectedKeys = cloudResults.keys
    val failures = mutableListOf<String>()
    copyMethods.forEach { copyMethod ->
        runCatching {
            copyMethod.isAccessible = true
            val paramTypes = copyMethod.parameterTypes

            val baseArgs = Array<Any?>(paramTypes.size) { index ->
                runCatching { state.invokeNoArg("component${index + 1}") }.getOrNull()
            }

            val candidateIndices = paramTypes.indices.filter { i ->
                Map::class.java.isAssignableFrom(paramTypes[i])
            }
            if (candidateIndices.isEmpty()) {
                throw IllegalStateException(
                    "no Map parameter for ${paramTypes.size}-arg HomeUiState.copy",
                )
            }

            val orderedCandidates = candidateIndices.sortedByDescending { i ->
                if (baseArgs[i] != null && baseArgs[i] === currentCloudResults) 1 else 0
            }
            var lastCopied: Any? = null
            orderedCandidates.forEach { cloudIndex ->
                val attemptResult = runCatching {
                    val args = baseArgs.copyOf()
                    args[cloudIndex] = cloudResults
                    val copied = copyMethod.invoke(state, *args)
                    if (!stateClass.isInstance(copied)) {
                        return@runCatching null
                    }
                    lastCopied = copied
                    val resultMap = runCatching { homeCloudSearchResults(copied) }.getOrNull()

                    val verified = resultMap != null && expectedKeys.all { resultMap.containsKey(it) }
                    if (verified) copied else null
                }.getOrNull()
                if (attemptResult != null) return attemptResult
            }

            if (expectedKeys.isEmpty() && lastCopied != null) return lastCopied!!
            failures += "${paramTypes.size}: no verified cloud index in ${orderedCandidates.joinToString(",")}"
        }.onFailure {
            failures += "${copyMethod.parameterTypes.size}: ${it.javaClass.name}: ${it.message}"
        }
    }
    XposedBridge.log("$LOG_PREFIX HomeUiState dynamic copy failed: ${failures.joinToString(" | ")}")
    return copyHomeUiStateWithCloudResultsLegacy(state, cloudResults)
}

internal fun copyHomeUiStateWithCloudResultsLegacy(state: Any, cloudResults: Map<Any?, Any?>): Any {
    val copyMethod = state.javaClass.methods.firstOrNull {
        it.hostNameMatches("copy") && it.parameterTypes.size == 13
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

internal fun WebDavDriveHook.newCloudFolder(entry: WebDavEntry): Any =
    newStorageFolder(
        entry.name,
        normalizeWebDavPath(entry.path),
        false,
        entry.updatedAt,
    )

internal fun WebDavDriveHook.newDir(id: String, name: String): Any =
    cls(DIR_CLASS).getDeclaredConstructor(
        String::class.java,
        String::class.java,
    ).apply { isAccessible = true }.newInstance(id, name)

internal fun WebDavDriveHook.newCloudBook(entry: WebDavEntry): Any {
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

internal fun WebDavDriveHook.newOnlineCompletionSourceCloudBook(group: OnlineSearchGroup): Any {
    val encodedQuery = URLEncoder.encode(group.query, "UTF-8")
    val path = "$ONLINE_COMPLETION_SOURCE_PREFIX${group.source.id}?q=$encodedQuery"
    val best = group.results.firstOrNull()
    if (best != null) {
        updateOnlineCompletionSearchTarget(path, OnlineDownloadTarget(group.source, group.query, best))
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

internal fun onlineCompletionGroupTitle(group: OnlineSearchGroup): String =
    "$ONLINE_COMPLETION_TITLE-${group.source.name.ifBlank { "未知来源" }}"

internal fun WebDavDriveHook.onlineCompletionTitleForType(type: Int, fallback: String = ONLINE_COMPLETION_TITLE): String =
    onlineCompletionRenderTitles[type].orEmpty()
        .ifBlank { onlineCompletionCloudTitleText.get().orEmpty() }
        .ifBlank { fallback }
        .ifBlank { ONLINE_COMPLETION_TITLE }

internal fun WebDavDriveHook.onlineCompletionGroupVisibleCloudBooks(group: OnlineSearchGroup): List<Any> {
    if (group.results.isEmpty()) return listOf(newOnlineCompletionSourceCloudBook(group))
    return group.results.take(ONLINE_COMPLETION_RESULT_LIMIT).mapIndexedNotNull { index, result ->
        runCatching { newOnlineCompletionResultCloudBook(group.source, group.query, result, index) }
            .onFailure { error ->
                XposedBridge.log(
                    "$LOG_PREFIX online completion cloud book failed source=${group.source.name} " +
                        "name=${result.name} error=${error.stackTraceToString()}",
                )
            }
            .getOrNull()
    }.ifEmpty { listOf(newOnlineCompletionSourceCloudBook(group)) }
}

internal fun WebDavDriveHook.newOnlineCompletionResultCloudBook(
    source: OnlineSourceEntry,
    query: String,
    result: OnlineBookSearchResult,
    index: Int,
): Any {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val path = "$ONLINE_COMPLETION_BOOK_PREFIX${source.id}/$index?q=$encodedQuery"
    updateOnlineCompletionSearchTarget(path, OnlineDownloadTarget(source, query, result))
    return newOnlineCompletionCloudBook(
        path = path,
        name = result.name,
        subtitle = onlineCompletionSearchMetaLine(result),
        result = result,
    )
}

internal fun WebDavDriveHook.newOnlineCompletionCloudBook(
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

internal fun WebDavDriveHook.newOnlineCompletionLocalBook(path: String, result: OnlineBookSearchResult): Any {
    val now = System.currentTimeMillis()
    val bookClass = classLoader.hostClass(BOOK_CLASS)

    // B4 的 Book 是 27 字段不可变记录；按核实顺序填值，禁止用“最长构造器 + 类型默认值”猜槽位。
    val constructor = bookClass.declaredConstructors
        .single { it.parameterTypes.size == 27 && it.parameterTypes[0] == java.lang.Long.TYPE }
        .apply { isAccessible = true }
    val types = constructor.parameterTypes
    val args = arrayOfNulls<Any?>(types.size)
    args[0] = 0L
    args[1] = UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString()
    args[2] = 0L
    args[3] = result.name
    args[4] = ""
    args[5] = result.author
    args[6] = result.coverUrl
    args[7] = 0L
    args[8] = result.detailUrl.ifBlank { path }
    args[9] = ""
    args[10] = now
    args[11] = 1
    args[12] = 0
    args[13] = ""
    args[14] = ""
    args[15] = 0f
    args[16] = 0L
    args[17] = 0L
    args[18] = 0L
    args[19] = 0L
    args[20] = 0L
    args[21] = 0L
    args[22] = BACKUP_TYPE_ONLINE_COMPLETION
    args[23] = path
    args[24] = ""
    args[25] = result.sourceName
    args[26] = false
    return constructor.newInstance(*args)
}

internal fun WebDavDriveHook.emptyCloudBook(): Any =
    cls(CLOUD_BOOK_CLASS).hostField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)
        .let { companion ->
            companion.javaClass.methods.first { it.hostNameMatches("getEmpty") && it.parameterTypes.isEmpty() }
                .apply { isAccessible = true }
                .invoke(companion)
        }

internal fun isSupportedBookFile(name: String): Boolean =
    BOOK_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

internal fun isOnlineCompletionPath(path: String): Boolean =
    path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) || path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)

internal fun isOnlineCompletionLocalBook(book: Any): Boolean =
    isOnlineCompletionPath(bookBackupIdOf(book)) ||
        isOnlineCompletionPath(book.callString("getUri")) ||
        isOnlineCompletionUuid(book.callString("getUuid")) ||
        (bookBackupTypeOf(book) == BACKUP_TYPE_ONLINE_COMPLETION &&
            book.callString("getBackupCode").startsWith("online_"))

internal fun isOnlineCompletionUuid(uuid: String): Boolean =
    uuid.startsWith(ONLINE_COMPLETION_UUID_PREFIX)

internal fun WebDavDriveHook.onlineCompletionGeneratedEpubMetadata(book: Any): OnlineCompletionEpubMetadata =
    runCatching {
        val contentOpf = File(bookDirectory(book).canonicalFile, "OEBPS/content.opf")
        if (!contentOpf.isFile) return@runCatching OnlineCompletionEpubMetadata()
        val parser = Xml.newPullParser()
        contentOpf.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            var sourceId = ""
            var sourceName = ""
            var detailUrl = ""
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "meta") {
                    when (parser.getAttributeValue(null, "name").orEmpty()) {
                        "reamicro-online-source-id" -> sourceId = parser.getAttributeValue(null, "content").orEmpty()
                        "reamicro-online-source-name" -> sourceName = parser.getAttributeValue(null, "content").orEmpty()
                        "reamicro-online-detail-url" -> detailUrl = parser.getAttributeValue(null, "content").orEmpty()
                    }
                }
                eventType = parser.next()
            }
            OnlineCompletionEpubMetadata(
                sourceId = sourceId,
                sourceName = sourceName,
                detailUrl = detailUrl,
            )
        }
    }.getOrDefault(OnlineCompletionEpubMetadata())

internal fun WebDavDriveHook.updateOnlineCompletionBookIncrementally(
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
    onProgress(1, "同步 EPUB 默认样式")
    val styleChanged = syncOnlineCompletionDefaultStyle(bookDir)
    val tocSnapshot = loadOnlineCompletionToc(target) { progress, message ->
        onProgress(progress.coerceAtMost(12), message)
    }
    val remoteChapters = tocSnapshot.chapters
    val chapterAllowance = OnlineSourceDownloadPolicyStore.remainingToday(currentContext(), target.source)
    val (chapterHrefs, newRemoteIndices) = onlineChapterUpdatePlan(bookDir, remoteChapters)
    val originalFailures = readOnlineCompletionFailedChapters(bookDir)
    val loggedFailures = remapOnlineCompletionFailures(
        failures = originalFailures,
        remoteChapters = remoteChapters,
        chapterHrefs = chapterHrefs,
    )
    val detectedFailures = detectInvalidOnlineCompletionChapters(
        bookDir = bookDir,
        target = target,
        remoteChapters = remoteChapters,
        chapterHrefs = chapterHrefs,
        ignoredIndices = loggedFailures.mapTo(hashSetOf()) { it.index },
    )
    val retryFailures = (loggedFailures + detectedFailures).sortedBy { it.index }
    if (retryFailures != originalFailures) {
        writeOnlineCompletionFailedChapters(bookDir, target, retryFailures)
    }
    val retryResult = retryOnlineCompletionLoggedFailures(
        bookDir = bookDir,
        target = target,
        tocSnapshot = tocSnapshot,
        failures = retryFailures,
        chapterHrefs = chapterHrefs,
        maxChapterAttempts = chapterAllowance,
        onProgress = onProgress,
    )
    if (newRemoteIndices.isEmpty()) {
        val structureChanged = appendOnlineCompletionChapters(
            bookDir = bookDir,
            target = target,
            remoteChapters = remoteChapters,
            chapterHrefs = chapterHrefs,
            newChapters = emptyMap(),
        )
        if (structureChanged) refreshOnlineCompletionCatalogTables(book, bookDir)
        if (styleChanged || structureChanged || retryResult.changed) {
            val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
            updateLocalBook(
                copyBookWithBackupAndPublisher(
                    book = latest,
                    backupType = BACKUP_TYPE_ONLINE_COMPLETION,
                    backupId = bookBackupIdOf(latest).ifBlank { onlineImportedBookBackupId(target) },
                    backupCode = latest.callString("getBackupCode").ifBlank { target.source.id },
                    publisher = latest.callString("getPublisher"),
                    cover = latest.callString("getCover").ifBlank { target.result.coverUrl },
                    size = bookDirectorySize(bookDir),
                    updated = System.currentTimeMillis(),
                ),
            )
        }
        logWebDav(
            "online completion update no new chapters title=${target.result.name} " +
                "remote=${remoteChapters.size} retried=${retryResult.retried} " +
                "failed=${retryResult.failed} style=$styleChanged structure=$structureChanged",
        )
        return OnlineChapterUpdateResult(0, retryResult.failed, retryResult.retried, styleChanged)
    }
    logWebDav(
        "online completion update start title=${target.result.name} " +
            "remote=${remoteChapters.size} new=${newRemoteIndices.size} " +
            "retried=${retryResult.retried} previousFailed=${retryResult.failed}",
    )
    val newChapterAllowance = chapterAllowance
        ?.minus(retryResult.attempted)
        ?.coerceAtLeast(0)
    val allowedNewChapterCount = newChapterAllowance
        ?.coerceAtMost(newRemoteIndices.size)
        ?: newRemoteIndices.size
    val quotaPendingMessage = OnlineSourceDownloadPolicyStore.limitReachedMessage(target.source)
    val downloaded = MutableList<OnlineDownloadedChapter?>(newRemoteIndices.size) { null }
    val attempts = IntArray(newRemoteIndices.size)
    val failed = linkedSetOf<Int>()
    val nonRetryable = linkedSetOf<Int>()
    val failureMessages = mutableMapOf<Int, String>()
    var dailyLimitMessage: String? = null
    var consecutiveServiceFailures = 0
    val batchSession = onlineChapterBatchSession(
        target,
        remoteChapters,
        newRemoteIndices.take(allowedNewChapterCount),
    )
    for (offset in allowedNewChapterCount until newRemoteIndices.size) {
        failed.add(offset)
        nonRetryable.add(offset)
        failureMessages[offset] = quotaPendingMessage
    }

    fun attemptChapter(offset: Int, immediateRetry: Boolean): Boolean {
        val remoteIndex = newRemoteIndices[offset]
        val chapter = remoteChapters[remoteIndex]
        dailyLimitMessage?.let { message ->
            failed.add(offset)
            nonRetryable.add(offset)
            failureMessages[offset] = message
            return false
        }
        val maxAttemptsThisRound = if (immediateRetry) 2 else 1
        var roundAttempts = 0
        while (attempts[offset] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT &&
            roundAttempts < maxAttemptsThisRound && offset !in nonRetryable
        ) {
            attempts[offset]++
            roundAttempts++
            val attempt = attempts[offset]
            runCatching {
                downloadOnlineChapter(
                    target,
                    tocSnapshot.detail.url,
                    tocSnapshot.detail.body,
                    chapter,
                    remoteIndex,
                    batchSession,
                )
            }.onSuccess { chapterContent ->
                downloaded[offset] = chapterContent
                failed.remove(offset)
                failureMessages.remove(offset)
                val recoveredFromServiceFailure = consecutiveServiceFailures > 0
                consecutiveServiceFailures = 0
                if (recoveredFromServiceFailure) {
                    onProgress(
                        32 + ((offset + 1) * 51 / newRemoteIndices.size.coerceAtLeast(1)),
                        "上游已恢复，继续新增章节 ${offset + 1}/${newRemoteIndices.size}",
                    )
                }
                logWebDav(
                    "online completion update chapter downloaded ${offset + 1}/${newRemoteIndices.size} " +
                        "remote=${remoteIndex + 1}/${remoteChapters.size} " +
                        "transport=${chapterContent.transport} attempt=$attempt " +
                        "content=${chapterContent.content.length}",
                )
                return true
            }.onFailure { error ->
                failed.add(offset)
                val message = error.message.orEmpty().ifBlank { error.javaClass.name }
                failureMessages[offset] = message
                if (error is OnlineSourceDailyLimitException) {
                    dailyLimitMessage = message
                    nonRetryable.add(offset)
                }
                if (isPermanentOnlineChapterFailure(error)) {
                    nonRetryable.add(offset)
                }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && offset !in nonRetryable) {
                    consecutiveServiceFailures++
                    val waitMs = onlineHttpRetryDelayMs(error, consecutiveServiceFailures)
                    val reason = if (retryKind == OnlineHttpRetryKind.CIRCUIT_OPEN) "上游熔断" else "上游暂时异常"
                    onProgress(
                        32 + ((offset + 1) * 51 / newRemoteIndices.size.coerceAtLeast(1)),
                        "$reason，等待 ${waitMs / 1_000} 秒后继续新增章节 ${offset + 1}/${newRemoteIndices.size}",
                    )
                    logWebDav(
                        "online completion update service backoff chapter=${offset + 1}/${newRemoteIndices.size} " +
                            "kind=$retryKind consecutive=$consecutiveServiceFailures wait=${waitMs}ms",
                    )
                    sleepOnlineCompletionRetryDelay(null, waitMs)
                }
                logWebDav(
                    "online completion update chapter failed ${offset + 1}/${newRemoteIndices.size} " +
                        "remote=${remoteIndex + 1}/${remoteChapters.size} " +
                        "attempt=$attempt/${ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT} " +
                        "title=${chapter.title} error=${error.javaClass.name}: ${error.message.orEmpty()}",
                )
            }
        }
        return false
    }

    newRemoteIndices.forEachIndexed { offset, _ ->
        val progress = 32 + ((offset + 1) * 51 / newRemoteIndices.size.coerceAtLeast(1))
        onProgress(progress, "下载新增章节 ${offset + 1}/${newRemoteIndices.size}${onlineCompletionFailureSuffix(failed.size)}")
        attemptChapter(offset, immediateRetry = true)
        if (failed.isNotEmpty()) {
            onProgress(progress, "下载新增章节 ${offset + 1}/${newRemoteIndices.size}${onlineCompletionFailureSuffix(failed.size)}")
        }
    }
    var retryRound = 0
    while (failed.any { it !in nonRetryable && attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }) {
        retryRound++
        val retryCandidates = failed.filter { it !in nonRetryable && attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }
        val waitMs = onlineCompletionRetryDelayMs(retryRound)
        onProgress(84, "等待重试新增章节 ${retryCandidates.size} 章${onlineCompletionFailureSuffix(failed.size)}")
        logWebDav(
            "online completion update delayed retry round=$retryRound wait=${waitMs}ms " +
                "candidates=${retryCandidates.size} failed=${failed.size} " +
                "success=${downloaded.count { it != null }}/${newRemoteIndices.size}",
        )
        sleepOnlineCompletionRetryDelay(null, waitMs)
        retryCandidates.forEachIndexed { retryIndex, offset ->
            val progress = 84 + ((retryIndex + 1) * 4 / retryCandidates.size.coerceAtLeast(1))
            onProgress(progress, "重试新增章节 ${offset + 1}/${newRemoteIndices.size}${onlineCompletionFailureSuffix(failed.size)}")
            attemptChapter(offset, immediateRetry = false)
            if (failed.isNotEmpty()) {
                onProgress(progress, "重试新增章节 ${offset + 1}/${newRemoteIndices.size}${onlineCompletionFailureSuffix(failed.size)}")
            }
        }
    }
    val successCount = downloaded.count { it != null }
    val newDownloadedChapters = newRemoteIndices.mapIndexed { offset, remoteIndex ->
        val chapter = remoteChapters[remoteIndex]
        remoteIndex to (downloaded[offset] ?: OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${remoteIndex + 1} 章" },
            content = onlineCompletionFailurePlaceholder(attempts[offset], failureMessages[offset].orEmpty()),
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            sourceUrl = chapter.url,
        ))
    }.toMap()
    val newFailures = failed.sorted().map { offset ->
        val remoteIndex = newRemoteIndices[offset]
        onlineCompletionFailedChapter(
            target = target,
            chapter = remoteChapters[remoteIndex],
            index = remoteIndex,
            attempts = attempts[offset],
            message = failureMessages[offset].orEmpty(),
            href = chapterHrefs[remoteIndex],
        )
    }
    logOnlineCompletionFinalFailures(target, newFailures, "update")
    onProgress(90, "写入新增章节")
    appendOnlineCompletionChapters(
        bookDir = bookDir,
        target = target,
        remoteChapters = remoteChapters,
        chapterHrefs = chapterHrefs,
        newChapters = newDownloadedChapters,
    )
    if (newFailures.isNotEmpty()) {
        mergeOnlineCompletionFailedChapters(bookDir, target, newFailures)
    }
    val newSize = bookDirectorySize(bookDir)
    val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
    refreshOnlineCompletionCatalogTables(latest, bookDir)
    val updated = copyBookWithBackupAndPublisher(
        book = latest,
        backupType = BACKUP_TYPE_ONLINE_COMPLETION,
        backupId = bookBackupIdOf(latest).ifBlank { onlineImportedBookBackupId(target) },
        backupCode = latest.callString("getBackupCode").ifBlank { target.source.id },
        publisher = latest.callString("getPublisher"),
        cover = latest.callString("getCover").ifBlank { target.result.coverUrl },
        size = newSize,
        updated = System.currentTimeMillis(),
    )
    updateLocalBook(updated)
    logWebDav(
        "online completion update complete title=${target.result.name} " +
            "added=$successCount retried=${retryResult.retried} " +
            "failed=${readOnlineCompletionFailedChapters(bookDir).size} total=${remoteChapters.size} size=$newSize",
    )
    return OnlineChapterUpdateResult(
        added = successCount,
        failed = readOnlineCompletionFailedChapters(bookDir).size,
        retried = retryResult.retried,
        styled = styleChanged,
    )
}

internal fun WebDavDriveHook.refreshOnlineCompletionCatalogTables(book: Any, bookDir: File) {
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
        managerClass.hostField("INSTANCE").apply { isAccessible = true }.get(null)
    }.getOrNull() ?: error("阅微 EpubFileManager INSTANCE 未找到")
    val bookPath = okioPath(bookDir.canonicalFile)
    val opf = obtainOnlineCompletionOpf(bookPath)
    val managerMethods = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
        .distinct()
        .toList()
    val itemRefMethod = managerMethods.firstOrNull {
        it.hostNameMatches("getItemRefs") &&
            it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == java.lang.Long.TYPE &&
            typeMatches(it.parameterTypes[1], OPF_CLASS) &&
            typeMatches(it.parameterTypes[2], OKIO_PATH_CLASS)
    } ?: error(
        "阅微 EpubFileManager.getItemRefs 方法未找到；候选=" +
            methodCandidates(managerMethods, "getItemRefs"),
    )
    val itemRefs = itemRefMethod
        .apply { isAccessible = true }
        .invoke(manager, java.lang.Long.valueOf(bookId), opf, bookPath) as? List<*>
        ?: emptyList<Any>()
    if (itemRefs.isEmpty()) {
        error("阅微目录条目解析为空，已阻止刷新目录表")
    }

    val chapterMethod = managerMethods
        .filter {
            it.hostNameMatches("getChapters") &&
                (it.parameterTypes.size == 3 || it.parameterTypes.size == 4) &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                typeMatches(it.parameterTypes[1], OPF_CLASS) &&
                typeMatches(it.parameterTypes[2], OKIO_PATH_CLASS) &&
                (it.parameterTypes.size == 3 || typeMatches(it.parameterTypes[3], FUNCTION2_CLASS))
        }
        .minByOrNull { it.parameterTypes.size }
        ?: error(
            "阅微 EpubFileManager.getChapters 方法未找到；候选=" +
                methodCandidates(managerMethods, "getChapters"),
        )
    val chapterArgs = arrayListOf<Any?>(itemRefs, opf, bookPath).apply {
        if (chapterMethod.parameterTypes.size == 4) add(null)
    }
    val chapters = chapterMethod
        .apply { isAccessible = true }
        .invoke(manager, *chapterArgs.toTypedArray()) as? List<*>
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

internal fun WebDavDriveHook.replaceOnlineCompletionDaoRows(dao: Any, bookId: Long, rows: List<*>, label: String) {
    val daoMethods = (dao.javaClass.methods.asSequence() + dao.javaClass.declaredMethods.asSequence())
        .distinct()
        .toList()
    val deleteMethod = daoMethods.firstOrNull {
        it.hostNameMatches("deleteByBookId") &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == java.lang.Long.TYPE &&
            typeMatches(it.parameterTypes[1], KOTLIN_CONTINUATION_CLASS)
    }?.apply { isAccessible = true }
        ?: error("阅微 $label DAO.deleteByBookId 方法未找到；候选=${methodCandidates(daoMethods, "deleteByBookId")}")
    val upsertMethod = daoMethods.firstOrNull {
        it.hostNameMatches("upsert") &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0].isArray &&
            typeMatches(it.parameterTypes[1], KOTLIN_CONTINUATION_CLASS)
    }?.apply { isAccessible = true }
        ?: error("阅微 $label DAO.upsert 方法未找到；候选=${methodCandidates(daoMethods, "upsert")}")
    val componentType: Class<*> = requireNotNull(upsertMethod.parameterTypes[0].componentType) {
        "阅微 $label DAO.upsert 数组元素类型不可用"
    }
    val array = java.lang.reflect.Array.newInstance(componentType, rows.size)
    rows.forEachIndexed { index, row -> java.lang.reflect.Array.set(array, index, row) }
    invokeSuspendBlocking(deleteMethod, dao, java.lang.Long.valueOf(bookId))
    invokeSuspendBlocking(upsertMethod, dao, array)
    logWebDav("online completion $label rows replaced bookId=$bookId count=${rows.size}")
}

internal fun methodCandidates(methods: List<Method>, name: String): String =
    methods.filter { it.hostNameMatches(name) }
        .joinToString(prefix = "[", postfix = "]") { method ->
            method.parameterTypes.joinToString(
                prefix = "${method.name}(",
                postfix = "):${method.returnType.name}",
            ) { type -> type.name }
        }

internal fun onlineSourceIdFromEncodedValue(value: String): String {
    val text = value.trim()
    if (text.isBlank()) return ""
    if (text.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) {
        return runCatching { Uri.parse(text).host.orEmpty() }.getOrDefault("")
    }
    return text.takeUnless { it.contains("://") }.orEmpty()
}

internal fun onlineCompletionQueryParameter(value: String, name: String): String =
    runCatching {
        if (!value.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) return@runCatching ""
        Uri.parse(value).getQueryParameter(name).orEmpty()
    }.getOrDefault("")

internal fun isPathLikeOnlineSourceName(value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    if (text.contains("://")) return true
    if (text.startsWith("/") || text.startsWith("\\") || text.startsWith("／")) return true
    if (Regex("""^[A-Za-z]:[\\/].*""").matches(text)) return true
    return text.endsWith(".epub", ignoreCase = true) ||
        text.endsWith(".txt", ignoreCase = true) ||
        text.endsWith(".pdf", ignoreCase = true)
}

internal fun onlineCompletionDisplayProgress(progress: Int, message: String): Int {
    val match = ONLINE_COMPLETION_PROGRESS_CHAPTER_REGEX.find(message) ?: return progress.coerceIn(0, 100)
    val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
    val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
    if (total <= 0) return progress.coerceIn(0, 100)
    return ((current.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
}

internal fun onlineCompletionFailedCount(message: String): Int =
    ONLINE_COMPLETION_FAILED_COUNT_REGEX.find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

internal fun onlineCompletionFailureSuffix(failedCount: Int): String =
    if (failedCount > 0) " · 失败 $failedCount 章" else ""

internal fun logOnlineCompletionFinalFailures(
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
    stage: String,
) {
    failures.forEach { failure ->
        logWebDav(
            "online completion $stage final failed chapter " +
                "${failure.index + 1} source=${target.source.name} book=${target.result.name} " +
                "attempts=${failure.attempts} title=${failure.title} url=${failure.url.take(160)} " +
                "error=${failure.message}",
        )
    }
}

internal fun WebDavDriveHook.onlineContentProxyTemplate(source: OnlineSourceEntry): String? {
    val contentRule = runCatching { JSONObject(source.ruleContent).optString("content", "") }.getOrNull().orEmpty()
    return extractOnlineBacktickTemplates(contentRule)
        .firstOrNull { it.contains("\${itemId}") }
        ?: OnlineSourceScriptCompat.resolveContentRequestTemplate(
            sourceUrl = sourceBaseUrl(source),
            rawScript = contentRule,
            loginInfo = OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), source),
        )
}

internal fun extractOnlineBacktickTemplates(rule: String): List<String> {
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

internal fun WebDavDriveHook.evaluateOnlineContentJs(
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

internal fun onlineReadTimeoutMillis(source: OnlineSourceEntry): Int =
    source.respondTime.coerceIn(15_000, 300_000)

internal fun onlineConnectTimeoutMillis(source: OnlineSourceEntry): Int =
    onlineReadTimeoutMillis(source).coerceAtMost(30_000).coerceAtLeast(10_000)

internal fun onlineCompletionFailurePlaceholder(attempts: Int, message: String): String =
    if (message.contains("今日已达到章节下载限额")) {
        "本章尚未下载，当前在线源今日额度已用完。\n\n请明日点击更新继续补全。"
    } else {
        "本章下载失败，已按在线源重试 $attempts 次。\n\n$message"
    }

internal fun bookDirectorySize(bookDir: File): Long =
    bookDir.walkTopDown()
        .filter { it.isFile }
        .fold(0L) { total, file -> total + file.length() }

internal fun onlineCompletionFailedLogFile(bookDir: File): File =
    File(bookDir, "OEBPS/$ONLINE_COMPLETION_FAILED_CHAPTER_LOG")

internal fun WebDavDriveHook.findLocalBookByUrl(bookshelf: Any, url: String): Any? {
    if (url.isBlank()) return null
    val candidates = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .filter {
            (it.hostNameMatches("liveBookByUrl") || it.hostNameMatches("findBookByUrl")) &&
                it.parameterTypes.size in 1..2
        }
        .distinct()
        .toList()
    // B2/B4 的 liveBookByUrl 返回 Flow，旧版 findBookByUrl 才是挂起函数。
    candidates.firstOrNull { it.parameterTypes.size == 1 }?.let { flowMethod ->
        flowMethod.isAccessible = true
        val flow = runCatching { flowMethod.invoke(bookshelf, url) }.getOrNull()
        if (flow != null) return firstFlowValue(flow)
    }
    val findMethod = candidates.firstOrNull {
        it.parameterTypes.size == 2 && typeMatches(it.parameterTypes.last(), "kotlin.coroutines.Continuation")
    }
        ?.apply { isAccessible = true }
        ?: candidates.firstOrNull { it.parameterTypes.size == 2 }?.apply { isAccessible = true }
        ?: return null
    return invokeSuspendBlocking(findMethod, bookshelf, url)
}

internal fun currentOnlineCompletionBooksDir(bookshelf: Any): Any {
    val userStorage = fieldValue(bookshelf, "userStorage")
        ?: bookshelf.javaClass.methods.firstOrNull { it.hostNameMatches("getUserStorage") && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(bookshelf)
        ?: error("阅微书库目录不可用：UserStorage 未找到")
    val uid = (fieldValue(userStorage, "uid") as? Number)?.toLong()?.takeIf { it > 0L }
        ?: (userStorage.javaClass.declaredFields.asSequence() + userStorage.javaClass.fields.asSequence())
            .firstOrNull { it.type == java.lang.Long::class.java }
            ?.apply { isAccessible = true }
            ?.get(userStorage)
            ?.let { it as? Number }
            ?.toLong()
            ?.takeIf { it > 0L }
        ?: error("阅微书库目录不可用：当前用户 UID 未找到")
    val userStorageMethods =
        userStorage.javaClass.methods.asSequence() + userStorage.javaClass.declaredMethods.asSequence()
    val directMethod = userStorageMethods.firstOrNull {
        it.hostNameMatches("userBooksDir") &&
            it.parameterTypes.size == 1 &&
            (it.parameterTypes[0] == java.lang.Long.TYPE || it.parameterTypes[0] == java.lang.Long::class.java)
    }
    if (directMethod != null) {
        directMethod.isAccessible = true
        return directMethod.invoke(userStorage, java.lang.Long.valueOf(uid))
            ?: error("阅微书库目录不可用：userBooksDir 返回空")
    }

    // Kotlin 默认参数桥接不能传 null，否则 B4 会按默认 uid=0 返回 files/0/books，
    // 导致书架记录属于当前用户、EPUB 文件却落在 0 用户目录。
    val defaultMethod = userStorageMethods.firstOrNull {
        it.hostNameMatches("userBooksDir\$default") &&
            Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.size == 4
    } ?: error("阅微书库目录不可用：userBooksDir 方法未找到")
    defaultMethod.isAccessible = true
    return defaultMethod.invoke(null, userStorage, java.lang.Long.valueOf(uid), 0, null)
        ?: error("阅微书库目录不可用：userBooksDir 返回空")
}

internal fun onlineCompletionCancelPendingIntent(context: Context, id: Int, key: String): PendingIntent {
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

internal fun sendOnlineCompletionCancelBroadcastToModule(context: Context, id: Int, key: String): Boolean =
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

internal fun WebDavDriveHook.rememberCloudStorageRepository(repository: Any?) {
    if (repository == null) return
    cloudStorageRepositoryRef = WeakReference(repository)
    fieldValue(repository, "bookshelfRepository")?.let {
        bookshelfRepositoryRef = WeakReference(it)
    }
    fieldValue(repository, "workerManager")?.let {
        rememberWorkerManager(it)
    }
}

internal fun WebDavDriveHook.rememberHomeViewModelDependencies(viewModel: Any?) {
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

internal fun WebDavDriveHook.rememberBookshelfRepository(repository: Any?) {
    if (repository == null) return
    bookshelfRepositoryRef = WeakReference(repository)
    repairOnlineCompletionBookMetadataSoon(repository)
}

internal fun WebDavDriveHook.rememberWorkerManager(workerManager: Any?) {
    if (workerManager == null) return
    workerManagerRef = WeakReference(workerManager)
    fieldValue(workerManager, "bookshelf")?.let {
        rememberBookshelfRepository(it)
    }
    fieldValue(workerManager, "tracker")?.let {
        workTrackerRef = WeakReference(it)
    }
}

internal fun WebDavDriveHook.currentBookshelfRepository(): Any? =
    bookshelfRepositoryRef?.get()
        ?: cloudStorageRepositoryRef?.get()?.let { fieldValue(it, "bookshelfRepository") }
        ?: workerManagerRef?.get()?.let { fieldValue(it, "bookshelf") }

internal fun WebDavDriveHook.awaitBookshelfRepository(timeoutMs: Long = ONLINE_COMPLETION_REPOSITORY_WAIT_TIMEOUT_MS): Any? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var logged = false
    while (true) {
        currentBookshelfRepository()?.let { return it }
        if (System.currentTimeMillis() >= deadline) return null
        if (!logged) {
            logWebDav("online completion waiting for bookshelf repository after activity recreation")
            logged = true
        }
        Thread.sleep(ONLINE_COMPLETION_REPOSITORY_WAIT_STEP_MS)
    }
}

internal fun WebDavDriveHook.currentWorkTracker(): Any? =
    workTrackerRef?.get()
        ?: workerManagerRef?.get()?.let { fieldValue(it, "tracker") }

internal fun WebDavDriveHook.repairOnlineCompletionBookMetadataSoon(repository: Any) {
    val now = System.currentTimeMillis()
    val last = onlineCompletionMetadataRepairAt.get()
    if (last > 0L && now - last < ONLINE_COMPLETION_METADATA_REPAIR_INTERVAL_MS) return
    if (!onlineCompletionMetadataRepairAt.compareAndSet(last, now)) return
    Thread({
        runCatching {
            repairOnlineCompletionBookMetadata(repository)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX online completion metadata repair failed: ${it.stackTraceToString()}")
        }
    }, "ReaMicroOnlineCompletionRepair").start()
}

internal fun WebDavDriveHook.repairOnlineCompletionBookMetadata(repository: Any) {
    val books = listLocalBooks(repository) ?: return
    var repaired = 0
    books.forEach { book ->
        if (book == null) return@forEach
        if (bookBackupTypeOf(book) != BACKUP_TYPE_ONLINE_COMPLETION) return@forEach
        val currentPinnedAt = book.callLong("getPinnedAt")
        val currentCloudId = book.callLong("getCloudId")
        val needsPinnedCloudRepair = currentCloudId == 0L &&
            currentPinnedAt in 1 until ONLINE_COMPLETION_PINNED_TIMESTAMP_FLOOR
        if (!needsPinnedCloudRepair) return@forEach
        logWebDav(
            "online completion repairing swapped pinned/cloud title=${book.callString("getTitle")} " +
                "pinnedAt=$currentPinnedAt cloudId=$currentCloudId",
        )
        val updatedBook = copyBookWithBackupAndPublisher(
            book = book,
            backupType = BACKUP_TYPE_ONLINE_COMPLETION,
            backupId = bookBackupIdOf(book),
            backupCode = book.callString("getBackupCode"),
            publisher = book.callString("getPublisher"),
            cover = book.callString("getCover"),
            pinnedAt = 0L,
            cloudId = currentPinnedAt,
        )
        updateLocalBook(updatedBook)
        repaired++
    }
    if (repaired > 0) {
        logWebDav("online completion repaired book metadata count=$repaired")
    }
}

internal fun WebDavDriveHook.listLocalBooks(repository: Any): Iterable<*>? {
    val uid = currentUserId(repository) ?: return null
    val bookDao = fieldValue(repository, "bookDao") ?: return null
    val listMethod = (bookDao.javaClass.methods.asSequence() + bookDao.javaClass.declaredMethods.asSequence())
        .firstOrNull { candidate ->
            candidate.hostNameMatches("listByUid") &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0] == java.lang.Long.TYPE
        }
        ?.apply { isAccessible = true }
        ?: return null
    val flow = listMethod.invoke(bookDao, uid) ?: return null
    return firstFlowValue(flow) as? Iterable<*>
}

internal fun WebDavDriveHook.currentUserId(repository: Any): Long? =
    runCatching {
        val flow = (repository.javaClass.methods.asSequence() + repository.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.hostNameMatches("getCurrentUserFlow") && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(repository)
            ?: fieldValue(repository, "currentUserFlow")
        flow?.let {
            firstFlowValue(it)?.callLong("getId")?.takeIf { userId -> userId > 0L }?.let { return@runCatching it }
        }

        // B4 的 BookshelfRepository 不再暴露 getCurrentUserFlow，UserStorage.uid 是同一份当前用户 ID。
        val userStorage = fieldValue(repository, "userStorage")
        val storedUid = fieldValue(userStorage, "uid")
            ?: userStorage?.let { storage ->
                (storage.javaClass.declaredFields.asSequence() + storage.javaClass.fields.asSequence())
                    .firstOrNull { it.type == java.lang.Long::class.java }
                    ?.apply { isAccessible = true }
                    ?.get(storage)
            }
        (storedUid as? Number)?.toLong()?.takeIf { it > 0L }
    }.onFailure {
        logWebDav("online completion current user lookup failed: ${it.message ?: it.javaClass.name}")
    }.getOrNull()

internal fun WebDavDriveHook.firstFlowValue(flow: Any): Any? {
    val firstMethod = (cls(FLOW_KT_CLASS).methods.asSequence() + cls(FLOW_KT_CLASS).declaredMethods.asSequence())
        .firstOrNull { it.hostNameMatches("first") && it.parameterTypes.size == 2 }
        ?.apply { isAccessible = true }
        ?: return null
    return invokeSuspendBlocking(firstMethod, null, flow)
}

internal fun fieldValue(target: Any?, vararg names: String): Any? {
    if (target == null) return null
    for (name in names) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.hostField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            type = type.superclass
        }
    }
    return null
}

internal fun bookBackupTypeOf(book: Any?): Int {
    if (book == null) return 0
    val methodValue = book.callInt("getBackupType")
    if (methodValue != 0) return methodValue
    val field = fieldValue(book, "backupType") ?: return 0
    return (field as? Number)?.toInt() ?: field.toString().toIntOrNull() ?: 0
}

internal fun bookBackupIdOf(book: Any?): String {
    if (book == null) return ""
    val methodValue = book.callString("getBackupId")
    if (methodValue.isNotBlank()) return methodValue
    return fieldValue(book, "backupId")?.toString().orEmpty()
}

internal fun WebDavDriveHook.currentContext(): Context? =
    activityProvider() ?: currentApplicationContext()

internal fun currentApplicationContext(): Context? =
    runCatching {
        Class.forName("android.app.ActivityThread")
            .hostMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

internal fun isDescendingOrder(direction: String): Boolean =
    direction == "1" || direction.equals("DESC", ignoreCase = true) || direction.equals("desc", ignoreCase = true)

internal fun orderKind(orderBy: String): String =
    when (orderBy) {
        "size", "file_size" -> "size"
        "time", "user_utime", "updated_at", "created_at" -> "time"
        else -> "name"
    }

internal fun <T> sortStorageEntries(
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

internal fun skeletonForMatch(value: String): String {
    val nfc = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
    val sb = StringBuilder(nfc.length)
    for (ch in nfc) {
        if (ch.isLetterOrDigit()) sb.append(ch)
    }
    return sb.toString()
}

internal fun normalizeServerUrl(input: String): String {
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

internal fun WebDavDriveHook.pushOnlineCompletionCloudTitle(title: String = ONLINE_COMPLETION_TITLE) {
    onlineCompletionCloudTitleDepth.set((onlineCompletionCloudTitleDepth.get() ?: 0) + 1)
    onlineCompletionCloudTitleText.set(title.ifBlank { ONLINE_COMPLETION_TITLE })
}

internal fun WebDavDriveHook.popOnlineCompletionCloudTitle() {
    val next = (onlineCompletionCloudTitleDepth.get() ?: 0) - 1
    if (next <= 0) {
        onlineCompletionCloudTitleDepth.remove()
        onlineCompletionCloudTitleText.remove()
    } else {
        onlineCompletionCloudTitleDepth.set(next)
    }
}

internal fun WebDavDriveHook.getNativeAndroidOsVector(): Any? =
    runCatching { method(ANDROID_OS_ICON_CLASS, ANDROID_OS_ICON_METHOD, 0).invoke(null) }
        .recoverCatching {
            method(ANDROID_OS_ICON_CLASS, ANDROID_OS_ICON_METHOD, 1).invoke(
                null,
                cls("app.zhendong.reamicro.arch.icons.AppIcons\$Colored")
                    .hostField("INSTANCE")
                    .apply { isAccessible = true }
                    .get(null),
            )
        }
        .getOrNull()

internal fun WebDavDriveHook.iconVectorMethod(className: String, methodName: String): Method =
    runCatching { method(className, methodName, 0) }.getOrElse { method(className, methodName, 1) }

internal fun WebDavDriveHook.addVectorPath(
    builderClass: Class<*>,
    builder: Any,
    pathData: String,
    argb: Long,
    alpha: Float = 1f,
) {
    val vectorKt = cls(VECTOR_KT_CLASS)
    val nodes = vectorKt.hostMethod("addPathNodes", String::class.java)
        .apply { isAccessible = true }
        .invoke(null, pathData)
    val fillType = vectorKt.hostMethod("getDefaultFillType")
        .apply { isAccessible = true }
        .invoke(null) as Int
    val strokeCap = vectorKt.hostMethod("getDefaultStrokeLineCap")
        .apply { isAccessible = true }
        .invoke(null) as Int
    val strokeJoin = vectorKt.hostMethod("getDefaultStrokeLineJoin")
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

internal fun formatCloudUpdatedTime(updatedAt: Long): String =
    runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(java.util.Date(updatedAt))
    }.getOrDefault("")

internal fun cloudBookExtensionLabel(extension: String): String =
    if (extension.equals("epub", ignoreCase = true)) "ePub" else extension.uppercase(Locale.ROOT)

internal fun WebDavDriveHook.method(className: String, methodName: String, parameterCount: Int): Method {
    val cacheKey = "$className#$methodName/$parameterCount"
    return synchronized(methodCache) {
        methodCache.getOrPut(cacheKey) {
            val logicalClass = com.reamicro.fix.core.HostSymbols.logicalClass(className)
            val owner = com.reamicro.fix.core.HostSymbols.resolveMethodOwner(logicalClass, methodName)
            val actualName = com.reamicro.fix.core.HostSymbols.resolveMethodName(logicalClass, methodName)
            val candidates = cls(owner).methods.asSequence() + cls(owner).declaredMethods.asSequence()
            candidates.firstOrNull {
                (it.name == actualName || it.hostNameMatches(methodName)) &&
                    it.parameterTypes.size == parameterCount
            }?.apply { isAccessible = true }
                ?: error("$className.$methodName/$parameterCount not found")
        }
    }
}

internal fun WebDavDriveHook.staticObject(className: String, fieldName: String): Any {
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

internal fun WebDavDriveHook.staticInt(className: String, fieldName: String): Int =
    cls(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

internal fun invokeFunction1(function: Any, arg: Any?) {
    val invoke = function.javaClass.methods.firstOrNull {
        it.hostNameMatches("invoke") && it.parameterTypes.size == 1
    } ?: function.javaClass.declaredMethods.first {
        it.hostNameMatches("invoke") && it.parameterTypes.size == 1
    }
    invoke.isAccessible = true
    invoke.invoke(function, arg)
}

internal fun invokeFunction2(function: Any, first: Any?, second: Any?) {
    val invoke = function.javaClass.methods.firstOrNull {
        it.hostNameMatches("invoke") && it.parameterTypes.size == 2
    } ?: function.javaClass.declaredMethods.first {
        it.hostNameMatches("invoke") && it.parameterTypes.size == 2
    }
    invoke.isAccessible = true
    invoke.invoke(function, first, second)
}

internal fun callStringPath(root: Any?, vararg methodNames: String): String {
    var current = root ?: return ""
    methodNames.forEach { name ->
        current = current.javaClass.methods.firstOrNull { it.hostNameMatches("get${name.replaceFirstChar(Char::titlecase)}") && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(current)
            ?: return ""
    }
    return current.toString()
}

internal fun WebDavDriveHook.newKotlinPair(first: Any?, second: Any?): Any =
    cls(KOTLIN_PAIR_CLASS).getDeclaredConstructor(Any::class.java, Any::class.java)
        .apply { isAccessible = true }
        .newInstance(first, second)

internal fun WebDavDriveHook.platformFile(file: File): Any {
    if (com.reamicro.fix.core.HostNativeAbi.fileWrapperField(com.reamicro.fix.core.HostSymbols.profileId) != null) {
        val wrapper = cls("ReaMicroHost.FileWrapper").getDeclaredConstructor(File::class.java).newInstance(file)
        return cls("ReaMicroHost.PlatformFile").declaredConstructors.single { it.parameterCount == 1 }.newInstance(wrapper)
    }
    return cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.first {
        it.hostNameMatches(PLATFORM_FILE_METHOD) && it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java
    }.apply { isAccessible = true }.invoke(null, file)
}

internal fun WebDavDriveHook.platformFileKeys(platformFile: Any): List<String> =
    listOfNotNull(
        platformFileString(platformFile, PLATFORM_FILE_GET_PATH_METHOD),
        platformFileString(platformFile, PLATFORM_FILE_ABSOLUTE_PATH_METHOD),
    ).filter { it.isNotBlank() }.distinct()

internal fun WebDavDriveHook.platformFileString(platformFile: Any, methodName: String): String? =
    runCatching {
        val fileField = com.reamicro.fix.core.HostNativeAbi.fileWrapperField(com.reamicro.fix.core.HostSymbols.profileId)
        if (fileField != null) {
            val wrapper = cls("ReaMicroHost.PlatformFile").getDeclaredField("a").get(platformFile)
            if (cls("ReaMicroHost.FileWrapper").isInstance(wrapper)) {
                val file = cls("ReaMicroHost.FileWrapper").getDeclaredField(fileField).get(wrapper) as File
                return@runCatching if (methodName == PLATFORM_FILE_GET_PATH_METHOD) file.path else file.absolutePath
            }
            return@runCatching null
        }
        cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.firstOrNull {
            it.hostNameMatches(methodName) && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(null, platformFile)?.toString()
    }.getOrNull()

internal fun WebDavDriveHook.invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? =
        com.reamicro.fix.core.HostCoroutines.invoke(classLoader, method, target, *args)

internal fun WebDavDriveHook.emptyCoroutineContext(): Any =
    cls(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS).hostField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)

internal fun WebDavDriveHook.coroutineSuspended(): Any =
    runCatching {
        cls(KOTLIN_INTRINSICS_CLASS).methods.first {
            it.hostNameMatches("getCOROUTINE_SUSPENDED") && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(null)
    }.getOrElse {
        (cls(KOTLIN_COROUTINE_SINGLETONS_CLASS).enumConstants ?: emptyArray()).first { value ->
            value.toString() == "COROUTINE_SUSPENDED"
        }
    }

internal fun WebDavDriveHook.newWorkHandle(id: String, state: Any): Any =
    newNativeWorkHandle(id, createMutableStateFlow(state))

internal fun WebDavDriveHook.newWorkState(statusName: String, progress: Int, error: String?, result: String?, name: String?): Any {
    val status = cls(WORK_STATUS_CLASS).hostMethod("valueOf", String::class.java)
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

internal fun WebDavDriveHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)

internal fun WebDavDriveHook.cls(className: String): Class<*> =
    XposedHelpers.findClass(className, classLoader)

internal fun WebDavDriveHook.targetUnit(): Any? = runCatching {
    val clazz = cls("kotlin.Unit")
    val field = clazz.hostField("INSTANCE")
    field.isAccessible = true
    field.get(null)
}.getOrNull()
