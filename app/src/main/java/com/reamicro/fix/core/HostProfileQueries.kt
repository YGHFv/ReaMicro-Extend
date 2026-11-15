package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Profile rendering follows native Community and privilege-card calls, with no release slots. */
internal object HostProfileQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Profile $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun found(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        fun ClassData.isA(parent: ClassData): Boolean = name == parent.name || superClass?.isA(parent) == true || interfaces.any { it.isA(parent) }
        fun known(role: String) = requireNotNull(bridge.getMethodData(ui.getValue("method:$role")))
        fun save(role: String, method: MethodData) { put("method:$role", method.descriptor) }
        val community = requireNotNull(bridge.getMethodData(base.getValue("method:app.zhendong.reamicro.ui.profile.components.CommunityKt#Community")))
        val background = known("background")
        val materialize = known("materialize")
        val modifier = background.paramTypes.first()
        val composer = materialize.paramTypes.first()
        val content = community.callers.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
            it.returnTypeName == "java.lang.Object" }.one("profile content")
        val owner = requireNotNull(content.declaredClass)
        val systemBars = content.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("int", composer.name, "boolean") && it.returnTypeName == "void" }
            .one("profile system bars")
        val contentCalls = flow.calls(content.descriptor)
        check(contentCalls.indexOfFirst { it.descriptor == systemBars.descriptor } in
            0 until contentCalls.indexOfFirst { it.descriptor == materialize.descriptor })
        save("systemBars", systemBars)
        val root = owner.methods.filter { it.isConstructor }.flatMap { it.callers }.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(community.paramTypeNames.first(), composer.name, "int") && it.returnTypeName == "void" }.one("profile screen")
        val privilege = content.invokes.filter { it.usingStrings.containsAll(listOf("青士特权", "开通青士特权")) }.one("privilege card")
        val surface = community.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("boolean", "float", composer.name) && it.returnTypeName == modifier.name }.one("community background")
        val brush = requireNotNull(requireNotNull(bridge.getMethodData(base.getValue("method:androidx.compose.ui.text.TextStyle#getBrush"))).returnType)
        val brushBackground = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == modifier.name &&
            it.paramCount == 4 && it.paramTypeNames.take(2) == listOf(modifier.name, brush.name) &&
            it.paramTypeNames.last() == "int" }.one("brush background")
        val brushCall = flow.calls(content.descriptor).filter { it.descriptor == brushBackground.descriptor }
            .filter { it.arguments[2] == HostDexCalls.Literal(0) && it.arguments[3] is HostDexCalls.Literal }.distinct().single()
        put("value:brushMask", (brushCall.arguments[3] as HostDexCalls.Literal).value.toString())
        val alpha = surface.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("float", "long") &&
            it.returnTypeName == "long" && privilege.invokes.any { p -> p.descriptor == it.descriptor } }.one("surface color alpha")
        val color = requireNotNull(alpha.declaredClass)
        check(color.methods.any { it.usingStrings.contains("Color(") })
        val fromArgb = found(MethodMatcher.create().paramTypes("int").returnType("long")).filter { m ->
            Modifier.isStatic(m.modifiers) && m.usingFields.any { it.field.declaredClassName == color.name } &&
                m.usingNumbers.any { it.longValue() == 32L } && m.invokes.isEmpty()
        }.one("ARGB color encoding")
        val toArgb = found(MethodMatcher.create().paramTypes("long").returnType("void")).filter { m ->
            m.invokes.any { it.descriptor == "Landroid/graphics/Paint;->setColor(I)V" }
        }.flatMap { m -> m.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "int" } }
            .one("Android paint color conversion")
        val shader = found(MethodMatcher.create().name("<init>").paramTypes("android.graphics.Shader")).filter { it.declaredClass?.isA(brush) == true }
            .one("shader brush constructor")
        for ((role, cls) in listOf("modifier" to modifier, "composer" to composer, "brush" to brush,
            "content" to owner, "scope" to community.paramTypes.first(), "shader" to requireNotNull(shader.declaredClass))) put("class:$role", cls.descriptor)
        put("constructor:shader", shader.descriptor)
        for ((role, m) in listOf("root" to root, "content" to content, "community" to community, "privilege" to privilege,
            "surface" to surface, "brushBackground" to brushBackground, "alpha" to alpha, "fromArgb" to fromArgb, "toArgb" to toArgb)) save(role, m)
        for (role in listOf("background", "materialize", "colorScheme", "backgroundAuto", "backgroundBright", "backgroundDim", "divider")) save(role, known(role))
        for ((role, m) in listOf("community" to community, "privilege" to privilege)) {
            val boolean = m.paramTypeNames.indices.single { m.paramTypeNames[it] == "boolean" }
            val composerIndex = m.paramTypeNames.indices.single { m.paramTypeNames[it] == composer.name }
            check(boolean < composerIndex && m.paramTypeNames.drop(composerIndex + 1).all { it == "int" })
            val flags = composerIndex + 1 + boolean / 10
            check(flags < m.paramCount)
            val mask = 14 shl (3 * (boolean % 10))
            put("value:$role.boolean", boolean.toString()); put("value:$role.flags", flags.toString()); put("value:$role.mask", mask.toString())
        }
        check(content.invokes.any { it.descriptor == materialize.descriptor })
        check(privilege.invokes.any { it.descriptor == materialize.descriptor })
        check(surface.invokes.any { it.descriptor == background.descriptor })
    }
}
