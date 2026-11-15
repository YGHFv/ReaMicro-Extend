package io.legado.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.WebSettings
import android.widget.Toast
import java.io.File
import io.legado.app.data.entities.BaseSource
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Application boundary for the imported Legado parser. Rule/network/crypto
 * algorithms stay in their upstream packages; host UI and private storage are
 * supplied once by the module integration.
 */
object LegadoPlatform {
    data class BrowserRequest(
        val url: String?,
        val title: String? = null,
        val html: String? = null,
        val javaScript: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val sourceKey: String? = null,
        val sourceRegex: String? = null,
        val overrideUrlRegex: String? = null,
        val delayTime: Long = 0,
        val awaitResult: Boolean = true,
        val refetchAfterSuccess: Boolean = true,
        val visible: Boolean = true,
        val encode: String? = null,
        val cacheFirst: Boolean = false,
        val timeoutMs: Long = 60_000,
        val result: String? = null,
        val isRule: Boolean = false,
        val sourceSnapshot: BaseSource? = null,
        val coroutineContext: CoroutineContext = EmptyCoroutineContext,
        val sessionRunner: ((() -> Any?) -> Any?)? = null,
        val preloadJavaScript: String? = null,
        val viewConfig: String? = null,
    )

    data class BrowserResult(
        val url: String,
        val body: String,
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
    )

    interface Adapter {
        val context: Context
        val filesDir: File get() = File(context.filesDir, "legado-parser").apply { mkdirs() }
        val cacheDir: File get() = File(context.cacheDir, "legado-parser").apply { mkdirs() }
        val userAgent: String get() = WebSettings.getDefaultUserAgent(context)
        val threadCount: Int get() = 4
        /** Capture the caller's source/revision guard for OkHttp dispatcher callbacks. */
        fun captureSession(): ((() -> Any?) -> Any?)? = null
        fun getCookie(url: String): String? =
            context.getSharedPreferences("legado_parser_cookies", Context.MODE_PRIVATE)
                .getString(url, null)
        fun putCookie(url: String, cookie: String?) {
            val preferences = context.getSharedPreferences("legado_parser_cookies", Context.MODE_PRIVATE)
            check(preferences.edit().apply {
                if (cookie == null) remove(url) else putString(url, cookie)
            }.commit()) { "无法持久化书源 Cookie" }
        }
        fun clearCookies() {
            check(context.getSharedPreferences("legado_parser_cookies", Context.MODE_PRIVATE)
                .edit().clear().commit()) { "无法删除书源 Cookie" }
        }
        fun onNetworkFailure(host: String, error: java.io.IOException) {}
        suspend fun browser(request: BrowserRequest): BrowserResult
        suspend fun verificationCode(sourceKey: String?, imageUrl: String): String =
            throw UnsupportedOperationException("宿主尚未接入图片验证码输入")
        fun openUrl(url: String, mimeType: String?, sourceKey: String?, sourceName: String?): Unit =
            throw UnsupportedOperationException("宿主尚未接入书源打开链接")
        fun importUrl(url: String): Unit =
            throw UnsupportedOperationException("宿主尚未接入书源URL导入")
        fun sourceAction(name: String, url: String?, title: String?, origin: String?, sourceKey: String?): Unit =
            throw UnsupportedOperationException("宿主尚未接入书源页面操作: $name")
        fun readBookConfig(): Map<String, Any> =
            throw UnsupportedOperationException("宿主尚未接入阅读配置映射")
        fun themeConfig(): Map<String, Any?> =
            throw UnsupportedOperationException("宿主尚未接入主题配置映射")
        fun themeMode(): String =
            throw UnsupportedOperationException("宿主尚未接入主题模式映射")
        fun androidId(): String =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        fun log(message: String, error: Throwable? = null) {
            android.util.Log.w("LegadoParser", message, error)
        }
        fun toast(message: String, long: Boolean = false) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Volatile private var installed: Adapter? = null
    val adapter: Adapter get() = installed ?: error("LegadoPlatform has not been installed")
    val context: Context get() = adapter.context
    val filesDir: File get() = adapter.filesDir
    val cacheDir: File get() = adapter.cacheDir
    val userAgent: String get() = adapter.userAgent
    val threadCount: Int get() = adapter.threadCount.coerceIn(1, 64)

    @JvmStatic fun install(value: Adapter) {
        installed = value
        io.legado.app.help.http.HttpRuntime.activate()
    }
    @JvmStatic fun uninstall(value: Adapter) {
        if (installed === value) {
            io.legado.app.help.http.HttpRuntime.close()
            io.legado.app.help.AppCacheManager.clearMemory()
            io.legado.app.help.CacheManager.close()
            io.legado.app.help.ConcurrentRateLimiter.concurrentRecordMap.clear()
            installed = null
        }
    }
    @JvmStatic fun log(message: String, error: Throwable? = null) { adapter.log(message, error) }
}
