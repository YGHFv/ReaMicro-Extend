package com.reamicro.fix.core

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 宿主反射的唯一入口。
 *
 * 业务代码始终写未混淆的逻辑名；命中 profile 时改写为真实名，未命中或宿主未混淆时
 * 原样透传。改写失败一定回退逻辑名再试一次，避免单个 profile 错误放大成整模块失效。
 */

/** 逻辑名加载宿主类；profile 过期时回退原始名。 */
fun ClassLoader.hostClass(name: String): Class<*> {
    val resolved = HostSymbols.resolveClass(name)
    return runCatching { Class.forName(resolved, false, this) }
        .recoverCatching {
            if (resolved == name) throw it
            Class.forName(name, false, this)
        }
        .getOrThrow()
}

fun ClassLoader.hostClassOrNull(name: String): Class<*>? =
    runCatching { hostClass(name) }.getOrNull()

/**
 * 运行期类型名与逻辑类型名是否等价。
 *
 * 大量「找方法」的调用点靠 `parameterTypes[n].name == "kotlin.coroutines.Continuation"` 识别
 * 挂起函数。混淆版里该类型已被改名（2.3.3-b2 为 `tx1`），直接比名字永远不成立，
 * 于是整条 hook 静默失效。这里把两侧都过一遍符号解析，未命中 profile 时退化为原比较。
 */
fun typeMatches(type: Class<*>?, logicalName: String): Boolean {
    if (type == null) return false
    if (type.name == logicalName) return true
    val resolved = HostSymbols.resolveClass(logicalName)
    if (type.name == resolved) return true
    // R8 也会收窄未改名的 List/Reader；允许接口和抽象类的实现，不放宽 Object。
    if (resolved == "java.lang.Object") return false
    return runCatching {
        val expected = Class.forName(resolved, false, type.classLoader)
        (expected.isInterface || java.lang.reflect.Modifier.isAbstract(expected.modifiers)) && expected.isAssignableFrom(type)
    }.getOrDefault(false)
}

/** 兼容 Kotlin 值类后缀与 profile 改名；必须继续用参数签名过滤重载。 */
fun Method.hostNameMatches(logicalName: String): Boolean =
    // declaringClass 通常是混淆后的实际类名，先反查逻辑 owner 才能命中 profile 的成员映射。
    name == HostSymbols.resolveMethodName(declaringClass, logicalName) || name == logicalName

fun Method.hostNameStartsWith(logicalName: String): Boolean =
    name.startsWith(logicalName) || name in HostSymbols.methodNames(declaringClass, logicalName.removeSuffix("-"))

fun Field.hostNameMatches(logicalName: String): Boolean =
    name == HostSymbols.resolveFieldName(
        HostSymbols.logicalClass(declaringClass.name),
        logicalName,
    ) || name == logicalName

/** 挂起函数的尾参就是解析后的 Continuation，用它判断比数参数个数可靠。 */
fun Method.isSuspendingContinuationParameter(index: Int, logicalName: String): Boolean =
    parameterTypes.getOrNull(index)?.let { parameter ->
        typeMatches(parameter, logicalName) || runCatching {
            declaringClass.classLoader?.hostClass(logicalName)?.isAssignableFrom(parameter) == true
        }.getOrDefault(false)
    } == true

/** 声明方法优先，其次公开方法，与 getDeclaredMethod/getMethod 的并集语义一致。 */
fun Class<*>.hostMethod(name: String, vararg types: Class<*>?): Method {
    val parameters = types.map { requireNotNull(it) { "Missing parameter type for ${this.name}#$name" } }.toTypedArray()
    val logicalOwner = HostSymbols.logicalClass(this.name)
    val owner = HostSymbols.resolveMethodOwner(logicalOwner, name)
    if (owner != this.name && owner != logicalOwner) {
        val redirected = Class.forName(owner, false, classLoader)
        val actualName = HostSymbols.resolveMethodName(logicalOwner, name)
        redirected.findMethodExact(actualName, parameters)?.let {
            require(java.lang.reflect.Modifier.isStatic(it.modifiers)) { "Relocated method must be static: $it" }
            return it
        }
    }
    val resolved = HostSymbols.resolveMethodName(this, name)
    for (candidate in listOf(resolved, name).distinct()) {
        findMethodExact(candidate, parameters)?.let { return it }
    }
    throw NoSuchMethodException("${this.name}#$name")
}

