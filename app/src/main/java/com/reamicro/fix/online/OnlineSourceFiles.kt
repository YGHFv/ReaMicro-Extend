package com.reamicro.fix.online

import com.reamicro.fix.reload.TaskCancellation
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal const val MAX_ONLINE_SOURCE_BYTES = 16 * 1024 * 1024

internal fun readOnlineSourceBytes(input: java.io.InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        TaskCancellation.checkCurrent()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= MAX_ONLINE_SOURCE_BYTES) { "在线源文件过大（最多 16 MB）" }
        output.write(buffer, 0, count)
    }
}

internal fun downloadOnlineSourceBytes(rawUrl: String): ByteArray {
    var target = java.net.URL(rawUrl)
    repeat(6) { hop ->
        TaskCancellation.checkCurrent()
        require(target.protocol.equals("http", true) || target.protocol.equals("https", true)) { "在线源链接协议不受支持" }
        val connection = target.openConnection() as java.net.HttpURLConnection
        val cancellation = TaskCancellation.current()?.register(connection::disconnect)
        try {
            TaskCancellation.checkCurrent()
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ReaMicro-Extend/online-source")
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                check(hop < 5) { "在线源重定向次数过多" }
                val next = java.net.URL(target, connection.getHeaderField("Location") ?: error("在线源重定向地址为空"))
                check(!target.protocol.equals("https", true) || next.protocol.equals("https", true)) { "在线源重定向不能降级为明文连接" }
                target = next
            } else {
                check(code in 200..299) { "在线源下载失败：HTTP $code" }
                require(connection.contentLengthLong <= MAX_ONLINE_SOURCE_BYTES) { "在线源文件过大" }
                return connection.inputStream.use(::readOnlineSourceBytes)
            }
        } finally {
            cancellation?.close()
            connection.disconnect()
        }
    }
    error("在线源重定向次数过多")
}

internal fun onlineSourceFileName(id: String): String {
    if (id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}"))) return id
    return "online_" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun writeOnlineSourceAtomically(
    target: File,
    bytes: ByteArray,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = TaskCancellation::checkCurrent,
) {
    checkCurrent()
    val parent = requireNotNull(target.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "在线源存储目录不可用" }
    val temporary = File.createTempFile(".online-source-", ".tmp", parent)
    try {
        temporary.outputStream().use { output ->
            var offset = 0
            while (offset < bytes.size) {
                checkCurrent()
                val count = minOf(64 * 1024, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
            output.fd.sync()
        }
        var committed = false
        commit {
            check(!committed) { "在线源已提交" }
            checkCurrent()
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            committed = true
        }
        check(committed) { "在线源未提交，已保留原文件" }
    } finally {
        temporary.delete()
    }
}
