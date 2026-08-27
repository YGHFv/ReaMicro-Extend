package com.reamicro.fix.cloud.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContentLibraryModelsTest {
    @Test
    fun `域名归一化与服务端口径一致`() {
        // 协议、端口、www 前缀和大小写都要去掉，同一个站点的不同写法必须归一到同一个域名。
        assertEquals("example.com", normalizeSourceDomain("https://www.Example.com/search?q=1"))
        assertEquals("example.com", normalizeSourceDomain("example.com:8080"))
        assertEquals("example.com", normalizeSourceDomain(" https://example.com/ "))
        assertEquals("a.example.com", normalizeSourceDomain("http://a.example.com"))
    }

    @Test
    fun `纯标识不作为域名参与比对`() {
        // 关联源的 ID 形如 youshu，不含点；若当成域名会让同名的不同源被误判为同一个。
        assertEquals("", normalizeSourceDomain("youshu"))
        assertEquals("", normalizeSourceDomain(""))
        assertEquals("", normalizeSourceDomain("   "))
        assertEquals("", normalizeSourceDomain("有书 网"))
    }

    @Test
    fun `上传描述只带比对所需字段`() {
        val item = ApiLibraryItem(
            kind = ApiPackageKind.ONLINE_SOURCE,
            name = "示例书源",
            contentId = "online_abc",
            domains = setOf("example.com"),
            identities = setOf("online_abc", "https://example.com"),
            payloadName = "online_abc.json",
            payload = "{}".toByteArray(),
            localContentId = "online_abc",
        )
        val json = item.toDescriptorJson()
        assertEquals("online_source", json.getString("kind"))
        assertEquals("示例书源", json.getString("name"))
        assertEquals("online_abc", json.getString("contentId"))
        assertEquals(1, json.getJSONArray("domains").length())
        assertEquals(2, json.getJSONArray("identities").length())
        // 描述里不应包含内容本体，内容只在真正上传时单独 base64 编码。
        assertFalse(json.has("payload"))
    }

    @Test
    fun `上传许可解析服务器响应`() {
        val policy = ApiUploadPolicy.fromJson(
            JSONObject(
                """
                {"enabled":true,"allowed":false,"reason":"阅微账号 20250 不在上传白名单",
                 "hostAccountId":"20250","kinds":["online_source","association_source","theme"],"maxSize":8388608}
                """.trimIndent(),
            ),
        )
        assertTrue(policy.enabled)
        assertFalse(policy.allowed)
        assertEquals("20250", policy.hostAccountId)
        assertEquals(8_388_608L, policy.maxSize)
        assertEquals(
            setOf(ApiPackageKind.ONLINE_SOURCE, ApiPackageKind.ASSOCIATION_SOURCE, ApiPackageKind.THEME),
            policy.kinds,
        )
    }

    @Test
    fun `内容包摘要缺少标识时视为无效`() {
        assertNull(ApiPackageSummary.fromJson(JSONObject("""{"kind":"online_source"}""")))
        assertNull(ApiPackageSummary.fromJson(JSONObject("""{"packageId":"a","kind":"unknown_kind"}""")))
        val summary = ApiPackageSummary.fromJson(
            JSONObject("""{"kind":"online_source","packageId":"example-com","version":"1.2.0","aliases":["old-id",""]}"""),
        )
        // contentId 缺失时回退到 packageId，保证关联登记始终有稳定标识。
        assertEquals("example-com", summary?.contentId)
        assertEquals("1.2.0", summary?.version)
        assertEquals(setOf("old-id"), summary?.aliases)
    }

    @Test
    fun `比对结果按匹配状态解析`() {
        val matched = ApiLibraryMatch.fromJson(
            JSONObject("""{"kind":"online_source","name":"示例","contentId":"local_1","matched":true,"package":{"kind":"online_source","packageId":"example-com"}}"""),
        )
        assertTrue(matched!!.matched)
        assertEquals("example-com", matched.summary?.packageId)
        val unmatched = ApiLibraryMatch.fromJson(
            JSONObject("""{"kind":"association_source","name":"未匹配","contentId":"local_2","matched":false}"""),
        )
        assertFalse(unmatched!!.matched)
        assertNull(unmatched.summary)
    }

    @Test
    fun `同步汇总统计变更数量`() {
        val summary = ApiLibrarySyncSummary(total = 5, uploaded = 2, linked = 2, failed = 1, messages = listOf("失败原因"))
        assertEquals(4, summary.changed)
    }

    @Test
    fun `关联占位版本保证下次检查一定判定为有更新`() {
        // 关联只登记映射不下载内容，版本必须低于服务器任何正式版本。
        assertEquals("0.0.0", ApiPackageManager.LINKED_VERSION)
        assertTrue(isPackageUpdateAvailable("1.0.0", 0L, ApiPackageManager.LINKED_VERSION, 0L))
    }
}
