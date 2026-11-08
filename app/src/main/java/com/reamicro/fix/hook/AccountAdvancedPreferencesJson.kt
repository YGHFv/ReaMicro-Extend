package com.reamicro.fix.hook

import org.json.JSONObject

internal fun AccountAdvancedPreferences.toJson(): JSONObject = JSONObject().apply {
    put("dynamicColor", dynamicColor)
    put("smartRecognition", smartRecognition)
    put("illustrationOptimization", illustrationOptimization)
    put("relateDownloadCover", relateDownloadCover)
    put("callingCardColor", callingCardColor)
}

internal fun accountAdvancedPreferencesFromJson(json: JSONObject?): AccountAdvancedPreferences? {
    val source = json ?: return null
    val keys = listOf("dynamicColor", "smartRecognition", "illustrationOptimization", "relateDownloadCover", "callingCardColor")
    // 无快照/仅凭据导入不等于用户主动关闭全部设置；不猜测历史会员资格。
    if (keys.any { source.opt(it) !is Boolean }) return null
    return AccountAdvancedPreferences(
        source.getBoolean("dynamicColor"), source.getBoolean("smartRecognition"),
        source.getBoolean("illustrationOptimization"), source.getBoolean("relateDownloadCover"),
        source.getBoolean("callingCardColor"),
    )
}
