package com.reamicro.fix.online

import android.content.Context
import java.io.File
import com.reamicro.fix.reload.TaskCancellation
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class OnlineSourceEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sourceUrl: String,
    val loginUrl: String,
    val loginUi: String,
    val loginCheckJs: String,
    val concurrentRate: String,
    val header: String,
    val enabledCookieJar: Boolean,
    val searchUrl: String,
    val ruleSearch: String,
    val ruleBookInfo: String,
    val ruleToc: String,
    val ruleContent: String,
    val respondTime: Int,
    val origin: String,
    val jsLib: String = "",
    val configuredRequestsPerSecond: Int? = null,
    val dailyChapterLimit: Int? = null,
    val preferOnDemandLoading: Boolean = false,
    val paragraphCommentsEnabled: Boolean = false,
    val chapterBatchEndpoint: String = "",
    val chapterBatchSize: Int = 0,
    val variableComment: String = "",
    val packageId: String = "",
    val aliases: Set<String> = emptySet(),
    val knownNames: Set<String> = emptySet(),

    val exploreUrl: String = "",

    val ruleExplore: String = "",
    /** Content digest and installation generation; copies retain the source they were loaded from. */
    val revision: String = "",
    internal val storageKey: String = "",
    /** Full source document for the upstream parser, including fields unknown to the legacy engine. */
    val rawJson: String = "",
) {
    val hasLoginConfig: Boolean
        get() = loginUrl.isNotBlank() || loginUi.isNotBlank() || loginCheckJs.isNotBlank()

    val hasExplore: Boolean
        get() = exploreUrl.isNotBlank()

    val webLoginUrl: String
        get() = loginUrl.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }.orEmpty()

    val supportsParagraphComments: Boolean
        get() = listOf(loginUi, loginUrl, ruleContent, jsLib).any { text ->
            text.contains("段评", ignoreCase = true) ||
                text.contains("idea_counts", ignoreCase = true) ||
                text.contains("idea_comments", ignoreCase = true) ||
                text.contains("toggleParacomment", ignoreCase = true)
        }
}

data class OnlineConcurrentRecord(
    var time: Long,
    val accessLimit: Int,
    val interval: Int,
    var frequency: Int,
)

object OnlineConcurrentRateLimiter {
    private val records = java.util.concurrent.ConcurrentHashMap<String, OnlineConcurrentRecord>()
    private val cooldownUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun clear(sourceId: String) { records.remove(sourceId); cooldownUntilMs.remove(sourceId) }

    fun updateConcurrentRate(key: String, concurrentRate: String) {
        records.compute(key) { _, record ->
            val parsed = parseConcurrentRate(concurrentRate, System.currentTimeMillis(), 0)
                ?: return@compute null
            if (record?.accessLimit == parsed.accessLimit && record.interval == parsed.interval) record else parsed
        }
    }

    fun waitTurn(source: OnlineSourceEntry): OnlineConcurrentRecord? {
        OnlineSourceStore.requireCurrent(source)
        val concurrentRate = OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(source)
        updateConcurrentRate(source.id, concurrentRate)
        while (true) {
            OnlineSourceStore.requireCurrent(source)
            val cooldownWait = (cooldownUntilMs[source.id] ?: 0L) - System.currentTimeMillis()
            if (cooldownWait > 0L) {
                Thread.sleep(minOf(cooldownWait, 100L))
                continue
            }
            if (concurrentRate.isBlank() || concurrentRate == "0") {
                records.remove(source.id)
                return null
            }
            val waitTime = waitTime(source.id, concurrentRate)
            if (waitTime <= 0L) return records[source.id]
            Thread.sleep(minOf(waitTime, 100L))
        }
    }

    fun recordRateLimited(source: OnlineSourceEntry, retryAfterMs: Long = DEFAULT_RATE_LIMIT_COOLDOWN_MS) {
        val until = System.currentTimeMillis() + retryAfterMs.coerceIn(MIN_RATE_LIMIT_COOLDOWN_MS, MAX_RATE_LIMIT_COOLDOWN_MS)
        cooldownUntilMs.merge(source.id, until) { previous, next -> maxOf(previous, next) }
    }

    inline fun <T> withLimitBlocking(source: OnlineSourceEntry, block: () -> T): T {
        waitTurn(source)
        return try {
            block()
        } catch (error: Throwable) {
            if (error.message.orEmpty().contains("HTTP 429")) recordRateLimited(source)
            throw error
        }
    }

