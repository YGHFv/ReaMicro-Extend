package com.reamicro.fix.cloud.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `selects public mode without credentials`() {
        val meta = ApiServerMeta("1", "server", emptySet(), setOf(ApiAuthMode.PUBLIC), "", "")
        assertEquals(ApiAuthMode.PUBLIC, selectApiAuthMode(meta, ApiServerSettings()))
    }

    @Test
    fun `prefers current host account for allowlist`() {
        val meta = ApiServerMeta(
            "1",
            "server",
            emptySet(),
            setOf(ApiAuthMode.API_KEY, ApiAuthMode.HOST_ACCOUNT_ALLOWLIST),
            "",
            "",
        )
        assertEquals(
            ApiAuthMode.HOST_ACCOUNT_ALLOWLIST,
            selectApiAuthMode(meta, ApiServerSettings(hostAccountId = "1001")),
        )
        assertNull(selectApiAuthMode(meta, ApiServerSettings()))
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
