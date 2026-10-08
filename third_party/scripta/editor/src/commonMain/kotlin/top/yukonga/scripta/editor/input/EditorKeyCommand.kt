package top.yukonga.scripta.editor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

enum class EditorKeyCommand {
    SelectAll, Copy, Cut, Paste, Undo, Redo,
    Find, Replace, FindNext, FindPrev, GotoLine, ToggleComment,
    WordLeft, WordRight, LineStart, LineEnd, DocStart, DocEnd, PageUp, PageDown,
}

internal fun resolveCtrlBased(e: KeyEvent): EditorKeyCommand? {
    if (e.isAltPressed) return null
    val ctrl = e.isCtrlPressed
    return when (e.key) {
        Key.A -> if (ctrl) EditorKeyCommand.SelectAll else null
        Key.C -> if (ctrl) EditorKeyCommand.Copy else null
        Key.X -> if (ctrl) EditorKeyCommand.Cut else null
        Key.V -> if (ctrl) EditorKeyCommand.Paste else null
        Key.Z -> if (ctrl) (if (e.isShiftPressed) EditorKeyCommand.Redo else EditorKeyCommand.Undo) else null
        Key.Y -> if (ctrl) EditorKeyCommand.Redo else null
        Key.F -> if (ctrl) EditorKeyCommand.Find else null
        Key.H -> if (ctrl) EditorKeyCommand.Replace else null
        Key.G -> if (ctrl) EditorKeyCommand.GotoLine else null
        Key.Slash -> if (ctrl) EditorKeyCommand.ToggleComment else null
        Key.F3 -> if (e.isShiftPressed) EditorKeyCommand.FindPrev else EditorKeyCommand.FindNext
        Key.DirectionLeft -> if (ctrl) EditorKeyCommand.WordLeft else null
        Key.DirectionRight -> if (ctrl) EditorKeyCommand.WordRight else null
        Key.MoveHome -> if (ctrl) EditorKeyCommand.DocStart else EditorKeyCommand.LineStart
        Key.MoveEnd -> if (ctrl) EditorKeyCommand.DocEnd else EditorKeyCommand.LineEnd
        Key.PageUp -> EditorKeyCommand.PageUp
        Key.PageDown -> EditorKeyCommand.PageDown
        else -> null
    }
}

internal fun resolveMacBased(e: KeyEvent): EditorKeyCommand? {
    val cmd = e.isMetaPressed
    val opt = e.isAltPressed
    return when (e.key) {
        Key.A -> if (cmd) EditorKeyCommand.SelectAll else null
        Key.C -> if (cmd) EditorKeyCommand.Copy else null
        Key.X -> if (cmd) EditorKeyCommand.Cut else null
        Key.V -> if (cmd) EditorKeyCommand.Paste else null
        Key.Z -> if (cmd) (if (e.isShiftPressed) EditorKeyCommand.Redo else EditorKeyCommand.Undo) else null
        Key.F -> when {
            cmd && opt -> EditorKeyCommand.Replace
            cmd -> EditorKeyCommand.Find
            else -> null
        }
        Key.G -> if (cmd) (if (e.isShiftPressed) EditorKeyCommand.FindPrev else EditorKeyCommand.FindNext) else null
        Key.L -> if (cmd) EditorKeyCommand.GotoLine else null
        Key.Slash -> if (cmd) EditorKeyCommand.ToggleComment else null
        Key.DirectionLeft -> when {
            opt -> EditorKeyCommand.WordLeft
            cmd -> EditorKeyCommand.LineStart
            else -> null
        }
        Key.DirectionRight -> when {
            opt -> EditorKeyCommand.WordRight
            cmd -> EditorKeyCommand.LineEnd
            else -> null
        }
        Key.DirectionUp -> if (cmd) EditorKeyCommand.DocStart else null
        Key.DirectionDown -> if (cmd) EditorKeyCommand.DocEnd else null
        Key.MoveHome -> EditorKeyCommand.DocStart
        Key.MoveEnd -> EditorKeyCommand.DocEnd
        Key.PageUp -> EditorKeyCommand.PageUp
        Key.PageDown -> EditorKeyCommand.PageDown
        else -> null
    }
}

expect fun resolveEditorKeyCommand(event: KeyEvent): EditorKeyCommand?
