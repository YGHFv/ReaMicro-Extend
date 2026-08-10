package com.reamicro.fix.hook.reader

import android.content.Context
import com.reamicro.fix.hook.reader.*

// 原先是 ReaderHook.kt 里的文件级 private 顶层工具函数。
//
// 功能簇拆成同包扩展函数后这些工具不可见；改成同包 internal 又会与其它 hook 文件里
// 各自的同名文件私有实现冲突，因此挪到子包由包级 star import 引用。
internal fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
