package com.reamicro.fix.core

import android.view.KeyEvent
import android.view.View
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderGuardBindings {
    const val BACK = "reader.backKey"
    const val TYPE = "reader.typographyGuard"
    @Volatile var backKey: Contract? = null; private set
    @Volatile var typography: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        backKey = null; typography = null; snapshots = emptyMap()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            for (key in listOf(BACK, TYPE)) runCatching {
                val values = query(session, key, false, HostAutoScanner.queriedDescriptors().orEmpty())
                val contract = restore(key, values, loader)
                if (key == BACK) backKey = contract else typography = contract
                snapshots = snapshots + (key to values)
            }.onFailure { HookRuntime.logError("ReaMicro $key unavailable", it) }
        } }.onFailure { HookRuntime.logError("ReaMicro reader guard session unavailable", it) }
        backKey?.let { c -> HostSymbols.installFeatureDescriptors(mapOf(
            "class:org.epub.ui.EpubSelectionController" to c.values.getValue("class:selection"),
            "method:org.epub.ui.EpubSelectionController#getSelection" to c.values.getValue("method:selection"),
        ) + c.values.filterKeys { it.startsWith("class:status.") }.mapKeys {
            "class:app.zhendong.reamicro.ui.reader.UiStatus\$${it.key.substringAfter('.')}"
        } + c.values.filterKeys { it.startsWith("class:sheet.") }.mapKeys {
            "class:app.zhendong.reamicro.ui.reader.UiSheetStatus\$${it.key.substringAfter('.')}"
        } + c.values.filterKeys { it.startsWith("field:sheet.") }.mapKeys {
            "field:app.zhendong.reamicro.ui.reader.UiSheetStatus\$${it.key.substringAfter('.')}#INSTANCE"
        }) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>): Map<String, Map<String, String>> =
        session.collect(listOf(BACK, TYPE)) { key -> query(session, key, true, base) }
    private fun query(session: HostQuerySession.Session, key: String, force: Boolean, base: Map<String, String>) =
        session.query(key, force, validate = { restore(key, it, session.loader) },
            find = { bridge -> if (key == BACK) HostReaderGuardQueries.backKey(bridge, base) else HostReaderGuardQueries.typography(bridge, session.apk) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun constructor(key: String) = members.constructor(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
        fun callers() = values.keys.filter { it.startsWith("method:caller.") }.map { method(it.removePrefix("method:")) }
        fun statusName(status: Any): String? = values.keys.filter { it.startsWith("class:status.") }
            .firstOrNull { type(it.removePrefix("class:")).isInstance(status) }?.substringAfter('.')
    }
    private fun restore(key: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val required = if (key == BACK) setOf("method:key", "field:owner", "class:vm", "class:state", "method:currentState",
            "field:status", "field:sheet", "class:hidden", "class:selection", "class:snapshot", "method:selection")
        else setOf("class:config", "constructor:config", "field:textSize", "field:lineHeight", "value:textSize", "value:lineHeight", "method:caller.0")
        check(values.keys.containsAll(required)) { "Incomplete reader guard $key: ${required - values.keys}" }
        val c = Contract(values, HostRuntimeBindings.Members(values, loader))
        if (key == BACK) {
            val m = c.method("key")
            check(!Modifier.isStatic(m.modifiers) && m.parameterTypes.toList() == listOf(KeyEvent::class.java) && m.returnType == java.lang.Boolean.TYPE)
            val owner = c.field("owner")
            check(owner.declaringClass == m.declaringClass && !Modifier.isStatic(owner.modifiers) && View::class.java.isAssignableFrom(owner.type))
            val current = c.method("currentState")
            check(!Modifier.isStatic(current.modifiers) && current.parameterCount == 0 && current.declaringClass.isAssignableFrom(c.type("vm")) &&
                current.returnType.isAssignableFrom(c.type("state")))
            for (f in listOf("status", "sheet")) check(c.field(f).declaringClass == c.type("state") && !Modifier.isStatic(c.field(f).modifiers))
            check(c.field("sheet").type.isAssignableFrom(c.type("hidden")))
            for (label in listOf("Reader", "Menu", "Catalog", "Font", "Highlight", "MarginEditor", "Theme", "TypeSetting", "EinkSettings"))
                check(c.field("status").type.isAssignableFrom(c.type("status.$label")))
            for (label in listOf("Hidden", "BuildInFamily", "EpubFamily", "UserFamily")) {
                val type = c.type("sheet.$label")
                check(c.field("sheet").type.isAssignableFrom(type) && c.field("sheet.$label").let {
                    it.declaringClass == type && it.type == type && Modifier.isStatic(it.modifiers) })
            }
            val selection = c.method("selection")
            check(!Modifier.isStatic(selection.modifiers) && selection.declaringClass == c.type("selection") && selection.parameterCount == 0 && selection.returnType == c.type("snapshot"))
        } else {
            val constructor = c.constructor("config")
            check(constructor.declaringClass == c.type("config"))
            check(c.index("textSize") != c.index("lineHeight"))
            for (property in listOf("textSize", "lineHeight")) {
                val f = c.field(property)
                check(f.declaringClass == c.type("config") && !Modifier.isStatic(f.modifiers) && f.type == java.lang.Float.TYPE &&
                    c.index(property) in constructor.parameterTypes.indices && constructor.parameterTypes[c.index(property)] == java.lang.Float.TYPE)
            }
            check(c.callers().isNotEmpty())
        }
        return c
    }
}
