package com.reamicro.fix.core

import android.content.Context
import android.os.Build

/** Only the two audited releases may install host-specific bindings and hooks. */
object HostSupportedVersions {
    const val VERSION_CODE = 2330L
    private val versionNames = setOf("2.3.3", "2.3.4 beta")

    data class Check(
        val accepted: Boolean,
        val reason: String,
        val versionName: String? = null,
        val versionCode: Long? = null,
    )

    @JvmStatic
    fun accepts(packageName: String?, versionName: String?, versionCode: Long): Boolean =
        HostPackages.contains(packageName) && versionName?.trim() in versionNames && versionCode == VERSION_CODE

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
                    else "unsupported host: ${info.packageName} $name ($code); expected 2.3.3 or 2.3.4 beta (2330)",
                    name,
                    code,
                )
            }
        }
    } catch (error: Exception) {
        Check(false, "host APK package metadata failed: ${error.javaClass.simpleName}")
    }
}
