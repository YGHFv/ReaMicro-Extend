package com.reamicro.fix.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 某一时刻的成书样式配置：内置库与用户改写合并后的全部样式，以及各类当前选中项。 */
data class OnlineEpubStyleSettings(
    val styles: List<OnlineEpubStyle> = OnlineEpubStyleLibrary.BUILT_INS,
    val selection: Map<OnlineEpubStyleKind, String> = emptyMap(),
) {
    fun byKind(kind: OnlineEpubStyleKind): List<OnlineEpubStyle> = styles.filter { it.kind == kind }

    fun selectedId(kind: OnlineEpubStyleKind): String =
        selection[kind]?.takeIf { id -> styles.any { it.id == id } }
            ?: OnlineEpubStyleDefaults.defaultStyleId(kind)

    fun selected(kind: OnlineEpubStyleKind): OnlineEpubStyle? {
        val id = selectedId(kind)
        return styles.firstOrNull { it.id == id } ?: byKind(kind).firstOrNull()
    }
}

/**
 * 在线补全成书样式的持久化。
 *
 * 内置样式不落盘，只有用户新建或改写过的样式写进 SharedPreferences，读取时按 id 覆盖内置库；
 * 这样内置样式随模块升级自动更新，用户的改动也不会丢。
 */
object OnlineEpubStyleStore {
    fun read(context: Context?): OnlineEpubStyleSettings {
        context ?: return OnlineEpubStyleSettings()
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = decodeStyles(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, "").orEmpty())
        val removed = decodeIds(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, "").orEmpty())
        val merged = LinkedHashMap<String, OnlineEpubStyle>()
        OnlineEpubStyleLibrary.BUILT_INS.forEach { merged[it.id] = it }
        overrides.forEach { style ->
            // 改写过的内置样式保留 builtIn 标记，删除按钮据此隐藏。
            merged[style.id] = style.copy(builtIn = merged[style.id]?.builtIn ?: false)
        }
        removed.forEach(merged::remove)
        return OnlineEpubStyleSettings(
            styles = merged.values.toList(),
            selection = decodeSelection(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLE_SELECTION, "").orEmpty()),
        )
    }

    /** 新建或改写一条样式。 */
    fun save(context: Context?, style: OnlineEpubStyle) {
        context ?: return
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = decodeStyles(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, "").orEmpty())
            .filterNot { it.id == style.id } + style
        val removed = decodeIds(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, "").orEmpty()) - style.id
        prefs.edit()
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, encodeStyles(overrides))
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, encodeIds(removed))
            .commit()
    }

    /**
     * 删除一条样式。
     *
     * 内置样式无法真正从库里去掉，改为记进删除名单；再次保存同 id 会自动从名单中移除。
     */
    fun remove(context: Context?, styleId: String) {
        context ?: return
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = decodeStyles(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, "").orEmpty())
            .filterNot { it.id == styleId }
        val removed = decodeIds(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, "").orEmpty()) + styleId
        prefs.edit()
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, encodeStyles(overrides))
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, encodeIds(removed))
            .commit()
    }

    fun select(context: Context?, kind: OnlineEpubStyleKind, styleId: String) {
        context ?: return
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val selection = decodeSelection(
            prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLE_SELECTION, "").orEmpty(),
        ) + (kind to styleId)
        prefs.edit()
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLE_SELECTION, encodeSelection(selection))
            .commit()
    }

    fun styleJson(style: OnlineEpubStyle): JSONObject =
        JSONObject()
            .put("id", style.id)
            .put("kind", style.kind.id)
            .put("name", style.name)
            .put("description", style.description)
            .put("css", style.css)
            .put("fontFamily", style.fontFamily)

    fun styleFromJson(json: JSONObject): OnlineEpubStyle? {
        val kind = OnlineEpubStyleKind.fromId(json.optString("kind")) ?: return null
        val id = json.optString("id").trim().ifBlank { return null }
        return OnlineEpubStyle(
            id = id,
            kind = kind,
            name = json.optString("name").trim().ifBlank { id },
            description = json.optString("description"),
            css = json.optString("css"),
            fontFamily = json.optString("fontFamily"),
        )
    }

    private fun encodeStyles(styles: List<OnlineEpubStyle>): String =
        JSONArray().apply { styles.forEach { put(styleJson(it)) } }.toString()

    private fun decodeStyles(raw: String): List<OnlineEpubStyle> =
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::styleFromJson)
            }
        }.getOrDefault(emptyList())

    private fun encodeIds(ids: Collection<String>): String =
        JSONArray().apply { ids.distinct().forEach(::put) }.toString()

    private fun decodeIds(raw: String): List<String> =
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())

    private fun encodeSelection(selection: Map<OnlineEpubStyleKind, String>): String =
        JSONObject().apply { selection.forEach { (kind, id) -> put(kind.id, id) } }.toString()

    private fun decodeSelection(raw: String): Map<OnlineEpubStyleKind, String> =
        runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val kind = OnlineEpubStyleKind.fromId(key) ?: return@forEach
                    json.optString(key).takeIf { it.isNotBlank() }?.let { put(kind, it) }
                }
            }
        }.getOrDefault(emptyMap())
}
