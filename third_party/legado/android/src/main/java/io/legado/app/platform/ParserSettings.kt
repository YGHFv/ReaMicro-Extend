package io.legado.app.platform

/**
 * The parser-relevant subset of Legado settings. Defaults match the upstream
 * settings; the application may replace the complete snapshot on preference changes.
 */
object ParserSettings {
    data class Snapshot(
        val adaptSpecialStyle: Boolean = true,
        val replaceEnableDefault: Boolean = true,
        val pageAnim: Int = 0,
    )

    @Volatile var current = Snapshot()
    val threadCount: Int get() = LegadoPlatform.threadCount
    val adaptSpecialStyle: Boolean get() = current.adaptSpecialStyle
    val replaceEnableDefault: Boolean get() = current.replaceEnableDefault
    val pageAnim: Int get() = current.pageAnim
}
