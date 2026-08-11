package com.reamicro.fix.online.epub

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
        val header = settings.selected(OnlineEpubStyleKind.Header)
            ?.takeIf { OnlineEpubStyleKind.Header in appliedKinds(settings) }
        if (header != null && !headerSitsFlushToTop(header.css)) {
            sections += KEEP_HEADER_TOP_MARGIN_CSS
        }
        return sections.filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
    }

    /**
     * 头图是否设计成贴住页面上边缘。
     *
     * 判据是 `.te-header-figure` 的上外边距是否为 0：贴边样式一律写 `margin-top: 0`，
     * 卡片、浮印这类要在上方留白的写正值。
     *
     * 不看 `duokan-bleed`：电影裁幅头图只声明了 `leftright`、细线装帧头图干脆没声明，
     * 但它们的上外边距都是 0，同样应该贴住页顶。按外边距判断才和样式作者的意图一致。
     */
    fun headerSitsFlushToTop(css: String): Boolean = headerFigureTopMargin(css) <= 0.0

    /**
     * 解析 `.te-header-figure` 的上外边距，单位按 em 归一（无法识别时按 0 处理）。
     *
     * 同一块里出现多次时后写的生效，与 CSS 一致。
     */
    private fun headerFigureTopMargin(css: String): Double {
        val block = OnlineEpubCssBlocks.parse(css)
            .lastOrNull { it.selector == HEADER_FIGURE_SELECTOR }
            ?: return 0.0
        var top = 0.0
        // 按分号切而不是按行切：同一行写多条声明也要认。
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

    /** 只需要区分「零/负」与「正」，因此按 1em = 16px 粗略折算即可。 */
    private fun cssLengthToEm(value: String): Double {
        val text = value.trim().lowercase()
        val number = Regex("^-?\\d*\\.?\\d+").find(text)?.value?.toDoubleOrNull() ?: return 0.0
        return when {
            text.endsWith("px") -> number / 16.0
            text.endsWith("%") -> number
            else -> number
        }
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

    private const val HEADER_FIGURE_SELECTOR = ".te-header-figure"

    /**
     * 非贴边头图保住上方留白。
     *
     * 头图 figure 是 body 的第一个子元素，而 body 没有上内边距／上边框，按 CSS 规则
     * 它的上外边距会折叠到 body 外面——结果就是本该留白的卡片头图也贴在了页顶。
     * 让 body 建立独立的格式化上下文即可把外边距关在里面。贴边样式的上外边距本来就是 0，
     * 因此这段只在非贴边头图时才写入。
     */
    private val KEEP_HEADER_TOP_MARGIN_CSS = """
        body {
            display: flow-root;
        }
    """.trimIndent()

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

        /*
         * 标题容器基准字号。
         *
         * 内置样式移植自 TEpub，那边标题是 h3；这里用 h1，浏览器/阅读器默认 2em 会和样式里
         * .te-chapter-name 的 1.2em 相乘，标题大到换行。对齐成 h3 的 1.17em，没写 font-size
         * 的样式才是设计时的大小；样式自己声明了 font-size 的照常覆盖。
         */
        h1.te-chapter-title, h1.te-volume-title {
            font-size: 1.17em;
            font-weight: bold;
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
