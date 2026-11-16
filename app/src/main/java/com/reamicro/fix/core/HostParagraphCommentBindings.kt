package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostParagraphCommentBindings {
    const val KEY = "reader.comments"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro paragraph comment query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostParagraphCommentQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        fun fontSize(dom: Any): Float {
            val font = field("style.font").get(field("dom.style").get(dom))
            return (method("fontNumber").invoke(font) as Number).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
        }
        fun placeholder(size: Float): Any {
            val unit = method("unit").invoke(null, size) as Long
            val ctor = constructor("placeholder")
            return ctor.newInstance(*Array<Any>(ctor.parameterCount) { index ->
                if (index == number("placeholder.alignIndex")) number("placeholder.align") else unit
            })
        }
        fun image(id: String, size: Float, annotation: String): Any {
            val properties = HostParagraphCommentQueries.imageProperties(values)
            val args = arrayOfNulls<Any>(properties.size)
            for (name in properties) args[number("image.$name")] = when (name) {
                "id" -> id; "path", "name" -> "#comment"; "width", "height" -> size; "x", "y" -> 0f
                // 批注图标始终按普通行内图片处理，不能继承整页或跨页图片模式。
                "imagePageMode" -> type("imageMode").enumConstants.single { (it as Enum<*>).name == "Normal" }
                "preserveAspectRatio" -> false; "commentAnnotation" -> annotation; else -> ""
            }
            return constructor("image").newInstance(*args)
        }
        private val wireSlots: Map<String, Int> by lazy {
            val properties = HostParagraphCommentQueries.commentProperties
            val markers = properties.indices.map { "ReaMicroCommentProbe$it" }
            val decoded = requireNotNull(method("decode").invoke(null, "__reamicro_comment__" + markers.joinToString("\u001f")))
            val slots = properties.filter { field("comment.$it").type == String::class.java }.associateWith { name ->
                val value = field("comment.$name").get(decoded)
                markers.indices.single { markers[it] == value }
            }
            check(slots.values.toSet().size == slots.size)
            slots
        }
        fun encode(plainText: String, href: String, prefix: String, backRefId: String): String {
            val values = mapOf("plainText" to plainText, "imagePath" to "", "href" to href,
                "sourcePath" to "", "prefix" to prefix, "backRefId" to backRefId)
            val parts = MutableList(HostParagraphCommentQueries.commentProperties.size) { "" }
            values.forEach { (name, value) -> parts[wireSlots.getValue(name)] = value.replace("\\", "\\\\").replace("\u001f", "\\u001F") }
            val encoded = "__reamicro_comment__" + parts.joinToString("\u001f")
            val decoded = requireNotNull(method("decode").invoke(null, encoded))
            check(values.all { (name, value) -> (field("comment.$name").get(decoded) as? String).orEmpty() == value }) {
                "Native paragraph comment wire format did not preserve annotation"
            }
            return encoded
        }
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val imageProperties = HostParagraphCommentQueries.imageProperties(values)
        val required = listOf("class:dom", "class:image", "class:comment", "class:style", "class:cfi", "class:placeholder", "class:font", "class:range",
            "constructor:image", "constructor:placeholder", "method:cfiText", "method:decode", "method:unescape", "method:fontNumber", "method:unit",
            "field:dom.location", "field:dom.style", "field:dom.images", "field:dom.placeholders", "field:style.font", "field:placeholder.align",
            "value:placeholder.align", "value:placeholder.alignIndex") + imageProperties.flatMap { listOf("field:image.$it", "value:image.$it") } +
            HostParagraphCommentQueries.commentProperties.map { "field:comment.$it" }
        check(values.keys.containsAll(required))
        for ((role, owner, type) in listOf(Triple("dom.location", "dom", s.type("cfi")), Triple("dom.style", "dom", s.type("style")),
            Triple("style.font", "style", s.type("font")), Triple("dom.images", "dom", HashMap::class.java), Triple("dom.placeholders", "dom", HashMap::class.java))) {
            val field = s.field(role); check(field.declaringClass == s.type(owner) && field.type == type && !Modifier.isStatic(field.modifiers))
        }
        check(s.field("dom.images") != s.field("dom.placeholders"))
        val image = s.constructor("image")
        check(image.declaringClass == s.type("image") && image.parameterCount == imageProperties.size)
        check(imageProperties.map { s.number("image.$it") }.toSet() == image.parameterTypes.indices.toSet())
        for (name in imageProperties) {
            val type = when (name) {
                "width", "height", "x", "y" -> java.lang.Float.TYPE
                "preserveAspectRatio" -> java.lang.Boolean.TYPE
                "imagePageMode" -> s.type("imageMode").also { mode ->
                    check(mode.isEnum && mode.enumConstants.count { (it as Enum<*>).name == "Normal" } == 1)
                }
                else -> String::class.java
            }
            val field = s.field("image.$name")
            check(field.declaringClass == s.type("image") && !Modifier.isStatic(field.modifiers) && field.type == type && image.parameterTypes[s.number("image.$name")] == type)
        }
        for (name in HostParagraphCommentQueries.commentProperties) {
            val field = s.field("comment.$name")
            check(field.declaringClass == s.type("comment") && !Modifier.isStatic(field.modifiers) &&
                field.type == (if (name == "anchorFontSizeSp") java.lang.Float::class.java else String::class.java))
        }
        val placeholder = s.constructor("placeholder")
        check(placeholder.declaringClass == s.type("placeholder") && placeholder.parameterCount == 3 &&
            placeholder.parameterTypes.count { it == java.lang.Long.TYPE } == 2 && placeholder.parameterTypes[s.number("placeholder.alignIndex")] == Integer.TYPE)
        check(s.field("placeholder.align").declaringClass == s.type("placeholder") && s.field("placeholder.align").type in setOf(java.lang.Byte.TYPE, Integer.TYPE))
        check(s.method("fontNumber").let { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == java.lang.Float.TYPE && it.declaringClass.isAssignableFrom(s.type("font")) })
        check(s.method("cfiText").let { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == String::class.java && it.declaringClass == s.type("cfi") })
        check(s.method("unit").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(java.lang.Float::class.java) && it.returnType == java.lang.Long.TYPE })
        check(s.method("decode").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(String::class.java) && it.returnType == s.type("comment") })
        check(s.method("unescape").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 2 && it.parameterTypes.toSet() == setOf(Integer.TYPE, List::class.java) && it.returnType == String::class.java })
        return s
    }
}
