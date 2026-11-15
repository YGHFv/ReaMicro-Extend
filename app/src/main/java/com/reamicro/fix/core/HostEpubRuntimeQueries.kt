package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Native EPUB records shared by search, chapter loading and reader context. */
internal object HostEpubRuntimeQueries {
    private const val P = "app.zhendong.reamicro."
    val records = linkedMapOf(
        "epub" to (P + "data.epub.Epub" to "id directory itemRefs spineCfiIndex chapters bookmarks tags".split(' ')),
        "item" to (P + "data.db.entity.BookItemRef" to "id bookId index spineIndex idRef href properties size".split(' ')),
        "chapter" to (P + "data.db.entity.BookChapter" to "id bookId playOrder title href anchor epubcfi parentOrder".split(' ')),
        "book" to (P + "data.db.entity.Book" to "id uid uuid title subtitle uri cover chapter epubcfi".split(' ')),
        "page" to (P + "data.epub.EpubPage" to "document start end summary".split(' ')),
        "state" to (P + "ui.reader.ReaderUiState" to "book catalog catalogIndex".split(' ')),
        "catalogItem" to (P + "ui.reader.CatalogChapterItem" to "bookmark mark progressCfi isCurrentProgress".split(' ')),
        "bookmark" to (P + "data.reader.Bookmark" to "chapter title summary epubcfi remark type".split(' ')),
    )
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("EPUB runtime $role is missing or ambiguous")
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        for ((role, schema) in records) {
            put("class:$role", base.getValue("class:${schema.first}"))
            for (name in schema.second) put("field:$role.$name", base.getValue("field:${schema.first}#$name"))
        }
        fun type(role: String) = requireNotNull(bridge.getClassData(getValue("class:$role")))
        put("class:vm", base.getValue("class:${P}ui.reader.ReaderViewModel"))
        put("class:continuation", base.getValue("class:kotlin.coroutines.Continuation"))
        val create = known("org.epub.html.EpubCFI\$Companion", "create")
        put("method:cfi", create.descriptor); put("class:cfi", requireNotNull(create.returnType).descriptor)
        for ((role, owner, name) in listOf(
            Triple("rootStyle", "org.epub.UIEpubWindow", "getRootStyle"), Triple("current", P + "arch.MVIViewModel", "getCurrentState"),
            Triple("read", P + "data.epub.Epub", "read"), Triple("readElements", P + "data.epub.Epub", "readElements"),
            Triple("resolvePath", P + "data.epub.Epub", "resolveHtmlPath"),
            Triple("catalogIndex", P + "ui.reader.ReaderViewModel", "resolveCatalogIndexByCfi"),
            Triple("chapterAnchor", P + "ui.reader.ReaderViewModel", "resolveChapterAnchor"),
        )) put("method:$role", known(owner, name).descriptor)
        val chapter = type("chapter")
        val chapterCfi = chapter.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == create.returnTypeName }
        val chapterAnchor = known(P + "ui.reader.ReaderViewModel", "resolveChapterAnchor")
        check(chapterAnchor.usingFields.any { it.field.descriptor == chapterCfi.descriptor } || chapterAnchor.invokes.any {
            it.declaredClassName == chapter.name && it.paramCount == 0 && it.returnTypeName == create.returnTypeName &&
                it.usingFields.any { use -> use.field.descriptor == chapterCfi.descriptor }
        })
        put("field:chapter.cfi", chapterCfi.descriptor)
        val scheduler = requireNotNull(bridge.getClassData(base.getValue("class:kotlinx.coroutines.scheduling.DefaultIoScheduler")))
        put("class:io", scheduler.descriptor)
        put("field:io", scheduler.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == scheduler.name }.descriptor)
        val page = type("page")
        val anchor = page.methods.filter { it.isConstructor }.flatMap { ctor -> flow.writes(ctor.descriptor) }.filter { write ->
            val call = write.value as? HostDexCalls.Call
            write.receiver == HostDexCalls.Parameter(0) && write.field.endsWith(":Ljava/lang/String;") &&
                call?.descriptor == base.getValue("method:org.epub.html.EpubCFI#toString")
        }.map { it.field }.distinct().single()
        put("field:page.anchor", anchor)
        put("field:page.chapter", page.fields.single { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.String" }.descriptor)
        for (role in listOf("catalogItem", "bookmark")) {
        val record = type(role)
        val fields = records.getValue(role).second.associateWith { getValue("field:$role.$it") }
        val ctor = record.methods.filter { it.isConstructor && it.paramCount == fields.size &&
            flow.writes(it.descriptor).filter { w -> w.receiver == HostDexCalls.Parameter(0) }.map { w -> w.field }.toSet() == fields.values.toSet()
        }.one("$role constructor")
        put("constructor:$role", ctor.descriptor)
        for ((name, descriptor) in fields) {
            val parameter = flow.writes(ctor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) && it.field == descriptor }.value
                as? HostDexCalls.Parameter ?: error("$role $name constructor source unavailable")
            put("value:$role.$name", (parameter.index - 1).toString())
        }
        }
        for ((role, property) in listOf("MarkJump" to "mark", "BookmarkJump" to "bookmark")) {
            val intent = requireNotNull(bridge.getClassData(base.getValue("class:${P}ui.reader.ReaderUiIntent\$$role")))
            val data = requireNotNull(bridge.getFieldData(base.getValue("field:${P}ui.reader.ReaderUiIntent\$$role#$property")))
            put("class:intent.$role", intent.descriptor); put("field:intent.$role", data.descriptor)
            put("constructor:$role", intent.methods.filter { it.isConstructor && it.paramTypeNames == listOf(data.typeName) }
                .one("$role constructor").descriptor)
        }
        putAll(HostReaderStateQueries.query(bridge, apk, base))
    }
}
