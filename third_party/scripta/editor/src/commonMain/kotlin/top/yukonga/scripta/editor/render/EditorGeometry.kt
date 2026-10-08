package top.yukonga.scripta.editor.render

import kotlin.math.roundToInt

enum class HandleKind { Caret, SelectionStart, SelectionEnd }

data class VisualTarget(val line: Int, val row: Int)

data class HandleGeometry(
    val centerX: Float,
    val centerY: Float,
    val hitLeft: Float,
    val hitTop: Float,
    val hitRight: Float,
    val hitBottom: Float,
) {
    fun hitContains(x: Float, y: Float): Boolean =
        x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom
}

object EditorGeometry {
    fun shouldRevealCaret(explicit: Boolean, shrank: Boolean, focused: Boolean, scrolling: Boolean): Boolean =
        explicit || (shrank && focused && !scrolling)

    fun gutterDigits(lineCount: Int): Int {
        var digits = 1
        var n = lineCount
        while (n >= 10) {
            n /= 10; digits++
        }
        return maxOf(2, digits)
    }

    fun gridVisibleColumns(
        scrollX: Float,
        textAreaWidth: Float,
        charW: Float,
        lineLength: Int,
        marginCols: Int = 8,
    ): IntRange {
        if (charW <= 0f || lineLength <= 0) return 0..0
        val c0 = (scrollX / charW).toInt() - marginCols
        val c1 = ((scrollX + textAreaWidth) / charW).toInt() + marginCols + 1
        return c0.coerceIn(0, lineLength)..c1.coerceIn(0, lineLength)
    }

    fun gridColumnToX(col: Int, charW: Float): Float = col * charW

    fun gridXToColumn(localX: Float, charW: Float, lineLength: Int): Int =
        if (charW <= 0f) 0 else (localX / charW).roundToInt().coerceIn(0, lineLength)

    fun visualVerticalTarget(
        curLine: Int,
        curRow: Int,
        dir: Int,
        lineCount: Int,
        rowsOf: (Int) -> Int,
    ): VisualTarget? {
        val targetRow = curRow + dir
        if (targetRow in 0 until rowsOf(curLine)) return VisualTarget(curLine, targetRow)
        return if (dir < 0) {
            if (curLine <= 0) null else VisualTarget(curLine - 1, (rowsOf(curLine - 1) - 1).coerceAtLeast(0))
        } else {
            if (curLine >= lineCount - 1) null else VisualTarget(curLine + 1, 0)
        }
    }

    fun handleGeometry(
        kind: HandleKind,
        caretLeft: Float,
        caretTop: Float,
        caretBottom: Float,
        radius: Float,
        slop: Float,
    ): HandleGeometry {
        val cx = when (kind) {
            HandleKind.Caret -> caretLeft
            HandleKind.SelectionStart -> caretLeft - radius
            HandleKind.SelectionEnd -> caretLeft + radius
        }
        val cy = caretBottom + radius
        return HandleGeometry(
            centerX = cx,
            centerY = cy,
            hitLeft = cx - (radius + slop),
            hitTop = caretTop,
            hitRight = cx + (radius + slop),
            hitBottom = cy + (radius + slop),
        )
    }
}
