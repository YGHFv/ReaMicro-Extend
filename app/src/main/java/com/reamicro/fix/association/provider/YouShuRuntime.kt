package com.reamicro.fix.association.provider

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.reamicro.fix.association.network.HttpResponseException
import com.reamicro.fix.association.network.HttpClient
import com.reamicro.fix.hook.AccountHostRuntime
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

class YouShuLoginExpiredException : RuntimeException("YouShu login expired")

object YouShuLoginState {
    const val BASE_URL = YouShuCookiePolicy.BASE_URL
    const val MOBILE_BASE_URL = YouShuCookiePolicy.MOBILE_BASE_URL

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
        // Retain the attachment ABI. The native HTTP layer rebinds this legacy header per URL.
        return checkWithCookie({ cookieHeader }, htmlFetcher) == YouShuLoginStatus.VALID
    }

    fun checkWithCookie(
        cookieProvider: (String) -> String = YouShuLoginCookies::cookieHeader,
        htmlFetcher: (String, Map<String, String>, Int, Int) -> String = { url, headers, connectTimeoutMs, readTimeoutMs ->
            HttpClient.get(url, headers, connectTimeoutMs, readTimeoutMs)
        },
    ): YouShuLoginStatus {
        val results = arrayListOf<YouShuLoginStatus>()
        for (template in LOGIN_CHECK_URLS) {
            TaskCancellation.checkCurrent()
            val url = template.replace("{key}", HttpClient.encode(LOGIN_CHECK_KEYWORD))
            val cookies = cookieProvider(url)
            if (!YouShuLoginCookies.hasLoginCookie(cookies)) continue
            val status = try {
                YouShuLoginPolicy.classify(
                    htmlFetcher(
                        url,
                        mapOf(
                            "Referer" to if (url.startsWith(MOBILE_BASE_URL)) MOBILE_BASE_URL else BASE_URL,
                            "Cookie" to cookies,
                        ),
                        CONNECT_TIMEOUT_MS,
                        READ_TIMEOUT_MS,
                    ),
                )
            } catch (error: Throwable) {
                TaskCancellation.rethrowIfCancelled(error)
                if (error is HttpResponseException) YouShuLoginPolicy.classify(error.responseBody)
                else YouShuLoginStatus.UNKNOWN
            }
            if (status == YouShuLoginStatus.VALID) return status
            results += status
        }
        return if (results.isEmpty()) YouShuLoginStatus.EXPIRED else YouShuLoginPolicy.combine(results)
    }

    fun canSearchWithWebView(
        htmlFetcher: (String, Long) -> String? = YouShuWebSearchBridge::fetchHtml,
    ): Boolean = checkWithWebView(htmlFetcher) == YouShuLoginStatus.VALID

    fun checkWithWebView(
        htmlFetcher: (String, Long) -> String? = YouShuWebSearchBridge::fetchHtml,
    ): YouShuLoginStatus {
        val results = arrayListOf<YouShuLoginStatus>()
        for (url in LOGIN_CHECK_URLS) {
            TaskCancellation.checkCurrent()
            val status = try {
                YouShuLoginPolicy.classify(htmlFetcher(
                    url.replace("{key}", HttpClient.encode(LOGIN_CHECK_KEYWORD)), WEBVIEW_TIMEOUT_MS))
            } catch (error: Throwable) {
                TaskCancellation.rethrowIfCancelled(error)
                YouShuLoginStatus.UNKNOWN
            }
            if (status == YouShuLoginStatus.VALID) return status
            results += status
        }
        return YouShuLoginPolicy.combine(results)
    }

    fun isLoginExpiredHtml(html: String): Boolean =
        YouShuLoginPolicy.classify(html) == YouShuLoginStatus.EXPIRED

    private const val LOGIN_CHECK_KEYWORD = "\u51e1\u4eba\u4fee\u4ed9\u4f20"
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_500
    private const val WEBVIEW_TIMEOUT_MS = 7_000L
    private val LOGIN_CHECK_URLS = listOf(
        "$MOBILE_BASE_URL/search/all/{key}/1.html",
        "$MOBILE_BASE_URL/modules/article/search.php?searchtype=all&searchkey={key}&page=1",
        "$BASE_URL/search/all/{key}/1.html",
        "$BASE_URL/modules/article/search.php?searchtype=all&searchkey={key}&page=1",
    )
}

