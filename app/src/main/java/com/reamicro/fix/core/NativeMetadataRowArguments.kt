package com.reamicro.fix.core

internal object NativeMetadataRowArguments {
    fun text(
        value: String,
        modifier: Any,
        color: Long,
        endAlign: Any,
        style: Any,
        composer: Any,
    ): Array<Any?> {
        // 保留 weight 与显式对齐、单行省略；默认位不能覆盖这些实参。
        val defaults = 131066 and (2 or 1024 or 4096 or 16384).inv()
        return arrayOf(
            value, modifier, color, null, 0L, null, null, null, 0L, null,
            endAlign, 0L, 2, false, 1, 0, null, style, composer, 0, 0, defaults,
        )
    }
}
