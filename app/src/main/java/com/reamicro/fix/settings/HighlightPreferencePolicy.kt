package com.reamicro.fix.settings

internal object HighlightPreferencePolicy {
    fun affectsReader(key: String?): Boolean = key == null || key == "module_enabled" ||
        key.startsWith("reader_highlight_") || key.startsWith("reader_dialogue_highlight_") || key.startsWith("font_")
}

internal class HighlightConfigCache<V : Any> {
    private var input: List<String?>? = null
    private var snapshot: V? = null

    @Synchronized
    fun get(current: List<String?>, create: () -> V): V {
        snapshot?.takeIf { input == current }?.let { return it }
        return create().also { input = current.toList(); snapshot = it }
    }

    @Synchronized
    fun clear() { input = null; snapshot = null }
}
