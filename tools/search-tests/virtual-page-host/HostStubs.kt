package app.zhendong.reamicro.ui.reader

import app.zhendong.reamicro.data.epub.Epub
import kotlin.coroutines.Continuation

internal class ReaderViewModel {
    val virtualPages = linkedMapOf<Int, Any>()
    val spinePagesCache = linkedMapOf<Int, Any>()
    fun ensureVirtualPageWindow(page: Int, epub: Epub?, buffer: Int, continuation: Continuation<Unit>): Any = Unit
    fun ensureVirtualPageMapped(page: Int, epub: Epub?, continuation: Continuation<Unit>): Any = Unit
}
