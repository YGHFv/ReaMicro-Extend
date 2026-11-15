package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostBrowserBindings {
    const val KEY = "ui.browser"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null
        descriptors = null
        runCatching {
            HostQuerySession.use(loader, apk, dataDir) { session ->
                var restored: Contract? = null
                val result = session.query(KEY,
                    validate = { restored = restore(it, loader) },
                    find = { HostBrowserQueries.query(it, apk, requireNotNull(HostUiBindings.boundDescriptors())) })
                current = requireNotNull(restored)
                descriptors = result.values.toMap()
            }
        }.onFailure { HookRuntime.logError("ReaMicro native dictionary browser unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, ui: Map<String, String>) =
        session.query(KEY, true,
            validate = { restore(it, session.loader) },
            find = { HostBrowserQueries.query(it, session.apk, ui) }).values.toMap()

    class Contract internal constructor(values: Map<String, String>, loader: ClassLoader) {
        private val members = HostRuntimeBindings.Members(values, loader)
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        fun render(token: String, haze: Any, dismiss: Any, composer: Any) {
            method("overlay").invoke(null, token, "", true, "", haze, dismiss, composer, 0)
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        check(values.keys == setOf("class:composer", "class:dismiss", "class:source", "class:haze",
            "method:overlay", "constructor:source", "constructor:haze"))
        return Contract(values, loader).apply {
            check(type("composer") == requireNotNull(HostUiBindings.current).composerClass)
            check(type("dismiss").isInterface && type("dismiss") == requireNotNull(HostUiBindings.current).type("function0"))
            check(method("overlay").let {
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterTypes.toList() ==
                    listOf(String::class.java, String::class.java, java.lang.Boolean.TYPE, String::class.java,
                        type("haze"), type("dismiss"), type("composer"), Integer.TYPE)
            })
            check(constructor("source").let {
                it.declaringClass == type("source") && it.parameterTypes.toList() == List(3) { String::class.java }
            })
            check(type("source").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.let {
                it.size == 3 && it.all { field -> Modifier.isFinal(field.modifiers) && field.type == String::class.java }
            })
            check(constructor("haze").let {
                it.declaringClass == type("haze") && it.parameterTypes.toList() == listOf(java.lang.Boolean.TYPE)
            })
        }
    }
}
