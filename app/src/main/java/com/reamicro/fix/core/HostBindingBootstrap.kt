package com.reamicro.fix.core

/** Initialize host bindings in dependency order within one query session. */
internal object HostBindingBootstrap {
    @JvmStatic fun initialize(loader: ClassLoader, hostPackage: String, apk: String, dataDir: String,
        afterCrash: Runnable?, afterUi: Runnable?) {
        HostQuerySession.use(loader, apk, dataDir) {
            HostSymbols.initialize(classLoader = loader, hostPackage = hostPackage,
                hostApkPath = apk, hostDataDir = dataDir)
            HostCrashBindings.initialize(loader, apk, dataDir)
            afterCrash?.run()
            HostReaderFeatures.initialize(loader, apk, dataDir)
            HostReaderGuardBindings.initialize(loader, apk, dataDir)
            HostRuntimeBindings.initialize(loader, apk, dataDir)
            HostIntegrationBindings.initialize(loader, apk, dataDir)
            HostSettingsFeatures.initialize(loader, apk, dataDir)
            HostUiBindings.initialize(loader, apk, dataDir)
            HostCompositionBindings.initialize(loader, apk, dataDir)
            HostDialogBindings.initialize(loader, apk, dataDir)
            HostTypographyBindings.initialize(loader, apk, dataDir)
            HostUiBindings.current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
            afterUi?.run()
            HostBrowserBindings.initialize(loader, apk, dataDir)
            HostBookBindings.initialize(loader, apk, dataDir)
            HostReaderMenuBindings.initialize(loader, apk, dataDir)
            HostSelectionBindings.initialize(loader, apk, dataDir)
            HostHighlightDomBindings.initialize(loader, apk, dataDir)
            HostHighlightTextBindings.initialize(loader, apk, dataDir)
            HostReaderRenderBindings.initialize(loader, apk, dataDir)
            HostReaderRecordBindings.initialize(loader, apk, dataDir)
            HostReaderPositionBindings.initialize(loader, apk, dataDir)
            HostReaderExtrasBindings.initialize(loader, apk, dataDir)
            HostHomeBindings.initialize(loader, apk, dataDir)
            HostHomeSearchBindings.initialize(loader, apk, dataDir)
            HostAccountBindings.initialize(loader, apk, dataDir)
            HostProfileBindings.initialize(loader, apk, dataDir)
            HostStorageBindings.initialize(loader, apk, dataDir)
            HostBookCoverBindings.initialize(loader, apk, dataDir)
            HostBookUiBindings.initialize(loader, apk, dataDir)
            HostGraphicsBindings.initialize(loader, apk, dataDir)
            HostDiscoverBindings.initialize(loader, apk, dataDir)
            HostGlobalFontBindings.initialize(loader, apk, dataDir)
            HostAssociationBindings.initialize(loader, apk, dataDir)
            HostManualEditBindings.initialize(loader, apk, dataDir)
            HostParagraphCommentBindings.initialize(loader, apk, dataDir)
            HostReaderLifecycleBindings.initialize(loader, apk, dataDir)
            HostReaderScrollBindings.initialize(loader, apk, dataDir)
            HostReaderPagerBindings.initialize(loader, apk, dataDir)
            HostReaderWindowBindings.initialize(loader, apk, dataDir)
            HostHighlightUiBindings.initialize(loader, apk, dataDir)
            HostEpubRuntimeBindings.initialize(loader, apk, dataDir)
            HostEpubSourceBindings.initialize(loader, apk, dataDir)
            HostEpubServicesBindings.initialize(loader, apk, dataDir)
            HostWorkBindings.initialize(loader, apk, dataDir)
            HostSyncBindings.initialize(loader, apk, dataDir)
            // Publish the complete generation after all feature queries have supplied their
            // validated logical exports. The initial scan only supplies bootstrap dependencies.
            val report = HostAutoScanner.scan(apk, dataDir)
            HostSymbols.installFeatureDescriptors(HostAutoScanner.queriedDescriptors().orEmpty())
            HookInstallReport.record("HostSymbols", "automaticScan", report?.functionalityComplete == true,
                if (report?.functionalityComplete == true) null else IllegalStateException(
                    report?.runtimeFailures?.take(8)?.joinToString() ?: "Final host query report unavailable"))
            val unavailable = missing().toSet()
            HostQueryRegistry.keys.filterNot { it.startsWith("symbols.") }.forEach { key ->
                HookInstallReport.record("HostBindings", key, key !in unavailable,
                    if (key in unavailable) IllegalStateException("Queried binding unavailable") else null)
            }
        }
    }

