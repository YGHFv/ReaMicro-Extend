package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostBookUiQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Book UI $role is missing or ambiguous")

    class Discovery(private val bridge: DexKitBridge, private val apk: String, private val base: Map<String, String>) {
        private fun type(name: String) = requireNotNull(bridge.getClassData(base.getValue("class:$name")))
        private fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        private fun anchor(value: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(value)))
        private val flow by lazy { HostDexCalls(apk) }
        fun query(group: String): Map<String, String> = when (group) {
            "export" -> export()
            "imageSheet" -> imageSheet()
            "metadata" -> metadata()
            "backup" -> backup()
            "syncUi" -> syncUi()
            "fade" -> fade()
            "localSheet" -> localSheet()
            else -> error("Unknown book UI group: $group")
        }
        private fun localSheet(): Map<String, String> = buildMap {
            val owner = P + "ui.home.components.BookLocalSheetKt"
            val root = known(owner, "BookLocalSheet")
            val title = known(owner, "BookInfoTitle")
            val cell = known(owner, "BookMetaActionRow")
            val book = type(P + "data.db.entity.Book")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function0")
            val vector = cell.paramTypes[1]
            val sheet = requireNotNull(root.declaredClass)
            val group = sheet.methods.filter { it.isMethod && Modifier.isStatic(it.modifiers) &&
                it.returnTypeName == "void" && it.paramTypeNames == listOf(vector.name, "java.lang.String", function.name, composer.name, "int") &&
                "分组" in it.usingStrings }.one("local sheet group row")
            val info = sheet.methods.filter { it.isMethod && Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(book.name, composer.name, "int") && it.returnTypeName == "void" &&
                it.invokes.any { call -> call.descriptor == title.descriptor } }.one("local sheet book information")
            val content = group.callers.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { call -> call.descriptor == info.descriptor } }.one("deferred local sheet content")
            val contentClass = requireNotNull(content.declaredClass)
            check(root.invokes.any { it.isConstructor && it.declaredClassName == contentClass.name && book.name in it.paramTypeNames })
            val sourceField = base.getValue("field:${P}data.db.entity.Book#subtitle")
            val titleArgs = flow.calls(info.descriptor).filter { it.descriptor == title.descriptor }
                .map { it.arguments }.distinct().single()
            val subtitle = titleArgs.indices.single { titleArgs[it] == HostDexCalls.Field(sourceField) }
            check(title.paramTypeNames[subtitle] == "java.lang.String")
            val bookCapture = flow.calls(content.descriptor).filter { it.descriptor == info.descriptor }
                .mapNotNull { it.arguments.firstOrNull() as? HostDexCalls.Field }.map { it.descriptor }.distinct().single()
            check(contentClass.fields.any { it.descriptor == bookCapture && !Modifier.isStatic(it.modifiers) })
            val clickConstructors = content.invokes.filter { it.isConstructor && book.name in it.paramTypeNames &&
                it.declaredClass?.interfaces.orEmpty().any { iface -> iface.name == function.name }
            }.distinctBy { it.descriptor }.sortedBy { it.descriptor }
            check(clickConstructors.isNotEmpty())
            val clicks = clickConstructors.map { requireNotNull(it.declaredClass) }.distinctBy { it.name }.sortedBy { it.name }
            clicks.forEachIndexed { index, click ->
                val field = click.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == book.name }
                clickConstructors.filter { it.declaredClassName == click.name }.forEach { ctor ->
                    val parameter = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == book.name }
                    check(flow.writes(ctor.descriptor).any { it.field == field.descriptor &&
                        it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(parameter + 1) })
                }
                put("class:click.$index", click.descriptor); put("field:clickBook.$index", field.descriptor)
            }
            clickConstructors.forEachIndexed { index, ctor -> put("constructor:click.$index", ctor.descriptor) }
            put("value:clicks", clicks.size.toString()); put("value:clickConstructors", clickConstructors.size.toString())
            put("value:subtitle", subtitle.toString())
            put("value:changed", title.paramTypeNames.indices.single { it > subtitle && it > title.paramTypeNames.indexOf(composer.name) && title.paramTypeNames[it] == "int" }.toString())
            put("field:contentBook", bookCapture)
            for ((role, type) in listOf("book" to book, "composer" to composer, "function0" to function,
                "vector" to vector, "modifier" to cell.paramTypes[0], "content" to contentClass, "sheet" to sheet)) put("class:$role", type.descriptor)
            for ((role, method) in listOf("root" to root, "title" to title, "cell" to cell, "group" to group,
                "info" to info, "content" to content)) put("method:$role", method.descriptor)
        }
        private fun fade(): Map<String, String> = buildMap {
            fun save(role: String, method: MethodData) {
                put("${if (method.isConstructor) "constructor" else "method"}:$role", method.descriptor)
            }
            val request = requireNotNull(anchor("ImageRequest(context=").filter { it.name == "toString" }.one("image request").declaredClass)
            val factoryCtor = anchor("durationMillis must be > 0.").filter { it.isConstructor && it.paramTypeNames == listOf("int") }
                .one("crossfade factory")
            val factory = requireNotNull(factoryCtor.declaredClass)
            val create = factory.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 2 &&
                it.returnTypeName != "void" }.one("crossfade create")
            val protocol = factory.interfaces.single { iface -> iface.methods.any {
                it.name == create.name && it.paramTypeNames == create.paramTypeNames && it.returnTypeName == create.returnTypeName
            } }
            val immediate = protocol.methods.filter { it.name == create.name && it.paramTypeNames == create.paramTypeNames &&
                it.returnTypeName == create.returnTypeName }.one("transition factory protocol")
            val noFade = protocol.fields.single { field -> Modifier.isStatic(field.modifiers) &&
                field.type?.interfaces.orEmpty().any { it.name == protocol.name } }
            val result = create.paramTypes[1]
            val requestFromResult = result.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == request.name }.one("transition result request")
            val painter = requireNotNull(anchor("AsyncImagePainter.onRemembered").one("image painter").declaredClass)
            val update = painter.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 1 && it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == immediate.descriptor }
            }.one("painter transition decision")
            val data = request.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object" }
            val keys = request.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }
            check(keys.size == 2)
            keys.sortedBy { it.descriptor }.forEachIndexed { index, field -> put("field:key.$index", field.descriptor) }
            val publisher = type("ReaMicroHost.BookPublisherRecord")
            val constructors = publisher.methods.filter { it.isConstructor }.sortedBy { it.descriptor }
            check(constructors.isNotEmpty())
            constructors.forEachIndexed { index, method -> save("publisher.$index", method) }
            put("field:publisherCover", base.getValue("field:ReaMicroHost.BookPublisherRecord#cover"))
            val oldCover = known(P + "ui.home.HomeScreenKt", "OldBookCover-ziNgDLE")
            check(Modifier.isStatic(oldCover.modifiers) && oldCover.paramTypeNames.count { it == "java.lang.String" } == 1)
            put("value:coverSource", oldCover.paramTypeNames.indexOf("java.lang.String").toString())
            for ((role, clazz) in listOf("request" to request, "factory" to factory, "protocol" to protocol,
                "result" to result, "painter" to painter, "publisher" to publisher)) put("class:$role", clazz.descriptor)
            for ((role, method) in listOf("create" to create, "immediate" to immediate, "request" to requestFromResult,
                "update" to update, "oldCover" to oldCover)) save(role, method)
            put("field:noFade", noFade.descriptor); put("field:data", data.descriptor)
        }
        private fun syncUi(): Map<String, String> = buildMap {
            val composer = type("androidx.compose.runtime.Composer")
            val state = type("androidx.compose.runtime.MutableState")
            val function0 = type("kotlin.jvm.functions.Function0")
            val function1 = type("kotlin.jvm.functions.Function1")
            val function2 = type("kotlin.jvm.functions.Function2")
            val root = known(P + "ui.backup.components.AuthCardKt", "AuthCard")
            val card = known(P + "ui.backup.components.AuthCardKt", "DriveAuthCard")
            val available = requireNotNull(root.declaredClass).methods.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.util.List", function1.name, composer.name, "int") &&
                it.returnTypeName == "void" }.one("sync available providers")
            val ctor = root.invokes.filter { it.isConstructor && it.paramCount == 7 &&
                it.paramTypeNames.count { t -> t == "java.lang.Integer" } == 1 &&
                it.paramTypeNames.count { t -> t == state.name } == 3 &&
                it.paramTypeNames.count { t -> t == function1.name } == 2 &&
                it.paramTypeNames.count { t -> t == function2.name } == 1 }.one("sync content constructor")
            val owner = requireNotNull(ctor.declaredClass)
            val content = owner.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { m -> m.descriptor == card.descriptor } &&
                it.invokes.any { m -> m.descriptor == available.descriptor } }.one("sync content")
            val writes = flow.writes(ctor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
            val captured = ctor.paramTypeNames.indices.associateWith { slot ->
                writes.single { it.value == HostDexCalls.Parameter(slot + 1) }.field.also {
                    check(bridge.getFieldData(it)?.declaredClassName == owner.name)
                    put("field:argument.$slot", it)
                }
            }
            val tag = writes.single { it.value is HostDexCalls.Literal }
            check(bridge.getFieldData(tag.field)?.let { it.declaredClassName == owner.name && it.typeName in listOf("byte", "int") } == true)
            check(owner.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet() == captured.values.toSet() + tag.field)
            put("field:tag", tag.field); put("value:tag", (tag.value as HostDexCalls.Literal).value.toString())
            val calls = flow.calls(content.descriptor)
            val authorize = calls.filter { it.descriptor == available.descriptor }
                .map { (it.arguments[1] as HostDexCalls.Field).descriptor }.distinct().single()
            val pick = captured.getValue(ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == function2.name })
            val setup = captured.filterKeys { ctor.paramTypeNames[it] == function1.name }.values.single { it != authorize }
            val provider = captured.getValue(ctor.paramTypeNames.indexOf("java.lang.Integer"))
            val rootArgs = flow.calls(root.descriptor).filter { it.descriptor == ctor.descriptor }
                .map { it.arguments }.distinct().single()
            for ((role, field) in mapOf("authorize" to authorize, "setup" to setup, "pick" to pick)) {
                val slot = captured.entries.single { it.value == field }.key
                val parameter = (rootArgs[slot + 1] as HostDexCalls.Parameter).index
                check(root.paramTypeNames[parameter] == ctor.paramTypeNames[slot])
                put("value:root.$role", parameter.toString()); put("field:$role", field)
            }
            put("field:provider", provider)
            put("value:root.bookId", root.paramTypeNames.indices.single { root.paramTypeNames[it] == "long" }.toString())
            val casts = flow.casts(content.descriptor)
            for ((role, name) in listOf("running" to "Ljava/lang/Boolean;", "composer" to composer.descriptor)) {
                val slot = casts.filter { it.type == name && it.value is HostDexCalls.Parameter }
                    .map { (it.value as HostDexCalls.Parameter).index - 1 }.distinct().single()
                check(slot in 0..3); put("value:content.$role", slot.toString())
            }
            val flags = casts.filter { it.type == "Ljava/lang/Integer;" && it.value is HostDexCalls.Parameter }
                .map { (it.value as HostDexCalls.Parameter).index - 1 }
                .filter { it != getValue("value:content.running").toInt() }.distinct().single()
            check(flags in 0..3 && flags != getValue("value:content.composer").toInt())
            put("value:content.flags", flags.toString())
            // The actual 115 State is identified by the value cast after State.getValue.
            val auth = type(HostStorageQueries.LOCAL_AUTH)
            val authReads = casts.filter { it.type == auth.descriptor }.mapNotNull { it.value as? HostDexCalls.Call }
            val authState = authReads.mapNotNull { it.arguments.singleOrNull() as? HostDexCalls.Field }
                .map { it.descriptor }.filter { it in captured.values }.distinct().single()
            check(ctor.paramTypeNames[captured.entries.single { it.value == authState }.key] == state.name)
            put("field:yun115State", authState)
            val backupCard = known(P + "ui.backup.components.DriveCardKt", "DriveBackupCard")
            val providerId = flow.equalityTexts(backupCard.descriptor).filter {
                it.value == HostDexCalls.Parameter(0) && it.text == "115网盘"
            }.map { it.literal }.distinct().single()
            put("value:provider.yun115", providerId.toString())
            val rowCtor = available.invokes.filter { it.isConstructor &&
                it.paramTypeNames == listOf(function1.name, "int", "byte") &&
                it.declaredClass?.interfaces?.any { t -> t.name == function0.name } == true }.one("sync provider click")
            val rowArgs = flow.calls(available.descriptor).filter { it.descriptor == rowCtor.descriptor }.map { it.arguments }.distinct().single()
            val rowWrites = flow.writes(rowCtor.descriptor)
            for ((role, parameter) in listOf("callback" to 1, "provider" to 2, "tag" to 3)) {
                put("field:row.$role", rowWrites.single { it.receiver == HostDexCalls.Parameter(0) &&
                    it.value == HostDexCalls.Parameter(parameter) }.field)
            }
            put("value:row.tag", (rowArgs[3] as HostDexCalls.Literal).value.toString())
            val clickable = available.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 6 &&
                it.paramTypeNames[1] == "boolean" && it.paramTypeNames[2] == "java.lang.String" &&
                it.paramTypeNames[4] == function0.name && it.paramTypeNames[5] == "int" &&
                it.returnTypeName == it.paramTypeNames[0] }.one("sync provider clickable")
            val image = available.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(card.paramTypeNames[0], "java.lang.String", clickable.paramTypeNames[0], composer.name, "int", "int") &&
                it.returnTypeName == "void" }.one("sync provider image")
            for ((role, cls) in listOf("composer" to composer, "state" to state, "function0" to function0,
                "function1" to function1, "function2" to function2, "content" to owner, "auth" to auth,
                "row" to requireNotNull(rowCtor.declaredClass), "vector" to card.paramTypes[0], "modifier" to clickable.paramTypes[0]))
                put("class:$role", cls.descriptor)
            for ((role, method) in listOf("root" to root, "card" to card, "available" to available,
                "content" to content, "rowClickable" to clickable, "image" to image)) put("method:$role", method.descriptor)
            for ((role, function, arity) in listOf(Triple("invoke0", function0, 0), Triple("invoke1", function1, 1), Triple("invoke2", function2, 2))) {
                put("method:$role", function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == List(arity) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one(role).descriptor)
            }
            put("constructor:content", ctor.descriptor); put("constructor:row", rowCtor.descriptor)
        }
        private fun backup(): Map<String, String> = buildMap {
            val book = type(P + "data.db.entity.Book")
            val cloud = type(P + "data.storage.CloudBook")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function1")
            val storage = type(P + "repository.storage.CloudStorageRepository")
            val receiver = type(P + "arch.MVIViewModel")
            val state = requireNotNull(anchor("BookBackupUIState(book=").filter { it.name == "toString" &&
                it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("backup state").declaredClass)
            val stateFactory = state.methods.filter { it.isConstructor }.flatMap { it.callers }.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                it.declaredClass?.superClass?.name == receiver.name }.one("backup state factory")
            val vm = requireNotNull(stateFactory.declaredClass)
            val vmCtor = vm.methods.filter { it.isConstructor && it.paramCount == 3 &&
                it.paramTypeNames.count { t -> t == "long" } == 1 && it.paramTypeNames.count { t -> t == storage.name } == 1 }.one("backup view model constructor")
            val vmWrites = flow.writes(vmCtor.descriptor)
            for ((role, name) in listOf("bookId" to "long", "storage" to storage.name)) {
                val slot = vmCtor.paramTypeNames.indices.single { vmCtor.paramTypeNames[it] == name }
                val field = vmWrites.single { it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1) }.field
                check(bridge.getFieldData(field)?.let { it.declaredClassName == vm.name && it.typeName == name } == true)
                put("field:vm.$role", field); put("value:vm.$role", slot.toString())
            }
            val records = HostRecordQueries.query(apk, mapOf("class:state" to state.descriptor))
            val properties = records.filterKeys { it.startsWith("field:state#") }.mapKeys { it.key.substringAfter('#') }
            check(properties.keys == setOf("book", "backup", "deleting"))
            check(properties.values.toSet() == state.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet())
            val stateCtor = state.methods.filter { it.isConstructor && it.paramCount == properties.size }.one("backup state constructor")
            val writes = flow.writes(stateCtor.descriptor)
            properties.forEach { (name, descriptor) ->
                val write = writes.single { it.field == descriptor && it.receiver == HostDexCalls.Parameter(0) }
                val slot = (write.value as HostDexCalls.Parameter).index - 1
                check(stateCtor.paramTypeNames[slot] == requireNotNull(bridge.getFieldData(descriptor)).typeName)
                put("field:state.$name", descriptor); put("value:state.$name", slot.toString())
            }
            check(properties.keys.map { getValue("value:state.$it").toInt() }.toSet() == (0..2).toSet())
            val auth = known(P + "ui.backup.components.AuthCardKt", "AuthCard")
            val content = auth.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 9 &&
                it.paramTypeNames.take(3) == listOf(book.name, cloud.name, "boolean") &&
                it.paramTypeNames.subList(3, 7) == List(4) { function.name } &&
                it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") }.one("backup content")
            val modifier = type("androidx.compose.ui.Modifier")
            val materialize = content.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(composer.name, modifier.name) && it.returnTypeName == modifier.name
            }.one("backup root materialization")
            val root = flow.calls(content.descriptor).filter { it.descriptor == materialize.descriptor }
                .map { (it.arguments[1] as HostDexCalls.Field).descriptor }.distinct().single()
            check(bridge.getFieldData(root)?.let { Modifier.isStatic(it.modifiers) } == true)
            val scroll = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(3).returnType(modifier.name))).filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames[0] == modifier.name && it.paramTypeNames[2] == "int" &&
                it.paramTypeNames[1] != modifier.name && it.paramTypes[1].methods.any { m -> m.isConstructor && m.paramTypeNames == listOf("int") } &&
                it.invokes.any { call -> call.paramTypeNames == listOf(modifier.name, it.paramTypeNames[1], "boolean", "boolean") &&
                    call.returnTypeName == modifier.name && Modifier.isStatic(call.modifiers) }
            }.one("vertical scroll default")
            val stateType = scroll.paramTypes[1]
            val scrollImpl = scroll.invokes.filter { it.paramTypeNames == listOf(modifier.name, stateType.name, "boolean", "boolean") &&
                it.returnTypeName == modifier.name && Modifier.isStatic(it.modifiers) }.one("native scroll modifier")
            check(scrollImpl.invokes.any { it.isConstructor && it.paramTypeNames == listOf(stateType.name, "boolean") })
            fun enabled(mask: Long): Boolean = flow.calls(scroll.descriptor, mapOf(2 to mask)).single { it.descriptor == scrollImpl.descriptor }
                .arguments == listOf(HostDexCalls.Parameter(0), HostDexCalls.Parameter(1), HostDexCalls.Literal(1), HostDexCalls.Literal(1))
            check(!enabled(0))
            val mask = scroll.usingNumbers.map { it.longValue() }.filter { it > 0 && it <= Int.MAX_VALUE && (it and (it - 1)) == 0L }
                .distinct().filter(::enabled).single()
            val remember = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramTypes(composer.name).returnType(stateType.name))).filter { Modifier.isStatic(it.modifiers) }.one("remember native scroll state")
            put("class:modifier", modifier.descriptor); put("class:scrollState", stateType.descriptor)
            put("field:rootModifier", root); put("method:materialize", materialize.descriptor)
            put("method:rememberScroll", remember.descriptor); put("method:verticalScroll", scroll.descriptor)
            put("value:scrollMask", mask.toString())
            val screen = content.callers.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object" && flow.casts(it.descriptor).any { cast -> cast.type == vm.descriptor } }.one("backup screen callback")
            val loading = screen.invokes.filter {
                Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                    it.paramTypeNames == listOf("int", composer.name) && it.invokes.any { draw ->
                        Modifier.isStatic(draw.modifiers) && draw.returnTypeName == "void" &&
                            draw.paramTypeNames == listOf(modifier.name, "long", "float", "long", "int", "float",
                                composer.name, "int", "int")
                    }
            }.one("backup loading indicator")
            val captured = flow.casts(screen.descriptor).filter { it.type == vm.descriptor }.map { (it.value as HostDexCalls.Field).descriptor }.distinct().single()
            check(bridge.getFieldData(captured)?.declaredClassName == screen.declaredClassName)
            put("field:screen.vm", captured)
            val card = known(P + "ui.backup.components.DriveCardKt", "DriveBackupCard")
            val labels = flow.equalityTexts(card.descriptor).filter { it.value == HostDexCalls.Parameter(0) }
            for ((role, text) in listOf("baidu" to "百度网盘", "yun115" to "115网盘", "aliyun" to "阿里云盘")) {
                put("value:provider.$role", labels.filter { it.text == text }.map { it.literal }.distinct().single().toString())
            }
            val yun115 = card.callers.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames ==
                listOf(cloud.name, "boolean", function.name, composer.name, "int") && flow.calls(it.descriptor).any { call ->
                    call.descriptor == card.descriptor && call.arguments[0] == HostDexCalls.Literal(getValue("value:provider.yun115").toLong())
                } }.one("115 backup wrapper")
            val typeNames = anchor("百度网盘").filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Integer") && it.returnTypeName == "java.lang.String" &&
                it.usingStrings.containsAll(listOf("阿里云盘", "115网盘", "本地存储")) }.distinctBy { it.descriptor }
            check(typeNames.size <= 1)
            typeNames.singleOrNull()?.let { put("method:typeName", it.descriptor) }
            val update = known(P + "arch.MVIViewModel", "updateUiState")
            for ((role, cls) in listOf("book" to book, "cloud" to cloud, "composer" to composer, "function" to function,
                "storage" to storage, "receiver" to receiver, "state" to state, "vm" to vm,
                "screen" to requireNotNull(screen.declaredClass), "vector" to card.paramTypes[1])) put("class:$role", cls.descriptor)
            put("constructor:vm", vmCtor.descriptor); put("constructor:state", stateCtor.descriptor)
            for ((role, m) in listOf("stateFactory" to stateFactory, "content" to content, "screen" to screen,
                "loading" to loading, "card" to card, "yun115" to yun115, "update" to update)) put("method:$role", m.descriptor)
            for (role in listOf("id", "backupType", "backupId")) put("field:book.$role", base.getValue("field:${P}data.db.entity.Book#$role"))
            for (role in listOf("type", "name", "path")) put("field:cloud.$role", base.getValue("field:${P}data.storage.CloudBook#$role"))
        }
        private fun metadata(): Map<String, String> = buildMap {
            val book = type(P + "data.db.entity.Book")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function0")
            val navigationController = requireNotNull(anchor("No destination with route ")
                .one("book navigation controller").declaredClass)
            val navigationActivity = navigationController.fields.filter {
                !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                    it.typeName == "android.app.Activity"
            }.single()
            val navigationConstructor = navigationController.methods.filter {
                it.isConstructor && it.paramTypeNames == listOf("android.content.Context")
            }.one("book navigation owner constructor")
            check(flow.writes(navigationConstructor.descriptor).any {
                it.field == navigationActivity.descriptor && it.receiver == HostDexCalls.Parameter(0)
            })
            put("class:navigationController", navigationController.descriptor)
            put("field:navigation.activity", navigationActivity.descriptor)
            val title = known(P + "ui.home.components.BookOverviewItemsKt", "BookTitleAuthorItem")
            val sync = known(P + "ui.home.components.BookOverviewItemsKt", "BookSyncSizeItem")
            val edit = anchor("Outlined.Edit").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 }.one("edit icon")
            val vector = requireNotNull(edit.returnType)
            val icon = title.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 7 &&
                it.paramTypeNames.first() == vector.name && it.paramTypeNames[3] == "long" &&
                it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int") && it.returnTypeName == "void" }.one("metadata icon")
            // Reuse the complete native top-bar button. Its click indication is inlined
            // in a deferred Function2; it is not the standard Material3 IconButton.
            val top = known(P + "arch.components.AppTopBarKt", "AppTopBar-1YH7lEI")
            val function2 = type("kotlin.jvm.functions.Function2")
            check(Modifier.isStatic(top.modifiers) && top.paramCount == 11 &&
                top.paramTypeNames[3] == vector.name && top.paramTypeNames[4] == function.name &&
                top.paramTypeNames[5] == "long" && top.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int"))
            val extra = top.paramTypes[6]
            val buttonCtor = top.invokes.filter { it.isConstructor &&
                it.paramTypeNames == listOf("byte", "long") + List(3) { "java.lang.Object" } &&
                it.declaredClass?.interfaces.orEmpty().any { iface -> iface.descriptor == function2.descriptor }
            }.one("native top-bar button constructor")
            val button = requireNotNull(buttonCtor.declaredClass)
            val buttonBody = button.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
            }.one("native top-bar button body")
            val defaults = flow.calls(top.descriptor, mapOf(9 to 0L, 10 to 32L))
                .filter { it.descriptor == buttonCtor.descriptor }.distinct().single()
            val tag = (defaults.arguments[1] as? HostDexCalls.Literal)?.value
                ?: error("Native top-bar button branch is unproven")
            check(tag in Byte.MIN_VALUE..Byte.MAX_VALUE)
            fun buttonCapture(slot: Int): String = flow.writes(buttonCtor.descriptor).filter {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot)
            }.map { it.field }.distinct().single()
            val tagField = buttonCapture(1)
            val buttonCalls = flow.callsWithFields(buttonBody.descriptor, mapOf(tagField to tag))
            val nativeIcon = buttonCalls.filter { it.descriptor == icon.descriptor }.single()
            check(nativeIcon.arguments[0] == HostDexCalls.Field(buttonCapture(3)) &&
                nativeIcon.arguments[3] == HostDexCalls.Field(buttonCapture(2)) &&
                nativeIcon.arguments[1] == HostDexCalls.Text(""))
            val casts = flow.casts(buttonBody.descriptor)
            for ((slot, cls) in listOf(3 to vector, 4 to function, 5 to extra))
                check(casts.any { it.type == cls.descriptor && it.value == HostDexCalls.Field(buttonCapture(slot)) })
            val extraInvoke = extra.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
            }.one("native top-bar extra slot")
            check(buttonCalls.any { it.descriptor == extraInvoke.descriptor &&
                it.arguments.first() == HostDexCalls.Field(buttonCapture(5)) })
            val foreground = (defaults.arguments[2] as? HostDexCalls.Field)?.descriptor
                ?: error("Native top-bar foreground is unproven")
            val empty = (defaults.arguments[5] as? HostDexCalls.Field)?.descriptor
                ?: error("Native top-bar default extra slot is unproven")
            check(bridge.getFieldData(foreground)?.typeName == "long" &&
                bridge.getFieldData(foreground)?.declaredClass?.descriptor == type("androidx.compose.material3.ColorScheme").descriptor)
            check(bridge.getFieldData(empty)?.let { field -> Modifier.isStatic(field.modifiers) &&
                (field.typeName == extra.name || field.type?.interfaces.orEmpty().any { it.descriptor == extra.descriptor }) } == true)
            for ((role, cls) in listOf("button" to button, "button.function2" to function2, "button.extra" to extra))
                put("class:$role", cls.descriptor)
            put("constructor:button", buttonCtor.descriptor)
            put("method:button.body", buttonBody.descriptor)
            put("method:button.extraInvoke", extraInvoke.descriptor)
            put("field:button.foreground", foreground)
            put("field:button.empty", empty)
            put("value:button.tag", tag.toString())
            for ((slot, role) in listOf("tag", "color", "vector", "click", "extra").withIndex()) {
                put("field:button.$role", buttonCapture(slot + 1))
                put("value:button.slot.$role", slot.toString())
            }
            val image = sync.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 6 &&
                it.paramTypeNames.take(3) == icon.paramTypeNames.take(3) &&
                it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int") && it.returnTypeName == "void" }.one("storage icon image")
            val backupField = base.getValue("field:${P}data.db.entity.Book#backupType")
            val backupLabels = flow.equalityTexts(sync.descriptor).filter { it.value == HostDexCalls.Field(backupField) }
            for ((role, label) in listOf("baidu" to "百度网盘", "yun115" to "115网盘", "aliyun" to "阿里云盘")) {
                put("value:backup.$role", backupLabels.filter { it.text == label }.map { it.literal }.distinct().single().toString())
            }
            require("本地存储" in sync.usingStrings)
            val repository = type(P + "repository.BookshelfRepository")
            val storage = type(P + "arch.fs.UserStorage")
            val storageField = repository.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == storage.name }.single()
            val books = known(P + "arch.fs.UserStorage", "userBooksDir")
            val path = requireNotNull(books.returnType)
            val toFile = path.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "java.io.File" }.one("book root path conversion")
            val align = type("androidx.compose.ui.text.style.TextAlign")
            val alignName = align.methods.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int") &&
                it.returnTypeName == "java.lang.String" && it.usingStrings.containsAll(listOf("Start", "End", "Unspecified")) }.one("text alignment names")
            val alignConstructor = align.methods.filter { it.isConstructor && it.paramTypeNames == listOf("int") }.one("text alignment box")
            val activity = requireNotNull(bridge.getClassData(P + "MainActivity"))
            val result = activity.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == "onActivityResult" &&
                it.paramTypeNames == listOf("int", "int", "android.content.Intent") && it.returnTypeName == "void" }.one("editor picker result")
            val invoke = function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("metadata callback")
            for ((role, cls) in listOf("book" to book, "composer" to composer, "function" to function, "vector" to vector,
                "scope" to title.paramTypes[0], "repository" to repository, "storage" to storage, "path" to path,
                "align" to align, "modifier" to icon.paramTypes[2], "activity" to activity)) put("class:$role", cls.descriptor)
            for ((role, method) in listOf("title" to title, "sync" to sync, "icon" to icon, "image" to image, "edit" to edit,
                "books" to books, "toFile" to toFile, "alignName" to alignName,
                "activityResult" to result, "invoke" to invoke)) put("method:$role", method.descriptor)
            for ((role, text) in listOf("copy" to "Outlined.ContentCopy", "next" to "AutoMirrored.Filled.NavigateNext",
                "local" to "Colored.AndroidOs", "baidu" to "Colored.BaiduNetdisk", "yun115" to "Colored.Yun115", "aliyun" to "Colored.Aliyun")) {
                val method = anchor(text).filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == vector.name }.one("$role icon")
                put("method:$role", method.descriptor)
            }
            put("constructor:align", alignConstructor.descriptor)
            put("value:align.candidates", flow.literals(alignName.descriptor).filter { it in Int.MIN_VALUE..Int.MAX_VALUE }.joinToString(","))
            put("field:repository.storage", storageField.descriptor)
            repository.methods.filter { it.isConstructor }.sortedBy { it.descriptor }.forEachIndexed { index, ctor ->
                put("constructor:repository.$index", ctor.descriptor)
            }
            for (role in listOf("uid", "uuid", "title", "author", "size", "backupType"))
                put("field:book.$role", base.getValue("field:${P}data.db.entity.Book#$role"))
        }
        private fun imageSheet(): Map<String, String> = buildMap {
            val book = type(P + "data.db.entity.Book")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function0")
            val function1 = requireNotNull(bridge.getClassData("kotlin.jvm.functions.Function1"))
            val sheet = type("androidx.compose.material3.SheetState")
            val continuation = type("kotlin.coroutines.Continuation")
            val context = type("kotlin.coroutines.CoroutineContext")
            val items = P + "ui.home.components.BookOverviewItemsKt"
            val overview = known(items, "BookCoverBannerItem")
            val hide = anchor("Attempted to animate to hidden when skipHiddenState was enabled. Set skipHiddenState to false to use this function.")
                .filter { it.declaredClass?.descriptor == sheet.descriptor && !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == listOf(continuation.name) && it.returnTypeName == "java.lang.Object" }.one("sheet hide")
            val visible = sheet.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "boolean" }.one("sheet visibility")
            val invoke0 = function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("image callback")
            val invoke1 = function1.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("cover update callback")
            for ((role, cls) in listOf("book" to book, "composer" to composer, "function" to function,
                "function1" to function1, "sheet" to sheet, "continuation" to continuation, "context" to context)) put("class:$role", cls.descriptor)
            for ((role, method) in listOf("overview" to overview, "hide" to hide, "visible" to visible,
                "invoke" to invoke0, "update" to invoke1)) put("method:$role", method.descriptor)
            for (role in listOf("uid", "uuid", "title", "author", "publisher", "cover", "uri"))
                put("field:book.$role", base.getValue("field:${P}data.db.entity.Book#$role"))
            for ((role, name) in listOf("cover" to "CoverBottomSheet", "banner" to "BannerBottomSheet")) {
                val entry = known(items, name)
                val callbackCount = entry.paramTypeNames.count { it == function.name }
                require(Modifier.isStatic(entry.modifiers) && entry.paramTypeNames == listOf("boolean") +
                    List(callbackCount) { function.name } + listOf(composer.name, "int"))
                require(callbackCount == if (role == "cover") 4 else 3)
                val constructor = entry.invokes.filter { it.isConstructor && it.paramTypeNames.count { p -> p == function.name } == callbackCount &&
                    sheet.name in it.paramTypeNames && "boolean" in it.paramTypeNames }.one("$role content constructor")
                val owner = requireNotNull(constructor.declaredClass)
                val content = owner.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                    Modifier.isPublic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
                    it.returnTypeName == "java.lang.Object" }.one("$role sheet content")
                val call = flow.calls(entry.descriptor).filter { it.descriptor == constructor.descriptor }.distinct().single()
                val pickSlot = call.arguments.withIndex().filter { it.value == HostDexCalls.Parameter(1) }.single().index
                val stateSlot = constructor.paramTypeNames.indexOf(sheet.name) + 1
                val scopeType = constructor.paramTypes.filter { it.name !in setOf(function.name, sheet.name, "boolean") }.single()
                val scopeSlot = constructor.paramTypeNames.indexOf(scopeType.name) + 1
                val scopeContext = scopeType.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                    it.paramCount == 0 && it.returnTypeName == context.name }.one("$role coroutine context")
                fun captured(slot: Int): String = flow.writes(constructor.descriptor).filter {
                    it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot)
                }.map { it.field }.distinct().single()
                put("class:$role", owner.descriptor)
                put("class:$role.scope", scopeType.descriptor)
                put("method:$role.entry", entry.descriptor)
                put("method:$role.content", content.descriptor)
                put("method:$role.context", scopeContext.descriptor)
                put("constructor:$role", constructor.descriptor)
                put("field:$role.pick", captured(pickSlot))
                put("field:$role.state", captured(stateSlot))
                put("field:$role.scope", captured(scopeSlot))
                put("value:$role.callbacks", callbackCount.toString())
            }
            // These rows are inlined in the native image sheet. Keep that sheet intact and
            // derive its row primitives and arguments for the additional image operations.
            val content = requireNotNull(bridge.getMethodData(getValue("method:cover.content")))
            val image = anchor("Outlined.Image").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 }.one("image menu icon")
            val vector = requireNotNull(image.returnType)
            val icon = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 7 &&
                it.paramTypeNames.first() == vector.name && it.paramTypeNames[3] == "long" &&
                it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int") && it.returnTypeName == "void" }.one("image menu icon renderer")
            val modifier = icon.paramTypes[2]
            val text = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 20 &&
                it.paramTypeNames.take(2) == listOf("java.lang.String", modifier.name) &&
                it.paramTypeNames.takeLast(4) == listOf(composer.name, "int", "int", "int") }.one("image menu text")
            val padding = content.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(modifier.name, "float", "float", "float", "float") &&
                it.returnTypeName == modifier.name }.one("image menu padding")
            val clickable = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 6 &&
                it.paramTypeNames.take(3) == listOf(modifier.name, "boolean", "java.lang.String") &&
                it.paramTypeNames[4] == function.name && it.returnTypeName == modifier.name }.one("image menu click indication")
            val divider = content.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(modifier.name, "long", composer.name, "int", "int") &&
                it.returnTypeName == "void" }.one("image menu divider")
            val endNode = content.invokes.filter { !Modifier.isStatic(it.modifiers) &&
                it.declaredClass?.descriptor == composer.descriptor && it.paramTypeNames == listOf("boolean") &&
                it.returnTypeName == "void" }.one("image menu node end")
            val calls = flow.calls(content.descriptor)
            val pickText = calls.filter { it.descriptor == text.descriptor && it.arguments.first() == HostDexCalls.Text("选取图片") }.single()
            val pickIndex = calls.indexOf(pickText)
            val rowPadding = calls.take(pickIndex).last { it.descriptor == padding.descriptor }
            val click = rowPadding.arguments.first() as? HostDexCalls.Call ?: error("Image menu click is not proven")
            check(click.descriptor == clickable.descriptor)
            fun literal(value: HostDexCalls.Value): Long = (value as? HostDexCalls.Literal)?.value
                ?: error("Image menu argument is not a literal: $value")
            fun dimension(value: HostDexCalls.Value): Int {
                val call = value as? HostDexCalls.Call ?: error("Image menu dimension is not scaled: $value")
                val method = requireNotNull(bridge.getMethodData(call.descriptor))
                check(Modifier.isStatic(method.modifiers) && method.paramTypeNames == listOf("int") && method.returnTypeName == "float")
                return literal(call.arguments.single()).toInt().also { check(it in 0..128) }
            }
            fun capturedField(role: String, value: HostDexCalls.Value) {
                put("field:menu.$role", (value as? HostDexCalls.Field)?.descriptor ?: error("Image menu $role is not a field"))
            }
            for ((role, value) in listOf("start", "top", "end", "bottom").zip(rowPadding.arguments.drop(1)))
                put("value:menu.$role", dimension(value).toString())
            put("value:menu.clickMask", literal(click.arguments.last()).toString())
            check(literal(click.arguments[1]) == 0L)
            for ((index, role) in listOf("textFlags", "textFlags2", "textMask").withIndex())
                put("value:menu.$role", literal(pickText.arguments[17 + index]).toString())
            capturedField("style", pickText.arguments[15])
            capturedField("foreground", pickText.arguments[2])
            val leading = calls.take(pickIndex).last { it.descriptor == icon.descriptor }
            val sized = leading.arguments[2] as? HostDexCalls.Call ?: error("Image menu icon size is not proven")
            val iconPadding = sized.arguments.first() as? HostDexCalls.Call ?: error("Image menu icon inset is not proven")
            val size = requireNotNull(bridge.getMethodData(sized.descriptor))
            val paddingDefault = requireNotNull(bridge.getMethodData(iconPadding.descriptor))
            check(size.paramTypeNames == listOf(modifier.name, "float") && size.returnTypeName == modifier.name)
            check(paddingDefault.paramTypeNames == listOf(modifier.name, "float", "float", "float", "float", "int") &&
                paddingDefault.returnTypeName == modifier.name)
            put("value:menu.iconSize", dimension(sized.arguments.last()).toString())
            put("value:menu.iconGap", dimension(iconPadding.arguments[3]).toString())
            put("value:menu.iconMask", literal(iconPadding.arguments.last()).toString())
            val trailing = calls.drop(pickIndex + 1).first { it.descriptor == icon.descriptor }
            val arrowPadding = trailing.arguments[2] as? HostDexCalls.Call ?: error("Image menu arrow inset is not proven")
            check(arrowPadding.descriptor == paddingDefault.descriptor)
            put("value:menu.arrowTop", dimension(arrowPadding.arguments[2]).toString())
            put("value:menu.arrowMask", literal(arrowPadding.arguments.last()).toString())
            capturedField("trailing", trailing.arguments[3])
            val separator = calls.drop(pickIndex + 1).first { it.descriptor == divider.descriptor }
            val separatorPadding = separator.arguments.first() as? HostDexCalls.Call ?: error("Image menu separator inset is not proven")
            check(separatorPadding.descriptor == paddingDefault.descriptor)
            put("value:menu.dividerInset", dimension(separatorPadding.arguments[1]).toString())
            put("value:menu.dividerMask", literal(separatorPadding.arguments.last()).toString())
            for ((role, method) in listOf("icon" to icon, "text" to text, "clickable" to clickable, "endNode" to endNode,
                "size" to size, "paddingDefault" to paddingDefault, "divider" to divider))
                put("method:menu.$role", method.descriptor)
            put("class:menu.vector", vector.descriptor)
            put("class:menu.modifier", modifier.descriptor)
            put("class:menu.style", text.paramTypes[15].descriptor)
            for ((role, label) in listOf("next" to "AutoMirrored.Filled.NavigateNext", "icon.Link" to "Outlined.Link",
                "icon.AutoAwesome" to "Outlined.AutoAwesome", "icon.Download" to "Outlined.Download")) {
                val method = anchor(label).filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                    it.returnTypeName == vector.name }.one("image menu $role")
                put("method:menu.$role", method.descriptor)
            }
        }
        private fun export(): Map<String, String> = buildMap {
            val book = type(P + "data.db.entity.Book")
            val composer = type("androidx.compose.runtime.Composer")
            val function = type("kotlin.jvm.functions.Function0")
            val auth = known(P + "ui.backup.components.AuthCardKt", "AuthCard")
            val drive = known(P + "ui.backup.components.AuthCardKt", "DriveAuthCard")
            val content = auth.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 9 &&
                it.paramTypeNames.first() == book.name && it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") &&
                it.paramTypeNames.count { p -> p == "kotlin.jvm.functions.Function1" } == 4 }.one("backup content")
            // Current releases no longer call the old LocalStorageCard. The book header is
            // present for both backed-up and unbacked books, inside the same native Column.
            val header = content.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(book.name, composer.name, "int") }.one("backup header")
            val icon = anchor("Colored.AndroidOs").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                it.returnTypeName == drive.paramTypeNames.first() }.one("local storage icon")
            val activity = requireNotNull(bridge.getClassData(P + "MainActivity"))
            val result = activity.methods.filter { it.name == "onActivityResult" && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("int", "int", "android.content.Intent") && it.returnTypeName == "void" }.one("document picker result")
            val invoke = function.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod && it.paramCount == 0 &&
                it.returnTypeName == "java.lang.Object" }.one("export callback")
            for ((role, cls) in listOf("book" to book, "composer" to composer, "function" to function,
                "activity" to activity, "vector" to requireNotNull(icon.returnType))) put("class:$role", cls.descriptor)
            for ((role, m) in listOf("content" to content, "header" to header, "drive" to drive, "icon" to icon, "activityResult" to result, "invoke" to invoke)) put("method:$role", m.descriptor)
            for (role in listOf("uid", "uuid", "title")) put("field:book.$role", base.getValue("field:${P}data.db.entity.Book#$role"))
        }
    }
}
