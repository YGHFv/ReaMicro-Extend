package com.reamicro.fix.core

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostCompositionBindings {
    const val KEY = "runtime.composition"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, requireNotNull(HostUiBindings.boundDescriptors()))
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro composition reload query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, ui: Map<String, String>) = query(session, true, ui)
    private fun query(session: HostQuerySession.Session, force: Boolean, ui: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostCompositionQueries.query(it, session.apk, ui) }).values.toMap()
    class Contract internal constructor(
        private val members: HostRuntimeBindings.Members,
        private val values: Map<String, String>,
    ) {
        fun type(role: String) = members.type(role)
        fun field(role: String) = members.field(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        private fun arguments(role: String, context: Context?): Array<Any> {
            val arguments = values.getValue("value:${role}Arguments").split(',')
            return constructor(role).parameterTypes.mapIndexed { index, type ->
                val value = arguments[index]
                when (type) {
                    Context::class.java -> requireNotNull(context)
                    java.lang.Boolean.TYPE -> value.toLong() != 0L
                    java.lang.Byte.TYPE -> value.toByte()
                    java.lang.Short.TYPE -> value.toShort()
                    java.lang.Character.TYPE -> value.toInt().toChar()
                    Integer.TYPE -> value.toInt()
                    java.lang.Long.TYPE -> value.toLong()
                    else -> error("Unsupported native navigation constructor type: $type")
                }
            }.toTypedArray()
        }
        fun saveNavigation(controller: Any): Bundle? {
            check(type("controller").isInstance(controller))
            val callback = constructor("navigationSave").newInstance(*arguments("navigationSave", null))
            return method("navigationSave").invoke(callback, null, controller) as? Bundle
        }
        fun restoreNavigation(context: Context, snapshot: Bundle): Any {
            val callback = constructor("navigationRestore").newInstance(*arguments("navigationRestore", context))
            return requireNotNull(method("navigationRestore").invoke(callback, snapshot)).also {
                check(type("controller").isInstance(it))
            }
        }
    }
    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val keys = listOf("activity", "root", "wrapper", "callback", "function2", "controller").map { "class:$it" } +
            listOf("root", "wrapper", "navigationSave", "navigationRestore").map { "constructor:$it" } +
            listOf("setContent", "dispose", "stateGet", "rememberNavigation", "navigationFactory",
                "navigationSave", "navigationRestore").map { "method:$it" } +
            listOf("state", "callbackActivity", "navCache", "key", "tracked", "callback").map { "field:$it" } +
            listOf("navigationSaveArguments", "navigationRestoreArguments").map { "value:$it" }
        check(values.keys == keys.toSet()) { "Incomplete composition reload contract" }
        val s = Contract(HostRuntimeBindings.Members(values, loader), values)
        val root = s.type("root"); val activity = s.type("activity"); val wrapper = s.type("wrapper")
        check(Activity::class.java.isAssignableFrom(activity) && ViewGroup::class.java.isAssignableFrom(root))
        check(s.type("function2").isInterface && s.type("function2").isAssignableFrom(wrapper) &&
            s.type("function2").isAssignableFrom(s.type("callback")))
        check(s.constructor("root").let { it.declaringClass == root && it.parameterTypes.toList() == listOf(activity) })
        check(s.constructor("wrapper").let { it.declaringClass == wrapper && it.parameterTypes.toList() ==
            listOf(Integer.TYPE, java.lang.Boolean.TYPE, Any::class.java) })
        check(s.method("setContent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == root &&
            it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(s.type("function2")) })
        check(s.method("dispose").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(root) &&
            it.returnType == Void.TYPE && it.parameterCount == 0 })
        val state = s.field("state")
        check(state.declaringClass == root && !Modifier.isStatic(state.modifiers) && !state.type.isPrimitive)
        check(s.method("stateGet").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(state.type) &&
            it.parameterCount == 0 && it.returnType == Any::class.java })
        for ((role, owner, type) in listOf(Triple("key", wrapper, Integer.TYPE), Triple("tracked", wrapper, java.lang.Boolean.TYPE),
            Triple("callback", wrapper, Any::class.java))) {
            check(s.field(role).let { it.declaringClass == owner && it.type == type && !Modifier.isStatic(it.modifiers) })
        }
        check(s.field("callbackActivity").let { it.declaringClass == s.type("callback") &&
            it.type.isAssignableFrom(activity) && !Modifier.isStatic(it.modifiers) })
        check(s.field("navCache").let { it.type == s.type("controller") && Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) })
        check(s.method("rememberNavigation").let {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                it.parameterTypes[0].isArray && it.returnType == s.type("controller")
        })
        check(s.method("navigationFactory").let {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(Context::class.java) &&
                it.returnType == s.type("controller")
        })
        for (role in listOf("navigationSave", "navigationRestore")) {
            val constructor = s.constructor(role)
            val method = s.method(role)
            check(method.declaringClass == constructor.declaringClass && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Any::class.java &&
                method.parameterTypes.toList() == List(if (role == "navigationSave") 2 else 1) { Any::class.java })
            val arguments = values.getValue("value:${role}Arguments").split(',')
            check(arguments.size == constructor.parameterCount)
            constructor.parameterTypes.forEachIndexed { index, type ->
                val argument = arguments[index]
                when (type) {
                    Context::class.java -> check(role == "navigationRestore" && argument == "@context")
                    java.lang.Boolean.TYPE -> check(argument.toLong() in 0..1)
                    java.lang.Byte.TYPE -> argument.toByte()
                    java.lang.Short.TYPE -> argument.toShort()
                    java.lang.Character.TYPE -> check(argument.toInt() in Char.MIN_VALUE.code..Char.MAX_VALUE.code)
                    Integer.TYPE -> argument.toInt()
                    java.lang.Long.TYPE -> argument.toLong()
                    else -> error("Invalid native navigation constructor type: $type")
                }
            }
        }
        return s
    }
}
