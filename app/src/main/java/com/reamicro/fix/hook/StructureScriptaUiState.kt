package com.reamicro.fix.hook

import kotlin.math.roundToInt

internal data class StructureScriptaUiState(
    val loading: Boolean,
    val saving: Boolean,
    val searching: Boolean,
    val dismissing: Boolean,
    val modalOpen: Boolean,
    val modified: Boolean,
    val hasSnapshot: Boolean,
    val undoAvailable: Boolean,
    val redoAvailable: Boolean,
) {
    val readOnly get() = saving || searching || dismissing || modalOpen
    val interactive get() = !loading && !readOnly && hasSnapshot
    val canSave get() = interactive && hasSnapshot && modified
    val canUndo get() = interactive && undoAvailable
    val canRedo get() = interactive && redoAvailable
    val canSearch get() = interactive && hasSnapshot
}

internal object StructureScriptaLayout {
    const val compactEditorHeight = 48f
    data class SearchHeights(val min: Int, val max: Int)

    fun searchHeights(bodyHeight: Int, footerHeight: Int, density: Float, fontScale: Float): SearchHeights {
        val available = (bodyHeight - footerHeight).coerceAtLeast(0)
        val layout = calculate(bodyHeight / density, fontScale, true)
        val maximum = if (layout.compactSearch) {
            (available - (compactEditorHeight * density).roundToInt()).coerceAtLeast(0)
        } else minOf(available, (layout.searchMaxHeight * density).roundToInt())
        return SearchHeights(if (layout.compactSearch) maximum else 0, maximum)
    }

    fun showSourceHeader(searchVisible: Boolean, editorHeight: Int, density: Float): Boolean =
        !searchVisible || editorHeight > (compactEditorHeight * density).roundToInt()

    data class Result(val compactSearch: Boolean, val searchMaxHeight: Float)

    fun calculate(bodyHeightDp: Float, fontScale: Float, searchVisible: Boolean): Result {
        val height = bodyHeightDp.coerceAtLeast(0f)
        val scale = fontScale.coerceAtLeast(1f)
        val compact = searchVisible && height < 360f * scale

        val maximum = if (compact) height else minOf(height * .48f, 300f * scale)
        return Result(compact, maximum.coerceIn(0f, height))
    }
}
