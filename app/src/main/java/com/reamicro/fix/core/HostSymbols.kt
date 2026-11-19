package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.io.File

/** 当前宿主 APK 的动态查询结果及已安装的反射契约。 */
object HostSymbols {
    private const val LOG_PREFIX = "ReaMicro symbols"

    private class Contracts(val values: Map<String, String>) {
        val methodsByOwnerAndName: Map<Pair<String, String>, List<String>> by lazy {
            values.entries.filter { it.key.startsWith("method:") }.groupBy(
                { org.luckypray.dexkit.wrap.DexMethod(it.value).className to it.key.substringAfter('#') },
                { it.value },
            ).mapValues { (_, descriptors) -> descriptors.distinct() }
        }
    }

    private data class HostSource(
        val packageName: String,
        val loader: ClassLoader,
        val apk: String,
        val dataDir: String,
    )
    @Volatile private var initializedSource: HostSource? = null
    @Volatile private var active: HostProfile.Parsed? = null
    @Volatile private var contracts = Contracts(emptyMap())
    private var exactDescriptors: Map<String, String>
        get() = contracts.values
        set(values) { contracts = Contracts(values) }

    val profileId: String get() = if (active != null) "automatic" else "identity"
    val isIdentity: Boolean get() = active == null

    fun logicalClassesOf(actual: String): Set<String> =
        active?.aliasesOf(actual)?.takeIf { it.isNotEmpty() } ?: setOf(actual)

    /** 在安装 hook 前，使用宿主 ClassLoader、APK 和数据目录建立当前代际的符号表。 */
    @JvmStatic
    fun initialize(
        classLoader: ClassLoader,
        hostPackage: String,
        hostApkPath: String,
        hostDataDir: String,
    ) {
        synchronized(this) {
            val source = HostSource(hostPackage, classLoader,
                File(hostApkPath).absolutePath, File(hostDataDir).absolutePath)
            initializedSource?.let {
                if (it.packageName == source.packageName && it.loader === source.loader &&
                    it.apk == source.apk && it.dataDir == source.dataDir && active != null) return
                HookRuntime.log("$LOG_PREFIX host source or classloader changed，重建符号表")
            }
            // A failed first scan must remain retryable. Package equality alone
            // cannot reuse classes from a different loader of the same host.
            initializedSource = null
            active = null
            exactDescriptors = emptyMap()
            HostAutoScanner.configure(classLoader)
            val scan = HostAutoScanner.scan(hostApkPath, hostDataDir)
            active = scan?.profile
            exactDescriptors = HostAutoScanner.queriedDescriptors().orEmpty()
            if (active != null) initializedSource = source
            HookInstallReport.record("HostSymbols", "automaticScan", scan?.complete == true,
                if (scan?.complete == true) null else IllegalStateException(
                    scan?.failures?.take(8)?.joinToString() ?: "当前 APK 动态符号扫描失败"))
            HookInstallReport.record("HostSymbols", "profile", active != null,
                if (active != null) null else IllegalStateException("当前 APK 未产生可用的动态符号"))
            HookRuntime.logAlways("$LOG_PREFIX package=$hostPackage profile=$profileId " +
                "mapped=${active?.classes?.size ?: 0} descriptors=${exactDescriptors.size} cached=${scan?.cached}")
        }
    }

    /** Add a feature's already validated bindings before hooks are installed; nothing is persisted here. */
    internal fun installFeatureDescriptors(values: Map<String, String>) {
        synchronized(this) {
            val parsed = active ?: return
            exactDescriptors = exactDescriptors + values
            val classes = parsed.classes.toMutableMap()
            val aliases = parsed.typeAliases.toMutableMap()
            val methods = parsed.methods.toMutableMap()
            val owners = parsed.methodOwners.toMutableMap()
            val fields = parsed.fields.toMutableMap()
            values.forEach { (key, descriptor) ->
                val logical = key.substringAfter(':')
                when {
                    key.startsWith("class:") -> {
                        val actual = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
                        if (logical in classes || actual !in classes.values) classes[logical] = actual else aliases[logical] = actual
                    }
                    key.startsWith("method:") -> org.luckypray.dexkit.wrap.DexMethod(descriptor).let {
                        methods[logical] = it.name; owners[logical] = it.className
                    }
                    key.startsWith("field:") -> fields[logical] = org.luckypray.dexkit.wrap.DexField(descriptor).name
                }
            }
            active = parsed.copy(classes = classes, typeAliases = aliases, methods = methods, methodOwners = owners, fields = fields)
        }
    }

