package com.reamicro.fix.core

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 宿主反射的唯一入口。
 *
 * 业务代码始终写未混淆的逻辑名；动态查询命中时改写为真实名，未命中或宿主未混淆时
 * 原样透传。已经解析的契约缺失、歧义或链接失败时终止该次绑定，不猜测其他成员。
 */

private val sharedHostMembers by lazy { HostMembers() }

/** 缓存实际 Class 与成员；逻辑映射仍在每次调用时解析，热重载释放旧代际缓存。 */
internal fun hostMembers(): HostMembers = sharedHostMembers

/** 未登记的稳定名称原样加载；显式映射失效时保留 ClassNotFoundException。 */
fun ClassLoader.hostClass(name: String): Class<*> =
    hostMembers().namedClass(HostSymbols.resolveClass(name), this)

/** 通过当前查询结果和类型别名比较逻辑类型，兼容宿主混淆后的名称。 */
fun typeMatches(type: Class<*>?, logicalName: String): Boolean {
    if (type == null) return false
    val resolved = HostSymbols.resolveClass(logicalName)
    return try {
        val expected = hostMembers().namedClass(resolved, type.classLoader)
        expected == type ||
            // R8 可收窄接口和抽象类。描述符绑定另行使用严格 Class 相等，不采用此规则。
            (expected != Any::class.java && (expected.isInterface || Modifier.isAbstract(expected.modifiers)) &&
                expected.isAssignableFrom(type))
    } catch (_: ClassNotFoundException) {
        false
    }
}

/** 使用动态解析的完整实际名称；包括 Kotlin 值类后缀，调用方仍需过滤参数签名。 */
fun Method.hostNameMatches(logicalName: String): Boolean =
    // declaringClass 通常是混淆后的实际类名，先反查逻辑 owner 再查成员映射。
    name == HostSymbols.resolveMethodName(declaringClass, logicalName)

fun Field.hostNameMatches(logicalName: String): Boolean =
    name == HostSymbols.resolveFieldName(declaringClass, logicalName)

/** 精确参数类型优先；继承、接口和 bridge 的消歧统一由 HostMembers 处理。 */
fun Class<*>.hostMethod(name: String, vararg types: Class<*>): Method =
    findHostMethod(name, HostMethodParameters.Exact(types.toList()), hostMembers())
        ?: throw NoSuchMethodException("${this.name}#$name")

/** DEX 允许仅返回类型不同的重载；没有描述符的调用点也可指定完整签名。 */
fun Class<*>.hostMethodReturning(name: String, returnType: Class<*>, vararg types: Class<*>): Method =
    findHostMethod(name, HostMethodParameters.Exact(types.toList()), hostMembers(), returnType)
        ?: throw NoSuchMethodException("${this.name}#$name -> ${returnType.name}")

/** 返回真正的同名重载；映射名命中后不混入原名或旧门面的无关短名。 */
fun Class<*>.hostMethodsNamed(name: String): List<Method> {
    val members = hostMembers()
    HostSymbols.methodDescriptor(this, name)?.let { descriptor ->
        return listOf(requireNotNull(members.describedMethod(descriptor, classLoader)
            .matchHostContract(HostMethodParameters.All, receiver = this)))
    }
    val relocated = relocatedMethods(name, HostMethodParameters.All, members)
    if (relocated != null) return HostMembers.distinctOverloads(relocated, "${this.name}#$name")
    val names = mappedMethodNames(name)
    if (names.isNotEmpty()) {
        return HostMembers.distinctOverloads(names.flatMap { members.methods(this, it) }, "${this.name}#$name")
    }
    return members.methods(this, name)
}

fun Class<*>.hostField(name: String): Field = findHostField(name, hostMembers())
    ?: throw NoSuchFieldException("${this.name}#$name")

fun Class<*>.hostFieldOrNull(name: String): Field? = try {
    hostField(name)
} catch (_: NoSuchFieldException) {
    null
}

fun Any?.hostField(name: String): Field? {
    val target = this ?: return null
    return target.javaClass.hostFieldOrNull(name)
}

