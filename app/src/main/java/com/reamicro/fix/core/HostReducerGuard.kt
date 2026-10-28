package com.reamicro.fix.core

/** 状态 reducer 失败时只能保留原状态，不能把 Unit 或错误记录提交给宿主。 */
object HostReducerGuard {
    fun reduce(state: Any, expected: Class<*>, onFailure: (Throwable) -> Unit, block: () -> Any?): Any {
        require(expected.isInstance(state)) { "Unexpected host reducer input: ${state.javaClass.name}" }
        return runCatching {
            val result = block()
            require(expected.isInstance(result)) { "Unexpected host reducer output: ${result?.javaClass?.name}" }
            result!!
        }.getOrElse {
            onFailure(it)
            state
        }
    }
}
