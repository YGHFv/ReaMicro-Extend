package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** The dictionary's real overlay is the browser surface; no unrelated SDK WebView is accepted. */
internal object HostBrowserQueries {
    private fun List<MethodData>.one(role: String) =
        distinctBy { it.descriptor }.singleOrNull()
            ?: error("Native browser $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, ui: Map<String, String>): Map<String, String> {
        val composer = requireNotNull(bridge.getMethodData(ui.getValue("method:title"))).paramTypes[2]!!
        val overlay = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(
            "p.quark.cn", "https://hanyu.baidu.com/hanyu-page/zici/s?wd=",
        ))).filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramCount == 8 && it.paramTypeNames.take(4) ==
                listOf("java.lang.String", "java.lang.String", "boolean", "java.lang.String") &&
                it.paramTypeNames.takeLast(2) == listOf(composer.name, "int")
        }.one("dictionary overlay")
        val source = requireNotNull(bridge.findMethod(FindMethod.create().matcher(
            MethodMatcher.create().usingEqStrings("DictionaryWebSource(url=", ", host=", ", submissionScript="),
        )).filter {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String"
        }.one("dictionary source identity").declaredClass)
        val constructor = overlay.invokes.filter {
            it.isConstructor && it.declaredClass?.descriptor == source.descriptor &&
                it.paramTypeNames == List(3) { "java.lang.String" }
        }.one("dictionary source construction")
        check(source.fields.filterNot { Modifier.isStatic(it.modifiers) }.let { fields ->
            fields.size == 3 && fields.all { Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.String" }
        })
        val flow = HostDexCalls(apk)
        val writes = flow.writes(constructor.descriptor)
        check((1..3).all { parameter ->
            writes.count { it.receiver == HostDexCalls.Parameter(0) &&
                it.value == HostDexCalls.Parameter(parameter) } == 1
        })
        val haze = requireNotNull(overlay.paramTypes[4])
        val hazeConstructor = haze.methods.filter {
            it.isConstructor && it.paramTypeNames == listOf("boolean")
        }.one("dictionary backdrop state")
        val dismiss = requireNotNull(overlay.paramTypes[5])
        check(Modifier.isInterface(dismiss.modifiers) && dismiss.methods.any {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.Object"
        })
        check(overlay.invokes.any { call ->
            Modifier.isStatic(call.modifiers) && call.paramTypeNames.contains(haze.name)
        })
        return linkedMapOf(
            "class:composer" to composer.descriptor,
            "class:dismiss" to dismiss.descriptor,
            "class:source" to source.descriptor,
            "class:haze" to haze.descriptor,
            "method:overlay" to overlay.descriptor,
            "constructor:source" to constructor.descriptor,
            "constructor:haze" to hazeConstructor.descriptor,
        )
    }
}
