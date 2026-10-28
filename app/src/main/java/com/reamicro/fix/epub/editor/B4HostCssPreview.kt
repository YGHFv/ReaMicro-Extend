package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostReflect
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentMap

/** 复用 B4 原生 attach/merge，只恢复被内联的缓存单键移除和薄包装访问器。 */
internal class B4HostCssPreview(private val loader: ClassLoader) {
    private val reflect = HostReflect(loader)
    private val provider = loader.hostClass("org.epub.css.StyleSheetsProvider")
    private val cacheType = loader.hostClass("io.github.reactivecircus.cache4k.RealCache")
    private val trim = loader.hostStaticMethod(
        "app.zhendong.reamicro.arch.extensions.StringExtKt", "cssTrim", String::class.java,
    )
    private val merge = loader.hostStaticMethod(
        "org.epub.css.RuleSet\$Companion", "merge", ArrayList::class.java, Map::class.java,
    )
    private val registry = loader.hostClass("org.epub.css.CssRegistry").hostField("properties").get(null) as Map<*, *>
    private val unit = loader.hostClass("org.epub.css.property.CSSUnit")

    private fun cache(): Any {
        val lazy = requireNotNull(provider.hostField("mRuleSetCache\$delegate").get(null))
        return requireNotNull(lazy.javaClass.hostMethod("getValue").invoke(lazy)).also {
            check(cacheType.isInstance(it)) { "Unexpected CSS cache" }
        }
    }

    fun invalidate(file: File) {
        val cache = cache()
        // 与旧 RealCache.invalidate 相同：先过期处理，再移除单键及两个索引队列。
        cacheType.hostMethod("expireEntries").invoke(cache)
        val holder = requireNotNull(cacheType.hostField("cacheEntries").get(cache))
        @Suppress("UNCHECKED_CAST")
        val entries = holder.javaClass.hostField("entries").get(holder) as ConcurrentMap<Any, Any>
        B4CssCompatibility.invalidate(entries, file.absolutePath) { removed ->
            for (queueName in listOf("writeQueue", "accessQueue")) {
                val queue = cacheType.hostField(queueName).get(cache) ?: continue
                queue.javaClass.getMethod("remove", Any::class.java).invoke(queue, removed)
            }
        }
        // B4 已内联删除 eventListener/onEvent，不能伪造通知或清空其他 CSS 项。
    }

    private fun cssTrim(value: String): String = trim.invoke(null, value) as String
    private fun value(target: Any, getter: String) = reflect.accessor(target, getter)

    fun preview(file: File): JSONArray {
        invalidate(file)
        val sheets = requireNotNull(loader.hostStaticMethod(
            "org.epub.css.StyleSheetsProvider", "attach", Array<String>::class.java,
        ).invoke(null, arrayOf(file.absolutePath) as Any))
        val rules = (value(sheets, "getRuleSets") as List<*>).filterNotNull()
        val sorted = rules.sortedBy { rule ->
            (value(requireNotNull(value(rule, "getSelector")), "getLevel") as Number).toInt()
        }
        val declarations = sorted.map { rule ->
            (value(rule, "getDeclarations") as List<*>).filterNotNull().map { declaration ->
                (value(declaration, "getProperty") as String) to (value(declaration, "getValue") as String)
            }
        }
        val custom = B4CssCompatibility.customProperties(declarations, ::cssTrim)
        return JSONArray().apply {
            for (rule in rules) {
                val selector = requireNotNull(value(rule, "getSelector"))
                val properties = merge.invoke(null, arrayListOf(rule), HashMap(custom)) as Iterable<*>
                val rows = JSONArray()
                for (property in properties.filterNotNull()) {
                    val name = property.javaClass.hostMethod("getProperty").invoke(property) as String
                    if (!isValueProvided(property, registry[name])) continue
                    val actual = property.javaClass.hostMethod("getActualValue").invoke(property)
                    rows.put(JSONObject().put("name", name).put("summary", CssPropertyDescriptions.describe(name, null))
                        .put("value", actual?.toString().orEmpty()))
                }
                put(JSONObject().put("selector", value(selector, "getName") as String).put("rows", rows))
            }
        }
    }

    private fun isValueProvided(property: Any, default: Any?): Boolean {
        if (default == null) return false
        val initial = property.javaClass.hostMethod("getInitialValue").invoke(property)
        val baseline = default.javaClass.hostMethod("getInitialValue").invoke(default) ?: return false
        return if (unit.isInstance(initial) && unit.isInstance(baseline)) {
            (reflect.accessor(requireNotNull(initial), "getNumber") as Number).toFloat() !=
                (reflect.accessor(baseline, "getNumber") as Number).toFloat()
        } else initial != baseline
    }

}

/** 旧版被内联的薄包装；传入宿主 cssTrim，不另建 CSS 解析或变量求值算法。 */
internal object B4CssCompatibility {
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
