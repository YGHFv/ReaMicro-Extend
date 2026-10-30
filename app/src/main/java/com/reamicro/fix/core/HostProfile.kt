package com.reamicro.fix.core

/**
 * 宿主符号 profile 的纯数据解析层。
 *
 * 不依赖 Android，可在 JVM 单元测试中直接校验仓库内的 profile 文本。
 * profile 只承载「版本差异」，业务代码永远使用未混淆的逻辑符号名。
 */
object HostProfile {
    const val HOST_PACKAGE = "app.zhendong.reamicro"
    const val SIZE_TOLERANCE = 0.08

    data class Fingerprint(
        val id: String,
        val packageName: String,
        val requiredClasses: List<String>,
        val absentClasses: List<String>,
        val sizeHint: Long,
    )

    data class Parsed(
        val fingerprint: Fingerprint,
        val classes: Map<String, String>,
        val methods: Map<String, String>,
        val fields: Map<String, String>,
        val typeAliases: Map<String, String> = emptyMap(),
        val methodOwners: Map<String, String> = emptyMap(),
    ) {
        private val logicalByActual: Map<String, String> by lazy {
            buildMap {
                classes.forEach { (logical, actual) -> putIfAbsent(actual, logical) }
            }
        }
        private val aliasesByActual: Map<String, Set<String>> by lazy {
            typeAliases.entries.groupBy({ it.value }, { it.key })
                .mapValues { (_, names) -> names.toSet() }
        }

        /** 混淆名反查逻辑名，用于只有 Class 对象的调用点。 */
        fun logicalClassOf(actual: String): String? = logicalByActual[actual]

        fun classOf(logical: String): String? = classes[logical] ?: typeAliases[logical]

        fun methodOf(logicalClass: String, logicalName: String): String? =
            methods["$logicalClass#$logicalName"]

        fun fieldOf(logicalClass: String, logicalName: String): String? =
            fields["$logicalClass#$logicalName"]

        fun aliasesOf(actual: String): Set<String> = aliasesByActual[actual].orEmpty()

        /**
         * 剔除运行期校验失败的逻辑类。
         *
         * 只保留通过结构指纹校验的映射，让失效符号退回 identity（逻辑名原样返回），
         * 避免把 hook 装到重新混淆后恰好重名的无关类上。
         */
        fun excluding(logicals: Set<String>): Parsed {
            if (logicals.isEmpty()) return this
            fun keepMember(key: String): Boolean = key.substringBefore('#') !in logicals
            return Parsed(
                fingerprint = fingerprint,
                classes = classes.filterKeys { it !in logicals },
                methods = methods.filterKeys(::keepMember),
                fields = fields.filterKeys(::keepMember),
                typeAliases = typeAliases.filterKeys { it !in logicals },
                methodOwners = methodOwners.filterKeys(::keepMember),
            )
        }
    }

    class ProfileSyntaxException(val line: String, reason: String) :
        IllegalArgumentException("host profile 行非法（$reason）：$line")

    fun parse(text: String, source: String = "<memory>"): Parsed {
        var id = ""
        var packageName = HOST_PACKAGE
        var sizeHint = 0L
        val required = mutableListOf<String>()
        val absent = mutableListOf<String>()
        val classes = linkedMapOf<String, String>()
        val methods = linkedMapOf<String, String>()
        val fields = linkedMapOf<String, String>()
        val typeAliases = linkedMapOf<String, String>()
        val methodOwners = linkedMapOf<String, String>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            when {
                line.startsWith("profile=") -> id = value(line, "profile=", source)
                line.startsWith("package=") -> packageName = value(line, "package=", source)
                line.startsWith("sizeHint=") -> sizeHint = parseSize(line, source)
                line.startsWith("requiredClass=") -> required += value(line, "requiredClass=", source)
                line.startsWith("absentClass=") -> absent += value(line, "absentClass=", source)
                line.startsWith("class=") -> {
                    val (logical, actual) = pair(line, "class=", source)
                    classes[logical] = actual
                }
                line.startsWith("typeAlias=") -> {
                    val (logical, actual) = pair(line, "typeAlias=", source)
                    typeAliases[logical] = actual
                }
                line.startsWith("method=") -> {
                    val (key, actual) = pair(line, "method=", source)
                    methods[requireMember(key, line, source)] = actual
                }
                line.startsWith("methodOwner=") -> {
                    val (key, actual) = pair(line, "methodOwner=", source)
                    methodOwners[requireMember(key, line, source)] = actual
                }
                line.startsWith("field=") -> {
                    val (key, actual) = pair(line, "field=", source)
                    fields[requireMember(key, line, source)] = actual
                }
                else -> throw ProfileSyntaxException(line, "未知指令")
            }
        }

