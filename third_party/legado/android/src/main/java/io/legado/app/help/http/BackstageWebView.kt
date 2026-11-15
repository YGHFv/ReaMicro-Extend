package io.legado.app.help.http

import io.legado.app.platform.LegadoPlatform
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Upstream BackstageWebView constructor/API mapped to the host's browser.
 * Waiting, cancellation and page ownership belong to the installed host adapter.
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val javaScript: String? = null,
    private val tag: String? = null,
    private val headerMap: Map<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false,
) {
    suspend fun getStrResponse(): StrResponse = withTimeout(timeout ?: 60_000L) {
        val browserResult = LegadoPlatform.adapter.browser(
            LegadoPlatform.BrowserRequest(
                url = url,
                html = html,
                javaScript = javaScript,
                headers = headerMap.orEmpty(),
                sourceKey = tag,
                sourceRegex = sourceRegex,
                overrideUrlRegex = overrideUrlRegex,
                delayTime = delayTime.coerceAtLeast(0),
                visible = false,
                refetchAfterSuccess = false,
                encode = encode,
                cacheFirst = cacheFirst,
                timeoutMs = timeout ?: 60_000L,
                result = result,
                isRule = isRule,
                coroutineContext = currentCoroutineContext(),
            ),
        )
        val response = Response.Builder()
            .request(Request.Builder().url(browserResult.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(browserResult.status)
            .message("Browser result")
            .body(browserResult.body.toResponseBody())
            .apply { browserResult.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()
        StrResponse(response, browserResult.body)
    }
}
