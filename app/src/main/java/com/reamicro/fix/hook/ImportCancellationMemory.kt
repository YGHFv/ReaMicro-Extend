package com.reamicro.fix.hook

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/** 取消记忆的存活时长。 */
internal const val IMPORT_CANCELLATION_TTL_MS = 10 * 60_000L

/**
 * 「刚刚被用户取消过的导入」记忆。
 *
 * 为什么需要它：宿主的导入是可重发的——同一个文件会被多条目重新发起 Work
 * （`HomeViewModel.scanAutoImportDirectory` 每次扫描都会重新入队）。「取消导入」现在是
 * 「按独立导入落地一本副本、导完删掉」（见 `ReaderImportOverwriteHook.applyCancelAsIndependentCopy`），
 * 所以重入的那几次也必须被识别成"曾经取消过的那一次"，否则它们会按覆盖导入落地、把原书改掉。
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
 * 传入值凡是取不到的（空串）都会被跳过；只要还剩一个键就能记住/命中。
 *
 * **文件名不带大小**：同一次导入在三个入口拿到的是不同对象（预检是 `okio.Path`、
 * `importBook` 是 `PlatformFile`、模块自己的导入入口是缓存 `File`），大小只有部分能读到。
 * 把大小写进键里，只要有一侧读不到就会两侧对不上、取消识别失败——而"识别不出曾取消过"
 * 会让重入的导入按覆盖导入落地、把原书改掉，正是要修的那个 bug。代价是同名不同内容的文件
 * 在存续期内会互相命中，表现为"多删一次副本"，比"原书被覆盖"轻得多。大小仍然写进日志便于排查。
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

/**
 * 模块自己驱动的导入（本地书库 / WebDAV 下载完成后）在**发起导入之前**先判一次冲突的入口。
 *
 * 为什么前移：宿主的导入是「可重发 Work」，从 Hook 里取消只能中止当前那一次；实机日志显示
 * 即便把链路上每一个 `importBook` 调用都取消掉（连临时文件都删了），宿主仍会在我们 hook 不到的
 * 那一层把书写掉——它被加固过，真实现藏在 `android.os.Turlng` / `android.media.Curloust` 这类
 * 系统包名下。前移到模块自己的入口，是为了**在导入开始前就把用户的决定问出来**：选「取消导入」时
 * 把决定存成 pre-decision，整条链按"独立副本 + 导完删除"落地，用户只看到一次弹窗。
 *
 * Hook 实例由 [de.robv.android.xposed.XposedBridge] 加载模块时挂上来；没挂上（或解析不出身份）
 * 时一律放行，交回原有流程处理。
 */
internal object ModuleImportPrecheck {
    @Volatile private var hook: ReaderImportOverwriteHook? = null

    fun attach(hook: ReaderImportOverwriteHook) {
        this.hook = hook
    }

    /** 返回值目前恒为 true（问完用户就继续导入）；保留布尔返回值是为将来可能的"直接拒绝"留口子。 */
    fun precheck(epubFile: java.io.File, sourceUri: String): Boolean =
        hook?.precheckModuleImport(epubFile, sourceUri) ?: true
}

/** 书名做空白归一化，避免" 三体 "与"三体"被当成两本书。 */
internal fun String.normalizedCancellationTitle(): String = trim().replace(Regex("\\s+"), " ")

/**
 * 全模块共享的「取消导入」记忆。
 *
 * 必须是共享的、而不是各 Hook 各持一份：一次导入会经过**多个入口**——预检
 * （`EpubFileManager.import`）、`BookshelfRepository.importBook`，以及模块自己驱动的
 * WebDAV / 本地书库导入（`enqueueNativeImport` 之前）。用户在预检弹窗里点了取消，这份记忆
 * 必须能被后面这些入口读到，重入的那几次才会走同样的"独立副本 + 删除"，而不是覆盖原书。
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

}
