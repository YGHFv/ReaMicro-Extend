package com.reamicro.fix.association.provider

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.reamicro.fix.association.network.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

class YouShuLoginExpiredException : RuntimeException("YouShu login expired")

object YouShuLoginState {
    const val BASE_URL = "https://www.youshu.me"
    const val MOBILE_BASE_URL = "https://m.youshu.me"

    fun canSearchWithCookie(
        cookieHeader: String = YouShuLoginCookies.cookieHeader(),
        htmlFetcher: (String, Map<String, String>, Int, Int) -> String = { url, headers, connectTimeoutMs, readTimeoutMs ->
            HttpClient.get(
                url = url,
                headers = headers,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
        },
    ): Boolean {
        if (!YouShuLoginCookies.hasLoginCookie(cookieHeader)) return false
        return LOGIN_CHECK_URLS.any { url ->
            val html = runCatching {
                htmlFetcher(
                    url.replace("{key}", HttpClient.encode(LOGIN_CHECK_KEYWORD)),
                    mapOf(
                        "Referer" to BASE_URL,
                        "Cookie" to cookieHeader,
                    ),
                    CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS,
                )
            }.getOrNull() ?: return@any false
            isSearchUsableHtml(html)
        }
    }

    fun canSearchWithWebView(
        htmlFetcher: (String, Long) -> String? = YouShuWebSearchBridge::fetchHtml,
    ): Boolean = LOGIN_CHECK_URLS.any { url ->
        val html = htmlFetcher(
            url.replace("{key}", HttpClient.encode(LOGIN_CHECK_KEYWORD)),
            WEBVIEW_TIMEOUT_MS,
        ) ?: return@any false
        isSearchUsableHtml(html)
    }

    fun isLoginExpiredHtml(html: String): Boolean {
        val lower = html.lowercase()
        val hasStandaloneLoginForm = html.contains("\u7528\u6237\u767b\u5f55") &&
            (lower.contains("name=\"frmlogin\"") || lower.contains("name='frmlogin'"))
        val hasSearchJumpLogin = lower.contains("login.php") &&
            lower.contains("jumpurl=") &&
            (lower.contains("search.php") || lower.contains("/search/all/"))
        val hasTopLoginBox = (lower.contains("id=\"t_frmlogin\"") ||
            lower.contains("id='t_frmlogin'") ||
            lower.contains("id=\"fixed-login-box\"") ||
            lower.contains("id='fixed-login-box'")) &&
            !html.contains("\u9000\u51fa") &&
            !lower.contains("logout")
        val hasPasswordLoginForm = (lower.contains("type=\"password\"") ||
            lower.contains("type='password'") ||
            lower.contains("name=\"password\"") ||
            lower.contains("name='password'")) &&
            (lower.contains("login.php") || html.contains("\u767b\u5f55") || html.contains("\u7528\u6237\u767b\u5f55"))
        return hasStandaloneLoginForm ||
            hasSearchJumpLogin ||
            hasTopLoginBox ||
            hasPasswordLoginForm
    }

    private fun isSearchUsableHtml(html: String): Boolean =
        html.length > MIN_VALID_RESPONSE_LENGTH &&
            !isLoginExpiredHtml(html) &&
            !html.contains("Just a moment", ignoreCase = true) &&
            !html.contains("Cloudflare", ignoreCase = true) &&
            hasBookResultLink(html)

    private fun hasBookResultLink(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("/book/") ||
            lower.contains("articleinfo.php") ||
            lower.contains("bookinfo.php")
    }

    private const val LOGIN_CHECK_KEYWORD = "\u51e1\u4eba\u4fee\u4ed9\u4f20"
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_500
    private const val WEBVIEW_TIMEOUT_MS = 7_000L
    private const val MIN_VALID_RESPONSE_LENGTH = 500
    private val LOGIN_CHECK_URLS = listOf(
        "$MOBILE_BASE_URL/search/all/{key}/1.html",
        "$MOBILE_BASE_URL/modules/article/search.php?searchtype=all&searchkey={key}&page=1",
        "$BASE_URL/search/all/{key}/1.html",
        "$BASE_URL/modules/article/search.php?searchtype=all&searchkey={key}&page=1",
    )
}

object YouShuWebSearchBridge {
    @Volatile private var activityProvider: (() -> Activity?)? = null

    fun attach(provider: () -> Activity?) {
        activityProvider = provider
    }

