package top.yukonga.scripta.editor.text

import kotlin.random.Random

class PieceTree(text: String = "") {

    private enum class Buf { OG, ADD }

    private var original: String = ""
    private val add = StringBuilder()

    private var ogLF = IntArray(0)
    private var addLF = IntArray(16)
    private var addLFCount = 0

    private val rng = Random(0x5C819A)

    private class Node(
        val buf: Buf,
        val start: Int,
        val len: Int,
        val lf: Int,
        val priority: Int,
    ) {
        var left: Node? = null
        var right: Node? = null
        var subSize: Int = len
        var subLF: Int = lf
    }

    private var root: Node? = null

    init {
        reset(text)
    }

    val length: Int get() = root?.subSize ?: 0

    val lineCount: Int get() = (root?.subLF ?: 0) + 1

    fun reset(text: String) {
        original = text
        add.setLength(0)
        addLF = IntArray(16); addLFCount = 0
        ogLF = computeLineStarts(text)
        root = if (text.isEmpty()) null
        else Node(Buf.OG, 0, text.length, ogLF.size, priority()).also { updateAgg(it) }
    }

    fun getText(): String {
        val sb = StringBuilder(length)
        appendSubtree(root, sb)
        return sb.toString()
    }

    fun substring(offset: Int, len: Int): String {
        if (len <= 0) return ""
        val lo = offset.coerceIn(0, length)
        val hi = (offset + len).coerceIn(0, length)
        if (lo >= hi) return ""
        val sb = StringBuilder(hi - lo)
        appendRange(root, lo, hi, 0, sb)
        return sb.toString()
    }

    fun lineLength(line: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        val start = lineStartOffset(l)
        val end = if (l >= lineCount - 1) length else lineStartOffset(l + 1) - 1
        return end - start
    }

    fun lineContent(line: Int): String {
        val l = line.coerceIn(0, lineCount - 1)
        val start = lineStartOffset(l)
        val end = if (l >= lineCount - 1) length else lineStartOffset(l + 1) - 1
        return substring(start, end - start)
    }

    fun offsetAt(position: TextPosition): Int {
        val line = position.line.coerceIn(0, lineCount - 1)
        val col = position.column.coerceIn(0, lineLength(line))
        return lineStartOffset(line) + col
    }

    fun positionAt(offset: Int): TextPosition {
        val off = offset.coerceIn(0, length)
        val line = lineOfOffset(off)
        return TextPosition(line, off - lineStartOffset(line))
    }

