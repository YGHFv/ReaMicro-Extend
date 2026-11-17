package com.reamicro.fix.epub.editor

/** 宿主解析器可能保留 CRLF，而实体解码会转为 LF；仅统一换行后比较，不折叠空格或正文。 */
internal fun selectionSourceTextMatches(actual: String, expected: String): Boolean {
    fun normalize(value: String) = value.replace("\r\n", "\n").replace('\r', '\n')
    return actual == expected || normalize(actual) == normalize(expected)
}
