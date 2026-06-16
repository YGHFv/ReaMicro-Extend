package com.reamicro.fix.hook

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.settings.ReaderTypeSettingSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import kotlin.text.RegexOption

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
        hookSessionUpdate()
        hookFontProvider()
    }

    private fun hookReaderViewModel() {
        runCatching {
            val readerViewModelClass = cls(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(readerViewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bookId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                    val session = param.args?.getOrNull(2) ?: return
                    activeReader = ActiveReader(
                        bookId = bookId,
                        viewModelRef = WeakReference(param.thisObject),
                        sessionRef = WeakReference(session),
                    )
                    XposedBridge.log("$LOG_PREFIX font completion reader captured: bookId=$bookId")
                }
            })
            XposedBridge.hookAllMethods(readerViewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val active = activeReader ?: return
                    if (active.viewModelRef.get() !== param.thisObject) return
                    restoreGlobalTypeSetting(active)
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
                    if (active?.originalConfig == null) active?.originalConfig = current

                    val prepared = active?.takeIf { it.lastRenderConfig == current }?.lastEffectiveConfig
                    val effective = prepared ?: effectiveTypeSetting(current, active, snapshot.canIsolateReaderTypeSetting)
                    param.args[0] = newReaderEpubConfig(effective)
                    if (snapshot.canIsolateReaderTypeSetting && active != null) {
                        syncSessionForActiveBook(active, effective)
                    }
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
                    if (active?.originalConfig == null) active?.originalConfig = current
                    val effective = effectiveTypeSetting(current, active, snapshot.canIsolateReaderTypeSetting)
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

    private fun hookSessionUpdate() {
        runCatching {
            XposedBridge.hookAllMethods(cls(SESSION_CLASS), "update", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val active = activeReader ?: return
                    if (active.sessionRef.get() !== param.thisObject) return
                    if (active.syncing || active.restoring) return
                    if (!settings.snapshot().canDetectObfuscatedFonts) return
                    if (shouldBlockEmbeddedFontDisable(active, param)) {
                        param.setObjectExtra(BLOCKED_EMBEDDED_FONT_DISABLE, true)
                        param.result = targetUnit()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val active = activeReader ?: return
                    if (active.sessionRef.get() !== param.thisObject) return
                    if (active.syncing || active.restoring) return
                    if (param.getObjectExtra(BLOCKED_EMBEDDED_FONT_DISABLE) == true) return
                    if (!settings.snapshot().canIsolateReaderTypeSetting) return
                    val keyName = param.args?.getOrNull(0)?.toString().orEmpty()
                    val typeKey = typeSettingKeyName(keyName) ?: return
                    settings.setBookTypeSettingValue(active.bookId, typeKey, param.args?.getOrNull(1))
                    XposedBridge.log("$LOG_PREFIX isolated type setting saved: bookId=${active.bookId}, key=$typeKey")
                }
            })
            XposedBridge.log("$LOG_PREFIX font completion Session.update hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX font completion Session.update hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookFontProvider() {
        runCatching {
            val fontProviderClass = cls(FONT_PROVIDER_CLASS)
            XposedBridge.hookAllMethods(fontProviderClass, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val active = activeReader ?: return
                    if (!settings.snapshot().canDetectObfuscatedFonts) return
                    val cssPath = param.args?.getOrNull(0)?.toString().orEmpty()
                    val ruleSets = param.args?.getOrNull(1) as? Array<*> ?: return
                    rememberAttachedFonts(active, cssPath, ruleSets)
                }
            })
            XposedBridge.hookAllMethods(fontProviderClass, "withName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val names = fontFamilyArgumentNames(param.args)
                    activeReader?.takeIf { settings.snapshot().canDetectObfuscatedFonts }?.let {
                        inspectRequestedAttachedFonts(it, names)
                    }
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

    private fun isolatedTypeSetting(stored: ReaderTypeSettingSnapshot, current: ResolvedTypeSetting): ResolvedTypeSetting {
        return ResolvedTypeSetting(
            family = current.family,
            textSize = current.textSize,
            lineHeight = current.lineHeight,
            padding = current.padding,
            embeddedFonts = stored.embeddedFonts ?: current.embeddedFonts,
            buildInFonts = current.buildInFonts,
        )
    }

    private fun effectiveTypeSetting(
        current: ResolvedTypeSetting,
        active: ActiveReader?,
        isolationEnabled: Boolean,
    ): ResolvedTypeSetting {
        if (!isolationEnabled || active == null) return current
        return isolatedTypeSetting(settings.bookTypeSetting(active.bookId), current)
    }

    private fun syncSessionForActiveBook(active: ActiveReader, target: ResolvedTypeSetting) {
        if (active.synced) return
        val session = active.sessionRef.get() ?: return
        active.synced = true
        Thread {
            active.syncing = true
            runCatching {
                updateSession(session, target)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX sync isolated type setting failed: ${it.stackTraceToString()}")
            }
            active.syncing = false
        }.apply {
            name = "ReaMicro-FontIsolationSync"
            isDaemon = true
            start()
        }
    }

    private fun restoreGlobalTypeSetting(active: ActiveReader) {
        val original = active.originalConfig ?: return
        val session = active.sessionRef.get() ?: return
        if (!settings.snapshot().canIsolateReaderTypeSetting) return
        Thread {
            active.restoring = true
            runCatching {
                updateSession(session, original)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX restore global type setting failed: ${it.stackTraceToString()}")
            }
            active.restoring = false
        }.apply {
            name = "ReaMicro-FontIsolationRestore"
            isDaemon = true
            start()
        }
    }

    private fun updateSession(session: Any, value: ResolvedTypeSetting) {
        val update = method(SESSION_CLASS, "update", 3)
        invokeSuspendBlocking(update, session, prefKey("getEMBEDDED_FONTS"), value.embeddedFonts)
    }

    private fun prefKey(getterName: String): Any {
        val prefKeys = staticObject(PREF_KEYS_CLASS, "INSTANCE")
        return prefKeys.javaClass.methods.first {
            it.name == getterName && it.parameterTypes.isEmpty()
        }.invoke(prefKeys)
    }

    private fun typeSettingKeyName(prefName: String): String? =
        when (prefName) {
            "embedded_fonts" -> "embedded_fonts"
            else -> null
        }

    private fun shouldBlockEmbeddedFontDisable(active: ActiveReader, param: XC_MethodHook.MethodHookParam): Boolean {
        val keyName = param.args?.getOrNull(0)?.toString().orEmpty()
        val requestedValue = param.args?.getOrNull(1) as? Boolean
        if (keyName != "embedded_fonts" || requestedValue != false) {
            active.pendingEmbeddedFontDisableAtMs = 0L
            return false
        }
        if (!active.hasObfuscatedEmbeddedFonts) return false
        val now = System.currentTimeMillis()
        val pendingAt = active.pendingEmbeddedFontDisableAtMs
        if (pendingAt > 0L && now - pendingAt <= FORCE_DISABLE_EMBEDDED_FONT_WINDOW_MS) {
            active.pendingEmbeddedFontDisableAtMs = 0L
            XposedBridge.log("$LOG_PREFIX embedded font disable force-allowed: bookId=${active.bookId}")
            return false
        }
        active.pendingEmbeddedFontDisableAtMs = now
        showToast("本书疑似存在字体混淆，不建议关闭 EPUB 字体；再点一次可强制关闭")
        XposedBridge.log(
            "$LOG_PREFIX embedded font disable blocked: bookId=${active.bookId}, reason=${active.obfuscatedFontReason.orEmpty()}",
        )
        return true
    }

    private fun rememberAttachedFonts(active: ActiveReader, cssPath: String, ruleSets: Array<*>) {
        var remembered = 0
        for (ruleSet in ruleSets) {
            val candidates = extractAttachedFontCandidates(cssPath, ruleSet)
            if (candidates.isEmpty()) continue
            synchronized(active.attachedFontFilesByFamily) {
                for (candidate in candidates) {
                    val familyKey = normalizedFamilyName(candidate.familyName)
                    val files = active.attachedFontFilesByFamily.getOrPut(familyKey) { mutableListOf() }
                    if (files.none { it.absolutePath == candidate.file.absolutePath }) {
                        files.add(candidate.file)
                        remembered++
                    }
                }
            }
        }
        if (remembered > 0) {
            XposedBridge.log("$LOG_PREFIX epub font candidates remembered: bookId=${active.bookId}, count=$remembered")
        }
    }

    private fun inspectRequestedAttachedFonts(active: ActiveReader, names: List<String>) {
        if (active.hasObfuscatedEmbeddedFonts || names.isEmpty()) return
        val candidateFiles = synchronized(active.attachedFontFilesByFamily) {
            names.asSequence()
                .map(::normalizedFamilyName)
                .flatMap { familyKey -> active.attachedFontFilesByFamily[familyKey].orEmpty().asSequence() }
                .distinctBy { it.absolutePath }
                .toList()
        }
        val fontFile = candidateFiles.maxByOrNull { it.length() } ?: return
        inspectFontFile(active, fontFile, names)
    }

    private fun inspectFontFile(active: ActiveReader, fontFile: File, names: List<String>) {
        val normalizedPath = fontFile.absolutePath
        val shouldInspect = synchronized(active.inspectedFontPaths) {
            active.inspectedFontPaths.add(normalizedPath)
        }
        if (!shouldInspect) return
        val result = EpubFontObfuscationDetector.detect(fontFile)
        XposedBridge.log(
            "$LOG_PREFIX epub font inspect: bookId=${active.bookId}, requested=${names.joinToString("|")}, path=$normalizedPath, " +
                "suspicious=${result.suspicious}, reason=${result.reason}, detail=${result.detail}",
        )
        if (!result.suspicious) return
        active.hasObfuscatedEmbeddedFonts = true
        active.obfuscatedFontReason = "${fontFile.name}:${result.reason}${formatDetectionDetail(result.detail)}"
        active.pendingEmbeddedFontDisableAtMs = 0L
        XposedBridge.log(
            "$LOG_PREFIX epub font obfuscation detected: bookId=${active.bookId}, ${active.obfuscatedFontReason}",
        )
    }

    private fun extractAttachedFontCandidates(cssPath: String, ruleSet: Any?): List<AttachedFontCandidate> {
        val declarations = callMethod(ruleSet, "getDeclarations") as? Iterable<*> ?: return emptyList()
        var familyName: String? = null
        var srcValue: String? = null
        for (declaration in declarations) {
            val property = callMethod(declaration, "getProperty")?.toString().orEmpty()
            when (property) {
                "font-family" -> {
                    familyName = callMethod(declaration, "getValue")
                        ?.toString()
                        ?.trim()
                        ?.trim('"', '\'')
                        ?.takeIf { it.isNotEmpty() }
                }
                "src" -> {
                    srcValue = callMethod(declaration, "getValue")?.toString()
                }
            }
        }
        if (familyName.isNullOrBlank() || srcValue.isNullOrBlank()) return emptyList()
        val baseDir = File(cssPath).parentFile
        return extractUrlValues(srcValue)
            .mapNotNull { resolveAttachedFontFile(baseDir, it) }
            .filter { it.isFile && isFontFileName(it.name) }
            .map { AttachedFontCandidate(familyName = familyName, file = it) }
    }

    private fun extractUrlValues(srcValue: String): List<String> =
        FONT_URL_REGEX.findAll(srcValue)
            .mapNotNull { match ->
                match.groups[2]?.value
                    ?.substringBefore('#')
                    ?.substringBefore('?')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .toList()

    private fun resolveAttachedFontFile(baseDir: File?, value: String): File? {
        val normalized = value.replace('\\', File.separatorChar).replace('/', File.separatorChar)
        val candidate = File(normalized)
        val resolved = if (candidate.isAbsolute) candidate else File(baseDir, normalized)
        return resolved.takeIf { it.exists() }
    }

    private fun formatDetectionDetail(detail: String): String =
        if (detail.isBlank()) "" else " ($detail)"

    private fun showToast(message: String) {
        val activity = activityProvider() ?: return
        runCatching {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = cls(KOTLIN_CONTINUATION_CLASS)
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext()
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure(result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    targetUnit()
                }
                "toString" -> "ReaMicroFontContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val returned = try {
            method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended()) return returned
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun throwOnFailure(value: Any?) {
        cls(KOTLIN_RESULT_KT_CLASS).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }.invoke(null, value)
    }

    private fun emptyCoroutineContext(): Any =
        staticObject(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, "INSTANCE")

    private fun coroutineSuspended(): Any =
        runCatching {
            cls(KOTLIN_INTRINSICS_CLASS).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.invoke(null) ?: error("COROUTINE_SUSPENDED not found")
        }.getOrElse {
            cls(KOTLIN_COROUTINE_SINGLETONS_CLASS).enumConstants.orEmpty()
                .first { it.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun targetUnit(): Any =
        staticObject(KOTLIN_UNIT_CLASS, "INSTANCE")

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
        val sessionRef: WeakReference<Any>,
        @Volatile var originalConfig: ResolvedTypeSetting? = null,
        @Volatile var lastEffectiveConfig: ResolvedTypeSetting? = null,
        @Volatile var lastRenderConfig: ResolvedTypeSetting? = null,
        @Volatile var hasObfuscatedEmbeddedFonts: Boolean = false,
        @Volatile var obfuscatedFontReason: String? = null,
        val attachedFontFilesByFamily: MutableMap<String, MutableList<File>> = LinkedHashMap(),
        val inspectedFontPaths: MutableSet<String> = LinkedHashSet(),
        @Volatile var pendingEmbeddedFontDisableAtMs: Long = 0L,
        @Volatile var synced: Boolean = false,
        @Volatile var syncing: Boolean = false,
        @Volatile var restoring: Boolean = false,
    )

    private data class AttachedFontCandidate(
        val familyName: String,
        val file: File,
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
        const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
        const val PREF_KEYS_CLASS = "app.zhendong.reamicro.constants.PrefKeys"
        const val UI_EPUB_WINDOW_CLASS = "org.epub.UIEpubWindow"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val KOTLIN_PAIR_CLASS = "kotlin.Pair"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        const val BLOCKED_EMBEDDED_FONT_DISABLE = "reamicro.blockedEmbeddedFontDisable"
        const val FORCE_DISABLE_EMBEDDED_FONT_WINDOW_MS = 3000L
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        val FONT_URL_REGEX = Regex("""url\s*\(\s*(['"]?)([^)'"]+)\1\s*\)""", RegexOption.IGNORE_CASE)
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
