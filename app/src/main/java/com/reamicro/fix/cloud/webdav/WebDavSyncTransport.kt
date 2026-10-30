package com.reamicro.fix.cloud.webdav

import android.util.Base64
import java.io.File
import java.util.UUID

internal class WebDavSyncTransport(
    private val http: B2HostHttp,
    private val url: String,
    username: String,
    password: String,
    private val onHttpError: (Int) -> Nothing,
) {
    private val authorization = if (username.isEmpty() && password.isEmpty()) emptyMap() else mapOf(
        "Authorization" to "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
        ),
    )

    private fun request(
        method: String, key: String, bytes: ByteArray? = null, file: File? = null,
        mime: String = "application/octet-stream", condition: String? = null, output: File? = null,
    ): WebDavHttpResponse {
        val headers = authorization + WebDavSyncProtocol.condition(condition)
        val body = when {
            file != null -> http.fileBody(file, mime)
            bytes != null -> http.byteBody(bytes, mime)
            else -> null
        }
        val endpoint = WebDavSyncProtocol.objectUrl(url, key) + if (method == "MKCOL") "/" else ""
        return http.request(endpoint, method, headers, body,
            outputFile = output, binary = method == "GET" && output == null, captureHeaders = true)
    }

    fun get(key: String): WebDavHttpResponse? {
        val response = request("GET", key)
        if (response.code == 404) return null
        checkStatus(response)
        // 无版本号的清单会导致并发设备互相覆盖，宁可拒绝也不退化成无条件写。
        WebDavSyncProtocol.requireStrongEtag(response.headers["ETag"])
        return response
    }

    fun head(key: String): WebDavHttpResponse? {
        val response = request("HEAD", key)
        if (response.code == 404) return null
        checkStatus(response)
        return response
    }

    fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
        ensureParents(key)
        checkStatus(request("PUT", key, bytes, file, mime, condition))
    }

    fun delete(key: String) {
        val response = request("DELETE", key)
        if (response.code != 404) checkStatus(response)
    }

    fun download(key: String, output: File) {
        val parent = requireNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建同步下载目录" }
        val temp = File.createTempFile("webdav-sync-", ".part", parent)
        try {
            checkStatus(request("GET", key, output = temp))
            check(temp.renameTo(output)) { "无法替换同步下载文件" }
        } finally {
            temp.delete()
        }
    }

    /** 在用户保存时仅探测随机私有对象，校验两种条件写并清理，不访问书架清单。 */
    fun verify() {
        val key = ".reamicro-webdav-probe-${UUID.randomUUID()}/test.bin"
        try {
            put(key, byteArrayOf(0, 1, 2, -1), null, "application/octet-stream", "*")
            val first = requireNotNull(get(key)) { "WebDAV 无法读回测试对象" }
            check(first.bodyBytes?.contentEquals(byteArrayOf(0, 1, 2, -1)) == true) { "WebDAV 读回内容不一致" }
            check(request("PUT", key, byteArrayOf(9), condition = "*").code == 412) {
                "WebDAV 不支持 If-None-Match，不能安全同步"
            }
            put(key, byteArrayOf(3, 4), null, "application/octet-stream", first.headers["ETag"])
            check(request("PUT", key, byteArrayOf(9), condition = "\"reamicro-invalid-etag\"").code == 412) {
                "WebDAV 不支持 If-Match，不能安全同步"
            }
        } finally {
            runCatching { delete(key) }
            runCatching { delete(key.substringBeforeLast('/')) }
        }
    }

    private fun ensureParents(key: String) {
        val segments = key.split('/').dropLast(1)
        for (index in segments.indices) {
            val parent = segments.take(index + 1).joinToString("/")
            val response = request("MKCOL", parent)
            if (response.code == 405) {
                // 405 也可能表示服务禁用 MKCOL，不能直接当作目录已经存在。
                checkStatus(request("HEAD", parent))
            } else checkStatus(response)
        }
    }

    private fun checkStatus(response: WebDavHttpResponse) {
        if (response.code !in 200..299) onHttpError(response.code)
    }
}
