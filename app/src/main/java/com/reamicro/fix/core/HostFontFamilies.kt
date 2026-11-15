package com.reamicro.fix.core

import java.io.File

/** Font families use the current host's file loader and native constructors. */
object HostFontFamilies {
    private fun contract(loader: ClassLoader): HostReaderExtrasBindings.Contract =
        requireNotNull(HostReaderExtrasBindings.fontFamilies) { "Native font families unavailable" }.also {
            require(hostMembers().namedClass(it.type("family").name, loader) === it.type("family")) {
                "Native font family loader differs"
            }
        }
    @JvmStatic fun fromFile(loader: ClassLoader, file: File): Any? {
        require(file.isFile) { "Font file is unavailable: $file" }
        val host = contract(loader)
        val fonts = listOf(400, 700).mapNotNull { weight ->
            host.method("fromPath").invoke(null, file.absolutePath, host.constructor("weight").newInstance(weight))
        }
        return if (fonts.isEmpty()) null else host.constructor("list").newInstance(fonts)
    }
    @JvmStatic fun system(loader: ClassLoader): Any = requireNotNull(contract(loader).field("system").get(null))
    @JvmStatic fun song(loader: ClassLoader): Any? = contract(loader).method("song").invoke(null)
}
