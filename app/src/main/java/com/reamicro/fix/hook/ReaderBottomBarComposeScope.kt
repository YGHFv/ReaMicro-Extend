package com.reamicro.fix.hook

internal class ReaderBottomBarComposeScope {
    private val depth = ThreadLocal.withInitial { 0 }
    val active: Boolean get() = (depth.get() ?: 0) > 0

    fun enter() { depth.set((depth.get() ?: 0) + 1) }

    fun exit() {
        val next = (depth.get() ?: 0) - 1
        if (next > 0) depth.set(next) else depth.remove()
    }
}

internal data class ReaderBottomBarContentBinding(val receiverIndex: Int, val bookIndex: Int)

internal fun readerBottomBarContentBinding(
    name: String,
    parameterNames: List<String>,
): ReaderBottomBarContentBinding? {
    // 动画内容会脱离外层调用独立重组；按捕获参数识别，不绑定编译器生成的 lambda 序号。
    if (!name.startsWith("ReaderBottomBar\$lambda\$") ||
        "androidx.compose.runtime.Composer" !in parameterNames
    ) return null
    val receiver = parameterNames.indexOf("app.zhendong.reamicro.arch.IntentReceiver")
    val book = parameterNames.indexOf("app.zhendong.reamicro.data.db.entity.Book")
    return if (receiver >= 0 && book >= 0) ReaderBottomBarContentBinding(receiver, book) else null
}
