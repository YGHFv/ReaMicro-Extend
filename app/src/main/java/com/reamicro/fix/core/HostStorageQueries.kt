package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Storage contracts are derived from records, constructor arguments and business call sites. */
internal object HostStorageQueries {
    private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
        superClass?.isA(parent) == true || interfaces.any { it.isA(parent) }
    val GROUPS = listOf("records", "operations", "data", "folder", "tap", "import", "ui", "routes", "account", "presentation", "library", "catalog")
    const val REPOSITORY = "app.zhendong.reamicro.repository.storage.CloudStorageRepository"
    const val BOOK = "app.zhendong.reamicro.data.storage.CloudBook"
    const val FOLDER = "app.zhendong.reamicro.data.storage.CloudFolder"
    const val INFO = "app.zhendong.reamicro.data.storage.CloudUserInfo"
    const val AUTH = "app.zhendong.reamicro.data.third.Auth\$BaiduAuth"
    const val LOCAL_AUTH = "app.zhendong.reamicro.data.third.Auth\$Yun115Auth"
    const val DIR = "app.zhendong.reamicro.data.third.Dir"
    val ACCOUNT_SCREENS = mapOf("webdav" to "app.zhendong.reamicro.ui.storage.baidu.BaiduNetDiskAccountScreenKt",
        "local" to "app.zhendong.reamicro.ui.storage.c115.Y115NetDiskAccountScreenKt")
    const val FOLDER_ARGS = "app.zhendong.reamicro.ui.storage.CloudFolderNavArgs"
    const val STATE = "app.zhendong.reamicro.ui.storage.CloudStorageUiState"
    const val SHEET = "app.zhendong.reamicro.ui.home.components.BookLibrarySheetKt"
    val RECORDS = linkedMapOf(
        "auth" to (AUTH to "token orderBy orderDirection nickname usedSize totalSize libraryDir backupDir"),
        "localAuth" to (LOCAL_AUTH to "token orderBy orderDirection nickname usedSize totalSize libraryDir backupDir"),
        "dir" to (DIR to "id name"),
        "info" to (INFO to "id name avatar driveId identity level usedSize totalSize"),
        "folder" to (FOLDER to "name path empty updatedAt"),
        "book" to (BOOK to "id name type url size updatedAt path hash local workers"),
        "state" to (STATE to "userInfo treeList"),
    )
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")
    private fun contains(value: HostDexCalls.Value, expected: HostDexCalls.Value): Boolean =
        value == expected || value is HostDexCalls.Call && value.arguments.any { contains(it, expected) }

