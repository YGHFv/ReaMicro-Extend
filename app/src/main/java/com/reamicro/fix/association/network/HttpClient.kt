package com.reamicro.fix.association.network

import java.io.BufferedReader
import java.io.InputStreamReader
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
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return connection.readTextAndClose()
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
    ): String {
        val body = form.toQueryString().toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        connection.outputStream.use { it.write(body) }
        return connection.readTextAndClose()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun Map<String, String>.toQueryString(): String = entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun HttpURLConnection.readTextAndClose(): String = try {
        val stream = if (responseCode in 200..299) inputStream else errorStream ?: inputStream
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    } finally {
        disconnect()
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36"
}
