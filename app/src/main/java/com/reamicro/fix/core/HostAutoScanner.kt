package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** 字符串锚点、类型关系和完整方法特征驱动的自动扫描，不硬编码当前宿主短名。 */
object HostAutoScanner {
    private const val ASSET = "assets/host-profiles/automatic.rules.json"
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var last: Report? = null
    @Volatile private var loadedProfileText: String? = null

    data class Report(
        val total: Int,
        val succeeded: Int,
        val failures: List<String>,
        val cached: Boolean,
        val durationMs: Long,
        val unchanged: Boolean,
        val profile: HostProfile.Parsed?,
    ) {
        fun summary(): String = "DexKit 扫描 $succeeded/$total，失败 ${failures.size} 项" +
            if (cached) "（缓存）" else "（${durationMs}ms）"
    }

    fun snapshot(): Report? = last

    /** 只恢复展示记录，不安装映射、不触发扫描；宿主和规则摘要都匹配才接受缓存。 */
    fun restoreCachedReport(apk: String, dataDir: String): Report? = synchronized(lock) {
        last?.let { return@synchronized it }
        runCatching {
            val asset = if (HostSymbols.profileId == "2.3.3")
                "assets/host-profiles/automatic-2.3.3.rules.json" else ASSET
            val rules = HostSymbols.bundledAsset(asset)
            val digest = MessageDigest.getInstance("SHA-256")
            File(apk).inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val key = HostScanRules.cacheKey(digest.digest().hex(),
                MessageDigest.getInstance("SHA-256").digest(rules).hex())
            val cache = JSONObject(File(dataDir, "code_cache/reamicro_dexkit/automatic-cache.json").readText(Charsets.UTF_8))
            if (cache.optString("key") != key) return@synchronized null
            Report(cache.getInt("total"), cache.getInt("succeeded"), cache.getJSONArray("failures").strings(),
                true, 0, false, HostProfile.parse(cache.getString("profile"))).also { last = it }
        }.getOrNull()
    }

    fun scanProfileAvailable(profileId: String): Boolean =
        profileId in setOf("2.3.3-rc3", "2.3.3", "identity", "automatic")

