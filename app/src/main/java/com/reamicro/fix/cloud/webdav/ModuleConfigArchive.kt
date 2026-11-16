package com.reamicro.fix.cloud.webdav

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** 可移植配置包保存类型及选中范围，恢复前完整验证大小、路径和配置值。 */
internal object ModuleConfigArchive {
    fun encodePreference(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> put("type", "boolean").put("value", value)
            is Int -> put("type", "int").put("value", value)
            is Long -> put("type", "long").put("value", value)
            is Float -> put("type", "float").put("value", value.toDouble())
            is String -> put("type", "string").put("value", value)
            is Set<*> -> put("type", "string_set").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
            else -> error("不支持的配置类型")
        }
    }
    fun decodePreference(item: JSONObject): Any = when (item.getString("type")) {
        "boolean" -> item.getBoolean("value")
        "int" -> item.getInt("value")
        "long" -> item.getLong("value")
        "float" -> item.getDouble("value").toFloat().also { require(it.isFinite()) }
        "string" -> item.getString("value")
        "string_set" -> item.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        else -> error("不支持的配置类型")
    }
    fun create(manifest: JSONObject, files: Map<String, ByteArray>): ByteArray {
        val entries = mapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8)) + files
        require(entries.values.sumOf { it.size.toLong() } <= ModuleConfigCrypto.MAX_BYTES) { "模块配置备份超过 32 MiB" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.toSortedMap().forEach { (name, body) ->
                require(ModuleConfigPolicy.safePath(name))
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(body); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= ModuleConfigCrypto.MAX_BYTES)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && ModuleConfigPolicy.safePath(entry.name) &&
                    entry.name !in entries && entries.size < 10000) { "模块配置备份路径无效或重复" }
                val body = ByteArrayOutputStream()
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= ModuleConfigCrypto.MAX_BYTES) { "模块配置解压后超过 32 MiB" }
                    body.write(buffer, 0, count)
                }
                entries[entry.name] = body.toByteArray()
            }
        }
        require("manifest.json" in entries) { "模块配置备份清单缺失" }
        return entries
    }
}
