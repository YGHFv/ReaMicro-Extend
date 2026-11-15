package io.legado.app.platform

import android.annotation.SuppressLint
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.JavascriptInterface
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions
import java.io.ByteArrayInputStream
import java.util.WeakHashMap
import org.json.JSONObject
import kotlinx.coroutines.ensureActive

/** The original Legado JS interface and injection names on a host-owned WebView. */
object LegadoWebViewBindings {
    private val bindings = WeakHashMap<WebView, WebJsExtensions>()
    val injectionString: String get() = WebJsExtensions.getInjectionString

    @SuppressLint("JavascriptInterface")
    @Synchronized
    fun attach(web: WebView, request: LegadoPlatform.BrowserRequest) {
        if (!request.isRule) return
        detach(web)
        web.addJavascriptInterface(WebCacheManager, WebJsExtensions.nameCache)
        request.sourceKey?.let {
            val source = request.sourceSnapshot
                ?: throw IllegalStateException("未安装网页规则关联书源")
            val java = WebJsExtensions(source, web, request.coroutineContext, request.sessionRunner)
            bindings[web] = java
            web.addJavascriptInterface(SourceJsBridge(source, request), WebJsExtensions.nameSource)
            web.addJavascriptInterface(java, WebJsExtensions.nameJava)
        }
    }

    fun initializeResult(web: WebView, request: LegadoPlatform.BrowserRequest) {
        if (request.isRule && request.result != null) {
            web.evaluateJavascript(
                "window.result = ${JSONObject.quote(request.result)}",
                null,
            )
        }
    }

    fun intercept(requestUrl: String): WebResourceResponse? {
        if (requestUrl != WebJsExtensions.nameUrl) return null
        return WebResourceResponse(
            "text/javascript", "UTF-8",
            ByteArrayInputStream(WebJsExtensions.JS_INJECTION.toByteArray(Charsets.UTF_8)),
        )
    }

    @Synchronized
    fun detach(web: WebView) {
        bindings.remove(web)?.close()
        web.removeJavascriptInterface(WebJsExtensions.nameCache)
        web.removeJavascriptInterface(WebJsExtensions.nameSource)
        web.removeJavascriptInterface(WebJsExtensions.nameJava)
    }

    /** Only the methods annotated in upstream BaseSource are exposed. */
    private class SourceJsBridge(
        private val source: BaseSource,
        private val request: LegadoPlatform.BrowserRequest,
    ) {
        @Suppress("UNCHECKED_CAST")
        private fun <T> run(action: () -> T): T {
            request.coroutineContext.ensureActive()
            val operation: () -> Any? = {
                runScriptWithContext(request.coroutineContext) { action() }
            }
            return (if (request.sessionRunner == null) operation() else request.sessionRunner.invoke(operation)) as T
        }
        @JavascriptInterface fun login() { run { source.login() } }
        @JavascriptInterface fun getLoginHeader(): String? = run { source.getLoginHeader() }
        @JavascriptInterface fun getLoginInfo(): String? = run { source.getLoginInfo() }
        @JavascriptInterface fun putLoginInfo(info: String): Boolean = run { source.putLoginInfo(info) }
        @JavascriptInterface fun removeLoginInfo() { run { source.removeLoginInfo() } }
        @JavascriptInterface fun putVariable(variable: String?) { run { source.putVariable(variable) } }
        @JavascriptInterface fun getVariable(): String = run { source.getVariable() }
        @JavascriptInterface fun put(key: String, value: String): String = run { source.put(key, value) }
        @JavascriptInterface fun get(key: String): String = run { source.get(key) }
    }
}
