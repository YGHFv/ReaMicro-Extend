package com.reamicro.fix.core

object HostUiArguments {
    /** restart 位会让 startDefaults 跳过初始化，不能用它强刷带默认槽的 top bar。 */
    fun b2TopBarActionsFlags(flags: Int): Int = (flags and 1.inv()) or 0x800000

    /** B2 删除 TextAutoSize、FontStyle 两个槽位，但默认位保持源码编号。 */
    fun b2Text(args: Array<out Any?>): Array<Any?> {
        require(args.size == 22 && args[3] == null && args[5] == null) { "Unverified B2 Text call" }
        return args.filterIndexed { index, _ -> index != 3 && index != 5 }.toTypedArray()
    }
}
