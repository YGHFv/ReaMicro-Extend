package com.reamicro.fix.core

import android.os.Build
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.io.RandomAccessFile
import org.luckypray.dexkit.DexKitBridge

/** Queries run during installation/rescan; callbacks use already bound members. */
internal object HostQuerySession {
    private const val SCHEMA = "feature-queries-3"
    private val lock = Any()
    private val local = ThreadLocal<Session?>()
    private var active: Session? = null
    private class Inputs(val loader: ClassLoader, val apk: String, val apks: List<String>) {
        val identity = HostDexIdentity.Installed(apks.map(::File))
    }
    private var inputs: Inputs? = null

    /** Called with the package lifecycle's ApplicationInfo before opening any query session. */
    fun configure(loader: ClassLoader, apk: String, splitApks: List<String> = emptyList()) = synchronized(lock) {
        val paths = (listOf(apk) + splitApks).map { File(it).absolutePath }.distinct()
        require(apk.isNotBlank() && paths.all { File(it).isFile }) { "Host APK class path unavailable" }
        active?.let { session ->
            require(session.loader === loader && session.apks == paths) { "Cannot replace an active host query class path" }
        }
        if (inputs?.let { it.loader === loader && it.apks == paths } != true) {
            inputs = Inputs(loader, paths.first(), paths)
        }
    }
    internal fun dexIndex(apk: String): HostDexIndex? =
        local.get()?.takeIf { it.apk == apk }?.dexIndex()
    val registeredKeys: Set<String> get() = HostQueryRegistry.keys

    data class Result(val values: Map<String, String>, val cached: Boolean)

    class Session internal constructor(
        val loader: ClassLoader,
        internal val apk: String,
        internal val dataDir: String,
        private val cache: HostQueryCache?,
        internal val apks: List<String> = listOf(apk),
    ) : AutoCloseable {
        private data class Resolved(val values: Map<String, String>, val cached: Boolean)
        private var bridge: DexKitBridge? = null
        private var bridgeFailure: IllegalStateException? = null
        private val descriptorMembers = lazy(LazyThreadSafetyMode.NONE) { HostMembers() }
        private var index: HostDexIndex? = null
        private var closed = false
        private val failed = linkedMapOf<String, Throwable>()
        private val resolved = HashMap<String, Resolved>()
        private var freshQueries = false
        internal fun failures(): Map<String, Throwable> = failed.toMap()
        internal fun hasFreshQueries(): Boolean = freshQueries
        internal fun recordFailure(key: String, error: Throwable) {
            require(key in registeredKeys) { "Unregistered query key: $key" }
            if (failed[key] === error) return
            failed[key] = error
            resolved.remove(key)
            cache?.remove(key)
        }
        internal fun collect(
            keys: Iterable<String>,
            resolve: (String) -> Map<String, String>,
        ): Map<String, Map<String, String>> = buildMap {
            keys.forEach { key ->
                try {
                    put(key, resolve(key))
                } catch (error: Throwable) {
                    recordFailure(key, error)
                    HookRuntime.logError("ReaMicro $key unavailable", error)
                }
            }
        }
        internal fun dexIndex(): HostDexIndex {
            check(!closed) { "Query session has been released" }
            return index ?: HostDexIndex(apk, apks).also { index = it }
        }
        fun query(key: String, force: Boolean = false,
            validate: (Map<String, String>) -> Unit = {},
            find: (DexKitBridge) -> Map<String, String>,
        ): Result {
            check(!closed) { "Query session has been released" }
            require(key in registeredKeys) { "Unregistered query key: $key" }
            if (!force) {
                failed[key]?.let { throw it }
                // Keep the installation's working set even when the disk cache is
                // unavailable/busy. Bootstrap and final coverage must not scan twice.
                resolved[key]?.let { saved ->
                    val values = saved.values.toMap()
                    runCatching { validate(values) }.onSuccess {
                        // Restorers also publish bound reflective members.
                        return Result(values, saved.cached)
                    }.onFailure {
                        resolved.remove(key)
                        cache?.remove(key)
                        HookRuntime.logError("ReaMicro session binding rejected: $key", it)
                    }
                }
                cache?.get(key)?.let { saved ->
                    runCatching { validate(saved) }.onSuccess {
                        failed.remove(key)
                        resolved[key] = Resolved(saved.toMap(), true)
                        return Result(saved, true)
                    }.onFailure {
                        cache.remove(key)
                        HookRuntime.logError("ReaMicro query cache rejected: $key", it)
                    }
                }
            }
            try {
                val native = requireBridge()
                freshQueries = true
                val values = find(native).toMap()
                validate(values)
                failed.remove(key)
                resolved[key] = Resolved(values.toMap(), false)
                runCatching { cache?.put(key, values) }.onFailure {
                    HookRuntime.logError("ReaMicro query cache store failed: $key", it)
                }
                return Result(values, false)
            } catch (error: Throwable) {
                recordFailure(key, error)
                throw error
            }
        }
        private fun requireBridge(): DexKitBridge {
            bridgeFailure?.let { throw it }
            return bridge ?: try {
                HostDexKit.requireBridge(apk, dataDir, apks).also { bridge = it }
            } catch (error: IllegalStateException) {
                bridgeFailure = error
                throw error
            }
        }

        internal fun validateDescriptors(values: Map<String, String>) {
            val members = descriptorMembers.value
            values.forEach { (key, descriptor) ->
                when {
                    key.startsWith("class:") -> members.describedClass(descriptor, loader)
                    key.startsWith("method:") -> members.describedMethod(descriptor, loader)
                    key.startsWith("constructor:") -> members.describedConstructor(descriptor, loader)
                    key.startsWith("field:") -> members.describedField(descriptor, loader)
                }
            }
        }
        fun cached(key: String): Map<String, String>? {
            check(!closed) { "Query session has been released" }
            return if (key in failed) null else resolved[key]?.values?.toMap() ?: cache?.get(key)
        }
        override fun close() {
            if (closed) return
            closed = true
            try { bridge?.close() } finally {
                bridge = null
                bridgeFailure = null
                if (descriptorMembers.isInitialized()) descriptorMembers.value.retire()
                index = null
                resolved.clear()
                failed.clear()
                // A bootstrap or forced rescan owns one session. Persist every completed,
                // validated group once, including when a later group fails.
                runCatching { cache?.flush() }.onFailure {
                    HookRuntime.logError("ReaMicro query cache write failed", it)
                }
            }
        }
    }

