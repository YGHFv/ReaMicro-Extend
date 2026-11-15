package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.utils.NetworkUtils
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Call
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import io.legado.app.platform.LegadoPlatform

internal class HttpRequestSession(
    private val context: CoroutineContext,
    private val runner: ((() -> Any?) -> Any?)?,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> run(action: () -> T): T {
        context.ensureActive()
        return (if (runner == null) action() else runner.invoke(action)) as T
    }
}

internal fun <T> okhttp3.Request.withSession(action: () -> T): T {
    val session = tag(HttpRequestSession::class.java)
    return if (session == null) action() else session.run(action)
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            //临时保存 书源启用cookie选项再添加到数据库
            val cookieBuilder = StringBuilder()
            cookies.forEachIndexed { index, cookie ->
                if (index > 0) cookieBuilder.append(";")
                cookieBuilder.append(cookie.name).append('=').append(cookie.value)
            }
            val domain = NetworkUtils.getSubDomain(url.toString())
            CacheManager.putMemory("${domain}_cookieJar", cookieBuilder.toString())
        }

    }
}

/** Parser-owned HTTP lifecycle; no process-wide URL handler is installed. */
object HttpRuntime : AutoCloseable {
    internal val proxyClients = ConcurrentHashMap<String, OkHttpClient>()
    private var current: OkHttpClient? = null
    private var retired = false
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    @Synchronized fun activate() { retired = false }
    @Synchronized fun client(): OkHttpClient {
        check(!retired) { "书源请求层已退场" }
        return current ?: createClient().also { current = it }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Synchronized
    internal fun trackCall(call: Call, job: Job?): DisposableHandle {
        if (retired) {
            call.cancel()
            error("书源请求层已退场")
        }
        calls.add(call)
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            error -> if (error != null) call.cancel()
        }
        return DisposableHandle {
            calls.remove(call)
            cancellation?.dispose()
        }
    }

    @Synchronized override fun close() {
        retired = true
        calls.toList().forEach { it.cancel() }
        calls.clear()
        val clients = (proxyClients.values.toList() + listOfNotNull(current)).distinct()
        proxyClients.clear()
        current = null
        clients.forEach { it.dispatcher.cancelAll() }
        clients.map { it.connectionPool }.distinct().forEach { it.evictAll() }
        clients.mapNotNull { it.cache }.distinct().forEach { runCatching { it.close() } }
        clients.map { it.dispatcher.executorService }.distinct().forEach { it.shutdownNow() }
    }
}

val okHttpClient: OkHttpClient get() = HttpRuntime.client()

private fun createClient(): OkHttpClient {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    //TODO：自定义缓存大小
    val cache = Cache(File(LegadoPlatform.cacheDir, "http_cache"), 100L * 1024L * 1024L)

    val builder = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        //.cookieJar(cookieJar = cookieJar)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(OkHttpExceptionInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, LegadoPlatform.userAgent)
            } else if (request.header(AppConst.UA_NAME) == "null") {
                builder.removeHeader(AppConst.UA_NAME)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val enableCookieJar = request.header(cookieJarHeader) != null

            if (enableCookieJar) {
                val requestBuilder = request.newBuilder()
                requestBuilder.removeHeader(cookieJarHeader)
                request = request.withSession { CookieManager.loadRequest(requestBuilder.build()) }
            }

            val networkResponse = chain.proceed(request)

            if (enableCookieJar) {
                try {
                    request.withSession { CookieManager.saveResponse(networkResponse) }
                } catch (error: Throwable) {
                    networkResponse.close()
                    throw error
                }
            }
            networkResponse
        }
    builder.addInterceptor(DecompressInterceptor)
    return builder.build()
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null): OkHttpClient {
    synchronized(HttpRuntime) {
    if (proxy.isNullOrBlank()) {
        return okHttpClient
    }
    HttpRuntime.proxyClients[proxy]?.let {
        return it
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""       //代理服务器验证用户名
    var password = ""       //代理服务器验证密码
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (host != "") {
        val builder = okHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        HttpRuntime.proxyClients[proxy] = proxyClient
        return proxyClient
    }
    return okHttpClient
}
}
