package io.legado.app.help

import android.webkit.JavascriptInterface
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.Keep
import androidx.collection.LruCache
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.platform.LegadoPlatform
import io.legado.app.utils.ACache
import io.legado.app.utils.memorySize
import java.io.File

private val queryTTFMap = LruCache<String, QueryTTF>(4)

/**
 * 最多只缓存50M的数据,防止OOM
 */
private val memoryLruCache = object : LruCache<String, Any>(1024 * 1024 * 50) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.toString().memorySize()
    }

}

object AppCacheManager {

    fun clearMemory() {
        queryTTFMap.evictAll()
        memoryLruCache.evictAll()
    }

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    fun clearSourceVariables() {
        memoryLruCache.snapshot().keys.forEach {
            if (it.startsWith("v_")
                || it.startsWith("userInfo_")
                || it.startsWith("loginHeader_")
                || it.startsWith("sourceVariable_")
            ) {
                memoryLruCache.remove(it)
            }
        }
    }

}


@Keep
@Suppress("unused")
object CacheManager {
    // Same exact-key/value/deadline table semantics as the upstream Room cache.
    // ACache uses String.hashCode file names, so it cannot replace this table.
    private var database: SQLiteDatabase? = null
    private var databasePath: String? = null

    @Synchronized
    private fun database(): SQLiteDatabase {
        val file = File(LegadoPlatform.filesDir, "rule-cache.db")
        if (databasePath != file.absolutePath) {
            database?.close()
            database = null
        }
        return database ?: SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.execSQL("CREATE TABLE IF NOT EXISTS cache (key TEXT NOT NULL PRIMARY KEY, value TEXT, deadline INTEGER NOT NULL)")
            databasePath = file.absolutePath
            database = it
        }
    }

    @Synchronized
    fun close() {
        database?.close()
        database = null
        databasePath = null
    }

    @Synchronized
    private fun getDisk(key: String): String? = database().query(
        "cache", arrayOf("value", "deadline"), "key = ?", arrayOf(key),
        null, null, null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val deadline = cursor.getLong(1)
        if (deadline == 0L || deadline > System.currentTimeMillis()) cursor.getString(0) else null
    }

    @Synchronized
    private fun putDisk(key: String, value: String, saveTime: Int) {
        val deadline = if (saveTime == 0) 0L else System.currentTimeMillis() + saveTime * 1000L
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("deadline", deadline)
        }
        check(database().insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "无法保存书源缓存"
        }
    }

    @Synchronized
    private fun deleteDisk(key: String) { database().delete("cache", "key = ?", arrayOf(key)) }

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        when (value) {
            is ByteArray -> ACache.get().put(key, value, saveTime)
            else -> {
                val valueStr = value.toString()
                putMemory(key, valueStr)
                putDisk(key, valueStr, saveTime)
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(key, value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[key]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }

    fun get(key: String): String? {
        getFromMemory(key)?.let {
            if (it is String) return it
        }
        return getDisk(key)?.also { putMemory(key, it) }
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) {
            return get(key)
        }
        return getDisk(key)
    }

    fun getInt(key: String): Int? {
        getFromMemory(key)?.let {
            if (it is Int) return it
        }
        return get(key, true)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        getFromMemory(key)?.let {
            if (it is Long) return it
        }
        return get(key, true)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        getFromMemory(key)?.let {
            if (it is Double) return it
        }
        return get(key, true)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        getFromMemory(key)?.let {
            if (it is Float) return it
        }
        return get(key, true)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return ACache.get().getAsBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        ACache.get().put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return ACache.get().getAsString(key)
    }

    fun delete(key: String) {
        deleteDisk(key)
        deleteMemory(key)
        ACache.get().remove(key)
    }
}

@Keep
@Suppress("unused")
object WebCacheManager {
    @JvmOverloads
    @JavascriptInterface
    fun put(key: String, value: String, saveTime: Int = 0) {
        CacheManager.put(key, value, saveTime)
    }

    @JavascriptInterface
    fun putMemory(key: String, value: String) {
        memoryLruCache.put(key, value)
    }

    @JavascriptInterface
    fun getFromMemory(key: String): String? {
        return memoryLruCache[key] as? String
    }

    @JavascriptInterface
    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }

    @JavascriptInterface
    fun get(key: String): String? {
        return CacheManager.get(key)
    }

    @JavascriptInterface
    fun get(key: String, onlyDisk: Boolean): String? {
        return CacheManager.get(key, onlyDisk)
    }

    @JvmOverloads
    @JavascriptInterface
    fun putFile(key: String, value: String, saveTime: Int = 0) {
        CacheManager.putFile(key, value, saveTime)
    }

    @JavascriptInterface
    fun getFile(key: String): String? {
        return CacheManager.getFile(key)
    }

    @JavascriptInterface
    fun delete(key: String) {
        CacheManager.delete(key)
    }
}
