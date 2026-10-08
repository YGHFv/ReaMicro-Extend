package com.reamicro.fix.epub.editor

internal object EpubSourceLayoutSafety {
    const val MAX_LINE_CHARS = 32768
    fun requireEditable(text: String) {
        var count = 0
        for (character in text) {
            count = if (character == '\n' || character == '\r') 0 else count + 1
            require(count <= MAX_LINE_CHARS) {
                "文件含超过 $MAX_LINE_CHARS 字符的超长单行，自动换行排版可能卡顿，未载入编辑画布、未修改文件。目录列表仍可使用；请先在外部工具分行后再编辑。"
            }
        }
    }
}
