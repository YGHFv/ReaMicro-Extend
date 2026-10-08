package com.reamicro.fix.hook.discover

import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method

internal object DiscoverVectorBuilder {

    @Volatile
    private var cachedClassLoader: ClassLoader? = null

    @Volatile
    private var ctor: Constructor<*>? = null

    @Volatile
    private var addPathDefault: Method? = null

    @Volatile
    private var buildMethod: Method? = null

    @Volatile
    private var colorMethod: Method? = null

    @Volatile
    private var solidColorCtor: Constructor<*>? = null

    @Volatile
    private var addPathNodesMethod: Method? = null

    @Volatile
    private var pathDefaults: IntArray? = null

    fun build(
        classLoader: ClassLoader,
        name: String,
        layers: List<Pair<Long, String>>,
        sizeDp: Int = DEFAULT_ICON_SIZE_DP,
        evenOddFill: Boolean = false,
        viewport: Float = VIEWPORT,
    ): Any? = runCatching {
        val members = resolve(classLoader)
        val size = udp(classLoader, sizeDp)

        val fillType = if (evenOddFill) PATH_FILL_TYPE_EVEN_ODD else members.defaults[0]
        val builder = members.ctor.newInstance(
            name, size, size, viewport, viewport,
            0L, 0, false, BUILDER_CTOR_MASK, null,
        )
        layers.forEach { (argb, path) ->
            val nodes = members.addPathNodes.invoke(null, path)
                ?: error("addPathNodes returned null for $path")
            val brush = members.solidColorCtor.newInstance(members.colorOf.invoke(null, argb), null)
            members.addPathDefault.invoke(
                null,
                builder,
                nodes,
                fillType,
                "",
                brush,
                1f,
                null,
                1f,
                STROKE_LINE_WIDTH,
                members.defaults[1],
                members.defaults[2],
                0f,
                1f,
                0f,
                0,
                ADD_PATH_MASK,
                null,
            )
        }
        members.build.invoke(builder) ?: error("ImageVector.build returned null")
    }.onFailure {
        XposedBridge.logAlways("$LOG_PREFIX failed to build icon $name: ${it.stackTraceToString()}")
    }.getOrNull()

    private class Members(
        val ctor: Constructor<*>,
        val addPathDefault: Method,
        val build: Method,
        val colorOf: Method,
        val solidColorCtor: Constructor<*>,
        val addPathNodes: Method,
        val defaults: IntArray,
    )

