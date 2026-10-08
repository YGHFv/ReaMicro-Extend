package com.reamicro.fix.epub.editor

internal class CssSourceDocument private constructor(
    val text: String,
    val rules: List<Rule>,
) {
    data class Declaration(
        val id: Int, val property: String,
        val valueStart: Int, val valueEnd: Int,
        val importantBang: Int = -1, val importantEnd: Int = -1,
        val comment: String = "",
    ) { val important: Boolean get() = importantBang >= 0 }

    data class Rule(
        val id: Int, val selectorEnd: Int, val bodyStart: Int, val bodyEnd: Int,
        val selector: String, val context: List<String>, val comment: String,
        val declarations: List<Declaration>, val hasChildren: Boolean,
    )

    fun value(declaration: Declaration): String = text.substring(declaration.valueStart, declaration.valueEnd)
    fun rule(id: Int): Rule = rules.firstOrNull { it.id == id } ?: error("CSS 规则位置已失效，请刷新")
    fun declaration(ruleId: Int, id: Int): Declaration =
        rule(ruleId).declarations.firstOrNull { it.id == id } ?: error("CSS 声明位置已失效，请刷新")

    fun replaceValue(ruleId: Int, declarationId: Int, value: String): String {
        val declaration = declaration(ruleId, declarationId)
        val next = normalizeNewlines(value.trim())
        validateValue(declaration.property, next)
        val prefix = text.substring(0, declaration.valueStart) + next
        return prefix + text.substring(declaration.valueEnd)
    }

    fun replaceSelector(ruleId: Int, value: String): String {
        val target = rule(ruleId)
        val next = normalizeNewlines(value.trim())
        require(next.isNotEmpty()) { "选择器不能为空" }
        val lex = Lexer(next)
        require(lex.tokens.any { it.kind != 'c' }) { "选择器不能只有注释" }
        var i = 0
        while (i < lex.tokens.size) {
            val token = lex.tokens[i]
            require(token.kind !in charArrayOf('{', '}', ';')) { "这里只能修改选择器，不能插入其他规则" }
            i = if (token.kind == '(' || token.kind == '[') lex.pairs[i] + 1 else i + 1
        }
        return text.substring(0, target.id) + next + text.substring(target.selectorEnd)
    }

    fun normalizeNewlines(value: String): String {
        val ending = Regex("\\r\\n|\\r|\\n").find(text)?.value ?: "\n"
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", ending)
    }

    companion object {
        private const val MAX_TOKENS = 250_000
        private const val MAX_RULES = 20_000
        private const val MAX_DECLARATIONS = 50_000
        private const val MAX_DEPTH = 64
        private val propertyName = Regex("""(?:--)?[-_a-zA-Z\u0080-\uFFFF][-_a-zA-Z0-9\u0080-\uFFFF]*""")

        fun parse(text: String): CssSourceDocument {
            val parser = Parser(text)
            parser.parseTop(0, parser.lex.tokens.size, emptyList(), 0)
            return CssSourceDocument(text, parser.rules.sortedBy { it.id })
        }

        fun validateValue(property: String, value: String) {
            require(value.isNotBlank()) { "CSS 属性值不能为空" }
            val lex = Lexer(value)
            val significant = lex.tokens.indices.filter { lex.tokens[it].kind != 'c' }
            require(significant.isNotEmpty()) { "CSS 属性值不能只有注释" }
            var i = 0
            while (i < lex.tokens.size) {
                val t = lex.tokens[i]
                require(t.kind != ';') { "属性值中不能插入其他声明" }
                if (t.kind == '{') require(property.startsWith("--")) { "普通属性值中不能插入规则块" }
                i = if (t.kind in charArrayOf('(', '[', '{')) lex.pairs[i] + 1 else i + 1
            }
            if (significant.size >= 2) {
                val last = lex.tokens[significant.last()]
                val previous = lex.tokens[significant[significant.lastIndex - 1]]
                require(!(previous.kind == '!' && last.kind == 'w' &&
                    value.substring(last.start, last.end).equals("important", true))) {
                    "CSS 属性值格式无效"
                }
            }
        }

        private fun cleanComment(text: String): String =
            text.removePrefix("/*").removeSuffix("*/").take(2048).trim()
    }

    private data class Token(val kind: Char, val start: Int, val end: Int)

    private class Lexer(val source: String) {
        val tokens = ArrayList<Token>()
        val pairs: IntArray
        init {
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && source.getOrNull(i + 1) == '*' -> {
                        val end = source.indexOf("*/", i + 2)
                        require(end >= 0) { "CSS 注释未闭合（位置 $i）" }
                        tokens.add(Token('c', i, end + 2)); i = end + 2
                    }
                    c == '"' || c == '\'' -> {
                        val start = i++
                        var closed = false
                        while (i < source.length) {
                            when (source[i]) {
                                '\\' -> {
                                    i++
                                    require(i < source.length) { "CSS 字符串转义未完成（位置 $start）" }
                                    if (source[i] == '\r' && source.getOrNull(i + 1) == '\n') i++
                                    i++
                                }
                                c -> { i++; closed = true; break }
                                '\r', '\n' -> error("CSS 字符串中存在未转义换行（位置 $i）")
                                else -> i++
                            }
                        }
                        require(closed) { "CSS 字符串未闭合（位置 $start）" }
                        tokens.add(Token('s', start, i))
                    }
                    c in "{}()[]:;!" -> { tokens.add(Token(c, i, i + 1)); i++ }
                    else -> {
                        val start = i
                        while (i < source.length) {
                            val ch = source[i]
                            if (ch == '\\') {
                                require(i + 1 < source.length) { "CSS 转义未完成（位置 $i）" }
                                i += 2
                            } else if (ch.isWhitespace() || ch in "{}()[]:;!\"'" ||
                                (ch == '/' && source.getOrNull(i + 1) == '*')) break
                            else i++
                        }
                        require(i > start)
                        tokens.add(Token('w', start, i))
                    }
                }
                require(tokens.size <= MAX_TOKENS) { "CSS 过于复杂，超过安全解析范围" }
            }
            pairs = IntArray(tokens.size) { -1 }
            val stack = ArrayList<Int>()
            for (n in tokens.indices) {
                when (tokens[n].kind) {
                    '{', '(', '[' -> {
                        require(stack.size < MAX_DEPTH) { "CSS 嵌套过深" }
                        stack.add(n)
                    }
                    '}', ')', ']' -> {
                        val opening = stack.lastOrNull() ?: error("CSS 出现多余的闭合符号（位置 ${tokens[n].start}）")
                        val expected = when (tokens[opening].kind) { '{' -> '}'; '(' -> ')'; else -> ']' }
                        require(tokens[n].kind == expected) { "CSS 括号类型不匹配（位置 ${tokens[n].start}）" }
                        stack.removeAt(stack.lastIndex); pairs[opening] = n; pairs[n] = opening
                    }
                }
            }
            require(stack.isEmpty()) { "CSS 规则或括号未闭合" }
        }
    }

    private class Parser(val text: String) {
        val lex = Lexer(text)
        val rules = ArrayList<Rule>()
        private var declarationCount = 0
        private val ts get() = lex.tokens

        fun parseTop(start: Int, end: Int, context: List<String>, depth: Int) {
            require(depth <= MAX_DEPTH) { "CSS 规则嵌套过深" }
            var i = start
            var comments = ArrayList<String>()
            while (i < end) {
                if (ts[i].kind == 'c') { comments.add(cleanComment(raw(ts[i]))); i++; continue }
                val beginning = i
                var j = i
                while (j < end && ts[j].kind != '{' && ts[j].kind != ';') {
                    j = if (ts[j].kind == '(' || ts[j].kind == '[') lex.pairs[j] + 1 else j + 1
                }
                if (j >= end) return
                if (ts[j].kind == ';') { i = j + 1; comments = ArrayList(); continue }
                parseRule(beginning, j, lex.pairs[j], context, comments.joinToString("\n").take(2048), depth)
                i = lex.pairs[j] + 1
                comments = ArrayList()
            }
        }

        private fun parseRule(start: Int, open: Int, close: Int, context: List<String>, comment: String, depth: Int) {
            require(rules.size < MAX_RULES) { "CSS 规则过多" }
            val headerTokens = (start until open).filter { ts[it].kind != 'c' }
            require(headerTokens.isNotEmpty()) { "CSS 规则缺少选择器" }
            val selectorStart = ts[headerTokens.first()].start
            val selectorEnd = ts[headerTokens.last()].end
            require(selectorEnd - selectorStart <= 65_536) { "CSS 选择器过长" }
            val selector = text.substring(selectorStart, selectorEnd)
            val declarations = ArrayList<Declaration>()
            var hasChildren = false
            var comments = ArrayList<String>()
            var i = open + 1
            while (i < close) {
                if (ts[i].kind == 'c') { comments.add(cleanComment(raw(ts[i]))); i++; continue }
                if (ts[i].kind == ';') { i++; continue }
                val first = i
                var colon = -1
                var j = i
                var handled = false
                while (j < close) {
                    when (ts[j].kind) {
                        '(', '[' -> { j = lex.pairs[j] + 1; continue }
                        ':' -> if (colon < 0) colon = j
                        '{' -> {
                            val property = if (colon >= 0) property(first, colon) else null
                            if (property?.startsWith("--") == true) {
                                j = lex.pairs[j] + 1; continue
                            }
                            parseRule(first, j, lex.pairs[j], context + selector, comments.joinToString("\n").take(2048), depth + 1)
                            hasChildren = true
                            i = lex.pairs[j] + 1; handled = true; break
                        }
                        ';' -> {
                            declaration(first, colon, j, comments)?.let(declarations::add)
                            i = j + 1; handled = true; break
                        }
                    }
                    j++
                }
                if (!handled) {
                    declaration(first, colon, close, comments)?.let(declarations::add)
                    i = close
                }
                comments = ArrayList()
            }
            require(rules.size < MAX_RULES) { "CSS 规则过多" }
            rules.add(Rule(selectorStart, selectorEnd, ts[open].end, ts[close].start,
                selector, context, comment, declarations, hasChildren))
        }

        private fun declaration(first: Int, colon: Int, end: Int, leading: List<String>): Declaration? {
            if (colon <= first) return null
            val property = property(first, colon) ?: return null
            require(++declarationCount <= MAX_DECLARATIONS) { "CSS 声明过多" }
            val significant = (colon + 1 until end).filter { ts[it].kind != 'c' }.toMutableList()
            var bang = -1; var priorityEnd = -1
            if (significant.size >= 2) {
                val last = ts[significant.last()]
                val prev = ts[significant[significant.lastIndex - 1]]
                if (prev.kind == '!' && last.kind == 'w' && raw(last).equals("important", true)) {
                    bang = prev.start; priorityEnd = last.end
                    significant.removeAt(significant.lastIndex); significant.removeAt(significant.lastIndex)
                }
            }
            val emptyAt = if (bang >= 0) bang else ts[end].start
            val valueStart = significant.firstOrNull()?.let { ts[it].start } ?: emptyAt
            val valueEnd = significant.lastOrNull()?.let { ts[it].end } ?: valueStart
            val attached = leading + (colon + 1 until end).filter {
                ts[it].kind == 'c' && (ts[it].end <= valueStart || ts[it].start >= valueEnd)
            }.map { cleanComment(raw(ts[it])) }
            return Declaration(ts[first].start, property, valueStart, valueEnd, bang, priorityEnd,
                attached.filter { it.isNotBlank() }.joinToString("\n").take(2048))
        }

        private fun property(first: Int, colon: Int): String? {
            if (colon <= first) return null
            val token = (first until colon).filter { ts[it].kind != 'c' }.singleOrNull()?.let { ts[it] } ?: return null
            if (token.kind != 'w') return null
            val value = raw(token)
            return value.takeIf { propertyName.matches(it) }
        }
        private fun raw(token: Token) = text.substring(token.start, token.end)
    }
}
