package com.reamicro.fix.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Bind by DexKit-resolved classes and exact type relationships, never by obfuscated names. */
internal class CoverFadeBindings(factoryType: Class<*>, requestType: Class<*>) {
    val create: Method = factoryType.declaredMethods.single {
        !Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
            it.parameterTypes.none(Class<*>::isPrimitive) && !it.returnType.isPrimitive
    }.apply { isAccessible = true }

    private val resultType = create.parameterTypes[1]
    private val resultRequest = resultType.methods.single {
        !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == requestType
    }.apply { isAccessible = true }
    private val requestData = requestType.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && it.type == Any::class.java
    }.apply { isAccessible = true }
    // Both cache-key fields are considered. The host currently sets the same key on both.
    private val requestKeys = requestType.declaredFields.filter {
        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
    }.onEach { it.isAccessible = true }
    private val factoryInterface = factoryType.interfaces.single { type ->
        type.methods.any {
            it.name == create.name && it.returnType == create.returnType &&
                it.parameterTypes.contentEquals(create.parameterTypes)
        }
    }
    private val noTransitionFactory = factoryInterface.declaredFields.single {
        Modifier.isStatic(it.modifiers) && factoryInterface.isAssignableFrom(it.type)
    }.apply { isAccessible = true }.get(null)
    private val createWithoutFade = factoryInterface.methods.single {
        it.name == create.name && it.returnType == create.returnType &&
            it.parameterTypes.contentEquals(create.parameterTypes)
    }.apply { isAccessible = true }

    init {
        check(requestKeys.isNotEmpty()) { "Coil ImageRequest has no cache-key fields" }
        check(noTransitionFactory != null && !factoryType.isInstance(noTransitionFactory)) {
            "Coil immediate transition factory is not unique"
        }
    }

    fun isCoverResult(result: Any, knownSources: CoverSources): Boolean {
        if (!resultType.isInstance(result)) return false
        val request = resultRequest.invoke(result) ?: return false
        return requestKeys.any { CoverFadePolicy.isCoverKey(it.get(request) as? String) } ||
            knownSources.contains(requestData.get(request))
    }

    fun withoutFade(target: Any?, result: Any): Any =
        requireNotNull(createWithoutFade.invoke(noTransitionFactory, target, result))
}

/** Keep in-flight request copies recognizable; bound retained URLs and clear on module reload. */
internal class CoverSources(private val capacity: Int = 1_024) {
    private val sources = LinkedHashMap<String, Unit>(64, 0.75f, true)

    init { require(capacity > 0) }

    @Synchronized fun remember(value: Any?) {
        if (value is String && value.isNotBlank()) {
            sources[value] = Unit
            while (sources.size > capacity) {
                sources.keys.iterator().also { it.next(); it.remove() }
            }
        }
    }

    @Synchronized fun contains(value: Any?): Boolean = value is String && sources[value] != null
    @Synchronized fun clear() = sources.clear()
}

internal object PublisherCoverArgument {
    /** Publisher's serialization constructor: presence mask, id, cover, remaining text fields. */
    fun index(constructor: Constructor<*>): Int? = constructor.parameterTypes.let { types ->
        when {
            types.size > 2 && types[0] == Int::class.javaPrimitiveType &&
                types[1] == Long::class.javaPrimitiveType && types[2] == String::class.java -> 2
            types.size > 1 && types[0] == Long::class.javaPrimitiveType && types[1] == String::class.java -> 1
            else -> null
        }
    }

    fun value(index: Int, args: Array<out Any?>): Any? {
        // Serialization's missing cover bit initializes an empty string, ignoring its argument.
        if (index == 2 && (((args.getOrNull(0) as? Int) ?: 0) and 2) == 0) return null
        return args.getOrNull(index)
    }
}
