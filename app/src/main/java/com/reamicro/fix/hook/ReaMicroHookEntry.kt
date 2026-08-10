package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.ExternalFeatureApi
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * Single Xposed entry point for the ReaMicro host process.
 *
 * Keep feature hooks installed from here so activity tracking, settings snapshots, and
 * optional external feature APIs all share the same host Activity reference.
 */
class ReaMicroHookEntry {
    private var currentActivityRef: WeakReference<Activity>? = null
    @Volatile private var currentActivityResumed: Boolean = false
    private val moduleSettings = XposedModuleSettings { currentActivityRef?.get() }

    // 每个功能 hook 都要这两个 provider；此前 17 处各写一遍同样的 lambda，
    // 收成字段后既少一份重复，也保证所有 hook 拿到的是同一个 Activity 视图。
    private val activityProvider: () -> Activity? = { currentActivityRef?.get() }
    private val settingsProvider: () -> ModuleSettingsSnapshot = moduleSettings::snapshot
    private val installedFeatureIds = linkedSetOf<String>()
    @Volatile private var memoryCallbacksInstalled: Boolean = false

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
        installFeature("ReaderImportOverwriteHook") {
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
        installFeature("ReaderBackgroundHook") {
            ReaderBackgroundHook(
                classLoader = classLoader,
                activityProvider = activityProvider,
                settings = moduleSettings,
                settingsProvider = settingsProvider,
            ).install()
        }
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
        )
        installFeature("WebDavDriveHook", webDavDriveHook::install)
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

    /**
     * 安装一个功能 hook 并登记结果。
     *
     * 单个功能的构造或 install 抛异常时不再中断后续功能的安装——此前一个功能挂掉
     * 会连带后面所有功能一起消失，而日志里只有一行栈。
     */
    private fun installFeature(feature: String, block: () -> Unit) {
        HookInstallReport.install(ENTRY_FEATURE_ID, feature, block)
    }

    /** 打印 hook 安装汇总。宿主升级后对比这一行即可定位掉线的 hook。 */
    private fun logHookInstallReport() {
        XposedBridge.log("$LOG_PREFIX ${HookInstallReport.summaryLine()}")
        HookInstallReport.failureDetails().forEach { detail ->
            XposedBridge.log("$LOG_PREFIX hook install failure: $detail")
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
        runCatching {
            val mainActivityClass = XposedHelpers.findClass("app.zhendong.reamicro.MainActivity", classLoader)
            // Most feature hooks need a live Activity for Compose state, storage, and toasts.
            // Capture it both before and after onCreate so early hooks and post-create work
            // can use the same settings context.
            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
                        currentActivityResumed = false
                        moduleSettings.attachContext(activity)
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
                        XposedBridge.log("$LOG_PREFIX MainActivity.onCreate hooked")
                    }

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
                        currentActivityResumed = false
                        moduleSettings.attachContext(activity)
                        installExternalFeatures(classLoader)
                    }
                },
            )
            runCatching {
                XposedHelpers.findAndHookMethod(
                    mainActivityClass,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            currentActivityRef = WeakReference(activity)
                            currentActivityResumed = true
                            moduleSettings.attachContext(activity)
                            installExternalFeatures(classLoader)
                            RotationOrientationController.apply(activity, moduleSettings.snapshot())
                            profileBackgroundHook.refreshRandomImageFor(activity)
                        }
                    },
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX MainActivity does not override onResume; using onCreate ref only")
            }
            runCatching {
                XposedHelpers.findAndHookMethod(
                    mainActivityClass,
                    "onPause",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (currentActivityRef?.get() === activity) {
                                currentActivityResumed = false
                            }
                        }
                    },
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX MainActivity does not override onPause; background state may be conservative")
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook MainActivity: ${it.stackTraceToString()}")
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
                override fun onActivityPaused(activity: Activity) = Unit
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
                    }
                }
            })
            memoryCallbacksInstalled = true
            XposedBridge.log("$LOG_PREFIX memory callbacks installed")
        }
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
        const val ENTRY_FEATURE_ID = "Entry"
        const val GLOBAL_FONT_RECREATE_DELAY_MS = 180L
    }
}
