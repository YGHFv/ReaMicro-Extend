package com.reamicro.fix.core

import java.lang.reflect.Modifier
import com.reamicro.fix.core.HostEpubServicesQueries.one
import org.luckypray.dexkit.result.ClassData

internal object HostCssServiceQueries {
    fun query(q: HostEpubServicesQueries.Query) = with(q) {
        val attach = known("org.epub.css.StyleSheetsProvider", "attach")
        val sheets = requireNotNull(attach.returnType)
        val rule = type("org.epub.css.RuleSet")
        val selector = type("org.epub.css.query.Selector")
        val property = type("org.epub.css.property.CSSProperty")
        val unit = type("org.epub.css.property.CSSUnit")
        val initialize = known("org.epub.css.RuleSet", "initialize")
        val merge = initialize.callers.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.util.ArrayList", "java.util.Map") &&
            it.returnTypeName == "java.util.ArrayList" }.one("CSS merge")
        val cacheGet = attach.invokes.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" &&
            it.invokes.any { m -> m.descriptor == "Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;" }
        }.one("CSS cache read")
        val cache = requireNotNull(cacheGet.declaredClass)
        val lazyCall = flow.casts(attach.descriptor).filter { it.type == cache.descriptor }
            .map { it.value as HostDexCalls.Call }.distinct().single()
        val lazyGet = requireNotNull(bridge.getMethodData(lazyCall.descriptor))
        fieldFrom("provider.cache", lazyCall.arguments.single())
        val entriesCall = flow.calls(cacheGet.descriptor).single {
            it.descriptor == "Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;"
        }
        val entries = fieldFrom("cache.entries", entriesCall.arguments.first())
        val holder = requireNotNull(entries.declaredClass)
        field("cache.holder", cacheGet.usingFields.map { it.field }.distinctBy { it.descriptor }.single {
            it.declaredClassName == cache.name && it.typeName == holder.name
        })
        val expire = cacheGet.invokes.filter { it.declaredClassName == cache.name && it.isMethod &&
            it.paramCount == 0 && it.returnTypeName == "void" }.one("CSS cache expiry")
        fun ClassData.isCollection(): Boolean = name in setOf("java.util.Set", "java.util.Collection") ||
            interfaces.any { it.isCollection() } || superClass?.isCollection() == true
        val queues = expire.usingFields.map { it.field }.filter { it.declaredClassName == cache.name &&
            !Modifier.isStatic(it.modifiers) && it.type.isCollection() }.distinctBy { it.descriptor }.sortedBy { it.descriptor }
        check(queues.size == 2) { "CSS cache must unlink both native expiry queues" }
        queues.forEachIndexed { i, queue -> field("cache.queue.$i", queue) }
        field("sheets.rules", base.getValue("field:org.epub.css.StyleSheets#ruleSets"))
        field("rule.selector", rule.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == selector.name })
        val ruleCtor = rule.methods.filter { it.isConstructor && it.paramTypeNames.count { p -> p == "java.util.List" } == 1 }
            .one("CSS rule constructor")
        val declarationSlot = ruleCtor.paramTypeNames.indexOf("java.util.List") + 1
        field("rule.declarations", flow.writes(ruleCtor.descriptor).single {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(declarationSlot)
        }.field)
        val declaration = flow.casts(initialize.descriptor).mapNotNull { bridge.getClassData(it.type) }.distinctBy { it.descriptor }.single { cls ->
            cls.fields.count { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" } == 2 &&
                cls.methods.any { it.name == "toString" && it.usingStrings == listOf(": ") }
        }
        val rawValue = flow.calls(initialize.descriptor).filter { HostDexCalls.Text("!important") in it.arguments }
            .flatMap { it.arguments }.filterIsInstance<HostDexCalls.Field>()
            .filter { it.descriptor.startsWith(declaration.descriptor + "->") }.distinct().single()
        fieldFrom("declaration.value", rawValue)
        field("declaration.name", declaration.fields.single { !Modifier.isStatic(it.modifiers) &&
            it.typeName == "java.lang.String" && it.descriptor != rawValue.descriptor })
        val selectorCtor = selector.methods.filter { it.isConstructor && "@font-face" in it.usingStrings }.one("CSS selector")
        val nameParameter = flow.calls(selectorCtor.descriptor).filter { HostDexCalls.Text("@font-face") in it.arguments }
            .flatMap { it.arguments }.filterIsInstance<HostDexCalls.Parameter>().distinct().single()
        field("selector.name", flow.writes(selectorCtor.descriptor).single {
            it.receiver == HostDexCalls.Parameter(0) && it.value == nameParameter
        }.field)
        field("selector.level", selector.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "int" })
        val propertyName = property.methods.filter { Modifier.isAbstract(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == "java.lang.String" }.one("CSS property name")
        val initial = property.methods.filter { Modifier.isAbstract(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName != "java.lang.String" }.one("CSS initial value")
        val actual = property.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            !Modifier.isAbstract(it.modifiers) && it.paramCount == 0 && it.returnTypeName == initial.returnTypeName }.one("CSS actual value")
        val number = unit.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == "float" }.one("CSS unit number")
        val trim = initialize.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.String") &&
            it.returnTypeName == "java.lang.String" }.one("CSS trim")
        field("registry", initialize.usingFields.map { it.field }.filter { Modifier.isStatic(it.modifiers) &&
            it.typeName == "java.util.LinkedHashMap" }.distinctBy { it.descriptor }.single())
        for ((role, type) in listOf("provider" to requireNotNull(attach.declaredClass), "sheets" to sheets, "rule" to rule,
            "selector" to selector, "declaration" to declaration, "property" to property, "unit" to unit,
            "value" to requireNotNull(initial.returnType), "cache" to cache, "holder" to holder,
            "lazy" to requireNotNull(lazyGet.declaredClass))) cls(role, type)
        for ((role, method) in listOf("attach" to attach, "merge" to merge, "cache.get" to cacheGet,
            "lazy.get" to lazyGet, "cache.expire" to expire, "trim" to trim, "property.name" to propertyName,
            "property.initial" to initial, "property.actual" to actual, "unit.number" to number)) save(role, method)
    }
}