    /** 逻辑名 -> 实际名；identity 或未登记时原样返回。 */
    @JvmStatic
    fun resolveClass(logical: String): String = active?.classOf(logical) ?: logical

    /** 实际名 -> 逻辑名，供只拿到 Class 对象的调用点使用。 */
    @JvmStatic
    fun logicalClass(actual: String): String = active?.logicalClassOf(actual) ?: actual

    /** 只拿到运行时 Class 时，仍按业务层看到的简单类名做状态判断。 */
    @JvmStatic
    fun logicalSimpleName(actual: String): String =
        logicalClass(actual).substringAfterLast('$').substringAfterLast('.')

    @JvmStatic
    fun resolveMethodName(logicalClass: String, logicalName: String): String {
        val parsed = active ?: return logicalName
        parsed.methodOf(logicalClass, logicalName)?.let { return it }
        val actual = parsed.classOf(logicalClass) ?: logicalClass
        return mappedAliasName(parsed, actual, logicalName) { parsed.methodOf(it, logicalName) } ?: logicalName
    }

    /** 继承方法的映射归声明类所有，不能只查运行时子类名。 */
    @JvmStatic
    fun resolveMethodName(type: Class<*>, logicalName: String): String {
        val parsed = active ?: return logicalName
        for (owner in typeHierarchy(type)) {
            mappedAliasName(parsed, owner.name, logicalName) { parsed.methodOf(it, logicalName) }?.let { return it }
        }
        val relocated = parsed.relocatedMethodNames(type.name, logicalName)
        check(relocated.size <= 1) { "Ambiguous relocated method ${type.name}#$logicalName: $relocated" }
        return relocated.singleOrNull() ?: logicalName
    }

    /** R8 可把同一 Kotlin 门面的不同方法移到不同持有者，按成员而非类解析。 */
    @JvmStatic
    fun resolveMethodOwner(logicalClass: String, logicalName: String): String =
        active?.methodOwners?.get("$logicalClass#$logicalName") ?: resolveClass(logicalClass)

    /** Installation snapshot: rescanning must never change a member used by live hooks. */
    @JvmStatic
    fun methodDescriptor(logicalOwner: String, logicalName: String): String? {
        exactDescriptors["method:$logicalOwner#$logicalName"]?.let { return it }
        val actual = resolveClass(logicalOwner)
        val aliases = logicalClassesOf(actual) + logicalClass(actual)
        val candidates = aliases.mapNotNull { exactDescriptors["method:$it#$logicalName"] }.distinct()
        check(candidates.size <= 1) { "Ambiguous queried method $logicalOwner#$logicalName: $candidates" }
        return candidates.singleOrNull()
    }

    @JvmStatic
    fun methodDescriptor(type: Class<*>, logicalName: String): String? {
        val inherited = typeHierarchy(type).mapNotNull { methodDescriptor(it.name, logicalName) }.distinct()
        check(inherited.size <= 1) { "Conflicting queried contracts ${type.name}#$logicalName: $inherited" }
        inherited.singleOrNull()?.let { return it }
        val relocated = contracts.methodsByOwnerAndName[type.name to logicalName].orEmpty()
        check(relocated.size <= 1) { "Ambiguous relocated method ${type.name}#$logicalName: $relocated" }
        return relocated.singleOrNull()
    }
    @JvmStatic
    fun fieldDescriptor(logicalOwner: String, logicalName: String): String? {
        exactDescriptors["field:$logicalOwner#$logicalName"]?.let { return it }
        val actual = resolveClass(logicalOwner)
        val candidates = (logicalClassesOf(actual) + logicalClass(actual)).mapNotNull {
            exactDescriptors["field:$it#$logicalName"]
        }.distinct()
        check(candidates.size <= 1) { "Ambiguous queried field $logicalOwner#$logicalName: $candidates" }
        return candidates.singleOrNull()
    }
    @JvmStatic
    fun fieldDescriptor(type: Class<*>, logicalName: String): String? {
        // Unlike virtual methods, a field belongs to one declaring class. Honor a
        // nearer explicitly queried field before considering a hidden parent field.
        for (owner in typeHierarchy(type)) fieldDescriptor(owner.name, logicalName)?.let { return it }
        return null
    }
    @JvmStatic
    fun accessorFieldDescriptor(type: Class<*>, getter: String): String? {
        fieldDescriptor(type, getter)?.let { return it }
        val property = accessorPropertyName(getter) ?: return null
        return fieldDescriptor(type, property)
    }

