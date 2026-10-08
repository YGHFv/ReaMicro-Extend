package top.yukonga.scripta.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.text.EditKind
import top.yukonga.scripta.editor.text.SelectionState
import top.yukonga.scripta.editor.text.TextBuffer
import top.yukonga.scripta.editor.text.TextEdit
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import top.yukonga.scripta.editor.text.UndoHistory
import top.yukonga.scripta.editor.text.UndoStep

internal data class DirtyRange(val from: Int, val endExclusive: Int, val structural: Boolean)

internal data class LineSplice(val startLine: Int, val oldLines: Int, val newLines: Int)

class EditorEngine(initialText: String = "") {

    companion object {

        const val INDENT_UNIT = "    "

        private val AUTO_PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
        private val AUTO_PAIR_CLOSERS = AUTO_PAIRS.values.toSet()
    }

    val buffer = TextBuffer()

    private var directional: TextRange by mutableStateOf(TextRange.cursor(TextPosition(0, 0)))

    val selection: TextRange get() = directional.normalized()

    private val anchor: TextPosition get() = directional.start
    private val head: TextPosition get() = directional.end

    internal val selectionAnchor: TextPosition get() = anchor

    val caret: TextPosition get() = head

    internal var revealTick: Int by mutableStateOf(0)
        private set

    internal fun requestReveal() {
        revealTick++
    }

    var composing: TextRange? by mutableStateOf(null)
        private set

    var contentGeneration: Int by mutableStateOf(0)
        private set

    private var desiredColumn: Int? = null

    val selStart: TextPosition get() = selection.start
    val selEnd: TextPosition get() = selection.end

    val hasGoalColumn: Boolean get() = desiredColumn != null

    interface ImeListener {
        fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    }

    var imeListener: ImeListener? = null
    var requestShowKeyboard: (() -> Unit)? = null

    var onDocumentReplaced: (() -> Unit)? = null

    private val history = UndoHistory()

    private val pendingSplices = ArrayList<LineSplice>()

    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    private var batchDepth = 0

    init {
        if (initialText.isNotEmpty()) setText(initialText)
    }

    fun beginBatch() {
        batchDepth++
    }

