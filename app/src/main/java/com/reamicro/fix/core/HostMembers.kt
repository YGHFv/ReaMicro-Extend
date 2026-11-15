package com.reamicro.fix.core

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.processor.MemberProcessor
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexMethod
import org.luckypray.dexkit.wrap.DexField
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** 参数类型保留 Class 身份；不同 ClassLoader 中的同名类型不能互换。 */
internal sealed class HostMethodParameters {
    abstract fun matches(types: List<Class<*>>): Boolean

    class Exact(types: List<Class<*>>) : HostMethodParameters() {
        val types = types.toList()
        override fun matches(types: List<Class<*>>) = types == this.types
        override fun equals(other: Any?) = other is Exact && types == other.types
        override fun hashCode() = types.hashCode()
    }

    object All : HostMethodParameters() {
        override fun matches(types: List<Class<*>>) = true
    }
}

/**
 * 只接收已经解析过的真实成员名。缓存属于宿主绑定或可清理的模块代际，键保留 Class 身份。
 * 逻辑符号每次由调用层重新解析，因此 DexKit 增补映射不会命中旧的逻辑名缓存。
 */
internal class HostMembers {
    private val lock = Any()
    private var retired = false
    private data class MethodKey(
        val type: Class<*>,
        val name: String,
        val parameters: HostMethodParameters,
        val staticOnly: Boolean?,
        val returnType: Class<*>?,
    )

    private data class MethodSignature(val name: String, val parameters: List<Class<*>>)
    private data class ConstructorKey(val type: Class<*>, val parameters: List<Class<*>>)
    // ClassLoader may override equals/hashCode. Cache by identity, including the bootstrap loader.
    private val typeCache = IdentityHashMap<ClassLoader?, MutableMap<String, Class<*>>>()
    private val classDescriptorCache = IdentityHashMap<ClassLoader?, MutableMap<String, Class<*>>>()
    private val descriptorCache = IdentityHashMap<ClassLoader?, MutableMap<String, Method>>()
    private val fieldDescriptorCache = IdentityHashMap<ClassLoader?, MutableMap<String, Field>>()
    private val constructorDescriptorCache = IdentityHashMap<ClassLoader?, MutableMap<String, Constructor<*>>>()
    private val hierarchyCache = HashMap<Class<*>, List<Class<*>>>()
    private val methodCache = HashMap<MethodKey, List<Method>>()
    private val fieldCache = HashMap<Pair<Class<*>, String>, Field?>()
    private val constructorCache = HashMap<ConstructorKey, Constructor<*>?>()
    private val processor = StrictMembers()

    init {
        HostMemberCaches.track(this)
    }

    /** 每个缓存实例都退场；后续的旧代际回调不得重新填充宿主成员。 */
    internal fun retire() = synchronized(lock) {
        retired = true
        typeCache.clear()
        classDescriptorCache.clear()
        hierarchyCache.clear()
        methodCache.clear()
        fieldCache.clear()
        constructorCache.clear()
        descriptorCache.clear()
        fieldDescriptorCache.clear()
        constructorDescriptorCache.clear()
        processor.clear()
    }

    private fun requireActive() {
        check(!retired) { "Host reflection belongs to a retired module generation" }
    }

    /** Only successful lookups are cached: a missing class can become available in a later split. */
    fun namedClass(name: String, loader: ClassLoader?): Class<*> = synchronized(lock) {
        requireActive()
        typeCache.getOrPut(loader) { HashMap() }.getOrPut(name) {
            descriptorType(name, loader)
        }
    }

    fun hierarchyOf(type: Class<*>): List<Class<*>> = synchronized(lock) {
        requireActive()
        hierarchyCache.getOrPut(type) { Collections.unmodifiableList(hierarchy(type)) }
    }

    /** 验证描述符只加载类型；静态初始化仍由实际调用、字段读取或构造触发。 */
    fun describedClass(descriptor: String, loader: ClassLoader?): Class<*> = synchronized(lock) {
        requireActive()
        classDescriptorCache.getOrPut(loader) { HashMap() }.getOrPut(descriptor) {
            namedClass(DexClass(descriptor).typeName, loader)
        }
    }

    /** DexKit 的返回类型也是签名的一部分；不能把 bridge 自动替换成另一个返回类型。 */
    fun describedMethod(descriptor: String, loader: ClassLoader?): Method = synchronized(lock) {
        requireActive()
        descriptorCache.getOrPut(loader) { HashMap() }.getOrPut(descriptor) {
            val dex = DexMethod(descriptor)
            require(dex.isMethod) { "Expected host method descriptor: $descriptor" }
            val owner = namedClass(dex.className, loader)
            val expected = dex.paramTypeNames.map { namedClass(it, owner.classLoader) }
            val returnType = namedClass(dex.returnTypeName, owner.classLoader)
            val candidates = owner.resolve().processor(processor).optional(silent = true).method {
                name(dex.name)
                parameters(*expected.toTypedArray())
                parameterCount(expected.size)
                returnType(returnType)
            }.map { it.self }
            unique(candidates, descriptor)?.apply { isAccessible = true }
                ?: throw NoSuchMethodException(descriptor)
        }
    }

