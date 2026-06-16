package com.reamicro.fix.hook

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.ExternalFeatureApi
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

class ReaMicroHookEntry {
    private var currentActivityRef: WeakReference<Activity>? = null
    private val moduleSettings = XposedModuleSettings { currentActivityRef?.get() }
    private val installedFeatureIds = linkedSetOf<String>()

    fun handleLoadedPackage(packageName: String, classLoader: ClassLoader) {
        if (packageName !in REAMICRO_PACKAGES) return
        XposedBridge.log("$LOG_PREFIX loaded for $packageName")
        YouShuWebSearchBridge.attach { currentActivityRef?.get() }
        ReaderHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
        ).install()
        ReaderAutoPageHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
        ).install()
        ReaderImportOverwriteHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
        ).install()
        val globalFontHook = ReaMicroGlobalFontHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settings = moduleSettings,
        )
        globalFontHook.install()
        ReaderFontCompletionHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settings = moduleSettings,
        ).install()
        FileEditCompletionHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
            fontSettingsProvider = moduleSettings::fontSettings,
        ).install()
        ReaMicroSettingsHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settings = moduleSettings,
            onGlobalFontChanged = {
                globalFontHook.invalidateGlobalFontCache()
                refreshCurrentActivityForGlobalFont()
            },
        ).install()
        AssociationSearchHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
        ).install()
        LocalExportHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
        ).install()
        val webDavDriveHook = WebDavDriveHook(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
        )
        webDavDriveHook.install()
        hookMainActivity(classLoader, webDavDriveHook)
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

    private fun hookMainActivity(classLoader: ClassLoader, webDavDriveHook: WebDavDriveHook) {
        runCatching {
            val mainActivityClass = XposedHelpers.findClass("app.zhendong.reamicro.MainActivity", classLoader)
            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
                        moduleSettings.attachContext(activity)
                        installExternalFeatures(classLoader)
                        RotationOrientationController.apply(activity, moduleSettings.snapshot())
                        webDavDriveHook.cleanupStartupCacheIfNeeded(activity)
                        XposedBridge.log("$LOG_PREFIX MainActivity.onCreate hooked")
                    }

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        currentActivityRef = WeakReference(activity)
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
                            moduleSettings.attachContext(activity)
                            installExternalFeatures(classLoader)
                            RotationOrientationController.apply(activity, moduleSettings.snapshot())
                        }
                    },
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX MainActivity does not override onResume; using onCreate ref only")
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook MainActivity: ${it.stackTraceToString()}")
        }
    }

    private fun installExternalFeatures(classLoader: ClassLoader) {
        val activity = currentActivityRef?.get() ?: return
        val features = ExternalSourceLoader.loadFeatures(activity.applicationContext)
        if (features.isEmpty()) return
        val api = ExternalFeatureApi(
            classLoader = classLoader,
            activityProvider = { currentActivityRef?.get() },
            settingsProvider = moduleSettings::snapshot,
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
        const val GLOBAL_FONT_RECREATE_DELAY_MS = 180L
    }
}
