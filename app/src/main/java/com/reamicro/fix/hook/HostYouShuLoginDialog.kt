package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.association.provider.YouShuLoginCookies
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.hook.settings.YOUSHU_LOGIN_STATE_JS
import com.reamicro.fix.hook.settings.YOUSHU_LOGIN_STATE_TIMEOUT_MS
import com.reamicro.fix.hook.settings.YOUSHU_LOGIN_URL
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import com.reamicro.fix.ui.ModuleTheme
import com.reamicro.fix.ui.ModuleLinearProgressIndicator

/** The host dictionary browser uses a 92% by 78% centered window and an 18dp image surface. */
internal class HostYouShuLoginDialog(
    private val activity: Activity,
    private val isCurrent: () -> Boolean,
    private val onResult: (Boolean) -> Unit,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private var backdropImage by mutableStateOf<Bitmap?>(null)
    private val backdrop = HostWindowBackdrop(activity, main, ::acceptsWebCallbacks) { backdropImage = it }
    private var webView: WebView? = null
    private var compose: ComposeView? = null
    private var loading by mutableStateOf(true)
    private var checking by mutableStateOf(false)
    private var epoch by mutableIntStateOf(0)
    private var closed = false
    private var resolved = false
    private var configurationRegistered = false
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { epoch++ }
        override fun onLowMemory() = Unit
    }

    override fun show() {
        if (isShowing || closed) return
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) { release(); return }
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnDismissListener { release() }
            owner.start()
            val native = HostThemeColors.snapshot(activity, HostEmbeddedStyle.isDark(activity))
            val web = WebView(activity).apply {
                setBackgroundColor(native.contentArgb)
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (acceptsWebCallbacks()) loading = newProgress < 100
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!acceptsWebCallbacks()) return
                        loading = false
                        YouShuLoginCookies.persistSession()
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (acceptsWebCallbacks() && request?.isForMainFrame == true) loading = false
                    }
                }
            }
            webView = web
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { Content(web) }
            }
            compose = view
            setContentView(view)
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            activity.registerComponentCallbacks(configuration)
            configurationRegistered = true
            configureEpubEdgeToEdgeWindow(window, android.graphics.Color.TRANSPARENT, native.dark)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            super.show()
            active += this
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
            val weak = WeakReference(this)
            ModuleResources.blockWhile {
                if (weak.get()?.isShowing == true) "请先关闭优书网页面" else null
            }
            ModuleResources.cleanup { HostGeneration.onMain { weak.get()?.dismiss() } }
            web.loadUrl(YOUSHU_LOGIN_URL)
            web.requestFocus()
        } catch (error: Throwable) {
            runCatching { release() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun dismiss() {
        try { release() } finally { super.dismiss() }
    }

    private fun acceptsWebCallbacks(): Boolean =
        !closed && ModuleResources.accepting && !activity.isFinishing && !activity.isDestroyed && isCurrent()

    private fun resolve(value: Boolean) {
        if (resolved) return
        resolved = true
        runCatching { onResult(value && isCurrent()) }
            .onFailure { HookRuntime.logError("ReaMicro YouShu login callback failed", it) }
    }

    private fun finishLogin() {
        val web = webView ?: return
        if (closed || checking || !isCurrent()) return
        checking = true
        YouShuLoginCookies.persistSession()
        var delivered = false
        fun finish(hasPageLogin: Boolean?) {
            if (delivered || closed) return
            delivered = true
            checking = false
            YouShuLoginCookies.persistSession()
            val pageUrl = web.url ?: YOUSHU_LOGIN_URL
            if (isCurrent() && YouShuLoginCookies.hasLoginCookieForUrl(pageUrl) && hasPageLogin == true) {
                resolve(true)
                dismiss()
            } else if (isShowing) {
                val message = if (hasPageLogin == null) "暂时无法确认登录，请等待页面刷新后再点完成"
                    else "尚未确认优书网登录，请完成登录后再点完成"
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
        main.postDelayed({ finish(null) }, YOUSHU_LOGIN_STATE_TIMEOUT_MS)
        runCatching {
            web.evaluateJavascript(YOUSHU_LOGIN_STATE_JS) { value ->
                finish(when (value) { "true" -> true; "false" -> false; else -> null })
            }
        }.onFailure { finish(null) }
    }

    @Composable
    private fun Content(web: WebView) {
        val config = LocalConfiguration.current
        val native = HostThemeColors.snapshot(activity, HostEmbeddedStyle.isDark(activity))
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(epoch, config, scale, EmbeddedHostUi.typographyStyles) {
            BookImageDialogStyle.typography(activity, scale)
        }
        val blurRadius = with(LocalDensity.current) { 36.dp.toPx() }
        LaunchedEffect(native, config, epoch) {
            web.setBackgroundColor(native.contentArgb)
            configureEpubEdgeToEdgeWindow(window, android.graphics.Color.TRANSPARENT, native.dark)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
            backdrop.refresh()
        }
        ModuleTheme(colorScheme = native.controlsScheme(), typography = typography) {
            BoxWithConstraints(Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() },
                contentAlignment = Alignment.Center) {
                val image = backdropImage
                if (image != null) {
                    Image(image.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                        renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
                    }, contentScale = ContentScale.FillBounds)
                }
                // The dictionary uses 36dp blur, a .16 theme tint and a .28 fallback tint.
                Box(Modifier.fillMaxSize().background(Color(native.contentArgb)
                    .copy(alpha = if (image == null) .28f else .16f)))
                Box(Modifier.fillMaxSize().background(native.role("Scrim")
                    .copy(alpha = if (native.dark) .32f else .08f)))
                Column(Modifier.fillMaxWidth(.92f).height(maxHeight * .78f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(18.dp),
                        color = Color(native.contentArgb), border = BorderStroke(1.dp, Color(native.borderVariantArgb))) {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                            if (loading) ModuleLinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),
                                color = native.role("Tertiary"), trackColor = native.role("SurfaceContainerHighest"))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.reamicro.fix.ui.DialogActionButton(onClick = { dismiss() }, modifier = Modifier.weight(1f)) {
                            Text("取消", style = typography.labelLarge)
                        }
                        com.reamicro.fix.ui.DialogActionButton(onClick = { finishLogin() }, enabled = !checking && !loading,
                            modifier = Modifier.weight(1f)) {
                            Text("完成", style = typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    private fun release() {
        if (closed) return
        closed = true
        active -= this
        val view = compose
        val web = webView
        compose = null
        webView = null
        fun clean(action: () -> Unit) {
            runCatching(action).onFailure { HookRuntime.logError("ReaMicro YouShu browser cleanup failed", it) }
        }
        clean { main.removeCallbacksAndMessages(null) }
        clean { backdrop.close(); backdropImage = null }
        clean { YouShuLoginCookies.persistSession() }
        clean { view?.disposeComposition() }
        clean { web?.webViewClient = WebViewClient() }
        clean { web?.webChromeClient = null }
        clean { web?.stopLoading() }
        clean { (web?.parent as? ViewGroup)?.removeView(web) }
        clean { web?.destroy() }
        clean { owner.close() }
        if (configurationRegistered) {
            configurationRegistered = false
            clean { activity.unregisterComponentCallbacks(configuration) }
        }
        if (!resolved) resolve(false)
    }

    companion object {
        private val active = linkedSetOf<HostYouShuLoginDialog>()
        fun dismissActive() {
            HostGeneration.onMain { active.toList().forEach { runCatching { it.dismiss() } } }
        }
    }
}
