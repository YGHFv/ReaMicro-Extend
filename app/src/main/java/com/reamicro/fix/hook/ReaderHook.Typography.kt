package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.reader.ReaderTypographyPolicy
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

internal fun ReaderHook.hookReaderTypographyGuard() {
    if (HostSymbols.profileId != "2.3.3") return
    // REA 与 MT 核对的正式版 ReaderEpubConfig；仅修复全局非法数值，不修改书内 CSS 或用户存储。
    val config = classLoader.loadClass("np8")
    val constructor = config.declaredConstructors.single {
        it.parameterTypes.map(Class<*>::getName) == listOf(
            "java.lang.String", "float", "float", "int", "int", "hh8",
            "boolean", "int", "boolean", "boolean", "boolean",
        )
    }
    XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val size = param.args[1] as Float
            val height = param.args[2] as Float
            val safeSize = ReaderTypographyPolicy.textSize(size)
            val safeHeight = ReaderTypographyPolicy.lineHeight(height)
            if (size.toRawBits() == safeSize.toRawBits() && height.toRawBits() == safeHeight.toRawBits()) return
            param.args[1] = safeSize
            param.args[2] = safeHeight
            XposedBridge.logAlways("ReaMicro LSP invalid reader typography recovered size=$size->$safeSize line=$height->$safeHeight")
        }
    })
    // 保证已编译的配置组装入口仍经过构造器参数校验。
    val caller = classLoader.loadClass("t68").getDeclaredMethod("v", Any::class.java)
    XposedBridge.deoptimize(caller)
}
