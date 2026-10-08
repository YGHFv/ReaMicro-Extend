package top.yukonga.scripta.editor.input

import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.scripta.editor.EditorEngine

private const val EXTRACT_WINDOW = 2048

internal class EditorInputConnection(
    view: View,
    private val engine: EditorEngine,
) : BaseInputConnection(view,                    false) {

    override fun beginBatchEdit(): Boolean {
        engine.beginBatch(); return true
    }

    override fun endBatchEdit(): Boolean = engine.endBatch()

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        engine.setComposingText(text.toString(), newCursorPosition); return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        engine.setComposingRegion(start, end); return true
    }

    override fun finishComposingText(): Boolean {
        engine.finishComposing(); return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {

        if (engine.composing == null && text.length == 1 && text[0] == '\n') {
            engine.insertNewlineAutoIndent(); return true
        }

        if (engine.composing == null && text.length == 1 && newCursorPosition == 1) {
            engine.typeCharacter(text.toString()); return true
        }
        engine.commitText(text.toString(), newCursorPosition); return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        engine.deleteSurroundingText(beforeLength, afterLength); return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        engine.deleteSurroundingTextInCodePoints(beforeLength, afterLength); return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = engine.textBeforeCursor(n)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = engine.textAfterCursor(n)
    override fun getSelectedText(flags: Int): CharSequence? = engine.selectedText()

    override fun getCursorCapsMode(reqModes: Int): Int {

        val before = engine.textBeforeCursor(256).toString()
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {

        val before = engine.textBeforeCursor(EXTRACT_WINDOW).toString()
        val selected = engine.selectedText() ?: ""
        val after = engine.textAfterCursor(EXTRACT_WINDOW).toString()
        val (s, _) = engine.selectionOffsets()
        return ExtractedText().apply {
            text = before + selected + after
            startOffset = s - before.length
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = before.length
            selectionEnd = before.length + selected.length
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    engine.backspace(); return true
                }

                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    engine.deleteForward(); return true
                }

                KeyEvent.KEYCODE_ENTER -> {
                    engine.insertNewlineAutoIndent(); return true
                }

                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0) {
                        engine.typeCharacter(String(Character.toChars(ch))); return true
                    }
                }
            }
        }
        return super.sendKeyEvent(event)
    }
}

actual fun Modifier.editorTextInput(engine: EditorEngine, enabled: Boolean, caretRectInEditor: () -> Rect?): Modifier =
    if (enabled) this then EditorTextInputElement(engine) else this

private data class EditorTextInputElement(val engine: EditorEngine) :
    ModifierNodeElement<EditorTextInputNode>() {
    override fun create(): EditorTextInputNode = EditorTextInputNode(engine)
    override fun update(node: EditorTextInputNode) {
        node.engine = engine
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "editorTextInput"
    }
}

private class EditorTextInputNode(var engine: EditorEngine) :
    Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode {

    private var focusedJob: Job? = null
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        focusedJob?.cancel()
        focusedJob = if (focused) coroutineScope.launch { runSession() } else null
    }

    private suspend fun runSession() {
        establishTextInputSession {
            val v = view
            val imm = v.context.getSystemService(InputMethodManager::class.java)
            engine.imeListener = object : EditorEngine.ImeListener {
                override fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
                    imm?.updateSelection(v, selStart, selEnd, composingStart, composingEnd)
                }
            }
            engine.requestShowKeyboard = { imm?.showSoftInput(v, 0) }

            engine.onDocumentReplaced = { imm?.restartInput(v) }
            val request = PlatformTextInputMethodRequest { outAttrs ->

                outAttrs.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

                outAttrs.imeOptions =
                    EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                val (s, e) = engine.selectionOffsets()
                outAttrs.initialSelStart = s
                outAttrs.initialSelEnd = e
                EditorInputConnection(v, engine)
            }
            try {
                startInputMethod(request)
            } finally {
                engine.imeListener = null
                engine.requestShowKeyboard = null
                engine.onDocumentReplaced = null
            }
        }
    }

    override fun onDetach() {
        focusedJob?.cancel()
        focusedJob = null
        focused = false
        engine.imeListener = null
        engine.requestShowKeyboard = null
        engine.onDocumentReplaced = null
    }
}
