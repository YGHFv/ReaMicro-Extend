package com.reamicro.fix.hook

internal object StructureOpenedFiles {
    fun record(opened: MutableList<String>, path: String) {
        if (path.isNotBlank() && path !in opened) opened.add(path)
    }
}
