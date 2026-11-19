package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import org.luckypray.dexkit.wrap.DexMethod
import org.luckypray.dexkit.wrap.DexField

/** Compatibility report facade over code queries and a single current-host cache. */
object HostAutoScanner {
    @Volatile private var last: Report? = null
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var latestValues: Map<String, String>? = null
    @Volatile private var installedValues: Map<String, String>? = null
    private val installed = HostScanInstalledBaseline()
    @Volatile private var installedSettings: Map<String, String>? = null
    @Volatile private var installedUi: Map<String, String>? = null
    @Volatile private var installedRuntime: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedIntegrations: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedBooks: Map<String, String>? = null
    @Volatile private var installedBookCover: Map<String, String>? = null
    @Volatile private var installedSync: Map<String, String>? = null
    @Volatile private var installedReaderGuards: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedReaderMenu: Map<String, String>? = null
    @Volatile private var installedTypography: Map<String, String>? = null
    @Volatile private var installedStorage: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedSelection: Map<String, String>? = null
    @Volatile private var installedHighlightDom: Map<String, String>? = null
    @Volatile private var installedHighlightText: Map<String, String>? = null
    @Volatile private var installedReaderRender: Map<String, String>? = null
    @Volatile private var installedReaderRecords: Map<String, String>? = null
    @Volatile private var installedReaderPosition: Map<String, String>? = null
    @Volatile private var installedAccount: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedProfile: Map<String, String>? = null
    @Volatile private var installedComposition: Map<String, String>? = null
    @Volatile private var installedDialog: Map<String, String>? = null
    @Volatile private var installedBrowser: Map<String, String>? = null
    @Volatile private var installedCrash: Map<String, String>? = null
    @Volatile private var installedBookUi: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedGraphics: Map<String, String>? = null
    @Volatile private var installedDiscover: Map<String, String>? = null
    @Volatile private var installedGlobalFont: Map<String, String>? = null
    @Volatile private var installedAssociation: Map<String, String>? = null
    @Volatile private var installedManualEdit: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedParagraphComments: Map<String, String>? = null
    @Volatile private var installedReaderLifecycle: Map<String, String>? = null
    @Volatile private var installedReaderScroll: Map<String, String>? = null
    @Volatile private var installedReaderPager: Map<String, String>? = null
    @Volatile private var installedReaderWindow: Map<String, String>? = null
    @Volatile private var installedHighlightUi: Map<String, String>? = null
    @Volatile private var installedEpubRuntime: Map<String, String>? = null
    @Volatile private var installedEpubSource: Map<String, String>? = null
    @Volatile private var installedEpubServices: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedWork: Map<String, String>? = null
    @Volatile private var installedHomeSearch: Map<String, String>? = null
    @Volatile private var installedHome: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var installedReaderExtras: Map<String, Map<String, String>> = emptyMap()

    data class Report(val total: Int, val succeeded: Int, val failures: List<String>, val cached: Boolean,
        val durationMs: Long, val unchanged: Boolean, val profile: HostProfile.Parsed?,
        val complete: Boolean = true,
        val featureTotal: Int = 0,
        val featureSucceeded: Int = 0,
        val runtimeFailures: List<String> = failures,
        val catalogUnresolved: List<String> = emptyList()) {
        val functionalityComplete: Boolean get() = profile != null && featureTotal > 0 &&
            featureSucceeded == featureTotal && runtimeFailures.isEmpty()
        fun summary(): String = "DexKit 功能查询 $featureSucceeded/$featureTotal，符号目录 $succeeded/$total，" +
            "功能错误 ${runtimeFailures.size} 项，目录未解析 ${catalogUnresolved.size} 项" +
            if (cached) "（缓存）" else "（${durationMs}ms）"
    }

