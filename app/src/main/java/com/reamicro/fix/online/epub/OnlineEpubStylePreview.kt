package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.OnlineEpubHeaderScope
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings

/**
 * 配置弹窗的样式预览文档。
 *
 * 预览注入的 CSS 就是 [OnlineEpubStyleCss.build] 的输出 —— 与写进 `Styles/default.css` 的内容
 * 完全一致，不再另写一套排版，否则预览与成书必然对不上。宿主之外只补最小 reset 和一层模拟
 * 阅读页的纸张外观。
 */
internal object OnlineEpubStylePreview {
    /**
     * @param draft 正在编辑的样式，会覆盖设置里的同 id 项并被选中。
     * @param assetUrl 样式关联图片的可加载地址（`file://…`）；为空时用内置占位图。
     * @param fontUrl 字体文件的可加载地址（`file://…`）；为空表示未选文件字体。
     */
    fun html(
        settings: OnlineEpubStyleSettings,
        draft: OnlineEpubStyle,
        assetUrl: String = "",
        fontUrl: String = "",
    ): String {
        val previewSettings = settings.withDraft(draft).let {
            // 头图预览要能看到效果，这里临时把它当作已启用。
            if (draft.kind == OnlineEpubStyleKind.Header) it.copy(headerScope = HEADER_PREVIEW_SCOPE) else it
        }
        val bookCss = OnlineEpubStyleCss.build(previewSettings, previewFontFaces(draft, fontUrl))
        return """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
$RESET_CSS

$bookCss
</style></head><body>${OnlineEpubStyleDefaults.previewBody(draft.kind, assetUrl, draft.markup)}</body></html>"""
    }

    /**
     * 预览统一用 `file://` 直读设备字体，两种字体模式看到的字形一致。
     *
     * 成书时「仅声明」模式不会产生 @font-face，但预览要展示用户真正选的字，否则无从判断效果。
     */
    private fun previewFontFaces(draft: OnlineEpubStyle, fontUrl: String): Map<String, OnlineEpubFontFace> {
        if (fontUrl.isBlank()) return emptyMap()
        return mapOf(draft.id to OnlineEpubFontFace("rm-preview-font", fontUrl, ""))
    }

    /** 头图预览固定按「每章」渲染，只影响预览，不写回设置。 */
    private val HEADER_PREVIEW_SCOPE = OnlineEpubHeaderScope.EveryChapter

    /** 只做最小 reset，外加一层模拟阅微阅读页的纸张外观。 */
    private val RESET_CSS = """
        html {
            background: #F5EFE0;
            color: #1f2937;
            font-size: 17px;
        }
        body {
            margin: 0;
            padding: 20px 18px 28px;
        }
        img {
            max-width: 100%;
            height: auto;
        }
        /*
         * 贴边头图靠 duokan-bleed 出血到页顶，WebView 不认这个指令，预览里就会在头图上方留一条纸色。
         * 这里把 body 的上内边距抵消掉，让预览的贴边效果与成书一致。
         */
        body > .te-header-figure:first-child {
            margin-top: -20px;
        }
    """.trimIndent()
}
