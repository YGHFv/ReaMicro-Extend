package com.reamicro.fix.core

/** Navigation always operates on the current host's own route stack. */
object HostNavigation {
    private val host get() = requireNotNull(HostRuntimeBindings.navigation) { "Native navigation contract unavailable" }
    @JvmStatic fun navigate(loader: ClassLoader, scope: Any, route: Any) {
        val bound = host
        require(bound.scope.isInstance(scope))
        bound.navigate.invoke(null, scope, route)
    }
    @JvmStatic fun pop(scope: Any) {
        val bound = host
        require(bound.scope.isInstance(scope))
        bound.pop.invoke(scope)
    }
    @JvmStatic fun controller(scope: Any): Any {
        val bound = host
        require(bound.scope.isInstance(scope))
        return requireNotNull(bound.controller.get(scope))
    }
    fun scopeClass(loader: ClassLoader): Class<*> = host.scope
    fun navigateMethod(loader: ClassLoader): java.lang.reflect.Method = host.navigate
    fun controllerPopMethod(loader: ClassLoader): java.lang.reflect.Method = host.controllerPop
}