    fun endBatch(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) fireIme()
        return batchDepth > 0
    }

    private fun maybeNotify() {
        if (batchDepth == 0) fireIme()
    }

    fun fireIme() {
        val l = imeListener ?: return
        val s = selectionOffsets()
        val c = composingOffsets()
        l.onSelectionChanged(s.first, s.second, c.first, c.second)
    }

    fun selectionOffsets(): Pair<Int, Int> = buffer.offsetAt(selStart) to buffer.offsetAt(selEnd)

    fun composingOffsets(): Pair<Int, Int> {
        val c = composing ?: return -1 to -1
        return buffer.offsetAt(c.start) to buffer.offsetAt(c.end)
    }

    fun getText(): String = buffer.text()

    fun setText(text: String) {
        buffer.setText(text)
        collapseCaret(TextPosition(0, 0))
        composing = null
        desiredColumn = null
        contentGeneration++
        history.clear()
        syncHistory()
        onDocumentReplaced?.invoke()
        markDirty(0, Int.MAX_VALUE, structural = true)
        pendingSplices.clear()
        maybeNotify()
    }

    fun undo(): Boolean {
        val step = history.undo() ?: return false
        applyStep(step)
        return true
    }

    fun redo(): Boolean {
        val step = history.redo() ?: return false
        applyStep(step)
        return true
    }

    private fun applyStep(step: UndoStep) {
        composing = null
        desiredColumn = null
        for (e in step.edits) {
            val start = buffer.positionAt(e.offset)
            val end = buffer.positionAt(e.offset + e.removed.length)
            markReplaceDirty(start.line, e.removed, e.inserted)
            buffer.replace(TextRange(start, end), e.inserted)
        }
        directional = TextRange(buffer.positionAt(step.selection.anchor), buffer.positionAt(step.selection.head))
        syncHistory()
        maybeNotify()
    }

    private fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun selectionSnapshot() = SelectionState(buffer.offsetAt(anchor), buffer.offsetAt(head))

    private fun replaceCollecting(range: TextRange, text: String): Pair<TextEdit, TextPosition> {
        val r = range.normalized()
        val start = buffer.clamp(r.start)
        val startOff = buffer.offsetAt(start)
        val removed = buffer.textInRange(r)
        val newCaret = buffer.replace(r, text)
        val inserted = buffer.textInRange(TextRange(start, newCaret))
        markReplaceDirty(start.line, removed, inserted)
        return TextEdit(startOff, removed, inserted) to newCaret
    }

    private fun isSingleCodePoint(s: String): Boolean =
        s.length == 1 || (s.length == 2 && s[0].isHighSurrogate() && s[1].isLowSurrogate())

    private var dirtyFrom = 0
    private var dirtyEndEx = Int.MAX_VALUE
    private var dirtyStructural = true
    private var hasDirty = true

    internal fun consumeDirty(): DirtyRange? {
        if (!hasDirty) return null
        val d = DirtyRange(dirtyFrom, dirtyEndEx, dirtyStructural)
        hasDirty = false
        dirtyFrom = Int.MAX_VALUE
        dirtyEndEx = 0
        dirtyStructural = false
        return d
    }

    internal fun consumeLineSplices(): List<LineSplice> {
        if (pendingSplices.isEmpty()) return emptyList()
        val out = pendingSplices.toList()
        pendingSplices.clear()
        return out
    }

    private fun markDirty(fromLine: Int, endExclusive: Int, structural: Boolean) {
        hasDirty = true
        if (fromLine < dirtyFrom) dirtyFrom = fromLine
        if (endExclusive > dirtyEndEx) dirtyEndEx = endExclusive
        if (structural) dirtyStructural = true
    }

    private fun markReplaceDirty(startLine: Int, removed: String, inserted: String) {
        val rb = removed.count { it == '\n' }
        val ib = inserted.count { it == '\n' }
        markDirty(startLine, startLine + maxOf(rb, ib) + 1, structural = rb != ib)
        if (rb != ib) pendingSplices.add(LineSplice(startLine, rb + 1, ib + 1))
    }

    fun replaceAllOffsetRanges(ranges: List<Pair<Int, Int>>, replacement: String): Int {
        if (ranges.isEmpty()) return 0
        val selBefore = selectionSnapshot()
        val edits = ArrayList<TextEdit>(ranges.size)
        for ((s, e) in ranges.asReversed()) {
            val (edit, _) = replaceCollecting(TextRange(buffer.positionAt(s), buffer.positionAt(e)), replacement)
            edits.add(edit)
        }
        composing = null
        desiredColumn = null
        collapseCaret(buffer.positionAt(ranges.first().first + edits.last().inserted.length))
        val selAfter = selectionSnapshot()
        history.beginGroup()
        for (edit in edits) history.record(edit, selBefore, selAfter, EditKind.Other)
        history.endGroup()
        syncHistory()
        maybeNotify()
        return ranges.size
    }

    fun indentSelectedLines() {
        val edits = affectedLines().mapNotNull { line ->
            if (buffer.lineLength(line) == 0) return@mapNotNull null
            val p = buffer.offsetAt(TextPosition(line, 0))
            p to p
        }
        applyLineStartEdits(edits, INDENT_UNIT)
    }

    fun outdentSelectedLines() {
        val edits = affectedLines().mapNotNull { line ->
            val text = buffer.lineText(line)
            val k = if (text.startsWith("\t")) 1 else {
                var n = 0
                while (n < INDENT_UNIT.length && n < text.length && text[n] == ' ') n++
                n
            }
            if (k == 0) return@mapNotNull null
            val p = buffer.offsetAt(TextPosition(line, 0))
            p to p + k
        }
        applyLineStartEdits(edits, "")
    }

    fun toggleLineComment(prefix: String) {
        val lines = affectedLines()
        var minIndent = Int.MAX_VALUE
        var allCommented = true
        var sawContent = false
        for (line in lines) {
            val text = buffer.lineText(line)
            if (text.isBlank()) continue
            sawContent = true
            var i = 0
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i < minIndent) minIndent = i
            if (!text.startsWith(prefix, i)) allCommented = false
        }
        if (!sawContent) return
        if (allCommented) {
            val ranges = lines.mapNotNull { line ->
                val text = buffer.lineText(line)
                if (text.isBlank()) return@mapNotNull null
                var i = 0
                while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
                var end = i + prefix.length
                if (end < text.length && text[end] == ' ') end++
                val p = buffer.offsetAt(TextPosition(line, 0))
                (p + i) to (p + end)
            }
            applyLineStartEdits(ranges, "")
        } else {
            val ranges = lines.mapNotNull { line ->
                if (buffer.lineText(line).isBlank()) return@mapNotNull null
                buffer.offsetAt(TextPosition(line, minIndent)).let { it to it }
            }
            applyLineStartEdits(ranges, "$prefix ")
        }
    }

    fun toggleBlockComment(open: String, close: String) {
        var first = -1
        var last = -1
        for (line in affectedLines()) {
            if (buffer.lineText(line).isBlank()) continue
            if (first < 0) first = line
            last = line
        }
        if (first < 0) return
        val firstText = buffer.lineText(first)
        val lastText = buffer.lineText(last)
        var i = 0
        while (i < firstText.length && (firstText[i] == ' ' || firstText[i] == '\t')) i++
        var j = lastText.length
        while (j > 0 && (lastText[j - 1] == ' ' || lastText[j - 1] == '\t')) j--
        val openAt = buffer.offsetAt(TextPosition(first, i))
        val closeEnd = buffer.offsetAt(TextPosition(last, j))
        val wrapped = firstText.startsWith(open, i) && lastText.substring(0, j).endsWith(close) &&
            closeEnd - close.length >= openAt + open.length
        if (wrapped) {
            var openEnd = i + open.length
            if (openEnd < firstText.length && firstText[openEnd] == ' ') openEnd++
            var closeStart = j - close.length
            if (closeStart > 0 && lastText[closeStart - 1] == ' ') closeStart--
            val openEndOff = buffer.offsetAt(TextPosition(first, openEnd))
            val closeStartOff = maxOf(buffer.offsetAt(TextPosition(last, closeStart)), openEndOff)
            applyRangeEdits(listOf(Triple(openAt, openEndOff, ""), Triple(closeStartOff, closeEnd, "")))
        } else {
            applyRangeEdits(listOf(Triple(openAt, openAt, "$open "), Triple(closeEnd, closeEnd, " $close")))
        }
    }

    private fun affectedLines(): IntRange {
        val s = selStart
        val e = selEnd
        val endLine = if (e.line > s.line && e.column == 0) e.line - 1 else e.line
        return s.line..endLine
    }

    private fun applyLineStartEdits(ranges: List<Pair<Int, Int>>, replacement: String) =
        applyRangeEdits(ranges.map { (s, e) -> Triple(s, e, replacement) })

    private fun applyRangeEdits(edits: List<Triple<Int, Int, String>>) {
        if (edits.isEmpty()) return
        val selBefore = selectionSnapshot()
        var aOff = selBefore.anchor
        var hOff = selBefore.head
        for ((s, e, replacement) in edits.asReversed()) {
            val removed = e - s
            aOff = shiftForEdit(aOff, s, removed, replacement.length)
            hOff = shiftForEdit(hOff, s, removed, replacement.length)
        }
        val applied = ArrayList<TextEdit>(edits.size)
        for ((s, e, replacement) in edits.asReversed()) {
            val (edit, _) = replaceCollecting(TextRange(buffer.positionAt(s), buffer.positionAt(e)), replacement)
            applied.add(edit)
        }
        composing = null
        desiredColumn = null
        directional = TextRange(buffer.positionAt(aOff), buffer.positionAt(hOff))
        val selAfter = SelectionState(aOff, hOff)
        history.beginGroup()
        for (edit in applied) history.record(edit, selBefore, selAfter, EditKind.Other)
        history.endGroup()
        syncHistory()
        maybeNotify()
    }

    private fun shiftForEdit(off: Int, start: Int, removed: Int, inserted: Int): Int = when {
        off <= start -> off
        off <= start + removed -> start + inserted
        else -> off - removed + inserted
    }

    fun setSelection(a: TextPosition, b: TextPosition, keepComposing: Boolean = false) {
        directional = TextRange(buffer.clamp(a), buffer.clamp(b))
        if (!keepComposing) composing = null
        desiredColumn = null
        history.breakMerge()
        maybeNotify()
    }

    fun setCursor(p: TextPosition) = setSelection(p, p)

    fun extendSelectionTo(to: TextPosition) {
        directional = TextRange(anchor, buffer.clamp(to))
        composing = null
        desiredColumn = null
        history.breakMerge()
        maybeNotify()
    }

    fun selectAll() = setSelection(TextPosition(0, 0), buffer.endPosition())

    internal fun replaceRange(range: TextRange, text: String, kind: EditKind = EditKind.Other) {
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(range, text)
        composing = null
        desiredColumn = null
        collapseCaret(newCaret)
        history.record(edit, selBefore, selectionSnapshot(), kind)
        syncHistory()
        maybeNotify()
    }

    fun replaceSelection(text: String) = replaceRange(selection, text)

    fun insertNewlineAutoIndent() {
        val start = selStart
        val lineText = buffer.lineText(start.line)
        val limit = start.column.coerceIn(0, lineText.length)
        var i = 0
        while (i < limit && (lineText[i] == ' ' || lineText[i] == '\t')) i++
        insert("\n" + lineText.substring(0, i))
    }

    fun commitText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val wasComposing = composing != null
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(target, text)
        composing = null
        desiredColumn = null
        collapseCaret(cursorAfterInsert(target.start, newCursorPosition, newCaret))

        val kind = when {
            wasComposing -> EditKind.Composing
            edit.removed.isEmpty() && isSingleCodePoint(edit.inserted) -> EditKind.Typing
            else -> EditKind.Other
        }
        history.record(edit, selBefore, selectionSnapshot(), kind)
        if (wasComposing) history.breakMerge()
        syncHistory()
        maybeNotify()
    }

    fun insert(text: String) = commitText(text, 1)

    var autoClosePairs: Boolean = true

    fun typeCharacter(text: String) {
        if (!autoClosePairs || composing != null || text.length != 1 || !selection.isEmpty) {
            insert(text)
            return
        }
        val ch = text[0]
        val pos = selStart
        val line = buffer.lineText(pos.line)
        val right = if (pos.column < line.length) line[pos.column] else null
        if (right == ch && ch in AUTO_PAIR_CLOSERS) {
            setCursor(TextPosition(pos.line, pos.column + 1))
            return
        }
        val close = AUTO_PAIRS[ch]
        if (close == null) {
            insert(text)
            return
        }
        if (ch == '"' || ch == '\'') {
            var count = 0
            for (i in 0 until pos.column) if (line[i] == ch) count++
            if (count % 2 == 1 || (right != null && right.isLetterOrDigit())) {
                insert(text)
                return
            }
        }
        insert("$ch$close")
        setCursor(TextPosition(selStart.line, selStart.column - 1))
    }

    fun backspace() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }

        if (autoClosePairs && composing == null) {
            val line = buffer.lineText(selStart.line)
            val col = selStart.column
            if (col in 1 until line.length && AUTO_PAIRS[line[col - 1]] == line[col]) {
                replaceRange(
                    TextRange(TextPosition(selStart.line, col - 1), TextPosition(selStart.line, col + 1)),
                    "",
                    EditKind.DeleteBackward,
                )
                return
            }
        }
        val prev = previousCodePointPosition(selStart) ?: return
        replaceRange(TextRange(prev, selStart), "", EditKind.DeleteBackward)
    }

    fun deleteForward() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }
        val next = nextCodePointPosition(selEnd) ?: return
        replaceRange(TextRange(selEnd, next), "", EditKind.DeleteForward)
    }

    fun setComposingText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(target, text)
        composing = if (text.isEmpty()) null else TextRange(target.start, newCaret)
        desiredColumn = null
        collapseCaret(cursorAfterInsert(target.start, newCursorPosition, newCaret))
        history.record(edit, selBefore, selectionSnapshot(), EditKind.Composing)
        syncHistory()
        maybeNotify()
    }

    fun setComposingRegion(startOffset: Int, endOffset: Int) {
        val lo = minOf(startOffset, endOffset).coerceAtLeast(0)
        val hi = maxOf(startOffset, endOffset).coerceAtMost(buffer.totalLength())
        composing = if (lo == hi) null else TextRange(buffer.positionAt(lo), buffer.positionAt(hi))
        maybeNotify()
    }

    fun finishComposing() {
        if (composing != null) {
            composing = null
            history.breakMerge()
            maybeNotify()
        }
    }

    fun deleteSurroundingText(before: Int, after: Int) {
        val (selS, selE) = selectionOffsets()
        val total = buffer.totalLength()
        val delStart = (selS - before.coerceAtLeast(0)).coerceAtLeast(0)
        val delEnd = (selE + after.coerceAtLeast(0)).coerceAtMost(total)
        val selBefore = selectionSnapshot()

        val (tailEdit, _) = replaceCollecting(TextRange(buffer.positionAt(selE), buffer.positionAt(delEnd)), "")
        val (headEdit, _) = replaceCollecting(TextRange(buffer.positionAt(delStart), buffer.positionAt(selS)), "")
        composing = null
        desiredColumn = null
        collapseCaret(buffer.positionAt(delStart))

        val selAfter = selectionSnapshot()
        history.beginGroup()
        history.record(tailEdit, selBefore, selAfter, EditKind.Other)
        history.record(headEdit, selBefore, selAfter, EditKind.Other)
        history.endGroup()
        syncHistory()
        maybeNotify()
    }

    fun deleteSurroundingTextInCodePoints(before: Int, after: Int) {
        val beforeChars = charsForCodePoints(textBeforeCursorString(before * 2 + 2), before, fromEnd = true)
        val afterChars = charsForCodePoints(textAfterCursorString(after * 2 + 2), after, fromEnd = false)
        deleteSurroundingText(beforeChars, afterChars)
    }

    private fun textBeforeCursorString(n: Int): String {
        val off = buffer.offsetAt(selStart)
        val start = (off - n).coerceAtLeast(0)
        return buffer.textInRange(TextRange(buffer.positionAt(start), selStart))
    }

    private fun textAfterCursorString(n: Int): String {
        val off = buffer.offsetAt(selEnd)
        val end = (off + n).coerceAtMost(buffer.totalLength())
        return buffer.textInRange(TextRange(selEnd, buffer.positionAt(end)))
    }

    private fun charsForCodePoints(window: String, codePoints: Int, fromEnd: Boolean): Int {
        var chars = 0
        var cps = 0
        while (cps < codePoints) {
            val idx = if (fromEnd) window.length - chars - 1 else chars
            if (idx < 0 || idx >= window.length) break
            val c = window[idx]
            val pair = if (fromEnd) {
                idx - 1 >= 0 && window[idx - 1].isHighSurrogate() && c.isLowSurrogate()
            } else {
                idx + 1 < window.length && c.isHighSurrogate() && window[idx + 1].isLowSurrogate()
            }
            chars += if (pair) 2 else 1
            cps++
        }
        return chars
    }

    fun moveCaretHorizontally(dir: Int, extend: Boolean) {
        if (!extend && !selection.isEmpty) {
            setCursor(if (dir < 0) selStart else selEnd)
            return
        }
        val from = head
        val target = if (dir < 0) previousCodePointPosition(from) else nextCodePointPosition(from)
        val to = target ?: from
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretVertically(dir: Int, extend: Boolean) {
        val from = head
        val goal = desiredColumn ?: from.column
        val targetLine = (from.line + dir).coerceIn(0, buffer.lineCount - 1)
        val targetCol = goal.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) extendSelectionTo(to) else setCursor(to)
        desiredColumn = goal
    }

    fun moveCaretToLineStart(extend: Boolean) {
        val to = TextPosition(head.line, 0)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToLineEnd(extend: Boolean) {
        val to = TextPosition(head.line, buffer.lineLength(head.line))
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToDocStart(extend: Boolean) {
        val to = TextPosition(0, 0)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToDocEnd(extend: Boolean) {
        val to = buffer.endPosition()
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretByWord(dir: Int, extend: Boolean) {
        val to = wordBoundaryFrom(head, dir)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun movePage(dir: Int, lines: Int, extend: Boolean) {
        val from = head
        val goal = desiredColumn ?: from.column
        val targetLine = (from.line + dir * lines).coerceIn(0, buffer.lineCount - 1)
        val targetCol = goal.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) extendSelectionTo(to) else setCursor(to)
        desiredColumn = goal
    }

    private fun wordBoundaryFrom(pos: TextPosition, dir: Int): TextPosition {
        val line = buffer.lineText(pos.line)
        return if (dir > 0) {
            if (pos.column >= line.length) {
                if (pos.line < buffer.lineCount - 1) TextPosition(pos.line + 1, 0) else pos
            } else {
                val cls = charClass(line[pos.column])
                var c = pos.column
                while (c < line.length && charClass(line[c]) == cls) c++
                TextPosition(pos.line, c)
            }
        } else {
            if (pos.column <= 0) {
                if (pos.line > 0) TextPosition(pos.line - 1, buffer.lineLength(pos.line - 1)) else pos
            } else {
                val cls = charClass(line[pos.column - 1])
                var c = pos.column
                while (c > 0 && charClass(line[c - 1]) == cls) c--
                TextPosition(pos.line, c)
            }
        }
    }

    fun selectedText(): String? = if (selection.isEmpty) null else buffer.textInRange(selection)

    fun textBeforeCursor(n: Int): CharSequence = textBeforeCursorString(n.coerceAtLeast(0))

    fun textAfterCursor(n: Int): CharSequence = textAfterCursorString(n.coerceAtLeast(0))

    fun wordRangeAt(pos: TextPosition): TextRange {
        val p = buffer.clamp(pos)
        val line = buffer.lineText(p.line)
        if (line.isEmpty()) return TextRange.cursor(p)

        val idx = if (p.column < line.length) p.column else p.column - 1
        val cls = charClass(line[idx])
        var s = idx
        var e = idx + 1
        while (s > 0 && charClass(line[s - 1]) == cls) s--
        while (e < line.length && charClass(line[e]) == cls) e++
        return TextRange(TextPosition(p.line, s), TextPosition(p.line, e))
    }

    fun selectWordRange(anchorWord: TextRange, at: TextPosition) {
        val cur = wordRangeAt(at)
        setSelection(minOf(anchorWord.start, cur.start), maxOf(anchorWord.end, cur.end))
    }

    private fun charClass(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() || c == '_' || c == '$' -> 1
        else -> 2
    }

    private fun collapseCaret(p: TextPosition) {
        directional = TextRange.cursor(p)
    }

    private fun cursorAfterInsert(insertStart: TextPosition, newCursorPosition: Int, insertedEnd: TextPosition): TextPosition {
        if (newCursorPosition == 1) return insertedEnd
        val startOff = buffer.offsetAt(insertStart)
        val endOff = buffer.offsetAt(insertedEnd)
        val raw = if (newCursorPosition > 0) endOff + (newCursorPosition - 1) else startOff + newCursorPosition
        return buffer.positionAt(raw.coerceIn(0, buffer.totalLength()))
    }

    internal fun previousCodePointPosition(pos: TextPosition): TextPosition? {
        if (pos.column > 0) {
            val line = buffer.lineText(pos.line)
            val c = pos.column
            val step = if (c >= 2 && line[c - 1].isLowSurrogate() && line[c - 2].isHighSurrogate()) 2 else 1
            return TextPosition(pos.line, c - step)
        }
        if (pos.line > 0) return TextPosition(pos.line - 1, buffer.lineLength(pos.line - 1))
        return null
    }

    internal fun nextCodePointPosition(pos: TextPosition): TextPosition? {
        val len = buffer.lineLength(pos.line)
        if (pos.column < len) {
            val line = buffer.lineText(pos.line)
            val c = pos.column
            val step = if (c + 1 < len && line[c].isHighSurrogate() && line[c + 1].isLowSurrogate()) 2 else 1
            return TextPosition(pos.line, c + step)
        }
        if (pos.line < buffer.lineCount - 1) return TextPosition(pos.line + 1, 0)
        return null
    }
}
