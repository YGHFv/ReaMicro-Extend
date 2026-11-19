package com.reamicro.fix.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 查询缓存绑定真实 DEX 字节、模块代际和运行环境。 */
internal object HostDexIdentity {
    fun key(apk: File, generation: String): String = key(listOf(apk), generation)

    /** One configured class path. Only a full DEX hash is ever used as a disk cache key. */
    class Installed(private val apks: List<File>) {
        private data class Stamp(val device: Long, val inode: Long, val size: Long, val modified: FileTime, val changed: FileTime)
        private data class Saved(val generation: String, val stamps: List<Stamp>, val key: String)
        private var saved: Saved? = null

        @Synchronized fun key(generation: String): String {
            val before = stamps()
            saved?.let { if (it.generation == generation && it.stamps == before) return it.key }
            saved = null
            val key = HostDexIdentity.key(apks, generation)
            if (before != null) {
                val after = stamps()
                check(after == null || before == after) { "Host APK changed during DEX hashing" }
                if (after != null) saved = Saved(generation, after, key)
            }
            return key
        }

        // Some Android file systems do not expose Unix attributes. Keep hashing there.
        private fun stamps(): List<Stamp>? {
            return try {
                apks.map { file ->
                    val attrs = Files.readAttributes(file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime")
                    val inode = attrs["ino"] as Long
                    if (inode == 0L) return null
                    Stamp(attrs["dev"] as Long, inode, attrs["size"] as Long,
                        attrs["lastModifiedTime"] as FileTime, attrs["ctime"] as FileTime)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Order is the class path supplied by ApplicationInfo, including code-bearing splits. */
    fun key(apks: List<File>, generation: String): String {
        require(apks.isNotEmpty()) { "Host APK path unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
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
