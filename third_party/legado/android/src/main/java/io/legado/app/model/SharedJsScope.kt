package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import io.legado.app.platform.LegadoPlatform
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object SharedJsScope {

    private val cacheFolder by lazy { File(LegadoPlatform.cacheDir, "shareJs") }
    private val aCache by lazy { ACache.get(cacheFolder) }

    private val scopeMap = LruCache<String, WeakReference<Scriptable>>(16)
    private val cacheLock = Any()
    private var generation = 0L

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): Scriptable? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val expectedGeneration = synchronized(cacheLock) { generation }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = aCache.getAsString(fileName)
                        if (js == null) {
                            js = runBlocking(coroutineContext ?: EmptyCoroutineContext) {
                                okHttpClient.newCallStrResponse {
                                    url(value)
                                }.body
                            }
                            if (js != null) {
                                synchronized(cacheLock) {
                                    checkGeneration(expectedGeneration)
                                    aCache.put(fileName, js)
                                }
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
            if (scope is ScriptableObject) {
                /**
                 * 阻止新全局增加（即函数内未用var的隐性全局变量创建）,会直接隐性创建失败,提示变量未定义
                 */
                scope.preventExtensions()
            }
            synchronized(cacheLock) {
                checkGeneration(expectedGeneration)
                scopeMap.put(key, WeakReference(scope))
            }
        }
        synchronized(cacheLock) { checkGeneration(expectedGeneration) }
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        synchronized(cacheLock) {
            generation++
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        aCache.remove(fileName)
                    }
                }
            }
            val key = MD5Utils.md5Encode(jsLib)
            scopeMap.remove(key)
        }
    }

    /** Invalidates in-flight publication as well as scopes; optionally removes downloaded JS. */
    fun clearRuntimeCache(clearDownloadedScripts: Boolean = false) {
        synchronized(cacheLock) {
            generation++
            scopeMap.evictAll()
            if (clearDownloadedScripts) aCache.clear()
        }
    }

    private fun checkGeneration(expected: Long) {
        if (generation != expected) throw CancellationException("书源脚本缓存已更新")
    }
}
