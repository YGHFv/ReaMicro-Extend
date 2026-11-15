package com.reamicro.fix.core

object HostUiArguments {
    /** restart 位会让 startDefaults 跳过初始化，不能用它强刷带默认槽的 top bar。 */
    fun topBarActionsFlags(flags: Int): Int = (flags and 1.inv()) or 0x800000
}
