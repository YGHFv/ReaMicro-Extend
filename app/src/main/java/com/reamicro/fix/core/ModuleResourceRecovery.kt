package com.reamicro.fix.core

class ModuleResourcesUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

object ModuleResourceRecovery {
    const val RESTART_MESSAGE = "模块已更新或资源不可用，请彻底关闭并重启阅微后重试"

    fun run(action: () -> Unit, onUnavailable: (ModuleResourcesUnavailableException) -> Unit) {
        try { action() } catch (error: ModuleResourcesUnavailableException) { onUnavailable(error) }
    }
}
