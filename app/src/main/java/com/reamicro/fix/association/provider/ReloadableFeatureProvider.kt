package com.reamicro.fix.association.provider

/** 可重载源必须使用 ModuleResources 管理线程，并在页面销毁后释放宿主回调。 */
interface ReloadableFeatureProvider : ExternalFeatureProvider {
    fun canHotReload(): Boolean
    fun releaseForHotReload()
}
