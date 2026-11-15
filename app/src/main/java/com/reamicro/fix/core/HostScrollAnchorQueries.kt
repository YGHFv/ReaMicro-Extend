package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.ClassData

/** The optional native layout registry handles tables and nested scroll containers. */
internal object HostScrollAnchorQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Scroll anchor $role is missing or ambiguous")
    private fun ClassData.isMap(): Boolean = name == "java.util.Map" || interfaces.any { it.isMap() } || superClass?.isMap() == true

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, render: MethodData,
        structured: Boolean): Map<String, String> = buildMap {
        if (!structured) return@buildMap
        val flow = HostDexCalls(apk)
        val recordText = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("ScrollAnchorLayout(offsetForAnchor=", ", visibleAnchor=")))
            .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("layout record")
        val record = requireNotNull(recordText.declaredClass)
        put("class:scroll.anchor", record.descriptor)
        val fields = HostRecordQueries.query(apk, mapOf("class:anchor" to record.descriptor))
        val offsetField = requireNotNull(bridge.getFieldData(fields.getValue("field:anchor#offsetForAnchor")))
        put("field:scroll.anchor.offset", offsetField.descriptor)
        val callback = offsetField.type
        put("class:scroll.anchor.callback", callback.descriptor)
        val controller = requireNotNull(bridge.getMethodData(base.getValue(
            "method:org.epub.ui.EpubSelectionController#beginSelection-k-4lQ0M"))).declaredClass!!
        val cfi = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.EpubCFI\$Companion#create"))).returnType!!
        val invoke = callback.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" &&
            flow.casts(it.descriptor).any { cast -> cast.type == controller.descriptor } &&
            flow.casts(it.descriptor).any { cast -> cast.type == cfi.descriptor && cast.value == HostDexCalls.Parameter(1) }
        }.one("offset callback")
        put("method:scroll.anchor.offset", invoke.descriptor)
        val registrations = flow.referencingType(record.descriptor).mapNotNull(bridge::getMethodData).filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Object") &&
                it.returnTypeName == "java.lang.Object" && flow.casts(it.descriptor).any { cast ->
                    cast.type == record.descriptor && cast.value == HostDexCalls.Parameter(1)
                }
        }
        val registration = registrations.one("registry callback")
        val owner = requireNotNull(registration.declaredClass)
        put("class:scroll.anchor.registration", owner.descriptor)
        val element = base.getValue("class:org.epub.element.model.EpubElement")
        val nodes = controller.fields.single { !Modifier.isStatic(it.modifiers) && it.type.isMap() }.type
        for ((role, type) in listOf("element" to element, "map" to nodes.descriptor)) {
            val captured = flow.casts(registration.descriptor).filter { it.type == type }
                .mapNotNull { (it.value as? HostDexCalls.Field)?.descriptor }.distinct().single()
            check(requireNotNull(bridge.getFieldData(captured)).declaredClassName == owner.name)
            put("field:scroll.anchor.registration.$role", captured)
        }
        check(render.paramTypeNames.contains("kotlin.jvm.functions.Function1"))
        val constructors = owner.methods.filter { it.isConstructor }.map { it.descriptor }.toSet()
        check(render.callers.any { caller -> caller.invokes.any { it.descriptor in constructors } })
    }

    fun validate(c: HostEpubSourceBindings.Contract) {
        if (c.index("scroll.structured") == 0) return
        val offset = c.field("scroll.anchor.offset")
        check(!Modifier.isStatic(offset.modifiers) && offset.declaringClass == c.type("scroll.anchor") &&
            offset.type == c.type("scroll.anchor.callback"))
        check(c.method("scroll.anchor.offset").let { !Modifier.isStatic(it.modifiers) &&
            it.declaringClass == offset.type && it.parameterTypes.toList() == listOf(Any::class.java) && it.returnType == Any::class.java })
        for (role in listOf("element", "map")) check(c.field("scroll.anchor.registration.$role").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("scroll.anchor.registration") &&
                (it.type == Any::class.java || if (role == "element") it.type == c.type("scroll.element")
                    else Map::class.java.isAssignableFrom(it.type))
        })
    }
}
