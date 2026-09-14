package com.reamicro.fix.cloud.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 服务器配置的镜像载荷编解码。
 *
 * 这段是宿主进程与模块进程之间唯一的配置通道：模块被闹钟唤醒后要靠它拿 baseUrl 和凭据去
 * 服务器拉消息并回执。字段漏一个、枚举值写错，模块侧就会静默地什么都不做（此前 provider 通道
 * 报 `Can't resolve content provider` 时就是这种表现），所以逐字段锁住往返一致。
 */
class ApiServerSettingsMirrorCodecTest {

    private val full = ApiServerSettings(
        enabled = true,
        baseUrl = "https://api.example.com",
        authMode = ApiAuthMode.API_KEY,
        apiKey = "sk-测试-key",
        accountName = "someone",
        accountPassword = "p@ssword",
        hostAccountId = "acct_123",
        allowHttp = true,
        timeoutSeconds = 20,
        autoCheckUpdates = false,
        updateChannel = ApiUpdateChannel.STABLE,
    )

    @Test
    fun `往返之后逐字段一致`() {
        assertEquals(full, apiServerSettingsFromMirrorJson(full.toMirrorJson()))
    }

    @Test
    fun `凭据以明文出现在载荷里`() {
        // 模块进程只能用自己 UID 的 Keystore 重新加密，收到密文没有意义——这条是刻意的设计，
        // 用测试固定下来，避免以后有人"顺手"把它加密了导致模块侧静默失效。
        val json = full.toMirrorJson()
        assertTrue(json.contains("sk-测试-key"))
        assertTrue(json.contains("p@ssword"))
    }

    @Test
    fun `账号密码认证模式也能往返`() {
        val settings = full.copy(authMode = ApiAuthMode.ACCOUNT, apiKey = "")
        assertEquals(settings, apiServerSettingsFromMirrorJson(settings.toMirrorJson()))
    }

    @Test
    fun `缺字段时走默认值而不是抛异常`() {
        // 新旧版本并存时载荷可能缺字段，缺一个不该让整次同步失败。
        val settings = apiServerSettingsFromMirrorJson("""{"enabled":true,"baseUrl":"http://x"}""")
        assertTrue(settings.enabled)
        assertEquals("http://x", settings.baseUrl)
        assertEquals(ApiAuthMode.PUBLIC, settings.authMode)
        assertEquals(ApiUpdateChannel.BETA, settings.updateChannel)
        assertEquals(8, settings.timeoutSeconds)
    }

    @Test
    fun `非法枚举值退回默认`() {
        val settings = apiServerSettingsFromMirrorJson(
            """{"authMode":"totally-made-up","updateChannel":"nightly"}""",
        )
        assertEquals(ApiAuthMode.PUBLIC, settings.authMode)
        assertEquals(ApiUpdateChannel.BETA, settings.updateChannel)
    }

    @Test
    fun `超范围的超时被夹到合法区间`() {
        // 服务器端配置页限制了 5..60，镜像进来的值同样要夹，否则会构造出非法请求。
        assertEquals(60, apiServerSettingsFromMirrorJson("""{"timeoutSeconds":99999}""").timeoutSeconds)
        assertEquals(5, apiServerSettingsFromMirrorJson("""{"timeoutSeconds":1}""").timeoutSeconds)
    }
}
