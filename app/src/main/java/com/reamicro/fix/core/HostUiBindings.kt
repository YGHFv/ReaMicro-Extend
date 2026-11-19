package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.ModuleResources
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.lang.reflect.Modifier

/** Already-bound native UI members. Rendering never opens DexKit or consults short names. */
internal object HostUiBindings {
    const val KEY = "ui.native"
    @Volatile var current: Symbols? = null
        private set
    @Volatile var initializationAttempted = false
        private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors(): Map<String, String>? = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        var snapshot: Map<String, String>? = null
        current = runCatching {
            HostQuerySession.use(loader, apk, dataDir) { session ->
                var symbols: Symbols? = null
                val result = session.query(KEY,
                    validate = { symbols = restore(it, loader) },
                    find = { bridge -> HostNativeUiQueries.query(bridge, apk,
                        requireNotNull(HostReaderFeatures.boundDescriptors()), requireNotNull(HostSettingsFeatures.boundDescriptors())) })
                snapshot = result.values.toMap()
                HookRuntime.log("ReaMicro native UI ${if (result.cached) "cached" else "resolved"}: ${result.values.size} members")
                requireNotNull(symbols)
            }
        }.onFailure { HookRuntime.logError("ReaMicro native UI queries unavailable", it) }.getOrNull()
        descriptors = snapshot.takeIf { current != null }
    }

    fun restore(values: Map<String, String>, loader: ClassLoader): Symbols = Symbols(values, loader).apply { validate() }

    class Symbols internal constructor(descriptors: Map<String, String>, val loader: ClassLoader) {
        private val values = descriptors.toMap()
        private val resolver = HostMembers()
        private val methods: Map<String, Method> = values.filterKeys { it.startsWith("method:") }.mapKeys { it.key.removePrefix("method:") }
            .mapValues { resolver.describedMethod(it.value, loader) }
        private val fields: Map<String, Field> = values.filterKeys { it.startsWith("field:") }.mapKeys { it.key.removePrefix("field:") }
            .mapValues { resolver.describedField(it.value, loader) }
        private val constructors = values.filterKeys { it.startsWith("constructor:") }.mapKeys { it.key.removePrefix("constructor:") }
            .mapValues { resolver.describedConstructor(it.value, loader) }
        private val types = values.filterKeys { it.startsWith("class:") }.mapKeys { it.key.removePrefix("class:") }
            .mapValues { resolver.describedClass(it.value, loader) }
        fun method(key: String): Method = methods.getValue(key)
        fun constructor(key: String) = constructors.getValue(key)
        fun field(key: String): Field = fields.getValue(key)
        fun type(key: String): Class<*> = types.getValue(key)
        fun number(key: String): Int = values.getValue("value:$key").toInt()
        fun decimal(key: String): Double = values.getValue("value:$key").toDouble()
        fun value(key: String): Any = requireNotNull(field(key).get(null))
        fun call(key: String, vararg args: Any?): Any? = method(key).invoke(null, *args)
        fun instance(key: String, receiver: Any, vararg args: Any?): Any? = method(key).invoke(receiver, *args)
        fun stateValue(state: Any): Any? = instance("stateGet", state)
        fun setState(state: Any, value: Any?) { instance("stateSet", state, value) }
        fun navigationEntryId(entry: Any): String? =
            if (type("navigationEntry").isInstance(entry)) field("navigationEntryId").get(entry) as? String else null
        fun navigationEntries(controller: Any): List<Any> {
            val state = requireNotNull(field("navigationState").get(controller))
            val entries = field("navigationQueue").get(state) as? Collection<*>
                ?: error("Native navigation queue is not a collection")
            return entries.filterNotNull().filter(type("navigationEntry")::isInstance)
        }
        val composerClass: Class<*> get() = method("title").parameterTypes[2]
        val modifierClass: Class<*> get() = method("title").parameterTypes[1]
        val rowWeight = HostRowWeight(method("weight"), modifierClass)
        private val unit: Any get() = HostCoroutines.unit(loader)
        fun function(type: Class<*>, action: (Array<out Any?>?) -> Any?): Any =
            function(type, { unit }, action)
        // Native Function.invoke erases its result to Object; value callbacks need their own idle result.
        fun function(type: Class<*>, fallback: (Array<out Any?>?) -> Any?, action: (Array<out Any?>?) -> Any?): Any {
            val invocation = (0..3).singleOrNull { type == this.type("function$it") }?.let { method("invoke$it") }
                ?: error("Unsupported native callback interface: $type")
            return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
                when {
                    method == invocation -> {
                        val lease = ModuleResources.lease()
                        if (lease == null) fallback(args) else lease.use { action(args) }
                    }
                    method.name == "equals" -> proxy === args?.firstOrNull()
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "toString" -> "ReaMicroNativeUi"
                    else -> error("Unexpected native callback method: $method")
                }
            }
        }
        fun callback(type: Class<*>, action: (Array<out Any?>?) -> Unit): Any = function(type) { args -> action(args); unit }
        fun lambda(key: Int, callback: Any): Any = constructor("lambda").newInstance(key, true, callback)
        fun alignment(vertical: Boolean): Any = constructor(if (vertical) "verticalAlignment" else "horizontalAlignment").newInstance(0f)
        fun spacing(value: Float): Any = constructor("spacing").newInstance(value, true, null)
        fun emptySlot(): Any = lambda(0x524D454D, callback(type("function2")) {})
        /** Published only at installation; rescans keep the active generation's members. */
        fun logicalDescriptors(): Map<String, String> = buildMap {
            val theme = "app.zhendong.reamicro.arch.theme.ThemeKt"
            val propertyOwners = mapOf("color" to "androidx.compose.material3.ColorScheme", "typography" to "androidx.compose.material3.Typography")
            for ((prefix, owner) in propertyOwners) {
                put("class:$owner", "L${method(if (prefix == "color") "colorScheme" else "typography").returnType.name.replace('.', '/')};")
                this@Symbols.values.filterKeys { it.startsWith("field:$prefix.") }.forEach { (key, descriptor) ->
                    put("field:$owner#${key.substringAfter('.')}", descriptor)
                }
            }
            put("class:$theme", "L${method("backgroundAuto").declaringClass.name.replace('.', '/')};")
            for (name in listOf("backgroundAuto", "backgroundBright", "backgroundDim", "borderVariant", "onBackgroundVariant")) {
                put("method:$theme#get${name.replaceFirstChar(Char::uppercaseChar)}", this@Symbols.values.getValue("method:$name"))
            }
        }
        fun node(row: Boolean, modifier: Any, arrangement: Any?, alignment: Any?, content: Any, composer: Any) {
            instance("startGroup", composer, if (row) 0x524D7032 else 0x524D7031)
            try {
                val policy = requireNotNull(call(if (row) "rowPolicy" else "columnPolicy", arrangement ?: value(if (row) "rowStart" else "top"),
                    alignment ?: if (row) alignment(true) else value("start"), composer, 0))
                val hash = java.lang.Long.hashCode(field("hash").getLong(composer))
                val locals = instance("locals", composer)
                val materialized = call("materialize", composer, modifier)
                instance("startNode", composer)
                try {
                    if (field("inserting").getBoolean(composer)) instance("createNode", composer, value("nodeFactory"))
                    else instance("useNode", composer)
                    call("update", value("setPolicy"), composer, policy)
                    call("update", value("setLocals"), composer, locals)
                    call("update", value("setHash"), composer, hash)
                    call("updateComplete", composer)
                    call("update", value("setModifier"), composer, materialized)
                    method("invoke3").invoke(content, value(if (row) "rowScope" else "columnScope"), composer, 0)
                } finally {
                    instance("endGroup", composer, true)
                }
            } finally {
                instance("endGroup", composer, false)
            }
        }
        internal fun validate() {
            fun keys(prefix: String, names: String): Set<String> = names.split(' ').map { "$prefix:$it" }.toSet()
            val required = keys("method", "title toggle startGroup endGroup state stateGet stateSet dpInt topBar dpDouble text backgroundAuto backgroundBright backgroundDim borderVariant onBackgroundVariant colorScheme typography colorToArgb colorFromArgb listItem listColors settingsSwitchBody switch switchColors columnPolicy rowPolicy box weight fillWidth height width fillSize padding clickable combinedClickable background border clip rounded materialize createNode useNode startNode update locals updateComplete lazyColumn lazyRow item paddingValues paddingTop paddingBottom scaffold back divider aboutInvoke aboutWrapper destinationInvoke remember updateRemember") +
                keys("class", "function0 function1 function2 lambda function3 settingsSwitch horizontalAlignment verticalAlignment spacing aboutRoute") +
                keys("method", "invoke0 invoke1 invoke2 invoke3") +
                keys("method", "aboutBody") + keys("field", "defaultScheme0 defaultScheme1") +
                keys("method", "settingsEntry navigationEntryLookup") +
                keys("class", "navigationEntry") +
                keys("field", "settingsShape settingsArrow navigationEntryId navigationState navigationQueue") +
                keys("value", "settingsHeight settingsBorderWidth settingsElevation settingsClickMask settingsListFlags settingsListMask") +
                keys("constructor", "settingsSwitch lambda horizontalAlignment verticalAlignment spacing") +
                keys("field", "modifier listColorBase top start rowStart center rowScope columnScope rectangle inserting hash nodeFactory setPolicy setModifier setHash setLocals aboutRoute aboutContent destinationCallback emptyRemembered") +
                keys("field", "color.surface color.surfaceContainerLowest color.surfaceContainerLow color.surfaceContainer color.surfaceContainerHighest color.onBackground color.onSurfaceVariant typography.bodyMedium typography.labelLarge")
            require(values.keys.containsAll(required)) { "Incomplete native UI contract: missing=${required - values.keys}" }
            require(values.keys.all { it in required || it.startsWith("field:color.") || it.startsWith("field:typography.") }) {
                "Unexpected native UI contract members: ${values.keys - required}"
            }
            val any = Any::class.java
            val int = Integer.TYPE
            val long = java.lang.Long.TYPE
            val float = java.lang.Float.TYPE
            val boolean = java.lang.Boolean.TYPE
            val void = java.lang.Void.TYPE
            val string = String::class.java
            val composer = composerClass
            val modifier = modifierClass
            val function2 = type("function2")
            val function3 = type("function3")
            val function1 = type("function1")
            fun shape(key: String, count: Int, result: Class<*>? = null, static: Boolean = true): Method = method(key).also {
                require(it.parameterCount == count && Modifier.isStatic(it.modifiers) == static &&
                    (result == null || it.returnType == result)) { "Invalid native UI method: $key" }
            }
            fun Method.params(vararg expected: Class<*>) {
                require(parameterTypes.toList() == expected.toList()) { "Invalid native UI parameters: $this" }
            }
            fun Method.at(vararg expected: Pair<Int, Class<*>>) {
                require(expected.all { (index, type) -> parameterTypes[index] == type }) { "Invalid native UI parameters: $this" }
            }
            fun Method.slots(function: Class<*>, vararg indices: Int) {
                require(function.isAssignableFrom(type("lambda")) && indices.all { index ->
                    parameterTypes[index] == function || parameterTypes[index] == type("lambda")
                }) { "Invalid native UI content slots: $this" }
            }
            fun instance(key: String, owner: Class<*>, result: Class<*>?, vararg params: Class<*>) {
                shape(key, params.size, result, false).also {
                    require(it.declaringClass == owner) { "Invalid native UI owner: $key" }
                    it.params(*params)
                }
            }
            fun fieldType(key: String, expected: Class<*>, static: Boolean = true, owner: Class<*>? = null) {
                field(key).also {
                    require(it.type == expected && Modifier.isStatic(it.modifiers) == static &&
                        (owner == null || it.declaringClass == owner)) { "Invalid native UI field: $key" }
                }
            }
            for (count in 0..3) {
                require(type("function$count").isInterface)
                instance("invoke$count", type("function$count"), any, *List(count) { any }.toTypedArray())
            }
            shape("title", 4, void).params(string, modifier, composer, int)
            shape("toggle", 7, void).params(string, boolean, function1, string, composer, int, int)
            shape("dpInt", 1, float).params(int)
            shape("dpDouble", 1, float).params(java.lang.Double.TYPE)
            val state = shape("state", 1).also { it.params(any) }.returnType
            for (index in 0..1) fieldType("defaultScheme$index", method("colorScheme").returnType)
            require(field("defaultScheme0") != field("defaultScheme1"))
            for (role in listOf("stateGet", "stateSet")) require(method(role).declaringClass.let {
                it.isInterface && it.isAssignableFrom(state)
            })
            shape("stateGet", 0, any, false)
            shape("stateSet", 1, void, false).params(any)
            shape("colorToArgb", 1, int).params(long)
            shape("colorFromArgb", 1, long).params(int)
            val text = shape("text", 20, void)
            text.at(0 to string, 1 to modifier, 2 to long, 3 to long, 6 to long, 9 to long,
                10 to int, 11 to boolean, 12 to int, 13 to int, 14 to function1,
                16 to composer, 17 to int, 18 to int, 19 to int)
            val color = shape("colorScheme", 1).also { it.params(composer) }.returnType
            val typography = shape("typography", 1).also { it.params(composer) }.returnType
            for (key in listOf("backgroundAuto", "backgroundBright", "backgroundDim", "borderVariant", "onBackgroundVariant")) {
                shape(key, 1, long).params(color)
            }
            fields.filterKeys { it.startsWith("color.") }.forEach { (key, _) -> fieldType(key, long, false, color) }
            fields.filterKeys { it.startsWith("typography.") }.forEach { (key, _) -> fieldType(key, text.parameterTypes[15], false, typography) }
            val list = shape("listItem", 10, void)
            list.at(1 to modifier, 6 to float, 7 to composer, 8 to int, 9 to int)
            list.slots(function2, 0, 2, 3, 4)
            shape("listColors", 2, list.parameterTypes[5]).params(long, composer)
            fieldType("listColorBase", long)
            shape("settingsEntry", 3, void).params(type("function0"), composer, int)
            require(Modifier.isStatic(field("settingsArrow").modifiers) &&
                function2.isAssignableFrom(field("settingsArrow").type))
            require(number("settingsHeight") in 1..200 && decimal("settingsBorderWidth") in 0.0..8.0 &&
                number("settingsElevation") in 0..32)
            require(number("settingsClickMask") in 0..31 && number("settingsListMask") in 0..1023)
            require(number("settingsListFlags") >= 0)
            fieldType("navigationEntryId", string, false, type("navigationEntry"))
            require(Modifier.isFinal(field("navigationEntryId").modifiers))
            require(!Modifier.isStatic(field("navigationState").modifiers) &&
                !Modifier.isStatic(field("navigationQueue").modifiers) &&
                field("navigationState").type == field("navigationQueue").declaringClass &&
                Collection::class.java.isAssignableFrom(field("navigationQueue").type))
            instance("navigationEntryLookup", field("navigationState").type, type("navigationEntry"), int)
            val switch = shape("switch", 8, void)
            switch.params(boolean, function1, modifier, boolean, switch.parameterTypes[4], composer, int, int)
            shape("switchColors", 4, switch.parameterTypes[4]).params(long, long, long, composer)
            require(constructor("settingsSwitch").let { it.declaringClass == type("settingsSwitch") && it.parameterTypes.toList() == listOf(function1, boolean) })
            instance("settingsSwitchBody", type("settingsSwitch"), any, any, any)
            for ((key, vertical) in listOf("columnPolicy" to false, "rowPolicy" to true)) {
                val policy = shape(key, 4)
                policy.params(policy.parameterTypes[0], type(if (vertical) "verticalAlignment" else "horizontalAlignment"), composer, int)
                require(!policy.returnType.isPrimitive)
            }
            shape("box", 3, void).params(modifier, composer, int)
            require(method("weight").declaringClass == field("rowScope").type) { "Invalid row weight receiver" }
            for (key in listOf("fillWidth", "height", "width")) shape(key, 2, modifier).params(modifier, float)
            shape("fillSize", 1, modifier).params(modifier)
            shape("padding", 5, modifier).params(modifier, float, float, float, float)
            val clickable = shape("clickable", 6, modifier)
            val function0 = clickable.parameterTypes[4]
            require(function0.isInterface && function0 == type("function0"))
            clickable.params(modifier, boolean, string, clickable.parameterTypes[3], function0, int)
            shape("combinedClickable", 6, modifier).at(0 to modifier, 3 to function0, 4 to function0, 5 to int)
            val shapeType = shape("background", 3, modifier).also { it.at(0 to modifier, 1 to long) }.parameterTypes[2]
            require(Modifier.isStatic(field("settingsShape").modifiers) && shapeType.isAssignableFrom(field("settingsShape").type))
            shape("border", 4, modifier).params(modifier, float, long, shapeType)
            shape("clip", 2, modifier).params(modifier, shapeType)
            require(shapeType.isAssignableFrom(shape("rounded", 1).also { it.params(float) }.returnType))
            shape("materialize", 2, modifier).params(composer, modifier)
            instance("startGroup", composer, void, int)
            instance("endGroup", composer, void, boolean)
            instance("createNode", composer, void, function0)
            for (key in listOf("startNode", "useNode")) instance(key, composer, void)
            instance("locals", composer, null)
            instance("remember", composer, any)
            instance("updateRemember", composer, void, any)
            shape("update", 3, void).params(function2, composer, any)
            shape("updateComplete", 1, void).params(composer)
            fieldType("hash", long, false, composer)
            fieldType("inserting", boolean, false, composer)
            for ((key, policy) in listOf("lazyColumn" to "columnPolicy", "lazyRow" to "rowPolicy")) {
                shape(key, 12, void).at(0 to modifier, 3 to method(policy).parameterTypes[0],
                    6 to boolean, 8 to function1, 9 to composer, 10 to int, 11 to int)
            }
            shape("item", 3, void, false).params(any, any, function3)
            val padding = shape("paddingValues", 2, modifier).also { it.at(0 to modifier) }.parameterTypes[1]
            for (role in listOf("paddingTop", "paddingBottom")) instance(role, padding, float)
            require(padding.isAssignableFrom(method("lazyColumn").parameterTypes[2]) &&
                padding.isAssignableFrom(method("lazyRow").parameterTypes[2]))
            shape("scaffold", 13, void).also {
                it.at(0 to modifier, 5 to int, 6 to long, 7 to long, 9 to type("lambda"), 10 to composer, 11 to int, 12 to int)
                it.slots(function2, 1, 2, 3, 4)
            }
            shape("topBar", 11, void).also {
                it.at(0 to string, 4 to function0, 5 to long, 8 to composer, 9 to int, 10 to int)
                it.slots(function2, 1)
                it.slots(function3, 6, 7)
            }
            shape("back", 5, void).params(boolean, function0, composer, int, int)
            shape("divider", 5, void).params(modifier, long, composer, int, int)
            instance("aboutInvoke", type("lambda"), any, any, any, any, any)
            instance("aboutWrapper", type("lambda"), any, any, any, composer, int)
            shape("destinationInvoke", 4, any, false).params(any, any, any, any)
            require(method("destinationInvoke").declaringClass.isInterface)
            fieldType("destinationCallback", any, false, type("lambda"))
            fieldType("aboutContent", type("lambda"))
            fieldType("aboutRoute", type("aboutRoute"))
            require(type("aboutRoute").isInstance(value("aboutRoute")))
            val about = requireNotNull(field("destinationCallback").get(value("aboutContent")))
            require(method("destinationInvoke").declaringClass.isInstance(about))
            shape("aboutBody", 4, any, false).params(any, any, any, any)
            require(method("aboutBody").declaringClass.isInstance(about) && method("aboutBody").name == method("destinationInvoke").name)
            require(modifierClass.isInstance(value("modifier")))
            require(method("columnPolicy").parameterTypes[0].isInstance(spacing(2f)))
            require(method("rowPolicy").parameterTypes[1].isInstance(alignment(true)))
            require(method("columnPolicy").parameterTypes[1].isInstance(alignment(false)))
            require(method("columnPolicy").parameterTypes[0].isInstance(value("top")))
            require(method("columnPolicy").parameterTypes[1].isInstance(value("start")))
            require(method("rowPolicy").parameterTypes[0].isInstance(value("rowStart")))
            require(shapeType.isInstance(value("rectangle")))
            value("columnScope"); value("rowScope"); value("center"); value("emptyRemembered")
            require(method("createNode").parameterTypes[0].isInstance(value("nodeFactory")))
            for (key in listOf("setPolicy", "setLocals", "setHash", "setModifier")) require(type("function2").isInstance(value(key)))
            require(constructor("lambda").let { it.declaringClass == type("lambda") && it.parameterTypes.toList() == listOf(int, boolean, any) })
            for (role in listOf("horizontalAlignment", "verticalAlignment")) require(constructor(role).let {
                it.declaringClass == type(role) && it.parameterTypes.toList() == listOf(float)
            })
            require(constructor("spacing").let { it.declaringClass == type("spacing") && it.parameterCount == 3 &&
                it.parameterTypes.take(2) == listOf(float, boolean) && !it.parameterTypes[2].isPrimitive })
        }
    }
}
