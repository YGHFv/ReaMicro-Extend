package com.reamicro.fix.online

import android.content.Context
import com.reamicro.fix.reload.ModuleResources

/** Startup only retains the application context; the enhanced engine starts on first use. */
internal object OnlineParserContext {
    @Volatile private var app: Context? = null

    init {
        ModuleResources.cleanup { app = null }
    }

    fun attach(context: Context) {
        app = context.applicationContext ?: context
    }

    fun require(): Context = checkNotNull(app) { "增强解析尚未取得宿主 Context" }
}
