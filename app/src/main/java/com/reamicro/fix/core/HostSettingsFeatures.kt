package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass

/** Settings entry queries follow the state/VM/lazy-content graph, never a merged-lambda branch number. */
internal object HostSettingsFeatures {
    @Volatile var current: Symbols? = null
        private set
    @Volatile internal var initializationAttempted: Boolean = false
        private set
    @Volatile private var descriptors: Map<String, String>? = null
    internal fun boundDescriptors(): Map<String, String>? = descriptors?.toMap()

    class Symbols internal constructor(
        val root: Method,
        val body: Method,
        val builder: Method,
        val item: Method,
        val viewModelClass: Class<*>,
        val navigationClass: Class<*>,
        private val viewModelField: Field,
        private val navigationField: Field,
        private val entryWrapper: Class<*>,
        private val entryCallback: Class<*>,
        private val entryKeyField: Field,
        private val entryCallbackField: Field,
        private val entryKey: Int,
    ) {

        /** Check the actual objects created by the host, including after deferred composition. */
        fun isSettingsBuilder(value: Any): Boolean {
            if (!builder.declaringClass.isInstance(value)) return false
            return viewModelClass.isInstance(viewModelField.get(value)) && navigationClass.isInstance(navigationField.get(value))
        }
        fun isDefaultHomeItem(content: Any?): Boolean =
            content != null && entryWrapper.isInstance(content) &&
                entryKeyField.getInt(content) == entryKey &&
                entryCallback.isInstance(entryCallbackField.get(content))
    }

    fun initialize(loader: ClassLoader, apkPath: String, dataDir: String) {
        initializationAttempted = true
        var bound: Map<String, String>? = null
        current = runCatching {
            HostQuerySession.use(loader, apkPath, dataDir) { session ->
                var restored: Symbols? = null
                val result = session.query("settings.entry",
                    validate = { restored = restore(it, loader) }, find = { query(it, apkPath) })
                HookRuntime.log("ReaMicro settings entry ${if (result.cached) "cached" else "resolved"}")
                bound = result.values.toMap()
                requireNotNull(restored)
            }
        }.onFailure { HookRuntime.logError("ReaMicro settings entry query unavailable", it) }.getOrNull()
        descriptors = bound.takeIf { current != null }
    }

