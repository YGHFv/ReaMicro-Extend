package com.reamicro.fix.core

object ExternalFeatureCompatibility {
    const val SEMANTIC_MANUAL_EDIT = "manual_edit_semantic_v1"

    fun disabledReason(featureId: String, capabilities: Set<String>): String? {
        if (featureId != "manual-edit" && "manual_edit" !in capabilities) return null
        if (featureId == "manual-edit" && "manual_edit" in capabilities &&
            SEMANTIC_MANUAL_EDIT in capabilities && !HostSymbols.isIdentity) return null
        return "当前手动编辑扩展与宿主接口不兼容，请更新功能源。可继续使用关联搜索和条码关联操作。"
    }
}
