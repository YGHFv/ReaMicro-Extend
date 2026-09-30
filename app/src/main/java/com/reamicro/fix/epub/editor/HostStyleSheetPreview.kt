package com.reamicro.fix.epub.editor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Same RuleSets/merged CSSProperty data used by 1.3 EpubStyleSheetScreen.
 * On 2.3.2 the parser was moved from app.zhendong.epub to org.epub.
 * This is a read-only adapter; all edits still go through the existing text editor.
 */
internal fun hostStyleSheetPreview(loader: ClassLoader, file: File): JSONArray {
    val providerType = loader.loadClass("org.epub.css.StyleSheetsProvider")
    val provider = providerType.getField("INSTANCE").get(null)
    // Do not clear every book's shared CSS cache just to preview one stylesheet.
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
    // 2.3.2 adds a custom-property map argument to the 1.3 merge(List) API.
    val merge = companion.javaClass.getMethod("merge", List::class.java, Map::class.java)
    return JSONArray().apply {
        for (rule in rules) {
            rule ?: continue
            val selector = call(rule, "getSelector") ?: continue
            val properties = merge.invoke(companion, listOf(rule), java.util.HashMap<String, String>()) as? Iterable<*> ?: emptyList<Any>()
            val rows = JSONArray()
            for (property in properties) {
                property ?: continue
                rows.put(JSONObject()
                    .put("name", call(property, "getProperty")?.toString().orEmpty())
                    .put("summary", call(property, "getSummary")?.toString().orEmpty())
                    .put("value", call(property, "getActualValue")?.toString().orEmpty()))
            }
            put(JSONObject().put("selector", call(selector, "getName")?.toString().orEmpty()).put("rows", rows))
        }
    }
}
