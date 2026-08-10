package com.reamicro.fix.core

/**
 * hook 安装自检。
 *
 * 背景：本模块的每个功能都依赖宿主的具体类名、方法签名与参数个数。宿主一升级，
 * 某几个 hook 会静默装不上——功能消失但日志里没有任何异常，只能靠复现去猜。
 * 2.2.0 与 2.3.0 两次回归都是这么排查的。
 *
 * 这里给每个 hook 安装动作套一层记录：谁装上了、谁失败了、失败原因是什么。
 * 启动完成后打印一行汇总，宿主升级后对比这一行就能立刻定位掉线的 hook。
 *
 * 注意：[install] 会捕获单个 hook 的异常并继续安装后续 hook。原先某个 hook 抛异常
 * 会中断整个 install() 链条、导致后面的 hook 全部丢失，这里改为逐个隔离。
 */
object HookInstallReport {

    /** 单个 hook 安装动作的结果。[name] 在同一个 [feature] 下唯一。 */
    data class Entry(
        val feature: String,
        val name: String,
        val ok: Boolean,
        val error: String?,
    ) {
        val id: String get() = "$feature.$name"
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 执行一个 hook 安装动作并记录结果，异常不外抛。
     *
     * @return 安装是否成功
     */
    fun install(feature: String, name: String, block: () -> Unit): Boolean {
        val result = runCatching(block)
        record(feature, name, result.isSuccess, result.exceptionOrNull())
        return result.isSuccess
    }

    /** 按顺序执行一组 hook 安装动作，逐个隔离异常。 */
    fun installAll(feature: String, steps: List<Pair<String, () -> Unit>>) {
        steps.forEach { (name, block) -> install(feature, name, block) }
    }

    /** 直接登记一条结果，供已经自行处理过异常的调用方使用。 */
    fun record(feature: String, name: String, ok: Boolean, error: Throwable? = null) {
        val entry = Entry(
            feature = feature,
            name = name,
            ok = ok,
            error = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" },
        )
        synchronized(lock) { entries[entry.id] = entry }
    }

    /** 当前所有记录，按登记顺序。 */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }

    /** 失败条目的 id 列表。 */
    fun failures(): List<String> = snapshot().filterNot { it.ok }.map { it.id }

    /**
     * 一行汇总，形如：
     * `hook installed 47/49, failed: [WebDavDriveHook.webDavRowIcon, ReaderHook.selection]`
     */
    fun summaryLine(): String {
        val all = snapshot()
        val ok = all.count { it.ok }
        val failed = all.filterNot { it.ok }
        return if (failed.isEmpty()) {
            "hook installed $ok/${all.size}, all ok"
        } else {
            "hook installed $ok/${all.size}, failed: ${failed.joinToString(", ") { it.id }}"
        }
    }

    /** 每个失败条目一行的明细，用于需要看原因时输出。 */
    fun failureDetails(): List<String> =
        snapshot().filterNot { it.ok }.map { "${it.id} <- ${it.error ?: "unknown"}" }

    /** 按 feature 汇总，供设置页「关于」展示。 */
    fun featureSummaries(): List<String> =
        snapshot().groupBy { it.feature }.map { (feature, list) ->
            "$feature ${list.count { it.ok }}/${list.size}"
        }

    /** 仅供测试使用。 */
    fun reset() {
        synchronized(lock) { entries.clear() }
    }
}
