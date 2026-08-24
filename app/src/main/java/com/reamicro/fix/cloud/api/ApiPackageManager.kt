package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.OnlineEpubStyleStore
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.XposedModuleSettings
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import org.json.JSONObject
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.ModuleSettings

data class ApiPackageUpdateResult(
    val packageId: String,
    val version: String,
    val installed: Boolean,
    val message: String,
)

/** 统一管理内容包下载、校验、缓存和按类型安装。 */
class ApiPackageManager(
    private val context: Context,
    private val client: ApiServerClient,
    private val settings: XposedModuleSettings,
) {
    fun check(kind: ApiPackageKind): List<ApiContentPackage> {
        val packages = client.packages(kind)
        return packages.filter { candidate ->
            isPackageUpdateAvailable(
                candidate.version,
                candidate.buildTime,
                installedVersion(candidate.packageId),
                installedBuildTime(candidate.packageId),
            )
        }
    }

    fun install(contentPackage: ApiContentPackage): ApiPackageUpdateResult = runCatching {
        require(contentPackage.schemaVersion == 1) { "不支持的内容包格式" }
        val bytes = client.download(contentPackage)
        require(sha256(bytes) == contentPackage.sha256.lowercase()) { "内容包摘要校验失败" }
        verifySignature(contentPackage, bytes)
        val cache = cacheFile(contentPackage)
        cache.parentFile?.mkdirs()
        val temp = File(cache.parentFile, ".${cache.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(cache)) error("内容包缓存写入失败")
        val installed = installPayload(contentPackage, bytes)
        if (installed) saveInstalledVersion(contentPackage)
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, installed, "安装成功")
    }.getOrElse {
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, false, it.message ?: "安装失败")
    }

    private fun installPayload(contentPackage: ApiContentPackage, bytes: ByteArray): Boolean {
        return when (contentPackage.kind) {
            ApiPackageKind.ONLINE_SOURCE -> {
                OnlineSourceStore.importPackageBytes(
                    context = context,
                    bytes = bytes,
                    displayName = "${contentPackage.packageId}.json",
                    packageId = contentPackage.packageId,
                    stableSourceId = contentPackage.contentId.ifBlank { contentPackage.packageId },
                    aliases = contentPackage.aliases,
                )
                true
            }
            ApiPackageKind.EPUB_STYLE -> {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                val styleJson = json.optJSONObject("style") ?: json
                val style = OnlineEpubStyleStore.importStyle(styleJson, assetDir()) ?: error("成书样式格式无效")
                OnlineEpubStyleStore.save(context, style)
                true
            }
            ApiPackageKind.HIGHLIGHT_STYLE -> {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                val styleRoot = json.optJSONObject("style") ?: json
                val id = styleRoot.optString("id").trim().ifBlank { error("高亮样式缺少 ID") }
                val style = ReaderHighlightStyle(
                    id = id,
                    name = styleRoot.optString("name").ifBlank { id },
                    color = styleRoot.optString("color").ifBlank { ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR },
                    fontFamily = styleRoot.optString("fontFamily"),
                    css = styleRoot.optString("css"),
                    ninePatchPath = styleRoot.optString("ninePatchPath"),
                    ninePatchSlice = styleRoot.optString("ninePatchSlice"),
                )
                settings.setReaderHighlightStyle(style)
                true
            }
            ApiPackageKind.ASSOCIATION_SOURCE,
            ApiPackageKind.THEME,
            -> false
        }
    }

    private fun verifySignature(contentPackage: ApiContentPackage, bytes: ByteArray) {
        val keyText = client.probe().meta?.signingPublicKey.orEmpty()
        if (keyText.isBlank()) return
        require(contentPackage.signature.isNotBlank()) { "服务器要求内容包签名" }
        val keyBytes = Base64.decode(keyText, Base64.DEFAULT)
        val key: PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
        val signature = Signature.getInstance("Ed25519")
        signature.initVerify(key)
        signature.update(contentPackage.signedMessage())
        signature.update(bytes)
        require(signature.verify(Base64.decode(contentPackage.signature, Base64.DEFAULT))) { "内容包签名校验失败" }
    }

    private fun installedVersion(packageId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("version:$packageId", "0.0.0").orEmpty()

    private fun saveInstalledVersion(contentPackage: ApiContentPackage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("version:${contentPackage.packageId}", contentPackage.version)
            .putLong("build_time:${contentPackage.packageId}", contentPackage.buildTime)
            .apply()
    }

    private fun installedBuildTime(packageId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("build_time:$packageId", 0L)

    private fun cacheFile(contentPackage: ApiContentPackage): File =
        File(context.filesDir, "$ROOT/${safe(contentPackage.packageId)}/${safe(contentPackage.version)}.pkg")

    private fun assetDir(): File = File(context.filesDir, "reamicro_api_assets")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        private const val PREFS = "reamicro_api_packages"
        private const val ROOT = "reamicro_api_packages"
    }
}
