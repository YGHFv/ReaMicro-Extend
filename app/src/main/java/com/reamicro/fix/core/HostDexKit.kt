package com.reamicro.fix.core

import android.app.Application
import android.content.Context
import com.reamicro.fix.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData

/**
 * 运行期 DEX 结构校验（DexKit）。
 *
 * 背景：宿主每次重新混淆都会复用同一套短名池但重新分配名字，
 * 旧 profile 的「逻辑名 -> 混淆名」会整体失效，而且指纹（包名 + 体积区间）
 * 往往仍然命中，于是模块会把 hook 装到毫不相干的类上。
 *
 * 这里用 DexKit 直接读宿主 dex 的实时元数据，把每个映射类的**结构指纹**
 * 与模块内置的期望指纹比对：指纹一致才认为该 profile 仍适用于当前 dex。
 * 不一致时上层会退化为 identity（逻辑名原样返回），宁可少 hook 也不乱 hook。
 */
object HostDexKit {
    private const val LOG_PREFIX = "ReaMicro dexkit"
    private const val SHAPE_ASSET_DIR = "host-profiles"
    private const val LIB_ENTRY_ARM64 = "lib/arm64-v8a/libdexkit.so"
    private const val LIB_NAME = "libdexkit.so"
    private const val LIB_DIR_NAME = "reamicro_dexkit"
    private const val PREFS_NAME = "reamicro_fix_hostdex"
    private const val VERDICT_PREFIX = "verdict_v1_"

    /** 按签名反查到的宿主类名缓存（进程内有效，避免重复打开 DexKit） */
    private val methodOwnerCache = HashMap<String, String?>()

    private val reference = Regex("L[^;]+;")
    private val stablePrefixes = listOf(
        "Ljava/",
        "Landroid/",
        "Ljavax/",
        "Ldalvik/",
        "Lkotlin/jvm/functions/Function",
    )

    data class Verdict(
        val profileId: String,
        val total: Int,
        val ok: Int,
        val failed: Set<String>,
        val cached: Boolean,
        val durationMs: Long,
        /** 前几个指纹不一致的样本，供日志定位（format: logical|actual|expect|got）。 */
        val samples: List<String> = emptyList(),
    ) {
        val ratio: Double get() = if (total <= 0) 0.0 else ok.toDouble() / total
    }

    private const val SAMPLE_LIMIT = 3

    @Volatile private var libraryLoaded = false
    @Volatile private var libraryAttempts = 0
    private const val LIB_MAX_ATTEMPTS = 3

    /**
     * 校验一个候选 profile 是否仍然匹配当前宿主 dex。
     *
     * @param hostApkPath 宿主 base APK 路径（DexKit 直接读它的 dex）
     * @param profileId profile 标识，用于匹配内置指纹资产
     * @param classes 逻辑类名 -> 该 profile 声明的混淆类名
     * @param apkSize 宿主体积，用于缓存键
     * @param hostDataDir 宿主 dataDir；onPackageReady 阶段拿不到 Context，用它推导 code_cache
     */
    fun verify(
        hostApkPath: String?,
        profileId: String,
        classes: Map<String, String>,
        apkSize: Long,
        hostDataDir: String? = null,
    ): Verdict? {
        val expected = bundledShapes()[profileId] ?: return null
        if (classes.isEmpty()) return null
        // 缓存键必须带上 profile 内容指纹：只按 APK 体积/profileId 缓存的话，
        // 换了映射（新增/修正类）后仍会复用旧结论，新增的类就绕过了结构校验。
        val cacheKey = "$hostApkPath#$apkSize#$profileId#${classes.size}#${classFingerprint(classes)}"
        cachedVerdict(profileId, cacheKey)?.let { return it }
        if (hostApkPath.isNullOrBlank()) return null
        val bridge = openBridge(hostApkPath, hostDataDir) ?: return null
        val startedAt = System.nanoTime()
        return try {
            var ok = 0
            val failed = LinkedHashSet<String>()
            val samples = ArrayList<String>()
            var total = 0
            for ((logical, actual) in classes) {
                val want = expected[logical] ?: continue
                total++
                val data = runCatching { bridge.getClassData(actual) }.getOrNull()
                val got = data?.let(::shapeOf)
                if (got != null && got == want) {
                    ok++
                } else {
                    failed += logical
                    if (samples.size < SAMPLE_LIMIT) {
                        samples += "$logical|$actual|${truncate(want)}|${truncate(got ?: "<no-class-data>")}"
                    }
                }
            }
            val verdict = Verdict(
                profileId = profileId,
                total = total,
                ok = ok,
                failed = failed,
                cached = false,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                samples = samples,
            )
            storeVerdict(profileId, cacheKey, verdict)
            verdict
        } catch (error: Throwable) {
            XposedBridge.logError("$LOG_PREFIX verify failed for $profileId", error)
            null
        } finally {
            runCatching { bridge.close() }
        }
    }

