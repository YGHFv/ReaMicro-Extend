package top.yukonga.scripta.editor.menu

import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

enum class EditorContextAction { Undo, Redo, Cut, Copy, Paste, SelectAll }

val EditorContextAction.zhLabel: String
    get() = when (this) {
        EditorContextAction.Undo -> "撤销"
        EditorContextAction.Redo -> "重做"
        EditorContextAction.Cut -> "剪切"
        EditorContextAction.Copy -> "复制"
        EditorContextAction.Paste -> "粘贴"
        EditorContextAction.SelectAll -> "全选"
    }

data class ContextActionAvailability(
    val cut: Boolean,
    val copy: Boolean,
    val paste: Boolean,
    val selectAll: Boolean,
    val undo: Boolean = false,
    val redo: Boolean = false,
) {
    fun isAvailable(action: EditorContextAction): Boolean = when (action) {
        EditorContextAction.Undo -> undo
        EditorContextAction.Redo -> redo
        EditorContextAction.Cut -> cut
        EditorContextAction.Copy -> copy
        EditorContextAction.Paste -> paste
        EditorContextAction.SelectAll -> selectAll
    }
}

fun contextActionAvailability(
    readOnly: Boolean,
    hasSelection: Boolean,
    allSelected: Boolean,
    docNonEmpty: Boolean,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
): ContextActionAvailability = ContextActionAvailability(
    cut = !readOnly && hasSelection,
    copy = hasSelection,
    paste = !readOnly,
    selectAll = docNonEmpty && !allSelected,
    undo = !readOnly && canUndo,
    redo = !readOnly && canRedo,
)

fun visibleTouchActions(
    availability: ContextActionAvailability,
    hasSelection: Boolean,
): List<EditorContextAction> {
    val order = if (hasSelection) {
        listOf(
            EditorContextAction.Cut, EditorContextAction.Copy,
            EditorContextAction.Paste, EditorContextAction.SelectAll,
        )
    } else {
        listOf(EditorContextAction.Paste, EditorContextAction.SelectAll)
    }
    return order.filter { availability.isAvailable(it) }
}

fun toolbarTopLeft(
    anchorCenterX: Float,
    anchorTopY: Float,
    anchorBottomY: Float,
    contentW: Int,
    contentH: Int,
    windowW: Int,
    windowH: Int,
    gap: Int,
    margin: Int,
): IntOffset {
    val x = (anchorCenterX - contentW / 2f).roundToInt()
        .coerceIn(margin, (windowW - contentW - margin).coerceAtLeast(margin))
    var y = (anchorTopY - gap - contentH).roundToInt()
    if (y < margin) y = (anchorBottomY + gap).roundToInt()
    y = y.coerceIn(margin, (windowH - contentH - margin).coerceAtLeast(margin))
    return IntOffset(x, y)
}
