package com.reamicro.fix.hook

/** Only the editor-owned group changes; retries and delayed native results preserve all other groups. */
internal object ManualAssociationPreview {
    const val GROUP_PREFIX = "手动编辑-"

    fun <T> merge(groups: List<T>, preview: T?, publisher: (T) -> String): List<T> {
        val native = groups.filterNot { publisher(it).startsWith(GROUP_PREFIX) }
        return if (preview == null) native else listOf(preview) + native
    }
}
