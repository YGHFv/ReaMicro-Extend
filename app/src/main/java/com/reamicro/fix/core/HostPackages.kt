package com.reamicro.fix.core

import android.content.Context
import android.os.Build

internal object HostPackages {
    val all: Set<String> = setOf(
        "app.zhendong.reamicro",
        "app.zhendong.reamicro.fix",
    )

    fun contains(packageName: String?): Boolean = packageName in all

    data class Check(val accepted: Boolean, val reason: String)

    fun matchesArchive(packageName: String?, archivePackageName: String?): Boolean =
        contains(packageName) && packageName == archivePackageName

    fun inspect(context: Context, sourceDir: String): Check = try {
        if (!contains(context.packageName)) {
            Check(false, "unexpected host package: ${context.packageName}")
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
                val metadata = "host APK: ${info.packageName} ${info.versionName?.trim().orEmpty()} ($code)"
                val accepted = matchesArchive(context.packageName, info.packageName)
                Check(accepted, if (accepted) metadata else "$metadata; expected ${context.packageName}")
            }
        }
    } catch (error: Exception) {
        Check(false, "host APK package metadata failed: ${error.javaClass.simpleName}")
    }
}
