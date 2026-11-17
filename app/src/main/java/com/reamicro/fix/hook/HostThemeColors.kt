package com.reamicro.fix.hook


import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import top.yukonga.miuix.kmp.theme.Colors

internal object HostThemeColors {
    private val roleNames = listOf(
        "Primary", "OnPrimary", "PrimaryContainer", "OnPrimaryContainer", "InversePrimary",
        "Secondary", "OnSecondary", "SecondaryContainer", "OnSecondaryContainer", "Tertiary",
        "OnTertiary", "TertiaryContainer", "OnTertiaryContainer", "Background", "OnBackground",
        "Surface", "OnSurface", "SurfaceVariant", "OnSurfaceVariant", "SurfaceTint",
        "InverseSurface", "InverseOnSurface", "Error", "OnError", "ErrorContainer",
        "OnErrorContainer", "Outline", "OutlineVariant", "Scrim", "SurfaceBright",
        "SurfaceDim", "SurfaceContainer", "SurfaceContainerHigh", "SurfaceContainerHighest", "SurfaceContainerLow",
        "SurfaceContainerLowest", "PrimaryFixed", "PrimaryFixedDim", "OnPrimaryFixed", "OnPrimaryFixedVariant",
        "SecondaryFixed", "SecondaryFixedDim", "OnSecondaryFixed", "OnSecondaryFixedVariant", "TertiaryFixed",
        "TertiaryFixedDim", "OnTertiaryFixed", "OnTertiaryFixedVariant",
    )
    private var captured by mutableStateOf<Snapshot?>(null)
    @Volatile private var reader: Reader? = null
    @Volatile private var installed = false
    private val resolving = ThreadLocal.withInitial { false }
    @Volatile private var warned = false

    data class Snapshot(
        val roles: Map<String, Int>, val pageArgb: Int, val contentArgb: Int,
        val brightArgb: Int, val borderArgb: Int, val borderVariantArgb: Int,
        val captionArgb: Int, val dark: Boolean, val source: String,
    ) {
        fun role(name: String) = Color(roles.getValue(name))
        fun controlsScheme(): Colors = Colors(
            primary = role("Primary"),
            onPrimary = role("OnPrimary"),
            primaryVariant = role("PrimaryContainer"),
            onPrimaryVariant = role("OnPrimaryContainer"),
            error = role("Error"),
            onError = role("OnError"),
            errorContainer = role("ErrorContainer"),
            onErrorContainer = role("OnErrorContainer"),
            disabledPrimary = role("Primary").copy(alpha = .38f),
            disabledOnPrimary = role("OnPrimary").copy(alpha = .38f),
            disabledPrimaryButton = role("SurfaceContainerHighest"),
            disabledOnPrimaryButton = role("OnBackground").copy(alpha = .38f),
            disabledPrimarySlider = role("Primary").copy(alpha = .38f),
            primaryContainer = role("PrimaryContainer"),
            onPrimaryContainer = role("OnPrimaryContainer"),
            secondary = role("Secondary"),
            onSecondary = role("OnSecondary"),
            secondaryVariant = role("SecondaryContainer"),
            onSecondaryVariant = role("OnSecondaryContainer"),
            disabledSecondary = role("SurfaceContainerHighest"),
            disabledOnSecondary = role("OnSurfaceVariant").copy(alpha = .38f),
            disabledSecondaryVariant = role("SurfaceContainerHighest"),
            disabledOnSecondaryVariant = role("OnSurfaceVariant").copy(alpha = .38f),
            secondaryContainer = role("SecondaryContainer"),
            onSecondaryContainer = role("OnSecondaryContainer"),
            secondaryContainerVariant = role("SurfaceContainerHigh"),
            onSecondaryContainerVariant = role("OnSurfaceVariant"),
            tertiaryContainer = role("TertiaryContainer"),
            onTertiaryContainer = role("OnTertiaryContainer"),
            tertiaryContainerVariant = role("SurfaceContainerHigh"),
            background = role("Background"),
            onBackground = role("OnBackground"),
            onBackgroundVariant = Color(captionArgb),
            surface = role("Surface"),
            onSurface = role("OnSurface"),
            surfaceVariant = role("SurfaceVariant"),
            onSurfaceSecondary = role("OnSurfaceVariant"),
            onSurfaceVariantSummary = role("OnSurfaceVariant"),
            onSurfaceVariantActions = role("OnSurfaceVariant"),
            disabledOnSurface = role("OnSurface").copy(alpha = .38f),
            surfaceContainer = role("SurfaceContainer"),
            onSurfaceContainer = role("OnSurface"),
            onSurfaceContainerVariant = role("OnSurfaceVariant"),
            surfaceContainerHigh = role("SurfaceContainerHigh"),
            onSurfaceContainerHigh = role("OnSurface"),
            surfaceContainerHighest = role("SurfaceContainerHighest"),
            onSurfaceContainerHighest = role("OnSurface"),
            outline = role("Outline"),
            dividerLine = role("OutlineVariant"),
            // 宿主 Scrim 是实色，底部面板沿用原有 32% 遮罩透明度。
            windowDimming = role("Scrim").copy(alpha = .32f),
            sliderKeyPoint = role("SecondaryContainer"),
            sliderKeyPointForeground = role("OnSecondaryContainer"),
            sliderBackground = role("SecondaryContainer"),
        )
    }

