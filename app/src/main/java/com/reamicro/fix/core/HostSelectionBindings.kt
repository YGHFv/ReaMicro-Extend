package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostSelectionBindings {
    const val KEY = "reader.selection"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()))
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro selection contract unavailable", it) }
        current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, true, base, ui)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>, ui: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostSelectionQueries.query(it, session.apk, base, ui) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun constructor(key: String) = members.constructor(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
        fun title(action: Any) = field("action.title").get(action) as String
        fun icon(action: Any): Any = requireNotNull(field("action.icon").get(action))
        fun callback(action: Any): Any = requireNotNull(field("action.onClick").get(action))
        fun action(title: String, icon: Any, callback: Any): Any {
            val args = arrayOfNulls<Any>(3)
            args[index("action.title")] = title
            args[index("action.icon")] = icon
            args[index("action.onClick")] = callback
            return constructor("action").newInstance(*args)
        }
        fun matches(role: String, closure: Any): Boolean = method("$role.content").declaringClass.isInstance(closure) &&
            (index("$role.branchCount") == 0 || (field("$role.tag").get(closure) as Number).toLong() == values.getValue("value:$role.tag").toLong())
        fun logicalDescriptors(): Map<String, String> = mapOf(
            "class:org.epub.ui.EpubSelectionController" to values.getValue("class:controller"),
            "method:org.epub.ui.EpubSelectionController#getSelection" to values.getValue("method:selection"),
            "method:org.epub.ui.EpubSelectionController#selectedPayload" to values.getValue("method:payload"),
            "method:org.epub.ui.EpubSelectionController#selectedText" to values.getValue("method:text"),
            "method:org.epub.ui.EpubSelectionController#clearSelection" to values.getValue("method:clear"),
            "method:org.epub.ui.BodyKt#SelectionBubbleMenu" to values.getValue("method:bubble"),
        )
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = setOf("class:controller", "class:snapshot", "class:payload", "class:action", "class:vector", "class:function0",
            "constructor:controller", "constructor:action", "method:begin", "method:selection", "method:payload", "method:text", "method:clear", "method:bubble", "method:item", "method:icon", "method:editIcon") +
            listOf("title", "icon", "onClick").flatMap { listOf("field:action.$it", "value:action.$it") } +
            listOf("startCfi", "endCfi", "quote").map { "field:payload.$it" } +
            listOf("anchor", "focus", "roleId").map { "field:snapshot.$it" } +
            listOf("floating", "inline").flatMap { listOf("method:$it.content", "constructor:$it.content", "field:$it.actions", "field:$it.tint", "value:$it.branchCount") } +
            "field:floating.arrow"
        check(v.keys.containsAll(required)) { "Incomplete selection contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        fun field(key: String, owner: Class<*>, type: Class<*>) = check(c.field(key).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == owner && it.type == type })
        check(c.constructor("action").declaringClass == c.type("action") && c.constructor("action").parameterCount == 3)
        for ((key, type) in listOf("title" to String::class.java, "icon" to c.type("vector"), "onClick" to c.type("function0"))) {
            field("action.$key", c.type("action"), type)
            check(c.index("action.$key") in 0..2 && c.constructor("action").parameterTypes[c.index("action.$key")] == type)
        }
        check(listOf("title", "icon", "onClick").map { c.index("action.$it") }.toSet() == setOf(0, 1, 2))
        check(c.constructor("controller").declaringClass == c.type("controller") && c.constructor("controller").parameterCount == 0)
        for (key in listOf("startCfi", "endCfi", "quote")) field("payload.$key", c.type("payload"), String::class.java)
        val anchor = c.field("snapshot.anchor")
        field("snapshot.anchor", c.type("snapshot"), anchor.type)
        field("snapshot.focus", c.type("snapshot"), anchor.type)
        field("snapshot.roleId", c.type("snapshot"), String::class.java)
        for ((key, result) in listOf("selection" to c.type("snapshot"), "payload" to c.type("payload"), "text" to String::class.java, "clear" to Void.TYPE))
            check(c.method(key).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("controller") && it.parameterCount == 0 && it.returnType == result })
        check(c.method("begin").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("controller") &&
            it.parameterTypes.toList() == listOf(java.lang.Long.TYPE) && it.returnType == java.lang.Boolean.TYPE })
        val bubble = c.method("bubble")
        val item = c.method("item")
        check(Modifier.isStatic(bubble.modifiers) && bubble.returnType == Void.TYPE && bubble.parameterCount == 7 &&
            bubble.parameterTypes.take(4) == listOf(List::class.java, java.lang.Boolean.TYPE, java.lang.Integer.TYPE, java.lang.Boolean.TYPE) &&
            bubble.parameterTypes[6] == java.lang.Integer.TYPE)
        check(Modifier.isStatic(item.modifiers) && item.declaringClass == bubble.declaringClass && item.returnType == Void.TYPE &&
            item.parameterTypes.toList() == listOf(String::class.java, c.type("vector"), java.lang.Long.TYPE, c.type("function0"),
                bubble.parameterTypes[4], bubble.parameterTypes[5], java.lang.Integer.TYPE))
        check(c.method("icon").let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(c.type("vector"), String::class.java, bubble.parameterTypes[4], java.lang.Long.TYPE,
                bubble.parameterTypes[5], java.lang.Integer.TYPE, java.lang.Integer.TYPE) })
        check(c.method("editIcon").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == c.type("vector") })
        for (role in listOf("floating", "inline")) {
            val method = c.method("$role.content")
            val owner = method.declaringClass
            check(!Modifier.isStatic(method.modifiers) && method.parameterTypes.toList() == List(2) { Any::class.java } && method.returnType == Any::class.java)
            check(c.constructor("$role.content").declaringClass == owner)
            check(c.field("$role.actions").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == owner && it.type.isAssignableFrom(List::class.java) })
            field("$role.tint", owner, java.lang.Long.TYPE)
            check(c.index("$role.branchCount") in 0..1)
            if (c.index("$role.branchCount") == 1) {
                val tag = c.field("$role.tag")
                check(!Modifier.isStatic(tag.modifiers) && tag.declaringClass == owner && tag.type in setOf(java.lang.Byte.TYPE, java.lang.Integer.TYPE))
                val value = v.getValue("value:$role.tag").toLong()
                val range = if (tag.type == java.lang.Byte.TYPE) Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() else Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                check(value in range)
            }
        }
        field("floating.arrow", c.method("floating.content").declaringClass, java.lang.Boolean.TYPE)
        return c
    }
}
