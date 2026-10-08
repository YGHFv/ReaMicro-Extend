package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.reamicro.fix.R
import com.reamicro.fix.reader.HighlightImageLimits
import com.reamicro.fix.xposed.XposedBridge
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

internal object ReaderHighlightImageAssets {
    private const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
    private const val RAINBOW_GLASS_ASSET = "reader_highlight/rainbow_glass.png"
    private val loggedFailures = LinkedHashSet<String>()
    @Volatile private var moduleApkPath: String? = null

    fun configure(moduleApkPath: String?) {
        if (!moduleApkPath.isNullOrBlank()) {
            this.moduleApkPath = moduleApkPath
        }
    }

    fun decodeBitmap(path: String, context: Context?, logPrefix: String): Bitmap? {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return null
        val assetName = normalizedPath.removePrefix("asset://").takeIf { it != normalizedPath }
        return if (assetName != null) {
            decodeAssetBitmap(assetName, context, logPrefix)
        } else {
            decodeFileBitmap(normalizedPath, logPrefix)
        }
    }

    private fun decodeAssetBitmap(assetName: String, context: Context?, logPrefix: String): Bitmap? {
        decodeAssetBitmapFromModuleApk(assetName, logPrefix)?.let { return it }
        val moduleContext = moduleContext(context)
        if (moduleContext == null) {
            logFailure("asset-context|$assetName", "$logPrefix highlight image context unavailable: asset://$assetName")
            return null
        }
        val assetBitmap = runCatching {
            decodeCheckedStream { moduleContext.assets.open(assetName) }
        }.onFailure {
            logFailure("asset-open|$assetName|${it.javaClass.name}|${it.message}", "$logPrefix highlight asset open failed: asset://$assetName ${it.message}")
        }.getOrNull()
        if (assetBitmap != null) return assetBitmap
        if (assetName != RAINBOW_GLASS_ASSET) return null
        val fallbackBitmap = runCatching {
            BitmapFactory.decodeResource(moduleContext.resources, R.drawable.reader_highlight_rainbow_glass)
        }.onFailure {
            logFailure("asset-res|$assetName|${it.javaClass.name}|${it.message}", "$logPrefix highlight drawable fallback failed: $assetName ${it.message}")
        }.getOrNull()
        if (fallbackBitmap == null) {
            logFailure("asset-res-null|$assetName", "$logPrefix highlight drawable fallback decoded null: $assetName")
        }
        return fallbackBitmap
    }

    private fun decodeAssetBitmapFromModuleApk(assetName: String, logPrefix: String): Bitmap? {
        val apkFile = File(moduleApkPath ?: return null)
        if (!apkFile.isFile) {
            logFailure("asset-apk-missing|${apkFile.absolutePath}", "$logPrefix highlight module APK unavailable: ${apkFile.absolutePath}")
            return null
        }
        val bitmap = runCatching {
            ZipFile(apkFile).use { zip ->
                val entryName = "assets/$assetName"
                val entry = zip.getEntry(entryName) ?: return@use null.also {
                    logFailure("asset-apk-entry-missing|$entryName", "$logPrefix highlight asset missing in APK: $entryName")
                }
                decodeCheckedStream { zip.getInputStream(entry) }
            }
        }.onFailure {
            logFailure("asset-apk-open|$assetName|${it.javaClass.name}|${it.message}", "$logPrefix highlight asset APK open failed: asset://$assetName ${it.message}")
        }.getOrNull()
        if (bitmap == null) {
            logFailure("asset-apk-null|$assetName|${apkFile.length()}|${apkFile.lastModified()}", "$logPrefix highlight asset APK decode returned null: asset://$assetName")
        }
        return bitmap
    }

    private fun decodeFileBitmap(path: String, logPrefix: String): Bitmap? {
        val file = File(path)
        if (!file.isFile) {
            logFailure("file-missing|$path", "$logPrefix highlight image file missing: $path")
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (!HighlightImageLimits.accepts(options.outWidth, options.outHeight)) {
            logFailure("file-size|$path|${file.lastModified()}", "$logPrefix highlight image rejected: ${options.outWidth}x${options.outHeight} $path")
            return null
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions()).also {
            if (it == null) {
                logFailure("file-decode-null|${file.absolutePath}|${file.lastModified()}", "$logPrefix highlight image decode returned null: ${file.absolutePath}")
            }
        }
    }

    fun validateImageBytes(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        HighlightImageLimits.requireValid(options.outWidth, options.outHeight)
    }

    fun validateImageStream(input: InputStream) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        HighlightImageLimits.requireValid(options.outWidth, options.outHeight)
    }

    private fun decodeCheckedStream(open: () -> InputStream): Bitmap? {
        open().use(::validateImageStream)
        return open().use { BitmapFactory.decodeStream(it, null, decodeOptions()) }
    }

    private fun decodeOptions() = BitmapFactory.Options().apply {
        // 不缩放图片，保留九切片坐标和 NinePatch 元数据；按 ARGB_8888 估算像素上限。
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }

    private fun moduleContext(context: Context?): Context? {
        val base = context?.applicationContext ?: currentApplicationContext() ?: return null
        if (base.packageName == MODULE_PACKAGE_NAME) return base
        return runCatching {
            base.createPackageContext(MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()?.applicationContext
    }

    private fun currentApplicationContext(): Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.applicationContext

    private fun logFailure(key: String, message: String) {
        synchronized(loggedFailures) {
            if (!loggedFailures.add(key)) return
            while (loggedFailures.size > 128) loggedFailures.iterator().run { next(); remove() }
        }
        XposedBridge.log(message)
    }
}
