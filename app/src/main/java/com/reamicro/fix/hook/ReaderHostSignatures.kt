package com.reamicro.fix.hook

internal fun epubContainerMarksIndex(parameterTypes: Array<Class<*>>): Int {
    // 2.3.3 在 marks 前插入背景状态；按类型定位，避免把 tags 当成高亮列表。
    val index = parameterTypes.indexOfFirst { List::class.java.isAssignableFrom(it) }
    return index.takeIf { it >= 4 } ?: -1
}
