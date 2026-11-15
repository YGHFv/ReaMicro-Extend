package com.reamicro.fix.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** One bounded current-host snapshot. The owner serializes access across processes and logs I/O failures. */
internal class HostQueryCache(file: File, private val identity: String) {
    private data class Entry(val values: Map<String, String>, val encodedBytes: Int)
    private val file = file.absoluteFile
    private val entries = linkedMapOf<String, Entry>()
    private var dirty = false
    private var encodedBytes = 0

    init {
        encodedBytes = 24 + checkString(identity, "identity", allowEmpty = false).size
        read()?.let { bytes ->
            val (savedIdentity, savedEntries) = decode(bytes)
            if (savedIdentity == identity) {
                entries.putAll(savedEntries)
                encodedBytes += savedEntries.values.sumOf { it.encodedBytes }
            } else dirty = true
        }
    }

    @Synchronized fun get(key: String): Map<String, String>? =
        entries[key]?.values?.let { LinkedHashMap(it) }

    /** Validation precedes mutation, including the size of the complete resulting snapshot. */
    @Synchronized fun put(key: String, values: Map<String, String>) {
        var entryBytes = 8L + checkString(key, "feature key", allowEmpty = false).size
        if (values.size > MAX_VALUES_PER_ENTRY) throw IOException("Too many query values: ${values.size}")
        val snapshot = linkedMapOf<String, String>()
        values.forEach { (name, value) ->
            entryBytes += 8L + checkString(name, "value key", allowEmpty = false).size +
                checkString(value, "value", allowEmpty = true).size
            if (entryBytes > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
            snapshot[name] = value
        }
        val previous = entries[key]
        val entry = Entry(snapshot, entryBytes.toInt())
        if (previous == entry) return
        if (previous == null && entries.size == MAX_ENTRIES) throw IOException("Too many query-cache entries")
        val resultingBytes = encodedBytes.toLong() - (previous?.encodedBytes ?: 0) + entryBytes
        if (resultingBytes > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
        // Account only for the changed group. Encoding every preceding group here
        // made a complete cold bootstrap quadratic even when writes were batched.
        entries[key] = entry
        encodedBytes = resultingBytes.toInt()
        dirty = true
    }

    @Synchronized fun remove(key: String) {
        entries.remove(key)?.let {
            encodedBytes -= it.encodedBytes
            dirty = true
        }
    }

    @Synchronized fun retain(keys: Set<String>) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) {
                encodedBytes -= entry.value.encodedBytes
                iterator.remove()
                dirty = true
            }
        }
    }

    /** A failed write preserves both the old target and dirty in-memory state for a later retry. */
    @Synchronized fun flush() {
        if (!dirty) return
        val bytes = encode(entries)
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create query-cache directory: $parent")
        }
        val pending = File.createTempFile(".host-query-", ".tmp", parent)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Same-directory atomic rename: never expose a partly written cache or delete the old one first.
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            dirty = false
        } finally {
            pending.delete()
        }
    }

    private fun read(): ByteArray? {
        val input = try { Files.newInputStream(file.toPath()) } catch (_: NoSuchFileException) { return null }
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun encode(values: Map<String, Entry>): ByteArray {
        if (values.size > MAX_ENTRIES) throw IOException("Too many query-cache entries: ${values.size}")
        val strings = HashMap<String, ByteArray>()
        fun bytes(text: String, label: String, allowEmpty: Boolean): ByteArray = strings.getOrPut(text) {
            checkString(text, label, allowEmpty)
        }.also { if (!allowEmpty && it.isEmpty()) throw IOException("Empty $label") }
        var size = 4L + 4L + 4L + bytes(identity, "identity", false).size + 4L + 8L
        values.forEach { (key, entry) ->
            size += 4L + bytes(key, "feature key", false).size + 4L
            if (entry.values.size > MAX_VALUES_PER_ENTRY) throw IOException("Too many query values")
            entry.values.forEach { (name, value) ->
                size += 4L + bytes(name, "value key", false).size + 4L + bytes(value, "value", true).size
                if (size > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
            }
            if (size > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
        }
        if (size > MAX_FILE_BYTES) throw IOException("Query cache exceeds $MAX_FILE_BYTES bytes")
        val output = ByteArrayOutputStream(size.toInt())
        DataOutputStream(output).use { data ->
            fun string(text: String) { val raw = strings.getValue(text); data.writeInt(raw.size); data.write(raw) }
            data.writeInt(MAGIC)
            data.writeInt(SCHEMA)
            string(identity)
            data.writeInt(values.size)
            values.toSortedMap().forEach { (key, entry) ->
                string(key)
                data.writeInt(entry.values.size)
                entry.values.toSortedMap().forEach { (name, value) -> string(name); string(value) }
            }
            val payload = output.toByteArray()
            data.writeLong(CRC32().apply { update(payload) }.value)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Pair<String, LinkedHashMap<String, Entry>> {
        if (bytes.size < 24) throw IOException("Truncated query cache")
        val payloadSize = bytes.size - 8
        val checksum = DataInputStream(ByteArrayInputStream(bytes, payloadSize, 8)).readLong()
        if (CRC32().apply { update(bytes, 0, payloadSize) }.value != checksum) throw IOException("Query-cache checksum mismatch")
        return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { data ->
            if (data.readInt() != MAGIC) throw IOException("Unknown query-cache format")
            if (data.readInt() != SCHEMA) throw IOException("Unsupported query-cache schema")
            val savedIdentity = readString(data, allowEmpty = false)
            val count = data.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Invalid query-cache entry count: $count")
            val result = linkedMapOf<String, Entry>()
            repeat(count) {
                val remaining = data.available()
                val key = readString(data, allowEmpty = false)
                if (result.containsKey(key)) throw IOException("Duplicate query-cache feature key")
                val valueCount = data.readInt()
                if (valueCount !in 0..MAX_VALUES_PER_ENTRY) throw IOException("Invalid query value count: $valueCount")
                val values = linkedMapOf<String, String>()
                repeat(valueCount) {
                    val name = readString(data, allowEmpty = false)
                    val value = readString(data, allowEmpty = true)
                    if (values.put(name, value) != null) throw IOException("Duplicate query-cache value key")
                }
                result[key] = Entry(values, remaining - data.available())
            }
            if (data.available() != 0) throw IOException("Trailing query-cache bytes")
            savedIdentity to result
        }
    }

    private fun readString(data: DataInputStream, allowEmpty: Boolean): String {
        val length = data.readInt()
        if (length !in 0..MAX_STRING_BYTES || (!allowEmpty && length == 0) || length > data.available()) {
            throw IOException("Invalid query-cache string length: $length")
        }
        val raw = ByteArray(length)
        data.readFully(raw)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
    }

    private fun checkString(value: String, label: String, allowEmpty: Boolean): ByteArray {
        if ((!allowEmpty && value.isEmpty()) || value.length > MAX_STRING_BYTES) throw IOException("Invalid $label length")
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        if (encoded.remaining() > MAX_STRING_BYTES) throw IOException("$label exceeds $MAX_STRING_BYTES bytes")
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    internal companion object {
        const val MAX_ENTRIES = 256
        const val MAX_VALUES_PER_ENTRY = 4096
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_STRING_BYTES = 64 * 1024
        private const val MAGIC = 0x524D5143 // RMQC
        private const val SCHEMA = 2
    }
}
