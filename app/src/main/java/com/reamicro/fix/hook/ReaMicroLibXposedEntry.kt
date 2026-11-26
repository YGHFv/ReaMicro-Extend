package com.reamicro.fix.hook

import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.core.HostPackages
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.HotReloadContract
import com.reamicro.fix.reload.ModuleResources
import android.app.Application
import android.content.pm.ApplicationInfo

class ReaMicroLibXposedEntry : XposedModule() {
    private lateinit var hookEntry: ReaMicroHookEntry
    private val startupLoaders = java.util.IdentityHashMap<ClassLoader, MutableSet<String>>()
    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer || !HostPackages.contains(param.processName)) {
            detach()
            return
        }
        initializeGeneration(param)
    }

    private fun initializeGeneration(param: ModuleLoadedParam) {
        check(!param.isSystemServer && HostPackages.contains(param.processName)) { "Unsupported host process" }
        processName = param.processName
        synchronized(startupLoaders) { startupLoaders.clear() }
        ModuleResources.enable()
        HookRuntime.attachFramework(this)
        val moduleInfo = getModuleApplicationInfo()
        com.reamicro.fix.core.InjectedModuleContext.configure(moduleInfo)
        ExternalSourceLoader.configure(moduleInfo.sourceDir)
        ReaderHighlightImageAssets.configure(moduleInfo.sourceDir)

        hookEntry = ReaMicroHookEntry()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || !HostPackages.contains(param.packageName) || param.packageName != processName) return
        if (!synchronized(startupLoaders) {
                startupLoaders.getOrPut(param.classLoader) { mutableSetOf() }.add(param.packageName)
            }) return
        val started = android.os.SystemClock.elapsedRealtime()
        val startup = HostStartupBootstrap(param.packageName, param.classLoader, hookEntry)
        val packageName = param.packageName
        val loader = param.classLoader
        val applicationInfo = ApplicationInfo(param.applicationInfo)
        configureHostQueries(loader, applicationInfo)
        startup.observe(
            accept = { application ->
                val archive = HostPackages.inspect(application, applicationInfo.sourceDir)
                if (!archive.accepted) {
                    HookRuntime.logAlways("ReaMicro skipped host: ${archive.reason}")
                    false
                } else {
                    HookRuntime.setFeaturesEnabled(application.getSharedPreferences(
                        com.reamicro.fix.settings.ModuleSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        .getBoolean(com.reamicro.fix.settings.ModuleSettings.KEY_MODULE_ENABLED,
                            com.reamicro.fix.settings.ModuleSettings.DEFAULT_MODULE_ENABLED))
                    // Application.attach precedes ContentProvider/Sentry initialization. Verify
                    // the archive before these guards as well as before all functional hooks.
                    HostCrashReportBlocker.attachExistingApplication(application)
                    com.reamicro.fix.core.HostCrashBindings.initialize(loader,
                        applicationInfo.sourceDir, applicationInfo.dataDir)
                    com.reamicro.fix.core.HookInstallReport.install("Entry", "EarlyCrashReportBlocker") {
                        HostCrashReportBlocker.install(loader, packageName)
                    }
                    true
                }
            },
            install = { installPackage(packageName, loader, applicationInfo) },
        )
        HookRuntime.logAlways("ReaMicro startup package callback released earlyMs=" +
            "${android.os.SystemClock.elapsedRealtime() - started} pid=${android.os.Process.myPid()}")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 拒绝旧桥接进程尚未结束的升级重试；框架自动更新已由 module.prop 关闭。
        if (param.extras?.getBoolean(HotReloadContract.PACKAGE_UPGRADE) == true) return false
        return HostGeneration.prepare(
            param.extras?.getBoolean(HotReloadContract.IN_PLACE) == true,
            param::setSavedInstanceState,
        )
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val started = android.os.SystemClock.elapsedRealtime()
        val state = param.savedInstanceState as? Array<*> ?: error("Missing neutral reload state")
        val application = state.getOrNull(0) as? Application ?: error("Missing host application")
        val loader = state.getOrNull(1) as? ClassLoader ?: error("Missing host classloader")
        val packageName = state.getOrNull(2) as? String ?: error("Missing host package")
        check(HostPackages.contains(packageName) && packageName == param.processName) { "Unexpected reload target" }
        val composition = (state.getOrNull(3) as? Array<*>)?.takeIf { it.isNotEmpty() }
        // Version 92 handed off a foreground-only composition without this fifth value.
        val resumed = state.getOrNull(4) as? Boolean ?: (composition != null)
        var scanStarted = false
        try {
            val archive = HostPackages.inspect(
                application, application.applicationInfo.sourceDir,
            )
            check(archive.accepted) { archive.reason }
            initializeGeneration(param)
            configureHostQueries(loader, application.applicationInfo)
            hookEntry.attachStartupContext(application)
            HostCrashReportBlocker.attachExistingApplication(application)
            com.reamicro.fix.core.HostCrashBindings.initialize(loader,
                application.applicationInfo.sourceDir, application.applicationInfo.dataDir)
            val guardMethods = requireNotNull(com.reamicro.fix.core.HostCrashBindings.current)
                .guards.map { it.first }.toSet()
            val previousPrivacy = param.oldHookHandles.filter {
                it.id?.startsWith(HostCrashReportBlocker.GUARD_ID_PREFIX) == true ||
                    (it.id == null && it.executable in guardMethods)
            }
            // Replace privacy callbacks before removing any other callback. Legacy null-id handles
            // are recognized by the current host contract and remain recognizable after replacement.
            check(com.reamicro.fix.core.HookInstallReport.install("Entry", "EarlyCrashReportBlocker") {
                HostCrashReportBlocker.install(loader, packageName, previousPrivacy)
            }) { "Privacy guard replacement incomplete; previous guards retained" }
            var oldHookError: Throwable? = null
            param.oldHookHandles.filterNot { it in previousPrivacy }.forEach { handle ->
                try {
                    handle.unhook()
                } catch (error: Throwable) {
                    val previous = oldHookError
                    if (previous == null) oldHookError = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            oldHookError?.let { throw it }
            val prepared = android.os.SystemClock.elapsedRealtime()
            DexKitUpdateScan.beginStartup(application)
            scanStarted = true
            installPackage(packageName, loader, application.applicationInfo)
            HostGeneration.attach(application, loader)
            HostCrashReportBlocker.attachExistingApplication(application)
            if (composition != null) HostGeneration.onMain {
                val activity = composition[0] as android.app.Activity
                HostGeneration.adopt(activity, resumed)
                if (resumed) DexKitUpdateScan.onStartupResume(activity)
                hookEntry.attachAfterHotReload(activity, resumed)
                com.reamicro.fix.reload.HostCompositionReload.restore(composition)
            }
            check(com.reamicro.fix.core.HookInstallReport.failures().isEmpty()) {
                "New generation hook installation incomplete: ${com.reamicro.fix.core.HookInstallReport.failures()}"
            }
            DexKitUpdateScan.finishStartup(application, com.reamicro.fix.core.HostAutoScanner.snapshot())
            HookRuntime.logAlways("ReaMicro API102 new generation ready pid=${android.os.Process.myPid()} " +
                "oldHooks=${param.oldHookHandles.size} ${com.reamicro.fix.core.HookInstallReport.summaryLine()} " +
                "prepareMs=${prepared - started} installMs=${android.os.SystemClock.elapsedRealtime() - prepared} " +
                "activity=${composition?.getOrNull(0)?.let(System::identityHashCode)} resumed=$resumed")
        } catch (error: Throwable) {
            if (scanStarted) runCatching {
                DexKitUpdateScan.finishStartup(application, com.reamicro.fix.core.HostAutoScanner.snapshot(), error)
            }.onFailure { if (it !== error) error.addSuppressed(it) }
            if (composition != null) try {
                HostGeneration.onMain { com.reamicro.fix.reload.HostCompositionReload.restoreIfMissing(composition) }
            } catch (restoreError: Throwable) {
                if (restoreError !== error) error.addSuppressed(restoreError)
            }
            throw error
        }
    }

    private fun installPackage(packageName: String, classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        configureHostQueries(classLoader, applicationInfo)
        com.reamicro.fix.core.HostQuerySession.use(classLoader, applicationInfo.sourceDir, applicationInfo.dataDir) {
            installWithQueries(packageName, classLoader, applicationInfo)
        }
    }
    private fun configureHostQueries(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        com.reamicro.fix.core.HostQuerySession.configure(
            classLoader, applicationInfo.sourceDir, applicationInfo.splitSourceDirs?.toList().orEmpty(),
        )
    }
    private fun installWithQueries(packageName: String, classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        com.reamicro.fix.core.HostBindingBootstrap.initialize(classLoader, packageName,
            applicationInfo.sourceDir, applicationInfo.dataDir,
            afterCrash = Runnable {
                com.reamicro.fix.core.HookInstallReport.install("Entry", "HostCrashReportBlocker") {
                    HostCrashReportBlocker.install(classLoader, packageName)
                }
            },
            afterUi = Runnable {
                com.reamicro.fix.core.HookInstallReport.install("Entry", "NativeThemeCapture") {
                    HookRuntime.withManagementHooks { HostThemeColors.install(classLoader) }
                }
                HookRuntime.withManagementHooks { HostSettingsDialogs.install(classLoader, hookEntry.activityProvider) }
                DexKitUpdateScan.onUiReady()
            },
        )
        HookRuntime.log("ReaMicro API102 entry ready: package=$packageName, " +
            "symbols=${com.reamicro.fix.core.HostSymbols.summary()}, " +
            "api=${getApiVersion()}, framework=$frameworkName $frameworkVersion($frameworkVersionCode)")
        hookEntry.handleLoadedPackage(packageName, classLoader)
        com.reamicro.fix.core.HookInstallReport.install("Entry", "RootCompositionCallbacks") {
            com.reamicro.fix.reload.HostCompositionReload.prepareCallbacks()
        }
        com.reamicro.fix.core.HostAutoScanner.markInstalled()
    }
}
