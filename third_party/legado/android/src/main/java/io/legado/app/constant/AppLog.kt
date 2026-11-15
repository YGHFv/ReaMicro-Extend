package io.legado.app.constant

import io.legado.app.platform.LegadoPlatform

/** Application log storage is owned by the host; parser errors use its adapter. */
object AppLog {
    fun put(message: String, error: Throwable? = null) {
        LegadoPlatform.log(message, error)
    }
    fun putDebug(message: String, error: Throwable? = null) = put(message, error)
}
