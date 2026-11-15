package com.reamicro.fix.epub.editor

import org.json.JSONArray
import java.io.File

internal fun hostStyleSheetPreview(loader: ClassLoader, file: File): JSONArray =
    NativeCssPreview(loader).preview(file)

internal fun invalidateHostCssPreview(loader: ClassLoader, file: File) {
    NativeCssPreview(loader).invalidate(file)
}
