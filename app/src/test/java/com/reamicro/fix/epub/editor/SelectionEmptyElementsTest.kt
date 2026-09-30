package com.reamicro.fix.epub.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionEmptyElementsTest {
    private fun delete(source: String, vararg pieces: String): String {
        var cursor = 0
        val ranges = pieces.map {
            val at = source.indexOf(it, cursor)
            check(at >= 0)
            cursor = at + it.length
            at until cursor
        }
        val point = SelectionCfiPoint(6, 2, listOf(1), 0)
        return SelectionSourcePlan(source, ranges, pieces.joinToString(""), point, point).replace("")
    }
    @Test fun wholeParagraphDeletesBothTags() {
        assertEquals("<body><p>后</p></body>", delete("<body><p>整段</p><p>后</p></body>", "整段"))
    }
    @Test fun nestedInlineTagsAreRemovedTogether() {
        assertEquals("<body></body>", delete("<body><p class='text'>前<b>中</b>后</p></body>", "前", "中", "后"))
    }
    @Test fun deletingPartDoesNotRemoveParagraph() {
        assertEquals("<p>前后</p>", delete("<p>前中后</p>", "中"))
    }
    @Test fun newlyEmptyInlinePairIsRemoved() {
        assertEquals("<p>前后</p>", delete("<p>前<b>中</b>后</p>", "中"))
    }
    @Test fun imageAndContainingParagraphSurvive() {
        assertEquals("<p><img src='a.png'/></p>", delete("<p>文字<img src='a.png'/></p>", "文字"))
    }
    @Test fun existingEmptyParagraphIsNotCleaned() {
        assertEquals("<p></p>", delete("<p></p><p>文字</p>", "文字"))
    }
    @Test fun anchorIsPreservedForNavigation() {
        assertEquals("<p id='chapter'></p>", delete("<p id='chapter'>文字</p>", "文字"))
    }
    @Test fun quotedAngleBracketsDoNotConfuseTokenizer() {
        assertEquals("", delete("<p title='1 > 0'>文字</p>", "文字"))
    }
    @Test fun unmatchedMarkupDoesNotCauseSpeculativeDeletion() {
        assertEquals("<p><b></p>", delete("<p><b>文字</p>", "文字"))
    }
    @Test fun commentIsPreserved() {
        assertEquals("<p><!--keep--></p>", delete("<p>文字<!--keep--></p>", "文字"))
    }
    @Test fun scriptMarkupIsNotInterpretedAsElements() {
        assertEquals("<script>let s='<p>';</script>", delete("<script>let s='<p>';</script><p>文字</p>", "文字"))
    }
    @Test fun outsideWhitespaceUnchanged() {
        assertEquals("\r\n \r\n", delete("\r\n <p> 文字 </p>\r\n", "文字"))
    }
}
