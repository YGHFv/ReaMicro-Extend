package com.reamicro.fix.hook

/** 同一次查询重绘时复用宿主书籍及其 Flow，避免任务进度把整组结果重新绑定。 */
internal class OnlineSearchBookCache<T : Any> {
    private data class Entry<T>(val revision: String, val result: Any, val book: T)
    private var sequence = Long.MIN_VALUE
    private val books = linkedMapOf<String, Entry<T>>()

    @Synchronized
    fun get(path: String, query: Long, revision: String, result: Any, create: () -> T): T {
        if (sequence != query) {
            books.clear()
            sequence = query
        }
        books[path]?.takeIf { it.revision == revision && it.result == result }?.let { return it.book }
        return create().also { books[path] = Entry(revision, result, it) }
    }

    @Synchronized
    fun clear() {
        books.clear()
        sequence = Long.MIN_VALUE
    }
}
