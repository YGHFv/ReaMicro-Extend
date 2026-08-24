package com.reamicro.fix.cloud.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiServerModelsTest {
    @Test
    fun `normalizes https server address`() {
        assertEquals("https://example.com/api", normalizeApiBaseUrl(" https://example.com/api/ "))
    }

    @Test
    fun `rejects credentials embedded in address`() {
        expectIllegalArgument {
            normalizeApiBaseUrl("https://user:pass@example.com")
        }
    }

    @Test
    fun `http requires explicit local mode`() {
        expectIllegalArgument {
            normalizeApiBaseUrl("http://192.168.1.2:8080")
        }
        assertEquals(
            "http://192.168.1.2:8080",
            normalizeApiBaseUrl("http://192.168.1.2:8080", allowHttp = true),
        )
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
