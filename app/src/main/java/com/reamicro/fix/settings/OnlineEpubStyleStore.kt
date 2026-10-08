package com.reamicro.fix.settings

import android.content.Context
import android.util.Base64
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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

    val headerEnabled: Boolean
        get() = headerScope != OnlineEpubHeaderScope.Off &&
            selected(OnlineEpubStyleKind.Header)?.assetPath?.isNotBlank() == true

    fun withDraft(style: OnlineEpubStyle): OnlineEpubStyleSettings {
        val replaced = styles.filterNot { it.id == style.id } + style
        return copy(
            styles = replaced,
            selection = selection + (style.kind to style.id),
        )
    }
}

object OnlineEpubStyleStore {
    fun read(context: Context?): OnlineEpubStyleSettings {
        context ?: return OnlineEpubStyleSettings()
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = decodeStyles(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES, "").orEmpty())
        val removed = decodeIds(prefs.getString(ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED, "").orEmpty())
        val merged = LinkedHashMap<String, OnlineEpubStyle>()
        OnlineEpubStyleLibrary.BUILT_INS.forEach { merged[it.id] = it }
        overrides.forEach { style ->

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
