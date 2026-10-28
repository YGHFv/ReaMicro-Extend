package com.reamicro.fix.hook

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod

import android.app.Activity
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class ReaderFontCompletionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val methodCache = HashMap<String, Method>()
    private val fieldCache = HashMap<String, Field>()
    private val fontFamilyCache = HashMap<String, Any>()
    private val failedFontFamilyLogKeys = HashSet<String>()
    private val appliedMappingLogKeys = HashSet<String>()
    @Volatile private var lastMappingNamesLogKey: String = ""

    fun install() {
        // 字体映射只依赖 FontProvider，不重建宿主配置，保留新版边距及后续新增字段。
        hookFontProvider()
    }

    private fun hookFontProvider() {
        HookInstallReport.install("ReaderFontCompletionHook", "hookFontProvider") {
            val fontProviderClass = cls(FONT_PROVIDER_CLASS)
            check(fontProviderClass.declaredMethods.any { it.hostNameMatches("withName") }) { "FontProvider.withName not found" }
            XposedBridge.hookAllMethods(fontProviderClass, "withName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val names = fontFamilyArgumentNames(param.args)
                    if (!settings.snapshot().canUseFontSettings) return
                    if (!fontProviderBoolean("embeddedFonts", true)) return
                    if (!fontProviderBoolean("buildInFonts", true)) return
                    val config = settings.fontSettings()
                    if (fontProviderHasAttachedFont(names)) return
                    logMappingNames(config.songMapping, config.kaiMapping, names)
                    when {
                        names.any(::isSongFamilyName) -> resolveFontFamily(config.songMapping)?.let {
                            param.result = it
                            logMappingApplied("song", config.songMapping)
                        }
                        names.any(::isKaiFamilyName) -> resolveFontFamily(config.kaiMapping)?.let {
                            param.result = it
                            logMappingApplied("kai", config.kaiMapping)
                        }
                    }
                }
            })
            if (HostSymbols.profileId == "2.3.3-b4") {
                hookB4BuiltInFontRow()
            } else XposedBridge.hookAllMethods(fontProviderClass, "getBuildInFonts", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!settings.snapshot().canUseFontSettings) return
                    val list = param.result as? List<*> ?: return
                    if (list.isEmpty()) return
                    val config = settings.fontSettings()
                    if (config.songMapping.isBlank() && config.kaiMapping.isBlank()) return
                    param.result = list.map { pair ->
                        val label = callMethod(pair, "getFirst")?.toString().orEmpty()
                        val mapped = when {
                            isSongFamilyName(label) -> config.songMapping
                            isKaiFamilyName(label) -> config.kaiMapping
                            else -> ""
                        }
                        if (mapped.isBlank()) pair else resolveFontFamily(mapped)?.let { family ->
                            kotlinPair(mappedLabel(label, mapped), family)
                        } ?: pair
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX font completion FontProvider hook installed")
        }
    }

    private fun hookB4BuiltInFontRow() {
        val row = classLoader.hostStaticMethod(
            "ReaMicroHost.BuiltInFontRow", "render", classLoader.hostClass("ReaMicroHost.LazyItemScope"),
            cls(KOTLIN_PAIR_CLASS), cls("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType,
        )
        XposedBridge.hookMethod(row, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!settings.snapshot().canUseFontSettings) return
                val pair = param.args.getOrNull(1) ?: return
                val label = callMethod(pair, "getFirst") as? String ?: return
                // 该入口仅渲染宿主宋楷预览行；保留其行布局和所有点击/设置回调。
                val config = settings.fontSettings()
                val mapped = when {
                    isSongFamilyName(label) -> config.songMapping
                    isKaiFamilyName(label) -> config.kaiMapping
                    else -> return
                }
                if (mapped.isBlank()) return
                val family = resolveFontFamily(mapped) ?: return
                param.args[1] = kotlinPair(mappedLabel(label, mapped), family)
                param.args[3] = (param.args[3] as Int) and 0x70.inv()
            }
        })
        val content = classLoader.hostClass("ReaMicroHost.BuiltInFontItems").hostMethod(
            "invoke", Any::class.java, Any::class.java, Any::class.java, Any::class.java,
        )
        XposedBridge.deoptimize(content)
        XposedBridge.deoptimize(row)
        XposedBridge.logAlways("$LOG_PREFIX B4 built-in font preview row hook installed")
    }

    private fun fontFamilyArgumentNames(args: Array<Any?>?): List<String> {
        return args.orEmpty()
            .flatMap { raw ->
                when (raw) {
                    null -> emptyList()
                    is Array<*> -> raw.asList()
                    else -> listOf(raw)
                }
            }
            .mapNotNull { callMethod(it, "getName")?.toString() }
    }

    private fun fontProviderBoolean(fieldName: String, defaultValue: Boolean): Boolean =
        runCatching {
            field(FONT_PROVIDER_CLASS, fieldName)
                .getBoolean(null)
        }.getOrDefault(defaultValue)

    private fun fontProviderHasAttachedFont(names: List<String>): Boolean =
        runCatching {
            val fonts = field(FONT_PROVIDER_CLASS, "fonts")
                .get(null) as? Map<*, *> ?: return@runCatching false
            names.any { fonts.containsKey(it) }
        }.getOrDefault(false)

    private fun isSongFamilyName(name: String): Boolean {
        val normalized = normalizedFamilyName(name)
        if (normalized.contains("sans")) return false
        return normalized in SONG_FAMILY_KEYS || SONG_FAMILY_KEYS.any { normalized.contains(it) }
    }

    private fun isKaiFamilyName(name: String): Boolean {
        val normalized = normalizedFamilyName(name)
        return normalized in KAI_FAMILY_KEYS || KAI_FAMILY_KEYS.any { normalized.contains(it) }
    }

    private fun normalizedFamilyName(name: String): String =
        name.trim()
            .trim('"', '\'')
            .replace(Regex("[\\s_\\-]+"), "")
            .lowercase()

    private fun logMappingNames(songMapping: String, kaiMapping: String, names: List<String>) {
        if (songMapping.isBlank() && kaiMapping.isBlank()) return
        if (names.isEmpty()) return
        val key = names.joinToString("|")
        if (key == lastMappingNamesLogKey) return
        lastMappingNamesLogKey = key
        XposedBridge.log("$LOG_PREFIX font mapping names: $key")
    }

    private fun logMappingApplied(target: String, selection: String) {
        val key = "$target|$selection"
        synchronized(appliedMappingLogKeys) {
            if (!appliedMappingLogKeys.add(key)) return
        }
        XposedBridge.log("$LOG_PREFIX font mapping applied: $target -> ${displayFontName(selection)}")
    }

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val cacheKey = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        synchronized(fontFamilyCache) {
            fontFamilyCache[cacheKey]?.let { return it }
        }
        if (isBuiltinFontSelection(selection)) {
            return resolveBuiltinFontFamily(selection)?.also { family ->
                synchronized(fontFamilyCache) {
                    fontFamilyCache[cacheKey] = family
                }
            }
        }
        val file = resolvedFile ?: return null
        return runCatching {
            val family = com.reamicro.fix.core.HostFontFamilies.fromFile(classLoader, file) ?: return null
            synchronized(fontFamilyCache) {
                fontFamilyCache[cacheKey] = family
            }
            family
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> com.reamicro.fix.core.HostFontFamilies.song(classLoader)
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? =
        com.reamicro.fix.core.HostFontFamilies.system(classLoader)

    private fun fontWeight(methodName: String): Any {
        val clazz = cls(FONT_WEIGHT_CLASS)
        companionFontWeight(clazz, methodName)?.let { return it }
        val (fieldName, weight) = when (methodName) {
            "getBold" -> "Bold" to 700
            else -> "Normal" to 400
        }
        staticFontWeight(clazz, fieldName)?.let { return it }
        return clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(weight)
    }

    private fun companionFontWeight(clazz: Class<*>, methodName: String): Any? {
        for (fieldName in listOf("INSTANCE", "Companion")) {
            val companion = runCatching {
                clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
            }.recoverCatching {
                clazz.getField(fieldName).apply { isAccessible = true }.get(null)
            }.getOrNull() ?: continue
            val method = companion.javaClass.methods.firstOrNull {
                it.hostNameMatches(methodName) && it.parameterTypes.isEmpty()
            } ?: continue
            return method.invoke(companion)
        }
        return null
    }

    private fun staticFontWeight(clazz: Class<*>, fieldName: String): Any? =
        runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }.recoverCatching {
            clazz.getField(fieldName).apply { isAccessible = true }.get(null)
        }.getOrNull()

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
        }
        XposedBridge.log(
            "$LOG_PREFIX resolve font family failed: " +
                "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun resolveFontFile(selection: String): File? =
        com.reamicro.fix.epub.editor.FontSelectionResolution.file(selection,
            activityProvider()?.filesDir?.let(::fontDirectories).orEmpty())
    private fun fontDirectories(filesDir: File): List<File> {
        val dirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { File(it, "fonts") }
            ?.toMutableList()
            ?: mutableListOf()
        val defaultDir = File(File(filesDir, "0"), "fonts")
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) {
            dirs.add(defaultDir)
        }
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    private fun isFontFileName(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

    private fun mappedLabel(label: String, mapped: String): String =
        if (mapped.isBlank()) label else "$label（${displayFontName(mapped)}）"

    private fun displayFontName(value: String): String =
        when (value) {
            FAMILY_SYSTEM -> "系统字体"
            FAMILY_SOURCE_HAN_SERIF -> "思源宋体"
            else -> File(value).name.substringBeforeLast('.', File(value).name)
        }

    private fun kotlinPair(first: Any?, second: Any?): Any =
        cls(KOTLIN_PAIR_CLASS)
            .getDeclaredConstructor(Any::class.java, Any::class.java)
            .newInstance(first, second)

    private fun callMethod(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching { XposedHelpers.callMethod(target, name) }.getOrNull()
    }

    private fun staticMethod(className: String, name: String): Method? =
        cls(className).methods.firstOrNull {
            it.hostNameMatches(name) && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }

    private fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        runCatching {
            cls(className).run {
                runCatching { getDeclaredField(fieldName) }
                    .recoverCatching { getField(fieldName) }
                    .getOrThrow()
                    .apply { isAccessible = true }
                    .get(null)
            }
        }.getOrNull()

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.hostNameMatches(methodName) && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: cls(className).methods.firstOrNull {
                        it.hostNameMatches(methodName) && it.parameterTypes.size == parameterCount
                    }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun field(className: String, fieldName: String): Field {
        val cacheKey = "$className#$fieldName"
        return synchronized(fieldCache) {
            fieldCache.getOrPut(cacheKey) {
                XposedHelpers.findField(cls(className), fieldName).apply { isAccessible = true }
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any =
        cls(className).run {
            runCatching { getDeclaredField(fieldName) }
                .recoverCatching { getField(fieldName) }
                .getOrThrow()
                .apply { isAccessible = true }
                .get(null) ?: error("$className.$fieldName is null")
        }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val FONT_PROVIDER_CLASS = HostClasses.Epub.FONT_PROVIDER
        const val FONT_FAMILY_CLASS = HostClasses.Compose.FONT_FAMILY
        const val FONT_FAMILY_KT_CLASS = HostClasses.Compose.FONT_FAMILY_KT
        const val FONT_WEIGHT_CLASS = HostClasses.Compose.FONT_WEIGHT
        const val KOTLIN_PAIR_CLASS = HostClasses.Kotlin.KOTLIN_PAIR
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        fun isBuiltinFontSelection(selection: String): Boolean =
            selection == FAMILY_SYSTEM || selection == FAMILY_SOURCE_HAN_SERIF

        val SONG_FAMILY_KEYS = setOf(
            "宋体",
            "思源宋体",
            "st",
            "songti",
            "simsun",
            "nsimsun",
            "stsong",
            "sourcehanserif",
            "sourcehanserifcn",
            "notoserifcjk",
            "notoserifcjksc",
        )
        val KAI_FAMILY_KEYS = setOf(
            "楷体",
            "kt",
            "kaiti",
            "simkai",
            "stkaiti",
            "kaitisc",
            "kaitigb2312",
        )
    }
}