    private const val MIN_RATE_LIMIT_COOLDOWN_MS = 3_000L
    private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 10_000L
    private const val MAX_RATE_LIMIT_COOLDOWN_MS = 60_000L

    private fun waitTime(key: String, concurrentRate: String): Long {
        var isNewRecord = false
        val record = records.computeIfAbsent(key) {
            isNewRecord = true
            parseConcurrentRate(concurrentRate, System.currentTimeMillis(), 1)
                ?: OnlineConcurrentRecord(System.currentTimeMillis(), 1, 0, 1)
        }
        if (isNewRecord) return 0L
        synchronized(record) {
            val nextTime = record.time + record.interval.toLong()
            val now = System.currentTimeMillis()
            if (now >= nextTime) {
                record.time = now
                record.frequency = 1
                return 0L
            }
            if (record.frequency < record.accessLimit) {
                record.frequency++
                return 0L
            }
            return nextTime - now
        }
    }

    private fun parseConcurrentRate(rate: String, time: Long, frequency: Int): OnlineConcurrentRecord? {
        val text = rate.trim()
        if (text.isBlank() || text == "0") return null
        return runCatching {
            val splitIndex = text.indexOf("/")
            if (splitIndex > 0) {
                val accessLimit = text.take(splitIndex).toInt()
                val interval = text.substring(splitIndex + 1).toInt()
                if (accessLimit <= 0 || interval <= 0) error("invalid concurrentRate")
                OnlineConcurrentRecord(time, accessLimit, interval, frequency)
            } else {
                val interval = text.toInt()
                if (interval <= 0) error("invalid concurrentRate")
                OnlineConcurrentRecord(time, 1, interval, frequency)
            }
        }.getOrNull()
    }
}

object OnlineSourceStore {
    private const val SOURCE_DIR = "reamicro_online_sources"
    private val sourceExtensions = setOf("rmonline", "json", "txt")
    private val mutationLock = Any()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, List<OnlineSourceEntry>>()
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Hot request guards never reopen source files or parse JSON. */
    fun isCurrent(source: OnlineSourceEntry): Boolean =
        source.storageKey.isNotBlank() && source.revision.isNotBlank() &&
            revisions[source.storageKey] == source.revision

    fun requireCurrent(source: OnlineSourceEntry) {
        TaskCancellation.checkCurrent()
        if (!isCurrent(source)) throw java.util.concurrent.CancellationException("在线源已更新或删除，请重试")
    }

    /** Only short preference/result commits belong here, never network or image/file reads. */
    fun <T> withCurrent(source: OnlineSourceEntry, action: () -> T): T = synchronized(mutationLock) {
        requireCurrent(source)
        action()
    }

    fun observeChanges(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun notifyChanged() {
        listeners.forEach { listener -> runCatching(listener) }
    }

    fun cached(context: Context?): List<OnlineSourceEntry>? {
        context ?: return null
        return snapshots[sourceDir(context).absolutePath]?.map { OnlineSourceDownloadPolicyStore.attach(context, it) }
    }

    fun list(context: Context?): List<OnlineSourceEntry> {
        context ?: return emptyList()
        cached(context)?.let { return it }
        val dir = sourceDir(context)
        val root = dir.absolutePath
        while (true) {
            TaskCancellation.checkCurrent()
            val before = sequence.get()
            // The first scan is performed by callers' background workers. No lock spans disk I/O.
            val loaded = dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in sourceExtensions }
                .sortedWith(compareBy<File> { it.extension != "rmonline" }.thenBy { it.name })
                .mapNotNull { file ->
                    runCatching {
                        val bytes = file.inputStream().use(::readOnlineSourceBytes)
                        val parsed = parseSingleSource(bytes, file.nameWithoutExtension, file.name)
                        parsed.copy(
                            storageKey = "$root/${onlineSourceFileName(parsed.id)}",
                            revision = digest(bytes),
                        )
                    }.onFailure(TaskCancellation::rethrowIfCancelled).getOrNull()
                }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
            val committed = synchronized(mutationLock) {
                if (sequence.get() != before) null else {
                    snapshots[root] ?: loaded.map { source ->
                        val revision = revisions[source.storageKey]?.takeIf {
                            it.substringBefore(':') == source.revision
                        } ?: "${source.revision}:${sequence.incrementAndGet()}"
                        revisions[source.storageKey] = revision
                        source.copy(revision = revision)
                    }.also { snapshots[root] = it }
                }
            }
            if (committed != null) return committed.map { OnlineSourceDownloadPolicyStore.attach(context, it) }
        }
    }

