package com.reamicro.fix.cloud.root

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Large replies use an immutable, credential-free snapshot and bounded pipe reads. */
internal object RootTaskSnapshotTransport {
    private const val CHUNK = 512 * 1024
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val PREFIX = ".snapshot-"

    fun prepare(directory: File, snapshot: JSONObject): JSONObject {
        val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Root 快照过大" }
        if (bytes.size <= CHUNK) return snapshot
        directory.listFiles()?.filter { it.name.startsWith(PREFIX) &&
            System.currentTimeMillis() - it.lastModified() > 15 * 60_000L }?.forEach { it.delete() }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()
        require(compressed.size <= MAX_BYTES) { "Root 快照过大" }
        val id = UUID.randomUUID().toString()
        val path = File(directory, PREFIX + id).toPath()
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(path, compressed)
        } catch (error: Throwable) {
            Files.deleteIfExists(path)
            throw error
        }
        return JSONObject().put("schema", RootTaskRepository.SCHEMA)
            .put("snapshotTransfer", id).put("bytes", compressed.size).put("sha256", digest(compressed))
    }

    fun part(directory: File, request: JSONObject): JSONObject {
        val id = request.getString("snapshotTransfer")
        require(id == UUID.fromString(id).toString()) { "Root 快照标识无效" }
        val file = File(directory, PREFIX + id)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Root 快照已过期，请重新同步" }
        val offset = request.getInt("offset")
        val total = file.length()
        require(total in 1..MAX_BYTES.toLong() && offset >= 0 && offset < total) { "Root 快照分段无效" }
        val bytes = ByteArray(minOf(CHUNK.toLong(), total - offset).toInt())
        RandomAccessFile(file, "r").use {
            it.seek(offset.toLong())
            it.readFully(bytes)
        }
        if (offset.toLong() + bytes.size == total) Files.delete(file.toPath())
        return JSONObject().put("schema", RootTaskRepository.SCHEMA)
            .put("snapshotTransfer", id).put("offset", offset)
            .put("data", Base64.getEncoder().encodeToString(bytes))
    }

    fun collect(response: JSONObject, fetch: (JSONObject) -> JSONObject): JSONObject {
        if (!response.has("snapshotTransfer")) return response
        val id = response.getString("snapshotTransfer")
        val total = response.getInt("bytes")
        require(total in 1..MAX_BYTES) { "Root 快照长度无效" }
        val output = ByteArrayOutputStream()
        while (output.size() < total) {
            val offset = output.size()
            val part = fetch(JSONObject().put("snapshotTransfer", id).put("offset", offset))
            check(part.optString("snapshotTransfer") == id && part.getInt("offset") == offset) { "Root 快照分段不匹配" }
            val bytes = Base64.getDecoder().decode(part.getString("data"))
            check(bytes.size == minOf(CHUNK, total - offset)) { "Root 快照分段不完整" }
            output.write(bytes)
        }
        val compressed = output.toByteArray()
        check(digest(compressed) == response.getString("sha256")) { "Root 快照校验失败" }
        val decoded = ByteArrayOutputStream()
        GZIPInputStream(compressed.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(decoded.size() + count <= MAX_BYTES) { "Root 快照过大" }
                decoded.write(buffer, 0, count)
            }
        }
        return JSONObject(decoded.toString("UTF-8")).also {
            check(it.optInt("schema") == RootTaskRepository.SCHEMA) { "Root 快照格式无效" }
        }
    }

    private fun digest(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}
