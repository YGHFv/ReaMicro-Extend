package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostBrowserBindings
import com.reamicro.fix.core.HostDialogBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.online.OnlineParserMode
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.platform.LegadoPlatform.BrowserRequest
import io.legado.app.platform.LegadoPlatform.BrowserResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders the host's actual dictionary overlay and uses the WebView it creates.
 * All callbacks are scoped to that private native composition; the reader's own
 * dictionary and unrelated SDK WebViews are never redirected or inspected.
 */
internal object HostSourceBrowserBridge {
    private const val MAX_RESULT_CHARS = 16 * 1024 * 1024
    private val main by lazy { ModuleResources.handler(Looper.getMainLooper()) }
    private val backgroundPermits = Semaphore(2)
    private val cookieWriter by lazy {
        ModuleResources.pool(1) { task -> Thread(task, "ReaMicroBrowserCookie").apply { isDaemon = true } }
    }
    private val sessions = linkedMapOf<String, Session>()
    private val rendering = ThreadLocal<ArrayDeque<Session>>()
    private var provider: (() -> Activity?)? = null
    private var installed = false

    fun cancelAll(reason: String) {
        val cancel = {
            synchronized(sessions) { sessions.values.toList() }.forEach {
                it.fail(CancellationException(reason))
            }
        }
        if (Looper.myLooper() === Looper.getMainLooper()) cancel() else main.post { cancel() }
    }

    /** WebJsExtensions.upConfig updates only the WebView owned by this source. */
    fun updateConfig(sourceKey: String?, config: String): Boolean {
        val owner = synchronized(sessions) {
            sessions.values.lastOrNull { it.state.active() && it.request.visible && it.request.sourceKey == sourceKey }
        } ?: return false
        main.post {
            owner.webForConfig()?.let { view ->
                runCatching { owner.applyViewConfig(view, config) }
                    .onFailure { HookRuntime.logError("ReaMicro browser config rejected", it) }
            }
        }
        return true
    }

