package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.association.network.HttpClient
import org.json.JSONObject
import com.reamicro.fix.BuildConfig

data class ApiModuleRelease(
    val versionName: String,
    val versionCode: Long,
    val buildTime: Long,
    val apkUrl: String,
    val sha256: String,
    val signature: String,
    val changelog: String,
) {
    companion object {
        fun fromJson(json: JSONObject): ApiModuleRelease? {
            val versionName = json.optString("versionName").trim().ifBlank { return null }
            return ApiModuleRelease(
                versionName = versionName,
                versionCode = json.optLong("versionCode", 0L),
                buildTime = json.optLong("buildTime", 0L),
                apkUrl = json.optString("apkUrl"),
                sha256 = json.optString("sha256").lowercase(),
                signature = json.optString("signature"),
                changelog = json.optString("changelog"),
            )
        }
    }
}

data class ApiModuleUpdateCheck(
    val available: Boolean,
    val release: ApiModuleRelease?,
    val message: String,
)

fun checkModuleUpdate(context: Context, client: ApiServerClient): ApiModuleUpdateCheck = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersionName = packageInfo.versionName.orEmpty()
    val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    val currentBuildTime = BuildConfig.BUILD_TIME
    val release = client.latestModuleRelease() ?: return ApiModuleUpdateCheck(false, null, "服务器没有可用版本")
    val available = when {
        release.versionCode > 0 && release.versionCode > currentVersionCode -> true
        release.versionCode > 0 && release.versionCode < currentVersionCode -> false
        comparePackageVersions(release.versionName, currentVersionName) > 0 -> true
        comparePackageVersions(release.versionName, currentVersionName) < 0 -> false
        else -> release.buildTime > currentBuildTime
    }
    ApiModuleUpdateCheck(available, release, if (available) "发现新版本 ${release.versionName}" else "已是最新版本")
}.getOrElse { ApiModuleUpdateCheck(false, null, it.message ?: "检查更新失败") }
