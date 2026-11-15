package com.reamicro.fix.association

import com.reamicro.fix.association.model.ManualAssociationDraft
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** 预览只下载有界的图片数据，不写入图书文件或出版信息。 */
internal object ManualAssociationCoverDownload {
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

    fun read(client: OkHttpClient, url: String, checkCurrent: () -> Unit): ByteArray {
        require(ManualAssociationDraft.isCoverAddress(url)) { "请输入有效的 HTTP 或 HTTPS 封面地址" }
        checkCurrent()
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            call.execute().use { response ->
                checkCurrent()
                check(response.isSuccessful) { "封面下载失败：HTTP ${response.code}" }
                val body = response.body
                require(body.contentLength() <= MAX_IMAGE_BYTES) { "封面图片超过 24 MiB" }
                return body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count <= MAX_IMAGE_BYTES - output.size()) { "封面图片超过 24 MiB" }
                        output.write(buffer, 0, count)
                    }
                    checkCurrent()
                    output.toByteArray().also { require(it.isNotEmpty()) { "封面下载返回空内容" } }
                }
            }
        } finally { call.cancel() }
    }
}
