package com.reamicro.fix.settings

/** 在线补全成书可配置的五类样式。 */
enum class OnlineEpubStyleKind(val id: String, val title: String) {
    Title("title", "标题样式"),
    Header("header", "头图样式"),
    Transition("transition", "分割样式"),
    Volume("volume", "卷标样式"),
    Illustration("illustration", "插图样式"),
    ;

    companion object {
        fun fromId(id: String): OnlineEpubStyleKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一条成书样式配置。
 *
 * [css] 存完整 CSS 文本，编辑器再用 [OnlineEpubCssBlocks] 按选择器拆成可分别填写的段落；
 * 这样既能容纳 `::before`、修饰类等无法归入固定分段的写法，也不必为每类样式定死结构。
 */
data class OnlineEpubStyle(
    val id: String,
    val kind: OnlineEpubStyleKind,
    val name: String,
    val description: String = "",
    val css: String = "",
    /** 字体文件绝对路径 / 内置 family 名 / 空串表示跟随全局。 */
    val fontFamily: String = "",
    /** 字体文件是否随书嵌入；false 时只在 CSS 里声明字体名，交给阅读器自行解析。 */
    val embedFont: Boolean = true,
    /** 样式关联的本地图片：分割样式的装饰图、头图样式的原图。 */
    val assetPath: String = "",
    /** 头图样式的蒙版 asset 名与样板尺寸，随内置样式生成，用户样式为空。 */
    val maskAsset: String = "",
    val sampleWidth: Int = 0,
    val sampleHeight: Int = 0,
    val builtIn: Boolean = false,
) {
    /** 分割样式引用了图片选择器，或头图样式，都需要用户指定一张图片。 */
    val needsAsset: Boolean
        get() = kind == OnlineEpubStyleKind.Header ||
            (kind == OnlineEpubStyleKind.Transition && css.contains(".te-divider-img"))
}

/** 头图套用范围。 */
enum class OnlineEpubHeaderScope(val id: String, val title: String) {
    Off("off", "关闭"),
    EveryChapter("every_chapter", "每章"),
    VolumePage("volume_page", "卷首页"),
    VolumeFirstChapter("volume_first_chapter", "每卷首章"),
    ;

    companion object {
        fun fromId(id: String): OnlineEpubHeaderScope? = entries.firstOrNull { it.id == id }
    }
}

/** 一段可独立填写的 CSS：[selector] 为选择器，[declarations] 为花括号内的声明。 */
data class OnlineEpubCssBlock(
    val selector: String,
    val declarations: String,
)

/** 各类样式的默认选中项、预览片段与新建骨架。 */
object OnlineEpubStyleDefaults {
    fun defaultStyleId(kind: OnlineEpubStyleKind): String =
        when (kind) {
            OnlineEpubStyleKind.Title -> "title-classic-red"
            OnlineEpubStyleKind.Volume -> "volume-classic-red"
            OnlineEpubStyleKind.Illustration -> "illustration-centered-caption"
            OnlineEpubStyleKind.Transition -> "transition-fg1-stars"
            OnlineEpubStyleKind.Header -> "header-standard-edge"
        }

    /** 新建自定义样式时预置的选择器骨架，让用户直接分段填写。 */
    fun blankCss(kind: OnlineEpubStyleKind): String =
        OnlineEpubCssBlocks.compose(selectors(kind).map { OnlineEpubCssBlock(it, "") })

    /** 该类样式的标准接口选择器，用于分段编辑的默认分段。 */
    fun selectors(kind: OnlineEpubStyleKind): List<String> =
        when (kind) {
            OnlineEpubStyleKind.Title -> listOf(".te-chapter-title", ".te-chapter-number", ".te-chapter-name")
            OnlineEpubStyleKind.Volume -> listOf(".te-volume-title", ".te-volume-number", ".te-volume-name")
            OnlineEpubStyleKind.Illustration ->
                listOf(".te-illustration", ".te-illustration-image", ".te-illustration-caption")
            OnlineEpubStyleKind.Transition -> listOf("p.te-divider-line, p.fg1")
            OnlineEpubStyleKind.Header -> listOf(".te-header-figure", ".te-header-image", ".te-header-caption")
        }

    /** 分段切换器上显示的短标签，未知选择器回退为选择器本身。 */
    fun sectionLabel(kind: OnlineEpubStyleKind, selector: String): String =
        when (selector) {
            ".te-chapter-title", ".te-volume-title" -> "整体"
            ".te-chapter-number", ".te-volume-number" -> "序号"
            ".te-chapter-name", ".te-volume-name" -> "内容"
            ".te-illustration", ".te-header-figure" -> "容器"
            ".te-illustration-image", ".te-header-image" -> "图片"
            ".te-illustration-caption" -> "图注"
            ".te-header-caption" -> "说明"
            "p.te-divider-line, p.fg1" -> "整体"
            else -> selector.substringBefore(',').trim().removePrefix(".").ifBlank { selector }
        }

    /**
     * 弹窗预览用的正文片段，结构与成书完全一致，便于所见即所得。
     *
     * @param assetUrl 分割图 / 头图的实际地址，为空时用内置占位图。
     */
    fun previewBody(kind: OnlineEpubStyleKind, assetUrl: String = ""): String {
        val image = assetUrl.takeIf { it.isNotBlank() } ?: PREVIEW_IMAGE
        return when (kind) {
            OnlineEpubStyleKind.Title ->
                """<h1 class="te-chapter-title"><span class="te-chapter-number">第三章</span>""" +
                    """<span class="te-chapter-name">计划不如变化</span></h1>$PREVIEW_PARAGRAPHS"""
            OnlineEpubStyleKind.Volume ->
                """<div class="te-volume-page"><div class="te-volume-ornament">※</div>""" +
                    """<h1 class="te-volume-title"><span class="te-volume-number">第八小节</span>""" +
                    """<span class="te-volume-name">直至时间的尽头</span></h1></div>"""
            OnlineEpubStyleKind.Illustration ->
                """<p class="te-paragraph">她合上手中的书，抬头看见远处灯塔亮起。</p>""" +
                    """<figure class="te-illustration"><img class="te-illustration-image" src="$PREVIEW_IMAGE" alt=""/>""" +
                    """<figcaption class="te-illustration-caption">图 1　旧站台的最后一班列车</figcaption></figure>""" +
                    PREVIEW_PARAGRAPHS
            OnlineEpubStyleKind.Transition ->
                """<p class="te-paragraph">夜色沉入城市边缘，风从旧站台吹过。</p>""" +
                    transitionPreviewMark(assetUrl) +
                    PREVIEW_PARAGRAPHS
            OnlineEpubStyleKind.Header ->
                """<figure class="te-header-figure"><img class="te-header-image" src="$image" alt=""/>""" +
                    """<figcaption class="te-header-caption">章节头图</figcaption></figure>""" +
                    """<h1 class="te-chapter-title"><span class="te-chapter-name">计划不如变化</span></h1>""" +
                    PREVIEW_PARAGRAPHS
        }
    }

    /** 选了装饰图就预览图片转场，否则预览文字转场。 */
    private fun transitionPreviewMark(assetUrl: String): String =
        if (assetUrl.isBlank()) {
            """<p class="te-divider-line fg1">※※※</p>"""
        } else {
            """<div class="te-divider-image"><img class="te-divider-img" src="$assetUrl" alt=""/></div>"""
        }

    private const val PREVIEW_PARAGRAPHS =
        """<p class="te-paragraph">夜色沉入城市边缘，风从旧站台吹过，带着潮湿的铁锈味。</p>""" +
            """<p class="te-paragraph">她合上手中的书，抬头看见远处灯塔亮起，像一枚缓慢落下的星。</p>"""

    /** 预览占位图：内联 SVG，避免打包位图资源。 */
    private const val PREVIEW_IMAGE =
        "data:image/svg+xml;charset=utf-8," +
            "%3Csvg%20xmlns%3D'http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg'%20viewBox%3D'0%200%20320%20180'%3E" +
            "%3Crect%20width%3D'320'%20height%3D'180'%20fill%3D'%23d8d3c6'%2F%3E" +
            "%3Cpath%20d%3D'M0%20140L90%2075l60%2042%2050-32%20120%2055z'%20fill%3D'%23a8a08c'%2F%3E" +
            "%3Ccircle%20cx%3D'252'%20cy%3D'46'%20r%3D'20'%20fill%3D'%23efe9dc'%2F%3E%3C%2Fsvg%3E"
}

/**
 * CSS 文本与选择器分段之间的互转。
 *
 * 只做顶层规则的粗粒度切分，够配置界面按段编辑即可；解析不出结构时调用方回退到整段编辑。
 */
object OnlineEpubCssBlocks {
    fun parse(css: String): List<OnlineEpubCssBlock> {
        val blocks = ArrayList<OnlineEpubCssBlock>()
        var index = 0
        while (index < css.length) {
            val open = css.indexOf('{', index)
            if (open < 0) break
            val close = matchBrace(css, open)
            if (close < 0) break
            val selector = css.substring(index, open).trim()
            val declarations = css.substring(open + 1, close).trimIndent().trim()
            if (selector.isNotEmpty()) blocks += OnlineEpubCssBlock(selector, declarations)
            index = close + 1
        }
        return blocks
    }

    fun compose(blocks: List<OnlineEpubCssBlock>): String =
        blocks.filter { it.selector.isNotBlank() }
            .joinToString("\n\n") { block ->
                val body = block.declarations.trim()
                if (body.isEmpty()) "${block.selector} {\n}" else "${block.selector} {\n${body.prependIndent("  ")}\n}"
            }

    /** 替换指定选择器的声明块；选择器不存在时追加一段。 */
    fun replace(css: String, selector: String, declarations: String): String {
        val blocks = parse(css).toMutableList()
        val index = blocks.indexOfFirst { it.selector == selector }
        if (index >= 0) {
            blocks[index] = blocks[index].copy(declarations = declarations)
        } else {
            blocks += OnlineEpubCssBlock(selector, declarations)
        }
        return compose(blocks)
    }

    /** 找到与 [open] 处 `{` 配对的 `}`，找不到返回 -1。 */
    private fun matchBrace(css: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < css.length) {
            when (css[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        return -1
    }
}
