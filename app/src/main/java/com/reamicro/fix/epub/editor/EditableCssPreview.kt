package com.reamicro.fix.epub.editor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal fun editableCssPreview(loader: ClassLoader, file: File): JSONObject {
    val document = EpubCssEditor.read(file)
    val css = document.css
    val summaries = HashMap<String, String>()
    val nativeBySelector = HashMap<String, MutableList<JSONObject>>()

    if (document.loaded.text.length <= 256 * 1024 && css.rules.size <= 1000 &&
        !Regex("@import\\b", RegexOption.IGNORE_CASE).containsMatchIn(document.loaded.text)) {
        runCatching { hostStyleSheetPreview(loader, file) }.getOrNull()?.let { native ->
            for (i in 0 until native.length()) {
                val rule = native.getJSONObject(i)
                nativeBySelector.getOrPut(rule.optString("selector").trim()) { ArrayList() }.add(rule)
                val rows = rule.optJSONArray("rows") ?: JSONArray()
                for (j in 0 until rows.length()) {
                    val row = rows.getJSONObject(j)
                    summaries[row.optString("name").lowercase(Locale.ROOT)] = row.optString("summary")
                }
            }
        }
    }

    val latest = EpubCssEditor.load(file)
    require(latest.snapshot.fingerprint == document.loaded.snapshot.fingerprint) {
        "CSS 文件在解析期间被修改，请刷新"
    }
    val selectorCounts = css.rules.filter { it.context.isEmpty() }.groupingBy { it.selector.trim() }.eachCount()
    val output = JSONArray()
    css.rules.filter { it.declarations.isNotEmpty() || !it.hasChildren }.forEach { rule ->
        val native = nativeBySelector[rule.selector.trim()]?.singleOrNull()
            ?.takeIf { rule.context.isEmpty() && selectorCounts[rule.selector.trim()] == 1 }
        val declarationCounts = rule.declarations.groupingBy { it.property }.eachCount()
        val nativeValues = HashMap<String, String>()
        native?.optJSONArray("rows")?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                nativeValues[row.optString("name")] = row.optString("value")
            }
        }
        val rows = JSONArray()
        rule.declarations.forEach { declaration ->
            val length = declaration.valueEnd - declaration.valueStart
            val editable = length <= EpubCssEditor.MAX_VALUE_CHARS
            val rawValue = if (editable) css.value(declaration) else ""
            val preview = document.loaded.text.substring(declaration.valueStart,
                minOf(declaration.valueEnd, declaration.valueStart + 512)) + if (length > 512) "…" else ""
            val computed = nativeValues[declaration.property].orEmpty()
                .takeIf { declarationCounts[declaration.property] == 1 && it != rawValue }.orEmpty()
            rows.put(JSONObject()
                .put("id", declaration.id)
                .put("name", declaration.property)
                .put("summary", CssPropertyDescriptions.describe(declaration.property,
                    summaries[declaration.property.lowercase(Locale.ROOT)]))
                .put("comment", declaration.comment)
                .put("value", preview)
                .put("rawValue", rawValue)
                .put("important", declaration.important)
                .put("editable", editable)
                .put("computedValue", computed))
        }
        output.put(JSONObject()
            .put("id", rule.id)
            .put("selector", rule.selector)
            .put("context", rule.context.joinToString(" / "))
            .put("comment", rule.comment)
            .put("editableSelector", rule.selector.length <= 16_384)
            .put("rows", rows))
    }
    return JSONObject().put("ok", true).put("rules", output)
        .put("sourceHash", document.loaded.snapshot.fingerprint.sha256)
}
