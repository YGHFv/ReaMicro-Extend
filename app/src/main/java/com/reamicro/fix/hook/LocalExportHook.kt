package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import com.reamicro.fix.core.ComposeInterop
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalExportHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {

    private val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = ::cls,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    private val methodCache = mutableMapOf<String, Method>()
    private val currentBackupContext = ThreadLocal<BackupContext?>()
    @Volatile private var pendingPickContext: BackupContext? = null

    fun install() {
        hookBookBackupContent()
        hookLocalStorageCard()
        hookActivityResult()
    }

    private fun hookBookBackupContent() {
        runCatching {
            val method = cls(BOOK_BACKUP_SCREEN_CLASS).declaredMethods.first {
                it.hostNameMatches(BOOK_BACKUP_CONTENT_METHOD) && it.parameterTypes.size == 9
            }.apply { isAccessible = true }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    currentBackupContext.set(BackupContext(book))
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
            val method = staticMethod(LOCAL_STORAGE_CARD_CLASS, LOCAL_STORAGE_CARD_METHOD, 3)
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
            if (HostSymbols.abiProfileId == "2.3.3-b4") {
                // 恢复旧 authCardColumnModifier 的外层缩进，不能直接插入全宽的备份页 Column。
                val ui = B2NativeUi(classLoader)
                val content = functionProxy("LocalExportColumnContent", HostClasses.Kotlin.FUNCTION3) { args ->
                    renderLocalExportDriveCard(context, requireNotNull(args?.getOrNull(1)), onDefault, onPick)
                    targetUnit()
                }
                ui.column(ui.padding(ui.fillWidth(ui.modifier()), ui.dp(16), 0f, ui.dp(16), ui.dp(16)),
                    ui.top(), ui.start(), content, composer)
            } else renderLocalExportDriveCard(context, composer, onDefault, onPick)
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

    private fun addDeflatedBytes(zip: ZipOutputStream, bytes: ByteArray, name: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun patchedOpfBytes(file: File): ByteArray? {
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val patched = ensureNcxSpineToc(content)
        return patched.takeIf { it != content }?.toByteArray(Charsets.UTF_8)
    }

    private fun ensureNcxSpineToc(content: String): String {
        if (Regex("""<spine\b[^>]*\btoc\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            return content
        }
        val ncxId = Regex(
            """<item\b(?=[^>]*\bmedia-type\s*=\s*["']application/x-dtbncx\+xml["'])(?=[^>]*\bid\s*=\s*["']([^"']+)["'])[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return content
        return Regex("""<spine\b""", RegexOption.IGNORE_CASE)
            .replaceFirst(content, "<spine toc=\"$ncxId\"")
    }

    private fun bookDirectory(book: Any): File {
        val activity = activityProvider() ?: error("无法获取阅微上下文")
        val uid = book.hostFieldLong(BOOK_CLASS, "uid")
        val uuid = book.hostFieldString(BOOK_CLASS, "uuid")
        return File(activity.filesDir, "$uid/books/$uuid")
    }

    private fun exportFileName(book: Any): String =
        "${book.hostFieldString(BOOK_CLASS, "title").sanitizeFileName().ifBlank { "book" }}.epub"

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

    private fun Any.hostFieldString(className: String, fieldName: String): String =
        hostField(className, fieldName)?.toString().orEmpty()

    private fun Any.hostFieldLong(className: String, fieldName: String): Long {
        val value = hostField(className, fieldName)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLong() ?: 0L
    }

    private fun Any.hostField(className: String, fieldName: String): Any? {
        val actualName = HostSymbols.resolveFieldName(className, fieldName)
        var type: Class<*>? = javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField(actualName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(this)
            }
            type = type.superclass
        }
        error("$className.$fieldName not found")
    }

    private fun downloadsDir(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    private fun localExportIcon(): Any {
        runCatching {
            return method(ANDROID_OS_KT_CLASS, ANDROID_OS_METHOD, 0).invoke(null)
        }
        val colored = staticObject(APP_ICONS_COLORED_CLASS, "INSTANCE")
        return method(ANDROID_OS_KT_CLASS, ANDROID_OS_METHOD, 1).invoke(null, colored)
    }

    private fun method(className: String, methodName: String, parameterCount: Int): Method =
        staticMethod(className, methodName, parameterCount)

    private fun staticMethod(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val owner = Class.forName(
                    HostSymbols.resolveMethodOwner(className, methodName),
                    false,
                    classLoader,
                )
                val resolvedName = HostSymbols.resolveMethodName(className, methodName)
                val candidates = owner.methods.asSequence() + owner.declaredMethods.asSequence()
                candidates.firstOrNull {
                    (it.name == resolvedName || it.name == methodName) &&
                        it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
        composeInterop.functionProxy(name, functionClassName, block)

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
        const val MAIN_ACTIVITY_CLASS = HostClasses.Host.MAIN_ACTIVITY
        const val BOOK_BACKUP_SCREEN_CLASS = HostClasses.Host.BOOK_BACKUP_SCREEN
        const val BOOK_CLASS = HostClasses.Host.BOOK
        const val BOOK_BACKUP_CONTENT_METHOD = "BookBackupContent"
        const val LOCAL_STORAGE_CARD_CLASS = HostClasses.Host.LOCAL_STORAGE_CARD
        const val LOCAL_STORAGE_CARD_METHOD = "LocalStorageCard"
        const val AUTH_CARD_CLASS = HostClasses.Host.AUTH_CARD
        const val DRIVE_AUTH_CARD_METHOD = "DriveAuthCard"
        const val APP_ICONS_COLORED_CLASS = "app.zhendong.reamicro.arch.icons.AppIcons\$Colored"
        const val ANDROID_OS_KT_CLASS = HostClasses.Host.ANDROID_OS_ICON
        const val ANDROID_OS_METHOD = "getAndroidOs"
        const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
        const val REQUEST_PICK_EXPORT_DIR = 0x524D50
        const val EPUB_MIME_TYPE = "application/epub+zip"
        const val EXPORT_TITLE = "导出到本地存储"
        const val DOWNLOADS_LABEL = "下载"
    }
}
