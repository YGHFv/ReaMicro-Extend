package com.reamicro.fix.core

import com.reamicro.fix.core.HostEpubServicesQueries.one
import java.lang.reflect.Modifier
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Follows the native image input to the cache and the lock used by its own eviction path. */
internal object HostCoverCacheQueries {
    private val maps = setOf("java.util.Map", "java.util.LinkedHashMap")
    private data class Memory(val owner: ClassData, val strong: ClassData, val weak: ClassData,
        val lru: ClassData, val trim: MethodData)

    fun query(query: HostEpubServicesQueries.Query) = query.run {
        val loader = imageLoader()
        val memory = memoryCache(loader)
        val size = lruSize(memory)
        val image = cacheValue(memory, size)
        cacheKey()
        weakCache(memory, image)
        cacheConstructors(memory)
        loaderProvider(loader)
    }

    private fun HostEpubServicesQueries.Query.imageLoader(): ClassData {
        val input = requireNotNull(found(MethodMatcher.create().name("toString")
            .usingEqStrings("Input(imageLoader=")).one("cover image input").declaredClass)
        val inputConstructor = input.methods.filter { it.isConstructor && it.paramCount == 3 }
            .one("cover image input constructor")
        val loader = inputConstructor.paramTypes.first()
        check(inputConstructor.paramTypes[1].methods.any {
            it.name == "toString" && "ImageRequest(context=" in it.usingStrings
        }) { "Cover image input no longer carries a native request" }
        cls("coverCache.loader", loader)
        return loader
    }

