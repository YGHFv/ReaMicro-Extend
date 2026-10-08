package com.reamicro.fix.hook

internal data class ReaderSearchRequest(val generation: Long, val run: Long, val page: Long) {
    fun accepts(currentGeneration: Long, currentRun: Long, currentPage: Long, samePanel: Boolean): Boolean =
        page != 0L && currentPage == page && currentGeneration == generation && currentRun == run && samePanel
}
