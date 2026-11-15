package com.reamicro.fix.core

import java.util.Locale

/** 与宿主 PathExt 的前处理一致，避免被 R8 删除的薄包装方法成为反射前提。 */
internal object HostHtmlNormalization {
    private val pattern = Regex("<([A-Za-z][A-Za-z0-9:-]*)(\\s[^<>]*?)?\\s*/>")
    private val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr")

    fun normalize(source: String): String = pattern.replace(source) { match ->
        val tag = match.groupValues[1]
        if (tag.lowercase(Locale.ROOT) in voids) match.value
        else "<$tag${match.groupValues[2]}></$tag>"
    }
}

/** Invoke the native parser using the current APK's exact constructor and method contract. */
internal class HostHtmlParser(@Suppress("UNUSED_PARAMETER") loader: ClassLoader) {
    fun parse(raw: String, baseUri: String): Any {
        val source = HostHtmlNormalization.normalize(raw)
        return requireNotNull(HostEpubSourceBindings.current) { "EPUB source contract unavailable" }.parse(source, baseUri)
    }
}
