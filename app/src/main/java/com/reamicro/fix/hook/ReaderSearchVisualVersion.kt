package com.reamicro.fix.hook

internal class ReaderSearchVisualVersion {
    private var nativeVersion: Int? = null
    private var paintVersion: Long? = null
    var version: Int = 0
        private set

    fun update(native: Int, paint: Long): Int {
        if (nativeVersion != native || paintVersion != paint) {
            nativeVersion = native
            paintVersion = paint
            version++
        }
        return version
    }
}
