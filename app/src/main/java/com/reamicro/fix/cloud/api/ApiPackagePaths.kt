package com.reamicro.fix.cloud.api

import com.reamicro.fix.association.network.HttpRequestPolicy
import java.io.File
import java.security.MessageDigest

internal object ApiPackagePaths {
    fun component(value: String): String {
        if (value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}"))) return value
        return "item-" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun child(directory: File, name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "内容文件名无效" }
        val root = directory.canonicalFile
        return File(root, name).canonicalFile.also {
            require(it.parentFile == root) { "内容文件超出存储目录" }
        }
    }

    fun cacheStem(contentPackage: ApiContentPackage): String =
        "${component(contentPackage.version)}-${contentPackage.buildTime}-${component(contentPackage.sha256)}"
}

internal fun apiDownloadUrl(baseUrl: String, path: String): String {
    val target = if (path.startsWith("/") && !path.startsWith("//")) baseUrl + path else path
    require(HttpRequestPolicy.sameOrigin(baseUrl, target)) { "下载地址与 API 服务器不同源" }
    return target
}

internal fun packageSigningKey(probe: ApiServerProbeResult): String {
    require(probe.success) { "无法确认内容包签名要求：${probe.message}" }
    return requireNotNull(probe.meta) { "服务器没有返回内容包验证信息" }.signingPublicKey
}
