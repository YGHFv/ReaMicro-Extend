package com.reamicro.fix.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R

/**
 * Resources for a module-owned Compose island, without looking up the module by package name.
 *
 * A scoped/hidden module can be loaded by LSPosed while createPackageContext(packageName)
 * fails in the host. getResourcesForApplication(ApplicationInfo) uses the APK paths already
 * supplied by the framework instead of doing that filtered package lookup.
 * Never add module assets to the host's Resources and never change its AppOps identity.
 */
object InjectedModuleContext {
    @Volatile private var moduleInfo: ApplicationInfo? = null

    fun configure(info: ApplicationInfo) {
        moduleInfo = ApplicationInfo(info).apply {
            if (publicSourceDir.isNullOrBlank()) publicSourceDir = sourceDir
            if (splitPublicSourceDirs == null) splitPublicSourceDirs = splitSourceDirs
        }
    }

    fun create(base: Context): Context {
        val info = moduleInfo
        if (info == null && base.packageName == BuildConfig.APPLICATION_ID) return base
        checkNotNull(info) { "模块资源尚未初始化，请完全重启阅微后重试" }
        check(!info.sourceDir.isNullOrBlank()) { "模块 APK 路径无效，请重启阅微" }
        val resources = base.packageManager.getResourcesForApplication(info)
        check(resources.getResourceEntryName(R.string.module_name) == "module_name") {
            "模块资源版本不匹配，请重启阅微"
        }
        val theme = resources.newTheme().apply {
            applyStyle(if (info.theme != 0) info.theme else R.style.AppTheme, true)
        }
        return object : ContextWrapper(base) {
            override fun getResources() = resources
            override fun getAssets() = resources.assets
            override fun getTheme() = theme
            override fun getClassLoader() = InjectedModuleContext::class.java.classLoader!!
            // packageName, applicationContext and system services deliberately retain base identity.
        }
    }
}
