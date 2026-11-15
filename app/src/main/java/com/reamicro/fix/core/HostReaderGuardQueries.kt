package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostReaderGuardQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: missing/ambiguous ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun backKey(bridge: DexKitBridge, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val root = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes("android.view.KeyEvent").returnType("boolean"))).filter { m ->
            !Modifier.isStatic(m.modifiers) && m.invokes.any { it.descriptor ==
                "Landroid/view/FocusFinder;->findNextFocus(Landroid/view/ViewGroup;Landroid/view/View;I)Landroid/view/View;" } &&
                m.invokes.any { it.className == "androidx.compose.ui.platform.AndroidComposeView" && it.methodName == "getFocusOwner" } &&
                m.declaredClass?.fields?.count { !Modifier.isStatic(it.modifiers) && it.typeName == "androidx.compose.ui.platform.AndroidComposeView" } == 1
        }.one("Compose root focus key handler")
        out["method:key"] = root.descriptor
        out["field:owner"] = root.declaredClass!!.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == "androidx.compose.ui.platform.AndroidComposeView"
        }.descriptor
        out["class:vm"] = base.getValue("class:${P}ui.reader.ReaderViewModel")
        out["class:state"] = base.getValue("class:${P}ui.reader.ReaderUiState")
        out["method:currentState"] = base.getValue("method:${P}arch.MVIViewModel#getCurrentState")
        out["field:status"] = base.getValue("field:${P}ui.reader.ReaderUiState#status")
        out["field:sheet"] = base.getValue("field:${P}ui.reader.ReaderUiState#sheetStatus")
        val status = requireNotNull(bridge.getFieldData(out.getValue("field:status"))).typeName
        val sheet = requireNotNull(bridge.getFieldData(out.getValue("field:sheet"))).typeName
        val hidden = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Hidden")))
            .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
                it.declaredClass?.interfaces.orEmpty().any { t -> t.name == sheet } }.one("reader hidden sheet")
        out["class:hidden"] = requireNotNull(hidden.declaredClass).descriptor
        for (label in listOf("Reader", "Menu", "Catalog", "Font", "Highlight", "MarginEditor", "Theme", "TypeSetting", "EinkSettings")) {
            val type = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(label)))
                .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" && it.declaredClass?.superClass?.name == status }
                .one("reader status $label").declaredClass!!
            out["class:status.$label"] = type.descriptor
        }
        for (label in listOf("Hidden", "BuildInFamily", "EpubFamily", "UserFamily")) {
            val type = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(label)))
                .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
                    it.declaredClass?.interfaces.orEmpty().any { t -> t.name == sheet } }.one("reader sheet $label").declaredClass!!
            out["class:sheet.$label"] = type.descriptor
            out["field:sheet.$label"] = type.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == type.name }.descriptor
        }
        val snapshot = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("SelectionSnapshot(anchor=")))
            .one("selection snapshot").declaredClass!!
        val begin = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.ui.EpubSelectionController#beginSelection-k-4lQ0M")))
        val selection = requireNotNull(begin.declaredClass)
        out["class:selection"] = selection.descriptor
        out["class:snapshot"] = snapshot.descriptor
        out["method:selection"] = selection.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == snapshot.name }.one("current selection snapshot").descriptor
        return out
    }

    fun typography(bridge: DexKitBridge, apk: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val config = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("ReaderEpubConfig(family=")))
            .one("reader EPUB configuration").declaredClass!!
        out["class:config"] = config.descriptor
        val fields = config.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
        val properties = HostRecordQueries.query(apk, mapOf("class:config" to config.descriptor))
        val flow = HostDexCalls(apk)
        val constructor = config.methods.filter { c -> c.isConstructor && c.paramCount == fields.size &&
            flow.writes(c.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }.map { it.field }.toSet() == fields
        }.one("reader configuration constructor")
        out["constructor:config"] = constructor.descriptor
        val writes = flow.writes(constructor.descriptor)
        for (key in listOf("textSize", "lineHeight")) {
            val field = properties.getValue("field:config#$key")
            check(field.endsWith(":F"))
            out["field:$key"] = field
            val value = writes.filter { it.field == field && it.receiver == HostDexCalls.Parameter(0) }.single().value
            val parameter = value as? HostDexCalls.Parameter ?: error("Reader $key is not a constructor parameter")
            check(parameter.index > 0 && constructor.paramTypeNames[parameter.index - 1] == "float")
            out["value:$key"] = (parameter.index - 1).toString()
        }
        val callers = constructor.callers.filter { it.isMethod }.distinctBy { it.descriptor }
        check(callers.isNotEmpty())
        callers.forEachIndexed { index, caller -> out["method:caller.$index"] = caller.descriptor }
        return out
    }
}
