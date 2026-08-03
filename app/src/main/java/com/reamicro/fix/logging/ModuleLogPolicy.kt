package com.reamicro.fix.logging

import android.content.Intent
import android.util.Log

internal enum class ModuleLogLevel {
    INFO,
    WARN,
    ERROR,
}

internal fun shouldEmitModuleLog(
    conciseLogEnabled: Boolean,
    level: ModuleLogLevel,
): Boolean = !conciseLogEnabled || level == ModuleLogLevel.ERROR

internal fun legacyModuleLogLevel(message: String): ModuleLogLevel {
    val normalized = message.lowercase()
    if (
        normalized.contains("failed chapters count=0") ||
        normalized.contains("failed=0") ||
        normalized.contains("failure=0") ||
        normalized.contains("failures=0") ||
        (normalized.contains("failed") && normalized.contains("fallback to")) ||
        (
            normalized.contains("crash") &&
                listOf("installed", "switched", "cleared").any(normalized::contains)
        )
    ) {
        return ModuleLogLevel.INFO
    }
    return if (
        normalized.contains(" failed") ||
        normalized.startsWith("failed") ||
        normalized.contains(" failure") ||
        normalized.contains(" exception") ||
        normalized.contains(" crash") ||
        normalized.contains("onerror") ||
        normalized.contains("stacktrace")
    ) {
        ModuleLogLevel.ERROR
    } else {
        ModuleLogLevel.INFO
    }
}

internal object ModuleLogState {
    @Volatile
    var conciseLogEnabled: Boolean = true

    fun applyFromIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_CONCISE_LOG_ENABLED) == true) {
            conciseLogEnabled = intent.getBooleanExtra(EXTRA_CONCISE_LOG_ENABLED, true)
        }
    }

    const val EXTRA_CONCISE_LOG_ENABLED = "com.reamicro.fix.extra.CONCISE_LOG_ENABLED"
}

internal object ModuleAndroidLog {
    fun info(tag: String, message: String, throwable: Throwable? = null) {
        emit(ModuleLogLevel.INFO, tag, message, throwable)
    }

    fun legacy(tag: String, message: String, throwable: Throwable? = null) {
        emit(legacyModuleLogLevel(message), tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        emit(ModuleLogLevel.ERROR, tag, message, throwable)
    }

    private fun emit(
        level: ModuleLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (!shouldEmitModuleLog(ModuleLogState.conciseLogEnabled, level)) return
        when (level) {
            ModuleLogLevel.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            ModuleLogLevel.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            ModuleLogLevel.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
        }
    }
}
