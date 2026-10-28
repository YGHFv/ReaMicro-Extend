package com.reamicro.fix.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HostImportArgumentsTest {
    @Test fun sourceClearsOnlyItsOwnDefaultBits() {
        val args = Array<Any?>(9) { Any() }.apply { this[8] = 0xff }
        val before = args.copyOf()
        HostImportArguments.bindDefault(args, "https://fixture.invalid/Book.txt", 131L)
        for (index in listOf(0, 1, 2, 3, 6, 7)) assertSame(before[index], args[index])
        assertEquals("https://fixture.invalid/Book.txt", args[4])
        assertEquals(131L, args[5])
        assertEquals(0xe7, args[8])
    }

    @Test fun missingSizeKeepsHostValueAndDefaultBit() {
        for (size in listOf(null, 0L, -1L)) {
            val args = arrayOfNulls<Any>(9).apply { this[5] = 256L; this[8] = 56 }
            HostImportArguments.bindDefault(args, "https://fixture.invalid/Book.epub", size)
            assertEquals(256L, args[5])
            assertEquals(48, args[8])
        }
    }

    @Test fun invalidContractDoesNotPartiallyModifyArguments() {
        for (args in listOf(arrayOfNulls<Any>(8), arrayOfNulls<Any>(9))) {
            val before = args.copyOf()
            try {
                HostImportArguments.bindDefault(args, "https://fixture.invalid/Book.txt", 131)
                error("Invalid contract accepted")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(before, args)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankSourceCannotEraseHostProvenance() {
        HostImportArguments.bindDefault(arrayOfNulls<Any>(9).apply { this[8] = 56 }, "", 131)
    }
}
