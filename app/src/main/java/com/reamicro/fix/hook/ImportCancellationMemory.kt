package com.reamicro.fix.hook

import com.reamicro.fix.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

internal const val IMPORT_CANCELLATION_TTL_MS = 10 * 60_000L

internal class ImportCancellationMemory(
    private val ttlMs: Long = IMPORT_CANCELLATION_TTL_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val cancelled = ConcurrentHashMap<String, Long>()

    fun remember(keys: Collection<String>): Boolean {
        val valid = keys.filter(String::isNotBlank).distinct()
        if (valid.isEmpty()) return false
        val now = nowProvider()
        valid.forEach { cancelled[it] = now }
        clearExpired()
        return true
    }

    fun isCancelled(keys: Collection<String>): Boolean {
        clearExpired()
        return keys.any { it.isNotBlank() && cancelled.containsKey(it) }
    }

    fun forget(keys: Collection<String>) {
        keys.forEach { cancelled.remove(it) }
    }

    fun clear() {
        cancelled.clear()
    }

    private fun clearExpired() {
        val now = nowProvider()
        cancelled.entries.removeAll { (_, at) -> now - at > ttlMs }
    }
}

internal fun importCancellationKeys(
    uuid: String,
    title: String,
    uri: String,
    fileName: String,
): List<String> = buildList {
    uuid.trim().takeIf { it.isNotBlank() }?.let { add("uuid:$it") }
    title.normalizedCancellationTitle().takeIf { it.isNotBlank() }?.let { add("title:$it") }
    uri.trim().takeIf { it.isNotBlank() }?.let { add("uri:$it") }
    fileName.trim().takeIf { it.isNotBlank() }?.let { add("file:$it") }
}

internal object ModuleImportPrecheck {
    @Volatile private var hook: ReaderImportOverwriteHook? = null

    fun attach(hook: ReaderImportOverwriteHook) {
        this.hook = hook
    }

    fun precheck(epubFile: java.io.File, sourceUri: String): Boolean =
        hook?.precheckModuleImport(epubFile, sourceUri) ?: true
}

internal fun String.normalizedCancellationTitle(): String = trim().replace(Regex("\\s+"), " ")

internal object ImportCancellations {
    val memory = ImportCancellationMemory()

    fun remember(uuid: String, title: String, uri: String, fileName: String, fileSize: Long): Boolean {
        val keys = importCancellationKeys(uuid, title, uri, fileName)
        val ok = memory.remember(keys)
        XposedBridge.log(
            "ReaMicro LSP import cancellation remembered ok=$ok size=$fileSize keys=${keys.joinToString(" ").take(240)}",
        )
        return ok
    }

    fun isCancelled(uuid: String, title: String, uri: String, fileName: String, fileSize: Long): Boolean {
        val keys = importCancellationKeys(uuid, title, uri, fileName)
        val hit = memory.isCancelled(keys)
        XposedBridge.log(
            "ReaMicro LSP import cancellation lookup hit=$hit size=$fileSize keys=${keys.joinToString(" ").take(240)}",
        )
        return hit
    }

}
