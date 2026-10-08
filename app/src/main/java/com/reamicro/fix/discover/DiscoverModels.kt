package com.reamicro.fix.discover

import com.reamicro.fix.online.OnlineSourceEntry

internal data class DiscoverKind(

    val title: String,

    val url: String,

    val urls: List<String> = listOf(url),
) {

    val key: String get() = "$title|${urls.joinToString(",")}"
}

internal data class DiscoverBook(
    val name: String,
    val author: String,
    val coverUrl: String,
    val detailUrl: String,
    val intro: String,
    val kind: String = "",
    val lastChapter: String = "",
    val updateTime: String = "",
    val wordCount: String = "",

    val status: String = "",

    val chapterCount: String = "",
) {
    val key: String get() = "$name|$author|$detailUrl"
}

internal enum class DiscoverLayout {

    LIST,

    GRID,
    ;

    fun toggled(): DiscoverLayout = if (this == LIST) GRID else LIST

    companion object {

        fun fromStorage(value: String?): DiscoverLayout =
            entries.firstOrNull { it.name == value } ?: LIST
    }
}

internal sealed class DiscoverLoadState {

    object Idle : DiscoverLoadState()

    object Loading : DiscoverLoadState()

    data class Loaded(val books: List<DiscoverBook>) : DiscoverLoadState()

    data class Failed(val message: String) : DiscoverLoadState()
}

internal data class DiscoverSource(
    val source: OnlineSourceEntry,
    val kinds: List<DiscoverKind>,
    val filter: DiscoverFilter? = null,
) {
    val name: String get() = source.name
    val hasKinds: Boolean get() = kinds.isNotEmpty()
}

internal data class DiscoverFilterOption(
    val title: String,
    val params: List<Pair<String, String>>,
)

internal data class DiscoverFilterGroup(
    val name: String,
    val options: List<DiscoverFilterOption>,
)

internal data class DiscoverFilter(
    val prefix: String,
    val extras: List<String>,
    val groups: List<DiscoverFilterGroup>,
) {

    fun defaultSelection(): Map<String, String> =
        groups.associate { group -> group.name to group.options.first().title }

    fun buildUrl(selection: Map<String, String>): String {
        val keys = mutableListOf<String>()
        val values = LinkedHashMap<String, String>()
        groups.forEach { group ->
            val title = selection[group.name] ?: return@forEach
            val option = group.options.firstOrNull { it.title == title } ?: return@forEach
            option.params.forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) return@forEach
                if (key !in values) keys += key
                values[key] = value
            }
        }
        val parts = keys.map { key -> "$key=${encodeFilterValue(values.getValue(key))}" } + extras
        return prefix + parts.joinToString("&")
    }

    fun selectionTitle(selection: Map<String, String>): String {
        val picked = groups.mapNotNull { group ->
            val title = selection[group.name] ?: return@mapNotNull null
            if (title == group.options.firstOrNull()?.title) return@mapNotNull null
            title
        }
        return (if (picked.isEmpty()) FILTER_FALLBACK_TITLE else picked.joinToString("·"))
            .take(MAX_FILTER_KIND_TITLE)
    }
}

private const val FILTER_FALLBACK_TITLE = "组合筛选"

private const val MAX_FILTER_KIND_TITLE = 20

private fun encodeFilterValue(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

internal data class DiscoverSelection(
    val sourceId: String,
    val kindTitle: String,
) {
    companion object {
        val NONE = DiscoverSelection("", "")
    }
}