    fun describedField(descriptor: String, loader: ClassLoader?): Field = synchronized(lock) {
        requireActive()
        fieldDescriptorCache.getOrPut(loader) { HashMap() }.getOrPut(descriptor) {
            val dex = DexField(descriptor)
            val owner = namedClass(dex.className, loader)
            val expected = namedClass(dex.typeName, owner.classLoader)
            unique(declaredFields(owner, dex.name).filter { it.type == expected }, descriptor)
                ?.apply { isAccessible = true } ?: throw NoSuchFieldException(descriptor)
        }
    }

    fun describedConstructor(descriptor: String, loader: ClassLoader?): Constructor<*> = synchronized(lock) {
        requireActive()
        constructorDescriptorCache.getOrPut(loader) { HashMap() }.getOrPut(descriptor) {
            val dex = DexMethod(descriptor)
            require(dex.isConstructor && dex.returnTypeName == "void") { "Expected constructor: $descriptor" }
            val owner = namedClass(dex.className, loader)
            constructor(owner, dex.paramTypeNames.map { namedClass(it, owner.classLoader) })
                ?: throw NoSuchMethodException(descriptor)
        }
    }

    fun methods(
        type: Class<*>,
        methodName: String,
        query: HostMethodParameters = HostMethodParameters.All,
        staticOnly: Boolean? = null,
        returnType: Class<*>? = null,
    ): List<Method> = synchronized(lock) {
        requireActive()
        val key = MethodKey(type, methodName, query, staticOnly, returnType)
        methodCache.getOrPut(key) {
            val candidates = hierarchyOf(type).flatMap { owner ->
                owner.resolve().processor(processor).optional(silent = true).method {
                    name(methodName)
                    if (query is HostMethodParameters.Exact) {
                        // Empty parameters() is unconstrained in KavaRef; count keeps () exact.
                        // Static conditions also preserve accessor/linkage errors instead of
                        // turning a thrown runtime predicate into a missing member.
                        parameters(*query.types.toTypedArray())
                        parameterCount(query.types.size)
                    }
                }.map { it.self }.filter { method ->
                    (returnType == null || method.returnType == returnType) &&
                    (staticOnly == null || Modifier.isStatic(method.modifiers) == staticOnly) &&
                        // 接口的 static/private 方法不继承；直接查询该接口时仍可访问。
                        (owner == type || !owner.isInterface ||
                            (!Modifier.isStatic(method.modifiers) && Modifier.isPublic(method.modifiers)))
                }
            }
            val selected = candidates.groupBy { MethodSignature(it.name, it.parameterTypes.toList()) }
                .values.map { selectOverride(it, "${type.name}#$methodName") }
                .onEach { it.isAccessible = true }
            Collections.unmodifiableList(selected)
        }
    }

    fun method(
        type: Class<*>,
        name: String,
        parameters: HostMethodParameters,
        staticOnly: Boolean? = null,
        returnType: Class<*>? = null,
    ): Method? = uniqueMethod(methods(type, name, parameters, staticOnly, returnType), "${type.name}#$name")

    fun field(type: Class<*>, name: String): Field? = synchronized(lock) {
        requireActive()
        val key = type to name
        if (fieldCache.containsKey(key)) return@synchronized fieldCache[key]
        val owners = hierarchyOf(type)
        // 字段按声明层级隐藏；父类的 private/protected 字段也可以反射访问。
        val classOwners = owners.filter { !it.isInterface || it == type }
        var found: Field? = null
        for (owner in classOwners) {
            val fields = declaredFields(owner, name)
            if (fields.isNotEmpty()) {
                found = unique(fields, "${type.name}#$name")
                break
            }
        }
        if (found == null) {
            val fields = owners.filter { it.isInterface && it != type }
                .flatMap { declaredFields(it, name) }.distinct()
            val nearest = fields.filter { candidate ->
                fields.none { other ->
                    candidate.declaringClass != other.declaringClass &&
                        candidate.declaringClass.isAssignableFrom(other.declaringClass)
                }
            }
            found = unique(nearest, "${type.name}#$name")
        }
        found?.isAccessible = true
        fieldCache[key] = found
        found
    }

    fun constructor(type: Class<*>, types: List<Class<*>>): Constructor<*>? = synchronized(lock) {
        requireActive()
        val key = ConstructorKey(type, types.toList())
        if (constructorCache.containsKey(key)) return@synchronized constructorCache[key]
        val candidates = type.resolve().processor(processor).optional(silent = true).constructor {
            parameters(*key.parameters.toTypedArray())
            parameterCount(key.parameters.size)
        }.map { it.self }
        unique(candidates, "${type.name}#<init>")?.apply { isAccessible = true }
            .also { constructorCache[key] = it }
    }

