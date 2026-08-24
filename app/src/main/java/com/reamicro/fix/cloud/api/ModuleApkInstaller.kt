package com.reamicro.fix.cloud.api

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.File

object ModuleApkInstaller {
    fun install(activity: Activity, apk: File) {
        require(apk.isFile) { "模块 APK 不存在" }
        val uri = Uri.parse("content://${ModuleApkContentProvider.AUTHORITY}/${apk.name}")
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
