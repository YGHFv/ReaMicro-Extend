package com.reamicro.fix.hook

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** Capture values, not host Compose objects: module and host runtimes stay isolated. */
internal object StructureHostStyle {
    private var values by mutableStateOf<Map<String, Int>>(emptyMap())
    @Volatile private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val theme = loader.loadClass("androidx.compose.material3.MaterialThemeKt")
            val colorClass = loader.loadClass("androidx.compose.ui.graphics.ColorKt")
            val toArgb = colorClass.declaredMethods.first {
                it.name.startsWith("toArgb") && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
            }
            val roles = listOf("Primary", "OnPrimary", "Secondary", "Tertiary", "Background", "Surface",
                "OnSurface", "OnSurfaceVariant", "SurfaceContainer", "SurfaceContainerLow",
                "SurfaceContainerHighest", "OutlineVariant")
            val schemeType = loader.loadClass("androidx.compose.material3.ColorScheme")
            val getters = roles.associateWith { role ->
                schemeType.methods.first { it.name.startsWith("get$role-") && it.parameterTypes.isEmpty() }
            }
            theme.declaredMethods.filter {
                it.name == "MaterialTheme" && it.parameterTypes.firstOrNull()?.name == "androidx.compose.material3.ColorScheme"
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val scheme = param.args?.firstOrNull() ?: return
                        runCatching {
                            val snapshot = roles.associateWith { role ->
                                val packed = getters.getValue(role).invoke(scheme) as Long
                                toArgb.invoke(null, packed) as Int
                            }
                            if (snapshot != values) values = snapshot
                        }
                    }
                })
                installed = true
            }
        }.onFailure { XposedBridge.log("ReaMicro structure host palette unavailable: ${it.message}") }
    }

    fun apply(base: ColorScheme): ColorScheme {
        val source = values
        fun color(role: String, fallback: Color) = source[role]?.let(::Color) ?: fallback
        return base.copy(
            primary = color("Primary", base.primary), onPrimary = color("OnPrimary", base.onPrimary),
            secondary = color("Secondary", base.secondary), tertiary = color("Tertiary", base.tertiary),
            background = color("Background", base.background), surface = color("Surface", base.surface),
            onSurface = color("OnSurface", base.onSurface), onSurfaceVariant = color("OnSurfaceVariant", base.onSurfaceVariant),
            surfaceContainer = color("SurfaceContainer", base.surfaceContainer),
            surfaceContainerLow = color("SurfaceContainerLow", base.surfaceContainerLow),
            surfaceContainerHighest = color("SurfaceContainerHighest", base.surfaceContainerHighest),
            outlineVariant = color("OutlineVariant", base.outlineVariant),
        )
    }
}
