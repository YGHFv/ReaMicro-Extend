package com.reamicro.fix.cloud.api

import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightStyle
import org.json.JSONObject

/**
 * 高亮样式内容包的字段编解码。上传与安装共用这一份定义。
 *
 * **样式本身不区分深色浅色**。深浅只存在于另外两层：
 * - 高亮规则各自带 `styleId` 与 `darkStyleId`，决定深浅主题下引用哪个样式；
 * - 默认样式设置有 `defaultLightStyleId` 与 `defaultDarkStyleId`。
 *
 * `ReaderHighlightStyle` 里的 `dark*` 字段是该设定移除前的历史残留，设置界面不再写入，
 * 恒为默认值（`darkUsesLight = true`，即深色跟随浅色）。所以内容包**不携带**这些字段：
 * 传了也没有意义，还会让人误以为样式能自带深色外观。
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
        // dark* 一律留默认值：样式不带深色外观，深浅由规则和默认样式设置决定。
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
                .put("css", style.css),
        )
        .toString(2)
        .toByteArray(Charsets.UTF_8)

/** 该样式是否依赖本机图片资源。依赖的会在上传时提示用户，因为对方拿不到这些图。 */
internal fun highlightStyleUsesLocalAssets(style: ReaderHighlightStyle): Boolean =
    style.ninePatchPath.isNotBlank()
