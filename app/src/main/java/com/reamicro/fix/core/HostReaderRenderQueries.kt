package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Trace each mark input to the list that is actually iterated as Mark/ResolvedMark. */
internal object HostReaderRenderQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader render $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun method(role: String, value: MethodData) { out["method:$role"] = value.descriptor }
        fun index(role: String, value: Int) { out["value:$role"] = value.toString() }
        for ((role, logical) in listOf(
            "mark" to "app.zhendong.reamicro.data.db.entity.Mark", "resolved" to "org.epub.ui.ResolvedMark",
            "dom" to "org.epub.html.node.ContentDom", "window" to "org.epub.ui.ContentVisibleTextWindow",
            "page" to "app.zhendong.reamicro.data.epub.EpubPage", "composer" to "androidx.compose.runtime.Composer",
            "shared" to "app.zhendong.reamicro.ui.reader.components.ReaderSharedState",
        )) out["class:$role"] = base.getValue("class:$logical")
        fun typeName(role: String) = requireNotNull(bridge.getClassData(out.getValue("class:$role"))).name
        fun iterableSource(value: HostDexCalls.Value): HostDexCalls.Value? {
            val next = value as? HostDexCalls.Call ?: return null
            if (next.descriptor != "Ljava/util/Iterator;->next()Ljava/lang/Object;") return null
            val iterator = next.arguments.singleOrNull() as? HostDexCalls.Call ?: return null
            if (iterator.descriptor !in setOf("Ljava/util/List;->iterator()Ljava/util/Iterator;",
                    "Ljava/util/Collection;->iterator()Ljava/util/Iterator;", "Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;")) return null
            return iterator.arguments.singleOrNull()
        }
        fun marksArgument(method: MethodData, mark: String): Int {
            val origin = flow.casts(method.descriptor).filter { it.type == out.getValue("class:$mark") }
                .mapNotNull { iterableSource(it.value) as? HostDexCalls.Parameter }.distinct().single()
            val index = origin.index - if (Modifier.isStatic(method.modifiers)) 0 else 1
            check(method.paramTypeNames.getOrNull(index) == "java.util.List")
            return index
        }
        val body = known("org.epub.ui.BodyKt", "Body")
        val scroll = known("app.zhendong.reamicro.ui.reader.components.ScrollPagerKt", "ScrollPagerElement")
        val catalog = known("app.zhendong.reamicro.ui.reader.ReaderViewModel", "computeCatalogPrecomputations")
        for ((role, target) in listOf("body" to body, "scroll" to scroll, "catalog" to catalog)) {
            method(role, target)
            index("$role.marks", marksArgument(target, "mark"))
        }
        for ((role, target) in listOf("body" to body, "scroll" to scroll)) {
            val composer = target.paramTypeNames.indices.single { target.paramTypeNames[it] == typeName("composer") }
            check(target.paramTypeNames.getOrNull(composer + 1) == "int")
            index("$role.composer", composer)
        }
        val container = known("app.zhendong.reamicro.ui.reader.components.EpubContainerKt", "EpubContainer")
        method("container", container)
        index("container.page", container.paramTypeNames.indices.single { container.paramTypeNames[it] == typeName("page") })
        val bodyMarks = out.getValue("value:body.marks").toInt()
        val containerCalls = flow.calls(container.descriptor)
        val containerSources = containerCalls.filter { it.descriptor == body.descriptor }
            .mapNotNull { it.arguments.getOrNull(bodyMarks) as? HostDexCalls.Parameter }.toMutableList()
        for (constructor in container.invokes.filter { it.isConstructor }) {
            for (render in constructor.declaredClass!!.methods.filter { it.invokes.any { call -> call.descriptor == body.descriptor } }) {
                val fields = flow.calls(render.descriptor).filter { it.descriptor == body.descriptor }
                    .mapNotNull { it.arguments.getOrNull(bodyMarks) as? HostDexCalls.Field }.map { it.descriptor }.toSet()
                val sources = flow.writes(constructor.descriptor).filter {
                    it.receiver == HostDexCalls.Parameter(0) && it.field in fields
                }.mapNotNull { it.value as? HostDexCalls.Parameter }.distinct()
                for (call in containerCalls.filter { it.descriptor == constructor.descriptor }) for (source in sources) {
                    (call.arguments.getOrNull(source.index) as? HostDexCalls.Parameter)?.let(containerSources::add)
                }
            }
        }
        val containerMarks = containerSources.distinct().single().index
        check(Modifier.isStatic(container.modifiers) && container.paramTypeNames[containerMarks] == "java.util.List")
        index("container.marks", containerMarks)

        val shared = requireNotNull(bridge.getClassData(out.getValue("class:shared")))
        val marksField = base.getValue("field:app.zhendong.reamicro.ui.reader.components.ReaderSharedState#marks")
        out["field:shared.marks"] = marksField
        val constructor = shared.methods.filter { it.isConstructor && it.paramCount == 4 &&
            it.paramTypeNames.sorted() == listOf("long", "java.util.List", "java.util.List", "java.lang.String").sorted() }.one("shared state constructor")
        out["constructor:shared"] = constructor.descriptor
        val sharedIndex = (flow.writes(constructor.descriptor).filter {
            it.field == marksField && it.receiver == HostDexCalls.Parameter(0)
        }.single().value as HostDexCalls.Parameter).index - 1
        check(constructor.paramTypeNames[sharedIndex] == "java.util.List")
        index("shared.marks", sharedIndex)

        val overlay = known("org.epub.ui.ContentKt", "resolveMarkOverlays-xwkQ0AY")
        method("overlay", overlay)
        index("overlay.marks", marksArgument(overlay, "resolved"))
        for ((role, type) in listOf("dom" to typeName("dom"), "window" to typeName("window"), "length" to "int")) {
            index("overlay.$role", overlay.paramTypeNames.indices.single { overlay.paramTypeNames[it] == type })
        }
        val content = known("org.epub.ui.ContentKt", "Content")
        method("content", content)
        index("content.dom", content.paramTypeNames.indices.single { content.paramTypeNames[it] == typeName("dom") })
        val composerIndex = content.paramTypeNames.indices.single { content.paramTypeNames[it] == typeName("composer") }
        index("content.composer", composerIndex)
        check(content.paramTypeNames.getOrNull(composerIndex + 1) == "int")
        val consume = known("androidx.compose.runtime.Composer", "consume")
        method("consume", consume)
        val local = flow.calls(content.descriptor).filter { it.descriptor == overlay.descriptor }.mapNotNull { call ->
            (call.arguments.getOrNull(out.getValue("value:overlay.marks").toInt()) as? HostDexCalls.Call)
                ?.takeIf { it.descriptor == consume.descriptor }
                ?.arguments?.getOrNull(1) as? HostDexCalls.Field
        }.distinct().single()
        out["field:localMarks"] = local.descriptor

        val composer = requireNotNull(bridge.getClassData(out.getValue("class:composer")))
        val endScope = content.invokes.filter { m -> m.className == composer.name && !Modifier.isStatic(m.modifiers) && m.paramCount == 0 &&
            m.invokes.any { it.descriptor == "Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;" } }.one("end restart group")
        val currentScope = composer.methods.filter { m -> !Modifier.isStatic(m.modifiers) && m.paramCount == 0 && m.returnTypeName == endScope.returnTypeName &&
            m.invokes.any { it.descriptor == "Ljava/util/ArrayList;->get(I)Ljava/lang/Object;" } &&
            m.invokes.none { it.descriptor == "Ljava/util/ArrayList;->remove(I)Ljava/lang/Object;" } }.one("current recompose scope")
        method("scope", currentScope)
        return out
    }
}
