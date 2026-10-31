package com.reamicro.fix.reload

/** 模块自带编辑器/Compose 的调度线程暂无卸载协议，不能把宿主 Compose 的销毁等同于它们已退场。 */
internal object ModuleUiReloadGuard {
    private var activated = false

    fun markUsed() {
        if (activated) return
        activated = true
        ModuleResources.blockWhile {
            "本次进程已使用模块编辑器或独立 Compose 面板，其协程运行时暂不支持安全卸载"
        }
    }
}
