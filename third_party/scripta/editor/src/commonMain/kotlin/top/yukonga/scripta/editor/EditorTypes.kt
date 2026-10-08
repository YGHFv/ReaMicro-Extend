package top.yukonga.scripta.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import top.yukonga.scripta.editor.highlight.SyntaxColors

enum class LineEnding { LF, CRLF }

enum class LineNumberMode {
    PinnedToScreen,
    PinnedToLine,
}

@Immutable
data class EditorColors(
    val background: Color,
    val foreground: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val cursor: Color,
    val selection: Color,

    val handle: Color,

    val symbolBarBackground: Color = Color(0xFF2A2A2C),
    val symbolBarForeground: Color = Color(0xFFCFCFCF),
    val symbolBarPressed: Color = Color(0x33FFFFFF),

    val findMatch: Color = Color(0x4DFFC107),
    val findMatchActive: Color = Color(0x80FF6F00),

    val currentLine: Color = Color(0xFF252526),

    val bracketMatch: Color = Color(0x4D909090),

    val scrollbarThumb: Color = Color(0x66808080),

    val scrollbarThumbActive: Color = Color(0xB38C8C8C),

    val syntax: SyntaxColors = SyntaxColors.Dark,
) {
    companion object {

        val Default: EditorColors = EditorColors(
            background = Color(0xFF1E1E1E),
            foreground = Color(0xFFE0E0E0),
            gutterBackground = Color(0xFF252526),
            gutterForeground = Color(0xFF858585),
            cursor = Color(0xFFAEAFAD),
            selection = Color(0x553A6DA0),
            handle = Color(0xFF277AF7),
            currentLine = Color(0xFF252526),
        )

        val Light: EditorColors = EditorColors(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF1F1F1F),
            gutterBackground = Color(0xFFF3F3F3),
            gutterForeground = Color(0xFF9AA0A6),
            cursor = Color(0xFF1F1F1F),
            selection = Color(0x553B82F6),
            handle = Color(0xFF277AF7),
            symbolBarBackground = Color(0xFFECECEC),
            symbolBarForeground = Color(0xFF3C3C3C),
            symbolBarPressed = Color(0x14000000),
            currentLine = Color(0xFFF3F3F3),
            syntax = SyntaxColors.Light,
        )
    }
}
