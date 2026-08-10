package com.reamicro.fix.hook.settings

import android.content.Context
import com.reamicro.fix.hook.settings.*

// 从 ReaMicroSettingsHook 提升出来的成员扩展函数。
//
// 原先它们是「既是 String/Context/Any 的扩展、又是 hook 成员」的成员扩展函数，
// 这种函数只在类体内可见。把功能簇拆成同包扩展函数后调用不到，因此提升为不依赖
// hook 实例的顶层扩展函数。
internal fun String.compactOnlineSourceLine(): String =
    replace(Regex("\\s+"), " ").trim()

internal fun Any.method0(name: String): Any =
    javaClass.methods.first {
        it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
    }.invoke(this)

internal fun Any.longMethod(name: String): Long =
    method0(name) as Long
