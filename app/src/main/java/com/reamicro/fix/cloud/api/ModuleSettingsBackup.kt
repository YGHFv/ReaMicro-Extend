package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.settings.ModuleSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ModuleSettingsBackup {
    fun create(context: Context): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest = JSONObject()
                .put("backupVersion", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("modulePackage", "com.reamicro.fix")
                .put("sections", JSONArray(listOf("module_settings", "online_sources")))
            zip.writeEntry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val settings = JSONObject()
            prefs.all.forEach { (key, value) -> settings.put(key, encodePreference(value)) }
            zip.writeEntry("module-settings.json", settings.toString(2).toByteArray(Charsets.UTF_8))
            File(context.filesDir, "reamicro_online_sources").listFiles()?.filter(File::isFile)?.forEach { file ->
                zip.writeEntry("online-sources/${file.name}", file.readBytes())
            }
        }
        return output.toByteArray()
    }

    fun restore(context: Context, bytes: ByteArray): Int {
        val entries = readEntries(bytes)
        val manifest = JSONObject(entries["manifest.json"]?.toString(Charsets.UTF_8) ?: error("备份清单缺失"))
        require(manifest.optInt("backupVersion") == 1) { "不支持的备份版本" }
        val settingsJson = JSONObject(entries["module-settings.json"]?.toString(Charsets.UTF_8) ?: "{}")
        val editor = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
        var restored = 0
        settingsJson.keys().forEach { key ->
            val item = settingsJson.optJSONObject(key) ?: return@forEach
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                "int" -> editor.putInt(key, item.optInt("value"))
                "long" -> editor.putLong(key, item.optLong("value"))
                "float" -> editor.putFloat(key, item.optDouble("value").toFloat())
                "string" -> editor.putString(key, item.optString("value"))
                "string_set" -> editor.putStringSet(key, item.optJSONArray("value").toStringSet())
                else -> return@forEach
            }
            restored++
        }
        editor.commit()
        val sourceDir = File(context.filesDir, "reamicro_online_sources").apply { mkdirs() }.canonicalFile
        entries.filterKeys { it.startsWith("online-sources/") }.forEach { (name, body) ->
            val fileName = name.substringAfterLast('/')
            if (!fileName.matches(Regex("[A-Za-z0-9_.-]+"))) return@forEach
            val target = File(sourceDir, fileName).canonicalFile
            if (target.parentFile == sourceDir) target.writeBytes(body)
        }
        return restored
    }

    private fun encodePreference(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> put("type", "boolean").put("value", value)
            is Int -> put("type", "int").put("value", value)
            is Long -> put("type", "long").put("value", value)
            is Float -> put("type", "float").put("value", value.toDouble())
            is String -> put("type", "string").put("value", value)
            is Set<*> -> put("type", "string_set").put("value", JSONArray(value.filterIsInstance<String>()))
            else -> put("type", "unsupported")
        }
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith("/") && ".." !in name.split('/')) { "备份包含不安全路径" }
                val body = zip.readBytes()
                total += body.size
                require(total <= 50L * 1024L * 1024L) { "备份解压后过大" }
                put(name, body)
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}

private fun JSONArray?.toStringSet(): Set<String> = buildSet {
    val array = this@toStringSet ?: return@buildSet
    for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
}
