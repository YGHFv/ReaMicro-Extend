package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.EpubStorageFiles

internal object StructureFileOpenPolicy {
    fun canOpen(name: String, kind: String, nativeEditor: Boolean): Boolean {
        if (EpubStorageFiles.structure130TypeOrder(name) == 7) return false
        return nativeEditor || name.endsWith(".opf", ignoreCase = true) ||
            kind == "image" || kind == "font" || kind == "css" || kind == "navigation" || name.endsWith(".ncx", true)
    }
}
