package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Native UI contracts come from the host's own reader/settings call graph. */
internal object HostNativeUiQueries {
    fun query(bridge: DexKitBridge, apk: String, reader: Map<String, String>, settings: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun find(m: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(m))
        fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.let {
            it.singleOrNull() ?: error("Native UI $label: expected one, found ${it.size}: ${it.map { m -> m.descriptor }}")
        }
        fun save(key: String, method: MethodData): MethodData = method.also { out["method:$key"] = it.descriptor }
        fun field(key: String, value: HostDexCalls.Value) {
            out["field:$key"] = (value as? HostDexCalls.Field)?.descriptor ?: error("Native UI field $key is not proven: $value")
        }
        fun anchor(vararg strings: String) = find(MethodMatcher.create().usingEqStrings(*strings)).one(strings.first())
        fun type(key: String, name: String) { out["class:$key"] = "L${name.replace('.', '/')};" }
        fun rd(key: String) = requireNotNull(bridge.getMethodData(reader.getValue(key)))
        val title = save("title", rd("title"))
        val toggle = save("toggle", rd("toggle"))
        val button = rd("button")
        val composer = title.paramTypeNames[2]
        val modifier = title.paramTypeNames[1]
        val function0 = button.paramTypeNames[2]
        save("startGroup", rd("startGroup")); save("endGroup", rd("endGroup"))
        val state = save("state", rd("state"))
        save("dpInt", rd("udp"))
        val stateTypes = linkedMapOf<String, org.luckypray.dexkit.result.ClassData>()
        fun stateHierarchy(owner: org.luckypray.dexkit.result.ClassData) {
            if (stateTypes.putIfAbsent(owner.descriptor, owner) != null) return
            owner.interfaces.forEach(::stateHierarchy)
            owner.superClass?.let(::stateHierarchy)
        }
        stateHierarchy(requireNotNull(state.returnType))
        val stateInterfaces = stateTypes.values.filter { Modifier.isInterface(it.modifiers) }
        save("stateGet", stateInterfaces.flatMap { it.methods }.filter {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.Object"
        }.one("state value interface"))
        save("stateSet", stateInterfaces.flatMap { it.methods }.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void"
        }.one("mutable state interface"))
        save("topBar", rd("settingsTopBar"))
        save("dpDouble", rd("udp").declaredClass!!.methods.filter {
            it.paramTypeNames == listOf("double") && it.returnTypeName == "float" && Modifier.isStatic(it.modifiers)
        }.one("dimension scale double"))
        val modifierObject = rd("modifier").declaredClass!!
        out["field:modifier"] = modifierObject.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == modifierObject.name }.descriptor
        save("text", title.invokes.filter {
            it.paramCount == 20 && it.paramTypeNames.take(2) == listOf("java.lang.String", modifier) && it.paramTypeNames[16] == composer
        }.one("text"))
        val colorScheme = anchor("ColorScheme(primary=").className
        val typography = anchor("Typography(displayLarge=").className
        val colorFormat = anchor("Color(", ", ", ")")
        val paddingRecord = requireNotNull(anchor("PaddingValues(start=").declaredClass)
        val dpFormatter = anchor("Dp.Unspecified", ".dp")
        check(Modifier.isStatic(dpFormatter.modifiers) && dpFormatter.paramTypeNames == listOf("float") &&
            dpFormatter.returnTypeName == "java.lang.String")
        val propertyTypes = mapOf(
            "class:androidx.compose.material3.ColorScheme" to "L${colorScheme.replace('.', '/')};",
            "class:androidx.compose.material3.Typography" to "L${typography.replace('.', '/')};",
            "class:androidx.compose.ui.graphics.Color" to requireNotNull(colorFormat.declaredClass).descriptor,
            "class:paddingRecord" to paddingRecord.descriptor,
        )
        val properties = HostRecordQueries.query(apk, propertyTypes, setOf(dpFormatter.descriptor))
        save("colorToArgb", find(MethodMatcher.create().paramTypes("long").returnType("void")).filter { m ->
            m.invokes.any { it.descriptor == "Landroid/graphics/Paint;->setColor(I)V" }
        }.flatMap { m -> m.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("long") && it.returnTypeName == "int" } }.one("Android paint color conversion"))
        save("colorFromArgb", find(MethodMatcher.create().paramTypes("int").returnType("long")).filter { m ->
            Modifier.isStatic(m.modifiers) && m.usingFields.any { it.field.declaredClassName == colorFormat.className } &&
                m.usingNumbers.any { it.longValue() == 32L } && m.invokes.isEmpty()
        }.one("ARGB color encoding"))
        for ((prefix, owner) in listOf("color" to "androidx.compose.material3.ColorScheme", "typography" to "androidx.compose.material3.Typography")) {
            properties.filterKeys { it.startsWith("field:$owner#") }.forEach { (key, value) ->
                out["field:$prefix.${key.substringAfter('#')}"] = value
            }
        }
        val themeFunctions = find(MethodMatcher.create().paramTypes(colorScheme).returnType("long"))
            .filter { Modifier.isStatic(it.modifiers) }
        fun theme(key: String, vararg propertyNames: String) {
            val fields = propertyNames.map { out.getValue("field:color.$it") }.toSet()
            save(key, themeFunctions.filter { method ->
                method.usingFields.map { it.field }.filter { it.declaredClassName == colorScheme }
                    .map { it.descriptor }.toSet() == fields
            }.one(key))
        }
        theme("backgroundAuto", "surface", "surfaceContainerLowest")
        theme("backgroundBright", "surface", "surfaceContainerLow")
        theme("backgroundDim", "surface", "surfaceContainer", "surfaceContainerLowest")
        theme("borderVariant", "surfaceContainerHighest")
        theme("onBackgroundVariant", "onBackground")
        val themeOwner = requireNotNull(bridge.getMethodData(out.getValue("method:backgroundAuto"))?.declaredClass)
        val defaultSchemes = themeOwner.fields.filter { Modifier.isStatic(it.modifiers) && it.typeName == colorScheme }
            .sortedBy { it.descriptor }
        check(defaultSchemes.size == 2) { "Native theme must publish both default palettes" }
        val themeInitializer = themeOwner.methods.filter { it.name == "<clinit>" }.one("theme initializer")
        defaultSchemes.forEachIndexed { index, palette ->
            check(themeInitializer.usingFields.any { it.field.descriptor == palette.descriptor })
            out["field:defaultScheme$index"] = palette.descriptor
        }
        for ((key, returns) in listOf("colorScheme" to colorScheme, "typography" to typography)) {
            save(key, button.invokes.filter { it.paramTypeNames == listOf(composer) && it.returnTypeName == returns }.one(key))
        }
        val listItem = save("listItem", toggle.invokes.filter {
            it.paramCount == 10 && it.paramTypeNames[1] == modifier && it.paramTypeNames.takeLast(3) == listOf(composer, "int", "int")
        }.one("list item"))
        val function2 = listItem.paramTypeNames[0]
        type("function2", function2)
        val lambdaFactory = toggle.invokes.filter { m ->
            m.paramCount == 3 && m.paramTypeNames.first() == "int" && m.paramTypeNames.last() == composer &&
                m.returnType?.methods?.any { it.isConstructor && it.paramTypeNames == listOf("int", "boolean", "java.lang.Object") } == true
        }.one("composable lambda factory")
        type("lambda", lambdaFactory.returnTypeName)
        type("function3", rd("content").declaredClass!!.interfaces.single { i ->
            i.methods.any { it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }
        }.name)
        val listColors = save("listColors", toggle.invokes.filter {
            it.paramTypeNames == listOf("long", composer) && it.returnTypeName == listItem.paramTypeNames[5]
        }.one("list colors"))
        field("listColorBase", flow.calls(toggle.descriptor).single { it.descriptor == listColors.descriptor }.arguments[0])
        val switchConstructor = toggle.invokes.filter { it.isConstructor && it.paramTypeNames == listOf("kotlin.jvm.functions.Function1", "boolean") }
            .one("settings switch callback")
        out["constructor:settingsSwitch"] = switchConstructor.descriptor
        val switchBody = switchConstructor.declaredClass!!.methods.filter {
                it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
            }.one("settings switch body")
        type("settingsSwitch", switchBody.className)
        save("settingsSwitchBody", switchBody)
        val switch = save("switch", switchBody.invokes.filter {
            it.paramCount == 8 && it.paramTypeNames.take(4) == listOf("boolean", "kotlin.jvm.functions.Function1", modifier, "boolean") &&
                it.paramTypeNames.takeLast(3) == listOf(composer, "int", "int")
        }.one("switch"))
        save("switchColors", switchBody.invokes.filter {
            it.paramTypeNames == listOf("long", "long", "long", composer) && it.returnTypeName == switch.paramTypeNames[4]
        }.one("switch colors"))
        val columnType = anchor("ColumnMeasurePolicy(verticalArrangement=").className
        val rowType = anchor("RowMeasurePolicy(horizontalArrangement=").className
        val column = save("columnPolicy", find(MethodMatcher.create().returnType(columnType).paramCount(4)).filter {
            it.paramTypeNames.takeLast(2) == listOf(composer, "int") && Modifier.isStatic(it.modifiers)
        }.one("column policy"))
        val row = save("rowPolicy", find(MethodMatcher.create().returnType(rowType).paramCount(4)).filter {
            it.paramTypeNames.takeLast(2) == listOf(composer, "int") && Modifier.isStatic(it.modifiers)
        }.one("row policy"))
        fun defaultField(key: String, method: MethodData, param: Int) {
            val wanted = requireNotNull(bridge.getClassData(method.paramTypeNames[param]))
            out["field:$key"] = method.usingFields.map { it.field }.distinctBy { it.descriptor }.single { f ->
                Modifier.isStatic(f.modifiers) && (f.typeName == wanted.name || bridge.getClassData(f.typeName)?.interfaces?.any { it.name == wanted.name } == true)
            }.descriptor
        }
        defaultField("top", column, 0); defaultField("start", column, 1)
        defaultField("rowStart", row, 0)
        type("horizontalAlignment", column.paramTypeNames[1]); type("verticalAlignment", row.paramTypeNames[1])
        val boxPolicy = button.invokes.filter { it.paramCount == 2 && it.paramTypeNames[1] == "boolean" &&
            it.returnTypeName == requireNotNull(bridge.getClassData(rowType)).interfaces.first().name
        }.one("box policy")
        save("box", boxPolicy.declaredClass!!.methods.filter {
            it.paramTypeNames == listOf(modifier, composer, "int") && it.returnTypeName == "void" && Modifier.isStatic(it.modifiers)
        }.one("empty box"))
        val buttonCalls = flow.calls(button.descriptor)
        val boxCall = buttonCalls.filter { it.descriptor == boxPolicy.descriptor }.distinct().single()
        field("center", boxCall.arguments[0])
        val spacing = anchor("Arrangement#spacedAligned(").declaredClass!!
        type("spacing", spacing.name)
        val weights = find(MethodMatcher.create().usingEqStrings("invalid weight; must be greater than zero")
            .returnType(modifier)).filter { !Modifier.isStatic(it.modifiers) }
        val explicitFill = listOf("float", modifier, "boolean")
        val columns = weights.filter { it.paramTypeNames == explicitFill }
        val columnWeight = columns.singleOrNull() ?: columns.filter { candidate ->
            candidate.declaredClass!!.methods.any { !Modifier.isStatic(it.modifiers) && it.returnTypeName == modifier &&
                it.paramTypeNames == listOf(modifier, column.paramTypeNames[1]) }
        }.one("column weight with horizontal alignment")
        val rowCallers = row.callers.distinctBy { it.descriptor }
        val implicitFill = listOf(modifier, "float")
        val candidates = weights.filter { candidate ->
            val legacy = candidate.paramTypeNames == implicitFill
            (legacy || (candidate.paramTypeNames == explicitFill && candidate.className != columnWeight.className)) &&
                rowCallers.any { caller -> caller.usingFields.any { access ->
                    val field = access.field
                    field.typeName == candidate.className &&
                        (legacy || (Modifier.isStatic(field.modifiers) && field.declaredClassName == candidate.className))
                } }
        }
        val weight = save("weight", candidates.one("row weight"))
        out["field:rowScope"] = weight.declaredClass!!.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == weight.className }.descriptor
        out["field:columnScope"] = columnWeight.declaredClass!!.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == columnWeight.className }.descriptor
        val fillWidth = save("fillWidth", title.invokes.filter {
            it.paramTypeNames == listOf(modifier, "float") && it.returnTypeName == modifier
        }.one("fill width"))
        val height = save("height", toggle.invokes.filter {
            it.paramTypeNames == listOf(modifier, "float") && it.returnTypeName == modifier
        }.one("height"))
        val sizeCtor = height.invokes.filter { it.isConstructor && it.paramTypeNames == listOf("float", "float", "float", "float", "int", "boolean") }.one("size element")
        save("width", height.declaredClass!!.methods.filter { m ->
            m.paramTypeNames == listOf(modifier, "float") && m.returnTypeName == modifier &&
                flow.calls(m.descriptor).any { call -> call.descriptor == sizeCtor.descriptor &&
                    call.arguments.drop(1) == listOf(HostDexCalls.Parameter(1), HostDexCalls.Literal(0), HostDexCalls.Parameter(1),
                        HostDexCalls.Literal(0), HostDexCalls.Literal(10), HostDexCalls.Literal(1)) }
        }.one("constrained width"))
        save("fillSize", fillWidth.declaredClass!!.methods.filter { m ->
            m.paramTypeNames == listOf(modifier) && m.returnTypeName == modifier && m.usingFields.any { it.field.typeName == "androidx.compose.foundation.layout.FillElement" }
        }.one("fill size"))
        val paddingDefault = title.invokes.filter {
            it.paramTypeNames == listOf(modifier, "float", "float", "float", "float", "int") && it.returnTypeName == modifier
        }.one("padding defaults")
        save("padding", paddingDefault.invokes.filter {
            it.paramTypeNames == listOf(modifier, "float", "float", "float", "float") && it.returnTypeName == modifier
        }.one("padding"))
        val clickable = save("clickable", button.invokes.filter {
            it.paramCount == 6 && it.paramTypeNames.take(3) == listOf(modifier, "boolean", "java.lang.String") && it.paramTypeNames[4] == function0
        }.one("clickable"))
        save("combinedClickable", clickable.declaredClass!!.methods.filter {
            it.paramCount == 6 && it.paramTypeNames[0] == modifier && it.paramTypeNames.takeLast(3) == listOf(function0, function0, "int")
        }.one("combined clickable"))
        val background = save("background", button.invokes.filter {
            it.paramTypeNames.take(2) == listOf(modifier, "long") && it.paramCount == 3 && it.returnTypeName == modifier
        }.one("background"))
        val shape = background.paramTypeNames[2]
        val rectangle = anchor("RectangleShape").declaredClass!!
        out["field:rectangle"] = button.usingFields.map { it.field }.filter { Modifier.isStatic(it.modifiers) && it.typeName == rectangle.name }
            .distinctBy { it.descriptor }.single().descriptor
        save("border", button.invokes.filter { it.paramTypeNames == listOf(modifier, "float", "long", shape) && it.returnTypeName == modifier }.one("border"))
        save("clip", button.invokes.filter { it.paramTypeNames == listOf(modifier, shape) && it.returnTypeName == modifier }.one("clip"))
        val rounded = anchor("RoundedCornerShape(topStart = ").className
        save("rounded", button.invokes.filter { it.paramTypeNames == listOf("float") && it.returnTypeName == rounded }.one("rounded shape"))
        val materialize = save("materialize", button.invokes.filter { it.paramTypeNames == listOf(composer, modifier) && it.returnTypeName == modifier }.one("materialize"))
        val create = save("createNode", anchor("createNode() can only be called when inserting"))
        save("useNode", anchor("useNode() called while inserting"))
        val composerType = requireNotNull(bridge.getClassData(composer))
        save("startNode", button.invokes.filter { it.className == composer && it.paramCount == 0 && it.returnTypeName == "void" &&
            it.usingStrings.isEmpty() && it.invokes.size == 1 && it.invokes.single().paramTypeNames.take(2) == listOf("int", "int")
        }.one("start node"))
        out["field:inserting"] = create.usingFields.map { it.field }.filter { it.declaredClassName == composer && it.typeName == "boolean" }
            .distinctBy { it.descriptor }.single { f -> button.usingFields.any { it.field.descriptor == f.descriptor } }.descriptor
        val hashCall = buttonCalls.single { it.descriptor == "Ljava/lang/Long;->hashCode(J)I" }
        field("hash", hashCall.arguments.single())
        val createCall = buttonCalls.single { it.descriptor == create.descriptor }
        field("nodeFactory", createCall.arguments.last())
        val updater = save("update", button.invokes.filter { it.paramTypeNames == listOf(function2, composer, "java.lang.Object") && it.returnTypeName == "void" }.one("node update"))
        val updates = buttonCalls.filter { it.descriptor == updater.descriptor }
        fun setter(key: String, predicate: (HostDexCalls.Value) -> Boolean) {
            val update = updates.singleOrNull { predicate(it.arguments[2]) }
                ?: error("Native UI $key producer is ambiguous: $updates")
            field(key, update.arguments[0])
        }
        setter("setPolicy") { it is HostDexCalls.Call && it.descriptor == boxPolicy.descriptor }
        setter("setModifier") { it is HostDexCalls.Call && it.descriptor == materialize.descriptor }
        setter("setHash") { it is HostDexCalls.Call && it.descriptor == "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" }
        val localsUpdate = updates.single { call -> (call.arguments[2] as? HostDexCalls.Call)?.descriptor?.startsWith(composerType.descriptor + "->") == true }
        field("setLocals", localsUpdate.arguments[0])
        save("locals", requireNotNull(bridge.getMethodData((localsUpdate.arguments[2] as HostDexCalls.Call).descriptor)))
        save("updateComplete", button.invokes.filter { it.className == updater.className && it.paramTypeNames == listOf(composer) && it.returnTypeName == "void" }.one("node update complete"))
        val body = requireNotNull(bridge.getMethodData(settings.getValue("method:body")))
        val root = requireNotNull(bridge.getMethodData(settings.getValue("method:root")))
        check(root.invokes.any { it.descriptor == out.getValue("method:backgroundDim") }) {
            "Native settings background does not use the identified dim theme"
        }
        val lazy = save("lazyColumn", body.invokes.filter { it.paramCount == 12 && it.paramTypeNames[8] == "kotlin.jvm.functions.Function1" && it.paramTypeNames[9] == composer }.one("lazy column"))
        save("lazyRow", lazy.declaredClass!!.methods.filter { it.paramCount == 12 && it.paramTypeNames.take(3) == lazy.paramTypeNames.take(3) &&
            it.paramTypeNames[3] == row.paramTypeNames[0] && it.paramTypeNames[4] == row.paramTypeNames[1] && it.paramTypeNames.takeLast(4) == lazy.paramTypeNames.takeLast(4)
        }.one("lazy row"))
        out["method:item"] = settings.getValue("method:item")
        val paddingValues = save("paddingValues", body.invokes.filter { it.paramCount == 2 && it.paramTypeNames[0] == modifier && it.returnTypeName == modifier }.one("padding values"))
        val paddingInterface = paddingValues.paramTypes[1]
        check(paddingRecord.interfaces.any { it.descriptor == paddingInterface.descriptor })
        for (side in listOf("Top", "Bottom")) {
            val getter = requireNotNull(bridge.getMethodData(properties.getValue("method:paddingRecord#get$side")))
            check(getter.paramCount == 0 && getter.returnTypeName == "float")
            save("padding$side", paddingInterface.methods.filter { it.name == getter.name &&
                !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "float" }.one("padding $side"))
        }
        save("scaffold", root.invokes.filter { it.paramCount == 13 && it.paramTypeNames[0] == modifier && it.paramTypeNames.takeLast(3) == listOf(composer, "int", "int") }.one("scaffold"))
        save("back", find(MethodMatcher.create().paramTypes("boolean", function0, composer, "int", "int").returnType("void"))
            .filter { m -> m.usingStrings.any { "OnBackPressedDispatcherOwner" in it } }.one("back handler"))
        save("divider", find(MethodMatcher.create().paramTypes(modifier, "long", composer, "int", "int").returnType("void"))
            .filter { Modifier.isStatic(it.modifiers) }.one("divider"))
        val serializer = anchor("app.zhendong.reamicro.Route.About")
        val about = flow.calls(serializer.descriptor).filter { it.descriptor.contains("-><init>(Ljava/lang/String;Ljava/lang/Object;") &&
            it.arguments.getOrNull(1) == HostDexCalls.Text("app.zhendong.reamicro.Route.About") }.single()
        field("aboutRoute", about.arguments[2])
        val aboutType = (about.arguments[2] as HostDexCalls.Field).descriptor.substringAfter(':')
        out["class:aboutRoute"] = aboutType
        val (_, slot) = HostNavigationRegistration.find(bridge, flow, aboutType)
        field("aboutContent", slot.arguments.last())
        val lambdaType = requireNotNull(bridge.getClassData(lambdaFactory.returnTypeName))
        val aboutInvoke = save("aboutInvoke", lambdaType.methods.filter { it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("destination invocation"))
        val wrapper = save("aboutWrapper", aboutInvoke.invokes.filter {
            it.className == lambdaType.name && it.paramTypeNames == listOf("java.lang.Object", "java.lang.Object", composer, "int")
        }.one("destination restart wrapper"))
        val invocation = save("destinationInvoke", wrapper.invokes.filter {
            it.declaredClass?.let { c -> Modifier.isInterface(c.modifiers) } == true &&
                it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("destination callback invocation"))
        field("destinationCallback", flow.calls(wrapper.descriptor).single { it.descriptor == invocation.descriptor }.arguments.first())
        val aboutField = requireNotNull(bridge.getFieldData(out.getValue("field:aboutContent")))
        val aboutInitializer = requireNotNull(aboutField.declaredClass).methods.filter { it.name == "<clinit>" }.one("About content initializer")
        val aboutAllocation = flow.writes(aboutInitializer.descriptor).single { it.field == aboutField.descriptor }.value
        check(aboutAllocation is HostDexCalls.Allocation && aboutAllocation.type == lambdaType.descriptor)
        val aboutConstruction = flow.calls(aboutInitializer.descriptor).single { call ->
            call.arguments.firstOrNull() == aboutAllocation && bridge.getMethodData(call.descriptor)?.isConstructor == true
        }
        val callbackAllocation = aboutConstruction.arguments[3] as? HostDexCalls.Allocation
            ?: error("About callback allocation is not proven")
        val callbackType = requireNotNull(bridge.getClassData(callbackAllocation.type))
        check(callbackType.interfaces.any { it.descriptor == invocation.declaredClass?.descriptor })
        save("aboutBody", callbackType.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == invocation.name &&
            it.paramTypeNames == invocation.paramTypeNames && it.returnTypeName == invocation.returnTypeName }.one("About callback body"))
        val remember = save("remember", updater.invokes.filter {
            it.className == composer && it.paramCount == 0 && it.returnTypeName == "java.lang.Object"
        }.one("remembered value"))
        save("updateRemember", updater.invokes.filter {
            it.className == composer && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void"
        }.one("update remembered value"))
        out["field:emptyRemembered"] = remember.usingFields.map { it.field }.filter {
            Modifier.isStatic(it.modifiers) && rd("content").usingFields.any { f -> f.field.descriptor == it.descriptor }
        }.distinctBy { it.descriptor }.single().descriptor
        for (role in listOf("lambda", "horizontalAlignment", "verticalAlignment", "spacing")) {
            val owner = requireNotNull(bridge.getClassData(out.getValue("class:$role")))
            val constructor = owner.methods.filter { it.isConstructor && when (role) {
                "lambda" -> it.paramTypeNames == listOf("int", "boolean", "java.lang.Object")
                "spacing" -> it.paramCount == 3 && it.paramTypeNames.take(2) == listOf("float", "boolean") && it.paramTypes.last().descriptor.startsWith("L")
                else -> it.paramTypeNames == listOf("float")
            } }.one("$role constructor")
            out["constructor:$role"] = constructor.descriptor
        }
        type("function0", function0)
        type("function1", "kotlin.jvm.functions.Function1")
        for (count in 0..3) {
            val owner = requireNotNull(bridge.getClassData(out.getValue("class:function$count")))
            save("invoke$count", owner.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(count) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("function$count invoke"))
        }
        out += HostSettingsVisualQueries.query(bridge, apk, settings, out)
        out += HostNavigationEntryQueries.query(bridge, apk, settings.getValue("class:navigation"))
        return out
    }
}
