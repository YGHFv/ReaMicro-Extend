package com.reamicro.fix.epub.editor

import java.io.File

internal object FontSelectionResolution {
    fun followsHost(selection: String): Boolean = selection.isBlank()
    fun file(selection: String, directories: List<File>): File? {
        if(followsHost(selection))return null
        val direct=File(selection)
        fun valid(file:File)=file.isFile && file.extension.lowercase() in setOf("ttf","otf")
        if(direct.isAbsolute)return direct.takeIf(::valid)
        if(direct.parent!=null)return null
        return directories.asSequence().map { File(it,direct.name) }.filter(::valid)
            .distinctBy { it.canonicalPath }.toList().singleOrNull()
    }
    fun cacheKey(selection: String, file: File?): String = if(file==null)selection else
        "${file.canonicalPath}|${file.length()}|${file.lastModified()}"
}
