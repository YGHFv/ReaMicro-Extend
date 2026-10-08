package com.reamicro.fix.epub.editor

internal object EpubBannerVariants {
    fun names(cover: String, loader: ClassLoader? = null): List<String> {
        val path = cover.replace('\\', '/')
        if (path.isBlank()) return emptyList()
        if (loader != null) {
            val native = runCatching {
                loader.loadClass("app.zhendong.reamicro.ui.home.BannerVariantKt")
                    .getMethod("toBannerVariantNames", String::class.java).invoke(null, path) as? List<*>
            }.getOrNull()?.filterIsInstance<String>()
            if (!native.isNullOrEmpty()) return native.distinct()
        }
        val dot = path.lastIndexOf('.')
        val hasExtension = dot > path.lastIndexOf('/')
        val stem = (if (hasExtension) path.substring(0, dot) else path).removeSuffix("-reamicro")
        val extension = if (hasExtension) path.substring(dot) else ""
        return (listOf("$stem~banner$extension") + listOf(".jpg", ".png", ".webp").map { "$stem~banner$it" }).distinct()
    }
    fun primary(cover: String, loader: ClassLoader? = null): String =
        names(cover, loader).firstOrNull() ?: error("请先设置封面后再设置横幅")

    fun existing(cover: String, inventory: Set<String>, loader: ClassLoader? = null): String? =
        names(cover, loader).firstOrNull { it in inventory }

    fun imagePriority(path: String, cover: String, banner: String): Int = when {
        path == cover && cover.isNotBlank() -> 0
        path == banner && banner.isNotBlank() -> 1
        else -> 2
    }
}