    private fun truncate(value: String): String =
        if (value.length <= 260) value else value.take(260) + "...(len=${value.length})"

    /**
     * 按「返回类型 + 形参类型」在宿主 dex 中反查静态方法所属的类名。
     *
     * R8 会把逻辑类合并进无关类（例如 OPF 解析器被并入 Compose 类），这类符号
     * 无法通过 profile 还原，只能运行时按签名定位。找不到返回 null。
     */
    fun findStaticMethodOwner(
        returnDescriptor: String,
        paramDescriptors: List<String>,
        cacheKey: String,
    ): String? {
        synchronized(methodOwnerCache) {
            if (methodOwnerCache.containsKey(cacheKey)) return methodOwnerCache[cacheKey]
        }
        val application = hostApplication() ?: return null
        val apkPath = runCatching { application.applicationInfo.sourceDir }.getOrNull()
        val dataDir = runCatching { application.applicationInfo.dataDir }.getOrNull()
        val owner = if (apkPath.isNullOrBlank()) {
            null
        } else {
            val bridge = openBridge(apkPath, dataDir)
            if (bridge == null) {
                null
            } else {
                try {
                    val query = FindMethod.create().matcher(
                        MethodMatcher.create()
                            .returnType(returnDescriptor)
                            .paramTypes(paramDescriptors),
                    )
                    bridge.findMethod(query)
                        .asSequence()
                        .firstOrNull { Modifier.isStatic(it.modifiers) }
                        ?.declaredClassName
                        ?.takeIf { it.isNotBlank() }
                } catch (error: Throwable) {
                    XposedBridge.logError("$LOG_PREFIX find method owner failed for $cacheKey", error)
                    null
                } finally {
                    runCatching { bridge.close() }
                }
            }
        }
        if (owner != null) {
            XposedBridge.logAlways("$LOG_PREFIX method owner resolved $cacheKey -> $owner")
            synchronized(methodOwnerCache) { methodOwnerCache[cacheKey] = owner }
        }
        return owner
    }

    /** 逻辑名->混淆名的顺序无关指纹，用于让校验缓存随 profile 内容失效。 */
    private fun classFingerprint(classes: Map<String, String>): Int {
        var hash = 17
        for ((logical, actual) in classes.entries.sortedBy { it.key }) {
            hash = hash * 31 + logical.hashCode()
            hash = hash * 31 + actual.hashCode()
        }
        return hash
    }