    fun insert(offset: Int, text: String) {
        if (text.isEmpty()) return
        val at = offset.coerceIn(0, length)
        val addStart = add.length
        add.append(text)

        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') pushAddLF(addStart + i)
            i++
        }
        val node = Node(Buf.ADD, addStart, text.length, countLFIn(Buf.ADD, addStart, text.length), priority())
        updateAgg(node)
        val (l, r) = split(root, at)
        root = merge(merge(l, node), r)
    }

    fun delete(offset: Int, len: Int) {
        if (len <= 0) return
        val at = offset.coerceIn(0, length)
        val n = len.coerceIn(0, length - at)
        if (n == 0) return
        val (l, tmp) = split(root, at)
        val (_, r) = split(tmp, n)
        root = merge(l, r)
    }

    private fun split(t: Node?, k: Int): Pair<Node?, Node?> {
        if (t == null) return null to null
        val leftSize = t.left?.subSize ?: 0
        return when {
            k <= leftSize -> {
                val (l, r) = split(t.left, k)
                t.left = r; updateAgg(t)
                l to t
            }

            k >= leftSize + t.len -> {
                val (l, r) = split(t.right, k - leftSize - t.len)
                t.right = l; updateAgg(t)
                t to r
            }

            else -> {

                val off = k - leftSize
                val aLF = countLFIn(t.buf, t.start, off)
                val a = Node(t.buf, t.start, off, aLF, t.priority)
                val b = Node(t.buf, t.start + off, t.len - off, t.lf - aLF, t.priority)
                a.left = t.left; a.right = null; updateAgg(a)
                b.left = null; b.right = t.right; updateAgg(b)
                a to b
            }
        }
    }

    private fun merge(l: Node?, r: Node?): Node? {
        if (l == null) return r
        if (r == null) return l
        return if (l.priority >= r.priority) {
            l.right = merge(l.right, r); updateAgg(l); l
        } else {
            r.left = merge(l, r.left); updateAgg(r); r
        }
    }

    private fun updateAgg(n: Node) {
        n.subSize = (n.left?.subSize ?: 0) + n.len + (n.right?.subSize ?: 0)
        n.subLF = (n.left?.subLF ?: 0) + n.lf + (n.right?.subLF ?: 0)
    }

    private fun appendSubtree(n: Node?, sb: StringBuilder) {
        if (n == null) return
        appendSubtree(n.left, sb)
        sb.appendRange(bufOf(n.buf), n.start, n.start + n.len)
        appendSubtree(n.right, sb)
    }

    private fun appendRange(n: Node?, lo: Int, hi: Int, base: Int, sb: StringBuilder) {
        if (n == null) return
        val leftSize = n.left?.subSize ?: 0
        val nodeStart = base + leftSize
        val nodeEnd = nodeStart + n.len
        if (lo < nodeStart) appendRange(n.left, lo, hi, base, sb)
        val a = maxOf(lo, nodeStart)
        val b = minOf(hi, nodeEnd)
        if (a < b) {
            val s = n.start + (a - nodeStart)
            sb.appendRange(bufOf(n.buf), s, s + (b - a))
        }
        if (hi > nodeEnd) appendRange(n.right, lo, hi, nodeEnd, sb)
    }

    private fun lineStartOffset(line: Int): Int {
        if (line <= 0) return 0
        var node = root
        var acc = 0
        var need = line
        while (node != null) {
            val left = node.left
            val leftLF = left?.subLF ?: 0
            if (need <= leftLF) {
                node = left
            } else {
                need -= leftLF
                if (need <= node.lf) {
                    val leftSize = left?.subSize ?: 0
                    val absLF = nthLFPos(node.buf, node.start, need)
                    return acc + leftSize + (absLF - node.start) + 1
                } else {
                    need -= node.lf
                    acc += (left?.subSize ?: 0) + node.len
                    node = node.right
                }
            }
        }
        return length
    }

    private fun lineOfOffset(offset: Int): Int {
        var node = root
        var line = 0
        var rem = offset
        while (node != null) {
            val left = node.left
            val leftSize = left?.subSize ?: 0
            if (rem <= leftSize) {
                node = left
            } else {
                line += left?.subLF ?: 0
                rem -= leftSize
                if (rem < node.len) {
                    line += countLFIn(node.buf, node.start, rem)
                    return line
                } else {
                    line += node.lf
                    rem -= node.len
                    node = node.right
                }
            }
        }
        return line
    }

    private fun bufOf(b: Buf): CharSequence = if (b == Buf.OG) original else add

    private fun priority(): Int = rng.nextInt()

    private fun pushAddLF(pos: Int) {
        if (addLFCount == addLF.size) addLF = addLF.copyOf(addLF.size * 2)
        addLF[addLFCount++] = pos
    }

    private fun computeLineStarts(text: String): IntArray {
        var count = 0
        for (c in text) if (c == '\n') count++
        val arr = IntArray(count)
        var k = 0
        for (i in text.indices) if (text[i] == '\n') arr[k++] = i
        return arr
    }

    private fun countLFIn(b: Buf, start: Int, len: Int): Int {
        if (len <= 0) return 0
        return if (b == Buf.OG) lowerBound(ogLF, ogLF.size, start + len) - lowerBound(ogLF, ogLF.size, start)
        else lowerBound(addLF, addLFCount, start + len) - lowerBound(addLF, addLFCount, start)
    }

    private fun nthLFPos(b: Buf, start: Int, m: Int): Int {
        return if (b == Buf.OG) ogLF[lowerBound(ogLF, ogLF.size, start) + (m - 1)]
        else addLF[lowerBound(addLF, addLFCount, start) + (m - 1)]
    }

    private fun lowerBound(arr: IntArray, count: Int, x: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
