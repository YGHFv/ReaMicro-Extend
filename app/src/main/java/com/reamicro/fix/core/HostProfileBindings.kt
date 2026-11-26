package com.reamicro.fix.core

import android.graphics.Shader
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostProfileBindings {
    const val KEY = "profile.background"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()))
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro profile background query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, true, base, ui)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>, ui: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostProfileQueries.query(it, session.apk, base, ui) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun type(role: String) = members.type(role)
        fun constructor(role: String) = members.constructor(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        val hasNativeLayer get() = number("nativeLayer") == 1
        fun call(role: String, vararg args: Any?): Any? = method(role).invoke(null, *args)
        fun fromArgb(value: Int) = call("fromArgb", value) as Long
        fun toArgb(value: Long) = call("toArgb", value) as Int
        fun shader(value: Shader): Any = constructor("shader").newInstance(value)
        fun background(modifier: Any, brush: Any): Any = requireNotNull(call("brushBackground", modifier, brush, null, number("brushMask")))
        fun useImageCard(role: String, args: Array<Any?>) {
            args[number("$role.boolean")] = true
            val flags = number("$role.flags")
            args[flags] = (args[flags] as Int) and number("$role.mask").inv()
        }
    }
    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val methods = listOf("root", "content", "systemBars", "community", "privilege", "surface", "brushBackground", "alpha", "fromArgb", "toArgb",
            "background", "materialize", "colorScheme", "backgroundAuto", "backgroundBright", "backgroundDim", "divider")
        val required = methods.map { "method:$it" } + listOf("modifier", "composer", "brush", "content", "scope", "shader").map { "class:$it" } +
            listOf("constructor:shader", "value:brushMask", "value:nativeLayer") + listOf("community", "privilege").flatMap { role -> listOf("boolean", "flags", "mask").map { "value:$role.$it" } }
        check(values.keys.containsAll(required)) { "Incomplete profile background contract: ${required - values.keys}" }
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE; val long = java.lang.Long.TYPE; val float = java.lang.Float.TYPE; val bool = java.lang.Boolean.TYPE
        fun signature(role: String, args: List<Class<*>>, result: Class<*>) {
            val m = s.method(role); check(Modifier.isStatic(m.modifiers) && m.parameterTypes.toList() == args && m.returnType == result)
        }
        signature("root", listOf(s.type("scope"), s.type("composer"), int), Void.TYPE)
        signature("systemBars", listOf(int, s.type("composer"), bool), Void.TYPE)
        check(s.number("nativeLayer") in 0..1)
        if (s.hasNativeLayer) {
            signature("layer", listOf(Long::class.javaObjectType, float, s.type("composer"), int), Void.TYPE)
            signature("layerImage", listOf(Long::class.javaObjectType, s.type("composer")), bool)
            check(s.number("layer.composer") == 2)
            val restart = s.method("layerRestart")
            check(!Modifier.isStatic(restart.modifiers) && restart.returnType == any &&
                restart.parameterTypes.toList() == List(2) { any } &&
                restart.declaringClass == s.constructor("layerRestart").declaringClass &&
                s.type("layerFunction").isInterface && s.type("layerFunction").isAssignableFrom(restart.declaringClass))
        } else check("method:layer" !in values && "method:layerImage" !in values && "value:layer.composer" !in values)
        val content = s.method("content")
        check(!Modifier.isStatic(content.modifiers) && content.declaringClass == s.type("content") && content.parameterTypes.toList() == List(3) { any } && content.returnType == any)
        signature("surface", listOf(bool, float, s.type("composer")), s.type("modifier"))
        signature("materialize", listOf(s.type("composer"), s.type("modifier")), s.type("modifier"))
        signature("alpha", listOf(float, long), long); signature("fromArgb", listOf(int), long); signature("toArgb", listOf(long), int)
        val brush = s.method("brushBackground")
        check(Modifier.isStatic(brush.modifiers) && brush.returnType == s.type("modifier") && brush.parameterCount == 4 &&
            brush.parameterTypes.take(2) == listOf(s.type("modifier"), s.type("brush")) && brush.parameterTypes.last() == int && !brush.parameterTypes[2].isPrimitive)
        check((s.number("brushMask") and 2) != 0)
        signature("background", listOf(s.type("modifier"), long, s.method("background").parameterTypes[2]), s.type("modifier"))
        val scheme = s.method("colorScheme")
        check(Modifier.isStatic(scheme.modifiers) && scheme.parameterTypes.toList() == listOf(s.type("composer")) && !scheme.returnType.isPrimitive)
        for (role in listOf("backgroundAuto", "backgroundBright", "backgroundDim")) signature(role, listOf(scheme.returnType), long)
        val shader = s.constructor("shader")
        check(shader.declaringClass == s.type("shader") && shader.parameterTypes.toList() == listOf(Shader::class.java) && s.type("brush").isAssignableFrom(s.type("shader")))
        for (role in listOf("community", "privilege")) {
            val m = s.method(role); val b = s.number("$role.boolean"); val flags = s.number("$role.flags")
            val composer = m.parameterTypes.indexOf(s.type("composer"))
            check(Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.first() == s.type("scope"))
            check(m.parameterTypes.indices.filter { m.parameterTypes[it] == bool } == listOf(b) && b < composer)
            check(flags == composer + 1 + b / 10 && m.parameterTypes[flags] == int && s.number("$role.mask") == (14 shl (3 * (b % 10))))
        }
        val divider = s.method("divider")
        check(Modifier.isStatic(divider.modifiers) && divider.returnType == Void.TYPE && s.type("composer") in divider.parameterTypes)
        methods.forEach(s::method)
        return s
    }
}
