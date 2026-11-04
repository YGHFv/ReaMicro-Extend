package com.reamicro.fix.hook

/** 只释放模块持有的 family 引用，不销毁宿主仍在排版使用的字体对象。 */
internal class ReaderFontFamilyCache<T : Any>(private val capacity: Int = 8) {
    init { require(capacity > 0) }
    private val entries = LinkedHashMap<String, T>(capacity, .75f, true)
    private var generation = 0L

    fun getOrLoad(key: String, load: () -> T?): T? {
        val ticket = synchronized(this) {
            entries[key]?.let { return it }
            generation
        }
        val value = load() ?: return null
        return synchronized(this) {
            // 内存清理期间完成的加载可交还当前调用者，但不能重新填回旧缓存。
            if (ticket != generation) value else entries[key] ?: value.also {
                entries[key] = it
                while (entries.size > capacity) {
                    entries.entries.iterator().run { next(); remove() }
                }
            }
        }
    }

    @Synchronized fun clear() { generation++; entries.clear() }
    @Synchronized fun size(): Int = entries.size
}
