package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class HostExactRecordCopyTest {
    class Record(
        val id: Long, val title: String, val progress: Float, val cloudId: Long,
        val publisher: String, val uri: String, val listeningTotal: Long, val pinnedAt: Long,
        val contentDownloaded: Boolean,
    )

    private val schema = listOf("id", "title", "progress", "cloudId", "publisher", "uri",
        "listeningTotal", "pinnedAt", "contentDownloaded")

    @Test
    fun clearsOnlyAssociationAndPreservesSameTypeFields() {
        val record = Record(31, "title", 0.71f, 95, "publisher", "content://book", 123, 456, false)
        val bridge = HostExactRecordCopy(Record::class.java, schema)
        val before = bridge.snapshot(record)
        val result = bridge.copy(record, mapOf("cloudId" to 0L, "publisher" to ""))
        assertNotSame(record, result)
        assertEquals(before + mapOf("cloudId" to 0L, "publisher" to ""), bridge.snapshot(result))
        assertEquals(before, bridge.snapshot(record))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnrecognizedNewField() {
        HostExactRecordCopy(Record::class.java, schema.dropLast(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownReplacementField() {
        val bridge = HostExactRecordCopy(Record::class.java, schema)
        bridge.copy(Record(1, "title", 0f, 2, "publisher", "uri", 3, 4, true), mapOf("missing" to 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesConstructorReorderingEvenForSameTypeSlots() {
        val record = Record(31, "title", 0.71f, 95, "publisher", "uri", 123, 456, false)
        val wrong = schema.toMutableList().apply { this[4] = "uri"; this[5] = "publisher" }
        HostExactRecordCopy(Record::class.java, wrong).copy(record, emptyMap())
    }
}
