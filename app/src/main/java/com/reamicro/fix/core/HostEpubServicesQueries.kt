package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData

/** EPUB tools follow the host's decoder, parser and native cache, including inlined accessors. */
internal object HostEpubServicesQueries {
    internal fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("EPUB services $role is missing or ambiguous")
    internal fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
        superClass?.isA(parent) == true || interfaces.any { it.isA(parent) }
    internal class Query(val bridge: DexKitBridge, apk: String, val base: Map<String, String>) {
        val flow = HostDexCalls(apk)
        val values = linkedMapOf<String, String>()
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun type(name: String) = requireNotNull(bridge.getClassData(base.getValue("class:$name")))
        fun found(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        fun save(role: String, m: MethodData) { values["method:$role"] = m.descriptor }
        fun field(role: String, f: FieldData) { values["field:$role"] = f.descriptor }
        fun field(role: String, descriptor: String) = field(role, requireNotNull(bridge.getFieldData(descriptor)))
        fun cls(role: String, c: ClassData) { values["class:$role"] = c.descriptor }
        fun ctor(role: String, m: MethodData) { values["constructor:$role"] = m.descriptor }
        fun fieldFrom(role: String, value: HostDexCalls.Value): FieldData {
            val f = requireNotNull(bridge.getFieldData((value as HostDexCalls.Field).descriptor))
            field(role, f); return f
        }
    }
    fun query(group: String, bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> =
        Query(bridge, apk, base).run {
            when (group) {
                "svg" -> svg()
                "css" -> HostCssServiceQueries.query(this)
                "metadata" -> metadata()
                else -> error("Unknown EPUB service $group")
            }
            values
        }

    private fun Query.svg() {
        val decode = known("ReaMicroHost.SvgDecoder", "decode")
        val render = known("ReaMicroHost.SvgRenderer", "render")
        val renderer = requireNotNull(render.declaredClass)
        val rendererCtor = renderer.methods.filter { it.isConstructor && it.paramCount == 4 &&
            it.paramTypeNames.takeLast(2) == listOf("int", "int") }.one("SVG renderer constructor")
        val document = rendererCtor.paramTypes[0]
        val options = rendererCtor.paramTypes[1]
        val source = decode.paramTypes.single()
        val decoded = requireNotNull(decode.returnType)
        val documentConstructors = decode.invokes.filter { it.isConstructor && it.declaredClassName == decoded.name &&
            it.paramTypeNames == listOf(document.name) }.distinctBy { it.descriptor }
        val documentWrites = documentConstructors.flatMap { constructor -> flow.writes(constructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(1)
        } } + flow.writes(decode.descriptor).filter { write ->
            (write.receiver as? HostDexCalls.Allocation)?.type == decoded.descriptor &&
                (write.value as? HostDexCalls.Field)?.let { bridge.getFieldData(it.descriptor)?.typeName } == document.name
        }
        field("decoded.document", documentWrites.map { it.field }.distinct().single())
        val input = decode.invokes.filter { it.declaredClassName == source.name && it.paramCount == 0 &&
            it.returnTypeName == "java.io.InputStream" }.one("SVG input stream")
        val decoder = requireNotNull(decode.declaredClass)
        field("decoder.instance", decoder.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == decoder.name })
        val boxCtor = render.invokes.filter { it.isConstructor && it.paramTypeNames == List(4) { "float" } }.one("SVG box")
        val box = requireNotNull(boxCtor.declaredClass)
        val dimensions = document.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == box.name && it.invokes.any { call -> call.descriptor == boxCtor.descriptor } }.one("SVG dimensions")
        val rootCast = flow.casts(dimensions.descriptor).filter { cast ->
            val cls = bridge.getClassData(cast.type)
            cls?.methods?.any { it.paramCount == 0 && it.returnTypeName == "java.lang.String" && "svg" in it.usingStrings } == true &&
                (cast.value as? HostDexCalls.Field)?.descriptor?.startsWith(document.descriptor + "->") == true
        }.distinct().single()
        val root = requireNotNull(bridge.getClassData(rootCast.type))
        fieldFrom("document.root", rootCast.value)
        field("root.viewBox", dimensions.usingFields.map { it.field }.distinctBy { it.descriptor }.single {
            it.typeName == box.name && it.declaredClass?.let { owner -> root.isA(owner) } == true
        })
        val viewportCall = flow.calls(render.descriptor).filter { it.descriptor == boxCtor.descriptor }.single { call ->
            call.arguments.any { (it as? HostDexCalls.Conversion)?.value.let { value ->
                (value as? HostDexCalls.Call)?.descriptor == "Landroid/graphics/Canvas;->getWidth()I"
            } }
        }
        for ((role, native) in listOf("width" to "getWidth", "height" to "getHeight")) {
            val slot = viewportCall.arguments.indices.single { i ->
                val conversion = viewportCall.arguments[i] as? HostDexCalls.Conversion
                conversion?.operation == "INT_TO_FLOAT" &&
                    (conversion.value as? HostDexCalls.Call)?.descriptor == "Landroid/graphics/Canvas;->$native()I"
            }
            field("box.$role", flow.writes(boxCtor.descriptor).single {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot)
            }.field)
        }
        val rootRender = render.invokes.filter { it.paramTypes.firstOrNull()?.name == root.name &&
            it.paramTypeNames.count { p -> p == box.name } == 2 && it.returnTypeName == "void" }.one("SVG root rendering")
        val translation = flow.calls(rootRender.descriptor).filter { it.descriptor == "Landroid/graphics/Canvas;->translate(FF)V" }
            .single { call -> call.arguments.drop(1).all { (it as? HostDexCalls.Field)?.descriptor?.startsWith(box.descriptor + "->") == true } }
        fieldFrom("box.left", translation.arguments[1]); fieldFrom("box.top", translation.arguments[2])
        check(listOf("left", "top", "width", "height").map { values.getValue("field:box.$it") }.distinct().size == 4)
        for ((role, type) in listOf("decoder" to decoder, "source" to source, "decoded" to decoded,
            "document" to document, "renderer" to renderer, "options" to options, "box" to box, "root" to root)) cls(role, type)
        save("decode", decode); save("input", input); save("render", render); save("dimensions", dimensions)
        ctor("renderer", rendererCtor)
    }

    private fun Query.metadata() {
        val opf = type("org.epub.structure.opf.Opf")
        val metadata = type("org.epub.structure.opf.Metadata")
        val identifier = type("org.epub.structure.metadata.Identifier")
        val kind = type("org.epub.structure.metadata.IdentifierType")
        val obtain = known("org.epub.structure.opf.Opf", "obtain")
        val path = obtain.paramTypes.single()
        val attach = known("org.epub.css.StyleSheetsProvider", "attach")
        val pathCreate = attach.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.String", "boolean") && it.returnTypeName == path.name }.one("OPF path")
        for ((role, logical) in listOf("opf.metadata" to "org.epub.structure.opf.Opf#metadata",
            "opf.reference" to "org.epub.structure.opf.Opf#uniqueIdentifier", "metadata.identifier" to "org.epub.structure.opf.Metadata#uuid",
            "identifier.id" to "org.epub.structure.metadata.Identifier#id", "identifier.type" to "org.epub.structure.metadata.Identifier#type",
            "identifier.value" to "org.epub.structure.metadata.Identifier#value")) field(role, base.getValue("field:$logical"))
        val id = requireNotNull(bridge.getFieldData(values.getValue("field:identifier.id"))).type
        val xml = known("org.epub.structure.metadata.Identifier", "toXml")
        val idValue = xml.invokes.filter { it.declaredClassName == id.name && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("identifier ID value")
        field("kind.scheme", xml.usingFields.map { it.field }.distinctBy { it.descriptor }.single {
            it.declaredClassName == kind.name && !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String"
        })
        for ((role, type) in listOf("opf" to opf, "metadata" to metadata, "identifier" to identifier,
            "kind" to kind, "id" to id, "path" to path)) cls(role, type)
        save("obtain", obtain); save("path", pathCreate); save("id.value", idValue)
        save("bannerNames", known("app.zhendong.reamicro.ui.home.BannerVariantKt", "toBannerVariantNames"))
        val coverOverview = known("app.zhendong.reamicro.ui.home.components.BookOverviewItemsKt", "BookCoverBannerItem")
        val coverNames = found(MethodMatcher.create().usingEqStrings("cover~backup", "cover")
            .paramTypes("java.lang.String", "java.lang.String")).filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName != "void" &&
                it.declaredClassName == coverOverview.declaredClassName
        }.one("native cover names")
        val plan = requireNotNull(coverNames.returnType)
        val constructor = coverNames.invokes.filter { it.isConstructor && it.declaredClassName == plan.name &&
            it.paramTypeNames == List(2) { "java.lang.String" } }.one("native cover name pair")
        val writes = flow.writes(constructor.descriptor)
        cls("coverPlan", plan)
        save("coverNames", coverNames)
        for ((role, slot) in listOf("cover.primary" to 1, "cover.backup" to 2)) {
            field(role, writes.single { it.receiver == HostDexCalls.Parameter(0) &&
                it.value == HostDexCalls.Parameter(slot) }.field)
        }
        HostCoverCacheQueries.query(this)
    }
}
