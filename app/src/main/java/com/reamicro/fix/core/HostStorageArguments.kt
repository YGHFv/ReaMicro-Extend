package com.reamicro.fix.core

/** B2 数据记录的构造器重排按字段语义核实，不按同类型字段猜位置。 */
object HostStorageArguments {
    fun b2BaiduAuth(args: Array<out Any?>): Array<Any?> {
        require(args.size == 8 && args[4] is Long && args[5] is Long)
        require(listOf(0, 1, 2, 3, 6, 7).all { args[it] is String })
        return arrayOf(args[4], args[5], args[0], args[1], args[2], args[3], args[6], args[7])
    }

    fun b2CloudUserInfo(args: Array<out Any?>): Array<Any?> {
        require(args.size == 8 && args[6] is Long && args[7] is Long)
        require((0..4).all { args[it] is String } && (args[5] == null || args[5] is String))
        return arrayOf(args[6], args[7], args[0], args[1], args[2], args[3], args[4], args[5])
    }
}
