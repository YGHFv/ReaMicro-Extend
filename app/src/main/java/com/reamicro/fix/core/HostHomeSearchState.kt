package com.reamicro.fix.core

internal object HostHomeSearchState {
    fun cloudResults(state: Any): Map<*, *>? = HostHomeSearchBindings.requireCurrent().cloudResults(state)
    fun copyCloudResults(state: Any, results: Map<Any?, Any?>?): Any =
        HostHomeSearchBindings.requireCurrent().copyCloudResults(state, results)
}
