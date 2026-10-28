package com.reamicro.fix.core

import java.io.File

/** 保留宿主字体加载器与原生 family，兼容 FontProvider 的静态归约。 */
object HostFontFamilies {
    @JvmStatic
    fun fromFile(loader: ClassLoader, file: File): Any? {
        require(file.isFile) { "Font file unavailable: ${file.name}" }
        val provider = HostObjects.get(loader, "org.epub.FontProvider")
        val weight = loader.hostClass("androidx.compose.ui.text.font.FontWeight")
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
        val fonts = listOf(400, 700).mapNotNull {
            provider.call("fromPath", file.absolutePath, weight.newInstance(it))
        }
        if (fonts.isEmpty()) return null
        return loader.hostClass("androidx.compose.ui.text.font.FontListFontFamily")
            .getDeclaredConstructor(List::class.java).newInstance(fonts)
    }

    @JvmStatic
    fun system(loader: ClassLoader): Any =
        requireNotNull(loader.hostClass("androidx.compose.ui.text.font.FontFamily").hostField("Default").get(null))

    @JvmStatic
    fun song(loader: ClassLoader): Any? =
        HostObjects.get(loader, "org.epub.FontProvider").call("builtInSong")
}
