package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.ExternalFeatureApi
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.core.ExternalFeatureCompatibility
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.cloud.api.ApiPackageAutoUpdater
import com.reamicro.fix.cloud.api.CloudTaskNotificationPoller
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.mirrorToModule
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class ReaMicroHookEntry {
    @Volatile private var currentActivityRef: WeakReference<Activity>? = null
    @Volatile private var currentActivityResumed: Boolean = false
    private val moduleSettings = XposedModuleSettings { currentActivityRef?.get() }

    private val activityProvider: () -> Activity? = { currentActivityRef?.get() }
    private val settingsProvider: () -> ModuleSettingsSnapshot = moduleSettings::snapshot
    private val installedFeatureIds = linkedSetOf<String>()
    private val disabledFeatureIds = linkedSetOf<String>()
    private var bookshelfCompletion: BookshelfCompletionHook? = null
    private var coverFade: CoverFadeHook? = null
    private var bookBackupScroll: BookBackupScrollHook? = null
    private var attachReloadedActivity: ((Activity, Boolean) -> Unit)? = null
    private var attachStartedActivity: ((Activity, Boolean) -> Unit)? = null
    @Volatile private var readerAutoPage: ReaderAutoPageHook? = null
    @Volatile private var webDavDrive: WebDavDriveHook? = null
    @Volatile private var readerImportOverwrite: ReaderImportOverwriteHook? = null
    @Volatile private var manualAssociationUi: ManualAssociationUiHook? = null
    @Volatile private var disabledExecutionSyncPending = false

    init {
        moduleSettings.onModuleExecutionInvalidated = {
            listOf<() -> Unit>(
                { webDavDrive?.invalidateExecution() },
                { readerAutoPage?.invalidateExecution() },
                { readerImportOverwrite?.onModuleEnabledChanged(false) },
                { manualAssociationUi?.invalidateExecution() },
                YouShuWebSearchBridge::invalidateExecution,
                YouShuSourceRequests::invalidateAll,
            ).forEach { invalidate ->
                runCatching(invalidate).onFailure { HookRuntime.logError("$LOG_PREFIX execution cancellation failed", it) }
            }
        }
        moduleSettings.onModuleEnabledChanged = { enabled ->
            listOf<() -> Unit>(
                { webDavDrive?.onModuleEnabledChanged(enabled) },
                { readerAutoPage?.onModuleEnabledChanged(enabled) },
                { if (enabled) readerImportOverwrite?.onModuleEnabledChanged(true) },
                { manualAssociationUi?.onModuleEnabledChanged(enabled) },
                { YouShuWebSearchBridge.onModuleEnabledChanged(enabled) },
            ).forEach { notify ->
                runCatching(notify).onFailure { HookRuntime.logError("$LOG_PREFIX feature activation update failed", it) }
            }
            if (!enabled) {
                heartbeatHandler.removeCallbacks(heartbeatRunnable)
                cancelActivityUi()
                HostSettingsDialogs.dismissActive()
                HostYouShuLoginDialog.dismissActive()
            }
        }
        // This also covers generations installed while the master switch is off.
        com.reamicro.fix.reload.ModuleResources.cleanup {
            moduleSettings.closeForReload()
            currentActivityRef = null
            currentActivityResumed = false
            attachStartedActivity = null
            attachReloadedActivity = null
            readerAutoPage = null
            webDavDrive = null
            readerImportOverwrite = null
            manualAssociationUi = null
            disabledExecutionSyncPending = false
        }
    }

    internal fun attachStartupContext(context: android.content.Context) {
        com.reamicro.fix.online.OnlineParserContext.attach(context)
        moduleSettings.attachContext(context)
        synchronizeDisabledExecution(context)
    }

    internal fun observeStartupActivity(activity: Activity, resumed: Boolean) {
        com.reamicro.fix.online.OnlineParserContext.attach(activity)
        setCurrentActivity(activity)
        currentActivityResumed = resumed
        moduleSettings.attachContext(activity)
        if (resumed) {
            synchronizeDisabledExecution(activity)
            ReaMicroSettingsHook.activeInstanceOrNull()?.finishActivationRestart(activity)
        }
    }

    private fun synchronizeDisabledExecution(context: android.content.Context) {
        if (moduleSettings.snapshot().moduleEnabled || disabledExecutionSyncPending ||
            com.reamicro.fix.cloud.local.LocalTaskMirror.activationConfirmed(false)) return
        disabledExecutionSyncPending = true
        com.reamicro.fix.cloud.local.LocalTaskMirror.setModuleEnabled(context, false) { result ->
            disabledExecutionSyncPending = false
            if (!result.success) HookRuntime.logError("$LOG_PREFIX disabled execution handoff will retry on resume")
        }
    }

    internal fun attachAfterStartup(activity: Activity, resumed: Boolean) {
        requireNotNull(attachStartedActivity).invoke(activity, resumed)
    }

    internal fun attachAfterHotReload(activity: Activity, resumed: Boolean = true) {
        requireNotNull(attachReloadedActivity).invoke(activity, resumed)
    }
    @Volatile private var memoryCallbacksInstalled: Boolean = false
    @Volatile private var apiSettingsMirrored: Boolean = false
    private val heartbeatHandler = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private val activityUiHandler = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private val activityUiGeneration = AtomicLong(0L)
    @Volatile private var globalFontRefreshPending = false
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val activity = currentActivityRef?.get()
            if (activity != null && currentActivityResumed && moduleSettings.snapshot().moduleEnabled) {
                val appContext = activity.applicationContext
                CloudTaskNotificationPoller.poll(appContext, source = "foreground-heartbeat")

                com.reamicro.fix.cloud.local.LocalTaskMirror.push(appContext)
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun handleLoadedPackage(packageName: String, classLoader: ClassLoader) {
        if (!com.reamicro.fix.core.HostPackages.contains(packageName)) return
        HookRuntime.log("$LOG_PREFIX loaded for $packageName")
        if (!moduleSettings.snapshot().moduleEnabled) {
            installFeature("ReaMicroSettingsHook") {
                ReaMicroSettingsHook(classLoader, activityProvider, moduleSettings).install()
            }
            attachStartedActivity = { activity, resumed ->
                com.reamicro.fix.reload.HostGeneration.attach(activity.application)
                observeStartupActivity(activity, resumed)
            }
            attachReloadedActivity = { activity, resumed ->
                com.reamicro.fix.reload.HostGeneration.attach(activity.application)
                observeStartupActivity(activity, resumed)
            }
            HookRuntime.logAlways("$LOG_PREFIX module disabled; only activation settings installed")
            logHookInstallReport()
            return
        }
        YouShuWebSearchBridge.attach(activityProvider) { moduleSettings.snapshot().moduleEnabled }
        installFeature("HostSourceBrowserBridge") {
            check(HostSourceBrowserBridge.install(classLoader, activityProvider)) { "宿主书源网页入口不可用" }
        }
        val readerHook = ReaderHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settingsProvider = settingsProvider,
            settings = moduleSettings,
            isActivityResumedProvider = { currentActivityResumed },
        )
        installFeature("ReaderHook", readerHook::install)
        installFeature("ReaderAutoPageHook") {
            readerAutoPage = ReaderAutoPageHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
            ).also { it.install() }
        }
        HookInstallReport.installResult(ENTRY_FEATURE_ID, "ReaderImportOverwriteHook") {
            readerImportOverwrite = ReaderImportOverwriteHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
            ).also { it.install() }
        }
        val globalFontHook = ReaMicroGlobalFontHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settings = moduleSettings,
        )
        installFeature("ReaMicroGlobalFontHook", globalFontHook::install)
        val readerFontCompletionHook = ReaderFontCompletionHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settings = moduleSettings,
        )
        installFeature("ReaderFontCompletionHook", readerFontCompletionHook::install)
        val readerDialogueHighlightHook = ReaderDialogueHighlightHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settings = moduleSettings,
        )
        installFeature("ReaderDialogueHighlightHook", readerDialogueHighlightHook::install)
        installFeature("FileEditCompletionHook") {
            FileEditCompletionHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
                fontSettingsProvider = moduleSettings::fontSettings,
            ).install()
        }
        installFeature("ReaMicroSettingsHook") {
            ReaMicroSettingsHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settings = moduleSettings,
                onGlobalFontChanged = {
                    globalFontHook.invalidateGlobalFontCache()
                    refreshCurrentActivityForGlobalFont()
                },
            ).install()
        }
        installFeature("AssociationSearchHook") {
            AssociationSearchHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
            ).install()
        }
        installFeature("ManualEditUnlockHook") {
            ManualEditUnlockHook(activityProvider, moduleSettings).install()
        }
        val manualAssociationUiHook = ManualAssociationUiHook(classLoader, activityProvider, settingsProvider)
        manualAssociationUi = manualAssociationUiHook
        if (manualAssociationUiHook.install()) {
            installedFeatureIds += "manual-edit"
        }
        val profileBackgroundHook = ProfileBackgroundHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settings = moduleSettings,
            settingsProvider = settingsProvider,
        )
        installFeature("ProfileBackgroundHook", profileBackgroundHook::install)
        val bookDetailsAssociationActionHook = BookDetailsAssociationActionHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
        )
        installFeature("BookDetailsAssociationActionHook", bookDetailsAssociationActionHook::install)
        installFeature("BookDetailsAssociationBridge") {
            HostBookDetailsAssociationBridge(classLoader, activityProvider, settingsProvider,
                bookDetailsAssociationActionHook::requestCoverFixFromBarcode).install()
        }
        installFeature("BookOverviewImageSelectionHook") {
            HookRuntime.withFeatureGate({ moduleSettings.snapshot().bookImageSelectionEnabled }) { BookOverviewImageSelectionHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                requestCoverFix = bookDetailsAssociationActionHook::requestCoverFixForCurrentDetails,
                enabledProvider = {
                    val snapshot = moduleSettings.snapshot()
                    snapshot.moduleEnabled && snapshot.bookImageSelectionEnabled
                },
            ).install() }
        }
        installFeature("LocalExportHook") {
            HookRuntime.withFeatureGate({ moduleSettings.snapshot().bookLocalExportEnabled }) { LocalExportHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                enabledProvider = {
                    val snapshot = moduleSettings.snapshot()
                    snapshot.moduleEnabled && snapshot.bookLocalExportEnabled
                },
            ).install() }
        }
        val webDavDriveHook = WebDavDriveHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settingsProvider = settingsProvider,
            globalTypefaceProvider = globalFontHook::globalAndroidTypeface,
        )
        webDavDrive = webDavDriveHook
        installFeature("WebDavDriveHook", webDavDriveHook::install)
        installFeature("WebDavSyncHook") {
            WebDavSyncHook(classLoader, activityProvider, settingsProvider, webDavDriveHook).install()
        }
        installFeature("CoverFadeHook") {
            coverFade = CoverFadeHook(classLoader, settingsProvider).also { it.install() }
        }
        installFeature("BookBackupScrollHook") {
            bookBackupScroll = BookBackupScrollHook(classLoader, settingsProvider).also { it.install() }
        }
        installFeature("HomeDefaultPageHook") {
            HookRuntime.withFeatureGate({ moduleSettings.snapshot().homeDefaultPageEnabled }) {
                HomeDefaultPageHook(classLoader, settingsProvider).install()
            }
        }
        installFeature("BookshelfCompletionHook") {
            bookshelfCompletion = BookshelfCompletionHook(classLoader, activityProvider, settingsProvider, webDavDriveHook)
                .also { it.install() }
        }
        installFeature("DiscoverCardHook") {
            HookRuntime.withFeatureGate({ moduleSettings.snapshot().onlineDiscoverEnabled }) {
                DiscoverCardHook(classLoader).install()
            }
        }
        installFeature("MainActivity") {
            hookMainActivity(
                classLoader = classLoader,
                webDavDriveHook = webDavDriveHook,
                profileBackgroundHook = profileBackgroundHook,
                readerHook = readerHook,
                readerFontCompletionHook = readerFontCompletionHook,
                readerDialogueHighlightHook = readerDialogueHighlightHook,
                bookDetailsAssociationActionHook = bookDetailsAssociationActionHook,
            )
        }
        logHookInstallReport()
    }

    private fun installFeature(feature: String, block: () -> Any?) {
        HookInstallReport.install(ENTRY_FEATURE_ID, feature, block)
    }

    private fun logHookInstallReport() {
        HookRuntime.logAlways("$LOG_PREFIX ${HookInstallReport.summaryLine()}")
        HookInstallReport.failureDetails().forEach { detail ->
            HookRuntime.logError("$LOG_PREFIX hook install failure: $detail")
        }
    }

    private fun disableSearchSource(source: BookSource, message: String) {
        val groupId = ModuleSettings.searchSourceGroupId(source) ?: source.id
        moduleSettings.setAssociationSearchSourceEnabled(groupId, false)
        postCurrentActivityUi { activity ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
        HookRuntime.log("$LOG_PREFIX ${source.displayName} disabled: $message")
    }

    private fun refreshCurrentActivityForGlobalFont() {
        globalFontRefreshPending = true
        scheduleGlobalFontRecreate()
    }

    private fun scheduleGlobalFontRecreate() {
        if (!globalFontRefreshPending || !currentActivityResumed) return
        postCurrentActivityUi(GLOBAL_FONT_RECREATE_DELAY_MS) { activity ->
            if (!globalFontRefreshPending) return@postCurrentActivityUi
            globalFontRefreshPending = false
            runCatching {
                activity.recreate()
                HookRuntime.log("$LOG_PREFIX global UI font activity recreated")
            }.onFailure {
                HookRuntime.log("$LOG_PREFIX global UI font activity recreate failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun setCurrentActivity(activity: Activity) {
        if (currentActivityRef?.get() === activity) return
        cancelActivityUi()
        currentActivityRef = WeakReference(activity)
        currentActivityResumed = false
    }

    private fun cancelActivityUi() {
        activityUiGeneration.incrementAndGet()
        activityUiHandler.removeCallbacksAndMessages(null)
    }

    private fun postCurrentActivityUi(delayMillis: Long = 0L, action: (Activity) -> Unit): Boolean {
        if (!currentActivityResumed) return false
        val owner = currentActivityRef?.get() ?: return false
        val ownerRef = WeakReference(owner)
        val generation = activityUiGeneration.get()
        return activityUiHandler.postDelayed({
            val activity = ownerRef.get() ?: return@postDelayed
            if (currentActivityRef?.get() !== activity || !currentActivityResumed ||
                generation != activityUiGeneration.get() || activity.isFinishing || activity.isDestroyed
            ) return@postDelayed
            action(activity)
        }, delayMillis)
    }

    private fun hookMainActivity(
        classLoader: ClassLoader,
        webDavDriveHook: WebDavDriveHook,
        profileBackgroundHook: ProfileBackgroundHook,
        readerHook: ReaderHook,
        readerFontCompletionHook: ReaderFontCompletionHook,
        readerDialogueHighlightHook: ReaderDialogueHighlightHook,
        bookDetailsAssociationActionHook: BookDetailsAssociationActionHook,
    ) {
        val startedActivities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
        fun attachContext(activity: Activity) {
            com.reamicro.fix.reload.HostGeneration.attach(activity.application)
            webDavDriveHook.onHostActivityCreated(activity)
            readerHook.onHostActivityCreated(activity)
            readerAutoPage?.onHostActivityCreated(activity)
            setCurrentActivity(activity)
            moduleSettings.attachContext(activity)
            mirrorApiSettings(activity)
            installExternalFeatures(classLoader)
            installMemoryCallbacks(activity.application, webDavDriveHook, readerHook, readerFontCompletionHook,
                readerDialogueHighlightHook, profileBackgroundHook, bookDetailsAssociationActionHook)
        }
        fun finishCreation(activity: Activity) {
            if (!startedActivities.add(activity)) return
            RotationOrientationController.apply(activity, moduleSettings.snapshot())
            webDavDriveHook.cleanupStartupCacheIfNeeded(activity)
            bookshelfCompletion?.onHostStarted()
            coverFade?.onHostStarted()
            bookBackupScroll?.onHostStarted()
            profileBackgroundHook.refreshRandomImageFor(activity)
            ApiPackageAutoUpdater.checkIfDue(activity.applicationContext, moduleSettings)
            CloudTaskNotificationPoller.poll(activity.applicationContext)
            com.reamicro.fix.migration.LegacyAndroidWakeCleanup.runForHost(activity.applicationContext)
            com.reamicro.fix.cloud.local.LocalTaskMirror.push(activity.applicationContext)
            startForegroundHeartbeat()
        }
        attachStartedActivity = { activity, resumed ->
            attachContext(activity)
            currentActivityResumed = resumed
            finishCreation(activity)
            if (resumed) {
                webDavDriveHook.onHostActivityResumed(activity)
                readerHook.onHostActivityResumed(activity)
                readerAutoPage?.onHostActivityResumed(activity)
                scheduleGlobalFontRecreate()
                DexKitUpdateScan.onResume(activity)
            }
        }
        // 热重载保留 Activity，不会再次触发 onCreate，必须补齐同一套启动初始化。
        attachReloadedActivity = attachStartedActivity
        HookInstallReport.install(ENTRY_FEATURE_ID, "mainActivityCreate") {
            val lifecycle = requireNotNull(com.reamicro.fix.core.HostIntegrationBindings.activity) {
                "MainActivity lifecycle contract unavailable"
            }
            val mainActivityClass = lifecycle.type("activity")

            HookRuntime.hookMethod(
                lifecycle.method("create"),
                object : HookCallback() {
                    override fun after(param: Call) {
                        if (!mainActivityClass.isInstance(param.thisObject)) return
                        val activity = param.thisObject as? Activity ?: return
                        attachContext(activity)
                        currentActivityResumed = false
                        finishCreation(activity)
                        HookRuntime.log("$LOG_PREFIX MainActivity.onCreate hooked")
                    }

                    override fun before(param: Call) {
                        if (!mainActivityClass.isInstance(param.thisObject)) return
                        val activity = param.thisObject as? Activity ?: return
                        com.reamicro.fix.reload.HostGeneration.attach(activity.application)
                        webDavDriveHook.onHostActivityCreated(activity)
                        readerHook.onHostActivityCreated(activity)
                        readerAutoPage?.onHostActivityCreated(activity)
                        setCurrentActivity(activity)
                        currentActivityResumed = false
                        moduleSettings.attachContext(activity)
                        installExternalFeatures(classLoader)
                    }
                },
            )
            HookInstallReport.install(ENTRY_FEATURE_ID, "mainActivityResume") {
                HookRuntime.hookMethod(
                    lifecycle.method("resume"),
                    object : HookCallback() {
                        override fun after(param: Call) {
                            if (!mainActivityClass.isInstance(param.thisObject)) return
                            val activity = param.thisObject as? Activity ?: return
                            webDavDriveHook.onHostActivityCreated(activity)
                            readerHook.onHostActivityCreated(activity)
                            readerAutoPage?.onHostActivityCreated(activity)
                            setCurrentActivity(activity)
                            currentActivityResumed = true
                            moduleSettings.attachContext(activity)
                            mirrorApiSettings(activity)
                            installExternalFeatures(classLoader)
                            webDavDriveHook.onHostActivityResumed(activity)
                            readerHook.onHostActivityResumed(activity)
                            readerAutoPage?.onHostActivityResumed(activity)
                            scheduleGlobalFontRecreate()
                            RotationOrientationController.apply(activity, moduleSettings.snapshot())
                            profileBackgroundHook.refreshRandomImageFor(activity)
                            CloudTaskNotificationPoller.poll(activity.applicationContext)
                            startForegroundHeartbeat()
                            DexKitUpdateScan.onResume(activity)
                        }
                    },
                )
            }

        }
    }

    private fun installMemoryCallbacks(
        application: Application,
        webDavDriveHook: WebDavDriveHook,
        readerHook: ReaderHook,
        readerFontCompletionHook: ReaderFontCompletionHook,
        readerDialogueHighlightHook: ReaderDialogueHighlightHook,
        profileBackgroundHook: ProfileBackgroundHook,
        bookDetailsAssociationActionHook: BookDetailsAssociationActionHook,
    ) {
        if (memoryCallbacksInstalled) return
        synchronized(this) {
            if (memoryCallbacksInstalled) return
            val memoryCallback = object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onTrimMemory(level: Int) {
                    readerHook.onTrimMemory(level)
                    if (level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    ) {
                        readerFontCompletionHook.releaseMemory("trim memory level=$level")
                    }
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                        readerDialogueHighlightHook.releaseMemory("trim memory level=$level")
                        profileBackgroundHook.releaseMemory("trim memory level=$level")
                        bookDetailsAssociationActionHook.releaseMemory("trim memory level=$level")
                    }
                }

                override fun onLowMemory() {
                    readerHook.onLowMemory()
                    readerFontCompletionHook.releaseMemory("system low memory")
                    readerDialogueHighlightHook.releaseMemory("system low memory")
                    profileBackgroundHook.releaseMemory("system low memory")
                    bookDetailsAssociationActionHook.releaseMemory("system low memory")
                }
            }
            application.registerComponentCallbacks(memoryCallback)
            val lifecycleCallback = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) {
                    DexKitUpdateScan.onPause(activity)
                    webDavDriveHook.onHostActivityPaused(activity)
                    readerHook.onHostActivityPaused(activity)
                    readerAutoPage?.onHostActivityPaused(activity)
                    if (currentActivityRef?.get() !== activity) return
                    currentActivityResumed = false
                    cancelActivityUi()
                    heartbeatHandler.removeCallbacks(heartbeatRunnable)
                }
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    DexKitUpdateScan.onDestroyed(activity)
                    webDavDriveHook.onHostActivityDestroyed(activity)
                    readerAutoPage?.onHostActivityDestroyed(activity)
                    YouShuWebSearchBridge.onHostActivityDestroyed(activity)
                    if (currentActivityRef?.get() !== activity) return
                    currentActivityResumed = false
                    cancelActivityUi()
                    heartbeatHandler.removeCallbacks(heartbeatRunnable)
                    readerHook.onHostActivityDestroyed("MainActivity lifecycle destroyed")
                    readerFontCompletionHook.releaseMemory("MainActivity lifecycle destroyed")
                    readerDialogueHighlightHook.releaseMemory("MainActivity lifecycle destroyed")
                    profileBackgroundHook.releaseMemory("MainActivity lifecycle destroyed")
                    bookDetailsAssociationActionHook.releaseMemory("MainActivity lifecycle destroyed")
                    currentActivityRef = null
                }
            }
            application.registerActivityLifecycleCallbacks(lifecycleCallback)
            com.reamicro.fix.reload.ModuleResources.cleanup {
                application.unregisterComponentCallbacks(memoryCallback)
                application.unregisterActivityLifecycleCallbacks(lifecycleCallback)
                heartbeatHandler.removeCallbacksAndMessages(null)
                currentActivityResumed = false
                cancelActivityUi()
                globalFontRefreshPending = false
                bookshelfCompletion?.closeForReload()
                coverFade?.closeForReload()
                bookBackupScroll?.closeForReload()
                readerHook.onHostActivityDestroyed("API102 hot reload")
                readerFontCompletionHook.releaseMemory("API102 hot reload")
                readerDialogueHighlightHook.releaseMemory("API102 hot reload")
                profileBackgroundHook.releaseMemory("API102 hot reload")
                bookDetailsAssociationActionHook.releaseMemory("API102 hot reload")
                currentActivityRef = null
                attachStartedActivity = null
                attachReloadedActivity = null
            }
            memoryCallbacksInstalled = true
            HookRuntime.log("$LOG_PREFIX memory callbacks installed")
        }
    }

    private fun startForegroundHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun mirrorApiSettings(activity: Activity) {
        if (apiSettingsMirrored) return
        apiSettingsMirrored = ApiServerSettingsStore { activity.applicationContext }
            .get()
            .mirrorToModule(activity.applicationContext)
    }

    private fun installExternalFeatures(classLoader: ClassLoader) {
        val activity = currentActivityRef?.get() ?: return
        val features = ExternalSourceLoader.loadFeatures(activity.applicationContext)
        if (features.isEmpty()) return
        val api = ExternalFeatureApi(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settingsProvider = settingsProvider,
            fontSettingsProvider = moduleSettings::fontSettings,
            onSearchSourceDisabled = ::disableSearchSource,
        )
        features.forEach { feature ->
            if (feature.id in installedFeatureIds) return@forEach
            val disabledReason = ExternalFeatureCompatibility.disabledReason(feature.id, feature.capabilities)
            if (disabledReason != null) {
                if (disabledFeatureIds.add(feature.id)) {
                    HookInstallReport.record("ExternalFeature", feature.id, false,
                        UnsupportedOperationException(disabledReason))
                    HookRuntime.logAlways("$LOG_PREFIX external feature disabled: ${feature.id}, " +
                        "profile=${com.reamicro.fix.core.HostSymbols.profileId}, reason=$disabledReason")
                }
                return@forEach
            }
            if (!installedFeatureIds.add(feature.id)) return@forEach
            runCatching {
                feature.install(api)
                if (feature is com.reamicro.fix.association.provider.ReloadableFeatureProvider) {
                    com.reamicro.fix.reload.ModuleResources.blockWhile {
                        if (feature.canHotReload()) null else "扩展源尚未就绪：${feature.id}"
                    }
                    com.reamicro.fix.reload.ModuleResources.cleanup { feature.releaseForHotReload() }
                } else {
                    com.reamicro.fix.reload.HostGeneration.unsupportedExtension(feature.id)
                }
                HookRuntime.log("$LOG_PREFIX external feature installed: ${feature.id} ${feature.displayName}")
            }.onFailure {
                installedFeatureIds.remove(feature.id)
                HookRuntime.log("$LOG_PREFIX external feature ${feature.id} install failed: ${it.stackTraceToString()}")
            }
        }
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
        const val ENTRY_FEATURE_ID = "Entry"
        const val GLOBAL_FONT_RECREATE_DELAY_MS = 180L
    }
}
