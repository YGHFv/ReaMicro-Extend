package com.reamicro.fix.hook.reader

// 只隔离包含 Android 类型的模型文件；预览算法直接编译生产源码。
internal data class SearchSnippet(val text: String, val matchStart: Int, val matchEnd: Int)
