package com.reamicro.fix.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R
import android.widget.Toast
import java.io.File
import com.reamicro.fix.xposed.XposedBridge

object InjectedModuleContext {
    @Volatile private var moduleInfo: ApplicationInfo? = null

    fun configure(info: ApplicationInfo) {
        moduleInfo = ApplicationInfo(info).apply {
            if (publicSourceDir.isNullOrBlank()) publicSourceDir = sourceDir
            if (splitPublicSourceDirs == null) splitPublicSourceDirs = splitSourceDirs
        }
    }

    fun runUiAction(base: Context?, action: () -> Unit) {
        ModuleResourceRecovery.run(action) { error ->
            XposedBridge.logError("Module resource generation expired; restart the host instead of loading another APK", error)
            base?.let { Toast.makeText(it, ModuleResourceRecovery.RESTART_MESSAGE, Toast.LENGTH_LONG).show() }
        }
    }

    fun create(base: Context): Context = try {
        createPinned(base)
    } catch (error: ModuleResourcesUnavailableException) {
        throw error
    } catch (error: Exception) {

        throw ModuleResourcesUnavailableException(ModuleResourceRecovery.RESTART_MESSAGE, error)
    }

    private fun createPinned(base: Context): Context {
        val info = moduleInfo
        if (info == null && base.packageName == BuildConfig.APPLICATION_ID) return base
        checkNotNull(info) { "模块资源尚未初始化，请完全重启阅微后重试" }
        check(!info.sourceDir.isNullOrBlank()) { "模块 APK 路径无效，请重启阅微" }
        check(File(info.sourceDir).isFile) { "Pinned module APK is no longer present" }

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

        }
    }
}
