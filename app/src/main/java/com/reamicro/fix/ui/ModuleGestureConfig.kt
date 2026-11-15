package com.reamicro.fix.ui

internal enum class ModulePredictiveBackMode {
    Off, Miuix, Aosp;

    val enabled: Boolean get() = this != Off

    companion object {
        const val KEY = "predictiveBackMode"
        const val LEGACY_KEY = "predictiveBack"
        const val SWIPE_KEY = "swipeBack"

        fun read(values: Map<String, *>): ModulePredictiveBackMode =
            entries.firstOrNull { it.name == values[KEY] }
                ?: if (values[LEGACY_KEY] == true) Miuix else Off
    }
}
