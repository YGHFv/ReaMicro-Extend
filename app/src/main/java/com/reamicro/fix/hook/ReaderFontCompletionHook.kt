package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
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
    @Volatile private var activeReader: ActiveReader? = null
    @Volatile private var lastMappingNamesLogKey: String = ""

    fun install() {
        hookReaderViewModel()
        hookPagerInput()
        hookFontProvider()
    }

    private fun hookReaderViewModel() {
        runCatching {
            val readerViewModelClass = cls(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(readerViewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bookId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                    activeReader = ActiveReader(
                        bookId = bookId,
                        viewModelRef = WeakReference(param.thisObject),
                    )
                    XposedBridge.log("$LOG_PREFIX font completion reader captured: bookId=$bookId")
                }
            })
            XposedBridge.hookAllMethods(readerViewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val active = activeReader ?: return
                    if (active.viewModelRef.get() !== param.thisObject) return
                    activeReader = null
                }
            })
            val applyConfig = readerViewModelClass.declaredMethods.firstOrNull {
                it.name == APPLY_EPUB_CONFIG_METHOD && it.parameterTypes.size == 1
            } ?: error("$APPLY_EPUB_CONFIG_METHOD not found")
            applyConfig.isAccessible = true
            XposedBridge.hookMethod(applyConfig, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = param.args?.getOrNull(0) ?: return
                    val snapshot = settings.snapshot()
                    if (!snapshot.canRunFontCompletion) return
                    val current = readResolvedTypeSetting(config)
                    val active = activeReader?.takeIf { it.viewModelRef.get() === param.thisObject }

                    val prepared = active?.takeIf { it.lastRenderConfig == current }?.lastEffectiveConfig
                    val effective = prepared ?: current
                    param.args[0] = newReaderEpubConfig(effective)
                }
            })
            XposedBridge.log("$LOG_PREFIX font completion ReaderViewModel hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX font completion ReaderViewModel hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookPagerInput() {
        runCatching {
            val pagerInputClass = cls(READER_PAGER_INPUT_CLASS)
            XposedBridge.hookAllConstructors(pagerInputClass, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = param.args?.getOrNull(1) ?: return
                    val snapshot = settings.snapshot()
                    if (!snapshot.canRunFontCompletion) return
                    val current = readResolvedTypeSetting(config)
                    val active = activeReader
                    val effective = current
                    active?.lastEffectiveConfig = effective
                    active?.lastRenderConfig = effective
                    param.args[1] = newReaderEpubConfig(effective)
                }
            })
            XposedBridge.log("$LOG_PREFIX font completion PagerInput hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX font completion PagerInput hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookFontProvider() {
        runCatching {
            val fontProviderClass = cls(FONT_PROVIDER_CLASS)
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
            XposedBridge.hookAllMethods(fontProviderClass, "getBuildInFonts", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!settings.snapshot().canUseFontSettings) return
                    val list = param.result as? List<*> ?: return
                    if (list.isEmpty()) return
                    val config = settings.fontSettings()
                    param.result = list.mapIndexed { index, pair ->
                        val first = when (index) {
                            0 -> "宋体"
                            1 -> "楷体"
                            else -> callMethod(pair, "getFirst")?.toString().orEmpty()
                        }
                        val second = callMethod(pair, "getSecond") ?: return@mapIndexed pair
                        val mapped = when (index) {
                            0 -> config.songMapping
                            1 -> config.kaiMapping
                            else -> ""
                        }
                        kotlinPair(mappedLabel(first, mapped), resolveFontFamily(mapped) ?: second)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX font completion FontProvider hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX font completion FontProvider hook failed: ${it.stackTraceToString()}")
        }
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

    private fun readResolvedTypeSetting(config: Any): ResolvedTypeSetting =
        ResolvedTypeSetting(
            family = callMethod(config, "getFamily")?.toString().orEmpty(),
            textSize = (callMethod(config, "getTextSize") as? Number)?.toFloat() ?: 17f,
            lineHeight = (callMethod(config, "getLineHeight") as? Number)?.toFloat() ?: 1.5f,
            padding = (callMethod(config, "getPadding") as? Number)?.toInt() ?: 20,
            embeddedFonts = callMethod(config, "getEmbeddedFonts") as? Boolean ?: true,
            buildInFonts = callMethod(config, "getBuildInFonts") as? Boolean ?: true,
        )

    private fun newReaderEpubConfig(value: ResolvedTypeSetting): Any =
        cls(READER_EPUB_CONFIG_CLASS)
            .getDeclaredConstructor(
                String::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .newInstance(
                value.family,
                value.textSize,
                value.lineHeight,
                value.padding,
                value.embeddedFonts,
                value.buildInFonts,
            )

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        synchronized(fontFamilyCache) {
            fontFamilyCache[selection]?.let { return it }
        }
        if (isBuiltinFontSelection(selection)) {
            return resolveBuiltinFontFamily(selection)?.also { family ->
                synchronized(fontFamilyCache) {
                    fontFamilyCache[selection] = family
                }
            }
        }
        val file = resolveFontFile(selection) ?: return null
        return runCatching {
            val provider = staticObject(FONT_PROVIDER_CLASS, "INSTANCE")
            val normal = fontWeight("getNormal")
            val bold = fontWeight("getBold")
            val fromPath = method(FONT_PROVIDER_CLASS, "fromPath", 2)
            val fonts = listOfNotNull(
                fromPath.invoke(provider, file.absolutePath, normal),
                fromPath.invoke(provider, file.absolutePath, bold),
            )
            if (fonts.isEmpty()) return null
            val family = cls(FONT_FAMILY_KT_CLASS).declaredMethods.first {
                it.name == "FontFamily" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
            }.invoke(null, fonts) ?: return null
            synchronized(fontFamilyCache) {
                fontFamilyCache[selection] = family
            }
            family
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> callMethod(staticObject(FONT_PROVIDER_CLASS, "INSTANCE"), "builtInSong")
                else -> null
            }
        }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? {
        fieldObjectOrNull(FONT_FAMILY_CLASS, "Default")?.let { return it }
        for (fieldName in listOf("Companion", "INSTANCE")) {
            val companion = fieldObjectOrNull(FONT_FAMILY_CLASS, fieldName) ?: continue
            callMethod(companion, "getDefault")?.let { return it }
        }
        return staticMethod(FONT_FAMILY_CLASS, "getDefault")?.invoke(null)
    }

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
                it.name == methodName && it.parameterTypes.isEmpty()
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

    private fun resolveFontFile(selection: String): File? {
        val direct = File(selection)
        if (direct.isFile && isFontFileName(direct.name)) return direct
        val name = direct.name
        if (!isFontFileName(name)) return null
        val root = activityProvider()?.filesDir ?: return null
        return fontDirectories(root)
            .asSequence()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.isFile && it.name == name && isFontFileName(it.name) }
    }

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
        return target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.invoke(target)
    }

    private fun staticMethod(className: String, name: String): Method? =
        cls(className).methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
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
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: cls(className).methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.size == parameterCount
                    }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun field(className: String, fieldName: String): Field {
        val cacheKey = "$className#$fieldName"
        return synchronized(fieldCache) {
            fieldCache.getOrPut(cacheKey) {
                cls(className).run {
                    runCatching { getDeclaredField(fieldName) }
                        .recoverCatching { getField(fieldName) }
                        .getOrThrow()
                        .apply { isAccessible = true }
                }
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

    private data class ActiveReader(
        val bookId: Long,
        val viewModelRef: WeakReference<Any>,
        @Volatile var lastEffectiveConfig: ResolvedTypeSetting? = null,
        @Volatile var lastRenderConfig: ResolvedTypeSetting? = null,
    )

    private data class ResolvedTypeSetting(
        val family: String,
        val textSize: Float,
        val lineHeight: Float,
        val padding: Int,
        val embeddedFonts: Boolean,
        val buildInFonts: Boolean,
    )

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val READER_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        const val READER_EPUB_CONFIG_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel\$ReaderEpubConfig"
        const val READER_PAGER_INPUT_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel\$PagerInput"
        const val APPLY_EPUB_CONFIG_METHOD = "applyEpubConfig"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val KOTLIN_PAIR_CLASS = "kotlin.Pair"
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