    private fun HostEpubServicesQueries.Query.memoryCache(loader: ClassData): Memory {
        val memoryAccess = loader.methods.filter { method ->
            method.isMethod && !Modifier.isStatic(method.modifiers) && method.paramCount == 0 &&
                method.returnType?.methods.orEmpty().any { candidate ->
                    candidate.isMethod && candidate.paramCount == 0 && candidate.returnTypeName == "void" &&
                        candidate.invokes.any { it.className in maps && it.name == "clear" }
                }
        }.one("native cover memory cache")
        val memory = requireNotNull(memoryAccess.returnType)
        val clear = memory.methods.filter { method ->
            method.isMethod && !Modifier.isStatic(method.modifiers) && method.paramCount == 0 &&
                method.returnTypeName == "void" &&
                method.invokes.any { it.className in maps && it.name == "clear" }
        }.one("native cover cache eviction")
        val trim = clear.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "void"
        }.one("native cover cache trim")
        val trimCall = flow.calls(clear.descriptor).filter { it.descriptor == trim.descriptor }.single()
        check(trimCall.arguments.last() == HostDexCalls.Literal(-1))
        val strongLru = fieldFrom("coverCache.strong.lru", trimCall.arguments.first())
        val strong = requireNotNull(strongLru.declaredClass)
        val lru = requireNotNull(trim.declaredClass)
        field("coverCache.memory.strong", memory.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == strong.name
        })
        val mapClear = clear.invokes.filter { it.className in maps && it.name == "clear" && it.paramCount == 0 }
            .one("native cover weak cache clear")
        val weakMapCall = flow.calls(clear.descriptor).single { it.descriptor == mapClear.descriptor }
        val weakEntries = fieldFrom("coverCache.weak.entries", weakMapCall.arguments.first())
        val weak = requireNotNull(weakEntries.declaredClass)
        field("coverCache.memory.weak", memory.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == weak.name
        })
        field("coverCache.memory.lock", memory.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object"
        })
        for ((role, type) in listOf("memory" to memory, "strong" to strong, "weak" to weak, "lru" to lru)) {
            cls("coverCache.$role", type)
        }
        save("coverCache.memory", memoryAccess)
        return Memory(memory, strong, weak, lru, trim)
    }

    private fun HostEpubServicesQueries.Query.lruSize(memory: Memory): MethodData {
        val trim = memory.trim
        val lru = memory.lru
        val mapRemove = trim.invokes.filter {
            it.className in maps && it.name == "remove" && it.paramTypeNames == listOf("java.lang.Object")
        }.one("native cover strong cache remove")
        val remove = flow.calls(trim.descriptor).single { it.descriptor == mapRemove.descriptor }
        fieldFrom("coverCache.lru.entries", remove.arguments.first()).also {
            check(it.declaredClassName == lru.name)
        }
        val size = trim.invokes.filter {
            it.className == lru.name && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "long"
        }.one("native cover cache size")
        check(-1L in flow.literals(size.descriptor))
        val sizeField = flow.writes(size.descriptor).map { requireNotNull(bridge.getFieldData(it.field)) }
            .distinctBy { it.descriptor }.single { it.declaredClassName == lru.name && it.typeName == "long" }
        field("coverCache.lru.size", sizeField)
        save("coverCache.size", size)
        return size
    }

    private fun HostEpubServicesQueries.Query.cacheValue(memory: Memory, size: MethodData): ClassData {
        val lru = memory.lru
        val sizeOf = size.invokes.filter {
            it.className == lru.name && it.paramTypeNames == List(2) { "java.lang.Object" } &&
                it.returnTypeName == "long"
        }.one("native cover cache entry size")
        val valueType = flow.casts(sizeOf.descriptor).filter { it.value == HostDexCalls.Parameter(2) }
            .map { requireNotNull(bridge.getClassData(it.type)) }.distinctBy { it.descriptor }.single()
        val valueConstructor = valueType.methods.filter {
            it.isConstructor && it.paramCount == 3 &&
                it.paramTypeNames.drop(1) == listOf("java.util.Map", "long")
        }.one("native cover cache value")
        val image = valueConstructor.paramTypes.first()
        cls("coverCache.value", valueType)
        cls("coverCache.image", image)
        ctor("coverCache.value", valueConstructor)
        return image
    }

    private fun HostEpubServicesQueries.Query.cacheKey() {
        val key = requireNotNull(found(MethodMatcher.create().name("toString").usingEqStrings("Key(key="))
            .one("native cover cache key").declaredClass)
        val keyConstructor = key.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf("java.lang.String", "java.util.Map")
        }.one("native cover cache key constructor")
        field("coverCache.key.value", flow.writes(keyConstructor.descriptor).single {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(1)
        }.field)
        cls("coverCache.key", key)
        ctor("coverCache.key", keyConstructor)
    }

    private fun HostEpubServicesQueries.Query.weakCache(memory: Memory, image: ClassData) {
        val weak = memory.weak
        val weakConstructor = weak.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf("byte")
        }.one("native cover weak cache constructor")
        val weakTag = flow.switchKeys(weakConstructor.descriptor).single { tag ->
            flow.writes(weakConstructor.descriptor, mapOf(1 to tag)).any {
                it.field == values.getValue("field:coverCache.weak.entries") &&
                    (it.value as? HostDexCalls.Allocation)?.type == "Ljava/util/LinkedHashMap;"
            }
        }
        values["value:coverCache.weakTag"] = weakTag.toString()
        val key = requireNotNull(bridge.getClassData(values.getValue("class:coverCache.key")))
        val weakPut = weak.methods.filter {
            it.isMethod && it.paramTypeNames == listOf(key.name, image.name, "java.util.Map", "long")
        }.one("native cover weak cache insertion")
        val weakValueConstructor = weakPut.invokes.filter {
            it.isConstructor && it.paramTypeNames == listOf("java.lang.ref.WeakReference", "java.util.Map", "long")
        }.one("native cover weak cache value")
        cls("coverCache.weakValue", requireNotNull(weakValueConstructor.declaredClass))
        ctor("coverCache.weakValue", weakValueConstructor)
        ctor("coverCache.weak", weakConstructor)
    }

    private fun HostEpubServicesQueries.Query.cacheConstructors(memory: Memory) {
        val strong = memory.strong
        val weak = memory.weak
        ctor("coverCache.strong", strong.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf("long", weak.name)
        }.one("native cover strong cache constructor"))
        ctor("coverCache.memory", memory.owner.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf(strong.name, weak.name)
        }.one("native cover memory cache constructor"))
    }

    private fun HostEpubServicesQueries.Query.loaderProvider(loader: ClassData) {
        val provider = found(MethodMatcher.create().paramTypes("android.content.Context").returnType(loader.name))
            .filter { Modifier.isStatic(it.modifiers) &&
                it.invokes.any { call -> call.className == "java.util.concurrent.atomic.AtomicReference" &&
                    call.name == "compareAndSet" } }
            .one("native cover image loader provider")
        save("coverCache.provider", provider)
    }
}
