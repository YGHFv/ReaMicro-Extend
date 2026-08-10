package com.reamicro.fix.hook.reader

import java.io.File
import com.reamicro.fix.hook.reader.*

// 从 ReaderHook 提升出来的纯工具函数。
//
// 它们不依赖 hook 状态，但被同样提升到本子包的成员扩展函数调用，留在类里就调不到。
internal fun sameSearchContentPath(relative: String, href: String): Boolean {
    val left = normalizePath(relative).substringBefore('#')
    val right = normalizePath(href).substringBefore('#')
    if (left.isBlank() || right.isBlank()) return false
    return left == right ||
        left.endsWith("/$right") ||
        right.endsWith("/$left") ||
        left.substringAfterLast('/') == right.substringAfterLast('/')
}

internal fun relativePath(root: File, file: File): String {
    val rootPath = (root.canonicalFileSafe() ?: root).absolutePath.trimEnd(File.separatorChar)
    val filePath = (file.canonicalFileSafe() ?: file).absolutePath
    return filePath.removePrefix(rootPath).trimStart(File.separatorChar)
}

internal fun normalizePath(value: String): String =
    value.replace('\\', '/').trimStart('/')
