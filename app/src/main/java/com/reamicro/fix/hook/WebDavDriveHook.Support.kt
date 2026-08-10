package com.reamicro.fix.hook

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
import com.reamicro.fix.webdav.isPermanentOnlineChapterFailure
import com.reamicro.fix.webdav.onlineHttpRetryDelayMs
import com.reamicro.fix.webdav.onlineHttpRetryKind
import com.reamicro.fix.webdav.OnlineHttpRetryKind
import com.reamicro.fix.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.webdav.OnlineDownloadedChapter
import com.reamicro.fix.webdav.OnlineOnDemandBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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

// WebDavDriveHook 的宿主对象构造与杂项支撑簇。
//
// 伪造宿主需要的 PagingData/Flow/Result/CloudBook 等对象、反射取宿主仓库与
// ViewModel、书籍备份打包、以及各类小工具。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.install() {
    logWebDav("install start")
    OnlineOnDemandBridge.attach(::downloadOnlineCompletionOnDemandChapter)
    // 逐个登记安装结果：宿主升级导致某个 hook 装不上时，靠启动汇总即可定位，
    // 且单个 hook 失败不再中断后续 hook 的安装。
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
            "webDavFileFolderIcon" to ::hookWebDavFileFolderIcon,
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
            "cloudStorageWebDavScreenScope" to ::hookCloudStorageWebDavScreenScope,
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
        ),
    )
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