    private fun declaredFields(type: Class<*>, fieldName: String): List<Field> =
        type.resolve().processor(processor).optional(silent = true).field {
            name(fieldName)
        }.map { it.self }

    companion object {
        /** 类链优先，再遍历接口；不依赖反射数组或接口声明顺序挑选成员。 */
        fun hierarchy(type: Class<*>): List<Class<*>> {
            val classes = generateSequence<Class<*>>(type) { it.superclass }.toList()
            val interfaces = linkedSetOf<Class<*>>()
            fun visit(owner: Class<*>) {
                owner.interfaces.forEach { if (interfaces.add(it)) visit(it) }
            }
            classes.forEach(::visit)
            return classes + interfaces
        }

        fun uniqueMethod(
            methods: List<Method>,
            description: String,
        ): Method? = unique(methods, description)

        /** 重定位到不同实际方法时不能靠遍历顺序或返回值替调用方决定。 */
        fun distinctOverloads(methods: List<Method>, description: String): List<Method> =
            methods.distinct().groupBy { it.parameterTypes.toList() }
                .values.map { requireNotNull(unique(it, description)) }

        private fun selectOverride(methods: List<Method>, description: String): Method {
            val candidates = methods.distinct()
            // 类的方法实现优先于接口声明，包括 Object 提供的方法。
            val implementations = candidates.filter { !it.declaringClass.isInterface }.ifEmpty { candidates }
            val nearest = implementations.filter { candidate ->
                implementations.none { other ->
                    candidate.declaringClass != other.declaringClass &&
                        candidate.declaringClass.isAssignableFrom(other.declaringClass)
                }
            }
            // DEX permits same-name/same-parameter methods with distinct return types.
            // Only compiler bridges may be collapsed; two ordinary methods are separate contracts.
            require(nearest.groupBy { it.declaringClass }.values.none { declared ->
                declared.count { !it.isBridge } > 1
            }) {
                "Return type required for host method $description: ${nearest.joinToString()}"
            }
            val covariant = nearest.filter { candidate ->
                // A same-parameter member in a child is an override only when its return
                // type also satisfies the parent contract. DEX may contain unrelated returns.
                implementations.all { it.returnType.isAssignableFrom(candidate.returnType) }
            }
            val preferred = covariant.filterNot { it.isBridge }.ifEmpty { covariant }
            require(preferred.size == 1) {
                "Ambiguous host method $description: ${candidates.joinToString()}"
            }
            return preferred.single()
        }

        private fun <T> unique(values: List<T>, description: String): T? {
            val candidates = values.distinct()
            require(candidates.size <= 1) {
                "Ambiguous host member $description: ${candidates.joinToString()}"
            }
            return candidates.singleOrNull()
        }

        private fun descriptorType(name: String, loader: ClassLoader?): Class<*> {
            if (name.endsWith("[]")) {
                return java.lang.reflect.Array.newInstance(descriptorType(name.removeSuffix("[]"), loader), 0).javaClass
            }
            return when (name) {
                "void" -> Void.TYPE
                "boolean" -> java.lang.Boolean.TYPE
                "byte" -> java.lang.Byte.TYPE
                "short" -> java.lang.Short.TYPE
                "char" -> java.lang.Character.TYPE
                "int" -> java.lang.Integer.TYPE
                "long" -> java.lang.Long.TYPE
                "float" -> java.lang.Float.TYPE
                "double" -> java.lang.Double.TYPE
                else -> Class.forName(name, false, loader)
            }
        }
    }
}

/** 不把缺失依赖等链接错误当作「成员不存在」后继续猜测别的成员。 */
private class StrictMembers : MemberProcessor.Resolver() {
    private val methods = HashMap<Class<*>, List<Method>>()
    private val fields = HashMap<Class<*>, List<Field>>()
    private val constructors = HashMap<Class<*>, List<Constructor<*>>>()

    fun clear() {
        synchronized(methods) { methods.clear() }
        synchronized(fields) { fields.clear() }
        synchronized(constructors) { constructors.clear() }
    }

    override fun <T : Any> getDeclaredMethods(declaringClass: Class<T>): List<Method> = synchronized(methods) {
        methods.getOrPut(declaringClass) { declaringClass.declaredMethods.toList() }
    }

    override fun <T : Any> getDeclaredFields(declaringClass: Class<T>): List<Field> = synchronized(fields) {
        fields.getOrPut(declaringClass) { declaringClass.declaredFields.toList() }
    }

    override fun <T : Any> getDeclaredConstructors(declaringClass: Class<T>): List<Constructor<T>> = synchronized(constructors) {
        @Suppress("UNCHECKED_CAST")
        (constructors.getOrPut(declaringClass) { declaringClass.declaredConstructors.toList() } as List<Constructor<T>>)
    }
}
