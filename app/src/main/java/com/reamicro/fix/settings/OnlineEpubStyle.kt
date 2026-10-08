package com.reamicro.fix.settings

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

data class OnlineEpubStyle(
    val id: String,
    val kind: OnlineEpubStyleKind,
    val name: String,
    val description: String = "",
    val css: String = "",

    val fontFamily: String = "",

    val embedFont: Boolean = false,

    val assetPath: String = "",

    val markup: String = "",

    val maskAsset: String = "",
    val sampleWidth: Int = 0,
    val sampleHeight: Int = 0,
    val builtIn: Boolean = false,
) {

    val needsAsset: Boolean
        get() = kind == OnlineEpubStyleKind.Header ||
            (kind == OnlineEpubStyleKind.Transition && css.contains(".te-divider-img"))

    val supportsFont: Boolean
        get() = kind == OnlineEpubStyleKind.Title || kind == OnlineEpubStyleKind.Volume
}

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

data class OnlineEpubCssBlock(
    val selector: String,
    val declarations: String,
)

object OnlineEpubStyleDefaults {
    fun defaultStyleId(kind: OnlineEpubStyleKind): String =
        when (kind) {
            OnlineEpubStyleKind.Title -> "title-classic-red"
            OnlineEpubStyleKind.Volume -> "volume-classic-red"
            OnlineEpubStyleKind.Illustration -> "illustration-centered-caption"
            OnlineEpubStyleKind.Transition -> "transition-fg1-stars"
            OnlineEpubStyleKind.Header -> "header-standard-edge"
        }

    fun blankCss(kind: OnlineEpubStyleKind): String =
        OnlineEpubCssBlocks.compose(selectors(kind).map { OnlineEpubCssBlock(it, "") })

    fun selectors(kind: OnlineEpubStyleKind): List<String> =
        when (kind) {
            OnlineEpubStyleKind.Title -> listOf(".te-chapter-title", ".te-chapter-number", ".te-chapter-name")
            OnlineEpubStyleKind.Volume -> listOf(".te-volume-title", ".te-volume-number", ".te-volume-name")
            OnlineEpubStyleKind.Illustration ->
                listOf(".te-illustration", ".te-illustration-image", ".te-illustration-caption")
            OnlineEpubStyleKind.Transition -> listOf("p.te-divider-line, p.fg1")
            OnlineEpubStyleKind.Header -> listOf(".te-header-figure", ".te-header-image", ".te-header-caption")
        }

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

    fun previewBody(kind: OnlineEpubStyleKind, assetUrl: String = "", markup: String = ""): String {
        val image = assetUrl.takeIf { it.isNotBlank() } ?: PREVIEW_IMAGE
        val custom = markup.trim().takeIf { it.isNotBlank() }?.let { bindMarkupImage(it, image) }
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
                    (custom ?: defaultIllustration(image)) +
                    PREVIEW_PARAGRAPHS
            OnlineEpubStyleKind.Transition ->
                """<p class="te-paragraph">夜色沉入城市边缘，风从旧站台吹过。</p>""" +
                    (custom ?: transitionPreviewMark(assetUrl)) +
                    PREVIEW_PARAGRAPHS
            OnlineEpubStyleKind.Header ->
                """<figure class="te-header-figure"><img class="te-header-image" src="$image" alt=""/></figure>""" +
                    """<h1 class="te-chapter-title"><span class="te-chapter-name">计划不如变化</span></h1>""" +
                    PREVIEW_PARAGRAPHS
        }
    }

    fun bindMarkupImage(markup: String, imageUrl: String): String =
        markup.replace(MARKUP_IMAGE_SRC) { match -> """src="$imageUrl"""" }

    private fun defaultIllustration(image: String): String =
        """<figure class="te-illustration"><img class="te-illustration-image" src="$image" alt=""/>""" +
            """<figcaption class="te-illustration-caption">图 1　旧站台的最后一班列车</figcaption></figure>"""

    private val MARKUP_IMAGE_SRC = Regex("""src="[^"]*"""")

    private fun transitionPreviewMark(assetUrl: String): String =
        if (assetUrl.isBlank()) {
            """<p class="te-divider-line fg1">※※※</p>"""
        } else {
            """<div class="te-divider-image"><img class="te-divider-img" src="$assetUrl" alt=""/></div>"""
        }

    private const val PREVIEW_PARAGRAPHS =
        """<p class="te-paragraph">夜色沉入城市边缘，风从旧站台吹过，带着潮湿的铁锈味。</p>""" +
            """<p class="te-paragraph">她合上手中的书，抬头看见远处灯塔亮起，像一枚缓慢落下的星。</p>"""

    private const val PREVIEW_IMAGE =
        "data:image/svg+xml;charset=utf-8," +
            "%3Csvg%20xmlns%3D'http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg'%20viewBox%3D'0%200%20320%20180'%3E" +
            "%3Crect%20width%3D'320'%20height%3D'180'%20fill%3D'%23d8d3c6'%2F%3E" +
            "%3Cpath%20d%3D'M0%20140L90%2075l60%2042%2050-32%20120%2055z'%20fill%3D'%23a8a08c'%2F%3E" +
            "%3Ccircle%20cx%3D'252'%20cy%3D'46'%20r%3D'20'%20fill%3D'%23efe9dc'%2F%3E%3C%2Fsvg%3E"
}

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
