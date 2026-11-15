package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.core.HostRuntimeBindings
import com.reamicro.fix.core.HostRuntimeBridge
import com.reamicro.fix.reload.TaskCancellation
import dalvik.system.InMemoryDexClassLoader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** 保留宿主 OkHttp 的请求、TLS、重定向和流式 IO，仅桥接 R8 已删除的门面。 */
internal class NativeHostHttp(private val loader: ClassLoader, private val cleartextScope: CleartextScope) {
    private val semantic = requireNotNull(HostRuntimeBindings.http) { "宿主 HTTP 接口尚未解析" }
    init { check(semantic.type("client").classLoader === loader) { "宿主 HTTP ClassLoader 已改变" } }
    private val client by lazy {
        semantic.constructor("client").newInstance(semantic.constructor("clientBuilder").newInstance())
    }
    private val fileBody by lazy {
        val bridge = InMemoryDexClassLoader(ByteBuffer.wrap(HostRuntimeBridge.httpBody(semantic)), loader)
            .loadClass("ReaMicroDynamicRequestBody")
        check(semantic.type("requestBody").isAssignableFrom(bridge))
        bridge.getConstructor(File::class.java, semantic.type("media"), java.util.function.Consumer::class.java)
    }
    private fun mediaType(value: String): Any =
        requireNotNull(semantic.method("media").invoke(null, value)) { "宿主无法解析 MIME 类型：$value" }

    fun stringBody(value: String, mime: String): Any {
        return byteBody(value.toByteArray(StandardCharsets.UTF_8), mime)
    }

    fun byteBody(bytes: ByteArray, mime: String): Any =
        semantic.constructor("byteBody").newInstance(bytes.size, mediaType(mime), bytes)
    fun fileBody(file: File, mime: String): Any {
        val contract = semantic
        val writer = java.util.function.Consumer<Any> { sink ->
            TaskCancellation.checkCurrent()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    TaskCancellation.checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    TaskCancellation.checkCurrent()
                    try { contract.method("sinkWrite").invoke(sink, buffer, 0, count) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                }
                TaskCancellation.checkCurrent()
            }
        }
        return fileBody.newInstance(file, mediaType(mime), writer)
    }

    fun request(
        url: String, method: String, headers: Map<String, String>, body: Any?,
        outputFile: File? = null, onProgress: ((Int) -> Unit)? = null,
        binary: Boolean = false,
        captureHeaders: Boolean = false,
    ): WebDavHttpResponse {
        TaskCancellation.checkCurrent()
        val builder = semantic.constructor("requestBuilder").newInstance(semantic.requestBuilderTag)
        semantic.method("url").invoke(builder, url)
        for ((key, value) in headers) semantic.method("header").invoke(builder, key, value)
        semantic.method("setMethod").invoke(builder, method, body)
        val request = semantic.constructor("request").newInstance(builder)
        val call = semantic.constructor("call").newInstance(client, request, false)
        val registration = TaskCancellation.current()?.register {
            semantic.method("cancel").invoke(call)
        }
        try {
            TaskCancellation.checkCurrent()
            val response = cleartextScope.run { semantic.method("execute").invoke(call) } as Closeable
            response.use {
                TaskCancellation.checkCurrent()
                val code = semantic.field("code").getInt(response)
                val responseHeaders = if (!captureHeaders) emptyMap() else listOf("ETag", "Content-Length", "Content-Type").mapNotNull { name ->
                    (semantic.method("headerValue").invoke(semantic.field("headers").get(response), name) as? String)
                        ?.let { name to it }
                }.toMap()
                val responseBody = semantic.field("responseBody").get(response)
                if (method == "HEAD" || responseBody == null) return WebDavHttpResponse(code, null, responseHeaders)
                val source = semantic.method("source").invoke(responseBody)
                if (outputFile == null) {
                    val bytes = (semantic.method("input").invoke(source) as InputStream).use(::readMetadata)
                    return if (binary) {
                        WebDavHttpResponse(code, null, responseHeaders, bytes)
                    } else {
                        WebDavHttpResponse(code, bytes.toString(StandardCharsets.UTF_8), responseHeaders)
                    }
                }
                if (code in 200..299) {
                    val total = semantic.method("responseLength").invoke(responseBody) as Long
                    val input = semantic.method("input").invoke(source) as InputStream
                    input.use {
                        outputFile.outputStream().use { sink ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            var reported = -1
                            while (true) {
                                TaskCancellation.checkCurrent()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                TaskCancellation.checkCurrent()
                                sink.write(buffer, 0, count)
                                TaskCancellation.checkCurrent()
                                copied += count
                                if (total > 0) {
                                    val percent = (copied * 100 / total).toInt().coerceIn(0, 99)
                                    if (percent != reported) {
                                        reported = percent
                                        onProgress?.invoke(percent)
                                    }
                                }
                            }
                            TaskCancellation.checkCurrent()
                            onProgress?.invoke(100)
                        }
                    }
                }
                TaskCancellation.checkCurrent()
                return WebDavHttpResponse(code, null, responseHeaders)
            }
        } catch (error: Throwable) {
            TaskCancellation.rethrowIfCancelled(error)
            throw (if (error is InvocationTargetException) error.targetException else error)
        } finally {
            registration?.close()
        }
    }

    private fun readMetadata(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            TaskCancellation.checkCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            check(output.size().toLong() + count <= 64L * 1024 * 1024) { "WebDAV metadata response is too large" }
            output.write(buffer, 0, count)
        }
        TaskCancellation.checkCurrent()
        return output.toByteArray()
    }
}
