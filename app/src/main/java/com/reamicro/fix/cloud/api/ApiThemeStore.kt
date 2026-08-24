package com.reamicro.fix.cloud.api

import android.content.Context
import android.graphics.Color
import java.io.File
import org.json.JSONObject

data class ApiThemePalette(
    val pageBackground: Int,
    val rowBackground: Int,
    val border: Int,
    val title: Int,
    val body: Int,
    val primary: Int,
    val primarySoft: Int,
    val primaryText: Int,
    val neutralText: Int,
    val destructiveText: Int,
)

object ApiThemeStore {
    private const val PREFS = "reamicro_api_themes"
    private const val KEY_ACTIVE = "active_theme"
    private const val ROOT = "reamicro_api_themes"

    fun install(context: Context, bytes: ByteArray, fallbackId: String): String {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val id = json.optString("id").trim().ifBlank { fallbackId }
        require(id.matches(Regex("[A-Za-z0-9_.-]+"))) { "主题 ID 无效" }
        require(json.optJSONObject("light") != null || json.optJSONObject("dark") != null) { "主题缺少 light/dark 配色" }
        val root = File(context.filesDir, ROOT).apply { mkdirs() }
        val target = File(root, "$id.json")
        val temp = File(root, ".$id.tmp")
        temp.writeBytes(bytes)
        require(temp.renameTo(target)) { "主题文件写入失败" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, "").isNullOrBlank()) prefs.edit().putString(KEY_ACTIVE, id).apply()
        return id
    }

    fun activate(context: Context, id: String) {
        require(File(context.filesDir, "$ROOT/$id.json").isFile) { "主题不存在" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun remove(context: Context, id: String): Boolean {
        val target = File(context.filesDir, "$ROOT/$id.json")
        val removed = !target.exists() || target.delete()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, "") == id) prefs.edit().remove(KEY_ACTIVE).apply()
        return removed
    }

    fun palette(context: Context, dark: Boolean): ApiThemePalette? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "").orEmpty()
        if (id.isBlank()) return null
        val file = File(context.filesDir, "$ROOT/$id.json")
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val colors = root.optJSONObject(if (dark) "dark" else "light")
                ?: root.optJSONObject(if (dark) "light" else "dark")
                ?: return null
            ApiThemePalette(
                pageBackground = colors.color("pageBackground"),
                rowBackground = colors.color("rowBackground"),
                border = colors.color("border"),
                title = colors.color("title"),
                body = colors.color("body"),
                primary = colors.color("primary"),
                primarySoft = colors.color("primarySoft"),
                primaryText = colors.color("primaryText"),
                neutralText = colors.color("neutralText"),
                destructiveText = colors.color("destructiveText"),
            )
        }.getOrNull()
    }

    private fun JSONObject.color(name: String): Int {
        val value = optString(name).trim()
        require(value.isNotBlank()) { "主题缺少颜色 $name" }
        return Color.parseColor(value)
    }
}
