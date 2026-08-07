package com.reamicro.fix.hook

import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/** 从模块 APK 直接读取内置阅读背景，避免宿主包可见性限制。 */
internal object ReaderBackgroundAssets {
    private const val LIGHT_ASSET = "assets/reader_background/default_light.jpg"
    private const val DARK_ASSET = "assets/reader_background/default_dark.png"

    @Volatile private var moduleApkPath: String? = null

    fun configure(moduleApkPath: String?) {
        if (!moduleApkPath.isNullOrBlank()) {
            this.moduleApkPath = moduleApkPath
        }
    }

    fun copyTo(dark: Boolean, target: File): File {
        val apk = File(moduleApkPath ?: error("模块 APK 路径不可用"))
        require(apk.isFile) { "模块 APK 不存在: ${apk.absolutePath}" }
        val entryName = if (dark) DARK_ASSET else LIGHT_ASSET
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("模块内置背景不存在: $entryName")
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).buffered().use { input ->
                target.outputStream().buffered().use(input::copyTo)
            }
        }
        XposedBridge.log("ReaMicro LSP copied built-in reader background dark=$dark")
        return target
    }
}
