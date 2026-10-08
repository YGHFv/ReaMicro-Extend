package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.XposedModuleSettings
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

class ApiContentLibrarySync(
    private val context: Context,
    private val client: ApiServerClient,
    private val settings: XposedModuleSettings,
) {
    private val manager by lazy { ApiPackageManager(context, client, settings) }

    private val builtInHighlightStyleIds: Set<String> by lazy {
        ReaderHighlightStyle.builtIns().mapTo(mutableSetOf()) { it.id }
    }

    fun collect(): List<ApiLibraryItem> =
        collectOnlineSources() + collectAssociationSources() + collectHighlightStyles()

    fun linkedCount(): Int = manager.installed().count { it.kind in UPLOADABLE_KINDS }

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

    private fun registerLink(item: ApiLibraryItem, summary: ApiPackageSummary) {
        if (item.kind == ApiPackageKind.ONLINE_SOURCE) {
            OnlineSourceStore.linkPackage(
                context = context,
                sourceId = item.localContentId,
                packageId = summary.packageId,
                aliases = summary.aliases + summary.contentId + item.identities,

                names = summary.names + summary.name + item.name,
            )
        }
        manager.link(summary.kind, summary.packageId, item.localContentId)
    }

    private fun collectHighlightStyles(): List<ApiLibraryItem> =
        settings.highlightSettings().styles
            .filterNot { it.id in builtInHighlightStyleIds }
            .map { style ->
                ApiLibraryItem(
                    kind = ApiPackageKind.HIGHLIGHT_STYLE,
                    name = style.name.ifBlank { style.id },
                    names = linkedSetOf(style.name, style.id).filterTo(linkedSetOf()) { it.isNotBlank() },
                    contentId = style.id,
                    domains = emptySet(),
                    identities = linkedSetOf(style.id, style.name).filterTo(linkedSetOf()) { it.isNotBlank() },
                    payloadName = "${safeName(style.id)}.json",
                    payload = writeHighlightStylePayload(style),
                    localContentId = style.id,

                    usesLocalAssets = highlightStyleUsesLocalAssets(style),
                )
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

                names = linkedSetOf(source.name) + OnlineSourceStore.knownNames(context, source.id),
                contentId = source.id,
                domains = domains,

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

    internal companion object {

        val UPLOADABLE_KINDS = setOf(
            ApiPackageKind.ONLINE_SOURCE,
            ApiPackageKind.ASSOCIATION_SOURCE,
            ApiPackageKind.HIGHLIGHT_STYLE,
        )

        const val ONLINE_SOURCE_DIR = "reamicro_online_sources"
        const val ASSOCIATION_SOURCE_DIR = "reamicro_sources"
        val ASSOCIATION_EXTENSIONS = setOf("rmsource", "apk", "jar", "dex")

        val ASSOCIATION_DOMAIN_KEYS = listOf("domain", "host", "url", "siteUrl", "baseUrl", "homeUrl")
    }
}