/** 门面本身被删除时，不先加载逻辑类，直接加载方法的实际持有者。 */
fun ClassLoader.hostStaticMethod(owner: String, name: String, vararg types: Class<*>?): Method {
    val target = Class.forName(HostSymbols.resolveMethodOwner(owner, name), false, this)
    val actualName = HostSymbols.resolveMethodName(owner, name)
    return target.hostMethod(actualName, *types).also {
        require(java.lang.reflect.Modifier.isStatic(it.modifiers)) { "Expected static host method: $it" }
    }
}
fun Class<*>.hostMethodOrNull(name: String, vararg types: Class<*>): Method? =
    runCatching { hostMethod(name, *types) }.getOrNull()

/** 按方法名收集同名重载（含继承与接口方法），供 hookAllMethods 一类调用使用。 */
fun Class<*>.hostMethodsNamed(name: String): List<Method> {
    val resolved = HostSymbols.resolveMethodName(this, name)
    val wanted = listOf(resolved, name).distinct()
    val result = LinkedHashMap<List<Class<*>>, Method>()
    var current: Class<*>? = this
    while (current != null) {
        for (method in current.declaredMethods) {
            if (method.name in wanted) result.putIfAbsent(method.parameterTypes.toList(), method)
        }
        current = current.superclass
    }
    for (method in methods) {
        if (method.name in wanted) result.putIfAbsent(method.parameterTypes.toList(), method)
    }
    return result.values.onEach { it.isAccessible = true }.toList()
}

fun Class<*>.hostField(name: String): Field {
    val resolved = HostSymbols.resolveFieldName(this, name)
    for (candidate in listOf(resolved, name).distinct()) {
        findFieldExact(candidate)?.let { return it }
    }
    throw NoSuchFieldException("${this.name}#$name")
}

fun Class<*>.hostFieldOrNull(name: String): Field? = runCatching { hostField(name) }.getOrNull()

fun Class<*>.hostConstructor(vararg types: Class<*>): Constructor<*> {
    // 构造器名字不混淆，但参数类型可能已改名：先按原样匹配，再按解析后的名字匹配。
    runCatching { getDeclaredConstructor(*types) }
        .getOrNull()
        ?.let { return it.apply { isAccessible = true } }
    val wanted = types.map { HostSymbols.resolveClass(it.name) }
    declaredConstructors.firstOrNull { constructor ->
        val actual = constructor.parameterTypes.map { it.name }
        actual.size == wanted.size && actual.zip(wanted).all { (left, right) -> left == right }
    }?.let { return it.apply { isAccessible = true } }
    throw NoSuchMethodException("${this.name}#<init>")
}

fun Any?.hostField(name: String): Field? {
    val target = this ?: return null
    return runCatching { target.javaClass.hostField(name) }.getOrNull()
}

private fun Class<*>.findMethodExact(name: String, types: Array<out Class<*>>): Method? {
    var current: Class<*>? = this
    while (current != null) {
        runCatching { current.getDeclaredMethod(name, *types) }
            .getOrNull()
            ?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    return runCatching { getMethod(name, *types) }
        .getOrNull()
        ?.apply { isAccessible = true }
}

private fun Class<*>.findFieldExact(name: String): Field? {
    var current: Class<*>? = this
    while (current != null) {
        runCatching { current.getDeclaredField(name) }
            .getOrNull()
            ?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    return runCatching { getField(name) }.getOrNull()?.apply { isAccessible = true }
}
