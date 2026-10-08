package com.reamicro.fix.hook

import android.content.Context

internal object DiscoverSurfaceStyle {
    fun onShelf(palette: ModuleDialogTheme.Palette, shelfBackground: Int) = palette.copy(
        pageBackground = shelfBackground,
        rowBackground = shelfBackground,
    )
}

internal fun discoverDialogPalette(context: Context): ModuleDialogTheme.Palette {
    val palette = ModuleDialogTheme.palette(context)
    val shelf = EmbeddedHostUi.snapshot(context)?.contentArgb ?: palette.pageBackground
    return DiscoverSurfaceStyle.onShelf(palette, shelf)
}
