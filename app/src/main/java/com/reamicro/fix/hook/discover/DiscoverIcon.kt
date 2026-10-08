package com.reamicro.fix.hook.discover

import com.reamicro.fix.xposed.XposedBridge

internal class DiscoverIcon(private val classLoader: ClassLoader) {

    @Volatile
    private var cached: Any? = null

    fun imageVectorOrNull(): Any? {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return@synchronized it }
            runCatching {
                DiscoverVectorBuilder.build(classLoader, ICON_NAME, DiscoverIconArtwork.layers, ICON_SIZE_DP, evenOddFill = true,
                    viewport = DiscoverIconArtwork.viewport)
                    ?: error("ImageVector.build returned null")
            }.onFailure {
                XposedBridge.logAlways("$LOG_PREFIX failed to build discover icon: ${it.stackTraceToString()}")
            }.getOrNull()?.also { cached = it }
        }
    }

    private companion object {
        const val LOG_PREFIX = "[ReaMicro]"
        const val ICON_NAME = "Colored.Discover.Note232"
        const val ICON_SIZE_DP = 24
    }
}
