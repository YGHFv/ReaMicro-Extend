package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalExportHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private val methodCache = mutableMapOf<String, Method>()
    private val currentBackupContext = ThreadLocal<BackupContext?>()
    private val renderingLocalExport = ThreadLocal<Boolean>()
    @Volatile private var pendingPickContext: BackupContext? = null

    fun install() {
        hookBookBackupContent()
        hookLocalStorageCard()
        hookActivityResult()
    }

    private fun hookBookBackupContent() {
        runCatching {
            val method = cls(BOOK_BACKUP_SCREEN_CLASS).declaredMethods.first {
                it.name == BOOK_BACKUP_CONTENT_METHOD && it.parameterTypes.size == 9
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    val context = BackupContext(book)
                    if (renderScrollableBackupContent(param, context)) {
                        param.result = targetUnit()
                        return
                    }
                    currentBackupContext.set(context)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    currentBackupContext.remove()
                }
            })
            XposedBridge.log("$LOG_PREFIX local export backup content hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook backup content: ${it.stackTraceToString()}")
        }
    }

    private fun hookLocalStorageCard() {
        runCatching {
            val method = cls(LOCAL_STORAGE_CARD_CLASS).declaredMethods.first {
                it.name == LOCAL_STORAGE_CARD_METHOD && it.parameterTypes.size == 3
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = currentBackupContext.get() ?: return
                    val composer = param.args?.getOrNull(1) ?: return
                    renderLocalExportCard(context, composer)
                }
            })
            XposedBridge.log("$LOG_PREFIX local export card hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook local storage card: ${it.stackTraceToString()}")
        }
    }

    private fun hookActivityResult() {
        runCatching {
            val mainActivityClass = cls(MAIN_ACTIVITY_CLASS)
            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args?.getOrNull(0) as? Int ?: return
                        if (requestCode != REQUEST_PICK_EXPORT_DIR) return
                        val resultCode = param.args?.getOrNull(1) as? Int ?: return
                        val data = param.args?.getOrNull(2) as? Intent
                        val activity = param.thisObject as? Activity ?: activityProvider()
                        val context = pendingPickContext.also { pendingPickContext = null } ?: return
                        if (resultCode != Activity.RESULT_OK || data?.data == null) {
                            activity?.toast("已取消选择目录")
                            return
                        }
                        val uri = data.data ?: return
                        val flags = data.flags and
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        runCatching { activity?.contentResolver?.takePersistableUriPermission(uri, flags) }
                        exportToPickedDirectory(context, uri)
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX local export activity result hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook activity result for local export: ${it.stackTraceToString()}")
        }
    }

    private fun renderScrollableBackupContent(param: XC_MethodHook.MethodHookParam, context: BackupContext): Boolean {
        val args = param.args ?: return false
        val cloudBook = args.getOrNull(1)
        val deleting = args.getOrNull(2) as? Boolean ?: false
        val onIntent = args.getOrNull(3) ?: return false
        val onSelectFolder = args.getOrNull(4) ?: return false
        val onAuthorize = args.getOrNull(5) ?: return false
        val onSetupDefaultDir = args.getOrNull(6) ?: return false
        val composer = args.getOrNull(7) ?: return false
        return runCatching {
            renderScrollableColumn(composer) { innerComposer ->
                renderLocalStorageCard(context.book, innerComposer)
                renderLocalExportCard(context, innerComposer)
                renderBackupTargetCards(
                    book = context.book,
                    cloudBook = cloudBook,
                    deleting = deleting,
                    onIntent = onIntent,
                    onSelectFolder = onSelectFolder,
                    onAuthorize = onAuthorize,
                    onSetupDefaultDir = onSetupDefaultDir,
                    composer = innerComposer,
                )
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render scrollable backup content: ${it.stackTraceToString()}")
        }.isSuccess
    }

    private fun renderScrollableColumn(composer: Any, content: (Any) -> Unit) {
        val contentProxy = functionProxy("ScrollableBackupContent", FUNCTION3_CLASS) { args ->
            content(args?.getOrNull(1) ?: composer)
            targetUnit()
        }
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            scrollableBackupContentModifier(composer),
            arrangementTop(),
            alignmentStart(),
            contentProxy,
            composer,
            0,
            0,
        )
    }

    private fun renderLocalStorageCard(book: Any, composer: Any) {
        method(LOCAL_STORAGE_CARD_CLASS, LOCAL_STORAGE_CARD_METHOD, 3).invoke(
            null,
            book,
            composer,
            0,
        )
    }

    private fun renderBackupTargetCards(
        book: Any,
        cloudBook: Any?,
        deleting: Boolean,
        onIntent: Any,
        onSelectFolder: Any,
        onAuthorize: Any,
        onSetupDefaultDir: Any,
        composer: Any,
    ) {
        val type = cloudBook?.callInt("getType") ?: 0
        if (cloudBook != null && type in setOf(BACKUP_TYPE_BAIDU, BACKUP_TYPE_YUN115, BACKUP_TYPE_ALIYUN, BACKUP_TYPE_WEBDAV)) {
            val onDelete = functionProxy("BackupDelete", FUNCTION1_CLASS) {
                invokeFunction1(onIntent, backupDeleteEvent())
                targetUnit()
            }
            val methodName = when (type) {
                BACKUP_TYPE_BAIDU -> BAIDU_NET_DISK_CARD_METHOD
                BACKUP_TYPE_YUN115, BACKUP_TYPE_WEBDAV -> YUN115_NET_DISK_CARD_METHOD
                else -> ALIYUN_DRIVE_CARD_METHOD
            }
            method(DRIVE_CARD_CLASS, methodName, 5).invoke(
                null,
                cloudBook,
                deleting,
                onDelete,
                composer,
                0,
            )
            return
        }

        val uploadBlock = functionProxy("BackupUploadBlock", FUNCTION2_CLASS) { callbackArgs ->
            val uploadType = (callbackArgs?.getOrNull(0) as? Number)?.toInt() ?: return@functionProxy targetUnit()
            val dir = callbackArgs.getOrNull(1)?.toString()
            if (dir.isNullOrBlank()) {
                invokeFunction1(onSelectFolder, uploadType)
            } else {
                invokeFunction1(onIntent, backupUploadEvent(uploadType, dir))
            }
            targetUnit()
        }
        method(AUTH_CARD_CLASS, AUTH_CARD_METHOD, 6).invoke(
            null,
            book.callLong("getId"),
            onAuthorize,
            onSetupDefaultDir,
            uploadBlock,
            composer,
            0,
        )
    }

    private fun renderLocalExportCard(context: BackupContext, composer: Any) {
        runCatching {
            val onDefault = functionProxy("LocalExportDefault", FUNCTION0_CLASS) {
                exportToDefaultDirectory(context)
                targetUnit()
            }
            val onPick = functionProxy("LocalExportPick", FUNCTION0_CLASS) {
                openDirectoryPicker(context)
                targetUnit()
            }
            renderAuthCardColumn(composer) { innerComposer ->
                renderLocalExportDriveCard(context, innerComposer, onDefault, onPick)
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local export card: ${it.stackTraceToString()}")
        }
    }

    private fun renderLocalExportDriveCard(
        context: BackupContext,
        composer: Any,
        onDefault: Any = functionProxy("LocalExportDefault", FUNCTION0_CLASS) {
            exportToDefaultDirectory(context)
            targetUnit()
        },
        onPick: Any = functionProxy("LocalExportPick", FUNCTION0_CLASS) {
            openDirectoryPicker(context)
            targetUnit()
        },
    ) {
        renderingLocalExport.set(true)
        runCatching {
            method(AUTH_CARD_CLASS, DRIVE_AUTH_CARD_METHOD, 8).invoke(
                null,
                localExportIcon(),
                EXPORT_TITLE,
                DOWNLOADS_LABEL,
                false,
                onDefault,
                onPick,
                composer,
                0,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local export drive card: ${it.stackTraceToString()}")
        }
        renderingLocalExport.remove()
    }

    private fun renderAuthCardColumn(composer: Any, content: (Any) -> Unit) {
        runCatching {
            val contentProxy = functionProxy("LocalExportColumnContent", FUNCTION3_CLASS) { args ->
                content(args?.getOrNull(1) ?: composer)
                targetUnit()
            }
            method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
                null,
                authCardColumnModifier(),
                spacedByUdp(16),
                alignmentStart(),
                contentProxy,
                composer,
                0,
                0,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to render local export card with native column: ${it.stackTraceToString()}")
            content(composer)
        }
    }

    private fun exportToDefaultDirectory(context: BackupContext) {
        val activity = activityProvider()
        val targetDir = File(downloadsDir())
        exportAsync(activity, context.book, "dir=${targetDir.absolutePath}") {
            targetDir.mkdirs()
            val file = uniqueFile(targetDir, exportFileName(context.book))
            file.outputStream().use { output ->
                zipBookDirectory(context.book, output)
            }
            file.absolutePath
        }
    }

    private fun exportToPickedDirectory(context: BackupContext, treeUri: Uri) {
        val activity = activityProvider()
        if (activity == null) {
            XposedBridge.log("$LOG_PREFIX local export picker result skipped: no activity")
            return
        }
        exportAsync(activity, context.book, "tree=$treeUri") {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val document = DocumentsContract.createDocument(
                activity.contentResolver,
                parent,
                EPUB_MIME_TYPE,
                exportFileName(context.book),
            ) ?: error("无法创建导出文件")
            activity.contentResolver.openOutputStream(document)?.use { output ->
                zipBookDirectory(context.book, output)
            } ?: error("无法写入导出文件")
            document.toString()
        }
    }

    private fun exportAsync(activity: Activity?, book: Any, targetLog: String, block: () -> String) {
        activity?.toast("已开始导出到本地存储")
        Thread {
            runCatching {
                val output = block()
                activity?.toast("已导出到本地存储")
                XposedBridge.log("$LOG_PREFIX local export completed $targetLog output=$output")
            }.onFailure {
                activity?.toast("导出失败：${it.message.orEmpty()}")
                XposedBridge.log("$LOG_PREFIX failed to export local book: ${it.stackTraceToString()}")
            }
        }.apply {
            name = "ReaMicroLocalExport"
            start()
        }
    }

    private fun openDirectoryPicker(context: BackupContext) {
        val activity = activityProvider()
        if (activity == null) {
            XposedBridge.log("$LOG_PREFIX local export picker skipped: no activity")
            return
        }
        pendingPickContext = context
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, REQUEST_PICK_EXPORT_DIR)
        }.onFailure {
            pendingPickContext = null
            activity.toast("无法打开目录选择器")
            XposedBridge.log("$LOG_PREFIX failed to open local export picker: ${it.stackTraceToString()}")
        }
    }

    private fun zipBookDirectory(book: Any, output: OutputStream) {
        val root = bookDirectory(book)
        check(root.isDirectory) { "本地书籍数据不存在" }
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
        val activity = activityProvider() ?: error("无法获取阅微上下文")
        val uid = book.callLong("getUid")
        val uuid = book.callString("getUuid")
        return File(activity.filesDir, "$uid/books/$uuid")
    }

    private fun exportFileName(book: Any): String =
        "${book.callString("getTitle").sanitizeFileName().ifBlank { "book" }}.epub"

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            candidate = File(dir, nextName)
            index++
        }
        return candidate
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)

    private fun Any.callString(methodName: String): String =
        javaClass.methods.first { it.name == methodName && it.parameterTypes.isEmpty() }
            .invoke(this)
            ?.toString()
            .orEmpty()

    private fun Any.callLong(methodName: String): Long {
        val value = javaClass.methods.first { it.name == methodName && it.parameterTypes.isEmpty() }
            .invoke(this)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLong() ?: 0L
    }

    private fun Any.callInt(methodName: String): Int {
        val value = javaClass.methods.first { it.name == methodName && it.parameterTypes.isEmpty() }
            .invoke(this)
        return (value as? Number)?.toInt() ?: value?.toString()?.toInt() ?: 0
    }

    private fun downloadsDir(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    private fun localExportIcon(): Any {
        val colored = staticObject(APP_ICONS_COLORED_CLASS, "INSTANCE")
        return method(ANDROID_OS_KT_CLASS, ANDROID_OS_METHOD, 1).invoke(null, colored)
    }

    private fun authCardColumnModifier(): Any {
        val modifier = staticObject(MODIFIER_CLASS, "INSTANCE")
        val horizontalPadded = method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5).invoke(
            null,
            modifier,
            udp(16),
            0f,
            2,
            null,
        )
        val padded = method(PADDING_KT_CLASS, PADDING_INDIVIDUAL_DEFAULT_METHOD, 7).invoke(
            null,
            horizontalPadded,
            0f,
            0f,
            0f,
            udp(16),
            7,
            null,
        )
        return method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(
            null,
            padded,
            0f,
            1,
            null,
        )
    }

    private fun scrollableBackupContentModifier(composer: Any): Any {
        val modifier = staticObject(MODIFIER_CLASS, "INSTANCE")
        val fillMaxSize = method(SIZE_KT_CLASS, FILL_MAX_SIZE_DEFAULT_METHOD, 4).invoke(
            null,
            modifier,
            0f,
            1,
            null,
        )
        return scrollableModifier(fillMaxSize, composer)
    }

    private fun scrollableModifier(modifier: Any, composer: Any): Any {
        val scrollState = method(SCROLL_KT_CLASS, REMEMBER_SCROLL_STATE_METHOD, 4).invoke(
            null,
            0,
            composer,
            0,
            1,
        )
        return method(SCROLL_KT_CLASS, VERTICAL_SCROLL_DEFAULT_METHOD, 7).invoke(
            null,
            modifier,
            scrollState,
            false,
            null,
            false,
            14,
            null,
        )
    }

    private fun arrangementTop(): Any {
        val arrangement = staticObject(ARRANGEMENT_CLASS, "INSTANCE")
        return instanceMethod(arrangement, ARRANGEMENT_TOP_METHOD, 0).invoke(arrangement)
    }

    private fun spacedByUdp(value: Int): Any {
        val arrangement = staticObject(ARRANGEMENT_CLASS, "INSTANCE")
        return instanceMethod(arrangement, SPACED_BY_METHOD, 1).invoke(arrangement, udp(value))
    }

    private fun alignmentStart(): Any {
        val alignment = staticObject(ALIGNMENT_CLASS, "INSTANCE")
        return instanceMethod(alignment, ALIGNMENT_START_METHOD, 0).invoke(alignment)
    }

    private fun udp(value: Int): Float =
        (typedMethod(UNIT_EXT_KT_CLASS, UDP_METHOD, Int::class.javaPrimitiveType).invoke(null, value) as Number).toFloat()

    private fun method(className: String, methodName: String, parameterCount: Int): Method =
        method(className, methodName, parameterCount, null)

    private fun method(className: String, methodName: String, parameterCount: Int, receiver: Any?): Method {
        val receiverClass = receiver?.javaClass?.name.orEmpty()
        val cacheKey = "$className#$methodName/$parameterCount@$receiverClass"
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

    private fun typedMethod(className: String, methodName: String, vararg parameterTypes: Class<*>?): Method {
        val cacheKey = "$className#$methodName(${parameterTypes.joinToString { it?.name.orEmpty() }})"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).getDeclaredMethod(methodName, *parameterTypes).apply {
                    isAccessible = true
                }
            }
        }
    }

    private fun instanceMethod(receiver: Any, methodName: String, parameterCount: Int): Method {
        val cacheKey = "${receiver.javaClass.name}#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val candidates = receiver.javaClass.methods.asSequence() + receiver.javaClass.declaredMethods.asSequence()
                candidates.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("${receiver.javaClass.name}.$methodName/$parameterCount not found")
            }
        }
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

    private fun invokeFunction1(function: Any, arg: Any?) {
        instanceMethod(function, "invoke", 1).invoke(function, arg)
    }

    private fun backupDeleteEvent(): Any =
        staticObject(BOOK_BACKUP_DELETE_EVENT_CLASS, "INSTANCE")

    private fun backupUploadEvent(type: Int, dir: String): Any =
        cls(BOOK_BACKUP_UPLOAD_EVENT_CLASS)
            .getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(type, dir)

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = cls(className)
        val names = if (fieldName == "INSTANCE") arrayOf("INSTANCE", "Companion") else arrayOf(fieldName)
        val field = names.firstNotNullOfOrNull { name ->
            runCatching { clazz.getDeclaredField(name) }.getOrNull()
        } ?: error("No field ${names.joinToString("/")} in $className")
        field.isAccessible = true
        return field.get(null)
    }

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun targetUnit(): Any? = runCatching {
        staticObject("kotlin.Unit", "INSTANCE")
    }.getOrNull()

    private fun Activity.toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class BackupContext(
        val book: Any,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val MAIN_ACTIVITY_CLASS = "app.zhendong.reamicro.MainActivity"
        const val BOOK_BACKUP_SCREEN_CLASS = "app.zhendong.reamicro.ui.backup.BookBackupScreenKt"
        const val BOOK_BACKUP_CONTENT_METHOD = "BookBackupContent"
        const val BOOK_BACKUP_DELETE_EVENT_CLASS = "app.zhendong.reamicro.ui.backup.BookBackupUIEvent\$Delete"
        const val BOOK_BACKUP_UPLOAD_EVENT_CLASS = "app.zhendong.reamicro.ui.backup.BookBackupUIEvent\$Upload"
        const val LOCAL_STORAGE_CARD_CLASS = "app.zhendong.reamicro.ui.backup.components.LocalStorageCardKt"
        const val LOCAL_STORAGE_CARD_METHOD = "LocalStorageCard"
        const val AUTH_CARD_CLASS = "app.zhendong.reamicro.ui.backup.components.AuthCardKt"
        const val AUTH_CARD_METHOD = "AuthCard"
        const val DRIVE_AUTH_CARD_METHOD = "DriveAuthCard"
        const val DRIVE_CARD_CLASS = "app.zhendong.reamicro.ui.backup.components.DriveCardKt"
        const val ALIYUN_DRIVE_CARD_METHOD = "AliyunDriveCard"
        const val BAIDU_NET_DISK_CARD_METHOD = "BaiduNetDiskCard"
        const val YUN115_NET_DISK_CARD_METHOD = "Yun115NetDiskCard"
        const val BACKUP_TYPE_BAIDU = 1
        const val BACKUP_TYPE_YUN115 = 2
        const val BACKUP_TYPE_ALIYUN = 4
        const val BACKUP_TYPE_WEBDAV = 8
        const val APP_ICONS_COLORED_CLASS = "app.zhendong.reamicro.arch.icons.AppIcons\$Colored"
        const val ANDROID_OS_KT_CLASS = "app.zhendong.reamicro.arch.icons.colored.AndroidOsKt"
        const val ANDROID_OS_METHOD = "getAndroidOs"
        const val COLUMN_KT_CLASS = "androidx.compose.foundation.layout.ColumnKt"
        const val COLUMN_METHOD = "Column"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val FILL_MAX_SIZE_DEFAULT_METHOD = "fillMaxSize\$default"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_HORIZONTAL_DEFAULT_METHOD = "padding-VpY3zN4\$default"
        const val PADDING_INDIVIDUAL_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val ARRANGEMENT_TOP_METHOD = "getTop"
        const val SPACED_BY_METHOD = "spacedBy-0680j_4"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val ALIGNMENT_START_METHOD = "getStart"
        const val SCROLL_KT_CLASS = "androidx.compose.foundation.ScrollKt"
        const val REMEMBER_SCROLL_STATE_METHOD = "rememberScrollState"
        const val VERTICAL_SCROLL_DEFAULT_METHOD = "verticalScroll\$default"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val FUNCTION2_CLASS = "kotlin.jvm.functions.Function2"
        const val FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val REQUEST_PICK_EXPORT_DIR = 0x524D47
        const val EPUB_MIME_TYPE = "application/epub+zip"
        const val EXPORT_TITLE = "导出到本地存储"
        const val DOWNLOADS_LABEL = "下载"
    }
}
