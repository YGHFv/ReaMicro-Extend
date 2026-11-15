package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Home and preferences are discovered from the installed app's callers and property dataflow. */
internal object HostHomeQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Home $role is missing or ambiguous")

    class Discovery(private val bridge: DexKitBridge, apk: String, private val base: Map<String, String>,
        private val ui: Map<String, String>) {
        private val flow by lazy { HostDexCalls(apk) }
        private fun type(logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
        private fun method(logical: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$logical#$name")))
        private fun anchor(vararg strings: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
        private fun uiMethod(name: String) = requireNotNull(bridge.getMethodData(ui.getValue("method:$name")))
        private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
            interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true
        private val home by lazy { type(P + "ui.home.HomeViewModel") }
        private val session by lazy { type(P + "repository.core.Session") }
        private val shelf by lazy { type(P + "repository.BookshelfRepository") }
        private val composer by lazy { type("androidx.compose.runtime.Composer") }
        private val screen by lazy { method(P + "ui.home.HomeScreenKt", "HomeScreen") }

        fun query(group: String): Map<String, String> = when (group) {
            "preferences" -> preferences()
            "bookshelf" -> bookshelf()
            "header" -> header()
            "merchant" -> merchant()
            else -> error("Unknown home group: $group")
        }
        private fun merchant(): Map<String, String> = buildMap {
            val owner = P + "ui.shrine.components.TravelingMerchantSheetKt"
            val trip = type(P + "data.res.community.TravelingMerchantTrip")
            val cards = method(owner, "MerchantTripBusinessCards")
            val row = method(owner, "MerchantLoadoutRow")
            val end = base.getValue("field:${P}data.res.community.TravelingMerchantTrip#endTime")
            fun contains(value: HostDexCalls.Value, text: String): Boolean = value == HostDexCalls.Text(text) ||
                value is HostDexCalls.Call && value.arguments.any { contains(it, text) }
            val call = flow.calls(cards.descriptor).filter { it.descriptor == row.descriptor }.single()
            val duration = call.arguments.indices.single { contains(call.arguments[it], "约 ") && contains(call.arguments[it], " 个月") }
            check(row.paramTypeNames[duration] == "java.lang.String")
            val composerIndex = row.paramTypeNames.indices.single { row.paramTypeNames[it] == composer.name }
            check(duration < composerIndex && row.paramTypeNames.drop(composerIndex + 1) == listOf("int", "int"))
            put("class:trip", trip.descriptor); put("class:composer", composer.descriptor)
            put("method:cards", cards.descriptor); put("method:row", row.descriptor); put("field:endTime", end)
            put("value:trip", cards.paramTypeNames.indices.single { cards.paramTypeNames[it] == trip.name }.toString())
            put("value:duration", duration.toString()); put("value:changed", (composerIndex + 1).toString())
            put("value:defaults", (composerIndex + 2).toString())
        }

        private fun preferences(): Map<String, String> = buildMap {
            val key = type("ReaMicroHost.PreferenceKey")
            val continuation = type("kotlin.coroutines.Continuation")
            val first = anchor("Expected at least one element").filter {
                Modifier.isStatic(it.modifiers) && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" &&
                    it.paramTypes.last().isA(continuation)
            }.one("first flow value")
            val flowType = first.paramTypes.first()
            val stores = session.fields.filter { !Modifier.isStatic(it.modifiers) && it.type.methods.any { m ->
                !Modifier.isStatic(m.modifiers) && m.paramCount == 0 && m.returnTypeName == flowType.name
            } }
            val store = stores.single()
            val data = store.type.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                it.returnTypeName == flowType.name }.one("DataStore data")
            val read = session.methods.flatMap { it.invokes }.filter {
                !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(key.name) && it.returnTypeName == "java.lang.Object"
            }.one("preference lookup")
            val update = session.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 3 &&
                it.paramTypeNames.take(2) == listOf(key.name, "java.lang.Object") && it.paramTypes.last().isA(continuation) &&
                it.returnTypeName == "java.lang.Object" }.one("preference update")
            check(update.usingFields.any { it.field.descriptor == store.descriptor })
            val constructor = key.methods.filter { it.isConstructor && it.paramTypeNames == listOf("java.lang.String") }.one("preference key")
            val keyName = key.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }
            check(flow.writes(constructor.descriptor).any { it.field == keyName.descriptor && it.receiver == HostDexCalls.Parameter(0) &&
                it.value == HostDexCalls.Parameter(1) })
            put("class:session", session.descriptor); put("class:key", key.descriptor)
            put("class:store", store.type.descriptor); put("class:flow", flowType.descriptor)
            put("class:preferences", requireNotNull(read.declaredClass).descriptor)
            put("class:continuation", continuation.descriptor)
            put("constructor:key", constructor.descriptor); put("field:key.name", keyName.descriptor)
            put("field:store", store.descriptor)
            put("field:token", session.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }.descriptor)
            put("method:data", data.descriptor); put("method:first", first.descriptor)
            put("method:read", read.descriptor); put("method:update", update.descriptor)
        }

        private fun bookshelf(): Map<String, String> = buildMap {
            val book = type(P + "data.db.entity.Book")
            val dao = type(P + "data.db.dao.BookDao")
            val storage = type(P + "arch.fs.UserStorage")
            val state = type(P + "ui.home.HomeUiState")
            val pager = type("androidx.compose.foundation.pager.PagerState")
            val page = method(P + "ui.home.BookshelfScreenKt", "BookshelfPage-NpZTi58")
            val resolve = method(P + "ui.home.HomeViewModel", "resolveHomePageIndex")
            val first = anchor("Expected at least one element").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 2 }.one("flow first")
            val flowType = first.paramTypes.first()
            val initialized = resolve.usingFields.map { it.field }.filter {
                it.declaredClassName == home.name && it.typeName == "boolean" && !Modifier.isStatic(it.modifiers)
            }.distinctBy { it.descriptor }.single()
            for ((role, value) in listOf("home" to home, "shelf" to shelf, "session" to session, "dao" to dao,
                "storage" to storage, "state" to state, "book" to book, "pager" to pager, "composer" to composer)) put("class:$role", value.descriptor)
            for ((role, owner, value) in listOf(Triple("home.repository", home, shelf), Triple("shelf.session", shelf, session),
                Triple("shelf.dao", shelf, dao), Triple("shelf.storage", shelf, storage))) {
                put("field:$role", owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == value.name }.descriptor)
            }
            val userBooks = method(P + "arch.fs.UserStorage", "userBooksDir")
            val uid = storage.fields.single { it.typeName == "java.lang.Long" && !Modifier.isStatic(it.modifiers) }
            check(uid.readers.any { it.declaredClassName == storage.name && it.usingStrings.contains("books") &&
                it.invokes.any { call -> call.descriptor == userBooks.descriptor || call.paramTypeNames == listOf("java.lang.String", "java.lang.Long") &&
                    call.declaredClassName == storage.name } })
            put("field:storage.uid", uid.descriptor)
            put("field:home.initialized", initialized.descriptor)
            put("field:home.reading", screen.usingFields.map { it.field }.filter { it.declaredClassName == home.name &&
                !Modifier.isStatic(it.modifiers) && it.type.isA(flowType) }.distinctBy { it.descriptor }.single().descriptor)
            put("field:state.groups", base.getValue("field:${P}ui.home.HomeUiState#bookshelfGroups"))
            for (name in listOf("id", "uid", "group", "title")) put("field:book.$name", base.getValue("field:${P}data.db.entity.Book#$name"))
            put("method:screen", screen.descriptor); put("method:page", page.descriptor)
            put("method:resolve", resolve.descriptor)
            put("method:current", method(P + "arch.MVIViewModel", "getCurrentState").descriptor)
            put("method:books", method(P + "data.db.dao.BookDao", "listByUid").descriptor)
            put("value:screen.home", screen.paramTypeNames.indices.single { screen.paramTypeNames[it] == home.name }.toString())
            put("value:page.groups", page.paramTypeNames.indices.single { page.paramTypeNames[it] == "java.util.List" }.toString())
            val factory = page.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnType?.isA(pager) == true &&
                composer.name in it.paramTypeNames }.one("remember shelf pager")
            put("method:pagerFactory", factory.descriptor)
            put("method:pagerCurrent", page.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == pager.name &&
                it.paramCount == 0 && it.returnTypeName == "int" }.one("current shelf page").descriptor)
            resolve.callers.distinctBy { it.descriptor }.forEachIndexed { index, caller -> put("method:resolveCaller.$index", caller.descriptor) }
            check(keys.any { it.startsWith("method:resolveCaller.") })
            val mutable = requireNotNull(anchor("MutableState(value=").one("mutable state").declaredClass)
            put("class:mutable", mutable.descriptor)
            for ((role, args, result) in listOf(Triple("stateGet", emptyList<String>(), "java.lang.Object"),
                Triple("stateSet", listOf("java.lang.Object"), "void"))) {
                put("method:$role", mutable.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
                    it.paramTypeNames == args && it.returnTypeName == result }.one(role).descriptor)
            }
        }

        private fun header(): Map<String, String> = buildMap {
            val modifier = type("androidx.compose.ui.Modifier")
            val vector = requireNotNull(anchor("ImageVector.Builder is single use, create a new instance to create a new ImageVector")
                .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void" }
                .one("vector build result").returnType)
            val parent = anchor("Filled.Grain", "Outlined.TaskAlt").filter { Modifier.isStatic(it.modifiers) &&
                it.returnTypeName == "void" && composer.name in it.paramTypeNames }.one("shelf header")
            val caller = parent.callers.filter { candidate -> screen.invokes.any { it.descriptor == candidate.descriptor } }.one("home header caller")
            val syncType = type("ReaMicroShelf233.SyncState")
            val lanType = type("ReaMicroShelf233.LanState")
            val sync = parent.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames.containsAll(listOf(syncType.name, lanType.name, composer.name, "float")) }.one("sync indicator")
            val parentCalls = flow.calls(caller.descriptor).filter { it.descriptor == parent.descriptor }
            val availableField = base.getValue("field:${P}ui.home.HomeUiState#hasBooks")
            val selectedField = base.getValue("field:${P}ui.home.BookshelfMultiSelectUiState#enabled")
            fun slot(role: String, predicate: (HostDexCalls.Value) -> Boolean) {
                val index = parent.paramTypeNames.indices.filter { parent.paramTypeNames[it] == "boolean" &&
                    parentCalls.isNotEmpty() && parentCalls.all { call -> predicate(call.arguments[it]) } }.single()
                put("value:header.$role", index.toString())
            }
            slot("selected") { it == HostDexCalls.Field(selectedField) }
            slot("visible") { it is HostDexCalls.Call && it.arguments.contains(HostDexCalls.Text("书架")) }
            slot("available") { source -> source is HostDexCalls.Parameter &&
                flow.calls(screen.descriptor).filter { it.descriptor == caller.descriptor }.let { calls ->
                    calls.isNotEmpty() && calls.all { it.arguments.getOrNull(source.index) == HostDexCalls.Field(availableField) }
                } }
            put("class:composer", composer.descriptor); put("class:modifier", modifier.descriptor); put("class:vector", vector.descriptor)
            put("method:header", parent.descriptor); put("method:sync", sync.descriptor)
            for ((role, target) in listOf("header" to parent, "sync" to sync)) {
                put("value:$role.composer", target.paramTypeNames.indices.single { target.paramTypeNames[it] == composer.name }.toString())
            }
            val alpha = sync.paramTypeNames.indices.single { sync.paramTypeNames[it] == "float" }
            put("value:sync.alpha", alpha.toString())
            val syncCalls = flow.calls(sync.descriptor)
            val opacity = sync.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(modifier.name, "float") &&
                it.returnTypeName == modifier.name && syncCalls.any { call -> call.descriptor == it.descriptor &&
                    call.arguments.getOrNull(1) == HostDexCalls.Parameter(alpha) } }.one("sync opacity")
            put("method:opacity", opacity.descriptor)
            val dp = uiMethod("dpInt")
            val size = sync.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(modifier.name, "float") &&
                it.returnTypeName == modifier.name && parent.invokes.any { p -> p.descriptor == it.descriptor } &&
                syncCalls.any { call -> call.descriptor == it.descriptor && call.arguments.getOrNull(1) ==
                    HostDexCalls.Call(dp.descriptor, listOf(HostDexCalls.Literal(36))) } }.one("square header button")
            val clip = uiMethod("clip")
            val circle = syncCalls.filter { it.descriptor == clip.descriptor }.map { it.arguments[1] as HostDexCalls.Field }.distinct().single()
            val circleField = requireNotNull(bridge.getFieldData(circle.descriptor))
            check(Modifier.isStatic(circleField.modifiers) && circleField.type.isA(clip.paramTypes[1]))
            put("field:circle", circle.descriptor)
            put("method:size", size.descriptor); put("method:clip", clip.descriptor); put("method:dp", dp.descriptor)
            put("method:materialize", ui.getValue("method:materialize"))
            put("method:color", ui.getValue("method:onBackgroundVariant"))
            put("method:then", base.getValue("method:androidx.compose.ui.Modifier#then"))
            val icon = parent.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(vector.name, "java.lang.String", modifier.name, "long", composer.name, "int", "int") }.one("vector icon")
            put("method:icon", icon.descriptor)
            val allocation = flow.calls(parent.descriptor).filter { call -> call.descriptor.contains("-><init>(") &&
                call.arguments.contains(HostDexCalls.Text("Outlined.TaskAlt")) }.map { it.arguments.first() }.distinct().single()
            val selected = flow.writes(parent.descriptor).filter { write -> write.receiver == null &&
                (write.value as? HostDexCalls.Call)?.let { call -> call.arguments.firstOrNull() == allocation &&
                    bridge.getMethodData(call.descriptor)?.returnTypeName == vector.name } == true }.map { it.field }.distinct().single()
            put("field:selectionIcon", selected)
            put("method:skip", composer.methods.filter { it.paramCount == 0 && it.returnTypeName == "void" &&
                "No nodes can be emitted before calling skipAndEndGroup" in it.usingStrings &&
                parent.invokes.any { call -> call.descriptor == it.descriptor } }.one("skip group").descriptor)
        }
    }
}
