package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.settings.ModuleSettings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModuleConfigSyncTest {
    @Test fun sameAccountAcrossDevicesDecryptsAndRandomizesCiphertext() {
        val body = """{"密码":"secret-cookie-api-key"}""".toByteArray(Charsets.UTF_8)
        val a = ModuleConfigCrypto.encrypt(123, body)
        val b = ModuleConfigCrypto.encrypt(123, body)
        assertFalse(a.contentEquals(b))
        assertFalse(a.toString(Charsets.UTF_8).contains("secret-cookie-api-key"))
        assertArrayEquals(body, ModuleConfigCrypto.decrypt(123, a))
        assertThrows(IllegalArgumentException::class.java) { ModuleConfigCrypto.decrypt(456, a) }
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { ModuleConfigCrypto.decrypt(123, a) }
        assertThrows(IllegalArgumentException::class.java) { ModuleConfigCrypto.encrypt(0, body) }
    }
    @Test fun scopePolicySeparatesCredentialsAndExcludesSyncAndRuntimeState() {
        fun scope(key: String) = ModuleConfigPolicy.preferenceScope(ModuleSettings.PREFS_NAME, key)
        assertEquals(ModuleConfigScope.HIGHLIGHT_STYLES, scope(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES))
        assertEquals(ModuleConfigScope.HIGHLIGHT_SETTINGS, scope(ModuleSettings.KEY_READER_HIGHLIGHT_RULES))
        for (key in listOf("online_login_password_id", "online_cookie_domain_example", "online_source_variable_id"))
            assertEquals(ModuleConfigScope.SOURCE_CREDENTIALS, scope(key))
        assertEquals(ModuleConfigScope.SOURCES, scope("online_download_on_demand_id"))
        for (key in listOf("sync_book_limit", "online_download_daily_used_id", "module_enabled",
                "cloud_github_sync_enabled", "online_enhanced_parser_unlocked")) assertNull(scope(key))
        assertEquals(ModuleConfigScope.API, ModuleConfigPolicy.fileScope("reamicro_ai_apis.json"))
        assertEquals(ModuleConfigScope.SOURCES, ModuleConfigPolicy.fileScope("reamicro_online_sources/a.rmonline"))
        for (path in listOf("../token", "reamicro_online_sources/../../token", "/token", "C:/token", "reamicro_online_sources/.cache/a.json"))
            assertNull(ModuleConfigPolicy.fileScope(path))
    }
    @Test fun archivePreservesTypesUnicodeAndStableBytesAndRejectsUnsafeNames() {
        val values: List<Any> = listOf(true, 7, 9876543210L, 1.25f, "中文 Cookie", setOf("甲", "乙"))
        values.forEach { value -> assertEquals(value, ModuleConfigArchive.decodePreference(ModuleConfigArchive.encodePreference(value))) }
        val json = JSONObject().put("version", 1).put("secret", "密码")
        val files = mapOf("files/reamicro_online_sources/a.json" to "{}".toByteArray())
        val bytes = ModuleConfigArchive.create(json, files)
        assertArrayEquals(bytes, ModuleConfigArchive.create(json, files))
        assertArrayEquals(files.values.single(), ModuleConfigArchive.read(bytes)["files/reamicro_online_sources/a.json"])
        assertThrows(IllegalArgumentException::class.java) { ModuleConfigArchive.create(json, mapOf("../escape" to byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { ModuleConfigArchive.read(byteArrayOf(1, 2)) }
    }
    private class Store : ShelfSyncTransport {
        val data = linkedMapOf<String, ByteArray>()
        var rejectPointer = false
        val reads = arrayListOf<String>()
        override fun get(key: String): WebDavHttpResponse? {
            reads += key
            return data[key]?.let { WebDavHttpResponse(200, null, mapOf("ETag" to it.contentHashCode().toString()), it) }
        }
        override fun head(key: String) = get(key)
        override fun put(key: String, bytes: ByteArray?, file: File?, mime: String, condition: String?) {
            if (key.endsWith("latest.json") && rejectPointer) error("412")
            if (condition == "*" && key in data || condition != null && condition != "*" && condition != data[key]?.contentHashCode()?.toString())
                error("412")
            data[key] = bytes ?: requireNotNull(file).readBytes()
        }
        override fun delete(key: String) { error("不需要删除") }
        override fun download(key: String, output: File) { error("不需要自动下载") }
        override fun verify() {}
    }
    @Test fun remoteBackupIsAccountScopedAndDownloadIsExplicit() {
        val store = Store()
        val client = ModuleConfigRemote(store, "reamicro", 123)
        val plain = "secret-password-cookie-api-key".toByteArray()
        assertTrue(client.upload(plain))
        assertFalse(store.reads.any { it.endsWith(".bin") })
        assertFalse(client.upload(plain))
        assertEquals(2, store.data.size)
        assertArrayEquals(plain, client.download())
        assertThrows(IllegalStateException::class.java) { ModuleConfigRemote(store, "reamicro", 456).download() }
        store.rejectPointer = true
        assertThrows(IllegalStateException::class.java) { client.upload("new-config".toByteArray()) }
        assertArrayEquals(plain, client.download())
    }
}
