package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderGuardBindings
import com.reamicro.fix.reader.ReaderTypographyPolicy
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

internal fun ReaderHook.hookReaderTypographyGuard() {
    val symbols = requireNotNull(HostReaderGuardBindings.typography) { "Reader typography contract unavailable" }
    val sizeIndex = symbols.index("textSize")
    val heightIndex = symbols.index("lineHeight")
    val handle = HookRuntime.hookMethod(symbols.constructor("config"), object : HookCallback() {
        override fun before(param: Call) {
            val size = param.args[sizeIndex] as Float
            val height = param.args[heightIndex] as Float
            val safeSize = ReaderTypographyPolicy.textSize(size)
            val safeHeight = ReaderTypographyPolicy.lineHeight(height)
            if (size.toRawBits() == safeSize.toRawBits() && height.toRawBits() == safeHeight.toRawBits()) return
            param.args[sizeIndex] = safeSize
            param.args[heightIndex] = safeHeight
            HookRuntime.logAlways("ReaMicro LSP invalid reader typography recovered size=$size->$safeSize line=$height->$safeHeight")
        }
    })
    try {
        symbols.callers().forEach(HookRuntime::deoptimize)
    } catch (error: Throwable) {
        runCatching { handle.unhook() }.onFailure(error::addSuppressed)
        throw error
    }
}