    fun importFromUrl(context: Context, rawUrl: String): OnlineSourceEntry {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("剪贴板中没有在线源链接")
        }
        list(context)
        val startedAt = sequence.get()
        val bytes = download(url)
        return importBytes(context, bytes, url.substringAfterLast('/').ifBlank { "online_source.json" }, url, startedAt)
    }

    fun importBytes(context: Context, bytes: ByteArray, displayName: String, origin: String = "",
        startedAt: Long? = null, expected: OnlineSourceEntry? = null, requireAbsent: Boolean = false): OnlineSourceEntry {
        val installed = list(context)
        val before = startedAt ?: sequence.get()
        val incoming = parseSingleSource(bytes, displayName.substringBeforeLast('.', displayName), displayName)
        val existing = installed.firstOrNull { matchesIdentity(it, incoming.id) }
        if (requireAbsent && existing != null) throw java.util.concurrent.CancellationException("在线源已安装，请重新检查更新")
        val payload = if (existing != null && (existing.id != incoming.id ||
                existing.packageId.isNotBlank() && incoming.packageId.isBlank())) {
            injectPackageIdentity(bytes, existing.id, existing.packageId,
                existing.aliases + incoming.aliases + incoming.id + existing.id, existing.knownNames)
        } else bytes
        val parsed = parseSingleSource(payload, displayName.substringBeforeLast('.', displayName), displayName)
            .copy(origin = origin)
        val dir = sourceDir(context).apply { mkdirs() }
        val target = File(dir, "${onlineSourceFileName(parsed.id)}.rmonline")
        val key = target.absolutePath.removeSuffix(".rmonline")
        require(expected == null || expected.id == parsed.id && expected.storageKey == key) {
            "在线源身份不一致"
        }
        val fingerprint = digest(payload)
        var revision = ""
        writeOnlineSourceAtomically(target, payload, commit = { action ->
            synchronized(mutationLock) {
                expected?.let(::requireCurrent)
                if (existing != null) requireCurrent(existing)
                // An import that began before this source was removed must not resurrect it.
                val current = revisions[key]
                if (existing == null && current != null && !current.startsWith("removed:")) {
                    throw java.util.concurrent.CancellationException("在线源已变化，请重新导入")
                }
                if (current != null &&
                    current.substringAfterLast(':').toLongOrNull()?.let { it > before } == true) {
                    throw java.util.concurrent.CancellationException("在线源已变化，请重新导入")
                }
                action()
                revision = "$fingerprint:${sequence.incrementAndGet()}"
                revisions[key] = revision
                snapshots.remove(dir.absolutePath)
            }
        })
        notifyChanged()
        return OnlineSourceDownloadPolicyStore.attach(context, parsed.copy(
            fileName = target.name, storageKey = key, revision = revision,
        ))
    }

    fun importPackageBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        packageId: String,
        stableSourceId: String,
        aliases: Set<String>,
        expected: OnlineSourceEntry? = null,
        requireAbsent: Boolean = false,
    ): OnlineSourceEntry {
        require(packageId.isNotBlank()) { "在线源内容包缺少身份" }
        val incoming = parseSingleSource(bytes, displayName.substringBeforeLast('.', displayName), displayName)
        val knownAliases = (aliases + stableSourceId + incoming.id).filterTo(linkedSetOf()) { it.isNotBlank() }
        val existing = list(context).firstOrNull { source ->
            source.packageId == packageId || source.id in knownAliases || source.aliases.any(knownAliases::contains)
        }
        require(existing == null || existing.packageId.isBlank() || existing.packageId == packageId) {
            "在线源已关联其他内容包，未覆盖"
        }
        if (requireAbsent && existing != null) throw java.util.concurrent.CancellationException("在线源已安装，请重新检查更新")
        expected?.let(::requireCurrent)
        val effectiveId = existing?.id ?: stableSourceId.ifBlank { incoming.id }
        val payload = injectPackageIdentity(bytes, effectiveId, packageId,
            knownAliases + existing?.aliases.orEmpty() + effectiveId, existing?.knownNames.orEmpty())
        return importBytes(context, payload, displayName, "api:$packageId", expected = expected, requireAbsent = requireAbsent)
    }

    fun linkPackage(
        context: Context,
        sourceId: String,
        packageId: String,
        aliases: Set<String>,
        names: Set<String> = emptySet(),
        expected: OnlineSourceEntry? = null,
    ): Boolean {
        require(packageId.isNotBlank()) { "在线源内容包缺少身份" }
        val dir = sourceDir(context)
        val existing = list(context).firstOrNull { it.id == sourceId } ?: return false
        require(existing.packageId.isBlank() || existing.packageId == packageId) {
            "在线源已关联其他内容包，未覆盖"
        }
        val file = File(dir, existing.fileName)
        return runCatching {
            expected?.let { require(it.id == sourceId); requireCurrent(it) }
            val merged = (aliases + existing.aliases + sourceId).filterTo(linkedSetOf()) { it.isNotBlank() }
            val payload = injectPackageIdentity(file.inputStream().use(::readOnlineSourceBytes), sourceId, packageId, merged, names)
            val fingerprint = digest(payload)
            writeOnlineSourceAtomically(file, payload, commit = { action ->
                synchronized(mutationLock) {
                    expected?.let(::requireCurrent)
                    requireCurrent(existing)
                    action()
                    revisions[existing.storageKey] = "$fingerprint:${sequence.incrementAndGet()}"
                    snapshots.remove(dir.absolutePath)
                }
            })
            notifyChanged()
            true
        }.onFailure(TaskCancellation::rethrowIfCancelled).getOrDefault(false)
    }

    fun knownNames(context: Context?, sourceId: String): Set<String> {
        return list(context).firstOrNull { it.id == sourceId }?.knownNames.orEmpty()
    }

    fun matchesIdentity(source: OnlineSourceEntry, candidate: String): Boolean =
        candidate.isNotBlank() && (source.id == candidate || candidate in source.aliases)

    fun remove(context: Context?, sourceId: String, expected: OnlineSourceEntry? = null): Boolean {
        context ?: return false
        require(expected == null || expected.id == sourceId) { "在线源身份不一致" }
        val dir = sourceDir(context)
        val current = expected ?: list(context).firstOrNull { it.id == sourceId }
        current?.let(::requireCurrent)
        val files = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.extension.lowercase() in sourceExtensions && runCatching {
                parseSingleSource(file.inputStream().use(::readOnlineSourceBytes), file.nameWithoutExtension, file.name).id == sourceId
            }.onFailure(TaskCancellation::rethrowIfCancelled).getOrDefault(false)
        }
        if (files.isEmpty()) return false
        var changed = false
        val removed = synchronized(mutationLock) {
            current?.let(::requireCurrent)
            TaskCancellation.checkCurrent()
            val results = files.map { file -> file.delete().also { if (it) changed = true } }
            if (changed) {
                revisions["${dir.absolutePath}/${onlineSourceFileName(sourceId)}"] = "removed:${sequence.incrementAndGet()}"
                snapshots.remove(dir.absolutePath)
            }
            if (results.all { it }) {
                OnlineSourceAuth.clearSourceData(context, sourceId)
                OnlineSourceDownloadPolicyStore.clear(context, sourceId)
                OnlineConcurrentRateLimiter.clear(sourceId)
            }
            results.all { it }
        }
        if (changed) notifyChanged()
        return removed
    }

    private fun sourceDir(context: Context): File =
        File(context.filesDir, SOURCE_DIR)

    private fun parseSingleSource(bytes: ByteArray, fallbackName: String, fileName: String): OnlineSourceEntry {
        require(bytes.size <= MAX_ONLINE_SOURCE_BYTES) { "在线源文件过大" }
        TaskCancellation.checkCurrent()
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) error("在线源为空")
        val json = when {
            text.startsWith("[") -> {
                val array = JSONArray(text)
                if (array.length() != 1) error("一次最多导入一个在线源")
                array.optJSONObject(0) ?: error("在线源格式不正确")
            }
            text.startsWith("{") -> JSONObject(text)
            else -> error("在线源格式不正确")
        }
        val name = firstString(json, "bookSourceName", "sourceName", "name", "title")
            .ifBlank { fallbackName }
            .ifBlank { "在线源" }
        val sourceUrl = firstString(json, "bookSourceUrl", "sourceUrl", "url", "id")
        val loginUrl = firstString(json, "loginUrl", "loginURL", "login", "loginPage")
        val loginUi = rawJsonString(json, "loginUi").ifBlank { rawJsonString(json, "loginUI") }
        val loginCheckJs = firstString(json, "loginCheckJs", "loginCheckJS")
        val concurrentRate = firstString(json, "concurrentRate", "rateLimit", "requestRate")
        val header = rawJsonString(json, "header").ifBlank { rawJsonString(json, "headers") }
        val searchUrl = firstString(json, "searchUrl", "searchURL", "search")
        val ruleSearch = rawJsonString(json, "ruleSearch")
        val ruleBookInfo = rawJsonString(json, "ruleBookInfo")
        val ruleToc = rawJsonString(json, "ruleToc")
        val ruleContent = rawJsonString(json, "ruleContent")
        val jsLib = rawJsonString(json, "jsLib")
        val variableComment = firstString(json, "variableComment", "sourceVariableComment")
        val exploreUrl = firstString(json, "exploreUrl", "exploreURL", "discoverUrl")
        val ruleExplore = rawJsonString(json, "ruleExplore").ifBlank { rawJsonString(json, "exploreRule") }
        val chapterBatchEndpoint = firstString(json, "reamicroChapterBatchEndpoint")
        val chapterBatchSize = firstString(json, "reamicroChapterBatchSize")
            .toIntOrNull()
            ?.takeIf { it in 1..30 }
            ?: 0
        val respondTime = firstString(json, "respondTime", "timeout")
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 180_000
        val explicitId = firstString(json, "reamicroSourceId")
        val idBase = sourceUrl.ifBlank { name }
        val aliases = buildSet {
            val array = json.optJSONArray("reamicroSourceAliases")
            if (array != null) for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
            add(stableId(idBase))
        }
        return OnlineSourceEntry(
            id = explicitId.ifBlank { stableId(idBase) },
            name = name,
            fileName = fileName,
            sourceUrl = sourceUrl,
            loginUrl = loginUrl,
            loginUi = loginUi,
            loginCheckJs = loginCheckJs,
            concurrentRate = concurrentRate,
            header = header,
            enabledCookieJar = if (json.has("enabledCookieJar")) json.optBoolean("enabledCookieJar", true) else true,
            searchUrl = searchUrl,
            ruleSearch = ruleSearch,
            ruleBookInfo = ruleBookInfo,
            ruleToc = ruleToc,
            ruleContent = ruleContent,
            respondTime = respondTime,
            origin = "",
            jsLib = jsLib,
            chapterBatchEndpoint = chapterBatchEndpoint,
            chapterBatchSize = chapterBatchSize,
            variableComment = variableComment,
            packageId = firstString(json, "reamicroPackageId"),
            aliases = aliases,
            knownNames = buildSet {
                val names = json.optJSONArray("reamicroSourceNames")
                if (names != null) for (index in 0 until names.length()) {
                    names.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            },
            exploreUrl = exploreUrl,
            ruleExplore = ruleExplore,
            rawJson = json.toString(),
        )
    }

    private fun firstString(json: JSONObject, vararg names: String): String =
        names.asSequence()
            .map { json.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun rawJsonString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        val value = json.opt(name) ?: return ""
        return when (value) {
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString().trim()
        }
    }

    private fun stableId(value: String): String =
        "online_" + MessageDigest.getInstance("SHA-256")
            .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun injectPackageIdentity(
        bytes: ByteArray,
        sourceId: String,
        packageId: String,
        aliases: Set<String>,
        names: Set<String> = emptySet(),
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).trim()
        val root = if (text.startsWith("[")) {
            val array = JSONArray(text)
            require(array.length() == 1) { "一次最多导入一个在线源" }
            array.optJSONObject(0) ?: error("在线源格式不正确")
        } else JSONObject(text)

        val existingNames = buildSet {
            val array = root.optJSONArray("reamicroSourceNames")
            if (array != null) for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        root.put("reamicroSourceId", sourceId)
            .put("reamicroPackageId", packageId)
            .put("reamicroSourceAliases", JSONArray(aliases.toList()))
            .put("reamicroSourceNames", JSONArray((existingNames + names).filter(String::isNotBlank)))
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun download(url: String): ByteArray = downloadOnlineSourceBytes(url)
}
