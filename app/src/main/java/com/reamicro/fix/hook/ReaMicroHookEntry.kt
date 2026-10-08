package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.ExternalFeatureApi
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.cloud.api.ApiPackageAutoUpdater
import com.reamicro.fix.cloud.api.CloudTaskNotificationPoller
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.mirrorToModule
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference

class ReaMicroHookEntry {
    private var currentActivityRef: WeakReference<Activity>? = null
    @Volatile private var currentActivityResumed: Boolean = false
    private val moduleSettings = XposedModuleSettings { currentActivityRef?.get() }

    private val activityProvider: () -> Activity? = { currentActivityRef?.get() }
    private val settingsProvider: () -> ModuleSettingsSnapshot = moduleSettings::snapshot
    private val installedFeatureIds = linkedSetOf<String>()
    @Volatile private var memoryCallbacksInstalled: Boolean = false
    @Volatile private var apiSettingsMirrored: Boolean = false
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val activity = currentActivityRef?.get()
            if (activity != null && currentActivityResumed) {
                val appContext = activity.applicationContext
                CloudTaskNotificationPoller.poll(appContext, source = "foreground-heartbeat")

                com.reamicro.fix.cloud.local.LocalTaskMirror.push(appContext)
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun handleLoadedPackage(packageName: String, classLoader: ClassLoader) {
        if (packageName !in REAMICRO_PACKAGES) return
        XposedBridge.log("$LOG_PREFIX loaded for $packageName")
        YouShuWebSearchBridge.attach { currentActivityRef?.get() }
        val readerHook = ReaderHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settingsProvider = settingsProvider,
            settings = moduleSettings,
            isActivityResumedProvider = { currentActivityResumed },
        )
        installFeature("ReaderHook", readerHook::install)
        installFeature("ReaderAutoPageHook") {
            ReaderAutoPageHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
            ).install()
        }
        HookInstallReport.installResult(ENTRY_FEATURE_ID, "ReaderImportOverwriteHook") {
            ReaderImportOverwriteHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settingsProvider = settingsProvider,
            ).install()
        }
        val globalFontHook = ReaMicroGlobalFontHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settings = moduleSettings,
        )
        installFeature("ReaMicroGlobalFontHook", globalFontHook::install)
        installFeature("ReaderFontCompletionHook") {
            ReaderFontCompletionHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settings = moduleSettings,
            ).install()
        }
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
        installFeature("BookOverviewImageSelectionHook") {
            BookOverviewImageSelectionHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                requestCoverFix = bookDetailsAssociationActionHook::requestCoverFixForCurrentDetails,
            ).install()
        }
        installFeature("TravelingMerchantEndTimeHook") {
            TravelingMerchantEndTimeHook(classLoader).install()
        }
        installFeature("LocalExportHook") {
            LocalExportHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
            ).install()
        }
        val webDavDriveHook = WebDavDriveHook(
            classLoader = classLoader,
            activityProvider = activityProvider,
            settingsProvider = settingsProvider,
            globalTypefaceProvider = globalFontHook::globalAndroidTypeface,
        )
        installFeature("WebDavDriveHook", webDavDriveHook::install)

        installFeature("DiscoverCardHook") {
            DiscoverCardHook(classLoader).install()
        }
        installFeature("MainActivity") {
            hookMainActivity(
                classLoader = classLoader,
                webDavDriveHook = webDavDriveHook,
                profileBackgroundHook = profileBackgroundHook,
                readerHook = readerHook,
                readerDialogueHighlightHook = readerDialogueHighlightHook,
                bookDetailsAssociationActionHook = bookDetailsAssociationActionHook,
            )
        }
        logHookInstallReport()
    }

    private fun installFeature(feature: String, block: () -> Unit) {
        HookInstallReport.install(ENTRY_FEATURE_ID, feature, block)
    }

    private fun logHookInstallReport() {
        XposedBridge.logAlways("$LOG_PREFIX ${HookInstallReport.summaryLine()}")
        HookInstallReport.failureDetails().forEach { detail ->
            XposedBridge.logError("$LOG_PREFIX hook install failure: $detail")
        }
    }

    private fun disableSearchSource(source: BookSource, message: String) {
        val groupId = ModuleSettings.searchSourceGroupId(source) ?: source.id
        moduleSettings.setAssociationSearchSourceEnabled(groupId, false)
        val activity = currentActivityRef?.get()
        if (activity != null) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
        XposedBridge.log("$LOG_PREFIX ${source.displayName} disabled: $message")
    }

    private fun refreshCurrentActivityForGlobalFont() {
        val activity = currentActivityRef?.get() ?: return
        activity.runOnUiThread {
            activity.window?.decorView?.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                runCatching {
                    activity.recreate()
                    XposedBridge.log("$LOG_PREFIX global UI font activity recreated")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX global UI font activity recreate failed: ${it.stackTraceToString()}")
                }
            }, GLOBAL_FONT_RECREATE_DELAY_MS)
        }
    }

    private fun hookMainActivity(
        classLoader: ClassLoader,
        webDavDriveHook: WebDavDriveHook,
        profileBackgroundHook: ProfileBackgroundHook,
        readerHook: ReaderHook,
        readerDialogueHighlightHook: ReaderDialogueHighlightHook,
        bookDetailsAssociationActionHook: BookDetailsAssociationActionHook,
    ) {
        HookInstallReport.install(ENTRY_FEATURE_ID, "mainActivityCreate") {
            val mainActivityClass = XposedHelpers.findClass("app.zhendong.reamicro.MainActivity", classLoader)

            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!mainActivityClass.isInstance(param.thisObject)) return
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
                        currentActivityResumed = false
                        moduleSettings.attachContext(activity)
                        mirrorApiSettings(activity)
                        installExternalFeatures(classLoader)
                        installMemoryCallbacks(
                            application = activity.application,
                            readerHook = readerHook,
                            readerDialogueHighlightHook = readerDialogueHighlightHook,
                            profileBackgroundHook = profileBackgroundHook,
                            bookDetailsAssociationActionHook = bookDetailsAssociationActionHook,
                        )
                        RotationOrientationController.apply(activity, moduleSettings.snapshot())
                        webDavDriveHook.cleanupStartupCacheIfNeeded(activity)
                        profileBackgroundHook.refreshRandomImageFor(activity)
                        ApiPackageAutoUpdater.checkIfDue(activity.applicationContext, moduleSettings)
                        CloudTaskNotificationPoller.poll(activity.applicationContext)
                        val appContext = activity.applicationContext
                        com.reamicro.fix.migration.LegacyAndroidWakeCleanup.runForHost(appContext)

                        com.reamicro.fix.cloud.local.LocalTaskMirror.push(appContext)
                        startForegroundHeartbeat()
                        XposedBridge.log("$LOG_PREFIX MainActivity.onCreate hooked")
                    }

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!mainActivityClass.isInstance(param.thisObject)) return
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
                        currentActivityResumed = false
                        moduleSettings.attachContext(activity)
                        installExternalFeatures(classLoader)
                    }
                },
            )
            HookInstallReport.install(ENTRY_FEATURE_ID, "mainActivityResume") {
                XposedHelpers.findAndHookMethod(
                    mainActivityClass,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!mainActivityClass.isInstance(param.thisObject)) return
                            val activity = param.thisObject as? Activity ?: return
                            currentActivityRef = WeakReference(activity)
                            currentActivityResumed = true
                            moduleSettings.attachContext(activity)
                            mirrorApiSettings(activity)
                            installExternalFeatures(classLoader)
                            RotationOrientationController.apply(activity, moduleSettings.snapshot())
                            profileBackgroundHook.refreshRandomImageFor(activity)
                            CloudTaskNotificationPoller.poll(activity.applicationContext)
                            startForegroundHeartbeat()
                        }
                    },
                )
            }

        }
    }

    private fun installMemoryCallbacks(
        application: Application,
        readerHook: ReaderHook,
        readerDialogueHighlightHook: ReaderDialogueHighlightHook,
        profileBackgroundHook: ProfileBackgroundHook,
        bookDetailsAssociationActionHook: BookDetailsAssociationActionHook,
    ) {
        if (memoryCallbacksInstalled) return
        synchronized(this) {
            if (memoryCallbacksInstalled) return
            application.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onTrimMemory(level: Int) {
                    readerHook.onTrimMemory(level)
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                        readerDialogueHighlightHook.releaseMemory("trim memory level=$level")
                        profileBackgroundHook.releaseMemory("trim memory level=$level")
                        bookDetailsAssociationActionHook.releaseMemory("trim memory level=$level")
                    }
                }

                override fun onLowMemory() {
                    readerHook.onLowMemory()
                    readerDialogueHighlightHook.releaseMemory("system low memory")
                    profileBackgroundHook.releaseMemory("system low memory")
                    bookDetailsAssociationActionHook.releaseMemory("system low memory")
                }
            })
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) {
                    if (currentActivityRef?.get() !== activity) return
                    currentActivityResumed = false
                    heartbeatHandler.removeCallbacks(heartbeatRunnable)
                }
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity.javaClass.name != "app.zhendong.reamicro.MainActivity") return
                    if (activity.isChangingConfigurations) return
                    readerHook.onHostActivityDestroyed("MainActivity lifecycle destroyed")
                    readerDialogueHighlightHook.releaseMemory("MainActivity lifecycle destroyed")
                    profileBackgroundHook.releaseMemory("MainActivity lifecycle destroyed")
                    bookDetailsAssociationActionHook.releaseMemory("MainActivity lifecycle destroyed")
                    if (currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                        currentActivityResumed = false
                        heartbeatHandler.removeCallbacks(heartbeatRunnable)
                    }
                }
            })
            memoryCallbacksInstalled = true
            XposedBridge.log("$LOG_PREFIX memory callbacks installed")
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
            if (!installedFeatureIds.add(feature.id)) return@forEach
            runCatching {
                feature.install(api)
                XposedBridge.log("$LOG_PREFIX external feature installed: ${feature.id} ${feature.displayName}")
            }.onFailure {
                installedFeatureIds.remove(feature.id)
                XposedBridge.log("$LOG_PREFIX external feature ${feature.id} install failed: ${it.stackTraceToString()}")
            }
        }
    }

    private companion object {
        val REAMICRO_PACKAGES = setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix")
        const val LOG_PREFIX = "ReaMicro LSP"
        const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
        const val ENTRY_FEATURE_ID = "Entry"
        const val GLOBAL_FONT_RECREATE_DELAY_MS = 180L
    }
}
