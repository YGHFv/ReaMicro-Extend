package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

/** Initialized before hooks; a rescan updates the next generation's cache only. */
internal object HostIntegrationBindings {
    @Volatile var androidView: HostRuntimeBindings.Members? = null; private set
    @Volatile var webDavRoute: HostRuntimeBindings.Members? = null; private set
    @Volatile var activity: HostRuntimeBindings.Members? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()

    fun boundDescriptors(): Map<String, Map<String, String>> = snapshots.mapValues { it.value.toMap() }

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        androidView = null; webDavRoute = null; activity = null; snapshots = emptyMap()
        val bound = linkedMapOf<String, Map<String, String>>()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            for (group in listOf("androidView", "webDavRoute", "activity")) runCatching {
                val values = query(session, group, false)
                val members = restore(group, values, loader)
                when (group) {
                    "androidView" -> androidView = members
                    "webDavRoute" -> webDavRoute = members
                    "activity" -> activity = members
                }
                bound["integration.$group"] = values.toMap()
            }.onFailure { HookRuntime.logError("ReaMicro integration.$group unavailable", it) }
        } }.onFailure { HookRuntime.logError("ReaMicro integration query session unavailable", it) }
        snapshots = bound
    }

    fun rescan(session: HostQuerySession.Session): Map<String, Map<String, String>> {
        return session.collect(listOf("androidView", "webDavRoute", "activity").map { "integration.$it" }) { key ->
            query(session, key.substringAfter('.'), true)
        }
    }

    private fun query(session: HostQuerySession.Session, group: String, force: Boolean): Map<String, String> =
        session.query("integration.$group", force,
            validate = { restore(group, it, session.loader) }, find = { bridge -> when (group) {
                "activity" -> HostIntegrationQueries.activity(bridge)
                "androidView" -> HostIntegrationQueries.androidView(bridge, session.apk,
                    requireNotNull(session.cached("reader.controls")
                        ?: HostReaderFeatures.boundDescriptors()))
                "webDavRoute" -> HostIntegrationQueries.webDavRoute(bridge, session.apk,
                    requireNotNull(session.cached("runtime.navigation")
                        ?: HostRuntimeBindings.boundDescriptors()["runtime.navigation"]))
                else -> error("Unknown integration group: $group")
            } }).values.toMap()

    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): HostRuntimeBindings.Members {
        val required = when (group) {
            "activity" -> setOf("class:activity", "method:create", "method:resume")
            "androidView" -> setOf("method:render", "class:modifier", "class:composer", "field:release")
            "webDavRoute" -> setOf("class:route", "class:scope", "class:content", "class:lambda", "constructor:route",
                "field:preset", "field:scope", "field:callback", "value:branchCount", "method:setup", "method:register", "method:token", "method:render", "method:decode")
            else -> error("Unknown integration group: $group")
        }
        check(values.keys.containsAll(required)) { "$group missing ${required - values.keys}" }
        val m = HostRuntimeBindings.Members(values, loader)
        if (group == "activity") {
            check(values.keys == required && android.app.Activity::class.java.isAssignableFrom(m.type("activity")))
            for ((role, parameters) in mapOf("create" to listOf(android.os.Bundle::class.java), "resume" to emptyList())) {
                val method = m.method(role)
                check(method.declaringClass == m.type("activity") && !Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.toList() == parameters && method.returnType == Void.TYPE)
            }
            return m
        }
        val render = m.method("render")
        if (group == "androidView") {
            val function = Class.forName("kotlin.jvm.functions.Function1", false, loader)
            check(Modifier.isStatic(render.modifiers) && render.returnType == Void.TYPE &&
                render.parameterTypes.toList() == listOf(function, m.type("modifier"), function, function, function,
                    m.type("composer"), Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            check(Modifier.isStatic(m.field("release").modifiers) && function.isAssignableFrom(m.field("release").type))
        } else {
            val route = m.type("route")
            val scope = m.field("scope")
            val preset = m.field("preset")
            val callback = m.field("callback")
            val token = m.method("token")
            val register = m.method("register")
            val decode = m.method("decode")
            check(m.constructor("route").declaringClass == route &&
                m.constructor("route").parameterTypes.toList() == listOf(String::class.java))
            check(preset.declaringClass == route && preset.type == String::class.java && !Modifier.isStatic(preset.modifiers))
            check(scope.declaringClass == m.type("content") && scope.type == m.type("scope") && !Modifier.isStatic(scope.modifiers))
            check(render.declaringClass == m.type("content") && !Modifier.isStatic(render.modifiers) &&
                render.parameterTypes.toList() == List(4) { Any::class.java } && render.returnType == Any::class.java)
            val branchCount = values.getValue("value:branchCount").toInt()
            check(branchCount >= 0 && values.keys.count { it.startsWith("field:branch.") } == branchCount)
            repeat(branchCount) { index ->
                val field = m.field("branch.$index")
                check(field.declaringClass == m.type("content") && !Modifier.isStatic(field.modifiers) &&
                    field.type in setOf(java.lang.Byte.TYPE, java.lang.Integer.TYPE, java.lang.Short.TYPE, java.lang.Boolean.TYPE))
                values.getValue("value:branch.$index").toLong()
            }
            check(callback.declaringClass == m.type("lambda") && !Modifier.isStatic(callback.modifiers) &&
                callback.type.isAssignableFrom(m.type("content")))
            check(token.parameterTypes.toList() == listOf(Class::class.java) && !token.returnType.isPrimitive)
            if (!Modifier.isStatic(token.modifiers)) {
                val receiver = m.field("tokenReceiver")
                check(Modifier.isStatic(receiver.modifiers) && token.declaringClass.isAssignableFrom(receiver.type))
            }
            check(Modifier.isStatic(register.modifiers) && register.returnType == Void.TYPE && register.parameterCount == 3 &&
                register.parameterTypes[1].isAssignableFrom(token.returnType) && register.parameterTypes[2].isAssignableFrom(m.type("lambda")))
            check(Modifier.isStatic(decode.modifiers) && decode.returnType == Any::class.java && decode.parameterCount == 2 &&
                decode.parameterTypes[1].isAssignableFrom(token.returnType) && !decode.parameterTypes[0].isPrimitive)
            check(m.method("setup").returnType == Any::class.java)
            val marker = "reamicro-contract"
            check(preset.get(m.constructor("route").newInstance(marker)) == marker)
        }
        return m
    }
}
