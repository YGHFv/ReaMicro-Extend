package com.reamicro.fix.settings

import android.content.Context
import android.util.Base64
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 某一时刻的成书样式配置：内置库与用户改写合并后的全部样式，以及各类当前选中项。 */
data class OnlineEpubStyleSettings(
    val styles: List<OnlineEpubStyle> = OnlineEpubStyleLibrary.BUILT_INS,
    val selection: Map<OnlineEpubStyleKind, String> = emptyMap(),
    val headerScope: OnlineEpubHeaderScope = OnlineEpubHeaderScope.Off,
) {
    fun byKind(kind: OnlineEpubStyleKind): List<OnlineEpubStyle> = styles.filter { it.kind == kind }

    fun selectedId(kind: OnlineEpubStyleKind): String =
        selection[kind]?.takeIf { id -> styles.any { it.id == id } }
            ?: OnlineEpubStyleDefaults.defaultStyleId(kind)

    fun selected(kind: OnlineEpubStyleKind): OnlineEpubStyle? {
        val id = selectedId(kind)
        return styles.firstOrNull { it.id == id } ?: byKind(kind).firstOrNull()
    }

    /** 头图是否真正参与成书：范围没关且已选好图。 */
    val headerEnabled: Boolean
        get() = headerScope != OnlineEpubHeaderScope.Off &&
            selected(OnlineEpubStyleKind.Header)?.assetPath?.isNotBlank() == true

    /**
     * 用编辑中的草稿样式覆盖同 id 项并选中它。
     *
     * 供配置弹窗预览使用：预览注入的 CSS 与这份设置写出的成书 CSS 完全一致。
     */
    fun withDraft(style: OnlineEpubStyle): OnlineEpubStyleSettings {
        val replaced = styles.filterNot { it.id == style.id } + style
        return copy(
            styles = replaced,
            selection = selection + (style.kind to style.id),
        )
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
            headerScope = OnlineEpubHeaderScope.fromId(
                prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_HEADER_SCOPE, "").orEmpty(),
            ) ?: OnlineEpubHeaderScope.Off,
        )
    }

    fun setHeaderScope(context: Context?, scope: OnlineEpubHeaderScope) {
        context ?: return
        context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ModuleSettings.KEY_ONLINE_EPUB_HEADER_SCOPE, scope.id)
            .commit()
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

    /** 丢弃某条内置样式的改写记录与删除标记，恢复内置库里的原样。 */
    fun resetToBuiltIn(context: Context?, styleId: String) {
        context ?: return
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = decodeStyles(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, "").orEmpty())
            .filterNot { it.id == styleId }
        val removed = decodeIds(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, "").orEmpty()) - styleId
        prefs.edit()
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, encodeStyles(overrides))
            .putString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, encodeIds(removed))
            .commit()
    }

    fun select(context: Context?, kind: OnlineEpubStyleKind, styleId: String) {        context ?: return
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
            .put("embedFont", style.embedFont)
            .put("assetPath", style.assetPath)
            .put("maskAsset", style.maskAsset)
            .put("sampleWidth", style.sampleWidth)
            .put("sampleHeight", style.sampleHeight)

    /**
     * 导出用 JSON：把关联图片以 base64 内嵌，别人导入后开箱即用。
     *
     * 字体因体积过大只导出选择路径，导入方没有同名字体时会回退到跟随全局。
     */
    fun exportJson(style: OnlineEpubStyle): JSONObject {
        val json = styleJson(style)
        val asset = File(style.assetPath.trim())
        if (style.assetPath.isNotBlank() && asset.isFile) {
            runCatching {
                json.put("assetName", asset.name)
                json.put("assetData", Base64.encodeToString(asset.readBytes(), Base64.NO_WRAP))
            }
        }
        return json
    }

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
            embedFont = json.optBoolean("embedFont", true),
            assetPath = json.optString("assetPath"),
            maskAsset = json.optString("maskAsset"),
            sampleWidth = json.optInt("sampleWidth"),
            sampleHeight = json.optInt("sampleHeight"),
        )
    }

    /**
     * 导入一条样式：内嵌的图片会还原到 [assetDir]，并把 assetPath 指向还原后的文件。
     *
     * 没有内嵌图片时保留原 assetPath，本机恰好存在同路径文件就仍然可用。
     */
    fun importStyle(json: JSONObject, assetDir: File): OnlineEpubStyle? {
        val style = styleFromJson(json) ?: return null
        val data = json.optString("assetData").takeIf { it.isNotBlank() } ?: return style
        val name = json.optString("assetName").trim().ifBlank { "${style.id}.png" }
        return runCatching {
            assetDir.mkdirs()
            val target = File(assetDir, "${style.id}_${name.substringAfterLast('/')}")
            target.writeBytes(Base64.decode(data, Base64.DEFAULT))
            style.copy(assetPath = target.absolutePath)
        }.getOrDefault(style)
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
