package com.reamicro.fix.hook

import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.xposed.XposedBridge
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

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        initializeGeneration()
    }

    private fun initializeGeneration() {
        ModuleResources.enable()
        XposedBridge.attachFramework(this)
        val moduleInfo = getModuleApplicationInfo()
        com.reamicro.fix.core.InjectedModuleContext.configure(moduleInfo)
        ExternalSourceLoader.configure(moduleInfo.sourceDir)
        ReaderHighlightImageAssets.configure(moduleInfo.sourceDir)

        hookEntry = ReaMicroHookEntry()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "app.zhendong.reamicro" && param.packageName != "app.zhendong.reamicro.fix") return
        installPackage(param.packageName, param.classLoader, param.applicationInfo)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val state = HostGeneration.prepare(param.extras?.getBoolean(HotReloadContract.IN_PLACE) == true)
            ?: return false
        param.setSavedInstanceState(state)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val started = android.os.SystemClock.elapsedRealtime()
        val state = param.savedInstanceState as? Array<*> ?: error("Missing neutral reload state")
        val application = state.getOrNull(0) as? Application ?: error("Missing host application")
        val loader = state.getOrNull(1) as? ClassLoader ?: error("Missing host classloader")
        val packageName = state.getOrNull(2) as? String ?: error("Missing host package")
        val composition = state.getOrNull(3) as? Array<*>
        initializeGeneration()
        param.oldHookHandles.forEach { it.unhook() }
        val prepared = android.os.SystemClock.elapsedRealtime()
        installPackage(packageName, loader, application.applicationInfo)
        HostGeneration.attach(application)
        HostCrashReportBlocker.attachExistingApplication(application)
        if (composition != null) HostGeneration.onMain {
            val activity = composition[0] as android.app.Activity
            HostGeneration.adopt(activity)
            hookEntry.attachAfterHotReload(activity)
            com.reamicro.fix.reload.HostCompositionReload.restore(composition)
        }
        check(com.reamicro.fix.core.HookInstallReport.failures().isEmpty()) {
            "New generation hook installation incomplete: ${com.reamicro.fix.core.HookInstallReport.failures()}"
        }
        XposedBridge.logAlways("ReaMicro API102 new generation ready pid=${android.os.Process.myPid()} " +
            "oldHooks=${param.oldHookHandles.size} ${com.reamicro.fix.core.HookInstallReport.summaryLine()} " +
            "prepareMs=${prepared - started} installMs=${android.os.SystemClock.elapsedRealtime() - prepared} " +
            "activity=${composition?.getOrNull(0)?.let(System::identityHashCode)}")
    }

    private fun installPackage(packageName: String, classLoader: ClassLoader, applicationInfo: ApplicationInfo) {

        // 符号解析必须先于任何 hook：混淆版据此把逻辑符号名改写成真实类/方法名
        val apkSize = runCatching {
            java.io.File(applicationInfo.sourceDir).length()
        }.getOrDefault(0L)
        com.reamicro.fix.core.HostSymbols.initialize(
            classLoader = classLoader,
            hostPackage = packageName,
            apkSize = apkSize,
            hostApkPath = applicationInfo.sourceDir,
            hostDataDir = applicationInfo.dataDir,
        )
        if (!com.reamicro.fix.core.HostSymbols.isIdentity) {
            com.reamicro.fix.core.HookInstallReport.install("Entry", "HostCoroutineProtocol") {
                com.reamicro.fix.core.HostCoroutines.verifyProtocol(classLoader)
            }
        }
        com.reamicro.fix.core.HookInstallReport.install("Entry", "HostCrashReportBlocker") {
            HostCrashReportBlocker.install(classLoader, packageName)
        }
        XposedBridge.log(
            "ReaMicro API102 entry ready: package=$packageName, " +
                "symbols=${com.reamicro.fix.core.HostSymbols.summary()}, " +
                "api=${getApiVersion()}, framework=$frameworkName $frameworkVersion($frameworkVersionCode)",
        )
        if (packageName == "app.zhendong.reamicro") StructureHost232Colors.install(classLoader)
        hookEntry.handleLoadedPackage(packageName, classLoader)
    }
}