object YouShuWebSearchBridge {
    @Volatile private var activityProvider: (() -> Activity?)? = null
    @Volatile private var enabledProvider: (() -> Boolean)? = null
    @Volatile private var enabled = true
    private val revision = AtomicLong()
    private val pending = ConcurrentHashMap.newKeySet<Request>()
    private val main by lazy { ModuleResources.handler(Looper.getMainLooper()) }
    private var registered = false

    private class Request(activity: Activity, val revision: Long, val identity: AccountHostRuntime.Identity?) {
        val activity = WeakReference(activity)
        val completion = YouShuFetchCompletion()
        val latch = CountDownLatch(1)
        val webView = AtomicReference<WebView?>()
        val pageVersion = AtomicInteger()
    }

    internal val executionRevision: Long get() = revision.get()

    internal fun isExecutionCurrent(value: Long): Boolean = ModuleResources.accepting && enabled &&
        value == revision.get() && enabledProvider?.let { runCatching(it).getOrDefault(false) } != false

    fun attach(provider: () -> Activity?) {
        attach(provider) { true }
    }

    internal fun attach(provider: () -> Activity?, enabledProvider: () -> Boolean) {
        activityProvider = provider
        this.enabledProvider = enabledProvider
        enabled = enabledProvider()
        YouShuHttpSession.bind(YouShuLoginCookies::cookieHeader, { executionRevision },
            ::isExecutionCurrent, YouShuLoginCookies::acceptResponseCookies)
        synchronized(this) {
            if (registered) return
            ModuleResources.blockWhile { if (pending.isNotEmpty()) "优书网页请求尚未结束" else null }
            ModuleResources.cleanup {
                revision.incrementAndGet()
                enabled = false
                HostGeneration.onMain { pending.toList().forEach { finish(it) }; pending.toList().forEach(::cleanup) }
                activityProvider = null
                this.enabledProvider = null
                YouShuHttpSession.clear()
                registered = false
            }
            registered = true
        }
    }

    internal fun invalidateExecution() {
        revision.incrementAndGet()
        pending.toList().forEach { finish(it) }
    }

