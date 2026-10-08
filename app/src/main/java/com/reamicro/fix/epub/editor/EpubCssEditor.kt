package com.reamicro.fix.epub.editor

import java.io.File

internal object EpubCssEditor {
    const val MAX_SOURCE_DIALOG_BYTES = 256L * 1024L
    const val MAX_VALUE_CHARS = 128 * 1024
    private const val MAX_CSS_BYTES = 8L * 1024L * 1024L

    data class Document(val loaded: LoadedEpubText, val css: CssSourceDocument)
    data class Saved(val snapshot: EpubTextSnapshot, val changed: Boolean)

    fun load(file: File, sourceDialog: Boolean = false): LoadedEpubText {
        require(file.isFile && file.extension.equals("css", true)) { "不是 CSS 文件" }
        val limit = if (sourceDialog) MAX_SOURCE_DIALOG_BYTES else MAX_CSS_BYTES
        require(file.length() <= limit) {
            if (sourceDialog) "CSS 较大，请使用逐项编辑；源码弹窗最多支持 256KB" else "CSS 超出安全编辑范围"
        }
        return EpubTextFiles.load(file, minOf(limit, EpubTextFiles.availableLoadBudget()))
    }

    fun read(file: File): Document = load(file).let { Document(it, CssSourceDocument.parse(it.text)) }

    private fun current(file: File, expectedSha256: String, sourceDialog: Boolean = false): LoadedEpubText {
        require(Regex("[0-9a-f]{64}").matches(expectedSha256)) { "缺少 CSS 文件版本，请刷新后重试" }
        val loaded = load(file, sourceDialog)
        require(loaded.snapshot.fingerprint.sha256 == expectedSha256) {
            "CSS 文件已被其他操作修改，保存已取消，请刷新后重新编辑"
        }
        return loaded
    }

    private fun save(file: File, loaded: LoadedEpubText, next: String): Saved {
        if (next == loaded.text) return Saved(loaded.snapshot, false)
        return Saved(EpubTextFiles.save(file, next, loaded.snapshot), true)
    }

    fun saveValue(
        file: File, expectedSha256: String, ruleId: Int, declarationId: Int,
        value: String,
    ): Saved {
        require(value.length <= MAX_VALUE_CHARS) { "CSS 属性值过长，不能在弹窗中安全编辑" }
        val loaded = current(file, expectedSha256)
        val css = CssSourceDocument.parse(loaded.text)
        val target = css.declaration(ruleId, declarationId)
        require(target.valueEnd - target.valueStart <= MAX_VALUE_CHARS) { "原属性值过长，不能在弹窗中安全编辑" }
        return save(file, loaded, css.replaceValue(ruleId, declarationId, value))
    }

    fun saveSelector(file: File, expectedSha256: String, ruleId: Int, selector: String): Saved {
        require(selector.length <= 16_384) { "选择器过长" }
        val loaded = current(file, expectedSha256)
        val css = CssSourceDocument.parse(loaded.text)
        return save(file, loaded, css.replaceSelector(ruleId, selector))
    }

    fun saveSource(file: File, expectedSha256: String, source: String): Saved {
        require(source.length <= MAX_SOURCE_DIALOG_BYTES) { "CSS 源码超出弹窗安全编辑范围" }
        val loaded = current(file, expectedSha256, sourceDialog = true)
        val ending = Regex("\\r\\n|\\r|\\n").find(loaded.text)?.value ?: "\n"
        val next = source.replace("\r\n", "\n").replace('\r', '\n').replace("\n", ending)
        val encoded = loaded.snapshot.format.charset.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(next))
        require(encoded.remaining().toLong() + loaded.snapshot.format.bom.size <= MAX_SOURCE_DIALOG_BYTES) {
            "CSS 编码后的内容超过 256KB，未修改原文件"
        }
        CssSourceDocument.parse(next)
        return save(file, loaded, next)
    }
}