    fun <T> use(loader: ClassLoader, apk: String, dataDir: String, block: (Session) -> T): T = synchronized(lock) {
        active?.let {
            require(it.loader === loader && it.apk == apk && it.dataDir == dataDir) { "Nested query session belongs to another host" }
            return@synchronized block(it)
        }
        val configured = inputs?.takeIf { it.loader === loader && it.apk == File(apk).absolutePath }
        val apks = configured?.apks ?: listOf(apk)
        val directory = File(dataDir, "files/reamicro_dexkit").apply { mkdirs() }
        // One lock and cache per host app, shared safely by its processes.
        val fileLock = runCatching { RandomAccessFile(File(directory, "queries.lock"), "rw") }.onFailure {
            HookRuntime.logError("ReaMicro cache lock unavailable; using uncached queries", it)
        }.getOrNull()
        val held = runCatching { fileLock?.channel?.tryLock() }.onFailure {
            HookRuntime.logError("ReaMicro cache lock failed; using uncached queries", it)
        }.getOrNull()
        if (held == null && fileLock != null) {
            HookRuntime.logAlways("ReaMicro query cache busy; querying without waiting for another process")
        }
        try {
            val cache = if (held == null) null else {
                // An uncached process opens the DEX through DexKit directly; hashing it
                // beforehand serves no purpose when no cache lock is available.
                val generation = "$SCHEMA:${BuildConfig.BUILD_TIME}:${Build.FINGERPRINT}:${Build.VERSION.SDK_INT}"
                val key = configured?.identity?.key(generation) ?: HostDexIdentity.key(apks.map(::File), generation)
                val file = File(directory, "queries.bin")
                runCatching { HostQueryCache(file, key) }.getOrElse {
                    HookRuntime.logError("ReaMicro query cache unreadable; rebuilding", it)
                    if (!file.exists() || file.delete()) runCatching { HostQueryCache(file, key) }.getOrNull() else null
                }
            }
            cache?.retain(registeredKeys)
            Session(loader, apk, dataDir, cache, apks).use { session ->
                active = session
                local.set(session)
                try { block(session) } finally { local.remove(); active = null }
            }
        } finally {
            runCatching { held?.release() }
            runCatching { fileLock?.close() }
        }
    }

}
