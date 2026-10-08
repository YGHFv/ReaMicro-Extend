package com.reamicro.fix.hook

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reamicro.fix.R
import java.util.Locale

internal object StructureFileLabels {
    @Composable
    fun name(fileName: String, cover: Boolean, banner: Boolean = false): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = fileName.substringBeforeLast('.', fileName)
        return when (extension) {
            "html", "htm", "xhtml" -> stringResource(R.string.epub_structure_content, extension.uppercase(Locale.ROOT))
            "xml" -> stringResource(if (stem == "container") R.string.epub_structure_container else R.string.epub_structure_unknown_file)
            "ttf", "otf" -> stringResource(R.string.epub_structure_font)
            "svg", "png", "jpg", "jpeg", "gif", "webp" ->
                stringResource(if (cover) R.string.epub_structure_cover else if (banner) R.string.epub_structure_banner else R.string.epub_structure_image)
            "opf" -> stringResource(R.string.epub_structure_metadata)
            "ncx" -> stringResource(R.string.epub_structure_toc)
            "css" -> stringResource(R.string.epub_structure_css)
            else -> if (stem == "bookmarks") stringResource(R.string.epub_structure_bookmarks)
                else stringResource(R.string.epub_structure_unknown_format, extension)
        }
    }
}
