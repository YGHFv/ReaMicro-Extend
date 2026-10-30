package com.reamicro.fix.cloud.webdav

import java.net.URI

/** 使用独立标记隔离 S3 配置；同步清单和冲突重试仍由宿主维护。 */
internal object WebDavSyncProtocol {
    const val BUCKET = "reamicro-webdav-sync-v1"
    const val ANONYMOUS = "reamicro-webdav-anonymous-v1"

    fun baseUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "请输入有效的 WebDAV HTTP(S) 地址" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "地址不能包含凭据、查询参数或片段" }
        require(uri.path.orEmpty().split('/').none { it in setOf(".", "..") }) { "地址不能包含相对路径" }
        return uri.toASCIIString().trimEnd('/')
    }

    fun objectUrl(base: String, key: String): String {
        require(key.isNotBlank() && !key.startsWith('/') && '\\' !in key) { "无效的同步对象路径" }
        val segments = key.split('/')
        require(segments.none { it.isBlank() || it in setOf(".", "..") }) { "同步对象不能越过根目录" }
        val encoded = segments.joinToString("/") { segment ->
            URI(null, null, "/$segment", null).toASCIIString().removePrefix("/")
        }
        return "${baseUrl(base)}/$encoded"
    }

    fun condition(value: String?): Map<String, String> = when {
        value == null -> emptyMap()
        value == "*" -> mapOf("If-None-Match" to "*")
        else -> mapOf("If-Match" to requireStrongEtag(value))
    }

    fun requireStrongEtag(value: String?): String {
        val tag = value?.trim().orEmpty()
        require(tag.length >= 2 && tag.startsWith('"') && tag.endsWith('"') &&
            !tag.startsWith("W/") && tag.none { it == '\r' || it == '\n' }) {
            "WebDAV 服务必须支持强 ETag 和条件写入，不能安全同步到此服务"
        }
        return tag
    }
}
