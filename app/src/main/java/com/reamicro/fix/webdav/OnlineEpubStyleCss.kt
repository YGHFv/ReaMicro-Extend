package com.reamicro.fix.webdav

import com.reamicro.fix.settings.OnlineEpubCssBlock
import com.reamicro.fix.settings.OnlineEpubCssBlocks
import com.reamicro.fix.settings.OnlineEpubHeaderScope
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
    /** 始终参与成书的样式类别；头图另外由 headerScope 决定。 */
    val APPLIED_KINDS = listOf(
        OnlineEpubStyleKind.Title,
        OnlineEpubStyleKind.Volume,
        OnlineEpubStyleKind.Transition,
        OnlineEpubStyleKind.Illustration,
    )

    /**
     * 本次成书实际写入 CSS 的类别。
     *
     * 头图只要范围没关就写入 CSS —— 是否真的插入图片元素由 [OnlineEpubStyleSettings.headerEnabled]
     * 另行决定，多一段用不上的样式无害，但少一段会让预览看不到效果。
     */
    fun appliedKinds(settings: OnlineEpubStyleSettings): List<OnlineEpubStyleKind> =
        if (settings.headerScope != OnlineEpubHeaderScope.Off) {
            APPLIED_KINDS + OnlineEpubStyleKind.Header
        } else {
            APPLIED_KINDS
        }

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
        appliedKinds(settings).forEach { kind ->
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

    /**
     * 选中字体文件时：嵌入模式用 `@font-face` 的族名，仅声明模式用文件名（去扩展名）当族名，
     * 由阅读器自行解析同名的本机字体。选内置字体名时直接写名字。
     *
     * 只有标题与卷标提供字体设置，其余类别即使历史配置里带了字体也不注入。
     */
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
