package com.reamicro.fix.hook

import com.reamicro.fix.association.provider.ExternalSourceLoader
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ReaMicroLibXposedEntry : XposedModule() {
    private val hookEntry = ReaMicroHookEntry()

    override fun onPackageReady(param: PackageReadyParam) {
        XposedBridge.attachFramework(this)
        val moduleApkPath = runCatching { getModuleApplicationInfo().sourceDir }.getOrNull()
        ExternalSourceLoader.configure(moduleApkPath)
        XposedBridge.log(
            "ReaMicro API101 entry ready: package=${param.packageName}, " +
                "api=${getApiVersion()}, framework=$frameworkName $frameworkVersion($frameworkVersionCode)",
        )
        hookEntry.handleLoadedPackage(param.packageName, param.classLoader)
    }
}
