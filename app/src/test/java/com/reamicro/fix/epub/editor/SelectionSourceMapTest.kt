package com.reamicro.fix.epub.editor

import com.reamicro.fix.hook.readerSelectionSpinePosition
import org.junit.Assert.*
import org.junit.Test

class SelectionSourceMapTest {
    private fun decode(raw: String): String = when (raw) {
        "&amp;" -> "&"; "&nbsp;" -> "\u00a0"; "&lt;" -> "<"; "&gt;" -> ">"
        "&#x1F600;" -> "😀"; "&#128512;" -> "😀"; "&NotEqualTilde;" -> "≂̸"
        else -> raw
    }
    private fun point(path: String, offset: Int, item: Int = 12) =
        SelectionCfiPoint(6, item, path.split('/').map(String::toInt), offset)
    private fun map(
        source: String, pieces: List<Triple<String, String, String>>, appendBreak: Boolean = false,
    ): SelectionSourceMap {
        val text = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        val anchors = linkedMapOf<String, Int>()
        var sourceCursor = 0
        for ((path, raw, expected) in pieces) {
            val at = source.indexOf(raw, sourceCursor)
            check(at >= 0)
            sourceCursor = at + raw.length
            anchors[path] = text.length
            val piece = mapSelectionTextNode(raw, at, expected, ::decode)
            text.append(piece.text); starts.addAll(piece.starts.toList()); ends.addAll(piece.ends.toList())
        }
        val structural = mutableSetOf<Int>()
        if (appendBreak) { structural += text.length; text.append('\n'); starts += -1; ends += -1 }
        return SelectionSourceMap(source, text.toString(), starts.toIntArray(), ends.toIntArray(),
            anchors, anchors.mapValues { text.length }, structural)
    }

