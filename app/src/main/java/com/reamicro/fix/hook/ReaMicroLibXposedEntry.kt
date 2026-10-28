package com.reamicro.fix.hook

import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ReaMicroLibXposedEntry : XposedModule() {
    private lateinit var hookEntry: ReaMicroHookEntry

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.attachFramework(this)
        val moduleInfo = getModuleApplicationInfo()
        com.reamicro.fix.core.InjectedModuleContext.configure(moduleInfo)
        ExternalSourceLoader.configure(moduleInfo.sourceDir)
        ReaderHighlightImageAssets.configure(moduleInfo.sourceDir)

        hookEntry = ReaMicroHookEntry()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "app.zhendong.reamicro" && param.packageName != "app.zhendong.reamicro.fix") return

        // 符号解析必须先于任何 hook：混淆版据此把逻辑符号名改写成真实类/方法名
        val apkSize = runCatching {
            param.applicationInfo?.sourceDir?.let { java.io.File(it).length() } ?: 0L
        }.getOrDefault(0L)
        com.reamicro.fix.core.HostSymbols.initialize(
            classLoader = param.classLoader,
            hostPackage = param.packageName,
            apkSize = apkSize,
            hostApkPath = param.applicationInfo?.sourceDir,
            hostDataDir = param.applicationInfo?.dataDir,
        )
        if (!com.reamicro.fix.core.HostSymbols.isIdentity) {
            com.reamicro.fix.core.HookInstallReport.install("Entry", "HostCoroutineProtocol") {
                com.reamicro.fix.core.HostCoroutines.verifyProtocol(param.classLoader)
            }
        }
        com.reamicro.fix.core.HookInstallReport.install("Entry", "HostCrashReportBlocker") {
            HostCrashReportBlocker.install(param.classLoader, param.packageName)
        }
        XposedBridge.log(
            "ReaMicro API102 entry ready: package=${param.packageName}, " +
                "symbols=${com.reamicro.fix.core.HostSymbols.summary()}, " +
                "api=${getApiVersion()}, framework=$frameworkName $frameworkVersion($frameworkVersionCode)",
        )
        if (param.packageName == "app.zhendong.reamicro") StructureHost232Colors.install(param.classLoader)
        hookEntry.handleLoadedPackage(param.packageName, param.classLoader)
    }
}
