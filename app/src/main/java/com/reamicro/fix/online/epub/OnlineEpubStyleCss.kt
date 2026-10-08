package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.OnlineEpubCssBlock
import com.reamicro.fix.settings.OnlineEpubCssBlocks
import com.reamicro.fix.settings.OnlineEpubHeaderScope
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings

internal data class OnlineEpubFontFace(
    val family: String,
    val href: String,
    val format: String,
)

internal object OnlineEpubStyleCss {

    val APPLIED_KINDS = listOf(
        OnlineEpubStyleKind.Title,
        OnlineEpubStyleKind.Volume,
        OnlineEpubStyleKind.Transition,
        OnlineEpubStyleKind.Illustration,
    )

    fun appliedKinds(settings: OnlineEpubStyleSettings): List<OnlineEpubStyleKind> =
        if (settings.headerScope != OnlineEpubHeaderScope.Off) {
            APPLIED_KINDS + OnlineEpubStyleKind.Header
        } else {
            APPLIED_KINDS
        }

    fun build(
        settings: OnlineEpubStyleSettings,
        fontFaces: Map<String, OnlineEpubFontFace> = emptyMap(),
    ): String {
        val sections = ArrayList<String>()
        fontFaces.values.distinctBy { it.family }.forEach { face ->
            sections += "@font-face {\n" +
                "  font-family: \"${face.family}\";\n" +
                "  src: url(\"${face.href}\")${if (face.format.isBlank()) "" else " format(\"${face.format}\")"};\n" +
                "}"
        }
        sections += BASE_CSS
        appliedKinds(settings).forEach { kind ->
            val style = settings.selected(kind) ?: return@forEach
            sections += "/* ${kind.title}：${style.name} */"
            sections += styleCss(style, fontFaces[style.id])
        }
        val header = settings.selected(OnlineEpubStyleKind.Header)
            ?.takeIf { OnlineEpubStyleKind.Header in appliedKinds(settings) }
        if (header != null && !headerSitsFlushToTop(header.css)) {
            sections += KEEP_HEADER_TOP_MARGIN_CSS
        }
        return sections.filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
    }

    fun headerSitsFlushToTop(css: String): Boolean = headerFigureTopMargin(css) <= 0.0

    private fun headerFigureTopMargin(css: String): Double {
        val block = OnlineEpubCssBlocks.parse(css)
            .lastOrNull { it.selector == HEADER_FIGURE_SELECTOR }
            ?: return 0.0
        var top = 0.0

        block.declarations.split(';').forEach { declaration ->
            val (property, rawValue) = declaration.split(':', limit = 2)
                .takeIf { it.size == 2 }
                ?.let { it[0].trim().lowercase() to it[1].trim() }
                ?: return@forEach
            when (property) {
                "margin" -> rawValue.split(Regex("\\s+")).firstOrNull()?.let { top = cssLengthToEm(it) }
                "margin-top" -> top = cssLengthToEm(rawValue)
            }
        }
        return top
    }

    private fun cssLengthToEm(value: String): Double {
        val text = value.trim().lowercase()
        val number = Regex("^-?\\d*\\.?\\d+").find(text)?.value?.toDoubleOrNull() ?: return 0.0
        return when {
            text.endsWith("px") -> number / 16.0
            text.endsWith("%") -> number
            else -> number
        }
    }

    fun styleCss(style: OnlineEpubStyle, fontFace: OnlineEpubFontFace?): String {
        val fontStack = fontStack(style, fontFace)
        if (fontStack.isBlank()) return style.css.trim()
        val primary = OnlineEpubStyleDefaults.selectors(style.kind).firstOrNull()
            ?: return style.css.trim()
        val blocks = OnlineEpubCssBlocks.parse(style.css)
        val index = blocks.indexOfFirst { it.selector == primary }
        val declaration = "font-family: $fontStack;"
        val next = if (index >= 0) {

            blocks.toMutableList().also {
                it[index] = it[index].copy(
                    declarations = listOf(declaration, it[index].declarations).filter(String::isNotBlank).joinToString("\n"),
                )
            }
        } else {
            blocks + OnlineEpubCssBlock(primary, declaration)
        }
        return OnlineEpubCssBlocks.compose(next)
    }

    private fun fontStack(style: OnlineEpubStyle, fontFace: OnlineEpubFontFace?): String {
        if (!style.supportsFont) return ""
        if (fontFace != null) return "\"${fontFace.family}\", serif"
        val selection = style.fontFamily.trim()
        if (selection.isBlank()) return ""
        if (!selection.contains('/') && !selection.contains('\\')) return "\"$selection\", serif"
        if (style.embedFont) return ""
        val declaredName = selection.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return if (declaredName.isBlank()) "" else "\"$declaredName\", serif"
    }

    private const val HEADER_FIGURE_SELECTOR = ".te-header-figure"

    private val KEEP_HEADER_TOP_MARGIN_CSS = """
        body {
            display: flow-root;
        }
    """.trimIndent()

    private val BASE_CSS = """

        body {
            font-family: "楷体", serif;
            line-height: 130%;
            margin-left: 1%;
            margin-right: 1%;
            text-align: justify;
            background-color: transparent;
        }


        p, p.te-paragraph {
            font-family: "楷体", serif;
            line-height: 130%;
            margin-left: 1%;
            margin-right: 1%;
            text-align: justify;
            text-indent: 2em;
        }


        .te-volume-page {
            margin: 4em 0 0 0;
            font-size: 1.15em;
            text-align: center;
            text-indent: 0;
        }


        .te-volume-ornament {
            text-align: center;
            text-indent: 0;
            font-size: 0.85em;
            color: #8c7b5a;
            line-height: 130%;
            margin: 0 0 1.6em 0;
        }


        h1.te-chapter-title, h1.te-volume-title {
            font-size: 1.17em;
            font-weight: bold;
        }

        .te-chapter-number, .te-chapter-name,
        .te-volume-number, .te-volume-name {
            display: block;
        }


        .online-illustration {
            margin: 0.8em 0;
            text-align: center;
            text-indent: 0;
        }
        .online-illustration img {
            max-width: 100%;
            height: auto;
        }
        p.divider-line {
            text-align: center;
            text-indent: 0;
            margin: 1em 0;
            padding: 0;
            line-height: 130%;
        }
    """.trimIndent()
}