    fun fetchHtml(url: String, timeoutMs: Long = WEBVIEW_TIMEOUT_MS): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val activity = activityProvider?.invoke() ?: return null
        val latch = CountDownLatch(1)
        val htmlRef = AtomicReference<String?>()
        val completed = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>()
        val pageFinishedVersion = AtomicInteger(0)
        val handler = Handler(Looper.getMainLooper())

        fun finish(webView: WebView?, html: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            htmlRef.set(html)
            runCatching { CookieManager.getInstance().flush() }
            runCatching { webView?.stopLoading() }
            runCatching { (webView?.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView?.destroy() }
            latch.countDown()
        }

        handler.post {
            runCatching {
                if (activity.isFinishing || activity.isDestroyed) {
                    finish(null)
                    return@post
                }
                val webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    if (url.shouldUseDesktopUserAgent()) settings.userAgentString = DESKTOP_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    visibility = View.INVISIBLE
                    alpha = 0f
                }
                webViewRef.set(webView)
                (activity.window?.decorView as? ViewGroup)?.addView(
                    webView,
                    FrameLayout.LayoutParams(1, 1).apply {
                        leftMargin = 0
                        topMargin = 0
                    },
                )
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        val currentView = view ?: webView
                        val version = pageFinishedVersion.incrementAndGet()
                        handler.postDelayed({
                            if (version != pageFinishedVersion.get()) return@postDelayed
                            if (completed.get()) return@postDelayed
                            currentView.evaluateJavascript(OUTER_HTML_JS) { value ->
                                val html = value.decodeJavascriptString()
                                finish(currentView, html)
                            }
                        }, PAGE_SETTLE_DELAY_MS)
                    }
                }
                webView.loadUrl(url)
            }.onFailure {
                finish(webViewRef.get())
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            handler.post { finish(webViewRef.get()) }
        }
        return htmlRef.get()
    }

    private fun String?.decodeJavascriptString(): String? {
        if (this == null || this == "null") return null
        return runCatching { JSONArray("[$this]").optString(0) }.getOrNull()
    }

    private const val WEBVIEW_TIMEOUT_MS = 7_000L
    private const val PAGE_SETTLE_DELAY_MS = 800L
    private const val OUTER_HTML_JS = "(function(){return document.documentElement.outerHTML;})();"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private fun String.shouldUseDesktopUserAgent(): Boolean =
        startsWith("https://www.youshu.me/book/", ignoreCase = true) ||
            startsWith("http://www.youshu.me/book/", ignoreCase = true) ||
            startsWith("https://m.youshu.me/book/", ignoreCase = true) ||
            startsWith("http://m.youshu.me/book/", ignoreCase = true)
}

object YouShuLoginCookies {
    const val BASE_URL = "https://www.youshu.me"
    const val MOBILE_BASE_URL = "https://m.youshu.me"

    fun cookieHeader(): String = runCatching {
        val cookieManagerClass = Class.forName("android.webkit.CookieManager")
        val instance = cookieManagerClass.getMethod("getInstance").invoke(null)
        COOKIE_URLS.mapNotNull { url ->
            cookieManagerClass.getMethod("getCookie", String::class.java)
                .invoke(instance, url) as? String
        }.mergeCookieHeaders()
    }.getOrNull().orEmpty()

    fun cookieNames(cookieHeader: String = cookieHeader()): String =
        cookieHeader.split(';')
            .map { it.trim().substringBefore('=') }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

    fun hasLoginCookie(cookieHeader: String = cookieHeader()): Boolean =
        cookieHeader.split(';')
            .map { it.trim().substringBefore('=').trim() }
            .any { it.equals("jieqiUserInfo", ignoreCase = true) }

    private fun List<String>.mergeCookieHeaders(): String {
        val cookies = linkedMapOf<String, String>()
        flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { cookie ->
                val name = cookie.substringBefore('=').trim()
                if (name.isNotBlank()) cookies[name] = cookie
            }
        return cookies.values.joinToString("; ")
    }

    private val COOKIE_URLS = listOf(
        BASE_URL,
        "$BASE_URL/",
        "$BASE_URL/login.php",
        "$BASE_URL/search/all/test/1.html",
        MOBILE_BASE_URL,
        "$MOBILE_BASE_URL/",
        "$MOBILE_BASE_URL/login.php",
        "$MOBILE_BASE_URL/search/all/test/1.html",
        "http://www.youshu.me",
        "http://www.youshu.me/",
        "http://m.youshu.me",
        "http://m.youshu.me/",
    )
}
