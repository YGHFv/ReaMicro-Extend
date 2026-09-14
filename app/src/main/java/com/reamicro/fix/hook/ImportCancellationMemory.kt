package com.reamicro.fix.hook

import de.robv.android.xposed.XposedBridge
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

    /** 最近一次记录取消的时刻（0 表示从未记录），用于"刚取消过"的时间窗判定。 */
    @Volatile
    var lastRememberedAtMs: Long = 0L
        private set

    /** 记下这次取消。没有任何可用键时不记录（调用方应尽量给出文件名等兜底身份）。 */
    fun remember(keys: Collection<String>): Boolean {
        val valid = keys.filter(String::isNotBlank).distinct()
        if (valid.isEmpty()) return false
        val now = nowProvider()
        valid.forEach { cancelled[it] = now }
        lastRememberedAtMs = now
        clearExpired()
        return true
    }

    /**
     * 上一次取消是否发生在 [windowMs] 之内。
     *
     * 给"没有任何可用身份的 importBook 调用"兜底：这类调用连文件名都没有，不可能是别的书的
     * 合法导入，只可能是同一次导入动作的后续调用（宿主在钩子返回后还会以桥接/重入的形式再调
     * 几次）。用户刚点过取消，就把它们一并取消。
     */
    fun hasRecentCancellation(windowMs: Long): Boolean {
        val last = lastRememberedAtMs
        if (last == 0L) return false
        return nowProvider() - last <= windowMs
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
 * 传入值凡是取不到的（空串）都会被跳过；只要还剩一个键就能记住/命中。
 *
 * **文件名不带大小**：同一次导入在三个入口拿到的是不同对象（预检是 `okio.Path`、
 * `importBook` 是 `PlatformFile`、模块自己的导入入口是缓存 `File`），大小只有部分能读到。
 * 把大小写进键里，只要有一侧读不到就会两侧对不上、取消静默失效——而"取消失效"正是要修的
 * 那个 bug。代价是同名不同内容的文件在存续期内会互相命中，但那只表现为"多拦一次"，
 * 比"取消被忽略、书被覆盖"轻得多。大小仍然会写进日志便于排查。
 */
internal fun importCancellationKeys(
    uuid: String,
    title: String,
    uri: String,
    fileName: String,
): List<String> = buildList {
    uuid.trim().takeIf { it.isNotBlank() }?.let { add("uuid:$it") }
    title.normalizedCancellationTitle().takeIf { it.isNotBlank() }?.let { add("title:$it") }
    uri.trim().takeIf { it.isNotBlank() }?.let { add("uri:$it") }
    fileName.trim().takeIf { it.isNotBlank() }?.let { add("file:$it") }
}

/** 书名做空白归一化，避免" 三体 "与"三体"被当成两本书。 */
internal fun String.normalizedCancellationTitle(): String = trim().replace(Regex("\\s+"), " ")

/**
 * 模块为导入而复制的临时文件所在目录名（见 `importCacheFile(cacheDir, "reamicro-local-library", name)`）。
 *
 * 取消导入时要删的是**这份副本**——用户的原文件绝不能碰，所以删除前必须先用这个标记确认路径。
 */
internal const val MODULE_IMPORT_CACHE_MARKER = "reamicro-local-library"

/**
 * 这个路径是不是模块自己复制出来的待导入临时文件。
 *
 * 单独抽成可测的判定：它决定"取消导入时删哪个文件"，判错就会删到用户的原始文件。
 */
internal fun isModuleImportCachePath(path: String): Boolean =
    path.isNotBlank() && path.contains(MODULE_IMPORT_CACHE_MARKER)

/**
 * 全模块共享的「取消导入」记忆。
 *
 * 必须是共享的、而不是各 Hook 各持一份：一次导入会经过**多个入口**——预检
 * （`EpubFileManager.import`）、`BookshelfRepository.importBook`，以及模块自己驱动的
 * WebDAV / 本地书库导入（`enqueueNativeImport` 之前）。用户在预检弹窗里点了取消，
 * 取消消息必须能被后面这些入口读到，否则「取消」只在第一个入口生效。
 */
internal object ImportCancellations {
    val memory = ImportCancellationMemory()

    /** 记下这次取消，并把用到的身份键写进日志（排查"为什么取消没生效"只能靠这几行）。 */
    fun remember(uuid: String, title: String, uri: String, fileName: String, fileSize: Long): Boolean {
        val keys = importCancellationKeys(uuid, title, uri, fileName)
        val ok = memory.remember(keys)
        XposedBridge.log(
            "ReaMicro LSP import cancellation remembered ok=$ok size=$fileSize keys=${keys.joinToString(" ").take(240)}",
        )
        return ok
    }

    /**
     * 这批身份是否命中过取消。未命中时也把键打出来：键不匹配是"取消没生效"最常见的原因，
     * 而两侧键对不上的话光看命中结果根本无从判断。
     */
    fun isCancelled(uuid: String, title: String, uri: String, fileName: String, fileSize: Long): Boolean {
        val keys = importCancellationKeys(uuid, title, uri, fileName)
        val hit = memory.isCancelled(keys)
        XposedBridge.log(
            "ReaMicro LSP import cancellation lookup hit=$hit size=$fileSize keys=${keys.joinToString(" ").take(240)}",
        )
        return hit
    }

    /** 供模块自己的导入入口使用：不写日志，避免高频调用刷屏。 */
    fun peek(keys: Collection<String>): Boolean = memory.isCancelled(keys)

    /**
     * 刚刚（[windowMs] 内）有没有取消过导入。
     *
     * 给「没有任何可用身份的 importBook 调用」兜底，见
     * [ImportCancellationMemory.hasRecentCancellation]。
     */
    fun hasFreshCancellation(windowMs: Long = FRESH_CANCELLATION_WINDOW_MS): Boolean =
        memory.hasRecentCancellation(windowMs)

    /** 「刚取消过」的时间窗。取消后宿主的后续调用都在毫秒级，30 秒足够宽松又不会误伤。 */
    const val FRESH_CANCELLATION_WINDOW_MS = 30_000L

    /** 一条导入记录的身份键（模块自己的导入入口用文件名 + 来源 url）。 */
    fun keysForSource(fileName: String, uri: String): List<String> =
        importCancellationKeys(uuid = "", title = "", uri = uri, fileName = fileName)
}
