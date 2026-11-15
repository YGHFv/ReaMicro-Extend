package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException

/**
 * 带时间预算的字符视图。仅能约束逐字符读取 CharSequence 的 JVM 正则实现；
 * Android native matcher 会先快照成 String，必须通过 capability 检查拒绝假超时。
 */
class RegexDeadlineSequence private constructor(
    private val source: CharSequence,
    private val deadlineNanos: Long,
    private val interrupted: () -> Unit,
) : CharSequence {
    companion object {
        val matcherSupportsDeadline: Boolean by lazy {
            if (System.getProperty("java.vm.name").equals("Dalvik", ignoreCase = true)) {
                // Android's native matcher cannot be interrupted through a CharSequence view.
                false
            } else {
                var reads = 0
                val probe = object : CharSequence {
                    override val length = 1
                    override fun get(index: Int): Char { reads++; return 'x' }
                    override fun subSequence(startIndex: Int, endIndex: Int) =
                        "x".subSequence(startIndex, endIndex)
                    override fun toString() = "x"
                }
                java.util.regex.Pattern.compile("x").matcher(probe).find()
                reads > 0
            }
        }
    }

    constructor(source: CharSequence, timeoutMillis: Long) : this(
        source,
        System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L,
        {
            if (Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("Regex matching interrupted")
            }
            com.script.rhino.rhinoContextOrNull?.ensureActive()
        },
    )

    override val length: Int
        get() = source.length

    override fun get(index: Int): Char {
        interrupted()
        if (System.nanoTime() > deadlineNanos) {
            throw RegexTimeoutException("正则匹配超时：表达式回溯过多，请简化表达式")
        }
        return source[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        RegexDeadlineSequence(source.subSequence(startIndex, endIndex), deadlineNanos, interrupted)

    override fun toString(): String = source.toString()
}
