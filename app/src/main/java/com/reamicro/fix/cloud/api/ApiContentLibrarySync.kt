package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.XposedModuleSettings
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * 本地书源、关联源与服务器内容库之间的上传和关联。
 *
 * 判定同一个源的口径与服务端一致：书源比对“名称 + 域名”，
 * 关联源没有域名，退化为“名称 + 清单 ID”。命中已有内容包时只建立关联，
 * 不覆盖服务器内容；关联后由“检查内容库更新”统一拉取服务器版本。
 */
class ApiContentLibrarySync(
    private val context: Context,
    private val client: ApiServerClient,
    private val settings: XposedModuleSettings,
) {
    private val manager by lazy { ApiPackageManager(context, client, settings) }

    /** 收集本地书源和关联源，供上传或比对使用。 */
    fun collect(): List<ApiLibraryItem> = collectOnlineSources() + collectAssociationSources()

    /** 已经关联到服务器内容包的本地源数量。 */
    fun linkedCount(): Int = manager.installed()
        .count { it.kind == ApiPackageKind.ONLINE_SOURCE || it.kind == ApiPackageKind.ASSOCIATION_SOURCE }

    /**
     * 上传本地内容库。服务器已有同名同域的源时只关联不上传，
     * 新上传成功的源同样立刻登记关联，供后续更新使用。
     */
    fun upload(
        items: List<ApiLibraryItem> = collect(),
        allowedKinds: Set<ApiPackageKind> = emptySet(),
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ApiLibrarySyncSummary {
        val targets = items.filter { allowedKinds.isEmpty() || it.kind in allowedKinds }
        var uploaded = 0
        var linked = 0
        var failed = 0
        val messages = mutableListOf<String>()
        targets.forEachIndexed { index, item ->
            onProgress(index + 1, targets.size, item.name)
            runCatching { client.uploadPackage(item) }
                .onSuccess { result ->
                    val summary = result.summary
                    if (summary == null) {
                        failed++
                        messages += "${item.name}：服务器未返回内容包信息"
                        return@onSuccess
                    }
                    registerLink(item, summary)
                    if (result.uploaded) uploaded++ else linked++
                }
                .onFailure { error ->
                    failed++
                    messages += "${item.name}：${error.message ?: "上传失败"}"
                }
        }
        if (uploaded + linked > 0) ExternalSourceLoader.invalidate()
        return ApiLibrarySyncSummary(targets.size, uploaded, linked, failed, messages.take(20))
    }

    /** 比对本地内容库与服务器内容库，返回可关联的结果。 */
    fun match(items: List<ApiLibraryItem> = collect()): List<Pair<ApiLibraryItem, ApiPackageSummary>> {
        if (items.isEmpty()) return emptyList()
        val matches = client.matchPackages(items)
        val byKey = matches.filter { it.matched && it.summary != null }
            .associateBy { it.kind to it.contentId }
        return items.mapNotNull { item ->
            val summary = byKey[item.kind to item.contentId]?.summary ?: return@mapNotNull null
            item to summary
        }
    }

    /**
     * 全量关联：把所有能与服务器对上的本地源登记成对应内容包。
     * 已经关联过且未变化的源会被跳过。
     */
    fun link(
        pairs: List<Pair<ApiLibraryItem, ApiPackageSummary>> = match(),
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): ApiLibrarySyncSummary {
        var linked = 0
        var failed = 0
        val messages = mutableListOf<String>()
        pairs.forEachIndexed { index, (item, summary) ->
            onProgress(index + 1, pairs.size, item.name)
            runCatching { registerLink(item, summary) }
                .onSuccess { linked++ }
                .onFailure { error ->
                    failed++
                    messages += "${item.name}：${error.message ?: "关联失败"}"
                }
        }
        if (linked > 0) ExternalSourceLoader.invalidate()
        return ApiLibrarySyncSummary(pairs.size, 0, linked, failed, messages.take(20))
    }

    /**
     * 登记关联关系。书源把 packageId 写回本地 JSON，关联源是 ZIP 归档无法改写，
     * 只在内容包登记表里记下本地文件名，两者都保证下次更新覆盖同一份本地内容。
     */
    private fun registerLink(item: ApiLibraryItem, summary: ApiPackageSummary) {
        if (item.kind == ApiPackageKind.ONLINE_SOURCE) {
            OnlineSourceStore.linkPackage(
                context = context,
                sourceId = item.localContentId,
                packageId = summary.packageId,
                aliases = summary.aliases + summary.contentId + item.identities,
                // 服务器合并后的名称集合写回本地，下次上报就带上全部历史名称。
                names = summary.names + summary.name + item.name,
            )
        }
        manager.link(summary.kind, summary.packageId, item.localContentId)
    }

    private fun collectOnlineSources(): List<ApiLibraryItem> =
        OnlineSourceStore.list(context).mapNotNull { source ->
            val file = File(File(context.filesDir, ONLINE_SOURCE_DIR), source.fileName)
            val bytes = file.takeIf(File::isFile)?.let { runCatching(it::readBytes).getOrNull() } ?: return@mapNotNull null
            val domains = buildSet {
                normalizeSourceDomain(source.sourceUrl).takeIf(String::isNotEmpty)?.let(::add)
                normalizeSourceDomain(source.origin).takeIf(String::isNotEmpty)?.let(::add)
            }
            ApiLibraryItem(
                kind = ApiPackageKind.ONLINE_SOURCE,
                name = source.name,
                // 本机记录过的历史名称一起交给服务器：源改过名时旧名仍能命中。
                names = linkedSetOf(source.name) + OnlineSourceStore.knownNames(context, source.id),
                contentId = source.id,
                domains = domains,
                // bookSourceUrl 是书源自己声明的入口，作为主地址。
                primaryDomain = normalizeSourceDomain(source.sourceUrl),
                identities = (source.aliases + source.id + source.sourceUrl).filterTo(linkedSetOf()) { it.isNotBlank() },
                payloadName = "${safeName(source.id)}.json",
                payload = bytes,
                localContentId = source.id,
            )
        }

    private fun collectAssociationSources(): List<ApiLibraryItem> {
        val root = File(context.filesDir, ASSOCIATION_SOURCE_DIR)
        return root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in ASSOCIATION_EXTENSIONS }
            .mapNotNull { file ->
                val bytes = runCatching(file::readBytes).getOrNull() ?: return@mapNotNull null
                val manifest = readAssociationManifest(file) ?: return@mapNotNull null
                val id = manifest.optString("id").trim().ifBlank { file.nameWithoutExtension }
                val name = manifest.optString("name").trim().ifBlank { id }
                val domains = buildSet {
                    for (key in ASSOCIATION_DOMAIN_KEYS) {
                        normalizeSourceDomain(manifest.optString(key)).takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
                ApiLibraryItem(
                    kind = ApiPackageKind.ASSOCIATION_SOURCE,
                    name = name,
                    contentId = id,
                    domains = domains,
                    primaryDomain = domains.firstOrNull().orEmpty(),
                    identities = linkedSetOf(id, file.name, manifest.optString("entryClass").trim())
                        .filterTo(linkedSetOf()) { it.isNotBlank() },
                    payloadName = file.name,
                    payload = bytes,
                    localContentId = file.name,
                )
            }
    }

    private fun readAssociationManifest(file: File): JSONObject? = runCatching {
        if (file.extension.equals("json", ignoreCase = true) || file.extension.equals("rmsource", ignoreCase = true)) {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.startsWith("{")) return@runCatching JSONObject(text)
        }
        if (file.extension.equals("dex", ignoreCase = true)) {
            val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
            return@runCatching sidecar.takeIf(File::isFile)?.let { JSONObject(it.readText(Charsets.UTF_8)) }
        }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@use null
            JSONObject(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }.getOrNull()

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]+"), "_").ifBlank { "source" }

    private companion object {
        const val ONLINE_SOURCE_DIR = "reamicro_online_sources"
        const val ASSOCIATION_SOURCE_DIR = "reamicro_sources"
        val ASSOCIATION_EXTENSIONS = setOf("rmsource", "apk", "jar", "dex")
        /** 关联源清单里可能出现的站点地址字段，用于补出域名参与比对。 */
        val ASSOCIATION_DOMAIN_KEYS = listOf("domain", "host", "url", "siteUrl", "baseUrl", "homeUrl")
    }
}
