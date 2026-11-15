package com.reamicro.fix.hook

/** Track the host mode row without depending on the optional corner-preview branch. */
internal open class ReaderSettingsInsertion {
    var mode: Int = -1
        private set
    var rendering: Boolean = false
    private var inModes = false
    private var buttonDepth = 0

    fun beginModes() { if (!rendering) inModes = true }
    fun enterButton(candidate: Int, selected: Boolean) {
        buttonDepth++
        if (inModes && !rendering && candidate >= 0 && selected) mode = candidate
    }
    fun exitButton() { buttonDepth = (buttonDepth - 1).coerceAtLeast(0) }
    fun endGroup(isNode: Boolean): Boolean {
        if (!isNode || !inModes || rendering || buttonDepth != 0 || mode < 0) return false
        inModes = false
        return true
    }
}