    fun scan(apk: String, dataDir: String, force: Boolean = false): Report? = synchronized(lock) {
        if (running) return@synchronized null
        running = true
        val started = System.nanoTime()
        try {
            val asset = if (HostSymbols.profileId == "2.3.3")
                "assets/host-profiles/automatic-2.3.3.rules.json" else ASSET
            val bytes = HostSymbols.bundledAsset(asset)
            val rules = JSONObject(bytes.toString(Charsets.UTF_8))
            val apkDigest = File(apk).inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex()
            }
            val key = HostScanRules.cacheKey(apkDigest, MessageDigest.getInstance("SHA-256").digest(bytes).hex())
            val cache = File(dataDir, "code_cache/reamicro_dexkit/automatic-cache.json")
            val previous = runCatching { JSONObject(cache.readText(Charsets.UTF_8)) }.getOrNull()
                ?.takeIf { it.optString("key") == key }
            if (!force && previous != null) {
                val parsed = HostProfile.parse(previous.getString("profile"))
                loadedProfileText = previous.getString("profile")
                val report = Report(previous.getInt("total"), previous.getInt("succeeded"),
                    previous.getJSONArray("failures").strings(), true, 0, true, parsed)
                last = report
                XposedBridge.logAlways("ReaMicro ${report.summary()}")
                return@synchronized report
            }
            val bridge = HostDexKit.openBridge(apk, dataDir) ?: return@synchronized null
            bridge.use {
                val resolved = linkedMapOf<String, String>()
                val classes = rules.getJSONArray("classes").objects()
                val methods = rules.getJSONArray("methods").objects()
                val fields = rules.getJSONArray("fields").objects()
                val failures = arrayListOf<String>()
                val methodResults = linkedMapOf<String, String>()
                val owners = linkedMapOf<String, String>()
                val fieldResults = linkedMapOf<String, String>()
                val callCache = HashMap<String, Set<String>>()
                val shapeCache = HashMap<String, List<String>>()
                val allClasses by lazy { bridge.findClass(FindClass.create()) }
                fun matchesClass(type: ClassData, rule: JSONObject): Boolean {
                    val expected = rule.optJSONArray("methodShapes")?.strings() ?: return true
                    val actual = shapeCache.getOrPut(type.name) {
                        type.methods.map {
                            HostScanRules.normalize(it.descriptor.substringAfter("->").substringAfter(it.name))
                        }.sorted()
                    }
                    return actual == expected.sorted()
                }
                fun stableCalls(method: MethodData, depth: Int): Set<String> =
                    callCache.getOrPut("${method.descriptor}/$depth") {
                        buildSet {
                            method.invokes.forEach { called ->
                                if (listOf("java.", "javax.", "android.").any(called.declaredClassName::startsWith)) {
                                    add(called.descriptor)
                                } else if (depth > 0) addAll(stableCalls(called, depth - 1))
                            }
                        }
                    }
                // 类锚点可用 toString/异常文本；无独立文本的接口由已定位方法的签名推导。
                classes.forEach { rule ->
                    val anchors = rule.optJSONArray("strings")?.strings().orEmpty()
                    val stable = rule.optString("stable")
                    val hits = when {
                        stable.isNotEmpty() -> listOfNotNull(bridge.getClassData(stable))
                        anchors.isNotEmpty() -> bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().usingEqStrings(anchors),
                        ))
                        rule.has("methodShapes") -> allClasses
                        else -> emptyList()
                    }
                    val filtered = hits.filter { matchesClass(it, rule) }
                    HostScanRules.unique(filtered)?.let { resolved[rule.getString("id")] = it.name }
                }
                repeat(8) {
                    classes.filter { it.getString("id") !in resolved }.forEach { rule ->
                        val relation = rule.optJSONObject("relation") ?: return@forEach
                        val owner = resolved[relation.getString("owner")] ?: return@forEach
                        val index = relation.getInt("index")
                        var hits = bridge.getClassData(owner)?.methods.orEmpty().filter { matches(it, relation) }
                        if (hits.isEmpty()) {
                            val opcodes = relation.getJSONArray("opcodes").let { array ->
                                (0 until array.length()).map(array::getInt)
                            }
                            hits = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().opCodes(opcodes, OpCodeMatchType.Equals),
                            )).filter { matches(it, relation) }
                        }
                        val values = hits.mapNotNull { method ->
                            if (index < 0) method.returnTypeName else method.paramTypeNames.getOrNull(index)
                        }.distinct()
                        val actual = HostScanRules.unique(values)
                        if (actual != null) resolved[rule.getString("id")] = actual
                    }
                }
                classes.forEach { if (it.getString("id") !in resolved) failures += "class:${it.getString("id")}" }
                methods.forEach { rule ->
                    val keyName = rule.getString("id")
                    val anchors = rule.optJSONArray("strings")?.strings().orEmpty()
                    val owner = resolved[rule.getString("owner")]
                    val pool = if (anchors.isNotEmpty()) {
                        bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().usingEqStrings(anchors),
                        ))
                    } else if (owner != null) bridge.getClassData(owner)?.methods.orEmpty()
                    else {
                        val opcodes = rule.getJSONArray("opcodes").let { array ->
                            (0 until array.length()).map(array::getInt)
                        }
                        bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().opCodes(opcodes, OpCodeMatchType.Equals),
                        ))
                    }
                    var candidates = pool.filter { matches(it, rule) }
                    val types = rule.optJSONArray("types")?.strings()
                    if (types != null) candidates = candidates.filter { candidate ->
                        val actual = candidate.paramTypeNames + candidate.returnTypeName
                        actual.size == types.size && types.indices.all { index ->
                            val expected = resolved[types[index]]
                            expected == null || actual[index] == expected
                        }
                    }
                    val calls = rule.optJSONArray("stableCalls")?.strings().orEmpty().toSet()
                    if (candidates.size > 1 && calls.isNotEmpty()) {
                        candidates = candidates.filter { stableCalls(it, 2) == calls }
                    }
                    val returnShape = rule.optJSONArray("returnShape")?.strings()
                    if (returnShape != null) candidates = candidates.filter { candidate ->
                        candidate.returnType?.methods?.map {
                            HostScanRules.normalize(it.descriptor.substringAfter("->").substringAfter(it.name))
                        }?.sorted() == returnShape.sorted()
                    }
                    val parameterShapes = rule.optJSONObject("parameterShapes")
                    if (parameterShapes != null) candidates = candidates.filter { candidate ->
                        parameterShapes.keys().asSequence().all { key ->
                            val index = key.toInt()
                            val expected = parameterShapes.getJSONArray(key).strings().sorted()
                            candidate.paramTypes.getOrNull(index)?.methods?.map {
                                HostScanRules.normalize(it.descriptor.substringAfter("->").substringAfter(it.name))
                            }?.sorted() == expected
                        }
                    }
                    val numbers = rule.optJSONArray("numbers")
                    if (numbers != null && candidates.size > 1) {
                        val query = MethodMatcher.create().usingNumbers(
                            (0 until numbers.length()).map { numbers.getInt(it) as Number },
                        )
                        val allowed = bridge.findMethod(FindMethod.create().searchInMethod(candidates).matcher(query))
                            .mapTo(hashSetOf()) { it.descriptor }
                        candidates = candidates.filter { it.descriptor in allowed }
                    }
                    val scoped = candidates.filter { it.declaredClassName == owner }
                    val candidate = HostScanRules.unique(scoped) ?: HostScanRules.unique(candidates)
                    if (candidate == null) failures += "method:$keyName candidates=${candidates.size}"
                    else {
                        methodResults[keyName] = candidate.name
                        owners[keyName] = candidate.declaredClassName
                    }
                }
                fields.forEach { rule ->
                    val keyName = rule.getString("id")
                    val owner = resolved[rule.getString("owner")]
                    val candidates = owner?.let { bridge.getClassData(it)?.fields }.orEmpty().filter { field ->
                        HostScanRules.compatibleSignature(rule.getString("type"), field.typeSign) &&
                            Modifier.isStatic(field.modifiers) == rule.getBoolean("static") &&
                            (rule.optString("stable").isEmpty() || field.name == rule.getString("stable"))
                    }
                    val field = HostScanRules.unique(candidates)
                    if (field == null) failures += "field:$keyName candidates=${candidates.size}"
                    else fieldResults[keyName] = field.name
                }
                val text = buildString {
                    append("profile=automatic\npackage=${HostProfile.HOST_PACKAGE}\n")
                    append("requiredClass=app.zhendong.reamicro.MainActivity\n")
                    val primary = HashSet<String>()
                    resolved.forEach { (k, v) ->
                        // 同一实际类只有一个反向逻辑身份，其余合并角色保持 typeAlias。
                        val kind = if (!k.startsWith("ReaMicro") && primary.add(v)) "class" else "typeAlias"
                        append("$kind=$k=$v\n")
                    }
                    methodResults.forEach { (k, v) -> append("method=$k=$v\n") }
                    owners.forEach { (k, v) -> append("methodOwner=$k=$v\n") }
                    fieldResults.forEach { (k, v) -> append("field=$k=$v\n") }
                }
                val total = classes.size + methods.size + fields.size
                val unchanged = loadedProfileText == text
                if (!force) loadedProfileText = text
                val report = Report(total, total - failures.size, failures, false,
                    (System.nanoTime() - started) / 1_000_000, unchanged,
                    HostProfile.parse(text))
                cache.parentFile?.mkdirs()
                val temp = File(cache.parentFile, cache.name + ".tmp")
                temp.writeText(JSONObject().put("key", key).put("profile", text).put("total", total)
                    .put("succeeded", report.succeeded).put("failures", JSONArray(failures)).toString(), Charsets.UTF_8)
                check(temp.renameTo(cache)) { "Unable to atomically store DexKit cache" }
                last = report
                XposedBridge.logAlways("ReaMicro ${report.summary()} unchanged=${report.unchanged}")
                report
            }
        } catch (error: Throwable) {
            XposedBridge.logError("ReaMicro automatic DexKit scan failed", error)
            last = Report(1, 0, listOf(error.javaClass.simpleName + ": " + error.message),
                false, (System.nanoTime() - started) / 1_000_000, false, null)
            last
        } finally {
            running = false
        }
    }

    private fun matches(method: MethodData, rule: JSONObject): Boolean {
        val signature = method.descriptor.substringAfter("->").substringAfter(method.name)
        if (!HostScanRules.compatibleSignature(rule.getString("signature"), signature)) return false
        if (Modifier.isStatic(method.modifiers) != rule.getBoolean("static")) return false
        val stable = rule.optString("stable")
        if (stable.isNotEmpty() && method.name != stable) return false
        val opcodes = rule.optJSONArray("opcodes")?.let { array -> (0 until array.length()).map(array::getInt) }
        if (opcodes != null && method.opCodes != opcodes) return false
        val strings = rule.optJSONArray("strings")?.strings().orEmpty()
        return method.usingStrings.containsAll(strings)
    }

    private fun classMatches(type: ClassData, rule: JSONObject): Boolean {
        val shapes = rule.optJSONArray("methodShapes")?.strings() ?: return true
        val actual = type.methods.map {
            HostScanRules.normalize(it.descriptor.substringAfter("->").substringAfter(it.name))
        }.sorted()
        return actual == shapes.sorted()
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