    /** Exact descriptors installed by this bootstrap, grouped by the production cache key. */
    @JvmStatic fun snapshots(): Map<String, Map<String, String>> = buildMap {
        fun add(key: String, values: Map<String, String>?) { if (values != null) put(key, values) }
        add(HostCrashBindings.KEY, HostCrashBindings.boundDescriptors())
        add("reader.controls", HostReaderFeatures.boundDescriptors())
        putAll(HostReaderGuardBindings.boundDescriptors())
        putAll(HostRuntimeBindings.boundDescriptors())
        putAll(HostIntegrationBindings.boundDescriptors())
        add("settings.entry", HostSettingsFeatures.boundDescriptors())
        add(HostUiBindings.KEY, HostUiBindings.boundDescriptors())
        add(HostCompositionBindings.KEY, HostCompositionBindings.boundDescriptors())
        add(HostDialogBindings.KEY, HostDialogBindings.boundDescriptors())
        add(HostBrowserBindings.KEY, HostBrowserBindings.boundDescriptors())
        add(HostBookBindings.KEY, HostBookBindings.boundDescriptors())
        add(HostReaderMenuBindings.KEY, HostReaderMenuBindings.boundDescriptors())
        add(HostSelectionBindings.KEY, HostSelectionBindings.boundDescriptors())
        add(HostHighlightDomBindings.KEY, HostHighlightDomBindings.boundDescriptors())
        add(HostHighlightTextBindings.KEY, HostHighlightTextBindings.boundDescriptors())
        add(HostReaderRenderBindings.KEY, HostReaderRenderBindings.boundDescriptors())
        add(HostReaderRecordBindings.KEY, HostReaderRecordBindings.boundDescriptors())
        add(HostReaderPositionBindings.KEY, HostReaderPositionBindings.boundDescriptors())
        putAll(HostReaderExtrasBindings.boundDescriptors())
        putAll(HostHomeBindings.boundDescriptors())
        add(HostHomeSearchBindings.KEY, HostHomeSearchBindings.boundDescriptors())
        putAll(HostAccountBindings.boundDescriptors())
        add(HostProfileBindings.KEY, HostProfileBindings.boundDescriptors())
        add(HostTypographyBindings.KEY, HostTypographyBindings.boundDescriptors())
        putAll(HostStorageBindings.boundDescriptors())
        add(HostBookCoverBindings.KEY, HostBookCoverBindings.boundDescriptors())
        putAll(HostBookUiBindings.boundDescriptors())
        add(HostGraphicsBindings.KEY, HostGraphicsBindings.boundDescriptors())
        add(HostDiscoverBindings.KEY, HostDiscoverBindings.boundDescriptors())
        add(HostGlobalFontBindings.KEY, HostGlobalFontBindings.boundDescriptors())
        add(HostAssociationBindings.KEY, HostAssociationBindings.boundDescriptors())
        putAll(HostManualEditBindings.boundDescriptors())
        add(HostParagraphCommentBindings.KEY, HostParagraphCommentBindings.boundDescriptors())
        add(HostReaderLifecycleBindings.KEY, HostReaderLifecycleBindings.boundDescriptors())
        add(HostReaderScrollBindings.KEY, HostReaderScrollBindings.boundDescriptors())
        add(HostReaderPagerBindings.KEY, HostReaderPagerBindings.boundDescriptors())
        add(HostReaderWindowBindings.KEY, HostReaderWindowBindings.boundDescriptors())
        add(HostHighlightUiBindings.KEY, HostHighlightUiBindings.boundDescriptors())
        add(HostEpubRuntimeBindings.KEY, HostEpubRuntimeBindings.boundDescriptors())
        add(HostEpubSourceBindings.KEY, HostEpubSourceBindings.boundDescriptors())
        putAll(HostEpubServicesBindings.boundDescriptors())
        add(HostWorkBindings.KEY, HostWorkBindings.boundDescriptors())
        add(HostSyncBindings.QUERY_KEY, HostSyncBindings.boundDescriptors())
    }
    @JvmStatic fun missing(): List<String> {
        val expected = HostQueryRegistry.keys.filterNot { it.startsWith("symbols.") }.toSet()
        return (expected - snapshots().keys).sorted()
    }
}
