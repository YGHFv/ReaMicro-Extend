package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Native Other S3 body, inputs, footer and header roles from the current APK's call arguments. */
internal object HostS3ConfigurationQueries {
    val REQUIRED = setOf(
        "class:configurationPreset", "class:configurationScheme", "class:configurationFunction1", "class:configurationModifier", "class:configurationState",
        "constructor:configurationBody", "constructor:configurationSaveBar", "constructor:configurationHeaderRestart",
        "method:configurationBody", "method:configurationImePadding", "method:configurationSaveBar", "method:configurationCard",
        "method:configurationHeader", "method:configurationHeaderRestart", "method:configurationField", "method:configurationBackground", "method:configurationSurface",
        "method:configurationTopBar", "method:configurationIcon", "method:configurationProviderGap",
        "value:configurationSaveBar.branch", "value:configurationScaffold.flags", "value:configurationScaffold.mask",
        "value:configurationDescription", "value:configurationProviderGap",
    )
    private fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Native S3 $label is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, configName: String, composer: String, function0: String,
        choices: String, other: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun anchor(vararg texts: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*texts)))
        fun save(role: String, method: MethodData): MethodData = method.also {
            out["${if (it.isConstructor) "constructor" else "method"}:$role"] = it.descriptor
        }
        val header = save("configurationHeader", anchor("Other S3", "Cloudflare R2", "Mote Sync").filter {
            Modifier.isStatic(it.modifiers) && it.paramCount == 4 && it.paramTypeNames[2] == composer
        }.one("provider header"))
        val headerRestart = save("configurationHeaderRestart", header.invokes.filter { it.isConstructor &&
            it.paramTypeNames == listOf("java.lang.Object", "java.lang.Object", "int", "byte")
        }.one("header restart constructor"))
        save("configurationHeaderRestart", requireNotNull(headerRestart.declaredClass).methods.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { target -> target.descriptor == header.descriptor }
        }.one("header restart callback"))
        val preset = header.paramTypes.first()
        check(preset.modifiers and 0x4000 != 0)
        out["class:configurationPreset"] = preset.descriptor
        val editor = requireNotNull(header.declaredClass).methods.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramCount == 7 && it.paramTypeNames.take(3) == listOf(preset.name, configName, "java.lang.String") &&
            it.paramTypeNames[4] == composer
        }.one("editor")
        out["class:configurationModifier"] = header.paramTypes[1].descriptor
        val input = save("configurationField", requireNotNull(header.declaredClass).methods.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramCount == 10 &&
                it.paramTypeNames.take(6) == listOf("java.lang.String", "java.lang.String", "boolean", "boolean", "boolean", "boolean") &&
                it.paramTypeNames.takeLast(3) == listOf(composer, "int", "int") &&
                it.paramTypes[6].modifiers and Modifier.INTERFACE != 0
        }.one("input"))
        out["class:configurationFunction1"] = input.paramTypes[6].descriptor
        val card = save("configurationCard", requireNotNull(header.declaredClass).methods.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramCount == 4 && it.paramTypeNames[0] == preset.name && it.paramTypeNames[2] == composer &&
            it.descriptor != header.descriptor
        }.one("provider card"))
        save("configurationSurface", card.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramCount == 9 && it.paramTypeNames[6] == composer
        }.one("provider surface"))
        val scaffold = editor.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 13 &&
            it.paramTypeNames[10] == composer && it.paramTypeNames[6] == "long"
        }.one("scaffold")
        val editorCalls = flow.calls(editor.descriptor)
        val scaffoldCall = editorCalls.filter { it.descriptor == scaffold.descriptor }.distinct().single()
        fun slotConstructor(slot: Int, role: String): MethodData {
            val lambda = scaffoldCall.arguments[slot] as? HostDexCalls.Call ?: error("Native S3 $role slot missing")
            val capture = lambda.arguments[1]
            val constructorCall = editorCalls.filter { it.descriptor.contains("-><init>(") && it.arguments.firstOrNull() == capture }.distinct().single()
            return save(role, requireNotNull(bridge.getMethodData(constructorCall.descriptor)))
        }
        val body = slotConstructor(9, "configurationBody")
        check(body.paramCount == 5 && body.paramTypeNames.first() == "boolean" &&
            body.paramTypeNames[1] == preset.name && body.paramTypeNames.drop(2).distinct().size == 1)
        out["class:configurationState"] = body.paramTypes[2].descriptor
        val bodyMethod = save("configurationBody", requireNotNull(body.declaredClass).methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
            it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("body callback"))
        // R8 merges unrelated screens into this callback. Only the discriminator written by
        // the verified S3 constructor may identify its inset modifier.
        val bodyBranch = flow.writes(body.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
            .mapNotNull { write -> (write.value as? HostDexCalls.Literal)?.let { write.field to it.value } }.toMap()
        val bodyCalls = flow.callsWithFields(bodyMethod.descriptor, bodyBranch).map { it.descriptor }.toSet()
        val modifier = header.paramTypes[1].name
        save("configurationImePadding", bodyMethod.invokes.filter { it.descriptor in bodyCalls &&
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(modifier) && it.returnTypeName == modifier
        }.one("body IME padding"))
        val saveBar = slotConstructor(2, "configurationSaveBar")
        check(saveBar.paramCount in 4..5 && saveBar.paramTypeNames.take(3) == listOf(function0, "boolean", "boolean"))
        save("configurationSaveBar", requireNotNull(saveBar.declaredClass).methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("save callback"))
        val saveCall = editorCalls.filter { it.descriptor == saveBar.descriptor }.distinct().single()
        out["value:configurationSaveBar.branch"] = if (saveBar.paramCount == 5)
            (saveCall.arguments.last() as HostDexCalls.Literal).value.toString() else "-1"
        val backgroundCall = scaffoldCall.arguments[6] as HostDexCalls.Call
        val background = save("configurationBackground", requireNotNull(bridge.getMethodData(backgroundCall.descriptor)))
        check(background.paramCount == 1 && background.returnTypeName == "long")
        out["class:configurationScheme"] = background.paramTypes.single().descriptor
        out["value:configurationScaffold.flags"] = (scaffoldCall.arguments[11] as HostDexCalls.Literal).value.toString()
        out["value:configurationScaffold.mask"] = (scaffoldCall.arguments[12] as HostDexCalls.Literal).value.toString()
        val bodyStrings = requireNotNull(bridge.getMethodData(out.getValue("method:configurationBody"))).usingStrings
        out["value:configurationDescription"] = bodyStrings.filter { it.startsWith("书架发生新增、修改或删除时") }.distinct().single()
        save("configurationTopBar", anchor("配置S3").flatMap { it.invokes }.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramCount == 11 && it.paramTypeNames.first() == "java.lang.String" && it.paramTypeNames[8] == composer
        }.one("top bar"))
        save("configurationIcon", header.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 7 &&
            it.paramTypeNames[1] == "java.lang.String" && it.paramTypeNames[3] == "long" && it.paramTypeNames[4] == composer
        }.one("link icon renderer"))
        val choiceMethod = requireNotNull(bridge.getMethodData(choices))
        val spacer = save("configurationProviderGap", choiceMethod.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 3 &&
                it.paramTypeNames[0] == "int" && it.paramTypeNames[2] == composer
        }.one("provider spacer"))
        val choiceCalls = flow.calls(choices)
        val otherIndex = choiceCalls.indexOfFirst { it.descriptor == other }
        check(otherIndex >= 0)
        val gap = choiceCalls.take(otherIndex).last { it.descriptor == spacer.descriptor }
            .arguments.firstOrNull() as? HostDexCalls.Literal ?: error("Native S3 provider gap is not a dimension")
        check(gap.value in 1L..48L)
        out["value:configurationProviderGap"] = gap.value.toString()
        check(out.keys == REQUIRED)
        return out
    }
}