    internal fun query(bridge: DexKitBridge, apk: String): Map<String, String> {
        val flow = HostDexCalls(apk)
        fun find(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.let {
            it.singleOrNull() ?: error("$label: expected one candidate, found ${it.size}: ${it.map { m -> m.descriptor }}")
        }
        val state = requireNotNull(find(MethodMatcher.create().usingEqStrings("SettingsUIState(currentUser="))
            .one("settings state").declaredClass)
        val vm = requireNotNull(find(MethodMatcher.create().name("<init>").paramCount(4)).filter { method ->
            "app.zhendong.reamicro.data.db.AppDatabase" in method.paramTypeNames &&
                method.invokes.any { it.declaredClassName == state.name }
        }.one("settings view model").declaredClass)
        val root = find(MethodMatcher.create().paramCount(3).returnType("void")).filter { method ->
            Modifier.isStatic(method.modifiers) && method.paramTypeNames.last() == "int" &&
                method.usingFields.any { it.field.declaredClassName == state.name }
        }.one("settings root")
        val composer = root.paramTypeNames[1]
        val bodies = root.invokes.filter { it.name == "<init>" }.mapNotNull { it.declaredClass }
            .distinctBy { it.descriptor }.flatMap { it.methods }.filter { method ->
                method.paramTypeNames == List(3) { "java.lang.Object" } && method.returnTypeName == "java.lang.Object" &&
                    method.invokes.any { call ->
                        call.paramCount == 12 && call.paramTypeNames[8] == "kotlin.jvm.functions.Function1" &&
                            call.paramTypeNames.takeLast(3) == listOf(composer, "int", "int") && call.returnTypeName == "void"
                    }
            }
        val body = bodies.one("settings lazy content")
        val builderClass = requireNotNull(body.invokes.filter {
            it.name == "<init>" && it.declaredClass?.interfaces?.any { type ->
                type.name == "kotlin.jvm.functions.Function1"
            } == true
        }.one("settings list callback").declaredClass)
        val builder = builderClass.methods.filter {
            it.name == "invoke" && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object"
        }.one("settings list builder")
        check(builder.invokes.any { it.name == "<init>" && vm.name in it.paramTypeNames }) {
            "Settings list callback is not linked to the settings view model"
        }
        val itemDefault = builder.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.paramCount == 4 && it.paramTypeNames.takeLast(3) ==
                listOf("java.lang.String", "kotlin.jvm.functions.Function3", "int") && it.returnTypeName == "void"
        }.one("settings lazy item default")
        val item = itemDefault.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames ==
                listOf("java.lang.Object", "java.lang.Object", "kotlin.jvm.functions.Function3") && it.returnTypeName == "void"
        }.one("settings lazy item")
        fun constants(construction: HostDexCalls.Call): Map<String, Long> = flow.writes(construction.descriptor).mapNotNull { write ->
            val value = when (val source = write.value) {
                is HostDexCalls.Literal -> source
                is HostDexCalls.Parameter -> construction.arguments.getOrNull(source.index) as? HostDexCalls.Literal
                else -> null
            } ?: return@mapNotNull null
            val field = requireNotNull(bridge.getFieldData(write.field))
            if (write.receiver != HostDexCalls.Parameter(0) || !Modifier.isFinal(field.modifiers) ||
                field.typeName !in setOf("int", "byte", "short", "boolean", "long", "char")) return@mapNotNull null
            write.field to value.value
        }.toMap()
        val builderConstruction = flow.calls(body.descriptor).filter { call ->
            call.arguments.firstOrNull() is HostDexCalls.Allocation && bridge.getMethodData(call.descriptor)?.let {
                it.isConstructor && it.declaredClass?.descriptor == builderClass.descriptor
            } == true
        }.distinct().single()
        val builderConstants = constants(builderConstruction)
        val captures = listOf("viewModel" to vm, "navigation" to root.paramTypes[0]).associate { (role, type) ->
            val field = flow.castsWithFields(builder.descriptor, builderConstants).filter { it.type == type.descriptor }
                .mapNotNull { it.value as? HostDexCalls.Field }.distinct().single()
            val data = requireNotNull(bridge.getFieldData(field.descriptor))
            check(!Modifier.isStatic(data.modifiers) && data.declaredClassName == builderClass.name && data.typeName == "java.lang.Object")
            "field:builder.$role" to field.descriptor
        }
        val defaultHome = find(MethodMatcher.create().usingEqStrings("默认首页", "书架样式"))
            .filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                composer in it.paramTypeNames }.one("native default-home preferences")
        val contentBodies = defaultHome.callers.filter { method ->
            !Modifier.isStatic(method.modifiers) && method.paramTypeNames == List(3) { "java.lang.Object" } &&
                method.returnTypeName == "java.lang.Object"
        }.distinctBy { it.descriptor }
        // Follow the item's actual wrapper and callback allocation. Shared callbacks are
        // constrained only by final fields written from their constructor arguments.
        data class Entry(val construction: HostDexCalls.Call, val callback: org.luckypray.dexkit.result.ClassData,
            val callbackConstruction: HostDexCalls.Call)
        val calls = flow.callsWithFields(builder.descriptor, builderConstants)
        val entries = calls.filter { it.descriptor == itemDefault.descriptor }.mapNotNull { registration ->
            val wrapper = registration.arguments.getOrNull(2) as? HostDexCalls.Allocation ?: return@mapNotNull null
            val construction = calls.singleOrNull { call -> call.arguments.firstOrNull() == wrapper &&
                bridge.getMethodData(call.descriptor)?.isConstructor == true } ?: return@mapNotNull null
            val constructor = requireNotNull(bridge.getMethodData(construction.descriptor))
            if (constructor.paramTypeNames != listOf("int", "boolean", "java.lang.Object")) return@mapNotNull null
            val callback = construction.arguments.getOrNull(3) as? HostDexCalls.Allocation ?: return@mapNotNull null
            val callbackConstruction = calls.singleOrNull { call -> call.arguments.firstOrNull() == callback &&
                bridge.getMethodData(call.descriptor)?.isConstructor == true } ?: return@mapNotNull null
            val constants = constants(callbackConstruction)
            val body = contentBodies.singleOrNull { method -> method.declaredClass?.descriptor == callback.type &&
                flow.callsWithFields(method.descriptor, constants).any { it.descriptor == defaultHome.descriptor } }
                ?: return@mapNotNull null
            Entry(construction, requireNotNull(body.declaredClass), callbackConstruction)
        }.distinct().singleOrNull() ?: error("Default-home lazy item is missing or ambiguous")
        val wrapperConstructor = requireNotNull(bridge.getMethodData(entries.construction.descriptor))
        val wrapperWrites = flow.writes(wrapperConstructor.descriptor)
        fun wrapperField(parameter: Int): String = wrapperWrites.filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(parameter)
        }.map { it.field }.distinct().single()
        val key = (entries.construction.arguments[1] as? HostDexCalls.Literal)?.value?.toInt()
            ?: error("Default-home wrapper key is not proven")
        return linkedMapOf(
            "method:root" to root.descriptor,
            "method:body" to body.descriptor,
            "method:builder" to builder.descriptor,
            "method:item" to item.descriptor,
            "class:vm" to vm.descriptor,
            "class:navigation" to root.paramTypes[0].descriptor,
            "class:entry.wrapper" to requireNotNull(wrapperConstructor.declaredClass).descriptor,
            "class:entry.callback" to entries.callback.descriptor,
            "field:entry.key" to wrapperField(1),
            "field:entry.callback" to wrapperField(3),
            "value:entry.key" to key.toString(),
            "method:defaultHome" to defaultHome.descriptor,
            "constructor:entry.wrapper" to wrapperConstructor.descriptor,
            "constructor:entry.callback" to entries.callbackConstruction.descriptor,
        ) + captures
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Symbols {
        check(values.keys == setOf("method:root", "method:body", "method:builder", "method:item", "class:vm", "class:navigation",
            "field:builder.viewModel", "field:builder.navigation", "class:entry.wrapper", "class:entry.callback",
            "field:entry.key", "field:entry.callback", "value:entry.key", "method:defaultHome",
            "constructor:entry.wrapper", "constructor:entry.callback"))
        val resolver = HostMembers()
        fun method(key: String) = resolver.describedMethod(values.getValue("method:$key"), loader)
        val root = method("root")
        val body = method("body")
        val builder = method("builder")
        val item = method("item")
        val navigation = DexClass(values.getValue("class:navigation")).getInstance(loader)
        val vm = DexClass(values.getValue("class:vm")).getInstance(loader)
        check(Modifier.isStatic(root.modifiers) && root.parameterCount == 3 && root.parameterTypes[0] == navigation &&
            root.parameterTypes.last() == Int::class.javaPrimitiveType && root.returnType == Void.TYPE)
        check(!Modifier.isStatic(body.modifiers) && body.parameterTypes.toList() == List(3) { Any::class.java } && body.returnType == Any::class.java)
        check(!Modifier.isStatic(builder.modifiers) && builder.parameterTypes.toList() == listOf(Any::class.java) && builder.returnType == Any::class.java)
        check(!Modifier.isStatic(item.modifiers) && item.parameterCount == 3 &&
            item.parameterTypes.take(2) == listOf(Any::class.java, Any::class.java) &&
            item.parameterTypes[2].name == "kotlin.jvm.functions.Function3" && item.returnType == Void.TYPE)
        check(vm.declaredFields.any { it.type.name == "app.zhendong.reamicro.data.db.AppDatabase" })
        val fields = listOf("viewModel", "navigation").map { role ->
            resolver.describedField(values.getValue("field:builder.$role"), loader).also {
                check(!Modifier.isStatic(it.modifiers) && it.declaringClass == builder.declaringClass && it.type == Any::class.java)
            }
        }
        check(fields[0] != fields[1])
        val wrapper = DexClass(values.getValue("class:entry.wrapper")).getInstance(loader)
        val callback = DexClass(values.getValue("class:entry.callback")).getInstance(loader)
        val entryFields = listOf("key", "callback").map { role ->
            resolver.describedField(values.getValue("field:entry.$role"), loader).also {
                check(it.declaringClass == wrapper && !Modifier.isStatic(it.modifiers))
            }
        }
        check(entryFields[0].type == Int::class.javaPrimitiveType && entryFields[1].type == Any::class.java)
        check(Modifier.isFinal(entryFields[0].modifiers))
        check(resolver.describedConstructor(values.getValue("constructor:entry.wrapper"), loader).let {
            it.declaringClass == wrapper && it.parameterTypes.toList() == listOf(Integer.TYPE, java.lang.Boolean.TYPE, Any::class.java)
        })
        check(resolver.describedConstructor(values.getValue("constructor:entry.callback"), loader).declaringClass == callback)
        check(item.parameterTypes[2].isAssignableFrom(wrapper) && item.parameterTypes[2].isAssignableFrom(callback))
        val defaultHome = method("defaultHome")
        check(Modifier.isStatic(defaultHome.modifiers) && defaultHome.returnType == Void.TYPE &&
            defaultHome.parameterTypes.contains(root.parameterTypes[1]))
        return Symbols(root, body, builder, item, vm, navigation, fields[0], fields[1],
            wrapper, callback, entryFields[0], entryFields[1], values.getValue("value:entry.key").toInt())
    }
}