/** 映射必须在缓存之外解析，以便新安装的 DexKit 描述符立刻生效。 */
private fun Class<*>.findHostMethod(
    name: String,
    parameters: HostMethodParameters,
    members: HostMembers,
    returnType: Class<*>? = null,
): Method? {
    HostSymbols.methodDescriptor(this, name)?.let { descriptor ->
        return members.describedMethod(descriptor, classLoader).matchHostContract(
            parameters, receiver = this, expectedReturnType = returnType,
        )
    }
    relocatedMethods(name, parameters, members, returnType)?.let {
        return HostMembers.uniqueMethod(it, "${this.name}#$name")
    }
    return findRawHostMethod(mappedMethodNames(name), name, parameters, members, returnType)
}

private fun Method.matchHostContract(
    parameters: HostMethodParameters,
    receiver: Class<*>? = null,
    expectedReturnType: Class<*>? = null,
): Method? {
    val isStatic = Modifier.isStatic(modifiers)
    require(isStatic || receiver == null || declaringClass.isAssignableFrom(receiver)) {
        "Host method owner ${declaringClass.name} is incompatible with ${receiver?.name}: $this"
    }
    if (receiver != null && declaringClass != receiver && declaringClass.isInterface && declaringClass.isAssignableFrom(receiver)) {
        require(!isStatic && Modifier.isPublic(modifiers)) {
            "Interface static/private method is not inherited by ${receiver.name}: $this"
        }
    }
    return takeIf {
        parameters.matches(parameterTypes.toList()) && (expectedReturnType == null || returnType == expectedReturnType)
    }
}

private fun Class<*>.findHostField(name: String, members: HostMembers): Field? {
    HostSymbols.fieldDescriptor(this, name)?.let { descriptor ->
        return members.describedField(descriptor, classLoader).also {
            require(it.declaringClass.isAssignableFrom(this)) { "Queried field owner does not match ${this.name}: $it" }
        }
    }
    val actualClass = this.name
    val mappedNames = buildSet {
        add(HostSymbols.resolveFieldName(this@findHostField, name))
        HostSymbols.logicalClassesOf(actualClass).forEach { add(HostSymbols.resolveFieldName(it, name)) }
    }.filter { it != name }
    val mapped = mappedNames.mapNotNull { members.field(this, it) }.distinct()
    require(mapped.size <= 1) { "Ambiguous host field ${this.name}#$name: ${mapped.joinToString()}" }
    return if (mappedNames.isNotEmpty()) mapped.singleOrNull() else members.field(this, name)
}

private fun Class<*>.mappedMethodNames(name: String): List<String> {
    return buildSet {
        add(HostSymbols.resolveMethodName(this@mappedMethodNames, name))
        // 合并门面可能有多个逻辑别名；不能用别名的枚举顺序选择同签名函数。
        for (type in hostMembers().hierarchyOf(this@mappedMethodNames)) {
            val candidates = HostSymbols.logicalClassesOf(type.name).mapNotNull { logical ->
                if (HostSymbols.resolveMethodOwner(logical, name) != type.name) null
                else HostSymbols.resolveMethodName(logical, name).takeIf { it != name }
            }
            addAll(candidates)
        }
    }.filter { it != name }
}

private fun Class<*>.findRawHostMethod(
    resolvedNames: List<String>,
    logicalName: String,
    parameters: HostMethodParameters,
    members: HostMembers,
    returnType: Class<*>?,
): Method? {
    val mapped = resolvedNames.distinct().filter { it != logicalName }.flatMap {
        members.methods(this, it, parameters, returnType = returnType)
    }
    return if (resolvedNames.any { it != logicalName }) {
        HostMembers.uniqueMethod(mapped, "${this.name}#$logicalName")
    } else members.method(this, logicalName, parameters, returnType = returnType)
}

/** null 表示无重定位；空集合表示已有重定位但该签名缺失，不允许回旧门面猜短名。 */
private fun Class<*>.relocatedMethods(
    name: String,
    parameters: HostMethodParameters,
    members: HostMembers,
    returnType: Class<*>? = null,
): List<Method>? {
    val routes = members.hierarchyOf(this).flatMap { type ->
        HostSymbols.logicalClassesOf(type.name).mapNotNull { logical ->
            val owner = HostSymbols.resolveMethodOwner(logical, name)
            if (owner == HostSymbols.resolveClass(logical)) null
            else owner to HostSymbols.resolveMethodName(logical, name)
        }
    }.distinct()
    if (routes.isEmpty()) return null
    return routes.flatMap { (owner, resolvedName) ->
        val target = members.namedClass(owner, classLoader)
        members.methods(target, resolvedName, parameters, staticOnly = true, returnType = returnType)
    }.distinct()
}
