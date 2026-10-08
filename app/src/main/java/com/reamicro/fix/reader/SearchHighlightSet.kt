package com.reamicro.fix.reader

class SearchHighlightSet(drafts: List<SearchHighlightMarkDraft>, val activeId: Long) {
    data class Match(val draft: SearchHighlightMarkDraft, val active: Boolean) {
        val colorToken: String get() = if (active) "red" else "yellow"
    }

    val matches: List<Match> = drafts.sortedBy { if (it.id == activeId) 0 else 1 }
        .distinctBy { it.startCfi to it.endCfi }
        .map { Match(it, it.id == activeId) }.sortedBy { it.active }
    private val bySpine = matches.groupBy { spinePrefix(it.draft.startCfi) }

    fun forContent(location: String): List<Match> =
        spinePrefix(location)?.let { bySpine[it] }.orEmpty()

    companion object {
        fun spinePrefix(cfi: String): String? = SearchCfiAddress.spineKey(cfi)
        fun sameGeneration(expected: Any?, current: Any?): Boolean = expected != null && expected === current
    }
}