        if (id.isBlank()) throw ProfileSyntaxException("<file:$source>", "缺少 profile=")
        if (required.isEmpty() && absent.isEmpty()) {
            throw ProfileSyntaxException("<file:$source>", "缺少指纹，无法判断是否命中该版本")
        }
        return Parsed(
            fingerprint = Fingerprint(id, packageName, required.toList(), absent.toList(), sizeHint),
            classes = classes.toMap(),
            methods = methods.toMap(),
            fields = fields.toMap(),
            typeAliases = typeAliases.toMap(),
            methodOwners = methodOwners.toMap(),
        )
    }

    /** 按指纹判断 profile 是否适用于当前进程；类可用性由调用方提供。 */
    fun matches(
        parsed: Parsed,
        hostPackage: String,
        apkSize: Long,
        classExists: (String) -> Boolean,
    ): Boolean {
        val fp = parsed.fingerprint
        // NPatch 等免 Root 打包器会通过改包名实现共存，但宿主 DEX 仍是同一个构建。
        // 这里只放行原包名的子包名，后续体积、类指纹和 DEX 校验仍会限制误匹配。
        val sameHostFamily =
            fp.packageName == hostPackage ||
                hostPackage.startsWith("${fp.packageName}.")
        if (!sameHostFamily) return false
        if (fp.sizeHint > 0L && apkSize > 0L) {
            val low = fp.sizeHint * (1.0 - SIZE_TOLERANCE)
            val high = fp.sizeHint * (1.0 + SIZE_TOLERANCE)
            if (apkSize.toDouble() < low || apkSize.toDouble() > high) return false
        }
        if (fp.requiredClasses.any { !classExists(it) }) return false
        if (fp.absentClasses.any { classExists(it) }) return false
        return true
    }

    /** 命中强度：映射条目越多越优先，用于同版本多变体时挑选最具体的 profile。 */
    fun specificity(parsed: Parsed): Int =
        parsed.classes.size + parsed.methods.size + parsed.fields.size

    private fun parseSize(line: String, source: String): Long {
        val raw = value(line, "sizeHint=", source)
        return raw.toLongOrNull()
            ?: throw ProfileSyntaxException(line, "sizeHint 必须是整数（来源 $source）")
    }

    private fun requireMember(key: String, line: String, source: String): String {
        if (!key.contains('#')) throw ProfileSyntaxException(line, "方法/字段键必须形如 类名#成员（来源 $source）")
        return key
    }

    private fun value(line: String, prefix: String, source: String): String =
        line.removePrefix(prefix).trim().also {
            if (it.isEmpty()) throw ProfileSyntaxException(line, "空值（来源 $source）")
        }

    private fun pair(line: String, prefix: String, source: String): Pair<String, String> {
        val body = line.removePrefix(prefix)
        val index = body.lastIndexOf('=')
        if (index <= 0 || index == body.length - 1) {
            throw ProfileSyntaxException(line, "必须形如 key=value（来源 $source）")
        }
        return body.substring(0, index).trim() to body.substring(index + 1).trim()
    }
}
