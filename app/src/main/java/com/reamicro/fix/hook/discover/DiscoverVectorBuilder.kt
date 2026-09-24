package com.reamicro.fix.hook.discover

import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 宿主 `ImageVector` 的通用构造器。
 *
 * 从 [DiscoverIcon] 里原样抽取出来的那段构建流程——模块不编译 Compose，只能用反射拼出宿主的
 * 矢量图标，而这套「11 步反射 + 三个必须对上的隐藏参数」到处都要用，抽出来避免每个图标抄一遍。
 *
 * ## 三处必须与宿主真实签名一致（均以 MT 反编译核对，[DiscoverIcon] 的类注释有完整推导）
 *
 * 1. 构造器是 **10 参 synthetic**：
 *    `(String;FFFFJIZILDefaultConstructorMarker;)V` ——
 *    `name, w, h, vw, vh, tintColor:J, tintBlendMode:I, autoMirror:Z, $mask:I, marker`；
 *    宿主传 `$mask = 0xe0`（三个可选参取默认值）。
 * 2. `addPath-oIyEayM$default` 的 **`$mask` 在倒数第二位**，第二位是 `pathData`。
 *    放错位置会报 `argument 2 has type java.util.List, got java.lang.Integer`。
 * 3. `$mask` 取 `0x3800`，与宿主一致。
 *
 * ## 描边参数一律走宿主自己的默认值
 *
 * `pathFillType` / `strokeLineCap` / `strokeLineJoin` 三个默认值不自己写死，全部从宿主
 * `VectorKt` 的 `getDefaultXxx()` 取，保证与宿主图标是同一套描边语义。
 *
 * ## 每个图层一个子路径
 *
 * 宿主 `addPathNodes` 能吃多子路径的 pathData，但本项目只验证过「单 M 子路径」的图元
 * （见 [DiscoverIcon] 注释里那条自我约束）。需要多个形状时按图层叠加，而不是拼一个长 path。
 * 例外：`evenOddFill = true` 时允许**同层**内外两圈子路径——even-odd 填充会自动抠出
 * 透明空腔（[DiscoverIcon] 的罗盘环与轴孔就是这么做的），这是唯一验证过的多子路径用法。
 */
internal object DiscoverVectorBuilder {

    /** 反射成员按 ClassLoader 缓存：宿主 ClassLoader 在一次进程里是同一个。 */
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

    /** 三个默认值枚举：fillType / strokeLineCap / strokeLineJoin。 */
    @Volatile
    private var pathDefaults: IntArray? = null

