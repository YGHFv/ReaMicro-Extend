package com.reamicro.fix.hook

import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ReaMicroLibXposedEntry : XposedModule() {
    private val hookEntry = ReaMicroHookEntry()

    override fun onPackageReady(param: PackageReadyParam) {
        XposedBridge.attachFramework(this)
        val moduleInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
        moduleInfo?.let(com.reamicro.fix.core.InjectedModuleContext::configure)
        val moduleApkPath = moduleInfo?.sourceDir
        ExternalSourceLoader.configure(moduleApkPath)
        ReaderHighlightImageAssets.configure(moduleApkPath)
        XposedBridge.log(
            "ReaMicro API102 entry ready: package=${param.packageName}, " +
                "api=${getApiVersion()}, framework=$frameworkName $frameworkVersion($frameworkVersionCode)",
        )
        if (param.packageName == "app.zhendong.reamicro") StructureHostStyle.install(param.classLoader)
        hookEntry.handleLoadedPackage(param.packageName, param.classLoader)
    }
}
