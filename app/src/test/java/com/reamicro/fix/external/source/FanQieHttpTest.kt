package com.reamicro.fix.external.source

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanQieHttpTest {
    @Test
    fun localHttpRoundTripUsesCorrectRouteAndReturnsMetadata() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val query = AtomicReference("")
        val book = JSONObject().put("book_id", "7123456789012345678")
            .put("book_name", "测试小说").put("source", "番茄").put("tab", "小说")
        val payload = JSONObject().put("data", JSONArray().put(book)).toString().toByteArray()
        server.createContext("/search") { exchange ->
            query.set(exchange.requestURI.rawQuery)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val provider = FanQieSourceProvider(arrayOf("http://127.0.0.1:${server.address.port}")) { url, connect, read ->
                SourceUtils.get(url, connect, read)
            }
            assertEquals(1, provider.search("测试&书", 10).size)
            assertTrue(query.get().contains("title=" + URLEncoder.encode("测试&书", "UTF-8")))
            assertTrue(query.get().endsWith("disabled_sources=0"))
        } finally {
            server.stop(0)
        }
    }
}
