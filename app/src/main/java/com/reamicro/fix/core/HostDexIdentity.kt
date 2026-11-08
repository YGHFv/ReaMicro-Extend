package com.reamicro.fix.core

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 校验缓存绑定真实 DEX 字节及本代模块的映射、预期结构，不依赖文件大小或安装路径。 */
internal object HostDexIdentity {
    fun key(apk: File, profile: String, classes: Map<String, String>, shapes: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        text("host-verdict-v2")
        text(profile)
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) }
                .sortedBy { it.name }.toList()
            require(entries.isNotEmpty()) { "Host APK contains no DEX" }
            entries.forEach { entry ->
                text(entry.name)
                digest.update(ByteBuffer.allocate(8).putLong(entry.size).array())
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        for (mapping in listOf(classes, shapes)) {
            digest.update(ByteBuffer.allocate(4).putInt(mapping.size).array())
            mapping.toSortedMap().forEach { (name, value) -> text(name); text(value) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun validCounts(total: Int, ok: Int, failed: Set<String>, expected: Set<String>): Boolean =
        total > 0 && total == expected.size && ok in 0..total &&
            failed.size == total - ok && expected.containsAll(failed)
}
