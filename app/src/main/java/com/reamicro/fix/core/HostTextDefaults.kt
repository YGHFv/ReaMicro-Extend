package com.reamicro.fix.core

/** R8 删除形参后保留源码默认位；默认掩码不能按反射下标重新编号。 */
object HostTextDefaults {
    fun fontFamilyBit(profileId: String, reflectionIndex: Int): Int {
        require(reflectionIndex in 0..30)
        if (profileId == "2.3.3-b2" || profileId in setOf("2.3.3-b4", "2.3.3-rc3")) {
            require(reflectionIndex == 5) { "Unverified native Text font-family slot: $reflectionIndex" }
            return 0x80
        }
        return 1 shl reflectionIndex
    }
}
