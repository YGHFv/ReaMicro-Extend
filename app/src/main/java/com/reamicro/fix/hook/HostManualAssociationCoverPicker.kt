package com.reamicro.fix.hook

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.reamicro.fix.association.AssociationBookIdentity
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostBookBindings
import com.reamicro.fix.epub.editor.AccountBookCoverFiles
import com.reamicro.fix.epub.editor.AccountBookImageFiles
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Selection is a private draft; only the host's confirmed association may publish book assets. */
internal class HostManualAssociationCoverPicker(private val loader: ClassLoader) {
    private data class Request(
        val generation: Long,
        val activity: Activity,
        val account: AccountHostRuntime.Identity,
        val identity: AssociationBookIdentity,
        val isCurrent: () -> Boolean,
        val onBusy: (Boolean) -> Unit,
        val onImported: (String) -> Unit,
        val onProblem: (String) -> Unit,
    )
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var pending: Request? = null
    @Volatile private var importing = false
    @Volatile private var drafts: ManualAssociationCoverStore? = null

    fun install(): io.github.libxposed.api.XposedInterface.HookHandle {
        val method = requireNotNull(HostBookUiBindings.metadata).method("activityResult")
        val handle = HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                if (param.args.getOrNull(0) != REQUEST_COVER) return
                val request = pending?.takeIf { it.activity === param.thisObject } ?: return
                pending = null
                param.result = null
                val uri = (param.args.getOrNull(2) as? Intent)?.data
                if (param.args.getOrNull(1) != Activity.RESULT_OK || uri == null || !current(request)) {
                    request.onBusy(false)
                    return
                }
                import(request, uri)
            }
        })
        ModuleResources.blockWhile {
            if (importing) "请等待封面导入完成" else if (pending != null) "请先关闭封面选择页" else null
        }
        ModuleResources.cleanup { cancel() }
        return handle
    }

    fun open(
        activity: Activity,
        account: AccountHostRuntime.Identity,
        identity: AssociationBookIdentity,
        isCurrent: () -> Boolean,
        onBusy: (Boolean) -> Unit,
        onImported: (String) -> Unit,
        onProblem: (String) -> Unit,
    ) {
        if (pending != null || importing) return
        val request = Request(generation.get(), activity, account, identity,
            isCurrent, onBusy, onImported, onProblem)
        if (!current(request)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pending = request
        onBusy(true)
        try {
            activity.startActivityForResult(intent, REQUEST_COVER)
        } catch (error: Exception) {
            pending = null
            onBusy(false)
            onProblem("无法打开封面选择器")
            HookRuntime.logError("ReaMicro manual cover picker unavailable", error)
        }
    }

    fun invalidateExecution() {
        generation.incrementAndGet()
    }
    fun executionToken(): Long = generation.get()

    fun cancel() {
        invalidateExecution()
        pending?.onBusy?.invoke(false)
        pending = null
        retain(emptySet())
    }

    fun isDraft(path: String): Boolean = drafts?.contains(path) == true
    fun retain(paths: Set<String>) { drafts?.retain(paths) }
    fun acquire(path: String): ManualAssociationCoverStore.Selection? = drafts?.acquire(path)

    fun imageSource(activity: Activity, identity: AssociationBookIdentity, path: String): java.io.File? = runCatching {
        acquire(path)?.use { return@runCatching it.file }
        if (path.isBlank() || path.contains(':')) return@runCatching null
        val directory = AccountBookImageFiles.directory(activity.filesDir, identity.uid, identity.uuid)
        AccountBookImageFiles.child(directory, path).takeIf { it.isFile }
    }.getOrNull()

    fun preview(activity: Activity, identity: AssociationBookIdentity, path: String): Bitmap? = runCatching {
        val selection = acquire(path)
        try {
            val file = selection?.file ?: imageSource(activity, identity, path) ?: return@runCatching null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
            var sample = 1
            while (options.outWidth / sample > 216 || options.outHeight / sample > 288) sample *= 2
            options.inSampleSize = sample
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.absolutePath, options)
        } finally { selection?.close() }
    }.getOrNull()

    private fun current(request: Request): Boolean =
        ModuleResources.accepting && request.generation == generation.get() && !Thread.currentThread().isInterrupted &&
            !request.activity.isFinishing && !request.activity.isDestroyed && request.isCurrent() &&
            AccountHostRuntime.isCurrentStorage(request.account, request.activity)

    private fun import(request: Request, uri: Uri) {
        importing = true
        try {
            ModuleResources.thread {
                val result = try { runCatching { stageCover(request, uri) } } finally { importing = false }
                request.activity.runOnModuleUiThread {
                    request.onBusy(false)
                    if (!current(request)) {
                        result.getOrNull()?.let { drafts?.discard(it) }
                        return@runOnModuleUiThread
                    }
                    result.onSuccess(request.onImported)
                        .onFailure { error ->
                            request.onProblem(error.message ?: "封面导入失败")
                            HookRuntime.logError("ReaMicro manual cover import stopped", error)
                        }
                }
            }.apply { name = "ReaMicro-ManualCoverImport"; start() }
        } catch (error: Throwable) {
            importing = false
            request.onBusy(false)
            if (current(request)) request.onProblem("无法开始封面导入，请重试")
            HookRuntime.logError("ReaMicro manual cover import unavailable", error)
        }
    }

    private fun stageCover(request: Request, uri: Uri): String {
        fun checkCurrent() = check(current(request)) { "账号或关联页面已变化，请重新打开" }
        checkCurrent()
        val bytes = readImage(request, uri)
        checkCurrent()
        val extension = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }.outMimeType?.substringAfter('/')?.let {
            when (it) { "jpeg" -> "jpg"; "png", "webp", "gif", "bmp", "avif", "heif", "heic" -> it; else -> "jpg" }
        } ?: "jpg"
        val store = drafts ?: ManualAssociationCoverStore(request.activity.cacheDir).also { drafts = it }
        val path = store.stage(bytes, extension)
        if (!current(request)) { store.discard(path); error("关联页面已关闭") }
        return path
    }

    data class Published(val book: Any, val publication: ManualAssociationCoverPublication, val path: String)

    fun publish(
        activity: Activity, account: AccountHostRuntime.Identity, identity: AssociationBookIdentity,
        selection: ManualAssociationCoverStore.Selection, book: Any, executionToken: Long,
    ): Published {
        val host = requireNotNull(HostBookBindings.current)
        fun property(name: String) = host.properties.getValue(name).get(book)
        fun checkCurrent() {
            check(ModuleResources.accepting && executionToken == generation.get() && !Thread.currentThread().isInterrupted &&
                AccountHostRuntime.isCurrentStorage(account, activity)) { "账号已变化，请重新打开关联" }
            check(property("id") == identity.id && property("uid") == identity.uid &&
                property("uuid") == identity.uuid && selection.file.isFile) { "关联图书或封面草稿已变化" }
        }
        checkCurrent()
        val oldCover = (property("cover") as? String).orEmpty()
        val plan = AccountBookCoverFiles.nativePlan(loader, oldCover, "cover.${selection.file.extension}")
        val directory = AccountBookImageFiles.directory(activity.filesDir, identity.uid, identity.uuid)
        val target = AccountBookImageFiles.child(directory, plan.path)
        fun validatePath() {
            check(AccountBookImageFiles.directory(activity.filesDir, identity.uid, identity.uuid) == directory &&
                AccountBookImageFiles.child(directory, plan.path) == target) { "图书封面目录已变化" }
        }
        fun commit(action: () -> Unit) {
            AccountHostRuntime.withCurrentStorage(account, activity) { checkCurrent(); action() }
        }
        if (!directory.isDirectory) commit { check(directory.isDirectory || directory.mkdirs()) { "图书目录不可用" } }
        AccountBookCoverFiles.prepare(directory, plan, ::checkCurrent, ::commit)
        val publication = ManualAssociationCoverPublication.publish(selection.file, target, ::checkCurrent, ::commit, ::validatePath)
        return try {
            checkCurrent()
            HostNativeCoverCache.invalidate(activity, identity.uuid, plan.path)
            // The native caller persists this returned book through its existing bookshelf.update.
            Published(host.copy(book, mapOf("cover" to plan.path, "updated" to System.currentTimeMillis())),
                publication, plan.path)
        } catch (error: Throwable) {
            runCatching { publication.finish(false) }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun readImage(request: Request, uri: Uri): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        request.activity.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(current(request)) { "账号或关联页面已变化" }
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMAGE_BYTES) { "图片过大" }
                output.write(buffer, 0, count)
            }
        } ?: error("无法读取所选图片")
        return output.toByteArray().also { bytes ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "无法识别所选图片" }
        }
    }

    private companion object {
        const val REQUEST_COVER = 0x4D50
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    }
}
