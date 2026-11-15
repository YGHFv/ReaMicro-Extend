package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostTypographyBindings {
    const val KEY = "ui.typography"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()))
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro typography metrics unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, true, base, ui)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>, ui: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostTypographyQueries.query(it, session.apk, base, ui) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun typography(composer: Any): Any = requireNotNull(method("typography").invoke(null, composer))
        fun density(composer: Any): Any = requireNotNull(method("consume").invoke(composer, field("localDensity").get(null)))
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val roles = listOf("displayLarge", "displayMedium", "displaySmall", "headlineLarge", "headlineMedium", "headlineSmall",
            "titleLarge", "titleMedium", "titleSmall", "bodyLarge", "bodyMedium", "bodySmall", "labelLarge", "labelMedium", "labelSmall")
        val required = setOf("class:density", "class:style", "class:span", "class:paragraph", "class:weight", "class:typography",
            "field:span", "field:paragraph", "field:fontSize", "field:letterSpacing", "field:fontWeight", "field:lineHeight", "field:weight",
            "field:localDensity", "method:unitType", "method:unitValue", "method:toPx", "method:fontScale", "method:density", "method:typography", "method:consume") +
            roles.map { "field:typography.$it" }
        check(values.keys.containsAll(required)) { "Incomplete typography contract: ${required - values.keys}" }
        val c = Contract(values, HostRuntimeBindings.Members(values, loader))
        for (key in listOf("span", "paragraph")) check(c.field(key).let { it.declaringClass == c.type("style") &&
            !Modifier.isStatic(it.modifiers) && it.type == c.type(key) })
        for ((key, owner, type) in listOf(Triple("fontSize", "span", java.lang.Long.TYPE), Triple("letterSpacing", "span", java.lang.Long.TYPE),
            Triple("fontWeight", "span", c.type("weight")), Triple("lineHeight", "paragraph", java.lang.Long.TYPE), Triple("weight", "weight", java.lang.Integer.TYPE))) {
            check(c.field(key).let { it.declaringClass == c.type(owner) && !Modifier.isStatic(it.modifiers) && it.type == type })
        }
        for ((key, result) in listOf("unitType" to java.lang.Long.TYPE, "unitValue" to java.lang.Float.TYPE)) check(c.method(key).let {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(java.lang.Long.TYPE) && it.returnType == result })
        check(c.method("toPx").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("density") &&
            it.parameterTypes.toList() == listOf(java.lang.Long.TYPE) && it.returnType == java.lang.Float.TYPE })
        for (key in listOf("fontScale", "density")) check(c.method(key).let { !Modifier.isStatic(it.modifiers) &&
            it.declaringClass == c.type("density") && it.parameterCount == 0 && it.returnType == java.lang.Float.TYPE })
        val typography = c.method("typography")
        check(Modifier.isStatic(typography.modifiers) && typography.parameterCount == 1 && typography.returnType == c.type("typography"))
        for (role in roles) check(c.field("typography.$role").let { !Modifier.isStatic(it.modifiers) &&
            it.declaringClass == c.type("typography") && it.type == c.type("style") })
        val consume = c.method("consume")
        check(!Modifier.isStatic(consume.modifiers) && consume.declaringClass.isAssignableFrom(typography.parameterTypes[0]) &&
            consume.parameterCount == 1 && consume.returnType == Any::class.java && Modifier.isStatic(c.field("localDensity").modifiers) &&
            consume.parameterTypes[0].isAssignableFrom(c.field("localDensity").type))
        return c
    }
}
