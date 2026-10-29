package com.reamicro.fix.core

object ExternalFeatureCompatibility {
    fun disabledReason(
        featureId: String,
        capabilities: Set<String>,
        profileId: String = HostSymbols.profileId,
    ): String? =
        if (featureId == "manual-edit" || "manual_edit" in capabilities) {
            manualEditDisabledReason(profileId)
        } else null

    fun manualEditDisabledReason(profileId: String = HostSymbols.profileId): String? =
        if (profileId in setOf("2.3.3-b4", "2.3.3-rc3", "2.3.3")) {
            "\u5f53\u524d\u9605\u5fae\u7248\u672c\u4e0d\u652f\u6301\u65e7\u7248\u624b\u52a8\u7f16\u8f91\u6269\u5c55\u3002" +
                "\u53ef\u7ee7\u7eed\u4f7f\u7528\u5173\u8054\u641c\u7d22\u548c\u6761\u7801\u5173\u8054\u64cd\u4f5c\u3002"
        } else null
}
