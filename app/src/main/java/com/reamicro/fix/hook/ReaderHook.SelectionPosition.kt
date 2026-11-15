package com.reamicro.fix.hook

import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostReaderPositionBindings
import com.reamicro.fix.epub.editor.ReaderPositionCopy

internal fun ReaderHook.persistSelectionReadingPosition(viewModel: Any, epub: Any, anchor: Any) {
    checkSelectionEditSession(viewModel, epub)
    val raw = anchor.toString()
    require(raw.isNotBlank()) { "当前阅读锚点为空" }
    val symbols = requireNotNull(HostReaderPositionBindings.current) { "宿主阅读进度接口尚未定位" }
    val state = symbols.method("currentState").invoke(viewModel)
    val book = symbols.field("state#book").get(state) ?: error("无法取得本地阅读进度")
    val repository = symbols.field("vm.repository").get(viewModel) ?: error("宿主阅读仓库不存在")
    val page = symbols.page(viewModel, anchor)
    val percent = if (page == null) symbols.field("book#progress").getFloat(book)
        else symbols.field("page.progress").getFloat(page)
    val chapter = (if (page == null) symbols.field("book#chapter").get(book)
        else symbols.field("page.chapter").get(page)) as String
    val previousVersion = symbols.field("book#updated").getLong(book)
    val version = symbols.method("version").invoke(viewModel, previousVersion) as Long
    val patch = ReaderPositionCopy.copy(book, raw, chapter, percent, version)
    checkSelectionEditSession(viewModel, epub)
    val saved = HostCoroutines.invokeTimed(classLoader, symbols.method("save"), repository, 10_000L,
        patch, true, previousVersion) ?: error("宿主没有返回保存后的阅读进度")
    check(symbols.field("book#uuid").get(saved) == symbols.field("book#uuid").get(book) &&
        symbols.field("book#epubcfi").get(saved) == raw) {
        "阅读进度已被其他操作更新，当前锚点未确认保存，请重新选择"
    }
    checkSelectionEditSession(viewModel, epub)
    val savedVersion = symbols.field("book#updated").getLong(saved)
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    check(symbols.type("reducer") == ui.type("function1"))
    val update = ui.function(ui.type("function1"), fallback = { args -> args?.firstOrNull() }) { args ->
        val current = requireNotNull(args?.firstOrNull())
        val currentBook = symbols.field("state#book").get(current)
        if (currentBook == null || symbols.field("book#uuid").get(currentBook) != symbols.field("book#uuid").get(saved) ||
            symbols.field("book#updated").getLong(currentBook) > savedVersion) current
        else symbols.copy("state", current, mapOf("book" to saved))
    }
    symbols.method("updateState").invoke(viewModel, update)
    val committed = symbols.field("state#book").get(symbols.method("currentState").invoke(viewModel))
    if (committed != null && symbols.field("book#uuid").get(committed) == symbols.field("book#uuid").get(saved) &&
        symbols.field("book#updated").getLong(committed) == savedVersion && symbols.field("book#epubcfi").get(committed) == raw) {
        symbols.field("vm.visible").set(viewModel, raw)
    }
}
