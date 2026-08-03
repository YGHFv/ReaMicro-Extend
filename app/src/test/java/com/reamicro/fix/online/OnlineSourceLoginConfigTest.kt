package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSourceLoginConfigTest {
    @Test
    fun `builds api key header from saved login field`() {
        assertEquals(
            mapOf("X-API-Key" to "fq_test_key"),
            OnlineSourceAuth.credentialHeaders(
                rawHeader = "<js>JSON.stringify({'X-API-Key': apiKey})</js>",
                loginInfo = mapOf("密钥" to " fq_test_key "),
            ),
        )
    }

    @Test
    fun `builds api key header when login field is named X-Key`() {
        // 番茄 api.yuezhi.me 源 loginUi 字段名为 "X-Key"，此前 credentialHeaders 只认 密钥/apiKey 等，
        // 取不到值 → 请求缺 X-API-Key → 持续 401“未填写密钥”。
        assertEquals(
            mapOf("X-API-Key" to "sq_abc123"),
            OnlineSourceAuth.credentialHeaders(
                rawHeader = "@js:JSON.stringify({\"X-API-Key\": source.get(\"fq_x_key\")})",
                loginInfo = mapOf("X-Key" to " sq_abc123 "),
            ),
        )
    }

    @Test
    fun `resolves api key referenced by getLoginInfoMap in header`() {
        assertEquals(
            "token-42",
            OnlineSourceAuth.resolveApiKey(
                rawHeader = "@js:var k=source.getLoginInfoMap().get('授权令牌');JSON.stringify({'X-API-Key':k})",
                loginInfo = mapOf("授权令牌" to "token-42", "主题色" to "红"),
            ),
        )
    }

    @Test
    fun `falls back to the single saved credential value`() {
        assertEquals(
            "only-secret",
            OnlineSourceAuth.resolveApiKey(
                rawHeader = "X-API-Key: whatever",
                loginInfo = mapOf("凭证" to "only-secret"),
            ),
        )
    }

    @Test
    fun loginUiKeepsSingleSecretAndIgnoresUnrelatedStyleFields() {
        val fields = OnlineSourceLoginConfig.credentialFields(
            """
            [
              {"name":"密钥","type":"password"},
              {"name":"登录/注册","type":"button","action":"openLoginPage()"},
              {"name":"段评图标颜色","type":"text"},
              {"name":"段评气泡大小","type":"select","chars":["大号","小号"]}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("密钥"), fields.map { it.name })
        assertTrue(fields.single().isSecret)
    }

    @Test
    fun textApiKeyIsAlsoRecognizedAsSingleCredential() {
        val fields = OnlineSourceLoginConfig.credentialFields(
            """[{"name":"apiKey","type":"text"},{"name":"主题颜色","type":"text"}]""",
        )

        assertEquals(listOf("apiKey"), fields.map { it.name })
    }

    @Test
    fun scriptReferencedEndpointFieldIsKeptAlongsideApiKey() {
        val fields = OnlineSourceLoginConfig.credentialFields(
            raw = """
                [
                  {"name":"正文接口","type":"text"},
                  {"name":"API Key","type":"password"},
                  {"name":"主题颜色","type":"text"}
                ]
            """.trimIndent(),
            referencedScripts = """
                var api = shuqiValue(info, '正文接口');
                var key = shuqiValue(info, 'API Key');
                source.putLoginInfo(JSON.stringify({'正文接口': api, 'API Key': key}));
            """.trimIndent(),
        )

        assertEquals(listOf("正文接口", "API Key"), fields.map { it.name })
    }

    @Test
    fun fieldsOnlyWrittenToLoginInfoAreNotTreatedAsCredentials() {
        val fields = OnlineSourceLoginConfig.credentialFields(
            raw = """[{"name":"密钥","type":"password"},{"name":"主题颜色","type":"text"}]""",
            referencedScripts = "source.putLoginInfo(JSON.stringify({'密钥': key, '主题颜色': color}));",
        )

        assertEquals(listOf("密钥"), fields.map { it.name })
    }

    @Test
    fun loginScriptExposesRegistrationUrl() {
        val script = """
            function openLoginPage() {
                java.startBrowser("http://103.52.152.82:7788/dashboard.php", "登录/注册");
            }
        """.trimIndent()

        assertEquals(
            "http://103.52.152.82:7788/dashboard.php",
            OnlineSourceLoginConfig.browserUrl(script),
        )
    }

    @Test
    fun loginInfoRoundTripsAndResolvesLegadoExpressions() {
        val encoded = OnlineSourceLoginConfig.encode(mapOf("密钥" to "secret-value"))
        val values = OnlineSourceLoginConfig.decode(encoded)

        assertEquals("secret-value", values["密钥"])
        assertEquals(
            "secret-value",
            OnlineSourceLoginConfig.expressionValue(
                "source.getLoginInfoMap().get('密钥')",
                "http://example.com/",
                values,
            ),
        )
        assertEquals(
            "http://example.com/",
            OnlineSourceLoginConfig.expressionValue("source.key", "http://example.com/", values),
        )
    }

    @Test
    fun accountPasswordEndpointTakesPriorityOverGenericLoginFields() {
        val source = onlineSourceForLoginTest(
            loginUi = """[{"name":"用户名","type":"text"},{"name":"密码","type":"password"}]""",
            loginUrl = """function login(){java.post(BASE+'/reader-auth/login','{}',{});}""",
        )

        assertTrue(OnlineSourceAuth.usesAccountPasswordLogin(source))
        assertEquals(listOf("用户名", "密码"), OnlineSourceAuth.loginFields(source).map { it.name })
    }

    @Test
    fun recoversCredentialsSavedByGenericFieldRegression() {
        val fields = OnlineSourceLoginConfig.credentialFields(
            """[{"name":"用户名","type":"text"},{"name":"密码","type":"password"}]""",
        )

        assertEquals(
            "alice" to "secret",
            OnlineSourceAuth.accountPasswordFromLoginInfo(
                mapOf("用户名" to " alice ", "密码" to "secret"),
                fields,
            ),
        )
    }

    private fun onlineSourceForLoginTest(loginUi: String, loginUrl: String): OnlineSourceEntry =
        OnlineSourceEntry(
            id = "login-test",
            name = "登录测试",
            fileName = "login-test.json",
            sourceUrl = "https://example.com",
            loginUrl = loginUrl,
            loginUi = loginUi,
            loginCheckJs = "",
            concurrentRate = "",
            header = "",
            enabledCookieJar = true,
            searchUrl = "",
            ruleSearch = "",
            ruleBookInfo = "",
            ruleToc = "",
            ruleContent = "",
            respondTime = 60_000,
            origin = "",
        )
}
