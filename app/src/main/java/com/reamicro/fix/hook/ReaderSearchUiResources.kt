package com.reamicro.fix.hook

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal object ReaderSearchUiResources {
    private val started = AtomicBoolean()
    @Volatile var cachedFonts: StructureHome130Style.Fonts? = null
        private set

    @Synchronized fun fonts(context: Context): StructureHome130Style.Fonts =
        cachedFonts ?: StructureHome130Style.fonts(context).also { cachedFonts = it }

    fun warm(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { fonts(app) }.onFailure { started.set(false) }
        }, "ReaMicroSearchUiWarmup").apply { isDaemon = true; start() }
    }
}
