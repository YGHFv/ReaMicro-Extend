package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostGlobalFontBindings {
    const val KEY = "ui.globalFont"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()),
                requireNotNull(HostReaderExtrasBindings.boundDescriptors()["reader.fontCompletion"]), requireNotNull(HostReaderMenuBindings.boundDescriptors()), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro global font query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>,
        preview: Map<String, String>, menu: Map<String, String>) = query(session, base, ui, preview, menu, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>,
        preview: Map<String, String>, menu: Map<String, String>, force: Boolean) = session.query(KEY, force,
        validate = { restore(it, session.loader) }, find = { HostGlobalFontQueries.query(it, session.apk, base, ui, preview, menu) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        fun methods(prefix: String) = values.keys.filter { it.startsWith("method:$prefix") }.map { method(it.removePrefix("method:")) }
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        check(values.keys.containsAll(listOf("class:family", "class:style", "class:span", "class:composer", "method:text", "method:preview",
            "method:reader.ReaderScreen", "method:reader.InitEpubWindow", "method:reader.BottomSheet", "method:reader.render", "method:reader.bottomBar",
            "method:text.0", "field:style.span", "field:span.family", "value:family", "value:style", "value:composer", "value:mask", "value:familyBit")))
        for ((role, owner, type) in listOf(Triple("style.span", "style", "span"), Triple("span.family", "span", "family"))) {
            val field = s.field(role); check(field.declaringClass == s.type(owner) && field.type == s.type(type) && !Modifier.isStatic(field.modifiers))
        }
        val text = s.method("text")
        check(text.parameterTypes[s.number("family")] == s.type("family") && text.parameterTypes[s.number("style")] == s.type("style") &&
            text.parameterTypes[s.number("composer")] == s.type("composer") && s.number("mask") == text.parameterCount - 1 &&
            text.parameterTypes[s.number("mask")] == Integer.TYPE && s.number("familyBit") > 0 && s.number("familyBit").countOneBits() == 1)
        for (method in s.methods("text.")) check(Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.drop(1) == text.parameterTypes.drop(1))
        val render = s.method("reader.render")
        check(!Modifier.isStatic(render.modifiers) && render.returnType == Any::class.java && render.parameterTypes.toList() == List(4) { Any::class.java })
        for (method in s.methods("reader.").filter { it != render } + s.method("preview")) check(Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.count { it == s.type("composer") } == 1)
        return s
    }
}
