package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Test

class SelectionTextReplacementTest {
    @Test fun replacementIsTextNotMarkup() {
        assertEquals("<p>&lt;b&gt;你好&amp;&lt;/b&gt;</p>",
            replaceUniqueSelectionText("<p>原文</p>", "原文", "<b>你好&</b>"))
    }
    @Test fun entityIsReplacedAsAWhole() {
        assertEquals("<p>和</p>", replaceUniqueSelectionText("<p>&amp;</p>", "&", "和"))
    }
    @Test fun duplicateSelectionIsRejected() {
        assertNull(replaceUniqueSelectionText("<p>相同</p><p>相同</p>", "相同", "新文字"))
    }
    @Test fun missingSelectionNeverChangesAnotherFile() {
        assertNull(replaceUniqueSelectionText("<p>本章</p>", "另章", "新文字"))
    }
    @Test fun blankSelectionIsRejected() {
        assertNull(replaceUniqueSelectionText("<p>本章</p>", "", "新文字"))
    }
}
