package com.reamicro.fix.external.source

import com.reamicro.fix.association.network.AssociationNetworkScope
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class FanQieRedirectTest {
    @Test fun followsChangedPortWithoutKeepingPermission() {
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val next = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        next.createContext("/v2/search") {
            val data = """{"code":200,"data":[]}""".toByteArray()
            it.sendResponseHeaders(200, data.size.toLong())
            it.responseBody.use { out -> out.write(data) }
        }
        first.createContext("/search") {
            it.responseHeaders.add("Location", "http://127.0.0.1:${next.address.port}/v2/search")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        first.start(); next.start()
        try {
            assertEquals("""{"code":200,"data":[]}""",
                SourceUtils.get("http://127.0.0.1:${first.address.port}/search", 1000, 1000))
            assertFalse(AssociationNetworkScope.allowsHost("127.0.0.1"))
        } finally { first.stop(0); next.stop(0) }
    }

    @Test fun resolvesRelativeLocationAndAcceptsHttpsUpgrade() {
        assertEquals("http://example.com:4321/v2/search",
            SourceUtils.redirectTarget("http://example.com:4321/old", "/v2/search"))
        assertEquals("https://new.example/search",
            SourceUtils.redirectTarget("http://example.com:4321/old", "https://new.example/search"))
    }

    @Test fun refusesDowngradeNonHttpAndEmbeddedCredentials() {
        for (target in listOf("http://example.com/x", "file:///etc/hosts", "https://user:pass@example.com/x")) {
            try {
                SourceUtils.redirectTarget("https://example.com/search", target)
                fail("accepted unsafe redirect: $target")
            } catch (_: IOException) { }
        }
    }
}
