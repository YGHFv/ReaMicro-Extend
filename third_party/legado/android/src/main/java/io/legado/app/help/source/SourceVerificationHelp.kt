package io.legado.app.help.source

import com.script.rhino.rhinoContextOrNull
import io.legado.app.data.entities.BaseSource
import io.legado.app.platform.LegadoPlatform
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isMainThread
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

/** UI boundary of upstream SourceVerificationHelp; result/cancel comes from host. */
object SourceVerificationHelp {
    fun startBrowser(source: BaseSource?, url: String, title: String, html: String? = null) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        runBlocking(rhinoContextOrNull?.coroutineContext ?: EmptyCoroutineContext) {
            LegadoPlatform.adapter.browser(
                LegadoPlatform.BrowserRequest(
                    url = url, title = title, html = html,
                    sourceKey = source.getKey(),
                    headers = source.getHeaderMap(LegadoPlatform.userAgent, true),
                    awaitResult = false,
                ),
            )
        }
    }

    @JvmOverloads
    fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        isBrowser: Boolean,
        refetchAfterSuccess: Boolean = true,
        html: String? = null,
    ): Pair<String, String> {
        source ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        check(!isMainThread) { "getVerificationResult must be called on a background thread" }
        return runBlocking(rhinoContextOrNull?.coroutineContext ?: EmptyCoroutineContext) {
            val response = if (!isBrowser) {
                url to LegadoPlatform.adapter.verificationCode(source.getKey(), url)
            } else {
            val headers = source.getHeaderMap(LegadoPlatform.userAgent, true)
            val result = LegadoPlatform.adapter.browser(
                LegadoPlatform.BrowserRequest(
                    url = url, title = title, html = html,
                    sourceKey = source.getKey(),
                    headers = headers,
                    refetchAfterSuccess = refetchAfterSuccess,
                ),
            )
            if (refetchAfterSuccess) {
                // Same original URL and header map as WebViewModel.saveVerificationResult.
                val fetched = AnalyzeUrl(
                    url, headerMapF = headers, source = source, coroutineContext = coroutineContext
                ).getStrResponseAwait(useWebView = false)
                result.url to fetched.body.orEmpty()
            } else result.url to result.body
            }
            if (response.second.isEmpty()) throw NoStackTraceException("验证结果为空")
            response
        }
    }
}
