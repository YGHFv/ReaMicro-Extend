package com.reamicro.fix.webdav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnlineFanqieBatchCompatTest {
    private val endpoint = "/api/fanqie/books/{{bookId}}/chapters/batch"

    @Test
    fun `requires explicit config and exact https host`() {
        assertTrue(supportsOnlineFanqieBatch("https://api.yuezhi.me", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me", "", 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me", "/api/other/{{bookId}}", 30))
        assertFalse(supportsOnlineFanqieBatch("http://api.yuezhi.me", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me.evil.test", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me:8443", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me/prefix", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me?proxy=other", endpoint, 30))
        assertFalse(supportsOnlineFanqieBatch("https://api.yuezhi.me", endpoint, 31))
    }

    @Test
    fun `extracts only supported fanqie chapter urls`() {
        assertEquals(
            OnlineFanqieBatchChapterRef("7502283561827847230", "7503570118811583001"),
            parseOnlineFanqieBatchChapterRef(
                "https://api.yuezhi.me/v1/books/7502283561827847230/chapters/7503570118811583001?source=fanqie",
            ),
        )
        assertNull(parseOnlineFanqieBatchChapterRef("https://other.test/v1/books/1/chapters/2"))
        assertNull(parseOnlineFanqieBatchChapterRef("https://api.yuezhi.me:8443/v1/books/1/chapters/2"))
        assertNull(parseOnlineFanqieBatchChapterRef("https://api.yuezhi.me/v1/books/1/directory"))
    }

    @Test
    fun `builds capped request body and endpoint`() {
        assertEquals(
            "https://api.yuezhi.me/api/fanqie/books/7502283561827847230/chapters/batch",
            buildOnlineFanqieBatchEndpoint("https://api.yuezhi.me", endpoint, "7502283561827847230"),
        )
        val body = JSONObject(buildOnlineFanqieBatchBody(listOf("2", "1", "2")))
        assertEquals(2, body.getJSONArray("item_ids").length())
        assertEquals("2", body.getJSONArray("item_ids").getString(0))
        assertEquals(1, body.getInt("novel_text_type"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects more than thirty ids`() {
        buildOnlineFanqieBatchBody((1..31).map(Int::toString))
    }

    @Test
    fun `indexes partial unordered response by item id and keeps html`() {
        val parsed = parseOnlineFanqieBatchResponse(
            """{"ok":true,"chapters":[{"itemId":"2","contentHtml":"<p>二</p><img src=\"x\"/>","content":"二"},{"itemId":"1","contentHtml":"<p>一</p>"}]}""",
        )
        assertEquals(listOf("2", "1"), parsed.keys.toList())
        assertTrue(parsed.getValue("2").contains("<img"))
    }

    @Test
    fun `keeps circuit open response in service backoff path`() {
        try {
            parseOnlineFanqieBatchResponse(
                """{"ok":false,"detail":"fanqie upstream circuit is open"}""",
            )
            fail("应抛出服务级 HTTP 异常")
        } catch (error: OnlineSourceHttpException) {
            assertEquals(503, error.statusCode)
            assertEquals(OnlineHttpRetryKind.CIRCUIT_OPEN, onlineHttpRetryKind(error))
        }
    }
}
