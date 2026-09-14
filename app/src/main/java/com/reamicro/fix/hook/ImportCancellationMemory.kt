package com.reamicro.fix.hook

import java.util.concurrent.ConcurrentHashMap

/** 取消记忆的存活时长。 */
internal const val IMPORT_CANCELLATION_TTL_MS = 10 * 60_000L

/**
 * 「刚刚被用户取消过的导入」记忆。
 *
 * 为什么需要它：模块是靠**抛异常**中止导入的，而宿主的导入是可重发的——同一个文件会被
 * 多条目重新发起 Work（`HomeViewModel.scanAutoImportDirectory` 每次扫描都会重新入队），
 * 异常只让那一次 Work 变成 `WorkStatus.Error`，下一次照样重来。所以"取消"必须记在
 * **文件身份**上，而不是"这一次调用"上。
 *
 * 为什么是**多键并集**：同一个文件在不同车道解析出的身份并不一致——预检（`EpubFileManager.import`）
 * 拿到的是解包路径，`importBook` 拿到的是 PlatformFile，两条链路上 uuid / 书名 / uri 未必都能
 * 解析出来。此前只存"uuid 优先、否则书名"这一个键，两次解析不一致就直接失配，表现为
 * 「点了取消导入，书还是被导入/更新了」。这里改成存全部可用身份、查询时任一命中即算取消。
 *
 * 键带命名空间前缀，避免 uuid 恰好等于书名这类跨类型撞键。
 */
internal class ImportCancellationMemory(
    private val ttlMs: Long = IMPORT_CANCELLATION_TTL_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val cancelled = ConcurrentHashMap<String, Long>()

    /** 记下这次取消。没有任何可用键时不记录（调用方应尽量给出文件名等兜底身份）。 */
    fun remember(keys: Collection<String>): Boolean {
        val valid = keys.filter(String::isNotBlank).distinct()
        if (valid.isEmpty()) return false
        val now = nowProvider()
        valid.forEach { cancelled[it] = now }
        clearExpired()
        return true
    }

    /** 这批身份里是否有任一命中最近的取消。 */
    fun isCancelled(keys: Collection<String>): Boolean {
        clearExpired()
        return keys.any { it.isNotBlank() && cancelled.containsKey(it) }
    }

    /** 用户明确要重新导入时清掉记忆，避免继续拦截。 */
    fun forget(keys: Collection<String>) {
        keys.forEach { cancelled.remove(it) }
    }

    fun clear() {
        cancelled.clear()
    }

    /** 仅供测试观察当前规模。 */
    internal fun size(): Int {
        clearExpired()
        return cancelled.size
    }

    private fun clearExpired() {
        val now = nowProvider()
        cancelled.entries.removeAll { (_, at) -> now - at > ttlMs }
    }
}

/**
 * 由导入的各路身份拼出取消键。
 *
 * 传入值凡是取不到的（空串、0、负数）都会被跳过；只要还剩一个键就能记住/命中。
 * 文件名与大小合成一个键（`file:<名>|<大小>`），因为同名不同大小的文件不是同一个文件。
 */
internal fun importCancellationKeys(
    uuid: String,
    title: String,
    uri: String,
    fileName: String,
    fileSize: Long,
): List<String> = buildList {
    uuid.trim().takeIf { it.isNotBlank() }?.let { add("uuid:$it") }
    title.normalizedCancellationTitle().takeIf { it.isNotBlank() }?.let { add("title:$it") }
    uri.trim().takeIf { it.isNotBlank() }?.let { add("uri:$it") }
    fileName.trim().takeIf { it.isNotBlank() }?.let { name ->
        add(if (fileSize > 0L) "file:$name|$fileSize" else "file:$name")
    }
}

/** 书名做空白归一化，避免" 三体 "与"三体"被当成两本书。 */
internal fun String.normalizedCancellationTitle(): String = trim().replace(Regex("\\s+"), " ")