    internal fun configure(classLoader: ClassLoader) {
        loader = classLoader
        last = null
        latestValues = null
        installedValues = null
        installed.reset()
        installedSettings = null
        installedUi = null
        installedRuntime = emptyMap()
        installedIntegrations = emptyMap()
        installedBooks = null
        installedBookCover = null
        installedSync = null
        installedReaderGuards = emptyMap()
        installedReaderMenu = null
        installedTypography = null
        installedStorage = emptyMap()
        installedSelection = null
        installedHighlightDom = null
        installedHighlightText = null
        installedReaderRender = null
        installedReaderRecords = null
        installedReaderPosition = null
        installedAccount = emptyMap()
        installedProfile = null
        installedComposition = null
        installedDialog = null
        installedBrowser = null
        installedCrash = null
        installedBookUi = emptyMap()
        installedGraphics = null
        installedDiscover = null
        installedGlobalFont = null
        installedAssociation = null
        installedManualEdit = emptyMap()
        installedParagraphComments = null
        installedReaderLifecycle = null
        installedReaderScroll = null
        installedReaderPager = null
        installedReaderWindow = null
        installedHighlightUi = null
        installedEpubRuntime = null
        installedEpubSource = null
        installedEpubServices = emptyMap()
        installedWork = null
        installedHomeSearch = null
        installedHome = emptyMap()
        installedReaderExtras = emptyMap()
    }
    /** Publish once after hook installation; scans only update reports and future cache entries. */
    internal fun markInstalled() {
        val reader = HostReaderFeatures.boundDescriptors()
        installed.capture(latestValues, reader)
        installedValues = latestValues?.toMap()
        installedSettings = HostSettingsFeatures.boundDescriptors()
        installedUi = HostUiBindings.boundDescriptors()
        installedRuntime = HostRuntimeBindings.boundDescriptors()
        installedIntegrations = HostIntegrationBindings.boundDescriptors()
        installedBooks = HostBookBindings.boundDescriptors()
        installedBookCover = HostBookCoverBindings.boundDescriptors()
        installedSync = HostSyncBindings.boundDescriptors()
        installedReaderGuards = HostReaderGuardBindings.boundDescriptors()
        installedReaderMenu = HostReaderMenuBindings.boundDescriptors()
        installedTypography = HostTypographyBindings.boundDescriptors()
        installedStorage = HostStorageBindings.boundDescriptors()
        installedSelection = HostSelectionBindings.boundDescriptors()
        installedHighlightDom = HostHighlightDomBindings.boundDescriptors()
        installedHighlightText = HostHighlightTextBindings.boundDescriptors()
        installedReaderRender = HostReaderRenderBindings.boundDescriptors()
        installedReaderRecords = HostReaderRecordBindings.boundDescriptors()
        installedReaderPosition = HostReaderPositionBindings.boundDescriptors()
        installedAccount = HostAccountBindings.boundDescriptors()
        installedProfile = HostProfileBindings.boundDescriptors()
        installedComposition = HostCompositionBindings.boundDescriptors()
        installedDialog = HostDialogBindings.boundDescriptors()
        installedBrowser = HostBrowserBindings.boundDescriptors()
        installedCrash = HostCrashBindings.boundDescriptors()
        installedBookUi = HostBookUiBindings.boundDescriptors()
        installedGraphics = HostGraphicsBindings.boundDescriptors()
        installedDiscover = HostDiscoverBindings.boundDescriptors()
        installedGlobalFont = HostGlobalFontBindings.boundDescriptors()
        installedAssociation = HostAssociationBindings.boundDescriptors()
        installedManualEdit = HostManualEditBindings.boundDescriptors()
        installedParagraphComments = HostParagraphCommentBindings.boundDescriptors()
        installedReaderLifecycle = HostReaderLifecycleBindings.boundDescriptors()
        installedReaderScroll = HostReaderScrollBindings.boundDescriptors()
        installedReaderPager = HostReaderPagerBindings.boundDescriptors()
        installedReaderWindow = HostReaderWindowBindings.boundDescriptors()
        installedHighlightUi = HostHighlightUiBindings.boundDescriptors()
        installedEpubRuntime = HostEpubRuntimeBindings.boundDescriptors()
        installedEpubSource = HostEpubSourceBindings.boundDescriptors()
        installedEpubServices = HostEpubServicesBindings.boundDescriptors()
        installedWork = HostWorkBindings.boundDescriptors()
        installedHomeSearch = HostHomeSearchBindings.boundDescriptors()
        installedHome = HostHomeBindings.boundDescriptors()
        installedReaderExtras = HostReaderExtrasBindings.boundDescriptors()
        val missing = HostBindingBootstrap.missing().map { "$it: binding unavailable" }
        last = last?.let { report -> report.copy(
            unchanged = installed.matches(latestValues, reader),
            failures = (report.failures + missing).distinct(),
            runtimeFailures = (report.runtimeFailures + missing).distinct(),
            complete = report.complete && missing.isEmpty(),
        ) }
    }
    fun snapshot(): Report? = last
    /** Descriptors from this installation, never results awaiting a host restart. */
    internal fun installedDescriptor(key: String): String? = installedValues?.get(key)
    internal fun queriedDescriptors(): Map<String, String>? = latestValues?.toMap()
    fun cachedProfile(apk: String, dataDir: String): Report? = scanInternal(apk, dataDir, false, true)
    fun restoreCachedReport(apk: String, dataDir: String): Report? = last ?: cachedProfile(apk, dataDir)
    fun scan(apk: String, dataDir: String, force: Boolean = false): Report? = scanInternal(apk, dataDir, force, false)

