package com.reamicro.fix.hook

internal data class ReaderSearchRequest(val generation: Long, val run: Long, val page: Long) {
    fun acceptsSearch(currentGeneration: Long, currentRun: Long): Boolean =
        currentGeneration == generation && currentRun == run

    fun accepts(currentGeneration: Long, currentRun: Long, currentPage: Long, samePanel: Boolean): Boolean =
        page != 0L && currentPage == page && acceptsSearch(currentGeneration, currentRun) && samePanel
}
