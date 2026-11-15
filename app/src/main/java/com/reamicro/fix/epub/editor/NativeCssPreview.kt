package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostEpubServicesBindings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentMap

/** Native attach/merge with exact bindings for inlined record and cache accessors. */
internal class NativeCssPreview(loader: ClassLoader) {
    private val host = requireNotNull(HostEpubServicesBindings.css) { "宿主 CSS 预览接口尚未解析" }
    init { check(host.type("provider").classLoader === loader) }
    private val registry get() = host.required("registry", null) as Map<*, *>
    private fun cache(): Any = requireNotNull(host.method("lazy.get").invoke(host.required("provider.cache", null))).also {
        check(host.type("cache").isInstance(it)) { "Unexpected CSS cache" }
    }
    fun invalidate(file: File) {
        val cache = cache()
        host.method("cache.expire").invoke(cache)
        val holder = host.required("cache.holder", cache)
        @Suppress("UNCHECKED_CAST")
        val entries = host.required("cache.entries", holder) as ConcurrentMap<Any, Any>
        NativeCssCompatibility.invalidate(entries, file.absolutePath) { removed ->
            for (i in 0..1) {
                @Suppress("UNCHECKED_CAST")
                val queue = host.value("cache.queue.$i", cache) as? MutableCollection<Any> ?: continue
                queue.remove(removed)
            }
        }
    }
    private fun cssTrim(value: String): String = host.method("trim").invoke(null, value) as String
    fun preview(file: File): JSONArray {
        invalidate(file)
        val sheets = requireNotNull(host.method("attach").invoke(null, arrayOf(file.absolutePath) as Any))
        val rules = (host.required("sheets.rules", sheets) as List<*>).filterNotNull()
        val sorted = rules.sortedBy { rule -> host.field("selector.level").getInt(host.required("rule.selector", rule)) }
        val declarations = sorted.map { rule ->
            (host.required("rule.declarations", rule) as List<*>).filterNotNull().map { declaration ->
                host.text("declaration.name", declaration) to host.text("declaration.value", declaration)
            }
        }
        val custom = NativeCssCompatibility.customProperties(declarations, ::cssTrim)
        return JSONArray().apply {
            for (rule in rules) {
                val selector = host.required("rule.selector", rule)
                val properties = host.method("merge").invoke(null, arrayListOf(rule), HashMap(custom)) as Iterable<*>
                val rows = JSONArray()
                for (property in properties.filterNotNull()) {
                    check(host.type("property").isInstance(property))
                    val name = host.method("property.name").invoke(property) as String
                    if (!isValueProvided(property, registry[name])) continue
                    val actual = host.method("property.actual").invoke(property)
                    rows.put(JSONObject().put("name", name).put("summary", CssPropertyDescriptions.describe(name, null))
                        .put("value", actual?.toString().orEmpty()))
                }
                put(JSONObject().put("selector", host.text("selector.name", selector)).put("rows", rows))
            }
        }
    }
    private fun isValueProvided(property: Any, default: Any?): Boolean {
        if (default == null) return false
        check(host.type("property").isInstance(default))
        val initial = host.method("property.initial").invoke(property)
        val baseline = host.method("property.initial").invoke(default) ?: return false
        return if (host.type("unit").isInstance(initial) && host.type("unit").isInstance(baseline)) {
            (host.method("unit.number").invoke(initial) as Number).toFloat() !=
                (host.method("unit.number").invoke(baseline) as Number).toFloat()
        } else initial != baseline
    }
}

/** 用宿主 cssTrim 处理自定义属性，保留其 CSS 值裁剪规则。 */
internal object NativeCssCompatibility {
    fun invalidate(entries: ConcurrentMap<Any, Any>, key: String, unlink: (Any) -> Unit) {
        entries.remove(key)?.let(unlink)
    }

    fun customProperties(
        declarations: List<List<Pair<String, String>>>, trim: (String) -> String,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val important = hashMapOf<String, Boolean>()
        for (rule in declarations) {
            for ((property, raw) in rule) {
                if (!property.startsWith("--")) continue
                val isImportant = raw.endsWith("!important")
                if (important[property] == true && !isImportant) continue
                result[property] = trim(raw.removeSuffix("!important"))
                important[property] = isImportant
            }
        }
        return result
    }
}