    /**
     * 用一份「颜色 + 单子路径」的图层列表拼出宿主的 `ImageVector`。
     *
     * 任意一步失败都返回 null 并记一条日志，让调用方静默降级——图标是锦上添花的东西，
     * 绝不能把异常抛进宿主的 Compose 渲染栈（那是直接崩进程）。
     *
     * @param layers 按**叠放顺序**排列的图层，每项是 `色彩 ARGB(Long) to SVG pathData`。
     *   色彩只是兜底：用 `Icon` 渲染时会被 tint 覆盖；用 `Image` 渲染时才会显色。
     */
    fun build(
        classLoader: ClassLoader,
        name: String,
        layers: List<Pair<Long, String>>,
        sizeDp: Int = DEFAULT_ICON_SIZE_DP,
        evenOddFill: Boolean = false,
    ): Any? = runCatching {
        val members = resolve(classLoader)
        val size = udp(classLoader, sizeDp)
        // evenOddFill：同层多子路径抠空腔用（外圈 + 内圈反向叠出透明内腔）。
        // PathFillType 是 inline class，JVM 侧就是 Int：NonZero=0、EvenOdd=1。
        val fillType = if (evenOddFill) PATH_FILL_TYPE_EVEN_ODD else members.defaults[0]
        val builder = members.ctor.newInstance(
            name, size, size, VIEWPORT, VIEWPORT,
            0L, 0, false, BUILDER_CTOR_MASK, null,
        )
        layers.forEach { (argb, path) ->
            val nodes = members.addPathNodes.invoke(null, path)
                ?: error("addPathNodes returned null for $path")
            val brush = members.solidColorCtor.newInstance(members.colorOf.invoke(null, argb), null)
            members.addPathDefault.invoke(
                null,
                builder,               // 1 $this
                nodes,                 // 2 pathData
                fillType,              // 3 pathFillType
                "",                    // 4 name
                brush,                 // 5 fill
                1f,                    // 6 fillAlpha
                null,                  // 7 stroke（只填充）
                1f,                    // 8 strokeAlpha
                STROKE_LINE_WIDTH,     // 9 strokeLineWidth
                members.defaults[1],   // 10 strokeLineCap
                members.defaults[2],   // 11 strokeLineJoin
                0f,                    // 12 trimPathStart
                1f,                    // 13 trimPathEnd
                0f,                    // 14 trimPathOffset
                0,                     // 15 tintBlendMode
                ADD_PATH_MASK,         // 16 $mask（倒数第二）
                null,                  // 17 marker
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

            // Color 是 inline class，JVM 侧 Color(J)J。
            val resolvedColor = findClass(classLoader, COLOR_KT_CLASS).declaredMethods.firstOrNull {
                it.name == COLOR_METHOD && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }?.apply { isAccessible = true } ?: error("$COLOR_KT_CLASS.$COLOR_METHOD(J) not found")

            // SolidColor(J, DefaultConstructorMarker)。
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

    /** 与宿主 colored 图标一致的线宽；纯填充图元其实用不到，但保持与宿主同参。 */
    private const val STROKE_LINE_WIDTH = 4f

    /** `ImageVector.Builder` 10 参 synthetic 构造器的 `$mask`：三个可选参取默认值。 */
    private const val BUILDER_CTOR_MASK = 0xe0

    /** `addPath-oIyEayM$default` 的 `$mask`。注意它在**倒数第二位**，不是第二位。 */
    private const val ADD_PATH_MASK = 0x3800

    /** `PathFillType.EvenOdd` 的 Int 值（NonZero=0）；even-odd 下同层内外两圈自动抠出透明空腔。 */
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

/**
 * 发现页要用到的三个界面图标：下拉三角、列表、网格。
 *
 * 全是「24dp 画布 + 1024 viewport + 纯填充单子路径」，与宿主 colored 图标同族；
 * 填充色统一给白，实际显色交给 `Icon` 的 tint（宿主 `IconKt.Icon` 会用
 * `ColorFilter.tint` 覆盖矢量自身的颜色），因此跟随主题的深浅色自动适配。
 *
 * 三个图标都只在第一次组合时构建，之后常驻。
 */
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
    private var settingsGearValue: Any? = null

    /** 标签行尾部的展开箭头（▾）。 */
    fun chevronDown(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return chevronDownValue
    }

    /** 「列表」布局图标：左侧三个方块 + 右侧三条横线。 */
    fun layoutList(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return layoutListValue
    }

    /** 「网格」布局图标：2×2 四个方块。 */
    fun layoutGrid(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return layoutGridValue
    }

    /** 「配置」齿轮图标：实心圆 + 八颗辐射齿（发现页顶栏）。 */
    fun settingsGear(classLoader: ClassLoader): Any? {
        ensure(classLoader)
        return settingsGearValue
    }

    private fun ensure(classLoader: ClassLoader) {
        if (loadedClassLoader === classLoader) return
        synchronized(lock) {
            if (loadedClassLoader === classLoader) return
            chevronDownValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.ChevronDown", CHEVRON_DOWN)
            layoutListValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.LayoutList", LAYOUT_LIST)
            layoutGridValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.LayoutGrid", LAYOUT_GRID)
            settingsGearValue = DiscoverVectorBuilder.build(classLoader, "ReaMicro.SettingsGear", SETTINGS_GEAR)
            loadedClassLoader = classLoader
        }
    }

    /** 白色：会被 `Icon` 的 tint 覆盖，仅在直接 `Image` 渲染时才显色。 */
    private const val WHITE = 0xFFFFFFFFL

    /**
     * 实心倒三角：顶边 y=400 横跨 x 320..704，尖端 (512, 664)。
     *
     * 顶端留 400 的空白而不是贴 0，是因为这一族图标与宿主的 24dp 图标放在同一行时，
     * 视觉重量（墨迹占比）要接近，满幅三角会显得过大。
     */
    private val CHEVRON_DOWN = listOf(
        WHITE to "M320,404 L704,404 L512,660 Z",
    )

    /**
     * 列表图标：把画布竖分三行，每行「左侧小块 + 右侧长条」。
     *
     * 三行的中线落在 215 / 512 / 809（1024 均分），小块 130×130、长条 474×100，
     * 小块与长条之间留 80 的缝——目的是在 24dp 下仍能看出「缩略图 + 文本行」的结构，
     * 而不是一坨实心块。
     */
    private val LAYOUT_LIST = listOf(
        WHITE to "M170,150 L300,150 L300,280 L170,280 Z",
        WHITE to "M380,165 L854,165 L854,265 L380,265 Z",
        WHITE to "M170,447 L300,447 L300,577 L170,577 Z",
        WHITE to "M380,462 L854,462 L854,562 L380,562 Z",
        WHITE to "M170,744 L300,744 L300,874 L170,874 Z",
        WHITE to "M380,759 L854,759 L854,859 L380,859 Z",
    )

    /** 网格图标：2×2 四个等大方块，块 340、缝 44、外沿 150。 */
    private val LAYOUT_GRID = listOf(
        WHITE to "M150,150 L490,150 L490,490 L150,490 Z",
        WHITE to "M534,150 L874,150 L874,490 L534,490 Z",
        WHITE to "M150,534 L490,534 L490,874 L150,874 Z",
        WHITE to "M534,534 L874,534 L874,874 L534,874 Z",
    )

    /**
     * 齿轮图标：中心实心圆（r=260）+ 八颗 45° 均布的粗短齿（r 240→365，半宽 80）。
     *
     * 不做镂空内孔——`Icon` 的 tint 会把所有图层刷成同一颜色，白色抠环也会被盖住。
     * 齿必须**短而粗**：第一版是 r=210 细盘 + 细长齿（180→340），渲染出来是「米字形」
     * 星芒而不是齿轮；加大圆盘、把齿改成盘边的凸起后才读得出「齿轮」。齿全部是单子路径
     * 多边形，与本族其它图标同规则。
     */
    private val SETTINGS_GEAR = listOf(
        WHITE to "M512,252 A260,260 0 1,1 512,772 A260,260 0 1,1 512,252 Z",
        WHITE to "M752,432 L752,592 L877,592 L877,432 Z",
        WHITE to "M625.1,738.3 L738.3,625.1 L826.7,713.5 L713.5,826.7 Z",
        WHITE to "M432,752 L592,752 L592,877 L432,877 Z",
        WHITE to "M285.7,625.1 L398.9,738.3 L310.5,826.7 L197.3,713.5 Z",
        WHITE to "M272,432 L272,592 L147,592 L147,432 Z",
        WHITE to "M398.9,285.7 L285.7,398.9 L197.3,310.5 L310.5,197.3 Z",
        WHITE to "M432,272 L592,272 L592,147 L432,147 Z",
        WHITE to "M738.3,398.9 L625.1,285.7 L713.5,197.3 L826.7,310.5 Z",
    )
}