    @Synchronized fun install(loader: ClassLoader) {
        if (installed) return
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            val r = Reader(loader)
            reader = r
            for (name in listOf("backgroundAuto", "backgroundDim", "backgroundBright")) {
                handles += HookRuntime.hookMethod(r.ui.method(name), object : HookCallback() {
                    override fun after(param: Call) {
                        if (resolving.get() || param.throwable != null) return
                        acceptNativeScheme(param.args.firstOrNull() ?: return)
                    }
                })
            }
            installed = true
            com.reamicro.fix.reload.ModuleResources.cleanup {
                reader = null; captured = null; installed = false; warned = false; resolving.remove()
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            reader = null
            throw error
        }
    }
    private fun acceptNativeScheme(scheme: Any) {
        if (resolving.get()) return
        val r = reader ?: return
        resolving.set(true)
        try {
            if (!r.schemeType.isInstance(scheme)) return
            val next = r.read(scheme, "host/current-theme")
            if (next != captured) captured = next
        } catch (e: Throwable) { warn(e) }
        finally { resolving.set(false) }
    }

    fun readerSnapshot(scheme: Any): Snapshot? {
        val r = reader ?: return null
        if (!r.schemeType.isInstance(scheme) || resolving.get()) return null
        resolving.set(true)
        return try { r.read(scheme, "host/reader-theme") }
        catch (error: Exception) { warn(error); null }
        finally { resolving.set(false) }
    }

    fun snapshot(context: Context, darkHint: Boolean): Snapshot {
        captured?.let { return it }
        return try {
            val r = reader ?: synchronized(this) {
                reader ?: Reader(hostLoader(context)).also { reader = it }
            }
            resolving.set(true)
            try { r.defaultSnapshot(darkHint) } finally { resolving.set(false) }
        } catch (e: Throwable) {
            warn(e)
            unavailable(darkHint)
        }
    }

    private fun hostLoader(context: Context): ClassLoader {
        var current = context
        repeat(12) {
            if (current is Activity) return current.classLoader
            val next = (current as? ContextWrapper)?.baseContext ?: return current.classLoader
            if (next === current) return current.classLoader
            current = next
        }
        return current.classLoader
    }

    private fun warn(error: Throwable) {
        if (warned) return
        warned = true
        HookRuntime.log("ReaMicro native colour query unavailable: ${error.message}")
    }

    private class Reader(loader: ClassLoader) {
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        init { require(ui.loader === loader) }
        val schemeType: Class<*> = ui.method("colorScheme").returnType
        private val roleAccessors = roleNames.associateWith { role ->
            ui.field("color.${role.replaceFirstChar(Char::lowercaseChar)}")
        }
        private var lightDefault: Snapshot? = null
        private var darkDefault: Snapshot? = null
        private var previousPacked: Map<String, Long>? = null
        private var previousCurrent: Snapshot? = null

        private fun argb(packed: Long): Int = ui.call("colorToArgb", packed) as Int

        fun defaultSnapshot(dark: Boolean): Snapshot {
            if (dark) darkDefault?.let { return it } else lightDefault?.let { return it }
            val native = (0..1).map { ui.value("defaultScheme$it") }.single { candidate ->
                !isLightSurface(argb(ui.field("color.surface").getLong(candidate))) == dark
            }
            return read(native, if (dark) "host/default-dark" else "host/default-light").also {
                if (dark) darkDefault = it else lightDefault = it
            }
        }

        fun read(scheme: Any, source: String): Snapshot {
            val packed = roleAccessors.mapValues { (_, accessor) -> accessor.getLong(scheme) }
            if (source == "host/current-theme" && packed == previousPacked) {
                previousCurrent?.let { return it }
            }
            fun helper(name: String) = argb(ui.call(name, scheme) as Long)
            val surfaceArgb = argb(packed.getValue("Surface"))
            val value = Snapshot(
                roles = packed.mapValues { argb(it.value) },
                pageArgb = helper("backgroundAuto"),
                contentArgb = helper("backgroundDim"),
                brightArgb = helper("backgroundBright"),
                borderArgb = argb(packed.getValue("SurfaceContainerHigh")),
                borderVariantArgb = helper("borderVariant"),
                captionArgb = helper("onBackgroundVariant"),
                dark = !isLightSurface(surfaceArgb),
                source = source,
            )
            if (source == "host/current-theme") { previousPacked = packed; previousCurrent = value }
            return value
        }

        private fun isLightSurface(argb: Int): Boolean {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92
                else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            val luminance = linear((argb ushr 16) and 0xff) * 0.2126 +
                linear((argb ushr 8) and 0xff) * 0.7152 +
                linear(argb and 0xff) * 0.0722
            return luminance > 0.179
        }
    }

    private fun unavailable(dark: Boolean): Snapshot {
        val bg = if (dark) 0xff000000.toInt() else 0xffffffff.toInt()
        val fg = if (dark) 0xffffffff.toInt() else 0xff000000.toInt()
        val roles = roleNames.associateWith { if (it.startsWith("On") || it in listOf("Primary", "Secondary", "Tertiary", "Error")) fg else bg }
        val caption = (fg and 0x00ffffff) or 0x99000000.toInt()
        return Snapshot(roles, bg, bg, bg, caption, caption, caption, dark, "unavailable/neutral")
    }
}