    @Test fun duplicateParagraphsAreDisambiguatedByCfi() {
        val source = "<p>同一句</p><p>同一句</p>"
        val projection = map(source, listOf(Triple("1/1", "同一句", "同一句"), Triple("2/1", "同一句", "同一句")))
        assertEquals("<p>同一句</p><p>新内容</p>", projection.plan(point("2/1", 0), point("2/1", 3), "同一句").replace("新内容"))
    }
    @Test fun duplicateWithinParagraphUsesCharacterOffset() {
        val source = "<p>哈哈，哈哈</p>"
        val projection = map(source, listOf(Triple("1/1", "哈哈，哈哈", "哈哈，哈哈")))
        assertEquals("<p>哈哈，你好</p>", projection.plan(point("1/1", 3), point("1/1", 5), "哈哈").replace("你好"))
    }
    @Test fun selectionAcrossInlineTagsKeepsAttributesAndEscapesUserText() {
        val source = "<p title='AB&amp;CD'>A<span id='keep'>B&amp;C</span>D</p>"
        val projection = map(source, listOf(Triple("1/1", ">A", ">A")))
        // Explicit raw text ranges never include a tag or attribute.
        val rawA = source.indexOf(">A") + 1
        val rawB = source.indexOf("B&amp;C", source.indexOf("id='keep'"))
        val rawD = source.indexOf(">D") + 1
        val b = mapSelectionTextNode("B&amp;C", rawB, "B&C", ::decode)
        val full = SelectionSourceMap(source, "AB&CD",
            intArrayOf(rawA, *b.starts, rawD), intArrayOf(rawA + 1, *b.ends, rawD + 1),
            mapOf("1/1" to 0), mapOf("1/1" to 5))
        assertEquals("<p title='AB&amp;CD'>&lt;b&gt;&amp;<span id='keep'></span></p>",
            full.plan(point("1/1", 0), point("1/1", 5), "AB&CD").replace("<b>&"))
    }
    @Test fun rawAttributesContainingQuoteAreNotTouched() {
        val source = "<p title='same'>same</p>"
        val at = source.indexOf(">same") + 1
        val projection = SelectionSourceMap(source, "same", IntArray(4) { at + it }, IntArray(4) { at + it + 1 },
            mapOf("1/1" to 0), mapOf("1/1" to 4))
        assertEquals("<p title='same'>new</p>", projection.plan(point("1/1", 0), point("1/1", 4), "same").replace("new"))
    }
    @Test fun normalizesWhitespaceButMapsWholeEntityAndKeepsUnselectedWhitespace() {
        val source = "<p> \tA  \r\n B&nbsp;C </p>"
        val p = map(source, listOf(Triple("1/1", " \tA  \r\n B&nbsp;C ", "A B C")))
        assertEquals("<p> \tA  \r\n Z </p>", p.plan(point("1/1", 2), point("1/1", 5), "B C").replace("Z"))
    }
    @Test fun numericEntityEmojiIsWholeAndUtf16OffsetsMatchHost() {
        val s = "<p>A&#x1F600;B</p>"
        val p = map(s, listOf(Triple("1/1", "A&#x1F600;B", "A😀B")))
        assertEquals("<p>AXB</p>", p.plan(point("1/1", 1), point("1/1", 3), "😀").replace("X"))
    }
    @Test(expected = IllegalArgumentException::class) fun refusesHalfSurrogate() {
        val p = map("<p>A&#x1F600;B</p>", listOf(Triple("1/1", "A&#x1F600;B", "A😀B")))
        p.plan(point("1/1", 1), point("1/1", 2), "\ud83d")
    }
    @Test(expected = IllegalArgumentException::class) fun refusesHalfMultiCodepointEntity() {
        val p = map("<p>&NotEqualTilde;</p>", listOf(Triple("1/1", "&NotEqualTilde;", "≂̸")))
        p.plan(point("1/1", 0), point("1/1", 1), "≂")
    }
    @Test(expected = IllegalArgumentException::class) fun staleQuoteIsNotReplacedElsewhere() {
        val p = map("<p>当前原文</p>", listOf(Triple("1/1", "当前原文", "当前原文")))
        p.plan(point("1/1", 0), point("1/1", 4), "另一原文")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCrossChapter() {
        val p = map("<p>AB</p>", listOf(Triple("1/1", "AB", "AB")))
        p.plan(point("1/1", 0), point("1/1", 2, 13), "AB")
    }
    @Test fun virtualParagraphEndingCanMatchQuoteWithoutTouchingMarkup() {
        val p = map("<p>AB</p>", listOf(Triple("1/1", "AB", "AB")), appendBreak = true)
        assertEquals("<p>X<br/>Y</p>", p.plan(point("1/1", 0), point("1/1", 3), "AB\n").replace("X\nY"))
    }
    @Test fun deletingSelectionKeepsOutsideSourceByteIdentical() {
        val p = map("<p>前中后</p>", listOf(Triple("1/1", "前中后", "前中后")))
        assertEquals("<p>前后</p>", p.plan(point("1/1", 1), point("1/1", 2), "中").replace(""))
    }
    @Test(expected = IllegalArgumentException::class) fun syntheticCfiIsRejected() {
        val p = map("<p>AB</p>", listOf(Triple("1/1", "AB", "AB")))
        p.plan(point("1/0", 0), point("1/1", 2), "AB")
    }
    @Test fun mapsHostSelfClosingNormalizationBackToOriginalPositions() {
        val old = "<p>A<span/>B</p>"
        val normalized = old.replace("<span/>", "<span></span>")
        val p = NormalizedSelectionPositions(old, normalized) { it.replace("<span/>", "<span></span>") }
        val start = normalized.indexOf("B")
        assertEquals(old.indexOf("B") to old.indexOf("B") + 1, p.original(start, start + 1))
    }
    @Test fun cfiMarkerMapsToListPositionRatherThanSpineField() {
        assertEquals(1, readerSelectionSpinePosition(listOf(2, 6, 10), 6))
    }
    @Test(expected = IllegalArgumentException::class) fun repeatedChapterIdsAreRejected() {
        readerSelectionSpinePosition(listOf(2, 6, 6), 6)
    }
    @Test(expected = IllegalArgumentException::class) fun missingChapterIsRejected() {
        readerSelectionSpinePosition(listOf(2, 6, 10), 3)
    }
}
