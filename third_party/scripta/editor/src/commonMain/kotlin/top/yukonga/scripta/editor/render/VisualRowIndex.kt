package top.yukonga.scripta.editor.render

class VisualRowIndex(lineCount: Int) {

    private var n = lineCount
    private var rowsArr = IntArray(n) { 1 }
    private var tree = IntArray(n + 1)

    init {
        buildTree()
    }

    val lineCount: Int get() = n

    fun rows(line: Int): Int = rowsArr[line]

    fun setRows(line: Int, rows: Int) {
        if (line < 0 || line >= n) return
        val r = rows.coerceAtLeast(1)
        val delta = r - rowsArr[line]
        if (delta == 0) return
        rowsArr[line] = r
        var i = line + 1
        while (i <= n) {
            tree[i] += delta; i += i and (-i)
        }
    }

    fun rowsBefore(line: Int): Int {
        var s = 0
        var x = line.coerceIn(0, n)
        while (x > 0) {
            s += tree[x]; x -= x and (-x)
        }
        return s
    }

    fun totalRows(): Int = rowsBefore(n)

    fun lineAtRow(row: Int): Int {
        if (n == 0) return 0
        var pos = 0
        var remaining = row.coerceAtLeast(0)
        var k = 1
        while (k shl 1 <= n) k = k shl 1
        while (k > 0) {
            val next = pos + k
            if (next <= n && tree[next] <= remaining) {
                pos = next
                remaining -= tree[next]
            }
            k = k shr 1
        }
        return pos.coerceIn(0, n - 1)
    }

    fun splice(from: Int, oldLines: Int, newLines: Int) {
        val f = from.coerceIn(0, n)
        val removed = oldLines.coerceIn(0, n - f)
        val inserted = newLines.coerceAtLeast(0)
        val m = n - removed + inserted
        val next = IntArray(m)
        rowsArr.copyInto(next, 0, 0, f)
        next.fill(1, f, f + inserted)
        rowsArr.copyInto(next, f + inserted, f + removed, n)
        rowsArr = next
        n = m
        buildTree()
    }

    private fun buildTree() {
        tree = IntArray(n + 1)
        for (i in 1..n) {
            tree[i] += rowsArr[i - 1]
            val parent = i + (i and (-i))
            if (parent <= n) tree[parent] += tree[i]
        }
    }
}
