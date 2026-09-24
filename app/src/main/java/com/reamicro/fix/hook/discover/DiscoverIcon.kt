package com.reamicro.fix.hook.discover

import com.reamicro.fix.xposed.XposedBridge

/**
 * 「发现」入口图标（罗盘）。
 *
 * 宿主 `arch/icons/colored/` 下 28 个图标（Alipay/Aliyun/AndroidOs/Baidu/Book/Battery×11/
 * Donate/FileAzw3|Epub|Folder|Mobi|Txt/Like/QQ/Star/Wechat/WechatPay/Yun115）没有罗盘一类，
 * 因此按宿主 `BookKt.getBook` 的同一套构建流程自建：24dp 画布、1024 viewport、分层绘制。
 * 反射构造的细节（10 参 synthetic 构造器、`addPath` 的 `$mask` 位置等）已抽到
 * [DiscoverVectorBuilder]，本类只负责几何与配色。
 *
 * ## 为什么内腔与轴孔必须是透明的（白色版被用户判定「深色主题下白得刺眼」）
 *
 * 第二版用**白色实心内盘**（r=320）模拟「留白」：浅色主题下白盘与页面背景几乎同色，
 * 看起来是镂空的；但深色主题下这层白是实打实画上去的——24dp 的图标中央一片白，
 * 与宿主其它彩色图标的透明底格格不入（用户实测截图确认）。
 *
 * 现在改用 even-odd 填充把「白色」全部换成**真透明**：
 *
 * - 环：外盘 r=410 与内盘 r=320 放进**同一个 path 的两段子路径**，even-odd 规则下
 *   内圈自动抠空——这是本项目验证过的唯一多子路径用法（单层、纯圆、无自交）；
 * - 指针：实心菱形，针尖微微搭进环带（r≈330 > 内缘 320），不悬空；中心开一个 r=72 的
 *   **透明轴孔**（同层 even-odd 抠空），替代原来的白色轴点。
 *
 * 全图只剩深绿一种颜色（`0xFF1DCE75`），浅深色主题下都落在宿主彩色图标的明度区间内。
 * 本图标走 `ImageKt.Image` 渲染（色由下面两层写死，不依赖 tint）。
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
                DiscoverVectorBuilder.build(classLoader, ICON_NAME, LAYERS, ICON_SIZE_DP, evenOddFill = true)
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

        /** 与宿主 colored 图标一致的深绿。白色已全部移除——空腔/轴孔改由 even-odd 抠透明。 */
        const val DEEP = 0xFF1DCE75L

        /**
         * 两层路径，按「环 → 指针」叠放（颜色, SVG pathData），构建时统一走 even-odd 填充。
         *
         * - 环（第一层，两段子路径）：外盘 r=410 盖满 + 内盘 r=320 抠空，环宽 90 单位
         *   （24dp 下约 2.1dp）；内腔与轴孔透出的是页面背景本身；
         * - 指针（第二层，两段子路径）：菱形长轴沿右上 (745,279) 到左下 (279,745)，
         *   针尖距圆心 `233·√2 ≈ 330`，越过内缘 320 一点点搭进环带；两侧腰点
         *   (601,601)/(423,423)（短半轴 ≈ 126）；中心 r=72 的圆子路径被 even-odd 抠成
         *   透明轴孔，给指针一个「转轴」交代，也让小尺寸下更像罗盘。
         */
        val LAYERS = listOf(
            DEEP to "M512,102 A410,410 0 1,1 512,922 A410,410 0 1,1 512,102 Z " +
                "M512,192 A320,320 0 1,1 512,832 A320,320 0 1,1 512,192 Z",
            DEEP to "M745,279 L601,601 L279,745 L423,423 Z " +
                "M512,440 A72,72 0 1,1 512,584 A72,72 0 1,1 512,440 Z",
        )
    }
}
