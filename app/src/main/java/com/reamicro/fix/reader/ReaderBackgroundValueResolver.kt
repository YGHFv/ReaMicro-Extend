package com.reamicro.fix.reader

/**
 * 阅读页背景取值的判定。
 *
 * 从 ReaderBackgroundHook 的 getBackground() 钩子里抽出来的纯逻辑：给定宿主本次返回的值、
 * 之前记住的宿主原值、以及当前主题选中的侧载背景，算出该交给宿主什么。
 *
 * 抽出来是因为这里有三条互相影响的分支，而它原先埋在 hook 里只能靠真机翻主题去试——
 * 一帧的闪烁连截图都抓不到。
 */
internal object ReaderBackgroundValueResolver {

    /**
     * @param hostValue 应当被记住的宿主原值；令牌帧不会污染它。
     * @param result 交给宿主的最终值；null 表示不改宿主的返回值。
     */
    data class Resolution(
        val hostValue: String,
        val result: String?,
    )

    /**
     * @param rawValue 宿主这次真正返回的值，可能是模块写入的刷新令牌。
     * @param rememberedHostValue 上一次记下的宿主原值。
     * @param selectedUri 当前主题选中的侧载背景，未选中为空。
     * @param refreshTokenPrefix 模块刷新令牌的前缀。
     * @param isSideLoaded 判断某个地址是否为模块侧载的背景图。
     */
    fun resolve(
        rawValue: String,
        rememberedHostValue: String,
        selectedUri: String,
        refreshTokenPrefix: String,
        isSideLoaded: (String) -> Boolean,
    ): Resolution {
        val isRefreshToken = rawValue.startsWith(refreshTokenPrefix)
        // 令牌只是用来触发失效的内部信号，不能当成宿主原值记下来，
        // 否则下次"恢复原值"会把令牌永久写回宿主。
        val hostValue = if (isRefreshToken) rememberedHostValue else rawValue
        val result = when {
            // 本主题选了侧载背景，直接用。
            selectedUri.isNotBlank() -> selectedUri
            // 令牌帧：还原成宿主原值，避免宿主拿着加载不了的地址画出没有背景的一帧。
            isRefreshToken -> hostValue
            // 宿主 BACKGROUND 是全局单值；本主题没选侧载时，不能继承另一主题的图片。
            isSideLoaded(hostValue) -> ""
            // 宿主自己的背景，原样放行。
            else -> null
        }
        return Resolution(hostValue = hostValue, result = result)
    }
}
