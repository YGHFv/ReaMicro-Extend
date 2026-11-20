package com.reamicro.fix.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R
import com.reamicro.fix.reload.ModuleResources
import android.widget.Toast
import java.io.File
import java.nio.file.Files
import com.reamicro.fix.xposed.HookRuntime

object InjectedModuleContext {
    @Volatile private var moduleInfo: ApplicationInfo? = null
    private var resourceInfo: ApplicationInfo? = null
    private var pinnedApks: List<ParcelFileDescriptor> = emptyList()
    private var resourceDirectory: File? = null

    init {
        ModuleResources.cleanup {
            synchronized(this) {
                removeResourceDirectory()
                closeApks(pinnedApks)
                pinnedApks = emptyList()
                resourceInfo = null
                moduleInfo = null
            }
        }
    }

    @Synchronized fun configure(info: ApplicationInfo) {
        val snapshot = ApplicationInfo(info).apply {
            if (publicSourceDir.isNullOrBlank()) publicSourceDir = sourceDir
            if (splitPublicSourceDirs == null) splitPublicSourceDirs = splitSourceDirs
        }
        val opened = mutableListOf<ParcelFileDescriptor>()
        val resources = runCatching {
            fun pin(path: String): String {
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                opened += descriptor
                return "/proc/self/fd/${descriptor.fd}"
            }
            // Keep this generation's inode readable after an update unlinks its installed APK.
            ApplicationInfo(snapshot).apply {
                sourceDir = pin(requireNotNull(snapshot.sourceDir))
                publicSourceDir = sourceDir
                splitSourceDirs = snapshot.splitSourceDirs?.map(::pin)?.toTypedArray()
                splitPublicSourceDirs = splitSourceDirs
            }
        }.getOrElse { error ->
            closeApks(opened)
            opened.clear()
            HookRuntime.logError("ReaMicro could not retain module resource APKs", error)
            snapshot
        }
        removeResourceDirectory()
        closeApks(pinnedApks)
        pinnedApks = opened.toList()
        moduleInfo = snapshot
        resourceInfo = resources
    }

    private fun retainResourcePaths(base: Context): ApplicationInfo? {
        val info = resourceInfo ?: return null
        if (pinnedApks.isEmpty()) return info
        if (resourceDirectory != null && File(info.sourceDir).isFile &&
            info.splitSourceDirs.orEmpty().all { File(it).isFile }) return info
        removeResourceDirectory()
        val parent = File(base.codeCacheDir, "reamicro-module-resources")
        check(parent.isDirectory || parent.mkdirs()) { "Module resource directory unavailable" }
        // Only dead processes' directories are stale; another live generation may still be exiting.
        parent.listFiles()?.filter { directory ->
            directory.name.substringBefore('-').toIntOrNull()?.let { !File("/proc/$it").exists() } == true
        }?.forEach(::removeResourceDirectory)
        val directory = Files.createTempDirectory(parent.toPath(), "${Process.myPid()}-").toFile()
        try {
            // Framework caches use the path as a key; FD numbers alone can repeat after a reload.
            val aliases = pinnedApks.mapIndexed { index, descriptor ->
                File(directory, "$index.apk").also { Os.symlink("/proc/self/fd/${descriptor.fd}", it.path) }.path
            }
            return ApplicationInfo(info).apply {
                sourceDir = aliases.first()
                publicSourceDir = sourceDir
                splitSourceDirs = aliases.drop(1).takeIf { it.isNotEmpty() }?.toTypedArray()
                splitPublicSourceDirs = splitSourceDirs
            }.also {
                resourceInfo = it
                resourceDirectory = directory
            }
        } catch (error: Throwable) {
            removeResourceDirectory(directory)
            throw error
        }
    }

    private fun removeResourceDirectory(directory: File? = resourceDirectory) {
        directory ?: return
        // Unlink the aliases themselves, never follow them to the retained APK.
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        if (directory === resourceDirectory) resourceDirectory = null
    }

    private fun closeApks(apks: List<ParcelFileDescriptor>) {
        apks.forEach { descriptor ->
            runCatching { descriptor.close() }.onFailure {
                HookRuntime.logError("ReaMicro resource APK handle close failed", it)
            }
        }
    }

    /** 供符号解析层读取模块 APK 路径；未初始化时返回 null。 */
    fun sourceDirOrNull(): String? = moduleInfo?.sourceDir

    fun runUiAction(base: Context?, action: () -> Unit) {
        ModuleResourceRecovery.run(action) { error ->
            HookRuntime.logError("Module resource generation expired; restart the host instead of loading another APK", error)
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

    @Synchronized private fun createPinned(base: Context): Context {
        val info = retainResourcePaths(base)
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
