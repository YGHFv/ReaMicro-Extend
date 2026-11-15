package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.core.HostEpubServicesBindings
import com.reamicro.fix.epub.editor.AccountBookImageFiles
import com.reamicro.fix.reload.ModuleResources

/** Local file fetchers read the book file directly; only their matching memory entries need eviction. */
internal object HostNativeCoverCache {
    private val dimensions = Regex("\\[\\d+,\\d+](?:@(?:null|-?\\d+)#-?\\d+)?")
    private val modified = Regex("(?:null|-?\\d+)")

    @JvmOverloads
    fun invalidate(activity: Activity, uuid: String, cover: String, checkCurrent: () -> Unit = {}): Int {
        val lease = ModuleResources.lease() ?: error("模块正在更新，请重新打开检查")
        try {
            val identity = AccountHostRuntime.storageSnapshot(activity) ?: error("当前账号存储不可用")
            fun current() {
                check(ModuleResources.accepting && !Thread.currentThread().isInterrupted &&
                    !activity.isFinishing && !activity.isDestroyed &&
                    AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号或图书页面已变化" }
                checkCurrent()
            }
            current()
            val contract = requireNotNull(HostEpubServicesBindings.metadata) { "宿主封面缓存接口尚未解析" }
            check(contract.type("coverCache.loader").classLoader === activity.classLoader)
            val directory = AccountBookImageFiles.directory(activity.filesDir, identity.accountId, uuid)
            val path = AccountBookImageFiles.child(directory, cover).absolutePath
            val loader = requireNotNull(contract.method("coverCache.provider").invoke(null, activity))
            check(contract.type("coverCache.loader").isInstance(loader))
            current()
            val memory = contract.method("coverCache.memory").invoke(loader) ?: return 0
            return AccountHostRuntime.withCurrentStorage(identity, activity) {
                invalidateMemory(memory, uuid, cover, path, ::current)
            }
        } finally {
            lease.close()
        }
    }

    fun matchesKey(key: String, uuid: String, cover: String, file: String): Boolean {
        val prefix = "#$uuid/$cover"
        if (key.startsWith(prefix) && dimensions.matches(key.removePrefix(prefix))) return true
        val local = "file://$file"
        return key == local || key.startsWith("$local-") && modified.matches(key.removePrefix("$local-"))
    }

    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun invalidateMemory(memory: Any, uuid: String, cover: String, file: String, checkCurrent: () -> Unit = {}): Int {
        require(AccountBookImageFiles.validUuid(uuid) && cover.isNotBlank() &&
            !cover.startsWith('/') && cover.none { it == '\\' || it == ':' || it == '\u0000' } &&
            cover.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        require(file.startsWith('/'))
        val contract = requireNotNull(HostEpubServicesBindings.metadata) { "宿主封面缓存接口尚未解析" }
        check(contract.type("coverCache.memory").isInstance(memory)) { "宿主封面缓存对象已变化" }
        val lock = contract.required("coverCache.memory.lock", memory)
        synchronized(lock) {
            checkCurrent()
            val strong = contract.required("coverCache.memory.strong", memory)
            val weak = contract.required("coverCache.memory.weak", memory)
            val lru = contract.required("coverCache.strong.lru", strong)
            check(contract.type("coverCache.lru").isInstance(lru))
            val strongEntries = contract.required("coverCache.lru.entries", lru) as? LinkedHashMap<Any, Any?>
                ?: error("宿主封面强缓存结构已变化")
            val weakEntries = contract.required("coverCache.weak.entries", weak) as? LinkedHashMap<Any, Any?>
                ?: error("宿主封面弱缓存结构已变化")
            val keys = (strongEntries.keys + weakEntries.keys).toSet().filter { key ->
                check(contract.type("coverCache.key").isInstance(key)) { "宿主封面缓存键已变化" }
                matchesKey(contract.text("coverCache.key.value", key), uuid, cover, file)
            }
            if (keys.isEmpty()) return 0
            checkCurrent()
            keys.forEach { key -> strongEntries.remove(key); weakEntries.remove(key) }
            contract.field("coverCache.lru.size").setLong(lru, -1L)
            val size = (contract.method("coverCache.size").invoke(lru) as Number).toLong()
            check(size >= 0L) { "宿主封面缓存大小尚未确认" }
            return keys.size
        }
    }
}
