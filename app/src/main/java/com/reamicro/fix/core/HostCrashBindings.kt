package com.reamicro.fix.core

import android.content.ContentProvider
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object HostCrashBindings {
    const val KEY = "privacy.crash"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false)
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro crash reporting query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session) = query(session, true)
    private fun query(session: HostQuerySession.Session, force: Boolean) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostCrashQueries.query(it, session.apk) }).values.toMap()
    class Contract internal constructor(val guards: List<Pair<Method, Any?>>, val deopt: List<Method>)
    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val providers = values.getValue("value:initProviders").toInt().also { check(it in 0..8) }
        val deopt = values.getValue("value:deopt").toInt().also { check(it in 0..256) }
        val voids = listOf("androidInit", "coreInit", "ndkInit", "androidNdkInit", "preload")
        val ready = listOf("performanceProvider", "preloadProvider") + (0 until providers).map { "initProvider$it" }
        val methods = voids + ready + listOf("send", "successPredicate") + (0 until deopt).map { "deopt$it" }
        check(values.keys == (methods.map { "method:$it" } + listOf("field:success", "value:initProviders", "value:deopt")).toSet())
        val m = HostRuntimeBindings.Members(values, loader)
        voids.forEach { role -> check(m.method(role).let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE }) }
        check(m.method("preload").parameterCount == 0)
        ready.forEach { role -> check(m.method(role).let { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
            it.returnType == java.lang.Boolean.TYPE && ContentProvider::class.java.isAssignableFrom(it.declaringClass) }) }
        val send = m.method("send"); val field = m.field("success"); val predicate = m.method("successPredicate")
        check(!Modifier.isStatic(send.modifiers) && send.parameterCount == 1 && !send.returnType.isPrimitive)
        check(Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) && send.returnType.isAssignableFrom(field.type))
        check(!Modifier.isStatic(predicate.modifiers) && predicate.declaringClass == field.type && predicate.parameterCount == 0 && predicate.returnType == java.lang.Boolean.TYPE)
        val success = requireNotNull(field.get(null))
        check(predicate.invoke(success) == true)
        val guards = voids.map { m.method(it) to null } + ready.map { m.method(it) to true } + listOf(send to success)
        check(guards.map { it.first }.distinct().size == guards.size)
        return Contract(guards, (0 until deopt).map { m.method("deopt$it") })
    }
}
