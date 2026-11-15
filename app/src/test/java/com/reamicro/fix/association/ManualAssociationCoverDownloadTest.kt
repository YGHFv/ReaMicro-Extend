package com.reamicro.fix.association

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ManualAssociationCoverDownloadTest {
    @Test fun redirectsAndChunkedResponsesKeepTheDownloadedBytes() = withServer { server, client, base ->
        val bytes = "仅供测试的图片数据".toByteArray(Charsets.UTF_8)
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "/image")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.createContext("/image") {
            it.sendResponseHeaders(200, 0)
            it.responseBody.use { output -> output.write(bytes) }
        }
        assertArrayEquals(bytes, ManualAssociationCoverDownload.read(client, "$base/redirect") {})
    }

    @Test fun emptyContentAndHttpErrorsCannotGeneratePreview() = withServer { server, client, base ->
        server.createContext("/empty") { it.sendResponseHeaders(200, -1); it.close() }
        server.createContext("/missing") { it.sendResponseHeaders(404, -1); it.close() }
        assertThrows(IllegalArgumentException::class.java) {
            ManualAssociationCoverDownload.read(client, "$base/empty") {}
        }
        assertThrows(IllegalStateException::class.java) {
            ManualAssociationCoverDownload.read(client, "$base/missing") {}
        }
    }

    @Test fun sizeLimitAppliesEvenWithoutContentLength() = withServer { server, client, base ->
        server.createContext("/large") {
            it.sendResponseHeaders(200, 0)
            runCatching {
                it.responseBody.use { output ->
                    val chunk = ByteArray(1024 * 1024)
                    repeat(25) { output.write(chunk) }
                }
            }
            it.close()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualAssociationCoverDownload.read(client, "$base/large") {}
        }
    }

    @Test fun invalidatedPageStopsDownloadBeforeReturningData() = withServer { server, client, base ->
        server.createContext("/image") {
            it.sendResponseHeaders(200, 3)
            it.responseBody.use { output -> output.write(byteArrayOf(1, 2, 3)) }
        }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ManualAssociationCoverDownload.read(client, "$base/image") {
                if (++checks >= 3) throw CancellationException("测试页面已退出")
            }
        }
    }

    private fun withServer(test: (HttpServer, OkHttpClient, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        server.start()
        try { test(server, client, "http://127.0.0.1:${server.address.port}") } finally {
            server.stop(0)
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
