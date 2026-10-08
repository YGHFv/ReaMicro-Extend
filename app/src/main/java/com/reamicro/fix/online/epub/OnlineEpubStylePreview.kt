package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.OnlineEpubHeaderScope
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings

internal object OnlineEpubStylePreview {

    fun html(
        settings: OnlineEpubStyleSettings,
        draft: OnlineEpubStyle,
        assetUrl: String = "",
        fontUrl: String = "",
    ): String {
        val previewSettings = settings.withDraft(draft).let {

            if (draft.kind == OnlineEpubStyleKind.Header) it.copy(headerScope = HEADER_PREVIEW_SCOPE) else it
        }
        val bookCss = OnlineEpubStyleCss.build(previewSettings, previewFontFaces(draft, fontUrl))
        val headerCss = previewSettings.selected(OnlineEpubStyleKind.Header)?.css.orEmpty()
        val bleedReset = if (OnlineEpubStyleCss.headerSitsFlushToTop(headerCss)) BLEED_HEADER_RESET_CSS else ""
        return """<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<style>
$RESET_CSS
$bleedReset

$bookCss
</style></head><body>${OnlineEpubStyleDefaults.previewBody(draft.kind, assetUrl, draft.markup)}</body></html>"""
    }

    private fun previewFontFaces(draft: OnlineEpubStyle, fontUrl: String): Map<String, OnlineEpubFontFace> {
        if (fontUrl.isBlank()) return emptyMap()
        return mapOf(draft.id to OnlineEpubFontFace("rm-preview-font", fontUrl, ""))
    }

    private val HEADER_PREVIEW_SCOPE = OnlineEpubHeaderScope.EveryChapter

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
    """.trimIndent()

    private val BLEED_HEADER_RESET_CSS = """
        body > .te-header-figure:first-child {
            margin-top: -20px;
        }
    """.trimIndent()
}
