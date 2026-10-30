package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.hostClass
import dalvik.system.InMemoryDexClassLoader
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** 保留宿主 OkHttp 的请求、TLS、重定向和流式 IO，仅桥接 R8 已删除的门面。 */
internal class B2HostHttp(private val loader: ClassLoader, private val cleartextScope: CleartextScope) {
    private val abi = requireNotNull(HostNativeAbi.http(HostSymbols.profileId)) { "Unverified native HTTP host" }
    private fun type(role: String): Class<*> {
        check(HostNativeAbi.hasNativeHttp(HostSymbols.profileId))
        return loader.hostClass("ReaMicroHost.Http$role")
    }
    private val client by lazy {
        type("Client").getDeclaredConstructor(type("ClientBuilder"))
            .newInstance(type("ClientBuilder").getDeclaredConstructor().newInstance())
    }
    private val fileBody by lazy {
        val bytes = HostSymbols.bundledAsset("assets/host-bridges/http-${HostSymbols.profileId}.dex")
        val bridge = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), loader)
            .loadClass("ReaMicroStreamingRequestBody")
        check(type("RequestBody").isAssignableFrom(bridge))
        bridge.getConstructor(File::class.java, type("MediaType"))
    }

    private val streamingVerified by lazy {
        val application = Class.forName("android.app.ActivityThread").hostAbiMethod("currentApplication").invoke(null) as android.app.Application
        val file = File.createTempFile("ReaMicro-http-probe-", ".tmp", application.cacheDir)
        try {
            val bytes = "ReaMicro streaming ABI".toByteArray(StandardCharsets.UTF_8)
            file.writeBytes(bytes)
            val output = java.io.ByteArrayOutputStream()
            val sink = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(type("BufferedSink"))) { proxy, method, args ->
                when (method.name) {
                    "write" -> {
                        output.write(args!![0] as ByteArray, args[1] as Int, args[2] as Int)
                        proxy
                    }
                    "toString" -> "ReaMicroHttpProbeSink"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected streaming probe call: $method")
                }
            }
            val body = fileBody(file, "application/octet-stream")
            type("RequestBody").hostAbiMethod("e", type("BufferedSink")).invoke(body, sink)
            check(output.toByteArray().contentEquals(bytes))
            com.reamicro.fix.xposed.XposedBridge.logAlways("ReaMicro native HTTP streaming protocol passed bytes=${bytes.size}")
            true
        } finally {
            check(file.delete()) { "Unable to remove the task-owned HTTP protocol probe" }
        }
    }

    private fun mediaType(value: String): Any =
        type("MediaTypeFactory").hostAbiMethod("b", String::class.java).invoke(null, value)

    fun stringBody(value: String, mime: String): Any {
        return byteBody(value.toByteArray(StandardCharsets.UTF_8), mime)
    }

    fun byteBody(bytes: ByteArray, mime: String): Any {
        return type("ByteBody").getDeclaredConstructor(Int::class.javaPrimitiveType, type("MediaType"), ByteArray::class.java)
            .newInstance(bytes.size, mediaType(mime), bytes)
    }

    fun fileBody(file: File, mime: String): Any = fileBody.newInstance(file, mediaType(mime))

    fun request(
        url: String, method: String, headers: Map<String, String>, body: Any?,
        outputFile: File? = null, onProgress: ((Int) -> Unit)? = null,
        binary: Boolean = false,
        captureHeaders: Boolean = false,
    ): WebDavHttpResponse {
        check(streamingVerified)
        val builderType = type("RequestBuilder")
        val builder = builderType.getDeclaredConstructor(Byte::class.javaPrimitiveType).newInstance(abi.builderBranch)
        check(builderType.hostAbiField("a").getByte(builder).toInt() == abi.builderBranch.toInt())
        if (HostSymbols.abiProfileId == "2.3.3-b4") {
            // R8 默认分支也会原样保存编号；必须验证请求方法和请求头容器的真实类型。
            check(builderType.hostAbiField("c").get(builder) == "GET")
            check(type("HeadersBuilder").isInstance(builderType.hostAbiField("d").get(builder)))
        }
        builderType.getDeclaredMethod(abi.url, String::class.java).invoke(builder, url)
        for ((key, value) in headers) builderType.getDeclaredMethod(abi.header, String::class.java, String::class.java).invoke(builder, key, value)
        builderType.getDeclaredMethod(abi.method, String::class.java, type("RequestBody")).invoke(builder, method, body)
        val request = type("Request").getDeclaredConstructor(builderType).newInstance(builder)
        val call = type("Call").getDeclaredConstructor(type("Client"), type("Request"), Boolean::class.javaPrimitiveType)
            .newInstance(client, request, false)
        val response = cleartextScope.run { type("Call").hostAbiMethod("d").invoke(call) }
        try {
            val code = type("Response").hostAbiField("d").getInt(response)
            val responseHeaders = if (!captureHeaders) emptyMap() else listOf("ETag", "Content-Length", "Content-Type").mapNotNull { name ->
                (type("Response").getDeclaredMethod("g", type("Response"), String::class.java).invoke(null, response, name) as? String)
                    ?.let { name to it }
            }.toMap()
            val responseBody = type("Response").hostAbiField("g").get(response)
            if (method == "HEAD" || responseBody == null) return WebDavHttpResponse(code, null, responseHeaders)
            val source = type("ResponseBody").hostAbiMethod("c1").invoke(responseBody)
            if (outputFile == null) {
                if (binary) {
                    val input = type("BufferedSource").hostAbiMethod("i1").invoke(source) as InputStream
                    val bytes = input.use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            check(output.size().toLong() + count <= 64L * 1024 * 1024) { "WebDAV metadata response is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    return WebDavHttpResponse(code, null, responseHeaders, bytes)
                }
                val text = type("BufferedSource").hostAbiMethod("o0", java.nio.charset.Charset::class.java)
                    .invoke(source, StandardCharsets.UTF_8) as String
                return WebDavHttpResponse(code, text, responseHeaders)
            }
            if (code in 200..299) {
                val total = type("ResponseBody").hostAbiMethod("g").invoke(responseBody) as Long
                val input = type("BufferedSource").hostAbiMethod("i1").invoke(source) as InputStream
                input.use {
                    outputFile.outputStream().use { sink ->
                        val buffer = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            sink.write(buffer, 0, count)
                            copied += count
                            if (total > 0) onProgress?.invoke((copied * 100 / total).toInt().coerceIn(0, 100))
                        }
                        onProgress?.invoke(100)
                    }
                }
            }
            return WebDavHttpResponse(code, null, responseHeaders)
        } finally {
            (response as Closeable).close()
        }
    }
}
