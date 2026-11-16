package com.reamicro.fix.hook

/** 设置页只发起用户操作，网络和账号校验留在当前同步实例内。 */
internal object ModuleConfigSyncActions {
    @Volatile var request: ((Boolean) -> Unit)? = null
}
