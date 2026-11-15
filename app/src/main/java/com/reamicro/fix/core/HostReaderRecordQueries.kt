package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

internal object HostReaderRecordQueries {
    val schemas = linkedMapOf(
        "mark" to "id bookId cloudId chapter kind startCfi endCfi quote note style color synced createdAt updatedAt".split(' '),
        "resolved" to "id kind startCfi endCfi style color note quote createdAt".split(' '),
        "overlay" to "mark range style color fillAlpha".split(' '),
    )
    val logicals = mapOf("mark" to "app.zhendong.reamicro.data.db.entity.Mark", "resolved" to "org.epub.ui.ResolvedMark", "overlay" to "org.epub.ui.ContentMarkOverlay")
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader record $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        val overlay = requireNotNull(bridge.getClassData(base.getValue("class:${logicals.getValue("overlay")}")))
        val toString = overlay.methods.filter { it.name == "toString" && it.paramCount == 0 }.one("overlay toString")
        val formatters = listOf("TextRange(", "Color(").map { anchor ->
            toString.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") &&
                it.returnTypeName == "java.lang.String" && anchor in it.usingStrings }.one(anchor).descriptor
        }.toSet()
        val fields = HostRecordQueries.query(apk, logicals.map { (role, name) ->
            "class:$role" to base.getValue("class:$name")
        }.toMap(), scalarFormatters = formatters)
        for ((role, properties) in schemas) {
            val owner = requireNotNull(bridge.getClassData(base.getValue("class:${logicals.getValue(role)}")))
            out["class:$role"] = owner.descriptor
            val propertiesByField = properties.associateBy { fields.getValue("field:$role#$it") }
            val constructor = owner.methods.filter { candidate -> candidate.isConstructor && candidate.paramCount == properties.size &&
                flow.writes(candidate.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field in propertiesByField }
                    .let { writes -> writes.map { it.field }.toSet() == propertiesByField.keys &&
                        writes.mapNotNull { it.value as? HostDexCalls.Parameter }.map { it.index }.toSet() == (1..properties.size).toSet() }
            }.one("$role constructor")
            out["constructor:$role"] = constructor.descriptor
            for ((field, name) in propertiesByField) {
                val source = flow.writes(constructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field == field }
                    .map { it.value }.distinct().single() as HostDexCalls.Parameter
                out["field:$role.$name"] = field
                out["value:$role.$name"] = (source.index - 1).toString()
            }
        }
        val create = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.EpubCFI\$Companion#create")))
        check(Modifier.isStatic(create.modifiers) && create.paramTypeNames == listOf("java.lang.String"))
        out["method:cfi"] = create.descriptor
        out["class:cfi"] = requireNotNull(create.returnType).descriptor
        val resolver = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.ui.ContentKt#resolveMarkOverlays-xwkQ0AY")))
        val color = resolver.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.String") &&
            it.returnTypeName == "long" && it.usingStrings.containsAll(listOf("green", "blue", "yellow", "purple")) }.one("mark color")
        out["method:color"] = color.descriptor
        val range = resolver.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int", "int") &&
            it.returnTypeName == "long" && "start and end cannot be negative. [start: " in it.usingStrings }.one("text range")
        out["method:range"] = range.descriptor
        val dom = requireNotNull(bridge.getClassData(base.getValue("class:org.epub.html.node.ContentDom")))
        val annotated = requireNotNull(bridge.getClassData(base.getValue("class:androidx.compose.ui.text.AnnotatedString")))
        out["class:dom"] = dom.descriptor
        out["class:annotated"] = annotated.descriptor
        out["field:dom.location"] = dom.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == create.returnTypeName }.descriptor
        out["field:dom.content"] = dom.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == annotated.name }.descriptor
        out["field:annotated.text"] = annotated.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }.descriptor
        for ((role, logical, names) in listOf(
            Triple("window", "org.epub.ui.ContentVisibleTextWindow", listOf("start", "endExclusive")),
            Triple("page", "app.zhendong.reamicro.data.epub.EpubPage", listOf("start")),
        )) {
            out["class:$role"] = base.getValue("class:$logical")
            names.forEach { out["field:$role.$it"] = base.getValue("field:$logical#$it") }
        }
        return out
    }
}
