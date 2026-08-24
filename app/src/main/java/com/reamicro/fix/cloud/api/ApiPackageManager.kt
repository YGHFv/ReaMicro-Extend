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
import com.reamicro.fix.association.provider.ExternalSourceLoader

data class ApiPackageUpdateResult(
    val packageId: String,
    val version: String,
    val installed: Boolean,
    val message: String,
)

data class InstalledApiPackage(
    val packageId: String,
    val kind: ApiPackageKind,
    val version: String,
    val buildTime: Long,
    val enabled: Boolean,
    val contentId: String,
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
                installedVersion(candidate.kind, candidate.packageId),
                installedBuildTime(candidate.kind, candidate.packageId),
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
        saveCacheManifest(contentPackage)
        val contentId = installPayload(contentPackage, bytes)
        saveInstalledVersion(contentPackage, contentId)
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, true, "安装成功")
    }.getOrElse {
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, false, it.message ?: "安装失败")
    }

    fun installed(): List<InstalledApiPackage> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return installedKeys().mapNotNull { key ->
            val kind = ApiPackageKind.fromWireValue(prefs.getString("kind:$key", "").orEmpty()) ?: return@mapNotNull null
            InstalledApiPackage(
                packageId = prefs.getString("package:$key", key).orEmpty(),
                kind = kind,
                version = prefs.getString("version:$key", "0.0.0").orEmpty(),
                buildTime = prefs.getLong("build_time:$key", 0L),
                enabled = prefs.getBoolean("enabled:$key", true),
                contentId = prefs.getString("content:$key", "").orEmpty(),
            )
        }
    }

    fun uninstall(kind: ApiPackageKind, packageId: String): Boolean {
        val item = installed().firstOrNull { it.kind == kind && it.packageId == packageId } ?: return false
        val removed = when (kind) {
            ApiPackageKind.ONLINE_SOURCE -> OnlineSourceStore.remove(context, item.contentId.ifBlank { packageId })
            ApiPackageKind.EPUB_STYLE -> {
                OnlineEpubStyleStore.remove(context, item.contentId.ifBlank { packageId })
                true
            }
            ApiPackageKind.HIGHLIGHT_STYLE -> {
                settings.removeReaderHighlightStyle(item.contentId.ifBlank { packageId })
                true
            }
            ApiPackageKind.ASSOCIATION_SOURCE -> {
                val root = File(context.filesDir, "reamicro_sources")
                val target = File(root, item.contentId)
                target.setWritable(true)
                !target.exists() || target.delete()
            }
            ApiPackageKind.THEME -> ApiThemeStore.remove(context, item.contentId.ifBlank { packageId })
        }
        if (removed) {
            val packageKey = key(kind, packageId)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_INSTALLED_IDS, installedKeys() - packageKey)
                .apply()
            ExternalSourceLoader.invalidate()
        }
        return removed
    }

    fun rollback(kind: ApiPackageKind, packageId: String): ApiPackageUpdateResult = runCatching {
        val current = installed().firstOrNull { it.kind == kind && it.packageId == packageId }
            ?: error("内容包尚未安装")
        val directory = File(context.filesDir, "$ROOT/${kind.wireValue}/${safe(packageId)}")
        val target = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file -> runCatching { ApiContentPackage.fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
            .filter { it.version != current.version }
            .maxWithOrNull(compareBy<ApiContentPackage> { it.buildTime }.thenBy { it.version })
            ?: error("没有可回滚的历史版本")
        val bytes = cacheFile(target).readBytes()
        require(sha256(bytes) == target.sha256.lowercase()) { "历史内容包摘要校验失败" }
        verifySignature(target, bytes)
        val contentId = installPayload(target, bytes)
        saveInstalledVersion(target, contentId)
        ApiPackageUpdateResult(packageId, target.version, true, "已回滚到 ${target.version}")
    }.getOrElse { error ->
        ApiPackageUpdateResult(packageId, "", false, error.message ?: "回滚失败")
    }

    private fun installPayload(contentPackage: ApiContentPackage, bytes: ByteArray): String {
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
                contentPackage.contentId.ifBlank { contentPackage.packageId }
            }
            ApiPackageKind.EPUB_STYLE -> {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                val styleJson = json.optJSONObject("style") ?: json
                val style = OnlineEpubStyleStore.importStyle(styleJson, assetDir()) ?: error("成书样式格式无效")
                OnlineEpubStyleStore.save(context, style)
                style.id
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
                id
            }
            ApiPackageKind.ASSOCIATION_SOURCE -> {
                val extension = contentPackage.payloadName.substringAfterLast('.', "rmsource").lowercase()
                require(extension in setOf("rmsource", "apk", "jar", "dex")) { "关联源文件类型不受支持" }
                val root = File(context.filesDir, "reamicro_sources").apply { mkdirs() }
                val target = File(root, "${safe(contentPackage.contentId.ifBlank { contentPackage.packageId })}.$extension")
                val temp = File(root, ".${target.name}.tmp")
                temp.writeBytes(bytes)
                if (target.exists()) target.setWritable(true)
                require(temp.renameTo(target)) { "关联源文件写入失败" }
                target.setReadOnly()
                ExternalSourceLoader.invalidate()
                target.name
            }
            ApiPackageKind.THEME -> {
                ApiThemeStore.install(context, bytes, contentPackage.contentId.ifBlank { contentPackage.packageId })
            }
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

    private fun installedVersion(kind: ApiPackageKind, packageId: String): String =
        installed().firstOrNull { it.kind == kind && it.packageId == packageId }?.version ?: "0.0.0"

    private fun saveInstalledVersion(contentPackage: ApiContentPackage, contentId: String) {
        val packageKey = key(contentPackage.kind, contentPackage.packageId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind:$packageKey", contentPackage.kind.wireValue)
            .putString("package:$packageKey", contentPackage.packageId)
            .putString("version:$packageKey", contentPackage.version)
            .putLong("build_time:$packageKey", contentPackage.buildTime)
            .putBoolean("enabled:$packageKey", true)
            .putString("content:$packageKey", contentId)
            .putStringSet(KEY_INSTALLED_IDS, installedKeys() + packageKey)
            .apply()
    }

    private fun installedBuildTime(kind: ApiPackageKind, packageId: String): Long =
        installed().firstOrNull { it.kind == kind && it.packageId == packageId }?.buildTime ?: 0L

    private fun installedKeys(): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_INSTALLED_IDS, emptySet()).orEmpty()

    private fun key(kind: ApiPackageKind, packageId: String): String = "${kind.wireValue}:$packageId"

    private fun cacheFile(contentPackage: ApiContentPackage): File =
        File(context.filesDir, "$ROOT/${contentPackage.kind.wireValue}/${safe(contentPackage.packageId)}/${safe(contentPackage.version)}.pkg")

    private fun saveCacheManifest(contentPackage: ApiContentPackage) {
        val directory = cacheFile(contentPackage).parentFile ?: return
        val manifest = File(directory, "${safe(contentPackage.version)}.json")
        manifest.writeText(
            JSONObject()
                .put("packageId", contentPackage.packageId)
                .put("kind", contentPackage.kind.wireValue)
                .put("version", contentPackage.version)
                .put("buildTime", contentPackage.buildTime)
                .put("contentId", contentPackage.contentId)
                .put("sha256", contentPackage.sha256)
                .put("signature", contentPackage.signature)
                .put("payload", contentPackage.payloadName)
                .toString(),
            Charsets.UTF_8,
        )
    }

    private fun assetDir(): File = File(context.filesDir, "reamicro_api_assets")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        private const val PREFS = "reamicro_api_packages"
        private const val ROOT = "reamicro_api_packages"
        private const val KEY_INSTALLED_IDS = "installed_ids"
    }
}
