package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSourceScriptCompatTest {
    private val searchScript = """
        @js:
        var tab;
        if (!type) {
          tab = 3;
        }
        if (String(key).startsWith("m:") || String(key).startsWith("m：")) {
          tab = 8
          key = key.slice(2)
        }
        if (key.length == 19 && !Number.isNaN(parseInt(key))) {
          result = source.key + '/detail?bookid=' + key
        } else {
          result = source.key + 'api_v2.php?api=search&key=' + encodeURI(java.put("key", key)) + '&tab_type=' + tab+'&api_key='+source.getLoginInfoMap().get('密钥')+'&offset='+((page-1)*10)
        }
    """.trimIndent()

    @Test
    fun singleKeySearchScriptBuildsAuthenticatedUrl() {
        val url = OnlineSourceScriptCompat.resolveSearchUrl(
            sourceUrl = "http://103.52.152.82:7788/",
            rawScript = searchScript,
            rawQuery = "测试小说",
            page = 2,
            loginInfo = mapOf("密钥" to "abc123"),
        )

        assertEquals(
            "http://103.52.152.82:7788/api_v2.php?api=search&key=%E6%B5%8B%E8%AF%95%E5%B0%8F%E8%AF%B4&tab_type=3&api_key=abc123&offset=10",
            url,
        )
    }

    @Test
    fun searchPrefixSelectsComicTabAndRemovesPrefix() {
        val url = OnlineSourceScriptCompat.resolveSearchUrl(
            sourceUrl = "http://103.52.152.82:7788/",
            rawScript = searchScript,
            rawQuery = "m:漫画",
            page = 1,
            loginInfo = mapOf("密钥" to "abc123"),
        )

        assertEquals(
            "http://103.52.152.82:7788/api_v2.php?api=search&key=%E6%BC%AB%E7%94%BB&tab_type=8&api_key=abc123&offset=0",
            url,
        )
    }

    @Test
    fun nineteenDigitIdUsesDetailBranch() {
        val url = OnlineSourceScriptCompat.resolveSearchUrl(
            sourceUrl = "http://103.52.152.82:7788/",
            rawScript = searchScript,
            rawQuery = "1234567890123456789",
            page = 1,
            loginInfo = mapOf("密钥" to "abc123"),
        )

        assertEquals("http://103.52.152.82:7788/detail?bookid=1234567890123456789", url)
    }

    @Test
    fun contentScriptBuildsSingleKeyProxyTemplate() {
        val script = """
            var id = java.hexDecodeToString(result);
            var _contentType = java.get('fq_content_type') || 'full';
            var _bid = '';
            let url3 = source.key + "api_v2.php?api=content&item_ids=" + id + "&api_type=" + _contentType + "&book_id=" + _bid + "&api_key=" + source.getLoginInfoMap().get('密钥');
            var _resp = java.ajax(url3);
        """.trimIndent()

        assertEquals(
            "http://103.52.152.82:7788/api_v2.php?api=content&item_ids=\${itemId}&api_type=full&book_id=&api_key=abc123",
            OnlineSourceScriptCompat.resolveContentRequestTemplate(
                sourceUrl = "http://103.52.152.82:7788/",
                rawScript = script,
                loginInfo = mapOf("密钥" to "abc123"),
            ),
        )
    }
}
