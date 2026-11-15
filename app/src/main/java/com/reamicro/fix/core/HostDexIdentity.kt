package com.reamicro.fix.core

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 查询缓存绑定真实 DEX 字节、模块代际和运行环境。 */
internal object HostDexIdentity {
    fun key(apk: File, generation: String): String = key(listOf(apk), generation)

    /** Order is the class path supplied by ApplicationInfo, including code-bearing splits. */
    fun key(apks: List<File>, generation: String): String {
        require(apks.isNotEmpty()) { "Host APK path unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        text("host-query-dex-v3")
        text(generation)
        apks.forEachIndexed { apkIndex, apk ->
            ZipFile(apk).use { zip ->
                val entries = zip.hostDexEntries(requireDex = apkIndex == 0)
                // Resource-only splits cannot change a DEX binding. Code splits do:
                // hash boundaries, order and contents, without pinning an install path.
                if (entries.isNotEmpty()) {
                    text("apk")
                    digest.update(ByteBuffer.allocate(4).putInt(entries.size).array())
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
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
