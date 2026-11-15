package io.legado.app.help.webView

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.platform.LegadoPlatform
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.lang.ref.WeakReference
import kotlin.uuid.Uuid

/** Upstream WebJsExtensions dispatch/JS, with a cancellable WebView lifetime. */
@Suppress("unused")
class WebJsExtensions(
    private val source: BaseSource,
    webView: WebView,
    parentContext: CoroutineContext = EmptyCoroutineContext,
    private val sessionRunner: ((() -> Any?) -> Any?)? = null,
) : JsExtensions, AutoCloseable {
    private val webViewRef = WeakReference(webView)
    private val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]) + Dispatchers.IO)
    val analyzeRule by lazy { AnalyzeRule(null, source = source) }

    override fun getSource(): BaseSource = source
    override fun getTag(): String = source.getTag()
    override fun close() { scope.cancel(); webViewRef.clear() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> runBound(context: CoroutineContext = scope.coroutineContext, action: () -> T): T {
        context.ensureActive()
        val operation: () -> Any? = { runScriptWithContext(context) { action() } }
        return (if (sessionRunner == null) operation() else sessionRunner.invoke(operation)) as T
    }

    @JavascriptInterface fun put(key: String, value: String): String {
        runBound { source.put(key, value) }
        return value
    }
    @JavascriptInterface fun get(key: String): String = runBound { source.get(key) }
    @JavascriptInterface fun searchBook(key: String) = searchBook(key, null)
    @JavascriptInterface fun searchBook(key: String, searchScope: String?) =
        runBound { LegadoPlatform.adapter.sourceAction("searchBook", null, key, searchScope, source.getKey()) }
    @JavascriptInterface fun addBook(bookUrl: String) =
        runBound { LegadoPlatform.adapter.sourceAction("addBook", bookUrl, null, null, source.getKey()) }
    @JavascriptInterface fun showPhoto(url: String) =
        runBound { LegadoPlatform.adapter.sourceAction("photo", url, null, null, source.getKey()) }
    @JavascriptInterface fun upConfig(config: String) =
        runBound { LegadoPlatform.adapter.sourceAction("upConfig", config, null, null, source.getKey()) }
    @JavascriptInterface fun navigateToArticles() = navigateToArticles(null)
    @JavascriptInterface fun navigateToArticles(sortUrl: String?) =
        runBound { LegadoPlatform.adapter.sourceAction("sort", sortUrl, null, null, source.getKey()) }
    @JavascriptInterface @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) =
        runBound { LegadoPlatform.adapter.sourceAction(name, url, title, origin, source.getKey()) }

    @JavascriptInterface
    fun request(funName: String, jsParam: Array<String?>, id: String) {
        scope.launch {
            try {
                val data = runBound(coroutineContext) {
            val params = Array(6) { i -> jsParam.getOrNull(i) }
            val p0 = params[0]
            val p1 = params[1]
            val p2 = params[2]
            val p3 = params[3]
            val p4 = params[4]
            val p5 = params[5]
            when (funName) {
                "run" -> {
                    analyzeRule.setCoroutineContext(coroutineContext)
                        .evalJS(
                            p0 ?: throw NoStackTraceException("error null")
                        ).toString()
                }
                "ajaxAwait" -> {
                    ajax(
                        p0 ?: throw NoStackTraceException("error url null"),
                        p1?.toLongOrNull()
                    ).toString()
                }
                "connectAwait" -> {
                    connect(
                        p0 ?: throw NoStackTraceException("error url null"),
                        p1,
                        p2?.toIntOrNull()
                    )
                }
                "getAwait" -> {
                    get(
                        p0 ?: throw NoStackTraceException("error url null"),
                        p1 ?: throw NoStackTraceException("error header null"),
                        p2?.toIntOrNull()
                    )
                }
                "headAwait" -> {
                    head(
                        p0 ?: throw NoStackTraceException("error url null"),
                        p1 ?: throw NoStackTraceException("error header null"),
                        p2?.toIntOrNull()
                    )
                }
                "postAwait" -> {
                    post(
                        p0 ?: throw NoStackTraceException("error url null"),
                        p1 ?: throw NoStackTraceException("error body null"),
                        p2 ?: throw NoStackTraceException("error header null"),
                        p3?.toIntOrNull()
                    )
                }
                "webViewAwait" -> {
                    webView(
                        p0,
                        p1,
                        p2,
                        p3.toBoolean()
                    ).toString()
                }
                "webViewGetSourceAwait" -> {
                    webViewGetSource(
                        p0,
                        p1,
                        p2,
                        p3 ?: throw NoStackTraceException("error sourceRegex null"),
                        p4.toBoolean(),
                        p5?.toLongOrNull() ?: 0
                    ).toString()
                }
                "decryptStrAwait" -> {
                    createSymmetricCrypto(
                        p0 ?: throw NoStackTraceException("error transformation null"),
                        p1 ?: throw NoStackTraceException("error key null"),
                        p2
                    ).decryptStr(p3 ?: throw NoStackTraceException("error data null"))
                }
                "encryptBase64Await" -> {
                    createSymmetricCrypto(
                        p0 ?: throw NoStackTraceException("error transformation null"),
                        p1 ?: throw NoStackTraceException("error key null"),
                        p2
                    ).encryptBase64(p3 ?: throw NoStackTraceException("error data null"))
                }
                "encryptHexAwait" -> {
                    createSymmetricCrypto(
                        p0 ?: throw NoStackTraceException("error transformation null"),
                        p1 ?: throw NoStackTraceException("error key null"),
                        p2
                    ).encryptHex(p3 ?: throw NoStackTraceException("error data null"))
                }
                "createSignHexAwait" -> {
                    createSign(p0 ?: throw NoStackTraceException("error algorithm null"))
                        .setPublicKey(p1 ?: throw NoStackTraceException("error publicKey null"))
                        .setPrivateKey(p2 ?: throw NoStackTraceException("error privateKey null"))
                        .signHex(p3 ?: throw NoStackTraceException("error data null"))
                }
                "downloadFileAwait" -> {
                    downloadFile(p0 ?: throw NoStackTraceException("error url null"))
                }
                "readTxtFileAwait" -> {
                    readTxtFile(p0 ?: throw NoStackTraceException("error path null"))
                }
                "importScriptAwait" -> {
                    importScript(p0 ?: throw NoStackTraceException("error path null"))
                }
                "getStringAwait" -> {
                    analyzeRule.setCoroutineContext(coroutineContext)
                        .getString(p0, p1)
                }
                else -> throw NoStackTraceException("error funName")
            }
                }
                CacheManager.putMemory(id, data)
                withContext(Dispatchers.Main) {
                    webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult('$id', true);", null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CacheManager.putMemory(id, error.localizedMessage ?: "err")
                withContext(Dispatchers.Main) {
                    webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult('$id', false);", null)
                }
            }
        }
    }

    @JavascriptInterface
    fun toast(msg: String?) {
        runBound { super.toast(msg) }
    }

    @JavascriptInterface
    fun longToast(msg: String?) {
        runBound { super.longToast(msg) }
    }

    @JavascriptInterface
    fun log(msg: String?): String {
        return runBound { super.log(msg).toString() }
    }

    @JavascriptInterface
    @JvmOverloads
    fun ajax(url: String, callTimeout: Int = 9000): String? {
        return runBound { super.ajax(url, callTimeout.toLong()) }
    }

    @JavascriptInterface
    @JvmOverloads
    fun connect(urlStr: String, header: String? = null, callTimeout: Int? = 9000): String {
        return runBound { super.connect(urlStr, header, callTimeout?.toLong()).toString() }
    }

    @JavascriptInterface
    @JvmOverloads
    fun get(urlStr: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return runBound { super.get(urlStr, headers, timeout).body() }
    }

    @JavascriptInterface
    @JvmOverloads
    fun post(urlStr: String, body: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return runBound { super.post(urlStr, body, headers, timeout).body() }
    }

    @JavascriptInterface
    @JvmOverloads
    fun head(urlStr: String, header: String, timeout: Int? = 9000): String {
        val headers = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return runBound { GSON.toJson(super.head(urlStr, headers, timeout).headers()) }
    }

    @JavascriptInterface
    fun getStringList(rule: String?, mContent: String? = null, isUrl: Boolean = false): List<String>? {
        return runBound { AnalyzeRule(null, source = source).setCoroutineContext(scope.coroutineContext).getStringList(rule, mContent, isUrl) }
    }

    @JavascriptInterface
    fun getString(ruleStr: String?, mContent: String? = null, isUrl: Boolean = false): String {
        return runBound { AnalyzeRule(null, source = source).setCoroutineContext(scope.coroutineContext).getString(ruleStr, mContent, isUrl) }
    }

    companion object {
        private fun getRandomLetter(): Char {
            val letters = "abcdefghijklmnopqrstuvwxyz"
            return letters.random()
        }

        val uuid by lazy {
            Uuid.random().toString().replace('-', getRandomLetter()).chunked(6)
        }
        val uuid2 by lazy {
            Uuid.random().toString().replace('-', getRandomLetter()).chunked(6)
        }
        val nameUrl by lazy { "https://" + uuid[0] + ".com/" + uuid2[0] + ".js" }
        val nameJava by lazy { getRandomLetter() + uuid[1] + uuid2[1] }
        val nameCache by lazy { getRandomLetter() + uuid[2] + uuid2[2] }
        val nameSource by lazy { getRandomLetter() + uuid[3] + uuid2[3] }
        val nameBasic by lazy { getRandomLetter() + uuid[4] + uuid2[4] }
        val JSBridgeResult by lazy { getRandomLetter() + uuid[5] + uuid2[5] }
        val JS_URL by lazy {
            "<script src=\"$nameUrl\"></script>"
        }

        val getInjectionString = "try{var cache=$nameCache,source=$nameSource,java=$nameJava;}catch(e){}"

        val JS_INJECTION by lazy {
            """
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            const params = a => a.map(p => p != null && typeof p.toString === 'function' ? p.toString() : null);
            const JSBridgeCallbacks = {};
            const java = window.$nameJava;
            delete window.$nameJava;
            const source = window.$nameSource;
            delete window.$nameSource;
            const cache = window.$nameCache;
            delete window.$nameCache;
            function run(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId("run");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("run", [String(jsCode)], id);
                });
            };
            function ajaxAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("ajaxAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("ajaxAwait", params(args), id);
                });
            };
            function connectAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("connectAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("connectAwait", params(args), id);
                });
            };
            function getAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("getAwait", params(args), id);
                });
            };
            function headAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("headAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("headAwait", params(args), id);
                });
            };
            function postAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("postAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("postAwait", params(args), id);
                });
            };
            function webViewAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("webViewAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("webViewAwait", params(args), id);
                });
            };
            function webViewGetSourceAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("webViewGetSourceAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("webViewGetSourceAwait", params(args), id);
                });
            }
            function decryptStrAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("decryptStrAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("decryptStrAwait", params(args), id);
                });
            };
            function encryptBase64Await(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptBase64Await");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("encryptBase64Await", params(args), id);
                });
            };
            function encryptHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptHexAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("encryptHexAwait", params(args), id);
                });
            };
            function createSignHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("createSignHexAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("createSignHexAwait", params(args), id);
                });
            };
            function downloadFileAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("downloadFileAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("downloadFileAwait", [String(url)], id);
                });
            };
            function readTxtFileAwait(path) {
                return new Promise((resolve, reject) => {
                    const id = requestId("readTxtFileAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("readTxtFileAwait", [String(path)], id);
                });
            };
            function importScriptAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("importScriptAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("importScriptAwait", [String(url)], id);
                });
            };
            function getStringAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getStringAwait");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("getStringAwait", params(args), id);
                });
            };
            window.$JSBridgeResult = function(id, success) {
                const callBack = JSBridgeCallbacks[id];
                if (callBack) {
                    const result = cache.getFromMemory(id);
                    if (success) {
                        callBack.resolve(result);
                    } else {
                        callBack.reject(result);
                    }
                    delete JSBridgeCallbacks[id];
                }
            };""".trimIndent()
        }

        val JS_INJECTION2 by lazy {
            """
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            const JSBridgeCallbacks = {};
            const java = window.$nameJava;
            delete window.$nameJava;
            const cache = window.$nameCache;
            delete window.$nameCache;
            function run(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId("run");
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request("run", [String(jsCode)], id);
                });
            };
            window.$JSBridgeResult = function(id, success) {
                const callBack = JSBridgeCallbacks[id];
                if (callBack) {
                    const result = cache.getFromMemory(id);
                    if (success) {
                        callBack.resolve(result);
                    } else {
                        callBack.reject(result);
                    }
                    delete JSBridgeCallbacks[id];
                }
            };""".trimIndent()
        }

        val basicJs by lazy {
            """
            (function() {
            if (screen.orientation) {
                screen.orientation.lock = function(orientation) {
                    return new Promise((resolve, reject) => {
                        window.$nameBasic?.lockOrientation(orientation);
                        resolve()
                    });
                };
                screen.orientation.unlock = function() {
                    return new Promise((resolve, reject) => {
                        window.$nameBasic?.lockOrientation('unlock');
                        resolve()
                    });
                };
            };
            window.close = function() {
                window.$nameBasic?.onCloseRequested();
            };
            })();""".trimIndent()
        }
    }
}
