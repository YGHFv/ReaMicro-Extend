package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostParagraphCommentQueries {
    val imageProperties = "id path name width height x y viewBox fill preserveAspectRatio commentAnnotation".split(' ')
    val commentProperties = "plainText imagePath href sourcePath prefix backRefId anchorFontSizeSp".split(' ')
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Paragraph comment $role is missing or ambiguous")
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun anchor(vararg text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*text)))
        val dom = type("dom", "org.epub.html.node.ContentDom")
        val image = type("image", "org.epub.html.ImagePlaceholder")
        val comment = type("comment", "org.epub.ui.CommentAnnotation")
        val style = type("style", "org.epub.css.Style")
        val content = known("org.epub.html.node.ContentDom", "buildContent")
        val inline = known("org.epub.html.node.ContentDom", "inlineContents")
        val cfiText = known("org.epub.html.EpubCFI", "toString")
        val cfi = requireNotNull(cfiText.declaredClass)
        put("class:cfi", cfi.descriptor); put("method:cfiText", cfiText.descriptor)
        put("field:dom.location", dom.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == cfi.name }.descriptor)
        put("field:dom.style", base.getValue("field:org.epub.html.node.ContentDom#style"))
        val records = HostRecordQueries.query(apk, mapOf("class:image" to image.descriptor, "class:comment" to comment.descriptor))
        for ((role, properties) in listOf("image" to imageProperties, "comment" to commentProperties))
            for (name in properties) put("field:$role.$name", records.getValue("field:$role#$name"))
        val flow = HostDexCalls(apk)
        val imageConstructor = image.methods.filter { candidate ->
            candidate.isConstructor && candidate.paramCount == imageProperties.size &&
                flow.writes(candidate.descriptor).let { writes -> imageProperties.all { name ->
                    writes.filter { it.receiver == HostDexCalls.Parameter(0) && it.field == getValue("field:image.$name") }
                        .map { it.value }.distinct().singleOrNull() is HostDexCalls.Parameter
                } }
        }.one("image constructor")
        put("constructor:image", imageConstructor.descriptor)
        val writes = flow.writes(imageConstructor.descriptor)
        for (name in imageProperties) {
            val parameter = writes.filter { it.receiver == HostDexCalls.Parameter(0) && it.field == getValue("field:image.$name") }
                .map { it.value }.distinct().single() as HostDexCalls.Parameter
            put("value:image.$name", (parameter.index - 1).toString())
        }
        val placeholderConstructor = anchor("width cannot be TextUnit.Unspecified", "height cannot be TextUnit.Unspecified")
            .filter { it.isConstructor && it.paramTypeNames.sorted() == listOf("int", "long", "long") }.one("Compose placeholder constructor")
        val placeholder = requireNotNull(placeholderConstructor.declaredClass)
        put("class:placeholder", placeholder.descriptor); put("constructor:placeholder", placeholderConstructor.descriptor)
        val alignText = placeholder.methods.filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
            "TextCenter" in it.usingStrings }.one("placeholder vertical alignment")
        val align = flow.equalityTexts(alignText.descriptor).filter { it.text == "TextCenter" }.single()
        val alignField = (align.value as? HostDexCalls.Field)?.descriptor ?: error("Placeholder alignment is not a field")
        val alignParameter = flow.writes(placeholderConstructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.field == alignField
        }.map { it.value }.distinct().single() as HostDexCalls.Parameter
        put("field:placeholder.align", alignField)
        put("value:placeholder.align", align.literal.toString())
        put("value:placeholder.alignIndex", (alignParameter.index - 1).toString())
        val range = type("range", "androidx.compose.ui.text.AnnotatedString\$Range")
        val casts = flow.casts(inline.descriptor)
        for ((role, target) in listOf("images" to image, "placeholders" to range)) {
            val fields = casts.filter { it.type == target.descriptor }.mapNotNull { cast ->
                val call = cast.value as? HostDexCalls.Call ?: return@mapNotNull null
                if (call.descriptor != "Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;") return@mapNotNull null
                (call.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor
            }.distinct()
            put("field:dom.$role", fields.single())
        }
        val fontConstructors = anchor("font-size").filter { it.isConstructor }.mapNotNull { it.declaredClass }.distinctBy { it.descriptor }
        val fontField = style.fields.filter { field -> !Modifier.isStatic(field.modifiers) && fontConstructors.any { it.name == field.typeName } }.single()
        val font = requireNotNull(bridge.getClassData(fontField.typeName))
        put("class:font", font.descriptor); put("field:style.font", fontField.descriptor)
        val fontNumber = content.invokes.filter { it.paramCount == 0 && it.returnTypeName == "float" &&
            "actualValue[" in it.usingStrings && "] isn't CSSUnit" in it.usingStrings }.one("CSS numeric font size")
        put("method:fontNumber", fontNumber.descriptor)
        val unit = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Float") &&
            it.returnTypeName == "long" && "density" in it.usingStrings }.one("native placeholder text unit")
        put("method:unit", unit.descriptor)
        val decoder = anchor("__reamicro_comment__").filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == comment.name }.one("native comment wire decoder")
        // Keep the wire format tied to the native parser's escaping operation as well.
        val unescape = decoder.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.toSet() == setOf("int", "java.util.List") &&
            it.paramCount == 2 && it.returnTypeName == "java.lang.String" && it.usingStrings.containsAll(listOf("\\u001F", "\u001f", "\\\\", "\\")) }.one("comment escaping")
        put("method:decode", decoder.descriptor); put("method:unescape", unescape.descriptor)
    }
}
