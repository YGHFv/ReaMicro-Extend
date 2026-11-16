package com.reamicro.fix.cloud.webdav

import org.json.JSONObject

/** 配置使用独立账号目录和条件写；普通书架读取从不下载或应用模块配置。 */
internal class ModuleConfigRemote(private val remote: ShelfSyncTransport, prefix: String, private val id: Long) {
    private val root = "${prefix.trim('/')}/.reamicro-module-config/${ModuleConfigCrypto.accountKey(id)}"
    private val latest = "$root/latest.json"
    fun upload(plain: ByteArray): Boolean {
        val hash = ModuleConfigCrypto.digest(plain)
        val current = remote.get(latest)
        if (current != null && JSONObject(requireNotNull(current.bodyBytes).toString(Charsets.UTF_8))
                .optString("digest") == hash) return false
        val encrypted = ModuleConfigCrypto.encrypt(id, plain)
        val objectId = ModuleConfigCrypto.digest(encrypted)
        remote.put("$root/$objectId.bin", encrypted, null, "application/octet-stream", "*")
        val pointer = JSONObject().put("version", 1).put("object", objectId).put("digest", hash)
            .put("updatedAt", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        remote.put(latest, pointer, null, "application/json",
            if (current == null) "*" else current.headers["ETag"] ?: error("备份缺少版本信息"))
        return true
    }
    fun download(): ByteArray {
        val pointer = remote.get(latest)?.bodyBytes ?: error("当前阅微账号还没有模块配置备份")
        val json = JSONObject(pointer.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val objectId = json.getString("object")
        require(objectId.matches(Regex("[a-f0-9]{64}"))) { "模块配置备份索引无效" }
        val encrypted = remote.get("$root/$objectId.bin")?.bodyBytes ?: error("模块配置备份内容缺失")
        require(ModuleConfigCrypto.digest(encrypted) == objectId) { "模块配置备份校验失败" }
        val plain = ModuleConfigCrypto.decrypt(id, encrypted)
        require(ModuleConfigCrypto.digest(plain) == json.getString("digest")) { "模块配置备份校验失败" }
        return plain
    }
}
