package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Feature queries describe behavior and signatures, never a previous build's short names. */
internal object HostReaderFeatures {
    private val required = setOf("content", "title", "previewModifier", "button", "settingsTopBar",
        "endGroup", "startGroup", "toggle", "slider", "colors", "state", "modifier", "unit", "udp",
        "dispatch", "sendIntent", "tap", "clear", "voice", "voiceBody", "voiceLabel", "voiceSlider",
        "voiceSemantics", "voiceDescription", "settingsGroup", "settingsGroupInvoke",
        "settingsGroupDispatch", "settingsGroupFunction")
    @Volatile var current: Symbols? = null
        private set
    @Volatile private var descriptors: Map<String, String>? = null

    internal fun boundDescriptors(): Map<String, String>? = descriptors?.toMap()

    class Symbols(private val members: Map<String, Method>, private val fields: Map<String, Field>,
        private val constructors: Map<String, Constructor<*>>, val loader: ClassLoader) {
        operator fun get(key: String): Method = members.getValue(key)
        fun optional(key: String): Method? = members[key]
        fun field(key: String): Field = fields.getValue(key)
        fun constructor(key: String): Constructor<*> = constructors.getValue(key)
        val composerClass get() = get("title").parameterTypes[2]
        val modifierClass get() = get("title").parameterTypes[1]
        val unit: Any by lazy { requireNotNull(field("unit").get(null)) }
        val modifier: Any by lazy { requireNotNull(field("modifier").get(null)) }
    }

    fun initialize(loader: ClassLoader, apkPath: String, dataDir: String) {
        var bound: Map<String, String>? = null
        current = runCatching {
            HostQuerySession.use(loader, apkPath, dataDir) { session ->
                var restored: Map<String, Method>? = null
                val result = session.query("reader.controls",
                    validate = { values -> restored = restore(values, loader) },
                    find = ::query)
                val methods = requireNotNull(restored)
                runCatching {
                    val obsolete = File(dataDir, "files/reamicro_reader_features.json")
                    check(!obsolete.exists() || obsolete.delete()) { "Cannot remove obsolete reader feature cache" }
                }.onFailure { HookRuntime.logError("ReaMicro obsolete reader cache cleanup failed", it) }
                val source = if (result.cached) "cached" else "resolved"
                HookRuntime.log("ReaMicro reader features $source: ${methods.size} methods")
                bound = result.values.toMap()
                Symbols(methods, restoreFields(result.values, loader), restoreConstructors(result.values, loader), loader)
            }
        }.onFailure {
            HookRuntime.logError("ReaMicro reader feature resolution failed", it)
        }.getOrNull()
        descriptors = bound.takeIf { current != null }
    }

