package top.yukonga.scripta.editor.text

data class TextEdit(val offset: Int, val removed: String, val inserted: String)

data class SelectionState(val anchor: Int, val head: Int)

enum class EditKind { Typing, DeleteBackward, DeleteForward, Composing, Other }

class UndoStep(val edits: List<TextEdit>, val selection: SelectionState)

class UndoHistory(private val maxUnits: Int = 1000) {

    private class HistoryUnit(
        val edits: MutableList<TextEdit>,
        val selBefore: SelectionState,
        var selAfter: SelectionState,
        val kind: EditKind,
        var sealed: Boolean = false,
    )

    private val undoStack = ArrayDeque<HistoryUnit>()
    private val redoStack = ArrayDeque<HistoryUnit>()
    private var grouping = false
    private var groupUnit: HistoryUnit? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(edit: TextEdit, selBefore: SelectionState, selAfter: SelectionState, kind: EditKind) {
        if (edit.removed.isEmpty() && edit.inserted.isEmpty()) return
        redoStack.clear()

        if (grouping) {
            val g = groupUnit
            if (g == null) {
                val unit = HistoryUnit(mutableListOf(edit), selBefore, selAfter, EditKind.Other)
                undoStack.addLast(unit)
                groupUnit = unit
                trim()
            } else {
                g.edits.add(edit)
                g.selAfter = selAfter
            }
            return
        }

        val last = undoStack.lastOrNull()
        if (last != null && !last.sealed && last.kind == kind && last.edits.size == 1) {
            val prev = last.edits[0]
            when (kind) {
                EditKind.Typing -> if (
                    edit.removed.isEmpty() && prev.removed.isEmpty() &&
                    !edit.inserted.contains('\n') && !prev.inserted.contains('\n') &&
                    edit.offset == prev.offset + prev.inserted.length
                ) {
                    last.edits[0] = prev.copy(inserted = prev.inserted + edit.inserted)
                    last.selAfter = selAfter
                    return
                }

                EditKind.DeleteBackward -> if (
                    edit.inserted.isEmpty() && prev.inserted.isEmpty() &&
                    edit.offset + edit.removed.length == prev.offset
                ) {
                    last.edits[0] = TextEdit(edit.offset, edit.removed + prev.removed, "")
                    last.selAfter = selAfter
                    return
                }

                EditKind.DeleteForward -> if (
                    edit.inserted.isEmpty() && prev.inserted.isEmpty() &&
                    edit.offset == prev.offset
                ) {
                    last.edits[0] = prev.copy(removed = prev.removed + edit.removed)
                    last.selAfter = selAfter
                    return
                }

                EditKind.Composing -> if (edit.offset == prev.offset && edit.removed == prev.inserted) {
                    val net = TextEdit(prev.offset, prev.removed, edit.inserted)
                    if (net.removed.isEmpty() && net.inserted.isEmpty()) {
                        undoStack.removeLast()
                    } else {
                        last.edits[0] = net
                        last.selAfter = selAfter
                    }
                    return
                }

                EditKind.Other -> Unit
            }
        }

        val unit = HistoryUnit(mutableListOf(edit), selBefore, selAfter, kind)

        if (kind == EditKind.Typing && edit.inserted.contains('\n')) unit.sealed = true
        undoStack.addLast(unit)
        trim()
    }

    fun breakMerge() {
        if (!grouping) undoStack.lastOrNull()?.sealed = true
    }

    fun beginGroup() {
        grouping = true
        groupUnit = null
    }

    fun endGroup() {
        grouping = false
        groupUnit?.sealed = true
        groupUnit = null
    }

    fun undo(): UndoStep? {
        val unit = undoStack.removeLastOrNull() ?: return null
        unit.sealed = true
        redoStack.addLast(unit)
        val inverse = unit.edits.asReversed().map { TextEdit(it.offset, it.inserted, it.removed) }
        return UndoStep(inverse, unit.selBefore)
    }

    fun redo(): UndoStep? {
        val unit = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(unit)
        return UndoStep(unit.edits.toList(), unit.selAfter)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        groupUnit = null
        grouping = false
    }

    private fun trim() {
        while (undoStack.size > maxUnits) undoStack.removeFirst()
    }
}
