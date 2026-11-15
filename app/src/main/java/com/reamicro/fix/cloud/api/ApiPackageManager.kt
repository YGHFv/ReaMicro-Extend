package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceTask
import com.reamicro.fix.settings.OnlineEpubStyleStore
import com.reamicro.fix.settings.XposedModuleSettings
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import org.json.JSONObject
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.reload.TaskCancellation

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

class ApiPackageManager(
    private val context: Context,
    client: ApiServerClient,
    private val settings: XposedModuleSettings,
) {
    private val client = client.snapshot()

    fun check(kind: ApiPackageKind): List<ApiContentPackage> {
        checkModuleEnabled()
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
        checkModuleEnabled()
        require(contentPackage.schemaVersion == 1) { "不支持的内容包格式" }
        val originalSource = onlineSource(contentPackage)
        val bytes = if (originalSource == null) client.download(contentPackage)
            else OnlineSourceTask(originalSource).use { operation -> operation.run { client.download(contentPackage) } }
        require(sha256(bytes) == contentPackage.sha256.lowercase()) { "内容包摘要校验失败" }
        verifySignature(contentPackage, bytes)
        synchronized(MUTATION_LOCK) {
            checkModuleEnabled()
            client.checkCurrentRequest()
            originalSource?.let(OnlineSourceStore::requireCurrent)
            val cache = cacheFile(contentPackage)
            check(cache.parentFile!!.isDirectory || cache.parentFile!!.mkdirs()) { "内容包缓存目录不可用" }
            val temp = File.createTempFile(".package-", ".tmp", cache.parentFile)
            try {
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                require(temp.renameTo(cache)) { "内容包缓存写入失败" }
            } finally {
                temp.delete()
            }
            saveCacheManifest(contentPackage)
            checkModuleEnabled()
            val contentId = installPayload(contentPackage, bytes, originalSource,
                contentPackage.kind == ApiPackageKind.ONLINE_SOURCE && originalSource == null)
            checkModuleEnabled()
            saveInstalledVersion(contentPackage, contentId)
        }
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, true, "安装成功")
    }.getOrElse {
        TaskCancellation.rethrowIfCancelled(it)
        ApiPackageUpdateResult(contentPackage.packageId, contentPackage.version, false, it.message ?: "安装失败")
    }

    private fun checkModuleEnabled() {
        TaskCancellation.checkCurrent()
        if (!settings.snapshot().moduleEnabled) throw java.util.concurrent.CancellationException("补全计划已关闭")
    }

    fun link(kind: ApiPackageKind, packageId: String, localContentId: String, expectedSource: OnlineSourceEntry? = null) = synchronized(MUTATION_LOCK) {
        fun persist() {
        val packageKey = key(kind, packageId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind:$packageKey", kind.wireValue)
            .putString("package:$packageKey", packageId)
            .putString("version:$packageKey", LINKED_VERSION)
            .putLong("build_time:$packageKey", 0L)
            .putBoolean("enabled:$packageKey", true)
            .putString("content:$packageKey", localContentId)
            .putStringSet(KEY_INSTALLED_IDS, installedKeys() + packageKey)
            .apply()
        }
        checkModuleEnabled()
        if (expectedSource == null) persist() else OnlineSourceStore.withCurrent(expectedSource, ::persist)
    }

    fun isRegistered(kind: ApiPackageKind, packageId: String): Boolean =
        installed().any { it.kind == kind && it.packageId == packageId }

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

    fun uninstall(kind: ApiPackageKind, packageId: String): Boolean = synchronized(MUTATION_LOCK) {
        val item = installed().firstOrNull { it.kind == kind && it.packageId == packageId } ?: return@synchronized false
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
                val target = ApiPackagePaths.child(root, item.contentId)
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
        removed
    }

    fun rollback(kind: ApiPackageKind, packageId: String): ApiPackageUpdateResult = runCatching { synchronized(MUTATION_LOCK) {
        val current = installed().firstOrNull { it.kind == kind && it.packageId == packageId }
            ?: error("内容包尚未安装")
        val directory = File(context.filesDir, "$ROOT/${kind.wireValue}/${ApiPackagePaths.component(packageId)}")
        val historical = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file -> runCatching {
                ApiContentPackage.fromJson(JSONObject(file.readText(Charsets.UTF_8)))?.let { it to file }
            }.getOrNull() }
            .filter { (candidate, _) ->
                candidate.kind == kind && candidate.packageId == packageId &&
                    isPackageUpdateAvailable(current.version, current.buildTime, candidate.version, candidate.buildTime)
            }
            .maxWithOrNull { a, b ->
                comparePackageVersions(a.first.version, b.first.version).takeIf { it != 0 }
                    ?: a.first.buildTime.compareTo(b.first.buildTime)
            }
            ?: error("没有可回滚的历史版本")
        val (target, manifest) = historical
        val originalSource = onlineSource(target)
        val bytes = ApiPackagePaths.child(directory, "${manifest.nameWithoutExtension}.pkg").readBytes()
        require(sha256(bytes) == target.sha256.lowercase()) { "历史内容包摘要校验失败" }
        verifySignature(target, bytes)
        client.checkCurrentRequest()
        val contentId = installPayload(target, bytes, originalSource,
            kind == ApiPackageKind.ONLINE_SOURCE && originalSource == null)
        saveInstalledVersion(target, contentId)
        ApiPackageUpdateResult(packageId, target.version, true, "已回滚到 ${target.version}")
    } }.getOrElse { error ->
        TaskCancellation.rethrowIfCancelled(error)
        ApiPackageUpdateResult(packageId, "", false, error.message ?: "回滚失败")
    }

    private fun onlineSource(contentPackage: ApiContentPackage): OnlineSourceEntry? {
        if (contentPackage.kind != ApiPackageKind.ONLINE_SOURCE) return null
        val identities = contentPackage.aliases + contentPackage.contentId + contentPackage.packageId
        return OnlineSourceStore.list(context).firstOrNull { source ->
            source.packageId == contentPackage.packageId || identities.any { OnlineSourceStore.matchesIdentity(source, it) }
        }
    }

    private fun installPayload(contentPackage: ApiContentPackage, bytes: ByteArray,
        originalSource: OnlineSourceEntry? = null, requireAbsent: Boolean = false): String {
        return when (contentPackage.kind) {
            ApiPackageKind.ONLINE_SOURCE -> {
                OnlineSourceStore.importPackageBytes(
                    context = context,
                    bytes = bytes,
                    displayName = "${contentPackage.packageId}.json",
                    packageId = contentPackage.packageId,
                    stableSourceId = contentPackage.contentId.ifBlank { contentPackage.packageId },
                    aliases = contentPackage.aliases,
                    expected = originalSource,
                    requireAbsent = requireAbsent,
                ).id
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

                settings.setReaderHighlightStyle(
                    readHighlightStylePayload(
                        styleRoot = styleRoot,
                        fallbackId = id,
                        assetDir = File(context.filesDir, HIGHLIGHT_IMAGE_DIR),
                    ),
                )
                id
            }
            ApiPackageKind.ASSOCIATION_SOURCE -> {
                val extension = contentPackage.payloadName.substringAfterLast('.', "rmsource").lowercase()
                require(extension in setOf("rmsource", "apk", "jar", "dex")) { "关联源文件类型不受支持" }
                val root = File(context.filesDir, "reamicro_sources").apply { mkdirs() }
                val target = ApiPackagePaths.child(root, "${ApiPackagePaths.component(contentPackage.contentId.ifBlank { contentPackage.packageId })}.$extension")
                val temp = File.createTempFile(".association-", ".tmp", root)
                try {
                    temp.outputStream().use { it.write(bytes); it.fd.sync() }
                    require(temp.setReadOnly()) { "关联源文件只读保护失败" }
                    require(temp.renameTo(target)) { "关联源文件写入失败" }
                } finally {
                    temp.delete()
                }

                val previous = installed().firstOrNull { it.kind == contentPackage.kind && it.packageId == contentPackage.packageId }
                    ?.contentId
                    .orEmpty()
                if (previous.isNotBlank() && previous != target.name) {
                    File(root, previous).takeIf { it.isFile && it.parentFile == root }?.let { stale ->
                        stale.setWritable(true)
                        stale.delete()
                    }
                }
                ExternalSourceLoader.invalidate()
                target.name
            }
            ApiPackageKind.THEME -> {
                ApiThemeStore.install(context, bytes, contentPackage.contentId.ifBlank { contentPackage.packageId })
            }
        }
    }

    private fun verifySignature(contentPackage: ApiContentPackage, bytes: ByteArray) {
        val keyText = packageSigningKey(client.probe())
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
        File(context.filesDir, "$ROOT/${contentPackage.kind.wireValue}/${ApiPackagePaths.component(contentPackage.packageId)}/${ApiPackagePaths.cacheStem(contentPackage)}.pkg")

    private fun saveCacheManifest(contentPackage: ApiContentPackage) {
        val directory = cacheFile(contentPackage).parentFile ?: return
        val manifest = File(directory, "${ApiPackagePaths.cacheStem(contentPackage)}.json")
        manifest.writeText(
            JSONObject()
                .put("packageId", contentPackage.packageId)
                .put("kind", contentPackage.kind.wireValue)
                .put("version", contentPackage.version)
                .put("buildTime", contentPackage.buildTime)
                .put("schemaVersion", contentPackage.schemaVersion)
                .put("minModuleVersion", contentPackage.minModuleVersion)
                .put("contentId", contentPackage.contentId)
                .put("sha256", contentPackage.sha256)
                .put("signature", contentPackage.signature)
                .put("payload", contentPackage.payloadName)
                .put("aliases", org.json.JSONArray(contentPackage.aliases.toList()))
                .toString(),
            Charsets.UTF_8,
        )
    }

    private fun assetDir(): File = File(context.filesDir, "reamicro_api_assets")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val MUTATION_LOCK = Any()
        private const val PREFS = "reamicro_api_packages"
        private const val ROOT = "reamicro_api_packages"
        private const val HIGHLIGHT_IMAGE_DIR = "reader_highlight_nine_patch"
        private const val KEY_INSTALLED_IDS = "installed_ids"

        const val LINKED_VERSION = "0.0.0"
    }
}