    class Discovery(private val bridge: DexKitBridge, private val apk: String, private val base: Map<String, String>) {
        private val calls by lazy { HostDexCalls(apk) }
        private val code by lazy { StorageCode(apk) }
        private fun known(logical: String): ClassData = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
        private fun method(descriptor: String): MethodData = requireNotNull(bridge.getMethodData(descriptor))
        private fun find(matcher: MethodMatcher) = bridge.findMethod(FindMethod.create().matcher(matcher))
        private fun anchor(vararg strings: String) = find(MethodMatcher.create().usingEqStrings(*strings))
        private fun constructor(type: ClassData, vararg parameters: String) = type.methods.filter {
            it.isConstructor && it.paramTypeNames == parameters.toList()
        }.one("${type.name} constructor")
        private fun fieldFromParameter(ctor: MethodData, parameter: Int): String = calls.writes(ctor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(parameter + 1) &&
                it.field.startsWith(ctor.declaredClass!!.descriptor + "->")
        }.map { it.field }.distinct().single()
        private fun recordField(logical: String, property: String): String = base["field:$logical#$property"]
            ?: HostRecordQueries.query(apk, mapOf("class:$logical" to known(logical).descriptor)).getValue("field:$logical#$property")
        private val repository by lazy { known(REPOSITORY) }
        private val book by lazy { known(BOOK) }
        private val folder by lazy { known(FOLDER) }
        private val flow by lazy { requireNotNull(book.fields.single { it.descriptor == recordField(BOOK, "local") }.type) }
        private val continuation by lazy { known("kotlin.coroutines.Continuation") }
        private val invokeSuspend by lazy { method(base.getValue("method:kotlin.coroutines.jvm.internal.BaseContinuationImpl#invokeSuspend")) }
        private fun resume(type: ClassData) = type.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.name == invokeSuspend.name && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object"
        }.one("${type.name} coroutine resume")
        private val vmConstructor by lazy { find(MethodMatcher.create().name("<init>").paramTypes(
            "int", known("app.zhendong.reamicro.arch.WorkerManager").name, repository.name)).one("storage view model") }
        private val vm by lazy { requireNotNull(vmConstructor.declaredClass) }
        private val folderConstructor by lazy { find(MethodMatcher.create().name("<init>").paramTypes(
            known(FOLDER_ARGS).name, repository.name)).one("folder view model") }
        private val folderVm by lazy { requireNotNull(folderConstructor.declaredClass) }
        private val folderTask by lazy {
            calls.calls(folderConstructor.descriptor).filter { call ->
                call.descriptor.contains("-><init>(") && call.arguments.drop(1).any { it == HostDexCalls.Parameter(0) } &&
                    method(call.descriptor).let { ctor ->
                        ctor.paramTypeNames.count { it == "byte" } == 1 && ctor.paramTypeNames.contains(continuation.name) &&
                            calls.casts(resume(requireNotNull(ctor.declaredClass)).descriptor).any { it.type == folder.descriptor }
                    }
            }.single()
        }
        private val folderWorker by lazy { requireNotNull(method(folderTask.descriptor).declaredClass) }
        private val auth by lazy { repository.methods.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("int") && it.returnTypeName == flow.name }.one("storage auth flow") }
        private val operationMethods by lazy {
            val result = linkedMapOf<String, MethodData>()
            fun selected(key: String, list: List<MethodData>, predicate: (MethodData) -> Boolean) {
                result[key] = list.filter { it.className == repository.name && !Modifier.isStatic(it.modifiers) &&
                    it.returnTypeName == "java.lang.Object" && predicate(it) }.one(key)
            }
            fun path(it: MethodData) = it.paramCount == 3 && it.paramTypeNames.take(2) == listOf("int", "java.lang.String")
            fun pair(it: MethodData) = it.paramCount == 4 && it.paramTypeNames.first() == "int" &&
                it.paramTypeNames.takeLast(2) == listOf("java.lang.String", "java.lang.String")
            selected("createFolder", anchor("新建完成").flatMap { it.invokes }, ::pair)
            selected("delete", anchor("已删除").flatMap { it.invokes }, ::path)
            selected("move", anchor("移动完成").flatMap { it.invokes }, ::pair)
            selected("rename", anchor("重命名已完成").flatMap { it.invokes }) {
                it.paramCount == 4 && it.paramTypeNames.take(3) == listOf("int", book.name, "java.lang.String")
            }
            selected("setBackupDir", anchor("设置完成").flatMap { it.invokes }) {
                it.paramCount == 3 && it.paramTypeNames.take(2) == listOf("int", "java.util.List")
            }
            selected("listFolders", resume(folderWorker).invokes, ::path)
            selected("getBook", repository.methods) { path(it) && calls.casts(it.descriptor).any { cast -> cast.type == book.descriptor } }
            selected("search", repository.methods) { path(it) && it.descriptor !in result.values.map { m -> m.descriptor } &&
                calls.casts(it.descriptor).any { cast -> cast.type == "Ljava/util/List;" } }
            selected("openFolder", repository.methods) { pair(it) && it.descriptor !in result.values.map { m -> m.descriptor } }
            check(result.values.distinctBy { it.descriptor }.size == 9)
            result
        }
        private class Output {
            val values = linkedMapOf<String, String>()
            fun type(key: String, value: ClassData) = value.also { values["class:$key"] = it.descriptor }
            fun method(key: String, value: MethodData) = value.also {
                values["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
            }
            fun field(key: String, descriptor: String) { values["field:$key"] = descriptor }
            fun value(key: String, value: Int) { values["value:$key"] = value.toString() }
        }
        fun query(group: String): Map<String, String> = Output().apply { when (group) {
            "records" -> records(this)
            "operations" -> operations(this)
            "data" -> data(this)
            "folder" -> folders(this)
            "tap" -> tap(this)
            "import" -> imports(this)
            "ui" -> ui(this)
            "routes" -> routes(this)
            "account" -> account(this)
            "presentation" -> presentation(this)
            "library" -> library(this)
            "catalog" -> catalog(this)
            else -> error("Unknown storage group: $group")
        } }.values

        private fun catalog(out: Output) {
            val shelf = out.type("repository", known("app.zhendong.reamicro.repository.BookshelfRepository"))
            val dao = out.type("bookDao", known("app.zhendong.reamicro.data.db.dao.BookDao"))
            val itemDao = out.type("itemDao", known("app.zhendong.reamicro.data.db.dao.BookItemRefDao"))
            val chapterDao = out.type("chapterDao", known("app.zhendong.reamicro.data.db.dao.BookChapterDao"))
            val function = out.type("function1", known("kotlin.jvm.functions.Function1"))
            out.type("continuation", continuation)
            val unit = out.type("unit", requireNotNull(anchor("kotlin.Unit").filter {
                it.name == "toString" && it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
                    !Modifier.isStatic(it.modifiers)
            }.one("catalog callback Unit").declaredClass))
            out.field("unit", unit.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == unit.name }.descriptor)
            out.method("invoke", function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("Room write callback"))
            val upsert = method(base.getValue("method:app.zhendong.reamicro.data.db.dao.BookItemRefDao#upsert"))
            val perform = out.method("perform", upsert.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramCount == 5 && it.paramTypeNames.count { t -> t == "boolean" } == 2 && function.name in it.paramTypeNames &&
                it.paramTypes.any { t -> t.isA(continuation) } && it.returnTypeName == "java.lang.Object"
            }.one("Room write transaction"))
            val databaseIndex = perform.paramTypes.indices.single { perform.paramTypes[it].name != function.name &&
                perform.paramTypes[it].name != "boolean" && !perform.paramTypes[it].isA(continuation) }
            val database = out.type("database", perform.paramTypes[databaseIndex])
            out.value("perform.database", databaseIndex)
            out.value("perform.function", perform.paramTypeNames.indexOf(function.name))
            out.value("perform.continuation", perform.paramTypes.indices.single { perform.paramTypes[it].isA(continuation) })
            val call = calls.calls(upsert.descriptor).filter { it.descriptor == perform.descriptor }.single()
            val flags = perform.paramTypeNames.indices.filter { perform.paramTypeNames[it] == "boolean" }
            flags.forEachIndexed { n, slot ->
                out.value("perform.flag$n", slot)
                out.value("perform.flagValue$n", (call.arguments[slot] as HostDexCalls.Literal).value.toInt())
            }
            for ((role, type) in listOf("book" to dao, "item" to itemDao, "chapter" to chapterDao)) {
                out.field("repository.$role", shelf.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == type.name }.descriptor)
                out.field("database.$role", type.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == database.name }.descriptor)
            }
            val sql = anchor("SELECT * FROM books WHERE uid=? AND uri=? AND created > 0 LIMIT 1")
                .filter { it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }
                .one("Room book query statements")
            val prepare = out.method("prepare", sql.invokes.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName != "void" && it.returnTypeName != "java.lang.String"
            }.one("SQLite prepare"))
            val connection = out.type("connection", requireNotNull(prepare.declaredClass))
            val statement = out.type("statement", requireNotNull(prepare.returnType))
            for ((role, parameters, returns) in listOf(
                Triple("bindLong", listOf("long", "int"), "void"), Triple("bindText", listOf("int", "java.lang.String"), "void"),
                Triple("step", emptyList(), "boolean"), Triple("getLong", listOf("int"), "long"),
                Triple("getText", listOf("int"), "java.lang.String"))) {
                out.method(role, sql.invokes.filter { it.declaredClassName == statement.name && !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == parameters && it.returnTypeName == returns }.one("SQLite $role"))
            }
            out.method("columnCount", statement.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "int" }.one("SQLite column count"))
            val list = method(base.getValue("method:app.zhendong.reamicro.data.db.dao.BookDao#listByUid"))
            val factory = out.method("flow", list.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(database.name, "java.lang.String[]", function.name) }.one("Room query flow factory"))
            val nativeFlow = out.type("queryFlow", requireNotNull(factory.returnType))
            val collect = out.method("collect", nativeFlow.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 2 && it.paramTypes.last().isA(continuation) && it.returnTypeName == "java.lang.Object" }.one("Room flow collect"))
            val collector = out.type("collector", collect.paramTypes[0])
            out.method("emit", collector.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object", continuation.name) && it.returnTypeName == "java.lang.Object" }.one("Room flow emit"))
            out.method("items", method(base.getValue("method:app.zhendong.reamicro.arch.EpubFileManager#getItemRefs")))
            val chapters = out.method("chapters", method(base.getValue("method:app.zhendong.reamicro.arch.EpubFileManager#getChapters")))
            val function2 = out.type("function2", chapters.paramTypes.last())
            check(function2.isA(known("kotlin.jvm.functions.Function2")))
            val obtain = out.method("opf", method(base.getValue("method:org.epub.structure.opf.Opf#obtain")))
            out.type("opf", requireNotNull(obtain.returnType)); val path = out.type("path", obtain.paramTypes.single())
            out.method("path", find(MethodMatcher.create().paramTypes("java.lang.String", "boolean").returnType(path.name))
                .filter { Modifier.isStatic(it.modifiers) }.one("catalog filesystem path"))
            for ((role, schema) in HostEpubRuntimeQueries.records.filterKeys { it in setOf("item", "chapter") }) {
                out.type(role, known(schema.first))
                schema.second.forEach { property -> out.field("$role.$property", recordField(schema.first, property)) }
            }
        }
        private fun library(out: Output) {
            val shelf = out.type("repository", known("app.zhendong.reamicro.repository.BookshelfRepository"))
            val dao = out.type("dao", known("app.zhendong.reamicro.data.db.dao.BookDao"))
            val entity = out.type("book", known("app.zhendong.reamicro.data.db.entity.Book"))
            val storage = out.type("storage", known("app.zhendong.reamicro.arch.fs.UserStorage"))
            out.type("continuation", continuation)
            for ((role, type) in listOf("dao" to dao, "storage" to storage)) out.field("repository.$role",
                shelf.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == type.name }.descriptor)
            val first = out.method("first", anchor("Expected at least one element").filter { Modifier.isStatic(it.modifiers) &&
                it.paramCount == 2 && it.returnTypeName == "java.lang.Object" && it.paramTypes.last().isA(continuation)
            }.one("library first flow value"))
            val nativeFlow = out.type("flow", first.paramTypes.first())
            val update = out.method("update", shelf.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 2 && it.paramTypeNames.first() == entity.name && it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { call -> call.declaredClassName == dao.name && call.paramCount == 2 && call.paramTypeNames.first() == entity.name }
            }.one("library update book"))
            out.method("findId", update.invokes.filter { it.declaredClassName == dao.name && it.paramCount == 2 &&
                it.paramTypeNames.first() == "long" && it.returnTypeName == "java.lang.Object" }.one("library find by id"))
            val findUuid = out.method("findUuid", method(base.getValue("method:app.zhendong.reamicro.data.db.dao.BookDao#findByUidAndUuid")))
            for ((role, type) in listOf("uid" to "long", "uuid" to "java.lang.String"))
                out.value("findUuid.$role", findUuid.paramTypeNames.indices.single { findUuid.paramTypeNames[it] == type })
            out.value("findUuid.continuation", findUuid.paramTypes.indices.single { findUuid.paramTypes[it].isA(continuation) })
            out.method("list", method(base.getValue("method:app.zhendong.reamicro.data.db.dao.BookDao#listByUid")))
            val books = out.method("books", method(base.getValue("method:app.zhendong.reamicro.arch.fs.UserStorage#userBooksDir")))
            out.type("path", requireNotNull(books.returnType))
            val uid = storage.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Long" }
            check(uid.readers.any { it.declaredClassName == storage.name && it.usingStrings.contains("books") })
            out.field("storage.uid", uid.descriptor)

            val uriSql = "SELECT * FROM books WHERE uid=? AND uri=? AND created > 0 LIMIT 1"
            val sqlBody = anchor(uriSql).filter { it.paramTypeNames == listOf("java.lang.Object") &&
                it.returnTypeName == "java.lang.Object" && Modifier.isPublic(it.modifiers) &&
                !Modifier.isStatic(it.modifiers) }.one("library URI SQL")
            val sqlOwner = requireNotNull(sqlBody.declaredClass)
            val sqlCtor = sqlOwner.methods.filter { it.isConstructor &&
                it.paramTypeNames.sorted() == listOf("long", "java.lang.String", "byte").sorted() }
                .one("library URI SQL closure")
            val sqlTagSlot = sqlCtor.paramTypeNames.indexOf("byte")
            val sqlTextSlot = sqlCtor.paramTypeNames.indexOf("java.lang.String")
            val sqlTagField = fieldFromParameter(sqlCtor, sqlTagSlot)
            val nativeCandidates = shelf.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.String") && it.returnType?.isA(nativeFlow) == true }
            val liveUri = nativeCandidates.filter { candidate ->
                candidate.invokes.filter { it.isConstructor && it.paramCount == 4 &&
                    it.paramTypes[0].isA(continuation) && it.paramTypeNames.drop(1) == listOf(shelf.name, "java.lang.String", "byte")
                }.any { ctor ->
                    val body = resume(requireNotNull(ctor.declaredClass))
                    val sqlCalls = calls.calls(body.descriptor).filter { it.descriptor == sqlCtor.descriptor }
                    if (sqlCalls.isEmpty()) return@any false
                    val tagField = fieldFromParameter(ctor, 3)
                    val textField = fieldFromParameter(ctor, 2)
                    val nativeCalls = calls.calls(candidate.descriptor).filter { it.descriptor == ctor.descriptor }
                    nativeCalls.size == 1 && nativeCalls.single().let { call ->
                        val tag = call.arguments[4] as? HostDexCalls.Literal ?: return@let false
                        call.arguments[2] == HostDexCalls.Parameter(0) && call.arguments[3] == HostDexCalls.Parameter(1) &&
                            code.branchContains(body.descriptor, tagField, tag.value.toInt(), sqlCtor.descriptor) &&
                            sqlCalls.all { sql ->
                                val sqlTag = sql.arguments[sqlTagSlot + 1] as? HostDexCalls.Literal
                                sql.arguments[sqlTextSlot + 1] == HostDexCalls.Field(textField) && sqlTag != null &&
                                    code.branchString(sqlBody.descriptor, sqlTagField, sqlTag.value.toInt(), uriSql)
                            }
                    }
                }
            }.one("library live book by URI")
            out.method("liveUri", liveUri)
        }
        private fun presentation(out: Output) {
            val composer = out.type("composer", known("androidx.compose.runtime.Composer"))
            val function0 = out.type("function0", known("kotlin.jvm.functions.Function0"))
            val function1 = out.type("function1", known("kotlin.jvm.functions.Function1"))
            val function2 = out.type("function2", known("kotlin.jvm.functions.Function2"))
            val entity = out.type("entity", known("app.zhendong.reamicro.data.db.entity.Book"))
            out.type("cloud", book)
            val android = out.method("android", anchor("Colored.AndroidOs").filter { Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 }.one("native Android storage icon"))
            out.type("vector", requireNotNull(android.returnType))
            val tree = out.method("tree", android.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramCount == 5 && it.paramTypeNames.count { t -> t == "int" } == 2 &&
                composer.name in it.paramTypeNames && "java.util.List" in it.paramTypeNames && function1.name in it.paramTypeNames
            }.one("cloud folder tree"))
            // Provider comparisons use native provider constants; Compose flags use bit masks.
            val compared = calls.comparisons(tree.descriptor).flatMap { comparison ->
                listOf(comparison.left to comparison.right, comparison.right to comparison.left).mapNotNull { (value, literal) ->
                    (value as? HostDexCalls.Parameter)?.index?.takeIf { literal is HostDexCalls.Literal && literal.value in 1L..4L }
                }
            }.filter { tree.paramTypeNames[it] == "int" }.distinct().single()
            out.value("tree.provider", compared)
            out.value("tree.composer", tree.paramTypeNames.indexOf(composer.name))
            val extension = out.method("extension", book.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 0 && it.returnTypeName == "java.lang.String" && it.usingStrings.containsAll(listOf(".", "unknown"))
            }.one("cloud file extension"))
            val cloudRow = out.method("cloudRow", extension.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramCount == 5 && it.paramTypeNames[1] == book.name &&
                it.paramTypeNames.drop(2) == listOf(function2.name, composer.name, "int") && it.usingStrings.containsAll(listOf("zip", "rar"))
            }.one("storage cloud book row"))
            out.type("rowScope", cloudRow.paramTypes[0])
            out.method("title", cloudRow.invokes.filter { it.declaredClassName == book.name &&
                !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
                it.descriptor != extension.descriptor && it.usingStrings.contains(".") }.one("cloud filename title"))
            for (role in listOf("updatedAt", "path", "size")) out.field("cloud.$role", recordField(BOOK, role))
            val authorized = out.method("authorized", method(base.getValue("method:$SHEET#CloudAuthorizedRow")))
            val unauthorized = out.method("unauthorized", method(base.getValue("method:$SHEET#CloudUnauthRow")))
            out.type("auth", authorized.paramTypes[1])
            val labelCtor = out.method("label", authorized.invokes.filter { it.isConstructor &&
                it.paramTypeNames == listOf("java.lang.String", "byte") &&
                it.declaredClass?.interfaces?.any { t -> t.name == function2.name } == true }.one("import label constructor"))
            out.type("label", requireNotNull(labelCtor.declaredClass))
            for ((role, entry) in listOf("authorized" to authorized, "unauthorized" to unauthorized)) {
                val labelCall = calls.calls(entry.descriptor).filter { it.descriptor == labelCtor.descriptor }.single()
                out.value("label.$role", (labelCall.arguments[2] as HostDexCalls.Literal).value.toInt())
            }
            val detailCtor = out.method("detail", authorized.invokes.filter { it.isConstructor &&
                it.paramTypeNames == listOf("java.lang.String", "java.lang.String") &&
                it.declaredClass?.methods?.any { m -> m.usingStrings.contains(" / ") } == true }.one("import detail content constructor"))
            val detailOwner = out.type("detail", requireNotNull(detailCtor.declaredClass))
            val detail = out.method("detail", detailOwner.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object", "java.lang.Object") && it.returnTypeName == "java.lang.Object" &&
                it.usingStrings.contains(" / ") }.one("import detail content"))
            out.field("detail.first", fieldFromParameter(detailCtor, 0)); out.field("detail.second", fieldFromParameter(detailCtor, 1))
            val tag = calls.writes(detailCtor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) && it.value is HostDexCalls.Literal }
            out.field("detail.tag", tag.field); out.value("detail.tag", (tag.value as HostDexCalls.Literal).value.toInt())
            val duration = out.method("duration", anchor("（伴听", "佚名").filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.String", "long", "long", composer.name, "int") && it.returnTypeName == "void"
            }.one("book author and reading duration"))
            val bookInfo = out.method("bookInfo", duration.callers.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 4 &&
                it.paramTypeNames.drop(1) == listOf(entity.name, composer.name, "int") && it.returnTypeName == "void"
            }.one("book reading information"))
            out.type("bookScope", bookInfo.paramTypes[0])
            out.method("durationFormat", duration.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("long") && it.returnTypeName == "java.lang.String" &&
                it.usingStrings.containsAll(listOf("小时", "分钟", "不足1分钟")) }.one("reading duration formatter"))
            out.method("text", detail.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.paramCount == 20 && it.paramTypeNames.first() == "java.lang.String" &&
                it.paramTypeNames.takeLast(4) == listOf(composer.name, "int", "int", "int") && it.returnTypeName == "void"
            }.one("native presentation text"))
        }
        private fun records(out: Output) {
            val stateFlow = out.type("stateFlow", known("kotlinx.coroutines.flow.StateFlowImpl"))
            out.method("stateFlow", constructor(stateFlow, "java.lang.Object"))
            out.method("setValue", stateFlow.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void"
            }.one("mutable state flow value"))
            val failure = out.type("failure", anchor("Failure(").mapNotNull { it.declaredClass }.distinctBy { it.descriptor }.single {
                it.fields.filter { f -> !Modifier.isStatic(f.modifiers) }.let { fields -> fields.size == 1 && fields.single().typeName == "java.lang.Throwable" }
            })
            out.method("failure", constructor(failure, "java.lang.Throwable"))
            for ((key, record) in RECORDS) {
                val (logical, names) = record
                val properties = names.split(' ')
                val owner = out.type(key, known(logical))
                val candidates = owner.methods.filter { it.isConstructor && it.paramCount == properties.size }
                val fields = properties.associateWith { recordField(logical, it) }
                val valid = candidates.mapNotNull { ctor ->
                    val origins = code.constructorOrigins(ctor.descriptor)
                    val slots = fields.mapValues { (_, field) -> origins[field] ?: return@mapNotNull null }
                    (ctor to slots).takeIf { slots.values.toSet() == properties.indices.toSet() }
                }.single()
                out.method(key, valid.first)
                for ((property, index) in valid.second) {
                    out.field("$key.$property", fields.getValue(property)); out.value("$key.$property", index)
                }
            }
            val cloudBook = known(RECORDS.getValue("book").first)
            out.field("book.empty", cloudBook.fields.single {
                Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.typeName == cloudBook.name
            }.descriptor)
        }
        private fun operations(out: Output) {
            out.type("repository", repository); out.type("book", book); out.type("flow", flow)
            out.type("continuation", continuation)
            operationMethods.forEach { (key, value) -> out.method(key, value) }
            out.method("auth", auth)
        }
        private fun data(out: Output) {
            out.type("vm", vm); out.type("repository", repository); out.type("flow", flow)
            out.type("continuation", continuation)
            val sheet = method(base.getValue("method:$SHEET#BookLibrarySheet"))
            val scope = out.type("scope", requireNotNull(sheet.paramTypes[0]))
            val composer = out.type("composer", requireNotNull(sheet.paramTypes[3]))
            val info = out.type("info", known(INFO))
            val function1 = out.type("function1", known("kotlin.jvm.functions.Function1"))
            val screen = out.method("screen", calls.referencingType(vm.descriptor).map(::method).filter {
                Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(scope.name, "int", composer.name, "int") &&
                    it.returnTypeName == "void" && vm.descriptor in code.classTokens(it.descriptor)
            }.one("storage screen"))
            out.method("bar", requireNotNull(screen.declaredClass).methods.filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(scope.name, "int", info.name, composer.name, "int") && it.returnTypeName == "void"
            }.one("storage top bar"))
            val update = out.method("update", method(base.getValue("method:app.zhendong.reamicro.arch.MVIViewModel#updateUiState")))
            check(update.paramTypeNames == listOf(function1.name) && update.returnTypeName == "void")
            out.method("vm", vmConstructor); out.field("kind", fieldFromParameter(vmConstructor, 0))
            out.field("repository", fieldFromParameter(vmConstructor, 2))
            val cache = out.method("cache", vmConstructor.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 2 &&
                it.paramTypeNames[0] == flow.name && vm.fields.any { f -> !Modifier.isStatic(f.modifiers) && f.typeName == it.returnTypeName }
            }.one("storage paging cache"))
            out.type("cachedFlow", requireNotNull(cache.returnType))
            val collectors = calls.calls(vmConstructor.descriptor).filter { call ->
                val candidate = bridge.getMethodData(call.descriptor)
                candidate != null && candidate.isConstructor && candidate.paramTypeNames == listOf(vm.name, continuation.name, "byte") &&
                    call.arguments[1] == HostDexCalls.Parameter(0) && call.arguments[3] is HostDexCalls.Literal
            }
            val ctor = out.method("collector", method(collectors.map { it.descriptor }.distinct().single()))
            val collector = out.type("collector", requireNotNull(ctor.declaredClass))
            val owner = fieldFromParameter(ctor, 0); val tag = fieldFromParameter(ctor, 2)
            out.field("owner", owner); out.field("phase", tag)
            val body = out.method("resume", resume(collector))
            val tags = collectors.map { (it.arguments[3] as HostDexCalls.Literal).value.toInt() }.distinct()
            check(tags.size == 2 && tags.all { it in Byte.MIN_VALUE..Byte.MAX_VALUE })
            val authTag = tags.single { code.branchContains(body.descriptor, tag, it, auth.descriptor) }
            out.value("authPhase", authTag); out.value("infoPhase", tags.single { it != authTag })
            val collect = out.method("collect", body.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 3 &&
                it.paramTypeNames.first() == flow.name && it.paramTypeNames.last() == continuation.name && it.returnTypeName == "java.lang.Object"
            }.one("storage collectLatest"))
            out.type("function2", requireNotNull(collect.paramTypes[1]))
        }
        private fun folders(out: Output) {
            out.type("vm", folderVm); out.type("folder", folder); out.type("args", known(FOLDER_ARGS))
            out.type("continuation", continuation); out.type("repository", repository)
            out.method("vm", folderConstructor); out.field("args", fieldFromParameter(folderConstructor, 0))
            out.field("kind", recordField(FOLDER_ARGS, "type")); out.field("path", recordField(FOLDER, "path"))
            val ctor = out.method("worker", method(folderTask.descriptor))
            out.type("worker", folderWorker)
            val arguments = folderTask.arguments.drop(1)
            val ownerIndex = arguments.indices.single { arguments[it] == HostDexCalls.Parameter(0) }
            // Root folders are allocated separately in native-kind branches, so their merged
            // register intentionally has no allocation identity. The worker's cast proves which
            // captured field contains the selected folder; constructor writes prove its slot.
            val selectedField = calls.casts(resume(folderWorker).descriptor).filter { it.type == folder.descriptor }
                .mapNotNull { (it.value as? HostDexCalls.Field)?.descriptor }.distinct().single()
            val selectedIndex = calls.writes(ctor.descriptor).filter { it.field == selectedField &&
                it.receiver == HostDexCalls.Parameter(0) }.map { (it.value as HostDexCalls.Parameter).index - 1 }.distinct().single()
            val phaseIndex = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == "byte" }
            val phase = (arguments[phaseIndex] as? HostDexCalls.Literal)?.value?.toInt() ?: error("Folder worker phase is not constant")
            val branch = fieldFromParameter(ctor, phaseIndex)
            out.field("owner", fieldFromParameter(ctor, ownerIndex)); out.field("selected", fieldFromParameter(ctor, selectedIndex))
            out.field("phase", branch); out.value("phase", phase)
            val body = out.method("resume", resume(folderWorker))
            val list = out.method("listFolders", operationMethods.getValue("listFolders"))
            check(code.branchContains(body.descriptor, branch, phase, list.descriptor)) { "Folder worker phase does not load folders" }
        }
        private fun tap(out: Output) {
            out.type("vm", vm); out.type("book", book); out.method("vm", vmConstructor)
            out.field("kind", fieldFromParameter(vmConstructor, 0))
            val owner = out.type("tap", anchor("Tap(book=").mapNotNull { it.declaredClass }.distinctBy { it.descriptor }.single {
                it.fields.count { f -> !Modifier.isStatic(f.modifiers) && f.typeName == book.name } == 1 &&
                    it.superClass?.let { event -> vm.methods.any { method -> !Modifier.isStatic(method.modifiers) &&
                        method.paramTypeNames == listOf(event.name) && method.returnTypeName == "void" } } == true
            })
            out.field("book", owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == book.name }.descriptor)
            val event = out.type("event", requireNotNull(owner.superClass))
            val dispatch = out.method("dispatch", vm.methods.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf(event.name) && it.returnTypeName == "void" }.one("storage tap dispatcher"))
            val entry = out.method("entry", vm.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 1 &&
                it.returnTypeName == "void" && it.invokes.any { target -> target.descriptor == dispatch.descriptor }
            }.one("storage intent dispatcher"))
            out.type("intent", requireNotNull(entry.paramTypes.single()))
        }
        private fun imports(out: Output) {
            val repository = out.type("repository", known("app.zhendong.reamicro.repository.BookshelfRepository"))
            val platform = out.type("platform", known("ReaMicroHost.PlatformFile"))
            out.type("continuation", continuation)
            val worker = out.type("worker", requireNotNull(anchor("epubs", "unzip-").one("native import worker").declaredClass))
            val ctor = out.method("worker", worker.methods.filter { it.isConstructor && it.paramTypeNames.firstOrNull() == platform.name }
                .one("native import worker constructor"))
            out.field("originalFile", fieldFromParameter(ctor, 0)); val workerResume = out.method("resume", resume(worker))
            val normal = out.method("normal", repository.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 7 &&
                it.paramTypeNames.first() == platform.name && it.paramTypeNames.subList(3, 5) == listOf("java.lang.String", "java.lang.Long") &&
                it.returnTypeName == "java.io.Serializable" }.one("native book import"))
            val path = out.type("path", known("okio.Path"))
            val opf = out.type("opf", known("org.epub.structure.opf.Opf"))
            val epubImport = out.method("epubDirectory", method(base.getValue("method:app.zhendong.reamicro.arch.EpubFileManager#import")))
            check(Modifier.isStatic(epubImport.modifiers) && epubImport.paramCount == 3 &&
                epubImport.paramTypeNames.take(2) == listOf(path.name, path.name))
            val pair = out.type("pair", requireNotNull(epubImport.returnType))
            check(pair.name == known("kotlin.Pair").name)
            val pairConstructor = out.method("pair", pair.methods.filter { it.isConstructor &&
                it.paramTypeNames == listOf("java.lang.Object", "java.lang.Object") }.one("import result pair"))
            out.field("pair.first", fieldFromParameter(pairConstructor, 0))
            out.field("pair.second", fieldFromParameter(pairConstructor, 1))
            check(epubImport.invokes.any { it.descriptor == pairConstructor.descriptor })
            val function1 = out.type("function1", known("kotlin.jvm.functions.Function1"))
            for ((role, type) in mapOf("normalFile" to platform.name, "normalPath" to path.name, "normalOpf" to opf.name,
                "normalUrl" to "java.lang.String", "normalSize" to "java.lang.Long", "normalProgress" to function1.name)) {
                out.value(role, normal.paramTypeNames.indices.single { normal.paramTypeNames[it] == type })
            }
            out.value("normalContinuation", normal.paramTypes.indices.single { normal.paramTypes[it].isA(continuation) })
            val defaults = out.method("defaults", repository.methods.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 9 &&
                it.paramTypeNames.take(2) == listOf(repository.name, platform.name) && it.paramTypeNames.last() == "int" &&
                it.invokes.any { call -> call.descriptor == normal.descriptor } }.one("native import defaults"))
            val txt = out.method("txt", repository.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 5 &&
                it.paramTypeNames.subList(2, 4) == listOf("java.lang.String", "java.lang.Long") && it.paramTypeNames[1] == normal.paramTypeNames[1] &&
                it.returnTypeName == normal.returnTypeName }.one("TXT converted book import"))
            val txtDefaults = out.method("txtDefaults", repository.methods.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 4 &&
                it.paramTypeNames[0] == repository.name && it.paramTypeNames.last() == worker.name &&
                it.invokes.any { call -> call.descriptor == txt.descriptor } }.one("TXT import defaults"))
            check(workerResume.invokes.any { it.descriptor == defaults.descriptor } &&
                workerResume.invokes.any { it.descriptor == txtDefaults.descriptor }) { "Native worker does not call both import paths" }
            val url = normal.paramTypeNames.indices.single { normal.paramTypeNames[it] == "java.lang.String" }
            val size = normal.paramTypeNames.indices.single { normal.paramTypeNames[it] == "java.lang.Long" }
            val mask = defaults.paramTypeNames.indices.single { defaults.paramTypeNames[it] == "int" }
            // The receiver shifts default-wrapper arguments by one; Kotlin mask bits count the
            // declared parameters. Other defaults stay untouched.
            check(code.defaultMaskBits(defaults.descriptor, mask).containsAll(setOf(1 shl url, 1 shl size)))
            val forwarding = calls.calls(defaults.descriptor).filter { it.descriptor == normal.descriptor }.single()
            check(forwarding.arguments.take(4) == (0..3).map { HostDexCalls.Parameter(it) })
            out.value("file", defaults.paramTypeNames.indices.single { defaults.paramTypeNames[it] == platform.name })
            out.value("url", url + 1); out.value("size", size + 1); out.value("mask", mask)
            out.value("urlBit", 1 shl url); out.value("sizeBit", 1 shl size)
            out.value("txtUrl", txt.paramTypeNames.indices.single { txt.paramTypeNames[it] == "java.lang.String" })
            out.value("txtSize", txt.paramTypeNames.indices.single { txt.paramTypeNames[it] == "java.lang.Long" })
        }
        private fun ui(out: Output) {
            val sheet = known(SHEET)
            val function = out.type("function0", known("kotlin.jvm.functions.Function0"))
            val content = out.method("sheet", method(base.getValue("method:$SHEET#BookLibrarySheet")))
            val composer = out.type("composer", requireNotNull(content.paramTypes[3]))
            out.type("scope", requireNotNull(content.paramTypes[0]))
            val receiver = out.type("receiver", requireNotNull(content.paramTypes[1]))
            out.method("local", sheet.methods.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames ==
                listOf(function.name, composer.name, "int") && it.returnTypeName == "void" }.one("local import row"))
            out.method("authorized", method(base.getValue("method:$SHEET#CloudAuthorizedRow")))
            val unauthorized = out.method("unauthorized", method(base.getValue("method:$SHEET#CloudUnauthRow")))
            val nativeFolder = method(base.getValue("method:${ACCOUNT_SCREENS.getValue("local")}#DefaultFolder"))
            val string = out.method("string", nativeFolder.invokes.filter { Modifier.isStatic(it.modifiers) &&
                it.returnTypeName == "java.lang.String" && it.paramCount == 2 && it.paramTypeNames[1] == composer.name
            }.one("native storage string resource"))
            val resource = out.type("resource", requireNotNull(string.paramTypes[0]))
            val resourceBase = out.type("resourceBase", requireNotNull(resource.superClass))
            val resourceCtor = constructor(resourceBase, "java.lang.String", "java.util.Set")
            out.field("resourceKey", fieldFromParameter(resourceCtor, 0))
            out.method("notAuth", find(MethodMatcher.create().paramTypes("int", function.name, composer.name, "int")
                .returnType("void")).filter { Modifier.isStatic(it.modifiers) && it.descriptor != unauthorized.descriptor &&
                it.invokes.any { target -> target.descriptor == string.descriptor }
            }.one("storage authorization prompt"))
            val hide = out.type("hide", known("ReaMicroHost.HideLibrary"))
            out.field("hide", hide.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == hide.name }.descriptor)
            fun implements(type: ClassData, target: String, seen: MutableSet<String> = hashSetOf()): Boolean =
                seen.add(type.descriptor) && (type.descriptor == target || type.interfaces.any { implements(it, target, seen) } ||
                    type.superClass?.let { implements(it, target, seen) } == true)
            val dispatch = out.method("dismiss", receiver.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 1 &&
                it.returnTypeName == "void" && it.invokes.isNotEmpty() && it.paramTypes.single()?.let { intent ->
                    implements(hide, intent.descriptor)
                } == true }.one("import sheet intent dispatcher"))
            out.type("intent", requireNotNull(dispatch.paramTypes.single()))
        }
        private fun account(out: Output) {
            out.type("flow", flow)
            val sheet = method(base.getValue("method:$SHEET#BookLibrarySheet"))
            val scope = out.type("scope", requireNotNull(sheet.paramTypes[0]))
            val composer = out.type("composer", requireNotNull(sheet.paramTypes[3]))
            val function0 = out.type("function0", known("kotlin.jvm.functions.Function0"))
            val function1 = out.type("function1", known("kotlin.jvm.functions.Function1"))
            out.type("dir", known(DIR))
            val logoutTemplate = method(base.getValue("method:${ACCOUNT_SCREENS.getValue("local")}#LogOut"))
            val logoutDialog = logoutTemplate.invokes.filter { target ->
                val p = target.paramTypeNames
                Modifier.isStatic(target.modifiers) && target.returnTypeName == "void" && p.size == 10 &&
                    p[0] == "java.lang.Object" && p[2] == function1.name && p[4] == "java.lang.String" &&
                    p[5] == function1.name && p[7] == composer.name && p.takeLast(2) == listOf("int", "int")
            }.one("account logout confirmation dialog")
            for ((key, logical) in ACCOUNT_SCREENS) {
                val owner = known(logical)
                val screen = out.method("$key.screen", owner.methods.filter { Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == listOf(scope.name, composer.name, "int") && it.returnTypeName == "void"
                }.one("$key account screen"))
                for ((name, semantic) in mapOf("folder" to "DefaultFolder",
                    "order" to "QueryOrderBy", "direction" to "QueryOrderDirection"))
                    out.method("$key.$name", method(base.getValue("method:$logical#$semantic")))
                out.method("$key.logout", owner.methods.filter { Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == listOf(function0.name, composer.name, "int") && it.returnTypeName == "void" &&
                    it.invokes.any { call -> call.descriptor == logoutDialog.descriptor }
                }.one("$key account logout"))
                val types = code.classTokens(screen.descriptor)
                val nativeVm = out.type("$key.vm", types.mapNotNull(bridge::getClassData).single { type ->
                    type.methods.any { it.isConstructor && it.paramCount == 1 &&
                        it.paramTypeNames.single() == known(if (key == "webdav")
                            "app.zhendong.reamicro.repository.storage.BaiduNetDiskRepository" else
                            "app.zhendong.reamicro.repository.storage.Y115NetDiskRepository").name }
                })
                val ctor = out.method("$key.vm", nativeVm.methods.filter { it.isConstructor && it.paramCount == 1 }.one("$key account VM"))
                val nativeRepository = out.type("$key.repository", requireNotNull(ctor.paramTypes.single()))
                val allocated = calls.calls(screen.descriptor).filter { it.descriptor.contains("-><init>(") }
                    .mapNotNull { bridge.getMethodData(it.descriptor)?.declaredClass }.distinctBy { it.descriptor }
                val retained = allocated.flatMap { it.methods }.filter { !Modifier.isStatic(it.modifiers) &&
                    it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" &&
                    calls.casts(it.descriptor).any { cast -> cast.type == nativeVm.descriptor }
                }.one("$key retained account content")
                out.type("$key.content", requireNotNull(retained.declaredClass))
                out.method("$key.content", retained)
                out.field("$key.contentVm", calls.casts(retained.descriptor).filter { it.type == nativeVm.descriptor }
                    .map { (it.value as HostDexCalls.Field).descriptor }.distinct().single())
                out.method("$key.acquireVm", method(calls.casts(screen.descriptor).single {
                    it.type == nativeVm.descriptor
                }.let { (it.value as HostDexCalls.Call).descriptor }))
                val candidates = allocated.flatMap { it.methods }.filter { code.hasMethod(it.descriptor) }
                    .flatMap { body -> calls.calls(body.descriptor).map { body to it } }
                    .filter { (_, call) -> bridge.getMethodData(call.descriptor)?.let { target ->
                        Modifier.isStatic(target.modifiers) && target.paramTypeNames ==
                            listOf(flow.name, "java.lang.Object", composer.name, "int", "int") } == true
                    }.filter { (_, call) -> (call.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor?.startsWith(nativeVm.descriptor + "->") == true }
                val fields = candidates.map { (it.second.arguments[0] as HostDexCalls.Field).descriptor }.distinct()
                out.field("$key.auth", fields.single())
                check(calls.writes(ctor.descriptor).any { it.field == fields.single() && it.receiver == HostDexCalls.Parameter(0) &&
                    (it.value as? HostDexCalls.Field)?.descriptor?.startsWith(nativeRepository.descriptor + "->") == true })
                out.method("$key.collect", method(candidates.map { it.second.descriptor }.distinct().single()))
                val authType = out.type("$key.auth", known(if (key == "webdav") AUTH else LOCAL_AUTH))
                check(candidates.any { (_, call) -> (call.arguments[1] as? HostDexCalls.Allocation)?.type == authType.descriptor })
            }
        }
        private fun routes(out: Output) {
            val nav = HostRuntimeQueries.navigation(bridge)
            for (key in listOf("class:scope", "class:controller", "method:navigate", "method:pop", "method:controllerPop", "field:controller")) {
                out.values[key] = nav.getValue(key)
            }
            val homeNavigation = calls.calls(nav.getValue("method:pop")).single {
                bridge.getMethodData(it.descriptor)?.let { target ->
                    target.declaredClass?.descriptor == nav.getValue("class:controller") &&
                        target.paramTypeNames == listOf("java.lang.Object", "kotlin.jvm.functions.Function1")
                } == true
            }
            val homeField = requireNotNull(bridge.getFieldData((homeNavigation.arguments[1] as HostDexCalls.Field).descriptor))
            check(Modifier.isStatic(homeField.modifiers))
            val home = out.type("home", requireNotNull(homeField.type))
            val homeInit = home.methods.filter { it.name == "<clinit>" }.one("home route singleton")
            check(calls.writes(homeInit.descriptor).any { it.field == homeField.descriptor &&
                (it.value as? HostDexCalls.Allocation)?.type == home.descriptor })
            out.field("home", homeField.descriptor)
            val storage = out.type("storage", known("app.zhendong.reamicro.Route\$Storage"))
            out.method("storage", constructor(storage, "int")); out.field("storageKind", recordField("app.zhendong.reamicro.Route\$Storage", "type"))
            for ((key, label) in listOf("login" to "ThirdLogin(type=", "account" to "ThirdAccount(type=")) {
                val route = out.type(key, anchor(label).mapNotNull { it.declaredClass }.distinctBy { it.descriptor }.single {
                    it.fields.filter { f -> !Modifier.isStatic(f.modifiers) }.let { fields -> fields.size == 1 && fields.single().typeName == "int" }
                })
                val ctor = out.method(key, constructor(route, "int")); out.field("$key.kind", fieldFromParameter(ctor, 0))
                route(out, key, route, nav.getValue("class:scope"))
            }
            val folderRoute = out.type("folder", known("app.zhendong.reamicro.Route\$CloudFolder"))
            val constructor = out.method("folder", folderRoute.methods.filter { it.isConstructor && it.paramTypeNames ==
                listOf("int", "java.lang.String", "java.lang.Long", "int") }.one("cloud folder route constructor"))
            out.field("folder.kind", recordField("app.zhendong.reamicro.Route\$CloudFolder", "type"))
            out.field("folder.path", recordField("app.zhendong.reamicro.Route\$CloudFolder", "path"))
            out.field("folder.bookId", recordField("app.zhendong.reamicro.Route\$CloudFolder", "bookId"))
            check(fieldFromParameter(constructor, 0) == out.values["field:folder.kind"])
            check(code.defaultMaskBits(constructor.descriptor, 3).containsAll(setOf(2, 4)))
        }
        private fun route(out: Output, key: String, route: ClassData, scope: String) {
            val routeValue = HostDexCalls.Type(route.descriptor)
            val registrations = calls.referencingType(route.descriptor).flatMap { caller -> calls.calls(caller).filter { call ->
                call.arguments.size == 3 && contains(call.arguments[1], routeValue) && call.arguments[2] is HostDexCalls.Allocation &&
                    method(call.descriptor).let { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 3 }
            }.map { caller to it } }.distinct()
            val (setup, registration) = registrations.single()
            out.method("$key.setup", method(setup)); val register = out.method("$key.register", method(registration.descriptor))
            val tokenCall = registration.arguments[1] as? HostDexCalls.Call ?: error("Route class token is not a conversion")
            val token = out.method("$key.token", method(tokenCall.descriptor))
            check(token.paramTypeNames == listOf("java.lang.Class") && token.returnTypeName == register.paramTypeNames[1])
            if (!Modifier.isStatic(token.modifiers)) out.field("$key.tokenReceiver", (tokenCall.arguments.first() as HostDexCalls.Field).descriptor)
            val wrapper = registration.arguments[2] as HostDexCalls.Allocation
            val init = calls.calls(setup).single { it.arguments.firstOrNull() == wrapper && it.descriptor.contains("-><init>(IZLjava/lang/Object;)V") }
            val content = init.arguments[3] as? HostDexCalls.Allocation ?: error("Route content allocation unavailable")
            val contentType = out.type("$key.content", requireNotNull(bridge.getClassData(content.type)))
            val contentInit = calls.calls(setup).single {
                it.arguments.firstOrNull() == content && method(it.descriptor).isConstructor
            }
            val branches = calls.writes(contentInit.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
                .mapNotNull { write ->
                    val parameter = write.value as? HostDexCalls.Parameter ?: return@mapNotNull null
                    val literal = contentInit.arguments.getOrNull(parameter.index) as? HostDexCalls.Literal
                        ?: return@mapNotNull null
                    val field = requireNotNull(bridge.getFieldData(write.field))
                    check(field.declaredClassName == contentType.name && !Modifier.isStatic(field.modifiers) &&
                        Modifier.isFinal(field.modifiers) && field.typeName in
                        setOf("byte", "short", "int", "long", "boolean", "char"))
                    write.field to literal.value
                }.distinct().sortedBy { it.first }
            out.value("$key.branches", branches.size)
            branches.forEachIndexed { index, (field, value) ->
                out.field("$key.branch.$index", field)
                out.values["value:$key.branch.$index"] = value.toString()
            }
            out.field("$key.scope", contentType.fields.single { !Modifier.isStatic(it.modifiers) && it.type.descriptor == scope }.descriptor)
            val render = out.method("$key.render", contentType.methods.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("$key content"))
            val decode = calls.calls(render.descriptor).single { call -> call.arguments.size == 2 && contains(call.arguments[1], routeValue) &&
                method(call.descriptor).let { Modifier.isStatic(it.modifiers) && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" &&
                    it.paramTypeNames[1] == token.returnTypeName } }
            out.method("$key.decode", method(decode.descriptor)); check(decode.arguments[0] == HostDexCalls.Parameter(2))
            val lambda = out.type("$key.lambda", requireNotNull(bridge.getClassData(wrapper.type)))
            val invoke = lambda.methods.filter { it.paramTypeNames == List(4) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object" }.one("route lambda")
            val body = invoke.invokes.filter { it.className == lambda.name && it.paramCount == 4 &&
                it.paramTypeNames.take(2) == List(2) { "java.lang.Object" } && it.paramTypeNames.last() == "int" }.one("route lambda body")
            out.method("$key.invoke", invoke)
            out.method("$key.body", body)
            out.type("composer", requireNotNull(body.paramTypes[2]))
            val callback = body.invokes.filter { it.declaredClass?.let { c -> Modifier.isInterface(c.modifiers) } == true &&
                it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("route callback")
            val receiver = calls.calls(body.descriptor).single { it.descriptor == callback.descriptor }.arguments.first()
            out.field("$key.callback", (receiver as? HostDexCalls.Field)?.descriptor ?: error("Route callback is not a field"))
        }
    }

    /** Only the small bytecode facts not exposed by DexKit's reference graph are interpreted here. */
    private class StorageCode(apk: String) {
        private val methods: Map<String, Method> = HostDexIndex.forApk(apk).methods
        fun hasMethod(id: String): Boolean = id in methods
        fun classTokens(id: String): Set<String> = (methods.getValue(id).implementation?.instructions ?: emptyList()).mapNotNull {
            if (it.opcode.toString() == "CONST_CLASS") ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type else null
        }.toSet()
        fun constructorOrigins(id: String): Map<String, Int> {
            val method = methods.getValue(id); val body = requireNotNull(method.implementation)
            val instructions = body.instructions.toList()
            if (body.tryBlocks.isNotEmpty() || instructions.any { it is OffsetInstruction }) return emptyMap()
            val registers = HashMap<Int, Int>()
            var register = body.registerCount - 1 - method.parameterTypes.sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
            registers[register++] = -1
            method.parameterTypes.forEachIndexed { index, type -> registers[register] = index; register += if (type.toString() in setOf("J", "D")) 2 else 1 }
            val result = linkedMapOf<String, Int>()
            for (i in instructions) {
                val op = i.opcode.toString(); val one = i as? OneRegisterInstruction; val two = i as? TwoRegisterInstruction
                val reference = (i as? ReferenceInstruction)?.reference
                when {
                    (op.startsWith("MOVE") && !op.startsWith("MOVE_RESULT") || op in setOf("INT_TO_BYTE", "INT_TO_SHORT", "INT_TO_CHAR")) && two != null -> {
                        registers[two.registerB]?.let { registers[two.registerA] = it } ?: registers.remove(two.registerA)
                    }
                    op.startsWith("IPUT") && two != null && reference is FieldReference -> {
                        if (registers[two.registerB] != -1 || reference.definingClass != method.definingClass) continue
                        val origin = registers[two.registerA] ?: return emptyMap()
                        if (result.put(field(reference), origin)?.let { it != origin } == true) return emptyMap()
                    }
                    i.opcode.setsRegister() && one != null -> registers.remove(one.registerA)
                }
            }
            return result
        }
        fun defaultMaskBits(id: String, parameter: Int): Set<Int> {
            val method = methods.getValue(id); val body = requireNotNull(method.implementation)
            var register = body.registerCount - (if (method.accessFlags and 8 == 0) 1 else 0) -
                method.parameterTypes.sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
            if (method.accessFlags and 8 == 0) register++
            register += method.parameterTypes.take(parameter).sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
            return body.instructions.mapNotNull { i ->
                if (i.opcode.toString() !in setOf("AND_INT_LIT8", "AND_INT_LIT16") || i !is TwoRegisterInstruction ||
                    i.registerB != register) null else (i as? NarrowLiteralInstruction)?.narrowLiteral
            }.toSet()
        }
        fun branchContains(id: String, discriminator: String, tag: Int, target: String): Boolean =
            branchMatches(id, discriminator, tag) { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                instruction.opcode.toString().startsWith("INVOKE") && reference is MethodReference && descriptor(reference) == target
            }
        fun branchString(id: String, discriminator: String, tag: Int, text: String): Boolean =
            branchMatches(id, discriminator, tag) { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is StringReference && reference.string == text
            }
        private fun branchMatches(id: String, discriminator: String, tag: Int, matches: (Instruction) -> Boolean): Boolean {
            val body = requireNotNull(methods.getValue(id).implementation); val code = body.instructions.toList()
            val offsets = IntArray(code.size); var offset = 0
            code.forEachIndexed { index, instruction -> offsets[index] = offset; offset += instruction.codeUnits }
            val at = offsets.withIndex().associate { it.value to it.index }
            val switches = code.indices.filter { index ->
                val i = code[index]; if (i.opcode.toString() !in setOf("PACKED_SWITCH", "SPARSE_SWITCH")) return@filter false
                var register = (i as OneRegisterInstruction).registerA
                var before = index
                while (before > 0) {
                    val source = (before - 1 downTo 0).firstOrNull { p -> code[p].opcode.setsRegister() &&
                        (code[p] as? OneRegisterInstruction)?.registerA == register } ?: return@filter false
                    val instruction = code[source]; val op = instruction.opcode.toString()
                    if (op.startsWith("MOVE") && !op.startsWith("MOVE_RESULT") && instruction is TwoRegisterInstruction) {
                        register = instruction.registerB; before = source
                        continue
                    }
                    val reference = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: return@filter false
                    return@filter op.startsWith("IGET") && field(reference) == discriminator
                }
                false
            }
            val index = switches.singleOrNull() ?: return false
            val switch = code[index] as OffsetInstruction
            val payload = at[offsets[index] + switch.codeOffset]?.let { code[it] } as? SwitchPayload ?: return false
            val targets = payload.switchElements.associate { it.key to requireNotNull(at[offsets[index] + it.offset]) }
            val start = targets[tag] ?: index + 1
            val boundaries = targets.values.toSet() + (index + 1)
            val pending = ArrayDeque<Int>(); val seen = HashSet<Int>(); pending.add(start)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current !in code.indices || !seen.add(current) || current != start && current in boundaries) continue
                val instruction = code[current]; val op = instruction.opcode.toString()
                if (matches(instruction)) return true
                if (op.startsWith("RETURN") || op == "THROW" || instruction is SwitchPayload) continue
                if (!op.startsWith("GOTO")) pending.add(current + 1)
                if (instruction is OffsetInstruction && (op.startsWith("IF_") || op.startsWith("GOTO")))
                    at[offsets[current] + instruction.codeOffset]?.let(pending::add)
                if (instruction is OffsetInstruction && op in setOf("PACKED_SWITCH", "SPARSE_SWITCH")) {
                    val nested = at[offsets[current] + instruction.codeOffset]?.let { code[it] } as? SwitchPayload ?: return false
                    nested.switchElements.forEach { at[offsets[current] + it.offset]?.let(pending::add) }
                }
            }
            return false
        }
        companion object {
            fun descriptor(m: MethodReference) = "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"
            fun field(f: FieldReference) = "${f.definingClass}->${f.name}:${f.type}"
        }
    }
}
