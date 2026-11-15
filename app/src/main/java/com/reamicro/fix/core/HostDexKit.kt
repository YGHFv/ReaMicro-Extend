package com.reamicro.fix.core

import android.app.Application
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.util.zip.ZipFile
import org.luckypray.dexkit.DexKitBridge

/** 加载本代模块的 DexKit native 库并打开宿主查询桥接。 */
object HostDexKit {
    private const val LOG_PREFIX = "ReaMicro dexkit"
    private const val LIB_ENTRY_ARM64 = "lib/arm64-v8a/libdexkit.so"
    private const val LIB_NAME = "libdexkit.so"
    private const val LIB_DIR_NAME = "reamicro_dexkit"
    private const val LIB_MAX_ATTEMPTS = 3

    @Volatile private var libraryLoaded = false
    @Volatile private var libraryAttempts = 0
    @Volatile private var bridgeFailure: Throwable? = null
    private val nativeGeneration = java.util.UUID.randomUUID().toString()

    internal fun openBridge(hostApkPath: String, hostDataDir: String?,
        hostApkPaths: List<String> = listOf(hostApkPath)): DexKitBridge? {
        if (!ensureLibrary(hostDataDir)) return null
        return runCatching {
            val startedAt = System.nanoTime()
            require(hostApkPaths.firstOrNull()?.let { File(it).absolutePath } == File(hostApkPath).absolutePath) {
                "Host APK class path does not start with base APK"
            }
            val bridge = if (hostApkPaths.size == 1) DexKitBridge.create(hostApkPath) else {
                // Pass only ApplicationInfo's explicit class path; a ClassLoader may also
                // contain module/shared-library DEX that would make semantic anchors ambiguous.
                val dexFiles = hostApkPaths.flatMapIndexed { index, path ->
                    ZipFile(path).use { zip ->
                        zip.hostDexEntries(requireDex = index == 0).map { entry ->
                            zip.getInputStream(entry).use { it.readBytes() }
                        }
                    }
                }.toTypedArray()
                DexKitBridge.create(dexFiles)
            }
            try {
                check(bridge.isValid && bridge.getDexNum() > 0) { "Host APK contains no readable DEX" }
                // Installation already runs on a background thread. Bound query fan-out
                // instead of competing with rendering and input on every CPU core.
                bridge.setThreadNum(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
            } catch (error: Throwable) {
                bridge.close()
                throw error
            }
            bridgeFailure = null
            HookRuntime.logAlways(
                "$LOG_PREFIX bridge ready dex=${bridge.getDexNum()} " +
                    "in ${(System.nanoTime() - startedAt) / 1_000_000}ms",
            )
            bridge
        }.getOrElse {
            bridgeFailure = it
            HookRuntime.logError("$LOG_PREFIX bridge create failed", it)
            null
        }
    }

    internal fun requireBridge(hostApkPath: String, hostDataDir: String?,
        hostApkPaths: List<String> = listOf(hostApkPath)): DexKitBridge =
        openBridge(hostApkPath, hostDataDir, hostApkPaths) ?: throw IllegalStateException(
            "DexKit 初始化失败：${bridgeFailure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "native library unavailable"}",
            bridgeFailure,
        )

    /**
     * DexKit 的 Java 层不自动加载 so；这里从模块 APK 抽出来再 System.load。
     *
     * 宿主 Application 在 onPackageReady 阶段还没就绪，取不到 codeCacheDir，
     * 于是优先用调用方给的宿主 dataDir 推导 code_cache；落到 /tmp 会因为不可写而失败。
     */
    private fun ensureLibrary(hostDataDir: String?): Boolean {
        if (libraryLoaded) return true
        if (libraryAttempts >= LIB_MAX_ATTEMPTS) return false
        synchronized(this) {
            if (libraryLoaded) return true
            if (libraryAttempts >= LIB_MAX_ATTEMPTS) return false
            libraryAttempts++
            val attempt = libraryAttempts
            val loaded = runCatching {
                val apk = moduleApkFile() ?: error("module apk unavailable")
                // ART 不允许不同 ClassLoader 加载同一路径的 so；各代际独立映射且不覆盖正在使用的文件。
                val target = File(libraryDir(hostDataDir), "$nativeGeneration/$LIB_NAME")
                HostNativeLibraryFile.extract(apk, LIB_ENTRY_ARM64, target)
                System.load(target.absolutePath)
                target.delete()
                target.parentFile?.delete()
                target
            }
            return loaded.fold(
                onSuccess = { file ->
                    libraryLoaded = true
                    HookRuntime.logAlways("$LOG_PREFIX native library loaded from ${file.absolutePath}")
                    true
                },
                onFailure = { error ->
                    bridgeFailure = error
                    HookRuntime.logError(
                        "$LOG_PREFIX native library load failed (attempt $attempt/$LIB_MAX_ATTEMPTS)",
                        error,
                    )
                    false
                },
            )
        }
    }

    private fun libraryDir(hostDataDir: String?): File {
        hostDataDir?.takeIf { it.isNotBlank() }?.let { dataDir ->
            val base = File(dataDir, "code_cache")
            if (base.isDirectory || base.mkdirs()) {
                return File(base, LIB_DIR_NAME).apply { mkdirs() }
            }
        }
        val context = hostApplication()
        val base = context?.codeCacheDir ?: context?.cacheDir
        if (base != null) {
            return File(base, LIB_DIR_NAME).apply { mkdirs() }
        }
        val fallback = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", LIB_DIR_NAME)
        fallback.mkdirs()
        return fallback
    }

    private fun hostApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Application
    }.getOrNull()

    private fun moduleApkFile(): File? {
        val path = InjectedModuleContext.sourceDirOrNull()?.takeIf { it.isNotBlank() }
        val file = path?.let(::File) ?: return null
        return file.takeIf { it.isFile }
    }
}
