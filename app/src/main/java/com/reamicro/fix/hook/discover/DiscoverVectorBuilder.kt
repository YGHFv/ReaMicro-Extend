package com.reamicro.fix.hook.discover

import com.reamicro.fix.core.HostGraphicsBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.core.hostMembers
import com.reamicro.fix.xposed.HookRuntime

internal object DiscoverVectorBuilder {
    const val DEFAULT_ICON_SIZE_DP = 24

    fun build(
        classLoader: ClassLoader,
        name: String,
        layers: List<Pair<Long, String>>,
        sizeDp: Int = DEFAULT_ICON_SIZE_DP,
        evenOddFill: Boolean = false,
        viewport: Float = 1024f,
    ): Any? = runCatching {
        val graphics = requireNotNull(HostGraphicsBindings.current) { "Native vector contract unavailable" }
        check(hostMembers().namedClass(graphics.type("vector").name, classLoader) === graphics.type("vector"))
        val ui = requireNotNull(HostUiBindings.current) { "Native UI contract unavailable" }
        val size = (ui.call("dpInt", sizeDp) as Number).toFloat()
        graphics.build(name, layers, size, viewport, evenOddFill)
    }.onFailure {
        HookRuntime.logAlways("[ReaMicro] failed to build icon $name: ${it.stackTraceToString()}")
    }.getOrNull()
}

internal object DiscoverUiIcons {

    private val lock = Any()

    @Volatile
    private var loadedClassLoader: ClassLoader? = null

    @Volatile
    private var chevronDownValue: Any? = null

    @Volatile
    private var layoutListValue: Any? = null

    @Volatile
    private var layoutGridValue: Any? = null

    @Volatile
    private var configuration130Value: Any? = null

    fun chevronDown(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return chevronDownValue
    }

    fun layoutList(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return layoutListValue
    }

    fun layoutGrid(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return layoutGridValue
    }

    fun configuration130(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return configuration130Value
    }

    private fun ensure(classLoader: ClassLoader) {
        if (loadedClassLoader === classLoader) return
        synchronized(lock) {
            if (loadedClassLoader === classLoader) return
            chevronDownValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.ChevronDown", CHEVRON_DOWN)
            layoutListValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.LayoutList", DiscoverLayoutArtwork.list,
                viewport = DiscoverLayoutArtwork.viewport)
            layoutGridValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.LayoutGrid", DiscoverLayoutArtwork.grid,
                viewport = DiscoverLayoutArtwork.viewport)
            configuration130Value = DiscoverVectorBuilder.build(classLoader, "ReaMicro.MoreHorizontal130", CONFIGURATION_130, viewport = 24f)
            if (chevronDownValue != null && layoutListValue != null && layoutGridValue != null && configuration130Value != null) {
                loadedClassLoader = classLoader
            }
        }
    }

    private const val WHITE = 0xFFFFFFFFL

    private val CHEVRON_DOWN = listOf(
        WHITE to "M320,404 L704,404 L512,660 Z",
    )

    private val CONFIGURATION_130 = listOf(
        WHITE to "M12,12 m-2,0 a2,2 0 1,1 4,0 a2,2 0 1,1 -4,0",
        WHITE to "M19,12 m-2,0 a2,2 0 1,1 4,0 a2,2 0 1,1 -4,0",
        WHITE to "M5,12 m-2,0 a2,2 0 1,1 4,0 a2,2 0 1,1 -4,0",
    )
}