    /** A source action dialog temporarily owns the foreground above its page. */
    fun suspendForUi(activity: Activity, sourceKey: String): AutoCloseable {
        check(Looper.myLooper() === Looper.getMainLooper())
        val owner = synchronized(sessions) {
            sessions.values.lastOrNull { it.request.visible && it.owns(activity) && it.request.sourceKey == sourceKey }
        }
        owner?.cover()
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true) && owner != null) {
                if (Looper.myLooper() === Looper.getMainLooper()) owner.uncover()
                else main.post { owner.uncover() }
            }
        }
    }

    fun install(loader: ClassLoader, activityProvider: () -> Activity?): Boolean {
        if (installed) {
            provider = activityProvider
            return true
        }
        return HookInstallReport.install("LegadoParser", "nativeBrowser") {
            val native = requireNotNull(HostBrowserBindings.current)
            requireNotNull(HostDialogBindings.current)
            check(native.type("composer").classLoader === loader)
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(native.method("overlay"), object : HookCallback() {
                    override fun before(param: Call) {
                        val token = param.args.firstOrNull() as? String ?: return
                        val owner = synchronized(sessions) { sessions[token] } ?: return
                        val stack = rendering.get() ?: ArrayDeque<Session>().also(rendering::set)
                        stack.addLast(owner)
                        param.setObjectExtra("ReaMicro.sourceBrowser", owner)
                    }
                    override fun after(param: Call) {
                        if (param.getObjectExtra("ReaMicro.sourceBrowser") == null) return
                        val stack = rendering.get() ?: return
                        stack.removeLast()
                        if (stack.isEmpty()) rendering.remove()
                    }
                })
                handles += HookRuntime.hookMethod(native.constructor("source"), object : HookCallback() {
                    override fun before(param: Call) {
                        val owner = rendering.get()?.peekLast() ?: return
                        // Do not start a real request until the owned WebView has its headers,
                        // cookies, result callbacks and cancellation handlers attached.
                        param.args[0] = "about:blank"
                        param.args[1] = owner.displayHost
                        param.args[2] = null
                    }
                })
                handles += HookRuntime.hookMethod(Activity::class.java.getDeclaredMethod("onBackPressed"), object : HookCallback() {
                    override fun before(param: Call) {
                        val owner = synchronized(sessions) { sessions.values.lastOrNull {
                            it.request.visible && it.owns(param.thisObject as? Activity)
                        } } ?: return
                        owner.fail(CancellationException("用户返回取消书源网页"))
                        param.result = null
                    }
                })
                HookRuntime.deoptimize(native.method("overlay"))
                provider = activityProvider
                installed = true
                ModuleResources.cleanup {
                    HostGeneration.onMain {
                        synchronized(sessions) { sessions.values.toList() }.forEach {
                            it.fail(CancellationException("书源浏览器随模块热重载退出"))
                            it.closeImmediately()
                        }
                        handles.asReversed().forEach { it.unhook() }
                        rendering.remove()
                        provider = null
                        installed = false
                    }
                }
                true
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
                throw error
            }
        }
    }

    suspend fun browse(request: BrowserRequest): BrowserResult = browseInternal(request, false)

    /** Display the requested image; only the user's submitted text is returned. */
    suspend fun verificationCode(sourceKey: String?, imageUrl: String): String {
        val cookie = withContext(Dispatchers.IO) { CookieStore.getCookie(imageUrl) }
        return browseInternal(BrowserRequest(
            url = imageUrl, title = "输入图片验证码", sourceKey = sourceKey,
            headers = if (cookie.isEmpty()) emptyMap() else mapOf("Cookie" to cookie),
            refetchAfterSuccess = false, visible = true,
        ), true).body
    }

    private suspend fun browseInternal(request: BrowserRequest, verification: Boolean): BrowserResult {
        check(installed && ModuleResources.accepting) { "宿主浏览器尚未就绪或正在热重载" }
        require(!request.url.isNullOrBlank() || !request.html.isNullOrEmpty()) { "书源浏览器缺少 URL 和 HTML" }
        request.url?.takeIf(String::isNotBlank)?.let {
            require(URI(it).scheme?.lowercase() in setOf("http", "https")) { "书源浏览器仅接受 HTTP(S) 页面" }
        }
        require(request.delayTime >= 0) { "书源浏览器延迟不能为负数" }
        require(request.timeoutMs > 0) { "书源浏览器超时必须大于零" }
        val run: suspend () -> BrowserResult = {
            val outcome = withTimeout(request.timeoutMs) {
                suspendCancellableCoroutine<Outcome> { continuation ->
                    val owner = Session(request, continuation, verification)
                    continuation.invokeOnCancellation {
                        owner.state.finish()
                        owner.close()
                    }
                    if (!main.post {
                        if (!continuation.isActive) {
                            owner.close()
                        } else {
                            runCatching { owner.open() }.onFailure(owner::fail)
                        }
                    }) continuation.resumeWithException(IllegalStateException("宿主浏览器主线程已退出"))
                }
            }
            // RefetchAfterSuccess is implemented by upstream SourceVerificationHelp.
            // Persist the browser's cookies before it resumes that HTTP request.
            withContext(Dispatchers.IO) {
                outcome.cookies.forEach { (url, cookie) -> CookieStore.setCookie(url, cookie) }
            }
            outcome.result
        }
        return if (request.visible) run() else backgroundPermits.withPermit { run() }
    }

    private data class Outcome(val result: BrowserResult, val cookies: Map<String, String?>)

    private class Session(
        val request: BrowserRequest,
        private val continuation: CancellableContinuation<Outcome>,
        private val verification: Boolean,
    ) {
        val state = BrowserSessionState()
        val token = "reamicro-source-browser:${UUID.randomUUID()}"
        val displayHost = runCatching { URI(request.url.orEmpty()).host }.getOrNull()
            ?: request.title?.take(120) ?: "书源网页"
        private val native = requireNotNull(HostBrowserBindings.current)
        private val dialog = requireNotNull(HostDialogBindings.current)
        private val ui = requireNotNull(HostUiBindings.current)
        private val sourceRegex = request.sourceRegex?.takeIf(String::isNotBlank)?.toRegex()
        private val overrideRegex = request.overrideUrlRegex?.takeIf(String::isNotBlank)?.toRegex()
        // startBrowser returns as soon as it opens, while its page remains usable.
        // Keep the source context but detach that page from the completed caller Job.
        private val pageJob = SupervisorJob(
            if (request.awaitResult) request.coroutineContext[Job] else null,
        )
        private val pageRequest = request.copy(
            coroutineContext = request.coroutineContext.minusKey(Job) + pageJob,
        )
        private var sourceObserver: AutoCloseable? = null
        private var modeObserver: AutoCloseable? = null
        private var activity: WeakReference<Activity>? = null
        private var application: Application? = null
        private var container: FrameLayout? = null
        private var root: ViewGroup? = null
        private var web: WebView? = null
        private var originalClient: WebViewClient? = null
        private var completeButton: Button? = null
        private var codeInput: EditText? = null
        private var backCallback: OnBackInvokedCallback? = null
        private var started = false
        private var paused = false
        private var lifecyclePaused = false
        private val coverState = BrowserCoverState()
        private var parent: Session? = null
        private var lifetimeRemaining = request.timeoutMs
        private var lifetimeStartedAt = 0L
        private var acknowledged = false
        private var resultPending = false
        private var attempts = 0
        private var status = 200
        private var responseHeaders: Map<String, String> = emptyMap()
        private var eval: Runnable? = null
        private var outcome: Outcome? = null
        private var failure: Throwable? = null
        private var cleanupPosted = false
        private var mounted = false
        private val cleanup = Runnable { closeImmediately() }
        private val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { mounted = true }
            override fun onViewDetachedFromWindow(view: View) {
                if (mounted && state.active()) fail(CancellationException("宿主网页窗口已关闭"))
            }
        }
        private val mountTimeout = Runnable { fail(IllegalStateException("宿主字典浏览器未能创建 WebView")) }
        private val lifetimeTimeout = Runnable { fail(CancellationException("书源浏览器等待超时")) }
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching(::locateWebView).onFailure(::fail)
        }
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(value: Activity) {
                if (activity?.get() === value) fail(CancellationException("宿主页面已销毁"))
            }
            override fun onActivityPaused(value: Activity) {
                if (activity?.get() !== value || !request.visible) return
                lifecyclePaused = true
                paused = true
                eval?.let(main::removeCallbacks)
                web?.onPause()
            }
            override fun onActivityResumed(value: Activity) {
                if (activity?.get() !== value || !request.visible) return
                lifecyclePaused = false
                paused = coverState.covered()
                if (!paused) {
                    web?.onResume()
                    if (resultPending) scheduleEvaluation(0)
                }
            }
            override fun onActivityCreated(value: Activity, saved: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, saved: Bundle) = Unit
        }

        fun owns(value: Activity?): Boolean = state.active() && activity?.get() === value

        fun open() {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (!state.active()) return
            check(ModuleResources.accepting) { "模块正在热重载" }
            pageRequest.sessionRunner?.invoke { Unit }
            val owner = requireNotNull(provider?.invoke()) { "请先打开阅微再处理书源网页" }
            check(!owner.isFinishing && !owner.isDestroyed && owner.window.decorView.isAttachedToWindow) {
                "宿主窗口已退出"
            }
            synchronized(sessions) {
                if (request.visible) {
                    val visible = sessions.values.filter { it.request.visible && it.state.active() }
                    check(visible.size < 3) { "书源网页嵌套过多，请先关闭一个网页" }
                    check(visible.all { it.owns(owner) }) { "书源网页所属宿主页面已变化" }
                    visible.lastOrNull()?.let {
                        it.pageRequest.sessionRunner?.invoke { Unit }
                        parent = it
                        it.cover()
                    }
                }
                sessions[token] = this
            }
            activity = WeakReference(owner)
            sourceObserver = OnlineSourceStore.observeChanges {
                // Source observers may run on a cancelled import worker. Validate
                // on the UI owner, without inheriting that unrelated thread token.
                main.post {
                    if (state.active() && runCatching { pageRequest.sessionRunner?.invoke { Unit } }.isFailure) {
                        fail(CancellationException("书源已更新或删除"))
                    }
                }
            }
            modeObserver = OnlineParserMode.observeChanges {
                main.post { fail(CancellationException("解析方式已切换")) }
            }
            if (request.visible && Build.VERSION.SDK_INT >= 33) {
                val callback = OnBackInvokedCallback { fail(CancellationException("用户返回取消书源网页")) }
                backCallback = callback
                owner.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            }
            application = owner.application.also { it.registerActivityLifecycleCallbacks(lifecycle) }
            val holder = FrameLayout(owner).also { container = it }
            holder.addOnAttachStateChangeListener(attachment)
            val mount = (dialog.constructor("root").newInstance(owner) as ViewGroup).also { root = it }
            holder.addView(mount, FrameLayout.LayoutParams(-1, -1))
            if (!request.visible) {
                // Keep a real viewport for responsive pages without intercepting the visible
                // app's touch, accessibility or keyboard focus.
                holder.alpha = 0f
                holder.translationX = owner.resources.displayMetrics.widthPixels * 2f
                holder.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                holder.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                installActions(holder, owner)
            }
            val haze = native.constructor("haze").newInstance(false)
            val dismiss = ui.callback(ui.type("function0")) {
                fail(CancellationException("用户取消书源网页"))
            }
            val body = ui.lambda(0x524D4252, ui.callback(ui.type("function2")) { args ->
                val composer = args?.firstOrNull()?.takeIf(ui.composerClass::isInstance) ?: return@callback
                if (state.active()) native.render(token, haze, dismiss, composer)
            })
            val themed = ui.lambda(0x524D4254, ui.callback(ui.type("function2")) { args ->
                val composer = args?.firstOrNull()?.takeIf(ui.composerClass::isInstance) ?: return@callback
                if (state.active()) dialog.renderThemed(body, composer)
            })
            mount.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            dialog.method("setContent").invoke(mount, themed)
            // A source action may originate in the host's native settings Dialog.
            // Use that foreground window so its content cannot obscure verification.
            val foregroundWindow = if (request.visible) HostSettingsDialogs.backgroundWindow(owner) else owner.window
            (foregroundWindow.decorView as ViewGroup).addView(holder, FrameLayout.LayoutParams(-1, -1))
            main.postDelayed(mountTimeout, 5000)
            lifetimeStartedAt = SystemClock.uptimeMillis()
            main.postDelayed(lifetimeTimeout, lifetimeRemaining)
            locateWebView()
        }

        fun cover() {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (!state.active()) return
            if (!coverState.cover()) return
            paused = true
            eval?.let(main::removeCallbacks)
            main.removeCallbacks(lifetimeTimeout)
            if (lifetimeStartedAt != 0L) {
                lifetimeRemaining = (lifetimeRemaining - (SystemClock.uptimeMillis() - lifetimeStartedAt)).coerceAtLeast(1L)
            }
            lifetimeStartedAt = 0
            container?.visibility = View.INVISIBLE
            web?.onPause()
        }

        fun uncover() {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (!state.active() || !coverState.uncover()) return
            container?.visibility = View.VISIBLE
            paused = lifecyclePaused
            if (!paused) {
                web?.onResume()
                if (resultPending) scheduleEvaluation(0)
            }
            lifetimeStartedAt = SystemClock.uptimeMillis()
            main.postDelayed(lifetimeTimeout, lifetimeRemaining)
        }

        private fun installActions(holder: FrameLayout, owner: Activity) {
            val density = owner.resources.displayMetrics.density
            val row = LinearLayout(owner).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (12 * density).toInt())
            }
            if (verification) row.addView(EditText(owner).apply {
                hint = "验证码"
                isSingleLine = true
                setSelectAllOnFocus(true)
                codeInput = this
            }, LinearLayout.LayoutParams(0, -2, 2f))
            row.addView(Button(owner).apply {
                text = "取消"
                setOnClickListener { fail(CancellationException("用户取消书源网页")) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(owner).apply {
                text = if (verification) "提交" else if (request.awaitResult) "完成验证" else "关闭"
                isEnabled = false
                setOnClickListener {
                    if (verification) {
                        val code = codeInput?.text?.toString()?.trim().orEmpty()
                        if (code.isEmpty()) {
                            codeInput?.error = "请输入图片中的验证码"
                        } else {
                            complete(BrowserResult(web?.url ?: request.url.orEmpty(), code))
                        }
                    } else if (!request.awaitResult) {
                        complete(BrowserResult(web?.url ?: request.url.orEmpty(), ""))
                    } else {
                        resultPending = true
                        attempts = 0
                        scheduleEvaluation(0)
                    }
                }
                completeButton = this
            }, LinearLayout.LayoutParams(0, -2, 1f))
            holder.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        private fun locateWebView() {
            if (web != null || !state.active()) return
            val mount = root ?: return
            val queue = ArrayDeque<View>().apply { add(mount) }
            var found: WebView? = null
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 1024) {
                val view = queue.removeFirst()
                if (view is WebView) {
                    check(found == null) { "宿主字典根包含多个 WebView，拒绝混用" }
                    found = view
                } else if (view is ViewGroup) {
                    for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
                }
            }
            val value = found ?: return
            mount.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            main.removeCallbacks(mountTimeout)
            runCatching { attach(value) }.onFailure(::fail)
        }

        private fun attach(value: WebView) {
            if (!state.active()) return
            web = value
            originalClient = value.webViewClient
            value.webViewClient = Client(originalClient ?: WebViewClient())
            value.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = if (request.cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
                blockNetworkImage = !request.visible
                request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.let {
                    userAgentString = it
                }
            }
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            // The upstream WebJsExtensions bindings are installed here by the parser adapter.
            io.legado.app.platform.LegadoWebViewBindings.attach(value, pageRequest)
            if (request.preloadJavaScript != null || request.viewConfig != null) {
                value.addJavascriptInterface(BasicPageBridge(this), WebJsExtensions.nameBasic)
                applyViewConfig(value, request.viewConfig ?: "{}")
            }
            val cookieParts = request.headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
                ?.split(';')?.map(String::trim)?.filter { it.contains('=') }.orEmpty()
            if (request.url != null && cookieParts.isNotEmpty()) {
                var remaining = cookieParts.size
                cookieParts.forEach { cookie ->
                    cookies.setCookie(request.url, cookie) {
                        if (--remaining == 0 && accepts(value)) {
                            runCatching { startPage(value) }.onFailure(::fail)
                        }
                    }
                }
            } else {
                startPage(value)
            }
        }

        private fun startPage(value: WebView) {
            if (!accepts(value) || started) return
            started = true
            state.navigated()
            val encoding = request.encode ?: "utf-8"
            val html = request.html
            if (!html.isNullOrEmpty()) {
                value.loadDataWithBaseURL(request.url, spliceHtml(html), "text/html", encoding, request.url)
            } else {
                value.loadUrl(requireNotNull(request.url), request.headers)
            }
            completeButton?.isEnabled = true
            if (!request.awaitResult && !acknowledged) {
                acknowledged = true
                if (continuation.isActive) continuation.resume(Outcome(
                    BrowserResult(request.url ?: "about:blank", request.html.orEmpty()), emptyMap(),
                ))
            }
        }

        fun webForConfig(): WebView? = web?.takeIf(::accepts)

        fun applyViewConfig(value: WebView, raw: String) {
            val config = JSONObject(raw)
            if (config.has("webViewInitialScale") && !config.isNull("webViewInitialScale")) {
                value.settings.apply {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    textZoom = config.getInt("webViewInitialScale")
                }
            }
            if (config.has("webViewCacheMode") && !config.isNull("webViewCacheMode")) {
                value.settings.cacheMode = config.getInt("webViewCacheMode")
            }
            value.isNestedScrollingEnabled = config.optBoolean("isNestedScrollingEnabled", false)
            // Sheet sizing/dragging/shape parameters belong to BottomWebViewDialog.
            // This bridge intentionally retains the host dictionary's own geometry.
        }

        private fun spliceHtml(html: String): String = BrowserPageContent.splice(
            html, request.preloadJavaScript, WebJsExtensions.basicJs, WebJsExtensions.JS_INJECTION,
        )

        /** Upstream BottomWebViewDialog only intercepts main documents when preload is present. */
        private fun preloadResponse(resource: WebResourceRequest): WebResourceResponse? {
            val preload = request.preloadJavaScript
            if (preload.isNullOrEmpty() || !state.active()) return null
            val url = resource.url.toString()
            if (!resource.isForMainFrame && url.contains("https://legado-inject-js.internal")) {
                return WebResourceResponse("text/javascript", "UTF-8",
                    ByteArrayInputStream("(() => {${WebJsExtensions.JS_INJECTION}\n$preload\n})();".toByteArray()))
            }
            if (!resource.isForMainFrame) return null
            return try {
                val fetch: () -> Any? = {
                    runBlocking(pageRequest.coroutineContext + Dispatchers.IO) {
                        pageJob.ensureActive()
                        val cookies = CookieManager.getInstance()
                        val cookie = cookies.getCookie(url)
                        okHttpClient.newCallResponse {
                            url(url)
                            method(resource.method, null)
                            if (!cookie.isNullOrEmpty()) addHeader("Cookie", cookie)
                            resource.requestHeaders.forEach { (key, value) -> addHeader(key, value) }
                        }.use { response ->
                            response.headers("Set-Cookie").forEach { cookies.setCookie(url, it) }
                            val contentType = response.body.contentType()
                            val mime = contentType?.toString()?.substringBefore(';') ?: "text/html"
                            val charset = contentType?.charset() ?: Charsets.UTF_8
                            val body = spliceHtml(response.body.text(charset.name()))
                            pageJob.ensureActive()
                            WebResourceResponse(mime, charset.name(), ByteArrayInputStream(body.toByteArray(charset)))
                        }
                    }
                }
                val runner = pageRequest.sessionRunner
                (if (runner == null) fetch() else runner.invoke(fetch)) as WebResourceResponse
            } catch (error: Throwable) {
                if (error is CancellationException || !state.active()) {
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    HookRuntime.logError("ReaMicro browser preload request failed", error)
                    null // Same native WebView fallback as upstream getModifiedContentWithJs.
                }
            }
        }

        private fun accepts(view: WebView?): Boolean = state.active() && ModuleResources.accepting &&
            view === web && activity?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        private fun scheduleEvaluation(delay: Long) {
            eval?.let(main::removeCallbacks)
            if (!state.active() || paused) return
            val epoch = state.currentDocument()
            val action = Runnable {
                val view = web ?: return@Runnable
                if (!accepts(view) || !state.accepts(epoch) || paused) return@Runnable
                val script = request.javaScript?.takeIf(String::isNotEmpty)
                    ?: "document.documentElement.outerHTML"
                val injected = if (request.isRule) {
                    io.legado.app.platform.LegadoWebViewBindings.injectionString + "\n" + script
                } else script
                view.evaluateJavascript(injected) { raw ->
                    if (!accepts(view) || !state.accepts(epoch)) return@evaluateJavascript
                    if (sourceRegex != null || overrideRegex != null) return@evaluateJavascript
                    if (raw.isNullOrEmpty() || raw == "null") {
                        if (attempts++ >= 31) fail(IllegalStateException("书源网页脚本执行超时"))
                        else scheduleEvaluation(1000)
                        return@evaluateJavascript
                    }
                    runCatching {
                        require(raw.length <= MAX_RESULT_CHARS) { "书源网页结果超过16M字符上限" }
                        val decoded = JSONTokener(raw).nextValue()
                        val body = if (decoded is String) decoded else raw
                        complete(BrowserResult(view.url ?: request.url ?: "about:blank", body, status, responseHeaders), epoch)
                    }.onFailure(::fail)
                }
            }
            eval = action
            main.postDelayed(action, delay)
        }

        private fun complete(result: BrowserResult, epoch: Long? = null) {
            if (!state.finish(epoch)) return
            outcome = try {
                Outcome(result, cookieSnapshot(result.url))
            } catch (error: Throwable) {
                failure = error
                close()
                return
            }
            close()
        }

        private fun cookieSnapshot(fallbackUrl: String): Map<String, String?> {
            val cookies = CookieManager.getInstance()
            val snapshot = linkedMapOf<String, String?>()
            val pageUrl = web?.url ?: fallbackUrl
            for (url in listOfNotNull(pageUrl, request.url).distinct()) {
                if (runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)) {
                    snapshot[url] = cookies.getCookie(url)
                }
            }
            request.sourceKey?.let { snapshot[it] = snapshot[pageUrl] ?: snapshot[request.url] }
            return snapshot
        }

        fun fail(error: Throwable) {
            if (state.finish()) failure = error
            close()
        }

        /** WebView callbacks must return before disposing their native composition. */
        fun close() {
            if (Looper.myLooper() !== Looper.getMainLooper()) {
                main.post { close() }
                return
            }
            if (cleanupPosted) return
            cleanupPosted = true
            if (!main.post(cleanup)) closeImmediately()
        }

        /** Called directly by generation retirement after no hook callback remains active. */
        fun closeImmediately() {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (!state.claimCleanup()) return
            coverState.retire()
            main.removeCallbacks(cleanup)
            synchronized(sessions) { sessions.values.filter { it.parent === this }.toList() }
                .forEach { it.fail(CancellationException("上层书源网页已关闭")) }
            fun release(action: () -> Unit) {
                runCatching(action).onFailure {
                    HookRuntime.logError("ReaMicro source browser resource cleanup failed", it)
                }
            }
            eval?.let(main::removeCallbacks)
            eval = null
            main.removeCallbacks(mountTimeout)
            main.removeCallbacks(lifetimeTimeout)
            val cookiesToPersist = if (acknowledged) {
                outcome?.cookies ?: runCatching { cookieSnapshot(request.url.orEmpty()) }.getOrDefault(emptyMap())
            } else emptyMap()
            pageJob.cancel()
            sourceObserver?.let { release { it.close() } }
            sourceObserver = null
            modeObserver?.let { release { it.close() } }
            modeObserver = null
            application?.let { release { it.unregisterActivityLifecycleCallbacks(lifecycle) } }
            application = null
            if (Build.VERSION.SDK_INT >= 33) backCallback?.let { callback ->
                release { activity?.get()?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(callback) }
            }
            backCallback = null
            release { root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener) }
            web?.let {
                runCatching {
                    it.stopLoading()
                    io.legado.app.platform.LegadoWebViewBindings.detach(it)
                    it.removeJavascriptInterface(WebJsExtensions.nameBasic)
                    originalClient?.let { client -> it.webViewClient = client }
                }.onFailure { error -> HookRuntime.logError("ReaMicro source browser callback cleanup failed", error) }
            }
            // Both APKs' AndroidView release callbacks only forward to the default
            // callback; the dictionary path has no WebView.destroy() call. Dispose
            // native effects first, then release this exclusively owned renderer.
            root?.let { mount ->
                runCatching { dialog.method("dispose").invoke(mount) }
                    .onFailure { HookRuntime.logError("ReaMicro native browser disposal failed", it) }
            }
            web?.let { value ->
                release { (value.parent as? ViewGroup)?.removeView(value) }
                release { value.webViewClient = WebViewClient() }
                release { value.webChromeClient = null }
                release { value.removeAllViews() }
                release { value.destroy() }
            }
            container?.let {
                it.removeOnAttachStateChangeListener(attachment)
                release { (it.parent as? ViewGroup)?.removeView(it) }
            }
            root = null
            container = null
            web = null
            originalClient = null
            completeButton = null
            codeInput = null
            activity = null
            synchronized(sessions) { sessions.remove(token) }
            parent?.uncover()
            parent = null
            val completed = outcome
            outcome = null
            val error = failure
            failure = null
            if (cookiesToPersist.isNotEmpty()) {
                release {
                    cookieWriter.execute {
                        runCatching {
                            val save: () -> Any? = {
                                cookiesToPersist.forEach { (url, cookie) -> CookieStore.setCookie(url, cookie) }
                            }
                            val runner = pageRequest.sessionRunner
                            if (runner == null) save() else runner.invoke(save)
                        }.onFailure {
                            if (it !is CancellationException) HookRuntime.logError("ReaMicro browser cookie persistence failed", it)
                        }
                    }
                }
            }
            if (continuation.isActive) {
                if (completed != null) continuation.resume(completed)
                else continuation.resumeWithException(error ?: CancellationException("书源网页已关闭"))
            } else if (error != null && error !is CancellationException) {
                HookRuntime.logError("ReaMicro source browser stopped", error)
            }
        }

        private inner class Client(private val delegate: WebViewClient) : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                delegate.onPageStarted(view, url, favicon)
                if (!accepts(view) || !started || url == "about:blank") return
                state.navigated()
                status = 200
                responseHeaders = emptyMap()
                attempts = 0
                eval?.let(main::removeCallbacks)
                if (request.preloadJavaScript != null || request.viewConfig != null) {
                    view.evaluateJavascript(WebJsExtensions.basicJs, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                delegate.onPageFinished(view, url)
                if (!accepts(view) || !started || url == "about:blank" || (url != null && view.url != url)) return
                io.legado.app.platform.LegadoWebViewBindings.initializeResult(view, pageRequest)
                if (!request.visible && (sourceRegex == null && overrideRegex == null || !request.javaScript.isNullOrEmpty())) {
                    resultPending = true
                    scheduleEvaluation(1000 + request.delayTime)
                }
            }

            private fun intercept(url: String): Boolean {
                if (!state.active() || overrideRegex?.matches(url) != true) return false
                complete(BrowserResult(request.url ?: web?.url ?: url, url))
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (accepts(view) && intercept(request.url.toString())) true
                else delegate.shouldOverrideUrlLoading(view, request)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                if (accepts(view) && intercept(url)) true else delegate.shouldOverrideUrlLoading(view, url)

            override fun onLoadResource(view: WebView, url: String) {
                delegate.onLoadResource(view, url)
                if (accepts(view) && sourceRegex?.matches(url) == true) {
                    complete(BrowserResult(request.url ?: view.url ?: url, url))
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                preloadResponse(request) ?: (if (this@Session.request.isRule) {
                    io.legado.app.platform.LegadoWebViewBindings.intercept(request.url.toString())
                } else null) ?: delegate.shouldInterceptRequest(view, request)

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                (if (request.isRule) io.legado.app.platform.LegadoWebViewBindings.intercept(url) else null)
                    ?: delegate.shouldInterceptRequest(view, url)

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                delegate.doUpdateVisitedHistory(view, url, isReload)

            override fun onPageCommitVisible(view: WebView, url: String?) = delegate.onPageCommitVisible(view, url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                delegate.onReceivedError(view, request, error)
                if (accepts(view) && request.isForMainFrame) fail(IllegalStateException("书源网页加载失败：${error.errorCode}"))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                delegate.onReceivedHttpError(view, request, response)
                if (accepts(view) && request.isForMainFrame) {
                    // Match the upstream parser: a non-2xx page may contain usable content.
                    status = response.statusCode
                    responseHeaders = response.responseHeaders.orEmpty().toMap()
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Never turn an upstream proceed() into silent certificate acceptance.
                handler?.cancel()
                if (accepts(view)) fail(IllegalStateException("书源网页 TLS 证书校验失败"))
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
                delegate.onReceivedHttpAuthRequest(view, handler, host, realm)
            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
                delegate.onReceivedClientCertRequest(view, request)
            override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) =
                delegate.onReceivedLoginRequest(view, realm, account, args)
            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
                delegate.onScaleChanged(view, oldScale, newScale)
            override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) =
                delegate.onFormResubmission(view, dontResend, resend)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!accepts(view)) return delegate.onRenderProcessGone(view, detail)
                fail(IllegalStateException("书源网页渲染进程已退出"))
                return true
            }
        }

        private class BasicPageBridge(owner: Session) {
            private val owner = WeakReference(owner)

            @JavascriptInterface fun onCloseRequested() {
                main.post {
                    val session = owner.get() ?: return@post
                    if (session.state.active()) {
                        session.complete(BrowserResult(session.web?.url ?: session.request.url.orEmpty(), ""))
                    }
                }
            }

            // Upstream changes orientation only in a fullscreen media view. The
            // host dictionary stays embedded, so ordinary pages leave it alone.
            @JavascriptInterface fun lockOrientation(orientation: String) = Unit
        }
    }
}
