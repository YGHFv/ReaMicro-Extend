package com.reamicro.fix.epub.editor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun hostStyleSheetPreview(loader: ClassLoader, file: File): JSONArray {
    val providerType = loader.loadClass("org.epub.css.StyleSheetsProvider")
    val provider = providerType.getField("INSTANCE").get(null)

    val cacheGetter = providerType.getDeclaredMethod("getMRuleSetCache").apply { isAccessible = true }
    val cache = cacheGetter.invoke(provider)
    loader.loadClass("io.github.reactivecircus.cache4k.Cache")
        .getMethod("invalidate", Any::class.java).invoke(cache, file.absolutePath)
    val sheets = providerType.getMethod("attach", Array<String>::class.java)
        .invoke(provider, arrayOf(file.absolutePath) as Any)
    fun call(target: Any, name: String): Any? =
        target.javaClass.getMethod(name).invoke(target)
    val rules = call(sheets, "getRuleSets") as? Iterable<*> ?: error("宿主 CSS 规则为空")
    val ruleType = loader.loadClass("org.epub.css.RuleSet")
    val companion = ruleType.getField("Companion").get(null)

    val merge = companion.javaClass.getMethod("merge", List::class.java, Map::class.java)
    val ruleList = rules.filterNotNull()

    val mergeCustom = companion.javaClass.getMethod("mergeCustomProperties", List::class.java, Map::class.java)
    @Suppress("UNCHECKED_CAST")
    val custom = mergeCustom.invoke(companion, ruleList, java.util.HashMap<String, String>()) as Map<String, String>
    return JSONArray().apply {
        for (rule in ruleList) {
            rule ?: continue
            val selector = call(rule, "getSelector") ?: continue
            val properties = merge.invoke(companion, listOf(rule), java.util.HashMap(custom)) as? Iterable<*> ?: emptyList<Any>()
            val rows = JSONArray()
            for (property in properties) {
                property ?: continue

                if (call(property, "isValueProvided") != true) continue
                rows.put(JSONObject()
                    .put("name", call(property, "getProperty")?.toString().orEmpty())
                    .put("summary", call(property, "getSummary")?.toString().orEmpty())
                    .put("value", call(property, "getActualValue")?.toString().orEmpty()))
            }
            put(JSONObject().put("selector", call(selector, "getName")?.toString().orEmpty()).put("rows", rows))
        }
    }
}

internal fun invalidateHostCssPreview(loader: ClassLoader, file: File) {
    val providerType = loader.loadClass("org.epub.css.StyleSheetsProvider")
    val provider = providerType.getField("INSTANCE").get(null)
    val getter = providerType.getDeclaredMethod("getMRuleSetCache").apply { isAccessible = true }
    val cache = getter.invoke(provider)
    loader.loadClass("io.github.reactivecircus.cache4k.Cache")
        .getMethod("invalidate", Any::class.java).invoke(cache, file.absolutePath)
}
