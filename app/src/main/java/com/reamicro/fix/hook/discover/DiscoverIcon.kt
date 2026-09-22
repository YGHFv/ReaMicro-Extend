package com.reamicro.fix.hook.discover

import de.robv.android.xposed.XposedBridge

/**
 * 「发现」入口图标（罗盘）。
 *
 * 宿主 `arch/icons/colored/` 下 28 个图标（Alipay/Aliyun/AndroidOs/Baidu/Book/Battery×11/
 * Donate/FileAzw3|Epub|Folder|Mobi|Txt/Like/QQ/Star/Wechat/WechatPay/Yun115）没有罗盘一类，
 * 因此按宿主 `BookKt.getBook` 的同一套构建流程自建：24dp 画布、1024 viewport、分层绘制。
 * 反射构造的细节（10 参 synthetic 构造器、`addPath` 的 `$mask` 位置等）已抽到
 * [DiscoverVectorBuilder]，本类只负责几何与配色。
 *
 * ## 为什么不是「实心双色圆盘」（第一版被用户判定「图标根本看不清」）
 *
 * 第一版是四层同心实心盘：深绿外盘 + 浅绿内盘 + 深绿指针 + 浅绿轴点。
 * 问题出在**两个绿几乎同色**——`0xFF1DCE75` 与 `0xFF05C46D` 的亮度差不到 3%，
 * 缩到 24dp 后四层糊成一颗纯色圆点，指针/轴点完全看不出形状（截图实测：
 * 整个图标区域只有一片连续绿色，没有任何内部轮廓）。
 *
 * 现在改为宿主彩色图标一贯的**描边 + 留白**结构：绿环（深绿盘叠白盘抠出）+ 实心指针 + 白色轴点。
 * 全是「单子路径圆 / 多边形」——与第一版一样是已验证能渲染的图元，只换配色与几何，
 * 不引入多子路径 pathData 这类未经宿主验证的写法。
 *
 * 白 + 深绿的对比度远高于双绿，24dp 下仍能一眼认出「罗盘」。
 *
 * 本图标走 `ImageKt.Image` 渲染（色由下面两层写死，不依赖 tint），因此深浅色主题下都可见。
 * 构造只做一次并缓存；任何一步失败都返回 null，让调用方静默降级，绝不把异常抛进宿主渲染栈。
 */
internal class DiscoverIcon(private val classLoader: ClassLoader) {

    @Volatile
    private var cached: Any? = null

    /** 返回宿主的 `ImageVector`，失败时返回 null。 */
    fun imageVectorOrNull(): Any? {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return@synchronized it }
            runCatching {
                DiscoverVectorBuilder.build(classLoader, ICON_NAME, LAYERS, ICON_SIZE_DP)
                    ?: error("ImageVector.build returned null")
            }.onFailure {
                XposedBridge.logAlways("$LOG_PREFIX failed to build discover icon: ${it.stackTraceToString()}")
            }.getOrNull()?.also { cached = it }
        }
    }

    private companion object {
        const val LOG_PREFIX = "[ReaMicro]"

        const val ICON_NAME = "Colored.Discover"
        const val ICON_SIZE_DP = 24

        /** 与宿主 colored 图标一致的深绿；白用于内盘与轴点（双绿对比度不足，见类注释）。 */
        const val DEEP = 0xFF1DCE75L
        const val WHITE = 0xFFFFFFFFL

        /**
         * 四层路径，按「深绿外盘 → 白色内盘 → 深绿指针 → 白色轴点」叠放（颜色, SVG path）。
         *
         * - 环：外盘 r = 410 盖满，再用白盘 r = 320 抠出内腔，环宽 90 单位（24dp 下约 2.1dp）；
         * - 指针：右上 (710,314) 到左下 (314,710) 的实心菱形，两侧腰点 (590,590)/(434,434)；
         *   针尖到圆心 `√(198² + 198²) ≈ 280`，小于内腔内径 320，针不顶穿圆环；
         * - 轴点：圆心处 r = 72 的白圆，给实心指针一个「转轴」交代，也让小尺寸下更像罗盘。
         */
        val LAYERS = listOf(
            DEEP to "M512,102 A410,410 0 1,1 512,922 A410,410 0 1,1 512,102 Z",
            WHITE to "M512,192 A320,320 0 1,1 512,832 A320,320 0 1,1 512,192 Z",
            DEEP to "M710,314 L590,590 L314,710 L434,434 Z",
            WHITE to "M512,440 A72,72 0 1,1 512,584 A72,72 0 1,1 512,440 Z",
        )
    }
}
