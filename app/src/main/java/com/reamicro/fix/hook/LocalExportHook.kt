package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalExportHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val enabledProvider: () -> Boolean = { true },
) {

    private val host get() = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.export)
    private val ui get() = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    private val contexts = ThreadLocal<java.util.ArrayDeque<BackupContext>>()
    private val pickLock = Any()
    private var nextPickRequestCode = REQUEST_PICK_EXPORT_DIR
    @Volatile private var pendingPickContext: PendingExportPick? = null

    fun install() {
        val symbols = host
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("content"), object : HookCallback() {
                override fun before(param: Call) {
                    val book = param.args.firstOrNull()?.takeIf(symbols.type("book")::isInstance) ?: return
                    val frame = createBackupContext(book) ?: return
                    (contexts.get() ?: java.util.ArrayDeque<BackupContext>().also(contexts::set)).addLast(frame)
                    param.setObjectExtra(BACKUP_FRAME, frame)
                }
                override fun after(param: Call) {
                    val frame = param.getObjectExtra(BACKUP_FRAME) as? BackupContext ?: return
                    val stack = contexts.get() ?: return
                    if (stack.peekLast() === frame) stack.removeLast() else stack.remove(frame)
                    if (stack.isEmpty()) contexts.remove()
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("header"), object : HookCallback() {
                override fun after(param: Call) {
                    val context = contexts.get()?.peekLast() ?: return
                    if (param.throwable != null || param.args.firstOrNull() !== context.book || context.rendered) return
                    val composer = param.args.getOrNull(symbols.number("header.composer"))?.takeIf(symbols.type("composer")::isInstance) ?: return
                    context.rendered = true
                    com.reamicro.fix.reload.ModuleResources.lease()?.use { renderLocalExportCard(context, composer) }
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("activityResult"), object : HookCallback() {
                override fun after(param: Call) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val activity = param.thisObject as? Activity ?: return
                    val context = synchronized(pickLock) {
                        val pending = pendingPickContext ?: return
                        if (pending.context.owner !== activity || pending.requestCode != requestCode) return
                        pendingPickContext = null
                        pending.context
                    }
                    if (!isExportCurrent(context)) return
                    if (resultCode != Activity.RESULT_OK || data?.data == null) {
                        context.toast("已取消选择目录")
                        return
                    }
                    val uri = data.data ?: return
                    val readPermission = if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    } else 0
                    val writePermission = if ((data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    } else 0
                    runCatching {
                        requireExportCurrent(context)
                        activity.contentResolver.takePersistableUriPermission(uri, readPermission or writePermission)
                    }
                    if (!isExportCurrent(context)) return
                    exportToPickedDirectory(context, uri)
                }
            })
            listOf("content", "header", "activityResult").forEach { HookRuntime.deoptimize(symbols.method(it)) }
            com.reamicro.fix.reload.ModuleResources.cleanup { contexts.remove(); synchronized(pickLock) { pendingPickContext = null } }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    private fun renderLocalExportCard(context: BackupContext, composer: Any) {
        val onDefault = ui.callback(host.type("function")) { exportToDefaultDirectory(context) }
        val onPick = ui.callback(host.type("function")) { openDirectoryPicker(context) }
        ui.instance("startGroup", composer, 0x524D4558)
        try {
            val native = HostNativeUi(classLoader)
            val content = ui.callback(ui.type("function3")) { args ->
                host.call("drive", host.call("icon"), EXPORT_TITLE, DOWNLOADS_LABEL, false,
                    onDefault, onPick, requireNotNull(args?.getOrNull(1)), 0)
            }
            native.column(native.padding(native.fillWidth(native.modifier()), native.dp(16), 0f, native.dp(16), native.dp(16)),
                native.top(), native.start(), content, composer)
        } finally {
            ui.instance("endGroup", composer, false)
        }
    }

    private fun exportToDefaultDirectory(context: BackupContext) {
        val targetDir = File(downloadsDir())
        exportAsync(context, "dir=${targetDir.absolutePath}") { checkCurrent ->
            LocalExportFiles.exportToDirectory(context.root, targetDir, context.fileName,
                commit = { action ->
                    AccountHostRuntime.withCurrentStorage(context.storageIdentity, context.owner) {
                        checkCurrent(); action()
                    }
                }, checkCurrent = checkCurrent).absolutePath
        }
    }

    private fun exportToPickedDirectory(context: BackupContext, treeUri: Uri) {
        val activity = context.owner
        exportAsync(context, "tree=$treeUri") { checkCurrent ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val stage = File.createTempFile(".reamicro-export-", ".epub", activity.cacheDir)
            try {
                LocalExportFiles.writeArchive(context.root, stage, checkCurrent)
                LocalExportFiles.publishCreatedOutput(
                    create = {
                        DocumentsContract.createDocument(activity.contentResolver, parent, EPUB_MIME_TYPE, context.fileName)
                            ?: error("无法创建导出文件")
                    },
                    write = { document ->
                        activity.contentResolver.openOutputStream(document)?.use { output ->
                            stage.inputStream().use { LocalExportFiles.copyChecked(it, output, checkCurrent) }
                        } ?: error("无法写入导出文件")
                    },
                    cleanup = { document ->
                        check(DocumentsContract.deleteDocument(activity.contentResolver, document)) { "无法清理未完成的导出文件" }
                    },
                    commit = { action ->
                        // A SAF provider can perform remote I/O; never hold the account lock around its IPC.
                        checkCurrent(); action(); checkCurrent()
                    },
                    checkCurrent = checkCurrent,
                ).toString()
            } finally { stage.delete() }
        }
    }

    private fun exportAsync(context: BackupContext, targetLog: String, block: (() -> Unit) -> String) {
        if (!isExportCurrent(context)) return
        context.toast("已开始导出到本地存储")
        com.reamicro.fix.reload.ModuleResources.thread {
            runCatching {
                requireExportCurrent(context)
                val output = block { requireExportCurrent(context) }
                context.toast("已导出到本地存储")
                HookRuntime.log("$LOG_PREFIX local export completed $targetLog output=$output")
            }.onFailure {
                context.toast("导出失败：${it.message.orEmpty()}")
                HookRuntime.log("$LOG_PREFIX failed to export local book: ${it.stackTraceToString()}")
            }
        }.apply {
            name = "ReaMicroLocalExport"
            start()
        }
    }

    private fun openDirectoryPicker(context: BackupContext) {
        if (!isExportCurrent(context)) return
        val activity = context.owner
        val ticket = synchronized(pickLock) {
            if (pendingPickContext?.context?.let(::isExportCurrent) == true) {
                context.toast("目录选择器已打开")
                return
            }
            PendingExportPick(context, nextPickRequestCode).also {
                nextPickRequestCode = if (nextPickRequestCode == 0x7FFF) REQUEST_PICK_EXPORT_DIR else nextPickRequestCode + 1
                pendingPickContext = it
            }
        }
        runCatching {
            requireExportCurrent(context)
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, ticket.requestCode)
        }.onFailure {
            synchronized(pickLock) { if (pendingPickContext === ticket) pendingPickContext = null }
            context.toast("无法打开目录选择器")
            HookRuntime.log("$LOG_PREFIX failed to open local export picker: ${it.stackTraceToString()}")
        }
    }

    private fun createBackupContext(book: Any): BackupContext? = runCatching {
        val activity = activityProvider() ?: return null
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return null
        val uid = host.field("book.uid").getLong(book)
        val uuid = host.field("book.uuid").get(book) as String
        check(uid == identity.accountId) { "图书不属于当前账号" }
        val root = LocalExportFiles.bookRoot(activity.filesDir, uid, uuid)
        BackupContext(book, activity, identity, root, exportFileName(book)).also { requireExportCurrent(it) }
    }.onFailure { HookRuntime.log("$LOG_PREFIX local export context skipped: ${it.message}") }.getOrNull()

    private fun requireExportCurrent(context: BackupContext) {
        check(enabledProvider()) { "本地导出已关闭" }
        check(!Thread.currentThread().isInterrupted) { "导出已取消" }
        check(!context.owner.isFinishing && !context.owner.isDestroyed) { "导出页面已关闭" }
        check(AccountHostRuntime.isCurrentStorage(context.storageIdentity, context.owner)) { "账号已变更，请重新导出" }
        val uid = host.field("book.uid").getLong(context.book)
        val uuid = host.field("book.uuid").get(context.book) as String
        check(uid == context.storageIdentity.accountId &&
            LocalExportFiles.bookRoot(context.owner.filesDir, uid, uuid) == context.root) { "图书存储已变更，请重新导出" }
    }

    private fun isExportCurrent(context: BackupContext): Boolean = runCatching { requireExportCurrent(context) }.isSuccess

    private fun exportFileName(book: Any): String =
        "${(host.field("book.title").get(book) as String).sanitizeFileName().ifBlank { "book" }}.epub"

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120)

    private fun downloadsDir(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    private fun BackupContext.toast(message: String) {
        owner.runOnModuleUiThread {
            if (isExportCurrent(this)) Toast.makeText(owner, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class BackupContext(
        val book: Any,
        val owner: Activity,
        val storageIdentity: AccountHostRuntime.Identity,
        val root: File,
        val fileName: String,
        var rendered: Boolean = false,
    )
    private data class PendingExportPick(val context: BackupContext, val requestCode: Int)

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BACKUP_FRAME = "reamicro.localExportFrame"
        const val REQUEST_PICK_EXPORT_DIR = 0x524D
        const val EPUB_MIME_TYPE = "application/epub+zip"
        const val EXPORT_TITLE = "导出到本地存储"
        const val DOWNLOADS_LABEL = "下载"
    }
}

internal object LocalExportFiles {
    fun bookRoot(filesDir: File, accountId: Long, bookId: String): File {
        require(accountId >= 0L && bookId.isNotBlank() && bookId !in setOf(".", "..") &&
            bookId.none { it < ' ' || it == '/' || it == '\\' }) { "图书存储标识无效" }
        val target = File(filesDir.canonicalFile, "$accountId/books/$bookId").absoluteFile
        val canonical = target.canonicalFile
        require(canonical == target) { "图书目录已变更" }
        return canonical
    }

    fun ensureNcxSpineToc(content: String): String {
        if (runCatching { com.reamicro.fix.epub.editor.EpubNavigation.parse(content) }.isFailure) return content
        val nodes = com.reamicro.fix.epub.editor.EpubLinkRewriter.nodes(content, html = false)
        val packageNode = nodes.filter { it.name == "package" && it.parent == null }.singleOrNull() ?: return content
        val manifest = packageNode.children.filter { it.name == "manifest" }.singleOrNull() ?: return content
        val spine = packageNode.children.filter { it.name == "spine" }.singleOrNull() ?: return content
        if (spine.attr("toc") != null) return content
        val ncx = manifest.children.filter {
            it.name == "item" && it.attr("media-type")?.value.equals("application/x-dtbncx+xml", ignoreCase = true)
        }.singleOrNull() ?: return content
        val id = ncx.attr("id")?.value?.takeIf { it.isNotBlank() } ?: return content
        if (manifest.children.count { it.name == "item" && it.attr("id")?.value == id } != 1) return content
        val token = content.substring(spine.start, spine.openEnd)
        val name = token.drop(1).takeWhile { it.isLetterOrDigit() || it in "_:.-" }
        val insertAt = spine.start + name.length + 1
        val value = com.reamicro.fix.epub.editor.EpubLinkRewriter.xml(id)
        return content.substring(0, insertAt) + " toc=\"$value\"" + content.substring(insertAt)
    }

    fun patchedOpfBytes(file: File, checkCurrent: () -> Unit = {}): ByteArray? {
        checkCurrent()
        val loaded = runCatching {
            com.reamicro.fix.epub.editor.EpubTextFiles.load(file,
                com.reamicro.fix.epub.editor.EpubTextFiles.availableLoadBudget(), checkCurrent)
        }.getOrElse { checkCurrent(); return null }
        val patched = ensureNcxSpineToc(loaded.text)
        if (patched == loaded.text) return null
        val format = loaded.snapshot.format
        val encoded = format.charset.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(patched))
        checkCurrent()
        return ByteArray(format.bom.size + encoded.remaining()).also {
            format.bom.copyInto(it)
            encoded.get(it, format.bom.size, it.size - format.bom.size)
        }
    }

    fun zipBookDirectory(root: File, output: OutputStream, checkCurrent: () -> Unit = {}) {
        checkCurrent()
        require(root.canonicalFile == root.absoluteFile && root.isDirectory) { "图书目录不存在或已变更" }
        val leaveOpen = object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun close() { flush() }
        }
        ZipOutputStream(leaveOpen.buffered()).use { zip ->
            val mimetype = File(root, "mimetype")
            if (Files.isRegularFile(mimetype.toPath(), LinkOption.NOFOLLOW_LINKS)) addStoredFile(zip, mimetype, checkCurrent)
            root.walkTopDown()
                .onEnter { checkCurrent(); !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith(".reamicro-") }
                .filter { it != mimetype && !it.name.startsWith(".reamicro-") &&
                    Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                .forEach { file ->
                    checkCurrent()
                    require(file.canonicalFile.toPath().startsWith(root.toPath())) { "导出文件路径已变更" }
                    val name = file.relativeTo(root).invariantSeparatorsPath
                    val size = file.length()
                    val modified = file.lastModified()
                    val patched = if (name.endsWith(".opf", true)) patchedOpfBytes(file, checkCurrent) else null
                    zip.putNextEntry(ZipEntry(name))
                    if (patched != null) {
                        var offset = 0
                        while (offset < patched.size) {
                            checkCurrent()
                            val count = minOf(64 * 1024, patched.size - offset)
                            zip.write(patched, offset, count); offset += count
                        }
                    } else Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered().use {
                        copyChecked(it, zip, checkCurrent)
                    }
                    checkCurrent()
                    check(file.length() == size && file.lastModified() == modified) { "导出期间图书文件已改变，请重试" }
                    zip.closeEntry()
                }
            checkCurrent()
        }
    }

    private fun addStoredFile(zip: ZipOutputStream, file: File, checkCurrent: () -> Unit) {
        val size = file.length()
        val modified = file.lastModified()
        val crc = CRC32()
        var counted = 0L
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCurrent()
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count); counted += count
            }
        }
        check(counted == size && file.length() == size && file.lastModified() == modified) { "导出期间 mimetype 已改变" }
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc.value
        })
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered().use {
            copyChecked(it, zip, checkCurrent)
        }
        checkCurrent()
        check(file.length() == size && file.lastModified() == modified) { "导出期间 mimetype 已改变" }
        zip.closeEntry()
    }

    fun copyChecked(input: java.io.InputStream, output: OutputStream, checkCurrent: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        checkCurrent()
    }

    fun writeArchive(root: File, target: File, checkCurrent: () -> Unit) {
        target.outputStream().use { output ->
            zipBookDirectory(root, output, checkCurrent)
            output.fd.sync()
        }
    }

    fun exportToDirectory(root: File, directory: File, fileName: String,
        commit: (() -> Unit) -> Unit = { it() }, checkCurrent: () -> Unit = {}): File {
        checkCurrent()
        require(fileName.isNotBlank() && File(fileName).name == fileName && fileName.none { it == '\\' || it < ' ' }) { "导出文件名无效" }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建导出目录" }
        val stage = File.createTempFile(".reamicro-export-", ".epub", directory)
        try {
            writeArchive(root, stage, checkCurrent)
            val expected = com.reamicro.fix.epub.editor.VerifiedFileIO.digest(stage)
            var published: File? = null
            commit {
                check(published == null) { "导出文件已经提交" }
                checkCurrent()
                val stem = fileName.substringBeforeLast('.', fileName)
                val extension = fileName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
                for (index in 0..9999) {
                    val target = File(directory, if (index == 0) fileName else "$stem ($index)$extension")
                    try {
                        Files.move(stage.toPath(), target.toPath())
                        published = target
                        break
                    } catch (_: java.nio.file.FileAlreadyExistsException) { }
                }
                check(published != null) { "无法生成唯一导出文件名" }
            }
            val result = checkNotNull(published) { "导出未提交，原目录文件已保留" }
            com.reamicro.fix.epub.editor.VerifiedFileIO.requireMatches(result, expected)
            return result
        } finally { stage.delete() }
    }

    fun <T : Any> publishCreatedOutput(create: () -> T, write: (T) -> Unit, cleanup: (T) -> Unit,
        commit: (() -> Unit) -> Unit, checkCurrent: () -> Unit): T {
        var created: T? = null
        try {
            commit {
                check(created == null) { "导出目标已经创建" }
                checkCurrent()
                created = create()
            }
            val result = checkNotNull(created) { "导出目标未创建" }
            checkCurrent()
            write(result)
            checkCurrent()
            return result
        } catch (failure: Exception) {
            created?.let {
                try { cleanup(it) } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                    throw IllegalStateException("导出未完成，未完成文件也无法清理，请检查目标目录", failure)
                }
            }
            throw failure
        }
    }
}
