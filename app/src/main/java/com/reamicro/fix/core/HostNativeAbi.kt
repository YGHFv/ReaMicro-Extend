package com.reamicro.fix.core

/** 仅开放逐项核实的原生协议；新宿主不能因版本前缀相同而自动获得旧 ABI。 */
object HostNativeAbi {
    data class Navigation(
        val navigate: String,
        val pop: String,
        val controllerField: String,
        val controllerPop: String,
    )

    data class Http(
        val builderBranch: Byte,
        val url: String,
        val header: String,
        val method: String,
    )

    fun navigation(profileId: String): Navigation? = when (profileId) {
        "2.3.3-b2" -> Navigation("a", "b", "a", "a")
        "2.3.3-b4", "2.3.3-rc3", "2.3.3" -> Navigation("b", "a", "a", "c")
        else -> null
    }

    fun http(profileId: String): Http? = when (profileId) {
        "2.3.3-b2" -> Http(12, "z", "s", "w")
        "2.3.3-b4" -> Http(13, "A", "t", "x")
        "2.3.3-rc3", "2.3.3" -> Http(12, "x", "n", "t")
        else -> null
    }

    fun hasNativeHttp(profileId: String): Boolean = http(profileId) != null

    fun hasNativePaging(profileId: String): Boolean =
        profileId in setOf("2.3.3-b2", "2.3.3-b4", "2.3.3-rc3", "2.3.3")

    fun fileWrapperField(profileId: String): String? = when (profileId) {
        "2.3.3-b2" -> "b"
        "2.3.3-b4" -> "d"
        "2.3.3-rc3", "2.3.3" -> "b"
        else -> null
    }
}