    private fun scanInternal(apk: String, dataDir: String, force: Boolean, cacheOnly: Boolean): Report? {
        val started = System.nanoTime()
        return try {
            val activeLoader = requireNotNull(loader) { "Host query classloader is not configured" }
            HostQuerySession.use(activeLoader, apk, dataDir) { session ->
                val keys = listOf("symbols.types") + HostSemanticQueries.groups.map { "symbols.$it" }
                if (cacheOnly && keys.any { session.cached(it) == null }) return@use null
                if (cacheOnly && session.cached("symbols.records") == null) return@use null
                val types = session.query("symbols.types", force,
                    validate = session::validateDescriptors,
                    find = HostSemanticQueries::queryTypes)
                val values = types.values.toMutableMap()
                val queryErrors = linkedSetOf<String>()
                var allCached = types.cached
                HostSemanticQueries.groups.forEach { group ->
                    runCatching {
                        session.query("symbols.$group", force,
                            validate = session::validateDescriptors,
                            find = { bridge -> HostSemanticQueries.queryGroup(bridge, types.values, group) })
                    }.onSuccess { result ->
                        values.putAll(result.values)
                        allCached = allCached && result.cached
                    }.onFailure {
                        allCached = false
                        queryErrors += "$group: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro semantic query group failed: $group", it)
                    }
                }
                runCatching {
                    session.query("symbols.records", force,
                        validate = session::validateDescriptors,
                        find = { HostRecordQueries.query(apk, values) })
                }.onSuccess { result ->
                    // Exact field dataflow may supply missing accessors, never replace a proven method query.
                    result.values.forEach { (key, descriptor) -> values.putIfAbsent(key, descriptor) }
                    allCached = allCached && result.cached
                }.onFailure {
                    allCached = false
                    queryErrors += "symbols.records: ${it.javaClass.simpleName}: ${it.message}"
                    HookRuntime.logError("ReaMicro record property query failed", it)
                }
                var readerValues = HostReaderFeatures.boundDescriptors()
                var settingsValues = HostSettingsFeatures.boundDescriptors()
                var uiValues = HostUiBindings.boundDescriptors()
                var runtimeValues = HostRuntimeBindings.boundDescriptors()
                var integrationValues = HostIntegrationBindings.boundDescriptors()
                var bookValues = HostBookBindings.boundDescriptors()
                var bookCoverValues = HostBookCoverBindings.boundDescriptors()
                var syncValues = HostSyncBindings.boundDescriptors()
                var readerGuardValues = HostReaderGuardBindings.boundDescriptors()
                var readerMenuValues = HostReaderMenuBindings.boundDescriptors()
                var typographyValues = HostTypographyBindings.boundDescriptors()
                var selectionValues = HostSelectionBindings.boundDescriptors()
                var highlightDomValues = HostHighlightDomBindings.boundDescriptors()
                var highlightTextValues = HostHighlightTextBindings.boundDescriptors()
                var readerRenderValues = HostReaderRenderBindings.boundDescriptors()
                var readerRecordValues = HostReaderRecordBindings.boundDescriptors()
                var readerPositionValues = HostReaderPositionBindings.boundDescriptors()
                var storageValues = HostStorageBindings.boundDescriptors()
                var readerExtrasValues = HostReaderExtrasBindings.boundDescriptors()
                var accountValues = HostAccountBindings.boundDescriptors()
                var crashValues = HostCrashBindings.boundDescriptors()
                var compositionValues = HostCompositionBindings.boundDescriptors()
                var dialogValues = HostDialogBindings.boundDescriptors()
                var browserValues = HostBrowserBindings.boundDescriptors()
                var profileValues = HostProfileBindings.boundDescriptors()
                var graphicsValues = HostGraphicsBindings.boundDescriptors()
                var discoverValues = HostDiscoverBindings.boundDescriptors()
                var paragraphCommentValues = HostParagraphCommentBindings.boundDescriptors()
                var readerLifecycleValues = HostReaderLifecycleBindings.boundDescriptors()
                var readerScrollValues = HostReaderScrollBindings.boundDescriptors()
                var readerPagerValues = HostReaderPagerBindings.boundDescriptors()
                var readerWindowValues = HostReaderWindowBindings.boundDescriptors()
                var highlightUiValues = HostHighlightUiBindings.boundDescriptors()
                var epubRuntimeValues = HostEpubRuntimeBindings.boundDescriptors()
                var epubServiceValues = HostEpubServicesBindings.boundDescriptors()
                var homeSearchValues = HostHomeSearchBindings.boundDescriptors()
                var workValues = HostWorkBindings.boundDescriptors()
                var epubSourceValues = HostEpubSourceBindings.boundDescriptors()
                var associationValues = HostAssociationBindings.boundDescriptors()
                var manualEditValues = HostManualEditBindings.boundDescriptors()
                var globalFontValues = HostGlobalFontBindings.boundDescriptors()
                var bookUiValues = HostBookUiBindings.boundDescriptors()
                var homeValues = HostHomeBindings.boundDescriptors()
                if (force) {
                    // Refresh the reader cache for the next installation; installed hooks keep
                    // their current bindings until restart.
                    runCatching {
                        session.query("reader.controls", force = true,
                            validate = { HostReaderFeatures.restore(it, activeLoader) },
                            find = HostReaderFeatures::query)
                    }.onSuccess { readerValues = it.values }.onFailure {
                        readerValues = null
                        session.recordFailure("reader.controls", it)
                        queryErrors += "reader.controls: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader rescan failed", it)
                    }
                    // Recheck every settings contract initialized by this generation.
                    if (HostSettingsFeatures.initializationAttempted) runCatching {
                        session.query("settings.entry", force = true,
                            validate = { HostSettingsFeatures.restore(it, activeLoader) },
                            find = { HostSettingsFeatures.query(it, apk) })
                    }.onSuccess { settingsValues = it.values }.onFailure {
                        settingsValues = null
                        session.recordFailure("settings.entry", it)
                        queryErrors += "settings.entry: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro settings entry rescan failed", it)
                    }
                    if (HostUiBindings.initializationAttempted) runCatching {
                        session.query(HostUiBindings.KEY, force = true,
                            validate = { HostUiBindings.restore(it, activeLoader) },
                            find = { bridge -> HostNativeUiQueries.query(bridge, apk,
                                requireNotNull(readerValues), requireNotNull(settingsValues)) })
                    }.onSuccess { uiValues = it.values }.onFailure {
                        uiValues = null
                        session.recordFailure(HostUiBindings.KEY, it)
                        queryErrors += "${HostUiBindings.KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro native UI rescan failed", it)
                    }
                    if (HostRuntimeBindings.initializationAttempted) runCatching {
                        HostRuntimeBindings.rescan(session)
                    }.onSuccess { runtimeValues = it }.onFailure {
                        runtimeValues = emptyMap()
                        listOf("runtime.navigation", "runtime.http", "runtime.paging", "runtime.files", "runtime.coroutines").forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "runtime: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro runtime rescan failed", it)
                    }
                    if (HostIntegrationBindings.initializationAttempted) runCatching {
                        HostIntegrationBindings.rescan(session)
                    }.onSuccess { integrationValues = it }.onFailure {
                        integrationValues = emptyMap()
                        listOf("integration.androidView", "integration.webDavRoute", "integration.activity").forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "integration: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro integration rescan failed", it)
                    }
                    if (HostBookBindings.initializationAttempted) runCatching {
                        HostBookBindings.rescan(session, values)
                    }.onSuccess { bookValues = it }.onFailure {
                        bookValues = null
                        session.recordFailure(HostBookBindings.KEY, it)
                        queryErrors += "${HostBookBindings.KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro book actions rescan failed", it)
                    }
                    if (HostSyncBindings.initializationAttempted) runCatching {
                        HostSyncBindings.rescan(session, values)
                    }.onSuccess { syncValues = it }.onFailure {
                        syncValues = null
                        session.recordFailure(HostSyncBindings.QUERY_KEY, it)
                        queryErrors += "${HostSyncBindings.QUERY_KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro sync rescan failed", it)
                    }
                    if (HostBookCoverBindings.initializationAttempted) runCatching {
                        HostBookCoverBindings.rescan(session, values)
                    }.onSuccess { bookCoverValues = it }.onFailure {
                        bookCoverValues = null
                        session.recordFailure(HostBookCoverBindings.KEY, it)
                        queryErrors += "${HostBookCoverBindings.KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro cover actions rescan failed", it)
                    }
                    if (HostReaderGuardBindings.initializationAttempted) runCatching {
                        HostReaderGuardBindings.rescan(session, values)
                    }.onSuccess { readerGuardValues = it }.onFailure {
                        readerGuardValues = emptyMap()
                        listOf(HostReaderGuardBindings.BACK, HostReaderGuardBindings.TYPE).forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "reader guards: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader guards rescan failed", it)
                    }
                    if (HostReaderMenuBindings.initializationAttempted) runCatching {
                        HostReaderMenuBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { readerMenuValues = it }.onFailure {
                        readerMenuValues = null
                        session.recordFailure(HostReaderMenuBindings.KEY, it)
                        queryErrors += "reader.menu: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader menu rescan failed", it)
                    }
                    if (HostTypographyBindings.initializationAttempted) runCatching {
                        HostTypographyBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { typographyValues = it }.onFailure {
                        typographyValues = null
                        session.recordFailure(HostTypographyBindings.KEY, it)
                        queryErrors += "ui.typography: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro typography rescan failed", it)
                    }
                    if (HostStorageBindings.initializationAttempted) runCatching {
                        HostStorageBindings.rescan(session, values)
                    }.onSuccess { storageValues = it }.onFailure {
                        storageValues = emptyMap()
                        HostStorageBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "storage: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro storage rescan failed", it)
                    }
                    if (HostSelectionBindings.initializationAttempted) runCatching {
                        HostSelectionBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { selectionValues = it }.onFailure {
                        selectionValues = null
                        session.recordFailure(HostSelectionBindings.KEY, it)
                        queryErrors += "reader.selection: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro selection rescan failed", it)
                    }
                    if (HostHighlightDomBindings.initializationAttempted) runCatching {
                        HostHighlightDomBindings.rescan(session, values)
                    }.onSuccess { highlightDomValues = it }.onFailure {
                        highlightDomValues = null
                        session.recordFailure(HostHighlightDomBindings.KEY, it)
                        queryErrors += "reader.highlightDom: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro highlight DOM rescan failed", it)
                    }
                    if (HostHighlightTextBindings.initializationAttempted) runCatching {
                        HostHighlightTextBindings.rescan(session, values)
                    }.onSuccess { highlightTextValues = it }.onFailure {
                        highlightTextValues = null
                        session.recordFailure(HostHighlightTextBindings.KEY, it)
                        queryErrors += "reader.highlightText: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro highlight text rescan failed", it)
                    }
                    if (HostReaderRenderBindings.initializationAttempted) runCatching {
                        HostReaderRenderBindings.rescan(session, values)
                    }.onSuccess { readerRenderValues = it }.onFailure {
                        readerRenderValues = null
                        session.recordFailure(HostReaderRenderBindings.KEY, it)
                        queryErrors += "reader.render: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader render rescan failed", it)
                    }
                    if (HostReaderRecordBindings.initializationAttempted) runCatching {
                        HostReaderRecordBindings.rescan(session, values)
                    }.onSuccess { readerRecordValues = it }.onFailure {
                        readerRecordValues = null
                        session.recordFailure(HostReaderRecordBindings.KEY, it)
                        queryErrors += "reader.records: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader records rescan failed", it)
                    }
                    if (HostReaderPositionBindings.initializationAttempted) runCatching {
                        HostReaderPositionBindings.rescan(session, values)
                    }.onSuccess { readerPositionValues = it }.onFailure {
                        readerPositionValues = null
                        session.recordFailure(HostReaderPositionBindings.KEY, it)
                        queryErrors += "reader.position: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader position rescan failed", it)
                    }
                    if (HostReaderExtrasBindings.initializationAttempted) runCatching {
                        HostReaderExtrasBindings.rescan(session, values)
                    }.onSuccess { readerExtrasValues = it }.onFailure {
                        readerExtrasValues = emptyMap()
                        HostReaderExtrasBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "ReaderExtras: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro ReaderExtras rescan failed", it)
                    }
                    if (HostAccountBindings.initializationAttempted) runCatching {
                        HostAccountBindings.rescan(session, values)
                    }.onSuccess { accountValues = it }.onFailure {
                        accountValues = emptyMap()
                        HostAccountBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "account: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro account rescan failed", it)
                    }
                    if (HostCrashBindings.initializationAttempted) runCatching {
                        HostCrashBindings.rescan(session)
                    }.onSuccess { crashValues = it }.onFailure {
                        crashValues = null
                        session.recordFailure(HostCrashBindings.KEY, it)
                        queryErrors += "privacy.crash: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro crash reporting rescan failed", it)
                    }
                    if (HostCompositionBindings.initializationAttempted) runCatching {
                        HostCompositionBindings.rescan(session, requireNotNull(uiValues))
                    }.onSuccess { compositionValues = it }.onFailure {
                        compositionValues = null
                        session.recordFailure(HostCompositionBindings.KEY, it)
                        queryErrors += "runtime.composition: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro composition reload rescan failed", it)
                    }
                    if (HostDialogBindings.initializationAttempted) runCatching {
                        HostDialogBindings.rescan(session, requireNotNull(uiValues), requireNotNull(compositionValues))
                    }.onSuccess { dialogValues = it }.onFailure {
                        dialogValues = null
                        session.recordFailure(HostDialogBindings.KEY, it)
                        queryErrors += "${HostDialogBindings.KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro native dialog rescan failed", it)
                    }
                    if (HostBrowserBindings.initializationAttempted) runCatching {
                        HostBrowserBindings.rescan(session, requireNotNull(uiValues))
                    }.onSuccess { browserValues = it }.onFailure {
                        browserValues = null
                        session.recordFailure(HostBrowserBindings.KEY, it)
                        queryErrors += "${HostBrowserBindings.KEY}: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro native browser rescan failed", it)
                    }
                    if (HostProfileBindings.initializationAttempted) runCatching {
                        HostProfileBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { profileValues = it }.onFailure {
                        profileValues = null
                        session.recordFailure(HostProfileBindings.KEY, it)
                        queryErrors += "profile.background: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro profile background rescan failed", it)
                    }
                    if (HostBookUiBindings.initializationAttempted) runCatching {
                        HostBookUiBindings.rescan(session, values)
                    }.onSuccess { bookUiValues = it }.onFailure {
                        bookUiValues = emptyMap()
                        HostBookUiBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "books UI: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro book UI rescan failed", it)
                    }
                    if (HostGraphicsBindings.initializationAttempted) runCatching {
                        HostGraphicsBindings.rescan(session, values)
                    }.onSuccess { graphicsValues = it }.onFailure {
                        graphicsValues = null
                        session.recordFailure(HostGraphicsBindings.KEY, it)
                        queryErrors += "graphics.vector: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro graphics rescan failed", it)
                    }
                    if (HostDiscoverBindings.initializationAttempted) runCatching {
                        HostDiscoverBindings.rescan(session, values, requireNotNull(uiValues), requireNotNull(graphicsValues))
                    }.onSuccess { discoverValues = it }.onFailure {
                        discoverValues = null
                        session.recordFailure(HostDiscoverBindings.KEY, it)
                        queryErrors += "discover.community: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro Discover rescan failed", it)
                    }
                    if (HostReaderScrollBindings.initializationAttempted) runCatching {
                        HostReaderScrollBindings.rescan(session, values)
                    }.onSuccess { readerScrollValues = it }.onFailure {
                        readerScrollValues = null
                        session.recordFailure(HostReaderScrollBindings.KEY, it)
                        queryErrors += "reader.scroll: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader scroll rescan failed", it)
                    }
                    if (HostReaderPagerBindings.initializationAttempted) runCatching {
                        HostReaderPagerBindings.rescan(session, values)
                    }.onSuccess { readerPagerValues = it }.onFailure {
                        readerPagerValues = null
                        session.recordFailure(HostReaderPagerBindings.KEY, it)
                        queryErrors += "reader.pager: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader pager rescan failed", it)
                    }
                    if (HostEpubServicesBindings.initializationAttempted) runCatching {
                        HostEpubServicesBindings.rescan(session, values)
                    }.onSuccess { epubServiceValues = it }.onFailure {
                        epubServiceValues = emptyMap()
                        HostEpubServicesBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "epub.services: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro EPUB services rescan failed", it)
                    }
                    if (HostHomeSearchBindings.initializationAttempted) runCatching {
                        HostHomeSearchBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { homeSearchValues = it }.onFailure {
                        homeSearchValues = null
                        session.recordFailure(HostHomeSearchBindings.KEY, it)
                        queryErrors += "home.search: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro Home search rescan failed", it)
                    }
                    if (HostWorkBindings.initializationAttempted) runCatching {
                        HostWorkBindings.rescan(session, values)
                    }.onSuccess { workValues = it }.onFailure {
                        workValues = null
                        session.recordFailure(HostWorkBindings.KEY, it)
                        queryErrors += "integration.work: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro native work rescan failed", it)
                    }
                    if (HostEpubSourceBindings.initializationAttempted) runCatching {
                        HostEpubSourceBindings.rescan(session, values, requireNotNull(highlightDomValues))
                    }.onSuccess { epubSourceValues = it }.onFailure {
                        epubSourceValues = null
                        session.recordFailure(HostEpubSourceBindings.KEY, it)
                        queryErrors += "reader.epubSource: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro EPUB source rescan failed", it)
                    }
                    if (HostEpubRuntimeBindings.initializationAttempted) runCatching {
                        HostEpubRuntimeBindings.rescan(session, values)
                    }.onSuccess { epubRuntimeValues = it }.onFailure {
                        epubRuntimeValues = null
                        session.recordFailure(HostEpubRuntimeBindings.KEY, it)
                        queryErrors += "reader.epub: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro EPUB runtime rescan failed", it)
                    }
                    if (HostHighlightUiBindings.initializationAttempted) runCatching {
                        HostHighlightUiBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { highlightUiValues = it }.onFailure {
                        highlightUiValues = null
                        session.recordFailure(HostHighlightUiBindings.KEY, it)
                        queryErrors += "reader.highlightUi: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro highlight UI rescan failed", it)
                    }
                    if (HostReaderWindowBindings.initializationAttempted) runCatching {
                        HostReaderWindowBindings.rescan(session, values)
                    }.onSuccess { readerWindowValues = it }.onFailure {
                        readerWindowValues = null
                        session.recordFailure(HostReaderWindowBindings.KEY, it)
                        queryErrors += "reader.window: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader window rescan failed", it)
                    }
                    if (HostReaderLifecycleBindings.initializationAttempted) runCatching {
                        HostReaderLifecycleBindings.rescan(session, values)
                    }.onSuccess { readerLifecycleValues = it }.onFailure {
                        readerLifecycleValues = null
                        session.recordFailure(HostReaderLifecycleBindings.KEY, it)
                        queryErrors += "reader.lifecycle: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro reader lifecycle rescan failed", it)
                    }
                    if (HostParagraphCommentBindings.initializationAttempted) runCatching {
                        HostParagraphCommentBindings.rescan(session, values)
                    }.onSuccess { paragraphCommentValues = it }.onFailure {
                        paragraphCommentValues = null
                        session.recordFailure(HostParagraphCommentBindings.KEY, it)
                        queryErrors += "reader.comments: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro paragraph comment rescan failed", it)
                    }
                    if (HostAssociationBindings.initializationAttempted) runCatching {
                        HostAssociationBindings.rescan(session, values)
                    }.onSuccess { associationValues = it }.onFailure {
                        associationValues = null
                        session.recordFailure(HostAssociationBindings.KEY, it)
                        queryErrors += "books.association: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro association rescan failed", it)
                    }
                    if (HostManualEditBindings.initializationAttempted) runCatching {
                        HostManualEditBindings.rescan(session, requireNotNull(uiValues), requireNotNull(associationValues),
                            runtimeValues.getValue("runtime.navigation"))
                    }.onSuccess { manualEditValues = it }.onFailure {
                        manualEditValues = emptyMap()
                        HostManualEditBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "manual: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro manual editor rescan failed", it)
                    }
                    if (HostGlobalFontBindings.initializationAttempted) runCatching {
                        HostGlobalFontBindings.rescan(session, values, requireNotNull(uiValues),
                            requireNotNull(readerExtrasValues["reader.fontCompletion"]), requireNotNull(readerMenuValues))
                    }.onSuccess { globalFontValues = it }.onFailure {
                        globalFontValues = null
                        session.recordFailure(HostGlobalFontBindings.KEY, it)
                        queryErrors += "ui.globalFont: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro global font rescan failed", it)
                    }
                    if (HostHomeBindings.initializationAttempted) runCatching {
                        HostHomeBindings.rescan(session, values, requireNotNull(uiValues))
                    }.onSuccess { homeValues = it }.onFailure {
                        homeValues = emptyMap()
                        HostHomeBindings.QUERY_KEYS.forEach { key -> session.recordFailure(key, it) }
                        queryErrors += "Home: ${it.javaClass.simpleName}: ${it.message}"
                        HookRuntime.logError("ReaMicro Home rescan failed", it)
                    }
                }
                if (force || session.hasFreshQueries()) allCached = false
                val failedQueries = session.failures()
                if (failedQueries.isNotEmpty()) {
                    allCached = false
                    failedQueries.forEach { (key, error) ->
                        queryErrors += "$key: ${error.javaClass.simpleName}: ${error.message}"
                    }
                    runtimeValues = runtimeValues.filterKeys { it !in failedQueries }
                    integrationValues = integrationValues.filterKeys { it !in failedQueries }
                    readerGuardValues = readerGuardValues.filterKeys { it !in failedQueries }
                    storageValues = storageValues.filterKeys { it !in failedQueries }
                    readerExtrasValues = readerExtrasValues.filterKeys { it !in failedQueries }
                    accountValues = accountValues.filterKeys { it !in failedQueries }
                    epubServiceValues = epubServiceValues.filterKeys { it !in failedQueries }
                    manualEditValues = manualEditValues.filterKeys { it !in failedQueries }
                    bookUiValues = bookUiValues.filterKeys { it !in failedQueries }
                    homeValues = homeValues.filterKeys { it !in failedQueries }
                    if ("reader.controls" in failedQueries) readerValues = null
                    if ("settings.entry" in failedQueries) settingsValues = null
                    if (HostUiBindings.KEY in failedQueries) uiValues = null
                    if (HostBookBindings.KEY in failedQueries) bookValues = null
                    if (HostBookCoverBindings.KEY in failedQueries) bookCoverValues = null
                    if (HostSyncBindings.QUERY_KEY in failedQueries) syncValues = null
                    if (HostReaderMenuBindings.KEY in failedQueries) readerMenuValues = null
                    if (HostTypographyBindings.KEY in failedQueries) typographyValues = null
                    if (HostSelectionBindings.KEY in failedQueries) selectionValues = null
                    if (HostHighlightDomBindings.KEY in failedQueries) highlightDomValues = null
                    if (HostHighlightTextBindings.KEY in failedQueries) highlightTextValues = null
                    if (HostReaderRenderBindings.KEY in failedQueries) readerRenderValues = null
                    if (HostReaderRecordBindings.KEY in failedQueries) readerRecordValues = null
                    if (HostReaderPositionBindings.KEY in failedQueries) readerPositionValues = null
                    if (HostCrashBindings.KEY in failedQueries) crashValues = null
                    if (HostCompositionBindings.KEY in failedQueries) compositionValues = null
                    if (HostDialogBindings.KEY in failedQueries) dialogValues = null
                    if (HostBrowserBindings.KEY in failedQueries) browserValues = null
                    if (HostProfileBindings.KEY in failedQueries) profileValues = null
                    if (HostGraphicsBindings.KEY in failedQueries) graphicsValues = null
                    if (HostDiscoverBindings.KEY in failedQueries) discoverValues = null
                    if (HostParagraphCommentBindings.KEY in failedQueries) paragraphCommentValues = null
                    if (HostReaderLifecycleBindings.KEY in failedQueries) readerLifecycleValues = null
                    if (HostReaderScrollBindings.KEY in failedQueries) readerScrollValues = null
                    if (HostReaderPagerBindings.KEY in failedQueries) readerPagerValues = null
                    if (HostReaderWindowBindings.KEY in failedQueries) readerWindowValues = null
                    if (HostHighlightUiBindings.KEY in failedQueries) highlightUiValues = null
                    if (HostEpubRuntimeBindings.KEY in failedQueries) epubRuntimeValues = null
                    if (HostHomeSearchBindings.KEY in failedQueries) homeSearchValues = null
                    if (HostWorkBindings.KEY in failedQueries) workValues = null
                    if (HostEpubSourceBindings.KEY in failedQueries) epubSourceValues = null
                    if (HostAssociationBindings.KEY in failedQueries) associationValues = null
                    if (HostGlobalFontBindings.KEY in failedQueries) globalFontValues = null
                }
                if (force || installedValues != null) {
                    val featureKeys = buildSet {
                        if (readerValues != null) add("reader.controls")
                        if (settingsValues != null) add("settings.entry")
                        if (uiValues != null) add(HostUiBindings.KEY)
                        if (bookValues != null) add(HostBookBindings.KEY)
                        if (bookCoverValues != null) add(HostBookCoverBindings.KEY)
                        if (syncValues != null) add(HostSyncBindings.QUERY_KEY)
                        if (readerMenuValues != null) add(HostReaderMenuBindings.KEY)
                        if (typographyValues != null) add(HostTypographyBindings.KEY)
                        if (selectionValues != null) add(HostSelectionBindings.KEY)
                        if (highlightDomValues != null) add(HostHighlightDomBindings.KEY)
                        if (highlightTextValues != null) add(HostHighlightTextBindings.KEY)
                        if (readerRenderValues != null) add(HostReaderRenderBindings.KEY)
                        if (readerRecordValues != null) add(HostReaderRecordBindings.KEY)
                        if (readerPositionValues != null) add(HostReaderPositionBindings.KEY)
                        if (crashValues != null) add(HostCrashBindings.KEY)
                        if (compositionValues != null) add(HostCompositionBindings.KEY)
                        if (dialogValues != null) add(HostDialogBindings.KEY)
                        if (browserValues != null) add(HostBrowserBindings.KEY)
                        if (profileValues != null) add(HostProfileBindings.KEY)
                        if (graphicsValues != null) add(HostGraphicsBindings.KEY)
                        if (discoverValues != null) add(HostDiscoverBindings.KEY)
                        if (paragraphCommentValues != null) add(HostParagraphCommentBindings.KEY)
                        if (readerLifecycleValues != null) add(HostReaderLifecycleBindings.KEY)
                        if (readerScrollValues != null) add(HostReaderScrollBindings.KEY)
                        if (readerPagerValues != null) add(HostReaderPagerBindings.KEY)
                        if (readerWindowValues != null) add(HostReaderWindowBindings.KEY)
                        if (highlightUiValues != null) add(HostHighlightUiBindings.KEY)
                        if (epubRuntimeValues != null) add(HostEpubRuntimeBindings.KEY)
                        if (homeSearchValues != null) add(HostHomeSearchBindings.KEY)
                        if (workValues != null) add(HostWorkBindings.KEY)
                        if (epubSourceValues != null) add(HostEpubSourceBindings.KEY)
                        if (associationValues != null) add(HostAssociationBindings.KEY)
                        if (globalFontValues != null) add(HostGlobalFontBindings.KEY)
                        addAll(runtimeValues.keys)
                        addAll(integrationValues.keys)
                        addAll(readerGuardValues.keys)
                        addAll(storageValues.keys)
                        addAll(readerExtrasValues.keys)
                        addAll(accountValues.keys)
                        addAll(epubServiceValues.keys)
                        addAll(manualEditValues.keys)
                        addAll(bookUiValues.keys)
                        addAll(homeValues.keys)
                    }
                    (HostQueryRegistry.keys.filterNot { it.startsWith("symbols.") }.toSet() - featureKeys)
                        .forEach { queryErrors += "$it: missing feature query result" }
                }
                val features = buildMap<String, Map<String, String>> {
                    fun add(key: String, found: Map<String, String>?) { if (found != null) put(key, found) }
                    putAll(runtimeValues); putAll(integrationValues); putAll(readerGuardValues)
                    putAll(storageValues); putAll(readerExtrasValues); putAll(accountValues)
                    putAll(epubServiceValues); putAll(manualEditValues); putAll(bookUiValues); putAll(homeValues)
                    add("reader.controls", readerValues); add("settings.entry", settingsValues); add("ui.native", uiValues)
                    add(HostBookBindings.KEY, bookValues); add(HostBookCoverBindings.KEY, bookCoverValues)
                    add(HostSyncBindings.QUERY_KEY, syncValues); add(HostReaderMenuBindings.KEY, readerMenuValues)
                    add(HostTypographyBindings.KEY, typographyValues); add(HostSelectionBindings.KEY, selectionValues)
                    add(HostHighlightDomBindings.KEY, highlightDomValues); add(HostHighlightTextBindings.KEY, highlightTextValues)
                    add(HostReaderRenderBindings.KEY, readerRenderValues); add(HostReaderRecordBindings.KEY, readerRecordValues)
                    add(HostReaderPositionBindings.KEY, readerPositionValues); add(HostProfileBindings.KEY, profileValues)
                    add(HostCompositionBindings.KEY, compositionValues); add(HostCrashBindings.KEY, crashValues)
                    add(HostDialogBindings.KEY, dialogValues)
                    add(HostBrowserBindings.KEY, browserValues)
                    add(HostGraphicsBindings.KEY, graphicsValues); add(HostDiscoverBindings.KEY, discoverValues)
                    add(HostGlobalFontBindings.KEY, globalFontValues); add(HostAssociationBindings.KEY, associationValues)
                    add(HostParagraphCommentBindings.KEY, paragraphCommentValues); add(HostReaderLifecycleBindings.KEY, readerLifecycleValues)
                    add(HostReaderScrollBindings.KEY, readerScrollValues); add(HostReaderPagerBindings.KEY, readerPagerValues)
                    add(HostReaderWindowBindings.KEY, readerWindowValues); add(HostHighlightUiBindings.KEY, highlightUiValues)
                    add(HostEpubRuntimeBindings.KEY, epubRuntimeValues); add(HostEpubSourceBindings.KEY, epubSourceValues)
                    add(HostWorkBindings.KEY, workValues); add(HostHomeSearchBindings.KEY, homeSearchValues)
                }
                HostSemanticFeatureExports.query(features).forEach { (key, descriptor) ->
                    val previous = values.putIfAbsent(key, descriptor)
                    if (previous != null && previous != descriptor) queryErrors += "$key: feature query conflicts with base descriptor"
                }
                val expected = HostSemanticCatalog.types().map { "class:${it.id}" }.toSet() +
                    HostSemanticCatalog.relations().map { "class:${it.id}" } +
                    HostSemanticCatalog.methods().map { "method:${it.id}" }
                val catalogUnresolved = (expected - values.keys).sorted()
                val requiredFeatures = HostQueryRegistry.keys.filterNot { it.startsWith("symbols.") }.toSet()
                val runtimeFailures = HostScanCoverage.failures(requiredFeatures, features.keys, values.keys, queryErrors.toList())
                val failures = runtimeFailures + catalogUnresolved
                val resolved = values.filterKeys { it in expected }
                val profile = toProfile(values)
                val report = Report(expected.size, resolved.size, failures, allCached,
                    (System.nanoTime() - started) / 1_000_000,
                    installed.matches(values, readerValues) && installedSettings == settingsValues &&
                        installedUi == uiValues && installedRuntime == runtimeValues &&
                        installedIntegrations == integrationValues && installedBooks == bookValues &&
                        installedSync == syncValues && installedBookCover == bookCoverValues &&
                        installedReaderGuards == readerGuardValues && installedReaderMenu == readerMenuValues &&
                        installedTypography == typographyValues && installedStorage == storageValues &&
                        installedSelection == selectionValues && installedHighlightDom == highlightDomValues &&
                        installedHighlightText == highlightTextValues &&
                        installedReaderRender == readerRenderValues && installedReaderRecords == readerRecordValues &&
                        installedReaderExtras == readerExtrasValues &&
                        installedAccount == accountValues &&
                        installedProfile == profileValues && installedComposition == compositionValues &&
                        installedDialog == dialogValues && installedBrowser == browserValues && installedCrash == crashValues &&
                        installedBookUi == bookUiValues &&
                        installedGraphics == graphicsValues && installedDiscover == discoverValues &&
                        installedGlobalFont == globalFontValues &&
                        installedAssociation == associationValues &&
                        installedManualEdit == manualEditValues &&
                        installedParagraphComments == paragraphCommentValues &&
                        installedReaderLifecycle == readerLifecycleValues &&
                        installedReaderScroll == readerScrollValues &&
                        installedReaderPager == readerPagerValues &&
                        installedReaderWindow == readerWindowValues &&
                        installedHighlightUi == highlightUiValues &&
                        installedEpubRuntime == epubRuntimeValues &&
                        installedEpubSource == epubSourceValues &&
                        installedEpubServices == epubServiceValues &&
                        installedWork == workValues &&
                        installedHomeSearch == homeSearchValues &&
                        installedHome == homeValues &&
                        installedReaderPosition == readerPositionValues, profile, failures.isEmpty(),
                    requiredFeatures.size, features.keys.count { it in requiredFeatures }, runtimeFailures, catalogUnresolved)
                latestValues = values.toMap()
                last = report
                HookRuntime.logAlways("ReaMicro ${report.summary()}")
                report
            }
        } catch (error: Throwable) {
            HookRuntime.logError("ReaMicro semantic query failed", error)
            Report(1, 0, listOf("${error.javaClass.simpleName}: ${error.message}"), false,
                (System.nanoTime() - started) / 1_000_000, false, null, false).also { last = it }
        }
    }

    private fun toProfile(values: Map<String, String>): HostProfile.Parsed {
        val classes = linkedMapOf<String, String>()
        val aliases = linkedMapOf<String, String>()
        val methods = linkedMapOf<String, String>()
        val owners = linkedMapOf<String, String>()
        val fields = linkedMapOf<String, String>()
        val primary = HashSet<String>()
        values.forEach { (key, descriptor) ->
            val logical = key.substringAfter(':')
            when {
                key.startsWith("class:") -> {
                    val name = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
                    if (!logical.startsWith("ReaMicro") && primary.add(name)) classes[logical] = name else aliases[logical] = name
                }
                key.startsWith("method:") -> DexMethod(descriptor).let {
                    methods[logical] = it.name
                    owners[logical] = it.className
                }
                key.startsWith("field:") -> fields[logical] = DexField(descriptor).name
            }
        }
        return HostProfile.Parsed(classes, methods, fields, aliases, owners)
    }
}

/** An explicit installation boundary keeps a warm/background scan from adopting uninstalled bindings. */
internal class HostScanInstalledBaseline {
    private data class Snapshot(val symbols: Map<String, String>?, val reader: Map<String, String>?)
    private var current: Snapshot? = null

    @Synchronized fun capture(symbols: Map<String, String>?, reader: Map<String, String>?) {
        current = Snapshot(symbols?.toMap(), reader?.toMap())
    }

    @Synchronized fun matches(symbols: Map<String, String>?, reader: Map<String, String>?): Boolean =
        current?.let { it.symbols == symbols && it.reader == reader } == true

    @Synchronized fun reset() { current = null }
}