    /** 内置指纹资产：assets/host-profiles/<profileId>.shapes。 */
    fun bundledShapes(): Map<String, Map<String, String>> {
        val apk = moduleApkFile() ?: return emptyMap()
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("assets/$SHAPE_ASSET_DIR/") }
                    .filter { it.name.endsWith(".shapes") }
                    .associate { entry ->
                        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        parseShapes(text)
                    }
            }
        }.getOrElse {
            XposedBridge.logError("$LOG_PREFIX read shapes failed", it)
            emptyMap()
        }
    }

    private fun parseShapes(text: String): Pair<String, Map<String, String>> {
        var profileId = ""
        val shapes = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            when {
                line.startsWith("profile=") -> profileId = line.removePrefix("profile=").trim()
                line.startsWith("shape=") -> {
                    val body = line.removePrefix("shape=")
                    val logical = body.substringBefore('|')
                    val shape = body.substringAfter('|')
                    if (logical.isNotEmpty()) shapes[logical] = shape
                }
            }
        }
        return profileId.ifBlank { "unknown" } to shapes
    }

    /**
     * 与 tools/build_profile_shapes.py 完全一致的指纹规则：
     * 去掉空白，并把随构建改名的引用类型折叠成 `L*`。
     */
    private fun shapeOf(data: ClassData): String {
        val superClass = data.superClass?.descriptor?.let(::normalize) ?: ""
        val interfaces = data.interfaces.map { normalize(it.descriptor) }.sorted()
        val methods = data.methods.map { normalize(it.descriptor) }.sorted()
        val fields = data.fields.map { normalize(it.descriptor) }.sorted()
        return listOf(
            superClass,
            interfaces.joinToString(","),
            methods.joinToString(","),
            fields.joinToString(","),
        ).joinToString("|")
    }

    private fun normalize(descriptor: String): String {
        val compact = descriptor.filterNot { it.isWhitespace() }
        return reference.replace(compact) { match ->
            if (stablePrefixes.any { match.value.startsWith(it) }) match.value else "L*"
        }
    }

    internal fun openBridge(hostApkPath: String, hostDataDir: String?): DexKitBridge? {
        if (!ensureLibrary(hostDataDir)) return null
        return runCatching {
            val startedAt = System.nanoTime()
            val bridge = DexKitBridge.create(hostApkPath)
            XposedBridge.logAlways(
                "$LOG_PREFIX bridge ready dex=${bridge.getDexNum()} " +
                    "in ${(System.nanoTime() - startedAt) / 1_000_000}ms",
            )
            bridge
        }.getOrElse {
            XposedBridge.logError("$LOG_PREFIX bridge create failed", it)
            null
        }
    }

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
                val target = File(libraryDir(hostDataDir), LIB_NAME)
                extractLibrary(apk, target)
                System.load(target.absolutePath)
                target
            }
            return loaded.fold(
                onSuccess = { file ->
                    libraryLoaded = true
                    XposedBridge.logAlways("$LOG_PREFIX native library loaded from ${file.absolutePath}")
                    true
                },
                onFailure = { error ->
                    XposedBridge.logError(
                        "$LOG_PREFIX native library load failed (attempt $attempt/$LIB_MAX_ATTEMPTS)",
                        error,
                    )
                    false
                },
            )
        }
    }

    private fun extractLibrary(apk: File, target: File): File {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(LIB_ENTRY_ARM64)
                ?: error("module apk missing $LIB_ENTRY_ARM64")
            val expectedSize = entry.size
            if (target.isFile && target.length() == expectedSize && expectedSize > 0) return target
            target.parentFile?.mkdirs()
            val temp = File(target.absolutePath + ".tmp")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("cannot install $LIB_NAME")
            }
            return target
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

    private fun cachedVerdict(profileId: String, cacheKey: String): Verdict? {
        val prefs = runCatching {
            hostApplication()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val stored = runCatching { prefs.getString(VERDICT_PREFIX + profileId, null) }.getOrNull()
            ?: return null
        val parts = stored.split('\u0001')
        if (parts.size < 4 || parts[0] != cacheKey) return null
        val ok = parts[1].toIntOrNull() ?: return null
        val total = parts[2].toIntOrNull() ?: return null
        val failed = parts[3].split(',').filter { it.isNotEmpty() }.toSet()
        return Verdict(profileId, total, ok, failed, cached = true, durationMs = 0L)
    }

    private fun storeVerdict(profileId: String, cacheKey: String, verdict: Verdict) {
        runCatching {
            hostApplication()
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(
                    VERDICT_PREFIX + profileId,
                    listOf(
                        cacheKey,
                        verdict.ok.toString(),
                        verdict.total.toString(),
                        verdict.failed.joinToString(","),
                    ).joinToString("\u0001"),
                )
                ?.apply()
        }.onFailure { XposedBridge.logError("$LOG_PREFIX verdict cache write failed", it) }
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