internal fun WebDavDriveHook.isBookLocalSheetBookContextMethod(method: Method): Boolean {
    if (method.name == BOOK_LOCAL_SHEET_METHOD && method.parameterTypes.size == 5) return true
    // A14 moved the sheet body into a generated lambda; hook any BookLocalSheet lambda that carries Book + Composer.
    return method.name.startsWith("BookLocalSheet\$lambda\$") &&
        method.parameterTypes.firstOrNull()?.name == BOOK_CLASS &&
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

internal fun WebDavDriveHook.decodeOnlineDataImage(url: String): Bitmap? {
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

internal fun WebDavDriveHook.cancelOnlineCompletionTrackedWork(task: OnlineCompletionDownloadTask) {
    val tracker = task.tracker ?: return
    val workId = task.workId ?: return
    cancelTrackedWork(tracker, workId)
}

internal fun WebDavDriveHook.cancelTrackedWork(tracker: Any, id: String) {
    runCatching {
        tracker.javaClass.methods.first {
            it.name == "cancelWorkById" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(tracker, id)
    }.onFailure {
        setTrackedWorkState(tracker, id, "Cancelled", 100, null, null, null)
    }
}

internal fun WebDavDriveHook.isChildPath(child: File, parent: File): Boolean {
    val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
    return child.path.startsWith(parentPath)
}

internal fun WebDavDriveHook.workStateStatusName(state: Any): String =
    state.invokeNoArg("getStatus")?.toString().orEmpty()

internal fun WebDavDriveHook.cloudTypeTitle(type: Int): String =
    when (type) {
        BACKUP_TYPE_BAIDU -> "\u767e\u5ea6\u7f51\u76d8"
        BACKUP_TYPE_YUN115 -> "115"
        BACKUP_TYPE_ALIYUN -> "\u963f\u91cc\u4e91\u76d8"
        else -> "\u4e91\u76d8"
    }

internal fun WebDavDriveHook.findLocalBookById(bookId: Long): Any? {
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

internal fun WebDavDriveHook.findLocalBookByUuid(uid: Long, uuid: String): Any? {
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

internal fun WebDavDriveHook.updateLocalBook(book: Any) {
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

internal fun WebDavDriveHook.copyBookWithBackup(book: Any, backupType: Int, backupId: String, backupCode: String): Any {
    return copyBookWithBackupAndPublisher(
        book = book,
        backupType = backupType,
        backupId = backupId,
        backupCode = backupCode,
        publisher = book.callString("getPublisher"),
    )
}

internal fun WebDavDriveHook.copyBookWithBackupAndPublisher(
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
    val copyMethod = book.javaClass.methods
        .filter { it.name == "copy" && it.parameterTypes.size in 23..25 }
        .maxByOrNull { it.parameterTypes.size }
        ?: error("Unsupported Book.copy signature: ${book.javaClass.name}")
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
    if (copyMethod.parameterTypes.getOrNull(args.size) == Integer.TYPE) {
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
        ),
    )
    if (copyMethod.parameterTypes.size - args.size == 6) {
        args.add(pinnedAt ?: book.callLong("getPinnedAt"))
    }
    args.add(cloudId ?: book.callLong("getCloudId"))
    args.addAll(listOf(backupType, backupId, backupCode, publisher))
    check(args.size == copyMethod.parameterTypes.size) {
        "Book.copy argument mismatch: expected=${copyMethod.parameterTypes.size} actual=${args.size}"
    }
    return copyMethod.invoke(book, *args.toTypedArray())
}

internal fun WebDavDriveHook.copyCloudBookWithType(book: Any, type: Int): Any {
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

internal fun WebDavDriveHook.addStoredFile(zip: ZipOutputStream, file: File, name: String) {
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

internal fun WebDavDriveHook.addDeflatedFile(zip: ZipOutputStream, file: File, name: String) {
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

internal fun WebDavDriveHook.addDeflatedBytes(zip: ZipOutputStream, bytes: ByteArray, name: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(bytes)
    zip.closeEntry()
}

internal fun WebDavDriveHook.patchedOpfBytes(file: File): ByteArray? {
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

internal fun WebDavDriveHook.exportFileName(book: Any): String =
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

internal fun WebDavDriveHook.isRouteDestination(backStackEntry: Any, routeClassName: String): Boolean {
    val destination = runCatching {
        backStackEntry.javaClass.methods.firstOrNull {
            it.name == "getDestination" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(backStackEntry)
    }.getOrNull() ?: return false
    val route = runCatching {
        destination.javaClass.methods.firstOrNull {
            it.name == "getRoute" && it.parameterTypes.isEmpty()
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
    val kClass = reflectionClass.getDeclaredMethod("getOrCreateKotlinClass", Class::class.java)
        .apply { isAccessible = true }
        .invoke(null, routeClass)
    return cls(NAV_BACK_STACK_ENTRY_KT_CLASS).declaredMethods.first {
        it.name == NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD && it.parameterTypes.size == 2
    }.apply { isAccessible = true }
        .invoke(null, backStackEntry, kClass)
}

internal fun WebDavDriveHook.navBackStackEntryToThirdAccount(backStackEntry: Any): Any {
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

internal fun WebDavDriveHook.isBaiduTitle(title: String): Boolean =
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
    cls(ROUTE_HOME_CLASS).getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)

internal fun WebDavDriveHook.newCloudFolderRoute(path: String? = null, bookId: Long? = null, type: Int = BACKUP_TYPE_WEBDAV): Any =
    cls(ROUTE_CLOUD_FOLDER_CLASS).getDeclaredConstructor(
        java.lang.Integer.TYPE,
        String::class.java,
        java.lang.Long::class.java,
    ).apply { isAccessible = true }.newInstance(
        type,
        path,
        bookId,
    )

internal fun WebDavDriveHook.navigateStorage(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    navGraphScope.javaClass.methods.firstOrNull {
        it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
    }?.apply { isAccessible = true }?.invoke(navGraphScope, newStorageRoute(type), null, null)
}

internal fun WebDavDriveHook.navigateCloudFolder(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    navGraphScope.javaClass.methods.firstOrNull {
        it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
    }?.apply { isAccessible = true }?.invoke(navGraphScope, newCloudFolderRoute(type = type), null, null)
}

internal fun WebDavDriveHook.navigateHome(navGraphScope: Any): Boolean =
    runCatching {
        navGraphScope.javaClass.methods.first {
            it.name == NAVIGATE_METHOD && it.parameterTypes.size == 3
        }.apply { isAccessible = true }.invoke(navGraphScope, newHomeRoute(), null, null)
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to navigate WebDAV logout home: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun WebDavDriveHook.popBackStack(navGraphScope: Any) {
    navGraphScope.javaClass.methods.firstOrNull {
        it.name == "popBackStack" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(navGraphScope)
}

internal fun WebDavDriveHook.flowOf(value: Any): Any {
    val method = cls(FLOW_KT_CLASS).methods.first {
        it.name == FLOW_OF_METHOD && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
    }
    return method.invoke(null, arrayOf(value))
}

internal fun WebDavDriveHook.emptyBaiduAuth(): Any =
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

internal fun WebDavDriveHook.emptyCloudUserInfo(): Any =
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

internal fun WebDavDriveHook.emptyPagingData(): Any {
    val pagingDataClass = cls(PAGING_DATA_CLASS)
    val companion = pagingDataClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
    return companion.javaClass.methods.first {
        it.name == PAGING_DATA_EMPTY_METHOD && it.parameterTypes.isEmpty()
    }.apply { isAccessible = true }.invoke(companion)
}

internal fun WebDavDriveHook.pagingDataFrom(items: List<Any>): Any {
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

internal fun WebDavDriveHook.idleLoadStates(): Any {
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

internal fun WebDavDriveHook.emptyFlow(): Any =
    cls(FLOW_KT_CLASS).methods.first {
        it.name == "emptyFlow" && it.parameterTypes.isEmpty()
    }.apply { isAccessible = true }.invoke(null)

internal fun WebDavDriveHook.kotlinResultSuccess(value: Any?): Any =
    cls(KOTLIN_RESULT_CLASS).declaredMethods.first {
        it.name.contains("constructor") && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, value)

internal fun WebDavDriveHook.kotlinResultFailure(throwable: Throwable): Any {
    val failure = cls(KOTLIN_RESULT_KT_CLASS).declaredMethods.first {
        it.name == "createFailure" && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, throwable)
    return kotlinResultSuccess(failure)
}

internal fun WebDavDriveHook.createMutableStateFlow(value: Any): Any =
    cls(STATE_FLOW_KT_CLASS).methods.first {
        it.name == "MutableStateFlow" && it.parameterTypes.size == 1
    }.apply { isAccessible = true }.invoke(null, value)

internal fun WebDavDriveHook.setMutableStateFlowValue(flow: Any?, value: Any) {
    flow?.javaClass?.methods?.firstOrNull {
        it.name == "setValue" && it.parameterTypes.size == 1
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

internal fun WebDavDriveHook.enabledOnlineCompletionSources(context: Context): List<OnlineSourceEntry> {
    val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    return OnlineSourceStore.list(context)
        .filter { source -> prefs.getBoolean(ModuleSettings.onlineSourceKey(source.id), false) }
}

internal fun WebDavDriveHook.logOnlineMetadataGaps(
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

internal fun WebDavDriveHook.onlineCompletionPlatformName(node: Any?, kind: String): String {
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

internal fun WebDavDriveHook.copyHomeUiStateWithCloudResults(state: Any, cloudResults: Map<Any?, Any?>): Any {
    val currentCloudResults = homeCloudSearchResults(state)
    val stateClass = state.javaClass
    val copyMethods = stateClass.methods
        .filter { it.name == "copy" && it.returnType == stateClass && it.parameterTypes.isNotEmpty() }
        .sortedByDescending { it.parameterTypes.size }
    val expectedKeys = cloudResults.keys
    val failures = mutableListOf<String>()
    copyMethods.forEach { copyMethod ->
        runCatching {
            copyMethod.isAccessible = true
            val paramTypes = copyMethod.parameterTypes
            // 逐个取 componentN，失败保留 null（不会中断整体，避免错位）
            val baseArgs = Array<Any?>(paramTypes.size) { index ->
                runCatching { state.invokeNoArg("component${index + 1}") }.getOrNull()
            }
            // 收集所有“可接受 Map”的候选参数位。引用相等/单例空 Map 会误判，
            // 因此改为逐个候选注入后用 getCloudSearchResults() 验证输出是否真正生效。
            val candidateIndices = paramTypes.indices.filter { i ->
                Map::class.java.isAssignableFrom(paramTypes[i])
            }
            if (candidateIndices.isEmpty()) {
                throw IllegalStateException(
                    "no Map parameter for ${paramTypes.size}-arg HomeUiState.copy",
                )
            }
            // 候选排序：当前值 === cloudSearchResults 的优先（多为真实位），
            // 其余按声明顺序，最后逐个尝试并验证输出。
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
                    // 验证：注入后的 cloudSearchResults 必须真正包含我们写入的键
                    val verified = resultMap != null && expectedKeys.all { resultMap.containsKey(it) }
                    if (verified) copied else null
                }.getOrNull()
                if (attemptResult != null) return attemptResult
            }
            // 全部候选都未通过验证：若期望为空（清空场景），接受最后一次可用结果
            if (expectedKeys.isEmpty() && lastCopied != null) return lastCopied!!
            failures += "${paramTypes.size}: no verified cloud index in ${orderedCandidates.joinToString(",")}"
        }.onFailure {
            failures += "${copyMethod.parameterTypes.size}: ${it.javaClass.name}: ${it.message}"
        }
    }
    XposedBridge.log("$LOG_PREFIX HomeUiState dynamic copy failed: ${failures.joinToString(" | ")}")
    return copyHomeUiStateWithCloudResultsLegacy(state, cloudResults)
}

internal fun WebDavDriveHook.copyHomeUiStateWithCloudResultsLegacy(state: Any, cloudResults: Map<Any?, Any?>): Any {
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

internal fun WebDavDriveHook.newCloudFolder(entry: WebDavEntry): Any =
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

internal fun WebDavDriveHook.onlineCompletionGroupTitle(group: OnlineSearchGroup): String =
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
    val bookClass = cls(BOOK_CLASS)
    // 2.2.0 起 Book 主构造为 25 参（旧版 23/24），且存在带 Serialization/DefaultConstructorMarker
    // 的重载。此处不再写死参数个数，改为取“首参为 long 的最长构造器”（即真实全参构造，
    // 规避首参为 int 位掩码的序列化构造），按类型填默认值后仅设置关键字段（按 Book 字段声明顺序），
    // 抗后续字段增减。字段顺序：id,uuid,uid,title,subtitle,author,cover,size,uri,group,created,
    // cfiVersion,embeddedFonts,epubcfi,chapter,progress,total,finished,updated,pinnedAt,cloudId,
    // backupType,backupId,backupCode,publisher
    val constructor = bookClass.declaredConstructors
        .filter { it.parameterTypes.size >= 23 && it.parameterTypes[0] == java.lang.Long.TYPE }
        .maxByOrNull { it.parameterTypes.size }
        ?.apply { isAccessible = true }
        ?: error("Book constructor not found")
    val types = constructor.parameterTypes
    val args = arrayOfNulls<Any?>(types.size)
    for (i in types.indices) {
        args[i] = when (types[i]) {
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Character.TYPE -> ' '
            String::class.java -> ""
            else -> null
        }
    }
    fun set(index: Int, value: Any?) {
        if (index in args.indices) args[index] = value
    }
    set(0, 0L) // id
    set(1, UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString()) // uuid
    set(3, result.name) // title
    set(5, result.author) // author
    set(6, result.coverUrl) // cover
    set(8, result.detailUrl.ifBlank { path }) // uri
    set(10, now) // created
    set(21, BACKUP_TYPE_ONLINE_COMPLETION) // backupType
    set(22, path) // backupId
    set(24, result.sourceName) // publisher
    return constructor.newInstance(*args)
}

internal fun WebDavDriveHook.emptyCloudBook(): Any =
    cls(CLOUD_BOOK_CLASS).getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)
        .let { companion ->
            companion.javaClass.methods.first { it.name == "getEmpty" && it.parameterTypes.isEmpty() }
                .apply { isAccessible = true }
                .invoke(companion)
        }

internal fun WebDavDriveHook.isSupportedBookFile(name: String): Boolean =
    BOOK_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

internal fun WebDavDriveHook.isOnlineCompletionPath(path: String): Boolean =
    path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) || path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)

internal fun WebDavDriveHook.isOnlineCompletionLocalBook(book: Any): Boolean =
    isOnlineCompletionPath(bookBackupIdOf(book)) ||
        isOnlineCompletionPath(book.callString("getUri")) ||
        isOnlineCompletionUuid(book.callString("getUuid")) ||
        (bookBackupTypeOf(book) == BACKUP_TYPE_ONLINE_COMPLETION &&
            book.callString("getBackupCode").startsWith("online_"))

internal fun WebDavDriveHook.isOnlineCompletionUuid(uuid: String): Boolean =
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
        managerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    }.getOrNull() ?: error("阅微 EpubFileManager INSTANCE 未找到")
    val bookPath = okioPath(bookDir.canonicalFile)
    val opf = obtainOnlineCompletionOpf(bookPath)
    val managerMethods = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
        .distinct()
        .toList()
    val itemRefMethod = managerMethods.firstOrNull {
        it.name == "getItemRefs" &&
            it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == java.lang.Long.TYPE &&
            it.parameterTypes[1].name == OPF_CLASS &&
            it.parameterTypes[2].name == OKIO_PATH_CLASS
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
    // 阅微 2.3.0 为目录解析新增了可空 Function2<Int, Int, Unit> 进度回调；
    // 同时兼容旧版三参数签名。宿主默认参数实现同样向第四参传 null。
    val chapterMethod = managerMethods
        .filter {
            it.name == "getChapters" &&
                (it.parameterTypes.size == 3 || it.parameterTypes.size == 4) &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1].name == OPF_CLASS &&
                it.parameterTypes[2].name == OKIO_PATH_CLASS &&
                (it.parameterTypes.size == 3 || it.parameterTypes[3].name == FUNCTION2_CLASS)
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
        it.name == "deleteByBookId" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == java.lang.Long.TYPE &&
            it.parameterTypes[1].name == KOTLIN_CONTINUATION_CLASS
    }?.apply { isAccessible = true }
        ?: error("阅微 $label DAO.deleteByBookId 方法未找到；候选=${methodCandidates(daoMethods, "deleteByBookId")}")
    val upsertMethod = daoMethods.firstOrNull {
        it.name == "upsert" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0].isArray &&
            it.parameterTypes[1].name == KOTLIN_CONTINUATION_CLASS
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

internal fun WebDavDriveHook.methodCandidates(methods: List<Method>, name: String): String =
    methods.filter { it.name == name }
        .joinToString(prefix = "[", postfix = "]") { method ->
            method.parameterTypes.joinToString(
                prefix = "${method.name}(",
                postfix = "):${method.returnType.name}",
            ) { type -> type.name }
        }

internal fun WebDavDriveHook.onlineSourceIdFromEncodedValue(value: String): String {
    val text = value.trim()
    if (text.isBlank()) return ""
    if (text.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) {
        return runCatching { Uri.parse(text).host.orEmpty() }.getOrDefault("")
    }
    return text.takeUnless { it.contains("://") }.orEmpty()
}

internal fun WebDavDriveHook.onlineCompletionQueryParameter(value: String, name: String): String =
    runCatching {
        if (!value.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) return@runCatching ""
        Uri.parse(value).getQueryParameter(name).orEmpty()
    }.getOrDefault("")

internal fun WebDavDriveHook.isPathLikeOnlineSourceName(value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    if (text.contains("://")) return true
    if (text.startsWith("/") || text.startsWith("\\") || text.startsWith("／")) return true
    if (Regex("""^[A-Za-z]:[\\/].*""").matches(text)) return true
    return text.endsWith(".epub", ignoreCase = true) ||
        text.endsWith(".txt", ignoreCase = true) ||
        text.endsWith(".pdf", ignoreCase = true)
}

internal fun WebDavDriveHook.onlineCompletionDisplayProgress(progress: Int, message: String): Int {
    val match = ONLINE_COMPLETION_PROGRESS_CHAPTER_REGEX.find(message) ?: return progress.coerceIn(0, 100)
    val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
    val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return progress.coerceIn(0, 100)
    if (total <= 0) return progress.coerceIn(0, 100)
    return ((current.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
}

internal fun WebDavDriveHook.onlineCompletionFailedCount(message: String): Int =
    ONLINE_COMPLETION_FAILED_COUNT_REGEX.find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

internal fun WebDavDriveHook.onlineCompletionFailureSuffix(failedCount: Int): String =
    if (failedCount > 0) " · 失败 $failedCount 章" else ""

internal fun WebDavDriveHook.logOnlineCompletionFinalFailures(
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

internal fun WebDavDriveHook.extractOnlineBacktickTemplates(rule: String): List<String> {
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

internal fun WebDavDriveHook.onlineReadTimeoutMillis(source: OnlineSourceEntry): Int =
    source.respondTime.coerceIn(15_000, 300_000)

internal fun WebDavDriveHook.onlineConnectTimeoutMillis(source: OnlineSourceEntry): Int =
    onlineReadTimeoutMillis(source).coerceAtMost(30_000).coerceAtLeast(10_000)

internal fun WebDavDriveHook.onlineCompletionFailurePlaceholder(attempts: Int, message: String): String =
    if (message.contains("今日已达到章节下载限额")) {
        "本章尚未下载，当前在线源今日额度已用完。\n\n请明日点击更新继续补全。"
    } else {
        "本章下载失败，已按在线源重试 $attempts 次。\n\n$message"
    }

internal fun WebDavDriveHook.bookDirectorySize(bookDir: File): Long =
    bookDir.walkTopDown()
        .filter { it.isFile }
        .fold(0L) { total, file -> total + file.length() }

internal fun WebDavDriveHook.onlineCompletionFailedLogFile(bookDir: File): File =
    File(bookDir, "OEBPS/$ONLINE_COMPLETION_FAILED_CHAPTER_LOG")

internal fun WebDavDriveHook.findLocalBookByUrl(bookshelf: Any, url: String): Any? {
    if (url.isBlank()) return null
    val findMethod = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .firstOrNull { it.name == "findBookByUrl" && it.parameterTypes.size == 2 }
        ?.apply { isAccessible = true }
        ?: return null
    return invokeSuspendBlocking(findMethod, bookshelf, url)
}

internal fun WebDavDriveHook.currentOnlineCompletionBooksDir(bookshelf: Any): Any {
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

internal fun WebDavDriveHook.onlineCompletionCancelPendingIntent(context: Context, id: Int, key: String): PendingIntent {
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

internal fun WebDavDriveHook.sendOnlineCompletionCancelBroadcastToModule(context: Context, id: Int, key: String): Boolean =
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
            candidate.name == "listByUid" &&
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
            .firstOrNull { it.name == "getCurrentUserFlow" && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?.invoke(repository)
            ?: fieldValue(repository, "currentUserFlow")
            ?: return null
        firstFlowValue(flow)?.callLong("getId")?.takeIf { it > 0L }
    }.onFailure {
        logWebDav("online completion current user lookup failed: ${it.message ?: it.javaClass.name}")
    }.getOrNull()

internal fun WebDavDriveHook.firstFlowValue(flow: Any): Any? {
    val firstMethod = (cls(FLOW_KT_CLASS).methods.asSequence() + cls(FLOW_KT_CLASS).declaredMethods.asSequence())
        .firstOrNull { it.name == "first" && it.parameterTypes.size == 2 }
        ?.apply { isAccessible = true }
        ?: return null
    return invokeSuspendBlocking(firstMethod, null, flow)
}

internal fun WebDavDriveHook.fieldValue(target: Any?, vararg names: String): Any? {
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

internal fun WebDavDriveHook.bookBackupTypeOf(book: Any?): Int {
    if (book == null) return 0
    val methodValue = book.callInt("getBackupType")
    if (methodValue != 0) return methodValue
    val field = fieldValue(book, "backupType") ?: return 0
    return (field as? Number)?.toInt() ?: field.toString().toIntOrNull() ?: 0
}

internal fun WebDavDriveHook.bookBackupIdOf(book: Any?): String {
    if (book == null) return ""
    val methodValue = book.callString("getBackupId")
    if (methodValue.isNotBlank()) return methodValue
    return fieldValue(book, "backupId")?.toString().orEmpty()
}

internal fun WebDavDriveHook.currentContext(): Context? =
    activityProvider() ?: currentApplicationContext()

internal fun WebDavDriveHook.currentApplicationContext(): Context? =
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

internal fun WebDavDriveHook.isDescendingOrder(direction: String): Boolean =
    direction == "1" || direction.equals("DESC", ignoreCase = true) || direction.equals("desc", ignoreCase = true)

internal fun WebDavDriveHook.orderKind(orderBy: String): String =
    when (orderBy) {
        "size", "file_size" -> "size"
        "time", "user_utime", "updated_at", "created_at" -> "time"
        else -> "name"
    }

internal fun <T> WebDavDriveHook.sortStorageEntries(
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

// 生成用于模糊匹配的「骨架」：Unicode NFC 后，仅保留字母/数字/汉字（Letter/Digit），
// 丢弃所有标点、括号、空白、省略号。彻底消除请求名与服务器名之间的等价字符差异。
internal fun WebDavDriveHook.skeletonForMatch(value: String): String {
    val nfc = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
    val sb = StringBuilder(nfc.length)
    for (ch in nfc) {
        if (ch.isLetterOrDigit()) sb.append(ch)
    }
    return sb.toString()
}

internal fun WebDavDriveHook.normalizeServerUrl(input: String): String {
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
    runCatching {
        method(ANDROID_OS_ICON_CLASS, ANDROID_OS_ICON_METHOD, 1).invoke(
            null,
            cls("app.zhendong.reamicro.arch.icons.AppIcons\$Colored")
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null),
        )
    }.getOrNull()

internal fun WebDavDriveHook.addVectorPath(
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

internal fun WebDavDriveHook.formatCloudUpdatedTime(updatedAt: Long): String =
    runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(java.util.Date(updatedAt))
    }.getOrDefault("")

internal fun WebDavDriveHook.cloudBookExtensionLabel(extension: String): String =
    if (extension.equals("epub", ignoreCase = true)) "ePub" else extension.uppercase(Locale.ROOT)

internal fun WebDavDriveHook.method(className: String, methodName: String, parameterCount: Int): Method {
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

/**
 * 按签名解析首页搜索结果行的点击回调。
 * 2.2.0 起该 lambda 名从 SearchResult$lambda$0$0$1$0$0$0 变化，段数随混淆浮动，
 * 因此不再写死方法名，改为匹配 HomeSearchBarKt 内签名为 (IntentReceiver, CloudBook) 且
 * 名称以 SearchResult 开头的静态方法（唯一真实目标，另一个同签名为 $r8$lambda 合成桥接）。
 */

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

internal fun WebDavDriveHook.invokeFunction1(function: Any, arg: Any?) {
    val invoke = function.javaClass.methods.firstOrNull {
        it.name == "invoke" && it.parameterTypes.size == 1
    } ?: function.javaClass.declaredMethods.first {
        it.name == "invoke" && it.parameterTypes.size == 1
    }
    invoke.isAccessible = true
    invoke.invoke(function, arg)
}

internal fun WebDavDriveHook.invokeFunction2(function: Any, first: Any?, second: Any?) {
    val invoke = function.javaClass.methods.firstOrNull {
        it.name == "invoke" && it.parameterTypes.size == 2
    } ?: function.javaClass.declaredMethods.first {
        it.name == "invoke" && it.parameterTypes.size == 2
    }
    invoke.isAccessible = true
    invoke.invoke(function, first, second)
}

internal fun WebDavDriveHook.callStringPath(root: Any?, vararg methodNames: String): String {
    var current = root ?: return ""
    methodNames.forEach { name ->
        current = current.javaClass.methods.firstOrNull { it.name == "get${name.replaceFirstChar(Char::titlecase)}" && it.parameterTypes.isEmpty() }
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

internal fun WebDavDriveHook.platformFile(file: File): Any =
    cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.first {
        it.name == PLATFORM_FILE_METHOD && it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java
    }.apply { isAccessible = true }.invoke(null, file)

internal fun WebDavDriveHook.platformFileKeys(platformFile: Any): List<String> =
    listOfNotNull(
        platformFileString(platformFile, PLATFORM_FILE_GET_PATH_METHOD),
        platformFileString(platformFile, PLATFORM_FILE_ABSOLUTE_PATH_METHOD),
    ).filter { it.isNotBlank() }.distinct()

internal fun WebDavDriveHook.platformFileString(platformFile: Any, methodName: String): String? =
    runCatching {
        cls(PLATFORM_FILE_ANDROID_KT_CLASS).declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(null, platformFile)?.toString()
    }.getOrNull()

internal fun WebDavDriveHook.invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
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

internal fun WebDavDriveHook.emptyCoroutineContext(): Any =
    cls(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS).getDeclaredField("INSTANCE")
        .apply { isAccessible = true }
        .get(null)

internal fun WebDavDriveHook.coroutineSuspended(): Any =
    runCatching {
        cls(KOTLIN_INTRINSICS_CLASS).methods.first {
            it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(null)
    }.getOrElse {
        (cls(KOTLIN_COROUTINE_SINGLETONS_CLASS).enumConstants ?: emptyArray()).first { value ->
            value.toString() == "COROUTINE_SUSPENDED"
        }
    }

internal fun WebDavDriveHook.newWorkHandle(id: String, state: Any): Any =
    cls(WORK_HANDLE_CLASS).getDeclaredConstructor(String::class.java, cls(STATE_FLOW_CLASS))
        .apply { isAccessible = true }
        .newInstance(id, createMutableStateFlow(state))

internal fun WebDavDriveHook.newWorkState(statusName: String, progress: Int, error: String?, result: String?, name: String?): Any {
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

internal fun WebDavDriveHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)

internal fun WebDavDriveHook.cls(className: String): Class<*> =
    XposedHelpers.findClass(className, classLoader)

internal fun WebDavDriveHook.targetUnit(): Any? = runCatching {
    val clazz = cls("kotlin.Unit")
    val field = clazz.getDeclaredField("INSTANCE")
    field.isAccessible = true
    field.get(null)
}.getOrNull()
