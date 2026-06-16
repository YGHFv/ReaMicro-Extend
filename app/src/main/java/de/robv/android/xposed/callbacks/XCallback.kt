package de.robv.android.xposed.callbacks

open class XCallback {
    companion object {
        const val PRIORITY_HIGHEST = 10000
        const val PRIORITY_DEFAULT = 50
        const val PRIORITY_LOWEST = -10000
    }
}
