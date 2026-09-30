package com.reamicro.fix.epub.editor

import java.io.FilterOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Deliberately excludes CSS, OPF, images and fonts: those retain the host-style viewers. */
internal fun usesScriptaEpubEditor(path: String): Boolean {
    val name = path.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "")
    return extension in setOf("html", "xhtml", "ncx", "toc") || name == "toc" || name == "toc.xml"
}

internal data class EpubTextFormat(val charset: Charset, val bom: ByteArray)
internal data class EpubFileFingerprint(val size: Long, val modified: Long, val sha256: String)
internal data class EpubTextSnapshot(val format: EpubTextFormat, val fingerprint: EpubFileFingerprint)
internal data class LoadedEpubText(val text: String, val snapshot: EpubTextSnapshot)

internal object EpubTextFiles {
    private const val MIB = 1024L * 1024L

    fun resolve(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "文件路径为空" }
        val parent = root.canonicalFile
        val file = File(parent, relativePath).canonicalFile
        require(file.toPath().startsWith(parent.toPath()) && file != parent) { "文件路径超出图书目录" }
        require(file.isFile) { "文件不存在或不是普通文件" }
        return file
    }

    /** Bounds memory, rather than keeping the previous WebView's arbitrary 5 MB limit. */
    fun availableLoadBudget(): Long {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        return minOf(64 * MIB, available / 8).coerceAtLeast(0L)
    }

    fun load(file: File, maxBytes: Long = availableLoadBudget(), checkCancelled: () -> Unit = {}): LoadedEpubText {
        require(file.isFile) { "文件不存在" }
        val size = file.length()
        val modified = file.lastModified()
        require(size <= maxBytes) {
            "文件超过当前可用内存的安全范围（约 ${maxBytes / MIB} MB），未修改原文件"
        }
        val prefix = file.inputStream().use { input ->
            val bytes = ByteArray(minOf(size, 4096L).toInt())
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            bytes.copyOf(count)
        }
        val format = detectFormat(prefix)
        val decoder = format.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val digest = MessageDigest.getInstance("SHA-256")
        val builder = StringBuilder(minOf(size, MIB).toInt())
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            repeat(format.bom.size) { require(input.read() >= 0) { "文件在读取时发生变化" } }
            InputStreamReader(input, decoder).use { reader ->
                val buffer = CharArray(32 * 1024)
                while (true) {
                    checkCancelled()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(builder.length.toLong() + count <= maxBytes) { "文件在读取时超出安全范围" }
                    builder.append(buffer, 0, count)
                }
            }
        }
        require(file.length() == size && file.lastModified() == modified) { "文件在读取时被修改，请重新打开" }
        return LoadedEpubText(builder.toString(), EpubTextSnapshot(format, EpubFileFingerprint(size, modified, digest.digest().hex())))
    }

    fun save(file: File, text: String, snapshot: EpubTextSnapshot, checkCancelled: () -> Unit = {}): EpubTextSnapshot {
        requireCurrent(file, snapshot.fingerprint, checkCancelled)
        val parent = requireNotNull(file.parentFile)
        val temporary = File.createTempFile(".reamicro-scripta-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val encoder = snapshot.format.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            temporary.outputStream().use { stream ->
                val output = DigestOutputStream(object : FilterOutputStream(stream) {
                    // Let the encoder finish/validate without closing fd before fsync.
                    override fun close() { flush() }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        stream.write(bytes, offset, length)
                    }
                }, digest)
                output.write(snapshot.format.bom)
                val writer = OutputStreamWriter(output, encoder)
                var offset = 0
                while (offset < text.length) {
                    checkCancelled()
                    val count = minOf(32 * 1024, text.length - offset)
                    writer.write(text, offset, count)
                    offset += count
                }
                // Closing the encoder validates incomplete surrogate pairs as well.
                writer.close()
                stream.fd.sync()
            }
            runCatching { Files.setPosixFilePermissions(temporary.toPath(), Files.getPosixFilePermissions(file.toPath())) }
            checkCancelled()
            requireCurrent(file, snapshot.fingerprint, checkCancelled)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return EpubTextSnapshot(snapshot.format, EpubFileFingerprint(file.length(), file.lastModified(), digest.digest().hex()))
        } finally {
            temporary.delete()
        }
    }

    private fun requireCurrent(file: File, expected: EpubFileFingerprint, checkCancelled: () -> Unit) {
        require(file.isFile && file.length() == expected.size) { "文件已被其他操作修改，保存已取消，请重新打开" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        require(digest.digest().hex() == expected.sha256) { "文件已被其他操作修改，保存已取消，请重新打开" }
    }

    private fun detectFormat(prefix: ByteArray): EpubTextFormat {
        fun starts(vararg bytes: Int) = prefix.size >= bytes.size &&
            bytes.indices.all { (prefix[it].toInt() and 0xff) == bytes[it] }
        return when {
            starts(0xff, 0xfe, 0x00, 0x00) -> EpubTextFormat(Charset.forName("UTF-32LE"), prefix.copyOf(4))
            starts(0x00, 0x00, 0xfe, 0xff) -> EpubTextFormat(Charset.forName("UTF-32BE"), prefix.copyOf(4))
            starts(0xef, 0xbb, 0xbf) -> EpubTextFormat(StandardCharsets.UTF_8, prefix.copyOf(3))
            starts(0xff, 0xfe) -> EpubTextFormat(StandardCharsets.UTF_16LE, prefix.copyOf(2))
            starts(0xfe, 0xff) -> EpubTextFormat(StandardCharsets.UTF_16BE, prefix.copyOf(2))
            starts(0x3c, 0x00, 0x3f, 0x00) -> EpubTextFormat(StandardCharsets.UTF_16LE, byteArrayOf())
            starts(0x00, 0x3c, 0x00, 0x3f) -> EpubTextFormat(StandardCharsets.UTF_16BE, byteArrayOf())
            else -> {
                val header = String(prefix, StandardCharsets.ISO_8859_1)
                val name = Regex("""(?is)^\s*<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""")
                    .find(header)?.groupValues?.get(1)
                val charset = if (name == null) StandardCharsets.UTF_8 else Charset.forName(name)
                EpubTextFormat(charset, byteArrayOf())
            }
        }
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
