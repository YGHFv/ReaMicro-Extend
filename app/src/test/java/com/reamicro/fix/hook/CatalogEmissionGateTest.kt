package com.reamicro.fix.hook

import org.junit.Assert.*
import org.junit.Test

class CatalogEmissionGateTest {
    @Test fun unrelatedBookInvalidationsDoNotReemitCurrentCatalog() {
        val gate = CatalogEmissionGate()
        assertTrue(gate.accept(listOf("chapter")))
        repeat(1000) { assertFalse(gate.accept(arrayListOf("chapter"))) }
    }

    @Test fun currentBookChangesAndRealDeletionStillPropagate() {
        val gate = CatalogEmissionGate()
        assertTrue(gate.accept(listOf(1)))
        assertTrue(gate.accept(listOf(1, 2)))
        assertTrue(gate.accept(emptyList<Int>()))
        assertFalse(gate.accept(emptyList<Int>()))
    }

    @Test fun newCollectorGetsFirstValueEvenWhenOtherCollectorSawIt() {
        val first = CatalogEmissionGate()
        val second = CatalogEmissionGate()
        assertTrue(first.accept(listOf(1)))
        assertTrue(second.accept(listOf(1)))
        assertTrue(first.accept(listOf(2)))
        assertFalse(second.accept(listOf(1)))
    }

    @Test fun initialEmptyCatalogIsNotDropped() {
        assertTrue(CatalogEmissionGate().accept(emptyList<Int>()))
    }

    @Test fun onlySingleCatalogTableFlowsAreSelected() {
        assertTrue(ReaderCatalogFlowGuard.relevant(arrayOf("book_item_ref")))
        assertTrue(ReaderCatalogFlowGuard.relevant(arrayOf("book_chapter")))
        assertFalse(ReaderCatalogFlowGuard.relevant(arrayOf("books")))
        assertFalse(ReaderCatalogFlowGuard.relevant(arrayOf("marks")))
        assertFalse(ReaderCatalogFlowGuard.relevant(arrayOf("book_chapter", "books")))
        assertFalse(ReaderCatalogFlowGuard.relevant(emptyArray<String>()))
    }
}
