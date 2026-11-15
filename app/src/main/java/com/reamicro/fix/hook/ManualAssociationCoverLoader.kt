package com.reamicro.fix.hook

import android.app.Activity
import android.graphics.BitmapFactory
import com.reamicro.fix.association.AssociationBookIdentity
import com.reamicro.fix.core.HostBookBindings
import com.reamicro.fix.epub.editor.AccountBookCoverFiles
import com.reamicro.fix.epub.editor.AccountBookImageFiles
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.association.ManualAssociationCoverDownload
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 网络图片先存为临时草稿，只有宿主确认关联后才写入图书封面。 */
internal class ManualAssociationCoverLoader(private val loader: ClassLoader) {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var drafts: ManualAssociationCoverStore? = null
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun install() {
        ModuleResources.cleanup {
            cancel()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    fun invalidateExecution() {
        generation.incrementAndGet()
        client.dispatcher.cancelAll()
    }
    fun executionToken(): Long = generation.get()

    fun cancel() {
        invalidateExecution()
        retain(emptySet())
    }

    fun retain(paths: Set<String>) { drafts?.retain(paths) }
    fun acquire(path: String): ManualAssociationCoverStore.Selection? = drafts?.acquire(path)

    fun imageSource(activity: Activity, identity: AssociationBookIdentity, path: String): java.io.File? = runCatching {
        acquire(path)?.use { return@runCatching it.file }
        if (path.isBlank() || path.contains(':')) return@runCatching null
        val directory = AccountBookImageFiles.directory(activity.filesDir, identity.uid, identity.uuid)
        AccountBookImageFiles.child(directory, path).takeIf { it.isFile }
    }.getOrNull()

    fun download(activity: Activity, url: String, checkOwner: () -> Unit): String? {
        if (url.isBlank()) return null
        val executionToken = generation.get()
        fun checkCurrent() {
            checkOwner()
            check(ModuleResources.accepting && executionToken == generation.get() &&
                !Thread.currentThread().isInterrupted) { "封面下载已取消" }
        }
        checkCurrent()
        val bytes = ManualAssociationCoverDownload.read(client, url, ::checkCurrent)
        checkCurrent()
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }
        require(options.outWidth > 0 && options.outHeight > 0) { "封面地址未返回可识别的图片" }
        val extension = options.outMimeType?.substringAfter('/')?.let {
            when (it) { "jpeg" -> "jpg"; "png", "webp", "gif", "bmp", "avif", "heif", "heic" -> it; else -> "jpg" }
        } ?: "jpg"
        val store = synchronized(this) {
            drafts ?: ManualAssociationCoverStore(activity.cacheDir).also { drafts = it }
        }
        val path = store.stage(bytes, extension)
        try { checkCurrent() } catch (error: Throwable) { store.discard(path); throw error }
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

}
