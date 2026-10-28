package com.reamicro.fix.core

import android.app.Application
import com.reamicro.fix.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * 运行期统一符号解析入口。
 *
 * 设计要点：
 * - 未混淆 / 未命中 profile 时走 identity，逻辑名原样返回，现有宿主行为不变；
 * - 命中混淆 profile 时，逻辑名 -> 混淆名，所有反射与 hook 都经这里收敛；
 * - profile 只描述版本差异，业务代码不再为每个混淆版本重复改写。
 */
object HostSymbols {
    private const val LOG_PREFIX = "ReaMicro symbols"
    private const val PROFILE_ASSET_DIR = "host-profiles"
    private const val MODULE_PACKAGE = "com.reamicro.fix"

    @Volatile private var initialized = false
    @Volatile private var initializedPackage: String? = null
    @Volatile private var active: HostProfile.Parsed? = null
    private val classCache = HashMap<String, String>()

    val profileId: String get() = active?.fingerprint?.id ?: "identity"
    val abiProfileId: String get() = if (profileId == "2.3.3-rc3") "2.3.3-b4" else profileId
    val isIdentity: Boolean get() = active == null

    /**
     * 在安装任何 hook 之前调用。
     *
     * @param classLoader 宿主 ClassLoader，用于探测类是否存在
     * @param hostPackage 宿主包名
     * @param apkSize 宿主 base APK 字节数（<=0 表示不做体积门禁）
     * @param profileText 可选外部注入的 profile 文本（自检 / 调试用）
     * @param hostDataDir 宿主 dataDir，供 DexKit 抽 native so 时选可写目录
     */
    @JvmStatic
    fun initialize(
        classLoader: ClassLoader,
        hostPackage: String,
        apkSize: Long,
        profileText: String? = null,
        hostApkPath: String? = null,
        hostDataDir: String? = null,
    ) {
        synchronized(this) {
            if (initialized && initializedPackage == hostPackage) return
            if (initialized) {
                // LSPosed 可能先回调模块自身，再回调真实宿主；切包时必须重建解析状态。
                XposedBridge.log(
                    "$LOG_PREFIX switch package $initializedPackage -> $hostPackage，重建符号表",
                )
                active = null
                classCache.clear()
            }
            initialized = true
            initializedPackage = hostPackage
            val candidates = loadProfiles(profileText)
            val fingerprinted = candidates
                .filter { HostProfile.matches(it, hostPackage, apkSize) { name -> classExists(classLoader, name) } }
                .sortedByDescending { HostProfile.specificity(it) }
            val selection = selectCandidate(fingerprinted, hostApkPath, apkSize, hostDataDir)
            val matched = selection.profile
            active = matched
            if (!hostApkPath.isNullOrBlank() && !hostDataDir.isNullOrBlank() &&
                HostAutoScanner.scanProfileAvailable(profileId)) {
                val scan = HostAutoScanner.scan(hostApkPath, hostDataDir)
                HookInstallReport.record("HostSymbols", "automaticScan", scan != null && scan.failures.isEmpty(),
                    scan?.failures?.takeIf { it.isNotEmpty() }?.let { IllegalStateException(it.take(8).joinToString()) })
                // 已验证 ABI 优先；扫描补齐门面，未知构建仅装载已确认的定位结果。
                val scanned = scan?.profile
                if (matched != null && scanned != null) {
                    active = HostScanOverlay.merge(matched, scanned)
                } else if (scanned != null && scanned.classes.isNotEmpty()) {
                    active = scanned
                    XposedBridge.logAlways("$LOG_PREFIX automatic partial profile active; native ABI remains unverified")
                }
            }
            val unresolved = active?.classes?.entries
                ?.filter { classLoader.findClassOrNull(it.value) == null }
                ?.map { "${it.key}->${it.value}" }
                .orEmpty()
            HookInstallReport.record(
                feature = "HostSymbols",
                name = "profile",
                ok = active != null || candidates.isEmpty(),
                error = when {
                    active != null -> null
                    candidates.isEmpty() -> null
                    else -> IllegalStateException(
                        "profile 不可用（${selection.reason}），回退 identity（候选 ${candidates.size} 个）",
                    )
                },
            )
            HookInstallReport.record(
                feature = "HostSymbols",
                name = "dexValidation",
                ok = selection.verdict == null || selection.reason == "verified",
                error = selection.verdict?.let {
                    if (selection.reason == "verified") {
                        null
                    } else {
                        IllegalStateException(
                            "profile ${it.profileId} 结构指纹校验未通过 ${
                                "%.2f".format(it.ratio)
                            }（${it.ok}/${it.total}），当前 profile=$profileId，原生 ABI 未验证",
                        )
                    }
                },
            )
            XposedBridge.logAlways(
                "$LOG_PREFIX package=$hostPackage profile=${profileId} " +
                    "mapped=${active?.classes?.size ?: 0} " +
                    "unresolved=${unresolved.size} " +
                    "validation=${selection.summary()}",
            )
            // 混淆宿主换版本时，这行是定位「profile 为何没被选中」的关键证据。
            if (candidates.isNotEmpty()) {
                XposedBridge.logAlways(
                    "$LOG_PREFIX audit candidates=" +
                        candidates.joinToString(",") { "${it.fingerprint.id}(${HostProfile.specificity(it)})" } +
                        " fingerprinted=" + fingerprinted.joinToString(",") { it.fingerprint.id } +
                        " size=$apkSize -> " + selection.audit(),
                )
                selection.audits.forEach { (id, verdict) ->
                    verdict?.samples?.forEach { sample ->
                        XposedBridge.logAlways("$LOG_PREFIX mismatch $id $sample")
                    }
                }
            }
            if (unresolved.isNotEmpty()) {
                XposedBridge.log(
                    "$LOG_PREFIX unresolved symbols: ${unresolved.take(20).joinToString(", ")}",
                )
            }
        }
    }

    private const val VALIDATION_ACCEPT_RATIO = 0.9

    private class Selection(
        val profile: HostProfile.Parsed?,
        val verdict: HostDexKit.Verdict?,
        val dropped: Int,
        val reason: String,
        val audits: List<Pair<String, HostDexKit.Verdict?>> = emptyList(),
    ) {
        fun summary(): String = verdict?.let {
            "${it.profileId}${if (it.cached) "(cached)" else ""}=" +
                "${it.ok}/${it.total} dropped=$dropped ${it.durationMs}ms"
        } ?: reason

        /** 逐个候选的校验结果，用于定位「为什么没选中某个 profile」。 */
        fun audit(): String = audits.joinToString("; ") { (id, item) ->
            if (item == null) "$id=no-verdict" else "$id=${item.ok}/${item.total}"
        }
    }

    /**
     * 多个 profile 指纹都会命中同一构建（体积区间 + 类存在性很宽松），
     * 因此这里用运行期 DEX 结构校验挑出真正匹配当前 dex 的那一个。
     */
    private fun selectCandidate(
        candidates: List<HostProfile.Parsed>,
        hostApkPath: String?,
        apkSize: Long,
        hostDataDir: String?,
    ): Selection {
        if (candidates.isEmpty()) return Selection(null, null, 0, "no-fingerprint")
        if (hostApkPath.isNullOrBlank()) return Selection(candidates.first(), null, 0, "no-apk-path")
        val shapes = HostDexKit.bundledShapes()
        if (shapes.isEmpty() || candidates.none { shapes.containsKey(it.fingerprint.id) }) {
            return Selection(candidates.first(), null, 0, "no-shapes-asset")
        }
        var best: Pair<HostProfile.Parsed, HostDexKit.Verdict>? = null
        var rejected: HostDexKit.Verdict? = null
        val audits = ArrayList<Pair<String, HostDexKit.Verdict?>>()
        for (candidate in candidates) {
            val verdict = HostDexKit.verify(
                hostApkPath = hostApkPath,
                profileId = candidate.fingerprint.id,
                classes = candidate.classes,
                apkSize = apkSize,
                hostDataDir = hostDataDir,
            )
            audits += candidate.fingerprint.id to verdict
            if (verdict == null) continue
            if (verdict.ratio >= VALIDATION_ACCEPT_RATIO) {
                if (best == null || verdict.ratio > best.second.ratio) best = candidate to verdict
            } else if (rejected == null || verdict.ratio > rejected.ratio) {
                rejected = verdict
            }
        }
        val chosen = best
            ?: return Selection(
                null,
                rejected,
                0,
                if (rejected == null) "validation-unavailable" else "validation-rejected",
                audits,
            )
        val (profile, verdict) = chosen
        val filtered = if (verdict.failed.isEmpty()) profile else profile.excluding(verdict.failed)
        return Selection(filtered, verdict, verdict.failed.size, "verified", audits)
    }

    @JvmStatic
    fun reset() {
        synchronized(this) {
            initialized = false
            initializedPackage = null
            active = null
            classCache.clear()
        }
    }

    /** 逻辑名 -> 实际名；identity 或未登记时原样返回。 */
    @JvmStatic
    fun resolveClass(logical: String): String {
        if (isIdentity) return logical
        val cached = classCache[logical]
        if (cached != null) return cached
        val mapped = active?.classOf(logical) ?: logical
        classCache[logical] = mapped
        return mapped
    }

    /** 实际名 -> 逻辑名，供只拿到 Class 对象的调用点使用。 */
    @JvmStatic
    fun logicalClass(actual: String): String {
        val parsed = active ?: return actual
        // JADX/Android 的反射名有时带 defpackage.，而 profile 保留 DEX 短名。
        // 两者指向同一个类，成员解析不能因展示前缀不同而退回 identity。
        return parsed.logicalClassOf(actual)
            ?: parsed.logicalClassOf(actual.substringAfterLast('.'))
            ?: actual
    }

    /** 只拿到运行时 Class 时，仍按业务层看到的简单类名做状态判断。 */
    @JvmStatic
    fun logicalSimpleName(actual: String): String =
        logicalClass(actual).substringAfterLast('$').substringAfterLast('.')

    @JvmStatic
    fun resolveMethodName(logicalClass: String, logicalName: String): String {
        if (isIdentity) return logicalName
        val parsed = active ?: return logicalName
        return parsed.methodOf(logicalClass, logicalName)
            ?: parsed.logicalClassOf(logicalClass)?.let { parsed.methodOf(it, logicalName) }
            ?: parsed.methodOf(resolveClass(logicalClass), logicalName)
            ?: parsed.aliasesOf(resolveClass(logicalClass)).firstNotNullOfOrNull {
                parsed.methodOf(it, logicalName)
            }
            ?: logicalName
    }

    /** 继承方法的映射归声明类所有，不能只查运行时子类名。 */
    @JvmStatic
    fun resolveMethodName(type: Class<*>, logicalName: String): String {
        active?.let { parsed ->
            for (owner in typeHierarchy(type)) {
                val logical = logicalClass(owner.name)
                parsed.methodOf(logical, logicalName)?.let { return it }
                parsed.aliasesOf(owner.name).forEach { alias ->
                    parsed.methodOf(alias, logicalName)?.let { return it }
                }
            }
        }
        active?.methodOwners?.entries?.firstOrNull {
            it.value == type.name && it.key.substringAfter('#') == logicalName
        }?.key?.let { key -> active?.methods?.get(key)?.let { return it } }
        return logicalName
    }

    /** R8 可把同一 Kotlin 门面的不同方法移到不同持有者，按成员而非类解析。 */
    @JvmStatic
    fun resolveMethodOwner(logicalClass: String, logicalName: String): String =
        active?.methodOwners?.get("$logicalClass#$logicalName") ?: resolveClass(logicalClass)

    fun bundledAsset(path: String): ByteArray {
        require(path.startsWith("assets/") && ".." !in path)
        val apk = requireNotNull(moduleApkFile()) { "Module APK not available" }
        return ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry(path)) { "Missing module asset $path" }
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    @JvmStatic
    fun methodNames(type: Class<*>, logicalPrefix: String): Set<String> = buildSet {
        add(logicalPrefix)
        val parsed = active ?: return@buildSet
        for (owner in typeHierarchy(type)) {
            for (logical in setOf(logicalClass(owner.name)) + parsed.aliasesOf(owner.name)) {
                val prefix = "$logical#$logicalPrefix"
                parsed.methods.forEach { (key, value) ->
                    if (key == prefix || key.startsWith("$prefix-") || key.startsWith("$prefix\$")) add(value)
                }
            }
        }
    }

    /** 宿主 Proxy 回调的 invoke/resumeWith 本身也会被混淆。 */
    @JvmStatic
    fun logicalMethodName(method: java.lang.reflect.Method): String {
        val owner = logicalClass(method.declaringClass.name)
        return active?.methods?.entries?.firstOrNull {
            it.key.startsWith("$owner#") && it.value == method.name
        }?.key?.substringAfter('#') ?: method.name
    }

    private fun typeHierarchy(type: Class<*>): List<Class<*>> {
        val result = linkedSetOf<Class<*>>()
        fun visit(current: Class<*>?) {
            if (current == null || !result.add(current)) return
            current.interfaces.forEach(::visit)
            visit(current.superclass)
        }
        visit(type)
        return result.toList()
    }

    @JvmStatic
    fun resolveFieldName(logicalClass: String, logicalName: String): String {
        if (isIdentity) return logicalName
        val parsed = active ?: return logicalName
        return parsed.fieldOf(logicalClass, logicalName)
            ?: parsed.fieldOf(resolveClass(logicalClass), logicalName)
            ?: parsed.aliasesOf(resolveClass(logicalClass)).firstNotNullOfOrNull {
                parsed.fieldOf(it, logicalName)
            }
            ?: logicalName
    }

    @JvmStatic
    fun resolveFieldName(type: Class<*>, logicalName: String): String {
        active?.let { parsed ->
            for (owner in typeHierarchy(type)) {
                val logical = logicalClass(owner.name)
                parsed.fieldOf(logical, logicalName)?.let { return it }
                parsed.aliasesOf(owner.name).forEach { alias ->
                    parsed.fieldOf(alias, logicalName)?.let { return it }
                }
            }
        }
        return logicalName
    }

    /**
     * 混淆器可能删掉 Kotlin data class 的 getter。此时把 getX/isX 还原成属性名，
     * 再查 profile 中的人工/结构字段映射，让调用方仍能按稳定属性名读取字段。
     */
    @JvmStatic
    fun resolveAccessorFieldName(logicalClass: String, accessorName: String): String? {
        if (isIdentity) return null
        val property = accessorPropertyName(accessorName) ?: return null
        val parsed = active ?: return null
        return parsed.fieldOf(logicalClass, accessorName)
            ?: parsed.fieldOf(logicalClass, accessorName.removePrefix("get"))
            ?: parsed.fieldOf(logicalClass, property)
            ?: parsed.fieldOf(resolveClass(logicalClass), property)
    }

    /** 访问器可能声明在父类；字段映射也必须沿父类链查找（NodeDom.getTag -> Dom.tag）。 */
    @JvmStatic
    fun resolveAccessorFieldName(type: Class<*>, accessorName: String): String? {
        if (isIdentity) return null
        val property = accessorPropertyName(accessorName) ?: return null
        val parsed = active ?: return null
        for (owner in typeHierarchy(type)) {
            val logical = logicalClass(owner.name)
            parsed.fieldOf(logical, accessorName)?.let { return it }
            parsed.fieldOf(logical, accessorName.removePrefix("get"))?.let { return it }
            parsed.fieldOf(logical, property)?.let { return it }
            parsed.aliasesOf(owner.name).forEach { alias ->
                parsed.fieldOf(alias, property)?.let { return it }
            }
        }
        return null
    }

    fun summary(): String {
        val parsed = active ?: return "profile=identity"
        return "profile=${parsed.fingerprint.id} classes=${parsed.classes.size} " +
            "methods=${parsed.methods.size} fields=${parsed.fields.size}"
    }

    /** 逻辑名解析后能否在宿主 ClassLoader 中找到；用于安装前的自检 */
    @JvmStatic
    fun canResolve(logical: String, classLoader: ClassLoader): Boolean =
        classLoader.findClassOrNull(resolveClass(logical)) != null

    private fun loadProfiles(injected: String?): List<HostProfile.Parsed> {
        val texts = linkedMapOf<String, String>()
        injected?.takeIf { it.isNotBlank() }?.let { texts["<injected>"] = it }
        texts.putAll(readBundledProfiles())
        return texts.mapNotNull { (name, text) ->
            runCatching { HostProfile.parse(text, name) }
                .onFailure { XposedBridge.logError("$LOG_PREFIX bad profile $name", it) }
                .getOrNull()
        }
    }

    private fun readBundledProfiles(): Map<String, String> {
        val apkFile = moduleApkFile() ?: return emptyMap()
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val prefix = "assets/$PROFILE_ASSET_DIR/"
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".profile") }
                    .sortedBy { it.name }
                    .associate { entry ->
                        entry.name.substringAfterLast('/') to
                            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    }
            }
        }.getOrElse {
            XposedBridge.logError("$LOG_PREFIX read bundled profiles failed", it)
            emptyMap()
        }
    }

    private fun moduleApkFile(): File? {
        val path = InjectedModuleContext.sourceDirOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackModuleApkPath()
        val file = path?.let(::File) ?: return null
        return file.takeIf { it.isFile }
    }

    /** 兜底读取模块 APK 路径，避免 InjectedModuleContext 尚未就绪。 */
    private fun fallbackModuleApkPath(): String? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Application
        current?.packageManager?.getApplicationInfo(MODULE_PACKAGE, 0)?.sourceDir
    }.getOrNull()

    private fun classExists(classLoader: ClassLoader, name: String): Boolean =
        classLoader.findClassOrNull(name) != null

    private fun accessorPropertyName(name: String): String? {
        val accessor = name.substringBefore('-')
        fun property(value: String): String =
            if (value.length > 1 && value[0].isUpperCase() && value[1].isUpperCase()) value
            else value.replaceFirstChar { it.lowercaseChar() }
        if (accessor.length <= 3 || !accessor.startsWith("get")) {
            if (accessor.length <= 2 || !accessor.startsWith("is")) return null
            return property(accessor.substring(2))
        }
        return property(accessor.substring(3))
    }

    private fun ClassLoader.findClassOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, this) }.getOrNull()
}
