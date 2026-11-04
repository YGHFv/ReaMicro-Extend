package com.reamicro.fix.core

/** 手动编辑只开放逐版核验的顶栏分支和关联入口，不能仅靠相同方法签名猜选。 */
object HostManualEditAbi {
    data class Layout(
        val capability: String,
        val publishScreen: String,
        val screenMethod: String,
        val topBarContent: String,
        val topBarBranch: Byte,
        val relateMethod: String,
    )

    fun forProfile(profileId: String): Layout? = when (profileId) {
        "2.3.3" -> Layout("manual_edit_host_233", "utd", "a", "vo1", 14, "k")
        "2.3.4-b1" -> Layout("manual_edit_host_234_b1", "jvd", "b", "mr1", 11, "o0")
        else -> null
    }
}