    @JvmStatic
    fun methodNames(type: Class<*>, logicalPrefix: String): Set<String> = buildSet {
        add(logicalPrefix)
        val parsed = active ?: return@buildSet
        for (owner in typeHierarchy(type)) {
            for (logical in setOf(logicalClass(owner.name)) + parsed.aliasesOf(owner.name)) {
                addAll(parsed.methodNamesOf(logical, logicalPrefix))
            }
        }
    }

    private fun typeHierarchy(type: Class<*>): List<Class<*>> = hostMembers().symbolHierarchyOf(type)

    @JvmStatic
    fun resolveFieldName(logicalClass: String, logicalName: String): String {
        val parsed = active ?: return logicalName
        parsed.fieldOf(logicalClass, logicalName)?.let { return it }
        val actual = parsed.classOf(logicalClass) ?: logicalClass
        return mappedAliasName(parsed, actual, logicalName) { parsed.fieldOf(it, logicalName) } ?: logicalName
    }

    @JvmStatic
    fun resolveFieldName(type: Class<*>, logicalName: String): String {
        val parsed = active ?: return logicalName
        for (owner in typeHierarchy(type)) {
            mappedAliasName(parsed, owner.name, logicalName) { parsed.fieldOf(it, logicalName) }?.let { return it }
        }
        return logicalName
    }

    /** 显式逻辑 owner 由调用方选定；只有实际 owner 才必须消除多个别名的歧义。 */
    private inline fun mappedAliasName(
        parsed: HostProfile.Parsed,
        actualOwner: String,
        logicalName: String,
        memberName: (String) -> String?,
    ): String? {
        val candidates = buildSet {
            memberName(actualOwner)?.let(::add)
            parsed.aliasesOf(actualOwner).forEach { alias -> memberName(alias)?.let(::add) }
        }
        check(candidates.size <= 1) { "Ambiguous mapped member $actualOwner#$logicalName: $candidates" }
        return candidates.singleOrNull()
    }

    /**
     * 混淆器可能删掉 Kotlin data class 的 getter。此时把 getX/isX 还原成属性名，
     * 再查当前查询产生的字段映射，让调用方仍能按稳定属性名读取字段。
     */
    @JvmStatic
    fun resolveAccessorFieldName(logicalClass: String, accessorName: String): String? {
        if (isIdentity) return null
        val property = accessorPropertyName(accessorName) ?: return null
        val parsed = active ?: return null
        return parsed.fieldOf(logicalClass, accessorName)
            ?: parsed.fieldOf(logicalClass, accessorName.removePrefix("get"))
            ?: parsed.fieldOf(logicalClass, property)
            ?: parsed.fieldOf(resolveClass(logicalClass), property)
    }

    /** 访问器可能声明在父类；字段映射也必须沿父类链查找（NodeDom.getTag -> Dom.tag）。 */
    @JvmStatic
    fun resolveAccessorFieldName(type: Class<*>, accessorName: String): String? {
        val property = accessorPropertyName(accessorName) ?: return null
        val parsed = active ?: return null
        for (owner in typeHierarchy(type)) {
            for (name in listOf(accessorName, accessorName.removePrefix("get"), property).distinct()) {
                mappedAliasName(parsed, owner.name, name) { parsed.fieldOf(it, name) }?.let { return it }
            }
        }
        return null
    }

    fun summary(): String {
        val parsed = active ?: return "profile=identity"
        return "profile=$profileId classes=${parsed.classes.size} " +
            "methods=${parsed.methods.size} fields=${parsed.fields.size}"
    }

    private fun accessorPropertyName(name: String): String? {
        val accessor = name.substringBefore('-')
        fun property(value: String): String =
            if (value.length > 1 && value[0].isUpperCase() && value[1].isUpperCase()) value
            else value.replaceFirstChar { it.lowercaseChar() }
        if (accessor.length <= 3 || !accessor.startsWith("get")) {
            if (accessor.length <= 2 || !accessor.startsWith("is")) return null
            return property(accessor.substring(2))
        }
        return property(accessor.substring(3))
    }

}
