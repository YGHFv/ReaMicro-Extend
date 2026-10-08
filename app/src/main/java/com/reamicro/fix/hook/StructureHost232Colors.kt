package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

internal object StructureHost232Colors {
    private const val THEME = "app.zhendong.reamicro.arch.theme.ThemeKt"
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
    private val helperNames = listOf(
        "getBackgroundAuto", "getBackgroundDim", "getBackgroundBright",
        "getBorderDark", "getBorderVariant", "getOnBackgroundVariant",
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
        fun controlsScheme(): ColorScheme = ColorScheme(
            primary = role("Primary"),
            onPrimary = role("OnPrimary"),
            primaryContainer = role("PrimaryContainer"),
            onPrimaryContainer = role("OnPrimaryContainer"),
            inversePrimary = role("InversePrimary"),
            secondary = role("Secondary"),
            onSecondary = role("OnSecondary"),
            secondaryContainer = role("SecondaryContainer"),
            onSecondaryContainer = role("OnSecondaryContainer"),
            tertiary = role("Tertiary"),
            onTertiary = role("OnTertiary"),
            tertiaryContainer = role("TertiaryContainer"),
            onTertiaryContainer = role("OnTertiaryContainer"),
            background = role("Background"),
            onBackground = role("OnBackground"),
            surface = role("Surface"),
            onSurface = role("OnSurface"),
            surfaceVariant = role("SurfaceVariant"),
            onSurfaceVariant = role("OnSurfaceVariant"),
            surfaceTint = role("SurfaceTint"),
            inverseSurface = role("InverseSurface"),
            inverseOnSurface = role("InverseOnSurface"),
            error = role("Error"),
            onError = role("OnError"),
            errorContainer = role("ErrorContainer"),
            onErrorContainer = role("OnErrorContainer"),
            outline = role("Outline"),
            outlineVariant = role("OutlineVariant"),
            scrim = role("Scrim"),
            surfaceBright = role("SurfaceBright"),
            surfaceDim = role("SurfaceDim"),
            surfaceContainer = role("SurfaceContainer"),
            surfaceContainerHigh = role("SurfaceContainerHigh"),
            surfaceContainerHighest = role("SurfaceContainerHighest"),
            surfaceContainerLow = role("SurfaceContainerLow"),
            surfaceContainerLowest = role("SurfaceContainerLowest"),
            primaryFixed = role("PrimaryFixed"),
            primaryFixedDim = role("PrimaryFixedDim"),
            onPrimaryFixed = role("OnPrimaryFixed"),
            onPrimaryFixedVariant = role("OnPrimaryFixedVariant"),
            secondaryFixed = role("SecondaryFixed"),
            secondaryFixedDim = role("SecondaryFixedDim"),
            onSecondaryFixed = role("OnSecondaryFixed"),
            onSecondaryFixedVariant = role("OnSecondaryFixedVariant"),
            tertiaryFixed = role("TertiaryFixed"),
            tertiaryFixedDim = role("TertiaryFixedDim"),
            onTertiaryFixed = role("OnTertiaryFixed"),
            onTertiaryFixedVariant = role("OnTertiaryFixedVariant"),
        )
    }

    @Synchronized fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val r = reader ?: Reader(loader).also { reader = it }

            for (name in listOf("getBackgroundAuto", "getBackgroundDim", "getBackgroundBright")) {
                XposedBridge.hookMethod(r.helpers.getValue(name), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (resolving.get()) return
                        val scheme = param.args?.firstOrNull() ?: return
                        acceptNativeScheme(scheme)
                    }
                })
            }
            installed = true
        }.onFailure(::warn)
    }

    private fun acceptNativeScheme(scheme: Any) {
        if (resolving.get()) return
        val r = reader ?: return
        resolving.set(true)
        try {
            if (!r.schemeType.isInstance(scheme)) return
            val next = r.read(scheme, "host232/current-theme")
            if (next != captured) captured = next
        } catch (e: Throwable) { warn(e) }
        finally { resolving.set(false) }
    }

    fun readerSnapshot(scheme: Any): Snapshot? {
        val r = reader ?: return null
        if (!r.schemeType.isInstance(scheme) || resolving.get()) return null
        resolving.set(true)
        return try { r.read(scheme, "host232/reader-theme") }
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
        XposedBridge.log("ReaMicro structure native 2.3.2 colour mapping unavailable: ${error.message}")
    }

    private class Reader(loader: ClassLoader) {
        private val theme = loader.loadClass(THEME)
        val schemeType: Class<*> = loader.loadClass("androidx.compose.material3.ColorScheme")
        val helpers = helperNames.associateWith { theme.getDeclaredMethod(it, schemeType).apply { isAccessible = true } }
        private val getters = roleNames.associateWith { role ->
            schemeType.methods.first { it.name.startsWith("get$role-") && it.parameterTypes.isEmpty() }
        }
        private val toArgb = loader.loadClass("androidx.compose.ui.graphics.ColorKt").declaredMethods.first {
            it.name.startsWith("toArgb") && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
        }.apply { isAccessible = true }
        private val isLight = loader.loadClass("app.zhendong.reamicro.arch.extensions.ColorExtKt").declaredMethods.first {
            it.name.startsWith("isLight-") && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
        }.apply { isAccessible = true }
        private val originalLight = theme.getDeclaredMethod("getOriginalLightColorScheme").apply { isAccessible = true }
        private val originalDark = theme.getDeclaredField("darkScheme").apply { isAccessible = true }
        private var lightDefault: Snapshot? = null
        private var darkDefault: Snapshot? = null
        private var previousPacked: Map<String, Long>? = null
        private var previousCurrent: Snapshot? = null

        private fun argb(packed: Long): Int = toArgb.invoke(null, packed) as Int

        fun defaultSnapshot(dark: Boolean): Snapshot {
            if (dark) darkDefault?.let { return it } else lightDefault?.let { return it }
            val native = if (dark) originalDark.get(null) else originalLight.invoke(null)
            return read(requireNotNull(native), if (dark) "host232/default-dark" else "host232/default-light").also {
                if (dark) darkDefault = it else lightDefault = it
            }
        }

        fun read(scheme: Any, source: String): Snapshot {
            val packed = getters.mapValues { (_, method) -> method.invoke(scheme) as Long }
            if (source == "host232/current-theme" && packed == previousPacked) {
                previousCurrent?.let { return it }
            }
            fun helper(name: String) = argb(helpers.getValue(name).invoke(null, scheme) as Long)
            val value = Snapshot(
                roles = packed.mapValues { argb(it.value) },
                pageArgb = helper("getBackgroundAuto"),
                contentArgb = helper("getBackgroundDim"),
                brightArgb = helper("getBackgroundBright"),
                borderArgb = helper("getBorderDark"),
                borderVariantArgb = helper("getBorderVariant"),
                captionArgb = helper("getOnBackgroundVariant"),
                dark = !(isLight.invoke(null, packed.getValue("Surface")) as Boolean),
                source = source,
            )
            if (source == "host232/current-theme") { previousPacked = packed; previousCurrent = value }
            return value
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
