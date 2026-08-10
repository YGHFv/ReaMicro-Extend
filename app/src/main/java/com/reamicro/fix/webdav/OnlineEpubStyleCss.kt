package com.reamicro.fix.webdav

import com.reamicro.fix.settings.OnlineEpubCssBlock
import com.reamicro.fix.settings.OnlineEpubCssBlocks
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings

/** 随书嵌入的字体：[family] 是 CSS 里引用的名字，[href] 是相对 Styles/ 的路径。 */
internal data class OnlineEpubFontFace(
    val family: String,
    val href: String,
    val format: String,
)

/** 由成书样式配置拼装 EPUB 的 Styles/default.css。 */
internal object OnlineEpubStyleCss {
    /** 参与成书的样式类别；头图样式只做配置与预览，暂不套用。 */
    val APPLIED_KINDS = listOf(
        OnlineEpubStyleKind.Title,
        OnlineEpubStyleKind.Volume,
        OnlineEpubStyleKind.Transition,
        OnlineEpubStyleKind.Illustration,
    )

    /**
     * @param fontFaces 已嵌入 EPUB 的字体，key 为样式 id。
     */
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
        APPLIED_KINDS.forEach { kind ->
            val style = settings.selected(kind) ?: return@forEach
            sections += "/* ${kind.title}：${style.name} */"
            sections += styleCss(style, fontFaces[style.id])
        }
        return sections.filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
    }

    /** 把样式的字体选择注入主选择器；主选择器缺失时补一段。 */
    fun styleCss(style: OnlineEpubStyle, fontFace: OnlineEpubFontFace?): String {
        val fontStack = fontStack(style, fontFace)
        if (fontStack.isBlank()) return style.css.trim()
        val primary = OnlineEpubStyleDefaults.selectors(style.kind).firstOrNull()
            ?: return style.css.trim()
        val blocks = OnlineEpubCssBlocks.parse(style.css)
        val index = blocks.indexOfFirst { it.selector == primary }
        val declaration = "font-family: $fontStack;"
        val next = if (index >= 0) {
            // 字体声明放在最前面，样式自带的 font-family 若存在会在后面覆盖它，符合"样式优先、字体兜底"的直觉。
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

    /** 选中字体文件时用嵌入名，选中内置字体时直接写名字，未选则不注入。 */
    private fun fontStack(style: OnlineEpubStyle, fontFace: OnlineEpubFontFace?): String {
        if (fontFace != null) return "\"${fontFace.family}\", serif"
        val selection = style.fontFamily.trim()
        if (selection.isBlank() || selection.contains('/') || selection.contains('\\')) return ""
        return "\"$selection\", serif"
    }

    /** 与样式无关的基础排版：正文、卷首页容器和旧书兜底。 */
    private val BASE_CSS = """
        /* 正文页面 */
        body {
            font-family: "楷体", serif;
            line-height: 130%;
            margin-left: 1%;
            margin-right: 1%;
            text-align: justify;
            background-color: transparent;
        }

        /* 正文段落 */
        p, p.te-paragraph {
            font-family: "楷体", serif;
            line-height: 130%;
            margin-left: 1%;
            margin-right: 1%;
            text-align: justify;
            text-indent: 2em;
        }

        /* 卷首页容器：统一留出卷首排场，具体外观交给卷标样式 */
        .te-volume-page {
            margin: 4em 0 0 0;
            font-size: 1.15em;
            text-align: center;
            text-indent: 0;
        }

        /* 卷首页装饰符 */
        .te-volume-ornament {
            text-align: center;
            text-indent: 0;
            font-size: 0.85em;
            color: #8c7b5a;
            line-height: 130%;
            margin: 0 0 1.6em 0;
        }

        .te-chapter-number, .te-chapter-name,
        .te-volume-number, .te-volume-name {
            display: block;
        }

        /* 旧书兜底：迁移前生成的插图与分割线 */
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
