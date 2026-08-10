package com.reamicro.fix.hook.webdav

import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.widget.Toast
import com.reamicro.fix.association.model.AssociationPlatforms
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.webdav.decodeOnlineHtmlEntities
import java.util.Locale
import org.w3c.dom.Element
import com.reamicro.fix.hook.webdav.*

// 从 WebDavDriveHook 提升出来的成员扩展函数。
//
// 原先它们是「既是 String/Context/Any 的扩展、又是 hook 成员」的成员扩展函数，
// 这种函数只在类体内可见。把功能簇拆成同包扩展函数后调用不到，因此提升为不依赖
// hook 实例的顶层扩展函数。
internal fun String.normalizedAssociationPlatformName(): String {
    val clean = cleanOnlineText()
        .trim()
        .trim('/', '／', ',', '，', ';', '；', '|')
    if (clean.isBlank() || clean == "null") return ""
    val normalized = clean.lowercase(Locale.ROOT).replace(Regex("[\\s_.\\-/]+"), "")
    if (normalized == "doubanread") return "豆瓣阅读"
    return AssociationPlatforms.normalizeDisplayName(clean).ifBlank { clean }
}

internal fun OnlineSourceEntry.isWanFengLiSource(): Boolean =
    name.isWanFengLiSourceName() || id.isWanFengLiSourceName() || sourceUrl.contains("whispertale", ignoreCase = true)

internal fun String.isWanFengLiSourceName(): Boolean {
    val normalized = lowercase(Locale.ROOT).replace(Regex("[\\s_.\\-/]+"), "")
    return contains("晚风里") || normalized.contains("wanfengli") || normalized.contains("whispertale")
}

internal fun String.cleanOnlineText(): String =
    replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .decodeOnlineHtmlEntities()
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun ByteArray.toLowerHexString(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun String.hexDecodeToString(): String {
    val clean = trim().filterNot { it.isWhitespace() }
    if (clean.length < 2 || clean.length % 2 != 0) return this
    return runCatching {
        val bytes = ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        String(bytes, Charsets.UTF_8)
    }.getOrDefault(this)
}

internal fun Class<*>.companionField(name: String) =
    runCatching { getDeclaredField(name) }
        .recoverCatching { getField(name) }
        .getOrNull()

internal fun String.trimChapterHeadingSeparator(): String =
    trim().trimStart { it.isWhitespace() || it in "　:：/／、，,。.!！?？-—_；;]" }

internal fun String.normalizedChapterTitleKey(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s　:：/／、，,。.!！?？；;《》\"'“”‘’\\-—_]+"), "")

internal fun String.xmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

internal fun Element.textOf(localName: String): String {
    val nodes = getElementsByTagNameNS("*", localName)
    return nodes.item(0)?.textContent?.trim().orEmpty()
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}

internal fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal fun Context.resolveThemeColor(attr: Int, fallback: Int): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(attr))
    return try {
        typedArray.getColor(0, fallback)
    } finally {
        typedArray.recycle()
    }
}

internal fun Context.resolveOpaqueThemeColor(attr: Int, fallback: Int): Int {
    val color = resolveThemeColor(attr, fallback)
    return if (Color.alpha(color) < 64) fallback else color
}

internal fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES


internal fun Int.isDarkColor(): Boolean {
    val red = Color.red(this) / 255.0
    val green = Color.green(this) / 255.0
    val blue = Color.blue(this) / 255.0
    val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance < 0.45
}

internal fun String.redactWebDavUrl(): String =
    replace(Regex("""//([^/@]+)@"""), "//***@")

internal fun String.safeWebDavFileName(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "book" }

internal fun String.localPathPart(): String =
    Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

internal fun String.decodeLocalPathPart(): String =
    runCatching {
        String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrDefault(this)

internal fun android.database.Cursor.stringAt(index: Int): String =
    if (index < 0 || isNull(index)) "" else getString(index).orEmpty()

internal fun android.database.Cursor.longAt(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

internal fun Any.method0(name: String): Any =
    javaClass.methods.first {
        it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
    }.apply { isAccessible = true }.invoke(this)

internal fun Any.longMethod(name: String): Long =
    method0(name) as Long

internal fun Any.callString(methodName: String): String =
    javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.apply { isAccessible = true }
        ?.invoke(this)
        ?.toString()
        .orEmpty()

internal fun Any.callLong(methodName: String): Long {
    val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.apply { isAccessible = true }
        ?.invoke(this)
    return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
}

internal fun Any.callInt(methodName: String): Int {
    val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.apply { isAccessible = true }
        ?.invoke(this)
    return (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
}

internal fun Any.callFloat(methodName: String): Float {
    val value = javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.apply { isAccessible = true }
        ?.invoke(this)
    return (value as? Number)?.toFloat() ?: value?.toString()?.toFloatOrNull() ?: 0f
}

internal fun Any.invokeNoArg(methodName: String): Any? =
    javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.apply { isAccessible = true }
        ?.invoke(this)

internal fun String.htmlAttrEscape(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun String.jsonQuote(): String =
    buildString {
        append('"')
        this@jsonQuote.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
