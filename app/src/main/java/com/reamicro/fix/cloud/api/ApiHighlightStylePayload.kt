package com.reamicro.fix.cloud.api

import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightStyle
import java.io.File
import java.security.MessageDigest
import java.util.Base64
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
private const val PAYLOAD_SCHEMA_VERSION = 2
private const val HIGHLIGHT_IMAGE_MIME = "image/png"

/**
 * 从内容包 JSON 还原样式。[fallbackId] 用于 JSON 里没写 id 的情况。
 *
 * [assetDir] 由内容包安装器传入；载荷带图片时会先还原到该目录，再把本机路径写入样式。
 * 传 null 用于只解析字段的场景，旧版不带图片的载荷仍可直接读取。
 */
internal fun readHighlightStylePayload(
    styleRoot: JSONObject,
    fallbackId: String,
    assetDir: File? = null,
): ReaderHighlightStyle {
    val id = styleRoot.optString("id").trim().ifBlank { fallbackId }
    return ReaderHighlightStyle(
        id = id,
        name = styleRoot.optString("name").ifBlank { id },
        color = styleRoot.optString("color").ifBlank { ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR },
        fontFamily = styleRoot.optString("fontFamily"),
        css = styleRoot.optString("css"),
        ninePatchPath = restoreHighlightStyleImage(styleRoot.optJSONObject("ninePatchFile"), assetDir)
            ?: styleRoot.optString("ninePatchPath"),
        ninePatchSlice = styleRoot.optString("ninePatchSlice"),
        // dark* 一律留默认值：样式不带深色外观，深浅由规则和默认样式设置决定。
    )
}

/**
 * 把本机样式编码成内容包 JSON。
 *
 * 九宫格图片路径是本机绝对路径，不能直接上传；图片文件会以 base64 内嵌到
 * `ninePatchFile`，下载端再还原成本机路径。
 */
internal fun writeHighlightStylePayload(style: ReaderHighlightStyle): ByteArray {
    val styleJson = JSONObject()
        .put("id", style.id)
        .put("name", style.name)
        .put("color", style.color)
        .put("fontFamily", style.fontFamily)
        .put("css", style.css)
        .put("ninePatchSlice", style.ninePatchSlice)
    highlightStyleImageJson(style.ninePatchPath)?.let { styleJson.put("ninePatchFile", it) }
    return JSONObject()
        .put("schemaVersion", PAYLOAD_SCHEMA_VERSION)
        .put("style", styleJson)
        .toString(2)
        .toByteArray(Charsets.UTF_8)
}

/** 该样式是否包含可随内容包上传的本机图片。 */
internal fun highlightStyleUsesLocalAssets(style: ReaderHighlightStyle): Boolean =
    File(style.ninePatchPath).isFile

private fun highlightStyleImageJson(path: String): JSONObject? {
    val file = File(path)
    if (!file.isFile) return null
    val bytes = file.readBytes()
    if (!bytes.hasPngSignature()) return null
    return JSONObject()
        .put("name", file.name.take(160))
        .put("mime", HIGHLIGHT_IMAGE_MIME)
        .put("sha256", sha256(bytes))
        .put("base64", Base64.getEncoder().encodeToString(bytes))
}

private fun restoreHighlightStyleImage(item: JSONObject?, assetDir: File?): String? {
    item ?: return null
    assetDir ?: return null
    if (item.optString("mime").ifBlank { HIGHLIGHT_IMAGE_MIME } != HIGHLIGHT_IMAGE_MIME) return null
    val encoded = item.optString("base64")
    if (encoded.isBlank()) return null
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    if (!bytes.hasPngSignature()) return null
    val hash = sha256(bytes)
    val expectedHash = item.optString("sha256").trim().lowercase()
    if (expectedHash.isNotBlank() && expectedHash != hash) return null

    assetDir.mkdirs()
    val suffix = if (item.optString("name").endsWith(".9.png", ignoreCase = true)) ".9.png" else ".png"
    val target = File(assetDir, "cloud_${hash.take(24)}$suffix")
    if (target.isFile && runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)) {
        return target.absolutePath
    }
    val temp = File(assetDir, ".${target.name}.${System.nanoTime()}.tmp")
    temp.writeBytes(bytes)
    if (target.exists() && !target.delete()) {
        temp.delete()
        return null
    }
    if (!temp.renameTo(target)) {
        runCatching { target.writeBytes(bytes) }.getOrElse {
            temp.delete()
            return null
        }
        temp.delete()
    }
    return target.absolutePath
}

private fun ByteArray.hasPngSignature(): Boolean =
    size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)
