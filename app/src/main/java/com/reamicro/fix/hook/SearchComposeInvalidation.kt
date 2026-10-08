package com.reamicro.fix.hook

internal fun searchPaintChangedFlags(flags: Int, marksIndex: Int): Int {
    val stateMask = 0x6 shl (marksIndex * 3)

    val changed = if (flags and stateMask == 0) flags
        else (flags and stateMask.inv()) or (0x4 shl (marksIndex * 3))
    return changed or 1
}
