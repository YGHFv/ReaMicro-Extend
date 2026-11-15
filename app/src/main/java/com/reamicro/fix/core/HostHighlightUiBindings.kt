package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostHighlightUiBindings {
    const val KEY = "reader.highlightUi"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro highlight UI query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, base, ui, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostHighlightUiQueries.query(it, session.apk, base, ui) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun status(role: String): Any = requireNotNull(field("status.$role").get(null))
        fun sheetInsets(composer: Any): Any {
            val top = requireNotNull(method("sheet.topInsets").invoke(null, composer))
            val holder = requireNotNull(method("sheet.insetHolder").invoke(null, composer))
            val status = requireNotNull(field("sheet.statusInsets").get(holder))
            return constructor("sheet.excludeInsets").newInstance(top, status)
        }
        fun sheetNavigationBack(composer: Any, back: Any) {
            val state = requireNotNull(method("sheet.rememberNavigation").invoke(null, composer))
            method("sheet.navigationBack").invoke(null, state, true, null, back, composer, 0, index("sheet.navigationDefaults"))
        }
        fun send(receiver: Any, role: String) {
            require(type("receiver").isInstance(receiver))
            method("intent").invoke(receiver, constructor("intent").newInstance(status(role)))
        }
        fun capture(role: String, body: Any): Pair<Any, Any>? {
            if (!type("body.$role").isInstance(body)) return null
            val state = field("body.$role.status").get(body) ?: return null
            val owner = field("body.$role.owner").get(body) ?: return null
            return if (type("status").isInstance(state) && type("receiver").isInstance(owner)) state to owner else null
        }
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val required = listOf("class:receiver", "class:book", "class:composer", "class:function0", "class:function3", "class:intent", "class:status",
            "field:intent.status", "constructor:intent", "method:intent", "method:page", "method:preset", "method:height", "method:dpInt",
            "value:preset.composer", "value:preset.callers", "class:sheet.vector", "class:sheet.insets", "class:sheet.insetHolder",
            "class:sheet.navigationState", "method:sheet.close", "method:sheet.topInsets", "method:sheet.insetHolder",
            "field:sheet.statusInsets", "constructor:sheet.excludeInsets", "method:sheet.rememberNavigation",
            "method:sheet.navigationBack", "value:sheet.navigationDefaults") + (HostHighlightUiQueries.FAMILIES + "Hidden").flatMap { role ->
            listOf("class:status.$role", "field:status.$role") + if (role == "Hidden") emptyList() else listOf(
                "class:body.$role", "constructor:body.$role", "method:root.$role", "method:body.$role", "field:body.$role.status",
                "field:body.$role.owner", "value:root.$role.status", "value:body.$role.composer", "value:body.$role.height", "value:body.$role.callers")
        }
        check(values.keys.containsAll(required))
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE; val float = java.lang.Float.TYPE; val void = Void.TYPE
        val status = s.type("status"); val receiver = s.type("receiver"); val composer = s.type("composer")
        check(s.constructor("intent").let { it.declaringClass == s.type("intent") && it.parameterTypes.toList() == listOf(status) })
        check(s.field("intent.status").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("intent") && it.type == status })
        check(s.method("intent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(receiver) &&
            it.returnType == void && it.parameterCount == 1 && it.parameterTypes.single().isAssignableFrom(s.type("intent")) })
        check(s.method("page").let { Modifier.isStatic(it.modifiers) && it.returnType == void &&
            it.parameterTypes.first() == List::class.java && composer in it.parameterTypes })
        check(s.method("preset").let { Modifier.isStatic(it.modifiers) && it.returnType == void && it.parameterCount == 7 &&
            it.parameterTypes.count { p -> p == s.type("function0") } == 2 && it.parameterTypes[s.index("preset.composer")] == composer })
        check(s.index("preset.callers") in 1..16)
        for (i in 0 until s.index("preset.callers")) check(s.method("preset.caller.$i").let {
            !Modifier.isStatic(it.modifiers) && it.returnType == any && it.parameterCount in 2..4 && it.parameterTypes.all { p -> p == any }
        })
        for (role in HostHighlightUiQueries.FAMILIES + "Hidden") {
            check(status.isAssignableFrom(s.type("status.$role")))
            check(s.field("status.$role").let { Modifier.isStatic(it.modifiers) && it.type == s.type("status.$role") && it.declaringClass == it.type })
            if (role == "Hidden") continue
            check(s.method("root.$role").let { Modifier.isStatic(it.modifiers) && it.returnType == void &&
                it.parameterTypes.toList() == listOf(receiver, status) + (if (role == "EpubFamily") listOf(s.type("book")) else emptyList()) + listOf(composer, int) &&
                it.parameterTypes[s.index("root.$role.status")] == status })
            val body = s.type("body.$role")
            check(s.type("function3").isAssignableFrom(body) && s.constructor("body.$role").declaringClass == body)
            check(s.method("body.$role").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == body &&
                it.parameterTypes.toList() == List(3) { any } && it.returnType == any })
            check(s.index("body.$role.composer") in 0..2 && s.index("body.$role.height") in 1..1000)
            check(s.index("body.$role.callers") in 0..32)
            for (i in 0 until s.index("body.$role.callers")) s.method("body.$role.caller.$i")
            for ((name, kind) in listOf("status" to status, "owner" to receiver)) check(s.field("body.$role.$name").let {
                !Modifier.isStatic(it.modifiers) && it.declaringClass == body && it.type.isAssignableFrom(kind)
            })
            check(s.field("body.$role.status") != s.field("body.$role.owner"))
        }
        check(s.method("height").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
            it.parameterTypes[0] == it.returnType && it.parameterTypes[1] == float })
        check(s.method("dpInt").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(int) && it.returnType == float })
        fun signature(role: String, args: List<Class<*>>, result: Class<*>) = s.method(role).also {
            check(Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == args && it.returnType == result)
        }
        signature("sheet.close", emptyList(), s.type("sheet.vector"))
        val insets = s.type("sheet.insets")
        check(s.method("sheet.topInsets").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(composer) &&
            insets.isAssignableFrom(it.returnType) })
        signature("sheet.insetHolder", listOf(composer), s.type("sheet.insetHolder"))
        check(s.field("sheet.statusInsets").let { !Modifier.isStatic(it.modifiers) &&
            it.declaringClass == s.type("sheet.insetHolder") && insets.isAssignableFrom(it.type) })
        check(s.constructor("sheet.excludeInsets").let { insets.isAssignableFrom(it.declaringClass) &&
            it.parameterTypes.toList() == listOf(insets, insets) })
        signature("sheet.rememberNavigation", listOf(composer), s.type("sheet.navigationState"))
        signature("sheet.navigationBack", listOf(s.type("sheet.navigationState"), java.lang.Boolean.TYPE,
            s.type("function0"), s.type("function0"), composer, int, int), void)
        check(s.index("sheet.navigationDefaults") == 1 shl 2)
        return s
    }
}