    private fun resolve(classLoader: ClassLoader): Members {
        cachedClassLoader?.let { cached ->
            if (cached === classLoader) {
                return Members(
                    ctor!!, addPathDefault!!, buildMethod!!, colorMethod!!,
                    solidColorCtor!!, addPathNodesMethod!!, pathDefaults!!,
                )
            }
        }
        synchronized(this) {
            cachedClassLoader?.let { cached ->
                if (cached === classLoader) {
                    return Members(
                        ctor!!, addPathDefault!!, buildMethod!!, colorMethod!!,
                        solidColorCtor!!, addPathNodesMethod!!, pathDefaults!!,
                    )
                }
            }
            val builderClass = findClass(classLoader, BUILDER_CLASS)
            val vectorKtClass = findClass(classLoader, VECTOR_KT_CLASS)

            val resolvedCtor = builderClass.declaredConstructors.firstOrNull {
                val t = it.parameterTypes
                t.size == BUILDER_CTOR_PARAMETER_COUNT &&
                    t[0] == String::class.java &&
                    t.sliceArray(1..4).all { p -> p == Float::class.javaPrimitiveType } &&
                    t[5] == Long::class.javaPrimitiveType &&
                    t[6] == Int::class.javaPrimitiveType &&
                    t[7] == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: error("$BUILDER_CLASS 10-arg ctor not found")

            val resolvedAddPath = builderClass.declaredMethods.firstOrNull {
                it.name == ADD_PATH_DEFAULT_METHOD &&
                    it.parameterTypes.size == ADD_PATH_DEFAULT_PARAMETER_COUNT
            }?.apply { isAccessible = true } ?: error("$BUILDER_CLASS.$ADD_PATH_DEFAULT_METHOD not found")

            val resolvedBuild = builderClass.declaredMethods.first { it.name == BUILD_METHOD }
                .apply { isAccessible = true }

            val resolvedColor = findClass(classLoader, COLOR_KT_CLASS).declaredMethods.firstOrNull {
                it.name == COLOR_METHOD && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }?.apply { isAccessible = true } ?: error("$COLOR_KT_CLASS.$COLOR_METHOD(J) not found")

            val resolvedBrush = findClass(classLoader, SOLID_COLOR_CLASS).declaredConstructors.firstOrNull {
                it.parameterTypes.size == SOLID_COLOR_CTOR_PARAMETER_COUNT &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: error("$SOLID_COLOR_CLASS(J, marker) not found")

            val resolvedDefaults = listOf(FILL_TYPE, STROKE_CAP, STROKE_JOIN).map { name ->
                vectorKtClass.declaredMethods.first { it.name == name }
                    .apply { isAccessible = true }
                    .invoke(null) as Int
            }.toIntArray()

            val resolvedAddPathNodes = vectorKtClass.declaredMethods.firstOrNull {
                it.name == ADD_PATH_NODES_METHOD && it.parameterTypes.size == 1
            }?.apply { isAccessible = true } ?: error("$VECTOR_KT_CLASS.$ADD_PATH_NODES_METHOD not found")

            ctor = resolvedCtor
            addPathDefault = resolvedAddPath
            buildMethod = resolvedBuild
            colorMethod = resolvedColor
            solidColorCtor = resolvedBrush
            addPathNodesMethod = resolvedAddPathNodes
            pathDefaults = resolvedDefaults
            cachedClassLoader = classLoader

            return Members(
                resolvedCtor, resolvedAddPath, resolvedBuild, resolvedColor,
                resolvedBrush, resolvedAddPathNodes, resolvedDefaults,
            )
        }
    }

    private fun udp(classLoader: ClassLoader, value: Int): Float =
        findClass(classLoader, UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun findClass(classLoader: ClassLoader, name: String): Class<*> =
        XposedHelpers.findClass(name, classLoader)

    const val DEFAULT_ICON_SIZE_DP = 24

    private const val LOG_PREFIX = "[ReaMicro]"

    private const val VIEWPORT = 1024f

    private const val STROKE_LINE_WIDTH = 4f

    private const val BUILDER_CTOR_MASK = 0xe0

    private const val ADD_PATH_MASK = 0x3800

    private const val PATH_FILL_TYPE_EVEN_ODD = 1

    private const val BUILDER_CTOR_PARAMETER_COUNT = 10
    private const val ADD_PATH_DEFAULT_PARAMETER_COUNT = 17
    private const val SOLID_COLOR_CTOR_PARAMETER_COUNT = 2

    private const val BUILDER_CLASS = "androidx.compose.ui.graphics.vector.ImageVector\$Builder"
    private const val VECTOR_KT_CLASS = "androidx.compose.ui.graphics.vector.VectorKt"
    private const val SOLID_COLOR_CLASS = "androidx.compose.ui.graphics.SolidColor"
    private const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
    private const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"

    private const val ADD_PATH_DEFAULT_METHOD = "addPath-oIyEayM\$default"
    private const val ADD_PATH_NODES_METHOD = "addPathNodes"
    private const val BUILD_METHOD = "build"
    private const val COLOR_METHOD = "Color"
    private const val UDP_METHOD = "getUdp"
    private const val FILL_TYPE = "getDefaultFillType"
    private const val STROKE_CAP = "getDefaultStrokeLineCap"
    private const val STROKE_JOIN = "getDefaultStrokeLineJoin"
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
            loadedClassLoader = classLoader
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
