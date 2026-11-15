package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostManualEditBindings {
    val QUERY_KEYS = listOf("manual.publisher", "manual.unlock")
    @Volatile var publisher: Contract? = null; private set
    @Volatile var unlock: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; publisher = null; unlock = null; snapshots = emptyMap()
        val resolved = linkedMapOf<String, Map<String, String>>()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            for (key in QUERY_KEYS) runCatching {
                val values = query(session, key, false, requireNotNull(HostUiBindings.boundDescriptors()),
                    HostAssociationBindings.boundDescriptors().orEmpty(),
                    HostRuntimeBindings.boundDescriptors()["runtime.navigation"].orEmpty())
                val result = restore(key, values, loader)
                if (key == "manual.publisher") publisher = result else unlock = result
                resolved[key] = values
            }.onFailure { HookRuntime.logError("ReaMicro $key unavailable", it) }
        } }.onFailure { HookRuntime.logError("ReaMicro manual editor query session unavailable", it) }
        snapshots = resolved
    }
    fun rescan(session: HostQuerySession.Session, ui: Map<String, String>, association: Map<String, String>,
        navigation: Map<String, String>) = session.collect(QUERY_KEYS) { query(session, it, true, ui, association, navigation) }
    private fun query(session: HostQuerySession.Session, key: String, force: Boolean, ui: Map<String, String>,
        association: Map<String, String>, navigation: Map<String, String>) =
        session.query(key, force, validate = { restore(key, it, session.loader) },
            find = { HostManualEditQueries.query(key.substringAfter('.'), it, session.apk, ui, association, navigation) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        fun isCallback(role: String, value: Any?): Boolean = value != null &&
            method(role).declaringClass.isInstance(value) && (0 until number("$role.branches")).all { index ->
                val actual = field("$role.branch.$index").get(value)
                val expected = values.getValue("value:$role.branch.$index").toLong()
                when (actual) {
                    is Number -> actual.toLong() == expected
                    is Boolean -> (if (actual) 1L else 0L) == expected
                    is Char -> actual.code.toLong() == expected
                    else -> false
                }
            }
        fun replaceArgument(args: Array<Any?>, role: String, value: Any) {
            val slot = number("$role.modifier")
            args[slot] = value
            val flags = number("$role.changed")
            args[flags] = (args[flags] as Int) and (14 shl (3 * (slot % 10))).inv()
            val defaults = number("$role.defaults")
            args[defaults] = (args[defaults] as Int) and (1 shl slot).inv()
        }
    }

    private fun restore(key: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE; val long = java.lang.Long.TYPE; val void = Void.TYPE
        fun instance(role: String, count: Int) {
            val m = s.method(role)
            check(!Modifier.isStatic(m.modifiers) && m.parameterTypes.toList() == List(count) { any } && m.returnType == any)
        }
        fun callback(role: String, arity: Int) {
            instance(role, arity)
            val count = s.number("$role.branches"); check(count in 0..16)
            for (index in 0 until count) {
                val f = s.field("$role.branch.$index")
                check(f.declaringClass == s.method(role).declaringClass && !Modifier.isStatic(f.modifiers) && Modifier.isFinal(f.modifiers))
                check(f.type in setOf(int, long, java.lang.Byte.TYPE, java.lang.Short.TYPE, java.lang.Character.TYPE, java.lang.Boolean.TYPE))
                values.getValue("value:$role.branch.$index").toLong()
            }
        }
        val root = s.method("root")
        check(Modifier.isStatic(root.modifiers) && root.returnType == void)
        when (key) {
            "manual.publisher" -> {
                check(root.parameterTypes.toList() == listOf(s.type("scope"), long, s.type("composer"), int))
                val id = s.field("vm.bookId")
                check(id.declaringClass == s.type("vm") && id.type == long && Modifier.isFinal(id.modifiers) && !Modifier.isStatic(id.modifiers))
                instance("content", 3)
                val capture = s.field("content.vm")
                check(!Modifier.isStatic(capture.modifiers) && capture.declaringClass == s.method("content").declaringClass && capture.type == any)
                check(s.method("acquireVm").returnType.isAssignableFrom(s.type("vm")))
                callback("toolbar", 2)
                val scaffold = s.method("scaffold"); val top = s.method("topBar")
                check(Modifier.isStatic(scaffold.modifiers) && scaffold.parameterCount == 13 && scaffold.returnType == void)
                check(Modifier.isStatic(top.modifiers) && top.parameterCount == 11 && top.returnType == void)
                check(s.number("scaffold.toolbar") == 1 && s.number("topBar.actions") == 7 &&
                    s.number("topBar.changed") == 9 && s.number("topBar.defaults") == 10)
                check(s.number("scaffold.changed") == 11 && s.number("scaffold.defaults") == 12)
                check(scaffold.parameterTypes[10] == s.type("composer") && top.parameterTypes[8] == s.type("composer") &&
                    top.parameterTypes.takeLast(2) == listOf(int, int))
                val image = s.method("candidateImage")
                check(Modifier.isStatic(image.modifiers) && image.returnType == void && image.parameterCount == 7 &&
                    image.parameterTypes.take(3) == listOf(any, String::class.java, s.type("modifier")) &&
                    image.parameterTypes.takeLast(3) == listOf(s.type("composer"), int, int))
                check(s.number("candidateImage.data") == 0 && s.number("candidateImage.changed") == 5 &&
                    s.number("candidateImage.defaults") == 6)
                val relate = s.method("relate"); val relatedCover = s.method("relatedCover")
                check(!Modifier.isStatic(relate.modifiers) && relate.declaringClass == s.type("repository") &&
                    relate.returnType == any && relate.parameterCount == 3 &&
                    relate.parameterTypes.take(2) == listOf(s.type("book"), s.type("candidate")))
                check(!Modifier.isStatic(relatedCover.modifiers) && relatedCover.declaringClass == s.type("repository") &&
                    relatedCover.returnType == any &&
                    relatedCover.parameterTypes.toList() == listOf(s.type("book"), String::class.java, relate.parameterTypes[2]))
                check(s.number("candidateRender.count") in 1..16)
                repeat(s.number("candidateRender.count")) { index ->
                    val renderer = s.method("candidateRender.$index")
                    check(Modifier.isStatic(renderer.modifiers) && renderer.returnType == void &&
                        renderer.parameterTypes[s.number("candidateRender.$index.candidate")] == s.type("candidate") &&
                        s.type("composer") in renderer.parameterTypes)
                }
            }
            "manual.unlock" -> {
                check(root.parameterCount == 2 && root.parameterTypes.toSet() == setOf(int, s.type("composer")))
                callback("content", 3)
                val image = s.method("image")
                check(Modifier.isStatic(image.modifiers) && image.parameterCount == 10 && image.returnType == void &&
                    image.parameterTypes[1] == String::class.java && image.parameterTypes[2] == s.type("modifier") &&
                    image.parameterTypes[5] == java.lang.Float.TYPE && image.parameterTypes.takeLast(3) == listOf(s.type("composer"), int, int))
                check(s.number("image.modifier") == 2 && s.number("image.changed") == 8 && s.number("image.defaults") == 9)
            }
            else -> error("Unknown manual edit contract: $key")
        }
        return s
    }
}
