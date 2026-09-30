package com.reamicro.fix.epub.editor
import org.junit.Assert.*
import org.junit.Test

class SelectionRefreshValidationTest {
    @Test fun acceptsSameOrNeighbouringReadableChapter() {
        assertTrue(validSelectionRefreshPage(true, 0, 3))
        assertTrue(validSelectionRefreshPage(true, 2, 3))
    }
    @Test fun rejectsMissingPage() {
        assertFalse(validSelectionRefreshPage(false, 0, 3))
    }
    @Test fun rejectsMissingOrOutOfBoundsMapping() {
        assertFalse(validSelectionRefreshPage(true, null, 3))
        assertFalse(validSelectionRefreshPage(true, -1, 3))
        assertFalse(validSelectionRefreshPage(true, 3, 3))
        assertFalse(validSelectionRefreshPage(true, 0, 0))
    }
}
