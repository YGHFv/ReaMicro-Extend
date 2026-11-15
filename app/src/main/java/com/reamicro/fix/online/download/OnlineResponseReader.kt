package com.reamicro.fix.online.download

import java.io.Reader

/** Bound a source's HTML/JSON response and observe cancellation while it is being read. */
internal fun readOnlineResponseText(
    reader: Reader,
    checkCurrent: () -> Unit,
    maximumCharacters: Int = 16 * 1024 * 1024,
): String {
    require(maximumCharacters > 0)
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        checkCurrent()
        val count = reader.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        check(count <= maximumCharacters - result.length) { "在线源响应过大，请检查书源规则" }
        result.append(buffer, 0, count)
    }
    checkCurrent()
    return result.toString()
}
