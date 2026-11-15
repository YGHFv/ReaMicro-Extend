package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Reader extras bind behavior and data flow from the current APK, independently per feature. */
internal object HostReaderExtrasQueries {
    val GROUPS = listOf("autoPage", "fontFamilies", "fontCompletion", "importOverwrite", "importMetadata", "virtualPageGuard")
    private const val VM = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
    private const val PROVIDER = "org.epub.FontProvider"
    private const val FAMILY = "androidx.compose.ui.text.font.FontFamily"
    private const val REPOSITORY = "app.zhendong.reamicro.repository.BookshelfRepository"
    private const val DAO = "app.zhendong.reamicro.data.db.dao.BookDao"
    private const val BOOK = "app.zhendong.reamicro.data.db.entity.Book"
    private const val OPF = "org.epub.structure.opf.Opf"
    private const val METADATA = "org.epub.structure.opf.Metadata"
    private const val IDENTIFIER = "org.epub.structure.metadata.Identifier"
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")
    class Discovery(private val bridge: DexKitBridge, private val apk: String, private val base: Map<String, String>) {
        private val flow by lazy { HostDexCalls(apk) }
        private fun known(logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
        private fun method(value: String) = requireNotNull(bridge.getMethodData(value))
        private fun knownMethod(owner: String, name: String) = method(base.getValue("method:$owner#$name"))
        private fun find(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        private fun anchor(vararg text: String) = find(MethodMatcher.create().usingEqStrings(*text))
        private fun property(owner: String, name: String) = base["field:$owner#$name"]
            ?: HostRecordQueries.query(apk, mapOf("class:$owner" to known(owner).descriptor)).getValue("field:$owner#$name")
        private fun ctor(type: ClassData, vararg args: String) = type.methods.filter {
            it.isConstructor && it.paramTypeNames == args.toList()
        }.one("${type.name} constructor")
        private fun fieldFromParameter(ctor: MethodData, parameter: Int): String = flow.writes(ctor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(parameter + 1) &&
                it.field.startsWith(ctor.declaredClass!!.descriptor + "->")
        }.map { it.field }.distinct().single()
        private val continuation by lazy { known("kotlin.coroutines.Continuation") }
        private fun isSubtype(type: ClassData, expected: ClassData): Boolean {
            val pending = java.util.ArrayDeque<ClassData>(); val visited = hashSetOf<String>(); pending.add(type)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current.name == expected.name) return true
                if (!visited.add(current.descriptor)) continue
                current.superClass?.let(pending::add); current.interfaces.forEach(pending::add)
            }
            return false
        }
        private fun isContinuation(type: ClassData) = isSubtype(type, continuation)
        private fun slots(out: Output, key: String, method: MethodData, types: Map<String, String>) {
            for ((name, type) in types) out.value("$key.$name", method.paramTypeNames.indices.single { method.paramTypeNames[it] == type })
            val continuations = method.paramTypes.indices.filter { isContinuation(method.paramTypes[it]) }
            if (continuations.isNotEmpty()) out.value("$key.continuation", continuations.single())
        }
        private class Output {
            val values = linkedMapOf<String, String>()
            fun type(key: String, value: ClassData) = value.also { values["class:$key"] = it.descriptor }
            fun method(key: String, value: MethodData) = value.also {
                values["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
            }
            fun field(key: String, value: String) { values["field:$key"] = value }
            fun value(key: String, value: Int) { values["value:$key"] = value.toString() }
        }
        fun query(group: String) = Output().apply { when (group) {
            "autoPage" -> autoPage(this)
            "fontFamilies" -> fontFamilies(this)
            "fontCompletion" -> fontCompletion(this)
            "importOverwrite" -> importOverwrite(this)
            "importMetadata" -> importMetadata(this)
            "virtualPageGuard" -> virtualPages(this)
            else -> error("Unknown reader extra: $group")
        } }.values

