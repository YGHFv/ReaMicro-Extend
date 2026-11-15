package com.reamicro.fix.hook

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Private draft files never become book assets until a native association is confirmed. */
internal class ManualAssociationCoverStore(cache: File) {
    private data class Entry(val file: File, var retained: Boolean = true, var readers: Int = 0)
    private val directory = File(cache, "reamicro-manual-cover-${UUID.randomUUID()}")
    private val entries = linkedMapOf<String, Entry>()

    class Selection internal constructor(val key: String, val file: File, private val release: () -> Unit) : Closeable {
        private var closed = false
        private var published = false
        @Synchronized fun <T> publishOnce(action: (File) -> T): T {
            check(!closed && !published && file.isFile) { "封面草稿已变化，请重新确认" }
            published = true
            return action(file)
        }
        @Synchronized override fun close() {
            if (!closed) { closed = true; release() }
        }
    }

    fun stage(bytes: ByteArray, extension: String): String {
        require(bytes.isNotEmpty()) { "图片为空" }
        require(extension.matches(Regex("[a-z0-9]{2,5}")))
        val id = UUID.randomUUID().toString()
        val file = synchronized(this) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建封面草稿" }
            File(directory, "$id.$extension").also { check(it.createNewFile()) { "无法创建封面草稿" } }
        }
        // Image I/O must not hold the monitor used by UI cancellation and candidate rendering.
        try { file.outputStream().use { it.write(bytes) } }
        catch (error: Throwable) { file.delete(); throw error }
        val key = "$PREFIX$id"
        synchronized(this) { entries[key] = Entry(file) }
        return key
    }

    @Synchronized fun acquire(key: String): Selection? {
        val entry = entries[key]?.takeIf { it.file.isFile } ?: return null
        entry.readers++
        return Selection(key, entry.file) {
            synchronized(this) {
                entry.readers--
                collect(key, entry)
            }
        }
    }

    @Synchronized fun contains(key: String): Boolean = entries[key]?.file?.isFile == true

    @Synchronized fun discard(key: String) {
        entries[key]?.let { it.retained = false; collect(key, it) }
    }

    @Synchronized fun retain(keys: Set<String>) {
        entries.toMap().forEach { (key, entry) ->
            entry.retained = key in keys
            collect(key, entry)
        }
    }

    private fun collect(key: String, entry: Entry) {
        if (entry.retained || entry.readers != 0) return
        if (entry.file.delete() || !entry.file.exists()) entries.remove(key)
        if (entries.isEmpty()) directory.delete()
    }

    companion object { const val PREFIX = "reamicro-cover-draft:" }
}
