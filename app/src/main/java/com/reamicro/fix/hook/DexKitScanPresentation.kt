package com.reamicro.fix.hook

internal data class DexKitScanNotice(val message: String, val restartRequired: Boolean = false)

internal fun dexKitScanNotice(
    scanning: Boolean,
    error: String?,
    profileId: String,
    hasReport: Boolean,
    hasProfile: Boolean,
    failures: Boolean,
    unchanged: Boolean,
    cached: Boolean = false,
): DexKitScanNotice = when {
    scanning -> DexKitScanNotice("本次不使用扫描缓存；完成后自动显示结果。")
    error != null -> DexKitScanNotice("扫描未完成：${error.lineSequence().first().take(240)}。详细原因见日志。")
    !hasReport || !hasProfile -> DexKitScanNotice("扫描未完成，请重新扫描。")
    !unchanged -> DexKitScanNotice(
        (if (failures) "仍有定位失败项，具体原因见日志。新结果需重启阅微后采用。"
        else "新扫描结果需重启阅微后采用。") +
            if (profileId in setOf("identity", "automatic")) "扫描成功不代表全部功能可用。" else "",
        restartRequired = true,
    )
    profileId in setOf("identity", "automatic") ->
        DexKitScanNotice("当前宿主尚无已验证的完整功能适配；扫描成功不代表全部功能可用。")
    cached -> DexKitScanNotice("已读取与当前宿主及扫描规则匹配的记录。扫描缓存不等于功能验收结果。")
    failures -> DexKitScanNotice("仍有定位失败项，具体原因见日志；不能据此判定功能正常。")
    else -> DexKitScanNotice("结果与当前加载内容一致，无需重启阅微。")
}
