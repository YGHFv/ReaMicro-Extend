package com.reamicro.fix.hook

import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.XposedBridge
import java.security.MessageDigest

internal fun ReaderHook.hostIndexedSearchText(raw: String, base: CfiBase?): IndexedSearchText {
    if (base == null) return IndexedSearchText("", emptyList())
    return runCatching {
        val builder = searchSourceBuilder ?: HostSearchIndexBuilder(classLoader).also { searchSourceBuilder = it }
        builder.index(raw, base)
    }.getOrElse {
        if (it is InterruptedException || it is java.util.concurrent.CancellationException) throw it
        XposedBridge.log("ReaMicro search source indexing failed type=${it.javaClass.simpleName}")

        throw IllegalStateException("正文索引失败，请确认模块与阅微版本匹配后重新搜索", it)
    }
}

internal fun searchSourceDigest(raw: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) { bytes.forEach { val n = it.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) } }
}