        private fun autoPage(out: Output) {
            val vm = out.type("vm", known(VM))
            val state = out.type("mutableState", requireNotNull(anchor("MutableState(value=").one("Compose mutable state identity").declaredClass))
            for ((key, args, result) in listOf(Triple("stateGet", emptyList<String>(), "java.lang.Object"),
                Triple("stateSet", listOf("java.lang.Object"), "void"))) {
                out.method(key, state.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
                    it.paramTypeNames == args && it.returnTypeName == result }.one("Compose $key"))
            }
            val dispatch = out.method("dispatch", anchor("ReaderViewModel", "摘抄内容: ").filter {
                it.className == vm.name && it.paramCount == 1 && it.returnTypeName == "void" && !Modifier.isStatic(it.modifiers)
            }.one("reader dispatch"))
            out.method("send", requireNotNull(vm.superClass).methods.filter {
                it.paramTypeNames == dispatch.paramTypeNames && it.returnTypeName == "void" && Modifier.isFinal(it.modifiers)
            }.one("reader queued intent"))
            var parent = vm.superClass
            while (parent != null) {
                val methods = parent.methods.filter { it.isMethod && it.paramCount == 0 && it.returnTypeName == "void" &&
                    Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) }
                if (methods.size == 1) { out.method("clear", methods.single()); break }
                parent = parent.superClass
            }
            check("method:clear" in out.values)
            val tap = out.type("tap", requireNotNull(anchor("TapDirection(direction=").one("page tap record").declaredClass))
            out.method("tap", ctor(tap, "long"))
            val close = out.type("close", known("kotlin.jvm.functions.Function0"))
            out.method("close", close.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
                it.returnTypeName == "java.lang.Object" }.one("settings close invocation"))
            vm.methods.filter { it.isConstructor }.forEachIndexed { index, constructor -> out.method("vm.$index", constructor) }
            for ((key, text) in listOf("play" to "Filled.PlayArrow", "pause" to "Filled.Pause")) {
                out.method(key, anchor(text).filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 }.one("$key icon"))
            }
        }

        private fun fontFamilies(out: Output) {
            val provider = out.type("provider", known(PROVIDER)); val family = out.type("family", known(FAMILY))
            val weight = out.type("weight", known("androidx.compose.ui.text.font.FontWeight"))
            val list = out.type("list", known("androidx.compose.ui.text.font.FontListFontFamily"))
            out.method("weight", ctor(weight, "int")); out.method("list", ctor(list, "java.util.List"))
            out.method("fromPath", provider.methods.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.String", weight.name) && it.returnTypeName != "void"
            }.one("font file factory"))
            val default = requireNotNull(anchor("FontFamily.Default").one("default family identity").declaredClass)
            out.field("system", family.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == default.name }.descriptor)
            val initializer = family.methods.single { it.name == "<clinit>" }
            val serif = flow.calls(initializer.descriptor).single { call ->
                call.descriptor.contains("-><init>(") && HostDexCalls.Text("serif") in call.arguments
            }.arguments.first()
            val serifField = flow.writes(initializer.descriptor).single { it.value == serif }.field
            val providerInit = provider.methods.single { it.name == "<clinit>" }
            val songField = flow.writes(providerInit.descriptor).single { it.value == HostDexCalls.Field(serifField) }.field
            out.method("song", provider.methods.filter { candidate -> Modifier.isStatic(candidate.modifiers) && candidate.paramCount == 0 &&
                candidate.returnTypeName == family.name && candidate.usingFields.any { it.field.descriptor == songField } &&
                candidate.usingFields.map { it.field }.filter { it.declaredClassName == provider.name && it.typeName == family.name }
                    .distinctBy { it.descriptor }.size == 2 }.one("original built-in song family"))
        }

        private fun fontCompletion(out: Output) {
            val provider = out.type("provider", known(PROVIDER)); val family = out.type("family", known(FAMILY))
            val withName = out.method("withName", knownMethod(PROVIDER, "withName"))
            check(Modifier.isStatic(withName.modifiers) && withName.paramCount == 1 && withName.returnTypeName == family.name)
            val elementDescriptor = withName.paramTypes.single().descriptor.removePrefix("[")
            val name = out.type("name", requireNotNull(bridge.getClassData(elementDescriptor)))
            out.field("name", withName.usingFields.map { it.field }.filter { it.declaredClassName == name.name && it.typeName == "java.lang.String" }
                .distinctBy { it.descriptor }.single().descriptor)
            val booleans = withName.usingFields.map { it.field }.filter { it.declaredClassName == provider.name && it.typeName == "boolean" }
                .distinctBy { it.descriptor }
            check(booleans.size == 2)
            val init = provider.methods.single { it.name == "<clinit>" }
            val enabled = flow.writes(init.descriptor).single { it.value == HostDexCalls.Literal(1) && it.field in booleans.map { f -> f.descriptor } }.field
            out.field("embedded", enabled); out.field("builtIn", booleans.single { it.descriptor != enabled }.descriptor)
            out.field("attached", withName.usingFields.map { it.field }.filter { it.declaredClassName == provider.name && it.typeName == "java.util.HashMap" }
                .distinctBy { it.descriptor }.single().descriptor)
            out.method("fontKey", knownMethod(PROVIDER, "fontKey"))
            val pair = out.type("pair", known("kotlin.Pair")); val composer = out.type("composer", known("androidx.compose.runtime.Composer"))
            val pairCtor = out.method("pair", ctor(pair, "java.lang.Object", "java.lang.Object"))
            out.field("first", fieldFromParameter(pairCtor, 0)); out.field("second", fieldFromParameter(pairCtor, 1))
            val row = out.method("row", find(MethodMatcher.create().paramCount(4).returnType("void")).filter {
                Modifier.isStatic(it.modifiers) && it.paramTypeNames.drop(1) == listOf(pair.name, composer.name, "int") &&
                    flow.casts(it.descriptor).any { cast -> cast.type == family.descriptor }
            }.one("built-in font preview row"))
            slots(out, "row", row, mapOf("pair" to pair.name, "composer" to composer.name, "flags" to "int"))
            out.value("row.mask", 7 shl (out.values.getValue("value:row.pair").toInt() * 3 + 1))
            row.callers.distinctBy { it.descriptor }.filter { it.isMethod }.forEachIndexed { i, caller -> out.method("rowCaller.$i", caller) }
        }

        private fun virtualPages(out: Output) {
            val vm = out.type("vm", known(VM)); val epub = out.type("epub", known("app.zhendong.reamicro.data.epub.Epub"))
            out.type("continuation", continuation)
            val window = out.method("window", knownMethod(VM, "ensureVirtualPageWindow"))
            val mapped = out.method("mapped", window.invokes.filter { it.className == vm.name && it.paramCount == 3 &&
                epub.name in it.paramTypeNames && it.invokes.count { call -> call.className == vm.name && epub.name in call.paramTypeNames } == 2
            }.one("map requested virtual page"))
            for ((key, target) in listOf("window" to window, "mapped" to mapped)) {
                slots(out, key, target, mapOf("epub" to epub.name))
            }
            val mappedPage = mapped.paramTypeNames.indices.single { mapped.paramTypeNames[it] == "int" }
            val windowPage = flow.calls(window.descriptor).filter { it.descriptor == mapped.descriptor }
                .mapNotNull { it.arguments.getOrNull(mappedPage + 1) as? HostDexCalls.Parameter }.map { it.index - 1 }.distinct().single()
            check(window.paramTypeNames[windowPage] == "int")
            out.value("window.page", windowPage); out.value("mapped.page", mappedPage)
            val getPage = knownMethod(VM, "getVirtualPage")
            val pages = flow.calls(getPage.descriptor).filter { it.descriptor.endsWith("->get(Ljava/lang/Object;)Ljava/lang/Object;") }
                .mapNotNull { it.arguments.firstOrNull() as? HostDexCalls.Field }.map { it.descriptor }.distinct().single()
            out.field("pages", pages)
            out.field("spines", window.usingFields.map { it.field }.filter { it.declaredClassName == vm.name &&
                it.typeName == "java.util.LinkedHashMap" && it.descriptor != pages }.distinctBy { it.descriptor }.single().descriptor)
            val load = window.callers.filter { it.className == vm.name && it.paramCount == 2 && it.paramTypeNames.first() == "int" &&
                it.usingStrings.contains("epub") }.one("outer virtual page loader")
            out.method("load", load)
            val loadContinuation = load.invokes.filter { it.isConstructor && it.paramCount == 2 && it.paramTypeNames.first() == vm.name &&
                isContinuation(it.paramTypes.last()) }.one("outer virtual page continuation")
            out.type("loadContinuation", requireNotNull(loadContinuation.declaredClass))
            val unit = out.type("unit", requireNotNull(anchor("kotlin.Unit").filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" && !Modifier.isStatic(it.modifiers) }.one("native Unit identity").declaredClass))
            out.field("unit", unit.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == unit.name }.descriptor)
            check(listOf(window, mapped, load).all { it.returnTypeName == "java.lang.Object" &&
                it.usingFields.any { used -> used.field.descriptor == out.values.getValue("field:unit") } })
            (window.callers + mapped.callers).distinctBy { it.descriptor }.filter { it.isMethod }.forEachIndexed { i, caller -> out.method("caller.$i", caller) }
        }

        private fun importMetadata(out: Output) {
            val repository = out.type("repository", known(REPOSITORY)); val dao = out.type("dao", known(DAO))
            val book = out.type("book", known(BOOK)); val opf = out.type("opf", known(OPF)); val path = out.type("path", known("okio.Path"))
            out.type("continuation", continuation)
            val records = linkedMapOf("book" to BOOK, "metadata" to METADATA, "identifier" to IDENTIFIER,
                "title" to "org.epub.structure.metadata.Title", "creator" to "org.epub.structure.metadata.Creator",
                "publisher" to "org.epub.structure.metadata.Publisher", "meta" to "org.epub.structure.metadata.Meta")
            records.forEach { (key, logical) -> out.type(key, known(logical)) }
            for ((key, names) in linkedMapOf("metadata" to "title subtitle uuid authors publisher metas", "identifier" to "value",
                "title" to "value", "creator" to "name", "publisher" to "name", "meta" to "property id")) {
                names.split(' ').forEach { name -> out.field("$key.$name", property(records.getValue(key), name)) }
            }
            out.field("opf.metadata", property(OPF, "metadata"))
            out.field("repository.dao", repository.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == dao.name }.descriptor)
            for (name in "id uid uuid title subtitle author cover publisher size cloudId updated".split(' ')) {
                out.field("book.$name", property(BOOK, name))
            }
            val update = out.method("update", repository.methods.filter { candidate -> candidate.isMethod && !Modifier.isStatic(candidate.modifiers) &&
                candidate.paramCount == 2 && candidate.paramTypeNames.first() == book.name && candidate.returnTypeName == "java.lang.Object" &&
                candidate.invokes.any { it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == book.name }
            }.one("metadata book update"))
            out.method("find", update.invokes.filter { it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == "long" &&
                it.returnTypeName == "java.lang.Object" }.one("metadata book readback"))
            out.method("cover", knownMethod(OPF, "coverRelativePath"))
        }

        private fun importOverwrite(out: Output) {
            val repository = out.type("repository", known(REPOSITORY)); val dao = out.type("dao", known(DAO))
            val book = out.type("book", known(BOOK)); val worker = out.type("worker", known("app.zhendong.reamicro.arch.WorkerManager"))
            val opf = out.type("opf", known(OPF)); val metadata = out.type("metadata", known(METADATA))
            val identifier = out.type("identifier", known(IDENTIFIER)); val title = out.type("title", known("org.epub.structure.metadata.Title"))
            val meta = out.type("meta", known("org.epub.structure.metadata.Meta")); val path = out.type("path", known("okio.Path"))
            val platform = out.type("platform", known("ReaMicroHost.PlatformFile")); val user = out.type("user", known("app.zhendong.reamicro.data.db.entity.User"))
            val function = out.type("function1", known("kotlin.jvm.functions.Function1")); val pair = out.type("pair", known("kotlin.Pair"))
            out.type("continuation", continuation)
            for ((key, type) in listOf("repository" to repository, "worker" to worker)) {
                type.methods.filter { it.isConstructor }.forEachIndexed { index, constructor -> out.method("$key.$index", constructor) }
            }
            out.field("worker.repository", worker.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == repository.name }.descriptor)
            out.field("repository.dao", repository.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == dao.name }.descriptor)
            val imported = out.method("import", knownMethod(REPOSITORY, "importBook"))
            slots(out, "import", imported, mapOf("source" to platform.name, "directory" to path.name, "opf" to opf.name,
                "uri" to "java.lang.String", "size" to "java.lang.Long", "progress" to function.name))
            val importContinuation = out.method("importContinuation", imported.invokes.filter {
                it.isConstructor && it.paramCount == 2 && it.paramTypeNames.first() == repository.name &&
                    isContinuation(it.paramTypes.last()) && it.declaredClass?.let(::isContinuation) == true
            }.one("native book import continuation"))
            out.type("importContinuation", requireNotNull(importContinuation.declaredClass))
            out.method("bookCopy", imported.invokes.filter {
                it.isMethod && Modifier.isStatic(it.modifiers) && it.declaredClassName == book.name &&
                    it.paramCount > 1 && it.paramTypeNames.first() == book.name && it.returnTypeName == book.name
            }.one("native import book copy"))
            val repositoryUpdate = repository.methods.filter { candidate ->
                candidate.isMethod && !Modifier.isStatic(candidate.modifiers) && candidate.paramCount == 2 &&
                    candidate.paramTypeNames.first() == book.name && candidate.returnTypeName == "java.lang.Object" &&
                    candidate.invokes.any { it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == book.name }
            }.one("native shelf update")
            val daoUpdate = out.method("daoUpdate", repositoryUpdate.invokes.filter {
                it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == book.name &&
                    isContinuation(it.paramTypes.last()) && it.returnTypeName == "java.lang.Object"
            }.one("native book update commit"))
            check(imported.invokes.any { it.descriptor == daoUpdate.descriptor })
            out.method("daoInsert", imported.invokes.filter {
                it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == book.name &&
                    isContinuation(it.paramTypes.last()) && it.returnTypeName == "java.lang.Object" &&
                    it.descriptor != daoUpdate.descriptor
            }.one("native book insert commit"))
            out.method("daoDelete", imported.invokes.filter {
                it.className == dao.name && it.paramCount == 2 && it.paramTypeNames.first() == "long" &&
                    isContinuation(it.paramTypes.last()) && it.returnTypeName == "java.lang.Object"
            }.one("native import rollback delete"))
            val pairConstructor = out.method("pair", ctor(pair, "java.lang.Object", "java.lang.Object"))
            out.field("pair.first", fieldFromParameter(pairConstructor, 0))
            out.field("pair.second", fieldFromParameter(pairConstructor, 1))
            val first = out.method("first", anchor("Expected at least one element").filter { it.paramCount == 2 &&
                Modifier.isStatic(it.modifiers) && isContinuation(it.paramTypes.last()) }.one("Flow.first"))
            out.type("flow", first.paramTypes.first())
            out.value("first.continuation", first.paramCount - 1)
            val userFlow = flow.calls(imported.descriptor).filter { it.descriptor == first.descriptor }
                .mapNotNull { it.arguments.firstOrNull() as? HostDexCalls.Field }.distinct().single()
            out.field("repository.userFlow", userFlow.descriptor)
            out.field("user.id", property("app.zhendong.reamicro.data.db.entity.User", "id"))
            out.method("list", knownMethod(DAO, "listByUid"))
            val find = out.method("find", knownMethod(DAO, "findByUidAndUuid"))
            slots(out, "find", find, mapOf("uid" to "long", "uuid" to "java.lang.String"))
            for ((key, logical) in listOf("exists" to "checkBookExists", "delete" to "deleteBook")) {
                val target = out.method(key, knownMethod(REPOSITORY, logical))
                slots(out, key, target, mapOf("value" to if (key == "exists") "java.lang.String" else book.name))
            }
            for (prop in "id uuid uid title uri size created cloudId publisher backupType backupId backupCode".split(' ')) {
                out.field("book.$prop", property(BOOK, prop))
            }
            for ((key, logical, properties) in listOf(
                Triple("opf", OPF, "metadata"), Triple("metadata", METADATA, "title uuid metas"),
                Triple("identifier", IDENTIFIER, "value"), Triple("title", "org.epub.structure.metadata.Title", "value"),
                Triple("meta", "org.epub.structure.metadata.Meta", "property value"))) {
                for (prop in properties.split(' ')) out.field("$key.$prop", property(logical, prop))
            }
            val obtain = out.method("obtain", knownMethod(OPF, "obtain"))
            out.method("overwrite", knownMethod(OPF, "overwrite"))
            val epubImport = out.method("epubImport", knownMethod("app.zhendong.reamicro.arch.EpubFileManager", "import"))
            val source = flow.calls(epubImport.descriptor).filter { it.descriptor == obtain.descriptor }
                .mapNotNull { it.arguments.lastOrNull() as? HostDexCalls.Parameter }.map { it.index }.distinct().single()
            check(Modifier.isStatic(epubImport.modifiers) && Modifier.isStatic(obtain.modifiers))
            val callback = out.type("importCallback", epubImport.paramTypes.single { it.name != path.name })
            check(isSubtype(callback, function) && epubImport.returnTypeName == pair.name)
            out.value("epubImport.callback", epubImport.paramTypeNames.indices.single { epubImport.paramTypeNames[it] == callback.name })
            out.value("epubImport.source", source)
            out.value("epubImport.directory", epubImport.paramTypeNames.indices.single { it != source && epubImport.paramTypeNames[it] == path.name })
            out.method("path", find(MethodMatcher.create().paramTypes("java.lang.String", "boolean").returnType(path.name))
                .filter { Modifier.isStatic(it.modifiers) }.one("absolute native path parser"))
            out.method("parent", path.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod && it.paramCount == 0 &&
                it.returnTypeName == path.name }.one("native path parent"))
            out.method("resolve", path.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.String", "boolean") &&
                it.returnTypeName == path.name }.one("native child path"))
            importWorker(out, worker, repository, path, book)
        }

        private fun importWorker(out: Output, manager: ClassData, repository: ClassData, path: ClassData, book: ClassData) {
            val worker = out.type("importWorker", requireNotNull(anchor("epubs", "unzip-")
                .one("native import worker").declaredClass))
            val workerCtor = out.method("importWorker", worker.methods.filter {
                it.isConstructor && it.paramTypes.any { type -> type.descriptor == manager.descriptor }
            }.one("native import worker constructor"))
            val managerSlot = workerCtor.paramTypeNames.indices.single { workerCtor.paramTypeNames[it] == manager.name }
            out.field("importWorker.manager", fieldFromParameter(workerCtor, managerSlot))
            val resumeName = knownMethod("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "invokeSuspend").name
            val resume = out.method("importWorkerResume", worker.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.name == resumeName &&
                    it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object"
            }.one("native import worker resume"))
            val converter = out.method("txtConverter", resume.invokes.filter {
                it.isConstructor && it.paramCount == 4 &&
                    it.paramTypeNames.take(3) == listOf(path.name, path.name, "long") &&
                    isContinuation(it.paramTypes.last())
            }.one("native TXT conversion"))
            out.type("txtConverter", requireNotNull(converter.declaredClass))
            out.field("txtConverter.directory", fieldFromParameter(converter, 1))
            importTxt(out, repository, path, book, resume)
        }

        private fun importTxt(out: Output, repository: ClassData, path: ClassData, book: ClassData, worker: MethodData) {
            val txt = out.method("txt", repository.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.paramCount == 5 &&
                    it.paramTypeNames.take(4) == listOf(book.name, path.name, "java.lang.String", "java.lang.Long") &&
                    isContinuation(it.paramTypes.last()) && it.returnTypeName == "java.io.Serializable"
            }.one("native TXT import"))
            slots(out, "txt", txt, mapOf("book" to book.name, "directory" to path.name,
                "uri" to "java.lang.String", "size" to "java.lang.Long"))
            val continuationCtor = out.method("txtContinuation", txt.invokes.filter {
                it.isConstructor && it.paramCount == 2 && it.paramTypeNames.first() == repository.name &&
                    isContinuation(it.paramTypes.last()) && it.declaredClass?.let(::isContinuation) == true
            }.one("native TXT import continuation"))
            out.type("txtContinuation", requireNotNull(continuationCtor.declaredClass))
            check(worker.invokes.any { caller ->
                Modifier.isStatic(caller.modifiers) && caller.className == repository.name &&
                    caller.invokes.any { it.descriptor == txt.descriptor }
            }) { "Native worker does not call TXT import" }
        }
    }
}
