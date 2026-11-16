package com.reamicro.fix.cloud.webdav

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.reamicro.fix.cloud.webdav.SyncCbor.Atom
import com.reamicro.fix.cloud.webdav.SyncCbor.Fields
import com.reamicro.fix.cloud.webdav.SyncCbor.Items
import com.reamicro.fix.cloud.webdav.SyncCbor.Value
import com.reamicro.fix.cloud.webdav.SyncCbor.decode
import com.reamicro.fix.cloud.webdav.SyncCbor.encode
import com.reamicro.fix.cloud.webdav.SyncCbor.list
import com.reamicro.fix.cloud.webdav.SyncCbor.long
import com.reamicro.fix.cloud.webdav.SyncCbor.map
import com.reamicro.fix.cloud.webdav.SyncCbor.number
import com.reamicro.fix.cloud.webdav.SyncCbor.string
import com.reamicro.fix.cloud.webdav.SyncCbor.text

/** 数量清理只修改远端索引；清理历史与根清单正文绑定，失败的 CAS 不会误标记删除。 */
internal class ShelfSyncRetention(
    private val remote: ShelfSyncTransport,
    private val limit: Int?,
    private val now: () -> Long = System::currentTimeMillis,
    private val onHistory: (List<Fields>) -> Unit = {},
) : ShelfSyncTransport by remote {
    private val mime = "application/cbor"
    private val grace = 86_400_000L

    override fun get(key: String): WebDavHttpResponse? {
        val response = remote.get(key) ?: run {
            if (key.endsWith("/manifest.cbor")) onHistory(emptyList())
            return null
        }
        if (!key.endsWith("/manifest.cbor")) return response
        val bytes = requireNotNull(response.bodyBytes)
        val history = history(key, bytes)
        onHistory(history)
        if (limit == null) return response
        val plan = plan(key, bytes, history)
        if (plan == null) return response
        publish(key, plan, response.headers["ETag"])
        val actual = requireNotNull(remote.get(key)) { "同步清单更新后消失" }
        onHistory(history(key, requireNotNull(actual.bodyBytes)))
        return actual
    }

    override fun head(key: String): WebDavHttpResponse? {
        if (!key.endsWith("/manifest.cbor")) return remote.head(key)
        // 宿主会先 HEAD 并复用缓存；数量配置变更也必须在这个入口生效。
        val response = get(key) ?: return null
        return response.copy(headers = response.headers + ("Content-Length" to requireNotNull(response.bodyBytes).size.toString()))
    }

    override fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
        if (!key.endsWith("/manifest.cbor")) return remote.put(key, bytes, file, mime, condition)
        val current = remote.get(key)
        val old = current?.bodyBytes
        val retired = if (old == null) emptyList() else history(key, old)
        val data = bytes ?: requireNotNull(file).readBytes()
        val plan = if (limit == null) null else plan(key, data, retired)
        if (plan != null) publish(key, plan, condition)
        else {
            // 原生垃圾回收等根清单更新也必须携带历史，避免下一次同步误删本机。
            if (retired.isNotEmpty()) writeHistory(key, data, retired)
            remote.put(key, data, null, mime, condition)
            onHistory(retired)
        }
    }

    private data class Plan(val root: ByteArray, val shards: Map<String, ByteArray>, val retired: List<Fields>)
    private fun publish(key: String, plan: Plan, condition: String?) {
        require(condition != null) { "数量清理需要同步清单版本校验" }
        plan.shards.forEach { (path, bytes) -> remote.put(path, bytes, null, mime, "*") }
        writeHistory(key, plan.root, plan.retired)
        remote.put(key, plan.root, null, mime, condition)
        onHistory(plan.retired)
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun historyKey(key: String, bytes: ByteArray) =
        "${key.substringBeforeLast('/')}/.reamicro-retention/${digest(bytes)}.cbor"
    private fun history(key: String, bytes: ByteArray): List<Fields> {
        val data = remote.get(historyKey(key, bytes))?.bodyBytes ?: return emptyList()
        val fields = map(decode(data))
        require(long(fields["v"]) == 1L && string(fields["root"]) == digest(bytes)) { "同步数量清理记录不匹配" }
        return list(fields["books"]).map { requireNotNull(it as? Fields) }
    }
    private fun writeHistory(key: String, bytes: ByteArray, retired: List<Fields>) {
        if (retired.isEmpty()) return
        val path = historyKey(key, bytes)
        val data = encode(Fields(mapOf("v" to number(1), "root" to text(digest(bytes)), "books" to Items(retired))))
        val existing = remote.get(path)
        if (existing != null) {
            require(existing.bodyBytes?.contentEquals(data) == true) { "同步清理记录已变化，请重试" }
        } else remote.put(path, data, null, mime, "*")
    }

    private fun plan(key: String, bytes: ByteArray, previous: List<Fields>): Plan? {
        val count = limit ?: return null
        require(count >= 1)
        val prefix = key.substringBeforeLast('/')
        val root = map(decode(bytes)).toMutableMap()
        require(long(root["v"], 1) == 1L) { "暂不支持此版本的同步清单数量清理" }
        val shardNames = root["s"]?.let(::list)?.map(::string).orEmpty().toSet()
        val objects = root["sp"]?.let(::map).orEmpty().toMutableMap()
        require(shardNames == objects.keys) { "同步清单分片索引不一致" }
        val revisions = root["sr"]?.let(::map).orEmpty().toMutableMap()
        val shards = linkedMapOf<String, Fields>()
        val books = linkedMapOf<String, Fields>()
        for (shard in shardNames.sorted()) {
            require(shard.length == 2 && shard.all(Char::isLetterOrDigit)) { "同步分片路径无效" }
            val version = string(objects[shard])
            safeSegment(version)
            val data = remote.get("$prefix/manifests/$shard/$version.cbor")?.bodyBytes ?: error("同步分片缺失")
            val record = requireNotNull(decode(data) as? Fields)
            shards[shard] = record
            for (book in list(record.values["b"])) {
                val value = requireNotNull(book as? Fields)
                val uuid = uuid(value)
                require(shard(uuid) == shard && books.put(uuid, value) == null) { "同步分片存在重复或错位图书" }
                references(prefix, value)
            }
        }
        if (books.size <= count) return null
        val prior = previous.groupBy(::uuid)
        // 对未变更的重新上传沿用历史时间，不能用上传时间挤掉更新过的书。
        val sorted = books.values.sortedWith(compareByDescending<Fields> {
            val old = prior[uuid(it)].orEmpty().filter { old -> fingerprint(old) == fingerprint(it) }
                .minByOrNull(::changedAt)
            changedAt(old ?: it)
        }.thenBy { uuid(it) })
        val keep = sorted.take(count).map(::uuid).toSet()
        val evicted = sorted.drop(count)
        // 保留各设备仍可能持有的旧修订，不能只保护最近上传设备的基线。
        val history = (previous.filterNot { uuid(it) in keep } + evicted)
            .distinctBy { uuid(it) to revision(it) }
        val garbage = root["gc"]?.let(::map).orEmpty().toMutableMap()
        val expires = Math.addExact(now(), grace)
        val writes = linkedMapOf<String, ByteArray>()
        val activePaths = books.values.filter { uuid(it) in keep }.flatMap { references(prefix, it) }.toSet()
        evicted.flatMap { references(prefix, it) }.filterNot { it in activePaths }.forEach { garbage[it] = number(expires) }
        for ((name, record) in shards) {
            val all = list(record.values["b"])
            val retained = all.filter { uuid(requireNotNull(it as? Fields)) in keep }
            if (retained.size == all.size) continue
            garbage["$prefix/manifests/$name/${string(objects[name])}.cbor"] = number(expires)
            revisions[name] = number(Math.addExact(long(revisions[name]), 1))
            if (retained.isEmpty()) objects.remove(name)
            else {
                val version = "${now()}-${UUID.randomUUID().toString().replace("-", "")}"
                objects[name] = text(version)
                writes["$prefix/manifests/$name/$version.cbor"] = encode(Fields(record.values + ("b" to Items(retained))))
            }
        }
        garbage[historyKey(key, bytes)] = number(expires)
        activePaths.forEach { garbage.remove(it) }
        root["s"] = Items(objects.keys.sorted().map(::text))
        root["sp"] = Fields(objects)
        root["sr"] = Fields(revisions)
        root["gc"] = Fields(garbage)
        root["r"] = number(Math.addExact(long(root["r"]), 1))
        return Plan(encode(Fields(root)), writes, history.sortedWith(compareBy<Fields> { uuid(it) }
            .thenBy { revision(it).first }.thenBy { revision(it).second }.thenBy { revision(it).third }))
    }

    companion object {
        private fun safeSegment(value: String) {
            require(value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' || it.isISOControl() })
        }
        fun uuid(book: Fields): String = string(map(book.values["b"])["u"]).also(::safeSegment)
        fun revision(book: Fields): Triple<Long, Long, String> {
            val r = map(book.values["r"])
            return Triple(long(r["t"]), long(r["c"]), string(r["d"]))
        }
        private fun changedAt(book: Fields): Long = maxOf(long(book.values["m"]), revision(book).first)
        private fun shard(uuid: String) = uuid.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit).take(2).padEnd(2, '0')
        private fun references(prefix: String, book: Fields): List<String> =
            mapOf("e" to "book.epub", "c" to "cover", "k" to "bookmarks", "bn" to "banner").mapNotNull { (field, kind) ->
                val value = book.values[field] ?: return@mapNotNull null
                if (value is Atom && value.raw.contentEquals(byteArrayOf(0xf6.toByte()))) return@mapNotNull null
                val version = string(map(value)["o"])
                require(Regex("[0-9]+-[a-f0-9]{32}").matches(version)) { "同步图书对象版本无效" }
                "$prefix/${uuid(book)}/$version/$kind"
            }
        private fun fingerprint(book: Fields): String {
            fun canonical(value: Value): Value = when (value) {
                is Fields -> Fields(value.values.toSortedMap().mapValues { canonical(it.value) })
                is Items -> Items(value.values.map(::canonical))
                is Atom -> value
            }
            val fields = book.values.filterKeys { it != "r" }.mapValues { (key, value) ->
                if (key in setOf("e", "c", "k", "bn") && value is Fields) Fields(value.values - "o") else value
            }
            return MessageDigest.getInstance("SHA-256").digest(encode(canonical(Fields(fields))))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
