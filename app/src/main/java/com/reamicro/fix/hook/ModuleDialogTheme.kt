package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.ProgressBar

internal object ModuleDialogTheme {
    @Volatile private var cachedPalette: Palette? = null

    fun update(palette: Palette) {
        cachedPalette = palette
    }

    fun palette(context: Context): Palette {
        val darkHint = inferDarkMode(context)
        cachedPalette?.let { cached ->
            if (cached.matches(darkHint)) {
                return cached
            }
        }
        return fallbackPalette(context, darkHint)
    }

    fun tintProgress(progressBar: ProgressBar, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressBar.indeterminateTintList = ColorStateList.valueOf(color)
            progressBar.progressTintList = ColorStateList.valueOf(color)
        }
    }

    private fun fallbackPalette(context: Context, dark: Boolean): Palette {
        val pageFallback = if (dark) Color.rgb(17, 19, 24) else Color.rgb(244, 245, 247)
        val rowFallback = if (dark) Color.rgb(25, 28, 32) else Color.WHITE
        val titleFallback = if (dark) Color.rgb(226, 226, 233) else Color.rgb(30, 27, 19)
        val bodyFallback = colorWithAlpha(titleFallback, if (dark) 166 else 150)
        val page = themeColorByNames(
            context,
            listOf("android:windowBackground", "colorBackground", "android:colorBackground"),
        )?.takeIf { it.isUsableBackground(dark) } ?: pageFallback
        val row = themeColorByNames(
            context,
            listOf("colorSurface", "colorSurfaceContainer", "colorSurfaceContainerHigh"),
        )?.takeIf { it.isUsableBackground(dark) } ?: rowFallback
        val title = themeColorByNames(
            context,
            listOf("colorOnBackground", "colorOnSurface", "android:textColorPrimary"),
        )?.takeIf { it.isUsableForeground(dark) } ?: titleFallback
        val body = themeColorByNames(
            context,
            listOf("colorOnSurfaceVariant", "android:textColorSecondary"),
        )?.takeIf { it.isUsableForeground(dark) } ?: bodyFallback
        val border = themeColorByNames(
            context,
            listOf("colorOutlineVariant", "colorSurfaceContainerHighest"),
        )?.takeIf { it.isUsableBackground(dark) } ?: if (dark) Color.rgb(40, 42, 47) else Color.rgb(234, 235, 237)
        val primary = if (dark) titleFallback else Color.rgb(255, 90, 31)
        val primarySoft = if (dark) rowFallback else Color.rgb(255, 241, 234)
        val primaryText = if (dark) titleFallback else Color.rgb(194, 74, 20)
        return Palette(
            pageBackground = page,
            rowBackground = row,
            border = border,
            title = title,
            body = body,
            primary = primary,
            primarySoft = primarySoft,
            primaryText = primaryText,
            neutralText = title,
            destructiveText = if (dark) Color.rgb(255, 172, 172) else Color.rgb(214, 69, 69),
            dynamic = false,
        )
    }

    private fun themeColorByNames(context: Context, names: List<String>): Int? {
        for (name in names) {
            resolveThemeColor(context, name)?.let { return it }
        }
        return null
    }

    private fun inferDarkMode(context: Context): Boolean {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (systemDark) return true
        val activity = context as? Activity
        if (activity != null) {
            val content = activity.findViewById<View?>(android.R.id.content)
            content?.findColorInViewTree()?.let { return it.isDarkColor() }
            (activity.window?.decorView?.background as? ColorDrawable)
                ?.color
                ?.takeIf { Color.alpha(it) > 32 }
                ?.let { return it.isDarkColor() }
        }
        listOf(
            "android:windowBackground",
            "colorBackground",
            "android:colorBackground",
            "colorSurface",
        ).forEach { name ->
            resolveThemeColor(context, name)
                ?.takeIf { Color.alpha(it) > 32 }
                ?.let { return it.isDarkColor() }
        }
        return false
    }

    private fun View.findColorInViewTree(): Int? {
        var current: View? = this
        while (current != null) {
            val color = (current.background as? ColorDrawable)
                ?.color
                ?.takeIf { Color.alpha(it) > 32 }
            if (color != null) return color
            current = current.parent as? View
        }
        return null
    }

    private fun resolveThemeColor(context: Context, name: String): Int? {
        val attrId = when {
            name.startsWith("android:") -> {
                val androidName = name.substringAfter(':')
                context.resources.getIdentifier(androidName, "attr", "android")
            }
            else -> {
                context.resources.getIdentifier(name, "attr", context.packageName)
                    .takeIf { it != 0 }
                    ?: context.resources.getIdentifier(name, "attr", "com.google.android.material")
            }
        }
        if (attrId == 0) return null
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attrId, value, true)) return null
        return when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.getColor(value.resourceId)
                } else {
                    @Suppress("DEPRECATION")
                    context.resources.getColor(value.resourceId)
                }
            }.getOrNull()
            else -> null
        }
    }

    data class Palette(
        val pageBackground: Int,
        val rowBackground: Int,
        val border: Int,
        val title: Int,
        val body: Int,
        val primary: Int,
        val primarySoft: Int,
        val primaryText: Int,
        val neutralText: Int,
        val destructiveText: Int,
        val dynamic: Boolean = true,
    ) {
        fun matches(dark: Boolean): Boolean =
            pageBackground.isUsableBackground(dark) &&
                rowBackground.isUsableBackground(dark) &&
                title.isUsableForeground(dark) &&
                body.isUsableForeground(dark)
    }
}

private fun Int.isUsableBackground(dark: Boolean): Boolean =
    Color.alpha(this) > 32 && isDarkColor() == dark

private fun Int.isUsableForeground(dark: Boolean): Boolean =
    Color.alpha(this) > 32 && isDarkColor() != dark

private fun Int.isDarkColor(): Boolean {
    val red = Color.red(this) / 255.0
    val green = Color.green(this) / 255.0
    val blue = Color.blue(this) / 255.0
    val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance < 0.45
}

internal fun colorWithAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
