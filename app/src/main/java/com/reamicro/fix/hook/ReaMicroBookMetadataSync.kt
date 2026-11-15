package com.reamicro.fix.hook

import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.core.HostReaderPositionBindings
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference

internal object ReaMicroBookMetadataSync {
    private const val LOG_PREFIX = "ReaMicro LSP"
    private const val ONLINE_COMPLETION_UUID_PREFIX = "reamicro-online-"
    private val host get() = requireNotNull(HostReaderExtrasBindings.importMetadata) { "Import metadata contract unavailable" }
    @Volatile private var bookshelfRepositoryRef: WeakReference<Any>? = null

    fun requireAvailable() {
        val copy = requireNotNull(HostReaderPositionBindings.current) { "Exact book copy contract unavailable" }
        check(copy.type("book") == host.type("book"))
    }

    fun rememberBookshelfRepository(repository: Any?) {
        if (repository == null) return
        bookshelfRepositoryRef = WeakReference(repository)
    }

    fun currentBookshelfRepository(): Any? = bookshelfRepositoryRef?.get()

    fun metadataFromOpf(opf: Any?, root: Any? = null): BookMetadataPatch {
        if (opf == null) return BookMetadataPatch()
        val metadata = requireNotNull(host.read("opf.metadata", opf))
        val uuid = host.read("identifier.value", host.read("metadata.uuid", metadata)) as? String
        val metas = host.read("metadata.metas", metadata) as? Iterable<*>
        val online = uuid.orEmpty().startsWith(ONLINE_COMPLETION_UUID_PREFIX) || (metas?.any { meta ->
            listOf("property", "id").any { name -> host.read("meta.$name", meta) == "reamicro-online-source-id" }
        } == true)
        val authors = host.read("metadata.authors", metadata) as? Iterable<*>
        return BookMetadataPatch(
            title = (host.read("title.value", host.read("metadata.title", metadata)) as? String)?.takeIf(String::isNotBlank),
            subtitle = host.read("title.value", host.read("metadata.subtitle", metadata)) as? String,
            author = authors?.mapNotNull { (host.read("creator.name", it) as? String)?.takeIf(String::isNotBlank) }
                ?.joinToString(", ")?.takeIf(String::isNotBlank),
            cover = if (root == null) null else (host.method("cover").invoke(opf, root) as? String)?.takeIf(String::isNotBlank),
            publisher = if (online) "" else host.read("publisher.name", host.read("metadata.publisher", metadata)) as? String,
        )
    }

    @Synchronized
    fun syncBookMetadata(
        repository: Any?, book: Any?, patch: BookMetadataPatch, checkCurrent: () -> Unit = {},
    ): Boolean = runCatching {
        checkCurrent()
        requireAvailable()
        val target = repository ?: currentBookshelfRepository() ?: return@runCatching false
        val original = book ?: return@runCatching false
        val source = latestBook(target, original) ?: error("更新前未读到书架记录")
        checkCurrent()
        val changes = patch.changes() + ("updated" to System.currentTimeMillis())
        val updated = requireNotNull(HostReaderPositionBindings.current).copy("book", source, changes)
        checkCurrent()
        HostCoroutines.invokeTimed(host.type("repository").classLoader, host.method("update"), target, 30_000L, updated)
        checkCurrent()
        val stored = latestBook(target, original) ?: error("更新后未读到书架记录")
        checkCurrent()
        check(verifyPatch(stored, source, patch)) { "书架回读字段与提交值不同" }
        HookRuntime.log("$LOG_PREFIX synced book metadata fields=${patch.changes().keys.sorted().joinToString(",")}")
        true
    }.onFailure { HookRuntime.logError("$LOG_PREFIX failed to sync book metadata", it) }.getOrDefault(false)

    internal fun latestBook(repository: Any, book: Any): Any? = runCatching {
        val id = (host.read("book.id", book) as Number).toLong()
        if (id <= 0L) return@runCatching null
        val dao = requireNotNull(host.read("repository.dao", repository))
        HostCoroutines.invokeTimed(host.type("repository").classLoader, host.method("find"), dao, 30_000L, id)
            ?.also { require(host.type("book").isInstance(it)) }
            ?.takeIf { stored -> listOf("id", "uid", "uuid").all { host.read("book.$it", stored) == host.read("book.$it", book) } }
    }.onFailure { HookRuntime.logError("$LOG_PREFIX failed to reload book metadata target", it) }.getOrNull()

    internal fun verifyPatch(stored: Any, original: Any, patch: BookMetadataPatch): Boolean = runCatching {
        val id = (host.read("book.id", original) as Number).toLong()
        id > 0L && listOf("id", "uuid", "uid").all { host.read("book.$it", stored) == host.read("book.$it", original) } &&
            patch.changes().all { (name, expected) -> host.read("book.$name", stored) == expected }
    }.getOrDefault(false)

}
