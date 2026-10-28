package com.reamicro.fix.core

/** B4 默认导入包装器的来源槽位已由 DEX 调用和掩码核实。 */
internal object HostImportArguments {
    fun bindDefault(args: Array<Any?>, url: String, size: Long?) {
        require(args.size == 9 && args[8] is Int && url.isNotBlank())
        args[4] = url
        val validSize = size?.takeIf { it > 0 }
        if (validSize != null) args[5] = validSize
        args[8] = (args[8] as Int) and 8.inv() and
            (if (validSize != null) 16.inv() else -1)
    }
}
