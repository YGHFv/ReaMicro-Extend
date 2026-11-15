package com.reamicro.fix.association.network

import com.reamicro.fix.association.provider.YouShuHttpSession
import com.reamicro.fix.reload.TaskCancellation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object HttpClient {
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
        followRedirects: Boolean = true,
    ): String = request(url, "GET", null, null, headers, connectTimeoutMs, readTimeoutMs, followRedirects)
        .toString(StandardCharsets.UTF_8)

    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
        followRedirects: Boolean = true,
    ): ByteArray = request(url, "GET", null, null, headers, connectTimeoutMs, readTimeoutMs, followRedirects,
        accept = "application/octet-stream,application/json,*/*")

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
    ): String = request(url, "POST", form.toQueryString().toByteArray(StandardCharsets.UTF_8),
        "application/x-www-form-urlencoded; charset=UTF-8", headers, connectTimeoutMs, readTimeoutMs, true)
        .toString(StandardCharsets.UTF_8)

    fun postBytes(
        url: String,
        body: ByteArray,
        contentType: String = "application/octet-stream",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 30_000,
    ): String = request(url, "POST", body, contentType, headers, connectTimeoutMs, readTimeoutMs, false,
        accept = "application/json").toString(StandardCharsets.UTF_8)

    fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
    ): String = request(url, "DELETE", null, null, headers, connectTimeoutMs, readTimeoutMs, false,
        accept = "application/json").toString(StandardCharsets.UTF_8)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun Map<String, String>.toQueryString(): String = entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun request(
        url: String, method: String, body: ByteArray?, contentType: String?,
        headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int, followRedirects: Boolean,
        accept: String = "application/json,text/html;q=0.9,*/*;q=0.8",
    ): ByteArray {
        var target = HttpRequestPolicy.uri(url).toURL().toString()
        var verb = method
        var payload = body
        var mime = contentType
        var requestHeaders = headers
        val youShuSession = YouShuHttpSession.open(target, headers)
        var redirects = 0
        while (true) {
            val hopHeaders = youShuSession?.headers(target, requestHeaders) ?: requestHeaders
            val response = requestOnce(target, verb, payload, mime, hopHeaders, connectTimeoutMs, readTimeoutMs, accept,
                youShuSession)
            if (followRedirects && response.code in REDIRECT_CODES && response.location != null) {
                if (redirects++ >= MAX_REDIRECTS) throw IOException("HTTP 重定向次数过多")
                val next = HttpRequestPolicy.uri(URL(URL(target), response.location).toString()).toURL().toString()
                if (URL(target).protocol.equals("https", true) && URL(next).protocol.equals("http", true)) {
                    throw IOException("HTTP 重定向不能降级为明文连接")
                }
                if ((response.code == 303 && verb != "HEAD") || (response.code in setOf(301, 302) && verb == "POST")) {
                    verb = "GET"
                    payload = null
                    mime = null
                    requestHeaders = requestHeaders.filterKeys {
                        !it.equals("Content-Type", true) && !it.equals("Content-Length", true) && !it.equals("Transfer-Encoding", true)
                    }
                }
                if (payload != null && !HttpRequestPolicy.sameOrigin(target, next)) {
                    throw IOException("不能向另一服务器重发请求内容")
                }
                requestHeaders = HttpRequestPolicy.redirectHeaders(requestHeaders, target, next)
                target = next
                continue
            }
            if (response.code !in 200..299) {
                throw HttpResponseException(response.code, response.bytes.toString(StandardCharsets.UTF_8))
            }
            return response.bytes
        }
    }

    private data class Response(val code: Int, val bytes: ByteArray, val location: String?)

    private fun requestOnce(
        url: String, method: String, body: ByteArray?, contentType: String?,
        headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int, accept: String,
        youShuSession: YouShuHttpSession.Session?,
    ): Response {
        TaskCancellation.checkCurrent()
        val connection = URL(url).openConnection() as HttpURLConnection
        val cancellation = TaskCancellation.current()?.register(connection::disconnect)
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            // Handle each hop ourselves so custom API keys and cookies cannot cross origins.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            connection.setRequestProperty("Accept", accept)
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            return AssociationNetworkScope.begin(url).use {
                TaskCancellation.checkCurrent()
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { output ->
                        TaskCancellation.checkCurrent()
                        output.write(body)
                    }
                }
                val code = connection.responseCode
                TaskCancellation.checkCurrent()
                if (youShuSession != null) {
                    val cookies = connection.headerFields.entries
                        .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                        .flatMap { it.value.orEmpty() }
                    youShuSession.acceptResponse(url, cookies, headers)
                }
                // For an empty 404/503 response, inputStream throws before callers
                // can inspect the HTTP status. An absent error stream is an empty body.
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        TaskCancellation.checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                TaskCancellation.checkCurrent()
                Response(code, bytes, connection.getHeaderField("Location"))
            }
        } catch (error: Throwable) {
            TaskCancellation.rethrowIfCancelled(error)
            throw error
        } finally {
            cancellation?.close()
            connection.disconnect()
        }
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36"
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}

internal class HttpResponseException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("HTTP $statusCode")
