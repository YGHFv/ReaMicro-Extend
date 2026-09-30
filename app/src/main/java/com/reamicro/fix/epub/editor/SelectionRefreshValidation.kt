package com.reamicro.fix.epub.editor

/** Empty chapters may legitimately resolve to another spine in host 2310. */
internal fun validSelectionRefreshPage(pageExists: Boolean, mappedSpine: Int?, spineCount: Int): Boolean =
    pageExists && mappedSpine != null && mappedSpine >= 0 && mappedSpine < spineCount
