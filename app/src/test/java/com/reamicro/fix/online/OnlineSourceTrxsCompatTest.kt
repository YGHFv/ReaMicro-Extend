package com.reamicro.fix.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSourceTrxsCompatTest {
    private val source = OnlineSourceEntry(
        id = "trxs",
        name = "起点限免（同人小说网）",
        fileName = "trxs.json",
        sourceUrl = "https://m.qidian.com#同人小说网",
        loginUrl = "function _login(){java.startBrowser(`${'$'}{api}/console/login?token=${'$'}{token}`)}",
        loginUi = "@js: result = JSON.stringify([{name:'邀请码',type:'text'}])",
        loginCheckJs = "",
        concurrentRate = "1200",
        header = "{}",
        enabledCookieJar = false,
        searchUrl = "@js:getApiUrl(`/${'$'}{type}/search`, {keyword, s, page})",
        ruleSearch = "{}",
        ruleBookInfo = "{}",
        ruleToc = "{}",
        ruleContent = "{}",
        respondTime = 180_000,
        origin = "",
        jsLib = "const api = 'https://www.trxs666.com'",
        variableComment = "于此处填写共享Token",
    )

    @Test
    fun recognizesTargetSourceAndApiBase() {
        assertTrue(OnlineSourceTrxsCompat.isSource(source))
        assertEquals("https://www.trxs666.com", OnlineSourceTrxsCompat.apiBase(source))
        assertFalse(OnlineSourceTrxsCompat.isSource(source.copy(sourceUrl = "https://example.com")))
        assertTrue(OnlineSourceTrxsCompat.shouldAuthorize(source, "https://www.trxs666.com/novel/search"))
        assertFalse(OnlineSourceTrxsCompat.shouldAuthorize(source, "https://qidian.qpic.cn/cover.jpg"))
    }

    @Test
    fun buildsSearchUrlsForAllSupportedPrefixes() {
        assertEquals(
            "https://www.trxs666.com/novel/search?keyword=%E6%8D%9E%E5%B0%B8%E4%BA%BA&s=1&page=2",
            OnlineSourceTrxsCompat.searchUrl(source, "捞尸人", 2),
        )
        assertEquals(
            "https://www.trxs666.com/audio/search?keyword=%E6%B5%8B%E8%AF%95&page=1",
            OnlineSourceTrxsCompat.searchUrl(source, "a:测试", 1),
        )
        assertEquals(
            "https://www.trxs666.com/comic/search?keyword=%E6%B5%8B%E8%AF%95&page=1",
            OnlineSourceTrxsCompat.searchUrl(source, "c：测试", 1),
        )
    }

    @Test
    fun buildsDetailAndCatalogUrlsFromApiData() {
        val node = JSONObject("""{"id":"123","type":"novel"}""")
        assertEquals(
            "https://www.trxs666.com/novel/info?novelId=123",
            OnlineSourceTrxsCompat.detailUrl(source, node),
        )
        assertEquals(
            "https://www.trxs666.com/novel/catalog?novelId=123",
            OnlineSourceTrxsCompat.catalogUrl(
                source,
                "https://www.trxs666.com/novel/info?novelId=123",
                """{"msg":"success","data":{"id":"123","type":"novel"}}""",
            ),
        )
    }

    @Test
    fun convertsNovelCatalogToDirectChapterApiUrls() {
        val body = """
            {
              "msg":"success",
              "data":[
                {
                  "volumeName":"第一卷",
                  "chapters":[
                    {"chapId":"1001","chapName":"第一章"},
                    {"chapId":"1002","chapName":"第二章"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val chapters = OnlineSourceTrxsCompat.catalogChapters(
            source,
            "https://www.trxs666.com/novel/catalog?novelId=123",
            body,
        ).orEmpty()

        assertEquals(3, chapters.size)
        assertTrue(chapters[0].isVolume)
        assertEquals("第一卷", chapters[1].volumeTitle)
        assertEquals(
            "https://www.trxs666.com/novel/chap?novelId=123&chapId=1001",
            chapters[1].url,
        )
    }

    @Test
    fun extractsInviteTokenAndBuildsLoginUrls() {
        val response = """{"msg":"success","data":{"id":7,"token":"token-123","left":20}}"""
        assertEquals("token-123", OnlineSourceTrxsCompat.tokenFromInviteResponse(response))
        assertEquals(
            "https://www.trxs666.com/user/invite?id=android-id&code=invite%20code",
            OnlineSourceTrxsCompat.inviteUrl(source, "android-id", "invite code"),
        )
        assertEquals(
            "https://www.trxs666.com/console/login?token=token-123",
            OnlineSourceTrxsCompat.accountManagementUrl(source, "token-123"),
        )
    }
}
