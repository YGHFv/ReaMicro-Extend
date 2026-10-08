package com.reamicro.fix.hook

internal object StructureFileVisibility {
    fun isVisible(relativePath: String): Boolean {
        val path = relativePath.replace(92.toChar(), '/').removePrefix("./")
        return !path.substringAfterLast('/').equals("reamicro-online-chapters.json", ignoreCase = true) && path != "tags"
    }
}
