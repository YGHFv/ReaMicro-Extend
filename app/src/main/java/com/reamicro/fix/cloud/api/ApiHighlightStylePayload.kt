package com.reamicro.fix.cloud.api

import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightStyle
import org.json.JSONObject

/**
 * 高亮样式内容包的字段编解码。
 *
 * 上传与安装共用这一份定义，避免两边字段对不上——此前安装侧只读浅色字段，
 * 下载来的样式会静默丢掉整套深色配置（而 darkUsesLight 默认 true，
 * 表现是深色下回落成浅色外观，不报错但不对）。
 */
private const val PAYLOAD_SCHEMA_VERSION = 1

/** 从内容包 JSON 还原样式。[fallbackId] 用于 JSON 里没写 id 的情况。 */
internal fun readHighlightStylePayload(styleRoot: JSONObject, fallbackId: String): ReaderHighlightStyle {
    val id = styleRoot.optString("id").trim().ifBlank { fallbackId }
    return ReaderHighlightStyle(
        id = id,
        name = styleRoot.optString("name").ifBlank { id },
        color = styleRoot.optString("color").ifBlank { ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR },
        fontFamily = styleRoot.optString("fontFamily"),
        css = styleRoot.optString("css"),
        ninePatchPath = styleRoot.optString("ninePatchPath"),
        ninePatchSlice = styleRoot.optString("ninePatchSlice"),
        // 缺字段时保持 data class 的默认值：darkUsesLight 默认跟随浅色。
        darkUsesLight = styleRoot.optBoolean("darkUsesLight", true),
        darkColor = styleRoot.optString("darkColor"),
        darkFontFamily = styleRoot.optString("darkFontFamily"),
        darkCss = styleRoot.optString("darkCss"),
        darkNinePatchPath = styleRoot.optString("darkNinePatchPath"),
        darkNinePatchSlice = styleRoot.optString("darkNinePatchSlice"),
    )
}

/**
 * 把本机样式编码成内容包 JSON。
 *
 * 九宫格图片路径是本机绝对路径，对别的设备毫无意义，所以**不上传**——
 * 带上去只会让对方拿到一个指向不存在文件的路径。
 */
internal fun writeHighlightStylePayload(style: ReaderHighlightStyle): ByteArray =
    JSONObject()
        .put("schemaVersion", PAYLOAD_SCHEMA_VERSION)
        .put(
            "style",
            JSONObject()
                .put("id", style.id)
                .put("name", style.name)
                .put("color", style.color)
                .put("fontFamily", style.fontFamily)
                .put("css", style.css)
                .put("darkUsesLight", style.darkUsesLight)
                .put("darkColor", style.darkColor)
                .put("darkFontFamily", style.darkFontFamily)
                .put("darkCss", style.darkCss),
        )
        .toString(2)
        .toByteArray(Charsets.UTF_8)

/** 该样式是否依赖本机图片资源。依赖的会在上传时提示用户，因为对方拿不到这些图。 */
internal fun highlightStyleUsesLocalAssets(style: ReaderHighlightStyle): Boolean =
    style.ninePatchPath.isNotBlank() || style.darkNinePatchPath.isNotBlank()
