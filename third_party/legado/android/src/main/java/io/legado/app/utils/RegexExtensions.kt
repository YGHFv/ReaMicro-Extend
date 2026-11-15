package io.legado.app.utils

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.platform.ParserRepository

/**
 * Upstream global-title replacement algorithm, when the runtime permits an in-matcher deadline.
 * Android explicitly rejects this optional application capability instead of restarting the host
 * or leaving an uninterruptible native matcher behind. Source AnalyzeRule ## rules are separate.
 */
fun CharSequence.replace(
    regex: Regex,
    replacement: String,
    timeout: Long,
    chapter: BookChapter? = null,
    book: SearchBook? = null,
): String = replace(regex, replacement, timeout, chapter, book, onReplace = null)

internal fun CharSequence.replace(
    regex: Regex,
    replacement: String,
    timeout: Long,
    chapter: BookChapter? = null,
    book: SearchBook? = null,
    onReplace: ((Int, Int, Int) -> Unit)?,
): String {
    if (!RegexDeadlineSequence.matcherSupportsDeadline) {
        throw UnsupportedOperationException(
            "此运行时的 native 正则不支持可中断超时；未启用 Legado 全局标题正则替换"
        )
    }
    val charSequence = RegexDeadlineSequence(this@replace, timeout)
    val isJs = replacement.startsWith("@js:")
    val replacement1 = if (isJs) replacement.substring(4) else replacement
    val book = if (isJs) {
        book ?: chapter?.bookUrl?.let {
            ParserRepository.book(it)?.toSearchBook()
        }
    } else {
        null
    }
    val pattern = regex.toPattern()
    val matcher = pattern.matcher(charSequence)
    val stringBuffer = StringBuffer()
    var previousEnd = 0
    while (matcher.find()) {
        val previousLength = stringBuffer.length
        if (isJs) {
            val jsResult = RhinoScriptEngine.run {
                val bindings = ScriptBindings()
                bindings["result"] = matcher.group()
                bindings["chapter"] = chapter
                bindings["book"] = book
                eval(replacement1, bindings, com.script.rhino.rhinoContextOrNull?.coroutineContext)
            }.toString()
            val quotedResult = jsResult.quoteReplacementJs()
            matcher.appendReplacement(stringBuffer, quotedResult)
        } else {
            matcher.appendReplacement(stringBuffer, replacement1)
        }
        if (onReplace != null) {
            val replacementStart = previousLength + matcher.start() - previousEnd
            val count = stringBuffer.length - replacementStart
            var unchanged = count == matcher.end() - matcher.start()
            if (unchanged) for (index in 0 until count) {
                if (charSequence[matcher.start() + index] != stringBuffer[replacementStart + index]) {
                    unchanged = false
                    break
                }
            }
            if (!unchanged) onReplace(matcher.start(), matcher.end(), count)
        }
        previousEnd = matcher.end()
    }
    matcher.appendTail(stringBuffer)
    return stringBuffer.toString()
}
