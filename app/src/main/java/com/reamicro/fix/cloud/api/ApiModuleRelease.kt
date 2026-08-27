package com.reamicro.fix.cloud.api

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

fun checkModuleUpdate(client: ApiServerClient): ApiModuleUpdateCheck = runCatching {
    // 必须读模块自己的 BuildConfig：本函数运行在阅微宿主进程内，
    // 用 context.packageManager.getPackageInfo(context.packageName) 拿到的是阅微的版本号，
    // 会把宿主版本当成模块版本来比较。
    val currentVersionName = BuildConfig.VERSION_NAME
    val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
    val currentBuildTime = BuildConfig.BUILD_TIME
    val release = client.latestModuleRelease() ?: return ApiModuleUpdateCheck(false, null, "服务器没有可用版本")
    val available = when {
        release.versionCode > 0 && release.versionCode > currentVersionCode -> true
        release.versionCode > 0 && release.versionCode < currentVersionCode -> false
        // 服务器版本号不是语义版本号（例如 CI 的 ci-123-1）时只能比构建时间：
        // 这类字符串会被解析成 0.0.0，直接比较会永远判定"已是最新版本"。
        !isSemanticVersion(release.versionName) -> release.buildTime > currentBuildTime
        comparePackageVersions(release.versionName, currentVersionName) > 0 -> true
        comparePackageVersions(release.versionName, currentVersionName) < 0 -> false
        else -> release.buildTime > currentBuildTime
    }
    ApiModuleUpdateCheck(available, release, if (available) "发现新版本 ${release.versionName}" else "已是最新版本")
}.getOrElse { ApiModuleUpdateCheck(false, null, it.message ?: "检查更新失败") }
