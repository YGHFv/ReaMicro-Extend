package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostDiscoverBindings {
    const val KEY = "discover.community"
    @Volatile var initializationAttempted = false; private set
    @Volatile var current: Contract? = null; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors(): Map<String, String>? = snapshot?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val ui = requireNotNull(HostUiBindings.boundDescriptors())
            var contract: Contract? = null
            val result = session.query(KEY,
                validate = { contract = restore(it, loader, ui) }, find = { bridge -> HostDiscoverQueries.query(bridge, apk,
                    HostAutoScanner.queriedDescriptors().orEmpty(), ui, requireNotNull(HostGraphicsBindings.boundDescriptors())) })
            current = requireNotNull(contract); snapshot = result.values.toMap()
            HookRuntime.log("ReaMicro $KEY ${if (result.cached) "cached" else "resolved"}")
        } }.onFailure { HookRuntime.logError("ReaMicro $KEY unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>, graphics: Map<String, String>): Map<String, String> =
        session.query(KEY, true, validate = { restore(it, session.loader, ui) },
            find = { HostDiscoverQueries.query(it, session.apk, base, ui, graphics) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, val loader: ClassLoader) {
        private val members = HostRuntimeBindings.Members(values, loader)
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun constructor(key: String) = members.constructor(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
        fun isEntryClick(value: Any): Boolean = type("callback").isInstance(value) &&
            (field("callbackTag").get(value) as Number).toInt() == index("callbackTag")
        fun call(key: String, vararg args: Any?): Any? = method(key).invoke(null, *args)
    }

    private fun restore(values: Map<String, String>, loader: ClassLoader, ui: Map<String, String>): Contract {
        val required = linkedSetOf<String>()
        fun keys(kind: String, names: String) { names.split(' ').forEach { required += "$kind:$it" } }
        keys("class", "composer modifier vector function0 callback fontWeight verticalAlignment lazyState")
        keys("method", "community callback text weight clickable icon image rowImage spacer paddingAll size divider aspectRatio lazyState")
        keys("constructor", "fontWeight verticalAlignment")
        keys("field", "route callbackTag")
        keys("value", "callbackTag community.composer icon.composer icon.modifier image.composer image.modifier spacer.composer spacer.modifier divider.composer divider.modifier text.composer text.modifier image.mask text.mask icon.tint divider.color clickable.callback clickable.modifier")
        keys("value", "rowImage.composer rowImage.modifier rowImage.mask")
        check(values.keys == required) { "Incomplete Discover contract: missing=${required - values.keys}; extra=${values.keys - required}" }
        val s = Contract(values.toMap(), loader)
        val modifier = s.type("modifier"); val composer = s.type("composer"); val vector = s.type("vector")
        val int = Integer.TYPE; val long = java.lang.Long.TYPE; val float = java.lang.Float.TYPE; val string = String::class.java
        fun shape(key: String, args: List<Class<*>>, result: Class<*>, static: Boolean = true) {
            val m = s.method(key)
            check(m.parameterTypes.toList() == args && m.returnType == result && Modifier.isStatic(m.modifiers) == static)
        }
        shape("icon", listOf(vector, string, modifier, long, composer, int, int), Void.TYPE)
        shape("image", listOf(vector, string, modifier, composer, int, int), Void.TYPE)
        val rowImage = s.method("rowImage")
        if (rowImage != s.method("image")) {
            val parameters = rowImage.parameterTypes.toList()
            check(Modifier.isStatic(rowImage.modifiers) && rowImage.returnType == Void.TYPE && parameters.size == 10 &&
                parameters.slice(1..2) == listOf(string, modifier) && parameters[5] == float &&
                parameters.takeLast(3) == listOf(composer, int, int) &&
                listOf(0, 3, 4, 6).all { !parameters[it].isPrimitive })
        }
        check(s.index("rowImage.mask") == rowImage.parameterCount - 1)
        shape("paddingAll", listOf(modifier, float), modifier); shape("size", listOf(modifier, float), modifier)
        shape("aspectRatio", listOf(modifier, float), modifier)
        shape("lazyState", listOf(int, composer, int, int), s.type("lazyState"))
        check(s.constructor("verticalAlignment").declaringClass == s.type("verticalAlignment") && s.constructor("verticalAlignment").parameterTypes.toList() == listOf(float))
        check(s.constructor("fontWeight").declaringClass == s.type("fontWeight") && s.constructor("fontWeight").parameterTypes.toList() == listOf(int))
        shape("divider", listOf(modifier, long, composer, int, int), Void.TYPE)
        check(values.getValue("method:weight") == ui.getValue("method:weight")) {
            "Discover and native UI must use the same row weight"
        }
        val spacer = s.method("spacer")
        check(Modifier.isStatic(spacer.modifiers) && spacer.returnType == Void.TYPE && spacer.parameterCount == 2 && spacer.parameterTypes.toSet() == setOf(composer, modifier))
        val text = s.method("text")
        check(Modifier.isStatic(text.modifiers) && text.returnType == Void.TYPE && text.parameterCount == 20)
        check(text.parameterTypes.take(5) == listOf(string, modifier, long, long, s.type("fontWeight")) &&
            text.parameterTypes.slice(9..13) == listOf(long, int, java.lang.Boolean.TYPE, int, int) &&
            text.parameterTypes.takeLast(4) == listOf(composer, int, int, int))
        val community = s.method("community")
        check(Modifier.isStatic(community.modifiers) && community.returnType == Void.TYPE && community.parameterCount == 5 &&
            !community.parameterTypes[0].isPrimitive && community.parameterTypes.drop(1) == listOf(int, java.lang.Boolean.TYPE, composer, int))
        val click = s.method("clickable")
        check(Modifier.isStatic(click.modifiers) && click.returnType == modifier && click.parameterCount == 6 &&
            click.parameterTypes.take(3) == listOf(modifier, java.lang.Boolean.TYPE, string) &&
            !click.parameterTypes[3].isPrimitive && click.parameterTypes.takeLast(2) == listOf(s.type("function0"), int))
        shape("callback", emptyList(), Any::class.java, false)
        check(s.method("callback").declaringClass == s.type("callback") && s.type("function0").isAssignableFrom(s.type("callback")))
        val tag = s.field("callbackTag")
        check(tag.declaringClass == s.type("callback") && !Modifier.isStatic(tag.modifiers) && tag.type in setOf(java.lang.Byte.TYPE, int))
        if (tag.type == java.lang.Byte.TYPE) check(s.index("callbackTag") in Byte.MIN_VALUE..Byte.MAX_VALUE)
        check(Modifier.isStatic(s.field("route").modifiers))
        for (key in listOf("community", "icon", "image", "rowImage", "spacer", "divider", "text")) {
            val parameters = s.method(key).parameterTypes
            check(parameters.indices.single { parameters[it] == composer } == s.index("$key.composer"))
            if (key != "community") check(parameters.indices.single { parameters[it] == modifier } == s.index("$key.modifier"))
        }
        check(s.index("image.mask") == 5 && s.index("text.mask") == 19 && s.index("icon.tint") == 3 && s.index("divider.color") == 1)
        check(s.index("clickable.callback") == 4 && s.index("clickable.modifier") == 0)
        return s
    }
}
