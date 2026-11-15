package com.reamicro.fix.hook


import android.app.Activity
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File

class ReaderFontCompletionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private val fontFamilyCache = ReaderFontFamilyCache<Any>()
    private val failedFontFamilyLogKeys = LinkedHashSet<String>()
    private val appliedMappingLogKeys = LinkedHashSet<String>()
    @Volatile private var lastMappingNamesLogKey: String = ""

    fun install() {
        // 字体映射只依赖 FontProvider，不重建宿主配置，保留新版边距及后续新增字段。
        hookFontProvider()
    }

    fun releaseMemory(reason: String) {
        fontFamilyCache.clear()
        synchronized(failedFontFamilyLogKeys) { failedFontFamilyLogKeys.clear() }
        synchronized(appliedMappingLogKeys) { appliedMappingLogKeys.clear() }
        lastMappingNamesLogKey = ""
        HookRuntime.log("$LOG_PREFIX font completion memory released: $reason")
    }

    private fun hookFontProvider() {
        HookInstallReport.install("ReaderFontCompletionHook", "hookFontProvider") {
            val contract = requireNotNull(com.reamicro.fix.core.HostReaderExtrasBindings.fontCompletion) { "Font completion contract unavailable" }
            requireNotNull(com.reamicro.fix.core.HostReaderExtrasBindings.fontFamilies) { "Native font loaders unavailable" }
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(contract.method("withName"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null || !settings.snapshot().canUseFontSettings) return
                        if (!contract.field("embedded").getBoolean(null) || !contract.field("builtIn").getBoolean(null)) return
                        val arguments = param.args.singleOrNull() as? Array<*> ?: return
                        val names = arguments.mapNotNull { item -> item?.let { contract.field("name").get(it) as? String } }
                        val attached = contract.field("attached").get(null) as? Map<*, *> ?: return
                        if (names.any { attached.containsKey(contract.method("fontKey").invoke(null, it)) }) return
                        val config = settings.fontSettings()
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
                val row = contract.method("row")
                handles += HookRuntime.hookMethod(row, object : HookCallback() {
                    override fun before(param: Call) {
                        if (!settings.snapshot().canUseFontSettings) return
                        val pair = param.args[contract.index("row.pair")] ?: return
                        val label = contract.field("first").get(pair) as? String ?: return
                        val config = settings.fontSettings()
                        val mapped = when {
                            isSongFamilyName(label) -> config.songMapping
                            isKaiFamilyName(label) -> config.kaiMapping
                            else -> return
                        }
                        if (mapped.isBlank()) return
                        val family = resolveFontFamily(mapped) ?: return
                        param.args[contract.index("row.pair")] = contract.constructor("pair").newInstance(mappedLabel(label, mapped), family)
                        val flags = contract.index("row.flags")
                        param.args[flags] = (param.args[flags] as Int) and contract.index("row.mask").inv()
                    }
                })
                contract.methods("rowCaller.").forEach(HookRuntime::deoptimize)
                HookRuntime.deoptimize(row)
            } catch (failure: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() } }
                throw failure
            }
            HookRuntime.log("$LOG_PREFIX font completion and native preview hooks installed")
        }
    }

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
        HookRuntime.log("$LOG_PREFIX font mapping names: $key")
    }

    private fun logMappingApplied(target: String, selection: String) {
        val key = "$target|$selection"
        synchronized(appliedMappingLogKeys) {
            if (!appliedMappingLogKeys.add(key)) return
            while (appliedMappingLogKeys.size > MAX_FONT_LOG_KEYS) {
                appliedMappingLogKeys.iterator().run { next(); remove() }
            }
        }
        HookRuntime.log("$LOG_PREFIX font mapping applied: $target -> ${displayFontName(selection)}")
    }

    private fun resolveFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        val resolvedFile = if (isBuiltinFontSelection(selection)) null else resolveFontFile(selection) ?: return null
        val cacheKey = com.reamicro.fix.epub.editor.FontSelectionResolution.cacheKey(selection, resolvedFile)
        return fontFamilyCache.getOrLoad(cacheKey) {
            if (isBuiltinFontSelection(selection)) {
                resolveBuiltinFontFamily(selection)
            } else {
                val file = resolvedFile ?: return@getOrLoad null
                runCatching {
                    com.reamicro.fix.core.HostFontFamilies.fromFile(classLoader, file)
                }.onFailure { logResolveFontFamilyFailure(selection, it) }.getOrNull()
            }
        }
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

    private fun logResolveFontFamilyFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedFontFamilyLogKeys) {
            if (!failedFontFamilyLogKeys.add(key)) return
            while (failedFontFamilyLogKeys.size > MAX_FONT_LOG_KEYS) {
                failedFontFamilyLogKeys.iterator().run { next(); remove() }
            }
        }
        HookRuntime.log(
            "$LOG_PREFIX resolve font family failed: " +
                "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun resolveFontFile(selection: String): File? {
        val activity = activityProvider() ?: return null
        val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull() ?: return null
        val file = runCatching { com.reamicro.fix.epub.editor.AccountFontFiles.resolve(
            activity.filesDir, identity.accountId, selection,
        ) }.getOrNull() ?: return null
        return file.takeIf { runCatching { AccountHostRuntime.isCurrentStorage(identity, activity) }.getOrDefault(false) }
    }

    private fun mappedLabel(label: String, mapped: String): String =
        if (mapped.isBlank()) label else "$label（${displayFontName(mapped)}）"

    private fun displayFontName(value: String): String =
        when (value) {
            FAMILY_SYSTEM -> "系统字体"
            FAMILY_SOURCE_HAN_SERIF -> "思源宋体"
            else -> File(value).name.substringBeforeLast('.', File(value).name)
        }

    private companion object {
        const val MAX_FONT_LOG_KEYS = 64
        const val LOG_PREFIX = "ReaMicro LSP"
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