    internal fun onModuleEnabledChanged(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) pending.toList().forEach { finish(it) }
    }

    internal fun onHostActivityDestroyed(activity: Activity) {
        pending.toList().filter { it.activity.get() === activity }.forEach { finish(it) }
    }

    private fun current(request: Request): Boolean {
        if (!isExecutionCurrent(request.revision)) return false
        val activity = request.activity.get() ?: return false
        if (activityProvider?.invoke() !== activity || activity.isFinishing || activity.isDestroyed) return false
        return runCatching {
            request.identity?.let { AccountHostRuntime.isCurrentStorage(it, activity) }
                ?: (AccountHostRuntime.storageSnapshot(activity) == null)
        }.getOrDefault(false)
    }

    private fun cleanup(request: Request) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val webView = request.webView.getAndSet(null)
        try {
            if (webView != null) {
                runCatching { webView.webViewClient = WebViewClient(); webView.webChromeClient = null }
                runCatching { webView.stopLoading() }
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                runCatching { webView.removeAllViews() }
                runCatching { webView.destroy() }
            }
        } finally {
            pending.remove(request)
        }
    }

    private fun finish(request: Request, html: String? = null) {
        if (!request.completion.finish(html)) return
        request.latch.countDown()
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup(request)
        else main.post { cleanup(request) }
    }

    fun fetchHtml(url: String, timeoutMs: Long = WEBVIEW_TIMEOUT_MS): String? {
        TaskCancellation.checkCurrent()
        if (Looper.myLooper() == Looper.getMainLooper() || !YouShuCookiePolicy.supports(url)) return null
        val lease = ModuleResources.lease() ?: return null
        return lease.use { fetchAdmittedHtml(url, timeoutMs) }
    }

    private fun fetchAdmittedHtml(url: String, timeoutMs: Long): String? {
        val activity = activityProvider?.invoke() ?: return null
        val request = Request(activity, executionRevision,
            runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull())
        if (!current(request)) return null
        pending += request
        val cancellation = TaskCancellation.current()?.register { finish(request) }
        val posted = main.post {
            runCatching {
                if (request.completion.completed || !current(request)) {
                    finish(request)
                    cleanup(request)
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
                request.webView.set(webView)
                (activity.window?.decorView as? ViewGroup)?.addView(
                    webView,
                    FrameLayout.LayoutParams(1, 1).apply {
                        leftMargin = 0
                        topMargin = 0
                    },
                )
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        val lease = ModuleResources.lease() ?: return
                        try {
                            if (request.completion.completed || !current(request)) { finish(request); return }
                            val currentView = view ?: webView
                            val version = request.pageVersion.incrementAndGet()
                            main.postDelayed({
                                if (version != request.pageVersion.get()) return@postDelayed
                                if (request.completion.completed || !current(request)) { finish(request); return@postDelayed }
                                currentView.evaluateJavascript(OUTER_HTML_JS) { value ->
                                    val callbackLease = ModuleResources.lease() ?: return@evaluateJavascript
                                    try {
                                        if (request.completion.completed) return@evaluateJavascript
                                        if (!current(request)) { finish(request); return@evaluateJavascript }
                                        YouShuLoginCookies.persistSession()
                                        finish(request, value.decodeJavascriptString())
                                    } finally { callbackLease.close() }
                                }
                            }, PAGE_SETTLE_DELAY_MS)
                        } finally { lease.close() }
                    }
                }
                webView.loadUrl(url)
            }.onFailure {
                finish(request)
                cleanup(request)
            }
        }
        if (!posted) finish(request)
        try {
            if (!request.latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) finish(request)
            TaskCancellation.checkCurrent()
            return request.completion.html.takeIf { current(request) }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            finish(request)
            cancellation?.close()
        }
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
    const val BASE_URL = YouShuCookiePolicy.BASE_URL
    const val MOBILE_BASE_URL = YouShuCookiePolicy.MOBILE_BASE_URL

    fun cookieHeader(): String = YouShuCookiePolicy.candidateHeader(::cookieHeader)

    fun cookieHeader(url: String): String {
        if (!YouShuCookiePolicy.supports(url)) return ""
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
    }

    /** Flush the browser's real session, without inventing an expiry or copying it to another host. */
    fun persistSession(): Boolean = runCatching { CookieManager.getInstance().flush(); true }.getOrDefault(false)

    fun hasLoginCookieForUrl(url: String): Boolean = hasLoginCookie(cookieHeader(url))

    internal fun requestHeaders(url: String, headers: Map<String, String>): Map<String, String> =
        YouShuCookiePolicy.requestHeaders(url, headers, ::cookieHeader)

    internal fun acceptResponseCookies(url: String, values: List<String>, requestCookie: String, revision: Long) {
        if (!YouShuCookiePolicy.supports(url) || values.isEmpty() ||
            !YouShuWebSearchBridge.isExecutionCurrent(revision) || cookieHeader(url) != requestCookie) return
        runCatching {
            val manager = CookieManager.getInstance()
            values.forEach { value -> manager.setCookie(url, value) }
            manager.flush()
        }
    }

    fun cookieNames(cookieHeader: String = cookieHeader()): String =
        cookieHeader.split(';')
            .map { it.trim().substringBefore('=') }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

    fun hasLoginCookie(cookieHeader: String = cookieHeader()): Boolean =
        YouShuCookiePolicy.hasLoginCookie(cookieHeader)
}
