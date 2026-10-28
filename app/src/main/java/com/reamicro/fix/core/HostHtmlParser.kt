package com.reamicro.fix.core

import java.io.StringReader
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

/** B2 直接走 parseInput 被内联后仍使用的 TreeBuilder，保留宿主解析器而非替换解析算法。 */
internal class HostHtmlParser(private val loader: ClassLoader) {
    fun parse(raw: String, baseUri: String): Any {
        val source = HostHtmlNormalization.normalize(raw)
        val parserType = loader.hostClass("com.fleeksoft.ksoup.parser.Parser")
        if (HostSymbols.isIdentity) {
            val companion = parserType.hostField("Companion").get(null)
            val parser = companion.javaClass.hostMethod("htmlParser").invoke(companion)
            return requireNotNull(parserType.hostMethod("parseInput", String::class.java, String::class.java)
                .invoke(parser, source, baseUri))
        }
        val treeType = loader.hostClass("com.fleeksoft.ksoup.parser.TreeBuilder")
        val tree = loader.hostClass("com.fleeksoft.ksoup.parser.HtmlTreeBuilder").getDeclaredConstructor().newInstance()
        val parser = parserType.getDeclaredConstructor(treeType).newInstance(tree)
        val lock = requireNotNull(parserType.hostField("lock").get(parser))
        val parse = treeType.hostMethod("parse", StringReader::class.java, String::class.java, parserType)
        return synchronized(lock) { requireNotNull(parse.invoke(tree, StringReader(source), baseUri, parser)) }
    }
}