    internal fun query(bridge: DexKitBridge): Map<String, String> {
        fun find(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        fun anchor(vararg strings: String) = find(MethodMatcher.create().usingEqStrings(*strings)).single()
        fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.let { unique ->
            unique.singleOrNull() ?: error("$label: expected one candidate, found ${unique.size}: ${unique.map { it.descriptor }}")
        }
        val result = linkedMapOf<String, String>()
        fun save(key: String, method: MethodData): MethodData {
            result[key] = method.descriptor
            return method
        }
        fun optional(label: String, block: () -> Unit) {
            val previous = result.keys.toSet()
            runCatching(block).onFailure {
                result.keys.retainAll(previous)
                HookRuntime.logError("ReaMicro reader $label query unavailable", it)
            }
        }
        val content = save("content", anchor("翻页方式", "查看插图", "覆盖", "平移", "滚动"))
        check(content.paramCount == 3 && content.returnTypeName == "java.lang.Object")
        val title = save("title", content.invokes.filter {
            it.paramCount == 4 && it.paramTypeNames.first() == "java.lang.String" &&
                it.paramTypeNames.last() == "int" && it.returnTypeName == "void"
        }.one("page section title"))
        val composer = title.paramTypeNames[2]
        val modifier = title.paramTypeNames[1]
        save("previewModifier", content.invokes.filter {
            it.paramTypeNames == listOf("int", modifier) && it.returnTypeName == modifier
        }.one("page corner preview modifier"))
        save("button", content.invokes.filter {
            it.paramCount == 5 && it.paramTypeNames.take(2) == listOf("java.lang.String", "boolean") &&
                it.paramTypeNames[3] == composer
        }.one("page mode button"))
        val function0 = result.getValue("button").let { content.invokes.first { m -> m.descriptor == it }.paramTypeNames[2] }
        save("settingsTopBar", anchor("更多设置").invokes.filter {
            it.paramCount == 11 && it.paramTypeNames[0] == "java.lang.String" &&
                it.paramTypeNames[4] == function0 && it.paramTypeNames[8] == composer
        }.one("reader settings top bar"))
        save("endGroup", content.invokes.filter {
            it.className == composer && it.paramTypeNames == listOf("boolean") && it.returnTypeName == "void"
        }.one("composer end group"))
        save("startGroup", content.invokes.filter {
            it.className == composer && it.paramTypeNames == listOf("int") && it.returnTypeName == "void"
        }.one("composer start group"))
        save("toggle", requireNotNull(title.declaredClass).methods.filter {
            it.paramTypeNames == listOf("java.lang.String", "boolean", "kotlin.jvm.functions.Function1",
                "java.lang.String", composer, "int", "int") && it.returnTypeName == "void"
        }.one("native settings toggle"))
        // The toggle itself is transparent; the settings group owns its theme background.
        val settingsGroup = save("settingsGroup", requireNotNull(title.declaredClass).methods.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 5 &&
                it.paramTypeNames[0] == modifier && it.paramTypeNames.drop(2) == listOf(composer, "int", "int")
        }.one("native settings group"))
        check(content.invokes.any { it.descriptor == settingsGroup.descriptor })
        val groupLambda = requireNotNull(settingsGroup.paramTypes[1])
        result["constructor:settingsGroupContent"] = groupLambda.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf("int", "boolean", "java.lang.Object")
        }.one("native settings group content constructor").descriptor
        val groupInvoke = save("settingsGroupInvoke", settingsGroup.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.className == groupLambda.name &&
                it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("native settings group content invocation"))
        val groupDispatch = save("settingsGroupDispatch", groupInvoke.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.className == groupLambda.name &&
                it.paramTypeNames == listOf("java.lang.Object", composer, "int") &&
                it.returnTypeName == "java.lang.Object"
        }.one("native settings group scoped dispatch"))
        result["field:settingsGroupCallback"] = groupDispatch.usingFields.map { it.field }.filter {
            !Modifier.isStatic(it.modifiers) && it.className == groupLambda.name && it.typeName == "java.lang.Object"
        }.distinctBy { it.descriptor }.single().descriptor
        save("settingsGroupFunction", groupDispatch.invokes.filter {
            it.className == "kotlin.jvm.functions.Function3" &&
                it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("native settings group Function3"))
        save("udp", title.invokes.filter {
            it.paramTypeNames == listOf("int") && it.returnTypeName == "float"
        }.one("native dimension scale"))
        val slider = save("slider", find(MethodMatcher.create().paramCount(17).returnType("void")).filter {
            val p = it.paramTypeNames
            p.take(3) == listOf("float", "kotlin.jvm.functions.Function1", modifier) &&
                p.subList(8, 11) == List(3) { "java.lang.String" } && p[14] == composer
        }.one("native type slider"))
        save("colors", find(MethodMatcher.create().returnType(slider.paramTypeNames[12]).paramCount(7)).filter {
            it.paramTypeNames.takeLast(2) == listOf(composer, "int") && Modifier.isStatic(it.modifiers)
        }.one("native slider colors"))
        // The listening speed control owns the ruler, bubble, thumb and pointer/semantics path.
        val voiceBody = save("voiceBody", find(MethodMatcher.create().usingEqStrings("快", "慢")).filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object"
        }.one("listening speed ruler body"))
        val voiceConstructor = requireNotNull(voiceBody.declaredClass).methods.filter { it.isConstructor &&
            it.paramTypeNames.take(4) == List(4) { "float" } && it.paramTypeNames[4] == "java.lang.String" &&
            it.paramTypeNames[5] == "float" && it.paramTypeNames[6] == "kotlin.jvm.functions.Function1" &&
            it.paramTypeNames.last() == "int"
        }.one("listening ruler capture")
        result["constructor:voiceBody"] = voiceConstructor.descriptor
        save("voice", voiceConstructor.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf("java.lang.String", "float", "kotlin.jvm.functions.Function1", modifier, composer, "int")
        }.one("listening speed control"))
        save("voiceSlider", voiceBody.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramCount == 15 && it.paramTypeNames.take(3) == listOf("float", "kotlin.jvm.functions.Function1", modifier) &&
            it.paramTypeNames[7] == "int" && it.paramTypeNames[11] == composer
        }.one("listening slider"))
        val voiceLabel = save("voiceLabel", anchor("语速 "))
        result["constructor:voiceLabel"] = voiceBody.invokes.filter { it.isConstructor && it.declaredClassName == voiceLabel.declaredClassName &&
            it.paramCount == 3 && it.paramTypeNames.first() == "int" && it.paramTypeNames.last() == "byte"
        }.one("listening ruler label capture").descriptor
        val voiceSemantics = save("voiceSemantics", anchor("语速"))
        result["constructor:voiceSemantics"] = voiceBody.invokes.filter { it.isConstructor && it.declaredClassName == voiceSemantics.declaredClassName &&
            it.paramTypeNames == listOf("java.lang.String", "byte")
        }.one("listening ruler semantics capture").descriptor
        save("voiceDescription", voiceSemantics.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramCount == 2 && it.paramTypeNames.last() == "java.lang.String" &&
                // ContentDescription stores a list; StateDescription stores the scalar string.
                it.invokes.any { call -> call.paramCount == 1 && call.returnTypeName == "java.util.List" }
        }.one("listening ruler content description"))
        save("unit", find(MethodMatcher.create().usingEqStrings("kotlin.Unit").paramCount(0).returnType("java.lang.String"))
            .filter { it.name == "toString" && !Modifier.isStatic(it.modifiers) }.one("kotlin Unit"))
        save("modifier", find(MethodMatcher.create().usingEqStrings("Modifier")).filter {
            it.name == "toString" && it.declaredClass?.interfaces?.any { type -> type.name == modifier } == true
        }.one("Modifier companion"))
        val state = anchor("MutableState(value=", ")@").className
        save("state", find(MethodMatcher.create().returnType(state).paramTypes("java.lang.Object")).filter {
            Modifier.isStatic(it.modifiers)
        }.one("mutableState factory"))
        val dispatch = save("dispatch", find(MethodMatcher.create().usingEqStrings("ReaderViewModel", "摘抄内容: ")).filter {
            it.paramCount == 1 && it.returnTypeName == "void" && !Modifier.isStatic(it.modifiers)
        }.one("reader intent receiver"))
        save("sendIntent", requireNotNull(dispatch.declaredClass?.superClass).methods.filter {
            it.paramTypeNames == dispatch.paramTypeNames && it.returnTypeName == "void" && Modifier.isFinal(it.modifiers)
        }.one("reader intent queue"))
        save("tap", anchor("TapDirection(direction="))
        // ViewModel.clear is the final no-argument void method on the first lifecycle base.
        var parent = requireNotNull(dispatch.declaredClass).superClass
        while (parent != null) {
            val candidates = parent.methods.filter {
                it.isMethod && it.paramCount == 0 && it.returnTypeName == "void" &&
                    Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }
            if (candidates.size == 1) { save("clear", candidates.single()); break }
            parent = parent.superClass
        }
        check("clear" in result) { "Reader lifecycle clear missing" }
        optional("overlay") {
            val deps = anchor("SwipePagerDeps(getVirtualPage=").className
            val overlay = save("overlayAction", find(MethodMatcher.create().returnType("void").paramCount(16, 18)).filter {
                val p = it.paramTypeNames
                val start = if (it.paramCount == 18) 13 else 12
                deps in p.take(start) && p.drop(start).take(4) == listOf("int", "float", "float", "boolean") &&
                    Modifier.isStatic(it.modifiers)
            }.one("overlay completion action"))
            val coroutine = overlay.invokes.filter {
                it.isConstructor && it.paramCount >= 16 && deps in it.paramTypeNames
            }.one("overlay completion coroutine").declaredClass
            val resume = save("overlayResume", requireNotNull(coroutine).methods.filter {
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" &&
                    it.usingStrings.any { s -> s.contains("resume") && s.contains("invoke") }
            }.one("overlay resume"))
            save("tween", resume.invokes.filter {
                it.paramCount == 4 && it.paramTypeNames.take(2) == listOf("int", "int") &&
                    it.paramTypeNames.last() == "int" && it.returnTypeName != "void"
            }.one("overlay tween factory"))
        }
        optional("swipe") {
            val deps = anchor("SwipePagerDeps(getVirtualPage=").className
            val tweenType = org.luckypray.dexkit.wrap.DexMethod(result.getValue("tween")).returnTypeName
            val stateType = anchor("ReaderPagerState(initialPage=").className
            val flows = anchor("ReaderPagerFlows(bookProgress=").className
            val actions = anchor("ReaderPagerActions(onJumpCompleted=").className
            val pagerDeps = anchor("ReaderPagerDeps(findVirtualPageForCfi=").className
            val swipe = save("swipeEffects", find(MethodMatcher.create().paramCount(9, 11).returnType("void")).filter {
                val p = it.paramTypeNames
                p.filter { name -> name in listOf(stateType, flows, actions, pagerDeps, deps) } ==
                    listOf(stateType, flows, actions, pagerDeps, deps) && p.takeLast(2) == listOf(composer, "int") &&
                    (p.indexOf(deps) == p.size - 3 || p.size == 9)
            }.one("swipe effects"))
            if (swipe.paramTypeNames.indexOf(deps) == swipe.paramCount - 3) return@optional
            val pagerTypes = generateSequence(swipe.paramTypes[swipe.paramCount - 3]) { it.superClass }.map { it.name }.toSet()
            val wrapper = find(MethodMatcher.create().paramCount(5).returnType("java.lang.Object")).filter {
                val p = it.paramTypeNames
                p[0] in pagerTypes && p[1] == "int" && p[2] == tweenType && p[4] == "int"
            }.one("pager animate default wrapper")
            save("swipeAnimate", wrapper.invokes.filter {
                it.className in pagerTypes && it.paramCount == 3 && it.paramTypeNames[0] == "int" &&
                    it.returnTypeName == "java.lang.Object" && !Modifier.isStatic(it.modifiers)
            }.one("pager animate"))
            val fling = save("swipeFlingFactory", find(MethodMatcher.create().paramCount(5)).filter {
                it.paramTypeNames[0] in pagerTypes && it.paramTypeNames.drop(1) == listOf(tweenType, composer, "int", "int")
            }.one("reader fling factory"))
            save("swipeFling", requireNotNull(fling.returnType).methods.filter {
                it.paramCount == 3 && it.paramTypeNames[1] == "float" && it.returnTypeName == "java.lang.Object"
            }.one("fling execution"))
            val layout = save("swipeLayout", fling.callers.filter {
                val fields = it.declaredClass?.fields.orEmpty().map { f -> f.typeName }
                stateType in fields && deps in fields && fields.any { f -> f in pagerTypes } && it.paramCount == 3
            }.one("reader swipe layout"))
            check(layout.returnTypeName == "java.lang.Object")
        }
        result.putAll(HostReaderExtraMotion.query(bridge))
        for (role in listOf("unit", "modifier")) {
            val owner = requireNotNull(bridge.getMethodData(result.getValue(role))?.declaredClass)
            result["field:$role"] = owner.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == owner.name }.descriptor
        }
        if ("swipeFling" in result) {
            val animate = requireNotNull(bridge.getMethodData(result.getValue("swipeAnimate")))
            val owner = requireNotNull(bridge.getMethodData(result.getValue("swipeFling"))?.declaredClass)
            result["field:swipeFlingSpec"] = owner.fields.single { !Modifier.isStatic(it.modifiers) &&
                it.typeName == animate.paramTypeNames[1] }.descriptor
        }
        return result
    }
    private fun restoreFields(values: Map<String, String>, loader: ClassLoader): Map<String, Field> {
        val resolver = HostMembers()
        return values.filterKeys { it.startsWith("field:") }.mapKeys { it.key.removePrefix("field:") }
            .mapValues { resolver.describedField(it.value, loader) }
    }

    private fun restoreConstructors(values: Map<String, String>, loader: ClassLoader): Map<String, Constructor<*>> {
        val resolver = HostMembers()
        return values.filterKeys { it.startsWith("constructor:") }.mapKeys { it.key.removePrefix("constructor:") }
            .mapValues { resolver.describedConstructor(it.value, loader) }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Map<String, Method> {
        val resolver = HostMembers()
        return values.filterKeys { !it.startsWith("field:") && !it.startsWith("constructor:") }.mapValues { (_, descriptor) ->
            resolver.describedMethod(descriptor, loader)
        }.also { methods ->
            check(methods.keys.containsAll(required)) { "Reader feature cache is incomplete" }
            validateShapes(methods)
            val fields = restoreFields(values, loader)
            val constructors = restoreConstructors(values, loader)
            for (role in listOf("voiceBody", "voiceLabel", "voiceSemantics")) {
                check(constructors.getValue(role).declaringClass == methods.getValue(role).declaringClass) {
                    "Listening ruler callback constructor mismatch: $role"
                }
            }
            check(constructors.getValue("voiceBody").parameterTypes.let { p -> p.size == 9 &&
                p.take(4) == List(4) { Float::class.javaPrimitiveType } && p[4] == String::class.java &&
                p[5] == Float::class.javaPrimitiveType && p[6] == methods.getValue("voice").parameterTypes[2] &&
                p[8] == Int::class.javaPrimitiveType })
            check(constructors.getValue("voiceLabel").parameterTypes.let { p -> p.size == 3 &&
                p[0] == Int::class.javaPrimitiveType && p[1] == Any::class.java &&
                p[2] == Byte::class.javaPrimitiveType })
            check(constructors.getValue("voiceSemantics").parameterTypes.toList() ==
                listOf(String::class.java, Byte::class.javaPrimitiveType))
            fun field(role: String, owner: Class<*>, type: Class<*>, static: Boolean = false) {
                val f = fields.getValue(role)
                check(f.declaringClass == owner && f.type == type && Modifier.isStatic(f.modifiers) == static)
            }
            val groupLambda = methods.getValue("settingsGroup").parameterTypes[1]
            check(constructors.getValue("settingsGroupContent").let {
                it.declaringClass == groupLambda && it.parameterTypes.toList() ==
                    listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Any::class.java)
            }) { "Native settings group content constructor mismatch" }
            field("settingsGroupCallback", groupLambda, Any::class.java)
            for (role in listOf("unit", "modifier")) {
                val owner = methods.getValue(role).declaringClass
                field(role, owner, owner, true)
            }
            if ("swipeFling" in methods) field("swipeFlingSpec", methods.getValue("swipeFling").declaringClass,
                methods.getValue("swipeAnimate").parameterTypes[1])
            if ("extraCurlDispatch" in methods) {
                val owner = methods.getValue("extraCurlDispatch").declaringClass
                check(constructors.getValue("extraCurlOwner").let {
                    it.declaringClass == owner && it.parameterTypes.toList() == listOf(android.content.Context::class.java)
                }) { "Curl owner constructor mismatch" }
                field("extraCurlChild", owner, methods.getValue("extraCurlDuration").declaringClass)
                for (role in listOf("First", "Finish", "Maintenance"))
                    field("extraCurl${role}Owner", methods.getValue("extraCurl$role").declaringClass, owner)
            }
        }
    }

    /** Verify the contracts used by hooks without invoking host code or relying on obfuscated names. */
    private fun validateShapes(methods: Map<String, Method>) {
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val boolean = Boolean::class.javaPrimitiveType!!
        val void = Void.TYPE
        val any = Any::class.java
        val string = String::class.java
        fun shape(key: String, count: Int, result: Class<*>? = null, static: Boolean? = null): Method =
            methods.getValue(key).also {
                check(it.parameterCount == count && (result == null || it.returnType == result) &&
                    (static == null || Modifier.isStatic(it.modifiers) == static)) { "Reader method shape mismatch: $key" }
            }
        fun Method.params(vararg types: Class<*>) {
            check(parameterTypes.toList() == types.toList()) { "Reader parameter mismatch: $this" }
        }
        fun Method.at(vararg types: Pair<Int, Class<*>>) {
            check(types.all { (index, type) -> parameterTypes[index] == type }) { "Reader parameter mismatch: $this" }
        }
        fun optional(vararg keys: String, validate: () -> Unit) {
            if (keys.none { it in methods }) return
            check(keys.all { it in methods }) { "Incomplete reader feature group: ${keys.toList()}" }
            validate()
        }
        fun singleton(type: Class<*>) {
            check(type.declaredFields.count { Modifier.isStatic(it.modifiers) && it.type == type } == 1) {
                "Reader singleton field mismatch: ${type.name}"
            }
        }

        shape("content", 3, any, false)
        val title = shape("title", 4, void, true).also { it.at(0 to string, 3 to int) }
        val modifier = title.parameterTypes[1]
        val composer = title.parameterTypes[2]
        shape("previewModifier", 2, modifier, true).params(int, modifier)
        val button = shape("button", 5, void, true).also { it.at(0 to string, 1 to boolean, 3 to composer, 4 to int) }
        val function0 = button.parameterTypes[2]
        check(function0.isInterface && function0.methods.count { it.parameterCount == 0 && it.returnType == any } == 1)
        shape("settingsTopBar", 11, void, true).at(0 to string, 4 to function0, 5 to long, 8 to composer, 9 to int, 10 to int)
        for ((key, argument) in listOf("endGroup" to boolean, "startGroup" to int)) {
            shape(key, 1, void, false).also { check(it.declaringClass == composer); it.params(argument) }
        }
        val toggle = shape("toggle", 7, void, true)
        val function1 = toggle.parameterTypes[2]
        check(function1.isInterface && function1.name == "kotlin.jvm.functions.Function1")
        toggle.params(string, boolean, function1, string, composer, int, int)
        val settingsGroup = shape("settingsGroup", 5, void, true).also {
            it.at(0 to modifier, 2 to composer, 3 to int, 4 to int)
        }
        for (key in listOf("settingsGroupInvoke", "settingsGroupDispatch")) {
            check(shape(key, 3, any, false).declaringClass == settingsGroup.parameterTypes[1])
        }
        methods.getValue("settingsGroupInvoke").params(any, any, any)
        methods.getValue("settingsGroupDispatch").params(any, composer, int)
        val groupFunction = shape("settingsGroupFunction", 3, any, false).also { it.params(any, any, any) }
        check(groupFunction.declaringClass.isInterface &&
            groupFunction.declaringClass.name == "kotlin.jvm.functions.Function3")
        shape("voice", 6, void, true).params(string, float, function1, modifier, composer, int)
        shape("voiceBody", 3, any, false).params(any, any, any)
        shape("voiceLabel", 2, any, false).params(any, any)
        shape("voiceSemantics", 1, any, false).params(any)
        shape("voiceSlider", 15, void, true).at(0 to float, 1 to function1, 2 to modifier, 7 to int, 11 to composer)
        shape("voiceDescription", 2, void, true).at(1 to string)
        shape("udp", 1, float, true).params(int)
        val slider = shape("slider", 17, void, true)
        slider.at(0 to float, 1 to function1, 2 to modifier, 3 to boolean, 5 to int, 7 to float,
            8 to string, 9 to string, 10 to string, 11 to float, 13 to boolean, 14 to composer, 15 to int, 16 to int)
        check(!slider.parameterTypes[4].isPrimitive && !slider.parameterTypes[6].isPrimitive)
        shape("colors", 7, slider.parameterTypes[12], true).also {
            it.at(5 to composer, 6 to int)
            check(it.parameterTypes.take(5).none { type -> type.isPrimitive })
        }
        singleton(shape("unit", 0, string, false).declaringClass)
        val companion = shape("modifier", 0, string, false).declaringClass
        check(modifier.isAssignableFrom(companion))
        singleton(companion)
        val state = shape("state", 1, static = true).also { it.params(any) }.returnType
        check(state.methods.singleOrNull { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == any } != null)
        check(state.methods.singleOrNull { !Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(any) && it.returnType == void } != null)
        val dispatch = shape("dispatch", 1, void, false)
        val send = shape("sendIntent", 1, void, false)
        send.params(dispatch.parameterTypes.single())
        check(Modifier.isFinal(send.modifiers) && send.declaringClass.isAssignableFrom(dispatch.declaringClass))
        val tap = shape("tap", 0, string, false).declaringClass
        tap.getConstructor(long)
        check(send.parameterTypes.single().isAssignableFrom(tap))
        val clear = shape("clear", 0, void, false)
        check(Modifier.isFinal(clear.modifiers) && clear.declaringClass.isAssignableFrom(dispatch.declaringClass))

        optional("overlayAction", "overlayResume", "tween") {
            val action = methods.getValue("overlayAction")
            check(action.parameterCount in 16..18)
            val start = if (action.parameterCount == 18) 13 else 12
            shape("overlayAction", action.parameterCount, void, true).at(start to int, start + 1 to float, start + 2 to float, start + 3 to boolean)
            shape("overlayResume", 1, any, false).params(any)
            shape("tween", 4, static = true).also {
                it.at(0 to int, 1 to int, 3 to int)
                check(!it.returnType.isPrimitive && !it.parameterTypes[2].isPrimitive)
            }
        }
        if (methods["swipeEffects"]?.let { it.parameterCount == 10 } == true) {
            shape("swipeEffects", 10, void, true).at(8 to composer, 9 to int)
        } else optional("swipeEffects", "swipeAnimate", "swipeFlingFactory", "swipeFling", "swipeLayout") {
            val tween = methods.getValue("tween").returnType
            val effects = methods.getValue("swipeEffects")
            check(effects.parameterCount in 9..11)
            shape("swipeEffects", effects.parameterCount, void, true).at(effects.parameterCount - 2 to composer, effects.parameterCount - 1 to int)
            val animate = shape("swipeAnimate", 3, any, false).also { it.at(0 to int) }
            check(animate.parameterTypes[1].isAssignableFrom(tween))
            val factory = shape("swipeFlingFactory", 5, static = true).also { it.at(1 to tween, 2 to composer, 3 to int, 4 to int) }
            check(factory.parameterTypes[0].isAssignableFrom(effects.parameterTypes[effects.parameterCount - 3]))
            val fling = shape("swipeFling", 3, any, false).also { it.at(1 to float, 2 to animate.parameterTypes[2]) }
            check(factory.returnType == fling.declaringClass &&
                fling.declaringClass.declaredFields.count { it.type == animate.parameterTypes[1] } == 1)
            shape("swipeLayout", 3, any, false)
        }
        optional("extraCurlDuration", "extraCurlDispatch", "extraCurlTouch", "extraCurlCleanup", "extraCurlFirst",
            "extraCurlFinish", "extraCurlMaintenance", "extraCurlDetach") {
            val child = shape("extraCurlDuration", 1, void, false).also { it.params(int) }.declaringClass
            val dispatchTouch = shape("extraCurlDispatch", 1, boolean, false)
            check(dispatchTouch.parameterTypes.single().name == "android.view.MotionEvent")
            val owner = dispatchTouch.declaringClass
            check(owner.declaredFields.count { it.type == child } == 1)
            shape("extraCurlTouch", 4, void, false).also { it.params(float, float, int, long); check(it.declaringClass == owner) }
            for (key in listOf("extraCurlCleanup", "extraCurlDetach")) {
                check(shape(key, 0, void, false).declaringClass == owner)
            }
            for (key in listOf("extraCurlFirst", "extraCurlFinish", "extraCurlMaintenance")) {
                val runnable = shape(key, 0, void, false).declaringClass
                check(Runnable::class.java.isAssignableFrom(runnable) && runnable.declaredFields.count { it.type == owner } == 1)
            }
        }
    }
}
