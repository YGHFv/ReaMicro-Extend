package com.reamicro.fix.core

import android.content.Context
import android.os.Build

/** Version names and codes come from the APK manifest. */
object HostSupportedVersions {
    private val versionCodes = mapOf("2.3.3" to 2330L, "2.3.4 beta" to 2330L, "2.3.4" to 2340L)

    data class Check(
        val accepted: Boolean,
        val reason: String,
        val versionName: String? = null,
        val versionCode: Long? = null,
    )

    @JvmStatic
    fun accepts(packageName: String?, versionName: String?, versionCode: Long): Boolean =
        HostPackages.contains(packageName) && versionCodes[versionName?.trim()] == versionCode

    /** Inspect the APK used by this ClassLoader, including after an in-place host upgrade. */
    @JvmStatic
    fun inspect(context: Context, sourceDir: String): Check = try {
        if (!HostPackages.contains(context.packageName)) {
            Check(false, "unsupported package: ${context.packageName}")
        } else if (sourceDir.isBlank()) {
            Check(false, "host APK path unavailable")
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(sourceDir, 0)
            if (info == null) {
                Check(false, "host APK package metadata unavailable")
            } else {
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val name = info.versionName?.trim()
                val accepted = accepts(info.packageName, name, code)
                Check(
                    accepted,
                    if (accepted) "supported host: $name ($code)"
                    else "unsupported host: ${info.packageName} $name ($code); expected " +
                        versionCodes.entries.joinToString { "${it.key} (${it.value})" },
                    name,
                    code,
                )
            }
        }
    } catch (error: Exception) {
        Check(false, "host APK package metadata failed: ${error.javaClass.simpleName}")
    }
}
